# Implementation plan: inspection-safe recovery-store read for WAL databases

> Issue: #89
> Spec: [spec.md](./spec.md)
> Status: draft — Stage A decision document. Do not change production recovery behavior until this specification and plan are reviewed and accepted.

## Purpose and handoff

This plan closes the storage-level blocker that paused Issue #84 at commit `0427e0e62a587102f33432b0c1e354939f66ee4a`. #84 already protects the public preview boundary, opaque confirmation, typed failure mapping, and application-level recovery delegation. Its remaining unsupported assumption is that Android `SQLiteDatabase.OPEN_READONLY` establishes physical no-write for a valid WAL database. It does not; WAL readers may require or create sidecars unless a separate immutable contract exists.[1] [2]

The selected strategy is a **writer-published, non-authoritative inspection snapshot**. It prevents #84 inspection from opening SQLite at all. The exact Stage B handoff is a private recovery-storage read seam that exposes only a validated inspection projection and a complete Android instrumentation oracle proving the live database and sidecars are not touched. #84 may use that seam only after the evidence listed in this plan exists on the integration head.

## Current evidence

| Confirmed source | Current behavior | Planning consequence |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbHelper.kt` | `onConfigure()` enables WAL and sets `PRAGMA synchronous=FULL`. | The main DB, `-wal`, and `-shm` are a single physical no-write unit for inspection. |
| `RecoveryDbVersionGate.kt` | Existing-file probe opens through `SQLiteDatabase.OPEN_READONLY`. | It must not be reachable from the inspection path. It remains a writer/reconciliation compatibility helper only. |
| Paused #84 `RecoveryStore.inspectExistingDatabase()` at `0427e0e` | Missing DB is handled without a helper, but existing files are still opened with `OPEN_READONLY`. | Replace #84 I2's live SQLite read with the snapshot projection seam. |
| `RecoveryStore.kt` | Writer paths already own checkpoint, lifecycle, tombstone, retention, read-back, and post-crash recovery state. | Snapshot construction and publication belong here or in a private store collaborator, never in UI/coordinator or #84 protocol code. |
| `res/xml/backupscheme.xml` | Android full backup uses an explicit allowlist for named Launcher DBs, one preference, and a downgrade file. | Place the snapshot in `noBackupFilesDir`; do not add it to the backup allowlist. |
| Spec 13 and ADR-0003 | Recovery DB is a separate app-private authoritative store. Recovery revalidates lifecycle, retention, revision, and exact preconditions before mutation. | Snapshot is derived and non-authoritative. It neither replaces the recovery DB nor changes confirmation-time validation. |
| #84 accepted spec and final review | Inspection must remain silent, typed, non-authorizing, and physically non-writing. | Keep existing #84 result types; add only a private storage projection source. |

The Android bridge does not give app code a supported URI-open flag, while SQLite requires URI semantics for `immutable=1`; a framework path string is therefore not a cross-version immutable-reader contract.[2] [3] A bundled native immutable reader would instead introduce ABI, SQLite release, CVE, and native-update ownership. The snapshot route avoids both risks.

## Architecture and boundaries

### Selected storage topology

```text
Recovery writer / reconciliation (existing serializer)
  ├─ authoritative organizer_recovery.db (+ -wal / -shm)
  ├─ derive private inspection projection in memory
  └─ atomically publish no-backup/recovery-inspection.v1

#84 inspection (read-only)
  └─ validate/read recovery-inspection.v1 only
       └─ existing #84 classification + capture-only writer lease

