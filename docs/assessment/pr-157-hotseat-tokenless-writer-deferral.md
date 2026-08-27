# High-risk audit: PR #157 Hotseat tokenless writer deferral

> Status: final
> Audit date: 2026-08-27

Machine-checked fields:

- Auditor: Independent audit session, separate from the implementation session; no implementation changes made by the auditor
- PR: https://github.com/nunu1733/NunuLauncher/pull/157
- Head SHA: 6f90d08b4433ff0afbb0cbdb17f044e97ee985ca
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33041438862 — verified via GitHub API as `event=pull_request`, `pull_requests=[157]`, exact audited head SHA, completed `success`; `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`, and all connected instrumentation source jobs completed successfully.
- Criteria: specs/156-hotseat-tokenless-writer-deferral/spec.md AC-001 AC-002 AC-003 AC-004 AC-005 AC-006 AC-007

## Scope

Audited `main...6f90d08b4433ff0afbb0cbdb17f044e97ee985ca` (8 changed files):

- `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java`
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java`
- `tests/organizer-instrumentation/com/android/launcher3/hybridhotseat/HotseatRestoreAdmissionTest.java`
- `.github/workflows/ci.yml` and `tools/repo-contract/validate_writer_inventory.py`
- Issue #156 spec/plan and the Issue #60 writer-inventory assessment

The runtime review covered call-time FIFO reservation, execution-time atomic `MODEL_WRITER`
admission, re-deferral, same-thread transaction re-entry, exception release, exact-token
correlated reload progress, real `ModelWriter` backup-before-migration ordering, and the
unchanged backup/restore database semantics. No second lock, lease kind, schema, backup format,
public API, or recovery protocol change was found.

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
- [x] **AC-156-07:** The actual `ModelWriter` regression preserves the pre-migration rank in the
      backup table and the post-migration rank in `Favorites`; the deterministic executor test
      also verifies release-thread affinity.

## Executed test surface

Independent local execution on macOS arm64, JDK 21.0.12, Android SDK Platform 36.1 / Build Tools
36.1.0, and the connected API 36 emulator:

- `git diff --check main...HEAD` — passed.
- `./gradlew --no-configuration-cache spotlessCheck` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache assembleLawnWithQuickstepGithubDebug` — `BUILD SUCCESSFUL`.
- `./gradlew --no-configuration-cache connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest` — 10/10 passed.
- `./gradlew --no-configuration-cache connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.ModelWriterTransactionReentryTest,com.android.launcher3.organizer.LayoutWriteCoordinatorTest,com.android.launcher3.organizer.BinderOperationFutureTest,com.android.launcher3.organizer.NestedTransactionTest,com.android.launcher3.organizer.OrganizerReloadSupersessionTest,com.android.launcher3.organizer.RestoreLeaseSerializationTest,com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest` — 47/47 passed.
- `python3 tools/repo-contract/validate_writer_inventory.py` — passed: 19 allowlisted writer files, 1,437 source files, 0 errors, 0 warnings.
- `python3 tools/repo-contract/validate_repo_contract.py` — passed.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — passed.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — passed: 47 tests.

The qualifying PR CI run `33041438862` independently verified the exact head and completed all
source jobs and `final-status` successfully. Its shared-writer instrumentation job ran the same
7-class command and reported 47 tests, all successful.

## Findings

No blocking runtime, data-safety, ordering, or test-coverage finding was identified on the
audited head. The previous `createBackup` ordering blocker is resolved by call-time FIFO
reservation plus execution-time atomic admission.

Two documentation follow-ups are non-blocking for the code audit:

1. The PR body and earlier Issue/spec evidence state that the shared-writer suite has 23 tests,
   while the exact qualifying CI run and this independent run execute 47 tests. The evidence
   count should be corrected to 47.
2. `spec.md` currently says `status: implemented` while PR #157 and Issue #156 remain open. The
   repository workflow defines `implemented` as the post-merge state; retain `accepted` until
   merge, then transition it to `implemented`.

Conclusion: **PASS for the audited code and acceptance criteria; HighRiskGate requirements are
satisfied once this record is committed and the gate reruns on the resulting docs-only head.**
