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
| Reload supersession (AC-04) | **CONFIRMED** | `OrganizerReloadSupersessionTest.java`: Issue #119 adds a one-shot `BgDataModel.Callbacks.getPagesToBindSynchronously()` pre-completion barrier. A is held until B or regular `forceReload()` is issued, then A=`SUPERSEDED`, stale completion is rejected, and B/normal reload completion is observed without wall-clock sleeps. |
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

3. **Instrumentation runtime execution (historical #60 baseline).** The
   original #60 verification target compiled instrumentation tests only (per
   Issue #14). The current CI portfolio now executes the shared-writer
   coordinator suite on a clean API 36 emulator; Issue #119 adds
   `OrganizerReloadSupersessionTest` to that existing lane rather than creating
   a second lane.

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

## Issue #119 deterministic-barrier follow-up

> Follow-up date: 2026-08-24
> Scope: test/CI/documentation only; production source and API unchanged

The original AC-04 tests used `Thread.sleep(500)` to guess that request A was
still active. The three timing assumptions were replaced by a one-shot
`BgDataModel.Callbacks.getPagesToBindSynchronously()` callback barrier in
`OrganizerReloadSupersessionTest`. The callback counts down an A-reached latch
and blocks until the test explicitly releases it. `BaseLauncherBinder` reaches
the organizer completion signal after this bind callback, so A cannot complete
before the test issues the competing operation.

The A→B and stale-completion tests submit B on a second executor worker,
assert A=`SUPERSEDED`, release the barrier, then assert B=`COMPLETED`. The
regular `forceReload()` cancellation test also triggers cancellation from a
worker because the main thread is intentionally blocked by the barrier. Each
test has bounded waits and a `finally` release plus executor shutdown, so a
failed assertion cannot leave the instrumentation suite wedged.

`finishBindingItems` is retained only by the existing model-idle helper; it is
not used as the supersession barrier because the production organizer
completion signal is scheduled before pending `finishBindingItems` callbacks.

The existing API 36 clean-emulator
`organizer-instrumentation-shared-writer-tests` lane now includes
`OrganizerReloadSupersessionTest`. No new lane, production seam, or public API
is required.

## Issue #156 follow-up: Hybrid Hotseat atomic admission

> Follow-up date: 2026-08-27
> Scope: tokenless Hybrid Hotseat DB work that previously posted directly to
> `MODEL_EXECUTOR` and could synchronously wait on an external coordinator lease
> Implementation status: **implemented**. API 36 connected instrumentation and
> fresh-workspace device evidence completed on 2026-08-27; remote CI and the
> independent high-risk audit remain merge prerequisites.

Issue #156 found a writer path that the historical Issue #60 inventory did not
scan: `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java`.
`createBackup` and `restoreBackup` each posted tokenless
`ModelDbController.newTransaction()` work to the single `MODEL_EXECUTOR`. When
an organizer or restore-family lease was held, the transaction's fallback
could synchronously wait and starve the correlated organizer reload.

The corrective source change preserves the single `LayoutWriteCoordinator`
serialization seam. `HotseatRestoreHelper` now schedules its DB body through
`runModelWriterOrDefer`. That operation makes one monitor-protected decision:
it grants an outer `MODEL_WRITER` lease and runs the body, including the
existing same-thread transaction reentry, or it appends a continuation that
reposts the same atomic admission attempt to `MODEL_EXECUTOR`. A deferred
callback never performs Hotseat DB work on the lease-releasing thread. This
closes both the already-held-holder case and the race where a holder appears
after scheduling but before the model executor begins admission.

The executable inventory now scans `src`, `quickstep/src`, and
`lawnchair/src`; its bounded `dbController` receiver pattern covers both
Hotseat transaction expressions. The current source scan passes with **19
allowlisted writer files across 1,437 scanned source files, 0 errors, and 0
warnings**. The one new inventory row is as follows.

| File | Pattern kind(s) | Lease kind / reason |
|---|---|---|
| `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java` | controller-call | Hybrid Hotseat backup/restore uses `MODEL_EXECUTOR` atomic `LayoutWriteCoordinator.runModelWriterOrDefer` admission. |

The inventory's responsibility remains intentionally limited to identifying
writer paths and requiring their documented allowlist registration. It cannot
prove that an admission gate stays structurally present. The focused
`HotseatRestoreAdmissionTest` owns that behavioral guarantee: it uses the
actual helper scheduler seam and a deterministic single-executor fixture to
stage the order `schedule while empty → acquire ORGANIZER → begin admission`.
It asserts FIFO deferral, exact-token correlated-loader progress before
explicit lease release, re-deferral when a new holder appears, same-thread
writer reentry, and finally-based release after an admitted exception. Its
connected cases also execute the real uncontended `createBackup`, backup-absent
`restoreBackup`, and backup-present/drop-after-use `restoreBackup` DB paths.
The existing API 36 shared-writer CI lane is extended to include this test.

### Scope stop condition

The `quickstep/src` scan extension produced no writer path beyond
`HotseatRestoreHelper`. If a subsequent scan reveals another path, Issue #156
may change its production behavior only when inspection shows the same cause:
tokenless DB work running on `MODEL_EXECUTOR` can synchronously wait for an
external coordinator lease. Every other ownership or lifecycle finding must
be recorded with source evidence and split into its own Issue before an
allowlist rationale or product change is added. This preserves the Issue #156
boundary while keeping the inventory fail-closed.

### Connected-environment evidence

> Verification date: 2026-08-27
> Environment: macOS arm64, OpenJDK 21.0.12, Android SDK Platform 36.1, Build
> Tools 36.1.0, and isolated `issue142_api36` API 36 / Android 16 emulator.

| Surface | Result |
|---|---|
| `spotlessCheck` | Passed when run as a standalone `--no-configuration-cache` invocation. Combining it with Android compile/instrumentation tasks triggers an unrelated Gradle implicit-dependency validation error. |
| Organizer and full JVM suites | Passed: focused organizer gate and `testLawnWithQuickstepGithubDebugUnitTest --rerun-tasks`. |
| Compile and APK | Passed: Android-test Java compile and `assembleLawnWithQuickstepGithubDebug`. |
| Focused Hotseat instrumentation | Passed: 8 tests in `HotseatRestoreAdmissionTest`. |
| Shared-writer regression | Passed: 21 tests across Hotseat, coordinator, and reload-supersession classes. |

For fresh-workspace evidence, the debug launcher was installed on the isolated
emulator, its package data was cleared, and it was temporarily assigned the
HOME role. The review displayed 15 targets over two pages. Explicit apply
confirmation produced the `Deferring atomic MODEL_WRITER runnable; queue size=1`
log, followed by `APPLY_RECOVERED stage=A7 err=APPLY_FAILURE.VERIFICATION_FAILED`.
After more than the ten-second starvation window, `MODEL_RELOAD_FAILED` had zero
logcat matches and UI truthfully reported that the previous layout was restored.
The emulator HOME role was restored to `com.google.android.apps.nexuslauncher`
and the debug package was absent after cleanup.

[Issue #155](https://github.com/nunu1733/NunuLauncher/issues/155) was **Open**
at the evidence point. The fresh-workspace A7 verification failure is therefore
recorded as its separate layout-overlap result, not as a #156 regression: the
#156 Hotseat task deferred rather than blocking and recovery did not terminate
with `MODEL_RELOAD_FAILED`. The exact commit SHA, GitHub Actions `final-status`
run URL, and an independent high-risk audit must be added to the eventual PR
assessment before merge.
