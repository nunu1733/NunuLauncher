#!/usr/bin/env bash

# Keep the production-input emulator-runner input to one wrapper command. The
# wrapper must observe the first failing stage while the emulator is still
# alive and return the original stage status. The restart writer/reader pair
# keeps its tee + grep oracle: the `am instrument` output is streamed to a
# file and both the framework OK line and the INSTRUMENTATION_CODE marker are
# required (pipefail lets the instrument status itself fail the stage).

set -euo pipefail

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
