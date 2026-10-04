# High-risk audit: PR #78 executor and shared-writer admission audit follow-ups

> Status: final (independent audit)
> Audit date: 2026-08-18

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/78
- Head SHA: e5aa78951ed9ea45a667eccdb3e4c0c8c95b121a
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32044541454 (merge gate on the audited head SHA; `final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract` all success; verified via `gh run view`)
- Criteria: specs/60-executor-writer-admission-audit/spec.md (`status: implemented` at this head) AC-01..AC-07, and Issue #60 "Outcome" requirements

## Scope

Audited the complete `main..e5aa78951e` diff:
11 files, +1801/-1. Production surface: `LayoutWriteCoordinator.java`
(per-entry exception isolation in `release()` deferred drain, 9 lines changed).
Test surface: 4 instrumentation test files (222+128+273+353 = 976 lines new)
in `tests/organizer-instrumentation/com/android/launcher3/organizer/`:
`BinderOperationFutureTest.java`, `LayoutWriteCoordinatorTest.java`
(extended), `NestedTransactionTest.java`,
`OrganizerReloadSupersessionTest.java`. Tooling:
`tools/repo-contract/validate_writer_inventory.py` (423 lines, executable
writer-inventory source-scan allowlist). CI wiring:
`.github/workflows/ci.yml` (2 lines added for writer inventory scan).
Documentation: `docs/assessment/issue-60-executor-writer-admission-audit.md`
(192 lines), `docs/assessment/issue-44-shared-writer-audit.md` (20 lines
appended), `specs/60-executor-writer-admission-audit/spec.md` and
`plan.md`.

Code-level verification (read from the diff/files, not the PR body):

- **Fix (AC-03)**: `LayoutWriteCoordinator.release()` lines 343-350 wraps each
  deferred callback in an individual try/catch catching `Throwable`. The
  `while (!toRun.isEmpty())` loop removes the first entry, runs it within a
  try/catch, and continues to the next regardless of outcome. The previous
  code (`toRun.removeFirst().runWithOperationFuture()`) would abort the
  entire drain on any thrown exception. The fix is minimal: no new public
  seam, no new fields, no change to the `DeferredRunnable` contract. The
  comment correctly references Issue #60 and explains why the outer catch
  is a safety net for `runOrDefer` callbacks (which have no inner try/catch)
  vs `runOrDeferWithOperationFuture` futures (which already complete
  exceptionally via their own inner try/catch).
- **Writer-inventory scanner (AC-02)**: `validate_writer_inventory.py` scans
  source files in `src/` and `lawnchair/src/` (excluding test directories)
  for 4 pattern categories: favorites SQL string literals, DB operations on
  `TABLE_NAME`, `ModelDbController` mutation calls, and raw DB file
  operations. All 18 allowlisted files are verified against actual source
  patterns. The scanner runs as part of CI `validate-repo-contract` job.
- **No new public coordinator, lease kind, or parallel seam**: The fix is
  contained within the existing `release()` method. All new tests use public
  seams (`LayoutWriteCoordinator.getInstance()`, `tryAcquire`, `runOrDefer`,
  `runOrDeferWithOperationFuture`, `Lease.close()`, `ModelDbController.
  newTransaction()`, `LauncherModel.forceReloadForOrganizer`, etc.).

## Criteria check

- **AC-01 (writer inventory)**: PASS. The scanner's `ALLOWLIST` dict enumerates
  18 unique files with pattern-kind and lease-kind/documentation reason for
  each entry. I spot-checked 8 entries against actual source:
  `RestoreDbTask.java` (favorites-sql confirmed at lines 452-454, db-file-delete
  confirmed at line 198, controller-call confirmed at line 264, favorites-db
  confirmed via `db.delete(Favorites.TABLE_NAME, ...)` at line 348 and
  `db.update(Favorites.TABLE_NAME, ...)` at line 356);
  `GridSizeMigrationUtil.java` (favorites-sql confirmed at line 212);
  `DatabaseHelper.java` (favorites-sql confirmed at lines 158, 177, 285-287,
  389, 423, 433, 450);
  `AutoInstallsLayout.java` (favorites-db confirmed at lines 648, 661);
  `ModelWriter.java` (controller-call confirmed at line 178, favorites-db at
  line 328);
  `LoaderCursor.java` (controller-call and favorites-db confirmed at lines
  414, 437, 463);
  `LawnchairBackup.kt` (db-file-recursive confirmed at line 87);
  `ModelDbController.java` (favorites-db confirmed at lines 1016, 1049, 1079).
  All 8 entries have correct lease kind and reason matching the actual source.

