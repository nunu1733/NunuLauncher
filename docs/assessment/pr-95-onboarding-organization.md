# High-risk audit: PR #95 Onboarding organization proposal

> Status: blocked — NO-GO
> Audit date: 2026-08-21

- Auditor: Independent review session separate from the implementation/review-fix sessions (solo-maintenance audit)
- PR: https://github.com/nunu1733/NunuLauncher/pull/95
- Head SHA: efc5f2e8c3fa24d629f4cd17f82b2696a0ff2c8c
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32445805924
- Criteria: specs/53-onboarding-organization-proposal/spec.md — FR-006, FR-007, FR-015, NFR-001, NFR-005, NFR-009, NFR-011, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008; specs/52-manual-full-organization-vertical-slice/spec.md — FR-006, FR-015, NFR-001, NFR-005, NFR-009, NFR-011

## Scope

This audit covers the complete `main..efc5f2e8c3fa24d629f4cd17f82b2696a0ff2c8c` Issue #53 implementation diff and the PR-event evidence available for PR #95. The review focused on the high-risk boundaries introduced or reused by the onboarding entry point: install/restore provenance, launcher lifecycle admission, one-shot/deferred proposal state, Back and accessibility behavior, Review-to-`ONBOARDING_PROPOSAL` admission, busy-run isolation, navigation/recreation state, and preservation of the Issue #52 preview/confirmation/write authority.

The audit also inspected the repository CI and high-risk evidence contracts because AC-008 requires connected evidence plus a substantive independent audit, not only source-level confidence. No implementation files were changed by this audit; the only audit-session repository change is this record under `docs/assessment/`.

## Criteria check

- **AC-001 / FR-007 — BLOCKED.** The current lifecycle fix is materially better than the previously reviewed sticky `resumed` flag: `showIfReady()` now also checks the launcher's current lifecycle state and therefore does not present merely because an earlier resume callback fired. The fresh/upgrade/restore/unknown classifier also fails closed when provenance is unknown. However, provenance capture is invoked by `LawnchairLauncher.onCreate()` only *after* `super.onCreate()` returns. The superclass has already called `LauncherModel.addCallbacksAndLoad()`, whose loader path posts `LoaderTask` to `MODEL_EXECUTOR`. That loader can initialize `ModelDbController`, run `RestoreDbTask.restoreIfNeeded()` (which removes the restore-pending marker), and at model-load completion clear `IS_FIRST_LOAD_AFTER_RESTORE`. There is no happens-before edge requiring `OrganizationOnboardingProposal.captureProvenance()` to run before those background clears. A restored install whose package times otherwise look fresh can therefore be misclassified as `FRESH_INSTALL` if the background loader wins that race. Because upgrade/restore/unknown launches must fail closed, timing must not be the safety mechanism.
- **AC-002 — PASS at source/unit-test level.** Skip/defer/Back resolution is confined to the proposal preference/process-local state; it does not call the organizer runner or application writer. Back is handled only when committed, and unresolved close records the deferred outcome.
- **AC-003 / AC-005 — PASS at source/unit-test level, connected evidence incomplete.** Review admission calls the shared `ManualOrganizationModule` with `Trigger.ONBOARDING_PROPOSAL`; a successful admission is recorded before navigation, while `Busy` leaves the proposal actionable and does not relabel the active run. Unit coverage checks manual/onboarding trigger separation and fresh retry run IDs. The new connected test exercises the production proposal owner and navigation seam with injected admission outcomes, but that test is not executed by the current PR CI job.
- **AC-004 / FR-006 / NFR-001 / NFR-005 — PASS at source boundary level.** The onboarding proposal has no direct planner/materializer/application write path. A successful Review only enters the existing Issue #52 coordinator and opens the existing preference route; the reused flow still requires its explicit preview confirmation before materialization/application.
- **AC-006 — PASS at source/unit-test level.** The route serializes only the typed entry source, not a run ID, capability, or old preview. Retry goes through `start(trigger)` again and unit coverage verifies a fresh run ID.
- **AC-007 / NFR-009 / NFR-011 — PASS at inspected source boundary level.** No network dependency was introduced, and the proposal does not bypass the existing Issue #52 safe application/recovery boundary.
- **AC-008 — FAIL.** PR CI run 32445805924 executes organizer unit tests, style, build, and an API-35 instrumentation job. That instrumentation job is configured for the Issue #83 production-input class and the Issue #52 manual-organization classes; it does **not** execute `OnboardingOrganizationProposalInstrumentationTest`. The approved Issue #53 plan requires connected coverage for the onboarding proposal on API 36.1. Therefore a green `CI / final-status`, if eventually produced, would not by itself be the required Issue #53 connected evidence.
- **High-risk evidence contract — BLOCKED.** `tools/repo-contract/validate_high_risk_evidence.py` requires every criteria document named by the audit to expose an accepted/implemented YAML-frontmatter `status`. `specs/53-onboarding-organization-proposal/spec.md` declares `**Status:** Accepted` in Markdown but has no YAML frontmatter. An honest audit must reference that Issue #53 spec, so the mechanical high-risk gate cannot validate this record until the criteria document metadata is brought into the repository contract.

