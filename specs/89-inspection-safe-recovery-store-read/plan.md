# Implementation plan: inspection-safe recovery-store read for WAL databases

> Issue: #89
> Spec: [spec.md](./spec.md)
> Status: Stage A **accepted**. Stage B implementation is at `09d0f290fd`; test execution and test-driven remediation are delegated to a separate environment. See [stage-b-handoff.md](./stage-b-handoff.md).

## Purpose and handoff

This plan closes the storage-level blocker that paused Issue #84 at commit `0427e0e62a587102f33432b0c1e354939f66ee4a`. #84 already protects the public preview boundary, opaque confirmation, typed failure mapping, and application-level recovery delegation. Its remaining unsupported assumption is that Android `SQLiteDatabase.OPEN_READONLY` establishes physical no-write for a valid WAL database. It does not; WAL readers may require or create sidecars unless a separate immutable contract exists.[1] [2]

The selected strategy is a **writer-published, non-authoritative inspection snapshot**, guarded by a private **generation/health fence**. The fence prevents a checksum-valid old file from being accepted after the authoritative recovery database has advanced or failed. #84 may use the new private projection seam only after the final implementation proves the fence, publication primitive, startup authority, and no-write evidence listed here.

## Review-resolution matrix

| Review finding | Stage A correction | Implementation evidence |
|---|---|---|
| P0: Post-commit snapshot failure permits old snapshot classification | Add `UNKNOWN`/`DIRTY`/`VALID(generation)`/`INCOMPATIBLE` fence. Mark dirty before mutation; accept only a matching valid generation after authoritative read-back and snapshot publication. | Unit failure injection proves old snapshots cannot yield `MISSING`, restorable, or another success after post-commit failure. |
| P0: Stale snapshot success weakens #84 serialization | All writers, preview, and reconciliation share `RunMutex`. A writer race returns `Concurrent`; the old snapshot is never read as a successful preview. | Protocol concurrency tests and instrumentation with a held writer mutex. |
| P1: Filesystem primitive/scope is underspecified | Use the already pinned AndroidX `AtomicFile` only, with its fixed base and `.new` protocol in one `noBackupFilesDir` subdirectory. Define API/process/filesystem scope and explicitly do not assume parent-directory durability. | API 26 and API 35 emulator evidence, AndroidX `AtomicFile` failure tests, legacy `.bak` inventory compatibility, and restart-unknown tests. |
| P1: Snapshot codec versus recovery format is conflated | Unknown/malformed snapshot envelope is unavailable. Only a trusted projection with explicit authoritative recovery/record incompatibility is `INCOMPATIBLE_VERSION`. | Result-mapping test matrix. |
| P1: Publication trust point could precede existing validation | Publish/mark valid only after the established close/reopen read-back required by each mutation. `checkpoint()` waits until the whole `CREATING → READY` sequence is validated. | Store contract tests for checkpoint, lifecycle, retention, tombstone, prune, and reconciliation. |
| P0: no-commit failure would latch dirty and break checkpoint retry | Classify mutation result as proven-no-commit, committed, or outcome-uncertain; restore prior valid generation only for proven no-commit, including rolled-back PointIdCollision. | `PointIdCollision → retry → Ready` regression and no-publication assertion. |
| P1: inspection open primitive was not fixed | Direct final `FileInputStream` with fail-closed companion inventory; no `AtomicFile.openRead()`/`readFully()` in inspection. | Physical no-cleanup oracle and direct-reader tests. |
| P1: reconciliation issuer lacked a compilable source-level shape | Ordinary protocols receive `RunMutexPort` only. Session construction may use internal visibility, but every privileged operation validates the active lease identity for the exact module-owned concrete `RunMutex`; constructor privacy is not the security boundary. | Source-boundary compilation, foreign-mutex/forged/reused-session tests. |
| P0: fresh install was conflated with missing-store failure | Define pristine absence as no main DB, no sidecars, and no snapshot artifacts. Startup reconciliation alone initializes/validates an empty authoritative store and publishes an empty snapshot. Missing DB plus residual artifacts is suspicious and unavailable. | Fresh-install/restart/first-checkpoint regression plus suspicious-absence matrix. |
| P1: proven-no-commit overclaimed byte equality | Define it by no committed authoritative logical-state change. SQLite may physically touch rollback/journal/WAL internals during writer work; that is outside the inspection no-write oracle. | Collision rollback test proves committed logical state and snapshot generation remain unchanged. |

