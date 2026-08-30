---
issue: "#174"
status: draft
updated: 2026-08-30
---

# Plan: chunked recovery-manifest storage (format 3) and per-record reconciliation degradation

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
- `RecoveryDbSchema.kt`: `DDL_FORMAT_2`, `FORMAT_VERSION = 2`,
  `CHECKSUM_COLUMNS` (codec order; manifests are columns 8, 11, 14 in the
  checksum stream).
- `RecoveryDbHelper.kt`: `onUpgrade` throws — every version transition is a
  pre-open decision in `RecoveryDbVersionGate` + `availability()`
  (`migrateEmptyLegacyStore()` at `RecoveryStore.kt:L109` is the v1
  empty-store precedent, including the pre-probe read-only eligibility
  check L156).
- `RecoveryRecordCodec.kt`: `computePayloadChecksum` is a tagged SHA-256
  over the logical record (format tag, then every column with length
  prefixes) — representation-independent, so a chunked physical layout can
  keep migrated checksums valid.
- `RestartReconciler.kt` (L150+): per-record classification from
  `checksumValid`, `formatVersion`, and digests; `CREATING` +
  `PRE_STATE` → `READY` + prune (`SilentPrune`); checksum-invalid →
  `CORRUPT`. Classification needs no manifest bytes.
- Test surfaces: `tests/unit` is pure JVM with `FakeRecoveryStore` (port
  unchanged ⇒ unaffected); real-SQLite store evidence lives in
  `tests/organizer-instrumentation` (wired as the app module `androidTest`
  source set, run by the focused CI instrumentation jobs and locally via
  connectedAndroidTest on an emulator — the 2 MB `CursorWindow` is only
  enforced on real Android SQLite).

Hypothesis vs confirmed: the failure chain above is confirmed by device
instrumentation; the fix strategy below is a design decision (Stage A).

## Design

### Strategy (decision)

Move the three manifest blobs out of the `recovery_points` row into a
chunked side table (format 3), normalize every read to projected columns +
per-record chunk assembly, migrate v2 stores server-side, and add a
reconciliation quarantine path. `RecoveryStorePort`, the codec's logical
record/checksum, lifecycle, retention, fence, and diagnostics semantics are
unchanged.

### Modules and interfaces

| Unit | Change |
|---|---|
| `RecoveryDbSchema` | `FORMAT_VERSION = 3`; `DDL_FORMAT_3` (rebuilt `recovery_points` without the three manifest blob columns; new `recovery_manifest_chunks`); slot/chunk constants (slot `PRE`/`INTENDED`/`REVIEWED`; chunk size 512 KiB; per-manifest engineering bound, plan value 64 MiB = 128 chunks). `CHECKSUM_COLUMNS` unchanged (logical order). |
| `RecoveryRecordCodec` | `Encoded` gains no fields; manifest byte arrays are populated by the store from chunk assembly. Checksum computation untouched. |
| `RecoveryStore` (private) | New internal collaborator (e.g. `RecoveryManifestChunks.kt`): write slot chunk sets (`INSERT` per chunk), assemble slot bytes (`SELECT chunk … ORDER BY chunk_index`), delete/rewrite slots — all called inside the existing transactions. `readRecord`/`readEncoded` read the projected row, then assemble required slots and verify the slot digests. `insertRecordRow`/`writeMutableRecordColumns` drop blob puts and gain chunk-set writes in the same transaction. Bound check before insert (`CreateFailed` fail-closed beyond 64 MiB). |
| `RecoveryStore` (reads) | `listNonFinalRecords` and `publishCurrentProjection` switch to a projected small-column query. `publishCurrentProjection` computes `checksumValid` per record as payload-checksum validity + slot-digest match; a record whose assembly fails projects `checksumValid = false` and does not abort publication. Reconciliation listing returns metadata records with lazily-assembled manifests; per-record assembly failure is contained. |
| `RecoveryStore.availability()` | v2 file on a format-3 build: new pre-open migration decision (v1 precedent, L109/L156) — eligibility probe read-only, then one read-write transaction performing the server-side migration, then re-probe → `READY`. Runs identically under startup reconciliation (`startupAvailability` → `Existing` → `availability()`), which is the poisoned-store recovery path. |
| `RecoveryStoreReconciliationSession` / `RestartReconciler` | Per-record containment: any record that cannot be read/assembled/classified is quarantined — one transaction writing a `QUARANTINED` tombstone and deleting the row by `point_id` without reading the row body — then iteration continues with the remaining records. Readable-but-checksum-invalid records keep the existing `CORRUPT` path. |
| `RecoveryStorePort` | `TombstoneReason` gains `QUARANTINED` (canonical int 6; tombstone codec + `writeTombstone` mapping). No other port change. |

