# High-risk audit: PR #133 preserve two-panel orientation in organizer canonical capture

> Status: accepted
> Audit date: 2026-08-24

- Auditor: independent session (general-purpose subagent; implementer was the main session)
- PR: https://github.com/nunu1733/NunuLauncher/pull/133
- Head SHA: d639fc735c8e06f24e8b8364cc1368fa36926609
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32701200140
- Criteria: specs/130-two-panel-orientation-capture/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8

## Scope

This audit covers PR #133 (`risk: layout-data`, `Closes #130`) at head
`d639fc735c8e06f24e8b8364cc1368fa36926609` on branch
`issue-130-two-panel-orientation-capture`, with a clean working tree verified
before and after the audit. The audited diff consists of commits `a16c8ff34d`,
`cd83df70c1`, and `d639fc735c`, plus spec status flip `d558f4e466`:

- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
- `tests/unit/app/lawnchair/organizer/application/adapter/CanonicalOrientationTest.kt`
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`
- `.github/workflows/ci.yml`
- `specs/130-two-panel-orientation-capture/spec.md`, `specs/130-two-panel-orientation-capture/plan.md`

The change can affect persisted layout state because the organizer canonical
revision is computed over the captured `DeviceCapabilities`, whose
`orientation` value is one of its inputs. `capabilities()` previously mapped
every host to `PORTRAIT`/`LANDSCAPE` from `Configuration.orientation` alone;
it now derives two-panel-ness from the constructed current
`DeviceProfile.isTwoPanels` via `InvariantDeviceProfile.getDeviceProfile(context)`
and maps `(isTwoPanels, configurationOrientation)` to
`TWO_PANEL_PORTRAIT`/`TWO_PANEL_LANDSCAPE` through the new internal pure
function `canonicalOrientation`. On a host where this flag differs from the
old mapping's assumption, the captured orientation ordinal changes, which
flows into the `CanonicalMarshalling` digest/revision, the recovery
`DEVICE_PROFILE` resource, and planner input. No schema, migration, enum
ordering, write-path, or recovery-mechanism change is present; phone/tablet
hosts keep identical ordinals.

## Criteria check

- **AC-1 / AC-2 — satisfied via spec Test oracle path (2), not via real
  two-panel host evidence.** Real two-panel capture evidence was NOT obtained.
  I independently re-verified the negative-result claim in source at the
  audited head: `DisplayController.Info.getDeviceType()`
  (`src/com/android/launcher3/util/DisplayController.java`) returns
  `TYPE_MULTI_DISPLAY` only when `supportedBounds` contains both phone-mode
  (<600dp) and tablet-mode (>=600dp) bounds; on an unexpected normalized
  display info after a posture change the constructor REPLACES the
  `perDisplayBounds` cache (`mPerDisplayBounds.clear()` plus
  `wmProxy.estimateInternalDisplayBounds(...)`, logged as `(Invalid Cache)`,
  ~lines 410–421), and `WindowManagerProxy.estimateInternalDisplayBounds`
  (`src/com/android/launcher3/util/window/WindowManagerProxy.java`) returns a
  single-entry map for the current posture only. Fold transitions therefore
  cannot accumulate both modes' bounds on these emulators, so
  `TYPE_MULTI_DISPLAY`/`isTwoPanels=true` is unreachable there. The chain end
  points were also confirmed: all supported profiles are built with
  `.setIsMultiDisplay(deviceType == TYPE_MULTI_DISPLAY)`
  (`src/com/android/launcher3/InvariantDeviceProfile.java`) and
  `DeviceProfile.isTwoPanels = isMultiDisplay`
  (`src/com/android/launcher3/DeviceProfile.java`). Per oracle (2), evidence
  is the authority-consistency instrumentation plus the pure mapping proof,
  and no test rewrites the judgment input in isolation (see AC-6). The
  residual limitation (no real two-panel runtime cell) remains recorded for
  the Issue #108 matrix as the spec requires.
- **AC-3 — pass.** `capturedOrientationMatchesConstructedDeviceProfileAuthority`
  asserts on real hosts that the captured orientation's two-panel-ness equals
  the actually constructed `DeviceProfile.isTwoPanels`, and for non-two-panel
  hosts that the captured value equals the `Configuration.orientation`-derived
  `PORTRAIT`/`LANDSCAPE`. Verified green on both local emulators (API 35 and
  API 36 foldable AVD, both of which are non-two-panel per the source analysis).
- **AC-4 — pass.** `productionComposerPreservesCapturedOrientationIntoPlannerInput`
  asserts `ProductionOrganizationInputComposer.composeFullOrganization()` =
  `Ready` with `input.snapshot.device.orientation` equal to the captured
  orientation name. Verified green on both emulators.
- **AC-5 — pass.** `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`
  captures at O_A, prepares an update plan against that revision, performs a
  REAL rotation (launcher foreground + `forceAllowRotationForTesting` +
  `accelerometer_rotation=0` + `user_rotation`), waits for the host
  configuration to report O_B, runs `reconcileAtStart()` then `apply(plan)`,
  and asserts `ApplyResult.Rejected` with `PreWriteRejection.STALE_REVISION`.
  No-write is asserted on the plan's own row (pre/post row equality by `_ID`)
  plus marker-title absence ("orientation-stale" must not exist), matching the
  refined AC-5 oracle. Verified green on both emulators, including a genuine
  portrait→landscape transition.
- **AC-6 — pass.** The harness uses the production seam
  (`LauncherLayoutAdapter` over the live launcher model) and the same launcher
  device-profile authority; it reads (never rewrites)
  `InvariantDeviceProfile.getDeviceProfile(context).isTwoPanels` for the
  consistency assertion. No test sets `deviceType`, `isMultiDisplay`,
  `isTwoPanels`, or any reflection/test hook that would alter the two-panel
  judgment input alone; rotation changes only the legitimate second input
  (`configuration.orientation`) through real system state, which AC-5
  explicitly requires. Runtime/harness method, consistency-check results, and
  the residual device limitation are recorded here and in the spec change
  history.
- **AC-7 — pass.** All commands below succeeded locally on the audited head,
  and GitHub Actions run `32701200140` (event=`pull_request`, PR #133,
  head SHA = audited head, branch = `issue-130-two-panel-orientation-capture`)
  concluded `success` with every job successful, including `final-status`,
  `organizer-unit-tests`, `check-style`, `build-debug-apk`,
  `validate-repo-contract`, and `organizer-instrumentation-api35-tests` whose
  class list now includes
  `app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest`
  (verified at `.github/workflows/ci.yml` line 336).
- **AC-8 — pass.** PR #133 carries the `risk: layout-data` label (verified via
  API) and this independent audit record provides the required evidence from a
  session that did not implement the change.

## Executed test surface

Independent checks executed by this audit session on head
`d639fc735c8e06f24e8b8364cc1368fa36926609`:

```text
git status && git rev-parse HEAD && git branch --show-current
  PASS — clean tree, HEAD d639fc735c8e06f24e8b8364cc1368fa36926609, correct branch

