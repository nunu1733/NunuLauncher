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

The production recovery store enables WAL. SQLite documents that a read-only WAL connection is supported only when readable `-wal` and `-shm` sidecars already exist, the containing directory permits their creation, or the connection is immutable. Therefore SQL read-only access is not a portable physical no-write guarantee for the recovery main database and its sidecars.[1] The Android public database bridge maps its public open flags to read-write, create, or read-only native flags; it does not provide an application-level `SQLITE_OPEN_URI` path required to rely on SQLite URI parameters such as `immutable=1`.[2] [3]

The accepted #84 contract, especially RP-AC-04, is not weakened. A valid production-created WAL recovery database must either be inspected without opening the live SQLite store or inspection must fail closed before touching it. This specification selects the former strategy and keeps the latter as its required fallback.

## Outcome and decision

Recovery storage will own a **durable, app-private, SQLite-free inspection snapshot**. Writers publish a complete, validated projection after recovery-store state changes. The #84 inspection path reads only that immutable projection and never opens the recovery database, its `-wal`, or its `-shm` file.

> An **inspection snapshot** is a complete, non-authoritative, app-private projection of recovery-point and tombstone metadata sufficient to classify one requested recovery point without reading SQLite. It contains no manifest, payload, revision, digest, layout identity, row encoding, or diagnostic payload. It is not a backup, recovery point, mutation log, or a new public application contract.

The live recovery database remains authoritative for checkpoint creation, lifecycle mutation, retention, restart reconciliation, and confirmation-time recovery. A snapshot result never authorizes recovery. Explicit confirmation continues through the unchanged application-level recovery behavior from #84 and Spec 13, which reopens the authoritative store, rechecks readiness, retention, revision, and exact preconditions before any lifecycle or layout mutation.

| Decision | Normative consequence |
|---|---|
| **Selected strategy:** writer-published inspection snapshot | Inspection opens no recovery SQLite file; it reads one validated immutable snapshot file only. |
| Live recovery database remains authoritative | A stale snapshot may provide a point-in-time preliminary result, but confirmation must revalidate against the live store and can reject without mutation. |
| No bundled/native SQLite reader | The change introduces no JNI library, ABI matrix, SQLite release stream, or native security-update obligation. |
| No Android URI/immutable assumption | `file:...?immutable=1` is not passed through Android public `SQLiteDatabase` as a supported application contract. |
| Fail closed on snapshot uncertainty | Missing, invalid, incomplete, incompatible, unpublished, unreadable, or untrusted snapshots return the existing typed store-unavailable outcome without opening SQLite. |

## Scope

This specification owns the recovery-storage strategy required by #84. It includes the snapshot envelope, publication and startup-rehydration rules, inspection read boundary, supported environment, failure mapping, migration/rollback/backup behavior, diagnostics/privacy boundaries, concurrency semantics, and the physical no-write oracle.

The scope is intentionally below the #84 application seam. It does not change `LayoutApplicationModule` public mutation contracts, the #84 closed preview result types, `RecoveryRequest`, `RecoveryResult`, recovery lifecycle semantics, retention policy, writer lease ownership, or Launcher layout application. The storage owner may add private types and focused tests only as described in the accepted implementation plan.

## Supported environment and file set

The selected inspection reader is supported on every Android/API level supported by the app because it does not depend on Android SQLite URI parsing or platform SQLite behavior. The snapshot format is decoded by project-owned Kotlin code and is stored under app-private `Context.noBackupFilesDir`; it is never readable from UI/coordinator code or external storage.

The physical no-write invariant covers every existing or newly created file in the following closed set for each invocation of #84 inspection:

| File family | Covered files | Inspection requirement |
|---|---|---|
| Authoritative recovery SQLite store | `organizer_recovery.db` (main database) | Must not be opened, created, renamed, truncated, deleted, or otherwise changed. |
| SQLite WAL sidecars | `organizer_recovery.db-wal` and `organizer_recovery.db-shm` | Must not be opened for write, created, removed, renamed, resized, or otherwise changed. |
| Inspection snapshot | the single published `recovery-inspection.v1` file | May be opened read-only only; its bytes, size, and modification/change timestamps must remain unchanged. |
| Snapshot publication companions | all writer-owned pending, temporary, replacement, or backup names in the dedicated snapshot directory | Must not be created, removed, renamed, or changed by inspection. |

