#!/usr/bin/env bash

# Keep the manual-organization emulator-runner input to one wrapper command.
# The wrapper must observe both the instrumentation result and the existing UI
# evidence pull while the emulator is still alive.

set -euo pipefail

# The drag-guard oracle runs in its OWN instrumentation invocation: its
# real system-level drag injection disturbs the key/focus delivery of the
# tests that follow within the same instrumentation process (reproduced 3/3
# CI runs; bisected against the #493 baseline and an oracle-ignored variant
# of this lane — Issue #479 review round 3).
bash tools/ci/run-instrumentation-per-class.sh \
  app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest \
  app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest \
  app.lawnchair.organizer.ui.OrganizerHubPreferencesInstrumentationTest \
  app.lawnchair.organizer.ui.StrategyPickerInstrumentationTest \
  app.lawnchair.organizer.ui.MissingAppSelectionInstrumentationTest \
  app.lawnchair.organizer.ui.UsageAccessJitInstrumentationTest \
  app.lawnchair.organizer.ui.exchange.ExchangeImportSuccessInstrumentationTest \
  app.lawnchair.ui.preferences.destinations.StrategyPickerFreezeInstrumentationTest \
  app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest \
  app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest \
  app.lawnchair.organizer.diagnostics.export.OrganizerDiagnosticsExportTimestampInstrumentationTest
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.OrganizerHubDragGuardInstrumentationTest
mkdir -p build/manual-organization-ui-evidence
adb pull /sdcard/Pictures/Issue52-ui-evidence build/manual-organization-ui-evidence
