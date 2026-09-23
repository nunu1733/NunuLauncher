#!/usr/bin/env bash

# Keep the emulator-runner script input to one command line while preserving
# the existing Issue 52 instrumentation and UI evidence pull sequence.

set -euo pipefail

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest#capturesIntegratedFacesAcrossDisplayConditions
mkdir -p build/issue52-ui-evidence
if ! adb logcat -d -v epoch -s Issue418LazyListTest:I Issue418LazyListState:I \
    > build/issue52-ui-evidence/issue418-lazylist-timeline.log; then
    printf 'Issue 418 state log capture failed; preserving the passing test result\n' >&2
    rm -f build/issue52-ui-evidence/issue418-lazylist-timeline.log || true
fi
adb pull /sdcard/Pictures/Issue52-ui-evidence build/issue52-ui-evidence
