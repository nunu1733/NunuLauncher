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
   persisted state must remain correct. The lawnchair grid prefs are written
   through the typed setters under `batchEdit` so the pref caches hold the
   committed values at write time; `DeviceGridState.writeToPrefs(context,
   commit = true)` is issued last as a synchronous `commit()` on the same
   SharedPreferences file — the durability barrier that guarantees the grid
   values survive the restore's self-restart despite `batchEdit`'s async
   `apply()` (review follow-up).
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

1. `cleanUpDatabases()` runs its deletion loop inside
   `LayoutWriteCoordinator.runDbCleanupExclusively` — a short `MODEL_WRITER`
   section (same-thread reentry preserved for admitted writer loads) that is
   mutually exclusive with restore-family lease holders: a restore either
   already holds a lease (the cleanup call is skipped outright) or cannot
   start until the deletion completed. This closes the check→delete race a
   plain snapshot check would leave (review follow-up).
2. Within the section, a staged restore file (`restored.db`) still blocks
   deletion (crashed-restore leftovers), logged at WARN with the file name.
3. Every executed deletion is logged with the deleted file name and the
   surviving DB name (closing the current silent `file.delete()`). A cleanup
   skipped because another lease is held is also logged.
4. The cleanup contract for ordinary loads is unchanged: unmatched
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

- [x] S1 passes on the emulator (`nunu_qpr2_api36_1`) with the #167 backup
      (sha256 `fa7210a9…884f5`); one restore, no second restore anywhere in
      the supported path. (2026-08-29, build `9bf4af9`: one restore →
      `launcher_6_5_5.db` holds the import (12 rows, identical to the #167
      pass-2 success state on the same emulator), zero
      `loading default workspace` lines, import DB never deleted.)
- [x] S1 passes on the physical Pixel 9a (API 37, `56231JEBF08674`) with the
      same backup and build `11d4074`: one restore → active DB
      `launcher_6_5_5.db` holds the import (125 rows / 5 hotseat — identical
      to the #167 pass-2 success state on this device), prefs durable at
      rows=6/columns=5/hotseat=5 after the self-restart, zero
      `loading default workspace` lines, import DB never deleted, imported
      workspace visible.
- [x] No `launcher*` DB deletion can occur while a staged restore exists
      (test-proven: S3 at test level, S1/S4 at runtime level).
- [x] Grid application is deterministic for grid mismatch (S1, S4 cycle 1)
      and grid match (S2 via `applyGridInfo` seam test; every post-restore
      recompute converged `launcher_6_5_5.db -> launcher_6_5_5.db`).
- [x] Open item A mechanism pinned from trace logs and recorded in the PR.
- [x] `plan.md` in this directory implemented as specified; verification
      commands and results recorded in the PR.

## Open item A — pinned mechanism (trace result)

Direct trace on the failing path (2026-08-29, pre-fix build, restore window):

```text
.766 NovaBackupConverter: Committing converted grid to prefs: rows=6 columns=5 hotseat=5
     (raw SharedPreferences commit() returns; rows/columns pref caches NOT invalidated)
.849 IDP applyGridInfo-equivalent binding: launcher_5_4_4.db -> launcher_6_5_5.db
.876 IDP recompute requested from a preferences change   (hotseatColumns listener,
     posted by SharedPreferencesImpl.notifyListeners AFTER commit() returned)
.876/.877 Grid recompute from prefs: rows=5 columns=4 hotseat=5   ← STALE rows/cols
.961 IDP recompute: dbFile launcher_6_5_5.db -> launcher_5_4_5.db  ← revert
```

The hotseat `reloadGrid` listener fires from
`SharedPreferencesImpl.notifyListeners`, which runs after `commit()` returns;
the posted `onConfigChanged` then read `workspaceRows/Columns` through the
`BasePreferenceManager` pref caches, which a raw `sp.edit()...commit()` does
not refresh — reproducing exactly the mixed 5/4/5 signature from the #167
pass-1 evidence (fresh hotseat, stale rows/columns) and reverting the live
`idp.dbFile` onto the old grid. The fix removes both halves of the mechanism:
the converter writes grid prefs through the typed setters under `batchEdit`
(caches fresh at write time, so every recompute converges to the converted
grid) and applies the converted values to the IDP synchronously under the
lease (`applyGridInfo`), making the binding independent of listener timing.