The file set includes non-existence. A missing sidecar or snapshot companion before inspection must remain missing after it. The snapshot directory is dedicated to this strategy so its full inventory is an executable part of the oracle, rather than an unbounded filesystem search.

## Snapshot data and compatibility

The snapshot is a versioned binary envelope with a deterministic canonical encoding and an integrity checksum over the complete envelope. Its internal schema is private to recovery storage. A valid envelope contains only the following projection:

| Projection field | Purpose | Explicitly excluded |
|---|---|---|
| snapshot format version and recovery-store format compatibility value | Reject unsupported readers and stores before inspection classification. | SQLite page data, schema DDL, or a database copy. |
| publication epoch and publication time | Identify one complete point-in-time projection for internal diagnostics/tests. | Any value returned to UI/coordinator. |
| recovery point ID, lifecycle, creation/update time, checksum-valid classification, and record-format classification | Classify missing, expired, corrupt, incompatible, unresolved, restorable, and already-restored outcomes. | Manifest, raw row data, revision, digest, payload, item, package, profile, coordinate, or title. |
| tombstone point ID, typed reason, expiry time, and compatibility value | Return precise retained tombstone outcomes without lazy cleanup. | Tombstone payload, database row, or SQL exception text. |
| whole-envelope checksum and strict length/count bounds | Detect partial, corrupted, oversized, or incompatible snapshot input. | Any automatic repair or silent fallback to SQLite. |

A snapshot with an unsupported future snapshot format or incompatible recovery-store format maps to #84's `NotRestorable(INCOMPATIBLE_VERSION)`. A missing file, invalid magic/version combination, bad checksum, incomplete write, duplicate or non-canonical point ID, unsupported old encoding, unreadable file, invalid bounded length/count, or unexpected I/O maps to `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. None of these paths may open the recovery database or attempt snapshot repair.

Logical retention remains unchanged. The inspection protocol evaluates `RetentionPolicy.actionFor()` with its injected `Clock` over snapshot timestamps. An expired `VERIFIED` record returns `NotRestorable(EXPIRED)` and a tombstone whose retention deadline has passed returns `NotRestorable(MISSING)` without editing either the snapshot or the live store.

## Publication, startup, and authority

Recovery storage publishes the snapshot only while it owns the existing recovery-store writer path and the same serializer that protects lifecycle changes. Publication is never initiated by inspection, UI, coordinator, a background task, or diagnostics.

For each successful recovery-store transaction that creates, changes, removes, or tombstones a recovery point, the writer derives a complete post-transaction inspection projection, encodes and validates it in memory, writes it to a uniquely named pending file in the dedicated no-backup directory, and ensures that pending bytes are durable before committing the recovery-store transaction. Only after the live transaction succeeds may the writer atomically replace the published file with the validated pending file. A failed database transaction discards the pending file and leaves the previously published snapshot untouched.

A process death or publication failure after a successful database commit may leave an older published snapshot or no published snapshot. This is safe but unavailable-or-stale, never authoritative: the next completed startup reconciliation rebuilds and validates the snapshot while it already has legal access to the live store. Until that work succeeds, the readiness gate remains closed for inspection and #84 returns its existing typed unavailable result. The system must not mark a new snapshot as published before both its integrity check and atomic replacement succeed.

The writer path must treat a post-commit snapshot-publication failure as a recovery-store availability failure for the operation that required publication, preserve the authoritative database for reconciliation, emit only the existing writer/recovery diagnostics permitted by current contracts, and never claim an inspection-capable point. It must not compensate by deleting or rolling back a committed recovery record outside the established recovery protocol.

## Inspection and concurrency semantics

The #84 read-only protocol retains its existing readiness gate, `RunMutex`, and non-blocking writer lease for one authoritative Launcher current-state capture. Its I2 recovery-store read is replaced by one private `readInspectionProjection(pointId)` call. That call performs only bounded read/parse/validate work on the final snapshot file.

