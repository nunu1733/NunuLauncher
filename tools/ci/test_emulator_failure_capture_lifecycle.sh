#!/usr/bin/env bash

# Regression test for the emulator lifecycle boundary (Issues #422/#437/#438).
# The failure capture must run from inside android-emulator-runner's script
# while the device is alive, and it must never replace the command's original
# exit status. The workflow wiring check covers every organizer
# instrumentation lane: the three #437 lanes (manual organization via its
# helper, category override, onboarding proposal) and the seven #438 lanes
# (restore-capture and production-input route through per-lane helpers).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
WRAPPER_REL="tools/ci/run-emulator-command-with-failure-capture.sh"
MANUAL_HELPER_REL="tools/ci/run-manual-organization-ui-instrumentation.sh"
RESTORE_HELPER_REL="tools/ci/run-restore-capture-instrumentation.sh"
PRODUCTION_HELPER_REL="tools/ci/run-production-input-instrumentation.sh"
WRAPPER="$ROOT_DIR/$WRAPPER_REL"
MANUAL_HELPER="$ROOT_DIR/$MANUAL_HELPER_REL"
RESTORE_HELPER="$ROOT_DIR/$RESTORE_HELPER_REL"
PRODUCTION_HELPER="$ROOT_DIR/$PRODUCTION_HELPER_REL"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

python3 - "$WORKFLOW" "$WRAPPER_REL" "$MANUAL_HELPER_REL" "$RESTORE_HELPER_REL" "$PRODUCTION_HELPER_REL" <<'PY'
import re
import sys
from pathlib import Path

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
wrapper = sys.argv[2]
manual_helper = sys.argv[3]
restore_helper = sys.argv[4]
production_helper = sys.argv[5]

jobs = (
    ("organizer-instrumentation-manual-organization-ui-tests", manual_helper),
    ("organizer-instrumentation-category-override-tests", None),
    ("organizer-instrumentation-onboarding-proposal-tests", None),
    ("organizer-instrumentation-shared-writer-tests", None),
    ("organizer-instrumentation-db-migration-tests", None),
    ("organizer-instrumentation-restore-capture-tests", restore_helper),
    ("organizer-instrumentation-production-input-tests", production_helper),
    ("organizer-instrumentation-reservation-recovery-tests", None),
    ("organizer-instrumentation-exchange-import-ui-tests", None),
    ("organizer-instrumentation-method-choice-journey-tests", None),
)
for job, helper in jobs:
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        raise SystemExit(f"FAIL: workflow job {job} was not found")
    block = match.group(1)
    script = re.search(r"(?m)^          script:[ \t]*[|>][-+]?[ \t]*\n", block)
    if script is None:
        raise SystemExit(f"FAIL: {job} runner script input was not found")
    lines = []
    for line in block[script.end():].splitlines():
        if line.startswith("            "):
            if line.strip() and not line.strip().startswith("#"):
                lines.append(line.strip())
        elif line.strip():
            break
    if len(lines) != 1:
        raise SystemExit(
            f"FAIL: {job} runner script has {len(lines)} physical commands; "
            "android-emulator-runner executes each line independently"
        )
    command = lines[0]
    if wrapper not in command or " -- " not in command:
        raise SystemExit(f"FAIL: {job} does not invoke the live capture wrapper")
    if helper is not None and helper not in command:
        raise SystemExit(f"FAIL: {job} does not invoke its one-line helper")
    if re.search(r"Capture .*failure-time emulator evidence", block):
        raise SystemExit(f"FAIL: {job} still captures after emulator-runner teardown")
    # The capture script may only be reached through the live wrapper; a direct
    # reference in any non-comment line means a runner-external capture step
    # survives under a renamed step or a different command form.
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "capture-emulator-failure-evidence.sh" in stripped:
            raise SystemExit(
                f"FAIL: {job} references the runner-external capture script "
                "outside the live wrapper"
            )
    if "actions/upload-artifact@v6" not in block or "failure-time-emulator-evidence" not in block:
        raise SystemExit(f"FAIL: {job} lost its failure-time artifact upload")

print("workflow lifecycle wiring: PASS")
PY

test -x "$WRAPPER"
for helper in "$MANUAL_HELPER" "$RESTORE_HELPER" "$PRODUCTION_HELPER"; do
    test -x "$helper"
done

EVENT_LOG="$TEMP_DIR/events.log"
EMULATOR_ALIVE="$TEMP_DIR/emulator-alive"
FAKE_ADB="$TEMP_DIR/adb"
FAKE_TEST="$TEMP_DIR/test-command"

