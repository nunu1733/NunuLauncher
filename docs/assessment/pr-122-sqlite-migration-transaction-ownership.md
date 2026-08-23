# High-risk audit: PR #122 SQLite migration transaction ownership

> Status: accepted
> Audit date: 2026-08-23

- Auditor: Independent session (separate agent context from implementing session); solo-maintenance independent re-execution
- PR: https://github.com/nunu1733/NunuLauncher/pull/122
- Head SHA: 2b479ae0d450aa96cb2d8f9953d23e615fff7696
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32633914951
- Criteria: specs/118-sqlite-migration-transaction-audit/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, NFR-001, NFR-002, NFR-012; docs/adr/0004-organizer-lock-persistence.md ADR-0004

## Scope

Audited the complete `main..2b479ae0d450aa96cb2d8f9953d23e615fff7696`
diff: 10 files, +578/-34. The production surface is exactly two files:
`src/com/android/launcher3/model/DatabaseHelper.java` (inner
`SQLiteTransaction` removal in `case 13`, `addIntegerColumn`,
`updateFolderItemsRank`, `convertShortcutsToLauncherActivities`; `#118`
ownership comments on `onDowngrade` and `createEmptyDB`; no signature or
catch-semantics change) and `src/com/android/launcher3/model/DbDowngradeHelper.java`
(inner scope removed; javadoc records the caller-owned transaction contract).
The test surface updates the three compiled direct downgrade callers
(`DatabaseHelperSchema33Test`, `DowngradeSchema33Test`,
`InactiveGridDbNormalizationTest`) to own their transactions and adds
`MigrationTransactionOwnershipTest` with the two failure-injection scenarios.
Infrastructure/docs: a permanent `organizer-instrumentation-db-migration-tests`
CI lane (API 36 emulator) wired into `final-status` needs, its
`ci-test-portfolio.md` row, and the spec/plan under
`specs/118-sqlite-migration-transaction-audit/`.

Runtime write paths re-derived independently at this commit: inside
`DatabaseHelper`, the only remaining transaction scope is `createEmptyDB`'s
own (`DatabaseHelper.java:327`); `onUpgrade` cases 12–31 run directly in the
framework callback transaction, `case 32→33` keeps ADR-0004's direct
statements with throw-on-failure, `case 33` returns, and the wipe fallback
`createEmptyDB(db)` is reachable only through the legacy catch/break paths or
the `onDowngrade` catch. `case 31` (`migrateLegacyShortcuts`,
`LauncherDbUtils.java:128`) opens no inner scope and is unchanged by this
diff. Remaining repository `SQLiteTransaction` users (`RestoreDbTask`,
`GridSizeMigrationUtil`, `ModelWriter`, `ModelDbController`) are outside this
spec's non-goals (#117/#119/#120) and untouched here.

## Criteria check

- AC-1: PASS. The accepted spec carries the full owner-classification table,
  and each changed site mirrors it with an `Issue #118` comment (`case 13`,
  `addIntegerColumn`, `updateFolderItemsRank`,
  `convertShortcutsToLauncherActivities`, `onDowngrade`, `createEmptyDB`,
  `DbDowngradeHelper.onDowngrade`). Grep confirms no other nested scope
  remains in `DatabaseHelper`.
- AC-2: PASS. After the fix no reachable path catches an unsuccessful nested
  `SQLiteTransaction` and then relies on the outer helper transaction
  committing: the four legacy helpers no longer open a scope, so their
  catch→wipe / log-and-continue handlers act on a still-commitable framework
  transaction, and the schema 32→33 path propagates exceptions out of the
  callback so the framework rolls back without wiping (verified unchanged).
- AC-3: PASS. `DbDowngradeHelper.onDowngrade` now executes recipe statements
  in the caller's transaction with the contract documented in javadoc; the
  production caller relies on the framework wrapper around
  `DatabaseHelper.onDowngrade`; all three compiled direct callers wrap their
  own `SQLiteTransaction`. Residual note in Finding 3 for uncompiled upstream
  leftover callers under `tests/src`.
- AC-4: PASS. Both required failure-injection scenarios exist and were
  executed green locally and in CI:
  `legacyUpgradeFailureFallsBackToWipeThatPersists` (version-13 file whose
  replayed case 14 fails; open returns normally, file ends at version 33 with
  a fresh empty current-shape `favorites` incl. `organizerLockState`) and
  `downgradeStatementFailureFallsBackToWipeThatPersists` (a conflicting
  `temp_favorites` blocks the first rebuild; a framework-style wrapper mirroring
  `getDatabaseLocked` commits with version 32 and zero rows).
- AC-5: PASS. All existing non-destructive fixtures ran green:
  `DatabaseHelperSchema33Test` (6 tests, including
  `schema32UpgradeFailureRollsBackWithoutChangingLegacyRow` and
  `schema32To33To32To33PreservesRowsAndEndsUnknown`),
  `DowngradeSchema33Test.productionRecipeRebuilds33To32WithoutLosingRows`
  (row `_id=1 "kept"` survives, `organizerLockState` absent after 33→32), and
  `InactiveGridDbNormalizationTest.inactiveSchema32NormalizationDoesNotRewriteSchema33Source`.
- AC-6: PASS. The destructive fallbacks are explicitly justified in the
  accepted spec's Scenarios section; the diff preserves every catch/break
  trigger verbatim (no widened conditions), and the new tests assert the wiped
  result deterministically (exact version, row count 0, column shape).
