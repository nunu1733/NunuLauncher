# High-risk audit: PR #94 manual full-organization vertical slice

> Status: accepted
> Audit date: 2026-08-20

- Auditor: Faraday — independent audit subagent/session separate from implementation; no code or documentation changes were made by the auditor.
- PR: https://github.com/nunu1733/NunuLauncher/pull/94
- Head SHA: 19e47c084738ddd527b3d3165dab1297d0f1bc43
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32369651003
- Criteria: specs/52-manual-full-organization-vertical-slice/spec.md FR-002, FR-003, FR-004, FR-005, FR-006, FR-015, NFR-001, NFR-002, NFR-005, NFR-007, NFR-009, NFR-011

## Scope

This is an independent re-audit of PR #94 at the exact implementation head `19e47c084738ddd527b3d3165dab1297d0f1bc43`. The PR carries `risk: layout-data`. The audit reviewed the repository rules, `docs/project/github-workflow.md`, the accepted Issue #52 spec and plan, relevant design/ADR/quality documents, Issue #52 and its comments, PR #94 and its review, and the complete PR diff.

The reviewed production path is `ManualOrganizationRun` → `ManualOrganizationApplication` → `LayoutApplicationModule.applyWithRunId` → the existing application protocol. Recovery is `inspectRecovery` → opaque `RecoveryPreviewConfirmation` → application-owned confirmation and private `RecoveryRequest` construction. The UI does not write `favorites`, use `ModelWriter`, access `RecoveryStorePort` directly, access raw recovery records, or construct `RecoveryRequest` objects. No schema, permission, network, telemetry, or Deck-runtime mutation is in scope.

## Criteria check

- **MFO-AC-01 / FR-006:** **GO.** The current Issue #52 production E2E covers a real Launcher DB fixture and production capture, planner preview, no database write before confirmation, application, model reload and verification, and recovery. CI run #194 completed the production/manual instrumentation surface successfully; local API 36.1 production E2E passed 2/2.
- **MFO-AC-02 / FR-002, NFR-001:** **GO.** The production path consumes the accepted capture/planner/application seams. Source review and production E2E show no UI-local policy, second planner, or direct UI database access.
- **MFO-AC-03 / FR-003:** **GO.** Empty/no-change and no-write behavior are covered by the production path and coordinator/UI tests; confirmation is required before application.
- **MFO-AC-04 / FR-004:** **GO.** The real production stale-confirmation test mutates `favorites.MODIFIED` after preview, yields `State.Stale`, and verifies no additional write. UI cancellation and Back cancellation are also covered.
- **MFO-AC-05 / FR-005:** **GO.** Production apply/recovery E2E, recovery-failure UI coverage, rollback/checkpoint behavior, and the four-phase recovery smoke test passed. The smoke evidence covers READY, AROUND_COMMIT, COMMITTED_UNVERIFIED, and RESTORING.
- **MFO-AC-06 / NFR-002:** **GO.** The materializer emits preserve/update/insert actions, does not expose deletion or lock mutation through the manual flow, and preserves the application boundary. Tests cover the relevant no-loss/no-write behavior.
- **MFO-AC-07 / FR-002, FR-003, NFR-007:** **GO.** Scope, counts, typed reasons, warnings, unplaced state, and constraints are projected through the review model and exercised by the production and UI test surfaces.
- **MFO-AC-08 / FR-015, NFR-011:** **GO.** One run ID is used across the coordinator/application path and diagnostics remain fail-open through the closed diagnostics port; source and test evidence cover the required result/diagnostic behavior.
- **MFO-AC-09 / NFR-005:** **GO.** The connected recovery smoke test repeatedly force-stops the app and verifies lifecycle reconciliation before the next phase: `PRUNED`, `VERIFIED`, and `RESTORED` states all completed successfully across 4/4 phases.
- **MFO-AC-10 / NFR-009:** **GO.** API 36.1 UI instrumentation passed 9/9. The surface verifies Compose accessibility semantics and click actions, real dispatcher Back behavior, focus restoration, 200% font-scale readability, and emulator shell `DPAD_DOWN` then `TAB` traversal to confirm/cancel actions. Five UI evidence screenshots were uploaded by CI for start, preview/confirmation, success, stale, and recovery-failure surfaces.
- **MFO-AC-11 / all listed FR/NFR:** **GO.** CI run #194 succeeded, including `final-status`, source checks, build, style, repository contract validation, and Issue #52 instrumentation (11/11). This audit record targets the exact code head and is independently authored by the audit session.

## Executed test surface

The following checks were executed or independently verified for `19e47c084738ddd527b3d3165dab1297d0f1bc43`.

- `./gradlew spotlessCheck` — passed; `BUILD SUCCESSFUL`.
- `ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH ./gradlew -PandroidSerialNumber=emulator-5554 connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationProductionE2EInstrumentationTest --console=plain` — passed on the Android SDK Platform 36.1 / Build Tools 36.1.0 environment; 2/2 tests passed on `nunu_qpr2_api36_1`.
- `ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH ./gradlew -PandroidSerialNumber=emulator-5554 connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest --console=plain` — passed on API 36.1; 9/9 tests passed.
- `ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH ./tools/organizer-recovery-smoke.sh --serial emulator-5554` — passed on the exact current head; READY, AROUND_COMMIT, COMMITTED_UNVERIFIED, and RESTORING all completed with typed evidence, 4/4 phases.
- `git diff --check` — passed with no whitespace errors.
- GitHub PR CI run [#194 / `32369651003`](https://github.com/nunu1733/NunuLauncher/actions/runs/32369651003) — passed; `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`, and `organizer-instrumentation-tests` succeeded. Issue #52 instrumentation completed 11/11 with 0 failures or skips.
- CI UI evidence artifact: [Issue #52 UI evidence](https://github.com/nunu1733/NunuLauncher/actions/runs/32369651003/artifacts/9406934846) — five screenshots uploaded successfully.
- Independent audit conclusion from Faraday — AC-10 GO and current-head CI/high-risk evidence rechecked; no code or documentation changes made by the auditor.

## Findings

The prior NO-GO findings are resolved at the current head: real production capture/apply/verification/recovery evidence, stale-confirmation no-write evidence, connected process-death lifecycle reconciliation, API 36.1 accessibility/navigation coverage, UI screenshot evidence, and current-head CI evidence are now present. The previous High-riskGate failure was caused by the audit record targeting an older head; this record targets the exact code head and references the successful PR CI run above.

No blocking production safety defect was found in the independent re-audit. The repository remains intentionally unmerged; this audit establishes the evidence required for merge readiness and does not perform the merge.

## Verdict

**GO / merge-ready.** PR #94 satisfies the Issue #52 acceptance evidence reviewed here, the API 36.1 validation surface, and the independent high-risk audit requirements at `19e47c084738ddd527b3d3165dab1297d0f1bc43`.
