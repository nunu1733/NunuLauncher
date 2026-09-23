#!/usr/bin/env bash

# Keep the emulator-runner script input to one command line while preserving
# the existing Issue 52 instrumentation and UI evidence pull sequence.

set -euo pipefail

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest,app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest,app.lawnchair.organizer.ui.OrganizerHubPreferencesInstrumentationTest,app.lawnchair.organizer.ui.StrategyPickerInstrumentationTest,app.lawnchair.organizer.ui.MissingAppSelectionInstrumentationTest,app.lawnchair.organizer.ui.UsageAccessJitInstrumentationTest,app.lawnchair.organizer.ui.exchange.ExchangeImportSuccessInstrumentationTest,app.lawnchair.ui.preferences.destinations.StrategyPickerFreezeInstrumentationTest,app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest
mkdir -p build/issue52-ui-evidence
adb pull /sdcard/Pictures/Issue52-ui-evidence build/issue52-ui-evidence