Explicit confirmation (unchanged #84 application route)
  └─ application gate + diagnostics + authoritative RecoveryProtocol
       └─ live recovery SQLite revalidation before mutation
```

The snapshot is a deep implementation detail of the recovery storage owner. The only consumer-facing operation remains the #84 application-owned `inspectRecovery(pointId)` result. `RecoveryStorePort`, `StoredRecord`, snapshots, records, manifests, revisions, digests, SQLite rows, and filesystem paths remain unavailable to UI/coordinator consumers.

### Proposed private types and seams

File and type names are intentionally precise enough for implementation planning but remain private. Stage B may rename only for local repository convention, not to broaden visibility.

| Path | Intended change | Responsibility and boundary |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot.kt` **(new)** | Define private canonical projection model, envelope metadata, format constant, strict bounds, and typed validation outcome. | Contains only point/tombstone classification metadata; cannot contain manifests, revisions, digests, rows, payloads, or UI-facing data. |
| `.../store/RecoveryInspectionSnapshotCodec.kt` **(new)** | Deterministically encode/decode and verify the bounded whole-envelope checksum. | Rejects partial, duplicated, malformed, unsupported, oversized, or non-canonical input without repair or SQLite fallback. |
| `.../store/RecoveryInspectionSnapshotPublisher.kt` **(new)** | Create pending snapshot files, fsync/durably close them, atomically replace the final snapshot, enumerate and clean writer-owned pending files only during legal writer/reconciliation operations. | Owns the dedicated `noBackupFilesDir` subdirectory; inspection has no write/delete/cleanup entry. |
| `.../store/RecoveryStore.kt` | Derive one complete projection from post-transaction state; stage/publish snapshots for checkpoint, lifecycle, tombstone, retention, and startup-reconciliation changes. Implement the private typed snapshot read. | Retains the authoritative SQLite lifecycle. `readRecordForInspection`, `readTombstoneForInspection`, and `inspectExistingDatabase` are retired from the #84 inspection path. |
| `.../protocol/Ports.kt` | Replace the #84 inspection-only live-store read pair with one private, typed `readInspectionProjection(pointId)` outcome. | `Value`, `Unavailable`, and `Incompatible` are private protocol values; no raw store error escapes. |
| `.../protocol/RecoveryPreviewProtocol.kt` | Change only I2 to use the new projection; preserve readiness, `RunMutex`, retention classification, capture-only lease, result surface, and diagnostics silence. | Must not know snapshot file names, codecs, publication state, or SQLite details. |
| `.../protocol/RestartReconciler.kt` and `LayoutApplicationModule.kt` | Ensure inspection readiness opens only after recovery reconciliation has rebuilt/validated the derived snapshot for the current store generation. | The existing readiness owner remains authoritative. Do not add a public rebuild call or a second writer coordinator. |
| `tests/unit/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot*Test.kt` **(new)** | Add codec, publication, failure, canonicality, atomicity, and restart-rebuild tests. | Tests the private storage seam through its owner; no UI/coordinator test reaches store state. |
| `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt` | Replace fake live inspection reads with projection fixtures and extend unavailable/staleness cases. | Verifies #84 observable typed behavior without opening SQLite. |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/store/RecoveryStoreInspectionInstrumentationTest.kt` | Extend the production adapter test with the exact physical no-write oracle and valid WAL fixtures. | Load-bearing evidence for main DB, `-wal`, `-shm`, snapshot final file, and snapshot companions. |
| `specs/84-recovery-preview-seam/spec.md` and `plan.md` | After #89 evidence is accepted, replace the `OPEN_READONLY` I2 detail with the supported snapshot seam and cite #89. | This is a downstream integration update, not a new #84 application contract. |

No `DESIGN.md` change is planned. The derived snapshot remains inside the existing Layout Application/recovery-storage ownership boundary. No ADR is planned because ADR-0003's authoritative separate recovery DB remains unchanged; the snapshot is a derived, non-authoritative inspection artifact. If implementation proposes making the snapshot authoritative, changing recovery retention, changing backup scope, or moving storage ownership, stop and author a new ADR before code changes.

## Publication protocol

The live recovery database and snapshot file cannot form one filesystem transaction. The protocol therefore makes the snapshot **non-authoritative** and permits only two safe post-crash observations: the prior complete snapshot or no usable snapshot. It must never expose a partial new snapshot.

| Phase | Writer behavior | Failure behavior |
|---|---|---|
| P0: derive | Under the existing recovery-store writer path, derive the entire post-mutation inspection projection in memory from authoritative lifecycle/tombstone state. Validate canonical ordering, count/size bounds, and checksum before any final-file replacement. | Abort the writer operation at a pre-mutation boundary where possible; otherwise preserve the authoritative store and mark later inspection unavailable. |
| P1: stage | Encode to a uniquely named pending file in the dedicated no-backup directory. Flush file bytes and close the descriptor before committing the live recovery-store transaction. | Delete only this writer-owned pending file; retain the prior final snapshot. |
| P2: commit authoritative store | Execute the existing recovery DB transaction and its legal read-back/reconciliation behavior. | Remove pending file; do not replace the final snapshot. |
| P3: publish | After a successful DB commit, atomically replace the final snapshot with the fully validated pending file. Durably sync the parent directory where Android filesystem support permits; record no snapshot contents. | Live DB remains authoritative. The writer reports the existing store failure appropriate to its phase, and readiness/reconciliation must keep inspection unavailable until it rebuilds a valid snapshot. |
| P4: rebuild | During startup reconciliation, reconstruct the projection from the legal live-store writer/read path, validate, and publish it before opening inspection readiness. | Keep inspection closed; never "repair" by opening live SQLite from an inspection request. |

For checkpoint creation before a layout application, P3 failure is a checkpoint/storage failure: the layout transaction must not begin, and the existing reconciliation contract owns any now-unused authoritative record. For an already committed lifecycle transition, P3 failure must not roll back or delete the recovery database outside its existing protocol; subsequent inspection is unavailable until P4 succeeds. This distinction prevents a derived artifact from corrupting the authoritative recovery protocol.

## Result mapping and #84 integration

The snapshot reader must return only private typed outcomes. #84 maps them to its accepted closed result surface and must not infer missing state from an unavailable snapshot.

| Snapshot-reader observation | #84 inspection result | Live-store action during inspection |
|---|---|---|
| Valid current-format envelope; matching live projection entry | Continue existing record/tombstone/retention classification. | None. |
| Valid future/unsupported recovery or snapshot format identified by envelope compatibility metadata | `NotRestorable(INCOMPATIBLE_VERSION)`. | None. |
| No entry in a valid complete snapshot | `NotRestorable(MISSING)`. | None. |
| Missing final file, invalid header/checksum, truncated/noncanonical data, unsupported legacy encoding, unreadable file, publication-incomplete state, or I/O error | `Unavailable(RECOVERY_STORE_UNAVAILABLE)`. | None. |
| Existing `RunMutex` contention | `Concurrent`. | None. |
| Existing capture-only writer-lease contention | `WriterBusy`. | None. |

A complete old snapshot may be read while a newer writer transaction or publication is active. That is a permitted point-in-time preview, not a grant to mutate. The retained #84 confirmation token enters the unchanged application-level recovery routine, which rechecks current readiness, authoritative record/tombstone/lifecycle, retention, current revision, and exact preconditions before any `RESTORING`, recovery DB, or Launcher layout write.

## Physical no-write oracle

### Fixture production requirements

Instrumentation must create recovery records only through the production `RecoveryStore` checkpoint/lifecycle paths. It must never construct recovery SQLite bytes, WAL files, or snapshots manually. One fixture must retain a valid production-created WAL database with `-wal` and `-shm` present. A second fixture must close the writer legally so the valid main DB remains in WAL mode while both sidecars are absent. Both fixtures require a valid published inspection snapshot.

### Capture record

Before and after one `LayoutApplicationModule.inspectRecovery(pointId)` invocation, the test captures a `PhysicalFileState` for the main database, `-wal`, `-shm`, final snapshot, and each sorted snapshot-directory entry. A regular-file state includes the required following fields:

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
| Production-created valid WAL store; `-wal` and `-shm` present | Existing #84 valid or typed classification based on snapshot state. | All covered files unchanged. |
| Production-created valid closed WAL store; sidecars absent | Existing #84 valid or typed classification based on snapshot state. | Sidecars remain absent; all covered files unchanged. |
| No recovery DB and no snapshot | Existing unavailable/missing mapping defined by #84 integration. | DB and sidecars remain absent; snapshot directory unchanged. |
| Invalid/corrupt recovery DB and no trusted snapshot | Typed unavailable/incompatible as specified. | No helper/open repair/create side effect. |
| Missing, truncated, checksum-invalid, unreadable, or unsupported snapshot | Typed unavailable/incompatible. | Live DB and sidecars unchanged; no snapshot repair/write. |
| Snapshot publication races with a writer | Complete old/new snapshot result or unavailable; no wait/partial parse. | Inspection creates, removes, or changes no covered file. |
| Existing #84 mutex or capture lease contention | `Concurrent` or `WriterBusy`. | No covered file change. |
| Confirmation after a point-in-time snapshot becomes stale/expired/removed | Existing recovery result rejects before mutation as specified by #84/Spec 13. | The separate confirmation operation may use authoritative writer paths; it is not claimed as an inspection no-write case. |

## Implementation sequence

1. **Refresh integration baseline.** Rebase or otherwise stack implementation on the exact accepted #84 head, record the final base SHA, and verify no competing agent changes the recovery-store/preview seam. Do not alter production behavior during Stage A.
2. **Write failing private storage tests first.** Add codec bounds/canonicality/integrity tests and publication crash/failure tests. Add a fake projection source to #84 tests so I2 can be exercised without a live store.
3. **Implement immutable projection and publisher.** Keep all values internal to `application/store`; use app-private no-backup storage, unique pending names, explicit final-file replacement, and no inspection cleanup path.
4. **Integrate writers and startup reconciliation.** Publish from every authoritatively successful checkpoint/lifecycle/tombstone/retention mutation. Rebuild before readiness opens after startup, upgrade, or post-publication failure.
5. **Replace #84 I2.** Make the preview protocol depend on the typed projection seam rather than `OPEN_READONLY`; delete the unsupported inspection call chain from its path while retaining production recovery reads for actual confirmation/reconciliation.
6. **Add the physical Android oracle.** Cover valid production WAL sidecars-present and closed-sidecars-absent fixtures before asserting #84 can resume.
7. **Exercise failure, concurrency, migration, and privacy matrices.** Demonstrate fail-closed behavior, no public state leakage, no inspection diagnostics, stale confirmation revalidation, backup exclusion, and upgrade/downgrade/rollback behavior.
8. **Update the accepted #84 artifact only after evidence succeeds.** Replace its unsupported I2 claim with a reference to #89 and record the exact condition under which #84 Stage B resumes.

## Migration, rollback, and backup plan

| Concern | Required implementation and evidence |
|---|---|
| Existing recovery records on upgrade | Startup reconciliation derives a full snapshot from compatible authoritative records before inspection readiness. Until then, inspection is unavailable. |
| Snapshot format evolution | Future snapshot format is explicitly versioned. Newer readers must reject unknown old/future encodings according to the accepted compatibility mapping, never parse loosely or use SQLite from inspection. |
| Interrupted write | Pending file never becomes final. Recovery store reconciliation rebuilds a final snapshot later; no Launcher layout write occurs because a snapshot failed. |
| Publication failure after DB commit | Preserve the authoritative record and existing recovery semantics; close inspection readiness until successful rebuild. Test the exact writer result/reconciliation behavior. |
| App downgrade/strategy rollback | Ignore or remove only writer-owned derived artifacts during legal startup/recovery maintenance; do not alter Launcher layout or authoritative recovery records. |
| Lawnchair ZIP / Android full backup | Keep snapshot under `noBackupFilesDir` and absent from `backupscheme.xml`. Restore cannot make a stale snapshot authoritative; rebuild or fail closed. |

## Verification

| Acceptance criteria | Evidence | Command or environment |
|---|---|---|
| IS-AC-01, IS-AC-06, IS-AC-07 | Spec/plan review; JVM codec/public-boundary/publication/reconciliation tests. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.store.RecoveryInspectionSnapshot*'` |
| IS-AC-02, IS-AC-08, IS-AC-10 | #84 preview protocol matrix; source-boundary test; fake port call counters; diagnostic counter assertions. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest' --tests 'app.lawnchair.organizer.application.contract.RecoveryPreviewContractTest'` |
| IS-AC-03 through IS-AC-05 | Production-created WAL sidecars-present and closed-sidecars-absent physical oracle matrix. | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest --tests 'app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest'` on every supported API/device configuration accepted by the final implementation matrix. |
| IS-AC-09 | Upgrade/restart/rebuild, backup exclusion, downgrade, and rollback focused tests. | Focused JVM/instrumentation commands documented with final implementation paths; emulator/API matrix recorded in PR. |
| IS-AC-11 | Repository validator, formatting, organizer JVM suite, debug assembly, CI final status, and independent audit. | `python3 tools/repo-contract/validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_repo_contract.py`; `./gradlew spotlessCheck`; `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`; `./gradlew assembleLawnWithQuickstepGithubDebug`; CI `final-status`. |

The implementation PR must retain the `risk: layout-data` label and include the independent audit record required by `AGENTS.md` and the repository workflow. The audit must identify the implementation head SHA, #89 and #84 acceptance criteria, exact JVM/instrumentation/API matrix, physical-oracle artifacts or logs, and the successful `CI / final-status` URL.

## Documentation updates and completion

| Artifact | Required update |
|---|---|
| `specs/89-inspection-safe-recovery-store-read/spec.md` and `plan.md` | Mark `accepted` only after Stage A review selects this strategy. Update to `implemented` only with final Stage B evidence. |
| #84 accepted working spec/plan | After #89 implementation evidence passes, replace the old `OPEN_READONLY` inspection assumption with the snapshot seam and reference #89. |
| Issue #89 | Record strategy, rejected alternatives, exact file-set oracle, supported API/device matrix, final SHA/PR/audit, and the #84 resume condition. |
| `CONTEXT.md` / `DESIGN.md` | No update planned because neither domain language nor module ownership changes. |
| ADRs | No update planned unless implementation makes the snapshot authoritative, changes persistence ownership, alters backup/retention semantics, or otherwise crosses the ADR threshold. |

The Stage B completion condition is not merely that an inspection test passes. It is that the selected snapshot strategy has a reviewed implementation and reproducible physical evidence that valid production-created WAL stores are never touched by inspection in both sidecar-present and sidecar-absent states, while all uncertain states fail closed. Only then may #84 resume its paused integration work.

## Stop conditions

Stop implementation and open the owning contract/ADR follow-up if snapshot publication cannot be confined to the existing recovery storage owner, if recovery readiness cannot safely include rehydration, if the physical oracle detects a touched covered file, if a required compatibility path needs a native SQLite/URI contract, or if a public recovery/mutation value would need to expose projection internals. Do not solve any such condition by relaxing #84 RP-AC-04, by hiding an unsupported device state behind a successful result, or by adding a UI/coordinator store access path.

## References

[1]: https://www.sqlite.org/wal.html "SQLite: Write-Ahead Logging"
[2]: https://www.sqlite.org/uri.html "SQLite: URI filenames and immutable connections"
[3]: https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/android_database_SQLiteConnection.cpp "AOSP SQLiteConnection JNI bridge"
[4]: https://github.com/nunu1733/NunuLauncher/issues/89 "Issue #89"
[5]: https://github.com/nunu1733/NunuLauncher/issues/84#issuecomment-5342953676 "Issue #84 physical no-write decision"
[6]: https://github.com/nunu1733/NunuLauncher/blob/issue-84-recovery-preview-seam/specs/84-recovery-preview-seam/spec.md "Issue #84 accepted working specification"
[7]: ../13-safe-layout-application/spec.md "Spec 13"
[8]: ../../docs/adr/0003-organizer-recovery-point-storage.md "ADR-0003"
[9]: ../../docs/engineering/quality-strategy.md "Quality strategy"
[10]: ../../AGENTS.md "Repository rules"
