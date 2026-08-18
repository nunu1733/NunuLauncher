#!/usr/bin/env bash
# tools/deck-retirement-downgrade-smoke.sh
# Orchestrates AC-008 evidence: four downgrade scenarios with real APK transitions,
# pause handshake, evidence capture, and oracle classification.
#
# Spec: specs/57-deck-runtime-retirement/spec.md
# Plan: specs/57-deck-runtime-retirement/plan.md
#
# Usage:
#   tools/deck-retirement-downgrade-smoke.sh \
#     --scenario <name> \
#     --serial <emulator-serial> \
#     --pre-retirement-apk <path-to-old-apk> \
#     --retirement-apk <path-to-new-apk> \
#     --test-apk <path-to-test-apk> \
#     --evidence-dir <output-dir> \
#     --pre-retirement-record-url <url>
#
# Scenario names:
#   rollback-before-cleanup   Best-effort rollback after normalization before cleanup
#   downgrade-after-cleanup   Cleanup-complete downgrade, no Deck restoration promise
#   pre-initialization-old-binary     Unsupported: old binary before new init
#   pre-initialization-old-backup     Unsupported: old backup restore before new init

set -euo pipefail

# --- Constants ---
DEBUG_PACKAGE="app.lawnchair.debug"
TEST_PACKAGE="app.lawnchair.debug.test"
TEST_RUNNER="${TEST_PACKAGE}/app.lawnchair.migration.DeckRetirementTestRunner"
OLD_COMPAT_CLASS="app.lawnchair.migration.DeckRetirementOldTargetCompatInstrumentationTest"
NEW_TYPED_CLASS="app.lawnchair.migration.DeckRetirementBackupRestoreInstrumentationTest"
PAUSE_FIXTURE_CLASS="app.lawnchair.migration.DeckRetirementDowngradeFixtureInstrumentationTest"

# --- Global state ---
SERIAL=""
SCENARIO=""
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
      --scenario)
        [[ $# -ge 2 ]] || { echo "--scenario requires a value" >&2; exit 2; }
        SCENARIO="$2"
        shift 2
        ;;
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
        echo "Usage: $0 --scenario <name> --serial <emulator-serial> --pre-retirement-apk <path> --retirement-apk <path> --test-apk <path> --evidence-dir <dir> --pre-retirement-record-url <url>"
        echo "Scenarios: rollback-before-cleanup downgrade-after-cleanup pre-initialization-old-binary pre-initialization-old-backup"
        exit 0
        ;;
      *)
        echo "Unknown argument: $1" >&2
        exit 2
        ;;
    esac
  done

  # Validate required arguments
  for var in SCENARIO SERIAL PRE_RETIREMENT_APK RETIREMENT_APK TEST_APK EVIDENCE_DIR PRE_RETIREMENT_RECORD_URL; do
    if [[ -z "${!var}" ]]; then
      echo "Missing required argument: --${var,,}" >&2
      exit 2
    fi
  done

  # Validate scenario
  case "$SCENARIO" in
    rollback-before-cleanup|downgrade-after-cleanup|pre-initialization-old-binary|pre-initialization-old-backup)
      ;;
    *)
      echo "Unknown scenario: $SCENARIO" >&2
      echo "Valid scenarios: rollback-before-cleanup downgrade-after-cleanup pre-initialization-old-binary pre-initialization-old-backup" >&2
      exit 2
      ;;
  esac

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
  echo "Scenario: $SCENARIO"
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

# Exact run_bounded implementation from plan SS"Commands and high-risk evidence"
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