## Current evidence

| Confirmed source | Current behavior | Planning consequence |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbHelper.kt` | `onConfigure()` enables WAL and sets `PRAGMA synchronous=FULL`. | The main DB, `-wal`, and `-shm` are a single physical no-write unit for inspection. |
| `RecoveryDbVersionGate.kt` | Missing or zero-length file maps to `CreateNew`; existing files are probed through `OPEN_READONLY`. | The probe remains an authoritative startup/writer helper only. Stage B must distinguish true pristine absence from an existing zero-length/partial artifact. |
| `RecoveryStore.availability()` | `CreateNew` is currently treated as `READY`; normal readable/writable helper access may create the DB. | Fresh install is a legal empty authoritative state, not a permanent readiness failure. Creation must occur only inside startup writer/reconciliation authority, never inspection. |
| Paused #84 `RecoveryStore.inspectExistingDatabase()` at `0427e0e` | Missing DB is handled without a helper, but existing files are still opened with `OPEN_READONLY`. | Replace #84 I2's live SQLite read with a fenced snapshot projection seam. |
| `LayoutApplicationModule.kt` and #84 preview branch | Apply/recover and preview use the same `RunMutex`; #84 acquires it before classifying the store. | Restart reconciliation must also receive that mutex. Inspection races writers at mutex acquisition, never after reading an old snapshot. |
| `ReadinessGate.kt` | It manages `IDLE → RECONCILING → READY/FAILED` for startup and has no runtime snapshot-health state. | Add a private fence rather than overload startup state. |
| `RecoveryStore.kt` | Checkpoint and lifecycle mutations already require close/reopen read-back; retention/prune need equivalent explicit post-write read-back for a snapshot trust point. | Publication must follow, never replace, existing authoritative validation. |
| `build.gradle` | `minSdk 26`, `targetSdk 35`, and compilation against API 36.1. | Support and test scope is API 26–35; test the minimum and target endpoints. |
| `res/xml/backupscheme.xml` | Android full backup uses an explicit allowlist. | Place the snapshot in `noBackupFilesDir`; do not add it to backup. |
| Spec 13 and ADR-0003 | Recovery DB is a separate app-private authoritative store. | Snapshot remains derived/non-authoritative; fresh-store initialization does not change ownership. |

The Android bridge does not give app code a supported URI-open flag, while SQLite requires URI semantics for `immutable=1`; a framework path string is therefore not a cross-version immutable-reader contract.[2] [3] A bundled native immutable reader would instead introduce ABI, SQLite release, CVE, and native-update ownership. The fenced snapshot route avoids both risks.

## Architecture and boundaries

### Selected storage topology

```text
shared LayoutApplicationModule concrete RunMutex
  │
  ├─ RunMutexPort ──> apply / recover / #84 preview
  │
  ├─ startup reconciliation issuer (module-private)
  │    └─ active exact-mutex lease
  │         └─ method-scoped RecoveryStoreReconciliationSession
  │              ├─ pristine empty-store initialization when legal
  │              ├─ reconciliation / retention
  │              └─ snapshot rebuild
  │
  ├─ ordinary recovery-store writer
  │    ├─ InspectionSnapshotFence: DIRTY(next generation)
  │    ├─ authoritative organizer_recovery.db (+ -wal / -shm)
  │    ├─ mutation-specific close/reopen read-back validation
  │    ├─ pinned AndroidX AtomicFile publish
  │    └─ InspectionSnapshotFence: VALID(the same generation)
  │
  └─ #84 inspection
       ├─ cannot acquire RunMutexPort → Concurrent
       └─ clean inventory + exact-final FileInputStream + matching generation
            └─ existing capture-only writer lease / typed preview result

process start
  ├─ fence UNKNOWN
  ├─ pristine absence → initialize empty authoritative DB → publish empty snapshot → VALID
  ├─ existing compatible DB → reconcile/retain/rebuild → VALID
  ├─ verified incompatible DB → INCOMPATIBLE
  └─ corrupt/unreadable/suspicious absence → FAILED/unavailable
