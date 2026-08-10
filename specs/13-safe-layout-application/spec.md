---
issue: "#13"
status: accepted
requirements:
  - FR-004
  - FR-005
  - NFR-001
  - NFR-002
  - NFR-007
  - NFR-011
  - NFR-012
updated: 2026-08-10
---

# Safe layout application and recovery contract

## Problem

A validated organization plan must become the current home layout without a
stale plan, partial write, lost item, or false success. The baseline Deck path
copies live database files, waits on fixed delays, and restarts the process; it
does not supply the revision, atomicity, or verification guarantees required by
`DESIGN.md` §5 and the home-layout safety rules in `AGENTS.md`.

## Outcome

The Layout Application module keeps a small public interface:

```text
apply(ValidatedLayoutPlan) -> ApplyResult
recover(RecoveryRequest) -> RecoveryResult
```

Behind it, the module rejects stale input before mutation, creates a durable
verified recovery point, applies one preconditioned layout transaction, reloads
the model, and independently verifies the result. Recovery is bound to the
current layout revision that the user reviewed and applies an explicit
row-accounted transaction; it never replaces the Favorites table wholesale.

## Scope

- Observable request, result, ordering, failure, restart, and retention rules
  for `apply` and `recover`.
- The meaning of Issue #10 `RevisionId` at the application seam.
- Durable recovery intent and process-death reconciliation.
- Exact item accounting, lock/profile preservation, and DB/model convergence.
- The storage decision in
  [ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md).
- A production persistence adapter and a test persistence adapter exercising
  the same public module interface, as required by `DESIGN.md` §4.2.

## Non-goals

- Planning, classification, rule evaluation, snapshot capture, or plan UI.
- The concrete persistence adapter interface, SQL schema, hashing algorithm,
  ID allocator, locking primitive, or failure-injection implementation. These
  belong to the later implementation plan.
- Lock storage and migration implementation. ADR-0004 defines the resource;
  missing, unknown, or corrupt lock state fails closed through this contract.
- Empty-folder deletion policy, owned by Issue #24. Apply v1 has no deletion
  action. Recovery deletions are explicit reversal actions, not organizer
  policy decisions.
- Deck removal/coexistence, diagnostics field encoding, or performance budgets.

## Public contract

### Apply input

`ValidatedLayoutPlan` is a platform-free application artifact constructed from
the exact `OrganizationInput` and its `PlanningResult.Planned`. It contains:

| Field | Contract |
|---|---|
| `sourceRevision: RevisionId` | Equals the source snapshot revision and the planner-echoed revision. |
| `sourceState` | Canonical complete pre-apply layout state for every captured item and relevant persistent resource. |
| `intendedState` | Canonical complete post-apply layout state obtained by materializing all planned placements and candidates. |
| `actions` | Exactly one `Preserve`, `Update`, or `Insert` action per represented item. No `Delete` in apply v1. |
| `newPages` | Plan-local page ordinals and orders. Pages have no database row at baseline; a page exists through item screen references. |
| `newFolders` | Plan-local folder ordinals, profile, placement, and exact member order. |
| `ruleVersion`, `taxonomyVersion` | Planner-echoed versions used for traceability, not re-evaluation. |

Every source item, including a preserved item, carries an **exact canonical
expected state**. There are no nullable wildcard fields and no `NoCheck`
precondition. An insert carries an exact expected-absence identity and complete
new item state. The artifact contains no Android, Launcher row, cursor, or SQL
type.

### Revision semantics

`RevisionId` remains the opaque type defined by Issue #10. At capture and
application it must change whenever any persistent input that can affect the
write or verification changes, including:

- every captured item field needed to reproduce or validate the item;
- complete target identity and profile identity/availability;
- workspace placement, span, rank, ordered pages, and device capabilities;
- folder/app-pair identity, exact membership, stage, and snap metadata;
- widget provider, widget ID, bind/restore state, and profile;
- lock state and its ownership metadata; and
- the full profile inventory, including addition/removal of a profile with no
  current Favorites rows.

