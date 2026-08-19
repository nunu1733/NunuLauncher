# Implementation plan: inspection-safe recovery-store read for WAL databases

> Issue: #89
> Spec: [spec.md](./spec.md)
> Status: draft — Stage A decision document. Do not change production recovery behavior until this specification and plan are reviewed and accepted.

## Purpose and handoff

This plan closes the storage-level blocker that paused Issue #84 at commit `0427e0e62a587102f33432b0c1e354939f66ee4a`. #84 already protects the public preview boundary, opaque confirmation, typed failure mapping, and application-level recovery delegation. Its remaining unsupported assumption is that Android `SQLiteDatabase.OPEN_READONLY` establishes physical no-write for a valid WAL database. It does not; WAL readers may require or create sidecars unless a separate immutable contract exists.[1] [2]

The selected strategy is a **writer-published, non-authoritative inspection snapshot**, guarded by a private **generation/health fence**. The fence is the missing contract that prevents a checksum-valid old file from being accepted after the authoritative recovery database has advanced or failed. #84 may use the new private projection seam only after the final implementation proves the fence, publication primitive, and no-write evidence listed here.

## Review-resolution matrix

| Review finding | Stage A correction | Implementation evidence |
|---|---|---|
| P0: Post-commit snapshot failure permits old snapshot classification | Add `UNKNOWN`/`DIRTY`/`VALID(generation)`/`INCOMPATIBLE` fence. Mark dirty before mutation; accept only a matching valid generation after authoritative read-back and snapshot publication. | Unit failure injection proves old snapshots cannot yield `MISSING`, restorable, or another success after post-commit failure. |
| P0: Stale snapshot success weakens #84 serialization | All writers, preview, and reconciliation share `RunMutex`. A writer race returns `Concurrent`; the old snapshot is never read as a successful preview. | Protocol concurrency tests and instrumentation with a held writer mutex. |
| P1: Filesystem primitive/scope is underspecified | Use the already pinned AndroidX `AtomicFile` only, with its fixed base and `.new` protocol in one `noBackupFilesDir` subdirectory. Define API/process/filesystem scope and explicitly do not assume parent-directory durability. | API 26 and API 35 emulator evidence, AndroidX `AtomicFile` failure tests, legacy `.bak` inventory compatibility, and restart-unknown tests. |
| P1: Snapshot codec versus recovery format is conflated | Unknown/malformed snapshot envelope is unavailable. Only a trusted projection with explicit authoritative recovery/record incompatibility is `INCOMPATIBLE_VERSION`. | Result-mapping test matrix. |
| P1: Publication trust point could precede existing validation | Publish/mark valid only after the established close/reopen read-back required by each mutation. `checkpoint()` waits until the whole `CREATING → READY` sequence is validated. | Store contract tests for checkpoint, lifecycle, retention, tombstone, prune, and reconciliation. |
| P0: no-commit failure would latch dirty and break checkpoint retry | Classify mutation result as proven-no-commit, committed, or outcome-uncertain; restore prior valid generation only for proven no-commit, including rolled-back PointIdCollision. | `PointIdCollision → retry → Ready` regression and no-publication assertion. |
| P1: inspection open primitive and session issuer lacked source-level shape | Direct final `FileInputStream` with fail-closed companion inventory; `RunMutexPort` for ordinary protocols and module-private startup issuer for reconciliation session. | Physical no-cleanup oracle and source-boundary compilation tests. |

## Current evidence