./gradlew spotlessCheck
  PASS — BUILD SUCCESSFUL in 4s (run separately; see Findings)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
  PASS — BUILD SUCCESSFUL in 27s; 61 organizer result classes, 0 failures;
         TEST-app.lawnchair.organizer.application.adapter.CanonicalOrientationTest.xml:
         tests="2" skipped="0" failures="0" errors="0" (covers all 4 mapping branches)

./gradlew assembleLawnWithQuickstepGithubDebug
  PASS — BUILD SUCCESSFUL

./gradlew compileLawnWithQuickstepGithubDebugAndroidTestKotlin
  PASS — BUILD SUCCESSFUL

gh api repos/nunu1733/NunuLauncher/actions/runs/32701200140/jobs?per_page=100 --jq '.jobs[] | "\(.name) \(.conclusion)"'
  PASS — all 12 jobs success: changes, organizer-unit-tests,
         organizer-instrumentation-api35-tests,
         organizer-instrumentation-db-migration-tests, build-debug-apk,
         organizer-instrumentation-issue99-tests,
         organizer-instrumentation-issue52-tests,
         organizer-instrumentation-issue53-tests,
         organizer-instrumentation-shared-writer-tests, check-style,
         validate-repo-contract, final-status
gh api repos/nunu1733/NunuLauncher/actions/runs/32701200140 --jq '{head_sha, event, conclusion, ...}'
  PASS — head_sha=d639fc735c8e06f24e8b8364cc1368fa36926609, event=pull_request,
         pull_requests=[133], conclusion=success

