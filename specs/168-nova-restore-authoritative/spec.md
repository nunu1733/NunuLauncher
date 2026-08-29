---
issue: "#168"
status: proposed
requirements: []
updated: 2026-08-29
---

# Make one Nova backup restore authoritative and restore-safe in cleanUpDatabases

## Problem

Evidence: [docs/assessment/issue-167-nova-two-pass-restore.md](../../docs/assessment/issue-167-nova-two-pass-restore.md)
@ `3b079da0a8` ([RT]/[SRC]/[H] labels below follow that document's key).

One Nova backup restore does not establish the imported workspace. Pass 1
stages, converts and sanitizes the full import correctly into the staged grid
DB (`launcher_6_5_5.db`), then silently loses it:

1. The converter commits the backup grid to all pref stores and field-assigns
   `idp.dbFile` (`NovaBackupConverter.kt:170-190`), but the live in-process
   grid binding demonstrably stays on the old grid (logcat:
   `migrateGridIfNeeded: target db is same as current: launcher_5_4_5.db`).
   Static analysis says a recompute after the synchronous `commit()` should
   read 6/5/5; the runtime read 5/4/5. The exact interleaving is Open item A
   of the issue and must be pinned by the trace below before the fix is
   finalized.
2. `reloadAfterRestore` reloads through the app's own controller; its model
   load runs `LawnchairApp.cleanUpDatabases()`
   (`ModelDbController.java:1114-1163` → `LawnchairApp.kt:145-155`), which
   deletes every `databases/launcher*` file not matching the live `idp.dbFile`
   — deleting the staged import, unlogged — and loads the default workspace.
3. The designed self-restart re-creates the grid DB from the empty path; only
   then does a second identical restore succeed.

A second restore must never be a supported success path.

## Outcome

1. **One restore is authoritative.** After the converter commits grid prefs
   and stages `restored.db`, the active grid/DB selection is applied
   synchronously from the converted values under the still-held
   `BACKUP_RESTORE` lease, so the subsequent `performRestore` (temp
   controller) and the correlated model reload bind the same DB the sanitizer
   wrote. Grid application no longer depends on pref-change listener timing.
2. **`cleanUpDatabases()` is restore-safe.** It never deletes a `launcher*`
   DB while a restore family lease is active or a staged restore exists, and
   it logs every deletion and every blocked deletion.
3. **Open item A is closed.** The recompute path is instrumented so logcat
   shows, for every grid recompute in the restore window, the raw pref values
   read, the values written, and the recompute trigger; the pinned mechanism
   is recorded in the PR and (if it requires a further change beyond the
   deterministic application above) as a follow-up issue.
4. The fix holds for grid mismatch (default 5x4x4/5x4x5 vs backup 6x5x5) and
   grid match, and two consecutive import cycles from identical fresh state
   produce identical outcomes.

## Scope

- `NovaBackupConverter.convertAndRestore` grid-application step.
- `LawnchairApp.cleanUpDatabases()` deletion policy and logging.
- A minimal `InvariantDeviceProfile` bridge for explicit, synchronous grid
  application from known values (upstream file; documented per the bridge
  rule), and recompute trace logging on the same paths.
- `LayoutWriteCoordinator` accessor for "is a restore-family lease active".
- Trace logging in `DeviceProfileOverrides.DBGridInfo(prefs)`.
- Tests: coordinator accessor, `cleanUpDatabases` guard, converter
  grid-application determinism (mismatch + match).

## Non-goals

- Do not weaken restore/migration fail-closed behavior (per #167) or the
  `LayoutWriteCoordinator` lease model.
- The `IconCache` wrong-thread `IllegalStateException` on the
  reload-after-restore path (separate follow-up).
- Content-level import gaps (drawer groups skipped, not-installed packages
  dropped, missing-app widgets dropped).
- Changing backup formats, `sanitizeDB` semantics, or grid migration
  behavior.
- Organizer-after-external-restore work (stays blocked; its required stable
  post-fix state is defined by the #167 assessment).

## Behavior

### Authoritative grid application (converter)

1. The converter keeps writing the backup grid to all three pref stores
   (`DeviceGridState.writeToPrefs` x2, `writeGridToLawnchairPrefs`) — the
   persisted state must remain correct.
2. While still holding the `BACKUP_RESTORE` lease, the converter applies the
   converted grid to the live `InvariantDeviceProfile` singleton
   synchronously (main thread) from the converted values themselves — the
   same values it just committed — using one explicit bridge method. The
   application is the last grid-state mutation before staging and restore;
   any listener-posted recompute that runs earlier in the main queue is
   subsumed by it.
3. `performRestore` (temp `ModelDbController`) and the correlated reload
   (`reloadAfterRestore` → app controller helper creation) then observe
   `idp.dbFile` = converted DB name. `renameRestoredDb` places `restored.db`
   onto that DB and `cleanUpDatabases` cannot delete it.

### Restore-safe cleanup (LawnchairApp)

1. `cleanUpDatabases()` skips (does not delete) any `launcher*` DB file when
   either (a) a restore-family lease (`RESTORE`/`BACKUP_RESTORE`) is
   currently held on the `LayoutWriteCoordinator`, or (b) a staged restore
   file (`restored.db`) exists. Skips are logged at WARN with the file name
   and the blocking condition.
2. Every executed deletion is logged with the deleted file name and the
   surviving DB name (closing the current silent `file.delete()`).
3. The cleanup contract for non-restore loads is unchanged: unmatched
   `launcher*` files are still removed, so no orphan DB accumulation is
   introduced by the guard.

### Recompute trace (Open item A)

- `DBGridInfo(prefs)` logs the three raw pref values it reads.
- The converter logs the columns/rows/hotseat values it commits before the
  pref writes and before the explicit application.
- The recompute path logs each trigger and the resulting `dbFile` transition
  (old → new) so logcat alone can order the restore-window recomputes.

## Scenarios

### S1 — one restore, grid mismatch

Given a fresh install on the default grid (5x4x4 or 5x4x5) and a Nova backup
with a 6x5x5 grid, performing one Nova restore through Settings results,
after the normal restart/reload lifecycle, in the imported workspace being
visible and the active grid DB (`launcher_6_5_5.db`) containing the imported
favorites; logcat contains no `loading default workspace` for the import DB
and no deletion of `launcher_6_5_5.db`.

### S2 — one restore, grid match

Given a current grid equal to the backup grid, one restore binds the
restored DB to the live process with the same determinism as S1.

### S3 — cleanup never deletes a staged restore

When a restore-family lease is held or `restored.db` is staged,
`cleanUpDatabases()` deletes no `launcher*` file and logs the skip. With no
restore in progress, unmatched `launcher*` files are deleted and each
deletion is logged.

### S4 — determinism

Two consecutive import cycles from identical fresh state produce identical
outcomes at the logcat/state level (no pass-1-only divergence).

### S5 — trace observability

During a restore window, logcat alone establishes the order of: pref writes
(values), every grid recompute (raw pref values read, trigger, `dbFile`
old→new), and cleanup decisions (deletion/skip + reason).

## Acceptance

- [ ] S1 passes on the emulator (`nunu_qpr2_api36_1`) with the #167 backup
      (sha256 `fa7210a9…884f5`); one restore, no second restore anywhere in
      the supported path.
- [ ] No `launcher*` DB deletion can occur while a staged restore exists
      (test-proven: S3 at test level, S1/S4 at runtime level).
- [ ] Grid application is deterministic for grid mismatch (S1) and grid
      match (S2).
- [ ] Open item A mechanism pinned from trace logs and recorded in the PR.
- [ ] `plan.md` in this directory implemented as specified; verification
      commands and results recorded in the PR.
