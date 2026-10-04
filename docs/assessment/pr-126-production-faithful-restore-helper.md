# High-risk audit: PR #126 production-faithful restore helper

> Status: accepted
> Audit date: 2026-08-24

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/126
- Head SHA: 8413289a820cbcc8c31e3be00290a4ce43aa5fa9
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32653421148
- Criteria: specs/58-serialize-runtime-restores/spec.md AC-4, AC-5, AC-7

## Scope

This audit is limited to PR #126 at head
`8413289a820cbcc8c31e3be00290a4ce43aa5fa9`, which closes Issue #120. The
diff contains no production source change. It updates the
`RestoreLeaseSerializationTest` instrumentation fixture/matrix, adds the
class to the API 36 shared-writer CI command, and records the investigation in
the Issue #58 plan plus the CI portfolio/quality documentation.

The independent source review covered Issue #120 and parent Issue #115,
accepted spec #58 and plan, `CONTEXT.md`, `DESIGN.md`, the quality/building and
GitHub workflow documents, the relevant ADRs, the PR diff, and the production
seams in `LawnchairBackup.restore`, `RestoreDbTask.performRestore`,
`ModelDbController.closeActiveHelperForRestore/getDb`, and the restore reload
helpers. The production path closes the active helper before recursively
replacing the database directory, constructs a fresh controller, sanitizes,
and reloads under the restore-family lease; PR #126 intentionally adds
regression evidence without changing that path.

The test matrix independently verified:

- main-file-only deletion with same-controller lazy reopen;
- complete `-journal`, `-wal`, and `-shm` file-set cleanup and raw
  replacement with same-controller reopen; and
- the same complete replacement observed through a fresh controller, the
  practical in-process analogue of a fresh process.

The exact pre-fix API 36 failure is recorded in
`specs/58-serialize-runtime-restores/plan.md`: `after.execSQL("SELECT 1")`
entered `nativeExecuteForChangedRowCount` and failed because API 36 requires a
query to use `rawQuery`. The corrected test uses `rawQuery` and closes its
cursor, so the failure is classified as a test oracle/platform API mismatch,
not as a production restore defect.

## Criteria check

- AC-4 (active helper closed before replacement and fresh helper reopened):
  PASS. The focused tests assert the old database is closed, the controller's
  helper is cleared, raw replacement is possible, and a distinct usable
  helper/controller observes the replacement marker. The complete sidecar
  cleanup is asserted before and after every test case.
- AC-5 (failure boundaries leave no silent partial restore): PASS for the
  Issue #120 regression surface. Existing sanitization fault injection still
  asserts the documented `false` result and coordinator release; fixture
  cleanup now covers the full SQLite file set. PR #126 does not claim to add
  process-death or mid-copy fault injection, which remain outside this
  follow-up's reproduced oracle.
- AC-7 (style and relevant suites pass): PASS. The PR-event CI run is pinned
  to the audited head SHA; its required `final-status` and source jobs were
  checked via GitHub Actions after completion. The API 36 shared-writer job
  includes `RestoreLeaseSerializationTest`.

## Executed test surface

Independent commands and results against the audited head:

```text
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.RestoreLeaseSerializationTest'
  -> BUILD SUCCESSFUL; API 36 AVD; 11 tests, 0 failures, 0 errors, 0 skipped

./gradlew spotlessCheck
  -> BUILD SUCCESSFUL

python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK

python3 tools/repo-contract/test_validate_repo_contract.py
  -> 11 tests passed

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> 47 tests passed

gh pr view 126 --repo nunu1733/NunuLauncher --json headRefOid,statusCheckRollup,url
gh run view 32653421148 --repo nunu1733/NunuLauncher --json status,conclusion,jobs,url
  -> PR-event CI run on the audited SHA; final-status and required source jobs
     verified green after the instrumentation jobs completed
```

The API 36 result file reports `tests="11"`, `failures="0"`,
`errors="0"`, and `skipped="0"`. The local run specifically exercised all
three Issue #120 replacement cases as well as the restore-family coordinator,
lease-first, failure, and fallback tests.

## Findings

Verdict: **pass**. No blocking production defect, test-oracle defect, cleanup
gap, or CI portfolio mismatch was found at the audited head. The exact API 36
exception is a corrected test artifact, and the production restore seam is
unchanged.

The process-death/mid-raw-copy scenarios listed in the broader accepted spec
remain future evidence gaps, not findings against this Issue #120 follow-up:
the PR's scope is to reproduce the existing helper close/reopen failure using
production file-replacement semantics and determine its cause. No production
fix is justified by the observed `execSQL("SELECT 1")` failure.
