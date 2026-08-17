---
issue: "#58"
status: draft
requirements: []
updated: 2026-08-17
---

# Serialize runtime raw-file restores and reopen Launcher DB helpers

## Problem

Three runtime restore paths replace Launcher database files as raw bytes while
other writers and open SQLite helpers remain active, and none of them keeps one
writer lease across the complete restore lifecycle:

- `LawnchairBackup.restore` holds `BACKUP_RESTORE` only while deleting the DB
  directory and rewriting files, then releases it before
  `RestoreDbTask.performRestore` takes a separate later `RESTORE` lease.
- `LawndeckManager.restoreBackup` holds `DECK_FILE_RESTORE` only for the
  DB/journal file copy, then releases it before sanitization and falls back to a
  full process restart as the only "reopen" mechanism.
- `NovaBackupConverter.convertAndRestore` stages and copies `restored.db` with
  no `LayoutWriteCoordinator` lease at all.
- `RestoreDbTask.performRestore` calls `controller.getDb()` (which may create,
  rename, or copy DB files through `createDatabaseHelper`) *before* acquiring
  `RESTORE`.
- No path closes the active SQLite helper before raw replacement or reopens a
  fresh helper afterward as part of the same protected sequence.

This leaves a concrete interleaving where an ordinary `ModelWriter` or
`LauncherProvider` write enters between the raw-file lease and restore
sanitization, and where an open helper keeps file handles on deleted/replaced
files.

## Outcome

Each runtime raw-file restore path executes as one quiesced writer operation
using the existing `LayoutWriteCoordinator` ownership model:

```text
quiesce model writers -> close active helper -> raw file replacement
    -> reopen fresh helper -> restore sanitization (RESTORE semantics)
    -> rebind/reload -> release
```

The whole sequence runs under a single coordinator lease held by one thread.
No parallel public coordinator or seam is introduced; existing lease kinds and
reentrancy rules are extended, not duplicated.

## Scope

- The three enumerated runtime restore paths: Lawnchair ZIP restore, Deck
  restore, Nova converter restore.
- Lease acquisition ordering inside `RestoreDbTask.performRestore`.
- A helper close/reopen seam on `ModelDbController` for restore use.
- Model quiesce/reload correlation for restore completion.
- Failure-injection, restart, and exact-interleaving test coverage.
- Baseline behavior preservation when the organizer application is unavailable.

## Non-goals

- Retiring the Deck runtime (owned by spec 57) or changing Deck preference
  semantics; only its restore file-swap lifecycle is serialized.
- Changing backup formats, sanitizeDB/profile-remap semantics, or grid
  migration behavior.
- The organizer Layout Application seam (spec 13); restores do not create
  organizer recovery points.
- Backup/restore *transport* (Android full backup agent flows) beyond the
  runtime restore entry points listed above.

## Behavior

### Serialized restore lifecycle

1. The restore path acquires exactly one coordinator lease
   (`BACKUP_RESTORE`, `DECK_FILE_RESTORE`, or `RESTORE`) on the worker thread
   before any file or DB mutation. Blocking acquisition preserves baseline
   behavior for baseline callers.
2. Under the lease, the running loader/model writers are quiesced
   (stop loader; deferred tokenless model tasks cannot start because the lease
   is held).
3. The active Launcher DB helper(s) are closed through the
   `ModelDbController` seam before any raw file deletion or copy.
4. Raw file replacement (DB directory delete, file copies, prefs writes tied
   to the restore) happens with no open helper on those files.
5. A fresh helper is constructed and `performRestore` sanitization runs inside
   the same lease. `performRestore` must not open the DB before its lease is
   effective; when the calling thread already holds a restore-kind lease,
   `performRestore` reenters it instead of blocking (which would self-deadlock).
6. On completion the model is rebound/reloaded (correlated reload, not a
  process restart) and the lease is released. On failure the sequence fails
  closed: no partial raw swap is left silently active; the DB is reopened and
  the failure is reported as before (boolean/exception surface unchanged).

### Path mapping

| Path | Lease | Change |
|---|---|---|
| `LawnchairBackup.restore` | `BACKUP_RESTORE` | Lease spans directory delete, file writes, helper reopen, and `performRestore`. |
| `LawndeckManager.restoreBackup` | `DECK_FILE_RESTORE` | Lease spans DB/journal copy, sanitization, and reload; the unconditional `restartLauncher` fallback is replaced by correlated reload where the organizer application is available, preserving baseline restart when it is not. |
| `NovaBackupConverter.convertAndRestore` | `BACKUP_RESTORE` | Staging copy, prefs/IDP writes, `restored.db` copy, and `performRestore` move under the lease. |

