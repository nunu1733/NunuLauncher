#!/usr/bin/env bash

# Keep the manual-organization emulator-runner input to one wrapper command.
# The wrapper must observe both the instrumentation result and the existing UI
# evidence pull while the emulator is still alive.

set -euo pipefail

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest,app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest,app.lawnchair.organizer.ui.OrganizerHubPreferencesInstrumentationTest,app.lawnchair.organizer.ui.StrategyPickerInstrumentationTest,app.lawnchair.organizer.ui.MissingAppSelectionInstrumentationTest,app.lawnchair.organizer.ui.UsageAccessJitInstrumentationTest,app.lawnchair.organizer.ui.exchange.ExchangeImportSuccessInstrumentationTest,app.lawnchair.ui.preferences.destinations.StrategyPickerFreezeInstrumentationTest,app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest
mkdir -p build/manual-organization-ui-evidence
adb pull /sdcard/Pictures/Issue52-ui-evidence build/manual-organization-ui-evidence
