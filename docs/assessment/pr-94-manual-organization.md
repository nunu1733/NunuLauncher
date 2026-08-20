# High-risk audit: PR #94 manual full-organization vertical slice

> Status: accepted
> Audit date: 2026-08-20

- Auditor: Independent audit session, separate from the implementation session; no implementation changes were made by this auditor.
- PR: https://github.com/nunu1733/NunuLauncher/pull/94
- Head SHA: 16cd336f6168bb82c6c43a3af4b654a5d85e3a0f
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32354137537
- Criteria: specs/52-manual-full-organization-vertical-slice/spec.md FR-002, FR-003, FR-004, FR-005, FR-006, FR-015, NFR-001, NFR-002, NFR-005, NFR-007, NFR-009, NFR-011

## Scope

Audited PR #94 against base `main` commit `c7cbfa478971b2218da5272d197fc2a88f3da15f` and audited head `16cd336f6168bb82c6c43a3af4b654a5d85e3a0f`. The scope is the 12-file PR diff: manual-organization strings; `OrganizationPlanMaterializer`; the `LayoutApplicationModule` manual composition/materialization/run-ID and recovery-preview entry points; `ManualOrganizationRun`; Home Screen preference entry and typed navigation; the Issue #52 spec/plan; the coordinator unit tests; and the Compose/instrumentation test fixture.

The runtime write path reviewed is `ManualOrganizationRun` → `ManualOrganizationApplication` → `LayoutApplicationModule.applyWithRunId` → the existing `ApplyProtocol`. Recovery was reviewed as `inspectRecovery` → opaque `RecoveryPreviewConfirmation` → application-owned `confirmRecoveryPreview` → private `RecoveryRequest` construction → existing recovery protocol. No direct UI access to `favorites`, `ModelWriter`, `RecoveryStorePort`, raw recovery records, or `RecoveryRequest` was found in the audited diff. No schema, migration, permission, network, telemetry, or Deck-runtime change is in scope.

The final dismissal changes were specifically included: pre-admission `dismiss()`/cancel invalidates the active operation and emits `USER_CANCELLED`; admitted application work returns `ApplicationInProgress`; system Back is consumed during that interval; and terminal/no-active dismissal returns `NoActiveOperation` without clearing the terminal state.

## Criteria check

- **MFO-AC-01 / FR-006:** **Partial.** The source review and independent organizer JVM tests cover explicit start, capture, planning, preview, confirmation, materialization, apply admission, result projection, and the shared application seam. CI run #186 has successful `final-status`, `organizer-unit-tests`, `check-style`, and `build-debug-apk`. A real Issue #52 API 36.1 end-to-end execution was not independently run; `adb` is unavailable, and the CI instrumentation job is the existing Issue #83 production-seam test rather than the new manual-organization instrumentation surface.
- **MFO-AC-02 / FR-002, NFR-001:** **Partial/pass by source boundary.** `ProductionOrganizationInputComposer` is used through `LayoutApplicationModule`; the UI/coordinator does not construct fallback policy or read/write the Launcher database directly. This audit did not find a second planner or direct UI writer. Runtime production capture was not exercised on a device.
- **MFO-AC-03 / FR-003:** **Pass for the audited coordinator seam; integration evidence incomplete.** The independent unit test `emptyPlanDoesNotMaterializeOrApply` verifies zero materialization/apply calls for an empty plan, and the code returns `NoChanges` before application. A real DB/model-reload zero-write test was not run.
- **MFO-AC-04 / FR-004:** **Partial.** Unit tests cover stale materialization, cancellation during capture, cancellation before admission, final dismissal before admission, and the final admitted-apply dismissal/cancel race. Connected stale/back/device evidence was not run.
- **MFO-AC-05 / FR-005, NFR-005:** **Partial.** The unit surface retains all `ApplyResult` families and the UI maps them to distinct messages; recovery-preview/result variants are also mapped. Fault-injected application/recovery and post-apply verification behavior were not independently exercised through a test database or device.
- **MFO-AC-06 / NFR-002:** **Partial/pass by static review.** The materializer emits only preserve/update/insert actions, and the manual UI does not expose lock mutation or deletion. No dedicated independent negative application test for lock/profile/deletion invariants was added or run in this audit.
- **MFO-AC-07 / FR-002, FR-003, NFR-007:** **Partial.** The coordinator summary and Compose fixture retain scope, moved/preserved reasons, warnings, unplaced/rejection reasons, and constraint counts; the source path keeps the accepted typed input. Device/runtime rendering and the full real-layout fixture matrix remain unverified.
- **MFO-AC-08 / FR-015, NFR-011:** **Partial.** The coordinator emits the required manual phase events with one run ID and wraps diagnostic emission fail-open. The independent unit tests verify phase/run-ID correlation, but no independent logger-failure and privacy-negative corpus was executed, and no runtime journal evidence was produced.
- **MFO-AC-09 / NFR-005:** **Not demonstrated.** The PR's `recreationLikeRecompositionRetainsPreviewWithoutReplayingWrite` reuses the same in-memory coordinator; it is not process death plus application lifecycle reconciliation. No connected process-recreation/restart test was run.
- **MFO-AC-10 / NFR-009:** **Partial.** The source adds explicit focus targets, polite progress live regions, a Back handler, and fixture coverage for focus and 200% font scale. TalkBack behavior, keyboard/switch traversal, real Back navigation, clipping/reflow across the complete surface, and accessibility announcements were not independently executed.
- **MFO-AC-11 / all listed FR/NFR:** **Not satisfied at audit time.** Local contract, formatting, unit, and debug-build checks passed, and the specified CI jobs passed on the audited head. However, connected instrumentation and UI evidence remain absent, and the PR-triggered High-risk gate run #119 failed because the required audit file was missing at that time. The existing successful CI run #186 is not evidence that the High-risk gate passed.

