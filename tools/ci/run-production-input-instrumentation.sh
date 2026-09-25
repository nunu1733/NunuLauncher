#!/usr/bin/env bash

# Keep the production-input emulator-runner input to one wrapper command. The
# wrapper must observe the first failing stage while the emulator is still
# alive and return the original stage status. The restart writer/reader pair
# keeps its tee + grep oracle and its pre-#438 failure semantics: pipefail is
# deliberately NOT set, so `am instrument | tee` is judged by tee's status and
# the following `grep -q` checks remain the failure detector with status 1,
# exactly as the previous per-line `sh -c` runner script behaved.

set -eu

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest,app.lawnchair.organizer.rules.CategoryOverrideAtomicFileInstrumentationTest,com.android.launcher3.organizer.NestedTransactionTest,app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest
./gradlew installLawnWithQuickstepGithubDebug installLawnWithQuickstepGithubDebugAndroidTest
adb shell am force-stop app.lawnchair.debug.test
adb shell am force-stop app.lawnchair.debug
adb shell am instrument -w -r -e class app.lawnchair.organizer.rules.CategoryOverrideAtomicFileRestartWriterInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner | tee /tmp/category-override-restart-writer.txt
grep -q 'OK (1 test)' /tmp/category-override-restart-writer.txt
grep -q 'INSTRUMENTATION_CODE: -1' /tmp/category-override-restart-writer.txt
adb shell am force-stop app.lawnchair.debug.test
adb shell am force-stop app.lawnchair.debug
adb shell am instrument -w -r -e class app.lawnchair.organizer.rules.CategoryOverrideAtomicFileRestartReaderInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner | tee /tmp/category-override-restart-reader.txt
grep -q 'OK (1 test)' /tmp/category-override-restart-reader.txt
grep -q 'INSTRUMENTATION_CODE: -1' /tmp/category-override-restart-reader.txt