| Confirmed source | Current behavior | Planning consequence |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbHelper.kt` | `onConfigure()` enables WAL and sets `PRAGMA synchronous=FULL`. | The main DB, `-wal`, and `-shm` are a single physical no-write unit for inspection. |
| `RecoveryDbVersionGate.kt` | Existing-file probe opens through `SQLiteDatabase.OPEN_READONLY`. | It must not be reachable from the inspection path. It remains a legal authoritative writer/reconciliation compatibility helper only. |
| Paused #84 `RecoveryStore.inspectExistingDatabase()` at `0427e0e` | Missing DB is handled without a helper, but existing files are still opened with `OPEN_READONLY`. | Replace #84 I2's live SQLite read with a fenced snapshot projection seam. |
| `LayoutApplicationModule.kt` and #84 preview branch | Apply/recover and preview use the same `RunMutex`; #84 acquires it before classifying the store. | Restart reconciliation must also receive that mutex. Inspection races writers at mutex acquisition, never after reading an old snapshot. |
| `ReadinessGate.kt` | It manages `IDLE → RECONCILING → READY/FAILED` for startup and has no runtime snapshot-health state. | Add a private fence rather than overload startup state. Preview sees a dirty/unknown fence as store unavailable; all legal writers can rehydrate only under the shared mutex. |
| `RecoveryStore.kt` | Checkpoint and lifecycle mutations already require close/reopen read-back; retention/prune need equivalent explicit post-write read-back for a snapshot trust point. | Publication must follow, never replace, existing authoritative validation. |
| `build.gradle` | `minSdk 26`, `targetSdk 35`, and compilation against API 36.1. | Support and test scope is API 26–35; test the minimum and target endpoints. |
| `res/xml/backupscheme.xml` | Android full backup uses an explicit allowlist for named Launcher DBs, one preference, and a downgrade file. | Place the snapshot in `noBackupFilesDir`; do not add it to the backup allowlist. |
| Spec 13 and ADR-0003 | Recovery DB is a separate app-private authoritative store. Recovery revalidates lifecycle, retention, revision, and exact preconditions before mutation. | Snapshot remains derived and non-authoritative. It neither replaces the recovery DB nor changes confirmation-time validation. |

The Android bridge does not give app code a supported URI-open flag, while SQLite requires URI semantics for `immutable=1`; a framework path string is therefore not a cross-version immutable-reader contract.[2] [3] A bundled native immutable reader would instead introduce ABI, SQLite release, CVE, and native-update ownership. The fenced snapshot route avoids both risks.

## Architecture and boundaries

### Selected storage topology

```text
shared LayoutApplicationModule RunMutex
  │
  ├─ apply / recover / reconciliation writer
  │    ├─ InspectionSnapshotFence: DIRTY(next generation)
  │    ├─ authoritative organizer_recovery.db (+ -wal / -shm)
  │    ├─ existing mutation-specific close/reopen read-back validation
  │    ├─ pinned AndroidX AtomicFile publish in noBackupFilesDir/recovery-inspection/
  │    └─ InspectionSnapshotFence: VALID(the same generation)
  │
  └─ #84 inspection
       ├─ cannot acquire mutex → Concurrent
       └─ validate fence + matching final snapshot only
            └─ existing capture-only writer lease / typed preview result

process start
  └─ fence UNKNOWN → reconciliation rebuild → VALID or unavailable/incompatible
