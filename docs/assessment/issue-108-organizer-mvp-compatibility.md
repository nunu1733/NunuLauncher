# Organizer MVP compatibility evidence matrix

> Status: Executed (final matrix; remaining cells explicitly limited)
> Issue: [#108](https://github.com/nunu1733/NunuLauncher/issues/108)
> Requirement: NFR-007
> Production baseline: `51940f3dfc4b9308f7c9e7101c2c7cda81f16da7` plus merged fixes
> [#129](https://github.com/nunu1733/NunuLauncher/issues/129) (`8316333347`) and
> [#130](https://github.com/nunu1733/NunuLauncher/issues/130) (`534d0f32db`)
> Verified: 2026-08-24

## Purpose and support boundary

This assessment defines the compatibility cells claimed by the organizer MVP
on the adopted Lawnchair `v15.0.0-beta3.0` baseline. It does not introduce a
second planning or application path. Every device run uses the production
`LauncherLayoutAdapter`, `ProductionOrganizationInputComposer`, and, where the
cell exercises mutation, `ManualOrganizationRun` through the production layout
application and recovery module.

The build fixes `minSdk 26`, `targetSdk 35`, and `compileSdk 36.1`. These
values do not define a closed runtime-support range: `minSdk` sets the
installable lower boundary, `targetSdk` selects compatibility behavior, and
`compileSdk` selects build-time APIs. NFR-007 requires device/API coverage of
the surface supported by the adopted Lawnchair revision, but no accepted
repository source currently closes an upper API boundary. Evidence is
therefore separated from support policy:

- API 26 has full organizer-host execution evidence (capture, composition,
  manual apply→verify→recover) obtained on this date, with recorded
  environment limitations on constrained emulation.
- API 35 has production-input Launcher-host evidence.
- API 36/36.1 has manual, application, onboarding, override, current-matrix,
  multi-profile, foldable-posture, and live-grid evidence.
- API 27–34 have no installed system images and no justified equivalence
  mapping; they remain unverified.
- No closed upper range is claimed until an accepted product or design source
  defines one.

## Evidence revisions

Evidence is attributed to the source revision that supplied each test
harness. The production implementation under test is the baseline listed
above; `b80d7a9360` is this branch immediately before the harness-only grid
update described below.

| Evidence source revision | Exact command / surface | Result |
|---|---|---|
| `b80d7a9360` | `adb shell am instrument -w -r -e class app.lawnchair.organizer.integration.Issue108DeviceEvidenceInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner` on API 36.1 Pixel 6 with simultaneous personal/work/private profiles (serials 0/10/11 running) | **PASS 2/2.** `ISSUE108_PROFILE_EVIDENCE handles=[0,10,11] serials=[0,10,11] launcherApps={0=19,10=17,11=19}`; composer returned `Ready` on a three-profile host through the #129 authorized `LauncherApps` seam. |
| `b80d7a9360` | Same runner, `-e class ...ProductionOrganizationInputInstrumentationTest,...ui.ManualOrganizationProductionE2EInstrumentationTest` on the same host | **PASS 13/13**, including `ISSUE129_EVIDENCE profiles=0,10,11 insertedRows=3 systemPackage=true ready=true` (valid work/private classification composition without privileged cross-user access). |
| `b80d7a9360` | Same runner, `-e class ...application.TwoPanelOrientationCaptureInstrumentationTest` on API 36 Pixel 9 Pro Fold AVD, unfolded and folded postures | **PASS 3/3** (orientation-authority match, composer preservation, portrait→landscape stale/no-write). Folded posture additionally passed `Issue108DeviceEvidenceInstrumentationTest` 2/2 (`TYPE_PHONE`, ordinary `PORTRAIT`). |
| This PR's grid-harness update commit | Same runner, `-e class ...integration.Issue108GridEvidenceInstrumentationTest` (optionally with `-e issue108.grid <name>`) | **Phone 4×5→3×3 PASS 2/2 and phone 4×5→5_by_5 PASS 2/2** through official grid-control state. **Tablet 4×5→6_by_5 PASS 2/2 with the preset's exact dimensions applied through the preference-key seam** — this is equivalence-harness evidence for organizer safety semantics only; the production named-preset transition remains **BLOCKED / UNVERIFIED by [#134](https://github.com/nunu1733/NunuLauncher/issues/134)**. |

The grid-harness evidence was executed at `e580693cad`, the test-only
commit that updates the grid instrumentation in this PR.

The grid-harness update (`e580693cad`) is a test-only change to this PR's
instrumentation source set; production code is untouched after `534d0f32db`.

## Compatibility matrix

| Axis | Declared scope | Evidence status |
|---|---|---|
| API | Installable lower boundary API 26; upper range not fixed by an accepted source | API 26 full-flow evidence (this date), API 35 production-input evidence, API 36/36.1 full-flow evidence exist. API 27–34 unverified (no image, no equivalence mapping); `targetSdk 35` is not treated as an upper bound. |
| Device | phone; tablet; foldable/multi-display where the adopted profile selects it | Phone (Pixel 6), Pixel Tablet, Pixel 9 Pro Fold open/closed all passed Launcher-host execution through production seams. |
| Orientation | portrait and landscape; two-panel values where selected | Live portrait/landscape transitions passed on every host. Canonical orientation now derives from the constructed `DeviceProfile.isTwoPanels` authority (#130). No available emulator exposes `TYPE_MULTI_DISPLAY`; see the two-panel limitation below. |
| GridOption preset | `3_by_3`, `4_by_5`, `5_by_5`, `6_by_5`, `practical` (multi-display) | Catalog documented separately. Enabled-preset inventory is device-type dependent; a preset-seam defect was found and split to [#134](https://github.com/nunu1733/NunuLauncher/issues/134). |
| Runtime workspace dimensions | Dimensions reported by IDP/canonical capture on executed hosts | Phone 4×5 default with live transitions to 3×3 and 5×5 passed. Tablet 6×5: organizer safety semantics verified after applying the preset's exact dimensions; the production named-preset transition is **BLOCKED / UNVERIFIED — [#134](https://github.com/nunu1733/NunuLauncher/issues/134)**. |
| Profile | personal, managed work, private | Simultaneous personal/work/private (serials 0/10/11) canonical capture retained all identities and valid rows composed `Ready` without privileged cross-user access (#129 verified closed). Locked-private preservation remains covered by existing typed fail-closed tests. |

Cells that cannot be executed or mapped to an executed production seam are
listed as unverified below; omission is not interpreted as support.

### GridOption catalog versus runtime dimensions

`device_profiles.xml` declares named presets while `DeviceProfileOverrides`
stores workspace rows, columns, and hotseat columns as independent
preferences. The enabled-preset inventory is filtered per device type:
`3_by_3` is phone-only, `4_by_5`/`5_by_5` are `phone|multi_display`,
`6_by_5` is tablet-only, and `practical` is multi-display-only. On a
`TYPE_TABLET` host the only enabled preset is therefore `6_by_5`, even though
the host's observed default dimensions were 4×5/hotseat 4. The matrix keeps
the declared catalog and observed dimensions separate.

| GridOption name | Declared dimensions | Enabled for |
|---|---|---|
| `3_by_3` | 3 columns × 3 rows | phone |
| `4_by_5` | 4 columns × 5 rows | phone, multi-display |
| `5_by_5` | 5 columns × 5 rows | phone, multi-display |
| `6_by_5` | 6 columns × 5 rows | tablet |
| `practical` | 4 columns × 5 rows, with separate two-panel specs | multi-display |

### Grid preset seam defect (split to #134)

Executing the tablet grid cell surfaced a production defect:
`DeviceProfileOverrides.predefinedGrids` snapshots `parseAllGridOptions`
before `InvariantDeviceProfile.initGrid` sets the static `deviceType`, so the
snapshot is frozen to phone-category presets on every host. Consequences
observed on the Pixel Tablet AVD:

- `IDP.setCurrentGrid(context, "6_by_5")` throws `NoSuchElementException`
  inside `DeviceProfileOverrides.getGridInfo(gridName)`; the production
  named-preset seam cannot switch grids on tablets.
- `getCurrentGridName()` ceiling-maps the live 4×5 to names that are not
  enabled presets for the host, diverging from persisted `idp_grid_name`.
- Once 6×5/hotseat 6 is applied, launcher restarts durably reapply it;
  raw preference writes of other dimensions are reverted at start.

The Issue #108 grid harness therefore applies the requested preset's exact
rows/columns/hotseat through the same Lawnchair preference keys that
`DeviceProfileOverrides.setCurrentGrid` writes (the path Lawnchair's own
rows/columns settings use), validates that the target is an enabled
GridOption declaration, and restores the pre-test dimensions through the same
keys. Full details and acceptance criteria are in
[#134](https://github.com/nunu1733/NunuLauncher/issues/134).

## Executed launcher-host evidence

### API 36.1 phone, personal + managed work + private profiles

Environment: `nunu_qpr2_api36_1`, Pixel 6 definition, Google APIs arm64-v8a
API 36.1 image, 1080×2400 at 420 dpi. Managed profile created via
`pm create-user --profileOf 0 --managed Issue108` (user 10) and private
profile via `pm create-user --profileOf 0 --user-type
android.os.usertype.profile.PRIVATE Issue108` (user 11); both started and
running; `app.lawnchair.debug` holds `ROLE_HOME` and granted
`ACCESS_HIDDEN_PROFILES`.

Results at `b80d7a9360`:

- `Issue108DeviceEvidenceInstrumentationTest` 2/2 PASS. Canonical capture
  preserved exactly the UserCache identities {0, 10, 11} with launchable
  activities visible through `LauncherApps` per profile.
- `ProductionOrganizationInputInstrumentationTest` +
  `ManualOrganizationProductionE2EInstrumentationTest` 13/13 PASS. The
  #129 regression inserted the same component into personal, work, and
  private rows and composed `Ready` (`profiles=0,10,11 insertedRows=3
  ready=true`) — no privileged cross-user permission, no cross-profile
  fallback. Quiet/locked/unavailable-profile fail-closed behavior remains
  covered by existing typed tests.

This closes the previously blocked valid multi-profile cell; #129 is
consumed by this matrix.

### API 36.1 tablet, 6×5 dimension application — safety semantics verified; preset transition BLOCKED by #134

Environment: `issue108_api36_pixel_tablet`, Pixel Tablet definition,
2560×1600 at 320 dpi, `TYPE_TABLET`, default live grid 4×5/hotseat 4.

**Cell status: BLOCKED / UNVERIFIED by [#134](https://github.com/nunu1733/NunuLauncher/issues/134)
as a production-seam result.** The production named-preset transition
(`IDP.setCurrentGrid(context, "6_by_5")`) cannot execute on this host — it
throws `NoSuchElementException` from the frozen preset snapshot (#134) — so
this cell does not claim production preset-transition evidence.

What is verified is the narrower scope of organizer safety semantics after
the grid dimensions change: applying the enabled `6_by_5` preset's exact
rows/columns/hotseat through Lawnchair's own preference keys (the same keys
`DeviceProfileOverrides.setCurrentGrid` writes and its settings UI uses),
`Issue108GridEvidenceInstrumentationTest -e issue108.grid 6_by_5` passed 2/2
— fresh production capture and composer reflected exactly 6 columns × 5 rows,
and a plan captured before the change was rejected as `STALE_REVISION` with
unchanged persistence rows afterwards. The host durably rests on 6×5/hotseat
6 after this cell; restoration attempts of the previous 4×5 state are
reverted at launcher start, recorded as part of #134's durability finding.
Closing the cell as a production-seam result requires #134's fix and a
re-run through `setCurrentGrid`.

### API 36 Pixel 9 Pro Fold, both postures, two-panel oracle suite

Environment: `issue108_api36_pixel_9_pro_fold`, Google APIs arm64-v8a API 36,
open 2076×2152 at 390 dpi (`TYPE_TABLET`), closed 1080×2424 at 390 dpi
(`TYPE_PHONE`).

Results at `b80d7a9360`: `TwoPanelOrientationCaptureInstrumentationTest`
3/3 PASS (captured orientation matches the constructed
`DeviceProfile.isTwoPanels` authority; composer preserves it exactly;
portrait→landscape transition rejects the pre-change plan as
`STALE_REVISION` with no write to the planned row). Folded posture passed
`Issue108DeviceEvidenceInstrumentationTest` 2/2. Posture transitions were
driven with `emu fold` / `emu unfold`.

Two-panel limitation (explicit): the #130 investigation demonstrated that
this baseline's `DisplayController` replaces the `perDisplayBounds` cache on
posture change, so phone and tablet mode bounds never coexist and
`TYPE_MULTI_DISPLAY` never occurs on any available emulator
(`displayType=2`/`0` with `isTwoPanels=false` throughout). Real
two-panel-portrait/two-panel-landscape host capture therefore remains
**unverified**; the accepted #130 spec satisfies its AC-1/AC-2 through the
authority-consistency oracle plus pure mapping proof, and this matrix records
the residual device limitation rather than claiming support.

### API 36.1 phone, live alternate grids

Environment: `nunu_qpr2_api36_1` as above. Transitions driven through the
official grid-control state and restored in `finally`; a plan captured before
the transition must be rejected as `STALE_REVISION` with byte-identical
persistence rows afterwards.

| Transition | Result |
|---|---|
| phone 4×5 → 3×3 | **PASS 2/2** |
| phone 4×5 → 5_by_5 | **PASS 2/2** |

### API 26, installable lower-boundary host

Environment: `api26-test`, Google APIs arm64-v8a Android 8.0.0 (API 26)
image, 640×800-class generic arm64 device; restarted with `-cores 6 -memory
6144`; `app.lawnchair.debug` made default HOME via
`cmd package set-home-activity` (no RoleManager pre-29; Nexus Launcher
disabled) with the HOME activity foregrounded.

Results at `b80d7a9360`:

- `Issue108DeviceEvidenceInstrumentationTest` 2/2 PASS
  (`sdk=26`, IDP/capture 4×5/PORTRAIT, composer `Ready`).
- `ManualOrganizationProductionE2EInstrumentationTest` full class **PASS 3/3**
  in the consolidated final pass, including apply→verify→restore with
  `RECOVERY_RESTORED`.
- `ProductionOrganizationInputInstrumentationTest` 9/10–10/10 depending on
  run: `productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites`
  intermittently observes a typed not-ready composition under parallel
  authoring load on this constrained emulated host (it passes standalone);
  the failure mode is fail-closed, never a partial generation.

Environment limitation (explicit): back-to-back full-suite executions on
this low-end emulated host are timing-sensitive — earlier passes recorded
`WriterBusy` lease contention and a correlated-reload wait exceeding the
adapter's fixed 10 s budget (`MODEL_RELOAD_FAILED`, fail-closed with
`PRE_APPLY_DB_MODEL_UNVERIFIED`) before the clean-state configuration above
passed end-to-end. These are recorded as constrained-emulation limitations,
not API 26 product incompatibilities: the same seams pass deterministically
on API 36.1 hardware-class hosts.

## Safety mapping

| Required behavior | Launcher-host evidence |
|---|---|
| Stale plan rejection | `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite` (mutation after preview ⇒ `Stale`, no second write); grid-transition stale/no-write for phone 3×3/5×5 and tablet 6×5; posture/orientation-transition stale/no-write via `TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`. |
| Lock isolation | `ProductionOrganizationInputInstrumentationTest.productionComposerMapsCanonicalCaptureAndPreservesPageDeviceProfileAvailabilityAndLock` preserves a real locked row as `Preserved`. |
| Profile isolation | Simultaneous personal/work/private identities retained in canonical capture; valid work/private rows compose through the authorized `LauncherApps` seam with exact package+profile binding and no fallback (#131 regression, re-executed here); quiet/locked-private/removed profiles remain typed fail-closed results. |
| No-write failure | Unknown-lock and unrepresentable-capture tests require only `captureCurrent`; stale confirmations preserve externally changed DB state exactly; recovery context mismatch remains fail-closed. |

## Unverified or unsupported cells

- **API 27–34:** no local system images and no accepted equivalence mapping to
  executed cells. Unverified; not silently omitted.
- **Real two-panel (`TYPE_MULTI_DISPLAY`) host capture:** unavailable on any
  obtainable emulator (bounds-cache replacement on posture change, per #130
  investigation). Covered by the authority-consistency oracle and pure
  mapping proof; real-posture capture remains unverified pending access to a
  genuine multi-display runtime.
- **Tablet 6×5 production preset transition:** BLOCKED / UNVERIFIED —
  [#134](https://github.com/nunu1733/NunuLauncher/issues/134). Only the
  narrower safety-semantics scope after dimension application is verified
  (see the tablet section above).
- **API 26 back-to-back full-suite determinism:** timing-sensitive failures
  on constrained emulation as described above; individual surfaces pass.

## Split defects found during execution

| Defect | Status | Effect on this matrix |
|---|---|---|
| [#129](https://github.com/nunu1733/NunuLauncher/issues/129) — privileged cross-user classification query failed closed for valid work/private rows | Fixed (PR #131, merged) | Multi-profile cell re-executed here through the authorized `LauncherApps` seam. |
| [#130](https://github.com/nunu1733/NunuLauncher/issues/130) — canonical capture could not emit two-panel orientations | Fixed (PR #133, merged); real multi-display runtime remains unavailable | Two-panel cell verified via authority-consistency oracle only; real-posture capture stays a residual limitation. |
| [#134](https://github.com/nunu1733/NunuLauncher/issues/134) — `DeviceProfileOverrides` freezes its preset snapshot before the device type is determined | Open | Tablet 6×5 cell is BLOCKED / UNVERIFIED as a production preset transition; grid harness uses the preference-key seam for the narrower semantics scope. |

## Handover to #100

[#100](https://github.com/nunu1733/NunuLauncher/issues/100) should treat the
following as remaining blockers/gaps when forming its release-readiness
verdict: the open defect #134 (tablet/multi-display grid preset seam), the
real two-panel runtime capture gap, the unestablished API 27–34 coverage and
the unclosed upper API boundary, and the API 26 constrained-host timing
limitations. All other cells in the matrix are backed by Launcher-host
execution recorded above.

## Reproduction environment and commands

Host setup follows `docs/engineering/building.md`: JDK 21, Android SDK
Platform 36.1, Build Tools 36.1.0, emulator 37.x, platform-tools 37.x.

```bash
./gradlew spotlessCheck --no-configuration-cache
./gradlew assembleLawnWithQuickstepGithubDebug --no-configuration-cache
./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest --no-configuration-cache
adb install -r -t -g 'build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(<sha>).github.debug.apk'
adb install -r -t 'build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/NunuLauncher-lawn-withQuickstep-github-debug-androidTest.apk'
adb shell cmd role add-role-holder --user 0 android.app.role.HOME app.lawnchair.debug   # API 29+

# Multi-profile provisioning (API 36.1)
adb shell pm create-user --profileOf 0 --managed Issue108                # user 10
adb shell pm create-user --profileOf 0 \
  --user-type android.os.usertype.profile.PRIVATE Issue108               # user 11
adb shell am start-user 10 && adb shell am start-user 11

# Focused surfaces (runner: app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner)
adb shell am instrument -w -r -e class \
  app.lawnchair.organizer.integration.Issue108DeviceEvidenceInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
adb shell am instrument -w -r \
  -e class app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest,app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
adb shell am instrument -w -r \
  -e class app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
adb shell am instrument -w -r \
  -e class app.lawnchair.organizer.integration.Issue108GridEvidenceInstrumentationTest \
  -e issue108.grid 6_by_5 \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
```

Fold postures: `adb -s <serial> emu fold` / `emu unfold`. Grid evidence on
API 26 requires `cmd package set-home-activity` instead of the role command.

## Result

Executed as a compatibility-evidence task: the matrix fixes the state of
every cell as supported, blocked, unverified, or limited, with the verified
scope and the known gaps stated separately.

Verified through Launcher-host execution on production seams: phone and
foldable postures with live portrait/landscape transitions and stale/no-write
safety behavior; simultaneous personal/work/private capture and composition
after #129's fix; canonical orientation derived from the constructed
`DeviceProfile` authority after #130's fix; live grid transitions on the
phone; and the API 26 lower-boundary host within its recorded environment
limits.

Not claimed, and explicitly separated from the verified scope: the tablet
6×5 **production preset transition** (BLOCKED / UNVERIFIED by the open defect
[#134](https://github.com/nunu1733/NunuLauncher/issues/134) — only the
post-dimension-change safety semantics are verified), real two-panel runtime
capture, API 27–34 coverage, and back-to-back suite determinism on
constrained API 26 emulation. Split defects #129/#130/#134 are linked in the
table above, and the residual blockers/gaps handed to
[#100](https://github.com/nunu1733/NunuLauncher/issues/100) are listed in the
handover section. #100 can consume this matrix for its release-readiness
verdict.
