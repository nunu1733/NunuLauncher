---
issue: "#89"
status: draft
requirements:
  - FR-004
  - FR-005
  - FR-006
  - NFR-001
  - NFR-002
  - NFR-007
  - NFR-011
risk:
  - layout-data
updated: 2026-08-19
---

# Inspection-safe recovery-store read for WAL databases

## Problem

Issue #84 must inspect a recovery point before explicit confirmation without writing the recovery store, its SQLite sidecars, the Launcher database, lifecycle state, or diagnostics. Its paused implementation correctly avoids `SQLiteOpenHelper` for absent databases, but its version probe and inspection query still open an existing recovery store through Android `SQLiteDatabase.OPEN_READONLY`.

The production recovery store enables WAL. SQLite documents that a read-only WAL connection is supported only when readable `-wal` and `-shm` sidecars already exist, the containing directory permits their creation, or the connection is immutable. SQL read-only access is therefore not a portable physical no-write guarantee for the recovery main database and its sidecars.[1] The Android public database bridge maps its public open flags to read-write, create, or read-only native flags; it does not provide an application-level `SQLITE_OPEN_URI` path required to rely on SQLite URI parameters such as `immutable=1`.[2] [3]

The accepted #84 contract, especially RP-AC-04, is not weakened. A valid production-created WAL recovery database must either be inspected without opening the live SQLite store or inspection must fail closed before touching it. This specification selects the former strategy and makes every uncertain relationship between the authoritative database and a derived snapshot unavailable.

## Outcome and decision

Recovery storage will own a **durable, app-private, SQLite-free inspection snapshot** and a private **generation/health fence**. A writer first invalidates the fence, completes its existing authoritative mutation and read-back validation, then publishes a complete snapshot and marks the same generation valid. The #84 inspection path reads only a snapshot whose generation is valid in that fence; it never opens the recovery database, its `-wal`, or its `-shm` file.

> An **inspection snapshot** is a complete, non-authoritative, app-private projection of recovery-point and tombstone metadata sufficient to classify one requested recovery point without reading SQLite. It contains no manifest, payload, revision, digest, layout identity, row encoding, or diagnostic payload. It is not a backup, recovery point, mutation log, or a new public application contract.

The live recovery database remains authoritative for checkpoint creation, lifecycle mutation, retention, restart reconciliation, and confirmation-time recovery. A snapshot result never authorizes recovery. Explicit confirmation continues through the unchanged application-level recovery behavior from #84 and Spec 13, which reopens the authoritative store and rechecks readiness, retention, revision, and exact preconditions before any lifecycle or layout mutation.

| Decision | Normative consequence |
|---|---|
| **Selected strategy:** writer-published inspection snapshot | Inspection opens no recovery SQLite file; it reads one validated final snapshot only. |
| **Generation/health fence** | A snapshot can classify successfully only while the private fence is `VALID(generation)` and the decoded final envelope carries that exact generation. |
| **No stale success** | Once a writer starts, the fence is `DIRTY`; the shared `RunMutex` makes inspection `Concurrent` rather than allowing a prior snapshot to produce a successful preview. |
| Live recovery database remains authoritative | Snapshot trust is recreated only after authoritative validation. Confirmation still revalidates the live store before mutation. |
| No bundled/native SQLite reader | The change introduces no JNI library, ABI matrix, SQLite release stream, or native security-update obligation. |
| No Android URI/immutable assumption | `file:...?immutable=1` is not passed through Android public `SQLiteDatabase` as a supported application contract. |
| Fail closed on uncertainty | Unknown, dirty, missing, invalid, incomplete, unreadable, unpublished, or untrusted snapshots return typed store-unavailable without opening SQLite. |

## Scope

This specification owns the recovery-storage strategy required by #84. It includes the snapshot envelope, generation/health fence, writer publication and startup rehydration rules, inspection read boundary, supported environment, failure mapping, migration/rollback/backup behavior, diagnostics/privacy boundaries, concurrency semantics, and the physical no-write oracle.

The scope is intentionally below the #84 application seam. It does not change `LayoutApplicationModule` public mutation contracts, the #84 closed preview result types, `RecoveryRequest`, `RecoveryResult`, recovery lifecycle semantics, retention policy, writer lease ownership, or Launcher layout application. The storage owner may add private types and focused tests only as described in the accepted implementation plan.

## Supported environment and publication primitive

The inspection reader is supported in Lawnchair's declared Android API range, **API 26 through API 35**, compiled against API 36.1, when it runs in the app's default single launcher process and uses the app-private internal directory returned by `Context.noBackupFilesDir`.[4] This strategy does not support a second application process, shared/external storage, user-selected directories, or a filesystem abstraction that bypasses `Context.noBackupFilesDir`. The existing `RunMutex` is the required mutual-exclusion mechanism; the pinned AndroidX `AtomicFile` explicitly does not provide locking.[5]

