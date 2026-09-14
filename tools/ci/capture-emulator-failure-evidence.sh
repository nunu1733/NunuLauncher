#!/usr/bin/env bash

# Capture bounded, failure-time evidence from an Android emulator without
# masking the test failure that caused this script to run.
#
# Usage:
#   capture-emulator-failure-evidence.sh [serial] [output-directory]
#
# The caller runs this only from a failed emulator job. The services queried
# here are the same ones suspected of stalling during a natural failure
# (Issue #304), so every adb/dumpsys call is a bounded observation: a hard
# per-command timeout, an overall wall-budget deadline, and job-log plus
# artifact records for timeouts and skips. The script always exits 0 and
# leaves whatever partial files it produced in an uploadable state.
#
# Tunables (env):
#   CAPTURE_COMMAND_TIMEOUT_SECONDS  hard timeout per command (default 15)
#   CAPTURE_TOTAL_BUDGET_SECONDS     wall budget for the whole script (default 150)
#   CAPTURE_MAX_BYTES_PER_FILE       stored output cap per artifact file (default 2 MiB)
#   CAPTURE_LOGCAT_LINES             recent logcat lines to keep per buffer (default 5000)

set -u

SERIAL="${1:-emulator-5554}"
OUTPUT_DIR="${2:-build/failure-time-evidence}"
ADB_BIN="${ADB_BIN:-adb}"
COMMAND_TIMEOUT="${CAPTURE_COMMAND_TIMEOUT_SECONDS:-15}"
TOTAL_BUDGET="${CAPTURE_TOTAL_BUDGET_SECONDS:-150}"
MAX_BYTES="${CAPTURE_MAX_BYTES_PER_FILE:-2097152}"
LOGCAT_LINES="${CAPTURE_LOGCAT_LINES:-5000}"

mkdir -p "$OUTPUT_DIR"

# coreutils timeout exists on the CI runners; fall back to a watchdog for
# local development (e.g. macOS without gtimeout) so both paths stay bounded.
TIMEOUT_BIN="$(command -v timeout || true)"

START_TS="$(date +%s)"
DEADLINE=$((START_TS + TOTAL_BUDGET))
MANIFEST="$OUTPUT_DIR/capture-manifest.tsv"
printf 'name\tcommand_exit_status\telapsed_seconds\ttimed_out\toutcome\n' >"$MANIFEST"

TOTAL_CAPTURED=0
TOTAL_TIMED_OUT=0
TOTAL_SKIPPED=0

write_header() {
    local name="$1"
    shift
    {
        printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'serial=%s\n' "$SERIAL"
        printf 'command='
        printf ' %q' "$@"
        printf '\n\n'
    } >"$OUTPUT_DIR/$name.txt"
}

# run_with_watchdog <limit-seconds> <output-file> <command...>
# Kills the command KILL-style after the limit and signals the timeout by
# setting the global WATCHDOG_TIMED_OUT flag instead of guessing from status.
WATCHDOG_TIMED_OUT=false
run_with_watchdog() {
    local limit="$1" output_file="$2"
    shift 2
    local flag="${TMPDIR:-/tmp}/capture-timeout-flag.$$"
    rm -f "$flag"
    "$@" >"$output_file" 2>&1 &
    local cmd_pid=$!
    (
        sleep "$limit"
        if kill -0 "$cmd_pid" 2>/dev/null; then
            touch "$flag"
            kill -9 "$cmd_pid" 2>/dev/null
        fi
    ) &
    local watchdog_pid=$!
    CAPTURE_STATUS=0
    wait "$cmd_pid" || CAPTURE_STATUS=$?
    kill "$watchdog_pid" 2>/dev/null
    wait "$watchdog_pid" 2>/dev/null
    WATCHDOG_TIMED_OUT=false
    if [ -e "$flag" ]; then
        WATCHDOG_TIMED_OUT=true
    fi
    rm -f "$flag"
}