cat >"$FAKE_ADB" <<'FAKE_ADB'
#!/usr/bin/env bash
set -u
if [ ! -e "${EMULATOR_ALIVE:?}" ]; then
    printf 'adb:gone:%s\n' "$*" >>"${EVENT_LOG:?}"
    exit 1
fi
printf 'adb:live:%s\n' "$*" >>"${EVENT_LOG:?}"
if [ "${FAKE_ADB_FAIL:-false}" = true ]; then
    exit 1
fi
if [ "$*" = "devices -l" ]; then
    printf 'List of devices attached\nemulator-5554 device\n'
else
    printf 'fake live emulator output\n'
fi
FAKE_ADB
chmod +x "$FAKE_ADB"

cat >"$FAKE_TEST" <<'FAKE_TEST'
#!/usr/bin/env bash
printf 'test:status=%s\n' "$FAKE_TEST_STATUS" >>"${EVENT_LOG:?}"
if [ "${FAKE_TEST_REMOVE_EMULATOR:-false}" = true ]; then
    rm -f "${EMULATOR_ALIVE:?}"
fi
exit "$FAKE_TEST_STATUS"
FAKE_TEST
chmod +x "$FAKE_TEST"

run_case() {
    local status="$1" adb_fail="$2" output="$3"
    : >"$EVENT_LOG"
    touch "$EMULATOR_ALIVE"
    set +e
    EVENT_LOG="$EVENT_LOG" EMULATOR_ALIVE="$EMULATOR_ALIVE" \
        FAKE_TEST_STATUS="$status" FAKE_ADB_FAIL="$adb_fail" ADB_BIN="$FAKE_ADB" \
        CAPTURE_COMMAND_TIMEOUT_SECONDS=1 CAPTURE_TOTAL_BUDGET_SECONDS=20 \
        CAPTURE_MAX_BYTES_PER_FILE=4096 \
        bash "$WRAPPER" emulator-5554 "$output" -- "$FAKE_TEST"
    local actual=$?
    set -e
    printf 'runner:kill\n' >>"$EVENT_LOG"
    rm -f "$EMULATOR_ALIVE"
    test "$actual" -eq "$status"
    test -s "$output/capture-manifest.tsv"
    test "$(grep -c '^adb:live:' "$EVENT_LOG")" -gt 0
    test "$(grep -c '^adb:gone:' "$EVENT_LOG" || true)" -eq 0
    local kill_line last_live
    kill_line="$(grep -n '^runner:kill$' "$EVENT_LOG" | tail -n 1 | cut -d: -f1)"
    last_live="$(grep -n '^adb:live:' "$EVENT_LOG" | tail -n 1 | cut -d: -f1)"
    test "$last_live" -lt "$kill_line"
}

run_case 23 false "$TEMP_DIR/failure-evidence"
run_case 37 true "$TEMP_DIR/capture-failure-evidence"

# Teardown race path: the failing command removes the emulator marker before
# the wrapper starts capture. The capture must record device-gone statuses and
# still return the original command status.
: >"$EVENT_LOG"
gone_output="$TEMP_DIR/device-gone-evidence"
touch "$EMULATOR_ALIVE"
set +e
EVENT_LOG="$EVENT_LOG" EMULATOR_ALIVE="$EMULATOR_ALIVE" FAKE_TEST_STATUS=41 \
    FAKE_TEST_REMOVE_EMULATOR=true ADB_BIN="$FAKE_ADB" \
    CAPTURE_COMMAND_TIMEOUT_SECONDS=1 CAPTURE_TOTAL_BUDGET_SECONDS=20 \
    CAPTURE_MAX_BYTES_PER_FILE=4096 \
    bash "$WRAPPER" emulator-5554 "$gone_output" -- "$FAKE_TEST"
gone_status=$?
set -e
test "$gone_status" -eq 41
test -s "$gone_output/capture-manifest.tsv"
test "$(grep -c '^adb:gone:' "$EVENT_LOG" || true)" -gt 0
rm -f "$EMULATOR_ALIVE"

: >"$EVENT_LOG"
success_output="$TEMP_DIR/success-evidence"
touch "$EMULATOR_ALIVE"
set +e
EVENT_LOG="$EVENT_LOG" EMULATOR_ALIVE="$EMULATOR_ALIVE" FAKE_TEST_STATUS=0 \
    ADB_BIN="$FAKE_ADB" bash "$WRAPPER" emulator-5554 "$success_output" -- "$FAKE_TEST"
success_status=$?
set -e
rm -f "$EMULATOR_ALIVE"
test "$success_status" -eq 0
test ! -e "$success_output"
if grep -q '^adb:' "$EVENT_LOG"; then
    printf 'FAIL: success path captured failure evidence\n' >&2
    exit 1
fi

printf 'emulator failure capture lifecycle: PASS\n'
