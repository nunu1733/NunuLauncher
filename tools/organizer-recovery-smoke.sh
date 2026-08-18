#!/usr/bin/env bash
# Issue #14 Stage B step 7: Host orchestrator for the organizer recovery smoke test.
# Installs debug/test APKs, starts one fault phase, waits for its test-only readiness
# log, force-stops, relaunches verify-only, and records typed result/lifecycle for
# SA-12/13/14/interrupted recovery. Uses explicit package/component names and fails
# on timeout.
#
# Spec §"Emulator recovery smoke scenario"; plan §"Emulator recovery smoke scenario".
#
# This script is a PR-evidence orchestrator. It does NOT introduce a runtime
# production hook. It requires an API 36.1 emulator with the debug/test APKs
# already built (or builds them inline).

set -euo pipefail

SERIAL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || { echo "--serial requires a value" >&2; exit 2; }
            SERIAL="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 --serial <api-36.1-emulator-serial>"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done
if [[ -z "$SERIAL" ]]; then
    echo "Usage: $0 --serial <api-36.1-emulator-serial>" >&2
    exit 2
fi

DEBUG_PACKAGE="app.lawnchair.debug"
TEST_PACKAGE="app.lawnchair.debug.test"
TEST_CLASS="app.lawnchair.organizer.application.OrganizerRecoveryInstrumentationTest"
READINESS_TAG="OrganizerRecoverySmoke"
FORCE_STOP_PHASES=("READY" "AROUND_COMMIT" "COMMITTED_UNVERIFIED" "RESTORING")
TIMEOUT_SECONDS=120

adb_cmd() {
    adb -s "$SERIAL" "$@"
}

wait_for_device() {
    echo "Waiting for device $SERIAL..."
    adb_cmd wait-for-device
    local boot_anim
    boot_anim="$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    local waited=0
    while [[ "$boot_anim" != "1" && $waited -lt $TIMEOUT_SECONDS ]]; do
        sleep 2
        waited=$((waited + 2))
        boot_anim="$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    done
    if [[ "$boot_anim" != "1" ]]; then
        echo "Device $SERIAL failed to boot within ${TIMEOUT_SECONDS}s" >&2
        exit 3
    fi
}

build_apks_if_missing() {
    local debug_apk=$(find build/outputs/apk/lawnWithQuickstepGithub/debug -name '*.apk' 2>/dev/null | head -1 || true)
    if [[ -z "$debug_apk" ]]; then
        echo "Debug APK missing; running assembleLawnWithQuickstepGithubDebug..."
        ./gradlew assembleLawnWithQuickstepGithubDebug
    fi
    local test_apk_dir="build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug"
    if [[ ! -d "$test_apk_dir" ]]; then
        echo "Test APK missing; running connectedLawnWithQuickstepGithubDebugAndroidTest..."
        ./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest
    fi
}

install_apks() {
    echo "Installing debug and test APKs..."
    ./gradlew installLawnWithQuickstepGithubDebug
    ./gradlew installLawnWithQuickstepGithubDebugAndroidTest
    echo "Clearing prior smoke state..."
    adb_cmd shell pm clear "$DEBUG_PACKAGE" >/dev/null
    adb_cmd shell pm clear "$TEST_PACKAGE" >/dev/null || true
    echo "Initializing the HOME model and default workspace..."
    adb_cmd shell am start -W -a android.intent.action.MAIN \
        -c android.intent.category.HOME -p "$DEBUG_PACKAGE" >/dev/null
    sleep 3
    adb_cmd shell am force-stop "$DEBUG_PACKAGE"
    echo "Verifying packages..."
    adb_cmd shell pm path "$DEBUG_PACKAGE" >/dev/null
    adb_cmd shell pm path "$TEST_PACKAGE" >/dev/null
}

run_fault_phase() {
    local phase="$1"
    local output_file="build/organizer-smoke-$phase.out"
    echo "=== Running fault phase: $phase ==="
    adb_cmd logcat -c
    adb_cmd shell am instrument -w -e class "$TEST_CLASS" \
        -e organizerFaultPhase "$phase" \
        -e organizerMode "FAULT_INJECTION" \
        "$TEST_PACKAGE/app.lawnchair.migration.DeckRetirementTestRunner" >"$output_file" 2>&1 &
    local instrument_pid=$!

    echo "Waiting for readiness marker $READINESS_TAG phase=$phase..."
    local waited=0
    while [[ $waited -lt $TIMEOUT_SECONDS ]]; do
        if grep -q "$READINESS_TAG.*PAUSED phase=$phase.*typed=true" "$output_file"; then
            echo "Readiness marker observed at ${waited}s."
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    if [[ $waited -ge $TIMEOUT_SECONDS ]]; then
        echo "Timed out waiting for readiness marker for phase $phase" >&2
        kill $instrument_pid 2>/dev/null || true
        return 4
    fi

    echo "Force-stopping $DEBUG_PACKAGE to simulate process death..."
    adb_cmd shell am force-stop "$DEBUG_PACKAGE"
    wait $instrument_pid 2>/dev/null || true
}

EVIDENCE_DIR="build/organizer-smoke-evidence"

run_verify_only() {
    local phase="$1"
    local verify_file="$EVIDENCE_DIR/$phase.verify.txt"
    echo "=== Verify-only for phase: $phase ==="
    local output
    output="$(adb_cmd shell am instrument -w -e class "$TEST_CLASS" \
        -e organizerFaultPhase "$phase" \
        -e organizerMode "VERIFY_ONLY" \
        "$TEST_PACKAGE/app.lawnchair.migration.DeckRetirementTestRunner")"
    printf '%s\n' "$output" > "$verify_file"
    printf '%s\n' "$output"
    grep -q "OK (1 test)" "$verify_file"
    grep -q "$READINESS_TAG.*VERIFIED phase=$phase.*typed=true" "$verify_file"
}

record_evidence() {
    local phase="$1"
    local ts=$(date +%Y%m%d-%H%M%S)
    local verify_file="$EVIDENCE_DIR/$phase.verify.txt"
    if [[ ! -s "$verify_file" ]]; then
        echo "Typed verify evidence for phase $phase is missing or empty: $verify_file" >&2
        return 5
    fi
    if ! grep -q "$READINESS_TAG.*VERIFIED phase=$phase.*typed=true" "$verify_file"; then
        echo "Typed verify evidence for phase $phase lacks VERIFIED marker: $verify_file" >&2
        return 5
    fi
    echo "=== Formal typed evidence: $phase ==="
    cat "$verify_file"
    local log_file="$EVIDENCE_DIR/$phase-$ts.log"
    local log_lines
    log_lines="$(adb_cmd logcat -d 2>/dev/null | grep -E "OrganizerRecovery|LifecycleReconciler|RecoveryStore" || true)"
    if [[ -n "$log_lines" ]]; then
        printf '%s\n' "$log_lines" > "$log_file"
        echo "Lifecycle diagnostic: $log_file"
    else
        echo "Lifecycle diagnostic: not available (no matching logcat lines)"
    fi
}

main() {
    wait_for_device
    build_apks_if_missing
    install_apks
    mkdir -p "$EVIDENCE_DIR"
    for phase in "${FORCE_STOP_PHASES[@]}"; do
        run_fault_phase "$phase"
        run_verify_only "$phase"
        record_evidence "$phase"
    done
    echo "Smoke scenario complete."
}

main "$@"