A snapshot is immutable after publication. An inspection that observes a concurrent atomic replacement may see either the prior complete snapshot or the next complete snapshot, never a partial file. It does not wait for a recovery writer and it does not acquire a storage-mutation lease. If the existing #84 `RunMutex` or capture-only writer lease is contended, #84 preserves its specified `Concurrent` or `WriterBusy` result before any recovery authorization. If snapshot publication is in progress but a complete prior snapshot exists, inspection may read that prior point-in-time projection; confirmation remains conditionally safe because it revalidates the authoritative store. If no complete published snapshot is available, inspection fails closed as unavailable.

| Situation | Inspection behavior | Persistent effect |
|---|---|---|
| Valid published snapshot and valid unexpired record | Classify from snapshot, then use #84's existing capture-only lease behavior. | None. |
| Writer is publishing a replacement; prior final snapshot exists | Read one complete old or new snapshot without waiting. | None. |
| Writer is publishing and no valid final snapshot exists | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None. |
| #84 module mutex is held | Existing `Concurrent`. | None. |
| #84 capture-only writer lease is held | Existing `WriterBusy`. | None. |
| Snapshot is missing, corrupt, partial, unreadable, or unsupported old encoding | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None. |
| Snapshot shows a point that changed after publication | Return its valid point-in-time classification; confirmation rechecks live state and may reject. | None during inspection. |

Inspection must not call `SQLiteDatabase`, `SQLiteOpenHelper`, `RecoveryDbVersionGate.probe()`, `checkpoint`, lifecycle transition methods, retention/prune methods, writer snapshot publication, model reload, layout mutation, recovery authorization, or diagnostics projection. This restriction applies equally to successful, missing, invalid, unsupported, busy, concurrent, and exception paths.

## Behavior scenarios

### Valid production-created WAL store with sidecars present

Given a recovery point created through the production recovery writer, a valid published snapshot, and an existing recovery database with readable `-wal` and `-shm` sidecars,

When #84 inspects the point,

Then it classifies only the snapshot and performs the existing non-blocking current-layout capture protocol,

And the main database, `-wal`, `-shm`, snapshot final file, and snapshot-directory inventory remain physically unchanged.

### Valid production-created WAL store in closed state

Given a recovery point created through the production recovery writer, a valid published snapshot, a valid closed WAL-mode main database, and absent sidecars,

When #84 inspects the point,

Then it reads only the snapshot and produces its closed preview result,

And no `-wal` or `-shm` file is created and no authoritative recovery file is touched.

### Snapshot unavailable or invalid

Given that the snapshot is absent, incomplete, corrupted, unreadable, exceeds a bound, has an unsupported old encoding, or fails its checksum,

When #84 inspection runs,

Then it returns `Unavailable(RECOVERY_STORE_UNAVAILABLE)` without trying the live database, repairing the snapshot, invoking `SQLiteOpenHelper`, or emitting a diagnostic event.

### Snapshot is stale while a writer is active

Given a writer has committed a lifecycle change after the current final snapshot but before it can publish a replacement,

When #84 inspection reads the last complete snapshot,

Then it may return only the point-in-time classification represented by that snapshot and never treats it as recovery authorization,

And explicit confirmation re-enters the established application-level recovery routine and revalidates the authoritative store, readiness, retention, current revision, and exact preconditions before any mutation.

### Startup after interrupted publication

Given the recovery database commit completed but snapshot publication was interrupted or failed,

When the application starts,

Then startup reconciliation rebuilds the snapshot only while the recovery store is legally opened and serialized,

And inspection remains unavailable until that rebuild validates and publishes a complete snapshot; no Launcher layout state changes merely because a snapshot is missing.

## Migration, downgrade, rollback, backup, and recovery

The strategy adds a no-backup derived artifact and does not alter the recovery database schema, recovery record codec, lifecycle values, retention durations, Launcher schema, backup format, or public recovery contracts. Existing compatible recovery records are reprojected during startup reconciliation after upgrade; while the initial projection is absent, inspection fails closed. A newer or incompatible snapshot is never decoded by an older app and is ignored rather than repaired or opened as SQLite.

