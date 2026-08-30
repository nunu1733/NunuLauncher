---
issue: "#174"
status: draft
updated: 2026-08-30
---

# Plan: schema-3 chunked recovery manifests and lifecycle-sensitive containment

> Issue: #174
> Spec: [spec.md](./spec.md)
> Status: draft (Stage A — pending spec approval)

## Current evidence

Confirmed on the #171 device (Pixel 9a-class, Android 17), `main` @
`afb7618144`; see
[docs/assessment/issue-171-organizer-after-external-restore.md](../../docs/assessment/issue-171-organizer-after-external-restore.md).
Code paths on current `main`:

- `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt`
  - `checkpoint()` (L252–361): encodes the capture manifest once
    (`preManifestBytes`) and stores it in both `pre_manifest` and
    `intended_manifest`; the CREATING insert commits; the forced
    close/reopen `readRecord` (L313–320) throws
    `SQLiteBlobTooBigException`; the `catch (SQLException)` (L335) collapses
    it to `CreateFailed` and the fence finishes `OUTCOME_UNCERTAIN` (L357).
  - `readRecord` (L530) and `readEncoded` (L854): `SELECT *` per point.
  - `listNonFinalRecords` (L547): `SELECT * ORDER BY created_at_ms ASC` —
    the poisoning query (`requiredPos=1, totalRows=2` on the device).
  - `publishCurrentProjection` (L1021): `SELECT *` over all rows for the #89
    inspection snapshot, calling `validateRecord` per row.
  - `listRetentionRecords(db)` (L656): already a projected small-column read
    (the pattern to generalize).
  - `insertRecordRow` (L869) / `writeMutableRecordColumns` (L825): write
    `pre_manifest`/`intended_manifest`/`reviewed_manifest` blobs inline.
- `RecoveryDbSchema.kt`: `DDL_FORMAT_2`, `FORMAT_VERSION = 2`, currently
  coupling physical `PRAGMA user_version` to logical record `format_version`;
  `CHECKSUM_COLUMNS` (codec order; manifests are columns 8, 11, 14 in the
  checksum stream).
- `RecoveryDbHelper.kt`: `onUpgrade` throws — every version transition is a
  pre-open decision in `RecoveryDbVersionGate` + `availability()`
  (`migrateEmptyLegacyStore()` at `RecoveryStore.kt:L109` is the v1
  empty-store precedent, including the pre-probe read-only eligibility
  check L156).
- `RecoveryRecordCodec.kt`: `computePayloadChecksum` is a tagged SHA-256
  over the logical record and includes `formatVersion`; `decode()` requires
  that value to equal `RecoveryDbSchema.FORMAT_VERSION`.
- `RestartReconciler.kt` (L150+): per-record classification from
  `checksumValid`, `formatVersion`, and digests; `CREATING` +
  `PRE_STATE` → `READY` + prune (`SilentPrune`); checksum-invalid →
  `CORRUPT`. Classification needs no manifest bytes.
- `RecoveryStoreReconciliationSession.listNonFinalRecords()` returns a batch
  of full `StoredRecord` values, so one chunk-assembly failure currently has
  no point-level result seam and would still poison the whole batch.
- Test surfaces: `tests/unit` is pure JVM with `FakeRecoveryStore` (the
  reconciliation fake/session must adapt); real-SQLite store evidence lives in
  `tests/organizer-instrumentation` (wired as the app module `androidTest`
  source set, run by the focused CI instrumentation jobs and locally via
  connectedAndroidTest on an emulator — the 2 MB `CursorWindow` is only
  enforced on real Android SQLite).

Hypothesis vs confirmed: the failure chain above is confirmed by device
instrumentation; the fix strategy below is a design decision (Stage A).

## Design

### Strategy (decision)

Move the three manifest blobs out of the `recovery_points` row into a
chunked side table in physical schema 3, normalize every read to projected
columns + point-level chunk assembly, migrate schema-2 stores server-side,
and add a lifecycle-sensitive reconciliation containment path. Keep logical
record format 2 and its checksum bytes unchanged. Application-facing
operations/results, lifecycle transitions, retention policy, fence, and
diagnostics event shapes stay unchanged; the internal reconciliation session
and tombstone-reason enum change explicitly.