## Executed test surface

All commands below were executed independently in `/Users/nunu/Documents/work/NunuLauncher` at head `16cd336f6168bb82c6c43a3af4b654a5d85e3a0f`; the worktree was clean before execution.

- `python3 tools/repo-contract/validate_repo_contract.py` — passed (`repository contract OK`).
- `python3 tools/repo-contract/test_validate_repo_contract.py` — passed; 11 tests, 0 failures. Its intentional invalid-fixture subprocess output reported the expected failure and the test process exited successfully.
- `git diff --check c7cbfa478971b2218da5272d197fc2a88f3da15f...16cd336f6168bb82c6c43a3af4b654a5d85e3a0f` — passed with no output.
- `./gradlew spotlessCheck --console=plain` — passed; `BUILD SUCCESSFUL`.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --console=plain` — passed; `BUILD SUCCESSFUL`.
- `./gradlew assembleLawnWithQuickstepGithubDebug --console=plain` — passed; `BUILD SUCCESSFUL`.
- `command -v adb` / `adb version` — not available (`adb: command not found`); connected API 36.1 instrumentation was not run.

GitHub evidence independently checked for CI run #186:

- `gh run view 32354137537 --repo nunu1733/NunuLauncher --json databaseId,number,name,event,status,conclusion,headSha,headBranch,jobs,url` — completed/success, `event=pull_request`, head SHA matches the audited SHA.
- `gh api repos/nunu1733/NunuLauncher/actions/runs/32354137537 --jq '{id,run_number,name,event,head_sha,head_branch,pull_requests,workflow_id}'` — run #186 is associated with PR #94 and the audited base/head refs.
- The run's `organizer-unit-tests`, `check-style`, `build-debug-apk`, and `final-status` jobs all completed with `success`; none of these four jobs was skipped. The run's separate instrumentation job was named `Run Issue 83 production seam instrumentation` and therefore was not treated as Issue #52 E2E evidence.
- `gh run view 32354142257 --repo nunu1733/NunuLauncher --log-failed` — High-risk gate run #119 failed with `no docs/assessment/pr-94-<slug>.md audit record for this PR`; this is recorded as an outstanding gate result, not as a successful check.

## Findings

1. **Release-gate blocker — connected evidence is missing.** `adb` is unavailable, so the required API 36.1 manual-run E2E, real Back behavior, process recreation/reconciliation, and device accessibility evidence could not be independently verified. The PR plan and PR body also record these surfaces as not run or still required.
2. **Release-gate blocker — High-risk gate was red for the audited head.** Run #119 failed because this audit record did not yet exist. This file supplies the missing audit artifact, but no subsequent successful High-risk gate run was available during this audit; the gate must be rerun after the audit record is committed/available to CI.
3. **Evidence limitation — the new instrumentation file is not proof of connected execution.** Its Compose fixture covers preview, cancellation, focus, 200% font scale, and safe-terminal recomposition, but its same-process `recreationLikeRecomposition` test is not process death/reconciliation. CI run #186's instrumentation job covers Issue #83's production seam and uses API 35, not this Issue #52 surface/API 36.1.
4. **Issue state remains incomplete.** GitHub Issue #52 is still open and labeled `status: blocked`; the issue-level completion state and acceptance checklist were not independently verified as closed. PR #94 is still draft. These facts prevent an issue-level or merge-ready conclusion even though the requested CI source jobs are green.

No additional production-code defect was asserted from the final dismissal/Back/terminal-state patch: its source behavior and focused unit tests align with the latest review corrections. That is a code-review observation only, not a substitute for the missing connected/runtime evidence.

## Verdict

**NO-GO / not merge-ready.** The audited head has successful CI run #186 source jobs and passes the independent local contract, format, organizer-unit, and debug-build checks. The verdict remains not merge-ready because the independent high-risk gate is not green, connected API 36.1/manual-flow and lifecycle/accessibility evidence is unavailable, and the Issue #52 completion state remains open/blocked. Do not treat this audit record alone as approval to merge.