```

The snapshot is a deep implementation detail of the recovery-storage owner. The only consumer-facing operation remains the #84 application-owned `inspectRecovery(pointId)` result. `RecoveryStorePort`, stored records, snapshots, fence values, manifests, revisions, digests, SQLite rows, filesystem paths, reconciliation sessions, and issuer/lease values remain unavailable to UI/coordinator consumers.

### Proposed private types and seams

| Path | Intended change | Responsibility and boundary |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/InspectionSnapshotFence.kt` **(new)** | Define private states `UNKNOWN`, `DIRTY(generation)`, `VALID(generation)`, `INCOMPATIBLE` and outcomes `PROVEN_NO_COMMIT`, `COMMITTED`, `OUTCOME_UNCERTAIN`. | Proven-no-commit means no committed authoritative logical-state change; it restores only the exact pre-attempt valid generation. |
| `.../protocol/RunMutexPort.kt` **(new)** | Narrow dependency `RunMutexPort { tryAcquire(runId), release(runId) }`; `RunMutex` implements it without exposing startup/session issuance. | Apply, recovery, and preview receive only this port. |
| `.../protocol/LayoutApplicationModule.kt` | Keep the concrete `RunMutex` and a private startup-reconciliation issuer/helper in the composition root. The issuer acquires the concrete mutex, establishes one active startup lease, creates one scoped session, invokes reconciliation, closes the session, then releases the mutex in `finally`. | No issuer accessor or public rebuild API. Ordinary protocol constructors cannot receive the concrete mutex/issuer. |
| `.../protocol/RecoveryStoreReconciliationSession.kt` **(new)** | Define an internal opaque session that is identity-bound to one active startup lease for the exact module-owned `RunMutex`. Constructor/factory visibility may be `internal`; safety does not rely on cross-file `private` construction. | Store/session operations validate exact owner identity and active scope on every privileged call. Closed, reused, cached, forged, reflection-created, or foreign-mutex sessions fail before SQLite. |
| `.../store/RecoveryInspectionSnapshot.kt` **(new)** | Define private canonical projection model, generation and authoritative format classification, strict bounds, checksum. | No manifests/revisions/digests/rows/payload/UI data. |
| `.../store/RecoveryInspectionSnapshotCodec.kt` **(new)** | Deterministic encode/decode and bounded whole-envelope checksum verification. | Codec failure is unavailable, never recovery incompatibility. |
| `.../store/RecoveryInspectionSnapshotPublisher.kt` **(new)** | Publish with pinned `androidx.core.util.AtomicFile` at `noBackupFilesDir/recovery-inspection/recovery-inspection.v1`; final-file revalidate. | New writer uses base/`.new` only; `.bak` inventory compatibility only. |
| `.../store/RecoveryInspectionSnapshotReader.kt` **(new)** | Read-only directory inventory; require exactly final base; bounded `FileInputStream` of exact path. | No `AtomicFile.openRead/readFully`; no companion recovery/rename/delete/cleanup. |
| `.../store/RecoveryStore.kt` | Integrate fence around every mutation; classify mutation outcome. Add reconciliation-only pristine-store initialization and full projection rebuild seams. | Ordinary port rejects dirty/unknown before SQLite. Active session can initialize/reconcile/retain/rebuild. |
| `.../protocol/Ports.kt` | Replace #84 inspection-only live-store read pair with private typed `readInspectionProjection(pointId)`. Keep ordinary `RecoveryStorePort` free of bypass/session parameters. | `Value`/`Unavailable`/`Incompatible` only; no raw exception/state leak. |
| `.../protocol/RecoveryPreviewProtocol.kt` | Change only I2 to fenced projection/direct reader. Keep mutex-first, retention classification, capture-only lease, results, diagnostics silence. | Receives `RunMutexPort` only. |
| `.../protocol/RestartReconciler.kt` | Change to `reconcileAll(session)` and run all legal store-changing startup work through the supplied active session. | Receives no issuer/factory/concrete mutex and must not retain session. |
| `.../protocol/ReadinessGate.kt` | Keep startup-only state model. | Runtime snapshot health remains `InspectionSnapshotFence`. |
| `tests/unit/.../store/RecoveryInspectionSnapshot*Test.kt` **(new)** | Codec/fence/AtomicFile/direct-reader/publication/failure/pristine-init/proven-no-commit tests. | Include `PointIdCollision → retry → Ready`, fresh start, residual mismatch, companion no-cleanup. |
| `tests/unit/.../protocol/RecoveryStoreReconciliationSessionTest.kt` **(new)** | Active exact-mutex identity, finally-close/release, foreign-mutex, forged/reused/cached session tests. | Source-boundary compilation proves ordinary protocols see `RunMutexPort` only. |
| `tests/unit/.../protocol/RecoveryPreviewProtocolTest.kt` | Projection fixtures plus dirty/generation/concurrency/direct-reader cases. | No SQLite inspection. |
| `tests/organizer-instrumentation/.../RecoveryStoreInspectionInstrumentationTest.kt` | Exact physical no-write oracle and startup initialization fixture. | Main DB/sidecars/snapshot files and inventory evidence. |
| `specs/84-recovery-preview-seam/spec.md` and `plan.md` | After #89 implementation evidence succeeds, replace `OPEN_READONLY` I2 with #89 seam. | Downstream integration only. |