# Exact run_instrument from plan SS"Commands and high-risk evidence"
run_instrument() {
  local LOG="$1" MARKER="$2" NORMALIZED STATUS MARKER_LOG
  shift 2
  device_reachable || { echo 'device not reachable' >&2; return 1; }
  adb -s "$SERIAL" logcat -c 2>/dev/null || true
  if run_bounded "$LOG" 180 adb -s "$SERIAL" shell am instrument -r -w "$@"; then STATUS=0; else STATUS=$?; fi
  NORMALIZED="${LOG%.log}.normalized.log"
  tr -d '\r' <"$LOG" >"$NORMALIZED"
  MARKER_LOG="${LOG%.log}.marker.log"
  adb -s "$SERIAL" logcat -d -v tag 2>/dev/null | tr -d '\r' |
    sed -n -e 's/^DeckRetirementTestRunner: //p' -e 's/^System\.out: //p' >"$MARKER_LOG"
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

# New-pause phase handler: start instrumentation, wait for PAUSED, then rollback or release
# Usage: run_pause_phase <log> <nonce> <rollback|release> <am instrument args...>
# The typed PAUSED/ACK markers are emitted by the runner through logcat
# (DeckRetirementTestRunner tag / System.out), so the logcat buffer is cleared
# before the run and polled after it; the exact success code and failure
# patterns are verified against the normalized instrument log.
run_pause_phase() {
  local LOG="$1" NONCE="$2" MODE="$3" TIMEOUT=180
  shift 3

  local PAUSED_MARKER="PAUSED phase=AFTER_NORMALIZATION_BEFORE_CLEANUP nonce=${NONCE} typed=true"
  local DEADLINE=$(( $(date +%s) + TIMEOUT ))
  local FOUND_PAUSED=0

  adb -s "$SERIAL" logcat -c 2>/dev/null || true
  adb -s "$SERIAL" shell am instrument -r -w "$@" >"$LOG" 2>&1 &
  ACTIVE_CHILD_PID=$!

  while kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; do
    if test "$(date +%s)" -ge "$DEADLINE"; then
      kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$ACTIVE_CHILD_PID" 2>/dev/null || true
      wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
      printf 'HOST_TIMEOUT typed=true seconds=%s\n' "$TIMEOUT" >>"$LOG"
      force_stop_if_reachable
      ACTIVE_CHILD_PID=""
      return 124
    fi
    if test "$FOUND_PAUSED" -eq 0; then
      if dump_typed_logcat_lines | rg -q -F -x "$PAUSED_MARKER"; then
        FOUND_PAUSED=1
        # Verify .paused file exists inside the app's private data dir.
        if ! adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" test -f "cache/logs/deck-retirement-control/${NONCE}.paused" 2>/dev/null; then
          echo "ERROR: Missing .paused file for nonce $NONCE" >&2
          break
        fi
        echo "PAUSED detected for nonce $NONCE"
        if test "$MODE" = "rollback"; then
          force_stop_if_reachable
          # Wait briefly for child to die
          local CW=$(( $(date +%s) + 10 ))
          while kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null && test "$(date +%s)" -lt "$CW"; do sleep 1; done
          kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true
          wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
          ACTIVE_CHILD_PID=""
          return 0
        elif test "$MODE" = "release"; then
          adb -s "$SERIAL" exec-in run-as "$DEBUG_PACKAGE" sh -c \
            'mkdir -p cache/logs/deck-retirement-control && cat > cache/logs/deck-retirement-control/'"$NONCE"'.release' < /dev/null
          echo "Release file written for nonce $NONCE"
          # Continue loop: child will ACK and complete
        fi
      fi
    fi
    sleep 1
  done

  # Child has exited
  wait "$ACTIVE_CHILD_PID" 2>/dev/null; local STATUS=$?
  ACTIVE_CHILD_PID=""

  if test "$FOUND_PAUSED" -eq 0; then
    echo "ERROR: Child exited without PAUSED marker" >&2
    return 1
  fi

  if test "$MODE" = "release"; then
    local NORMALIZED="${LOG%.log}.normalized.log"
    tr -d '\r' <"$LOG" >"$NORMALIZED"
    local MARKER_LOG="${LOG%.log}.marker.log"
    dump_typed_logcat_lines >"$MARKER_LOG"
    test "$STATUS" -eq 0 || return "$STATUS"
    rg -F -x "ACK_RECEIVED nonce=${NONCE} typed=true" "$MARKER_LOG"
    adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" test -f "cache/logs/deck-retirement-control/${NONCE}.ack" 2>/dev/null || {
      echo "ERROR: Missing .ack file for nonce $NONCE" >&2
      return 1
    }
    rg -F -x 'INSTRUMENTATION_CODE: -1' "$NORMALIZED"
    if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Fatal signal|HOST_TIMEOUT' "$NORMALIZED"; then return 1; fi
    return 0
  fi

  return 0
}

# Dumps current logcat, stripping the two tag prefixes the runner/tests use
# for typed markers, so exact full-line matching works.
dump_typed_logcat_lines() {
  adb -s "$SERIAL" logcat -d -v tag 2>/dev/null | tr -d '\r' |
    sed -n -e 's/^DeckRetirementTestRunner: //p' -e 's/^System\.out: //p'
}

