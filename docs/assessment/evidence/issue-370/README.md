# Issue #370 evidence — onboarding proposal / re-entry hint hub connection

> Issue: [#370](https://github.com/nunu1733/NunuLauncher/issues/370)
> Spec: [specs/370-onboarding-hub-connection/spec.md](../../../../specs/370-onboarding-hub-connection/spec.md)
> Captured: 2026-09-21, emulator `issue142_api36` AVD (API 36, `sdk_gphone64_arm64` — CI organizer instrumentation lane equivalent), debug build `app.lawnchair.debug` (head `23e742095a`) on branch `issue-370-onboarding-hub-connection`, fresh-install provenance (uninstall → install per cycle)

## OCB-AC-02 — re-entry hint guides to the hub entry row (EN/ja × light/dark)

| File | Surface | What it shows |
|---|---|---|
| `01-en-light-proposal.png` | launcher Home（EN, light） | Fresh-install proposal (baseline of the flow). |
| `02-en-light-hint.png` | launcher Home（EN, light） | After `Later`: the 6s hint path is "Home settings → Home screen → **Organizer**" — the hub entry row label (`organizer_hub_title`), not the removed "Organize home layout" row. |
| `03-en-dark-proposal.png` | 同上（EN, dark） | Proposal in dark mode. |
| `04-en-dark-hint.png` | 同上（EN, dark） | Same hub-entry hint copy in dark mode. |
| `05-ja-light-proposal.png` | 同上（ja, light） | Proposal with Japanese resources. |
| `06-ja-light-hint.png` | 同上（ja, light） | ja hint: 「ホーム画面を長押し → ホーム設定 → ホーム画面 → **オーガナイザー**」— the ja `organizer_hub_title` is composed from the real settings label (spec 232 truthfulness rule). |
| `07-ja-dark-proposal.png` | 同上（ja, dark） | Proposal in ja/dark. |
| `08-ja-dark-hint.png` | 同上（ja, dark） | Same ja hub-entry hint copy in dark mode. |

## OCB-AC-01 — `確認` reaches the admitted run's face without the T-07 preamble

| File | Surface | What it shows |
|---|---|---|
| `09-en-dark-runface-after-confirm.png` | `PreferenceActivity` run face（EN, dark） | Tapping `REVIEW ORGANIZATION` navigates directly to the admitted run's face (`Organize home layout` + the T-08 missing-app selection for the fresh install's real candidates). No transitional T-07 preamble (`Organize as is` start CTA) renders on the route — the D-16 admission-direct connection on real hardware. The deterministic T-07 render-count-0 record is pinned by `OnboardingOrganizationProposalInstrumentationTest.realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface` (production admission path through a process-local runner fixture; face = pure function of the traced coordinator state). |

## OCB-AC-03 — settings → Home screen has exactly one organizer row

| File | Surface | What it shows |
|---|---|---|
| `10-en-dark-settings-hub-entry.png` | 設定 → Home screen（EN, dark） | The General group ends with the single **Organizer** hub entry row; the `Organize home layout` direct run row staged by #367 is gone (D-01 entry-row-only end state). |

## Automated evidence

- `OnboardingOrganizationProposalInstrumentationTest` — **OK (20 tests)**, local run 2026-09-21 on `issue142_api36` (emulator-5558), head `23e742095a`. Includes the reworked production-admission guard, the hub-label hint composition assert (with the removed-label absence), and the hub-entry settings-row assert with the removed-row absence scan.
- `OrganizerDiagnosticsRouteInstrumentationTest.homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub` — **OK (1 test)**, same run (manual run row absent + hub entry row present).
- `./gradlew spotlessCheck`, `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — PASS, head `23e742095a`.
