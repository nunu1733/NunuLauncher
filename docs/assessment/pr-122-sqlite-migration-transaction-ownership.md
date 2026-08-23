# High-risk audit: PR #122 SQLite migration transaction ownership

> Status: accepted
> Audit date: 2026-08-23

- Auditor: Independent session (separate agent context from implementing session); solo-maintenance independent re-execution
- PR: https://github.com/nunu1733/NunuLauncher/pull/122
- Head SHA: 7beced7f6fc1afe4835d1818d0e9ed73a2052a71
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32636892718
- Criteria: specs/118-sqlite-migration-transaction-audit/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, NFR-001, NFR-002, NFR-012; docs/adr/0004-organizer-lock-persistence.md ADR-0004

## Scope

Third-pass re-audit against head `7beced7f6fc1afe4835d1818d0e9ed73a2052a71`
(clean working tree, HEAD exactly at that commit). History of this record: the
first pass pinned `2b479ae0d450aa96cb2d8f9953d23e615fff7696` and was
retargeted to `dce32c83ae19c3cabba20dcea6e03f90dc9bcc97` (docs-only delta:
this record plus the `- NFR-012` spec frontmatter line); this pass adds the
changes-requested-review fix commit `7beced7f6f`.

Review-fix delta `dce32c83ae..7beced7f6f`: 5 files, +197/-57. Production delta
is exactly two files (`git diff dce32c83ae..7beced7f6f -- src lawnchair/src
tests .github res` = 2 files, +63/-2):
`src/com/android/launcher3/model/DatabaseHelper.java` — `createEmptyDB()`
additionally drops the `temp_favorites` staging table (name hardcoded in
`res/raw/downgrade_schema.json`) inside its own transaction, with an
`Issue #118` comment; and
`tests/organizer-instrumentation/com/android/launcher3/organizer/MigrationTransactionOwnershipTest.java`
— new `downgradePartialRecipeProgressStillEndsInDeterministicFreshWipe`
(drives `onDowngrade(db, 33, 22)` with a sabotaged `workspaceScreens`),
`temp_favorites`/leftover-table assertions added to the first-statement
downgrade test, a `tableExists` helper, and a header scope-note mirroring the
Non-goals. `specs/118-*` spec.md/plan.md were revised per review: downgrade
evidence narrowed to "helpers built from this tree", already-built schema-32
binary recovery moved to Non-goals as residual risk, new partial-progress
scenario, reworded AC-4.

Audited the cumulative `main..7beced7f6fc1afe4835d1818d0e9ed73a2052a71` diff:
11 files, +894/-34 (the eleventh file being this audit record). Production
surface remains exactly two files: `DatabaseHelper.java` (inner
`SQLiteTransaction` removal in `case 13`, `addIntegerColumn`,
`updateFolderItemsRank`, `convertShortcutsToLauncherActivities`; wipe fallback
in `createEmptyDB` now also drops `temp_favorites`; `#118` ownership comments;
no signature or catch-semantics change) and `DbDowngradeHelper.java` (inner
scope removed; javadoc records the caller-owned transaction contract). Test
surface: the three compiled direct downgrade callers
(`DatabaseHelperSchema33Test`, `DowngradeSchema33Test`,
`InactiveGridDbNormalizationTest`) own their transactions and
`MigrationTransactionOwnershipTest` carries three failure-injection scenarios.
Infrastructure/docs: permanent `organizer-instrumentation-db-migration-tests`
CI lane (API 36 emulator) wired into `final-status`, its
`ci-test-portfolio.md` row, and spec/plan under
`specs/118-sqlite-migration-transaction-audit/`.

Runtime write paths re-derived independently at this commit: inside
`DatabaseHelper`, the only remaining transaction scope is `createEmptyDB`'s own
(`DatabaseHelper.java:327`), which now drops `favorites`, `temp_favorites`,
and `workspaceScreens` before `onCreate`; `onUpgrade` cases 12–31 run directly
in the framework callback transaction, `case 32→33` keeps ADR-0004's direct
statements with throw-on-failure, `case 33` returns, and the wipe fallback
`createEmptyDB(db)` is reachable only through the legacy catch/break paths or
the `onDowngrade` catch. Remaining repository `SQLiteTransaction` users
(`RestoreDbTask`, `GridSizeMigrationUtil`, `ModelWriter`, `ModelDbController`)
are outside this spec's non-goals (#117/#119/#120) and untouched here.