```

The snapshot is a deep implementation detail of the recovery-storage owner. The only consumer-facing operation remains the #84 application-owned `inspectRecovery(pointId)` result. `RecoveryStorePort`, `StoredRecord`, snapshots, fence values, records, manifests, revisions, digests, SQLite rows, and filesystem paths remain unavailable to UI/coordinator consumers.

### Proposed private types and seams

| Path | Intended change | Responsibility and boundary |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/InspectionSnapshotFence.kt` **(new)** | Define private states `UNKNOWN`, `DIRTY(generation)`, `VALID(generation)`, and `INCOMPATIBLE`, plus private outcomes `PROVEN_NO_COMMIT`, `COMMITTED`, and `OUTCOME_UNCERTAIN`. Enforce monotonic in-process generation and matching-envelope checks. | A proven no-commit ordinary attempt restores exactly its pre-attempt valid generation without publication; all committed/uncertain attempts remain dirty. Starts unknown after process creation; no persistent fence record and no public/UI/diagnostic value. |
| `.../protocol/RunMutexPort.kt` **(new)** | Define the narrow protocol dependency `RunMutexPort { tryAcquire(runId), release(runId) }`; `RunMutex` implements it without exposing any startup/session factory. | Apply, recovery, and preview constructors receive only this port. |
| `.../protocol/StartupReconciliationIssuer.kt` **(new, private to composition-root source file)** | Hold the concrete `RunMutex` and the session factory; implement `withStartupReconciliation { session -> ... }` by acquiring the mutex, creating exactly one session, invoking the block, closing the session in `finally`, then releasing the mutex. | Only the private `LayoutApplicationModule` field holds this issuer. It is absent from ordinary protocol constructors and public module surfaces. |
| `.../protocol/RecoveryStoreReconciliationSession.kt` **(new)** | Define an opaque, constructor-hidden reconciliation capability, bound by identity to one active issuer scope; expose the existing legal reconciliation operations and full snapshot rebuild only through this method-scoped session. | `StartupReconciliationIssuer` is the sole production issuer. `RestartReconciler` receives the session only as a `reconcileAll(session)` argument; it has no issuer/factory reference and cannot retain the session after `finally`. No boolean bypass or public flag exists. |
| `.../store/RecoveryInspectionSnapshot.kt` **(new)** | Define private canonical projection model, envelope metadata including generation and authoritative format classification, strict bounds, and typed validation outcome. | Contains only point/tombstone classification metadata; cannot contain manifests, revisions, digests, rows, payloads, or UI-facing data. |
| `.../store/RecoveryInspectionSnapshotCodec.kt` **(new)** | Deterministically encode/decode and verify the bounded whole-envelope checksum. | A codec/parse failure is unavailable; it cannot report recovery-point incompatibility. |
| `.../store/RecoveryInspectionSnapshotPublisher.kt` **(new)** | Publish with the existing pinned `androidx.core.util.AtomicFile` at `noBackupFilesDir/recovery-inspection/recovery-inspection.v1`; use only its fixed same-directory base/`.new` protocol and final-file revalidation. | New writes never create `.bak`; that legacy name is inventory/read compatibility only. The publisher owns no platform-AtomicFile, cross-directory move, raw rename, copy fallback, or inspection cleanup. It calls `failWrite` on any failure. |
| `.../store/RecoveryInspectionSnapshotReader.kt` **(new)** | Inventory the snapshot directory read-only, require exactly the final base regular file, and open only that exact path with bounded `FileInputStream`. | Reader never calls `AtomicFile.openRead()`/`readFully`, and never opens, recovers, renames, deletes, or cleans `.new`, `.bak`, or unexpected entries. Any companion/unexpected entry is typed unavailable before final-file open. |
| `.../store/RecoveryStore.kt` | Before every authoritative mutation, mark the fence dirty. Classify every attempt as `PROVEN_NO_COMMIT`, `COMMITTED`, or `OUTCOME_UNCERTAIN`; restore prior valid generation only for the proven-no-commit path, including rolled-back `PointIdCollision`, and otherwise publish/revalidate or remain dirty. Ordinary `RecoveryStorePort` mutation entries reject while dirty/unknown; only methods invoked through the active opaque reconciliation session can perform legal reconcile/retention/rebuild work. | The store verifies the session identity and active issuer scope on every privileged operation. It retains the authoritative SQLite lifecycle; `readRecordForInspection`, `readTombstoneForInspection`, and `inspectExistingDatabase` are removed from the #84 inspection path. |
| `.../protocol/Ports.kt` | Replace the #84 inspection-only live-store read pair with one private typed `readInspectionProjection(pointId)` outcome. Keep ordinary `RecoveryStorePort` free of bypass parameters and ordinary protocol constructors free of session/issuer types. | `Value`, `Unavailable`, and `Incompatible` remain typed private values; raw exceptions never escape. UI/coordinator and ordinary protocols cannot obtain the session or issuer. |
| `.../protocol/RecoveryPreviewProtocol.kt` | Change only I2 to use the fenced projection and direct snapshot reader. Keep mutex-first ordering, retention classification, capture-only lease, result surface, and diagnostics silence. | It receives `RunMutexPort` only. If writer owns the mutex return `Concurrent`; if inventory/fence/envelope is not valid return unavailable without final-file open or cleanup. |
| `.../protocol/RestartReconciler.kt` | Receive the already-issued private reconciliation session only as the method-scoped `reconcileAll(session)` argument rather than ordinary `RecoveryStorePort` mutation authority; run all legal reconciliation/retention/rebuild work through it while the issuer scope is active. Add a private `ReconciliationSummary.Incompatible` outcome when legal authoritative validation establishes an unsupported format. | Receives no concrete mutex or issuer/factory. Prevents reconciliation/preview stale race and accidental ordinary-writer healing; does not publish from preview. |
| `.../protocol/LayoutApplicationModule.kt` | Keep the concrete `RunMutex` and one private `StartupReconciliationIssuer`; inject only `RunMutexPort` into apply/recovery/preview. `reconcileAtStart()` invokes the issuer, passes its session only to `RestartReconciler`, and may open the existing ready gate for a private `ReconciliationSummary.Incompatible` outcome solely so #84 can return its accepted typed incompatible result. | Ordinary apply/recover still stop at authoritative store-availability checks and cannot write. All other rebuild failures leave readiness failed; no public rebuild API or issuer accessor is added. |
| `.../protocol/ReadinessGate.kt` | Preserve its startup-only state model; do not add runtime snapshot generations or dirty state to it. | `InspectionSnapshotFence` is the private runtime health latch. Its dirty/unknown check gates ordinary recovery-store mutation entry points and preview classification under `RunMutex`. |
| `tests/unit/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot*Test.kt` **(new)** | Add codec, fence, proven-no-commit rollback, pinned AndroidX `AtomicFile`, direct `FileInputStream` reader, publication, failure, canonicality, atomicity, and restart-rebuild tests. | Assert `PointIdCollision → retry → Ready`, base/`.new` publication, `.new`/`.bak`/unexpected-entry unavailable-without-cleanup, and no UI/coordinator store state. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryStoreReconciliationSessionTest.kt` **(new)** | Add issuer/session identity binding, `finally` close/release, ordinary-port rejection, illegal reuse/forgery, and dirty recovery tests. | Source-boundary compilation tests prove Apply/Recovery/Preview receive only `RunMutexPort`, the private module issuer is absent from their dependency surface, and only its active session bypasses ordinary dirty/unknown rejection. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt` | Replace fake live inspection reads with projection fixtures and add dirty/generation/concurrency result cases. | Verifies #84 observable typed behavior without opening SQLite. |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/store/RecoveryStoreInspectionInstrumentationTest.kt` | Extend production adapter test with the exact physical no-write oracle and valid WAL fixtures. | Load-bearing evidence for main DB, `-wal`, `-shm`, snapshot final file, and `AtomicFile` companion inventory. |
| `specs/84-recovery-preview-seam/spec.md` and `plan.md` | After #89 evidence is accepted, replace the `OPEN_READONLY` I2 detail with the supported fenced snapshot seam and cite #89. | This is a downstream integration update, not a new #84 application contract. |

