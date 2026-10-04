---
issue: "#174"
status: implemented
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
oversized row — including the exact store state the #171 device still
exhibits — is recovered by the schema-2 migration, while a physically corrupt
record is contained by a lifecycle-sensitive path without touching the
Launcher layout. The fail-closed protocol, ADR-0003 ordering, and the #89
inspection boundary are unchanged.

| Decision | Normative consequence |
|---|---|
| **Selected strategy: chunked manifest side table (recovery DB schema 3)** | Manifest blobs (`pre`, `intended`, `reviewed` slots) leave the `recovery_points` row and are stored as fixed-size chunks in a per-point side table. Every physical row — record row and chunk row — stays far below the 2 MB `CursorWindow`. |
| Physical schema 3, logical record format 2 | `PRAGMA user_version` becomes 3, while every migrated and newly written logical recovery record keeps `format_version = 2`. The existing payload-checksum algorithm includes that logical format value and is unchanged, so logical-format-2 checksums remain valid without recomputation. |
| No full-row `SELECT *` on any recovery read path | Every read uses small-column projections plus per-record chunk assembly. One unreadable record degrades that record only. |
| Schema 2 → 3 migration is server-side SQL only | The migration chunks existing blobs with in-SQLite `substr()`; no Android `Cursor` is created, so an already-poisoned schema-2 store migrates successfully. |
| Lifecycle-sensitive per-record containment | Reconciliation enumerates metadata and loads one full record at a time. An unreadable `CREATING`/`READY` record is quarantined transactionally because Launcher mutation cannot yet have begun. An unreadable active record is preserved in its original lifecycle and makes the reconciliation summary unresolved; an unreadable `VERIFIED` point remains stored but is non-restorable. Healthy records still reconcile. |
| Explicit chunk ownership | The schema does not rely on SQLite foreign-key enablement. Every point deletion first deletes all owned chunk rows and then the point row in the same transaction; no committed orphan chunk may remain. |
| Write bound is a corruption bound, not a product cap | Manifests up to a generous engineering bound (well above any real-device scale) are accepted; exceeding it fails closed before any layout mutation. No deliberate size-cap policy is introduced. |

## Scope

- Recovery DB physical schema 3: `recovery_points` row without manifest blob columns,
  plus a `recovery_manifest_chunks` side table; chunk write/assembly in the
  existing store transaction paths.
- Elimination of every full-row `SELECT *` over `recovery_points`: record
  read-back, lifecycle updates, retention listing, reconciliation listing,
  and the #89 inspection-snapshot projection.
- The schema-2 → schema-3 pre-open migration (pattern of the existing v1
  empty-store migration in `RecoveryStore.migrateEmptyLegacyStore()`),
  including migration of a poisoned schema-2 store.
- The reconciliation-only per-record load seam, lifecycle-sensitive
  containment path, and typed quarantine tombstone reason.
- The ordinary closed record-read result and deterministic recovery/preview
  `CORRUPT` mapping for a preserved unreadable point.
- Explicit child-first chunk deletion in every retention, prune, quarantine,
  and tombstone path.
- Regression and migration test coverage at the application-contract level
  (real-SQLite instrumentation on the emulator) and codec-level coverage.

## Non-goals

- Diagnostics reason surfacing for input-unavailable and fence-dirty
  follow-ons — owned by #172. No new public diagnostics event is introduced;
  containment surfaces through existing reconciliation result/event values.
- No change to: apply/recovery public result semantics, `LifecycleTransitions`,
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

### Migration of the poisoned schema-2 store