No `DESIGN.md` change is planned. The snapshot/fence/session remain inside existing Layout Application/recovery-storage ownership. No ADR is planned unless implementation makes the snapshot authoritative, changes retention/backup ownership, or supports cross-process access.

## Fence, startup, and publication protocol

| Phase | Required behavior | Required failure result |
|---|---|---|
| F0: acquire | Apply/recover/preview contend through `RunMutexPort`. `LayoutApplicationModule` alone enters the concrete startup reconciliation issuer scope and creates one active exact-mutex session. | Preview `Concurrent`; no ordinary caller obtains issuer/session authority. |
| F1: invalidate | Before any ordinary recovery-store mutation, save `VALID(previousGeneration)` when present and set `DIRTY(candidateGeneration)`. | Dirty/unknown ordinary entries reject before SQLite. |
| F2: authoritative mutation | Ordinary calls run only from valid state. Classify as `PROVEN_NO_COMMIT`, `COMMITTED`, or `OUTCOME_UNCERTAIN`. Proven-no-commit means no committed logical-state change and restores the previous valid generation without publishing. `PointIdCollision` is proven-no-commit. | Committed/uncertain/read-back failure remains dirty. No byte-equality requirement for rollback writer files. |
| F3: derive and publish | After required authoritative validation, derive complete projection and publish via pinned AndroidX `AtomicFile.startWrite/finishWrite`; reopen/read/validate final envelope. | Any publish/final validation failure remains dirty. |
| F4: validate | Set `VALID(candidateGeneration)` only for matching canonical/checksum-valid final envelope. | Failed validation stays dirty. |
| F5: startup classify/rehydrate | New process starts `UNKNOWN`. Under active session: classify pristine absence vs existing/suspicious storage. Pristine means main DB absent, sidecars absent, snapshot directory absent/empty; initialize empty authoritative DB, validate, run retention, publish empty snapshot. Existing compatible DB reconciles/retains/rebuilds. Missing DB plus any residual sidecar/snapshot artifact is suspicious. Existing zero-length main DB is uncertain unless proven part of current initialization. | Pristine success → `VALID`; verified incompatible → `INCOMPATIBLE`; corrupt/unreadable/suspicious/failed init/publish → readiness failed/unavailable. |

The pinned AndroidX `AtomicFile.finishWrite()` supplies file-content sync-and-rename but no locking or portable parent-directory `fsync` guarantee.[5] After process loss, the in-memory fence is `UNKNOWN`; disk artifacts are never trusted before authoritative startup validation.

For post-commit publication failure, the authoritative record remains intact, fence stays dirty, and no ordinary later writer may heal it. Only an active startup reconciliation session can revalidate and rebuild. For pristine initialization, DB/schema/WAL creation is explicit startup **writer** work under the same concrete mutex; it is not part of inspection and does not weaken #84 RP-AC-04.

## Result mapping and #84 integration