No `DESIGN.md` change is planned. The derived snapshot and private fence remain inside the existing Layout Application/recovery-storage ownership boundary. No ADR is planned because ADR-0003's authoritative separate recovery DB remains unchanged; the snapshot is a derived inspection artifact. If implementation proposes making the snapshot authoritative, changing recovery retention/backup scope, moving storage ownership, or supporting cross-process access, stop and author a new ADR before code changes.

## Fence and publication protocol

The authoritative database and the snapshot cannot form one filesystem transaction. The protocol therefore never relies on a prior on-disk snapshot after an uncertain mutation. It makes `DIRTY` a mandatory in-process barrier and makes a fresh process `UNKNOWN` until legal reconciliation has rebuilt the projection.

| Phase | Required behavior | Required failure result |
|---|---|---|
| F0: acquire | Apply, recover, and preview contend through `RunMutexPort`; `LayoutApplicationModule` alone holds concrete `RunMutex` plus `StartupReconciliationIssuer`. Only its private `withStartupReconciliation` scope creates one opaque session for `RestartReconciler.reconcileAll(session)`. Preview fails `Concurrent` if a writer holds it. | No snapshot read or file change by preview; no ordinary caller can obtain a dirty/unknown bypass or issuer. |
| F1: invalidate | Before any recovery-store mutation or rebuild, save `VALID(previousGeneration)` when present and set `DIRTY(candidateGeneration)`. While dirty/unknown, ordinary `RecoveryStorePort` mutation entries reject before SQLite; only an active identity-bound reconciliation session may continue to F2. | A later inspection returns unavailable rather than reading an older file, and an ordinary later writer cannot accidentally heal the fault. |
| F2: authoritative mutation | Ordinary calls perform existing DB transaction/lifecycle logic only while valid. The active reconciliation session alone may perform legal unknown/dirty reconciliation/retention work. Classify the attempt: only a storage-proven `PROVEN_NO_COMMIT` ordinary attempt restores `VALID(previousGeneration)` without publication; `COMMITTED` or `OUTCOME_UNCERTAIN` remain dirty. `checkpoint()` covers all intermediate transactions through `READY`; its `PointIdCollision` is proven-no-commit and retries with a new ID in the same run. | If no authoritative commit is proven, no publication occurs and only the previous valid ordinary fence is restored. Any uncertain/committed result keeps fence unavailable until legal rebuild. |
| F3: derive and publish | Only after the required authoritative validation, derive the entire projection, validate it in memory, and use pinned AndroidX `AtomicFile.startWrite`/`finishWrite` on the same-directory base/`.new` file. Reopen/read/validate the final envelope. | Any exception, false/invalid final state, non-regular file, or AndroidX `AtomicFile` failure remains dirty. No platform-AtomicFile, raw rename, copy, or directory-sync fallback exists. |
| F4: validate | Mark `VALID(nextGeneration)` only when final envelope checksum/canonicality passes and its generation equals the fence. | A failed check stays dirty; an older final snapshot is not usable. |
| F5: rehydrate | A new process starts `UNKNOWN`. `LayoutApplicationModule.reconcileAtStart()` enters the private `StartupReconciliationIssuer.withStartupReconciliation` scope, passes its one method-scoped session only to `RestartReconciler.reconcileAll(session)`, and uses it for legal recovery reconciliation/retention/rebuild. It then sets valid or failed readiness. A private `ReconciliationSummary.Incompatible` sets fence incompatible and admits only the existing typed preview path; apply/recover remain stopped by authoritative availability. | The session closes in `finally` before concrete mutex release; residual valid snapshots never bypass authoritative validation. |