### Server-side v2 → v3 migration (no `Cursor` involvement)

Inside one migration transaction on a raw `OPEN_READWRITE` handle (the
helper is not opened for an incompatible store):

1. `CREATE TABLE recovery_points_v3 …` (format-3 columns) and
   `CREATE TABLE recovery_manifest_chunks …`.
2. `INSERT INTO recovery_points_v3 SELECT <small columns> FROM recovery_points`.
3. Chunk each manifest server-side with a recursive CTE over `length()`/
   `substr()` — `WITH RECURSIVE cnt(i) AS (…) INSERT INTO
   recovery_manifest_chunks SELECT point_id, <slot>, i, substr(<col>, (i-1)*L+1, L)
   FROM recovery_points, cnt WHERE (i-1)*L < length(<col>)` — once per slot
   (`pre_manifest`→`PRE`, `intended_manifest`→`INTENDED`,
   `reviewed_manifest`→`REVIEWED` where `NOT NULL`). `substr()` over blobs
   and recursive CTEs exist in every supported Android SQLite (≥3.8.3).
4. `DROP TABLE recovery_points; ALTER TABLE recovery_points_v3 RENAME TO
   recovery_points; PRAGMA user_version = 3;`

No `Cursor` is created, so the `CursorWindow` is never involved and the
poisoned row migrates. Logical bytes are unchanged, so existing
`payload_checksum` values remain valid (CW-AC-05). Crash mid-migration
rolls the transaction back → v2 store → incompatible → fail-closed; the
next start retries.

### Data flow

Checkpoint: encode manifest → bound check → CREATING transaction (retention
plan + record-row insert + `PRE`/`INTENDED` chunk sets) → close/reopen →
`readRecord` assembles slots, verifies slot digests + payload checksum →
`READY` advance + read-back → snapshot publication (projected reads).
Failures map exactly as today (`StoreUnavailable`/`PointIdCollision`/
`CreateFailed`/`ValidateFailed`); the oversized row can no longer make the
read-back throw.

### Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Projection-only reads, no schema change (read each blob column in its own query) | Works at the observed 2×1.12 MB scale but fails again as soon as one manifest blob alone exceeds 2 MB (larger workspaces), and cannot read an already-poisoned v2 row at all. Leaves the same defect class open. |
| File-backed manifests (digests in the row, bytes in app-private files) | Splits the checkpoint across DB + file with no cross-store transaction; ADR-0003's "each lifecycle transition is one transaction" and the crash-classification contract would need a new durability protocol. Higher risk than a second table in the same DB. |
| Deliberate record-size cap ("diagnosable failure" above N MB) | Explicitly rejected by the issue's acceptance criteria; the ≥2.25 MB checkpoint must succeed. |
| Deduplicating `pre`/`intended` slots while identical | Logical record and codec/checksum order keep both slots; dedup would change the codec contract for no capacity need. Storage duplication (2× manifest at CREATING) is the existing semantic. |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `store/RecoveryDbSchema.kt` | Format 3 DDL, chunk constants, slot enum | Schema ownership is centralized here today |
| `store/RecoveryManifestChunks.kt` (new, internal) | Chunk write/assemble/rewrite SQL + bound | Keeps chunking out of the protocol; pure store-internal |
| `store/RecoveryStore.kt` | Projected reads, chunk-aware transactions, v2→v3 migration, quarantine | All writer/reader authority already lives here behind `RecoveryStorePort` |
| `store/RecoveryDbVersionGate` | Accept the 2→3 pre-open migration decision | Version transitions are gate decisions, not `onUpgrade` |
| `protocol/RecoveryStorePort.kt` | `TombstoneReason.QUARANTINED` | Typed tombstone reasons are port-owned |
| `protocol/RestartReconciler.kt` | Per-record containment + quarantine classification | Reconciliation loop is the only caller that may quarantine |
| `store/RecoveryRecordCodec.kt` | No logical change; tests for checksum invariance | Checksum is representation-independent |
| `tests/unit` + `tests/organizer-instrumentation` | New codec/migration/quarantine/oversized tests | JVM codec; real-SQLite contract evidence on emulator |

