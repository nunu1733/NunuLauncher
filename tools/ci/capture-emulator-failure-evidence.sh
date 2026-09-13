#!/usr/bin/env bash

# Capture bounded, failure-time evidence from an Android emulator without
# masking the test failure that caused this script to run.
#
# Usage:
#   capture-emulator-failure-evidence.sh [serial] [output-directory]
#
# The caller runs this only from a failed emulator job. Every command is
# best-effort so that a missing service or a stopped emulator is itself
# recorded in the artifact instead of replacing the original test failure.

set -u

SERIAL="${1:-emulator-5554}"
OUTPUT_DIR="${2:-build/failure-time-evidence}"
ADB_BIN="${ADB_BIN:-adb}"

mkdir -p "$OUTPUT_DIR"

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

capture_serial() {
    local name="$1"
    shift
    write_header "$name" "$ADB_BIN" -s "$SERIAL" "$@"
    "$ADB_BIN" -s "$SERIAL" "$@" >>"$OUTPUT_DIR/$name.txt" 2>&1 || {
        local status=$?
        printf '\ncommand_exit_status=%s\n' "$status" >>"$OUTPUT_DIR/$name.txt"
    }
}

capture_global() {
    local name="$1"
    shift
    write_header "$name" "$ADB_BIN" "$@"
    "$ADB_BIN" "$@" >>"$OUTPUT_DIR/$name.txt" 2>&1 || {
        local status=$?
        printf '\ncommand_exit_status=%s\n' "$status" >>"$OUTPUT_DIR/$name.txt"
    }
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

# Keep logcat limited to the buffers relevant to framework/window/ANR timing.
capture_serial logcat-main-system-crash-events logcat -d -v threadtime \
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
    printf '\nFiles are best-effort snapshots; command failures are recorded in the corresponding file.\n'
} >"$OUTPUT_DIR/README.txt"

exit 0
