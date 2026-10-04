#!/usr/bin/env bash

# Keep the manual-organization emulator-runner input to one wrapper command.
# The wrapper must observe both the instrumentation result and the existing UI
# evidence pull while the emulator is still alive.

set -euo pipefail

# Issue #477/#479 quarantine: the issue372 touch oracle skips in CI while
# #479 owns the Compose-level ghost-row anomaly it hits (the failure keeps a
# classified message and a failure-instant screenshot; local and diagnostic
# runs omit this argument and keep the oracle observable). Remove this line
# when #479 resolves.
quarantine_args=(-Pandroid.testInstrumentationRunnerArguments.nunuQuarantineIssue479TouchOracle=true)

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest "${quarantine_args[@]}" -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest,app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest,app.lawnchair.organizer.ui.OrganizerHubPreferencesInstrumentationTest,app.lawnchair.organizer.ui.StrategyPickerInstrumentationTest,app.lawnchair.organizer.ui.MissingAppSelectionInstrumentationTest,app.lawnchair.organizer.ui.UsageAccessJitInstrumentationTest,app.lawnchair.organizer.ui.exchange.ExchangeImportSuccessInstrumentationTest,app.lawnchair.ui.preferences.destinations.StrategyPickerFreezeInstrumentationTest,app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest,app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest,app.lawnchair.organizer.diagnostics.export.OrganizerDiagnosticsExportTimestampInstrumentationTest
mkdir -p build/manual-organization-ui-evidence
adb pull /sdcard/Pictures/Issue52-ui-evidence build/manual-organization-ui-evidence
