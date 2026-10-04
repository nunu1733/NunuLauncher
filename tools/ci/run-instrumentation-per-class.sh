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
# Usage:
#   run-instrumentation-per-class.sh <class> [<class>...]

set -euo pipefail

if [ "$#" -eq 0 ]; then
    printf 'usage: %s <class> [<class>...]\n' "$0" >&2
    exit 2
fi

for class in "$@"; do
    printf 'per-class instrumentation run: %s\n' "$class"
    ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class="$class"
done
