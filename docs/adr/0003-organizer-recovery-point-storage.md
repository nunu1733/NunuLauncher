---
status: accepted
---

# Store organizer recovery points in a separate private database

## Decision

Organizer recovery points are stored in a versioned, app-private database that
is separate from every Launcher layout database. The recovery database is not
an export backup: it is omitted from Lawnchair ZIP backup and Android full
backup, and it is used only by the Layout Application module.

A checkpoint transaction commits and validates the recovery record before the
separate layout transaction starts. Cross-database atomic commit is neither
claimed nor required. Persisted pre-state, intended post-state, and lifecycle
metadata let restart reconciliation determine whether the layout transaction
did not commit, committed exactly, or left an unrecognized state.

## Context

`CONTEXT.md` defines a recovery point as a verified state that an organization
run can restore in-app. `DESIGN.md` §7 distinguishes it from export backup.
The choice is costly to reverse, its failure behavior is not apparent from the
baseline code, and multiple viable storage locations exist; it therefore meets
the ADR threshold in `AGENTS.md`.

The baseline facts are:

- `LawnchairBackup.getFiles()` includes the active Launcher database file and
  `create()` copies that complete file into the ZIP. Individual tables cannot
  be excluded from that export.
- `res/xml/backupscheme.xml` explicitly includes each Launcher database file.
  A table inside one of those files is therefore included in Android full
  backup as well.
- `GridBackupTable` demonstrates a table-shaped copy, but it is not a
  self-versioned recovery store and its restore path replaces the Favorites
  table. It is evidence for row compatibility only, not the selected protocol.
- Deck's raw database-file copy has no transactional or integrity contract and
  remains rejected by ADR-0002.

All source observations are fixed to Lawnchair baseline
`505dbc40e6154c05158b5d0271c45f6a885a411b`.

## Alternatives

| Alternative | Advantages | Disqualifying cost or risk |
|---|---|---|
| Dedicated tables in the Launcher DB | One SQLite engine; simple row reads | The whole DB file is exported/backed up, so the recovery data cannot satisfy the required backup separation. Layout DB corruption also destroys the checkpoint. |
| **Separate private recovery DB (selected)** | Transactional checkpoint; independent corruption domain; naturally excluded by the baseline file allowlists; independent schema/version lifecycle | No cross-DB transaction. The protocol must persist intent and reconcile pre/post/neither after a crash. |
| Versioned private snapshot file | Independent and naturally excluded from backup | Correct atomic replacement, locking, indexing, retention, and partial-write recovery would recreate database behavior. |
| Raw Launcher DB copy | Simple file operation | Rejected: inconsistent WAL/journal capture, no row-level integrity or preconditions, and Deck already demonstrates unsafe failure handling. |

## Rationale

The required ordering is checkpoint-then-layout, not one distributed commit:

1. Commit the complete recovery record in the recovery DB.
2. Read it back and validate its version, row count, and integrity digest.
3. Persist the canonical intended post-state and mark the record ready to apply.
4. Execute the Launcher layout transaction independently.
5. Reconcile lifecycle metadata from the authoritative Launcher state.

If the layout transaction rolls back, the checkpoint deliberately survives. If
the process dies around commit, the recovery record contains both the pre-state
and intended post-state needed to classify the current Launcher revision. This
turns the absence of cross-database commit into an explicit recovery protocol
rather than an unhandled gap.

The separate DB is also the only compared option that is both transactional and
actually excluded by the baseline backup allowlists without sanitizing or
rewriting the Launcher DB export path.

## Consequences

- The recovery DB has its own schema/version and migration tests. It does not
  change `DatabaseHelper.SCHEMA_VERSION` or add tables to a Launcher DB.
- Lawnchair ZIP backup remains unchanged because `getFiles()` does not include
  the recovery DB. Android full backup remains unchanged because
  `backupscheme.xml` allowlists only the named Launcher DB files. Tests must
  prove both exclusions.
- The recovery record is a resource manifest, not an assumption that all state
  lives in `favorites`. [ADR-0004](./0004-organizer-lock-persistence.md) places
  lock truth on each `favorites` row and requires it in capture and restore.
- Lawnchair schema 33 has no persistent workspace-screen table. The manifest therefore
  includes only desktop pages referenced by persistent rows; model-only empty pages are
  transient. Profile inventory/availability and device capabilities are encoded
  deterministically as externally owned, Preserve-only context. Any context mismatch is
  rejected before `RESTORING`; recovery never mutates that context.
- A recovery DB format newer than the running app is read-only and
  non-restorable. An older compatible format is migrated in the recovery DB or
  rejected without touching the Launcher layout.
- Downgrade/uninstall may discard recovery points but must not modify the
  Launcher layout. Export backup remains the long-lived recovery mechanism.
- Recovery reads a validated snapshot from the recovery DB, then applies an
  explicit preconditioned write-set in one Launcher DB transaction. It never
  copies a DB file or performs unconditional delete-and-reinsert.
