# Issue #576 baseline arm: 5/5 PASS receipts

> Arm: exact baseline source (overlay empty, 0 bytes all runs). Head
> `2a6bcb3188c2ceecd6e5f606d66aa1bd98edfa04`, test blob
> `075e5c1c2e1dc9f5197361d1a9595dcbc66c35c9` (all 5 runs).
> Prod APK (all 5): `746d77b0...aa867715f7`. Test APK (all 5):
> `ba82c462...90168ec2394f8` (full hashes below).
> Gradle exit `0` all 5. XML `tests=1 failures=0 errors=0 skipped=0` all 5.

## baseline-1

- start/end/exit: `2026-10-15T08:15:24Z` / `2026-10-15T08:20:45Z` / `0`
- APK SHA-256:
  - `746d77b02d10302c0f64a9152171f32f8cf9e4cf609c167fd41814aa867715f7  build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.16.Dev.(2a6bcb3).github.debug.apk`
  - `ba82c46287579945f1d806847949ac0c0389a08e1b6658d495e90168ec2394f8  build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/lawnchair-lawn-withQuickstep-github-debug-androidTest.apk`
- XML (`results/TEST-nunu_issue576_api36_20261015(AVD) - 16.xml`), SHA-256 `97c5b8bc13bcb50dd9a63226175e752ea0a803737495015002a9fb5d76e10e27`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:20:23" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="46.135" timestamp="2026-10-15T08:20:23" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="46.135" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `c6641016ef3956e3ac5313af7266459bfc8b7dcf7adab7168e73c293a7c90ca4`; verbatim build line: `BUILD SUCCESSFUL in 5m 15s`
- Scoped per-test logcat SHA-256 `d6d588cee9cbb3bb697bd3896e11497686aba8299907382127c7f398f28436e3` (2489 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## baseline-2

- start/end/exit: `2026-10-15T08:21:21Z` / `2026-10-15T08:24:22Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `ba82c462...90168ec2394f8` (identical to run 1)
- XML SHA-256 `3f2b587f8f79a782649281001b656e85b30eaa2360c323197930ad885f2be4c4`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:24:03" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="30.802" timestamp="2026-10-15T08:24:03" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="30.802" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `01749bbbd559005c5024b47ff16fa2033c4ef7f5cc82159be8d6f2ef747a11ed`; verbatim build line: `BUILD SUCCESSFUL in 2m 57s`
- Scoped per-test logcat SHA-256 `0eaf97e7d4adffc1c9cc3c155006df66e4cd6d53fca819963ab1121a3e462811` (1023 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## baseline-3

- start/end/exit: `2026-10-15T08:24:35Z` / `2026-10-15T08:26:58Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `ba82c462...90168ec2394f8` (identical to run 1)
- XML SHA-256 `ee1576e3a615a36a21e818252937c360299be2e79c06de6be3dfa4828a526d32`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:26:41" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="24.107" timestamp="2026-10-15T08:26:41" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="24.107" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `2bf73164f5937d58f9f6efa5ecb4386713e24b46340809edf3e34c0af82d6a39`; verbatim build line: `BUILD SUCCESSFUL in 2m 21s`
- Scoped per-test logcat SHA-256 `f3839d4be450a86d84ed3e1361ad3807122c96caff34893bec064b7fe170caf5` (1300 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## baseline-4

- start/end/exit: `2026-10-15T08:27:08Z` / `2026-10-15T08:28:53Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `ba82c462...90168ec2394f8` (identical to run 1)
- XML SHA-256 `e36f1f01a09b75cf2789ed2ea1717237ad118c9685d57eb6770bfe70f4beb1e0`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:28:40" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="30.086" timestamp="2026-10-15T08:28:40" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="30.086" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `774e65e1ed4abc924b39b9376d5ebccc01beff32a7d69224b99ecf68bbe8ef59`; verbatim build line: `BUILD SUCCESSFUL in 1m 42s`
- Scoped per-test logcat SHA-256 `e0198f0b809cbf5e88271fecf4b5ce8e085881675ed81245c260197fe3395dac` (575 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## baseline-5

- start/end/exit: `2026-10-15T08:29:11Z` / `2026-10-15T08:30:54Z` / `0`
- APK SHA-256: prod `746d77b0...aa867715f7`, test `ba82c462...90168ec2394f8` (identical to run 1)
- XML SHA-256 `1f482b2dfcabef23cf6289b038f3ba0c7d6fb30bb6da65f10f23960df537f14b`:

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="1" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-10-15T08:30:43" hostname="localhost">
  <testsuite name="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" tests="1" failures="0" errors="0" skipped="0" time="21.541" timestamp="2026-10-15T08:30:43" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5558" />
    </properties>
    <testcase name="staleApplyAttemptExplainsOutcomeAndNextStep" classname="app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest" time="21.541" />
  </testsuite>
</testsuites>
```

- `gradle.log` SHA-256 `68fba68080384ee46f6a0527c71afb866b276e8c4238cb42d446fcfc45adc306`; verbatim build line: `BUILD SUCCESSFUL in 1m 40s`
- Scoped per-test logcat SHA-256 `baee3ca860927b4136658f681115ecbc2f7fe66ffc81d112be07a4c05948edbe` (501 lines):
  - `TestRunner: started: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`
  - `TestRunner: finished: staleApplyAttemptExplainsOutcomeAndNextStep(app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest)`

## Arm reading

No `IndexOutOfBoundsException`, no LazyList measurement failure in any of the
5 runs. Times differ across runs (46.1s down to 21.5s); order was sequential
with no counterbalancing, so timing differences carry no causal claim.