## Executed test surface

The independent session inspected the PR diff, the current Issue #53 spec/plan, the Issue #52 accepted spec, `.github/workflows/ci.yml`, `.github/workflows/high-risk-gate.yml`, and `tools/repo-contract/validate_high_risk_evidence.py`. It also inspected the live PR-event GitHub Actions run rather than treating test declarations as executed evidence.

At the audit cut, CI run 32445805924 had the following observed results:

- `python3 tools/repo-contract/validate_repo_contract.py` and the repository-contract/self-test job: **PASS**.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --console=plain`: **PASS**.
- `./gradlew spotlessCheck`: **PASS**.
- `./gradlew assembleLawnWithQuickstepGithubDebug --console=plain`: **PASS**.
- The API-35 `organizer-instrumentation-tests` job was still in progress. Its configured test surface was Issue #83 `ProductionOrganizationInputInstrumentationTest` plus Issue #52 `ManualOrganizationProductionE2EInstrumentationTest` / `ManualOrganizationPreferencesInstrumentationTest`.
- The required API-36.1 connected execution of `OnboardingOrganizationProposalInstrumentationTest` was **not present in the PR CI workflow**, so no executed result for that class is available from this PR-event run.

The initial high-risk-gate run for PR #95 also executed:

- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 95 --head-sha efc5f2e8c3fa24d629f4cd17f82b2696a0ff2c8c`

and failed, as expected before this commit, because no `docs/assessment/pr-95-<slug>.md` record existed yet.

This audit session did not have a repository checkout/emulator execution environment independent of GitHub Actions, so it does not claim an unrecorded local `git diff --check` or API-36.1 emulator run.

## Findings

1. **High / Blocker — restore provenance capture has a loader race (AC-001, FR-007).** The proposal captures provenance after the superclass has already posted the model loader. Both restore signals consulted by the classifier can be cleared by that background model lifecycle. Fix by capturing an immutable restore/fresh-install provenance snapshot before model loading can consume those markers (or by introducing another ordering-safe, fail-closed provenance source), then add a test that deterministically covers loader-first ordering. Re-audit the new implementation head.
2. **High / Blocker — required Issue #53 connected evidence is not executed by PR CI (AC-008).** The onboarding connected test exists in source but the current instrumentation job does not select it, and the job uses API 35 rather than the plan's API 36.1 target. Add/produce accepted API-36.1 connected evidence for the exact onboarding class and preserve the Issue #52 cross-entry coverage; then obtain a fresh PR-event CI result and re-audit.
3. **High / Gate blocker — Issue #53 criteria metadata is incompatible with the high-risk validator.** The spec says Accepted only in Markdown while the validator reads YAML frontmatter `status`. Normalize `specs/53-onboarding-organization-proposal/spec.md` to the repository's accepted-spec contract before the next audit. Because that is outside `docs/`, the next substantive audit must cover the resulting new head.
4. **Informational — previously identified lifecycle/production-owner gaps are materially addressed in source.** The current head adds a live lifecycle-state check before presentation and connected test code that instantiates the production proposal owner, covers paused binding, Busy/Started Review behavior, and navigation. Those improvements do not override Findings 1–3.

**Verdict: NO-GO.** Do not mark PR #95 ready for merge on this audit. The implementation should address the provenance ordering bug and evidence/criteria-contract blockers, run the required current-head CI/connected surface, and receive a fresh independent audit.