- ADR-0004 constraints: PASS. The 32→33 non-wiping rollback path is untouched
  by the diff (direct statements, throw on failure, framework rollback, legacy
  row unchanged — asserted by
  `schema32UpgradeFailureRollsBackWithoutChangingLegacyRow`); the 33→32
  downgrade rebuild recipe is unchanged and row survival plus the
  33→32→33 re-upgrade-to-`UNKNOWN` cycle are asserted by the fixture tests.
- NFR-001: PASS. A failing legacy upgrade/downgrade can no longer leave an
  old-version file behind an open that reports success; it either persists the
  historical usable wipe deterministically or (32→33) rolls back and fails the
  open, preserving the pre-migration layout.
- NFR-002: PASS. The fallback produces a fresh current-shape database via
  `onCreate` inside one committed transaction, with deterministic assertions;
  integrity of successful paths is covered by the retained fixtures.
- NFR-012: PASS. Upgrade, downgrade/rollback, restore-of-fallback, and
  re-upgrade consistency are tested by the four suites, which this PR also
  promotes from compile-only to a permanent merge-gate emulator lane.

## Executed test surface

Independent re-execution by this auditor against
`2b479ae0d450aa96cb2d8f9953d23e615fff7696` (working tree clean except a
pre-existing uncommitted `specs/118-*` frontmatter line owned by the
implementing session; no code file modified):

```text
/opt/homebrew/share/android-commandline-tools/platform-tools/adb devices
  -> emulator-5554 device (AVD nunu_qpr2_api36_1 already up)

./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.MigrationTransactionOwnershipTest,com.android.launcher3.organizer.DatabaseHelperSchema33Test,com.android.launcher3.organizer.DowngradeSchema33Test,com.android.launcher3.organizer.InactiveGridDbNormalizationTest"
  -> exit 0; BUILD SUCCESSFUL in 24s; "Starting 10 tests on nunu_qpr2_api36_1(AVD) - 16" / "Finished 10 tests"
  -> build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/TEST-nunu_qpr2_api36_1(AVD) - 16-_-lawnWithQuickstepGithub.xml:
     tests="10" failures="0" errors="0" skipped="0"
     MigrationTransactionOwnershipTest 2, DatabaseHelperSchema33Test 6,
     DowngradeSchema33Test 1, InactiveGridDbNormalizationTest 1

./gradlew spotlessCheck
  -> exit 0; BUILD SUCCESSFUL in 1s (separate invocation)

./gradlew assembleLawnWithQuickstepGithubDebug
  -> exit 0; BUILD SUCCESSFUL in 1s, 445 tasks up-to-date from this session's
     instrumentation build of the identical tree (separate invocation)

gh run list --repo nunu1733/NunuLauncher --branch issue-118-migration-transaction-ownership --workflow CI
  -> pull_request run 32633914951 on the audited head branch

gh api repos/nunu1733/NunuLauncher/actions/runs/32633914951
  -> event=pull_request, head_sha=2b479ae0d450aa96cb2d8f9953d23e615fff7696,
     pull_requests=[122], conclusion=success

gh api .../runs/32633914951/jobs
  -> changes, validate-repo-contract, check-style, build-debug-apk,
     organizer-unit-tests, organizer-instrumentation-db-migration-tests (new lane),
     shared-writer/api35/issue52/issue99/issue53 lanes, final-status:
     all completed success, none skipped
```

The qualifying CI source jobs (`organizer-unit-tests`, `check-style`,
`build-debug-apk`) and the new DB-migration emulator lane all executed on the
audited SHA. Unlike the PR #75 audit, this audit additionally re-executed the
full instrumentation suite locally on the API 36 AVD, so AC-4/AC-5 evidence
rests on independent execution, not only compile verification.

## Findings

Verdict: pass. No blocking defect found; all six ACs, both ADR-0004
constraints, and NFR-001/NFR-002/NFR-012 are satisfied by independent code
review plus local re-execution and qualifying CI evidence.

1. This record and the `- NFR-012` line in the
   `specs/118-sqlite-migration-transaction-audit/spec.md` frontmatter were
   intentionally left uncommitted by this audit session per instruction. At
   the audited HEAD the committed spec defines only NFR-001/NFR-002 in its
   frontmatter, so the implementing session must push that one-line docs
   change together with this audit file; otherwise the gate will reject the
   NFR-012 citation as not defined in the referenced document. The delta after
   the audited SHA is docs-only, which keeps this Head SHA valid per
   github-workflow.md. Until then `High-risk gate / high-risk-evidence` is red
   solely due to the missing audit file, which is expected at audit time.
2. Two uncompiled upstream leftovers (`tests/src/com/android/launcher3/provider/LauncherDbUtilsTest.java:148`
   and `tests/src/com/android/launcher3/model/DbDowngradeHelperTest.java:198`)
   still invoke `DbDowngradeHelper.onDowngrade` without owning a transaction
   under the new caller-owned contract. They are not wired into this
   repository's Gradle build (`build.gradle` compiles only
   `tests/organizer-instrumentation`), so they are not merge-gate evidence and
   the accepted spec already records their status; if they are ever compiled
   here they will need caller-owned wrapping. Low risk, no separate tracking
   issue opened beyond this record.
3. `updateFolderItemsRank` additionally fixes a pre-existing cursor leak
   (try-with-resources) while removing its inner scope — behavior-neutral
   cleanup within the audited method, consistent with the spec disposition.
4. Local `assembleLawnWithQuickstepGithubDebug` reported all tasks up-to-date
   because this audit session's preceding instrumentation invocation had
   already built identical inputs; both commands ran as separate invocations
   as required, and spotlessCheck/build therefore verify this exact tree.