| Observation | #84 inspection result | Inspection live-store action |
|---|---|---|
| `VALID(generation)` + matching valid projection entry | Existing record/tombstone/retention classification. | None. |
| Private `INCOMPATIBLE` from authoritative startup validation, or trusted projection explicitly marking authoritative incompatibility | `NotRestorable(INCOMPATIBLE_VERSION)`. | None. |
| Valid matching complete projection with no entry | `NotRestorable(MISSING)`. | None. |
| `UNKNOWN`, `DIRTY`, missing/invalid snapshot, generation mismatch, I/O error | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None. |
| `.new`, `.bak`, or unexpected snapshot-directory entry | `Unavailable` before final open. | No recovery/rename/delete/cleanup. |
| Shared mutex held | `Concurrent`. | None. |
| Capture-only Launcher writer lease held | `WriterBusy`. | None. |

A checksum-valid residual snapshot never creates success after restart. A missing authoritative DB with that residual snapshot is suspicious absence, not a fresh empty store. Confirmation remains unchanged and re-enters authoritative application recovery.

## Physical no-write oracle

### Fixtures

Instrumentation creates recovery records only through production writer paths. Required fixtures include:

- pristine startup with no recovery DB, sidecars, or snapshot artifacts; startup reconciliation initializes empty store/snapshot, then first checkpoint succeeds;
- production-created WAL DB with sidecars present and matching valid snapshot;
- legally closed WAL DB with sidecars absent and matching valid snapshot;
- residual snapshot/companion with authoritative DB missing;
- post-commit publication failure with old final snapshot;
- `.new`, legacy `.bak`, and unexpected snapshot-directory entry.

### Capture record

Before and after one `inspectRecovery(pointId)`, capture main DB, `-wal`, `-shm`, final snapshot, and complete sorted snapshot-directory inventory. For each regular file capture existence/type, length, SHA-256, modification time and change time when available. Atime is excluded. The primary inspection verdict is exact equality of existence/type/length/digest/inventory; timestamp equality is additional where supported.

The pristine initialization fixture is **not** itself a no-write inspection assertion: its startup writer step is expected to create recovery storage and snapshot. The no-write oracle begins only after startup reaches `VALID`, and then inspection must leave all covered files unchanged.

### Required matrix

| Case | Expected | Oracle |
|---|---|---|
| Pristine first start | Startup reaches `VALID` with empty store/snapshot; first checkpoint can reach `Ready`. | Startup creation is recorded separately; later inspection unchanged. |
| Missing main DB + residual sidecar/snapshot artifact | Startup unavailable; no residual trust/cleanup. | Inspection never opens/repairs store. |
| Valid WAL, sidecars present | Existing #84 typed result from matching snapshot. | All covered files unchanged. |
| Valid closed WAL, sidecars absent | Existing #84 typed result. | Sidecars remain absent; all covered files unchanged. |
| Writer holds shared mutex | `Concurrent`. | Inspection causes no file change. |
| Commit then publication/final validation fails | `Unavailable`. | Inspection causes no file change. |
| Invalid/missing/generation-mismatched snapshot | `Unavailable`. | No live DB/sidecar change, no repair. |
| `.new`/`.bak`/unexpected entry | `Unavailable` before final open. | Exact inventory/files unchanged. |
| Capture lease contention | `WriterBusy`. | No file change. |
| Stale/expired confirmation | Existing recovery rejection at confirmation. | Separate mutation boundary, not inspection oracle. |

Run the instrumentation oracle on **API 26** and **API 35** and record device image, ABI, API, test APK, revision SHA, and available stat fields.

## Implementation sequence

