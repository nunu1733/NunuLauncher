#!/usr/bin/env bash
# tools/deck-retirement-backup-restore-smoke.sh
# Orchestrates AC-006 evidence: real old-backup archive created under old APK,
# restored under retirement APK, proved restored DB becomes current with tombstone
# normalization.
#
# Spec: specs/57-deck-runtime-retirement/spec.md
# Plan: specs/57-deck-runtime-retirement/plan.md
#
# Usage:
#   tools/deck-retirement-backup-restore-smoke.sh \
#     --serial <emulator-serial> \
#     --pre-retirement-apk <path-to-old-apk> \
#     --retirement-apk <path-to-new-apk> \
#     --test-apk <path-to-test-apk> \
#     --evidence-dir <output-dir> \
#     --pre-retirement-record-url <url>

set -euo pipefail

# --- Constants ---
DEBUG_PACKAGE="app.lawnchair.debug"
TEST_PACKAGE="app.lawnchair.debug.test"
TEST_RUNNER="${TEST_PACKAGE}/app.lawnchair.migration.DeckRetirementTestRunner"
OLD_COMPAT_CLASS="app.lawnchair.migration.DeckRetirementOldTargetCompatInstrumentationTest"
NEW_TYPED_CLASS="app.lawnchair.migration.DeckRetirementBackupRestoreInstrumentationTest"

# --- Global state ---
SERIAL=""
PRE_RETIREMENT_APK=""
RETIREMENT_APK=""
TEST_APK=""
EVIDENCE_DIR=""
PRE_RETIREMENT_RECORD_URL=""
ACTIVE_CHILD_PID=""
OLD_VERSION_CODE=""
OLD_VERSION_NAME=""
NEW_VERSION_CODE=""
NEW_VERSION_NAME=""
NONCE=""