### Coordinator extension

- `OwnerKind` restore kinds (`RESTORE`, `BACKUP_RESTORE`, `DECK_FILE_RESTORE`)
  are treated as one restore family for reentrancy: a thread already holding
  any restore-family lease may enter `performRestore` without releasing it.
  Ordinary `MODEL_WRITER`/`GRID_MIGRATION`/`ORGANIZER` leases still exclude
  restores completely.
- No new public coordinator type is added beyond this family reentrancy rule
  and the helper close/reopen seam.

### Baseline fallback

When the organizer application is unavailable (no `LauncherAppState`/model),
the sequence still runs: the quiesce and correlated-reload steps become no-ops,
raw replacement + helper reopen + sanitization still execute under the lease,
and observable results (return values, thrown exceptions, restart behavior)
match baseline.

## Scenario matrix

| ID | Scenario | Required observable result |
|---|---|---|
| SR-01 | Lawnchair ZIP restore, no contention | One continuous `BACKUP_RESTORE` lease from first file mutation to reload; sanitized DB; no stale helper. |
| SR-02 | Deck restore | One continuous `DECK_FILE_RESTORE` lease; sanitization and reload inside it; no unconditional process restart when organizer is available. |
| SR-03 | Nova restore | Same as SR-01 with `BACKUP_RESTORE`; prefs/IDP writes included. |
| SR-04 | Ordinary `ModelWriter` task dispatched during the replacement window | It is deferred (does not run) until the restore lease is released; exact interleaving test. |
| SR-05 | Binder `LauncherProvider` write during the replacement window | Deferred via the operation future; no DB access before release. |
| SR-06 | Helper state before replacement | Active helper is closed before file deletion/copy; no open handle on replaced files. |
| SR-07 | Failure during raw replacement or sanitization | Fails closed: lease released, DB reopened or reported as failure; no partial swap treated as success; observable failure surface unchanged. |
| SR-08 | Process death mid-restore | After restart, `RestoreDbTask.restoreIfNeeded` reconciliation produces a consistent DB; no stale-helper or half-swapped state passes verification. |
| SR-09 | Restore called with lease already held on same thread | Reentrant entry; no self-deadlock. |
| SR-10 | Concurrent restore + organizer apply attempt | Mutual exclusion via coordinator; one returns busy/blocks per existing semantics. |
| SR-11 | Organizer application unavailable | Sequence completes with baseline observable behavior. |
| SR-12 | `performRestore` invoked directly (no outer lease) | Acquires `RESTORE` itself before opening the DB; baseline callers unchanged. |

## Acceptance criteria

| AC | Criterion | Evidence |
|---|---|---|
| AC-1 | All three paths hold one restore-family lease across file replacement through reload. | Instrumentation test asserting lease continuity (observer/counter on the coordinator seam). |
| AC-2 | No DB open precedes lease acquisition in `performRestore`. | Code-level test: `getDb`/`createDatabaseHelper` not called before lease (fault-injection or ordering spy). |
| AC-3 | Exact interleaving: ordinary model/provider writes cannot enter the replacement window. | Test dispatching a `ModelWriter`/provider write during the window and asserting deferral. |
| AC-4 | Active helper closed before replacement and fresh helper reopened under the lease. | Test observing helper identity/close across the sequence. |
| AC-5 | Failure injection at replacement/sanitize/reload boundaries leaves no silent partial restore. | Fault-injection tests incl. process-restart reconciliation. |
| AC-6 | Baseline behavior preserved when organizer application is unavailable. | Unit/instrumentation test on the fallback path. |
| AC-7 | `spotlessCheck` and the relevant unit/instrumentation test suites pass on CI. | CI run on the PR head. |

## Baseline evidence

Fixed to baseline `505dbc40e6154c05158b5d0271c45f6a885a411b` plus current main
(`cfc665c19c`):

| Evidence | Source |
|---|---|
| `BACKUP_RESTORE` released before `performRestore`. | `LawnchairBackup.kt:83-91` |
| `DECK_FILE_RESTORE` released before sanitization; restart as reopen. | `LawndeckManager.kt:72-99` |
| Nova restore leaseless. | `NovaBackupConverter.kt:146-195` |
| `getDb()` before `RESTORE` lease. | `RestoreDbTask.java:202-221` |
| Lease exclusivity/reentrancy model. | `LayoutWriteCoordinator.java` |
| Writer lease surfaces (ModelWriter/Provider/LoaderTask). | `ModelDbController.java`, `LauncherProvider.java`, `LoaderTask.java` |

## Change history

- 2026-08-17: Drafted for Issue #58.
