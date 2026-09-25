#!/usr/bin/env bash

# Keep the restore-capture emulator-runner input to one wrapper command. The
# wrapper must observe the first failing stage while the emulator is still
# alive and return the original stage status. The stage order is normative
# (docs/assessment/issue-299-nova-restore-capture-invalid.md): each scenario
# class runs in its OWN connected invocation, and the cross-process pair needs
# manual installs plus `am instrument` with a force-stop process death between
# the two stages.

set -euo pipefail

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureControlTest
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureWidgetWindowTest
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureUnknownProviderTest
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaRestoreCaptureNoCallbacksTest
# Gradle uninstalls both APKs (wiping app data) after each connected run, so
# the cross-process pair installs them manually and drives the two stages with
# `am instrument` + force-stop: stage A restores (its completion barrier
# settles the repair before return), the force-stop is the process death, and
# stage B must observe the persisted repaired DB in a fresh process before any
# model reload runs.
./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest
adb install -r -d build/outputs/apk/lawnWithQuickstepGithub/debug/*.apk
adb install -r build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/*.apk
adb shell am instrument -w -e class app.lawnchair.backup.NovaRestoreCaptureCrossProcessStageATest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
adb shell am force-stop app.lawnchair.debug
adb shell am instrument -w -e class app.lawnchair.backup.NovaRestoreCaptureCrossProcessStageBTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner

# Temporary controlled failure probe for Issue #438 AC-5 evidence: the real
# sequence above runs first; failing here exercises live failure capture from
# inside the runner in this formerly runner-external lane. Reverted before
# merge.
printf 'issue438 controlled failure probe: real stages passed; failing to exercise live failure capture\n' >&2
exit 7
