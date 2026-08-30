---
issue: "#174"
status: draft
requirements: []
risk:
  - layout-data
  - migration
updated: 2026-08-30
---

# Recovery record storage that never exceeds the SQLite CursorWindow

## Problem

Evidence: [docs/assessment/issue-171-organizer-after-external-restore.md](../../docs/assessment/issue-171-organizer-after-external-restore.md)
(rev 3, PR #173) and [Issue #174](https://github.com/nunu1733/NunuLauncher/issues/174).

`RecoveryStore.checkpoint()` writes the capture manifest into the recovery
record row twice (`pre_manifest` + `intended_manifest`, identical while
`CREATING`). At real-device workspace scale — the 125-row Nova import with
icon-bearing rows reproduced on the #171 device — each manifest is
~1.12 MB, so one committed record row is ~2.25 MB and exceeds Android
SQLite's 2 MB `CursorWindow`. The protocol's mandatory close/reopen read-back
(`SELECT * … WHERE point_id = ?`) then throws
`android.database.sqlite.SQLiteBlobTooBigException`, which `checkpoint()`
collapses into `CheckpointResult.CreateFailed` → A4
`PRE_WRITE_REJECTED.CHECKPOINT_CREATE_FAILED` before any Launcher mutation.

The committed unreadable row then poisons the whole store:

1. The inspection fence finishes `OUTCOME_UNCERTAIN` → `DIRTY`; every later
   apply in that process is rejected at A4 with `RECOVERY_STORE_UNAVAILABLE`.
2. After restart, reconciliation's
   `SELECT * FROM recovery_points ORDER BY created_at_ms ASC` throws the same
   exception while reading the oversized row, so the entire non-final record
   list is unbuildable: healthy records are not reconciled and the huge
   `CREATING` row cannot be advanced, pruned, or tombstoned by any protocol
   path (every path reads the row first).

The checkpoint protocol and ADR-0003 ordering are correct; the defect is the
record's physical representation versus SQLite cursor limits. Emulator-scale
workspaces (12–13 rows, ~0.21 MB records) hide the failure, which is why it
surfaces only on real devices.

## Outcome

A checkpoint succeeds deterministically at any real-device workspace scale,
including the observed ~2.25 MB record: the committed record survives
close/reopen and passes the full read-back validation, later applies are not
degraded, and restart reconciliation reconciles every readable record even
when another record is unreadable. A recovery store that already contains an
unreadable row — including the exact store state the #171 device still
exhibits — is recovered by a specified migration/quarantine path without
touching the Launcher layout. The fail-closed protocol, ADR-0003 ordering,
and the #89 inspection boundary are unchanged.

| Decision | Normative consequence |
|---|---|
| **Selected strategy: chunked manifest side table (recovery DB format 3)** | Manifest blobs (`pre`, `intended`, `reviewed` slots) leave the `recovery_points` row and are stored as fixed-size chunks in a per-point side table. Every physical row — record row and chunk row — stays far below the 2 MB `CursorWindow`. |
| Logical recovery record and `RecoveryStorePort` contracts unchanged | The payload-checksum algorithm over the logical record is unchanged; a migrated record keeps its valid checksum. All protocol, lifecycle, retention, fence, and diagnostics semantics are unchanged. |
| No full-row `SELECT *` on any recovery read path | Every read uses small-column projections plus per-record chunk assembly. One unreadable record degrades that record only. |
| v2 → v3 migration is server-side SQL only | The migration chunks existing blobs with in-SQLite `substr()`; no Android `Cursor` is created, so an already-poisoned v2 store migrates successfully. |
| Per-record reconciliation degradation with quarantine | A record that cannot be read or reassembled is quarantined (tombstone + delete by ID, without reading the row) inside one reconciliation transaction; all other records still reconcile. |
| Write bound is a corruption bound, not a product cap | Manifests up to a generous engineering bound (well above any real-device scale) are accepted; exceeding it fails closed before any layout mutation. No deliberate size-cap policy is introduced. |

## Scope

- Recovery DB format 3: `recovery_points` row without manifest blob columns,
  plus a `recovery_manifest_chunks` side table; chunk write/assembly in the
  existing store transaction paths.
- Elimination of every full-row `SELECT *` over `recovery_points`: record
  read-back, lifecycle updates, retention listing, reconciliation listing,
  and the #89 inspection-snapshot projection.
- The v2 → v3 pre-open migration (pattern of the existing v1
  empty-store migration in `RecoveryStore.migrateEmptyLegacyStore()`),
  including migration of a poisoned v2 store.
- The reconciliation quarantine path and its typed tombstone reason.
- Regression and migration test coverage at the application-contract level
  (real-SQLite instrumentation on the emulator) and codec-level coverage.

## Non-goals

- Diagnostics reason surfacing for input-unavailable and fence-dirty
  follow-ons — owned by #172. No new public diagnostics event is introduced;
  quarantine surfaces through the existing reconciliation event with a
  classification value.
- No change to: apply/recovery protocol semantics, `LifecycleTransitions`,
  retention policy and durations, the #89 inspection snapshot fence
  semantics, `RunMutex` ownership, ADR-0003 ordering, or the
  checkpoint-then-layout invariant.
- No Launcher database, backup-format, or backup-allowlist change (the
  recovery DB file name is unchanged and stays excluded per ADR-0003).
- No deliberate record-size cap/policy decision; no manifest content or
  capture changes.

## Domain language

Implementation-level only; no `CONTEXT.md` change.

## Behavior scenarios

### Oversized checkpoint succeeds at real-device scale

Given a capture manifest of ~1.12 MB (125 icon-bearing rows, matching the
#171 device) so the logical record is ≥2.25 MB,

When `checkpoint()` runs through the production writer on a real SQLite
store,

Then it returns `Ready` deterministically: the `CREATING` insert (record row
plus both slot chunk sets) commits in one transaction, the forced
close/reopen read-back assembles and validates the full record, the
`READY` advance and its read-back succeed, and the inspection snapshot
publishes with the fence `VALID`,

And no `CreateFailed`/`CHECKPOINT_CREATE_FAILED` occurs at this scale, and a
subsequent apply reaches A6/A8 unchanged.

### Oversized record does not poison the store

Given a store holding an oversized ≥2.25 MB record plus a small healthy
record,

When restart reconciliation runs,

Then both records are reconciled per the existing protocol (the oversized
record classified from its digests; `RESTART_RECONCILED` emitted for each),

And no protocol path aborts because of the oversized record.

### Migration of the poisoned v2 store

Given a format-2 recovery DB containing a >2 MB unreadable `CREATING` row
and a healthy `VERIFIED` row (the #171 device state),

When the app opens the store on a format-3 build,

Then the pre-open migration converts the store server-side (no `Cursor` is
created), sets `user_version = 3`, and preserves every record's logical bytes
and payload-checksum validity,

And startup reconciliation then classifies the migrated `CREATING` row
(`PRE_STATE` → `READY` + prune) and reconciles the healthy `VERIFIED` row.

### Quarantine of an unreadable record

Given a record whose chunk rows are missing or unreadable so the record
cannot be reassembled,

When restart reconciliation processes the store,

Then that record alone is quarantined — one transaction writes a typed
quarantine tombstone and deletes the row by point ID without reading the row
body —

And every other record is still reconciled, and the inspection snapshot
publishes with the quarantined record's `checksumValid = false` projection
instead of failing wholesale.

### Manifest write bound fails closed

Given a capture manifest larger than the engineering bound,

When `checkpoint()` runs,

Then it fails closed before any layout mutation with the existing
`CreateFailed` mapping,

And the bound is fixed, far above real-device scale, and never used to
reject the ≥2.25 MB scale required by this issue.

### Downgrade stays fail-closed

Given a format-3 store and an app build whose `RecoveryDbSchema.FORMAT_VERSION`
is 2,

When the version gate probes the store,

Then the store is `INCOMPATIBLE_VERSION` (read-only, non-restorable per
ADR-0003) and the Launcher layout is not touched.

### Migration failure leaves the store fail-closed

Given a v2 store whose migration transaction fails (fault injection),

When availability is evaluated,

Then the store remains at v2, is reported incompatible, no partial schema
change is visible, and the Launcher layout is unchanged; the next start may
retry the migration.

## Data and state

- **Format 3 record row** (`recovery_points`, rebuilt): all v2 columns except
  `pre_manifest`, `intended_manifest`, `reviewed_manifest`. `point_id`
  remains the primary key; `payload_checksum` remains a 32-byte SHA-256 over
  the logical record in the existing `CHECKSUM_COLUMNS` order, with manifest
  bytes now sourced from the chunk table at verification time.
- **Chunk side table** (`recovery_manifest_chunks`): `(point_id, slot,
  chunk_index, chunk)`, primary key `(point_id, slot, chunk_index)`;
  slots `PRE`/`INTENDED`/`REVIEWED`; fixed chunk size (plan: 512 KiB). Slot
  integrity is established by reassembling chunks and verifying the slot's
  manifest digest (`pre_digest` / `intended_digest` / `reviewed_digest`).
  A zero-length manifest has zero chunks.
- **Writes stay transactional**: the `CREATING` insert writes the record row
  and both slot chunk sets in the checkpoint transaction; `markApplying`
  rewrites the `INTENDED` chunk set and `markRestoring` writes the
  `REVIEWED` chunk set inside their existing single transactions
  (ADR-0003: each lifecycle transition is one transaction).
- **New tombstone reason** `QUARANTINED` joins the existing typed reasons;
  quarantine tombstones expire through the existing retention.
- **Retention, reconciliation, inspection snapshot, recovery context,
  digests, and counts** are unchanged.
- **Backup/restore**: unchanged — the recovery DB keeps its file name and
  its exclusion from Lawnchair ZIP backup and `backupscheme.xml` (ADR-0003).
- **Launcher layout**: no code path introduced by this spec writes the
  Launcher databases.

## Permissions, privacy, and security

None. No new permission, network, or export surface. The chunk table lives
in the same app-private, no-backup recovery DB; its contents are the same
manifest bytes already stored today.

## Accessibility and localization

Not applicable (storage-layer change; no UI).

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| CW-AC-01 | `spec.md` and `plan.md` select the chunked-manifest strategy, keep the fail-closed protocol and ADR-0003 ordering unchanged, and define the format-3 schema, migration, read-path normalization, and quarantine path. |
| CW-AC-02 | A regression test at the application-contract level (real-SQLite test DB on the emulator) creates a checkpoint whose logical record is ≥ the ~2.25 MB observed on the #171 device. Pre-fix, the test reproduces the `SQLiteBlobTooBigException` → `CHECKPOINT_CREATE_FAILED` failure (recorded as PR evidence); post-fix, `checkpoint()` returns `Ready` deterministically at that scale: the record is committed, survives close/reopen, and passes the full read-back validation. |
| CW-AC-03 | No recovery read path executes a full-row `SELECT *` over `recovery_points`. Record read-back, lifecycle updates, retention, reconciliation listing, and the inspection-snapshot projection read bounded projections and per-record chunk sets, so no single row can exceed the 2 MB `CursorWindow` through a protocol path. Size accounting covers every blob slot (`pre`, `intended`, `reviewed`) and the chunk bound is enforced at write time. |
| CW-AC-04 | No store poisoning: a store containing an unreadable record still reconciles every healthy record (per-record degradation), and the unreadable record is quarantined — typed tombstone plus delete by point ID without reading the row — by a specified, tested protocol path inside one reconciliation transaction (failure injection, rollback per the test conventions). The inspection snapshot publication degrades the unreadable record to `checksumValid = false` instead of failing. |
| CW-AC-05 | The v2 → v3 migration runs server-side SQL only (no `Cursor`/`CursorWindow` involvement), is transactional (failure leaves a fail-closed v2 store), preserves logical record bytes and payload-checksum validity, and recovers the #171-device-class poisoned store: after migration, the oversized `CREATING` row is classified and cleaned per the existing `CREATING` reconciliation path and the healthy record is reconciled. |
| CW-AC-06 | Downgrade (format-3 store, format-2-era build) and backup/restore behavior are unchanged and fail-closed; the Launcher layout is untouched on every failure path. |
| CW-AC-07 | `RecoveryStorePort` and all protocol/public result types are unchanged; existing protocol, fence, retention, and inspection tests pass without semantic changes (Fake-based JVM suites need no behavioral updates beyond compile-level adaptation). |
| CW-AC-08 | Device-class verification is mandatory and strict: the #171 device sequence (organize → exactly one Nova restore → organize) passes A4 and reaches `APPLY_VERIFIED` on a real-device-scale workspace. No "leaves a diagnosable, recoverable state" alternative is accepted. |
| CW-AC-09 | High-risk evidence process applies: `risk: layout-data` / `risk: migration` labels, `CI / final-status` success on the verification head including the focused instrumentation jobs, and an independent audit record in `docs/assessment/pr-<PR>-<slug>.md` per `AGENTS.md`. |

## Test oracle

| Test surface | Required cases |
|---|---|
| Codec (JVM) | chunked assembly round-trip, zero-length manifest, digest mismatch detection, engineering-bound enforcement, payload-checksum equality between a v2 record and its migrated format-3 representation. |
| Store contract (instrumentation, real SQLite) | oversized ≥2.25 MB checkpoint end-to-end (`CREATING` → `READY` with both close/reopen read-backs), oversized `markApplying`/`markRestoring` rewrites, recovery restore of an oversized record, retention with oversized records present. |
| Migration (instrumentation, real SQLite) | v2 store with a >2 MB row migrates server-side; checksums remain valid; poisoned-store scenario (unreadable `CREATING` + healthy `VERIFIED`) reconciles per-record; migration fault injection leaves a fail-closed v2 store; downgrade probe is incompatible. |
| Quarantine (instrumentation + JVM session tests) | missing/corrupt chunk rows quarantine the affected record only; quarantine is transactional (fault injection rolls back); healthy records still reconcile; quarantine tombstone carries the typed reason and expires via retention. |
| Read-path normalization (source-level + instrumentation) | no `SELECT *` over `recovery_points` remains; inspection-snapshot publication succeeds with an unreadable record present, projecting `checksumValid = false`. |
| Existing suites | protocol, fence, reconciliation, retention, and inspection tests pass unchanged in behavior. |

## Stop conditions

Implementation must stop and return to this spec if any of the following
proves necessary:

- weakening exact apply verification, the checkpoint-then-layout ordering,
  or any ADR-0003 guarantee;
- reading a manifest through a path that can raise
  `SQLiteBlobTooBigException` (any full-row read reintroduction);
- migrating v2 records with a `Cursor`-based read, or migrating a store
  non-transactionally;
- quarantining or deleting any record outside the reconciliation session
  authority (#89), or quarantining a record that is readable and classified;
- introducing a deliberate record-size cap below real-device scale, or any
  policy decision that re-closes the ≥2.25 MB checkpoint as a
  "diagnosable failure";
- changing `RecoveryStorePort`, diagnostics event shapes, retention
  semantics, or the inspection-snapshot fence contract; or
- requiring a change to the Launcher databases, backup allowlists, or
  `DatabaseHelper.SCHEMA_VERSION`.

## Change history

- 2026-08-30: Stage A draft for #174. Chunked-manifest strategy selected
  over projection-only reads and file-backed manifests (see `plan.md`
  alternatives); migration and quarantine defined.

## References

- [Issue #174](https://github.com/nunu1733/NunuLauncher/issues/174) — owning bug issue.
- [Issue #171 assessment](../../docs/assessment/issue-171-organizer-after-external-restore.md) — device root cause and poisoned-store evidence.
- [ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md) — separate private recovery DB; checkpoint-then-layout ordering.
- [Spec 89](../89-inspection-safe-recovery-store-read/spec.md) — inspection snapshot fence that must not regress.
- [Spec 13](../13-safe-layout-application/spec.md) — apply/recovery contract.
- [AGENTS.md](../../AGENTS.md) — high-risk PR evidence requirements.
- Android `CursorWindow` 2 MB default and `SQLiteBlobTooBigException`:
  `frameworks/base` `CursorWindow` / `SQLiteQuery` behavior observed on the
  #171 device (see assessment doc).
