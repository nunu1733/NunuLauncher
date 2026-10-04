#!/usr/bin/env bash

# Smoke-test the failure evidence helper without requiring an emulator. The
# fake adb records the requested subcommands and returns representative output.
# Beyond the best-effort contract (one failing command), these scenarios pin
# the Issue #315 bounded-completion regressions: a hung adb command must be
# cut off by the per-command timeout, a huge-output command must be cut off
# by the production-side byte cap, neither may stop later captures, repeated
# hangs must exhaust the wall budget without ever overrunning it, and the
# script must exit 0 leaving an uploadable partial artifact.

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
IFS='|' read -r -a big_patterns <<<"${BIG_PATTERNS:-}"
for pattern in ${big_patterns[@]+"${big_patterns[@]}"}; do
    [ -z "$pattern" ] && continue
    if [[ "$*" == *"$pattern"* ]]; then
        # Stream ~200 MB; the helper must cap it near MAX_BYTES.
        yes '0123456789abcdef0123456789abcdef' | head -c "${BIG_TOTAL_BYTES:-200000000}"
        exit 0
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
grep -q '^timed_out=false$' "$out1/window-windows.txt"
grep -q '^output_truncated=false$' "$out1/window-windows.txt"
grep -q 'dumpsys window windows' "$FAKE_ADB_LOG"
grep -q 'logcat -d' "$FAKE_ADB_LOG"
grep -Eq $'^adb-version\t[0-9]+\t[0-9]+\tfalse\tfalse\tcaptured$' "$out1/capture-manifest.tsv"

# Scenario 2: one hung command is cut off by the per-command timeout, is
# recorded as timed_out, and later captures still run. The wall oracle is
# proportional to the 3s setting so budget regressions fail the test.
FAKE_ADB_LOG="$TEMP_DIR/adb2.log"
export HANG_PATTERNS='dumpsys window windows'
export CAPTURE_COMMAND_TIMEOUT_SECONDS=3
out2="$TEMP_DIR/evidence2"
start=$SECONDS
bash "$CAPTURE" emulator-5554 "$out2"
elapsed=$((SECONDS - start))
unset HANG_PATTERNS CAPTURE_COMMAND_TIMEOUT_SECONDS
test "$elapsed" -le 20
grep -q '^timed_out=true$' "$out2/window-windows.txt"
grep -Eq $'^window-windows\t[0-9]+\t[0-9]+\ttrue\tfalse\ttimed_out$' "$out2/capture-manifest.tsv"
grep -q 'dumpsys activity top' "$FAKE_ADB_LOG"
grep -q 'dumpsys SurfaceFlinger' "$FAKE_ADB_LOG"
test -s "$out2/surfaceflinger-state.txt"
test -s "$out2/README.txt"
grep -q 'timed_out=1' "$out2/README.txt"

# Scenario 3: repeated hangs exhaust the overall budget; the script skips the
# rest, still exits 0, and leaves a partial but uploadable artifact. The wall
# oracle (15s for a 6s budget) must catch budget enforcement regressions.
FAKE_ADB_LOG="$TEMP_DIR/adb3.log"
export HANG_PATTERNS='shell dumpsys'
export CAPTURE_COMMAND_TIMEOUT_SECONDS=2 CAPTURE_TOTAL_BUDGET_SECONDS=6
out3="$TEMP_DIR/evidence3"
start=$SECONDS
bash "$CAPTURE" emulator-5554 "$out3"
elapsed3=$((SECONDS - start))
unset HANG_PATTERNS CAPTURE_COMMAND_TIMEOUT_SECONDS CAPTURE_TOTAL_BUDGET_SECONDS
test "$elapsed3" -le 15
grep -q 'skipped_budget_exhausted=true' "$out3"/*.txt
grep -Eq $'^surfaceflinger-state\t-\t0\tfalse\tfalse\tskipped$' "$out3/capture-manifest.tsv"
grep -Eq $'\ttrue\tfalse\ttimed_out$' "$out3/capture-manifest.tsv"
test -s "$out3/README.txt"
test -s "$out3/capture-manifest.tsv"

# Scenario 4: a command streaming ~200 MB is cut off by the production-side
# byte cap (Issue #315 review P2): the saved file stays near the cap, the
# evidence dir stays bounded, truncation is recorded, and later captures run.
FAKE_ADB_LOG="$TEMP_DIR/adb4.log"
export BIG_PATTERNS='dumpsys window windows'
export CAPTURE_MAX_BYTES_PER_FILE=1048576
out4="$TEMP_DIR/evidence4"
start=$SECONDS
bash "$CAPTURE" emulator-5554 "$out4"
elapsed4=$((SECONDS - start))
unset BIG_PATTERNS CAPTURE_MAX_BYTES_PER_FILE
test "$elapsed4" -le 20
# capped body + header + status lines + truncation marker; far below 200 MB.
bytes4=$(wc -c <"$out4/window-windows.txt" | tr -d '[:space:]')
test "$bytes4" -le 1051000
grep -q '^output_truncated=true$' "$out4/window-windows.txt"
grep -Eq $'^window-windows\t[0-9]+\t[0-9]+\tfalse\ttrue\tcaptured$' "$out4/capture-manifest.tsv"
dir4=$(du -sk "$out4" | cut -f1)
test "$dir4" -le 2048
grep -q 'dumpsys SurfaceFlinger' "$FAKE_ADB_LOG"
test -s "$out4/surfaceflinger-state.txt"
grep -q 'truncated=1' "$out4/README.txt"

printf 'capture-emulator-failure-evidence smoke test: PASS\n'