1. **Refresh integration baseline.** Stack implementation on accepted #84 head and current `main`; record base SHA. No production behavior change during Stage A.
2. **Write failing tests first.** Cover fence states, pristine startup, suspicious absence, `PROVEN_NO_COMMIT`, collision retry, dirty post-commit failure, direct reader, companion no-cleanup, format mapping, residual snapshot, session identity/foreign mutex.
3. **Implement snapshot model/codec/publisher/reader.** AndroidX `AtomicFile` writer only; exact-final bounded `FileInputStream` reader only.
4. **Implement `RunMutexPort` and startup issuer shape.** Ordinary protocols receive port only. Module keeps concrete mutex. Session construction may be internal but must bind to active exact-mutex lease; every privileged call validates active identity. Do not use constructor privacy as the enforcement mechanism.
5. **Implement RecoveryStore fence/trust points.** Dirty before mutations; classify proven-no-commit/committed/uncertain; publish only after required read-back; add retention/prune/tombstone validation.
6. **Implement pristine startup initialization.** Before normal reconciliation, inventory main DB/sidecars/snapshot artifacts. If truly pristine, active session initializes empty DB through writer-owned helper, validates schema/empty store, runs retention, publishes empty projection, then readiness becomes valid. Residual artifact + missing main DB fails closed. Zero-length main DB is uncertain unless tied to current initialization.
7. **Integrate existing-store startup reconciliation.** Use scoped session for all reconciliation/retention/rebuild; add private incompatible summary.
8. **Replace #84 I2.** Use fenced projection/direct reader; remove inspection reachability to `OPEN_READONLY` chain.
9. **Add production evidence.** API 26/35 physical oracle, pristine first checkpoint, collision retry, residual mismatch, session misuse, writer concurrency.
10. **Update #84 artifact after #89 implementation evidence succeeds.** Record exact implementation head and resume condition.

## Migration, rollback, and backup plan

| Concern | Required implementation/evidence |
|---|---|
| Fresh install / never-used recovery | Startup initializes empty authoritative DB and empty snapshot before readiness; first checkpoint succeeds. |
| Existing records on upgrade | Reconcile compatible DB and rebuild full snapshot. |
| Missing DB with residual snapshot/sidecar | Suspicious, unavailable; no residual trust or cleanup by inspection. |
| Restore with no recovery artifacts | Equivalent to pristine absence; initialize empty recovery store because recovery DB/snapshot are intentionally excluded from backup. |
| Snapshot format evolution | Unknown snapshot codec unavailable; authoritative incompatibility only from trusted authoritative classification. |
| Interrupted publication | `.new` never establishes valid health; rebuild only through active startup session. |
| Publication failure after DB commit | Keep authoritative record, latch dirty, reject ordinary later mutations. |
| App downgrade/strategy rollback | Do not alter Launcher layout or authoritative recovery records from inspection. Writer maintenance may remove derived artifacts only under accepted rollback contract. |
| Backup | Snapshot remains under `noBackupFilesDir`; no backup allowlist change. |

## Verification

| Acceptance criteria | Evidence | Command/environment |
|---|---|---|
| IS-AC-01, IS-AC-03/03a/04/07/08/09/10a | JVM fence, pristine-init, proven-no-commit, codec, AtomicFile, session identity/boundary tests. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.store.RecoveryInspectionSnapshot*'` plus reconciliation-session focused tests. |
| IS-AC-02/02a/09/10/13 | #84 preview direct-reader matrix, source-boundary compile test, fake call counters, companion no-cleanup. | Focused `RecoveryPreviewProtocolTest` and `RecoveryPreviewContractTest`. |
| IS-AC-05/06/11 | Production sidecars-present/absent physical oracle; post-commit/residual/companion cases. | `connectedLawnWithQuickstepGithubDebugAndroidTest` on API 26 and API 35. |
| IS-AC-10a | Pristine startup → empty validated store/snapshot → first checkpoint `Ready`; restart remains non-pristine because DB now exists. | JVM + Android instrumentation startup fixture. |
| IS-AC-12 | Upgrade/restart/rebuild, backup exclusion, restore-pristine initialization, downgrade/rollback. | Focused JVM/instrumentation commands documented in PR. |
| IS-AC-14 | Repo validator, formatting, organizer JVM suite, debug assembly, CI final status, independent audit. | `validate_repo_contract.py`; validator tests; `spotlessCheck`; organizer unit suite; debug assembly; CI `final-status`. |

The implementation PR must retain `risk: layout-data` and include the independent audit record required by `AGENTS.md`, identifying implementation SHA, #89/#84 criteria, API/ABI matrix, physical-oracle evidence, and CI final-status.

## Documentation updates and completion