No Launcher3/AOSP file is touched (no bridge note required).

## Migration and recovery

- v2 → v3: pre-open, transactional, server-side (above). Eligibility probe
  is read-only; a partially failed migration leaves v2 fail-closed
  (retryable next start). The #171 device's poisoned store is recovered by
  this path plus the existing `CREATING` reconciliation classification.
- v3 → v2 downgrade: existing gate → `INCOMPATIBLE_VERSION`, read-only,
  non-restorable, Launcher layout untouched (ADR-0003).
- Quarantined records: tombstoned with the typed reason and expired by the
  existing retention; a quarantined checkpoint is one whose layout was
  never mutated (checkpoint precedes layout), so quarantine never loses a
  restorable state that the protocol could otherwise honor.
- Release rollback (revert PR): format-3 stores on a reverted build are
  incompatible fail-closed — same policy as today's downgrade; the Launcher
  layout is unaffected. Backup/restore behavior unchanged.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| CW-AC-02 (repro then fix) | Instrumentation test: ≥2.25 MB logical record (fixture mirrors the 125-row icon-bearing manifest scale) through the production writer; run pre-fix on the investigation branch to record the `SQLiteBlobTooBigException`/`CHECKPOINT_CREATE_FAILED` reproduction, then post-fix asserts `Ready` + close/reopen validation | `connectedLawnWithQuickstepGithubDebugAndroidTest` (emulator, real SQLite) |
| CW-AC-03 | Source-level assertion (no `SELECT *` over `recovery_points`) + store tests with oversized records across checkpoint/apply/restore/retention/snapshot | Instrumentation suite + spotless |
| CW-AC-04 | Quarantine tests (missing/corrupt chunks; fault-injected transaction rollback; healthy record still reconciled; snapshot publishes with `checksumValid=false`) | Instrumentation + `RecoveryStoreReconciliationSessionTest` (JVM) |
| CW-AC-05 | Migration tests: poisoned v2 store (raw-inserted >2 MB row) migrates server-side, checksums valid, per-record reconciliation of migrated store; fault injection leaves v2 fail-closed | Instrumentation (real SQLite) |
| CW-AC-06 | Downgrade probe test; existing backup-exclusion tests untouched and green | Instrumentation + unit |
| CW-AC-07 | Existing JVM protocol/fence/retention suites pass unchanged | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| CW-AC-08 | Manual device-class verification on the #171-class device: organize → exactly one Nova restore → organize → A4 passes → `APPLY_VERIFIED`; evidence (diagnostics export, no layout content) recorded in the PR/audit | Real device (Pixel 9a-class) |
| CW-AC-09 | Labels, CI `final-status` (incl. focused instrumentation jobs), independent audit `docs/assessment/pr-<PR>-<slug>.md` performed by a separate session | CI + audit process per AGENTS.md |

Baseline gates: `./gradlew spotlessCheck`,
`./gradlew assembleLawnWithQuickstepGithubDebug`.

## Documentation updates

- [x] spec.md / plan.md (this Stage A draft)
- [ ] `docs/adr/` — not required: the storage location (ADR-0003) is
  unchanged; the representation change is recorded in this spec. If
  review elevates the chunked-representation decision to ADR rank, add
  ADR-0009 instead of expanding this spec.
- [ ] `DESIGN.md` — §7 recovery-point persistence references ADR-0003; no
  structural change required (verify at merge).
- [ ] `CONTEXT.md` — no domain-language change.
- [ ] `docs/engineering/organizer-diagnostics.md` — only if the quarantine
  classification needs a named value in the reconciliation event contract.

## Execution checklist

- [ ] Reproduce the failure at contract level (oversized record, real SQLite) on `main`.
- [ ] Codec: chunk assembly + checksum-invariance tests (JVM) failing first.
- [ ] Format-3 schema + chunk collaborator; store transaction paths; projected reads.
- [ ] v2→v3 pre-open migration + version-gate decision; migration tests incl. poisoned store + fault injection.
- [ ] Quarantine path + reconciler containment; quarantine tests incl. fault injection.
- [ ] Full relevant verification (unit + instrumentation) green; existing suites unchanged.
- [ ] Device-class verification (CW-AC-08) executed and evidenced.
- [ ] PR: spec status update, evidence table, high-risk independent audit.
