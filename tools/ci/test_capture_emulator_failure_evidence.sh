#!/usr/bin/env bash

# Smoke-test the failure evidence helper without requiring an emulator. The
# fake adb records the requested subcommands and returns representative output.
# Beyond the best-effort contract (one failing command), these scenarios pin
# the Issue #315 bounded-completion regression: a hung adb command must be cut
# off by the per-command timeout, recorded as timed_out, must not stop later
# captures, and the script must finish and leave an uploadable partial artifact.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CAPTURE="$ROOT_DIR/tools/ci/capture-emulator-failure-evidence.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

make_fake_adb() {
    cat >"$1" <<'FAKE_ADB'
#!/usr/bin/env bash
set -u
printf 'fake-adb %s\n' "$*" >>"${FAKE_ADB_LOG:?}"
IFS='|' read -r -a hang_patterns <<<"${HANG_PATTERNS:-}"
for pattern in ${hang_patterns[@]+"${hang_patterns[@]}"}; do
    [ -z "$pattern" ] && continue
    if [[ "$*" == *"$pattern"* ]]; then
        printf 'fake-adb hanging on: %s\n' "$*"
        sleep "${HANG_SECONDS:-120}"
        exit 9
    fi
done
if [[ "$*" == *"system_app_anr"* ]]; then
    printf 'fake ANR trace\n'
    exit 7
fi
printf 'fake adb output\n'
FAKE_ADB
    chmod +x "$1"
}

FAKE_ADB="$TEMP_DIR/adb"
make_fake_adb "$FAKE_ADB"
export ADB_BIN="$FAKE_ADB"

# Scenario 1: healthy responses, one non-zero exit (existing #313 contract).
FAKE_ADB_LOG="$TEMP_DIR/adb1.log"
export FAKE_ADB_LOG
out1="$TEMP_DIR/evidence1"
bash "$CAPTURE" emulator-5554 "$out1"
test -s "$out1/README.txt"
test -s "$out1/window-windows.txt"
test -s "$out1/home-role.txt"
test -s "$out1/system-app-anr.txt"
grep -q 'fake ANR trace' "$out1/system-app-anr.txt"
grep -q 'command_exit_status=7' "$out1/system-app-anr.txt"
grep -q 'timed_out=false' "$out1/window-windows.txt"
grep -q 'dumpsys window windows' "$FAKE_ADB_LOG"
grep -q 'logcat -d' "$FAKE_ADB_LOG"
grep -Eq $'^adb-version\t[0-9]+\t[0-9]+\tfalse\tcaptured$' "$out1/capture-manifest.tsv"

# Scenario 2: one hung command is cut off, later captures still run.
FAKE_ADB_LOG="$TEMP_DIR/adb2.log"
export HANG_PATTERNS='dumpsys window windows'
export CAPTURE_COMMAND_TIMEOUT_SECONDS=3
out2="$TEMP_DIR/evidence2"
start=$SECONDS
bash "$CAPTURE" emulator-5554 "$out2"
elapsed=$((SECONDS - start))
unset HANG_PATTERNS CAPTURE_COMMAND_TIMEOUT_SECONDS
test "$elapsed" -lt 45
grep -q '^timed_out=true$' "$out2/window-windows.txt"
grep -Eq $'^window-windows\t[0-9]+\t[0-9]+\ttrue\ttimed_out$' "$out2/capture-manifest.tsv"
# Commands scheduled after the hang must still have been invoked.
grep -q 'dumpsys activity top' "$FAKE_ADB_LOG"
grep -q 'dumpsys SurfaceFlinger' "$FAKE_ADB_LOG"
test -s "$out2/surfaceflinger-state.txt"
test -s "$out2/README.txt"
grep -q 'timed_out=1' "$out2/README.txt"

# Scenario 3: repeated hangs exhaust the overall budget; the script skips the
# rest, still exits 0, and leaves a partial but uploadable artifact.
FAKE_ADB_LOG="$TEMP_DIR/adb3.log"
export HANG_PATTERNS='shell dumpsys'
export CAPTURE_COMMAND_TIMEOUT_SECONDS=2 CAPTURE_TOTAL_BUDGET_SECONDS=6
out3="$TEMP_DIR/evidence3"
start=$SECONDS
bash "$CAPTURE" emulator-5554 "$out3"
elapsed3=$((SECONDS - start))
unset HANG_PATTERNS CAPTURE_COMMAND_TIMEOUT_SECONDS CAPTURE_TOTAL_BUDGET_SECONDS
test "$elapsed3" -lt 40
grep -q 'skipped_budget_exhausted=true' "$out3"/*.txt
grep -Eq $'^surfaceflinger-state\t-\t0\tfalse\tskipped$' "$out3/capture-manifest.tsv"
grep -Eq $'\ttrue\ttimed_out$' "$out3/capture-manifest.tsv"
test -s "$out3/README.txt"
test -s "$out3/capture-manifest.tsv"

printf 'capture-emulator-failure-evidence smoke test: PASS\n'
