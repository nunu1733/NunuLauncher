# Organizer MVP compatibility evidence matrix

> Status: In progress
> Issue: [#108](https://github.com/nunu1733/NunuLauncher/issues/108)
> Requirement: NFR-007
> Production baseline: `51940f3dfc4b9308f7c9e7101c2c7cda81f16da7`
> Verified: 2026-08-24

## Evidence revisions

Evidence is attributed to the source revision that supplied each test harness;
the production implementation under test remains the baseline listed above.

| Evidence source revision | Exact command / surface | Result |
|---|---|---|
| `032e38816550cf67267ae29801ee8a5230a9e745` | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest,app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest` | API 36.1 phone/tablet/fold-open portrait and landscape runs passed 11/11 per host/orientation. The fold-closed production invocation was invalidated before discovery and is not claimed. |
| `032e38816550cf67267ae29801ee8a5230a9e745` | `adb -s <serial> shell am instrument -w -r -e class app.lawnchair.organizer.integration.Issue108DeviceEvidenceInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner` | Fold-closed device evidence passed 2/2; simultaneous-profile discovery retained the valid UserCache profile identities. This revision's orientation oracle expected ordinary portrait/landscape. |
| `032e38816550cf67267ae29801ee8a5230a9e745` | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.Issue108GridEvidenceInstrumentationTest` and the same command with `-Pandroid.testInstrumentationRunnerArguments.issue108.grid=5_by_5` | Phone transitions to 3×3 and 5×5 passed 2/2 each, including stale/no-write. |
| `8a9d1dabc34b71c1737555c61486e9349536a73e` | `adb -s emulator-5554 shell am instrument -w -r -e class app.lawnchair.organizer.integration.Issue108DeviceEvidenceInstrumentationTest app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner` | Review-adjusted oracle, including `TYPE_MULTI_DISPLAY` → `TWO_PANEL_*`, passed 2/2 on an API 36.1 phone. Only the ordinary-orientation branch executed. |
| `8a9d1dabc34b71c1737555c61486e9349536a73e` | `./gradlew spotlessCheck --no-configuration-cache`; `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest --no-configuration-cache`; `python3 tools/repo-contract/validate_repo_contract.py`; `git diff --check` | All passed. |

The second revision changes the device-orientation oracle, not production code.
Its phone run exercises the ordinary-orientation branch; a runtime selecting
`TYPE_MULTI_DISPLAY` remains required after #130 fixes the production mapping.

## Purpose and support boundary

This assessment defines the compatibility cells claimed by the organizer MVP
on the adopted Lawnchair `v15.0.0-beta3.0` baseline. It does not introduce a
second planning or application path. Every device run uses the production
`LauncherLayoutAdapter`, `ProductionOrganizationInputComposer`, and, where the
cell exercises mutation, `ManualOrganizationRun` through the production layout
application and recovery module.

The build fixes `minSdk 26`, `targetSdk 35`, and `compileSdk 36.1`, but these
values do not by themselves define a closed runtime-support range. `minSdk`
sets the installable lower API boundary, `targetSdk` selects Android
compatibility behavior, and `compileSdk` selects the APIs available at build
time; `targetSdk` is not a maximum runtime API. NFR-007 instead requires the
device/API surface supported by the adopted Lawnchair revision, but no accepted
repository source currently closes an upper API boundary. This WIP therefore
separates evidence from support policy: API 35 has production-input
Launcher-host evidence; API 36/36.1 has manual, application, onboarding,
override, and current matrix evidence; API 26 has recovery-inspection-only
device evidence but no full organizer-host run; and API 27–34 have no justified
equivalence mapping. No closed upper range is claimed until an accepted product
or design source defines it.

Lawnchair's `device_profiles.xml` declares phone, tablet, and multi-display
GridOptions. The organizer's accepted device input declares portrait,
landscape, two-panel portrait, and two-panel landscape orientations. Personal,
work, and private profiles remain distinct identities under the NFR-002
profile-isolation invariant.

## Compatibility matrix

| Axis | Declared scope | Evidence status |
|---|---|---|
| API | Installable lower boundary API 26; exact adopted-baseline runtime range not yet fixed | API 35 production-input evidence and API 36/36.1 full-flow evidence exist. API 26 has recovery-inspection-only device evidence, not a full organizer-host run. API 27–34 remain unverified; `targetSdk 35` is not treated as an upper bound. |
| Device | phone; tablet; foldable/multi-display where the adopted Lawnchair profile selects it | Phone, Pixel Tablet, and Pixel 9 Pro Fold launcher-host execution passed. The foldable changed between phone and tablet device types when folded/unfolded. |
| Orientation | portrait and landscape; two-panel portrait/landscape where selected by the foldable profile | Live phone/tablet/open-foldable portrait and landscape passed. Production capture cannot currently emit `TWO_PANEL_*`; [#130](https://github.com/nunu1733/NunuLauncher/issues/130) owns that defect. |
| GridOption preset | `3_by_3`, `4_by_5`, `5_by_5`, `6_by_5`, and multi-display `practical` | Catalog declarations are listed separately below; they are not presented as the observed runtime dimensions. |
| Runtime workspace dimensions | Dimensions actually reported by IDP/canonical capture for an executed host | Phone 4×5→3×3 and 4×5→5×5 live changes passed fresh capture and stale/no-write tests. Tablet 6×5 and multi-display remain unverified. |
| Profile | personal, managed work, private | Real simultaneous-profile execution reproduced a production classification blocker; tracked by [#129](https://github.com/nunu1733/NunuLauncher/issues/129). |

Cells that cannot be executed or mapped to an executed production seam are
listed as unverified below; omission is not interpreted as support.

### GridOption catalog versus runtime dimensions

`device_profiles.xml` declares named GridOption presets, while
`DeviceProfileOverrides` stores workspace rows, columns, and hotseat columns as
independent preferences. The runtime UI permits rows and columns from 3 through
10, optionally 3 through 20 when the extended-grid flag is enabled, and an
independent hotseat range from 3 through 10. `getGridName()` uses a ceiling-style
rows/columns lookup and ignores hotseat, so it is not exact preset-identity
mapping; `setCurrentGrid()` does write the selected preset's rows, columns, and
hotseat. Canonical capture records the resolved IDP runtime dimensions. The
matrix therefore keeps the declared catalog and observed dimensions separate.

| GridOption name | Declared dimensions | Device category |
|---|---|---|
| `3_by_3` | 3 columns × 3 rows | phone |
| `4_by_5` | 4 columns × 5 rows | phone, multi-display |
| `5_by_5` | 5 columns × 5 rows | phone, multi-display |
| `6_by_5` | 6 columns × 5 rows | tablet |
| `practical` | 4 columns × 5 rows, with separate two-panel specs | multi-display |

Executed runtime observations were 4×5 on the clean phone, tablet, and open
foldable hosts, followed by explicit phone transitions to 3×3 and 5×5 through
`parseAllGridOptions()` and `setCurrentGrid()`. The GridOption name and current
workspace dimensions are both recorded; neither is substituted for the other.

## Executed launcher-host evidence

### API 36.1 phone, live portrait and landscape

Environment: `nunu_qpr2_api36_1`, Pixel 6 device definition,
`google_apis` arm64-v8a system image revision 4, 1080×2400 at 420 dpi.

For portrait (`user_rotation=0`) and landscape (`user_rotation=1`), the same
focused command ran 11 tests from
`ProductionOrganizationInputInstrumentationTest` and
`ManualOrganizationProductionE2EInstrumentationTest`:

```bash
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest,app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest
```

| Orientation | Host observation | Result |
|---|---|---|
| Portrait | `settings ... user_rotation 0`; 1080×2400 physical display | **PASS — 11/11**, build successful in 24 s. |
| Landscape | `settings ... user_rotation 1`; `dumpsys` reported `orientation=1` | **PASS — 11/11**, build successful in 15 s. |

The production-input surface proves canonical page/device/profile/availability/
lock mapping and fail-closed no-write results. The manual surface proves the
production capture → plan → preview → stale admission/apply → verification →
recovery path. Running those same seams after a live orientation transition
establishes that the host reloads the changed configuration without creating an
alternate organizer path.

### API 36.1 tablet and foldable profiles

Two independent clean AVDs used the same Google APIs arm64-v8a API 36.1 image:

| AVD / posture | Runtime shape | Launcher classification and capture | Result |
|---|---|---|---|
| Pixel Tablet portrait/landscape | 2560×1600 at 320 dpi; smallest width 800 dp | `TYPE_TABLET`, not two-panel; IDP/capture 4×5, hotseat 4 | **PASS — production 11/11** in both orientations. |
| Pixel 9 Pro Fold open, portrait/landscape | 2076×2152 at 390 dpi; smallest width 852 dp | `TYPE_TABLET`, not two-panel; IDP/capture 4×5 | **PASS — production 11/11** in both orientations. |
| Pixel 9 Pro Fold closed | 1080×2424 at 390 dpi; smallest width 443 dp | `TYPE_PHONE`, not two-panel; IDP/capture 4×5 | **PASS — device evidence 2/2**. The production 11-test invocation was invalidated before discovery by concurrent shared build output, so it is not claimed for this posture. |

The posture transition used `adb -s emulator-5564 emu fold`, followed by
`adb -s emulator-5564 emu unfold` to restore the open state. This emulator
models the fold as a phone↔tablet device-type transition and never exposes
`TYPE_MULTI_DISPLAY` or `DeviceProfile.isTwoPanels=true`; therefore it cannot
provide the two-panel cell. The accepted domain type still declares
`TWO_PANEL_PORTRAIT` and `TWO_PANEL_LANDSCAPE`. Source review also found that
`LauncherLayoutAdapter.capabilities()` can emit only ordinary portrait or
landscape even if Launcher selects a two-panel profile. [Issue #130](https://github.com/nunu1733/NunuLauncher/issues/130)
owns the production mapping and stale/no-write regression. The cell is
**blocked/unverified**, not silently reclassified as unsupported.

The clean tablet selected a live 4×5 IDP even though the resource catalog also
declares tablet 6×5. Explicit live 6×5 evidence remains pending and is not
equated with the executed 4×5 tablet cell.

### API 36.1 phone alternate live grids

`Issue108GridEvidenceInstrumentationTest` changes the grid only through the
official `InvariantDeviceProfile.setCurrentGrid` seam and restores the original
option in `finally`. It proves that a fresh production capture/composition
reflects the new dimensions and that a plan captured before the change is
rejected as `STALE_REVISION`. A post-rejection `recaptureDb()` must have exactly
the same persistence rows and row count as the capture taken after the grid
change.

| Transition | Result |
|---|---|
| phone 4×5 → 3×3 | **PASS — 2/2** (fresh capture and stale/no-write). |
| phone 4×5 → 5×5 | **PASS — 2/2** using instrumentation argument `issue108.grid=5_by_5`. |

The AVD ended on the original 4×5 grid and its original Favorites rows.

## Safety mapping

| Required behavior | Launcher-host evidence |
|---|---|
| Stale plan rejection | `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite` mutates the authoritative revision after preview and requires `Stale` with no second write. Grid-specific transition evidence is recorded above; posture/orientation-transition stale/no-write evidence remains pending under #130. |
| Lock isolation | `ProductionOrganizationInputInstrumentationTest.productionComposerMapsCanonicalCaptureAndPreservesPageDeviceProfileAvailabilityAndLock` captures a real locked Launcher row and preserves it as a `Preserved` target. |
| Profile isolation | API 36.1 exposed personal/work/private serials 0/10/11 and canonical capture kept them distinct. Composition of valid work/private rows then failed closed because the classification adapter used a privileged cross-user `PackageManager` query. This is tracked by #129; the existing invalid-profile oracle is not promoted to valid work/private-profile evidence. |
| No-write failure | Unknown lock and unrepresentable capture tests require only `captureCurrent` and no planner/application/recovery write; stale confirmation preserves the externally changed DB state exactly. |

## Unverified or unsupported cells

### Valid simultaneous work/private profiles — blocked by #129

The API 36.1 phone was provisioned with a running personal user (serial 0),
managed work profile (10), and private profile (11). Canonical capture retained
all three identities and a locked private-profile fixture row. The production
composer returned:

```text
NotReady(reason=SourceUnreadable(source=PLATFORM_CLASSIFICATION_EVIDENCE),
diagnostic=CompositionDiagnostic(code=evidence-unreadable, ...))
```

The device log identified the cause: the normal launcher UID was denied
`INTERACT_ACROSS_USERS[_FULL]` when
`AndroidClassificationSignalSnapshotSource` used
`createContextAsUser(...).packageManager.getApplicationInfo(...)` for user 10.
The result is safely no-write, but valid multi-profile full organization is not
supported by the implementation as it stands. [Issue #129](https://github.com/nunu1733/NunuLauncher/issues/129)
owns an authorized, exact-profile classification seam and its device regression
test.

Other pending device cells will be tied either to an accepted product/design
source or to a split Issue; absence of evidence will not be presented as an
unsupported product decision.

### Remaining unverified cells

- API 26 full organizer-host execution (separate recovery-inspection-only device
  evidence exists), API 27–34 evidence/equivalence, and the exact
  adopted-baseline API support boundary.
- Explicit live tablet 6×5.
- A runtime that actually selects Lawnchair `TYPE_MULTI_DISPLAY` / two-panel,
  after #130 corrects production canonical orientation.
- Valid multi-profile composition after #129.

## Reproduction environment and commands

Host setup follows `docs/engineering/building.md`: JDK 21, Android SDK Platform
36.1, Build Tools 36.1.0, emulator 37.1.11, and platform-tools 37.0.1. Exact AVD
creation, profile provisioning, live-grid changes, and focused test commands
will be recorded here after the remaining cells complete.

## Result

In progress and blocked by #129 for the supported multi-profile cell and #130
for canonical two-panel orientation. The profile failure is fail-closed and
produced no layout write, but NFR-007 and Issue #108 cannot be marked complete
until both production paths and the remaining API/grid cells pass.
