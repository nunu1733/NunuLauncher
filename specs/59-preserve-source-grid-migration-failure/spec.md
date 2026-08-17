---
issue: "#59"
status: accepted
requirements:
  - NFR-001
  - NFR-002
  - NFR-012
updated: 2026-08-16
---

# Preserve source layout when grid migration fails

## Problem

A grid migration can rewrite valid schema-33 source lock values, expose a
candidate target before the serialized operation begins, or create an empty
database after failure. A target transaction can also commit before helper and
preference finalization fail. Issue [#59](https://github.com/nunu1733/NunuLauncher/issues/59)
must preserve source authority and recover a committed, unfinalized target
without relying on file copying or best-effort cleanup.

The governing lifecycle decision remains
[ADR-0004](../../docs/adr/0004-organizer-lock-persistence.md). The historical
integration plan is [spec 13's plan](../13-safe-layout-application/plan.md).

## Outcome

Migration either finalizes one serialized target with every resulting row
`UNKNOWN`, or returns `false` while the original source helper, database, lock
states, and preferences remain authoritative. A committed target that has not
finalized is recovered from durable state in that target database before it can
be used or migrated again. A schema-32 source upgrades locally to schema 33
with `UNKNOWN` values. Existing schema-33 `LOCKED` and `UNLOCKED` source values
are never rewritten merely because migration was attempted.

## Scope

- Normalize schema-32 active or inactive sources through `DatabaseHelper`.
- Hold the existing `GRID_MIGRATION` boundary before target setup through
  publication, source close, and destination preference persistence.
- Put backup, journal, target copy and placement, target-wide `UNKNOWN`,
  `favorites_tmp` cleanup, and pending-finalization state in one initial target
  transaction.
- Reconcile a durable unfinalized target before new migration or target use.
- Route active-database, target-open, and migration-entry recovery through one
  controller reconciliation dispatcher.
- Compensate partial destination-preference success and reopen the source after
  a delegated close that completes then throws.
- Commit every destination preference editor synchronously, aggregate every
  editor's Boolean result, and require a readback that matches the requested
  grid state.
- Store a versioned canonical `favorites` SHA-256 digest with the same-target
  backup, validate it after restore and before metadata cleanup, and reject an
  unsupported digest version or noncanonical digest.

## Non-goals

- Change tri-state encoding, organizer review, placement policy, backup policy,
  or UI behavior.
- Add a public coordinator, adapter, or migration entry point.
- Permit empty-database fallback or best-effort recovery reporting.

## Behavior scenarios

### Schema-32 source normalization

Given an active or inactive schema-32 migration source with layout rows
When `DatabaseHelper` opens it as a source
Then its framework upgrade adds the schema-33 column and writes `UNKNOWN` to
every pre-existing row in the same upgrade transaction
And it either finishes schema 33 with those values or leaves schema-32 rows
unchanged on failure.

### Initial target transaction

Given source normalization and candidate target setup have succeeded
When either the growth fast path or general placement path migrates the target
Then one initial target transaction records a same-target backup snapshot and
a transaction-local `TARGET_OLD` journal intent before target mutation
And that transaction includes copy, placement writes, target-wide `UNKNOWN`,
`favorites_tmp` cleanup, and durable journal transition to
`MIGRATED_PENDING_FINALIZATION`
And it commits all of those effects or commits none of them.

### Finalization and authority

Given the initial target transaction committed
When finalization succeeds
Then the controller publishes the target helper, delegates source close, writes
destination preferences, and records `FINALIZED` inside the existing lease
And the target becomes authoritative only after that sequence completes
And a failure while recording `FINALIZED` follows the same compensation and
restoration path as another finalization failure.
And a `FINALIZED` target is validated before use, including target identity,
destination preference identity, backup digest, all-`UNKNOWN` rows, and absence
of `favorites_tmp`
And backup and journal metadata are deleted together in one target transaction
only after that validation succeeds.

### Pending finalization recovery

Given a target journal in `MIGRATED_PENDING_FINALIZATION`
When a process restart or retry reaches that target
Then the source remains authoritative and the controller reconciles finalization
before target use or another migration begins
And it either finishes publication, delegated close, and destination preferences
before recording `FINALIZED`, or records `RESTORE_PENDING` and restores the
same-target backup.

### Reconciliation dispatcher and unknown preferences

Given an active database journal, a target journal, or a migration-entry target
with durable recovery state
When the controller admits that database
Then one reconciliation dispatcher classifies the journal phase before target
use or another migration begins
And `MIGRATED_PENDING_FINALIZATION` completes only when destination preferences
and the pending target both validate
And source, destination, or unknown current preferences otherwise progress
through source-authority restoration, with unknown preferences never selecting
the target as authoritative.

### Restoration failure

Given a finalization failure after the initial transaction committed
When target restoration is attempted
Then the controller records `RESTORE_PENDING` before restoration
And it restores source preferences before target restoration, validates the
restored `favorites` table against the versioned canonical backup digest, and
deletes neither backup nor journal before that validation succeeds
And it records `RESTORE_FAILED` and exposes recovery-pending status if
preference restoration, target restoration, or restore validation fails
And it returns `false`, never reports a completed rollback, and retries recovery
before new migration or target use.

### Partial finalization failure

Given destination preference persistence partially succeeds then throws, or a
delegated source close completes then throws
When the controller handles that failure
Then it compensates destination preferences back to source values, reopens the
source helper after the delegated close, retains source authority, and begins
target restoration.

### Source truth survives

Given a schema-33 source with `LOCKED` and `UNLOCKED` rows
When migration succeeds or any injected migration failure occurs
Then its rows and values remain unchanged
And each row in a successful target is `UNKNOWN`.

### Inactive and downgrade lifecycle

Given an inactive schema-32 grid and distinct active schema-33 source
When the inactive grid is selected for migration
Then only the inactive grid is normalized to `UNKNOWN`
And `32 -> 33 -> 32 -> 33` preserves layout rows and ends with `UNKNOWN` values.

## Data and recovery state

The source helper, source database, and source preferences are authoritative
until `FINALIZED`. Source and target handles must refer to different files.

| Phase | Durable visibility | Authority and required retry |
|---|---|---|
| `TARGET_OLD` | Transaction-local only | Initial transaction is open. No recovery observer can see it. Rollback leaves no migration changes or journal state. |
| `MIGRATED_PENDING_FINALIZATION` | Durable with same-target backup | Source is authoritative. The controller must finalize or begin restoration before target use or another migration. |
| `RESTORE_PENDING` | Durable | Source is authoritative. Restore is required before target use or another migration. |
| `RESTORE_FAILED` | Durable and observable as recovery pending | Source is authoritative. The failed restore is not success. Retry it before target use or another migration. |
| `FINALIZED` | Durable | Target is authoritative after publication, delegated source close, and destination preferences succeed. |

Journal and backup data are recovery metadata in the target database. After
`FINALIZED`, deleting them is recovery-metadata cleanup, not migration temporary
cleanup. A completed restore may delete them only after the pre-migration target
state passes versioned canonical-digest validation. A finalized target may delete
backup and journal only atomically after finalized-target validation. Unknown
current preferences restore source authority before target restoration.
`favorites_tmp` is migration temporary state and is cleaned in the initial
transaction.

## Acceptance criteria

- [ ] AC-59-01: A schema-32 active or inactive migration source upgrades every
  pre-existing lock state to `UNKNOWN` in one framework transaction, or leaves
  its schema-32 rows unchanged on upgrade failure.
- [ ] AC-59-02: A schema-33 source retains exact `LOCKED` and `UNLOCKED` values
  after successful migration and every injected migration failure.
- [ ] AC-59-03: After source restore or open, the existing `GRID_MIGRATION`
  lease begins before target setup and covers target transaction closure,
  publication, source close, and destination preference persistence.
- [ ] AC-59-04: Both paths perform same-target backup snapshot, journal creation
  with transaction-local `TARGET_OLD`, copy, placement writes, target-wide
  `UNKNOWN`, `favorites_tmp` cleanup, and durable `MIGRATED_PENDING_FINALIZATION`
  in one initial target transaction. Every successful target row is `UNKNOWN`.
  Deleting backup and journal after finalization is recovery-metadata cleanup,
  not temporary cleanup.
- [ ] AC-59-05: Target publication, delegated source close, and destination
  preference persistence occur only after successful initial transaction closure.
- [ ] AC-59-06: Any setup, copy, placement, marking, transaction-close,
  helper-lifecycle, or synchronous preference failure returns `false`, preserves
  source authority and values, and never calls `createEmptyDB()`. After an
  initial commit, recovery records `RESTORE_PENDING`, restores the same-target
  backup with source-preference progression and canonical-digest validation,
  records `RESTORE_FAILED` on restoration or validation failure, and retries
  before target use or new migration. It never swallows or reports failed
  restoration as completed rollback.
- [ ] AC-59-07: Selecting an inactive schema-32 grid normalizes only that
  database and does not alter a distinct schema-33 source.
- [ ] AC-59-08: `32 -> 33 -> 32 -> 33` preserves layout rows and ends with all
  pre-existing rows `UNKNOWN`.

## Test oracle

All Issue #59 migration tests enter `ModelDbController.tryMigrateDB` and use one
production-used internal `GridMigrationRuntime`. They must not call
`migrateGridIfNeededForTest`, invoke a manual migration transaction, use a
static pre-hook, or introduce a second coordinator. A shared fixture helper
creates source, pre-existing target, journal, and preference states.

| AC | Evidence |
|---|---|
| AC-59-01, AC-59-08 | `DatabaseHelperSchema33Test` real SQLite schema-upgrade and full lifecycle fixtures. |
| AC-59-02 | Controller-entry fast and general success tests plus controller-entry injected-copy failure test. |
| AC-59-03, AC-59-05 | Controller-entry ordering and reconciliation tests observe lease admission, one dispatcher, target transaction closure, publication, delegated close, synchronous all-editor preference aggregation plus readback, `FINALIZED` validation, and atomic metadata cleanup. |
| AC-59-04 | Controller-entry fast and general tests observe one initial transaction containing backup, journal, migration writes, `UNKNOWN`, and `favorites_tmp` cleanup. |
| AC-59-06 | Controller-entry restart and retry tests cover `MIGRATED_PENDING_FINALIZATION`, `RESTORE_PENDING`, `RESTORE_FAILED`, source, destination, and unknown preference progression, canonical-digest restore validation, partial preference compensation, and delegated close followed by throw. |
| AC-59-07 | `InactiveGridDbNormalizationTest` multi-database fixture. |

## Open questions

None. ADR-0004 and Issue #59 define the required source-preserving recovery
behavior.

## Change history

- 2026-08-16: Reconciled the accepted contract with the final implementation:
  one controller reconciliation dispatcher, synchronous all-editor preference
  aggregation with readback, versioned canonical digest validation, unknown
  preference restoration, finalized-target validation, and atomic recovery
  metadata deletion.