# append_capped_output <temp-file> <artifact-file>
append_capped_output() {
    local tmp_file="$1" artifact_file="$2"
    local bytes
    bytes="$(wc -c <"$tmp_file" | tr -d '[:space:]')"
    if [ "${bytes:-0}" -gt "$MAX_BYTES" ]; then
        head -c "$MAX_BYTES" "$tmp_file" >>"$artifact_file"
        printf '\n[output truncated: kept %s of %s bytes]\n' "$MAX_BYTES" "$bytes" >>"$artifact_file"
    elif [ "${bytes:-0}" -gt 0 ]; then
        cat "$tmp_file" >>"$artifact_file"
    fi
}

record_skip() {
    local name="$1"
    shift
    write_header "$name" "$@"
    {
        printf 'timed_out=false\n'
        printf 'skipped_budget_exhausted=true\n'
        printf '\n[capture skipped: overall budget of %ss exhausted before this command]\n' "$TOTAL_BUDGET"
    } >>"$OUTPUT_DIR/$name.txt"
    printf 'capture skip: name=%s budget_exhausted=true\n' "$name"
    printf '%s\t-\t0\tfalse\tskipped\n' "$name" >>"$MANIFEST"
    TOTAL_SKIPPED=$((TOTAL_SKIPPED + 1))
}

# run_bounded <name> <command...> executes one capture with the hard timeout
# and overall budget applied, records evidence, and never fails the script.
run_bounded() {
    local name="$1"
    shift
    local now remaining limit
    now="$(date +%s)"
    remaining=$((DEADLINE - now))
    if [ "$remaining" -le 0 ]; then
        record_skip "$name" "$@"
        return 0
    fi
    limit="$COMMAND_TIMEOUT"
    if [ "$limit" -gt "$remaining" ]; then
        limit="$remaining"
    fi

    printf 'capture start: %s (timeout=%ss)\n' "$name" "$limit"
    write_header "$name" "$@"
    local tmp_file
    tmp_file="$(mktemp)"
    local start elapsed timed_out=false
    start="$(date +%s)"
    if [ -n "$TIMEOUT_BIN" ]; then
        CAPTURE_STATUS=0
        "$TIMEOUT_BIN" -k 2 "$limit" "$@" >"$tmp_file" 2>&1 || CAPTURE_STATUS=$?
        # 124 = TERM after timeout, 137 = KILL after --kill-after escalation.
        if [ "$CAPTURE_STATUS" -eq 124 ] || [ "$CAPTURE_STATUS" -eq 137 ]; then
            timed_out=true
        fi
    else
        run_with_watchdog "$limit" "$tmp_file" "$@"
        if [ "$WATCHDOG_TIMED_OUT" = true ]; then
            timed_out=true
        fi
    fi
    elapsed=$(( $(date +%s) - start ))

    {
        printf 'timeout_seconds=%s\n' "$limit"
        printf 'elapsed_seconds=%s\n' "$elapsed"
        printf 'timed_out=%s\n' "$timed_out"
        printf 'command_exit_status=%s\n' "$CAPTURE_STATUS"
        printf '\n'
    } >>"$OUTPUT_DIR/$name.txt"
    append_capped_output "$tmp_file" "$OUTPUT_DIR/$name.txt"
    if [ "$timed_out" = true ] && [ ! -s "$tmp_file" ]; then
        printf '[command produced no output before being killed at %ss]\n' "$limit" >>"$OUTPUT_DIR/$name.txt"
    fi
    rm -f "$tmp_file"

    local outcome=captured
    if [ "$timed_out" = true ]; then
        outcome=timed_out
        TOTAL_TIMED_OUT=$((TOTAL_TIMED_OUT + 1))
    fi
    printf 'capture end: name=%s status=%s elapsed=%s timed_out=%s\n' \
        "$name" "$CAPTURE_STATUS" "$elapsed" "$timed_out"
    printf '%s\t%s\t%s\t%s\t%s\n' \
        "$name" "$CAPTURE_STATUS" "$elapsed" "$timed_out" "$outcome" >>"$MANIFEST"
    TOTAL_CAPTURED=$((TOTAL_CAPTURED + 1))
    return 0
}