## Criteria check

Checked against the REVISED (review-incorporated) spec text.

- AC-1: PASS. The classification tables are retained in the revised spec and
  each changed site mirrors them with an `Issue #118` comment, including the
  new `temp_favorites` drop inside `createEmptyDB`. Grep confirms no other
  nested scope remains in `DatabaseHelper`.
- AC-2: PASS. No reachable path catches an unsuccessful nested
  `SQLiteTransaction` and then relies on the outer helper transaction
  committing: the four legacy helpers open no scope so their catch→wipe /
  log-and-continue handlers act on a still-commitable framework transaction,
  and the schema 32→33 path propagates exceptions so the framework rolls back
  without wiping (re-verified unchanged by the review delta, which touches
  only `createEmptyDB`). `downgradeStatementFailureFallsBackToWipeThatPersists`
  pins the poisoned-outer behavior end to end.
- AC-3: PASS. `DbDowngradeHelper.onDowngrade` executes recipe statements in
  the caller's transaction with the contract documented in javadoc (re-read at
  this head); the production caller relies on the framework wrapper around
  `DatabaseHelper.onDowngrade`; all three compiled direct callers wrap their
  own `SQLiteTransaction`. Residual note in Finding 4 for uncompiled upstream
  leftover callers under `tests/src`.
- AC-4: PASS under the reworded criterion. All three required scenarios exist
  and ran green locally (11/11, see below) and in the CI emulator lane:
  `legacyUpgradeFailureFallsBackToWipeThatPersists`, 
  `downgradeStatementFailureFallsBackToWipeThatPersists` (first-statement
  failure; asserts version 32 committed, zero rows, and that the sabotaged
  `temp_favorites` did not survive the wipe), and
  `downgradePartialRecipeProgressStillEndsInDeterministicFreshWipe`. The test
  class scope-note matches the Non-goals wording verbatim in intent.
  - Review question 1 (does the `temp_favorites` drop in `createEmptyDB()`
    risk breaking standalone callers?): NO RISK FOUND. Every caller was
    checked at this head: `RestoreDbTask.java:125` (restore-failure wipe),
    `TestInformationHandler.java:407/421` (test-only reinit/clear-data
    endpoints), both via the `ModelDbController.createEmptyDB()` public seam
    (`ModelDbController.java:274`), and default-layout loading
    (`ModelDbController.java:1149/1154`, which wipes before loading defaults).
    All invoke `createEmptyDB()` with the explicit intent of destroying all
    data before reload; none can legitimately hold authoritative data in
    `temp_favorites` at call time, because that table exists only transiently
    inside an in-flight downgrade recipe executed within one helper callback
    holding the framework DB lock (standalone callers run outside any
    callback), and any surviving copy is precisely the leak being fixed.
    `dropTable` is `DROP TABLE IF EXISTS`
    (`LauncherDbUtils.java:87-89`), so absence is a no-op — today's normal
    state for every standalone caller. Semantically the table is staging
    scaffolding, never authoritative data.
  - Review question 2 (is the partial-progress test a genuine mid-chain
    injection?): YES. Verified against `res/raw/downgrade_schema.json` and
    `DbDowngradeHelper.onDowngrade`'s concatenation loop (blocks
    `oldVersion-1 .. newVersion` in descending order). For 33→22 the executed
    order is: 32-block renames `favorites`→`temp_favorites`, rebuilds
    `favorites` (32-shape), copies the row back, drops staging; 31-block adds
    `iconPackage`/`iconResource`; empty 30/29 blocks; 28-block renames,
    rebuilds, copies the row, drops staging again; then the 27-block
    `CREATE TABLE workspaceScreens` fails against the pre-created conflicting
    table. The failure therefore lands after 12 successful statements and two
    full row-carrying rebuilds — real progress, not a first-statement failure.
    Assertions prove the copied row does not leak (count 0), version commits
    at 22, and the leftover `workspaceScreens` is gone. Observation (not a
    defect): at this sabotage point the recipe itself has already dropped
    `temp_favorites` (block-28 final statement), so the `temp_favorites`
    assertion in THIS scenario is belt-and-braces; the load-bearing pin of the
    new `createEmptyDB` drop is the first-statement test, where the sabotaged
    staging table exists at wipe time. Between the two scenarios both states
    ("staging present at wipe" and "staging consumed before failure") are
    covered; a failure landing inside a rename/rebuild cycle (staging
    half-populated) relies on the same `dropTable("temp_favorites")` call and
    is noted in Finding 3.
