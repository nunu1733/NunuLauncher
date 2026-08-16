# High-risk audit: PR #75 preserve source grid migration failure

> Status: accepted
> Audit date: 2026-08-16

- Auditor: 実装sessionとは別の独立session（solo保守の独立再実行）
- PR: https://github.com/nunu1733/NunuLauncher/pull/75
- Head SHA: 73ec4f650cbcebf97f44d107e5990c04158c6625
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/31948281216
- Criteria: specs/59-preserve-source-grid-migration-failure/spec.md AC-59-01, AC-59-02, AC-59-03, AC-59-04, AC-59-05, AC-59-06, AC-59-07, AC-59-08, NFR-001, NFR-002, NFR-012

## Scope

Reviewed `git show 73ec4f650cbcebf97f44d107e5990c04158c6625 --stat` and the
complete `main..HEAD` implementation surface: 19 files, +2442/-153. The audited
production changes are `ModelDbController`, `GridSizeMigrationUtil`, the new
journal/digest/runtime-operation types, synchronous `LauncherPrefs`, and
`LauncherDbUtils`; the complete changed instrumentation suite was also read.

The runtime path enters `ModelDbController.tryMigrateDB`, reconciles a durable
journal before target admission, takes `GRID_MIGRATION` before target-helper
creation, and makes backup, journal, source copy/placement, UNKNOWN marking,
temporary-table cleanup, and pending-finalization one target transaction. The
audit also checked the unmodified schema-33 framework upgrade path in
`DatabaseHelper.onUpgrade` because AC-59-01/07/08 depend on it.

## Criteria check

- AC-59-01: PASS. `DatabaseHelper.java:283-292` adds the schema-33 column and
  sets legacy rows to `UNKNOWN` in the framework upgrade; failed writes leave
  the upgrade uncommitted. `DatabaseHelperSchema33Test.java:93-145` verifies
  successful normalization and an injected upgrade failure retaining schema 32
  and the legacy row.
- AC-59-02: PASS. The source database is attached read-only from the target
  migration transaction (`ModelDbController.java:431-444` and
  `GridSizeMigrationUtil.java:171-189`); only the target is copied/placed and
  marked. `GridMigrationSuccessTest.java:73-81` and
  `GridMigrationFailureTest.java:46-108,363-370` assert the source's `LOCKED`
  and `UNLOCKED` values survive successful and injected failure paths.
- AC-59-03: PASS. `ModelDbController.java:391-496` acquires the existing
  `GRID_MIGRATION` lease before target setup and retains it through target
  transaction closure, publication/finalization, source close, and preference
  handling. Restart/retry admissions are dispatched through
  `reconcileActiveDatabaseJournal` and `reconcileDurableJournal`
  (`ModelDbController.java:499-510,608-642`), with coverage in
  `GridMigrationFailureTest.java:146-199,338-360`.
- AC-59-04: PASS. One `SQLiteTransaction` encloses same-target backup,
  transaction-local `TARGET_OLD` journal creation, migration work, target-wide
  UNKNOWN marking, temporary-table deletion, and durable
  `MIGRATED_PENDING_FINALIZATION` before commit
  (`ModelDbController.java:427-451`, `GridSizeMigrationUtil.java:154-189`,
  `GridMigrationJournal.java:77-113`). Metadata cleanup is separate and only
  occurs after validation (`ModelDbController.java:588-605,745-770`). Fast and
  general controller tests assert UNKNOWN targets and temporary-table cleanup
  (`GridMigrationSuccessTest.java:44-81`).
- AC-59-05: PASS. Initial transaction close precedes `finalizeMigration`; only
  then does the controller publish the target, delegate source close, commit and
  read back destination preferences, and record `FINALIZED`
  (`ModelDbController.java:484-487,514-527,684-707`).
  `GridMigrationSuccessTest.java:58-80` and
  `GridMigrationFailureTest.java:46-108` cover publication ordering and
  finalization failures.