| Artifact | Required update |
|---|---|
| `specs/89-inspection-safe-recovery-store-read/spec.md` and `plan.md` | Mark `accepted` only after Stage A review. Mark `implemented` only with final Stage B evidence. |
| #84 working spec/plan | After #89 evidence, replace old `OPEN_READONLY` assumption with #89 seam. |
| Issue #89 | Record review resolution, primitive/scope, fence/startup/session contract, final SHA/PR/audit, #84 resume condition. |
| `CONTEXT.md` / `DESIGN.md` | No update planned. |
| ADRs | None unless snapshot becomes authoritative, persistence ownership changes, or cross-process access is added. |

Stage B completes only when production-created WAL stores are physically unchanged by inspection in sidecars-present/absent states; fresh install initializes an empty authoritative store without making inspection responsible for DB creation; suspicious missing-store residuals fail closed; direct final-file reading performs no cleanup; proven-no-commit preserves legal retry while committed/uncertain state stays dirty; residual snapshots never bypass authoritative revalidation; and ordinary protocols cannot exercise reconciliation authority.

## Stop conditions

Stop and return to the owning contract if any of the following occurs:

- pinned AndroidX `AtomicFile` cannot provide the named base/`.new` protocol on API 26/35;
- direct `FileInputStream` cannot read only final base without companion cleanup;
- pristine no-store startup cannot initialize/validate an empty authoritative store solely under startup writer/reconciliation authority;
- missing main DB plus residual artifacts cannot be distinguished and failed closed;
- ordinary protocols need concrete `RunMutex`, issuer, or session factory access;
- active exact-mutex identity cannot reject forged/reused/foreign sessions before SQLite;
- fence cannot restore only proven-no-commit logical state while remaining dirty for committed/uncertain results;
- a runtime/restart path must trust an older/residual snapshot;
- physical oracle finds an inspection-time covered-file change; or
- implementation would require weakening #84 RP-AC-04 or exposing raw recovery state.

## Change history

- 2026-08-20: Stage A accepted. Stacked accepted #84 preview seam and implemented the initial Stage B snapshot/fence/direct-reader/reconciliation integration at `09d0f290fd`. Added [Stage B handoff](./stage-b-handoff.md); Gradle test execution is delegated because this sandbox could not retrieve `develocity-gradle-plugin:4.3.1`.
- 2026-08-20: Revised after final Stage A re-review. Added pristine fresh-install initialization vs suspicious absence, replaced the cross-file private-constructor assumption with active exact-`RunMutex` lease identity enforcement, and narrowed proven-no-commit to committed logical-state change.
- 2026-08-20: Revised after additional review. Added collision rollback/retry, direct-final reader, companion fail-closed handling, and `RunMutexPort` boundary.
- 2026-08-20: Revised after re-review. Fixed publication protocol to pinned AndroidX and added scoped reconciliation capability.
- 2026-08-19: Drafted Stage A storage strategy; production behavior remains unchanged pending acceptance.

## References

[1]: https://www.sqlite.org/wal.html "SQLite: Write-Ahead Logging"
[2]: https://www.sqlite.org/uri.html "SQLite: URI filenames and immutable connections"
[3]: https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/android_database_SQLiteConnection.cpp "AOSP SQLiteConnection JNI bridge"
[4]: ../../build.gradle "NunuLauncher Android SDK support configuration"
[5]: https://developer.android.com/reference/androidx/core/util/AtomicFile "AndroidX AtomicFile API reference"
[6]: https://github.com/nunu1733/NunuLauncher/issues/89 "Issue #89"
[7]: https://github.com/nunu1733/NunuLauncher/issues/84#issuecomment-5342953676 "Issue #84 physical no-write decision"
[8]: https://github.com/nunu1733/NunuLauncher/blob/issue-84-recovery-preview-seam/specs/84-recovery-preview-seam/spec.md "Issue #84 accepted working specification"
[9]: ../13-safe-layout-application/spec.md "Spec 13"
[10]: https://developer.android.com/reference/java/io/FileInputStream "Android FileInputStream API reference"
[11]: ../../docs/adr/0003-organizer-recovery-point-storage.md "ADR-0003"
[12]: ../../docs/engineering/quality-strategy.md "Quality strategy"
[13]: ../../AGENTS.md "Repository rules"