capture_serial() {
    run_bounded "$1" "$ADB_BIN" -s "$SERIAL" "${@:2}"
}

capture_global() {
    run_bounded "$1" "$ADB_BIN" "${@:2}"
}

capture_global adb-version version
capture_global adb-devices devices -l

# Device identity and resource/display pressure.
capture_serial device-properties shell getprop
capture_serial device-pressure shell sh -c 'cat /proc/pressure/cpu; printf "\n"; cat /proc/pressure/io'
capture_serial power-state shell dumpsys power

# The focus holder, z-order, resumed activity, and HOME resolver are the
# minimum evidence needed to distinguish H1/H1' from H2/H2'.
capture_serial window-windows shell dumpsys window windows
capture_serial window-displays shell dumpsys window displays
capture_serial activity-top shell dumpsys activity top
capture_serial activity-activities shell dumpsys activity activities
capture_serial home-role shell cmd role get-role-holders android.app.role.HOME
capture_serial home-resolve shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME

# ANR/dropbox evidence is retained even when direct /data/anr access is denied
# to the shell user. dumpsys dropbox commonly includes the waited thread and
# CPU/Binder stack that are needed to compare a natural CI recurrence to the
# local H2 observation.
capture_serial system-app-anr shell dumpsys dropbox --print system_app_anr
capture_serial data-app-anr shell dumpsys dropbox --print data_app_anr
capture_serial system-server-wtf shell dumpsys dropbox --print system_server_wtf
capture_serial anr-directory shell sh -c 'ls -la /data/anr; cat /data/anr/traces.txt'

# Keep logcat bounded to recent lines in the buffers relevant to
# framework/window/ANR timing.
capture_serial logcat-main-system-crash-events logcat -d -v threadtime -t "$LOGCAT_LINES" \
    -b main -b system -b crash -b events

# These services provide the display/input side of the suspected stall.
capture_serial input-state shell dumpsys input
capture_serial surfaceflinger-state shell dumpsys SurfaceFlinger

{
    printf 'Failure-time emulator evidence\n'
    printf 'serial=%s\n' "$SERIAL"
    printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'github_run_id=%s\n' "${GITHUB_RUN_ID:-unavailable}"
    printf 'github_job=%s\n' "${GITHUB_JOB:-unavailable}"
    printf 'github_sha=%s\n' "${GITHUB_SHA:-unavailable}"
    printf 'command_timeout_seconds=%s\n' "$COMMAND_TIMEOUT"
    printf 'total_budget_seconds=%s\n' "$TOTAL_BUDGET"
    printf 'max_bytes_per_file=%s\n' "$MAX_BYTES"
    printf 'logcat_lines_per_buffer=%s\n' "$LOGCAT_LINES"
    printf 'captures_attempted=%s timed_out=%s skipped=%s\n' \
        "$((TOTAL_CAPTURED + TOTAL_SKIPPED))" "$TOTAL_TIMED_OUT" "$TOTAL_SKIPPED"
    printf 'elapsed_seconds=%s\n' "$(( $(date +%s) - START_TS ))"
    printf '\nFiles are best-effort snapshots; command failures, timeouts, and budget skips\n'
    printf 'are recorded per file and in capture-manifest.tsv.\n'
} >"$OUTPUT_DIR/README.txt"

printf 'capture summary: captured=%s timed_out=%s skipped=%s elapsed=%ss\n' \
    "$TOTAL_CAPTURED" "$TOTAL_TIMED_OUT" "$TOTAL_SKIPPED" "$(( $(date +%s) - START_TS ))"

exit 0
