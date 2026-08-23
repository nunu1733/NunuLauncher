# High-risk audit: PR #124 nested SQLiteTransaction whole-unit contract

> Status: accepted (independent audit)
> Audit date: 2026-08-24

- Auditor: independent audit session, separate from the implementing session; no implementation or review-fix changes were made by this auditor
- PR: https://github.com/nunu1733/NunuLauncher/pull/124
- Head SHA: 1c13edcdaf8278042a09d0d02c88052237c64378
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32651481503 — verified through the GitHub API: `event=pull_request`, `head_sha=1c13edcdaf8278042a09d0d02c88052237c64378`, `status=completed`, `conclusion=success`; all required jobs and `final-status` succeeded
- Criteria: specs/60-executor-writer-admission-audit/spec.md AC-06 (nested `SQLiteTransaction` whole-unit commit/rollback and outer-lease lifetime); Issue #117 acceptance criteria mapped to AC-06

## Scope

I audited the complete PR #124 diff from base
`880d489a8f73c01fcc66ce8042f7ce8bc990a4cf` through the exact head above.
The eight changed files are the CI lane, Issue #60 assessment/spec/plan and
portfolio documentation, the historical PR #78 erratum, and the two existing
instrumentation test classes. No production source, database schema, migration,
permission, dependency, or public API changed.

The runtime seam was independently read at the audited source boundary:
`LauncherDbUtils.SQLiteTransaction` calls Android
`beginTransaction`/`setTransactionSuccessful`/`endTransaction`, while
`ModelDbController.newTransaction()` owns the existing coordinator lease until
the transaction closes. The PR does not add SAVEPOINT behavior or alter lease
admission/re-entry. The relevant repository rules, Issue #117 and its comments,
the accepted Issue #60 spec/plan, `AGENTS.md`, `DESIGN.md`, quality strategy,
and related organizer recovery/lock ADRs were also checked.

## Criteria check

- **AC-06 whole-unit commit — PASS.**
  `outerAndInnerTransactionCommitAsOneUnit` marks both nested scopes successful
  and asserts both rows persist after the outer close.
- **AC-06 whole-unit rollback — PASS.**
  `innerCloseWithoutCommitRollsBackWholeUnit` closes the inner scope without
  success, calls `outer.commit()` afterward, and asserts that both outer and
  inner writes are absent. This is the required Android contract and does not
  claim SAVEPOINT isolation.
- **AC-06 outer lease lifetime — PASS.**
  `leaseHeldUntilOuterClose` proves a competing lease is rejected after plain
  inner close and accepted only after the outer close.
- **AC-06 re-entry and exception unwind — PASS.**
  `reentrantLeaseInnerCloseDoesNotReleaseOuterLease` verifies recursion remains
  held through the inner close, and
  `exceptionInsideInnerPropagatesAndOuterCloseReleasesLease` verifies exception
  propagation plus eventual outer lease release.
- **Issue #117 documentation and regression coverage — PASS.**
  The live spec, javadoc, tests, assessment errata, and CI portfolio use the
  whole-unit contract. The same `NestedTransactionTest` runs in the API 36
  shared-writer lane and the API 35 compatibility lane.

## Executed test surface

The following read-only commands were executed by this independent audit:

```text
gh pr view 124 --repo nunu1733/NunuLauncher --json baseRefOid,headRefOid,files,labels,statusCheckRollup
  -> PR head matches 1c13edcdaf8278042a09d0d02c88052237c64378; label risk: layout-data

git diff --check 880d489a8f73c01fcc66ce8042f7ce8bc990a4cf..1c13edcdaf8278042a09d0d02c88052237c64378
  -> clean

gh run view 32651481503 --repo nunu1733/NunuLauncher \
  --json status,conclusion,headSha,url,jobs
  -> completed/success on the audited head; all 12 jobs success:
     changes, organizer-unit-tests, organizer-instrumentation-api35-tests,
     organizer-instrumentation-shared-writer-tests, check-style,
     organizer-instrumentation-issue52-tests,
     organizer-instrumentation-db-migration-tests, validate-repo-contract,
     organizer-instrumentation-issue53-tests,
     organizer-instrumentation-issue99-tests, build-debug-apk, final-status

gh run view 32651481503 --repo nunu1733/NunuLauncher \
  --job 97223696052 --log
  -> API 36 job executed the shared-writer command containing
     com.android.launcher3.organizer.NestedTransactionTest; 21 tests finished
     and Gradle reported BUILD SUCCESSFUL.

gh run view 32651481503 --repo nunu1733/NunuLauncher \
  --job 97223696048 --log
  -> API 35 job executed the command containing
     com.android.launcher3.organizer.NestedTransactionTest; the job and its
     instrumentation command completed successfully with BUILD SUCCESSFUL.

rg -n -i "savepoint|nested transaction|whole-unit|rollback" \
  AGENTS.md CONTEXT.md DESIGN.md docs/adr docs/engineering/quality-strategy.md \
  docs/assessment specs/60-executor-writer-admission-audit tests/organizer-instrumentation
  -> no live test/spec/javadoc savepoint-isolation claim remains; historical
     wording is explicitly marked as the superseded Issue #117 erratum.

python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract validation passed on the checked-out repository.
python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> high-risk validator self-tests passed.
```

The CI workflow also reported the unrelated pre-existing Java deprecation
annotation warning in `quickstep/src/com/android/quickstep/TaskViewUtils.java:478`;
it did not affect any required job or the final gate.

## Findings

- **F1 — PASS: no production defect or scope expansion found.** The PR changes
  only tests, CI coverage, and the corresponding specification/audit records.
  The production wrapper and coordinator lease behavior remain unchanged.
- **F2 — PASS: platform contract is discriminatingly tested.** The rollback test
  would fail if a platform/API lane reintroduced the old inner-only SAVEPOINT
  expectation, while the all-success test protects the commit path. Lease and
  exception tests remain separate from the data rollback oracle.
- **F3 — PASS: required independent evidence is complete.** The exact PR-event
  CI run is green on the audited head, including API 35/API 36 instrumentation,
  repository-contract validation, and `final-status`.

No blocking code, test-oracle, CI, documentation, migration, or recovery
finding was identified. This record is the sole docs-only addition required to
complete the high-risk evidence for PR #124; any later non-documentation change
to the audited head requires a new independent audit and successful source CI
run.

**Verdict: PASS / GO.** Issue #117's AC-06 contract and all listed acceptance
criteria are satisfied at the exact audited head, with independent source review
and successful API 35/API 36 plus merge-gate evidence.