Publication uses exactly `androidx.core.util.AtomicFile(File(snapshotDirectory, "recovery-inspection.v1"))`, supplied by the already pinned `androidx.core:core-ktx:1.17.0` dependency. AndroidX documents this library artifact as creating the write file at the base path plus `.new`, then closing, syncing, and committing it on `finishWrite()`; this is the single publication protocol across the app's API 26–35 support range.[5] `snapshotDirectory` is created once below `noBackupFilesDir`. New writes use only the base and same-directory `.new` names. The physical oracle also inventories a legacy `.bak` name because AndroidX reads a backup created by an older implementation, but no Stage B writer may create or rename through `.bak`. Stage B must not use the platform AtomicFile, a second staging directory, cross-directory move, `Files.move`, copy-and-delete fallback, or raw `renameTo` path.

Parent-directory durability is **not** a prerequisite for a `VALID` fence transition. The public `AtomicFile` contract promises a completely written and synced file before rename, but does not specify an app-portable parent-directory `fsync` guarantee.[5] The strategy deliberately does not infer crash-persistent directory metadata from an unavailable portability guarantee. A process death, app restart, or any uncertain publication leaves the in-memory fence `UNKNOWN`, so an on-disk final file—even if checksum-valid—is unusable until startup reconciliation validates the authoritative recovery store and republishes it. Failure of `startWrite`, write, `finishWrite`, final-file validation, or any required local filesystem operation is a publication failure and leaves the fence `DIRTY`/unavailable; there is no best-effort success path.

| Environment or primitive condition | Required behavior |
|---|---|
| API 26–35, default app process, private no-backup directory, same-directory pinned AndroidX `AtomicFile` | Supported. The fixed base/`.new` publication and inspection contracts apply. |
| Restart, process death, power-loss recovery, or unobserved filesystem durability state | The new process starts `UNKNOWN`; it must reconcile and rebuild before inspection can classify any point. |
| A second process or an external/shared/user-selected filesystem path | Unsupported. Do not publish or inspect; return typed unavailable and record no snapshot success. |
| AndroidX `AtomicFile` write/finish/validation failure or non-regular final file | Publication failure; keep the fence unavailable, and do not use a prior final snapshot. |

The physical no-write oracle uses the following closed set. New publication can write only the base file and its same-directory `.new` companion. `.bak` is not a writer protocol; it is recorded only to make legacy AndroidX backup recovery observable, and any new `.bak` created by this strategy or any inspection-time change is a failure.

| File family | Covered files | Inspection requirement |
|---|---|---|
| Authoritative recovery SQLite store | `organizer_recovery.db` | Must not be opened, created, renamed, truncated, deleted, or otherwise changed. |
| SQLite WAL sidecars | `organizer_recovery.db-wal` and `organizer_recovery.db-shm` | Must not be opened for write, created, removed, renamed, resized, or otherwise changed. |
| AndroidX snapshot final | `recovery-inspection.v1` | May be opened read-only only; its bytes, size, and modification/change timestamps must remain unchanged. |
| AndroidX write companion | `recovery-inspection.v1.new` | May be created only by a writer; inspection must not create, remove, rename, or change it. |
| Legacy AndroidX compatibility name | `recovery-inspection.v1.bak` | No Stage B writer may create it; inspection must not create, remove, rename, or change it. |

## Snapshot data and compatibility

The snapshot is a versioned binary envelope with deterministic canonical encoding and an integrity checksum over the complete envelope. Its internal schema is private to recovery storage. A valid envelope contains only the following projection:

| Projection field | Purpose | Explicitly excluded |
|---|---|---|
| snapshot envelope format and private runtime generation | Identify one complete in-process publication and reject an envelope that is not trusted by the current fence. | SQLite page data, schema DDL, or a database copy. |
| authoritative recovery-store format compatibility value | Distinguish a trusted projection of an incompatible recovery record/store from an unreadable snapshot. | Any SQLite page, schema, or version-probe result exposed to UI. |
| recovery point ID, lifecycle, creation/update time, checksum-valid classification, and record-format classification | Classify missing, expired, corrupt, incompatible, unresolved, restorable, and already-restored outcomes. | Manifest, raw row data, revision, digest, payload, item, package, profile, coordinate, or title. |
| tombstone point ID, typed reason, expiry time, and compatibility value | Return precise retained tombstone outcomes without lazy cleanup. | Tombstone payload, database row, or SQL exception text. |
| whole-envelope checksum and strict length/count bounds | Detect partial, corrupted, oversized, or non-canonical snapshot input. | Any automatic repair or silent fallback to SQLite. |

