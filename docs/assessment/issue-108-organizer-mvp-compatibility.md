# Organizer MVP compatibility evidence matrix

> Status: In progress
> Issue: [#108](https://github.com/nunu1733/NunuLauncher/issues/108)
> Requirement: NFR-007
> Verification target: `51940f3dfc4b9308f7c9e7101c2c7cda81f16da7`
> Verified: 2026-08-24

## Purpose and support boundary

This assessment defines the compatibility cells claimed by the organizer MVP
on the adopted Lawnchair `v15.0.0-beta3.0` baseline. It does not introduce a
second planning or application path. Every device run uses the production
`LauncherLayoutAdapter`, `ProductionOrganizationInputComposer`, and, where the
cell exercises mutation, `ManualOrganizationRun` through the production layout
application and recovery module.

The product requirement is Android API 26 through target API 35 because the
build fixes `minSdk 26` and `targetSdk 35`. API 36.1 is additional forward
compatibility evidence, not an expansion of the release API range. Lawnchair's
`device_profiles.xml` declares phone, tablet, and multi-display grids. The
organizer's accepted device input declares portrait, landscape, two-panel
portrait, and two-panel landscape orientations. Personal, work, and private
profiles remain distinct identities under the NFR-002 profile-isolation
invariant.

## Compatibility matrix

| Axis | Supported or verified cells | Evidence disposition |
|---|---|---|
| API | API 26–35 supported; API 36.1 supplemental | Boundary execution at API 26 and 35; API 36.1 production runs; intermediate APIs use the min/target boundary equivalence described below. |
| Device | phone; tablet; foldable/multi-display where the adopted Lawnchair profile selects it | Phone, Pixel Tablet, and Pixel 9 Pro Fold launcher-host execution passed. The foldable changed between phone and tablet device types when folded/unfolded. |
| Orientation | portrait and landscape; two-panel portrait/landscape where selected by the foldable profile | Live phone/tablet/open-foldable portrait and landscape passed. The selected Pixel foldable did not expose a two-panel device profile; that cell remains unverified. |
| Grid | declared phone 3×3, 4×5, 5×5; tablet 6×5; multi-display `practical` 4×5 | Phone 4×5→3×3 and 4×5→5×5 live changes passed fresh capture and stale/no-write tests. Tablet 6×5 and multi-display remain unverified. |
| Profile | personal, managed work, private | Real simultaneous-profile execution reproduced a production classification blocker; tracked by [#129](https://github.com/nunu1733/NunuLauncher/issues/129). |

Cells that cannot be executed or mapped to an executed production seam are
listed as unverified below; omission is not interpreted as support.

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
`TWO_PANEL_PORTRAIT` and `TWO_PANEL_LANDSCAPE`, so this limitation is recorded
as **unverified**, not silently reclassified as unsupported.

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
| Stale plan rejection | `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite` mutates the authoritative revision after preview and requires `Stale` with no second write. Grid/orientation-specific evidence is recorded in the executed-cell sections. |
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

- API 26 full organizer-host execution and the API 26→35 boundary-equivalence
  claim.
- Explicit live tablet 6×5.
- A runtime that actually selects Lawnchair `TYPE_MULTI_DISPLAY` / two-panel.
- Valid multi-profile composition after #129.

## Reproduction environment and commands

Host setup follows `docs/engineering/building.md`: JDK 21, Android SDK Platform
36.1, Build Tools 36.1.0, emulator 37.1.11, and platform-tools 37.0.1. Exact AVD
creation, profile provisioning, live-grid changes, and focused test commands
will be recorded here after the remaining cells complete.

## Result

In progress and blocked for the supported multi-profile cell by #129. The
failure is fail-closed and produced no layout write, but NFR-007 and Issue #108
cannot be marked complete until the valid-profile production path passes.