# Capture evidence digest and metadata into evidence dir
capture_evidence_entry() {
  local PHASE="$1" LABEL="$2"
  local EVIDENCE_FILE="$EVIDENCE_DIR/$PHASE.evidence.txt"
  {
    echo "=== Evidence: $LABEL ==="
    echo "phase=$PHASE"
    echo "scenario=$SCENARIO"
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo ""

    echo "--- Package info ---"
    adb -s "$SERIAL" shell dumpsys package "$DEBUG_PACKAGE" 2>/dev/null | grep -E 'versionCode|versionName|path:' | head -5 || echo "dumpsys unavailable"
    echo ""

    echo "--- Pre-retirement APK SHA ---"
    $SHA_CMD "$PRE_RETIREMENT_APK" 2>/dev/null || echo "unavailable"
    echo "--- Retirement APK SHA ---"
    $SHA_CMD "$RETIREMENT_APK" 2>/dev/null || echo "unavailable"
    echo ""

    echo "--- DB digest ---"
    adb -s "$SERIAL" exec-out run-as "$DEBUG_PACKAGE" cat databases/launcher.db 2>/dev/null | $SHA_CMD || echo "DB unavailable"
    echo ""

    echo "--- Historical artifact presence ---"
    adb -s "$SERIAL" shell run-as "$DEBUG_PACKAGE" ls databases/ 2>/dev/null | grep -E '^bk_|^lawndeck_' || echo "(none)"
    echo ""

    echo "--- Pre-retirement record URL ---"
    echo "$PRE_RETIREMENT_RECORD_URL"
    echo ""

    echo "=== End evidence ==="
  } > "$EVIDENCE_FILE"
  echo "Evidence written: $EVIDENCE_FILE"
}

# Write oracle classification
write_oracle() {
  local CLASSIFICATION="$1" DETAIL="$2"
  local ORACLE_FILE="$EVIDENCE_DIR/oracle.txt"
  {
    echo "=== AC-008 Oracle ==="
    echo "scenario=$SCENARIO"
    echo "classification=$CLASSIFICATION"
    echo "detail=$DETAIL"
    echo "old_version_code=$OLD_VERSION_CODE"
    echo "old_version_name=$OLD_VERSION_NAME"
    echo "new_version_code=$NEW_VERSION_CODE"
    echo "new_version_name=$NEW_VERSION_NAME"
    echo "pre_retirement_record_url=$PRE_RETIREMENT_RECORD_URL"
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "status=completed"
  } > "$ORACLE_FILE"
  echo "Oracle written: $CLASSIFICATION"
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
  device_reachable || { echo "ERROR: Device $SERIAL not reachable" >&2; exit 1; }
  echo "=== Pre-flight checks passed === (SHA: $SHA_CMD)"
}

# === Common phase helpers ===

# Install old APK + test APK, derive metadata, verify SHA
install_old_and_verify() {
  echo "=== Installing old APK + test APK ==="
  adb -s "$SERIAL" install -r "$PRE_RETIREMENT_APK" >/dev/null
  adb -s "$SERIAL" install -r "$TEST_APK" >/dev/null
  echo "Installation complete."

  echo "=== Deriving old APK metadata ==="
  local OLD_META
  OLD_META="$(derive_apk_metadata "$PRE_RETIREMENT_APK")"
  OLD_VERSION_CODE="$(printf '%s\n' "$OLD_META" | cut -f2)"
  OLD_VERSION_NAME="$(printf '%s\n' "$OLD_META" | cut -f3)"
  echo "Old APK: v$OLD_VERSION_CODE ($OLD_VERSION_NAME)"

  echo "=== Verifying device APK SHA ==="
  local DEVICE_APK_PATH
  DEVICE_APK_PATH="$(get_device_apk_path)"
  [[ -n "$DEVICE_APK_PATH" ]] || { echo "ERROR: Device APK path not found" >&2; exit 1; }
  echo "Device APK path: $DEVICE_APK_PATH"
  verify_apk_sha "$PRE_RETIREMENT_APK" "$DEVICE_APK_PATH" >/dev/null
  echo "APK SHA verified."
}

