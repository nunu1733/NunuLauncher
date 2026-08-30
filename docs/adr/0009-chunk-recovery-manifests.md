---
status: proposed
issue: "#174"
updated: 2026-08-30
---

# Chunk recovery manifests without changing the logical record format

## Decision

Recovery DB physical schema 3 stores the `pre`, `intended`, and `reviewed`
manifest bytes in fixed-size rows of a same-database
`recovery_manifest_chunks` table. `recovery_points` retains bounded metadata,
physical per-slot byte sizes, digests, and the logical payload checksum. The
sizes determine the required contiguous chunk count and distinguish a
zero-length slot from a missing chunk set. Checkpoint and lifecycle writes
commit the point row and all affected chunks in the same SQLite transaction.
DDL checks bound slot values, indices, chunk lengths, and recorded slot sizes;
assembly additionally proves contiguity and exact reconstructed length.

Physical schema versioning and logical recovery-record versioning are separate
contracts. `PRAGMA user_version` becomes 3, while migrated and newly written
records keep logical `format_version = 2`. The checksum includes that logical
format value; schema-2 → schema-3 migration copies `format_version` and
`payload_checksum` unchanged, chunks the manifest blobs with server-side SQL,
and validates the reconstructed representation. Codec decode and lifecycle
compatibility continue to accept logical record format 2.

Chunk ownership is explicit rather than dependent on SQLite foreign-key
enablement. Every point-deletion path deletes owned chunks before the point
row in the same transaction. Migration, retention, prune, quarantine, and
failure-injection tests must prove that no committed orphan chunk remains.

Restart reconciliation enumerates bounded metadata first and loads one full
record at a time through a closed `Readable` / `Unreadable` result. Containment
is lifecycle-sensitive:

- unreadable `CREATING` or `READY` records may be transactionally quarantined
  and deleted because their durable lifecycle proves Launcher mutation has not
  begun;
- unreadable `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING` records retain
  their row, chunks, and lifecycle and make reconciliation unresolved;
- unreadable `VERIFIED` points remain stored, project checksum-invalid, and
  are non-restorable until ordinary retention expires them;
- a row whose identity or lifecycle metadata cannot be decoded is preserved,
  because safe deletion cannot be proven.

Healthy records continue reconciliation independently in every case. The
application-facing apply/recovery operations and public result variants do not
change. A new internal `QUARANTINED` tombstone reason maps to the existing
public corrupt rejection.

## Context

Issue #174 records a real-device checkpoint whose two inline manifest copies
make one `recovery_points` row approximately 2.25 MB. Android's 2 MB
`CursorWindow` rejects the mandatory close/reopen read-back and later prevents
batch reconciliation from reading any record in the store. Projection-only
queries address the observed duplicate-row case but fail again when one
manifest exceeds the window. The #171 device also retains an oversized
schema-2 `CREATING` row that must migrate without an Android `Cursor`.

This decision changes a persistent high-risk format, its upgrade/downgrade
behavior, and corruption containment. Its rationale is not apparent from the
DDL, and viable projection-only, file-backed, and capped alternatives exist;
it therefore meets the ADR threshold in `AGENTS.md`.

ADR-0003 remains authoritative for the separate private DB and
checkpoint-before-Launcher ordering. This ADR decides the representation and
failure policy inside that DB.

## Alternatives

| Alternative | Advantage | Disqualifying cost or risk |
|---|---|---|
| **Same-DB chunk side table, schema 3 / record format 2 (selected)** | Keeps each physical row bounded, preserves one-DB transactions, migrates poisoned schema-2 rows server-side, and leaves logical checksums intact. | Adds schema migration, chunk ownership, assembly validation, and per-record containment tests. |
| Projection-only reads of inline blobs | Smallest schema change; enough for the observed two-blob row. | A single manifest can still exceed the `CursorWindow`, leaving the same defect class and no robust already-poisoned migration path. |
| App-private manifest files referenced by DB rows | Avoids `CursorWindow` for payload bytes. | DB and filesystem cannot share a transaction; a new durability and crash-reconciliation protocol would be required. |
| Deliberate size cap | Bounds resource use. | Issue #174 requires deterministic success at the observed ≥2.25 MB scale; diagnosable rejection does not satisfy it. |
| Logical record format 3 with checksum recomputation | Could encode representation changes in the record version. | Chunking does not change logical content. Rewriting lifecycle rows and checksums during a physical migration adds risk without a semantic benefit. |
| Delete every unreadable record | Simplifies cleanup. | Active states may need their manifest to recover a layout already mutated; `VERIFIED` is the recovery point itself. Immediate deletion breaks the recovery guarantee. |

## Consequences

- Schema-version gates and `SQLiteOpenHelper` use physical schema version 3;
  codec and lifecycle classification use logical record format 2.
- Schema-2 → schema-3 migration uses raw SQLite SQL only, inside one
  transaction. Failure leaves the schema-2 DB intact and fail-closed; a
  schema-2-era downgrade treats schema 3 as incompatible and never touches the
  Launcher layout.
- Chunk indices, count/size bounds, slot presence, reconstructed length,
  logical payload checksum, and manifest decoding are validated before a
  record is exposed to protocol code.
- The ordinary store port drops its reconciliation-only batch listing;
  internal reconciliation-session and tombstone-reason contracts change;
  public apply/recovery behavior does not.
- Unreadable active records deliberately keep the aggregate organizer state
  unresolved. Per-record degradation prevents healthy-record poisoning but
  does not authorize further layout mutation while recovery evidence is
  incomplete.
- The DB filename, backup exclusion, permissions, retention durations,
  lifecycle transition table, inspection fence, and ADR-0003 ordering remain
  unchanged.

## References

- [Issue #174](https://github.com/nunu1733/NunuLauncher/issues/174)
- [Issue #174 spec](../../specs/174-recovery-record-cursor-window/spec.md)
- [Issue #174 plan](../../specs/174-recovery-record-cursor-window/plan.md)
- [ADR-0003](./0003-organizer-recovery-point-storage.md)
- [ADR-0008](./0008-qsb-reservation-context-and-recovery-compatibility.md)
- [Issue #171 assessment](../assessment/issue-171-organizer-after-external-restore.md)

## Change history

- 2026-08-30: Proposed for Issue #174 after Stage A review identified the
  schema/record-version coupling, unsafe all-lifecycle deletion, missing
  point-level load seam, and implicit chunk-ownership gaps.
