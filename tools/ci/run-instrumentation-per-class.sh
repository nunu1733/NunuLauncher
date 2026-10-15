#!/usr/bin/env bash

# Run each instrumentation scenario class in its OWN connected invocation.
#
# Why per-class (Issue #532 G4, replay-log specs/516-16-rebase-phase2 §6.4):
# the anchor build's Gradle built-in test runner parses
# `-Pandroid.testInstrumentationRunnerArguments...` as k=v,k=v pairs, so a
# comma-separated multi-class filter dispatches only its FIRST class and the
# remaining classes silently never run. Passing one class per invocation keeps
# the lane's class set authoritative without adding a lane; the caller stays
# responsible for the clean-emulator-per-lane contract (Issue #52/#53).
#
# Fail fast (same stage contract as run-restore-capture-instrumentation.sh):
# the enclosing run-emulator-command-with-failure-capture.sh wrapper observes
# the first failing class while the emulator is still alive.
#
# G5 (§6.12): each class invocation is bounded by a wall-clock cap. A hung
# class (e.g. a compose idling sync or an activity launch that never settles
# on the CI emulator) must fail the lane while the wrapper still owns the
# live emulator — the failure-time evidence capture runs on command failure,
# whereas a job-timeout kill leaves no evidence at all. The default cap is
# ~2x the slowest observed CI class (manual-org E2E at 11m15s in run
# 37280426374); override with PER_CLASS_TIMEOUT_MINUTES for one-off runs.
#
# Usage:
#   run-instrumentation-per-class.sh <class> [<class>...]

set -euo pipefail

PER_CLASS_TIMEOUT_MINUTES="${PER_CLASS_TIMEOUT_MINUTES:-20}"

if [ "$#" -eq 0 ]; then
    printf 'usage: %s <class> [<class>...]\n' "$0" >&2
    exit 2
fi

for class in "$@"; do
    printf 'per-class instrumentation run: %s\n' "$class"
    if command -v timeout >/dev/null 2>&1; then
        timeout --kill-after=60 "${PER_CLASS_TIMEOUT_MINUTES}m" \
            ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class="$class"
    else
        ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class="$class"
    fi
done