### Modules and interfaces

| Unit | Change |
|---|---|
| `RecoveryDbSchema` | Split `SCHEMA_VERSION = 3` from logical record versioning; `DDL_SCHEMA_3` rebuilds `recovery_points` without the three manifest blob columns, adds physical per-slot byte-size columns, and adds `recovery_manifest_chunks`. DDL checks constrain known slot ints, non-negative indices/sizes, chunk bytes to `1..512 KiB`, and each slot size to ≤64 MiB; reviewed size alone is nullable. Add slot/chunk constants (`PRE`/`INTENDED`/`REVIEWED`; 512 KiB; 64 MiB = 128 chunks). Keep logical `CHECKSUM_COLUMNS` order. |
| `RecoveryRecordCodec` | Own `RECORD_FORMAT_VERSION = 2`; `Encoded.formatVersion` defaults to it and `decode()` compares against it. Manifest arrays are populated by store assembly. Checksum computation is untouched and still includes logical format 2. `LifecycleReconciler.SUPPORTED_FORMAT` uses the same logical constant (or an equivalently tested value), never schema version. |
| `RecoveryStore` (private) | New internal collaborator (e.g. `RecoveryManifestChunks.kt`): write slot chunk sets (`INSERT` per chunk), assemble against the stored slot size (`SELECT chunk … ORDER BY chunk_index`), delete/rewrite slots — all called inside existing transactions. Assembly validates contiguous indices, derived count, each chunk length, and total length before logical checksum/decode. `insertRecordRow`/`writeMutableRecordColumns` replace blob puts with size metadata + chunk writes; `writeTombstone` writes logical record format 2 rather than the physical schema version. Bound check before insert (`CreateFailed` fail-closed beyond 64 MiB). |
| `RecoveryStore` (reads) | `publishCurrentProjection` and ordinary reads use bounded record projections plus point-level assembly. A preserved unreadable record projects `checksumValid = false`; a safely quarantined record appears through its tombstone. Neither aborts publication. |
| `RecoveryStore.availability()` | Schema-2 file on a schema-3 build: new pre-open migration decision (v1 precedent, L109/L156) — eligibility probe read-only, then one read-write transaction performing the server-side migration, then re-probe → `READY`. Runs identically under startup reconciliation, which is the poisoned-store recovery path. |
| `RecoveryStoreReconciliationSession` | Replace batch `listNonFinalRecords()` with bounded metadata enumeration and `loadRecord(candidate): RecordLoadResult`, a closed `Readable(StoredRecord)` / `Unreadable(metadata, failure)` result. Add a session-authorized `quarantineUnmutated(pointId, expectedLifecycle)` operation that succeeds only for `CREATING`/`READY`. Invalid metadata is preserved and makes the aggregate `Failed` after healthy candidates have been processed; it never authorizes a delete. |
| `RestartReconciler` | Iterate candidates independently. Reconcile `Readable` normally. For `Unreadable`: quarantine `CREATING`/`READY`; preserve `APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING` in the original state and add `Unresolved` so later mutation stays fail-closed; preserve `VERIFIED` as non-restorable. Continue processing healthy candidates in every case. |
| `RecoveryStorePort` | Remove the reconciliation-only batch `listNonFinalRecords()` from the ordinary port. `TombstoneReason` gains `QUARANTINED` (canonical int 6; snapshot/tombstone codec mappings); recovery/preview map it to the existing public `CORRUPT` rejection. No application-facing operation or public result variant changes. |
| All point-deletion paths | Add one internal child-first delete primitive: delete `recovery_manifest_chunks` then `recovery_points` in the caller's transaction. Retention, prune, quarantine, and tombstoning must use it. No foreign key is declared or assumed; tests assert zero orphans and rollback. |

### Server-side physical schema 2 → 3 migration (no `Cursor` involvement)

Inside one migration transaction on a raw `OPEN_READWRITE` handle (the
helper is not opened for an incompatible store):

1. `CREATE TABLE recovery_points_v3 …` (schema-3 columns) and
   `CREATE TABLE recovery_manifest_chunks …`.