`LauncherModel.getLastLoadId()` alone cannot meet this contract: it tracks
loader generations, not all database or device-state mutations.

### Recovery input

```text
RecoveryRequest {
    pointId: RecoveryPointId
    expectedCurrentRevision: RevisionId
}
```

`expectedCurrentRevision` is the revision shown by the recovery preview and
confirmed by the user. If current state differs at the recovery transaction,
recovery returns `STALE_REVISION` without mutation; a fresh preview and request
are required. This is why `DESIGN.md` uses `RecoveryRequest`, not a bare ID.

### Results

```text
ApplyResult =
  | NoChanges { runId }
  | Applied { runId, pointId }
  | Rejected { runId, reason: PreWriteRejection }
  | RolledBack { runId, failure: ApplyFailure }
  | Recovered { runId, pointId, failure: ApplyFailure }
  | Unresolved { runId, pointId, failure: ApplyFailure,
                 authoritativeState: AuthoritativeState }
  | RecoveryFailed { runId, pointId, failure: ApplyFailure,
                     recoveryFailure: RecoveryFailure,
                     authoritativeState: AuthoritativeState }
  | ConcurrentRun

PreWriteRejection =
  | INVALID_PLAN | STALE_REVISION | EXACT_PRECONDITION_FAILED
  | LOCK_STATE_UNAVAILABLE | IDENTITY_EXHAUSTED
  | CHECKPOINT_CREATE_FAILED | CHECKPOINT_VALIDATE_FAILED
  | RECOVERY_STORE_UNAVAILABLE | WRITER_BUSY

ApplyFailure =
  | WRITE_FAILED | COMMIT_OUTCOME_UNKNOWN
  | MODEL_RELOAD_FAILED | VERIFICATION_FAILED
  | RECOVERY_STORE_FAILED

RecoveryResult =
  | Restored { pointId }
  | NotRestorable { pointId, reason: RecoveryRejection }
  | RestoreFailed { pointId, failure: RecoveryFailure,
                    authoritativeState: AuthoritativeState }
  | WriterBusy
  | ConcurrentRun

RecoveryRejection =
  | MISSING | EXPIRED | CORRUPT | INCOMPATIBLE_VERSION
  | STALE_REVISION | LOCK_STATE_UNAVAILABLE | ALREADY_RESTORED

RecoveryFailure =
  | WRITE_FAILED | COMMIT_OUTCOME_UNKNOWN
  | MODEL_RELOAD_FAILED | VERIFICATION_FAILED
  | RECOVERY_STORE_FAILED

AuthoritativeState =
  | PRE_APPLY_DB_AND_MODEL | PRE_APPLY_DB_MODEL_UNVERIFIED
  | POST_APPLY_DB_AND_MODEL | POST_APPLY_DB_MODEL_UNVERIFIED
  | REVIEWED_CURRENT_DB_AND_MODEL
  | REVIEWED_CURRENT_DB_MODEL_UNVERIFIED | UNKNOWN
```

All variants are typed and localizable by code. Raw exception messages,
revisions, row contents, package names, coordinates, and profile identifiers
are not developer diagnostics by default. Issue #16 owns diagnostic encoding.

Cancellation is not part of v1. A later cancellation contract must change the
request and result types rather than adding an undocumented side channel.

## Recovery record and lifecycle

ADR-0003 places recovery records in a separate app-private, versioned database.
A record contains enough data to recover and to reconcile a crash without the
in-memory plan:

| Data | Purpose |
|---|---|
| Recovery format version, ID, run ID, creation time | Discovery and compatibility. |
| Complete canonical pre-state resource manifest | Row-accounted restoration, including every resource required by #23. |
| Pre-state `RevisionId` and integrity digest | Detect unchanged/rolled-back layout and checkpoint corruption. |
| Complete intended canonical post-state or equivalent lossless write intent | Reconstruct intended placements after process death. |
| Intended post-state digest and action-set digest | Distinguish exact post-state from an unrecognized state. |
| During recovery, the reviewed current-state manifest/digest, complete recovery action-set digest, and prior lifecycle state | Reconcile a death or uncertain commit while `RESTORING` without relying on process memory. |
| Row/resource count and checksum | Read-after-write validation. |
| Lifecycle state | Total restart reconciliation. |

Lifecycle states are:

```text
CREATING -> READY -> APPLYING -> COMMITTED_UNVERIFIED -> VERIFIED
                         |                |                 |
                         |                +----recover------+
                         +--pre-state---------------------> READY
READY | APPLYING | COMMITTED_UNVERIFIED | VERIFIED -> RESTORING -> RESTORED
VERIFIED --retention elapsed--> EXPIRED
checksum failure -> CORRUPT
unsupported version -> INCOMPATIBLE
```

- `CREATING` is never eligible for apply or restore. On restart, a committed
  `CREATING` record is read-back validated: a complete record whose Launcher
  state is still the stored pre-state becomes unused `READY` and is pruned;
  an incomplete record becomes `CORRUPT` and its payload is quarantined or
  deleted without touching Launcher state.
- `READY` means the checkpoint committed and passed read-after-write
  validation; the Launcher layout still matches the pre-state. A `READY`
  record that is not immediately advanced to `APPLYING` is an unused
  checkpoint and is pruned during reconciliation, not offered as user recovery.
- Before the layout transaction, the record is durably marked `APPLYING` and
  already contains complete post intent.
- `COMMITTED_UNVERIFIED` means the layout transaction is known to have
  committed but model convergence/invariants are not yet accepted.
- `VERIFIED` remains restorable during retention.
- `RESTORING` plus the stored pre/current intent makes recovery commit ambiguity
  reconcilable. `RESTORED` cannot be restored again.
- A failed lifecycle update never silently becomes success. Before layout
  commit it is a pre-write rejection. After commit it triggers recovery when
  the checkpoint remains readable; if the durable state still cannot be
  advanced, apply returns `Unresolved` or `RecoveryFailed` with the
  authoritative layout state. Recovery returns `RestoreFailed` with
  `RECOVERY_STORE_FAILED`.

The recovery DB lifecycle update and Launcher DB commit are intentionally not a
distributed transaction. If a process dies between them, reconciliation uses
the authoritative Launcher state and the stored pre/post digests.

### Retention

- A verified recovery point remains available for 24 hours
  (`86,400,000 ms`) from creation.
- At most three non-expired points are retained.
- Expiry/count cleanup and creation of the replacement point are one recovery
  DB transaction, so a failed creation does not discard the prior usable set.
- If all three retained points are unresolved and therefore ineligible for
  cleanup, a new apply is rejected with `RECOVERY_STORE_UNAVAILABLE`; no point
  or Launcher state is changed.
- Cleanup is lazy on discovery or checkpoint creation; no alarm or background
  thread is required.
- `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING` records are never expired
  by age or count. They are reconciled before cleanup; cleanup never erases the
  only recovery data for an unresolved mutation.
- `READY` is pruned after checkpoint-only/rolled-back reconciliation.
  `CORRUPT` and `INCOMPATIBLE` payloads are not counted as usable points and
  are safely quarantined or deleted. Their metadata, plus `RESTORED` and
  `EXPIRED` metadata, remains as a tombstone for a further 24 hours so requests
  return the exact typed reason; tombstones do not count toward the maximum
  three.

## Apply protocol

The module serializes organization application/recovery operations across all
instances and participates in the Launcher layout-writer serialization selected
by the implementation plan. Even with that serialization, correctness never
depends on an early check alone.

