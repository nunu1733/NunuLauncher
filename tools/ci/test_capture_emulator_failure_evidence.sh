#!/usr/bin/env bash

# Smoke-test the failure evidence helper without requiring an emulator. The
# fake adb records the requested subcommands and returns representative output,
# including a failed ANR command, so the helper's best-effort contract is tested.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

FAKE_ADB="$TEMP_DIR/adb"
cat >"$FAKE_ADB" <<'FAKE_ADB'
#!/usr/bin/env bash
set -u
printf 'fake-adb %s\n' "$*" >>"${FAKE_ADB_LOG:?}"
if [[ "$*" == *"system_app_anr"* ]]; then
    printf 'fake ANR trace\n'
    exit 7
fi
printf 'fake adb output\n'
FAKE_ADB
chmod +x "$FAKE_ADB"

export ADB_BIN="$FAKE_ADB"
export FAKE_ADB_LOG="$TEMP_DIR/adb.log"
OUTPUT_DIR="$TEMP_DIR/evidence"

bash "$ROOT_DIR/tools/ci/capture-emulator-failure-evidence.sh" emulator-5554 "$OUTPUT_DIR"

test -s "$OUTPUT_DIR/README.txt"
test -s "$OUTPUT_DIR/window-windows.txt"
test -s "$OUTPUT_DIR/home-role.txt"
test -s "$OUTPUT_DIR/system-app-anr.txt"
grep -q 'fake ANR trace' "$OUTPUT_DIR/system-app-anr.txt"
grep -q 'command_exit_status=7' "$OUTPUT_DIR/system-app-anr.txt"
grep -q 'dumpsys window windows' "$FAKE_ADB_LOG"
grep -q 'logcat -d' "$FAKE_ADB_LOG"

printf 'capture-emulator-failure-evidence smoke test: PASS\n'