2. `INSERT INTO recovery_points_v3 SELECT <small columns>,
   length(pre_manifest), length(intended_manifest),
   CASE WHEN reviewed_manifest IS NULL THEN NULL ELSE length(reviewed_manifest)
   END FROM recovery_points`. Copy logical `format_version` and
   `payload_checksum` unchanged.
3. Chunk each manifest server-side with a recursive CTE over `length()`/
   `substr()` — zero-based
   `WITH RECURSIVE cnt(i) AS (VALUES(0) UNION ALL SELECT i+1 …) INSERT INTO
   recovery_manifest_chunks SELECT point_id, <slot>, i,
   substr(<col>, i*L+1, L) FROM recovery_points, cnt
   WHERE i*L < length(<col>)` — once per slot
   (`pre_manifest`→`PRE`, `intended_manifest`→`INTENDED`,
   `reviewed_manifest`→`REVIEWED` where `NOT NULL`). `substr()` over blobs
   and recursive CTEs exist in every supported Android SQLite (≥3.8.3).
4. Validate in SQL that generated chunks account for the source blob lengths;
   abort the transaction on any mismatch.
5. `DROP TABLE recovery_points; ALTER TABLE recovery_points_v3 RENAME TO
   recovery_points; PRAGMA user_version = 3;`

No `Cursor` is created, so the `CursorWindow` is never involved and the
poisoned row migrates. `INSERT INTO recovery_points_v3` copies
`format_version` and `payload_checksum` byte-for-byte: both stay logical
format 2. Since the checksum includes `format_version`, not changing either
field is the checksum-invariance contract (CW-AC-05). New schema-3 rows also
write logical format 2. Crash mid-migration rolls the transaction back →
schema-2 store → incompatible → fail-closed; the next start retries.

`recovery_manifest_chunks` deliberately has no foreign key. Android SQLite
does not provide foreign-key enablement as an implicit store invariant, and
the migration temporarily owns both old and new tables. Referential integrity
is instead enforced by the single child-first deletion primitive and by
migration/deletion orphan checks.

### Data flow

Checkpoint: encode manifest → bound check → CREATING transaction (retention
plan + record-row insert + `PRE`/`INTENDED` chunk sets) → close/reopen →
`readRecord` assembles slots, verifies chunk shape/length + payload checksum,
then decodes manifests →
`READY` advance + read-back → snapshot publication (projected reads).
Failures map exactly as today (`StoreUnavailable`/`PointIdCollision`/
`CreateFailed`/`ValidateFailed`); the oversized row can no longer make the
read-back throw.

The new internal seam is explicit rather than lazy `StoredRecord` construction:

```text
listReconciliationCandidates() -> List<CandidateMetadata | MalformedCandidate>
loadRecord(CandidateMetadata) -> Readable(StoredRecord) | Unreadable(metadata, cause)
quarantineUnmutated(pointId, expectedLifecycle = CREATING | READY) -> Boolean
```

`CandidateMetadata` contains only bounded columns: point/run IDs, lifecycle
and prior lifecycle, timestamps, logical format version, and
pre/intended/reviewed digests used by authoritative classification. It never
contains manifest bytes. `quarantineUnmutated` rechecks point ID + expected
lifecycle in the same transaction before writing the tombstone and deleting
children/parent, so metadata enumeration cannot create a time-of-check/time-of-
use delete race. A malformed candidate is not loadable or deletable.

Reconciliation then enumerates bounded metadata candidates → loads exactly one
candidate → branch on `Readable`/`Unreadable` → reconcile or contain it →
continue with the next candidate → run retention → publish the inspection
snapshot. An unreadable active candidate contributes an existing unresolved
public result and remains durably unchanged; it does not prevent healthy
candidates from reaching their normal outcomes, but the aggregate summary
remains fail-closed.

No diagnostics shape is added. Bounded digests still provide the existing
authoritative classification. A safely quarantined candidate emits the
existing `RESTART_RECONCILED` shape with resulting lifecycle projected as
`CORRUPT`; a preserved unreadable candidate projects its unchanged lifecycle.
The inspection snapshot remains the storage truth: `QUARANTINED` tombstone for
the former, checksum-invalid record for the latter. Detailed reason surfacing
remains #172.

### Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Projection-only reads, no schema change (read each blob column in its own query) | Works at the observed 2×1.12 MB scale but fails again as soon as one manifest blob alone exceeds 2 MB (larger workspaces), and cannot read an already-poisoned schema-2 row at all. Leaves the same defect class open. |
| File-backed manifests (digests in the row, bytes in app-private files) | Splits the checkpoint across DB + file with no cross-store transaction; ADR-0003's "each lifecycle transition is one transaction" and the crash-classification contract would need a new durability protocol. Higher risk than a second table in the same DB. |
| Deliberate record-size cap ("diagnosable failure" above N MB) | Explicitly rejected by the issue's acceptance criteria; the ≥2.25 MB checkpoint must succeed. |
| Deduplicating `pre`/`intended` slots while identical | Logical record and codec/checksum order keep both slots; dedup would change the codec contract for no capacity need. Storage duplication (2× manifest at CREATING) is the existing semantic. |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `store/RecoveryDbSchema.kt` | Physical schema 3 DDL, schema-version constant, chunk constants, slot enum | Schema ownership is centralized here today |
| `store/RecoveryManifestChunks.kt` (new, internal) | Chunk write/assemble/rewrite SQL + bound | Keeps chunking out of the protocol; pure store-internal |
| `store/RecoveryStore.kt` | Projected reads, chunk-aware transactions, schema-2→3 migration, child-first deletes, session load/quarantine implementation | All writer/reader authority already lives here behind `RecoveryStorePort` |
| `store/RecoveryDbVersionGate.kt` / `RecoveryDbHelper.kt` | Probe/open by physical `SCHEMA_VERSION`; accept the 2→3 pre-open migration decision | Version transitions are gate decisions, not `onUpgrade` |
| `protocol/Ports.kt` | Reconciliation metadata/load-result seam; `TombstoneReason.QUARANTINED` | Internal session and typed reasons are declared here |
| `protocol/RestartReconciler.kt` | Point-level load loop and lifecycle-sensitive containment | Reconciliation loop is the only caller that may quarantine |
| `protocol/RecoveryProtocol.kt` / `RecoveryPreviewProtocol.kt` | Map `QUARANTINED` to existing public `CORRUPT` rejection | Exhaustive typed mapping without a new public result |
| `store/RecoveryRecordCodec.kt` / `lifecycle/LifecycleReconciler.kt` | Logical `RECORD_FORMAT_VERSION = 2` independent of schema version; checksum-invariance tests | Codec/lifecycle own logical compatibility, not physical DDL |
| `store/RecoveryInspectionSnapshotCodec.kt` | Canonical tombstone reason 6 round-trip | Snapshot is the SQLite-free inspection authority |
| `tests/unit` + `tests/organizer-instrumentation` | New codec/migration/containment/orphan/oversized tests; fake session adaptation | JVM contracts; real-SQLite evidence on emulator |
| `docs/adr/0009-chunk-recovery-manifests.md` | Persistent storage/version/containment decision | Meets the repository ADR threshold |

No Launcher3/AOSP file is touched (no bridge note required).

## Migration and recovery

- Physical schema 2 → 3: pre-open, transactional, server-side (above).
  Eligibility probe
  is read-only; a partially failed migration leaves schema 2 fail-closed
  (retryable next start). The #171 device's poisoned store is recovered by
  this path plus the existing `CREATING` reconciliation classification.
- Schema 3 → schema 2 downgrade: existing gate → `INCOMPATIBLE_VERSION`, read-only,
  non-restorable, Launcher layout untouched (ADR-0003).
- Unreadable `CREATING`/`READY`: the durable state and transition ordering
  prove the Launcher mutation has not begun. The session atomically writes a
  `QUARANTINED` tombstone, deletes chunks child-first, and deletes the point.
- Unreadable `APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING`: preserve lifecycle,
  row, and all remaining chunks; surface unresolved and leave later organizer
  mutation fail-closed. Never advance these to `CORRUPT` merely because full
  assembly is impossible: doing so would make retention eligible to erase the
  only in-flight recovery evidence.
- Unreadable `VERIFIED`: preserve as a recovery point record, project
  `checksumValid = false`, and reject recovery/preview through existing
  `CORRUPT` semantics. Existing retention may expire it at the normal time;
  corruption does not trigger early deletion.