The pinned AndroidX `AtomicFile.finishWrite()` provides the chosen fixed file-content sync-and-rename primitive, but it provides no locking and does not specify an app-portable parent-directory durability guarantee.[5] The design does not call private APIs or depend on best-effort directory `fsync`; after process loss, `UNKNOWN` treats every previous filename state as untrusted. This is why API 26–35 support does not overclaim durable directory metadata while still ensuring that an incomplete publication never becomes an inspection success source.

For post-commit publication failure, the authoritative record remains intact for reconciliation. The current writer returns its existing recovery-store failure result and does not delete/roll back the committed record outside the established protocol. The old final snapshot can remain physically present, but `DIRTY` makes it observationally unavailable. No ordinary later writer may heal the state: all ordinary checkpoint/lifecycle/retention entries reject before SQLite while the fence is dirty. Only the active private reconciliation session created within `StartupReconciliationIssuer.withStartupReconciliation` may publish a newly derived full snapshot that passes F3/F4 under the shared mutex.

## Result mapping and #84 integration

The snapshot reader returns only private typed outcomes. #84 maps them to its accepted closed result surface and must never infer missing state from an unavailable snapshot.

| Fence/envelope observation | #84 inspection result | Live-store action during inspection |
|---|---|---|
| `VALID(generation)` plus valid matching current-format projection entry | Continue existing record/tombstone/retention classification. | None. |
| Private `INCOMPATIBLE` established during authoritative startup validation, or a valid matching envelope explicitly carrying authoritative recovery/record incompatibility | `NotRestorable(INCOMPATIBLE_VERSION)`. | None. |
| Valid matching complete projection with no matching entry | `NotRestorable(MISSING)`. | None. |
| `UNKNOWN`, `DIRTY`, missing final file, invalid header/checksum, truncated/noncanonical data, unknown/unsupported snapshot envelope, generation mismatch, unreadable file, or I/O error | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None. |
| `.new`, `.bak`, or any unexpected snapshot-directory entry | `Unavailable(RECOVERY_STORE_UNAVAILABLE)` before the final file opens. | None; inspection performs no `AtomicFile` recovery, rename, delete, or cleanup. |
| Shared `RunMutex` held by a writer/reconciliation | `Concurrent`. | None. |
| Existing capture-only Launcher writer-lease contention after valid classification | `WriterBusy`. | None. |

A checksum-valid residual file cannot create a preview success after process restart: the new fence is unknown and successful startup reconciliation must validate the authoritative store and republish. This covers a residual snapshot paired with an absent, corrupt, unreadable, or incompatible recovery store. Confirmation remains unchanged and re-enters the application recovery routine, which rechecks current readiness, authoritative record/tombstone/lifecycle, retention, current revision, and exact preconditions before any `RESTORING`, recovery DB, or Launcher layout write.

## Physical no-write oracle

### Fixture production requirements

Instrumentation creates recovery records only through production `RecoveryStore` checkpoint/lifecycle paths. It never constructs recovery SQLite bytes, WAL files, or snapshots manually. One fixture retains a valid production-created WAL database with `-wal` and `-shm` present. A second legally closes the writer so the valid main DB remains in WAL mode while both sidecars are absent. Both fixtures require a fence at `VALID(generation)` with a matching valid published snapshot.

### Capture record

Before and after one `LayoutApplicationModule.inspectRecovery(pointId)` invocation, the test captures a `PhysicalFileState` for the main database, `-wal`, `-shm`, final snapshot, and each sorted snapshot-directory entry. Separate fixtures seed `.new`, legacy `.bak`, and an unexpected regular-file entry to prove unavailable-before-final-open and zero inspection cleanup. A regular-file state includes the following fields:

```text
PhysicalFileState =
  | Missing
  | RegularFile {
      lengthBytes,
      sha256,
      lastModifiedMillis,
      changeTimeMillis: optional
    }
```

The primary oracle is exact equality of `Missing`/`RegularFile`, file length, SHA-256, and complete sorted snapshot-directory inventory. Modification and change timestamps are independently compared when the Android filesystem exposes stable values; they must not become a false pass if the platform cannot report them. Atime is excluded because read access can legitimately update it. Any unexpected file, deletion, rename, length/digest difference, or observable timestamp change fails the instrumentation test.

### Required matrix

| Case | Expected inspection outcome | Physical oracle |
|---|---|---|
| Production-created valid WAL store; `-wal` and `-shm` present | Existing #84 valid or typed classification from matching snapshot. | All covered files unchanged. |
| Production-created valid closed WAL store; sidecars absent | Existing #84 valid or typed classification from matching snapshot. | Sidecars remain absent; all covered files unchanged. |
| Writer holds `RunMutex` before/through mutation and publication | `Concurrent`; no old snapshot classification. | No covered file change caused by inspection. |
| Authoritative commit succeeds, then publication/final validation fails while old snapshot exists | `Unavailable`, not `MISSING` or restorable. | Inspection changes no covered file. |
| New process with residual checksum-valid snapshot plus absent/corrupt/unreadable authoritative DB | `Unavailable`. | No helper/open repair/create side effect during inspection. |
| New process with residual checksum-valid snapshot plus authoritative incompatibility established in reconciliation | `NotRestorable(INCOMPATIBLE_VERSION)`. | No inspection-time covered file change. |
| Missing, truncated, checksum-invalid, unreadable, unknown-codec, or generation-mismatched snapshot | `Unavailable`. | Live DB and sidecars unchanged; no snapshot repair/write. |
| `.new`, legacy `.bak`, or unexpected snapshot-directory entry exists at entry | `Unavailable` before final open. | Exact inventory and every file unchanged; no open/recover/rename/delete/cleanup by inspection. |
| Existing #84 mutex or capture lease contention | `Concurrent` or `WriterBusy`. | No covered file change. |
| Confirmation after a valid preview becomes stale/expired/removed | Existing recovery result rejects before mutation as specified by #84/Spec 13. | The separate confirmation may use authoritative writer paths; it is not claimed as an inspection no-write case. |

Run the instrumentation oracle on the supported API endpoints: **API 26** and **API 35**. The implementation PR must explicitly record device images, ABI, API, test APK, revision SHA, and whether all required file-stat fields were observable. If an endpoint cannot provide the planned primitive/evidence, it is a Stage B stop condition rather than an omitted matrix cell.

## Implementation sequence

1. **Refresh integration baseline.** Rebase or otherwise stack implementation on the exact accepted #84 head and current `main`, record the final base SHA, and verify no competing agent changes the recovery-store/preview seam. Do not alter production behavior during Stage A.
2. **Write failing fence, direct-reader, and mapping tests first.** Add pure tests for unknown-on-startup, dirty-before-mutation, valid-generation equality, `PROVEN_NO_COMMIT` rollback, `PointIdCollision → retry → Ready`, dirty latch after post-commit publish failure, exact-final `FileInputStream`, companion/unexpected-entry unavailable-without-cleanup, envelope-versus-authoritative incompatibility, and residual snapshot behavior.
3. **Implement snapshot model, codec, publisher, and direct reader.** Keep all values internal to `application/store`; use exactly one app-private no-backup subdirectory and AndroidX `AtomicFile` only for writer/reconciliation publication. Implement inspection directory inventory plus bounded exact-final `FileInputStream`; add no `AtomicFile.openRead`, raw rename/copy fallback, or inspection cleanup path.
4. **Implement source-level issuer and `RecoveryStore` trust points.** Add `RunMutexPort`, then keep concrete `RunMutex` and private `StartupReconciliationIssuer` only in `LayoutApplicationModule`; inject the narrow port into ordinary protocols and pass the method-scoped session only to `RestartReconciler`. Mark dirty before every store mutation, classify its outcome, restore prior valid generation only for `PROVEN_NO_COMMIT`, and reject ordinary `RecoveryStorePort` entries while dirty/unknown. Publish only after existing close/reopen/read-back validation; add equivalent validation where retention/prune/tombstone currently lack it. Treat the checkpoint's intermediate `CREATING` state as unpublishable.
5. **Integrate full startup rehydration.** Invoke the private issuer, pass its scoped session to `RestartReconciler`, and use that session for legal recovery reconciliation/retention plus complete snapshot rebuild. Make readiness succeed only if the resulting fence is valid. Add a private `ReconciliationSummary.Incompatible` outcome that opens only #84's existing typed incompatible-preview path after authoritative validation; apply/recover still stop at their store-availability checks. All absent/corrupt/unreadable paths remain unavailable.
6. **Replace #84 I2.** Make preview use the fenced projection seam and direct reader, retain mutex-first order, and delete the unsupported `OPEN_READONLY` inspection chain. Preview reads no old file if it cannot obtain the port mutex or fence is not valid; it returns unavailable without opening the final file whenever `.new`, `.bak`, or an unexpected entry exists.
7. **Add production physical oracle and capability matrices.** Cover `PointIdCollision` no-commit retry, AndroidX base/`.new` publication plus inspection-time `.new`/legacy `.bak`/unexpected-entry inventory, direct reader, both valid WAL sidecar states, post-commit failure, residual valid snapshot plus invalid authoritative store, snapshot codecs, busy/concurrency, source-boundary issuer isolation, reconciliation capability misuse, and API 26/API 35.
8. **Update the accepted #84 artifact only after evidence succeeds.** Replace its unsupported I2 claim with a reference to #89, record the exact supported implementation head and evidence, and state that #84 Stage B may resume.

