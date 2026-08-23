# High-risk audit: PR #128 production-faithful restore helper coverage

> Status: accepted
> Audit date: 2026-08-24

- Auditor: Luna-extraHigh independent audit session (separate from the implementation session)
- PR: https://github.com/nunu1733/NunuLauncher/pull/128
- Head SHA: 4faa67cdcb86ec6b2d9c41f7c9052b9917f53d0a
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32655221944
- Criteria: specs/58-serialize-runtime-restores/spec.md AC-4, AC-7

## Scope

This audit covers PR #128 at head `4faa67cdcb86ec6b2d9c41f7c9052b9917f53d0a`,
Issue #120 and its parent investigation #115, the accepted restore contract in
`specs/58-serialize-runtime-restores/spec.md`, the related writer-admission
evidence in spec #60, and the changed files:

- `.github/workflows/ci.yml`
- `docs/engineering/ci-test-portfolio.md`
- `docs/engineering/quality-strategy.md`
- `specs/58-serialize-runtime-restores/plan.md`
- `tests/organizer-instrumentation/com/android/launcher3/organizer/RestoreLeaseSerializationTest.java`

The PR contains no production source, API, schema, migration, or database
write-path change. The production seam was checked for comparison: the
existing `LawnchairBackup.restore` holds `BACKUP_RESTORE` across quiesce,
helper close, recursive database-directory replacement, file writes, fresh
`ModelDbController` construction, `performRestore`, and reload; the existing
`RestoreDbTask.performRestore` acquires or re-enters the restore lease before
`controller.getDb()`; and `ModelDbController.closeActiveHelperForRestore`
drops the active helper before raw replacement.

## Criteria check

- Issue #120 exact failure evidence is recorded in
  `specs/58-serialize-runtime-restores/plan.md`: API 36 rejected
  `after.execSQL("SELECT 1")` through the changed-row-count path, with the
  exact `SQLiteException` and stack. The evidence identifies this as a test
  oracle artifact, not a helper-close/reopen or production restore failure.
- The fixture is cleaned in both `@Before` and `@After`. Cleanup enumerates the
  main database plus `-journal`, `-wal`, and `-shm`, asserts deletion success
  when an artifact exists, and asserts that every artifact is absent.
- The regression matrix covers main-file-only deletion with same-controller
  lazy reopen, complete raw file-set replacement with same-controller reopen,
  and complete raw file-set replacement with a fresh controller. Replacement
  identity is verified through a marker table. The API 36 query oracle uses
  `rawQuery` and closes its cursor.
- The shared-writer API 36 lane includes
  `RestoreLeaseSerializationTest` alongside the existing coordinator,
  transaction, and reload suites. The lane remains an independent clean
  emulator job and does not add a second restore seam.
- Spec #58 AC-4 is satisfied by the helper identity/closed-handle assertions
  and the same/fresh-controller reopen matrix. Spec #58 AC-7 is satisfied by
  the successful PR-associated CI merge gate and local contract checks below.

## Executed test surface

Independent checks on the audited head:

```text
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.RestoreLeaseSerializationTest'
  PASS — API 36 emulator nunu_qpr2_api36_1; 11 tests, 0 failures; BUILD SUCCESSFUL in 21s

git diff --check HEAD^ HEAD
  PASS

python3 tools/repo-contract/validate_repo_contract.py
  PASS

python3 tools/repo-contract/test_validate_repo_contract.py
  PASS — 11 tests
```

The authoritative PR-associated CI evidence is run
`32655221944`. GitHub API verification confirmed `event=pull_request`, the
audited head SHA and branch, PR association `pull_requests[].number=128`,
`conclusion=success`, and success for every job including `final-status`,
`check-style`, `organizer-unit-tests`, `build-debug-apk`,
`validate-repo-contract`, and `organizer-instrumentation-shared-writer-tests`.

## Findings

No blocking production, test-oracle, CI-lane, or restore-file-set finding was
identified. The original API 36 failure was a misuse of `execSQL` for a query
and is corrected at the test oracle; all three replacement/reopen cases and
the complete 11-test class pass on API 36. The PR does not modify production
restore behavior.

The fresh-controller case is an in-process proxy for process recreation, as
Issue #120 permits where practical; it does not claim to provide process-death
mid-restore evidence. That remains outside this PR and does not block the
Issue #120 acceptance criteria.
