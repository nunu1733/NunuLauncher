# High-risk audit: PR #77 serialize runtime raw-file restores

> Status: final (re-audit)
> Audit date: 2026-08-17

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/77
- Head SHA: c0931b1aaa09f1ee8da512ed3d98f29c240c45a8
- CI run (merge gate on the audited head SHA): https://github.com/nunu1733/NunuLauncher/actions/runs/32038322987 (`final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `changes`, `validate-repo-contract` all success; verified via `gh run view`)
- Criteria: specs/58-serialize-runtime-restores/spec.md (`status: accepted` at this head) AC-1..AC-7, scenario matrix SR-01..SR-12, and Issue #58 "Required outcome"
- Prior audit: FAIL record against head `3fe30650c28efa2534bba533f6e7e4ec3c782f32` (same file, this session's history); findings and resolution below.

## Scope

Audited the complete `main..c0931b1aaa09f1ee8da512ed3d98f29c240c45a8` diff:
10 files, +878/-73. Production surface: `LayoutWriteCoordinator.java`
(restore-family reentrancy `tryReenterRestoreFamily`, `defersTokenlessWork`
narrowing), `RestoreDbTask.java` (lease-first `performRestore`,
`prepareForRawFileRestore`/`reloadAfterRestore` with nullable-app
`@VisibleForTesting` overloads), `ModelDbController.java`
(`closeActiveHelperForRestore` seam, restore-family reentry in
`getCoordinatorLease`), `LauncherModel.java` (`quiesceForRestore`),
`LawnchairBackup.kt`, `NovaBackupConverter.kt`, `LawndeckManager.kt`
(single-lease lifecycles). Test surface: instrumentation suite
`tests/organizer-instrumentation/com/android/launcher3/organizer/RestoreLeaseSerializationTest.java`
(8 tests).

Code-level verification (read from the diff/files, not the PR body):

- Lease-before-getDb in `performRestore` is real: `tryReenterRestoreFamily()`
  first, else `acquireBlockingQuietly(RESTORE)`, and only then
  `controller.getDb()`; the SQLite transaction takes a nested same-kind/token
  view via `tryReenter` as a separate try-with resource, so a transaction
  construction failure cannot leak a recursion count, and only the outermost
  lease unlocks.
- One lease spans each of the three paths (verified in the Kotlin diffs):
  `LawnchairBackup.restore` (`BACKUP_RESTORE` covers quiesce, directory
  delete, prefs write, `readZip`, fresh-controller `performRestore`, reload);
  `NovaBackupConverter.convertAndRestore` (`BACKUP_RESTORE` covers staging,
  IDp/prefs writes, `restored.db` copy, `performRestore`, reload,
  `pinImportedDeepShortcuts`); `LawndeckManager.restoreBackup`
  (`DECK_FILE_RESTORE` covers quiesce, copy, `performRestore`, reload;
  `restartLauncher` is retained only when `LauncherAppState.INSTANCE
  .getNoCreate()` is null — baseline fallback preserved).
- Helper close before raw replacement: `prepareForRawFileRestore` no-ops when
  the app is null, else `quiesceForRestore()` (stops loader, clears
  `mModelLoaded` under `mLock`) + `closeActiveHelperForRestore()` (nulls
  `mOpenHelper` under `synchronized(this)` before `helper.close()`); fresh
  helpers come from new `ModelDbController` instances or lazy
  `createDbIfNotExists`.
- Restore-family reentrancy excludes other kinds: `tryReenterRestoreFamily`
  requires same thread + restore kind; `MODEL_WRITER`/`GRID_MIGRATION`/
  `ORGANIZER` cannot enter while a restore lease is held (asserted by test).
- Deferral narrowing (prior Finding 3): `runOrDefer` /
  `runOrDeferWithOperationFuture` now defer tokenless work only behind
  `ORGANIZER` or restore-family leases (`defersTokenlessWork`); baseline
  `MODEL_WRITER`/`GRID_MIGRATION` executor semantics are restored and
  pinned by `gridMigrationAndModelWriterLeasesDoNotDeferTokenlessWork`.
- Baseline fallback: nullable-app overloads make the null-guard directly
  testable; Deck path keeps `restartLauncher` only in the app-absent case.

## Prior-audit findings and resolution

| # | Prior finding | Resolution at c0931b1 |
|---|---|---|
| 1 | [Blocking] spec 58 `status: draft` | Fixed: spec committed at this head with `status: accepted` (frontmatter verified via `git show HEAD:specs/...`). The high-risk validator's criteria check now resolves. |
| 2 | [Blocking] missing SR-07/SR-08 fault-injection tests | Partially fixed: `sanitizationFailureReturnsFalseAndDoesNotWedgeCoordinator` (SR-07, sanitization failure -> `performRestore == false`, coordinator not wedged, deferred queue drained) added; no process-death restart-reconciliation test (SR-08) exists — remaining gap G2. |
| 3 | [Medium] `runOrDefer` deferred behind ANY lease | Fixed: `defersTokenlessWork` restricts deferral to `ORGANIZER` + restore family; dedicated test `gridMigrationAndModelWriterLeasesDoNotDeferTokenlessWork` asserts GRID_MIGRATION/MODEL_WRITER leases do not defer. |
| 4 | [Medium] missing AC-4/AC-6 evidence | Fixed: `closeActiveHelperClosesHandleAndReopensFreshHelper` (SR-06/AC-4: old handle closed, `mOpenHelper` cleared, DB file deletable while closed, fresh helper on next `getDb`, identity differs) and `prepareAndReloadAreNoOpsWhenAppStateAbsent` (SR-11/AC-6 null-overload no-op). |
| 5 | [Low] AC-1 evidence coordinator-level only | Unchanged (G1 below). |
| 6 | [Low] residual stale-handle risk from previously handed-out `SQLiteDatabase` refs | Unchanged (G4 below); coordinator lease excludes coordinate operations, no concrete defect found. |
| 7 | [Low] in-flight loader past `runInternal` stop check | Unchanged (G5 below); `stopLoader` is best-effort at check points. |

## Criteria check

- AC-1 (one restore-family lease across replacement through reload): PASS at
  code level for all three paths (verified in diff). Evidence remains
  coordinator-level: `performRestoreReentersOuterRestoreFamilyLease` proves
  lease continuity (same kind/token) across the nested restore and clean
  unwinding, and the Kotlin `use` blocks structurally span replacement
  through reload; no test drives the three real restore entry points
  end-to-end (see G1).
- AC-2 (no DB open before lease): PASS.
  `directPerformRestoreAcquiresLeaseBeforeOpeningDb` — `LeaseProbeController
  .getDb` probes `tryReenterRestoreFamily` at open time and asserts a held
  `RESTORE` lease (kind verified); returns null so the failure lands in the
  documented boolean surface. Code ordering independently confirmed.
- AC-3 (exact interleaving deferral): PASS.
  `tokenlessModelAndProviderWorkDeferBehindRestoreLease` asserts a tokenless
  model runnable and a provider operation future stay pending inside a
  `DECK_FILE_RESTORE` lease and complete only after release (SR-04/SR-05);
  `concurrentModelWriterAcquisitionBlocksUntilRestoreLeaseRelease` covers
  blocking acquisition (SR-10); `restoreFamilyIsReentrantAcrossRestoreKinds
  AndExcludesOthers` covers SR-09 exclusion semantics.
- AC-4 (helper closed before replacement, fresh helper reopened): PASS.
  `closeActiveHelperClosesHandleAndReopensFreshHelper` (SR-06) uses an
  isolated test DB and asserts: prior handle open -> closed after
  `closeActiveHelperForRestore`, `mOpenHelper` null, DB file deletable
  while closed, next `getDb` returns a distinct open database with a fresh
  helper, usable via `SELECT 1`.
- AC-5 (failure injection, no silent partial restore): PASS with notes.
  `sanitizationFailureReturnsFalseAndDoesNotWedgeCoordinator` (SR-07) builds
  a DB with an invalid `favorites` schema so sanitization throws inside
  `performRestore`, asserts the baseline `false` surface, that a subsequent
  `tryAcquire(MODEL_WRITER)` succeeds, and that tokenless work runs
  immediately with `pendingDeferredCount() == 0` (lease released by the
  try-with-resources scope). No mid-raw-copy fault injection and no
  process-death reconciliation test (SR-08) — see G2/G3.
- AC-6 (baseline behavior when organizer application unavailable): PASS with
  notes. `prepareAndReloadAreNoOpsWhenAppStateAbsent` exercises the
  nullable-app overloads (no-op, coordinator untouched) and the production
  entry points without throwing. The end-to-end app-absent raw-swap
  production path (Deck `restartLauncher` branch) is verified by inspection
  only — see G3.
- AC-7 (spotlessCheck and relevant suites pass on CI): PASS. CI run
  32038322987 on the audited head SHA: `final-status`, `organizer-unit-tests`,
  `check-style`, `build-debug-apk`, `changes`, `validate-repo-contract` all
  success. The `high-risk-gate` run 32038322994 on the same head fails only
  with "no docs/assessment/pr-77-<slug>.md audit record for this PR" — this
  record is the remedy; per workflow rule 4, only docs-only commits may
  follow this audit unless code changes (then re-audit).

Issue #58 required outcomes: single-lease coverage of all three paths,
lease-first `performRestore`, quiesce + helper close, correlated reload
(replacing unconditional restart where the model exists), interleaving
deferral tests, and failure-injection coverage are delivered at coordinator/
seam level; SR-08 restart reconciliation remains inspection-only (G2).

## Executed test surface

Independent local re-runs against `c0931b1aaa09f1ee8da512ed3d98f29c240c45a8`
(JDK 21.0.12 homebrew, ANDROID_HOME=/opt/homebrew/share/android-commandline-tools):

```text
./gradlew spotlessCheck
  -> BUILD SUCCESSFUL

./gradlew assembleLawnWithQuickstepGithubDebug
  -> BUILD SUCCESSFUL (445 tasks)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks
  -> BUILD SUCCESSFUL (386 tasks executed)

./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac
  -> BUILD SUCCESSFUL (389 tasks)
```

The new `RestoreLeaseSerializationTest` cases (including the three audit-fix
tests) were read line-by-line and verified to assert what their headers
claim; all eight compile via the androidTest compilation task. CI executes
`organizer-unit-tests`, `check-style`, `build-debug-apk` on the head;
instrumentation tests are compile-only in this audit (no AVD/device
execution — CI scopes instrumentation out per Issue #14), so the new tests'
runtime behavior on a device is unverified here (G6).

## Findings

Verdict: **pass-with-notes** (mergeable once this record is committed as a
docs-only commit and the high-risk gate re-runs green; no code changes
required by this audit).

1. **[Low] G1 — AC-1 evidence is coordinator-level only.** No test drives
   `LawnchairBackup.restore`, `LawndeckManager.restoreBackup`, or
   `NovaBackupConverter.convertAndRestore` end-to-end to observe one
   continuous lease from first file mutation to reload; continuity is
   inferred from the `use` block structure plus the `performRestore`
   reentry test. Follow-up: instrumentation test hooking a lease observer
   into the three real paths.
2. **[Low] G2 — SR-08 (process death mid-restore) untested.** No
   restart-reconciliation test exists; `RestoreDbTask.restoreIfNeeded`
   consistency after process death mid-restore is verified by inspection
   only. The spec's SR-08 row remains evidence-less; track in a follow-up
   issue.
3. **[Low] G3 — AC-6 end-to-end app-absent path inspection-only.** The
   null-overload no-ops are tested, but a real app-absent raw swap
   (including the Deck `restartLauncher` fallback branch) is not exercised.
   SR-07 fault injection covers sanitization failure only, not a failure
   mid-raw-copy.
4. **[Low] G4 — residual stale-handle risk.**
   `closeActiveHelperForRestore` closes only the controller's current
   helper; `SQLiteDatabase` references previously handed out by `getDb()`
   to completed operations are not tracked. The exclusive lease plus helper
   close covers all identified production paths; no concrete defect found.
5. **[Low] G5 — in-flight loader reads.** `quiesceForRestore` stops the
   loader at its check points; a loader already past the stop check could
   still read when replacement starts. Deferral prevents new loader starts;
   no interleaving test covers a mid-run loader at restore time.
6. **[Low] G6 — instrumentation tests compile-only.** CI has no
   instrumentation execution (Issue #14); the eight new tests were compiled
   locally and in CI androidTest compilation surfaces but not executed on a
   device in this audit.

Process note: the `high-risk-gate` workflow run 32038322994 on head
c0931b1 fails solely because this audit record was not yet committed. After
this record lands (docs-only commit), the gate should pass with no further
code changes; any subsequent code change requires a fresh audit on the new
head.