# === Argument parsing ===

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --serial)
        [[ $# -ge 2 ]] || { echo "--serial requires a value" >&2; exit 2; }
        SERIAL="$2"
        shift 2
        ;;
      --pre-retirement-apk)
        [[ $# -ge 2 ]] || { echo "--pre-retirement-apk requires a value" >&2; exit 2; }
        PRE_RETIREMENT_APK="$2"
        shift 2
        ;;
      --retirement-apk)
        [[ $# -ge 2 ]] || { echo "--retirement-apk requires a value" >&2; exit 2; }
        RETIREMENT_APK="$2"
        shift 2
        ;;
      --test-apk)
        [[ $# -ge 2 ]] || { echo "--test-apk requires a value" >&2; exit 2; }
        TEST_APK="$2"
        shift 2
        ;;
      --evidence-dir)
        [[ $# -ge 2 ]] || { echo "--evidence-dir requires a value" >&2; exit 2; }
        EVIDENCE_DIR="$2"
        shift 2
        ;;
      --pre-retirement-record-url)
        [[ $# -ge 2 ]] || { echo "--pre-retirement-record-url requires a value" >&2; exit 2; }
        PRE_RETIREMENT_RECORD_URL="$2"
        shift 2
        ;;
      -h|--help)
        echo "Usage: $0 --serial <emulator-serial> --pre-retirement-apk <path> --retirement-apk <path> --test-apk <path> --evidence-dir <dir> --pre-retirement-record-url <url>"
        exit 0
        ;;
      *)
        echo "Unknown argument: $1" >&2
        exit 2
        ;;
    esac
  done

  # Validate required arguments
  for var in SERIAL PRE_RETIREMENT_APK RETIREMENT_APK TEST_APK EVIDENCE_DIR PRE_RETIREMENT_RECORD_URL; do
    if [[ -z "${!var}" ]]; then
      echo "Missing required argument: --${var,,}" >&2
      exit 2
    fi
  done

  # Validate URL format
  if [[ ! "$PRE_RETIREMENT_RECORD_URL" =~ ^https://github\.com/nunu1733/NunuLauncher/issues/57#issuecomment-[0-9]+$ ]]; then
    echo "Invalid pre-retirement record URL format" >&2
    exit 2
  fi

  # Validate APK files exist
  for apk in PRE_RETIREMENT_APK RETIREMENT_APK TEST_APK; do
    if [[ ! -f "${!apk}" ]]; then
      echo "APK file not found: ${!apk}" >&2
      exit 2
    fi
  done

  mkdir -p "$EVIDENCE_DIR"
  echo "=== Arguments validated ==="
  echo "Serial: $SERIAL"
  echo "Pre-retirement APK: $PRE_RETIREMENT_APK"
  echo "Retirement APK: $RETIREMENT_APK"
  echo "Test APK: $TEST_APK"
  echo "Evidence dir: $EVIDENCE_DIR"
  echo "Record URL: $PRE_RETIREMENT_RECORD_URL"
}

# === Helper functions ===

device_reachable() {
  adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' | rg -qx device
}

force_stop_if_reachable() {
  device_reachable || return 0
  adb -s "$SERIAL" shell am force-stop "$DEBUG_PACKAGE" || true
  adb -s "$SERIAL" shell am force-stop "$TEST_PACKAGE" || true
}

# Exact run_bounded implementation from plan §"Commands and high-risk evidence"
run_bounded() {
  local LOG="$1" TIMEOUT_SECONDS="$2" STATUS NOW DEADLINE
  shift 2
  "$@" >"$LOG" 2>&1 & ACTIVE_CHILD_PID=$!
  DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; do
    NOW="$(date +%s)"
    if test "$NOW" -ge "$DEADLINE"; then
      kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$ACTIVE_CHILD_PID" 2>/dev/null || true
      wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
      printf 'HOST_TIMEOUT typed=true seconds=%s\n' "$TIMEOUT_SECONDS" >>"$LOG"
      force_stop_if_reachable
      ACTIVE_CHILD_PID=""
      return 124
    fi
    sleep 1
  done
  if wait "$ACTIVE_CHILD_PID"; then STATUS=0; else STATUS=$?; fi
  ACTIVE_CHILD_PID=""
  return "$STATUS"
}

# Single-quote a value for the on-device shell (Bash 3.2 compatible).
shell_quote() {
  printf "'%s'" "${1//\'/\'\\\'\'}"
}

# Build an "am instrument ..." command string with every argument quoted for
# the device shell so version names containing parentheses survive the hop.
build_instrument_command() {
  local CMD="am instrument -r -w" a
  for a in "$@"; do
    CMD="$CMD $(shell_quote "$a")"
  done
  printf '%s' "$CMD"
}

# run_instrument from plan §"Commands and high-risk evidence". The typed
# mode-ready marker is emitted by the runner through logcat (System.out /
# DeckRetirementTestRunner tags), so the logcat buffer is cleared before the
# run and verified after it; the exact success code and failure patterns are
# verified against the normalized instrument log.
run_instrument() {
  local LOG="$1" MARKER="$2" NORMALIZED STATUS MARKER_LOG INSTR_CMD
  shift 2
  device_reachable || { echo 'device not reachable' >&2; return 1; }
  INSTR_CMD="$(build_instrument_command "$@")"
  adb -s "$SERIAL" logcat -c 2>/dev/null || true
  if run_bounded "$LOG" 180 adb -s "$SERIAL" shell "$INSTR_CMD"; then STATUS=0; else STATUS=$?; fi
  NORMALIZED="${LOG%.log}.normalized.log"
  tr -d '\r' <"$LOG" >"$NORMALIZED"
  MARKER_LOG="${LOG%.log}.marker.log"
  adb -s "$SERIAL" logcat -d -v tag 2>/dev/null | tr -d '\r' |
    sed -n -e 's/^[IWEF]\/DeckRetirementTestRunner: //p' -e 's/^[IWEF]\/System\.out: //p' >"$MARKER_LOG"
  test "$STATUS" -eq 0 || return "$STATUS"
  rg -F -x "$MARKER" "$MARKER_LOG"
  rg -F -x 'INSTRUMENTATION_CODE: -1' "$NORMALIZED"
  if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Fatal signal|HOST_TIMEOUT' "$NORMALIZED"; then return 1; fi
}

# Derive package, versionCode, versionName from APK using aapt
derive_apk_metadata() {
  local APK="$1" BADGING PKG VER_CODE VER_NAME
  BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null)" || {
    echo "ERROR: Failed to run aapt dump badging on $APK" >&2
    return 1
  }
  PKG="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)' .*/\1/p")"
  VER_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)' .*/\1/p")"
  VER_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)' .*/\1/p")"
  if [[ "$PKG" != "$DEBUG_PACKAGE" ]]; then
    echo "ERROR: APK package mismatch: expected $DEBUG_PACKAGE, got $PKG" >&2
    return 1
  fi
  if [[ -z "$VER_CODE" ]]; then
    echo "ERROR: Missing versionCode in APK badging" >&2
    return 1
  fi
  if [[ -z "$VER_NAME" ]]; then
    echo "ERROR: Missing versionName in APK badging" >&2
    return 1
  fi
  printf '%s\t%s\t%s\n' "$PKG" "$VER_CODE" "$VER_NAME"
}

# Generate 32-hex-char nonce
generate_nonce() {
  local N
  N="$(LC_ALL=C od -An -N16 -tx1 /dev/urandom | tr -d '[:space:]')"
  if [[ ! "$N" =~ ^[0-9a-f]{32}$ ]]; then
    echo "ERROR: invalid deck retirement nonce generated" >&2
    return 1
  fi
  printf '%s' "$N"
}

# Get device APK sourceDir for the debug package
get_device_apk_path() {
  adb -s "$SERIAL" shell pm path "$DEBUG_PACKAGE" 2>/dev/null | sed 's/^package://' | tr -d '\r' | head -1
}

# Verify host APK SHA matches device APK SHA
verify_apk_sha() {
  local HOST_APK="$1" DEVICE_APK_PATH="$2" HOST_SHA DEVICE_SHA
  HOST_SHA="$($SHA_CMD "$HOST_APK" | cut -d' ' -f1)"
  # Try device-side sha256sum first, fall back to streaming
  DEVICE_SHA="$(adb -s "$SERIAL" exec-out run-as "$DEBUG_PACKAGE" sha256sum "$DEVICE_APK_PATH" 2>/dev/null | cut -d' ' -f1)"
  if [[ -z "$DEVICE_SHA" ]]; then
    DEVICE_SHA="$(adb -s "$SERIAL" exec-out cat "$DEVICE_APK_PATH" | $SHA_CMD | cut -d' ' -f1)"
  fi
  if [[ "$HOST_SHA" != "$DEVICE_SHA" ]]; then
    echo "ERROR: APK SHA mismatch: host=$HOST_SHA device=$DEVICE_SHA" >&2
    return 1
  fi
  printf '%s' "$HOST_SHA"
}

# Capture evidence digest and metadata into evidence dir
capture_evidence_entry() {
  local PHASE="$1" LABEL="$2"
  local EVIDENCE_FILE="$EVIDENCE_DIR/$PHASE.evidence.txt"
  {
    echo "=== Evidence: $LABEL ==="
    echo "phase=$PHASE"
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo ""

    # Package version info
    echo "--- Package info ---"
    adb -s "$SERIAL" shell dumpsys package "$DEBUG_PACKAGE" 2>/dev/null | grep -E 'versionCode|versionName|path:' | head -5 || echo "dumpsys unavailable"
    echo ""

    # APK SHA
    echo "--- Pre-retirement APK SHA ---"
    $SHA_CMD "$PRE_RETIREMENT_APK" 2>/dev/null || echo "unavailable"
    echo "--- Retirement APK SHA ---"
    $SHA_CMD "$RETIREMENT_APK" 2>/dev/null || echo "unavailable"
    echo ""

    # DB digest
    echo "--- DB digests (all active grid databases) ---"
    local DB_NAME
    for DB_NAME in $(adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" ls databases/ 2>/dev/null | tr -d '\r' | grep -E '\.db$' | grep -vE '^(bk_|lawndeck_)'); do
      printf '%s ' "$DB_NAME"
      adb -s "$SERIAL" exec-out run-as "$DEBUG_PACKAGE" cat "databases/$DB_NAME" 2>/dev/null | $SHA_CMD || echo "unavailable"
    done
    echo ""

    # Artifact presence
    echo "--- Historical artifact presence ---"
    adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" ls databases/ 2>/dev/null | grep -E '^bk_|^lawndeck_' || echo "(none)"
    echo ""

    # Record URL
    echo "--- Pre-retirement record URL ---"
    echo "$PRE_RETIREMENT_RECORD_URL"
    echo ""

    echo "=== End evidence ==="
  } > "$EVIDENCE_FILE"
  echo "Evidence written: $EVIDENCE_FILE"
}

# === Cleanup trap ===

cleanup() {
  local ORIGINAL_STATUS="$?" CLEANUP_STATUS=0
  trap - EXIT INT TERM
  if test -n "$ACTIVE_CHILD_PID" && kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; then
    kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true
    sleep 1
    kill -KILL "$ACTIVE_CHILD_PID" 2>/dev/null || true
    wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
  fi
  force_stop_if_reachable
  if test "$ORIGINAL_STATUS" -ne 0; then exit "$ORIGINAL_STATUS"; fi
  test "$CLEANUP_STATUS" -eq 0 || exit 125
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# === Pre-flight checks ===

preflight() {
  if ! command -v rg >/dev/null 2>&1; then
    echo "ERROR: rg (ripgrep) is required but not found in PATH" >&2
    exit 1
  fi
  AAPT="${AAPT:-aapt}"
  if ! command -v "$AAPT" >/dev/null 2>&1; then
    echo "ERROR: aapt is required but not found. Set AAPT env var or add to PATH." >&2
    echo "  ANDROID_HOME/build-tools/36.1.0/aapt is the expected version." >&2
    exit 1
  fi
  SHA_CMD="${SHA_CMD:-}"
  if command -v sha256sum >/dev/null 2>&1; then
    SHA_CMD="sha256sum"
  elif command -v shasum >/dev/null 2>&1; then
    SHA_CMD="shasum -a 256"
  else
    echo "ERROR: Neither sha256sum nor shasum found in PATH" >&2
    exit 1
  fi
  if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required but not found in PATH" >&2
    exit 1
  fi
  echo "=== Pre-flight checks passed === (SHA: $SHA_CMD)"
}

# === Main flow ===

main() {
  parse_args "$@"
  preflight

  # Verify device is reachable
  echo "=== Verifying device $SERIAL ==="
  device_reachable || { echo "ERROR: Device $SERIAL not reachable" >&2; exit 1; }
  echo "Device reachable."

  # ---- Phase 1: Old APK metadata ----
  echo "=== Deriving old APK metadata ==="
  OLD_META="$(derive_apk_metadata "$PRE_RETIREMENT_APK")"
  OLD_PACKAGE="$(printf '%s\n' "$OLD_META" | cut -f1)"
  OLD_VERSION_CODE="$(printf '%s\n' "$OLD_META" | cut -f2)"
  OLD_VERSION_NAME="$(printf '%s\n' "$OLD_META" | cut -f3)"
  echo "Old APK: $OLD_PACKAGE v$OLD_VERSION_CODE ($OLD_VERSION_NAME)"

  # ---- Phase 2: Install old APK + test APK ----
  echo "=== Installing old APK + test APK ==="
  adb -s "$SERIAL" install -r "$PRE_RETIREMENT_APK" >/dev/null
  adb -s "$SERIAL" install -r "$TEST_APK" >/dev/null
  echo "Installation complete."

  # ---- Phase 3: Verify device APK SHA ----
  echo "=== Verifying APK SHA ==="
  DEVICE_APK_PATH="$(get_device_apk_path)"
  [[ -n "$DEVICE_APK_PATH" ]] || { echo "ERROR: Device APK path not found" >&2; exit 1; }
  echo "Device APK path: $DEVICE_APK_PATH"
  OLD_APK_SHA="$(verify_apk_sha "$PRE_RETIREMENT_APK" "$DEVICE_APK_PATH")"
  echo "APK SHA verified: $OLD_APK_SHA"

  # ---- Phase 3.5: Launch HOME under the old APK ----
  # The old target must create its workspace database so the archive and the
  # pre-archive canonical digest describe a real layout.
  echo "=== Launching HOME under old APK ==="
  adb -s "$SERIAL" shell am start -n "${DEBUG_PACKAGE}/app.lawnchair.LawnchairLauncher" >/dev/null
  sleep 8
  adb -s "$SERIAL" shell am force-stop "$DEBUG_PACKAGE"
  sleep 2
  echo "Old HOME launched and stopped."

  # ---- Phase 4: Generate nonce ----
  echo "=== Generating nonce ==="
  NONCE="$(generate_nonce)"
  echo "Nonce: $NONCE"

  # ---- Phase 5: Run old_compat to create backup ----
  echo "=== Running old_compat to create backup ==="
  OLD_COMPAT_LOG="$EVIDENCE_DIR/old_compat.log"
  run_instrument "$OLD_COMPAT_LOG" 'OLD_COMPAT_READY typed=true' \
    -e deck_retirement_target_mode old_compat \
    -e expected_target_version_code "$OLD_VERSION_CODE" \
    -e expected_target_version_name "$OLD_VERSION_NAME" \
    -e expected_target_apk_path "$DEVICE_APK_PATH" \
    -e deck_retirement_nonce "$NONCE" \
    -e class "${OLD_COMPAT_CLASS}#seedAndCreateBackup" \
    "$TEST_RUNNER"
  rg -F -x 'OK (1 test)' "${OLD_COMPAT_LOG%.log}.normalized.log"
  echo "Backup created under old APK."

  # Extract the pre-archive canonical digest emitted by the old target.
  OLD_COMPAT_MARKER_LOG="${OLD_COMPAT_LOG%.log}.marker.log"
  PRE_ARCHIVE_DIGEST="$(sed -n "s/^PRE_ARCHIVE_DIGEST nonce=${NONCE} digest=\([0-9a-f]*\) typed=true$/\1/p" "$OLD_COMPAT_MARKER_LOG" | head -1)"
  [[ "$PRE_ARCHIVE_DIGEST" =~ ^[0-9a-f]{64}$ ]] || {
    echo "ERROR: PRE_ARCHIVE_DIGEST missing or invalid: $PRE_ARCHIVE_DIGEST" >&2
    exit 1
  }
  echo "Pre-archive canonical digest: $PRE_ARCHIVE_DIGEST"

  # ---- Phase 6: Pull archive ----
  echo "=== Pulling archive ==="
  ARCHIVE_FILE="$EVIDENCE_DIR/$NONCE.lawnchairbackup"
  adb -s "$SERIAL" exec-out run-as "$DEBUG_PACKAGE" cat "cache/logs/deck-retirement-backup/$NONCE.lawnchairbackup" > "$ARCHIVE_FILE"
  [[ -s "$ARCHIVE_FILE" ]] || { echo "ERROR: Archive file is empty" >&2; exit 1; }
  echo "Archive pulled: $ARCHIVE_FILE ($(wc -c < "$ARCHIVE_FILE") bytes)"

  # ---- Phase 7: SHA-256 verify archive ----
  echo "=== Verifying archive SHA ==="
  ARCHIVE_SHA="$($SHA_CMD "$ARCHIVE_FILE" | cut -d' ' -f1)"
  echo "Archive SHA: $ARCHIVE_SHA"
  # Cross-verify with device
  DEVICE_ARCHIVE_SHA="$(adb -s "$SERIAL" exec-out run-as "$DEBUG_PACKAGE" sha256sum "cache/logs/deck-retirement-backup/$NONCE.lawnchairbackup" 2>/dev/null | cut -d' ' -f1)"
  if [[ -n "$DEVICE_ARCHIVE_SHA" ]]; then
    [[ "$ARCHIVE_SHA" = "$DEVICE_ARCHIVE_SHA" ]] || { echo "ERROR: Archive SHA mismatch between host and device" >&2; exit 1; }
    echo "Archive SHA verified (host == device)."
  else
    echo "Warning: Could not verify archive SHA on device side."
  fi

  # ---- Phase 8: Capture pre-restore evidence on old APK ----
  capture_evidence_entry "old-apk-after-backup" "Old APK after backup creation"

  # ---- Phase 9: Derive retirement APK metadata ----
  echo "=== Deriving retirement APK metadata ==="
  NEW_META="$(derive_apk_metadata "$RETIREMENT_APK")"
  NEW_PACKAGE="$(printf '%s\n' "$NEW_META" | cut -f1)"
  NEW_VERSION_CODE="$(printf '%s\n' "$NEW_META" | cut -f2)"
  NEW_VERSION_NAME="$(printf '%s\n' "$NEW_META" | cut -f3)"
  echo "Retirement APK: $NEW_PACKAGE v$NEW_VERSION_CODE ($NEW_VERSION_NAME)"

  # ---- Phase 10: Install retirement APK + test APK ----
  echo "=== Installing retirement APK + test APK ==="
  adb -s "$SERIAL" install -r "$RETIREMENT_APK" >/dev/null
  adb -s "$SERIAL" install -r "$TEST_APK" >/dev/null
  echo "Retirement APK installed."

  # Verify new APK on device
  NEW_DEVICE_APK_PATH="$(get_device_apk_path)"
  [[ -n "$NEW_DEVICE_APK_PATH" ]] || { echo "ERROR: Device APK path not found after upgrade" >&2; exit 1; }
  NEW_APK_SHA="$(verify_apk_sha "$RETIREMENT_APK" "$NEW_DEVICE_APK_PATH")"
  echo "New APK SHA verified: $NEW_APK_SHA"

  # ---- Phase 11: Create distinct layout ----
  echo "=== Creating distinct layout under new APK ==="
  NEW_TYPED_LOG="$EVIDENCE_DIR/create_distinct_layout.log"
  run_instrument "$NEW_TYPED_LOG" 'NEW_TYPED_READY typed=true' \
    -e deck_retirement_target_mode new_typed \
    -e deck_retirement_nonce "$NONCE" \
    -e expected_target_version_code "$NEW_VERSION_CODE" \
    -e expected_target_version_name "$NEW_VERSION_NAME" \
    -e expected_target_apk_path "$NEW_DEVICE_APK_PATH" \
    -e class "${NEW_TYPED_CLASS}#createDistinctLayout" \
    "$TEST_RUNNER"
  rg -F -x 'OK (1 test)' "${NEW_TYPED_LOG%.log}.normalized.log"
  echo "Distinct layout created."

  # ---- Phase 12: Capture pre-restore evidence ----
  capture_evidence_entry "pre-restore" "Before archive restore under new APK"

  # ---- Phase 13: Reinject archive ----
  echo "=== Reinjecting archive ==="
  local TMP_PATH="/data/local/tmp/${NONCE}.lawnchairbackup"
  local PIPE_CMD="mkdir -p cache/logs/deck-retirement-backup && cat > cache/logs/deck-retirement-backup/${NONCE}.lawnchairbackup"
  local QUOTED_CMD
  QUOTED_CMD="$(shell_quote "$PIPE_CMD")"

  adb -s "$SERIAL" push "$ARCHIVE_FILE" "$TMP_PATH" >/dev/null 2>&1 || {
    echo "ERROR: Failed to push archive to device" >&2
    exit 1
  }
  adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" mkdir -p cache/logs/deck-retirement-backup || {
    echo "ERROR: Failed to create backup dir on device" >&2
    exit 1
  }

  set +e
  adb -s "$SERIAL" shell "cat ${TMP_PATH} | run-as ${DEBUG_PACKAGE} sh -c ${QUOTED_CMD}"
  local PIPE_STATUS=$?
  set -e
  test "$PIPE_STATUS" -eq 0 || {
    echo "ERROR: Failed to pipe archive into app data dir (status $PIPE_STATUS)" >&2
    exit 1
  }

  adb -s "$SERIAL" shell rm -f "$TMP_PATH" 2>/dev/null || true
  if ! adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" test -f "cache/logs/deck-retirement-backup/${NONCE}.lawnchairbackup" 2>/dev/null; then
    echo "ERROR: Archive file missing on device after reinject" >&2
    exit 1
  fi
  echo "Archive reinjected."

  # ---- Phase 14: Run restore ----
  echo "=== Running restore ==="
  RESTORE_LOG="$EVIDENCE_DIR/restore.log"
  run_instrument "$RESTORE_LOG" 'NEW_TYPED_READY typed=true' \
    -e deck_retirement_target_mode new_typed \
    -e deck_retirement_nonce "$NONCE" \
    -e expected_target_version_code "$NEW_VERSION_CODE" \
    -e expected_target_version_name "$NEW_VERSION_NAME" \
    -e expected_target_apk_path "$NEW_DEVICE_APK_PATH" \
    -e expected_archive_digest "$PRE_ARCHIVE_DIGEST" \
    -e class "${NEW_TYPED_CLASS}#restoreAndCapture" \
    "$TEST_RUNNER"
  rg -F -x 'OK (1 test)' "${RESTORE_LOG%.log}.normalized.log"
  echo "Restore completed."

  # ---- Phase 15: Force-stop, relaunch HOME ----
  echo "=== Force-stopping and relaunching HOME ==="
  adb -s "$SERIAL" shell am force-stop "$DEBUG_PACKAGE"
  sleep 2
  adb -s "$SERIAL" shell am start -n "${DEBUG_PACKAGE}/app.lawnchair.LawnchairLauncher" >/dev/null
  sleep 5
  echo "HOME launched."

  # ---- Phase 16: Capture after restart ----
  echo "=== Capturing after restart ==="
  RESTART_LOG="$EVIDENCE_DIR/capture_after_restart.log"
  run_instrument "$RESTART_LOG" 'NEW_TYPED_READY typed=true' \
    -e deck_retirement_target_mode new_typed \
    -e deck_retirement_nonce "$NONCE" \
    -e expected_target_version_code "$NEW_VERSION_CODE" \
    -e expected_target_version_name "$NEW_VERSION_NAME" \
    -e expected_target_apk_path "$NEW_DEVICE_APK_PATH" \
    -e class "${NEW_TYPED_CLASS}#captureAfterRestart" \
    "$TEST_RUNNER"
  rg -F -x 'OK (1 test)' "${RESTART_LOG%.log}.normalized.log"
  echo "Post-restart capture completed."

  # ---- Phase 17: Capture final evidence ----
  capture_evidence_entry "post-restart" "After archive restore and restart"

  # ---- Phase 18: Record oracle ----
  echo "=== Recording oracle ==="
  ORACLE_FILE="$EVIDENCE_DIR/oracle.txt"
  {
    echo "=== AC-006 Oracle ==="
    echo "scenario=backup-restore-smoke"
    echo "archive_nonce=$NONCE"
    echo "archive_sha256=$ARCHIVE_SHA"
    echo "old_version_code=$OLD_VERSION_CODE"
    echo "old_version_name=$OLD_VERSION_NAME"
    echo "new_version_code=$NEW_VERSION_CODE"
    echo "new_version_name=$NEW_VERSION_NAME"
    echo "pre_retirement_record_url=$PRE_RETIREMENT_RECORD_URL"
    echo "oracle=restored_db_current tombstone_normalized no_bk_lawndeck_artifacts"
    echo "status=completed"
  } > "$ORACLE_FILE"
  echo "Oracle recorded: $ORACLE_FILE"

  echo ""
  echo "=== Backup-restore smoke scenario completed successfully ==="
  echo "Evidence directory: $EVIDENCE_DIR"
}

main "$@"