- **AC-02 (executable allowlist)**: PASS. `python3 tools/repo-contract/
  validate_writer_inventory.py` reports PASS on the current tree: 18 writer
  files verified (1025 source files scanned, 0 errors, 0 warnings). The
  scanner is wired into CI `validate-repo-contract` job (`.github/workflows/
  ci.yml` lines 92-93). I probed the scanner for obvious bypasses:
  a bare `execSQL("UPDATE favorites...")` in a new `src/` file is correctly
  flagged as ungated; a `controller.delete(TABLE_NAME, ...)` in a new file is
  flagged; `db.delete(TABLE_NAME, ...)` is flagged; `getDatabasePath(...)
  .delete()` is flagged. Test files are excluded by design (the scanner
  targets production source). A theoretical bypass exists for `execSQL` with
  a dynamically constructed table name that avoids the literal string
  `favorites` (e.g. `db.execSQL("UPDATE " + TABLE_NAME + " SET title=?")`
  where `TABLE_NAME` is a variable resolving to `favorites`); this is a
  known limitation of the heuristic approach (see G1).

- **AC-03 (FIFO exactly-once)**: PASS. Defect fix confirmed: each deferred
  callback runs in an isolated try/catch. Tests read line-by-line:
  `deferredFifoOrderAcrossMultipleLeases` asserts FIFO [1,2,3,4] across two
  lease cycles and `pendingDeferredCount() == 0` after each release;
  `throwingDeferredCallbackDoesNotPreventLaterEntries` asserts that a
  throwing `runOrDefer` entry does not prevent two later entries from running
  (runCount=2, pending=0);
  `deferredOperationFutureCompletesExceptionallyAndLaterEntriesStillRun`
  asserts that a throwing operation future completes exceptionally while
  later entries (both operation future and plain runOrDefer) complete
  normally (okFuture=42, runCount=1, pending=0);
  `reentrantReleaseDoesNotDoubleRunDeferredEntries` asserts that a reentrant
  `lease.close()` during the drain loop does not double-run entries (all 3
  ran exactly once, pending=0).

- **AC-04 (reload supersession)**: PASS. `OrganizerReloadSupersessionTest.java`
  read line-by-line:
  `singleRequestCompletesWithOutcomeCompleted` asserts COMPLETED for a single
  request;
  `subsequentRequestSupersedesPriorRequest` asserts A is SUPERSEDED and B is
  COMPLETED (A-then-B supersession);
  `staleCompletionIsRejectedAfterSupersession` asserts A is SUPERSEDED (not
  COMPLETED), proving A's `completeOrganizerReload` callback was rejected by
  the identity check;
  `cancellationByForceReloadIsTerminalExactlyOnce` asserts that a regular
  `forceReload()` during an in-flight organizer reload delivers SUPERSEDED
  exactly once;
  `completedRequestReceivesExactlyOneTerminalSignal` asserts that two
  sequential requests each receive COMPLETED independently (no cross-request
  signal leakage).

- **AC-05 (Binder future)**: PASS. `BinderOperationFutureTest.java` read
  line-by-line:
  `deferredCallbackRunsOnReleasingThreadNotModelExecutor` simulates a Binder
  thread calling `runOrDeferWithOperationFuture` with a supplier that does
  `MODEL_EXECUTOR.submit(...).get()`, verifies the callback runs on the
  releasing thread (not MODEL_EXECUTOR), and MODEL_EXECUTOR is healthy
  during the deferred window;
  `modelExecutorNotBlockedByOrganizerLease` asserts MODEL_EXECUTOR can
  execute independent tasks while an organizer lease defers tokenless
  operations;
  `looperExecutorInlineOptimizationPreventsSelfDeadlock` documents the
  `LooperExecutor.execute()` inline optimization (line 43: inline execution
  when on the looper thread) that makes the self-wait hazard impossible at
  the executor level even hypothetically.

- **AC-06 (nested transaction)**: PASS. `NestedTransactionTest.java` read
  line-by-line:
  `outerAndInnerTransactionCommitAsOneUnit` asserts both outer and inner
  writes persist after commit (savepoint semantics);
  `innerCloseWithoutCommitRollsBackInnerWrites` asserts inner writes are
  rolled back on close-without-commit while outer writes survive;
  `leaseHeldUntilOuterClose` asserts the coordinator lease is held until the
  outer `ModelDbController.newTransaction()` close, and another kind cannot
  acquire during the lease;
  `reentrantLeaseInnerCloseDoesNotReleaseOuterLease` asserts a reentrant
  lease close decrements recursion count but does not release the outer
  lease;
  `exceptionInsideInnerPropagatesAndOuterCloseReleasesLease` asserts an
  inner exception propagates, the lease stays held, and the outer close
  releases it.

