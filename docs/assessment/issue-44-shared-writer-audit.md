# Issue #44: Shared-writer serialization audit

> Status: corrective action required
> Audit date: 2026-08-14
> Scope baseline: local `main` at `090e3a491053b441ae1f467a566e75fdfd0b17dc` (PR #47 merge)
> Upstream/application baseline: Lawnchair v15.0.0-beta3.0, `505dbc40e6154c05158b5d0271c45f6a885a411b`

## Question and method

This is the bounded read-only audit requested by Issue #44. It checks whether the
Issue #14 `LayoutWriteCoordinator` and correlated reload path cover the runtime
writers that can mutate `favorites`, replace or copy Launcher DB files, restore a
profile, or migrate a grid. It does not introduce a second coordinator, change a
public seam, or change production behavior.

The audit used:

- [Issue #14](https://github.com/nunu1733/NunuLauncher/issues/14), its comments,
  the accepted [spec at the audit commit](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/specs/13-safe-layout-application/spec.md)
  and [plan at the audit commit](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/specs/13-safe-layout-application/plan.md),
  plus [ADR-0003](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/docs/adr/0003-organizer-recovery-point-storage.md)
  and [ADR-0004](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/docs/adr/0004-organizer-lock-persistence.md).
- [PR #47](https://github.com/nunu1733/NunuLauncher/pull/47), whose merged source
  is fixed here to commit `090e3a491053b441ae1f467a566e75fdfd0b17dc`.
- Fixed source anchors for the coordinator and executor paths:
  [`LayoutWriteCoordinator.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/LayoutWriteCoordinator.java),
  [`ModelWriter.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelWriter.java),
  [`LauncherProvider.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/LauncherProvider.java),
  [`LauncherModel.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/LauncherModel.java),
  [`LoaderTask.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/LoaderTask.java),
  and [`OrganizerModelReloadAdapter.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java).
- Fixed source anchors for the raw restore and migration findings:
  [`LawnchairBackup.kt`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt),
  [`LawndeckManager.kt`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt),
  [`NovaBackupConverter.kt`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt),
  [`RestoreDbTask.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/provider/RestoreDbTask.java),
  [`ModelDbController.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelDbController.java),
  and [`GridSizeMigrationUtil.java`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/GridSizeMigrationUtil.java).
- A source inventory of `favorites` SQL, `SQLiteTransaction`, DB-file copy/delete,
  restore, grid migration, and model reload call sites.
- The focused organizer unit test suite and repository contract checks.
- A minimal SQLite reproduction of the source normalization SQL in
  `GridSizeMigrationUtil.ensureOrganizerLockColumn`.

## Runtime writer inventory

| Runtime path | Admission/lease observed | Audit result |
|---|---|---|
| Organizer apply/recovery through `LauncherLayoutAdapter` | Outer `ORGANIZER` lease; controller transaction re-enters with the exact token; correlated reload carries the token | Covered for the Issue #14 path |
| `ModelWriter` item updates/deletes | `runOrDefer` gates tokenless model work while an organizer lease is held; controller obtains a per-operation or transaction lease | Covered for ordinary SQL writes; no end-to-end test proves every writer call site |
| `LauncherProvider` Binder writes | Tokenless operation future is deferred while an organizer lease is held, then runs on `MODEL_EXECUTOR` | Covered for the provider path; no test exercises Binder + reload interleaving |
| `LoaderTask`/`LauncherModel` reload | Exact correlated token gets scoped capability; tokenless loader work is deferred; completion checks request identity | Mechanism is present; no full stale-loader/restart test exists |
| `RestoreDbTask.performRestore` | RESTORE lease surrounds the SQLite transaction | Incomplete: `controller.getDb()` is called before the lease, and raw DB rename/copy can also occur before the lease |
| `GridSizeMigrationUtil` | GRID_MIGRATION lease surrounds the migration helper method | Incomplete: target helper creation and `mOpenHelper` replacement happen before this lease; source normalization is destructive before migration success |
| `LawnchairBackup.restore` | BACKUP_RESTORE lease covers directory deletion and ZIP writes | Defective: active helpers are not closed/reopened and the lease ends before `performRestore` acquires its separate RESTORE lease |
| `LawndeckManager.restoreBackup` | DECK_FILE_RESTORE lease covers DB/journal copy | Defective: active helpers are not closed/reopened and restore sanitization starts after the lease ends |
| `NovaBackupConverter.convertAndRestore` | No `LayoutWriteCoordinator` lease around the runtime `restored.db` copy or restore call | Defective: user-facing runtime import is an uncoordinated raw DB writer |
| `LawnchairApp.renameRestoredDb`/`migrateDbName` | Called from `ModelDbController.createDatabaseHelper(false)` before a controller mutation lease | Incomplete for runtime restore; bootstrap-only use is not enforced by a lifecycle guard |
| `loadDefaultFavoritesIfNecessary`/`AutoInstallsLayout` | Direct DB cleanup and initial layout writes from `LoaderTask` | Bootstrap writes are not independently leased. They are expected before organizer readiness, but the same method is reachable on later loader runs and no source-scan/lifecycle assertion proves that invariant |
| `DatabaseHelper` create/upgrade/open migrations | SQLite helper lifecycle / framework transactions | Acceptable as bootstrap lifecycle work, but not a substitute for runtime writer coverage |
| `LauncherBackupAgent.onRestoreFile` and old-grid cleanup | Android backup/restore lifecycle or grid-profile cleanup | Must remain explicitly pre-organizer lifecycle work; no runtime coordinator coverage was found |
| `GridBackupTable`, preview DB helpers, and test handlers | Preview/test-only DB operations | Not active Launcher runtime writers; retain as excluded paths in the inventory |

## Confirmed corrective findings

### C-1. Runtime raw-file restores are not one quiesced writer operation

This finding is **source-proven; stale-handle/lost-update damage was not reproduced
on an Android runtime**. The source-level lease gap is sufficient to reject the
Issue #14 safety invariant, but this document does not claim that a data-loss
trace has already been observed.

There are three concrete user-facing/runtime paths with a gap between raw file
replacement and restore sanitization:

1. [`LawnchairBackup.kt:81-91`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt#L81-L91) acquires
   `BACKUP_RESTORE`, deletes/replaces the live DB directory, releases the lease,
   then constructs a controller and calls `RestoreDbTask.performRestore`.
2. [`LawndeckManager.kt:72-98`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt#L72-L98) acquires
   `DECK_FILE_RESTORE` for DB/journal copying, releases it, then starts restore
   sanitization and restarts Launcher.
3. [`NovaBackupConverter.kt:184-190`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt#L184-L190) copies a
   staged DB to `restored.db` without acquiring any coordinator lease, then calls
   `RestoreDbTask.performRestore`. The path is user-triggered by
   `RestoreNovaBackupViewModel`, not a startup-only import.

The common continuation is also unsafe: `RestoreDbTask.performRestore` obtains
[`controller.getDb()`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/provider/RestoreDbTask.java#L202-L210)
before acquiring its RESTORE lease. [`ModelDbController` performs
`renameRestoredDb`/`migrateDbName` during helper creation`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelDbController.java#L120-L140),
and those raw file operations are not covered by the RESTORE lease.

A concrete unsupported interleaving is:

1. Launcher has an open `ModelDbController`/SQLite helper and the model is ready.
2. A backup, Deck, or Nova restore replaces the DB file/directory while the
   existing helper is still open (Nova does not even acquire the lease).
3. The raw-file lease ends. A queued `ModelWriter` or provider operation can now
   acquire the ordinary writer lease and use the old/open helper before
   `performRestore` acquires RESTORE.
4. Restore sanitization then runs against whichever helper/file identity was
   reconstructed, without a close/reopen and model quiescence boundary.

This violates the Issue #14 requirement that all raw-file restore work be
serialized with the writer and that the active helper be closed/reopened before
rebind. The source check below reproduces the lease gap. No emulator or
instrumentation test in this audit reproduced a stale SQLite handle, lost update,
or observed data-loss trace; those are the unverified runtime consequences that
Issue #58 must test explicitly.

### C-2. Grid migration can destroy source lock state before reporting failure

`GridSizeMigrationUtil.migrateGridUnderLease` calls
[`ensureOrganizerLockColumn(source)`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/GridSizeMigrationUtil.java#L131-L140).
That helper
always executes:

```sql
UPDATE favorites SET organizerLockState = 0
```

at [lines 192-198](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/GridSizeMigrationUtil.java#L180-L199), even when the source already has schema 33 and contains valid
`LOCKED` or `UNLOCKED` state. The source update occurs before the copy and before
the [migration transaction](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/GridSizeMigrationUtil.java#L152-L177). If copy, migration, or commit fails,
the active source DB has already lost its lock state. This contradicts ADR-0004's
fail-closed/source-authoritative rule and the Issue #14 plan's claim that the
source remains authoritative on failure.

The production call path compounds this: [`ModelDbController.migrateGridIfNeeded`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelDbController.java#L359-L393)
creates and assigns the target helper before `GridSizeMigrationUtil` acquires
`GRID_MIGRATION`. On failure, [`tryMigrateDB` falls back to `createEmptyDB`](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelDbController.java#L328-L345),
rather than retaining the source helper and preserving the pre-migration state.

Minimal reproduction of the exact SQL behavior (schema-33 source with one locked
and one unlocked row):

```text
before: [(1, 2), (2, 1)]
run ensureOrganizerLockColumn SQL
after:  [(1, 0), (2, 0)]
```

The reproduction was executed with Python's SQLite engine on 2026-08-14 and
passed the assertion that both values become UNKNOWN before a simulated later
failure. The existing Android test only covers adding the column to a schema-32
fixture; it does not protect existing schema-33 source values or failure
rollback/source retention.

The complete executable reproduction is repeated in Verification below. It uses
an in-memory schema-33 `favorites` table, inserts `LOCKED` and `UNLOCKED` rows,
runs the exact source normalization SQL in autocommit mode, raises a simulated
failure at the next migration boundary, and asserts that the active source has
already changed to UNKNOWN.

## Executor, reload, FIFO, transaction, and restart review

The focused coordinator test proves only lease exclusivity, same-token lease
re-entry, exact-token capability installation, and tokenless deferral. The Issue
#44 topics were audited separately:

The fixed line evidence for this section is
[coordinator admission](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/LayoutWriteCoordinator.java#L190-L224),
[the Binder future path](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/LayoutWriteCoordinator.java#L240-L274),
[FIFO release](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/LayoutWriteCoordinator.java#L282-L303),
[ModelWriter admission](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelWriter.java#L551-L560),
[Provider's two waits](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/LauncherProvider.java#L153-L190),
[reload supersession](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/LauncherModel.java#L438-L490),
[exact loader admission](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/LoaderTask.java#L248-L264),
[reload wait outcomes](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java#L42-L93),
[transaction lease selection](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/src/com/android/launcher3/model/ModelDbController.java#L274-L309),
and [restart reconciliation](https://github.com/nunu1733/NunuLauncher/blob/090e3a491053b441ae1f467a566e75fdfd0b17dc/lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt#L47-L75).

| Topic | Evidence and classification | Remaining support/follow-up |
|---|---|---|
| `MODEL_EXECUTOR` admission | **Confirmed for two named paths:** `ModelWriter.ModelTask.executeOnModelThread` gates before scheduling, and `LoaderTask.run` defers before `runInternal`. `ModelDbController.newTransaction()` can still call blocking `acquireBlockingQuietly(MODEL_WRITER)` on the model executor for a non-organizer holder. | **Not supported end-to-end.** There is no source-scan or integration assertion that every direct loader/restore/migration path avoids blocking `MODEL_EXECUTOR`; tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60). |
| Synchronous Binder / two-future path | **Confirmed source sequence:** `LauncherProvider` creates a deferred operation future; the supplier submits to `MODEL_EXECUTOR` and waits with `.get()`; the Binder caller then waits on the operation future. The normal deferred callback runs after the coordinator clears `current`. | **No deadlock reproduced.** A static hazard remains if an organizer lease is released on `MODEL_EXECUTOR`: `release()` runs deferred callbacks inline, the supplier submits to the same executor, and `.get()` can self-wait. No production caller was found that proves this release-thread condition; tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60). |
| Superseding reload | **Confirmed source sequence:** `stopLoader()` cancels the old request; `forceReloadForOrganizer()` installs the new request; completion checks object identity; the reload adapter reports `COMPLETED`, `SUPERSEDED`, `FAILED`, or `TIMEOUT`; a stopped loader returns before work. | **Not supported by a test.** There is no A-then-B supersession test proving the old completion cannot complete B or that each request gets one terminal signal; tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60). |
| Deferred FIFO / exactly-once | **FIFO is source-confirmed for non-throwing callbacks:** `ArrayDeque` append order is moved to a local queue, cleared once, then invoked once per entry. `runOrDeferWithOperationFuture` catches supplier failures. | **Exactly-once is not fully supported:** the `runOrDefer` callback wrapper does not catch a thrown runnable, so one failing callback can abort later FIFO callbacks; no multi-entry/exception test exists. Tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60), not a reproduced production failure. |
| Nested/reentrant transactions | **Lease mechanism confirmed:** `tryReenter` requires the same thread, owner kind, and token; organizer `newTransaction(token)` uses the scoped capability; `SQLiteTransaction` closes the DB transaction before releasing its lease. | **Actual nested DB transaction behavior is unverified.** Existing tests exercise lease re-entry only, not nested `SQLiteTransaction` objects or failure during inner close; tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60). |
| Process restart | **Protocol/reopened-store behavior is covered:** `RestartReconciler` enumerates non-final records, reacquires an organizer lease, reloads, verifies, and advances lifecycle; unit tests cover repeated reconciliation and failure states; instrumentation relaunches an Activity and calls startup reconciliation. | **Real process death is not covered here.** The evidence does not kill/restart the process while a live helper, deferred FIFO, or raw-file restore is active. Recovery-store persistence is not proof of writer/helper recovery; tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60). |

Overall classification: coordinator behavior is **partially confirmed**, no
executor deadlock or stale-handle data-loss result was **reproduced**, and the
unverified conditions are explicit follow-ups. C-1 remains corrective because the
runtime lease/lifecycle invariant is disproven by source, not because an observed
data-loss trace is being asserted.

## Baseline-disabled/readiness matrix

| State | Ordinary writer | Organizer operation | Restore/migration implication |
|---|---|---|---|
| Organizer module not ready/failed | Baseline model/provider paths still use their existing coordinator admission and DB leases | Application module rejects the operation | Does not make uncoordinated Nova/raw-file paths safe |
| Organizer lease held | Tokenless model/provider work is deferred; exact correlated loader may proceed | Organizer transaction re-enters the lease | Only covers paths that actually enter the coordinator |
| Runtime backup/Deck restore | Raw replacement lease may block ordinary SQL writers while held | No single lease covers replacement, helper reopen, sanitization, and rebind | C-1 remains reproducible from source |
| Runtime grid migration | GRID lease starts after target helper setup and source normalization | Organizer can observe an incomplete migration boundary | C-2 remains reproducible from source |
| Startup schema/bootstrap | SQLite helper lifecycle performs create/upgrade work | Organizer readiness waits for model load/reconciliation | Acceptable only if the bootstrap-only invariant is kept explicit and tested |

## Test and evidence gaps

The plan describes a source-scan allowlist and broad writer coverage, but the
repository contains neither. `LayoutWriteCoordinatorTest` has two tests focused
on exclusivity and token deferral; it does not exercise ModelWriter, Provider,
grid migration, restore, backup, Deck, or Nova paths. The related instrumentation
tests also have narrower coverage than their names imply:

- `DatabaseHelperSchema33Test.freshRowDefaultsToUnlocked` inserts no row and
  asserts no value.
- `InactiveGridDbNormalizationTest` only starts from a schema-32-like table.
- `GridMigrationUnknownMarkingTest` calls the marking primitive inside a manually
  successful transaction; it does not run migration failure or source retention.
- `BackupExclusionTest` checks only an allowlist string, not a produced archive or
  active-helper close/reopen.
- `RestoreProfileRemapTest` directly calls a profile helper on a synthetic DB,
  not `performRestore` with the lease and file lifecycle.
- No test covers `NovaBackupConverter`'s runtime DB replacement.

## Conclusion

**CORRECTIVE ACTION REQUIRED.** The shared ordinary SQL writer and correlated
reload mechanism is present for the paths it wraps, but Issue #14's “every runtime
writer” claim is not satisfied. C-1 (uncoordinated/split raw-file restore with
open helpers) and C-2 (destructive source normalization and target-helper swap on
failed grid migration) are concrete unsupported source paths that can violate
layout-data safety. High-risk follow-up work #38/#52/#55 should remain blocked
until these findings are triaged and the required recovery/rollback tests pass.

This audit intentionally makes no production-code change. Required remediation is
tracked in [#58](https://github.com/nunu1733/NunuLauncher/issues/58) for runtime
raw-file restore serialization/helper reopening and [#59](https://github.com/nunu1733/NunuLauncher/issues/59)
for source-preserving grid migration failure handling. Executor, reload, FIFO,
nested-transaction, process-restart, and complete writer-inventory follow-ups are
tracked in [#60](https://github.com/nunu1733/NunuLauncher/issues/60). These are
linked from Issue #44 as well.

## Verification

Passed:

```text
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
python3 - <<'PY'
from pathlib import Path
nova = Path('lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt').read_text()
backup = Path('lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt').read_text()
deck = Path('lawnchair/src/app/lawnchair/deck/LawndeckManager.kt').read_text()
assert 'stagedDbFile.copyTo(restoredDbFile, overwrite = true)' in nova
assert 'LayoutWriteCoordinator' not in nova
assert backup.index('acquireBlockingQuietly') < backup.index('RestoreDbTask.performRestore')
assert deck.index('acquireBlockingQuietly') < deck.index('postRestoreActions()')
print('C-1 source proof passed; runtime stale-handle/lost-update effect not reproduced.')
PY
python3 - <<'PY'
import sqlite3

db = sqlite3.connect(':memory:', isolation_level=None)
db.execute('''
    CREATE TABLE favorites (
        _id INTEGER PRIMARY KEY,
        title TEXT,
        organizerLockState INTEGER NOT NULL DEFAULT 0
    )
''')
db.executemany(
    'INSERT INTO favorites (_id, title, organizerLockState) VALUES (?, ?, ?)',
    [(1, 'locked', 2), (2, 'unlocked', 1)],
)
columns = {row[1] for row in db.execute('PRAGMA table_info(favorites)')}
assert 'organizerLockState' in columns
before = db.execute(
    'SELECT _id, organizerLockState FROM favorites ORDER BY _id'
).fetchall()
try:
    # Exact SQL from GridSizeMigrationUtil.ensureOrganizerLockColumn().
    db.execute('UPDATE favorites SET organizerLockState = 0')
    raise RuntimeError('simulated copy/migration failure after source normalization')
except RuntimeError:
    pass
after = db.execute(
    'SELECT _id, organizerLockState FROM favorites ORDER BY _id'
).fetchall()
assert before == [(1, 2), (2, 1)]
assert after == [(1, 0), (2, 0)]
print('C-2 reproduction passed: source lock state changed before simulated migration failure.')
PY
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon --no-parallel \
  testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.application.*'
git diff --check
```

The repository contract reported OK; its test suite reported 11 tests passed; the
C-1 source proof passed; the C-2 SQLite reproduction passed; and the focused Gradle
task completed successfully. The C-1 command is deliberately a source proof, not
a runtime stale-handle/lost-update reproduction. No production source file was
modified by this audit.
