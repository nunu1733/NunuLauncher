# High-risk audit: PR #122 SQLite migration transaction ownership

> Status: accepted
> Audit date: 2026-08-23

- Auditor: Independent session (separate agent context from implementing session); solo-maintenance independent re-execution
- PR: https://github.com/nunu1733/NunuLauncher/pull/122
- Head SHA: eea2a2872850f98448f44d501504c83196030f10
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32639199354
- Criteria: specs/118-sqlite-migration-transaction-audit/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, NFR-001, NFR-002, NFR-012; docs/adr/0004-organizer-lock-persistence.md ADR-0004

## Scope

Fourth-pass re-audit against head
`eea2a2872850f98448f44d501504c83196030f10` (clean working tree, HEAD exactly
at that commit, branch `issue-118-migration-transaction-ownership`). History
of this record: pass 1 pinned `2b479ae0d450aa96cb2d8f9953d23e615fff7696`,
retargeted to `dce32c83ae19c3cabba20dcea6e03f90dc9bcc97` (docs-only delta);
pass 2 covered review-fix commit `7beced7f6f` (temp_favorites cleanup, third
failure-injection scenario, claim narrowing); this pass covers the second
changes-requested-review fix commit `eea2a28728`.

Review-fix delta `7beced7f6f..eea2a28728`: 8 files, +1132/-182. Production
delta is zero (`git diff 7beced7f6f..eea2a28728 -- src lawnchair/src res` is
empty). The delta adds `tests/organizer-instrumentation/com/android/launcher3/
organizer/rollback32/`: `Schema32RollbackDatabaseHelper` (+559),
`Schema32RollbackDbDowngradeHelper` (+129), and `Schema32RollbackBinaryTest`
(+160), revises `specs/118-*` spec.md/plan.md (AC-4 restored to Issue #118's
original wording with sub-bullets citing the pinned target; Outcome,
Scenarios, and Non-goals reframed around the residual risk being pinned by
test), and adds `rollback32.Schema32RollbackBinaryTest` to the
db-migration lane filter in `.github/workflows/ci.yml` and its
`ci-test-portfolio.md` row.

Audited the cumulative `main..eea2a2872850f98448f44d501504c83196030f10` diff:
14 files, +1844/-34. Production surface remains exactly two files:
`DatabaseHelper.java` (inner `SQLiteTransaction` removal in `case 13`,
`addIntegerColumn`, `updateFolderItemsRank`,
`convertShortcutsToLauncherActivities`; wipe fallback in `createEmptyDB` also
drops `temp_favorites`; `#118` ownership comments; no signature or
catch-semantics change) and `DbDowngradeHelper.java` (inner scope removed;
javadoc records the caller-owned transaction contract). Test surface: the
three compiled direct downgrade callers (`DatabaseHelperSchema33Test`,
`DowngradeSchema33Test`, `InactiveGridDbNormalizationTest`) own their
transactions; `MigrationTransactionOwnershipTest` carries three
failure-injection scenarios; the new `rollback32` package pins the actual
schema-32 rollback target. Infrastructure/docs: permanent
`organizer-instrumentation-db-migration-tests` CI lane (API 36 emulator)
wired into `final-status`, its portfolio row, and spec/plan under
`specs/118-sqlite-migration-transaction-audit/`.

Runtime write paths re-derived independently at this commit: inside
`DatabaseHelper`, the only remaining transaction scope is `createEmptyDB`'s
own (`DatabaseHelper.java:327`), which drops `favorites`, `temp_favorites`,
and `workspaceScreens` before `onCreate`; `onUpgrade` cases 12–31 run directly
in the framework callback transaction, `case 32→33` keeps ADR-0004's direct
statements with throw-on-failure, `case 33` returns, and the wipe fallback
`createEmptyDB(db)` is reachable only through the legacy catch/break paths or
the `onDowngrade` catch. Remaining repository `SQLiteTransaction` users
(`RestoreDbTask`, `GridSizeMigrationUtil`, `ModelWriter`, `ModelDbController`)
are outside this spec's non-goals (#117/#119/#120) and untouched here.

## Criteria check

Checked against the REVISED (second-review-incorporated) spec text, whose AC-4
is restored to Issue #118's original wording plus sub-bullets.

Verbatim-copy verification performed by this auditor (not taken from the PR):
extracted `src/com/android/launcher3/model/{DatabaseHelper,DbDowngradeHelper}.java`
at commit `866d231ffdfe2dcc8b0e550e65ea6f1301b6674c` via `git show`, stripped
the 11-line provenance header from each replica, normalized
`package com.android.launcher3.organizer.rollback32` →
`com.android.launcher3.model` and the class names, then diffed:

- `Schema32RollbackDbDowngradeHelper`: byte-identical to the pinned original.
- `Schema32RollbackDatabaseHelper`: identical except exactly two lines that
  retarget cross-class references `DbDowngradeHelper.updateSchemaFile(...)`
  / `DbDowngradeHelper.parse(...)` to the renamed companion
  `Schema32RollbackDbDowngradeHelper`. This is necessary and faithful: the
  binary at the pinned commit bundles both classes together, and without the
  retarget the replica would silently bind to the current (post-#118, fixed)
  `com.android.launcher3.model.DbDowngradeHelper`. Replica
  `SCHEMA_VERSION` is 32 (current tree: 33), confirming the pre-schema-33
  source was used.

- AC-1: PASS. Unchanged by this delta: classification tables retained in the
  revised spec and each changed site mirrors them with an `Issue #118`
  comment, including the `temp_favorites` drop inside `createEmptyDB`. Grep
  confirms no other nested scope remains in `DatabaseHelper`.
- AC-2: PASS. No reachable path catches an unsuccessful nested
  `SQLiteTransaction` and then relies on the outer helper transaction
  committing: the four legacy helpers open no scope so their catch→wipe /
  log-and-continue handlers act on a still-commitable framework transaction,
  and the schema 32→33 path propagates exceptions so the framework rolls back
  without wiping. Production code is byte-identical to the previously audited
  `7beced7f6f` (this delta adds tests/docs only).
- AC-3: PASS. `DbDowngradeHelper.onDowngrade` executes recipe statements in
  the caller's transaction with the contract documented in javadoc; the
  production caller relies on the framework wrapper around
  `DatabaseHelper.onDowngrade`; all three compiled direct callers wrap their
  own `SQLiteTransaction`. Residual note in Finding 5 for uncompiled upstream
  leftover callers under `tests/src`.
- AC-4: PASS under the restored original wording ("failure-injection tests
  prove rollback/fallback behavior for at least one upgrade path and the
  33→32 downgrade path"), now satisfied end to end including the new
  sub-bullets:
  - Upgrade-path injection:
    `legacyUpgradeFailureFallsBackToWipeThatPersists` (green locally and in
    the CI emulator lane).
  - This-tree 33→32 injections:
    `downgradeStatementFailureFallsBackToWipeThatPersists` (first-statement
    failure; asserts version 32 committed, zero rows, sabotaged staging table
    did not survive the wipe) and
    `downgradePartialRecipeProgressStillEndsInDeterministicFreshWipe`.
  - Actual-rollback-target evidence (new): `rollback32.Schema32RollbackBinaryTest`
    seeds a schema-33 file (one row, `organizerLockState` set) through the
    current production `DatabaseHelper` and opens it through the pinned
    schema-32 replica.
    `successful33To32RollbackReachesVersion32PreservingRows` pins committed
    version 32, row preservation, and schema-32 shape (no
    `ORGANIZER_LOCK_STATE` column);
    `failingRecipeOnRealRollbackBinaryLeavesFileUnDowngraded` pre-creates a
    conflicting `temp_favorites` to fail the first rebuild statement and pins
    the documented non-recovery: the open reports success while the file
    stays at version 33 with the row, lock column, and staging junk intact,
    re-checked through a fresh handle.
  - GENUINENESS: the test exercises the REAL `SQLiteOpenHelper` wrapping, not
    a manual mirror. Verified inheritance chain
    `Schema32RollbackDatabaseHelper` → `NoLocaleSQLiteHelper` →
    `SQLiteOpenHelper` (iconloaderlib submodule); the test calls
    `getWritableDatabase()` and contains no `beginTransaction`/
    `setTransactionSuccessful`/`endTransaction` of its own. Assertions are
    correct per the canonical nested-transaction contract: the replica's
    inner `SQLiteTransaction` (verbatim pre-#118 `DbDowngradeHelper`) ends
    unsuccessful on the blocked statement, the caught wipe fallback marks its
    own nested scope successful, but the poisoned ancestor forces the
    outermost end to roll back the whole unit including the framework's
    `setVersion(32)` — hence "open reports success, file stays at 33".
  - FIELD FIDELITY of the parsed recipe file: seeding through the current
    helper's `onOpen` runs `updateSchemaFile(file, 33)` which writes the
    current `res/raw/downgrade_schema.json` (version 33, containing the
    `downgrade_to_32` block absent from the pinned commit's bundled copy);
    the replica's own verbatim `updateSchemaFile(file, 32)` then keeps that
    file because `parse(schemaFile).version (33) >= expectedVersion (32)`
    (keep-rule read at the verbatim source). The replica therefore executes
    against exactly the v33 recipe a field device carries — the deliberate
    fidelity point documented in the test header.
- AC-5: PASS. All non-destructive fixtures ran green at this head:
  `DatabaseHelperSchema33Test` (6 tests, including
  `schema32UpgradeFailureRollsBackWithoutChangingLegacyRow` and
  `schema32To33To32To33PreservesRowsAndEndsUnknown`),
  `DowngradeSchema33Test.productionRecipeRebuilds33To32WithoutLosingRows`, and
  `InactiveGridDbNormalizationTest.inactiveSchema32NormalizationDoesNotRewriteSchema33Source`.
- AC-6: PASS. Destructive fallback triggers are untouched by this delta
  (zero production files changed); justification language in Scenarios /
  Data-and-state intact; wiped results asserted deterministically (exact
  version, row count 0, table absence).
- ADR-0004 constraints: PASS. The 32→33 non-wiping rollback path is untouched
  (direct statements, throw on failure, framework rollback, legacy row
  unchanged — asserted green); the 33→32 rebuild recipe statements are
  unchanged; the new rollback-target tests add evidence without altering any
  production behavior, and the 33→32→33 re-upgrade-to-UNKNOWN cycle fixture
  remains green.
- NFR-001: PASS. A failing legacy upgrade/downgrade executed by helpers built
  from this tree cannot leave an old-version file behind an open that reports
  success; and the one known exception — rollback binaries built before
  #118 — is now pinned by `failingRecipeOnRealRollbackBinaryLeavesFileUnDowngraded`,
  so drift in that released behavior fails CI instead of going unnoticed.
- NFR-002: PASS. The fallback produces a fresh current-shape database via
  `onCreate` inside one committed transaction, with deterministic assertions;
  successful-path integrity remains covered by the retained fixtures.
- NFR-012: PASS. Upgrade, downgrade/rollback, restore-of-fallback, partial
  progress, real-rollback-target success/non-recovery, and re-upgrade
  consistency are tested by the five suites, which this PR promotes from
  compile-only to a permanent merge-gate emulator lane (lane filter now
  includes `rollback32.Schema32RollbackBinaryTest`).

## Executed test surface

Independent re-execution by this auditor against
`eea2a2872850f98448f44d501504c83196030f10` (clean working tree, HEAD exactly
at that commit):

```text
git rev-parse HEAD
  -> eea2a2872850f98448f44d501504c83196030f10 (git status clean)

# verbatim verification (auditor's own method, see Criteria check AC-4)
git show 866d231ffdfe2dcc8b0e550e65ea6f1301b6674c:src/com/android/launcher3/model/{DatabaseHelper,DbDowngradeHelper}.java
  -> normalized diff vs tests/organizer-instrumentation/
     com/android/launcher3/organizer/rollback32/*.java:
     DbDowngradeHelper replica IDENTICAL;
     DatabaseHelper replica differs only in package/class name and 2
     cross-class references retargeted to the renamed companion

/opt/homebrew/share/android-commandline-tools/platform-tools/adb devices
  -> emulator-5554 device (AVD nunu_qpr2_api36_1 already up)

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.MigrationTransactionOwnershipTest,com.android.launcher3.organizer.DatabaseHelperSchema33Test,com.android.launcher3.organizer.DowngradeSchema33Test,com.android.launcher3.organizer.InactiveGridDbNormalizationTest,com.android.launcher3.organizer.rollback32.Schema32RollbackBinaryTest"
  -> exit 0; BUILD SUCCESSFUL in 24s; "Starting 13 tests on
     nunu_qpr2_api36_1(AVD) - 16" / "Finished 13 tests"
  -> build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/TEST-nunu_qpr2_api36_1(AVD) - 16-_-lawnWithQuickstepGithub.xml
     (testsuite timestamp 2026-08-23T12:38:54):
     tests="13" failures="0" errors="0" skipped="0"
     MigrationTransactionOwnershipTest 3,
     DatabaseHelperSchema33Test 6, DowngradeSchema33Test 1,
     InactiveGridDbNormalizationTest 1,
     rollback32.Schema32RollbackBinaryTest 2
       (successful33To32RollbackReachesVersion32PreservingRows,
        failingRecipeOnRealRollbackBinaryLeavesFileUnDowngraded)
     — every testcase PASS

./gradlew spotlessCheck
  -> exit 0; BUILD SUCCESSFUL in 2s (separate invocation; re-run again over
     this updated record: BUILD SUCCESSFUL, 5 actionable tasks)

./gradlew assembleLawnWithQuickstepGithubDebug
  -> exit 0; BUILD SUCCESSFUL in 1s, 445 tasks up-to-date from this session's
     instrumentation build of the identical tree (separate invocation)

gh api repos/nunu1733/NunuLauncher/actions/runs/32639199354
  --jq '.status+" "+.conclusion'
  -> completed success (event=pull_request,
     head_sha=eea2a2872850f98448f44d501504c83196030f10,
     pull_requests includes #122)

gh api repos/nunu1733/NunuLauncher/actions/runs/32639199354/jobs
  --jq '.jobs[] | .name+" "+.conclusion+"/"+.status'
  -> changes, build-debug-apk, validate-repo-contract, organizer-unit-tests,
     organizer-instrumentation-{issue99,api35,issue53,shared-writer,
     db-migration,issue52}-tests, check-style, final-status:
     all completed success, none skipped
     (final-status, organizer-unit-tests, check-style, build-debug-apk,
      organizer-instrumentation-db-migration-tests all success)
```

The qualifying CI source jobs (`organizer-unit-tests`, `check-style`,
`build-debug-apk`), the DB-migration emulator lane (now including the
rollback-target binary tests), and `final-status` all executed successfully on
this head. This audit independently re-executed the full instrumentation suite
locally on the API 36 AVD (now 13 tests after the second review fix) and
verified the merge-gate run via the GitHub API.

## Findings

Verdict: pass. No blocking defect found; all six ACs of the REVISED spec —
including the restored AC-4 with its actual-rollback-target sub-bullets — both
ADR-0004 constraints, and NFR-001/NFR-002/NFR-012 are satisfied by independent
code review plus local re-execution and qualifying CI evidence.

1. Re-audit/revision history: development first proved both original poisoning
   scenarios red on unmodified `main` (recorded in the spec change history);
   this record progressed `2b479ae0d4` → `dce32c83ae` (docs-only delta) →
   `7beced7f6f` (temp_favorites cleanup, third failure-injection scenario,
   claim narrowing) → `eea2a28728` (pinned real schema-32 rollback target).
   All four CI merge-gate runs (32633914951, 32634560825, 32636892718,
   32639199354) are green on their respective heads. Remaining action for the
   implementing session: push this updated doc — a docs-only commit after the
   audited head keeps the audit valid per github-workflow.md. Until then
   `High-risk gate / high-risk-evidence` stays red solely due to the stale
   committed copy of this file, expected at audit time.
2. Verbatim-copy fidelity: verified independently (method above). The only
   body deltas are the two retargeted cross-class references, which preserve
   the pinned binary's bundling semantics; leaving them pointed at the current
   `DbDowngradeHelper` would have silently tested the fixed helper instead.
   Correctly handled. One inherent caveat of source-pinning: the replicas
   resolve other dependencies (`LauncherDbUtils.SQLiteTransaction`,
   resources) against the current tree; verified `SQLiteTransaction`'s
   no-lease constructor behavior is unchanged since the pinned commit, and
   the recipe-file difference is deliberately neutralized by seeding through
   the current helper (see AC-4 field-fidelity bullet).
3. Residual risk accepted by the revised spec (Non-goals): failure recovery of
   downgrades executed by already-built schema-32 binaries, whose bundled
   pre-#118 helper still poisons its nested scope and silently rolls back the
   wipe fallback. Unlike the previous pass, this behavior is now actively
   pinned by `failingRecipeOnRealRollbackBinaryLeavesFileUnDowngraded`, so the
   residual risk can no longer drift silently. Deterministic recovery applies
   from the first rollback run by a binary built from this tree (e.g. a future
   34→33 rollback).
4. Prior-pass observations, still true and unchanged: the partial-progress
   scenario fails the chain after block 28 completes (staging table already
   dropped by the recipe itself; the load-bearing pin of the `createEmptyDB`
   drop is the first-statement scenario), and two uncompiled upstream leftovers
   (`tests/src/com/android/launcher3/provider/LauncherDbUtilsTest.java:148`,
   `tests/src/com/android/launcher3/model/DbDowngradeHelperTest.java:198`)
   still invoke `DbDowngradeHelper.onDowngrade` without owning a transaction
   under the caller-owned contract; they are not wired into this repository's
   Gradle build and are out of merge-gate scope per the spec. Low risk.
5. Local `assembleLawnWithQuickstepGithubDebug` reported all tasks up-to-date
   because this session's preceding instrumentation invocation had already
   built identical inputs; all gradle invocations ran separately as required,
   and `spotlessCheck` was additionally re-run over the updated record.