## Migration, rollback, and backup plan

| Concern | Required implementation and evidence |
|---|---|
| Existing recovery records on upgrade | A new process starts unknown. Startup reconciliation derives a full snapshot from compatible authoritative records before inspection readiness; until then preview is unavailable. |
| Snapshot format evolution | Snapshot-codec incompatibility is unavailable. Only a trusted projection's explicit authoritative format incompatibility reaches `INCOMPATIBLE_VERSION`; inspection never parses loosely or uses SQLite. |
| Interrupted publication | Pinned AndroidX `AtomicFile` `.new` never establishes valid health. Only an active reconciliation session rebuilds later; no Launcher layout write occurs because publication failed. |
| Publication failure after DB commit | Keep the authoritative record, latch dirty, reject ordinary subsequent mutations before SQLite, preserve existing recovery semantics, and prove that any old snapshot is unusable until an active private reconciliation session establishes a fresh valid generation. |
| App downgrade/strategy rollback | Start unknown and ignore/remove only writer-owned derived artifacts during legal startup/recovery maintenance; do not alter Launcher layout or authoritative recovery records. |
| Lawnchair ZIP / Android full backup | Keep snapshot under `noBackupFilesDir` and absent from `backupscheme.xml`. Restore cannot make a residual snapshot trusted; rebuild or fail closed. |

## Verification

