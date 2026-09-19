# Issue #367 evidence — organizer materials relocation

> Issue: [#367](https://github.com/nunu1733/NunuLauncher/issues/367)
> Spec: [specs/367-organizer-materials-relocation/spec.md](../../../specs/367-organizer-materials-relocation/spec.md)
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
- CI `organizer-instrumentation-issue52-tests` lane class list (now including `app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest`): locally green on the CI-equivalent AVD (8 classes, one invocation).
- `app.lawnchair.organizer.ui.OnboardingOrganizationProposalInstrumentationTest` + `InjectedInputEnvironmentStateInstrumentationTest` (issue-53 lane): locally green — #232 oracle (`homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`) and hint oracle unmodified and passing (MAT-AC-07).
- `app.lawnchair.organizer.locks.OrganizerLockScreenTest`: locally green (MAT-AC-08, T-20 flow-outside entry).
- `app.lawnchair.organizer.ui.CategoryOverridePreferencesInstrumentationTest`: locally green.
- Unit gate `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`: green.
- `./gradlew spotlessCheck`: green.

## Known pre-existing failure (not owned by #367, base-reproduced)

- `app.lawnchair.organizer.ui.CustomCategoryPreferencesInstrumentationTest`: `createFlowRendersTypedDuplicateFeedbackAndListsTheCreatedEntry` throws `IllegalArgumentException: verified Create must report the minted entry` (`UserDefinedCategoryAuthoringCoordinator.create`, spec 336 surface) and `renameKeepsTheEntryPresentedAsTheSameCategory` cascades ("Commute" row missing) when the class runs in a method order where `partialDelete…` precedes `create…`. Reproduced on base `dd09ef1a18` (spec-branch head) on this machine; the class is not in any CI lane. The very first combined local run today (different method order) passed, so the failure is order-dependent. Recorded here for the PR; not fixed by this PR (out of #367 scope — #367 changes no store or authoring code).