A malformed, truncated, checksum-invalid, unreadable, oversized, duplicate/non-canonical, unsupported-old, or future/unknown **snapshot envelope** is not trusted and maps to `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. A trusted envelope may map to `NotRestorable(INCOMPATIBLE_VERSION)` only when its validated projection explicitly states that the authoritative recovery-store format or selected record format is incompatible. Snapshot-codec incompatibility must never be represented as recovery-point incompatibility.

Logical retention remains unchanged. The inspection protocol evaluates `RetentionPolicy.actionFor()` with its injected `Clock` over trusted snapshot timestamps. An expired `VERIFIED` record returns `NotRestorable(EXPIRED)` and a tombstone whose retention deadline has passed returns `NotRestorable(MISSING)` without editing either the snapshot or the live store.

## Generation and health fence

`InspectionSnapshotFence` is a private storage/application collaboration; it is never exposed through `RecoveryStorePort`, preview results, UI, diagnostics, or a persistence record. Its generation is an in-process, strictly increasing value. It does not attempt to survive restart because survival would make an old snapshot appear authoritative without reopening and validating the live store.

| Fence state | Entry condition | Inspection behavior | Exit condition |
|---|---|---|---|
| `UNKNOWN` | New process, no successful reconciliation, or a reset after an untrusted condition. | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`; the snapshot file is not parsed as a success source. | Startup reconciliation successfully validates the authoritative store and publishes a current snapshot. |
| `DIRTY(generation)` | Before any operation that can mutate the authoritative recovery store, and after a committed, outcome-uncertain, read-back-failed, or publication-failed mutation. | No successful classification. A concurrent inspection cannot acquire `RunMutex`; any later inspection is unavailable. | Only full authoritative validation plus successful final-snapshot publication for the same generation. A `PROVEN_NO_COMMIT` attempt immediately restores its pre-attempt valid generation. |
| `VALID(generation)` | Existing mutation-specific read-back/reconciliation validation succeeded, `AtomicFile.finishWrite()` succeeded, the final envelope revalidates, and envelope generation equals the fence generation. | The sole state permitted to classify from the snapshot. | Next writer begins and atomically marks `DIRTY(nextGeneration)`. |
| `INCOMPATIBLE` | Legal startup/writer validation establishes that the authoritative recovery-store format is incompatible. | `NotRestorable(INCOMPATIBLE_VERSION)` without reading SQLite during inspection. | A new compatible process/reconciliation establishes `VALID`; otherwise remain unavailable/incompatible. |

Every recovery-store mutation path—checkpoint creation, lifecycle advance, `markApplying`, `markRestoring`, pruning/tombstoning, retention, and restart reconciliation—must participate in the fence while holding the shared module `RunMutex`. The writer marks `DIRTY(nextGeneration)` before it can touch authoritative state. It may mark `VALID(nextGeneration)` only after both the mutation's established authoritative trust point and successful snapshot publication have completed.

Each attempted mutation has one private fence outcome: `PROVEN_NO_COMMIT`, `COMMITTED`, or `OUTCOME_UNCERTAIN`. `PROVEN_NO_COMMIT` is permitted only when the storage owner proves that no authoritative transaction was made successful and no authoritative bytes/state changed. For an ordinary mutation—which can enter only from `VALID(previousGeneration)`—that outcome performs no snapshot publication and immediately restores the exact pre-attempt `VALID(previousGeneration)` fence. For a reconciliation-session mutation starting from `UNKNOWN` or `DIRTY`, it preserves that non-success state; it never invents `VALID`. `CheckpointResult.PointIdCollision` is `PROVEN_NO_COMMIT`: the primary-key constraint occurs before `setTransactionSuccessful()`, and all retention/tombstone work in that same transaction is rolled back. A retry with a new point ID under the existing apply/run mutex therefore remains legal. Every committed, close/reopen-read-back-failed, exception-after-commit, ambiguous transaction-close, or snapshot publication/final-validation failure is `COMMITTED` or `OUTCOME_UNCERTAIN` and must keep `DIRTY`; it may never restore a prior generation.

`DIRTY` is the runtime snapshot-health invalidation contract that the startup-only `ReadinessGate` does not currently represent. Ordinary checkpoint/lifecycle/retention callers receive only `RecoveryStorePort` and reject before SQLite while the fence is dirty or unknown.

