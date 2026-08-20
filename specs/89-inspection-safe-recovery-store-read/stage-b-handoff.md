# Stage B implementation handoff: inspection-safe recovery-store read

> Issue: #89
>
> Stage A status: **Accepted**
>
> Implementation head: [`09d0f290fd`](https://github.com/nunu1733/NunuLauncher/commit/09d0f290fd)
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
| Startup path | `LayoutApplicationModule` serializes reconciliation with the concrete shared mutex. `RestartReconciler` receives the reconciliation-only store port; existing compatible storage rebuilds a projection and known incompatible storage maps to a typed reconciliation outcome. |
| Source boundary | Ordinary apply/recover/preview protocols receive `RunMutexPort`, never the concrete `RunMutex` or reconciliation store port. `RecoveryStorePort` exposes only projection read; rebuild and scope methods are confined to `RecoveryStoreReconciliationPort`. |
| #84 integration | `RecoveryPreviewProtocol` reads the typed snapshot projection under the existing mutex-first sequence. The former inspection-only record/tombstone SQLite pair was removed. |

## Added or updated verification sources

These sources are present but **have not been executed in this environment**.

| Source | Intended evidence |
|---|---|
| `tests/unit/.../store/RecoveryInspectionSnapshotTest.kt` | Codec round trip and checksum rejection, companion inventory no-cleanup, fence no-commit rollback and uncertain-dirty behavior. |
| `tests/unit/.../protocol/RecoveryPreviewProtocolTest.kt` | Snapshot projection outcome and no-mutation preview regression coverage. |
| `tests/organizer-instrumentation/.../RecoveryStoreInspectionInstrumentationTest.kt` | Missing/invalid-store fail-closed behavior; production-created checkpoint physical state; WAL sidecars-present no-write oracle. |
| `tests/organizer-instrumentation/.../RecoveryStoreLifecycleTest.kt` | Explicit startup rehydration before production writer lifecycle fixtures. |
| `tests/unit/.../protocol/RestartReconcilerTest.kt` and `.../RestartReconcilerDiagnosticsTest.kt` | Explicit reconciliation-only port injection. |

## Required delegated validation

Run the following without altering the implementation contract. If compilation or test failures expose a defect, retain the failing command, stack trace, device/API/ABI, and commit SHA with the defect report.

```bash
# Static repository contract (already passed locally; re-run in the test environment)
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py

# Focused JVM coverage
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotTest' \
  --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest' \
  --tests 'app.lawnchair.organizer.application.protocol.RestartReconcilerTest' \
  --tests 'app.lawnchair.organizer.diagnostics.projection.RestartReconcilerDiagnosticsTest'

# Formatting and broader compilation/build evidence
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest
./gradlew assembleLawnWithQuickstepGithubDebug

# Physical no-write instrumentation matrix, separately on API 26 and API 35
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest
```

The instrumentation report must include the device image, API level, ABI, APK revision, and before/after inventory for the main recovery DB, `-wal`, `-shm`, final snapshot, and snapshot-directory entries. API 26 and API 35 both remain required under the accepted plan.

## Environment note

No Gradle test was executed in this sandbox. The build stopped before configuration because `com.gradle:develocity-gradle-plugin:4.3.1` could not be downloaded from the official plugin repository after three timed-out Gradle attempts; a direct official artifact retrieval also timed out. This is an environment/network limitation, not passing evidence.

## Completion rule

Do not mark Stage B implemented or resume final #84 acceptance until the delegated environment has supplied successful focused and broad build/test evidence, the API 26/API 35 physical oracle, formatting, build, CI final status, and the independent audit record required by `AGENTS.md`.