ANDROID_SERIAL=emulator-5554 ./gradlew installLawnWithQuickstepGithubDebug installLawnWithQuickstepGithubDebugAndroidTest
  PASS — "Installed on 1 device." twice (debug APK + test APK), BUILD SUCCESSFUL

adb -s emulator-5554 shell am instrument -w -r -e class app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  PASS — OK (3 tests) on API 15/API 35 AVD api35-test, Time: 14.128s

ANDROID_SERIAL=emulator-5556 ./gradlew installLawnWithQuickstepGithubDebug installLawnWithQuickstepGithubDebugAndroidTest
  PASS — "Installed on 1 device." twice, BUILD SUCCESSFUL

adb -s emulator-5556 shell am instrument -w -r -e class app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
  PASS — OK (3 tests) on API 16/API 36 AVD issue108_api36_pixel_9_pro_fold, Time: 25.86s
```

Both emulators were already running locally (`adb devices`: emulator-5554 =
API 35 phone `api35-test`, emulator-5556 = API 36 Pixel 9 Pro Fold
`issue108_api36_pixel_9_pro_fold`); gradle install success was confirmed via
"Installed on 1 device." before trusting the instrument results.

## Findings

No blocking finding was identified. Notable observations recorded for the
merge-gate record:

- **CI flake history before the final green run.** Branch run history shows
  failed attempts before `32701200140`: at `cd83df70c1`, run `32700192211`
  failed ALL emulator instrumentation lanes simultaneously
  (`organizer-instrumentation-{api35,db-migration,issue99,issue52,issue53,shared-writer}-tests`),
  while the parallel rerun `32700192217` of the same commit passed them and
  failed only `high-risk-evidence`; other attempts failed only
  `high-risk-evidence` (the gate correctly requiring this audit record). This
  pattern indicates shared-emulator infrastructure flakiness across lanes, not
  a defect in the added class; the audited run is fully green on the exact
  audited head.
- **Launcher-side writes during rotation required a targeted no-write oracle.**
  During CI, orientation-change relayout was observed to make the launcher
  itself (not the organizer apply path) rewrite placement/`modified` of folder
  child rows, so whole-table equality would be flaky for reasons outside this
  change. AC-5's oracle was therefore refined (commit `cd83df70c1`) to assert
  no-write via pre/post equality of the plan's own row plus marker-title
  absence; the underlying contract "a rejected apply performs no write" is
  unchanged.
- **Recovery-inspection inventory cleanup is required for test isolation.**
  Deleting only the recovery database leaves the startup classifier in
  `SuspiciousAbsence`, which fail-closes reconciliation for subsequent tests;
  the fixture consequently removes both `RecoveryDbSchema.FILE_NAME` and the
  `RecoveryInspectionSnapshotReader.DIRECTORY_NAME` inventory under
  `noBackupFilesDir` in `@Before`/`@After`.
- **Pre-existing Gradle issue with combined invocations.** Combining spotless
  tasks with compile tasks in one invocation fails regardless of this diff due
  to a Gradle implicit-dependency validation issue between `spotlessJava` and
  `compatLib:compileDebugAidl`; this audit ran each verification command in a
  separate invocation, and all passed.
- **Residual limitation (pre-existing, out of scope).** Real two-panel
  (`TYPE_MULTI_DISPLAY`) capture evidence was not obtained and is not
  obtainable on the available emulators given the `DisplayController` cache
  replacement semantics verified above; AC-1/AC-2 rest on Test oracle path (2)
  (authority-consistency check + pure mapping proof). The residual limitation
  stays tracked in the Issue #108 matrix per the spec.