# Install retirement APK + test APK, derive metadata
install_retirement_and_verify() {
  echo "=== Installing retirement APK + test APK ==="
  adb -s "$SERIAL" install -r "$RETIREMENT_APK" >/dev/null
  adb -s "$SERIAL" install -r "$TEST_APK" >/dev/null
  echo "Installation complete."

  echo "=== Deriving retirement APK metadata ==="
  local NEW_META
  NEW_META="$(derive_apk_metadata "$RETIREMENT_APK")"
  NEW_VERSION_CODE="$(printf '%s\n' "$NEW_META" | cut -f2)"
  NEW_VERSION_NAME="$(printf '%s\n' "$NEW_META" | cut -f3)"
  echo "Retirement APK: v$NEW_VERSION_CODE ($NEW_VERSION_NAME)"
}

# Downgrade to old APK with -r -d
downgrade_to_old() {
  echo "=== Downgrading to old APK ==="
  adb -s "$SERIAL" install -r -d "$PRE_RETIREMENT_APK" >/dev/null
  echo "Downgrade complete."
}

# Verify host APK SHA matches device APK SHA
verify_apk_sha() {
  local HOST_APK="$1" DEVICE_APK_PATH="$2" HOST_SHA DEVICE_SHA
  HOST_SHA="$($SHA_CMD "$HOST_APK" | cut -d' ' -f1)"
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

# Run old_compat seed/capture
run_old_compat_seed() {
  local LOG="$1" DEVICE_APK_PATH="$2" ACTION="$3"
  local OLD_COMPAT_LOG="$EVIDENCE_DIR/$LOG"
  run_instrument "$OLD_COMPAT_LOG" 'OLD_COMPAT_READY typed=true' \
    -e deck_retirement_target_mode old_compat \
    -e expected_target_version_code "$OLD_VERSION_CODE" \
    -e expected_target_version_name "$OLD_VERSION_NAME" \
    -e expected_target_apk_path "$DEVICE_APK_PATH" \
    -e class "${OLD_COMPAT_CLASS}#${ACTION}" \
    "$TEST_RUNNER"
  rg -F -x 'OK (1 test)' "$OLD_COMPAT_LOG"
}

# Launch HOME
launch_home() {
  adb -s "$SERIAL" shell am start -n "${DEBUG_PACKAGE}/com.android.launcher3.Launcher" >/dev/null
  sleep 3
}

# === Scenario implementations ===

scenario_rollback_before_cleanup() {
  echo "=== Scenario: rollback-before-cleanup ==="
  local DEVICE_APK_PATH

  # 1. Install old APK + test APK, verify
  install_old_and_verify
  DEVICE_APK_PATH="$(get_device_apk_path)"

  # 2. Seed Deck-enabled state via old_compat
  echo "=== Seeding Deck-enabled state ==="
  run_old_compat_seed "seed-deck.log" "$DEVICE_APK_PATH" "seedDeckEnabled"

  # 3. Install retirement APK
  install_retirement_and_verify

  # 4. Generate nonce for pause
  NONCE="$(generate_nonce)"
  echo "Nonce: $NONCE"

  # 5. Run new_pause mode, wait for PAUSED
  echo "=== Running new_pause mode ==="
  local PAUSE_LOG="$EVIDENCE_DIR/pause.log"
  run_pause_phase "$PAUSE_LOG" "$NONCE" "rollback" \
    -e deck_retirement_target_mode new_pause \
    -e deck_retirement_nonce "$NONCE" \
    -e class "$PAUSE_FIXTURE_CLASS" \
    "$TEST_RUNNER"
  echo "PAUSED phase captured (rollback mode)."

  # 6. Capture evidence (after normalization, before cleanup)
  capture_evidence_entry "after-normalization-before-cleanup" "After normalization, before cleanup (rollback pending)"

  # 7. Force-stop already performed by run_pause_phase (rollback mode)

  # 8. Downgrade to old APK
  downgrade_to_old

  # 9. Launch old HOME
  launch_home

  # 10. Capture with old_compat
  echo "=== Capturing under old binary ==="
  DEVICE_APK_PATH="$(get_device_apk_path)"
  run_old_compat_seed "old-capture.log" "$DEVICE_APK_PATH" "captureOnly"
  capture_evidence_entry "after-rollback" "After rollback to old APK"

  # 11. Oracle: best effort
  write_oracle "best effort" "Rollback before cleanup evidenced. Old-binary behavior is best effort."
  echo "=== Scenario rollback-before-cleanup completed ==="
}

scenario_downgrade_after_cleanup() {
  echo "=== Scenario: downgrade-after-cleanup ==="
  local DEVICE_APK_PATH

  # 1. Install old APK + test APK, verify
  install_old_and_verify
  DEVICE_APK_PATH="$(get_device_apk_path)"

  # 2. Seed Deck-enabled state via old_compat
  echo "=== Seeding Deck-enabled state ==="
  run_old_compat_seed "seed-deck.log" "$DEVICE_APK_PATH" "seedDeckEnabled"

  # 3. Install retirement APK
  install_retirement_and_verify

  # 4. Launch HOME to let migration complete naturally
  echo "=== Launching HOME to trigger migration ==="
  launch_home
  sleep 5

  # 5. Verify cleanup completed via new_typed capture
  echo "=== Verifying cleanup completed ==="
  local NEW_TYPED_LOG="$EVIDENCE_DIR/verify-cleanup.log"
  run_instrument "$NEW_TYPED_LOG" 'NEW_TYPED_READY typed=true' \
    -e deck_retirement_target_mode new_typed \
    -e class "${NEW_TYPED_CLASS}#captureAfterRestart" \
    "$TEST_RUNNER"
  rg -F -x 'OK (1 test)' "$NEW_TYPED_LOG"
  echo "Cleanup verified."

  # 6. Capture evidence after cleanup
  capture_evidence_entry "after-cleanup" "After migration cleanup completed"

  # 7. Downgrade to old APK
  downgrade_to_old

  # 8. Launch old HOME
  launch_home

  # 9. Capture with old_compat
  echo "=== Capturing under old binary ==="
  DEVICE_APK_PATH="$(get_device_apk_path)"
  run_old_compat_seed "old-capture.log" "$DEVICE_APK_PATH" "captureOnly"
  capture_evidence_entry "after-downgrade" "After downgrade to old APK"

  # 10. Oracle: active layout intact, no Deck restoration promise
  write_oracle "active layout intact, no Deck restoration promise" "Cleanup-complete downgrade evidenced. No Deck restoration promise."
  echo "=== Scenario downgrade-after-cleanup completed ==="
}

scenario_pre_initialization_old_binary() {
  echo "=== Scenario: pre-initialization-old-binary ==="
  local DEVICE_APK_PATH
  local SUPPORTED_MARKER=0

  # 1. Install old APK + test APK, verify
  install_old_and_verify
  DEVICE_APK_PATH="$(get_device_apk_path)"

  # 2. Seed Deck state and capture via old_compat
  echo "=== Seeding and capturing under old binary ==="
  run_old_compat_seed "seed-capture.log" "$DEVICE_APK_PATH" "seedAndCapture"
  capture_evidence_entry "pre-retirement" "Before retirement APK installation"

  # 3. Install retirement APK with -r but NEVER launch or instrument
  echo "=== Installing retirement APK (no launch) ==="
  adb -s "$SERIAL" install -r "$RETIREMENT_APK" >/dev/null
  echo "Retirement APK installed. NOT launching or initializing."

  # 4. Immediately downgrade
  downgrade_to_old

  # 5. Launch old HOME
  launch_home

  # 6. Capture with old_compat
  echo "=== Capturing under old binary after downgrade ==="
  DEVICE_APK_PATH="$(get_device_apk_path)"
  run_old_compat_seed "old-capture.log" "$DEVICE_APK_PATH" "captureOnly"
  capture_evidence_entry "after-downgrade" "After downgrade without initialization"

  # 7. Check for any evidence marking it supported
  if rg -q 'supported|normalized|cleanup' "$EVIDENCE_DIR/after-downgrade.evidence.txt" 2>/dev/null; then
    SUPPORTED_MARKER=1
  fi

  # 8. Oracle: unsupported boundary
  write_oracle "unsupported boundary recorded, no false pass" "Old binary downgrade before new-version initialization. No retirement state established."
  echo "=== Scenario pre-initialization-old-binary completed ==="

  # 9. Fail if any evidence marks it supported
  if test "$SUPPORTED_MARKER" -eq 1; then
    echo "ERROR: Evidence marks unsupported boundary as supported" >&2
    exit 1
  fi
}

scenario_pre_initialization_old_backup() {
  echo "=== Scenario: pre-initialization-old-backup ==="
  local DEVICE_APK_PATH
  local SUPPORTED_MARKER=0

  # 1. Install old APK + test APK, verify
  install_old_and_verify
  DEVICE_APK_PATH="$(get_device_apk_path)"

  # 2. Generate nonce for backup
  NONCE="$(generate_nonce)"
  echo "Nonce: $NONCE"

  # 3. Create backup under old binary via old_compat
  echo "=== Creating backup under old binary ==="
  run_old_compat_seed "create-backup.log" "$DEVICE_APK_PATH" "seedAndCreateBackup"
  # Pull and verify archive
  local ARCHIVE_FILE="$EVIDENCE_DIR/$NONCE.lawnchairbackup"
  adb -s "$SERIAL" exec-out run-as "$DEBUG_PACKAGE" cat "cache/logs/deck-retirement-backup/$NONCE.lawnchairbackup" > "$ARCHIVE_FILE"
  [[ -s "$ARCHIVE_FILE" ]] || { echo "ERROR: Archive file is empty" >&2; exit 1; }
  echo "Archive pulled: $ARCHIVE_FILE ($(wc -c < "$ARCHIVE_FILE") bytes)"

  # 4. Mutate state under old binary (different layout)
  echo "=== Mutating state under old binary ==="
  run_old_compat_seed "mutate-state.log" "$DEVICE_APK_PATH" "mutateLayout"

  # 5. Reinject archive and restore under old binary via old_compat
  echo "=== Reinjecting archive ==="
  adb -s "$SERIAL" exec-in run-as "$DEBUG_PACKAGE" sh -c \
    'mkdir -p cache/logs/deck-retirement-backup && cat > cache/logs/deck-retirement-backup/'"$NONCE"'.lawnchairbackup' < "$ARCHIVE_FILE"
  echo "=== Restoring backup under old binary ==="
  run_old_compat_seed "restore-backup.log" "$DEVICE_APK_PATH" "restoreBackup"

  # 6. Force-stop and relaunch old HOME
  echo "=== Force-stopping and relaunching old HOME ==="
  adb -s "$SERIAL" shell am force-stop "$DEBUG_PACKAGE"
  sleep 2
  launch_home

  # 7. Capture restored state under old binary
  echo "=== Capturing restored state under old binary ==="
  DEVICE_APK_PATH="$(get_device_apk_path)"
  run_old_compat_seed "restored-capture.log" "$DEVICE_APK_PATH" "captureOnly"
  capture_evidence_entry "backup-restored" "After backup restore under old binary"

  # 8. Install retirement APK with -r but NEVER launch or instrument
  echo "=== Installing retirement APK (no launch) ==="
  adb -s "$SERIAL" install -r "$RETIREMENT_APK" >/dev/null
  echo "Retirement APK installed. NOT launching or initializing."

  # 9. Immediately downgrade
  downgrade_to_old

  # 10. Launch old HOME
  launch_home

  # 11. Capture with old_compat
  echo "=== Capturing under old binary after downgrade ==="
  DEVICE_APK_PATH="$(get_device_apk_path)"
  run_old_compat_seed "old-final-capture.log" "$DEVICE_APK_PATH" "captureOnly"
  capture_evidence_entry "after-downgrade" "After downgrade without initialization (old backup)"

  # 12. Check for any evidence marking it supported
  if rg -q 'supported|normalized|cleanup' "$EVIDENCE_DIR/after-downgrade.evidence.txt" 2>/dev/null; then
    SUPPORTED_MARKER=1
  fi

  # 13. Oracle: unsupported boundary
  write_oracle "unsupported boundary recorded, no false pass" "Old backup restore before new-version initialization. No retirement state established."
  echo "=== Scenario pre-initialization-old-backup completed ==="

  # 14. Fail if any evidence marks it supported
  if test "$SUPPORTED_MARKER" -eq 1; then
    echo "ERROR: Evidence marks unsupported boundary as supported" >&2
    exit 1
  fi
}

# === Main dispatch ===

main() {
  parse_args "$@"
  preflight

  case "$SCENARIO" in
    rollback-before-cleanup)
      scenario_rollback_before_cleanup
      ;;
    downgrade-after-cleanup)
      scenario_downgrade_after_cleanup
      ;;
    pre-initialization-old-binary)
      scenario_pre_initialization_old_binary
      ;;
    pre-initialization-old-backup)
      scenario_pre_initialization_old_backup
      ;;
  esac

  echo ""
  echo "=== Downgrade smoke scenario '$SCENARIO' completed successfully ==="
  echo "Evidence directory: $EVIDENCE_DIR"
}

main "$@"