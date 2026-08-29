---
issue: "#168"
status: proposed
updated: 2026-08-29
---

# Plan: one Nova restore authoritative + restore-safe cleanUpDatabases

## Changed modules and paths

| Module | Path | Change |
|---|---|---|
| InvariantDeviceProfile | `src/com/android/launcher3/InvariantDeviceProfile.java` | Minimal bridge (Issue #168): public `applyGridInfo(Context, DBGridInfo)` that mutates the singleton through the existing private `initGrid` path and runs the same listener/reload notification as `onConfigChanged`, but from explicit values instead of pref reads. Trace logging (trigger + `dbFile` old→new) in `onPreferencesChanged`/`onConfigChanged`. |
| DeviceProfileOverrides | `lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt` | Trace log in `DBGridInfo(prefs)` (three raw `get()` values, per issue Open item A). |
| NovaBackupConverter | `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | After `writeGridToLawnchairPrefs` commit: log committed values; replace the raw `idp.dbFile =` field assignment with a main-thread, lease-held `applyGridInfo(...)` using the converted values (authoritative application). |
| LawnchairApp | `lawnchair/src/app/lawnchair/LawnchairApp.kt` | `cleanUpDatabases()`: skip deletion when a restore-family lease is active or `restored.db` is staged (WARN log with reason); log every executed deletion. |
| LayoutWriteCoordinator | `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | Accessor `hasActiveRestoreFamilyLease()` (synchronized read of the current holder, `isRestoreFamily(kind)`). |

Bridge justification (upstream files, per AGENTS.md): the grid-application
mutation point and the recompute notification live inside upstream
`InvariantDeviceProfile`; the fork's `launcher_{rows}_{cols}_{hotseat}.db`
naming is invisible upstream. Both bridge spots are single methods marked
`// Issue #168:`.

## Seam and invariants

- The converter applies the grid from the converted values (not from pref
  reads) on the main thread while holding `BACKUP_RESTORE`; no new lease kind
  or coordinator seam is introduced beyond the read-only accessor.
- The reload path is unchanged: `reloadAfterRestore` → app controller helper
  creation → `renameRestoredDb` + helper binding on `idp.dbFile`.
- `cleanUpDatabases` non-restore behavior is unchanged (no orphan DB
  accumulation); the guard only blocks while a restore is in flight.

## Open item A handling

The trace (spec S5) is implemented together with the fix. The deterministic
application (boundary 1) is primary and does not depend on which recompute
interleaving the trace reveals; the `cleanUpDatabases` guard (boundary 2) is
required independently by the issue's acceptance. If the trace reveals an
additional root cause that can revert grid state after the authoritative
application (e.g., persistent stale pref cache), it is fixed in this PR with
a regression test; otherwise the pinned mechanism is documented in the PR.

## Migration / rollback

No schema, pref-format, or backup-format change. Rollback = revert the PR;
the cleanup guard is fail-safe (skips deletion), never destructive.

## Tests

1. `LayoutWriteCoordinatorTest` (instrumentation suite): accessor returns
   true while any restore-family lease is held, false for other kinds and
   when free.
2. `cleanUpDatabases` guard test (instrumentation, app process): with a
   staged `restored.db`, no `launcher*` sibling file is deleted and the skip
   is logged; without it, unmatched files are deleted and deletions are
   logged.
3. Converter grid-application test (instrumentation): after the application
   step, live `idp.dbFile` equals the converted DB name for mismatched and
   matched grids; a listener-posted recompute preceding it cannot change the
   final binding.
4. Existing suites keep passing: `RestoreDbTaskTest`,
   `RestoreLeaseSerializationTest`, `BackupAndRestoreDBSelectionTest`.

## Verification

- `./gradlew spotlessCheck`
- `./gradlew assembleLawnWithQuickstepGithubDebug`
- Targeted unit/instrumentation test runs for the suites above.
- Emulator `nunu_qpr2_api36_1`: fresh install → one Nova restore
  (`/tmp/nova167/stageA/backup.novabackup`) → restart → imported workspace
  visible, active DB holds the import, no default-workspace load, no
  `launcher_6_5_5.db` deletion; repeat cycle for S4. Physical Pixel 9a if
  reachable; otherwise recorded as pending in the PR.
- Risk label `risk: layout-data` (DB file lifecycle on the restore path):
  high-risk PR requirements apply — `final-status` CI gate plus an
  independent audit record `docs/assessment/pr-<n>-…md` before merge.
