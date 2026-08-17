---
issue: "#58"
status: draft
updated: 2026-08-17
---

# Plan: serialize runtime raw-file restores

Baseline for evidence: main `cfc665c19c`. Spec: `spec.md` in this directory.

## Current evidence (implementation-relevant facts)

- `LayoutWriteCoordinator` (`src/com/android/launcher3/model/LayoutWriteCoordinator.java`)
  is exclusive: one lease at a time; reentrancy requires same kind + token
  (`tryReenter`). Tokenless model work is deferred via `runOrDefer`.
- `RestoreDbTask.performRestore` (`RestoreDbTask.java:202-221`) opens the DB
  via `controller.getDb()` before acquiring `RESTORE`.
- `ModelDbController` has no public close/quiesce seam; only grid-migration
  recovery paths (`failClosedActiveRecovery`, `publishFreshSource`) close and
  reopen helpers internally.
- `LawnchairBackup.restore` (`LawnchairBackup.kt:61-92`): `BACKUP_RESTORE`
  `use` block covers directory delete + file writes only.
- `LawndeckManager.restoreBackup` (`LawndeckManager.kt:72-99`): lease covers
  file copy only; `postRestoreActions` runs `performRestore` + process restart
  outside it.
- `NovaBackupConverter.convertAndRestore` (`NovaBackupConverter.kt:146-195`):
  no lease.
- `LauncherModel.forceReload()` (`LauncherModel.java:311-315`) is the existing
  correlated-ish reload; `LoaderTask.run` defers under a held lease.

## Change modules and order

1. **Coordinator restore-family reentrancy** —
   `LayoutWriteCoordinator.java`
   - Treat `RESTORE`, `BACKUP_RESTORE`, `DECK_FILE_RESTORE` as one reentrancy
     family: `tryReenter` (or a new package-private `tryReenterRestoreFamily`)
     succeeds when the current lease is any restore kind held by the same
     thread. Exclusion against all other kinds is unchanged.
2. **Helper close/reopen seam** — `ModelDbController.java`
   - Add a restore-scoped API (package/launcher-internal visibility) to close
     the active helper (`mOpenHelper`) and drop cached DB references, and to
     construct a fresh helper on next access. Model it on the existing
     `publishFreshSource` close/reopen pattern; do not add a new public
     coordinator type.
3. **`performRestore` lease-first** — `RestoreDbTask.java`
   - Acquire the lease (reenter if a restore-family lease is already held by
     the calling thread, else `acquireBlockingQuietly(RESTORE)`) *before*
     `controller.getDb()`. Keep the existing transaction/sanitize body and
     boolean result surface.
4. **Quiesce/reload helper for restores** — small internal function (e.g. in
   `RestoreDbTask` or `LauncherModel`) that: stops the loader
   (`LauncherModel.stopLoader`), and after sanitization triggers
   `forceReload`. No-ops when `LauncherAppState`/model is absent (baseline
   fallback).
5. **`LawnchairBackup.restore`** — hold `BACKUP_RESTORE` across: quiesce →
   close helper → directory delete + file writes → fresh
   `ModelDbController` + `performRestore` (reentrant) → reload. Prefs/grid
   writes tied to the restore stay inside the lease.
6. **`LawndeckManager.restoreBackup`** — hold `DECK_FILE_RESTORE` across file
   copy → `performRestore` (reentrant) → correlated reload; keep
   `restartLauncher` only for the organizer-unavailable baseline fallback.
7. **`NovaBackupConverter.convertAndRestore`** — wrap staging copy, IDP/prefs
   writes, `restored.db` copy, and `performRestore` in a `BACKUP_RESTORE`
   lease with the same quiesce/close/reopen sequence.

## Tests

- Unit (`tests/src`): coordinator restore-family reentrancy; direct
  `performRestore` lease-before-open ordering; fallback (no app state).
- Instrumentation (`tests/organizer-instrumentation`): lease continuity for
  the three paths (coordinator observer); exact interleaving test dispatching
  `ModelWriter`/provider writes inside the replacement window and asserting
  deferral; helper close/reopen identity; failure injection at replacement /
  sanitize / reload boundaries; restart reconciliation.
- Existing suites must stay green: `RestoreDbTaskTest`,
  `RestoreProfileRemapTest`, `BackupAndRestoreDBSelectionTest`.

## Migration / rollback

- No DB schema change; no data migration. Rollback is code revert.
- Observable caller surfaces (return values, exceptions) are unchanged, so no
  caller migration is required.

## Verification commands

- `./gradlew spotlessCheck`
- `./gradlew assembleLawnWithQuickstepGithubDebug`
- Relevant unit tests plus organizer-instrumentation on the API 36.1 emulator
  (see `docs/engineering/building.md`).

## Risks

- Deadlock if any restore path blocks on the coordinator from the UI thread
  or MODEL_EXECUTOR while holding another lease — mitigated by reentrancy
  rule and by keeping restores on their existing IO dispatchers.
- Deck path interacts with spec 57 retirement; changes here are limited to
  lease scope and reload so retirement is not blocked.