| Phase | Normative behavior | Failure result |
|---|---|---|
| A0 | Reject a concurrent apply/recover. | `ConcurrentRun` |
| A1 | Validate the artifact, materialize the canonical intended state, and derive unique plan-local page/folder IDs without persistent allocation. | `Rejected(INVALID_PLAN/IDENTITY_EXHAUSTED)` |
| A2 | Acquire layout-writer serialization and compare the complete current revision and every exact source/absence precondition. | `Rejected(WRITER_BUSY/STALE_REVISION/EXACT_PRECONDITION_FAILED/LOCK_STATE_UNAVAILABLE)` |
| A3 | If the materialized action set has no mutation, return `NoChanges`; do not write either DB, create a point, or reload. | `NoChanges` |
| A4 | Read every checkpoint resource through one consistent source snapshot, require its digest to equal the plan source state, commit the complete recovery record in its own recovery-DB transaction, then read it back and validate version/count/digest. | `Rejected(STALE_REVISION/CHECKPOINT_*)` |
| A5 | Mark the record `APPLYING` with post intent. Start one Launcher DB write transaction; **inside that transaction and after its writer lock is effective**, re-read the full current `RevisionId` and every exact precondition before the first mutation. | stale/precondition failure leaves Launcher DB unchanged and returns `Rejected`; the unused checkpoint is pruned, or left `READY` for restart reconciliation if cleanup is interrupted |
| A6 | Execute inserts and updates, resolve references using the unique plan-local mapping, and finish the transaction. No page row and no apply deletion exist. | Outcome classified by authoritative re-read; see below |
| A7 | Persist `COMMITTED_UNVERIFIED`, request a new model load, and wait for the generation caused by this request. Compare that model snapshot with an independent DB recapture. | Automatic recovery on reload/convergence failure |
| A8 | Verify the intended state and `DESIGN.md` §5 invariants; mark `VERIFIED` and return `Applied`. | Automatic recovery on mismatch |

The full A5 recheck catches a profile, page/device, lock, widget, membership, or
unrelated row change occurring after A2/A3. It is not replaced by selective
placement checks or by an in-process mutex.

New pages have no baseline database record. Their unique screen IDs are only
referenced by committed item rows. Multiple pages/folders in one plan receive
distinct, deterministic, in-range IDs. Failed preflight or rollback leaves no
externally visible reservation; allocator mechanics remain private.

### Transaction outcome classification

The baseline transaction helper marks success before `close/endTransaction`,
where durability is resolved. Therefore no exception location is assumed to
prove commit or rollback. After any write/close error or unknown return path,
the module re-reads the authoritative Launcher state:

| Current state | Outcome |
|---|---|
| Exact stored pre-state | Classify not committed, prune the unused record, and return `RolledBack`. No recovery write is performed. If pruning is interrupted, `READY` reconciliation completes it later. |
| Exact stored intended post-state | Continue model reload and verification as a committed apply. |
| Neither pre nor intended post | Attempt recovery. Return `Recovered` only after pre-state DB/model verification; otherwise `RecoveryFailed` with truthful `AuthoritativeState`. |

A normal write failure whose transaction rolled back is never reported as a
recovery. A model reload, DB/model convergence, or invariant failure after
commit always attempts recovery before returning.

## Restart reconciliation

Before accepting a new apply/recover operation, the module reconciles every
unresolved `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING` record:

| Launcher state | Required action |
|---|---|
| Complete `CREATING` record + exact pre-state | Validate it, classify unused `READY`, then prune without a layout write. |
| Incomplete/corrupt `CREATING` record | Mark/quarantine `CORRUPT`; never use it for apply or recovery. |
| Exact pre-state | For interrupted apply, classify as not committed and prune the unused `READY` record; for interrupted recovery, complete `RESTORED`. No layout write. |
| Exact intended post-state | Reload the model and run post-apply verification; mark `VERIFIED` on success, otherwise recover. |
| Exact recovery target during `RESTORING` | Reload/verify pre-state and mark `RESTORED`. |
| Exact stored reviewed recovery source during `RESTORING` | Recovery did not commit. Restore the recorded prior lifecycle and either resume the persisted recovery intent or surface an interrupted recovery; do not claim `Restored`. |
| Neither recognized state | Run stale-safe recovery using the record; if recovery cannot complete, preserve the record and surface an unresolved failure. |