The source-level authority boundary is fixed as follows. `ApplyProtocol`, `RecoveryProtocol`, and #84 preview receive only `RunMutexPort { tryAcquire, release }`; the concrete `RunMutex` instance is private to `LayoutApplicationModule` and its `withStartupReconciliation { lease -> ... }` issuer is deliberately absent from that port. Within the same private module composition, the module constructs one `RecoveryStoreReconciliationSession` from that lease and passes it as a method-scoped argument to `RestartReconciler.reconcileAll(session)`. `RestartReconciler` receives no issuer/factory and must not retain the session. The session has a private constructor, is identity-bound to the active lease, is closed in `finally` before the lease releases, and is checked by every privileged store operation. Its methods expose only the existing legal reconciliation operations plus snapshot rebuild; the session is not constructible, retrievable, or passable from UI/coordinator, apply, recover, preview, or a normal `RecoveryStorePort` caller. No boolean bypass, public flag, downcast, or ordinary protocol constructor may expose the issuer. Only this active session may replace a dirty/unknown fence with a valid generation.

For `checkpoint()`, one generation covers the whole `CREATING → READY` sequence. A snapshot created after the intermediate `CREATING` transaction is never valid for inspection; the fence becomes valid only after the required `READY` close/reopen read-back succeeds. For lifecycle changes that already use `updateRecord()` read-back, snapshot projection occurs after that close/reopen validation. Retention, tombstone, and prune paths must add an equivalent close/reopen projection read-back before publication; transaction success alone is insufficient.

If an authoritative transaction commits but the snapshot cannot be published or revalidated, the fence stays `DIRTY`. The old final snapshot may remain on disk but it is unusable: a later inspection reads `Unavailable`, never `MISSING`, a stale rejection, or a restorable result from it. The fence itself is the runtime health latch: it is checked under `RunMutex` by preview and every ordinary recovery-store mutation entry, so it closes the transition race even though `ReadinessGate` only models startup. The only route back to success is an active `RecoveryStoreReconciliationSession` issued by `LayoutApplicationModule.reconcileAtStart()`, which revalidates the live store and publishes a new matching generation; an ordinary later apply/recover/retention operation cannot heal dirty state.

## Publication, startup, and authority

Recovery storage publishes only while it owns the existing recovery-store writer path and the same shared concrete `RunMutex` that protects apply, recover, preview, and restart reconciliation. The reconciliation-only path may publish only through its active `RecoveryStoreReconciliationSession`; the session rejects after the module-owned startup reconciliation scope closes. Publication is never initiated by inspection, UI, coordinator, a background task, diagnostics, or an ordinary `RecoveryStorePort` caller while the fence is dirty/unknown.

For an operation generation, the writer derives a complete post-validation inspection projection in memory, validates canonical ordering, bounds, and checksum, and uses `AtomicFile.startWrite()`/`finishWrite()` to publish the final file. A `PROVEN_NO_COMMIT` attempt starts no snapshot publication and restores the prior valid generation; all other failed/ambiguous attempts call `failWrite()` if a write stream exists and remain dirty. A post-commit publication failure preserves the authoritative database for reconciliation, keeps the fence dirty, and must not be compensated by deleting or rolling back a committed recovery record outside the established recovery protocol.

At process construction the fence is `UNKNOWN`, regardless of the existence, timestamp, generation, or checksum of a final snapshot file. `LayoutApplicationModule.reconcileAtStart()` enters its private `withStartupReconciliation { session -> ... }` scope, opens the private `RecoveryStoreReconciliationSession`, and runs the existing legal recovery reconciliation and retention work through that session. It then rebuilds, validates, and publishes a snapshot while the same session and concrete mutex ownership remain active. It transitions the fence to `VALID` only after this succeeds. If authoritative recovery storage is absent/corrupt/unreadable, or validation/publish fails, readiness is failed and preview is unavailable. A checksum-valid residual snapshot must never produce a successful preview in those cases. If the legal authoritative availability check instead establishes a compatible-to-report but unsupported recovery-store format, reconciliation records private `INCOMPATIBLE` without parsing any residual snapshot and permits #84's existing typed `NotRestorable(INCOMPATIBLE_VERSION)` path; apply/recover remain protected by their ordinary authoritative store availability checks and cannot write.

## Inspection and concurrency semantics

The #84 read-only protocol retains its readiness gate, `RunMutexPort`, and non-blocking writer lease for one authoritative Launcher current-state capture. Its I2 recovery-store read is replaced by one private `readInspectionProjection(pointId)` call. That call checks `VALID(generation)` while holding `RunMutexPort`, then performs a read-only directory inventory. It succeeds only when the snapshot directory contains exactly the expected final base `recovery-inspection.v1`: a present `.new`, `.bak`, or any unexpected snapshot-directory entry is uncertain publication state and maps to `Unavailable(RECOVERY_STORE_UNAVAILABLE)`.