The existing recovery database is excluded from Lawnchair ZIP and Android full backup by ADR-0003. The snapshot is additionally outside the existing `res/xml/backupscheme.xml` allowlist and resides in the no-backup directory. Backup or restore therefore does not create a valid inspection snapshot by itself. After a restore, inspection is unavailable until ordinary startup reconciliation has a compatible authoritative recovery store from which to build the projection. This does not change backup semantics or write the Launcher layout.

A rollback that removes this strategy leaves the live recovery database and Launcher layout untouched. It may leave an ignored snapshot artifact, which a later compatible writer may replace during normal reconciliation. No downgrade or rollback path may make an inspection attempt delete, migrate, or modify the authoritative store.

## Privacy, diagnostics, and security

The snapshot remains app-private, no-backup, and inaccessible to UI/coordinator consumers. It stores only opaque recovery point IDs and typed metadata essential to #84 classification. It stores no recovery manifest, layout data, revision, digest, package/component/title, profile, coordinate, raw SQL exception, or free-form diagnostic data.

Inspection remains diagnostics-silent and creates no synthetic recovery lifecycle. Publication and startup rebuild are storage-writer work, not inspection; they may use only diagnostics already permitted for the underlying recovery operation and must not log projection contents. This design adds no permission, network behavior, telemetry, external storage, JNI dependency, or native SQLite update surface.

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| IS-AC-01 | `spec.md` and `plan.md` select the app-private inspection-snapshot strategy; #84 stays within its existing application-owned preview and confirmation contracts. |
| IS-AC-02 | Every #84 inspection result reads only a bounded, validated final snapshot and does not invoke Android SQLite, `SQLiteOpenHelper`, `RecoveryDbVersionGate`, a live-store query, lifecycle/retention action, layout mutation, model reload, recovery authorization, or diagnostics projection. |
| IS-AC-03 | A production-created valid WAL recovery store with `-wal` and `-shm` present is inspectable through a valid snapshot with zero physical change to the main DB, both sidecars, final snapshot, and every snapshot companion file. |
| IS-AC-04 | A production-created valid closed WAL recovery store with sidecars absent is inspectable through a valid snapshot without creating either sidecar or modifying any covered file. |
| IS-AC-05 | The physical oracle records pre/post existence, SHA-256 digest or byte content, size, and defined modification/change timestamps for every covered file, plus a complete inventory of the dedicated snapshot directory. Any difference fails the test. |
| IS-AC-06 | Missing, invalid, truncated, checksum-invalid, unreadable, oversized, unpublished, or unsupported-old snapshot input maps to typed unavailable before live storage is touched; unsupported future snapshot/recovery formats map to typed incompatible-version. |
| IS-AC-07 | Snapshot publication is complete, canonical, integrity-checked, atomic-final-file only, and writer-owned. Crash or publish failure leaves an older snapshot or no snapshot and makes inspection stale-safe or unavailable; it never publishes a partial projection. |
| IS-AC-08 | Visibility under a concurrent writer is explicit: inspection reads one complete immutable snapshot without waiting, returns existing busy/concurrent results for #84 serialization contention, and confirmation revalidates the authoritative store before mutation. |
| IS-AC-09 | Snapshot migration, app upgrade/downgrade, startup rehydration, backup/restore, and rollback preserve the existing recovery/Launcher contracts. No snapshot condition changes the Launcher layout; unsupported or unrebuildable state fails closed. |
| IS-AC-10 | Snapshot contents and public values do not expose a manifest, payload, row, revision, digest, item/profile identity, raw exception, or recovery internals. Inspection emits no recovery lifecycle diagnostic. |
| IS-AC-11 | Focused JVM and Android instrumentation evidence, repository checks, formatting, debug build, `CI / final-status`, and an independent high-risk audit are completed on the final implementation head before merge. |
| IS-AC-12 | #84 may resume only after the accepted strategy is implemented and IS-AC-03 through IS-AC-08 physical and behavioral evidence is available for the exact integration head. |

## Test oracle

The physical oracle is authoritative for IS-AC-03 through IS-AC-05. Instrumentation seeds the store only through the production writer, never by raw file construction. For each covered regular file, the harness captures `{exists, regular-file type, byte length, SHA-256, stat modification time, stat change time}` immediately before and immediately after one inspection. For a missing file it records `{exists=false}`. It also captures the complete sorted inventory of the dedicated snapshot directory, including all final and temporary names. The primary verdict is exact equality of existence, type, digest, length, and inventory; timestamp equality is an additional invariant where the device filesystem reports the field. Read-access time is deliberately excluded because an ordinary read may update it.