Because the complete pre-state and post intent are persisted before A5,
reconciliation does not need the former process's memory. A checkpoint-only
death (`READY`) causes no automatic restore because Launcher DB is unchanged;
reconciliation validates and prunes it before accepting another operation.

## Recovery protocol

Recovery is an explicit, revision-bound application operation:

1. Reject missing, expired, corrupt, incompatible, or `RESTORED` records with
   the matching typed `RecoveryRejection`.
2. Acquire the same layout-writer serialization used by apply. Contention with
   an external layout writer returns `WriterBusy`; no recovery state changes.
3. Start a Launcher DB write transaction. Inside it, re-read the full current
   `RevisionId`; it must equal `RecoveryRequest.expectedCurrentRevision`.
4. Compare the current canonical resource manifest with the checkpoint and
   compute a complete recovery write-set. Every current item/resource is
   explicitly preserved, updated, inserted, or deleted. Deletion is allowed
   only because the confirmed recovery request accounts for that exact current
   revision; it is not an Issue #24 organizer deletion.
5. Attach an exact current-state precondition to every action, including
   preserved rows. Validate folder/app-pair/widget/profile/lock references and
   bounds/overlap before the first write.
6. Persist the reviewed current-state manifest/digest and complete recovery
   action-set digest, mark the record `RESTORING`, apply the write-set
   atomically, then classify an uncertain transaction by the stored recovery
   target/current-state digests.
7. Request a correlated model reload and independently verify exact pre-state
   DB/model convergence and all invariants. Only then mark `RESTORED` and return
   `Restored`.

No unconditional table delete, raw DB copy, delay, or process restart is part
of recovery. If DB restoration commits but model reload/verification fails,
the result is `RestoreFailed` with `PRE_APPLY_DB_MODEL_UNVERIFIED`, not a false
success. The point remains unresolved and discoverable.

## Scenario matrix

| ID | Scenario | Required observable result/state |
|---|---|---|
| SA-01 | Valid mutating plan | `Applied`; intended DB/model state; point `VERIFIED`. |
| SA-02 | Valid empty diff | `NoChanges`; no Launcher/recovery write, point, or reload. |
| SA-03 | Any revision dimension changes before A2 | `Rejected(STALE_REVISION)`; no checkpoint/write. Includes empty profile, widget metadata, app-pair membership, page/device, and lock changes. |
| SA-04 | Change occurs after A2/checkpoint but before A5 | Full A5 revision/precondition recheck rejects; no Launcher mutation. |
| SA-05 | Checkpoint create/read-back/version/checksum failure | Typed checkpoint rejection; no Launcher mutation; prior usable points retained. |
| SA-06 | Multiple page/folder IDs or ID exhaustion | IDs unique and rollback-invisible, or `IDENTITY_EXHAUSTED`; no partial state. |
| SA-07 | Nth write failure and authoritative state is pre-state | `RolledBack`; no recovery write; unused point is pruned or left `READY` for restart cleanup. |
| SA-08 | Commit/close outcome unknown, state is exact post-state | Continue reload/verification; final `Applied`, `Recovered`, or `RecoveryFailed`. |
| SA-09 | Commit/close outcome unknown, state is neither | Recovery attempted; never `Applied` without exact verification. |
| SA-10 | Model reload or DB/model convergence fails | Recovery attempted; truthful recovered/unresolved result. |
| SA-11 | Post-apply invariant/intended-state mismatch | Recovery attempted; truthful recovered/unresolved result. |
| SA-12 | Death after checkpoint, before layout write | Record `READY`, Launcher pre-state; restart validates and prunes it without a layout write. |
| SA-13 | Death during/around layout commit | Restart classifies pre/post/neither from persisted intent and acts accordingly. |
| SA-14 | Death after layout commit, before verification | Restart verifies exact post-state or recovers; no new operation runs first. |
| SA-15 | Lock metadata missing/unreadable | Apply returns `Rejected(LOCK_STATE_UNAVAILABLE)`; explicit recovery returns `NotRestorable(LOCK_STATE_UNAVAILABLE)`; no checkpoint/write. |
| SA-16 | Explicit recovery with matching reviewed revision | Complete row-accounted recovery; `Restored` only after DB/model verification. |
| SA-17 | Current revision differs from reviewed revision | `NotRestorable(STALE_REVISION)`; no mutation; new preview required. |
| SA-18 | Row created after checkpoint | It is preserved or explicitly deleted according to the confirmed recovery write-set; never silently lost. |
| SA-19 | Recovery commit rolls back or dies before commit | Exact reviewed-current state is reported/reconciled; never misreported as pre/post/unknown. |
| SA-20 | Recovery store/lifecycle update fails before or after Launcher commit | Pre-write rejection, `Unresolved`, `RecoveryFailed`, or `RestoreFailed` according to phase; never false success. |
| SA-21 | Repeat recovery | `NotRestorable(ALREADY_RESTORED)`; no mutation. |
| SA-22 | Missing/expired/corrupt/incompatible point | Matching typed rejection; no Launcher mutation. |
| SA-23 | Concurrent module call | `ConcurrentRun`; never partial mutation. |
| SA-24 | External layout writer lease is busy | Apply returns `Rejected(WRITER_BUSY)`; recover returns `WriterBusy`; neither creates a checkpoint or mutation. |
| SA-25 | Retention limit/expiry during unresolved operation | Unresolved point retained and reconciled before cleanup. |