- AC-59-06: PASS. Failure paths return without the former migration fallback
  to `createEmptyDB` (`ModelDbController.java:349-357,452-495`). Post-commit
  failures first restore source authority/preferences, write `RESTORE_PENDING`,
  restore and canonical-digest-check the same-target backup, and retain or mark
  `RESTORE_FAILED` on failure (`ModelDbController.java:530-585,644-663`;
  `GridMigrationJournal.java:119-140`; `FavoritesTableDigest.java:24-58`).
  The failure suite covers close/detach/pref failure compensation, process
  restart, source/destination/unknown preference states, retry, and digest
  mismatch (`GridMigrationFailureTest.java:46-360`).
- AC-59-07: PASS. Schema upgrade opens only the selected inactive database;
  `InactiveGridDbNormalizationTest.java:46-64` proves it becomes UNKNOWN while
  the distinct active schema-33 source remains `LOCKED`/`UNLOCKED`.
- AC-59-08: PASS. The production `32 -> 33` upgrade is exercised twice after
  the production downgrade recipe; layout row identity is retained and the lock
  state is UNKNOWN after each upgrade (`DatabaseHelperSchema33Test.java:104-115`,
  `DowngradeSchema33Test.java:23-45`).
- NFR-001: PASS. Recovery state is durable in the target database, source
  authority is restored on finalization failure, and no migration failure calls
  `createEmptyDB`; crash/retry tests cover committed-pending recovery
  (`ModelDbController.java:530-585`, `GridMigrationFailureTest.java:111-199`).
- NFR-002: PASS. The source is never mutated by the migration transaction,
  target locks are verified all UNKNOWN, `favorites_tmp` is rejected at target
  validation, and source/target identity is checked
  (`ModelDbController.java:709-770`). The controller success/failure fixtures
  assert lock conservation and distinct-database behavior
  (`GridMigrationTestSupport.java:51-67,231-235`,
  `GridMigrationFailureTest.java:363-370`).
- NFR-012: PASS. Schema upgrade failure, downgrade/upgrade lifecycle, inactive
  database normalization, backup restore, canonical SHA-256 digest validation,
  and metadata cleanup are covered by the schema and controller failure tests
  (`DatabaseHelperSchema33Test.java:93-145`,
  `InactiveGridDbNormalizationTest.java:46-64`,
  `GridMigrationFailureTest.java:111-142,319-335`).

## Executed test surface

All local commands below were independently executed on branch
`issue-59-preserve-source-grid-migration-failure` at head
`73ec4f650cbcebf97f44d107e5990c04158c6625`:

```text
python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK (/Users/nunu/Documents/work/NunuLauncher), exit 0

python3 tools/repo-contract/test_validate_repo_contract.py
  -> Ran 11 tests; OK, exit 0

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> Ran 47 tests; OK, exit 0

git diff --check main..HEAD
  -> no output, exit 0
```

Gradle and emulator verification were not rerun locally in this audit session.
The cited successful `pull_request` CI run is the independent Gradle evidence:
`./gradlew spotlessCheck` (`check-style`),
`./gradlew assembleLawnWithQuickstepGithubDebug` (`build-debug-apk`), and
`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests
'app.lawnchair.organizer.*'` (`organizer-unit-tests`) all passed without skip,
as did `validate-repo-contract` and `final-status`. No `adb` command or
on-device manual QA was run by this auditor; the changed controller tests are
instrumentation tests and were reviewed but not independently rerun on an AVD.

## Findings

Verdict: pass-with-findings. All AC-59-01 through AC-59-08 and the referenced
NFR-001, NFR-002, and NFR-012 are satisfied by the reviewed implementation and
test evidence; no blocking defect was found.

1. PR #75 is not yet merged. This audit is bound to the stated head SHA; any
   non-documentation change after it requires a new independent audit.
2. The audit reran the required fast repository checks but did not rerun Gradle
   or instrumentation/emulator tests. CI confirms the configured Gradle source
   jobs, but `ci.yml` excludes instrumentation from that merge gate; no `adb`
   or real-device manual QA independently exercised restore faults here.
3. Fault injection covers transaction-close, detach, source-close, preference,
   restore, metadata-cleanup, restart, and digest cases. It does not contain a
   dedicated `TARGET_COPY`, `PLACEMENT`, or `UNKNOWN_MARK` fault test despite
   the runtime seam supporting those operations; source non-mutation is
   structurally supported by the reviewed attached-source read path, but adding
   those direct fault cases would strengthen AC-59-02/06 regression evidence.
