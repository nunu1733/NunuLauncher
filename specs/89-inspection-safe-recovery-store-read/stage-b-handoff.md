# Stage B implementation handoff: inspection-safe recovery-store read

> Issue: #89
>
> Stage A status: **Accepted**
>
> Initial implementation head: [`09d0f290fd`](https://github.com/nunu1733/NunuLauncher/commit/09d0f290fd). First post-test review remediation head: [`084d97ef5f`](https://github.com/nunu1733/NunuLauncher/commit/084d97ef5f). Current re-review remediation head: [`7c140632d0`](https://github.com/nunu1733/NunuLauncher/commit/7c140632d0).
>
> Baseline: [`9733d59450`](https://github.com/nunu1733/NunuLauncher/commit/9733d59450) — #89 stacked on the accepted #84 preview seam
>
> Scope of this handoff: **test execution and resulting remediation are delegated to a separate environment**.

## Implementation scope now on the branch

The implementation removes the #84 inspection-time live SQLite query path. Preview inspection now obtains one typed projection through the recovery store, which requires a matching in-process valid generation and reads only the final snapshot file with a bounded `FileInputStream`. It neither probes nor opens the recovery SQLite database during inspection.

| Concern | Implemented source boundary |
|---|---|
| Snapshot schema and integrity | `RecoveryInspectionSnapshot.kt` and `RecoveryInspectionSnapshotCodec.kt` provide a canonical, bounded binary envelope with a whole-file SHA-256 checksum. Only metadata required by preview classification is projected. |
| Publication | `RecoveryInspectionSnapshotPublisher.kt` uses the pinned AndroidX `AtomicFile` in `noBackupFilesDir/recovery-inspection`. Publication occurs after a `RecoveryStore` close/reopen authoritative read-back. |
| Inspection read | `RecoveryInspectionSnapshotReader.kt` accepts only exactly one final regular file. Missing, `.new`, `.bak`, unexpected entries, malformed content, I/O, and size-bound failures are unavailable and do not trigger cleanup. |
| Stale-snapshot prevention | `InspectionSnapshotFence.kt` provides `UNKNOWN`, `DIRTY`, `VALID(generation)`, and `INCOMPATIBLE` states. Ordinary mutation invalidates before writer work; only successful publication of the same generation restores `VALID`. Point-ID collision is handled as proven-no-commit. |
| Mutation coverage | `checkpoint`, lifecycle advance, `markApplying`, `markRestoring`, retention, and unused-point pruning flow through the fence/publication path. `readTombstone` no longer performs lazy purge writes. |
| Startup path | `RecoveryStartupStorageClassifier` inventories main DB, sidecars, and snapshot directory before any SQLite open. Only absent main DB with absent/empty snapshot directory is pristine; zero-length, invalid, unreadable, or residual-artifact states fail closed. |
| Reconciliation authority | `LayoutApplicationModule` binds the exact concrete `RunMutex` once to an opaque issuer. The issuer creates a method-scoped session only for its active lease; every session call rechecks lease identity and rejects closed, released, or foreign-mutex capability use before store work. |
| Source boundary | Ordinary apply/recover/preview protocols receive `RunMutexPort`, never the concrete `RunMutex`, issuer, or reconciliation session. `RecoveryStorePort` exposes only ordinary operations/projection read; privileged startup work is available only through `RecoveryStoreReconciliationSession`. |
| #84 integration | `RecoveryPreviewProtocol` reads the typed snapshot projection under the existing mutex-first sequence. The former inspection-only record/tombstone SQLite pair was removed. |

## Added or updated verification sources

These sources are present but **have not been executed in this environment**.

| Source | Intended evidence |
|---|---|
| `tests/unit/.../store/RecoveryInspectionSnapshotTest.kt` | Codec round trip and checksum rejection, companion inventory no-cleanup, fence no-commit rollback and uncertain-dirty behavior. |
| `tests/unit/.../protocol/RecoveryPreviewProtocolTest.kt` | Snapshot projection outcome and no-mutation preview regression coverage. |
| `tests/organizer-instrumentation/.../RecoveryStoreInspectionInstrumentationTest.kt` | Ordinary mutation rejects before version probe while `UNKNOWN`/`DIRTY`; legal mutation-start availability failure remains dirty; missing/corrupt/incompatible/residual-store fail-closed behavior; invalid final snapshot and `.new`/`.bak`/unexpected-entry no-cleanup; blocked-writer inspection; production-created checkpoint/WAL sidecars-present and sidecars-absent physical oracle with full file/inventory timestamp evidence. |
| `tests/organizer-instrumentation/.../RecoveryStoreLifecycleTest.kt` | Explicit lease-bound startup rehydration before production writer lifecycle fixtures. |
| `tests/unit/.../store/RecoveryStartupStorageClassifierTest.kt` | Pristine versus residual sidecar/snapshot, zero-length, and invalid-main classification before SQLite open. |
| `tests/unit/.../protocol/RecoveryStoreReconciliationSessionTest.kt` | Closed/released/foreign-mutex session rejection and one-mutex issuer binding. |
| `tests/unit/.../protocol/RestartReconcilerTest.kt` and `.../RestartReconcilerDiagnosticsTest.kt` | Method-scoped reconciliation session injection. |

## Required delegated validation

Run the following without altering the implementation contract. If compilation or test failures expose a defect, retain the failing command, stack trace, device/API/ABI, and commit SHA with the defect report.

```bash
# Static repository contract (already passed locally; re-run in the test environment)
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py

# Focused JVM coverage
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotTest' \
  --tests 'app.lawnchair.organizer.application.store.RecoveryStartupStorageClassifierTest' \
  --tests 'app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationSessionTest' \
  --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest' \
  --tests 'app.lawnchair.organizer.application.protocol.RestartReconcilerTest' \
  --tests 'app.lawnchair.organizer.diagnostics.projection.RestartReconcilerDiagnosticsTest'

# Formatting and broader compilation/build evidence
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest
./gradlew assembleLawnWithQuickstepGithubDebug

# Ordinary-mutation fence ordering and physical no-write instrumentation matrix, separately on API 26 and API 35
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest
```

The instrumentation report must include the device image, API level, ABI, APK revision, and before/after complete inventory for the main recovery DB, `-wal`, `-shm`, `-journal`, final snapshot, and snapshot-directory entries. For each covered path, record existence, type, length, SHA-256, mtime, and ctime where the device exposes it; atime is excluded. API 26 and API 35 both remain required under the accepted plan.

## Environment note

No Gradle test was executed in this sandbox. The build stopped before configuration because `com.gradle:develocity-gradle-plugin:4.3.1` could not be downloaded from the official plugin repository after three timed-out Gradle attempts; a direct official artifact retrieval also timed out. This is an environment/network limitation, not passing evidence.

## Completion rule

Do not mark Stage B implemented or resume final #84 acceptance until the delegated environment has supplied successful focused and broad build/test evidence, the API 26/API 35 physical oracle, formatting, build, CI final status, and the independent audit record required by `AGENTS.md`.