## Post-apply and post-recovery verification

Verification uses a fresh Launcher DB recapture and a model snapshot from the
specific reload requested after commit. It proves:

- exact equality with the intended apply state or checkpoint recovery state;
- conservation and one outcome per represented item;
- bounds, no overlap, and referential integrity;
- unchanged lock placement/occupancy and profile identity;
- exact folder/app-pair membership and widget identity/bind state; and
- byte-equivalent canonical DB/model representations for all layout resources.

The invariants themselves remain authoritative in `DESIGN.md` §5.

## Data, migration, backup, and privacy

- Current layout ownership remains with the Launcher DB.
- Recovery ownership follows ADR-0003's separate private database with its own
  format version. It does not change Launcher `SCHEMA_VERSION`.
- Lawnchair ZIP backup and Android full backup exclude the recovery DB because
  both baseline paths allowlist named Launcher files. Contract tests must prove
  that a backup/restore cycle contains no recovery record.
- An incompatible recovery format is rejected without touching Launcher DB.
  Downgrade may discard recovery records but never changes the current layout.
- The recovery manifest covers every persistent resource required to restore
  the run. ADR-0004 defines the tri-state `favorites` lock resource included in
  its row manifests and exact preconditions.
- No permission, network access, external storage, or external telemetry is
  introduced. Diagnostics use only privacy-safe typed categories and counts.

## Acceptance criteria and evidence