| Test surface | Required cases |
|---|---|
| Pure snapshot codec and publication tests | canonical encode/decode, bounded length/count, checksum, duplicate-ID rejection, unknown format, truncated bytes, pending-file failure, atomic replacement, and no partial final file. |
| Recovery-storage contract tests | every recovery lifecycle/tombstone mutation publishes the corresponding complete projection; post-commit publish failure keeps live DB recoverable and inspection unavailable-or-stale; startup reconciliation rebuilds the projection before inspection readiness. |
| #84 preview protocol tests | valid projection, missing record, retained tombstones, expired point, corrupt/incompatible/unresolved state, snapshot unavailable, `Concurrent`, `WriterBusy`, stale confirmation, and confirmation-time authoritative revalidation. |
| Production Android instrumentation | production-created valid WAL store with sidecars present; valid closed store with sidecars absent; absent store; invalid store; invalid/unpublished/incompatible snapshot; concurrent writer/publication behavior; exact physical oracle on all covered files. |
| Upgrade/downgrade/backup tests | no-backup placement, restore with no snapshot, snapshot rebuild from compatible recovery store, incompatible snapshot fail-closed, and rollback without Launcher-layout mutation. |

## Non-goals

This issue does not implement #52 UI/orchestration, expose `RecoveryStorePort` or raw store data, change #84 opaque confirmation, alter recovery mutation/reconciliation/diagnostics semantics, replace the recovery database, change retention policy, add a second authoritative recovery database, or treat `OPEN_READONLY` as proof of physical immutability. It does not bundle native SQLite, add URI parsing assumptions, introduce permissions, telemetry, networking, or user-facing settings.

## Stop conditions

Stage B must stop and return to the owning storage/application contract rather than weaken this specification if any of the following occurs:

- a complete inspection projection cannot be published atomically without exposing a partial file or requiring inspection to open SQLite;
- recovery storage cannot rebuild the projection during its existing legal writer/reconciliation path without a new public mutation contract;
- snapshot publication failure cannot be represented as unavailable-or-stale while preserving existing recovery reconciliation semantics;
- supporting a required Android environment would require an unreviewed native dependency, a new public recovery/store contract, or raw state leakage across the Layout Application boundary;
- the physical instrumentation oracle detects any covered recovery, sidecar, snapshot, or companion-file change during inspection; or
- Spec 13, ADR-0003, the accepted #84 artifact, or the organizer diagnostics contract conflicts with the resulting behavior.

## Change history

- 2026-08-19: Drafted for #89 Stage A. Selected a writer-published, no-backup, SQLite-free inspection snapshot; production behavior remains unchanged pending review and acceptance.

## References

[1]: https://www.sqlite.org/wal.html "SQLite: Write-Ahead Logging — read-only WAL conditions"
[2]: https://www.sqlite.org/uri.html "SQLite: URI filenames and immutable connections"
[3]: https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/android_database_SQLiteConnection.cpp "AOSP SQLiteConnection JNI bridge"
[4]: https://android.googlesource.com/platform/external/sqlite/+/master/dist/Android.bp "AOSP Android SQLite build configuration"
[5]: https://github.com/nunu1733/NunuLauncher/issues/89 "Issue #89"
[6]: https://github.com/nunu1733/NunuLauncher/issues/84#issuecomment-5342953676 "Issue #84 Stage B P0 response"
[7]: ../../AGENTS.md "Repository rules"
[8]: ../13-safe-layout-application/spec.md "Spec 13: safe layout application and recovery"
[9]: https://github.com/nunu1733/NunuLauncher/blob/issue-84-recovery-preview-seam/specs/84-recovery-preview-seam/spec.md "Issue #84 accepted working specification"
[10]: ../../docs/adr/0003-organizer-recovery-point-storage.md "ADR-0003: separate private recovery database"
[11]: ../../docs/engineering/quality-strategy.md "Quality strategy"
