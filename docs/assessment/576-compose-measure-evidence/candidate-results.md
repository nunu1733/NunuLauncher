# Issue #576 candidate arm: 5/5 PASS receipts

> Arm: baseline source plus the LOCAL overlay in `candidate-overlay.diff`
> (one line: `publicationThread = HandlerRunPublicationThread()` in the target
> runner fixture). Overlay SHA-256 identical all 5 runs:
> `2430febde1a09a5d496186f5fc343705587e2ddeb5b0f44093239bfe20ceaddd`.
> Head `2a6bcb3188c2ceecd6e5f606d66aa1bd98edfa04`, test blob
> `ca11bcc664c0d78df2836f2f582e7c57a35df0a9` (all 5 runs).
> Prod APK same as baseline (all 5): `746d77b0...aa867715f7`. Test APK (all 5):
> `82d7c954...b36e4` (full hashes below).
> Gradle exit `0` all 5. XML `tests=1 failures=0 errors=0 skipped=0` all 5.
> Overlay restored after the experiment: `git diff --exit-code -- tests lawnchair/src` passes.

## candidate-1

- start/end/exit: `2026-10-15T08:34:52Z` / `2026-10-15T08:37:41Z` / `0`
- APK SHA-256:
  - `746d77b02d10302c0f64a9152171f32f8cf9e4cf609c167fd41814aa867715f7  build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.16.Dev.(2a6bcb3).github.debug.apk`
  - `82d7c954cfe9a61a815d7e4053adddc2fe7db83fb6fad71ab8e19ceb0ebb36e4  build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/lawnchair-lawn-withQuickstep-github-debug-androidTest.apk`
- XML SHA-256 `1d2cc0a53db6c199bec71cd9c9bb6765545b23041bc2157761fb8ada0393abb6`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:37:29" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="20.702" timestamp="2026-10-15T08:37:29" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="20.702" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `eedbb79fdb60ffc1bee1ac9c95d69aa59a8b370838f7771b2da0e4b050e11178`; verbatim build line: `BUILD SUCCESSFUL in 2m 47s`
- Scoped per-test logcat SHA-256 `0708a59e96b4f2404f22a9bd08c0ecfcf80a518bc427e3fdf2a2bf9b4dcf7ff3` (1593 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## candidate-2

- start/end/exit: `2026-10-15T08:37:50Z` / `2026-10-15T08:39:39Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `82d7c954...b36e4` (identical to run 1)
- XML SHA-256 `a8145e77abc79592dcb870c946f52f24ea6dd48f1dbd4e27cf1717c2392049fb`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:39:25" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="19.606" timestamp="2026-10-15T08:39:25" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="19.606" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `fe7deff75517cd3481fbcc834c0e6b9f113988ca5c6cc46e533842fb8575b2eb`; verbatim build line: `BUILD SUCCESSFUL in 1m 45s`
- Scoped per-test logcat SHA-256 `0021337ec15a712c784be00eaa2fc8cb97477c161c8cc7d94c5570926e368dcd` (2052 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## candidate-3

- start/end/exit: `2026-10-15T08:39:53Z` / `2026-10-15T08:41:10Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `82d7c954...b36e4` (identical to run 1)
- XML SHA-256 `824ce1da97614a7090f663dcb293f09498175e61427b8c3a5626e39df6296016`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:40:58" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="14.438" timestamp="2026-10-15T08:40:58" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="14.438" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `c2bbae808ccfea6dc31e0f3642e75eeaaf17b57b4a3c0eabf3227594ff355e4c`; verbatim build line: `BUILD SUCCESSFUL in 1m 15s`
- Scoped per-test logcat SHA-256 `b63d09ca7ef0c928e2ca4e219a5f0efcfb4e54c3e1224a373a8b4ae460eb9971` (492 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## candidate-4

- start/end/exit: `2026-10-15T08:41:16Z` / `2026-10-15T08:42:35Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `82d7c954...b36e4` (identical to run 1)
- XML SHA-256 `9d87c1fce87c89bde0eac139fc61fcd51464c14d5ff7c87d315b5bf95a015054`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:42:26" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="21.714" timestamp="2026-10-15T08:42:26" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="21.714" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `999c688115c147688de7d037e890205ec815e6601997f04028405f816a24f47f`; verbatim build line: `BUILD SUCCESSFUL in 1m 17s`
- Scoped per-test logcat SHA-256 `605b2d5de7e67a135100e70d0a70c9a88f04609608e38bb79bf86759a1301401` (2021 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## candidate-5

- start/end/exit: `2026-10-15T08:42:44Z` / `2026-10-15T08:43:57Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `82d7c954...b36e4` (identical to run 1)
- XML SHA-256 `165bf1b0ac0025e2655050f529d9ed0ba8a63ef65bed5ef65ec6deee24e4a1ab`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:43:49" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="16.789" timestamp="2026-10-15T08:43:49" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="16.789" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `856aa7e4bd27b3a0d571f34c917cc7b567ab1abd3dee56dca6a86bf11b225067`; verbatim build line: `BUILD SUCCESSFUL in 1m 12s`
- Scoped per-test logcat SHA-256 `ad24433a39385a42e281ccf113229b7f2462ce67669ef2eddbc874ee4d8da731` (449 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## Arm reading

No `IndexOutOfBoundsException`, no LazyList measurement failure in any of the
5 runs. Both arms had same observed test outcome; no failure reproduced; no
claim that no behavior or timing changed. Timing differences across runs carry
no causal claim (sequential order, no counterbalancing).
