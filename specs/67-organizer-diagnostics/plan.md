# Implementation Plan: Privacy-safe organizer diagnostics journal and export

> Issue: #67
> Spec: [spec.md](./spec.md)
> Contract: [docs/engineering/organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
> Status: draft
> Updated: 2026-08-18

## Current evidence

### Existing diagnostics contract

- The accepted diagnostics contract (`docs/engineering/organizer-diagnostics.md`) defines the full `RunEvent` model (§3), phase taxonomy (§4), `ApplyStage` A0–A8 (§4.2), `ErrorEntry` family/code (§5), `PlanSummary`/`ApplySummary` counts (§6), data classification (§7), retention (§8), export (§9), logcat (§10), and restart correlation (§11). All normative field tables are final and this plan implements them.

- Representative fixtures D-01–D-10 (§13) and negative fixture D-09 (§13) define the exact serialization shape and non-containment ground truth.

### Existing production types (used as projection sources, not changed)

- **Planning result types** (`PlanningResult`, `Planned`, `Rejected.Invalid`, `Rejected.Impossible`, `PreserveReason`, `WarningCode`, `UnplacedReason`, `Confidence`, `RejectionCode`, `PlannedPlacement`): `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` and sibling files. Public planning types are unchanged by this Issue.

- **Application/recovery result types** (`ApplyResult`, `ApplyFailure`, `PreWriteRejection`, `RecoveryResult`, `RecoveryRejection`, `RecoveryFailure`, `AuthoritativeState`, `RunId`, `RecoveryPointId`): `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt`. Public application/recovery types are unchanged.

- **Lifecycle reconciler outcomes** (`ReconciliationPublicResult`, `ReconciliationSummary`, `ReconcilerRecord`, `LifecycleState`): `lawnchair/src/app/lawnchair/organizer/application/lifecycle/LifecycleReconciler.kt`, `LifecycleState.kt`. These are the projection source for `RESTART_RECONCILED` events.

- **ApplyProtocol** (`lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt`): Orchestrates A0–A8. Attachment points for `applyStage` events already exist as protocol phases.

- **RestartReconciler** (`lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt`): Invoked per process start. Its `ReconciliationSummary` output is the projection source for `RESTART_RECONCILED`.

- **LayoutApplicationModule** (`lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt`): Composition root; `apply()`/`recover()`/`reconcileAtStart()` entry points. The diagnostics port is wired here.

### Existing infrastructure

- `kotlinx.serialization.json` is already a dependency at the root build.gradle (`build.gradle:432`), and the Kotlin serialization plugin is applied (`build.gradle:16`). The `lawnchair` subproject inherits it. No dependency addition is needed.

- `res/xml/backupscheme.xml` explicitly lists only `launcher.db`, grid DBs, shared preferences, and `downgrade_schema.json`. A new diagnostics journal file in `context.filesDir` is not included and therefore excluded from backup by default. A contract test confirms this.

- Unit test source set: `tests/unit/app/lawnchair/organizer/` with subdirectories `application/`, `planning/`, `locks/`. Organizer instrumentation tests: `tests/organizer-instrumentation/`.

- Preferences UI: `lawnchair/src/app/lawnchair/ui/preferences/` with Compose destinations in `destinations/`, navigation routes in `navigation/PreferenceRoutes.kt`, and navigation wiring in `navigation/PreferenceNavigation.kt`.

### No existing diagnostics module

- `lawnchair/src/app/lawnchair/organizer/diagnostics/` does not exist yet. It is created by this plan as a new module per DESIGN.md §9.

## Design

### Modules and interfaces

The diagnostics module lives under `lawnchair/src/app/lawnchair/organizer/diagnostics/` and is a leaf module: it depends on the public application/recovery/planning types (as projection sources) but exposes no seam back to them. The module is consumed by:

1. **ApplyProtocol** — emits `applyStage` events through a diagnostics port at the existing A0–A8 phases.
2. **RestartReconciler** — emits `RESTART_RECONCILED` events through the same port.
3. **LayoutApplicationModule** — owns the diagnostics port instance and wires it into the composition root.
4. **Future run orchestrators** (Issues #52/#53/#55) — emit UI/run phase events through the same port.

The diagnostics module has **no internal interface that the rest of the organizer depends on for correctness**. Diagnostics are fail-open with respect to the organizer operation. The port is a simple `emit(event: RunEvent)` seam.

#### Diagnostics module layout

```
lawnchair/src/app/lawnchair/organizer/diagnostics/
├── model/                  # Closed RunEvent types (diagnostics-owned, not public)
│   ├── RunEvent.kt         # Top-level event + RunCorrelation, RecoveryContext, ReconciliationContext, RunVersions, DeviceProfileSummary
│   ├── PhaseCode.kt        # Enum from contract §4.1
│   ├── ApplyStage.kt       # Enum A0–A8 (diagnostics-owned, contract §4.2)
│   ├── ErrorEntry.kt       # ErrorFamily enum + ErrorEntry data class (contract §5)
│   ├── PlanSummary.kt      # Data class (contract §6.1)
│   ├── ApplySummary.kt     # Data class (contract §6.2)
│   └── Trigger.kt          # Enum from contract §3
├── journal/
│   ├── JournalStore.kt     # Append-only file store: open, append, read-back, retention, corruption reset
│   ├── JournalSequence.kt  # Durable monotonic sequence (persisted in a separate small file)
│   ├── RetentionPolicy.kt  # Lazy retention: 10 runs, 7 days, 512 KiB, unresolved protection
│   └── Serializer.kt       # kotlinx.serialization JSON codec for RunEvent (schema version 1)
├── projection/
│   ├── PlanningProjection.kt      # PlanningResult / Rejected -> RunEvent
│   ├── ApplyProjection.kt         # ApplyResult / ApplyProtocol A0–A8 state -> RunEvent
│   ├── RecoveryProjection.kt      # RecoveryResult -> RunEvent
│   └── ReconciliationProjection.kt # RestartReconciler outcome -> RunEvent
├── logger/
│   └── DiagnosticsLogger.kt       # Single-tag logcat sink (DEBUG/WARN, release failure-only)
├── export/
│   ├── ExportWriter.kt            # header + line-delimited JSON via SAF CreateDocument / share Intent
│   └── ExportUi.kt                # Compose @Composable for Settings entry (ClickablePreference)
└── DiagnosticsPort.kt             # Simple emit(RunEvent) interface, NOOP implementation for fail-open
```

#### Projection layer (seam)

The projection layer is a stateless set of Kotlin extension functions or top-level functions that consume existing public types and produce `RunEvent` values. No type is changed in the `application/` or `planning/` modules.

| Projection source | Target event | Key fields |
|---|---|---|
| `PlanningResult(outcome=Planned(...))` | `PLANNED` | `PlanSummary` from plan counts |
| `PlanningResult(outcome=Rejected.Invalid(...))` | `PLANNING_REJECTED` | `ErrorEntry(family=PLANNING_INVALID, code=RejectionCode.name, reasonTotal=reasons.size, additionalCodes=...)` |
| `PlanningResult(outcome=Rejected.Impossible(...))` | `PLANNING_IMPOSSIBLE` | `PlanSummary` with unplaced counts |
| `ApplyResult.NoChanges` | `APPLY_NO_CHANGES` | No summary |
| `ApplyResult.Applied` | `APPLY_COMMITTED` then `APPLY_VERIFIED` | `ApplyStage` from protocol, `ApplySummary` from action counts |
| `ApplyResult.Rejected` | `APPLY_REJECTED` or `CHECKPOINT_REJECTED` | `ApplyStage`, `ErrorEntry` from `PreWriteRejection` |
| `ApplyResult.RolledBack` | `APPLY_ROLLED_BACK` | `ApplyStage`, `ErrorEntry` from `ApplyFailure` |
| `ApplyResult.Recovered` | `APPLY_RECOVERED` | `ApplyStage`, `ErrorEntry` |
| `ApplyResult.Unresolved` | `APPLY_UNRESOLVED` | `ApplyStage`, `ErrorEntry` |
| `ApplyResult.RecoveryFailed` | `APPLY_RECOVERY_FAILED` | `ApplyStage`, `ErrorEntry` |
| `ApplyResult.ConcurrentRun` | `CONCURRENT_RUN_REJECTED` | No summary |
| `RecoveryResult.Restored` | `RECOVERY_RESTORED` | `RecoveryContext` |
| `RecoveryResult.NotRestorable` | `RECOVERY_REJECTED` | `ErrorEntry` from `RecoveryRejection` |
| `RecoveryResult.RestoreFailed` | `RECOVERY_FAILED` | `ErrorEntry` from `RecoveryFailure` |
| `RecoveryResult.WriterBusy` | `RECOVERY_WRITER_BUSY` | No summary |
| `RecoveryResult.ConcurrentRun` | `RECOVERY_CONCURRENT` | No summary |
| `RestartReconciler.ReconciliationSummary.Resolved` | `RESTART_RECONCILED` per entry | `ReconciliationContext` with `subjectRunId`, `priorLifecycle`, `classification`, `resultingLifecycle` |
| `RestartReconciler.ReconciliationSummary.Clean` | (no event) | No unresolved state |
| `RestartReconciler.ReconciliationSummary.Failed` | (no event) | Failed reconciliation surfaces no event |

**Every current variant** in the planning rejection/result enums and application/recovery result enums is enumerated by a `when` exhaustiveness check in the projection layer. Adding a new variant later requires a projection-test update; the `when` is exhaustive and Kotlin compilation will fail if a new variant is unhandled.

#### Attachment points (seam)

The existing `ApplyProtocol` and `RestartReconciler` classes receive a constructor parameter `diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP`. The `LayoutApplicationModule` production composition wires the real `DiagnosticsPort` backed by `JournalStore` and `DiagnosticsLogger`. The port is an interface:

```kotlin
fun interface DiagnosticsPort {
    fun emit(event: RunEvent)
    companion object {
        val NOOP = DiagnosticsPort {}
    }
}
```

Attachment points:

| Class | Method | Phase(s) emitted | Precondition for persist |
|---|---|---|---|
| `ApplyProtocol` | `apply()` entry | `RUN_STARTED` (carries `RunVersions`, `DeviceProfileSummary`) | Before A0 mutex check |
| `ApplyProtocol` | Before A0 return `ConcurrentRun` | `CONCURRENT_RUN_REJECTED` | After mutex contention |
| `ApplyProtocol` | After A1 (action materialization) | Can emit stage-bound events | Between phases |
| `ApplyProtocol` | After A2 (precondition check) | `CHECKPOINTED` / `CHECKPOINT_REJECTED` / `APPLY_REJECTED` | Before A4/A5 dangerous step |
| `ApplyProtocol` | After A4 (checkpoint) | `CHECKPOINTED` with `pointId` | Before A5 Launcher transaction |
| `ApplyProtocol` | After A5 (in-transaction recheck) | `APPLY_REJECTED` with stage | Before or after recheck |
| `ApplyProtocol` | After A6 (commit) | `APPLY_COMMITTED` with `applyStage=A6` | Before `COMMITTED_UNVERIFIED` mark |
| `ApplyProtocol` | After A7 (reload/verify) | `APPLY_VERIFIED` / `APPLY_ROLLED_BACK` / `APPLY_RECOVERED` / etc. | After outcome known |
| `ApplyProtocol` | After A8 (verification) | `APPLY_VERIFIED` with `ApplySummary` | After verification |
| `RestartReconciler` | `reconcileAll()` each record | `RESTART_RECONCILED` per record | After classification, before retention |
| `LayoutApplicationModule` | `apply()` | `RUN_STARTED` (trigger, runMode, versions, device profile) | Before delegating to `ApplyProtocol` |
| `LayoutApplicationModule` | `recover()` | `RECOVERY_REQUESTED` | Before delegating to `RecoveryProtocol` |

The diagnostics port is called **synchronously** before the next dangerous step. The `JournalStore.append()` blocks the caller until the event is durably written (or fails). Logcat rendering happens only after a successful append.

### Data flow

```
Planner/Application/Recovery result
    |
    v
Projection layer (stateless functions)
    |
    v
RunEvent (model types with @Serializable annotations)
    |
    v
DiagnosticsPort.emit(event)
    |
    +---> JournalStore.append(event)     [append-only file, synchronous]
    |         |
    |         +---> Serializer.encodeToByteArray(event)
    |         +---> Write length-prefixed bytes + newline to journal file
    |         +---> Pre-append retention check (lazy)
    |         +---> fsync (if write succeeded)
    |
    +---> if append succeeded:
              DiagnosticsLogger.log(event)   [logcat, single tag]
```

On journal append failure:
- The event is NOT rendered to logcat.
- No raw log fallback is produced.
- The organizer operation continues normally (fail-open).
- The `DiagnosticsPort` implementation catches the exception and silently continues.

### Alternatives rejected

- **Separate SQLite DB for journal.** An append-only file with length-prefixed JSON records is simpler, avoids schema migration, and provides the same durability (fsync per append). The recovery DB already handles recovery-point persistence; the journal is a different concern.

- **Shared logcat as the primary store.** Logcat is not durable across process restart and is cleared by the user; the journal is the durable source of truth.

- **In-memory buffer with periodic flush.** Violates the synchronous-persist-before-dangerous-step requirement. The spec requires the event to be durable before the next step proceeds.

- **Adding a telemetry/network transport.** Explicitly excluded by the contract (§12). No permission, no network dependency, no background worker.

- **Changing public planner/application types.** The projection layer isolates the diagnostics module from the public types. No public type is changed.

## Change set

### New files (diagnostics module)

| Area | File | Responsibility |
|---|---|---|
| model | `model/RunEvent.kt` | `RunEvent`, `RunCorrelation`, `RecoveryContext`, `ReconciliationContext`, `RunVersions`, `DeviceProfileSummary` — all with `@Serializable` |
| model | `model/PhaseCode.kt` | `PhaseCode` enum (closed set from contract §4.1) |
| model | `model/ApplyStage.kt` | `ApplyStage` enum with `A0`–`A8` entries |
| model | `model/ErrorEntry.kt` | `ErrorFamily` enum + `ErrorEntry` data class |
| model | `model/PlanSummary.kt` | `PlanSummary` data class (contract §6.1) |
| model | `model/ApplySummary.kt` | `ApplySummary` data class (contract §6.2) |
| model | `model/Trigger.kt` | `Trigger` enum (`MANUAL_FULL`, `ONBOARDING_PROPOSAL`, `INCREMENTAL_PROPOSAL`) |
| journal | `journal/Serializer.kt` | `kotlinx.serialization` JSON codec for schema version 1; `RunEventSerializer` object |
| journal | `journal/JournalSequence.kt` | Durable monotonic sequence persisted in a separate small file (e.g. `journal_seq`). On open, read last value; increment on each append. Corruption resets to 1. |
| journal | `journal/JournalStore.kt` | Append-only file (`organizer_diagnostics.journal`). Exposes `open()`, `append(RunEvent): Boolean`, `readAll(): Sequence<RunEvent>`, `prune()`, `close()`. On corruption: reset file and sequence, start fresh. |
| journal | `journal/RetentionPolicy.kt` | Lazy retention evaluation: return eligible runs for pruning given current state. Pure logic; no I/O. |
| projection | `projection/PlanningProjection.kt` | `PlanningResult` -> `RunEvent` projection functions |
| projection | `projection/ApplyProjection.kt` | `ApplyResult`, `ApplyFailure`, `PreWriteRejection` -> `RunEvent` projection |
| projection | `projection/RecoveryProjection.kt` | `RecoveryResult` -> `RunEvent` projection |
| projection | `projection/ReconciliationProjection.kt` | `RestartReconciler.ReconciliationSummary` + `ReconciliationPublicResult` -> `RunEvent` |
| logger | `logger/DiagnosticsLogger.kt` | Single-tag `OrganizerDiag` logcat sink. `log(event)` outputs only on successful persist. |
| export | `export/ExportWriter.kt` | Header + line-delimited JSON. `writeTo(context, uri)` using `ContentResolver` openOutputStream. `exportToShare(context)` for share Intent. |
| export | `export/ExportUi.kt` | `@Composable` `OrganizerDiagnosticsExportPreference()` — a `ClickablePreference` that triggers SAF CreateDocument or share flow. |
| port | `DiagnosticsPort.kt` | `fun interface DiagnosticsPort { fun emit(event: RunEvent) }` with `NOOP` companion. |

### Changed files (attachment points)

| File | Change |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt` | Add `diagnosticsPort: DiagnosticsPort` constructor parameter. Emit events at each A0–A8 phase boundary. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt` | Add `diagnosticsPort: DiagnosticsPort` constructor parameter. Emit `RESTART_RECONCILED` per reconciled record. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | Wire `DiagnosticsPort` backed by `JournalStore` + `DiagnosticsLogger`. Pass to `ApplyProtocol` and `RestartReconciler`. |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | Add `data object OrganizerDiagnosticsExport : PreferenceRoute` |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | Add `composable<OrganizerDiagnosticsExport>` route in `NavHost` |
| (preferences dashboard or debug menu) | Add `ClickablePreference` entry pointing to the export route, or a composable in the debug menu / dedicated organizer section. |

### Test files

#### Unit tests (`tests/unit/app/lawnchair/organizer/diagnostics/`)

| File | AC coverage | Responsibility |
|---|---|---|
| `model/RunEventSerializationTest.kt` | AC-67-01, D-01–D-10 | Serialization round-trip: every field round-trips through JSON; free-form fields are unrepresentable; D-09 strings are absent from serialized bytes. |
| `model/ApplyStageTest.kt` | AC-67-01 | `ApplyStage` enum values are exactly A0–A8; no extra values. |
| `journal/JournalStoreTest.kt` | AC-67-02, AC-67-07 | Append/reopen/restart with monotonic sequence; wall-clock rollback does not affect ordering; corruption reset; write failure does not throw to caller. |
| `journal/JournalSequenceTest.kt` | AC-67-02 | Monotonic sequence survives process restart; sequence file corruption resets to 1. |
| `journal/RetentionPolicyTest.kt` | AC-67-06 | 10-run cap, 7-day cap, 512 KiB cap, unresolved protection, resolution-then-prune ordering. |
| `projection/PlanningProjectionTest.kt` | AC-67-03, D-01–D-03 | Every `Planned`, `Rejected.Invalid`, `Rejected.Impossible` variant maps to expected phase/error/summary. |
| `projection/ApplyProjectionTest.kt` | AC-67-03, D-04–D-06 | Every `ApplyResult` variant maps to expected phase/stage/error. |
| `projection/RecoveryProjectionTest.kt` | AC-67-03, D-08 | Every `RecoveryResult` variant maps to expected phase/error. |
| `projection/ReconciliationProjectionTest.kt` | AC-67-03, D-07 | `RESTART_RECONCILED` with correct `ReconciliationContext`. |
| `projection/ProjectionExhaustivenessTest.kt` | AC-67-03 | Compile-time: every current planning/application/recovery enum variant is handled by a `when` expression; no silent fall-through. |
| `logger/DiagnosticsLoggerTest.kt` | AC-67-10 | Single tag `OrganizerDiag`; DEBUG for ordinary phases, WARN for terminal failures; release build failure-only; no output before persist. |
| `export/ExportWriterTest.kt` | AC-67-09, D-10 | Header + line-delimited JSON ordering; each exported event matches journal field set; cancel/write failure leaves journal intact. |
| `export/ExportCancellationTest.kt` | AC-67-09 | Export cancellation does not mutate journal or organizer state. |
| `integration/DiagnosticsContractTest.kt` | AC-67-05, D-09 | D-09 negative strings are absent from journal bytes, export bytes, and logcat renderer output (debug and release configurations). |
| `integration/DiagnosticsFailOpenTest.kt` | AC-67-07 | Injected journal write failure, corruption, logger failure do not change planner/application results and do not mutate layout/recovery state. |
| `integration/BackupExclusionTest.kt` | AC-67-11 | Journal file is absent from `backupscheme.xml` allowlist and from `LawnchairBackup.getFiles()` return set. |
| `integration/NoTransportContractTest.kt` | AC-67-12 | Source/build contract evidence: no `INTERNET` permission, no telemetry/network dependency, no upload worker, no automatic recipient selection. |

#### Instrumentation tests (`tests/organizer-instrumentation/app/lawnchair/organizer/diagnostics/`)

| File | AC coverage | Responsibility |
|---|---|---|
| `JournalStoreLifecycleTest.kt` | AC-67-02, AC-67-07 | Real app-private file I/O: append/survive-process-restart/journalSequence-continuity; corruption recovery. |
| `ApplyAttachmentLifecycleTest.kt` | AC-67-04 | Real `ApplyProtocol` + `DiagnosticsPort` test: prove required checkpoint/apply events carry correct A0–A8 stage at existing Issue #13 seams. |
| `RestartReconcilerAttachmentTest.kt` | AC-67-04 | Real `RestartReconciler` + `DiagnosticsPort` test: prove startup reconciliation emits `RESTART_RECONCILED` with accepted correlation. |
| `ExportDestinationIntegrationTest.kt` | AC-67-08, AC-67-09 | UI/integration: export starts only from explicit Settings action; SAF CreateDocument / share flow; cancellation/failure leaves journal intact. |
| `SettingsExportAccessibilityTest.kt` | AC-67-13 | Compose semantics test: localized accessible label, TalkBack reachable, keyboard/switch navigation, 200% font scaling. |

### New files in existing test source sets

| File | Location | Responsibility |
|---|---|---|
| `FakeJournalStore.kt` | `tests/unit/app/lawnchair/organizer/diagnostics/` | In-memory `JournalStore` for unit tests (recording mode + fault injection). |
| `FakeDiagnosticsLogger.kt` | `tests/unit/app/lawnchair/organizer/diagnostics/` | Recording logger for unit tests (capture last N events, tag, level). |

## Migration and recovery

- **No DB migration.** The diagnostics journal is a new file in `context.filesDir` (`organizer_diagnostics.journal`). It is created empty on first open. The sequence file (`journal_seq`) is also new.
- **No schema change** to the Launcher DB, recovery DB, or any existing table.
- **No permission change** (`INTERNET`, `MANAGE_DOCUMENTS`, etc.).
- **No backup/restore change.** The journal file is not in `res/xml/backupscheme.xml` or `LawnchairBackup.getFiles()`. A contract test (AC-67-11) proves exclusion.
- **Rollback**: The diagnostics module is additive. Reverting the code removes the journal file (no orphaned data affects the organizer). No migration is needed.
- **Feature disable**: If the diagnostics module is not wired, `DiagnosticsPort.NOOP` is used and no journal is created. The organizer runs normally.

## Verification

### Acceptance criteria mapping

| AC | Test evidence | Class |
|---|---|---|
| AC-67-01 | `RunEventSerializationTest`, `ApplyStageTest` | Unit |
| AC-67-02 | `JournalStoreTest`, `JournalSequenceTest`, `JournalStoreLifecycleTest` | Unit + instrumentation |
| AC-67-03 | `PlanningProjectionTest`, `ApplyProjectionTest`, `RecoveryProjectionTest`, `ReconciliationProjectionTest`, `ProjectionExhaustivenessTest` | Unit |
| AC-67-04 | `ApplyAttachmentLifecycleTest`, `RestartReconcilerAttachmentTest` | Instrumentation |
| AC-67-05 | `DiagnosticsContractTest` (D-09 non-containment) | Unit |
| AC-67-06 | `RetentionPolicyTest` | Unit |
| AC-67-07 | `DiagnosticsFailOpenTest`, `JournalStoreTest` (injected failure), `JournalStoreLifecycleTest` (corruption) | Unit + instrumentation |
| AC-67-08 | `ExportDestinationIntegrationTest` | Instrumentation |
| AC-67-09 | `ExportWriterTest`, `ExportCancellationTest`, `ExportDestinationIntegrationTest` | Unit + instrumentation |
| AC-67-10 | `DiagnosticsLoggerTest` | Unit |
| AC-67-11 | `BackupExclusionTest` | Unit |
| AC-67-12 | `NoTransportContractTest` | Unit |
| AC-67-13 | `SettingsExportAccessibilityTest` | Instrumentation |
| AC-67-14 | Standard CI verification (spotless, compile, unit tests, debug APK, repo-contract) | CI |

### Commands

```bash
# Format and lint
./gradlew spotlessCheck

# Organizer unit tests (diagnostics module tests are under app.lawnchair.organizer.diagnostics)
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.diagnostics.*'

# All organizer unit tests (existing + diagnostics)
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.*'

# Compile instrumentation tests
./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac

# Debug APK build
./gradlew assembleLawnWithQuickstepGithubDebug

# Repository contract validation
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
```

### Vertical implementation sequence

1. **Model types** — `model/RunEvent.kt`, `model/PhaseCode.kt`, `model/ApplyStage.kt`, `model/ErrorEntry.kt`, `model/PlanSummary.kt`, `model/ApplySummary.kt`, `model/Trigger.kt`. Tests: `RunEventSerializationTest`, `ApplyStageTest`.

2. **Serializer** — `journal/Serializer.kt`. Tests: D-01–D-10 JSON round-trip, D-09 non-containment proof.

3. **Journal store** — `journal/JournalSequence.kt`, `journal/JournalStore.kt`, `journal/RetentionPolicy.kt`. Tests: `JournalStoreTest`, `JournalSequenceTest`, `RetentionPolicyTest`, `JournalStoreLifecycleTest` (instrumentation).

4. **Projection layer** — `projection/*.kt`. Tests: `PlanningProjectionTest`, `ApplyProjectionTest`, `RecoveryProjectionTest`, `ReconciliationProjectionTest`, `ProjectionExhaustivenessTest`.

5. **Diagnostics port + attachment points** — `DiagnosticsPort.kt`, changes to `ApplyProtocol.kt`, `RestartReconciler.kt`, `LayoutApplicationModule.kt`. Tests: `ApplyAttachmentLifecycleTest`, `RestartReconcilerAttachmentTest` (instrumentation).

6. **Logger** — `logger/DiagnosticsLogger.kt`. Tests: `DiagnosticsLoggerTest`.

7. **Export writer + Settings UI** — `export/ExportWriter.kt`, `export/ExportUi.kt`, `PreferenceRoutes.kt` + `PreferenceNavigation.kt` changes. Tests: `ExportWriterTest`, `ExportCancellationTest`, `ExportDestinationIntegrationTest`, `SettingsExportAccessibilityTest` (instrumentation).

8. **Integration contract tests** — `DiagnosticsContractTest` (D-09), `DiagnosticsFailOpenTest`, `BackupExclusionTest`, `NoTransportContractTest`. These validate the cross-cutting contract properties.

## Documentation updates

- [ ] spec.md: update status to `implemented` after PR merge.
- [ ] plan.md: update status to `implemented` after PR merge.
- [ ] DESIGN.md: no structural change required; the diagnostics module directory is already shown in DESIGN.md §9.

## Execution checklist

- [ ] Current behavior confirmed: no diagnostics module exists; no journal, logcat sink, or export surface exists.
- [ ] Tests fail for the missing behavior (vertical sequence).
- [ ] Minimal implementation completed per vertical slice.
- [ ] No DB migration, permission change, or network dependency added.
- [ ] Backup exclusion contract test passes.
- [ ] D-09 negative fixture contract test passes.
- [ ] Full relevant verification completed (spotless, compile, unit tests, instrumentation compile, debug APK, repo-contract).
- [ ] PR evidence recorded with `Closes #67`.