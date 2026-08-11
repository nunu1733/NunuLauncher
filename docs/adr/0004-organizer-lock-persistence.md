---
status: accepted
---

# Store organizer lock state on each `favorites` row

## Decision

Each Launcher layout database owns organizer lock state in a dedicated
`favorites.organizerLockState` column. It is an `INTEGER NOT NULL DEFAULT 1`
with this closed encoding:

| Value | Meaning |
|---|---|
| `0` | `UNKNOWN`: lock truth must be reviewed; organization is fail-closed. |
| `1` | `UNLOCKED`: the row is known not to be a Locked Placement. |
| `2` | `LOCKED`: the row is a Locked Placement. |

Any other value is corrupt and is handled as `UNKNOWN`. The value is not a
general-purpose flag and is never stored in `Favorites.OPTIONS`.

The Launcher DB is the authority for both the row and its lock state. Snapshot,
revision, exact precondition, layout transaction, recovery manifest, and
post-write verification include the column. No separate lock store participates
in correctness.

The implementation raises the Launcher schema from 32 to 33. This ADR defines
the required migration and rollback behavior; Issue #14 owns the schema,
capture/application/recovery bridge, and tests. [Issue #38](https://github.com/nunu1733/NunuLauncher/issues/38)
owns user-facing lock authoring and review of `UNKNOWN` rows.

## Context and evidence

`CONTEXT.md` defines **Locked Placement**. `DESIGN.md` §5 requires lock
preservation, and `docs/product/item-preservation-policy.md` D-006 defines its
observable behavior. The accepted Issue #13 contract requires lock truth in the
revision, exact preconditions, recovery intent, and fail-closed paths.

Evidence was inspected on 2026-08-10 at upstream Lawnchair commit
`505dbc40e6154c05158b5d0271c45f6a885a411b` and NunuLauncher commit
`228cc0c5b672bddaba5d60a4d3916c3a65008826`:

- `LauncherSettings.java:368-389` defines the ordered `favorites` columns.
- `DatabaseHelper.java:165-292` switches on `oldVersion`; a 32→33 migration
  therefore belongs in `case 32`, followed by a `case 33` success return.
- `DatabaseHelper.java:289-292` wipes the layout after a caught/broken upgrade.
  The organizer migration must instead propagate failure so the framework
  transaction rolls back and the existing DB remains unchanged.
- `res/raw/downgrade_schema.json` removes columns with
  rename/create/copy/drop. `DbDowngradeHelper.java:56-72` has no per-statement
  fallback, so direct `DROP COLUMN` is not portable across supported SQLite
  versions.
- `LauncherDbUtils.copyTable()` uses the current `getColumns()` list, and
  `GridBackupTable` uses positional `SELECT *`; neither safely copies between
  schema 32 and 33 without prior normalization.
- `GridSizeMigrationUtil.java:187-218,259-289` can retain, remove, or recreate
  rows and assigns new `_id` values to copied rows. D-006 intentionally does not
  promise lock preservation through that separate operation.
- `RestoreDbTask.java:262-278,368-375` deletes rows for unrestored profiles and
  remaps `profileId` on surviving rows.
- `LawnchairBackup.kt:60-85,134-141` archives only the active Launcher DB and,
  on restore, deletes the DB directory before restoring that DB.
- `res/xml/backupscheme.xml:4-9` includes named Launcher DB files in Android
  backup. A column travels with an included DB file.
- `WorkspaceItemInfo.java`, `FolderInfo.java`, and
  `LauncherAppWidgetInfo.java` give `Favorites.OPTIONS` type-dependent meanings.
  `FolderInfo.FLAG_ITEMS_SORTED` is unrelated to organizer locking.

## Alternatives

### Same-row dedicated column (selected)

This is the only option that gives lock truth the same row identity,
transaction, backup unit, revision, and recovery write-set as placement. The
tri-state encoding distinguishes a safe default for newly created rows from a
legacy or migration state whose former lock truth cannot be proven.

### Same-DB side table

Rejected. References to `favorites._id` require explicit remapping whenever
grid migration recreates rows. It adds joins, orphan/duplicate states, and
migration logic without improving the transaction or backup boundary.

### Separate organizer database

Rejected. It cannot share the Launcher DB transaction and would require a
cross-DB reconciliation protocol for every row recreation, grid migration,
restore, and recovery. ADR-0003's separate DB is appropriate for durable
recovery records, not authoritative per-row lock truth.

### Preferences or DataStore

Rejected. They cannot be atomically checked or updated with Launcher rows;
serialized `_id` references become stale; their backup lifecycle differs from
the layout DB.

### A `Favorites.OPTIONS` bit

Rejected. The bitfield has different meanings by item type, has no project-wide
reserved bit, and can conflict with future upstream flags. It also obscures the
cross-type lock concept.

## Identity and effective-lock rules

Every persisted actionable `favorites` row has its own state. A parent lock
also protects the structural placements that make that container coherent.

| Row/container | Stored and effective behavior |
|---|---|
| Application, shortcut, deep shortcut | Row state controls its captured placement. |
| Widget/custom widget | `LOCKED` protects cell, span, and occupied region. |
| Dock row | `LOCKED` protects Dock rank and slot. |
| Folder parent | `LOCKED` protects the parent cell and every child's captured container/rank. |
| Folder child | Its own `LOCKED` protects its container/rank even when the parent is unlocked. A parent lock takes precedence over a child's `UNLOCKED`. |
| App-pair parent | `LOCKED` protects parent placement, membership, and split encoding. |
| App-pair member | Its own `LOCKED` protects membership/split placement; a parent lock protects both members regardless of their stored state. |
| Unsupported/non-actionable row | Preflight rejects according to D-006; a lock value does not make it actionable. |
| Personal/work/private profile | Same encoding; `profileId` remains distinct and is part of revision/preconditions. |
| Temporarily unavailable profile with retained row | State is read and preserved opaquely; availability is not lock state. |
| Profile absent from restore | `RestoreDbTask` may delete its rows; their locks are deleted with them. A later organizer run captures only the restored layout and never claims the absent rows were preserved. |

If any represented row is `UNKNOWN`, corrupt, unreadable, or missing the column
at the application boundary, capture/apply/recover fails closed. It is never
coerced to `UNLOCKED`.

## Lifecycle, migration, and rollback

| Event | Required behavior |
|---|---|
| Fresh schema-33 DB | Column default is `UNLOCKED`; every normal new row is therefore explicit and usable. |
| Explicit lock/unlock | The owning UI/domain command writes `LOCKED` or `UNLOCKED` through the Launcher model-writer transaction. Issue #14 provides the persistence operation; Issue #38 provides user intent/UI. |
| Item deletion | Deleting the row deletes its lock state; no orphan is possible. |
| 32→33 upgrade | In `onUpgrade case 32`, add the column with default `UNLOCKED`, then set every pre-existing row to `UNKNOWN` in the same framework transaction; `case 33` returns success. Existing rows require explicit review before organizer use. |
| Upgrade failure | Throw; do not enter the baseline `createEmptyDB()` fallback. The upgrade transaction rolls back and the schema-32 DB/layout remains unchanged. Organizer remains unavailable. |
| 33→32 downgrade | Use the established rename/create/explicit-copy/drop table-rebuild recipe. Layout rows survive, the column is intentionally absent, and lock truth is not claimed by the older app. |
| Re-upgrade after downgrade | All pre-existing rows become `UNKNOWN`; organizer remains fail-closed until review. Lost lock truth is never interpreted as unlocked. |
| Open an inactive schema-32 grid DB | Upgrade that DB to 33 before it becomes an organizer source. Its legacy rows become `UNKNOWN`. Do not call schema-33 `copyTable` against an unnormalized source. |
| Switch between existing schema-33 grid DBs | Each DB owns its own row states. Switching changes the active authoritative layout; a fresh revision/capture is required. |
| Grid migration | Treat every row in the resulting target layout as `UNKNOWN`, whether retained, copied, recreated, or structurally changed. Commit that marking with migration or leave the pre-migration DB active on failure. A review is required before organizer use. |
| Grid migration failure | Target changes roll back per the migration transaction; source layout remains authoritative. No partial lock claim is accepted. |
| Lawnchair ZIP backup | The active DB and its states are archived. Inactive grid DBs are not archived. |
| Lawnchair ZIP restore | The archived active DB is restored; other DB files are deleted by current Lawnchair behavior. Schema-33 states survive. A schema-32 archive upgrades to `UNKNOWN`. |
| Android backup/restore | States survive for included schema-33 DB rows. Rows for profiles Android cannot restore may be deleted by `RestoreDbTask`; surviving profile IDs may be remapped without changing the state. |
| Recovery capture/apply | Pre-state, post intent, reviewed current state, digests, revision, and exact row preconditions include the state. |
| Recovery to an exact checkpoint | Restore the checkpoint value per row. Missing/corrupt/unreadable lock resources return `NotRestorable(LOCK_STATE_UNAVAILABLE)` before Launcher mutation. |
| DB corruption/read error | Typed lock-unavailable rejection when classification is possible; otherwise the existing DB failure path applies. No organizer write begins. |
| Unsupported future encoding/version | Treat as lock state unavailable/incompatible; never coerce. |

The downgrade table rebuild must explicitly list all schema-32 columns in their
baseline order. No direct `ALTER TABLE ... DROP COLUMN` is permitted in the
downgrade JSON. Tests must execute the full 33→32→33 cycle and prove layout rows
survive while the final states are `UNKNOWN`.

## Fail-closed boundary

`UNKNOWN` is a valid persisted state but not a valid organization input. Before
checkpoint creation, and again inside the Launcher write transaction, Issue #14
must read every represented row and reject when:

- the column is absent after the required database upgrade;
- a value is `UNKNOWN`, outside the closed encoding, NULL, or unreadable;
- the recovery record lacks the state for any accounted row; or
- current state differs from the revision or exact precondition.

Apply returns `Rejected(LOCK_STATE_UNAVAILABLE)`. Explicit recovery returns
`NotRestorable(LOCK_STATE_UNAVAILABLE)`. Both leave the Launcher DB unchanged.
Issue #13 owns these public results and is corrected with this ADR.

## Implementation requirements and ownership

Issue #14 must include, in its later accepted plan:

- schema 33 column definition and the non-wiping `case 32` migration;
- portable downgrade table rebuild and 32→33→32→33 tests;
- normalization of every active/source grid DB before schema-dependent copies;
- grid-migration `UNKNOWN` marking and failure rollback tests;
- snapshot/revision/precondition/recovery-manifest encoding;
- typed apply/recover fail-closed results;
- model writer support for explicit state changes, without adding UI; and
- active/inactive DB, ZIP/Android restore, profile-remap/deletion, folder,
  widget, Dock, and app-pair integration tests.

Issue #38 owns user controls, review/confirmation of
`UNKNOWN` rows, and accessibility. Until that flow resolves all represented
rows, Issue #14 must demonstrate the fail-closed result rather than bypass it.

No production schema or migration is implemented by this research Issue.

## Consequences

- Lock truth has one transactional authority and no orphan side records.
- Existing and migrated layouts are conservatively blocked until reviewed;
  availability is traded for preservation of user intent.
- Grid migration and old-backup restore do not silently claim locks survived.
- The change touches upstream schema/version and migration paths and therefore
  needs focused upgrade, downgrade, restore, grid, and recovery evidence.
- Future Lawnchair schema-version conflicts must merge both migrations and use
  a new final version; they must not reuse the same number for different shapes.

## Change history

- 2026-08-10: Accepted dedicated tri-state `favorites` column; defined
  non-wiping migration, portable downgrade, fail-closed legacy/recovery
  handling, and grid/backup/profile boundaries for Issue #23.