For a clean inventory, inspection opens **only the exact final base file** using `FileInputStream(File(snapshotDirectory, "recovery-inspection.v1"))`, performs a bounded read/parse/validate, closes it, and verifies the matching envelope generation before classification.[10] It must not call AndroidX `AtomicFile.openRead()` or `readFully()`, and it must not open, recover, rename, delete, clean up, or fall back to `.new`, `.bak`, or another directory entry. AndroidX `AtomicFile` remains writer/reconciliation-only; direct `FileInputStream` is the exclusive inspection file-open primitive.

All application-managed writer/apply/recover/reconciliation paths acquire the same `RunMutex` before setting the fence dirty or mutating recovery storage. An inspection that races such an operation fails `Concurrent` at mutex acquisition; it never reads a previous snapshot. An inspection that runs after a committed publication failure observes `DIRTY` and returns `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. A `WriterBusy` result remains reserved for #84's later capture-only Launcher writer lease contention after a valid snapshot classification.

| Situation | Inspection behavior | Persistent effect |
|---|---|---|
| `VALID(generation)` and valid unexpired record | Classify from the matching snapshot, then run the existing capture-only lease behavior. | None. |
| Apply/recover/reconciliation/publication writer holds `RunMutex` | Existing `Concurrent`. | None. |
| A writer has committed but snapshot publication/read-back failed | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None during inspection; runtime health remains latched dirty. |
| `UNKNOWN` at startup or after a process restart | `Unavailable(RECOVERY_STORE_UNAVAILABLE)` or existing reconciliation-pending result before protocol entry. | None. |
| `INCOMPATIBLE` from authoritative validation | `NotRestorable(INCOMPATIBLE_VERSION)`. | None. |
| Snapshot envelope missing, corrupt, partial, unreadable, unsupported, or generation-mismatched | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None. |
| `.new`, `.bak`, or any unexpected snapshot-directory entry at inspection entry | `Unavailable(RECOVERY_STORE_UNAVAILABLE)` without opening the final file. | None; inspection performs no recovery, rename, delete, or cleanup. |
| #84 capture-only writer lease is held | Existing `WriterBusy`. | None. |

Inspection must not call `SQLiteDatabase`, `SQLiteOpenHelper`, `RecoveryDbVersionGate.probe()`, `AtomicFile.openRead()`, `AtomicFile.readFully()`, `checkpoint`, lifecycle transition methods, retention/prune methods, writer snapshot publication, model reload, layout mutation, recovery authorization, or diagnostics projection. It must not recover, rename, delete, or clean any snapshot entry. This restriction applies equally to successful, missing, invalid, unsupported, busy, concurrent, and exception paths.

## Behavior scenarios

### Valid production-created WAL store with sidecars present

Given a recovery point created through the production recovery writer, a fence at `VALID(generation)`, a matching valid snapshot, and an existing recovery database with readable `-wal` and `-shm` sidecars,

When #84 inspects the point,

Then it classifies only the matching snapshot and performs the existing non-blocking current-layout capture protocol,

And the main database, `-wal`, `-shm`, snapshot final file, and snapshot-directory inventory remain physically unchanged.

### Valid production-created WAL store in closed state

Given a recovery point created through the production recovery writer, a `VALID(generation)` matching snapshot, a valid closed WAL-mode main database, and absent sidecars,

When #84 inspects the point,

Then it reads only the snapshot and produces its closed preview result,

And no `-wal` or `-shm` file is created and no authoritative recovery file is touched.

### Post-commit snapshot publication failure

Given that an authoritative lifecycle transaction has passed its required database commit but snapshot publication or final-file validation fails,

When #84 inspection runs after the writer releases `RunMutex`,

Then the fence is `DIRTY` and it returns `Unavailable(RECOVERY_STORE_UNAVAILABLE)`, even if an older checksum-valid final snapshot contains or omits the requested point,

And only successful reconciliation plus republish may restore `VALID`.

### Snapshot unavailable, invalid, or format-unknown

Given that the snapshot is absent, incomplete, corrupted, unreadable, exceeds a bound, has an unsupported encoding, fails its checksum, or does not match the current fence generation,

When #84 inspection runs,

Then it returns `Unavailable(RECOVERY_STORE_UNAVAILABLE)` without trying the live database, repairing the snapshot, invoking `SQLiteOpenHelper`, or emitting a diagnostic event.

### Residual valid snapshot but authoritative store absent, corrupt, or incompatible

Given an application restart with a checksum-valid snapshot file left from an earlier process but the authoritative recovery database is absent, corrupt, unreadable, or incompatible,

When startup reconciliation runs,

Then it does not set `VALID` from that residual file; absent/corrupt/unreadable storage returns typed unavailable and a verified authoritative incompatibility returns typed incompatible-version,

And #84 inspection cannot produce a success preview from the residual snapshot.

### Writer concurrency

Given an application-managed apply, recover, or reconciliation writer begins,

When #84 inspection races it,

Then the writer already owns `RunMutex` and inspection returns `Concurrent` without reading a prior snapshot,

And when the writer completes, inspection can classify only a newly published matching generation or returns unavailable.

## Migration, downgrade, rollback, backup, and recovery

The strategy adds a no-backup derived artifact and does not alter the recovery database schema, recovery record codec, lifecycle values, retention durations, Launcher schema, backup format, or public recovery contracts. Existing compatible recovery records are reprojected during startup reconciliation after upgrade; while the initial projection is absent or the fence is unknown, inspection fails closed. An unsupported snapshot encoding is unavailable, not a claim that the recovery point is incompatible.

The existing recovery database is excluded from Lawnchair ZIP and Android full backup by ADR-0003. The snapshot is additionally outside the existing `res/xml/backupscheme.xml` allowlist and resides in the no-backup directory. Backup or restore therefore does not create a trusted inspection snapshot. After a restore, inspection is unavailable until ordinary startup reconciliation has a compatible authoritative recovery store from which to build a matching projection. This does not change backup semantics or write the Launcher layout.

A rollback that removes this strategy leaves the live recovery database and Launcher layout untouched. It may leave an ignored snapshot artifact, which a later compatible writer may replace during normal reconciliation. No downgrade or rollback path may make an inspection attempt delete, migrate, or modify the authoritative store.

## Privacy, diagnostics, and security

The snapshot remains app-private, no-backup, and inaccessible to UI/coordinator consumers. It stores only opaque recovery point IDs and typed metadata essential to #84 classification. It stores no recovery manifest, layout data, revision, digest, package/component/title, profile, coordinate, raw SQL exception, or free-form diagnostic data.

Inspection remains diagnostics-silent and creates no synthetic recovery lifecycle. Publication and startup rebuild are storage-writer work, not inspection; they may use only diagnostics already permitted for the underlying recovery operation and must not log projection contents. This design adds no permission, network behavior, telemetry, external storage, JNI dependency, or native SQLite update surface.

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| IS-AC-01 | `spec.md` and `plan.md` select the app-private inspection-snapshot strategy, retain #84's application-owned preview/confirmation contracts, and state the private `UNKNOWN`/`DIRTY`/`VALID(generation)`/`INCOMPATIBLE` fence. |
| IS-AC-02 | Every #84 inspection result first requires a clean snapshot-directory inventory, then reads only the exact final base through bounded direct `FileInputStream`; it never invokes AndroidX `AtomicFile.openRead()`/`readFully`, Android SQLite, `SQLiteOpenHelper`, `RecoveryDbVersionGate`, a live-store query, lifecycle/retention action, snapshot recovery/cleanup, layout mutation, model reload, recovery authorization, or diagnostics projection. |
| IS-AC-02a | An inspection-time `.new`, `.bak`, or unexpected snapshot-directory entry returns typed unavailable before the final file is opened. Inspection does not recover, rename, delete, clean up, or otherwise modify any entry. |
| IS-AC-03 | Every recovery-store mutation marks the fence dirty before authoritative work, and only an established mutation-specific close/reopen/read-back validation plus successful final snapshot publication can establish `VALID(generation)`. A storage-proven `PROVEN_NO_COMMIT` ordinary attempt restores its exact previous valid generation; all committed or outcome-uncertain attempts stay dirty. While dirty/unknown, ordinary mutation entries reject before SQLite; only reconciliation rehydration may establish a new valid generation. |
| IS-AC-03a | `CheckpointResult.PointIdCollision` is a proven-no-commit outcome: retrying with a newly generated point ID in the existing apply run remains legal and can reach `Ready`; it must not require reconciliation or expose a stale successful preview. |
| IS-AC-04 | A post-commit publication/read-back failure remains dirty and returns typed unavailable; no previous snapshot can yield `MISSING`, a restorable result, or another successful classification before reconciliation republishes. |
| IS-AC-05 | A production-created valid WAL recovery store with `-wal` and `-shm` present is inspectable through a valid matching snapshot with zero physical change to the main DB, both sidecars, final snapshot, and every snapshot companion file. |
| IS-AC-06 | A production-created valid closed WAL recovery store with sidecars absent is inspectable through a valid matching snapshot without creating either sidecar or modifying any covered file. |
| IS-AC-07 | Publication uses the pinned AndroidX `AtomicFile` in one `noBackupFilesDir` subdirectory with its documented base/`.new`/final protocol across API 26–35. New writers never create `.bak`; the oracle inventories it only for legacy read compatibility. There is no platform-AtomicFile, cross-directory, raw-rename, or copy fallback. Unsupported process/filesystem conditions or any publication failure are unavailable. Process restart starts unknown and requires rehydration; parent-directory durability is not assumed. |
| IS-AC-08 | Snapshot-envelope parse/codec incompatibility maps to typed unavailable. Only a trusted projection that explicitly represents authoritative recovery/record incompatibility maps to `NotRestorable(INCOMPATIBLE_VERSION)`. |
| IS-AC-09 | Shared concrete `RunMutex` serializes inspection with apply, recover, publication, and reconciliation: concurrent writer inspection is `Concurrent`, never a stale successful preview; capture-lease contention remains `WriterBusy`. Ordinary protocols receive only `RunMutexPort`; `LayoutApplicationModule` alone retains the startup-session issuer and passes a method-scoped private session to `RestartReconciler`, which is the sole dirty/unknown bypass. |
| IS-AC-10 | Residual checksum-valid snapshots cannot produce a successful preview when startup authoritative storage is absent, corrupt, unreadable, or incompatible. Reconciliation must validate/rebuild before opening the success path; verified authoritative incompatibility uses the existing typed incompatible path without decoding the residual snapshot. |
| IS-AC-11 | The physical oracle records pre/post existence, SHA-256 digest or byte content, size, and defined modification/change timestamps for every covered file, plus a complete inventory of the dedicated snapshot directory. Any difference fails the test. |
| IS-AC-12 | Snapshot migration, app upgrade/downgrade, startup rehydration, backup/restore, and rollback preserve the existing recovery/Launcher contracts. No snapshot condition changes the Launcher layout; unsupported or unrebuildable state fails closed. |
| IS-AC-13 | Snapshot contents and public values do not expose a manifest, payload, row, revision, digest, item/profile identity, raw exception, reconciliation session, or recovery internals. Inspection emits no recovery lifecycle diagnostic. |
| IS-AC-14 | Focused JVM and Android instrumentation evidence, repository checks, formatting, debug build, `CI / final-status`, and an independent high-risk audit are completed on the final implementation head before merge. |
| IS-AC-15 | #84 may resume only after the accepted strategy is implemented and IS-AC-02 through IS-AC-11 physical and behavioral evidence is available for the exact integration head. |

## Test oracle

The physical oracle is authoritative for IS-AC-05, IS-AC-06, and IS-AC-11. Instrumentation seeds the store only through the production recovery writer, never by raw file construction. For each covered regular file, the harness captures `{exists, regular-file type, byte length, SHA-256, stat modification time, stat change time}` immediately before and immediately after one inspection. For a missing file it records `{exists=false}`. It also captures the complete sorted inventory of the dedicated snapshot directory, including final, `.new`, and legacy `.bak` names. The primary verdict is exact equality of existence, type, digest, length, and inventory; timestamp equality is an additional invariant where the device filesystem reports the field. Read-access time is deliberately excluded because an ordinary read may update it.

| Test surface | Required cases |
|---|---|
| Pure snapshot codec and fence tests | canonical encode/decode, bounds, checksum, duplicate-ID rejection, generation match/mismatch, unknown envelope encoding, trusted authoritative-format incompatibility, and no public raw value. |
| Publication tests | Pinned AndroidX `AtomicFile` base/`.new` behavior, legacy `.bak` read-compatibility inventory, write/finish failure, final revalidation, dirty latch, no stale success, no partial final file, and restart starts unknown. |
| Proven-no-commit tests | Point-ID collision rolls back all transaction work, restores the previous valid generation without publishing, permits the same apply run to retry a new point ID, and reaches `Ready`; committed/ambiguous/read-back/publish failures remain dirty. |
| Reconciliation capability tests | Ordinary `RecoveryStorePort` mutations reject while dirty/unknown; an active identity-bound `RecoveryStoreReconciliationSession` alone can reconcile, retain, rebuild, and publish; closed, reused, forged, cached, or reflection-created capability attempts fail before SQLite. Source-boundary compilation tests prove ordinary protocols depend on `RunMutexPort` and cannot invoke the module-only issuer. |
| Recovery-storage contract tests | every lifecycle/tombstone mutation participates in the fence; checkpoint becomes valid only after `READY` read-back; lifecycle/retention/prune publish only after authoritative validation; post-commit failure is dirty; ordinary mutations reject while dirty/unknown; only an active reconciliation session can perform legal reconciliation/rebuild before readiness. |
| #84 preview protocol tests | valid projection, missing record, retained tombstones, expired point, corrupt/incompatible/unresolved state, snapshot unavailable, dirty state, generation mismatch, `Concurrent`, `WriterBusy`, stale confirmation, confirmation-time authoritative revalidation, direct-final `FileInputStream` use, and `.new`/`.bak`/unexpected-entry unavailable-without-cleanup behavior. |
| Production Android instrumentation | production-created WAL store with sidecars present; valid closed store with sidecars absent; residual valid snapshot with authoritative DB absent/corrupt/incompatible; invalid/unpublished snapshot; `.new`/`.bak`/unexpected-entry no-cleanup; concurrent writer/publication; exact physical oracle on all covered files. |
| Upgrade/downgrade/backup tests | no-backup placement, restore with no trusted snapshot, snapshot rebuild from compatible recovery store, snapshot-codec incompatibility unavailable, authoritative incompatibility result, and rollback without Launcher-layout mutation. |

## Non-goals

This issue does not implement #52 UI/orchestration, expose `RecoveryStorePort` or raw store data, change #84 opaque confirmation, alter recovery mutation/reconciliation/diagnostics semantics, replace the recovery database, change retention policy, add a second authoritative recovery database, or treat `OPEN_READONLY` as proof of physical immutability. It does not bundle native SQLite, add URI parsing assumptions, introduce permissions, telemetry, networking, or user-facing settings.

## Stop conditions

Stage B must stop and return to the owning storage/application contract rather than weaken this specification if any of the following occurs:

- a complete inspection projection cannot be published through the named `AtomicFile` primitive without exposing a partial file or requiring inspection to open SQLite;
- recovery storage cannot keep the fence dirty across every committed or outcome-uncertain authoritative mutation/publication result, cannot restore the prior valid generation only for storage-proven no-commit outcomes, or cannot rebuild a valid generation during legal reconciliation;
- a required runtime/restart path would need to treat an older or residual snapshot as a successful source;
- supporting a required Android environment would require a second process, unreviewed native dependency, unsupported filesystem primitive, new public recovery/store contract, or raw state leakage across the Layout Application boundary;
- the physical instrumentation oracle detects any covered recovery, sidecar, snapshot, or companion-file change during inspection; or
- Spec 13, ADR-0003, the accepted #84 artifact, or the organizer diagnostics contract conflicts with the resulting behavior.

## Change history

- 2026-08-20: Revised after additional review. Added `PROVEN_NO_COMMIT` fence rollback for PointIdCollision retry, direct-final `FileInputStream` inspection with companion-entry fail-closed behavior, and a `RunMutexPort`/module-only startup-session issuer boundary.
- 2026-08-20: Revised after re-review. Replaced platform AtomicFile with the pinned AndroidX implementation to fix the API 26–35 publication protocol, and added the opaque reconciliation-only capability that is required to bypass dirty/unknown ordinary-mutation rejection.
- 2026-08-19: Revised after Stage A review. Added generation/health fencing, eliminated stale snapshot success, fixed `AtomicFile`/API/process scope, separated snapshot-codec and authoritative-format mapping, and bound publication to authoritative read-back validation.
- 2026-08-19: Drafted for #89 Stage A. Selected a writer-published, no-backup, SQLite-free inspection snapshot; production behavior remains unchanged pending review and acceptance.

## References

[1]: https://www.sqlite.org/wal.html "SQLite: Write-Ahead Logging — read-only WAL conditions"
[2]: https://www.sqlite.org/uri.html "SQLite: URI filenames and immutable connections"
[3]: https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/android_database_SQLiteConnection.cpp "AOSP SQLiteConnection JNI bridge"
[4]: ../../build.gradle "NunuLauncher Android SDK support configuration"
[5]: https://developer.android.com/reference/androidx/core/util/AtomicFile "AndroidX AtomicFile API reference"
[6]: https://github.com/nunu1733/NunuLauncher/issues/89 "Issue #89"
[7]: https://github.com/nunu1733/NunuLauncher/issues/84#issuecomment-5342953676 "Issue #84 Stage B P0 response"
[8]: ../../AGENTS.md "Repository rules"
[9]: ../13-safe-layout-application/spec.md "Spec 13: safe layout application and recovery"
[10]: https://developer.android.com/reference/java/io/FileInputStream "Android FileInputStream API reference"
[11]: ../../docs/adr/0003-organizer-recovery-point-storage.md "ADR-0003: separate private recovery database"
[12]: ../../docs/engineering/quality-strategy.md "Quality strategy"
