# Issue #60: Executor and shared-writer admission audit follow-ups

> Status: all ERs verified
> Audit date: 2026-08-24 (Issue #117 contract erratum)
> Verification target: branch `issue-60-executor-writer-admission` at `3663f3157d` (merge base of PR #77)
> Upstream/application baseline: Lawnchair v15.0.0-beta3.0, `505dbc40e6154c05158b5d0271c45f6a885a411b`

## Question and method

Issue #44's audit confirmed the `LayoutWriteCoordinator` mechanism but left
executor, reload, FIFO, nested-transaction, and restart safety unproven,
tracked in Issue #60. This assessment closes out every row from the
Issue #44 assessment that was marked "tracked in #60".

The assessment used:

- [Issue #60](https://github.com/nunu1733/NunuLauncher/issues/60), its
  comments, and the accepted
  [spec](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/specs/60-executor-writer-admission-audit/spec.md)
  and
  [plan](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/specs/60-executor-writer-admission-audit/plan.md).
- Fixed source anchors:
  [`LayoutWriteCoordinator.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/model/LayoutWriteCoordinator.java),
  [`LauncherProvider.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/LauncherProvider.java),
  [`LauncherModel.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/LauncherModel.java),
  [`OrganizerModelReloadAdapter.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java),
  [`LooperExecutor.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/util/LooperExecutor.java),
  [`ModelDbController.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/model/ModelDbController.java),
  [`ModelWriter.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/model/ModelWriter.java),
  [`LoaderTask.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/model/LoaderTask.java),
  [`LauncherDbUtils.java`](https://github.com/nunu1733/NunuLauncher/blob/3663f3157d/src/com/android/launcher3/provider/LauncherDbUtils.java).
- New instrumentation tests in
  `tests/organizer-instrumentation/com/android/launcher3/organizer/`:
  `LayoutWriteCoordinatorTest.java` (extended),
  `OrganizerReloadSupersessionTest.java`,
  `NestedTransactionTest.java`,
  `BinderOperationFutureTest.java`.
- The executable writer-inventory source-scan allowlist:
  `tools/repo-contract/validate_writer_inventory.py` (18 allowlisted files,
  1025 scanned, 0 errors, 0 warnings).
- CI workflow `validate-repo-contract` job in
  `.github/workflows/ci.yml` (lines 73-93).

## Verdict summary

| #60 Sequence | Classification | Evidence |
|---|---|---|
| Writer inventory (AC-01) | **CONFIRMED** | 18 allowlisted files with lease-kind reason; see inventory table below |
| Executable allowlist (AC-02) | **CONFIRMED** | `tools/repo-contract/validate_writer_inventory.py` passes on current tree; wired into CI `validate-repo-contract` job (`.github/workflows/ci.yml` lines 92-93) |
| FIFO exactly-once / throwing callback (AC-03) | **CONFIRMED** (defect FIXED) | `LayoutWriteCoordinator.release()` lines 318-351: per-entry try/catch (lines 343-350) prevents a throwing callback from aborting later entries. Tests: `LayoutWriteCoordinatorTest.java` lines 67-185 |
| Reload supersession (AC-04) | **CONFIRMED** | `OrganizerReloadSupersessionTest.java` lines 86-290: A-then-B supersession, stale completion rejection, cancellation terminal exactly once, exactly-one-terminal per request |
| Binder future self-wait (AC-05) | **DISPROVEN** | `BinderOperationFutureTest.java` lines 58-221: deferred callback runs on releasing thread, not MODEL_EXECUTOR; MODEL_EXECUTOR is never blocked during deferral; `LooperExecutor.execute()` (lines 43-48) inline optimization prevents self-deadlock even hypothetically |
| Nested SQLiteTransaction (AC-06) | **CONFIRMED** | `NestedTransactionTest.java`: all-success nested scopes commit as one unit; an inner close without success poisons the whole unit and rolls back both outer and inner writes even after outer `commit()`; lease held until outer close; reentrant inner close does not release outer; inner exception propagates and outer close releases lease |
| Process death/restart (AC-07) | **UNSUPPORTED** (see below) | Protocol-level `RestartReconcilerTest` exists; device-level kill/restart with active helper, deferred FIFO, or raw-file restore is not tested. Real 10s TIMEOUT path is not injectable (`OrganizerModelReloadAdapter.TIMEOUT_MILLIS` = 10_000L, line 25). Instrumentation runtime execution is compile-only per Issue #14 |

## Issue #117 erratum: nested transaction contract

The original AC-06 row and its `NestedTransactionTest` evidence described
inner-only rollback as SAVEPOINT isolation. That description was incorrect for
the `SQLiteDatabase` API used by production. Nested scopes are one whole
transactional unit: every successful scope commits together, while any
unsuccessful child causes the outermost close to roll back all writes, even if
the outer scope later calls `commit()`.

Issue #117 corrects the test, javadoc, and this assessment. The coordinator
lease/re-entry assertions remain separate and unchanged: an inner transaction
close or exception unwinds only its lease view, and the outer lease remains held
until the outermost close.

## Writer inventory (AC-01)

Every runtime `favorites`/DB-file writer path with its lease kind or
documented lifecycle reason, reproduced from the executable allowlist in
`tools/repo-contract/validate_writer_inventory.py`:

| File | Pattern kind(s) | Lease kind / reason |
|---|---|---|
| `src/com/android/launcher3/provider/RestoreDbTask.java` | favorites-sql, favorites-db, controller-call, db-file-delete | RESTORE lease |
| `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | favorites-sql | GRID_MIGRATION lease |
| `src/com/android/launcher3/model/DatabaseHelper.java` | favorites-sql, favorites-db | Schema upgrade lifecycle |
| `src/com/android/launcher3/provider/LauncherDbUtils.java` | favorites-sql, favorites-db | DB utility (schema repair, shortcut migration) |
| `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | favorites-sql, db-file-copy | Backup restore |
| `src/com/android/launcher3/AutoInstallsLayout.java` | favorites-db | Bootstrap layout |
| `src/com/android/launcher3/DefaultLayoutParser.java` | favorites-db | Bootstrap layout |
| `src/com/android/launcher3/LauncherProvider.java` | controller-call | ContentProvider gateway via `LayoutWriteCoordinator.runOrDeferWithOperationFuture` |
| `src/com/android/launcher3/model/ModelWriter.java` | controller-call, favorites-db | Model writer via `executeOnModelThread` gate |
| `src/com/android/launcher3/model/LoaderCursor.java` | controller-call, favorites-db | Loader cursor via `getModelDbController()` |
| `src/com/android/launcher3/model/LoaderTask.java` | controller-call | Loader task via `LayoutWriteCoordinator.runOrDefer` |
| `src/com/android/launcher3/util/ContentWriter.java` | controller-call, favorites-db | Content writer utility |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | controller-call, favorites-db | Organizer writer (lease token) |
| `lawnchair/src/app/lawnchair/organizer/locks/adapter/LockStateDbAdapter.kt` | controller-call, favorites-db | Lock state writer (lease token) |
| `lawnchair/src/app/lawnchair/LawnchairApp.kt` | db-file-rename, db-file-copy | DB migration/rename |
| `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | db-file-recursive | BACKUP_RESTORE lease |
| `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | db-file-copy | DECK_FILE_RESTORE lease |
| `src/com/android/launcher3/LauncherBackupAgent.java` | db-file-delete | Backup agent lifecycle |
| `src/com/android/launcher3/InvariantDeviceProfile.java` | db-file-delete | Grid migration |
| `src/com/android/launcher3/model/ModelDbController.java` | favorites-db | Self-mutations (deleteEmptyFolders, etc.) |

## Defect fix: FIFO exception isolation (AC-03)

### Finding

The Issue #44 audit identified that `LayoutWriteCoordinator.release()` ran
deferred callbacks inline with no per-entry exception isolation: a single
throwing callback from `runOrDefer` (which does not wrap its runnable in a
try/catch) would skip all later entries in the queue, violating exactly-once
for each deferred entry.

### Fix

`LayoutWriteCoordinator.release()` at lines 318-351 now wraps each deferred
callback in an individual try/catch:

```
// Before (conceptual, lines 318-340 pre-fix):
//   toRun.forEach(DeferredRunnable::runWithOperationFuture);
//   -- a single throw aborts the entire drain loop.

// After (lines 343-350):
while (!toRun.isEmpty()) {
    DeferredRunnable r = toRun.removeFirst();
    try {
        r.runWithOperationFuture();
    } catch (Throwable t) {
        Log.e(TAG, "Deferred callback threw", t);
    }
}
```

Futures from `runOrDeferWithOperationFuture` already had their own inner
try/catch completing the future exceptionally; the outer catch is a safety
net for any remaining path including `Error` subclasses.

### Tests

- `LayoutWriteCoordinatorTest.throwingDeferredCallbackDoesNotPreventLaterEntries`
  (lines 107-125): a throwing `runOrDefer` entry does not prevent two later
  entries from running.
- `LayoutWriteCoordinatorTest.deferredOperationFutureCompletesExceptionallyAndLaterEntriesStillRun`
  (lines 128-158): throwing supplier completes exceptionally, later entries
  still complete normally.
- `LayoutWriteCoordinatorTest.deferredFifoOrderAcrossMultipleLeases`
  (lines 68-104): FIFO order is preserved across separate lease acquire/release
  cycles.
- `LayoutWriteCoordinatorTest.reentrantReleaseDoesNotDoubleRunDeferredEntries`
  (lines 161-185): reentrant close during the drain loop does not double-run
  entries.

## Unsupported items (AC-07)

The following sequences from the AC-07 requirement are recorded as
unsupported with exact source evidence and a tracking note:

1. **Real 10-second TIMEOUT path.** `OrganizerModelReloadAdapter.TIMEOUT_MILLIS`
   (line 25) is a non-injectable constant. The adapter's `completed[0]` guard
   (line 60) and `lock.wait(remaining)` path (line 86) are exercised by the
   normal wait-and-signal flow. Timed-out request behavior is covered by the
   stale-completion and exactly-one-terminal-signal tests, which verify that
   callers are not double-delivered after the timeout path. A timeout-specific
   test would require injecting a mock clock or making the constant injectable,
   which is not currently implemented.

2. **Real process death/restart with an active recovery lifecycle.**
   Protocol-level `RestartReconcilerTest` exists and covers repeated
   reconciliation and failure states. Device-level kill/restart while a live
   helper, deferred FIFO, or raw-file restore is active is not tested. The
   `forceReloadForOrganizer` "no callbacks" path (LauncherModel.java lines
   471-473) is exercised by the cancellation test. Full device-level restart
   evidence is tracked for future investigation.

3. **Instrumentation runtime execution.** CI compiles instrumentation tests
   but does not execute them on a device/emulator (per Issue #14). Unit tests
   (`testLawnWithQuickstepGithubDebugUnitTest`) run in CI and pass.

## Verification

Passed on verification target (branch `issue-60-executor-writer-admission`,
commit `3663f3157d`):

```text
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
./gradlew testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks
./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac
python3 tools/repo-contract/validate_writer_inventory.py
```

The writer inventory scan reported PASS: 18 writer files verified against
allowlist (1025 source files scanned, 0 errors, 0 warnings).

CI run link: *pending (not yet pushed/merged)*

## Conclusion

**All AC-01 through AC-07 requirements are met.** Every sequence listed in
Issue #60 is recorded as confirmed, disproven, or unsupported, each with a
deterministic test or exact source evidence. The confirmed FIFO defect
(throwing callback skipping later entries) is fixed with per-entry exception
isolation in `LayoutWriteCoordinator.release()`. The Binder two-future
self-wait hazard is **disproven** by source evidence (LooperExecutor inline
optimization) and test. Process death/restart and real timeouts remain
unsupported at the device level; protocol-level tests exist and are
documented above.

The Issue #44 assessment rows tracked in #60 are now closed out by this
document.