| AC | Acceptance criterion | Required evidence |
|---|---|---|
| AC-1 | Stale/full-precondition checks cover every revision dimension at A2 and again inside A5/recovery transaction. | Public-seam contract tests for each dimension and the A2→A5 mutation race. |
| AC-2 | Checkpoint commits/validates before layout mutation and survives layout rollback/death. | Separate recovery/Launcher DB tests with checkpoint, Nth-write, and process-death faults. |
| AC-3 | Empty diff is `NoChanges` with zero persistent writes and no reload. | Write/reload counters through the public seam. |
| AC-4 | Apply transaction is all-or-nothing; ordinary rollback is `RolledBack`, not recovery. | Nth-write and transaction-close tests with authoritative DB comparison. |
| AC-5 | Pre/post/neither and all lifecycle states reconcile without in-memory plan state. | Reopen both DBs after every crash boundary and assert state/result. |
| AC-6 | Model reload is correlated; DB/model/intended state and all DESIGN invariants are verified. | Reload-generation and independent-recapture tests. |
| AC-7 | Any post-commit reload/verification failure attempts recovery and never reports false success. | Failure injection at reload, recapture, verification, recovery write, recovery reload, and recovery verify. |
| AC-8 | Recovery binds to the reviewed current revision and accounts for every current/checkpoint resource with exact preconditions. | Unchanged and subsequently changed layouts, including added rows and container references. |
| AC-9 | Multiple new pages/folders are unique and overflow-safe; rollback exposes no reservation; no page row is written. | Boundary fixtures through `apply`. |
| AC-10 | Lock state missing is fail-closed with the seam-specific typed result; profiles, widgets, folders, and app pairs round-trip exactly. | Apply/recovery negative lock fixtures plus resource matrix. |
| AC-11 | Recovery DB is absent from ZIP/Android backup and incompatible versions do not touch layout. | Backup-content inspection plus upgrade/downgrade tests. |
| AC-12 | 24-hour/max-three retention is atomic and never removes an unresolved point. | Clock-controlled lifecycle tests. |
| AC-13 | Production and failure-injection persistence adapters are exercised only through the same Layout Application interface. | Shared contract suite; no internal helper mocking. |
| AC-14 | Every recovery-store/lifecycle write failure and `CREATING` crash boundary has a typed, restart-reconcilable outcome. | Fail each lifecycle transition before/after commit, reopen both DBs, and assert result/state. |
| AC-15 | Recovery rollback preserves and reports the exact reviewed-current state; writer contention is typed as `WRITER_BUSY`/`WriterBusy` on the respective public result. | Public-seam rollback, restart, and external-writer contention tests. |

## Downstream gates

- ADR-0004 defines capture, revision, checkpoint, and restore semantics for lock
  state; Issue #14 must implement them with the complete resource manifest.
- Issue #24 is required only before a future organizer plan can delete an empty
  folder. It does not block explicit row-accounted recovery.
- Issue #16 owns diagnostic field encoding, retention, and export.

## Open questions

No open product, persistence, or interface choice remains. Hashing, SQL schema,
writer-lock implementation, and diff algorithms are implementation details for
the later plan and must satisfy this contract.

## Baseline evidence

Source observations are fixed to
`505dbc40e6154c05158b5d0271c45f6a885a411b`:

| Evidence | Baseline source |
|---|---|
| Transaction success is marked before `close/endTransaction` resolves durability. | `LauncherDbUtils.SQLiteTransaction`, `src/com/android/launcher3/provider/LauncherDbUtils.java` |
| `getLastLoadId()` is loader generation, not layout revision. | `LauncherModel`, `src/com/android/launcher3/LauncherModel.java` |
| Item IDs use mutable in-memory max state; screen ID queries current max. | `DatabaseHelper`, `src/com/android/launcher3/model/DatabaseHelper.java` |
| Schema 32 has no current workspace-screens table. | `DatabaseHelper` migration 27 |
| Lawnchair ZIP copies the complete active Launcher DB file. | `LawnchairBackup.getFiles/create`, `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` |
| Android full backup allowlists named Launcher DB files. | `res/xml/backupscheme.xml` |
| Same-DB table copy is unversioned reference material only. | `GridBackupTable`, `src/com/android/launcher3/model/GridBackupTable.java` |
| Raw DB copy, fixed delay, and restart are negative evidence. | `LawndeckManager`, `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` |

## Change history

- 2026-08-10: Drafted the safe application/recovery contract for Issue #13.
- 2026-08-10: Corrected storage to a separate private recovery DB, made
  pre/post intent and lifecycle total, added full in-transaction revision
  rechecks, row-accounted stale-safe recovery, truthful commit/reload outcomes,
  and a no-write empty-diff result.