- **AC-07 (restart evidence)**: PASS with notes. The requirement acknowledges
  that device-level process death/restart is not fully testable in this
  environment. The assessment documents:
  - Real 10-second TIMEOUT path (`OrganizerModelReloadAdapter.TIMEOUT_MILLIS`,
    line 25) is not injectable; the normal wait-and-signal flow is exercised
    by the supersession tests.
  - Device-level kill/restart with active helper, deferred FIFO, or raw-file
    restore is not tested. Protocol-level `RestartReconcilerTest` exists from
    prior work.
  - Instrumentation tests are compile-only in CI (per Issue #14).

## Executed test surface

Independent local re-runs against `6ea5885156` (functionally identical to the final head `e5aa78951e`, which only renames requirement IDs ER-xx to AC-xx in comments and docs) (JDK 21.0.12 homebrew,
ANDROID_HOME=/opt/homebrew/share/android-commandline-tools):

```text
./gradlew spotlessCheck
  -> BUILD SUCCESSFUL

./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks
  -> BUILD SUCCESSFUL (386 tasks executed)

./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac
  -> BUILD SUCCESSFUL (389 tasks)

python3 tools/repo-contract/validate_writer_inventory.py
  -> PASS: 18 writer files verified against allowlist (1025 source files
     scanned, 0 errors, 0 warnings)
```

All new test files compile via the androidTest compilation task. CI executes
`organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`
on the head SHA; instrumentation tests are compile-only in this audit (no
AVD/device execution — CI scopes instrumentation out per Issue #14), so the
new tests' runtime behavior on a device is unverified here (G2).

## Findings

Verdict: **pass-with-notes** (mergeable once this audit record is committed as a
docs-only commit and the high-risk gate re-runs green; no code changes required
by this audit).

1. **[Low] G1 — Scanner heuristic bypass for dynamic table name construction.**
   The `favorites-sql` pattern uses `(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM|...)\s+favorites`
   which matches any occurrence of a SQL keyword followed by whitespace and
   the literal `favorites` string. A writer that constructs SQL dynamically
   without the literal string `favorites` (e.g. `db.execSQL("UPDATE " + TABLE_NAME
   + " SET title=?")` where `TABLE_NAME` is a variable resolving to `favorites`)
   would bypass the heuristic. The scanner's `favorites-db` pattern (`.delete/
   .update(TABLE_NAME, ...)`) and `controller-call` pattern would catch most
   real-world alternatives, but the combination of `execSQL` + dynamic table
   name using a variable (not `Favorites.TABLE_NAME` constant, which contains
   the string "favorites") is a residual gap. The scanner's docstring documents
   its heuristic nature. No concrete ungated writer exists in the current tree.

2. **[Low] G2 — Instrumentation tests compile-only.** CI has no instrumentation
   execution (Issue #14). The 4 new test files (976 lines) were compiled
   locally and in CI androidTest compilation surfaces but not executed on a
   device or emulator in this audit. The unit test portion of
   `LayoutWriteCoordinatorTest` (the AC-03 tests) is technically runnable as
   unit tests but lives in the instrumentation test source set; the existing
   `LayoutWriteCoordinatorTest` class was already there pre-PR in the same
   location.

3. **[Low] G3 — Device-level process death/restart untested.** AC-07
   acknowledges this gap. No test simulates process death mid-deferred-FIFO,
   mid-raw-file-restore, or with an active recovery lifecycle. The
   `forceReloadForOrganizer` "no callbacks" path (LauncherModel.java lines
   471-473) is exercised by the cancellation test, but full device-level
   restart reconciliation is inspection-only.

4. **[Low] G4 — Real 10-second TIMEOUT not injectable.**
   `OrganizerModelReloadAdapter.TIMEOUT_MILLIS` is a non-injectable constant.
   The adapter's `completed[0]` guard and `lock.wait(remaining)` path are
   exercised by the normal wait-and-signal flow. A timeout-specific test
   would require making the constant injectable or injecting a mock clock,
   which is not currently implemented.

5. **[Low] G5 — Stale allowlist entry warning not exercised in CI.** The
   scanner emits a warning when an allowlist entry claims a pattern_kind that
   the scan did not detect. This warns about stale/overly-broad claims but
   does not cause a non-zero exit. The current tree has 0 warnings; a future
   refactor that removes a pattern from a file without updating the allowlist
   would silently produce a warning-only signal. Consider promoting stale
   claims to an error or adding a `--strict` mode.

Process note: the `High-risk gate` workflow run 32042763375 on head
`e5aa78951e` fails solely because this audit record update was not yet committed. After
this record lands (docs-only commit), the gate should pass with no further
code changes; any subsequent code change requires a fresh audit on the new
head.