| Acceptance criteria | Evidence | Command or environment |
|---|---|---|
| IS-AC-01, IS-AC-03, IS-AC-03a, IS-AC-04, IS-AC-07, IS-AC-08, IS-AC-09 | JVM fence/proven-no-commit/codec/pinned-AndroidX-AtomicFile/reconciliation-capability/public-boundary/publication tests. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.store.RecoveryInspectionSnapshot*'` |
| IS-AC-02, IS-AC-02a, IS-AC-09, IS-AC-10, IS-AC-13 | #84 preview direct-reader matrix; source-boundary test; fake port call counters; companion-entry no-cleanup and diagnostic counter assertions. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest' --tests 'app.lawnchair.organizer.application.contract.RecoveryPreviewContractTest'` |
| IS-AC-05, IS-AC-06, IS-AC-11 | Production-created sidecars-present/closed-sidecars-absent physical oracle plus post-commit/residual-storage and companion-entry no-cleanup matrix. | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest --tests 'app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest'` on API 26 and API 35. |
| IS-AC-12 | Upgrade/restart/rebuild, backup exclusion, downgrade, and rollback focused tests. | Focused JVM/instrumentation commands documented with final implementation paths; API/ABI matrix recorded in PR. |
| IS-AC-14 | Repository validator, formatting, organizer JVM suite, debug assembly, CI final status, and independent audit. | `python3 tools/repo-contract/validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_repo_contract.py`; `./gradlew spotlessCheck`; `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`; `./gradlew assembleLawnWithQuickstepGithubDebug`; CI `final-status`. |

The implementation PR must retain the `risk: layout-data` label and include the independent audit record required by `AGENTS.md` and the repository workflow. The audit must identify the implementation head SHA, #89 and #84 acceptance criteria, exact API/ABI matrix, physical-oracle artifacts or logs, and the successful `CI / final-status` URL.

## Documentation updates and completion

| Artifact | Required update |
|---|---|
| `specs/89-inspection-safe-recovery-store-read/spec.md` and `plan.md` | Mark `accepted` only after Stage A review selects this revised strategy. Update to `implemented` only with final Stage B evidence. |
| #84 accepted working spec/plan | After #89 implementation evidence passes, replace the old `OPEN_READONLY` inspection assumption with the fenced snapshot seam and reference #89. |
| Issue #89 | Record the review resolution, exact `AtomicFile` primitive/scope, fence contract, supported API/ABI matrix, final SHA/PR/audit, and #84 resume condition. |
| `CONTEXT.md` / `DESIGN.md` | No update planned because neither domain language nor module ownership changes. |
| ADRs | No update planned unless implementation makes the snapshot authoritative, changes persistence ownership/backup/retention semantics, or introduces cross-process storage. |

The Stage B completion condition is not merely that an inspection test passes. It is that the selected strategy has a reviewed implementation and reproducible evidence that valid production-created WAL stores are never touched by inspection in both sidecar-present and sidecar-absent states; direct final-file reading leaves companion/unexpected entries untouched and unavailable; proven no-commit attempts preserve legal retry while every committed/uncertain state fails closed; writers never permit stale snapshot success; residual snapshots cannot bypass authoritative revalidation; and ordinary protocols cannot acquire the reconciliation issuer. Only then may #84 resume its paused integration work.

## Stop conditions

Stop implementation and open the owning contract/ADR follow-up if the pinned AndroidX `AtomicFile` cannot provide the named same-directory base/`.new` final-file protocol on an API 26/API 35 target, if direct `FileInputStream` cannot read only the final base without companion cleanup, if `LayoutApplicationModule` cannot keep the private issuer absent from ordinary protocol dependency surfaces, if the shared `RunMutex` cannot encompass every recovery store mutation/reconciliation path, if the fence cannot restore only a storage-proven no-commit valid generation while remaining dirty across every committed/uncertain result, if a required runtime/restart path needs to treat an older/residual snapshot as success, if the physical oracle detects a touched covered file, or if a public recovery/mutation value would need to expose projection internals. Do not solve any such condition by relaxing #84 RP-AC-04, hiding an unsupported device state, using URI/native SQLite without a new decision, or adding a UI/coordinator store access path.

## Change history

- 2026-08-20: Revised after additional review. Added `PROVEN_NO_COMMIT` fence rollback for collision retry, direct-final `FileInputStream` inspection with companion-entry fail-closed handling, and concrete `RunMutexPort`/module-private issuer composition.
- 2026-08-20: Revised after re-review. Fixed publication protocol to the pinned AndroidX primitive and made reconciliation an opaque scoped capability.
- 2026-08-19: Drafted Stage A storage strategy; production behavior remains unchanged pending acceptance.

## References

[1]: https://www.sqlite.org/wal.html "SQLite: Write-Ahead Logging"
[2]: https://www.sqlite.org/uri.html "SQLite: URI filenames and immutable connections"
[3]: https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/android_database_SQLiteConnection.cpp "AOSP SQLiteConnection JNI bridge"
[4]: ../../build.gradle "NunuLauncher Android SDK support configuration"
[5]: https://developer.android.com/reference/androidx/core/util/AtomicFile "AndroidX AtomicFile API reference"
[10]: https://developer.android.com/reference/java/io/FileInputStream "Android FileInputStream API reference"
[6]: https://github.com/nunu1733/NunuLauncher/issues/89 "Issue #89"
[7]: https://github.com/nunu1733/NunuLauncher/issues/84#issuecomment-5342953676 "Issue #84 physical no-write decision"
[8]: https://github.com/nunu1733/NunuLauncher/blob/issue-84-recovery-preview-seam/specs/84-recovery-preview-seam/spec.md "Issue #84 accepted working specification"
[9]: ../13-safe-layout-application/spec.md "Spec 13"
[10]: ../../docs/adr/0003-organizer-recovery-point-storage.md "ADR-0003"
[11]: ../../docs/engineering/quality-strategy.md "Quality strategy"
[12]: ../../AGENTS.md "Repository rules"