- Invalid projected metadata: preserve without mutation because lifecycle
  safety cannot be proven; continue independently identifiable candidates and
  return aggregate `Failed` after that pass.
- Release rollback (revert PR): schema-3 stores on a reverted build are
  incompatible fail-closed — same policy as today's downgrade; the Launcher
  layout is unaffected. Backup/restore behavior unchanged.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| CW-AC-02 (repro then fix) | Instrumentation test: ≥2.25 MB logical record (fixture mirrors the 125-row icon-bearing manifest scale) through the production writer; run pre-fix on the investigation branch to record the `SQLiteBlobTooBigException`/`CHECKPOINT_CREATE_FAILED` reproduction, then post-fix asserts `Ready` + close/reopen validation | `connectedLawnWithQuickstepGithubDebugAndroidTest` (emulator, real SQLite) |
| CW-AC-03 | Source-level assertion (no `SELECT *` over `recovery_points`) + store tests with oversized records across checkpoint/apply/restore/retention/snapshot | Instrumentation suite + spotless |
| CW-AC-04 | Lifecycle matrix for missing/corrupt chunks: `CREATING`/`READY` quarantine; active states preserved + unresolved; `VERIFIED` preserved/non-restorable; malformed metadata preserved; healthy candidates continue; snapshot representation and transaction rollback | Instrumentation + `RestartReconcilerTest`/session fake tests (JVM) |
| CW-AC-05 | Migration tests: poisoned schema-2 store (raw-inserted >2 MB row) migrates server-side; `user_version` becomes 3 while logical row/tombstone format stays 2; checksum bytes unchanged and valid; fault injection leaves schema 2 fail-closed | Instrumentation (real SQLite) |
| CW-AC-06 | Downgrade probe test; existing backup-exclusion tests untouched and green | Instrumentation + unit |
| CW-AC-07 | Compile/test adaptation for the internal session seam and tombstone reason; public apply/recover result behavior unchanged | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| CW-AC-10 | Child-first deletion tests for retention, prune, quarantine, every tombstone reason, and migration; fault injection rolls back parent+children; orphan-count query is zero after commit | Instrumentation (real SQLite) |
| CW-AC-08 | Manual device-class verification on the #171-class device: organize → exactly one Nova restore → organize → A4 passes → `APPLY_VERIFIED`; evidence (diagnostics export, no layout content) recorded in the PR/audit | Real device (Pixel 9a-class) |
| CW-AC-09 | Labels, CI `final-status` (incl. focused instrumentation jobs), independent audit `docs/assessment/pr-<PR>-<slug>.md` performed by a separate session | CI + audit process per AGENTS.md |

Baseline gates: `./gradlew spotlessCheck`,
`./gradlew assembleLawnWithQuickstepGithubDebug`.

## Documentation updates

- [x] spec.md / plan.md (this Stage A draft)
- [x] `docs/adr/0009-chunk-recovery-manifests.md` — proposed decision for
  physical schema 3, logical-format separation, chunk ownership, migration,
  and lifecycle-sensitive containment.
- [ ] `DESIGN.md` — §7 recovery-point persistence references ADR-0003; no
  structural change required (verify at merge).
- [ ] `CONTEXT.md` — no domain-language change.
- [ ] `docs/engineering/organizer-diagnostics.md` — only if the quarantine
  classification needs a named value in the reconciliation event contract.

## Execution checklist

- [ ] Reproduce the failure at contract level (oversized record, real SQLite) on `main`.
- [ ] Codec: chunk assembly + schema/record-version separation and checksum-invariance tests (JVM) failing first.
- [ ] Schema-3 DDL + chunk collaborator; child-first delete primitive; store transaction paths; projected reads.
- [ ] Schema-2→3 pre-open migration + version-gate decision; migration tests incl. poisoned store, unchanged logical format/checksum, orphan check, and fault injection.
- [ ] Metadata enumeration + point-level load-result session seam; adapt fakes/tests.
- [ ] Lifecycle-sensitive containment matrix + transaction/retention/orphan tests.
- [ ] Full relevant verification (unit + instrumentation) green; existing suites unchanged.
- [ ] Device-class verification (CW-AC-08) executed and evidenced.
- [ ] PR: spec status update, evidence table, high-risk independent audit.
