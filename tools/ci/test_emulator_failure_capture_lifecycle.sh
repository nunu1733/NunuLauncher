#!/usr/bin/env bash

# Verify that API 36 failure evidence is captured before
# android-emulator-runner tears down the emulator, without masking the test's
# exit status. The workflow wiring check makes the fake lifecycle exercise the
# same wrapper that both affected jobs invoke.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
WRAPPER_REL="tools/ci/run-emulator-command-with-failure-capture.sh"
WRAPPER="$ROOT_DIR/$WRAPPER_REL"
CAPTURE="$ROOT_DIR/tools/ci/capture-emulator-failure-evidence.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

python3 - "$WORKFLOW" "$WRAPPER_REL" <<'PY'
import re
import sys
from pathlib import Path

workflow = Path(sys.argv[1]).read_text()
wrapper = sys.argv[2]

for job, capture_name, artifact_name in (
    ("organizer-instrumentation-issue52-tests", "Capture Issue 52 failure-time emulator evidence", "issue52-failure-time-emulator-evidence"),
    ("organizer-instrumentation-issue53-tests", "Capture Issue 53 failure-time emulator evidence", "issue53-failure-time-emulator-evidence"),
):
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|^  # Required status)",
        workflow,
    )
    if match is None:
        raise SystemExit(f"FAIL: workflow job {job} was not found")
    block = match.group(1)
    if "uses: reactivecircus/android-emulator-runner@v2" not in block:
        raise SystemExit(f"FAIL: {job} no longer uses the expected emulator runner")
    script = re.search(r"(?ms)^          script:\s*[|>][-+]?\s*\n((?:^            .*\n|^            \n)+)", block)
    if script is None or wrapper not in script.group(1):
        raise SystemExit(
            f"FAIL: {job} does not invoke {wrapper} inside android-emulator-runner's script; "
            "the post-step capture runs after emulator teardown"
        )
    if capture_name in block:
        raise SystemExit(f"FAIL: {job} still captures emulator evidence after the runner step")
    if artifact_name not in block or "actions/upload-artifact@v6" not in block:
        raise SystemExit(f"FAIL: {job} must retain post-run artifact upload for captured evidence")

print("workflow lifecycle wiring: Issue 52 and Issue 53 capture inside live emulator script")
PY

mkdir -p "$TEMP_DIR/bin"
EVENT_LOG="$TEMP_DIR/lifecycle.log"
EMULATOR_ALIVE="$TEMP_DIR/emulator-alive"
FAKE_ADB="$TEMP_DIR/bin/adb"
FAKE_TEST="$TEMP_DIR/fake-test-command.sh"

cat >"$FAKE_ADB" <<'FAKE_ADB_SCRIPT'
#!/usr/bin/env bash
set -u
if [ ! -e "${EMULATOR_ALIVE:?}" ]; then
    printf 'adb:device-gone:%s\n' "$*" >>"${EVENT_LOG:?}"
    printf "adb: device 'emulator-5554' not found\n" >&2
    exit 1
fi
printf 'adb:live:%s\n' "$*" >>"${EVENT_LOG:?}"
if [ "${FAKE_ADB_FAIL:-false}" = true ]; then
    printf 'fake adb failure\n' >&2
    exit 1
fi
if [ "$*" = "devices -l" ]; then
    printf 'List of devices attached\nemulator-5554 device product:fake\n'
else
    printf 'fake live emulator output\n'
fi
FAKE_ADB_SCRIPT
chmod +x "$FAKE_ADB"

cat >"$FAKE_TEST" <<'FAKE_TEST_SCRIPT'
#!/usr/bin/env bash
set -u
printf 'test:exit=%s\n' "$FAKE_TEST_EXIT" >>"${EVENT_LOG:?}"
exit "$FAKE_TEST_EXIT"
FAKE_TEST_SCRIPT
chmod +x "$FAKE_TEST"

run_fake_runner() {
    local output_dir="$1"
    local fake_test_exit="$2"
    local fake_adb_fail="$3"
    touch "$EMULATOR_ALIVE"
    printf 'runner:emulator-start\n' >>"$EVENT_LOG"
    local command_status=0
    set +e
    EMULATOR_ALIVE="$EMULATOR_ALIVE" EVENT_LOG="$EVENT_LOG" FAKE_TEST_EXIT="$fake_test_exit" \
        FAKE_ADB_FAIL="$fake_adb_fail" ADB_BIN="$FAKE_ADB" \
        CAPTURE_COMMAND_TIMEOUT_SECONDS=1 CAPTURE_TOTAL_BUDGET_SECONDS=30 CAPTURE_MAX_BYTES_PER_FILE=4096 \
        bash "$WRAPPER" emulator-5554 "$output_dir" -- "$FAKE_TEST"
    command_status=$?
    set -e
    printf 'runner:emulator-kill\n' >>"$EVENT_LOG"
    rm -f "$EMULATOR_ALIVE"
    return "$command_status"
}

# Failure path: capture must run while the fake emulator is alive, and the
# runner must receive the exact original test status after the evidence call.
failure_output="$TEMP_DIR/failure-evidence"
if run_fake_runner "$failure_output" 23 false; then
    failure_status=0
else
    failure_status=$?
fi
test "$failure_status" -eq 23
test -s "$failure_output/capture-manifest.tsv"
grep -q '^adb:live:.*devices -l$' "$EVENT_LOG"
if grep -q '^adb:device-gone:' "$EVENT_LOG"; then
    printf 'FAIL: evidence capture attempted adb after emulator teardown\n' >&2
    exit 1
fi
kill_line="$(grep -n '^runner:emulator-kill$' "$EVENT_LOG" | head -n 1 | cut -d: -f1)"
last_capture_line="$(grep -n '^adb:live:' "$EVENT_LOG" | tail -n 1 | cut -d: -f1)"
test "$last_capture_line" -lt "$kill_line"

# Capture itself is best-effort: a failing adb must not replace the original
# test exit code.
: >"$EVENT_LOG"
failed_capture_output="$TEMP_DIR/failed-capture-evidence"
if run_fake_runner "$failed_capture_output" 37 true; then
    failed_capture_status=0
else
    failed_capture_status=$?
fi
test "$failed_capture_status" -eq 37
grep -q 'command_exit_status=1' "$failed_capture_output/window-windows.txt"
if grep -q '^adb:device-gone:' "$EVENT_LOG"; then
    printf 'FAIL: best-effort capture outlived the fake emulator\n' >&2
    exit 1
fi

# Success path must not trigger failure-time evidence collection.
: >"$EVENT_LOG"
success_output="$TEMP_DIR/success-evidence"
if run_fake_runner "$success_output" 0 false; then
    success_status=0
else
    success_status=$?
fi
test "$success_status" -eq 0
test ! -e "$success_output"
if grep -q '^adb:' "$EVENT_LOG"; then
    printf 'FAIL: successful test unexpectedly captured failure evidence\n' >&2
    exit 1
fi

printf 'emulator failure capture lifecycle test: PASS\n'
