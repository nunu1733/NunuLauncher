#!/usr/bin/env bash

# Keep the emulator-runner script input to one command line while preserving
# the existing Issue 52 instrumentation and UI evidence pull sequence.

set -euo pipefail

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest#capturesIntegratedFacesAcrossDisplayConditions
mkdir -p build/issue52-ui-evidence
adb pull /sdcard/Pictures/Issue52-ui-evidence build/issue52-ui-evidence
