# Issue #367 evidence — organizer materials relocation

> Issue: [#367](https://github.com/nunu1733/NunuLauncher/issues/367)
> Spec: [specs/367-organizer-materials-relocation/spec.md](../../../../specs/367-organizer-materials-relocation/spec.md)
> Captured: 2026-09-19, emulator `issue209_pixel_7_pro` AVD (API 36, pixel_7_pro profile — CI `organizer-instrumentation-issue52-tests` lane equivalent), debug build `app.lawnchair.debug` on branch `issue-367-materials-relocation`

## MAT-AC-02 / MAT-AC-01 — settings Home screen after the relocation

| File | Surface | What it shows |
|---|---|---|
| `settings-home-en-light.png` | 設定 → Home screen（EN, light） | General group keeps only the hub entry row ("Organizer") and the #232 manual run entry ("Organize home layout") — the staged coexistence. The Layout group has no organizer rows; the Personalization group is gone. |
| `settings-home-en-dark.png` | 同上（EN, dark） | Same structure in dark mode. |
| `settings-home-ja-light.png` | 同上（ja, light） | Same structure with Japanese resources: 「ホームレイアウトを整理」＋「オーガナイザー」のみ。 |

## MAT-AC-01 — hub materials section (single T-06 instance)

| File | Surface | What it shows |
|---|---|---|
| `hub-en-light.png` | Organizer hub（EN, light） | Status card phase 1 (checking line → "Review organization" CTA → standing diagnostics row) and the "Organizing materials" group: App category overrides / Custom categories / Placement locks / Record app launches toggle (T-06). |
| `hub-en-dark.png` | 同上（EN, dark） | Same structure in dark mode. |
| `hub-ja-light.png` | 同上（ja, light） | Japanese resources: 整理案を確認 → オーガナイザー診断 → 整理の材料（アプリカテゴリの手動設定 / カスタムカテゴリ / 配置ロック / 整理のためにアプリ起動を記録する / 使用状況アクセス権限）。 |

## Automated evidence

- Red→green cycle: `OrganizerDiagnosticsRouteInstrumentationTest.homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub` fails at `assertDoesNotExist` with the source deletion stashed (pre-change state detected) and passes after it (local run, 2026-09-19).
- MAT-AC-01: `homeScreenHubMaterialsRoutesToEachAuthoringDestination` (new) clicks each hub materials row and asserts arrival at the category-overrides / custom-categories / placement-locks destinations (backstack `hasRoute`; the custom-categories and placement-locks assertions additionally check each surface's own UI marker).
- MAT-AC-06: `homeScreenHubTogglesRecordingPreferenceAndRereadsUsageAccessOnResume` (new) flips the recording toggle on the production hub (the shared preference switch reflects both ways), clicks the usage-access row and asserts the blocked launch carries `Settings.ACTION_USAGE_ACCESS_SETTINGS` (instrumentation monitor), asserts the row text matches the current app-op state, and drives grant/revoke through the UiAutomation shell with an `ON_RESUME` cycle to verify the row re-reads its state.
- CI `organizer-instrumentation-issue52-tests` lane class list (now including `app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest`): locally green on the CI-equivalent AVD (8 classes, one invocation).
- `app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest` + `InjectedInputEnvironmentStateInstrumentationTest` (issue-53 lane): locally green — #232 oracle (`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`) and hint oracle unmodified and passing (MAT-AC-07).
- `app.lawnchair.organizer.locks.OrganizerLockScreenTest`: locally green (MAT-AC-08, T-20 flow-outside entry).
- `app.lawnchair.organizer.ui.CategoryOverridePreferencesInstrumentationTest` and `app.lawnchair.organizer.ui.CustomCategoryPreferencesInstrumentationTest`: locally green.
- Unit gate `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`: green.
- `./gradlew spotlessCheck`: green.

## Pre-existing test-infrastructure failure fixed in this PR

- `app.lawnchair.organizer.ui.CustomCategoryPreferencesInstrumentationTest` failed on base `dd09ef1a18` (the class is not in any CI lane): `FakeCatalogStore.mutate` — test infrastructure, not production code — returned `Committed(..., created = null)` for Create, violating the coordinator's verified-Create contract (`Committed` Create must report the minted entry, spec 336), which crashed `createFlow…` and cascaded into `renameKeeps…` (whose rename affordance is a contentDescription, not visible text). This PR corrects the fake's Create reporting and matches the rename row via `onNodeWithContentDescription`; the class is now stably green. Production code and the oracle's observed surfaces are unmodified.