- AC-5: PASS. All non-destructive fixtures ran green at this head:
  `DatabaseHelperSchema33Test` (6 tests, including
  `schema32UpgradeFailureRollsBackWithoutChangingLegacyRow` and
  `schema32To33To32To33PreservesRowsAndEndsUnknown`),
  `DowngradeSchema33Test.productionRecipeRebuilds33To32WithoutLosingRows`, and
  `InactiveGridDbNormalizationTest.inactiveSchema32NormalizationDoesNotRewriteSchema33Source`.
- AC-6: PASS. The destructive fallback triggers (catch/break conditions) are
  byte-identical to the previously audited state — the review delta widens
  nothing; it only makes the existing fallback destroy strictly more leaked
  scaffolding (`temp_favorites`) on the same triggers, which the revised
  Non-goals explicitly records ("its cleanup now covers the downgrade staging
  table"). Justification language in Scenarios / Data-and-state is intact, and
  the wiped results are asserted deterministically (exact version, row count
  0, table absence).
- ADR-0004 constraints: PASS. The 32→33 non-wiping rollback path is untouched
  by the review delta (direct statements, throw on failure, framework
  rollback, legacy row unchanged — asserted by
  `schema32UpgradeFailureRollsBackWithoutChangingLegacyRow`, green); the
  33→32 rebuild recipe statements themselves are unchanged (diff touches only
  `createEmptyDB`), and row survival plus the 33→32→33 re-upgrade-to-UNKNOWN
  cycle remain asserted by the fixture tests.
- NFR-001: PASS. A failing legacy upgrade/downgrade can no longer leave an
  old-version file behind an open that reports success; it either persists the
  historical usable wipe deterministically (now without staged-row leaks) or
  (32→33) rolls back and fails the open, preserving the pre-migration layout.
- NFR-002: PASS. The fallback produces a fresh current-shape database via
  `onCreate` inside one committed transaction, with deterministic assertions;
  successful-path integrity remains covered by the retained fixtures.
- NFR-012: PASS. Upgrade, downgrade/rollback, restore-of-fallback, partial
  progress, and re-upgrade consistency are tested by the four suites, which
  this PR promotes from compile-only to a permanent merge-gate emulator lane.

## Executed test surface

Independent re-execution by this auditor against
`7beced7f6fc1afe4835d1818d0e9ed73a2052a71` (clean working tree, HEAD exactly
at that commit):

```text
git rev-parse HEAD
  -> 7beced7f6fc1afe4835d1818d0e9ed73a2052a71 (git status clean)
git diff dce32c83ae..7beced7f6f --stat -- src lawnchair/src tests .github res
  -> DatabaseHelper.java (+4), MigrationTransactionOwnershipTest.java (+61/-2)

/opt/homebrew/share/android-commandline-tools/platform-tools/adb devices
  -> emulator-5554 device (AVD nunu_qpr2_api36_1 already up)

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.MigrationTransactionOwnershipTest,com.android.launcher3.organizer.DatabaseHelperSchema33Test,com.android.launcher3.organizer.DowngradeSchema33Test,com.android.launcher3.organizer.InactiveGridDbNormalizationTest"
  -> exit 0; BUILD SUCCESSFUL in 22s; "Starting 11 tests on
     nunu_qpr2_api36_1(AVD) - 16" / "Finished 11 tests"
  -> build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/TEST-nunu_qpr2_api36_1(AVD) - 16-_-lawnWithQuickstepGithub.xml
     (testsuite timestamp 2026-08-23T11:51:46):
     tests="11" failures="0" errors="0" skipped="0"
     MigrationTransactionOwnershipTest 3 (incl. new
       downgradePartialRecipeProgressStillEndsInDeterministicFreshWipe),
     DatabaseHelperSchema33Test 6, DowngradeSchema33Test 1,
     InactiveGridDbNormalizationTest 1 — every testcase PASS

./gradlew assembleLawnWithQuickstepGithubDebug
  -> exit 0; BUILD SUCCESSFUL in 1s, 445 tasks up-to-date from this session's
     instrumentation build of the identical tree (separate invocation)

gh api repos/nunu1733/NunuLauncher/actions/runs/32636892718
  -> status=completed conclusion=success event=pull_request,
     head_sha=7beced7f6fc1afe4835d1818d0e9ed73a2052a71,
     pull_requests=[122]

gh api repos/nunu1733/NunuLauncher/actions/runs/32636892718/jobs
  -> changes, build-debug-apk, validate-repo-contract, organizer-unit-tests,
     organizer-instrumentation-{issue99,api35,issue53,shared-writer,
     db-migration,issue52}-tests, check-style, final-status:
     all completed success, none skipped

python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 122 --head-sha 7beced7f6fc1afe4835d1818d0e9ed73a2052a71 --root /Users/nunu/Documents/work/NunuLauncher
  -> high-risk PR (label 'risk: layout-data'; label 'risk: migration'; high-risk
     path change(s): src/com/android/launcher3/model/DatabaseHelper.java):
     independent evidence required
  -> PASS: audit docs/assessment/pr-122-sqlite-migration-transaction-ownership.md
     covers 7beced7f6fc1afe4835d1818d0e9ed73a2052a71 with independent CI evidence
     (specs/118-sqlite-migration-transaction-audit/spec.md,
     docs/adr/0004-organizer-lock-persistence.md); exit=0
```

`spotlessCheck` was re-run as a separate invocation over this updated record:
BUILD SUCCESSFUL (5 actionable tasks, all up-to-date).

The qualifying CI source jobs (`organizer-unit-tests`, `check-style`,
`build-debug-apk`), the DB-migration emulator lane, and `final-status` all
executed successfully on this head. This audit independently re-executed the
full instrumentation suite locally on the API 36 AVD (now 11 tests after the
review fix) and verified the merge-gate run via the GitHub API.

## Findings

Verdict: pass. No blocking defect found; all six ACs of the REVISED spec, both
ADR-0004 constraints, and NFR-001/NFR-002/NFR-012 are satisfied by independent
code review plus local re-execution and qualifying CI evidence.

1. Re-audit/revision history: development first proved both original poisoning
   scenarios red on unmodified `main` (recorded in the spec change history);
   the first pass of this record pinned `2b479ae0d4` and was retargeted to
   `dce32c83ae` (docs-only delta); this pass covers review-fix commit
   `7beced7f6f` (temp_favorites cleanup, third failure-injection scenario,
   claim narrowing to helpers built from this tree). All three CI merge-gate
   runs (32633914951, 32634560825, 32636892718) are green on their respective
   heads. Remaining action for the implementing session: push this updated
   doc — a docs-only commit after the audited head keeps the audit valid per
   github-workflow.md. Until then `High-risk gate / high-risk-evidence` stays
   red solely due to the stale committed copy of this file, expected at audit
   time.
2. Residual risk accepted by the revised spec (Non-goals): failure recovery of
   downgrades executed by already-built schema-32 binaries, whose bundled
   pre-#118 helper still poisons its nested scope and silently rolls back the
   wipe fallback. Deterministic recovery applies from the first rollback run
   by a binary built from this tree (e.g. a future 34→33 rollback). Recorded
   in the spec, not tracked further here.
3. Minor observation: the partial-progress scenario fails the chain after the
   block-28 rebuild completes, i.e. after the recipe has itself dropped
   `temp_favorites`; a failure landing mid-rebuild-cycle (staging table
   half-populated at wipe time) is not separately exercised but is covered by
   the identical `dropTable("temp_favorites")` execution proven by the
   first-statement scenario. No action required; recorded for completeness.
4. Two uncompiled upstream leftovers
   (`tests/src/com/android/launcher3/provider/LauncherDbUtilsTest.java:148`
   and `tests/src/com/android/launcher3/model/DbDowngradeHelperTest.java:198`)
   still invoke `DbDowngradeHelper.onDowngrade` without owning a transaction
   under the new caller-owned contract. They are not wired into this
   repository's Gradle build, so they are not merge-gate evidence and the
   accepted spec already records their status; if ever compiled here they will
   need caller-owned wrapping. Low risk.
5. Local `assembleLawnWithQuickstepGithubDebug` reported all tasks up-to-date
   because this session's preceding instrumentation invocation had already
   built identical inputs; both ran as separate invocations as required, and
   `spotlessCheck` was additionally re-run over the updated record.
