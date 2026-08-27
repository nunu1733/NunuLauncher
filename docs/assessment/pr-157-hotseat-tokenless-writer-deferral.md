# High-risk audit: PR #157 Hotseat tokenless writer deferral

> Status: final
> Audit date: 2026-08-27

Machine-checked fields:

- Auditor: Independent audit session, separate from the implementation session; no implementation changes made by the auditor
- PR: https://github.com/nunu1733/NunuLauncher/pull/157
- Head SHA: 6ef8cb1e40834be8bf23f9afcb2129ab72496384
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33071961999 — verified via GitHub API as `event=pull_request`, `pull_requests=[157]`, exact audited head SHA, and completed `success`; `final-status`, `check-style`, `validate-repo-contract`, `build-debug-apk`, organizer unit tests, and all organizer instrumentation jobs passed. HighRiskGate run `33071961977` is stale: it evaluated the previous audit record before this current-head re-audit record was available.
- Criteria: specs/156-hotseat-tokenless-writer-deferral/spec.md AC-001 AC-002 AC-003 AC-004 AC-005 AC-006 AC-007

## Scope

Audited current PR head `main...6ef8cb1e40834be8bf23f9afcb2129ab72496384` (8 implementation/spec files plus this audit record):

- `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java`
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java`
- `tests/organizer-instrumentation/com/android/launcher3/hybridhotseat/HotseatRestoreAdmissionTest.java`
- `.github/workflows/ci.yml` and `tools/repo-contract/validate_writer_inventory.py`
- Issue #156 spec/plan and the Issue #60 writer-inventory assessment

The runtime review covered call-time FIFO reservation even with an empty coordinator, the
stable-reservation barrier behind every current-holder kind, head-only FIFO draining across
holder reacquisition, execution-time atomic `MODEL_WRITER` admission, re-deferral, same-thread
transaction re-entry, exception release, exact-token correlated reload progress, real
`ModelWriter` backup-before-migration ordering, and unchanged backup/restore database semantics.
No second lock, lease kind, schema, backup format, public API, or recovery protocol change was
found.

The only commit after the reviewed implementation head `efa9dc9e500ae08e18f487d887356539f8137d11`
is the docs-only audit-record commit; no production or test source changed after the independent
code audit.

## Criteria check

- [x] **AC-156-01:** `createBackup` and `restoreBackup` route through the coordinator. The
      coordinator atomically grants/re-enters `MODEL_WRITER` or registers a FIFO continuation;
      the helper does not enter `acquireBlockingQuietly` on `MODEL_EXECUTOR`.
- [x] **AC-156-02:** The deterministic schedule-without-holder, acquire-organizer-before-admission
      race defers the helper and permits the exact-token correlated reload to complete before
      organizer release.
- [x] **AC-156-03:** The tests cover one-time body execution, re-deferral after a new holder,
      exception unwinding, and continuation isolation. FIFO release posts back to
      `MODEL_EXECUTOR` rather than running helper DB work on the releasing thread.
- [x] **AC-156-04:** Real API 36 tests preserve uncontended backup creation, missing-table restore,
      existing-backup restore/drop-after-use, cache refresh, and reload behavior.
- [x] **AC-156-05:** The executable inventory scans `quickstep/src` and allowlists the helper's
      controller calls with the atomic-admission reason; the inventory command passed with zero
      errors and warnings.
- [x] **AC-156-06:** The branch records fresh-workspace evidence with tokenless deferral and zero
      `MODEL_RELOAD_FAILED` occurrences after the starvation window, while separately attributing
      the A7 layout verification result to Issue #155.
- [x] **AC-156-07:** The actual `ModelWriter` regressions cover holder-present, empty-gate
      holder-appearance, holder-reacquisition, baseline `MODEL_WRITER`, and release-thread
      affinity orderings; each preserves the pre-migration rank in the backup table and the
      post-migration rank in `Favorites`.

## Executed test surface

Independent local execution on macOS arm64, JDK 21.0.12, Android SDK Platform 36.1 / Build Tools
36.1.0, and the connected API 36 emulator:

- `git diff --check main...HEAD` — passed.
- `./gradlew --no-configuration-cache spotlessCheck` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --rerun-tasks` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache assembleLawnWithQuickstepGithubDebug` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest` — 14/14 passed.
- `./gradlew --no-configuration-cache connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.ModelWriterTransactionReentryTest,com.android.launcher3.organizer.LayoutWriteCoordinatorTest,com.android.launcher3.organizer.BinderOperationFutureTest,com.android.launcher3.organizer.NestedTransactionTest,com.android.launcher3.organizer.OrganizerReloadSupersessionTest,com.android.launcher3.organizer.RestoreLeaseSerializationTest,com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest` — 51/51 passed.
- `python3 tools/repo-contract/validate_writer_inventory.py` — passed: 19 allowlisted writer files, 1,437 source files, 0 errors, 0 warnings.
- `python3 tools/repo-contract/validate_repo_contract.py` — passed.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — passed.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — passed: 47 tests.

The qualifying PR CI run `33071961999` verified the exact audited head and completed all source
jobs successfully, including `final-status` and the shared-writer instrumentation job. The
independent local evidence above passes all Issue #156-specific surfaces; the current head adds
only the audit record shown above.

## Findings

No blocking runtime, data-safety, ordering, or test-coverage finding was identified on the
audited head. The previous ordering blockers are resolved by call-time stable FIFO reservation,
the reservation barrier for later tokenless `MODEL_WRITER` work, head-only draining, and
execution-time atomic admission.

One merge prerequisite remains outside the code finding:

1. HighRiskGate run `33071961977` must rerun after this current-head audit record is committed and
   pushed. Its failure is caused by the stale record it evaluated, not by a source CI failure.

Conclusion: **PASS for the audited code, Issue #156 acceptance criteria, and exact-head PR CI;
merge remains BLOCKED only until HighRiskGate reruns successfully against this re-audit record.**