Given a schema-2 recovery DB containing a >2 MB unreadable `CREATING` row
and a healthy `VERIFIED` row (the #171 device state),

When the app opens the store on a schema-3 build,

Then the pre-open migration converts the store server-side (no `Cursor` is
created), sets `user_version = 3`, preserves every row's
`format_version = 2`, and preserves every record's logical bytes and
payload-checksum validity without recomputation,

And startup reconciliation then classifies the migrated `CREATING` row
(`PRE_STATE` → `READY` + prune) and reconciles the healthy `VERIFIED` row.

### Lifecycle-sensitive containment of unreadable records

Given separate records whose metadata is readable but whose chunk rows are
missing or malformed so their full records cannot be reassembled, in
`CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, `RESTORING`, and
`VERIFIED` lifecycles,

When restart reconciliation processes the store,

Then each unreadable `CREATING`/`READY` record is quarantined because the
durable lifecycle proves no Launcher mutation can have begun: one transaction
writes a typed quarantine tombstone, deletes all owned chunks, and deletes the
point row by ID without loading the manifest,

And each unreadable `APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING` record is
left in its original lifecycle, no tombstone or row deletion occurs, and the
reconciliation summary is unresolved so later organizer mutation remains
fail-closed,

And each unreadable `VERIFIED` point remains stored and is rejected as
non-restorable until normal retention expires it. An ordinary recovery read
returns a typed `Unreadable` result, so `recover()` deterministically returns
`NotRestorable(CORRUPT)` rather than `RestoreFailed`, tombstone lookup, or
`MISSING`. Recovery preview returns the matching `NotRestorable(CORRUPT)` from
its SQLite-free inspection projection,

And every healthy record is still reconciled. Inspection publication emits a
`QUARANTINED` tombstone projection for a deleted safe-state record and a
record projection with `checksumValid = false` for every preserved unreadable
record, rather than failing wholesale.

### Unreadable metadata is never deleted speculatively

Given a row whose projected point ID or lifecycle cannot be decoded,

When reconciliation enumerates candidates,

Then the row is reported as unresolved and preserved, every independently
identifiable healthy record still reconciles, and no destructive containment
occurs because the no-layout-mutation precondition cannot be proven.

### Manifest write bound fails closed

Given a capture manifest larger than the engineering bound,

When `checkpoint()` runs,

Then it fails closed before any layout mutation with the existing
`CreateFailed` mapping,

And the bound is fixed, far above real-device scale, and never used to
reject the ≥2.25 MB scale required by this issue.

### Downgrade stays fail-closed

Given a schema-3 store and an app build whose supported physical schema
version is 2,

When the version gate probes the store,

Then the store is `INCOMPATIBLE_VERSION` (read-only, non-restorable per
ADR-0003) and the Launcher layout is not touched.

### Migration failure leaves the store fail-closed

Given a schema-2 store whose migration transaction fails (fault injection),

When availability is evaluated,

Then the store remains at schema 2, is reported incompatible, no partial schema
change is visible, and the Launcher layout is unchanged; the next start may
retry the migration.

## Data and state

- **Schema-3 record row** (`recovery_points`, rebuilt): all schema-2 columns except
  `pre_manifest`, `intended_manifest`, `reviewed_manifest`, plus physical
  `pre_manifest_size`, `intended_manifest_size`, and nullable
  `reviewed_manifest_size`. `point_id`
  remains the primary key; `payload_checksum` remains a 32-byte SHA-256 over
  the logical record in the existing `CHECKSUM_COLUMNS` order, with manifest
  bytes now sourced from the chunk table at verification time.
- **Version separation**: `RecoveryDbSchema.SCHEMA_VERSION = 3` owns DDL and
  `PRAGMA user_version`; `RecoveryRecordCodec.RECORD_FORMAT_VERSION = 2`
  owns `recovery_points.format_version`, tombstone `format_version`, checksum
  input, codec decode, and `LifecycleReconciler.SUPPORTED_FORMAT`. New rows
  and migrated rows both use logical format 2. Migration never rewrites
  `format_version` or `payload_checksum`.
- **Chunk side table** (`recovery_manifest_chunks`): `(point_id, slot,
  chunk_index, chunk)`, primary key `(point_id, slot, chunk_index)`;
  slots `PRE`/`INTENDED`/`REVIEWED`; fixed chunk size (plan: 512 KiB).
  Schema checks require a known slot, non-negative index, and chunk length in
  `1..512 KiB`; required `pre_manifest_size`/`intended_manifest_size` and a
  present `reviewed_manifest_size` are in `1..64 MiB`, while absent `REVIEWED`
  is represented only by `reviewed_manifest_size IS NULL`. The physical size
  columns determine a positive required chunk count. Assembly requires
  contiguous zero-based indices, exact non-final/final chunk sizes, and exact
  reconstructed length before the unchanged logical payload checksum and
  existing manifest decoder validate the record. Empty bytes are never a
  valid manifest and zero chunks always mean a missing/corrupt required slot.
  The table deliberately has no
  foreign key because Android SQLite foreign-key enablement is not an implicit
  invariant of this store.
- **Writes stay transactional**: the `CREATING` insert writes the record row
  and both slot chunk sets in the checkpoint transaction; `markApplying`
  rewrites the `INTENDED` chunk set and `markRestoring` writes the
  `REVIEWED` chunk set inside their existing single transactions
  (ADR-0003: each lifecycle transition is one transaction).
- **Deletion ownership**: retention, prune, quarantine, and every other point
  deletion execute `DELETE recovery_manifest_chunks WHERE point_id = ?`
  before deleting `recovery_points`, inside the same transaction. A failure
  rolls both operations back; a committed store contains no orphan chunks.
- **New tombstone reason** `QUARANTINED` joins the existing typed reasons and
  maps to the existing public `CORRUPT` rejection (no new public result);
  quarantine tombstones expire through the existing retention.
- **Reconciliation seam**: metadata enumeration is separate from point-level
  full-record loading. The session reuses the same closed `RecordRead` result
  as ordinary reads instead of exposing a second load-result type, and owns
  the only safe-state quarantine operation.
  Candidate metadata includes the bounded lifecycle/identity fields and
  pre/intended/reviewed digests needed for authoritative classification; it
  never contains manifest bytes.
- **Ordinary record-read seam**: `RecoveryStorePort.readRecord(pointId)`
  returns a closed `Readable(StoredRecord)` / `Unreadable(metadata)` /
  `Missing` / `Failed` result. `Unreadable` means the bounded row metadata was
  decoded but chunk assembly or manifest decode failed; an assembled,
  decodable record with a payload-checksum mismatch remains `Readable` with
  `checksumValid = false` so the existing `CORRUPT` path is preserved.
  `Failed` is reserved for store I/O or undecodable metadata. Recovery
  maps `Unreadable` to existing public `NotRestorable(CORRUPT)`, `Missing` to
  tombstone lookup, and `Failed` to existing `RestoreFailed` store failure.
  Preview performs no SQLite read per #89; its existing closed inspection
  projection must represent the same unreadable record as
  `checksumValid = false` after startup reconciliation/snapshot rebuild, which
  maps to preview `NotRestorable(CORRUPT)`.
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
| CW-AC-01 | `spec.md`, `plan.md`, and ADR-0009 select the chunked-manifest strategy, keep the fail-closed protocol and ADR-0003 ordering unchanged, and define physical schema 3, logical record format 2, migration, read-path normalization, lifecycle-sensitive containment, and chunk ownership. |
| CW-AC-02 | A regression test at the application-contract level (real-SQLite test DB on the emulator) creates a checkpoint whose logical record is ≥ the ~2.25 MB observed on the #171 device. Pre-fix, the test reproduces the `SQLiteBlobTooBigException` → `CHECKPOINT_CREATE_FAILED` failure (recorded as PR evidence); post-fix, `checkpoint()` returns `Ready` deterministically at that scale: the record is committed, survives close/reopen, and passes the full read-back validation. |
| CW-AC-03 | No recovery read path executes a full-row `SELECT *` over `recovery_points`. Record read-back, lifecycle updates, retention, reconciliation listing, and the inspection-snapshot projection read bounded projections and point-level chunk sets, so no single row can exceed the 2 MB `CursorWindow` through a protocol path. Required/present slot sizes are strictly positive and determine required chunk count; assembly rejects absent required slots, gaps, duplicate/non-contiguous indices, invalid chunk sizes, and length mismatch before logical decode. Size accounting covers every blob slot (`pre`, `intended`, `reviewed`) and the engineering bound is enforced at write time. |
| CW-AC-04 | No store poisoning: reconciliation enumerates bounded metadata and obtains the shared point-level `RecordRead`, so every healthy record still reconciles. Unreadable `CREATING`/`READY` records alone are transactionally quarantined; unreadable `APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING` records retain their row, chunks, and original lifecycle and yield an unresolved summary; unreadable `VERIFIED` records remain stored until normal retention. Ordinary recovery receives the same typed `Unreadable` and deterministically maps it to `NotRestorable(CORRUPT)`; after startup reconciliation rebuilds the inspection snapshot, preview maps the corresponding checksum-invalid projection to preview `NotRestorable(CORRUPT)`. Neither path returns `RestoreFailed`, consults a tombstone, nor reports `MISSING` for the preserved row. Malformed metadata is preserved, never speculatively deleted. Inspection publication represents each contained record without aborting. |
| CW-AC-05 | The physical schema-2 → schema-3 migration runs server-side SQL only (no `Cursor`/`CursorWindow` involvement), is transactional (failure leaves a fail-closed schema-2 store), leaves logical `format_version = 2` and `payload_checksum` bytes unchanged, and proves the checksum still valid after assembly. It recovers the #171-device-class poisoned store: after migration, the oversized `CREATING` row is classified and cleaned per the existing path and the healthy record remains available. |
| CW-AC-06 | Downgrade (schema-3 store, schema-2-era build) and backup/restore behavior are unchanged and fail-closed; the Launcher layout is untouched on every failure path. |
| CW-AC-07 | The application-facing `apply`/`recover` operations and public result types are unchanged. `RecoveryStorePort.readRecord()` changes from nullable/throwing behavior to the closed ordinary read result; its reconciliation-only batch listing is removed, and the internal reconciliation session owns metadata enumeration plus point-level load results. `TombstoneReason` gains `QUARANTINED`, canonically encoded as 6 and mapped to the existing public `CORRUPT` rejection. All ordinary-read callers and Fake-based store/session tests adapt to the changed internal contracts; readable-record behavior is unchanged. |
| CW-AC-08 | Device-class verification is mandatory and strict: the #171 device sequence (organize → exactly one Nova restore → organize) passes A4 and reaches `APPLY_VERIFIED` on a real-device-scale workspace. No "leaves a diagnosable, recoverable state" alternative is accepted. |
| CW-AC-09 | High-risk evidence process applies: `risk: layout-data` / `risk: migration` labels, `CI / final-status` success on the verification head including the focused instrumentation jobs, and an independent audit record in `docs/assessment/pr-<PR>-<slug>.md` per `AGENTS.md`. |
| CW-AC-10 | Schema checks enforce known slot values, non-negative contiguous indices at assembly, bounded non-empty chunk rows, strictly positive required/present slot sizes, and `NULL` as the only absent-`REVIEWED` representation. Every point-deletion path (retention, prune, quarantine, and tombstoning) explicitly deletes owned chunk rows before the point row in the same transaction. Migration and all deletion/failure-injection tests prove rollback atomicity and zero committed orphan chunks; correctness does not depend on foreign-key enablement. |

## Test oracle

| Test surface | Required cases |
|---|---|
| Codec/store assembly (JVM + instrumentation) | chunked assembly round-trip; required `PRE`/`INTENDED` and present `REVIEWED` reject size 0; absent `REVIEWED` uses `NULL`; missing/non-contiguous/oversized chunk detection; engineering-bound enforcement; payload-checksum validity with logical record format 2 before and after physical schema migration. |
| Store contract (instrumentation, real SQLite) | oversized ≥2.25 MB checkpoint end-to-end (`CREATING` → `READY` with both close/reopen read-backs), oversized `markApplying`/`markRestoring` rewrites, recovery restore of an oversized record, retention with oversized records present. |
| Migration (instrumentation, real SQLite) | schema-2 store with a >2 MB row migrates server-side; `user_version` changes 2→3 while row/tombstone `format_version` stays 2; stored checksum bytes are unchanged and validate after assembly; migration fault injection leaves a fail-closed schema-2 store; downgrade probe is incompatible. |
| Containment and ordinary reads (instrumentation + JVM protocol/session tests) | separate missing/corrupt-chunk fixtures for `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, `RESTORING`, and `VERIFIED`; only the first two quarantine; active states are preserved and unresolved; `VERIFIED` is preserved. For the preserved unreadable `VERIFIED` fixture, ordinary read returns `Unreadable`; after startup reconciliation/snapshot rebuild, recovery and preview both return their existing `NotRestorable(CORRUPT)` variants, and neither returns store failure or missing. Malformed metadata returns `Failed`/aggregate failure and is preserved; healthy records still reconcile. Quarantine fault injection rolls back and its tombstone uses canonical reason 6. |
| Ownership/deletion (instrumentation) | retention, prune, quarantine, and every tombstone path remove chunks child-first in the same transaction; failure injection restores parent and children; no orphan chunks remain after migration or committed deletion. |
| Read-path normalization (source-level + instrumentation) | no `SELECT *` over `recovery_points` remains; inspection publication emits a `QUARANTINED` tombstone for safely deleted records and `checksumValid = false` for preserved unreadable records. |
| Existing suites | protocol, fence, reconciliation, retention, and inspection tests pass unchanged in behavior. |

## Stop conditions

Implementation must stop and return to this spec if any of the following
proves necessary:

- weakening exact apply verification, the checkpoint-then-layout ordering,
  or any ADR-0003 guarantee;
- reading a manifest through a path that can raise
  `SQLiteBlobTooBigException` (any full-row read reintroduction);
- migrating schema-2 records with a `Cursor`-based read, or migrating a store
  non-transactionally;
- quarantining or deleting any unreadable record outside the reconciliation
  session authority (#89), deleting an unreadable record whose durable
  lifecycle is not proven `CREATING`/`READY`, or changing an active unreadable
  record's lifecycle;
- introducing a deliberate record-size cap below real-device scale, or any
  policy decision that re-closes the ≥2.25 MB checkpoint as a
  "diagnosable failure";
- changing application-facing operations/public result types, diagnostics
  event shapes, retention semantics, or the inspection-snapshot fence
  contract beyond the explicitly accepted internal session and tombstone
  changes; or
- requiring a change to the Launcher databases, backup allowlists, or
  `DatabaseHelper.SCHEMA_VERSION`.

## Change history

- 2026-08-30: Implemented and merged through
  [PR #175](https://github.com/nunu1733/NunuLauncher/pull/175). CW-AC-08
  operator device evidence recorded on the PR (Pixel 9a, build
  `15.Dev.(48e42e7)`: organize → exactly one Nova restore → organize reaches
  `APPLY_VERIFIED` with recovery, restart reconciliation, and idempotent
  no-op follow-ups; zero `CHECKPOINT_CREATE_FAILED` /
  `RECOVERY_STORE_UNAVAILABLE`). Independent audit:
  [docs/assessment/pr-175-chunk-recovery-manifests.md](../../docs/assessment/pr-175-chunk-recovery-manifests.md)
  (pass-with-findings).
- 2026-08-30: Review fixes on PR #175: normalized checked manifest-decode
  failures (`java.io.EOFException` from a truncated payload with valid chunk
  shape) into the closed record-read result at the store boundary, and made
  the v1-empty migration physically rebuild the current schema instead of only
  advancing `PRAGMA user_version` (with an end-to-end checkpoint/read-back
  migration test). Regression fixtures added for both.
- 2026-08-30: Accepted and implemented on
  `docs/issue-174-stage-a-spec-plan`. Pre-fix reproduction recorded (emulator
  API 36: `Row too big to fit into CursorWindow requiredPos=0, totalRows=1` on
  `SELECT * FROM recovery_points WHERE point_id = ?` at a 2,369,818-byte
  logical record → `CreateFailed`); post-fix evidence: organizer JVM suite
  758 tests green, new real-SQLite instrumentation suite green on the
  emulator and on a Pixel 9a device, and the four-phase organizer recovery
  smoke (READY / AROUND_COMMIT / COMMITTED_UNVERIFIED / RESTORING) passed
  end-to-end. The smoke script itself has a pre-existing startup-reconcile
  lease-contention race (reproduced on unfixed `main`) — follow-up issue
  required, not part of this fix.
- 2026-08-30: Revised after second Stage A review: added the ordinary closed
  record-read contract and recovery/preview parity for preserved unreadable
  `VERIFIED`; removed zero-length manifests from the physical schema and tests.
- 2026-08-30: Revised Stage A after Issue comment review: separated physical
  schema 3 from logical record format 2, made containment lifecycle-sensitive,
  defined the point-level reconciliation load seam and child-first chunk
  deletion, and added ADR-0009.
- 2026-08-30: Stage A draft for #174. Chunked-manifest strategy selected
  over projection-only reads and file-backed manifests (see `plan.md`
  alternatives); migration and quarantine defined.

## References

- [Issue #174](https://github.com/nunu1733/NunuLauncher/issues/174) — owning bug issue.
- [Issue #171 assessment](../../docs/assessment/issue-171-organizer-after-external-restore.md) — device root cause and poisoned-store evidence.
- [ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md) — separate private recovery DB; checkpoint-then-layout ordering.
- [ADR-0009](../../docs/adr/0009-chunk-recovery-manifests.md) — schema-3 chunk storage, version separation, and containment policy.
- [Spec 89](../89-inspection-safe-recovery-store-read/spec.md) — inspection snapshot fence that must not regress.
- [Spec 13](../13-safe-layout-application/spec.md) — apply/recovery contract.
- [AGENTS.md](../../AGENTS.md) — high-risk PR evidence requirements.
- Android `CursorWindow` 2 MB default and `SQLiteBlobTooBigException`:
  `frameworks/base` `CursorWindow` / `SQLiteQuery` behavior observed on the
  #171 device (see assessment doc).
