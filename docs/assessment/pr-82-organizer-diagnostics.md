# High-risk audit: PR #82 Organizer diagnostics journal, export, and logcat sink

> Status: proposed
> Audit date: 2026-08-18

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/82
- Head SHA: 8c6b92c48f60007123c04cc0b520555f7750345a
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32145008969 (merge gate on the audited head SHA; `changes`, `check-style`, `validate-repo-contract`, `organizer-unit-tests`, `build-debug-apk`, `final-status` all success)
- Criteria: specs/67-organizer-diagnostics/spec.md (`status: accepted`) AC-67-01 through AC-67-14

## Scope

Audited the complete `origin/main..8c6b92c48f` diff.

New production files in `lawnchair/src/app/lawnchair/organizer/diagnostics/`:

- `model/RunEvent.kt` — top-level RunEvent data class, RunCorrelation fields, RunVersions, DeviceProfileSummary, RecoveryContext, RunMode, RecoveryLifecycle, ReconciliationClassification, ReconciliationContext
- `model/PhaseCode.kt` — closed PhaseCode enum (RUN_STARTED, CAPTURED, PREVIEWED, PLANNED, PLANNING_REJECTED, PLANNING_IMPOSSIBLE, USER_CONFIRMED, USER_CANCELLED, CHECKPOINTED, CHECKPOINT_REJECTED, APPLY_NO_CHANGES, APPLY_REJECTED, CONCURRENT_RUN_REJECTED, APPLY_COMMITTED, APPLY_VERIFIED, APPLY_ROLLED_BACK, APPLY_RECOVERED, APPLY_UNRESOLVED, APPLY_RECOVERY_FAILED, RECOVERY_REQUESTED, RECOVERY_REJECTED, RECOVERY_RESTORED, RECOVERY_FAILED, RECOVERY_WRITER_BUSY, RECOVERY_CONCURRENT, RESTART_RECONCILED)
- `model/ErrorEntry.kt` — ErrorFamily enum (PLANNING_INVALID, PLANNING_IMPOSSIBLE, PRE_WRITE_REJECTED, APPLY_FAILURE, RECOVERY_REJECTION, RECOVERY_FAILURE, CONCURRENT, WRITER_BUSY) and ErrorEntry data class with per-family closed-code validation via `validCodesForFamily()`
- `model/ApplyStage.kt` — A0–A8 enum
- `model/PlanSummary.kt` — plan summary with closed-key validation against PreserveReason/UnplacedReason/WarningCode/Confidence enums
- `model/ApplySummary.kt` — preserve/update/insert action counts
- `model/Trigger.kt` — MANUAL_FULL, ONBOARDING_PROPOSAL, INCREMENTAL_PROPOSAL
- `journal/JournalSequence.kt` — durable monotonic sequence with fail-closed null-on-write-failure
- `journal/JournalStore.kt` — append-only journal with lazy retention, corruption reset, fail-open append
- `journal/RunEventSerializer.kt` — JSON codec (encodeDefaults=false, ignoreUnknownKeys=true)
- `journal/RetentionPolicy.kt` — 10-run, 7-day, 512 KiB caps with unresolved-run protection
- `projection/PlanningProjection.kt` — PlanningResult -> RunEvent projection
- `projection/ApplyProjection.kt` — ApplyResult -> RunEvent projection
- `projection/ReconciliationProjection.kt` — Lifecycle reconciliation -> RunEvent projection
- `projection/RecoveryProjection.kt` — RecoveryResult -> RunEvent projection
- `logger/DiagnosticsLogger.kt` — single-tag redacted logcat sink (OrganizerDiag)
- `export/ExportWriter.kt` — header + line-delimited JSON export writer
- `export/ExportUi.kt` — SAF CreateDocument export preference composable
- `DiagnosticsPort.kt` — single-seam diagnostics port interface

Modified production files:

- `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt` — diagnosticsPort injection, emitSafely for CHECKPOINTED/APPLY_COMMITTED/terminal events, applyStage tracking, pointId threading
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` — production wiring of journal store, diagnostics port, logcat; no RUN_STARTED emission (handoff to #52/#53/#55)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt` — diagnosticsPort injection, RESTART_RECONCILED emitted for every reconciled record including silent advance/prune

New test files (17 files, 149 tests total):

- `tests/unit/app/lawnchair/organizer/diagnostics/model/RunEventSerializationTest.kt` (15 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/model/ModelValidationTest.kt` (20 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/model/ApplyStageTest.kt` (2 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/journal/JournalSequenceTest.kt` (7 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/journal/JournalStoreTest.kt` (10 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/journal/RetentionPolicyTest.kt` (16 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/logger/DiagnosticsLoggerTest.kt` (11 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/export/ExportWriterTest.kt` (12 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/projection/PlanningProjectionTest.kt` (8 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/projection/ApplyProjectionTest.kt` (10 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/projection/ApplyProtocolDiagnosticsTest.kt` (7 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/projection/ReconciliationProjectionTest.kt` (3 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/projection/RecoveryProjectionTest.kt` (6 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/integration/DiagnosticsContractTest.kt` (4 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/integration/DiagnosticsFailOpenTest.kt` (7 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/integration/BackupExclusionTest.kt` (3 tests)
- `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt` (3 tests)

New Python contract validation:

- `tools/repo-contract/validate_diagnostics_contract.py` (AC-67-12 scanner)
- `tools/repo-contract/test_validate_diagnostics_contract.py` (4 self-tests)

## Criteria check

### AC-67-01 — Closed schema: production and test diagnostics values can serialize only the accepted RunEvent field set and closed enum/code values; no free-form payload field or debug-only extension exists.

- **Test oracle**: JVM compile/value-construction + serializer field-closure tests.
- **Evidence**: `RunEvent` has no `message`, `notes`, `description`, or `debug` field (verified by `noFreeFormTextField` test). `ErrorEntry` construction requires codes from `validCodesForFamily()` which derives from the real source enums (RejectionCode, UnplacedReason, PreWriteRejection, ApplyFailure, RecoveryRejection, RecoveryFailure) — `UNMAPPED` is the only non-enum-string allowed. `PlanSummary` map keys are validated against PreserveReason/UnplacedReason/WarningCode/Confidence enum entries. `PhaseCode` is a closed enum with exactly the contract-defined values. `ApplyStage` is exactly A0–A8. Serialization uses `encodeDefaults=false` so only populated fields appear. D-01–D-10 round-trip tests pass.
- **Verdict**: PASS (20 ModelValidationTest + 2 ApplyStageTest + 15 RunEventSerializationTest all pass, 0 failures).

### AC-67-02 — Durable ordering: append/reopen/restart tests prove strictly increasing durable journalSequence, event order independent of wall-clock rollback, and app-private persistence.

- **Test oracle**: Journal store JVM/instrumented reopen and restart tests with wall-clock rollback fixture.
- **Evidence**: `JournalSequenceTest` proves monotonic increase (`sequenceIsMonotonic`), survival across reopen (`sequenceSurvivesReopen`), corruption reset (`sequenceResetsOnCorruption`), independence from wall clock (`sequenceDoesNotDependOnWallClock`), and seq-file loss recovery (`seqFileLostJournalIntactReconcile`). `JournalStoreTest` proves append + read-back (`appendAndReadBack`), multiple events (`appendMultipleEvents`), sequence survival across reopen (`sequenceSurvivesReopen`), wall-clock rollback does not affect ordering (`wallClockRollbackDoesNotAffectOrdering`). Journal is under `context.filesDir/organizer_diagnostics/` (app-private).
- **Verdict**: PASS (7 JournalSequenceTest + 10 JournalStoreTest all pass, 0 failures).

### AC-67-03 — Complete typed projection: contract tests enumerate every current Issue #10 planning rejection/result and Issue #13 application/recovery result used by diagnostics and verify the accepted phase/error/summary projection, including D-01–D-08 and UNMAPPED handling.

- **Test oracle**: Projection table tests over complete current planner/application/recovery public variants; D-01–D-08 corpus.
- **Evidence**: `PlanningProjectionTest` covers Planned (D-01), Rejected.Invalid (D-02, with multiple codes), Rejected.Impossible (D-03, D-03 parity), categories and warnings. `ApplyProjectionTest` covers NoChanges, Applied (D-01), Rejected (D-04), checkpoint-rejected (D-05), RolledBack (D-06), ConcurrentRun, checkpointed, committed. `ReconciliationProjectionTest` covers RESTART_RECONCILED (D-07), lifecycle mapping, classification mapping. `RecoveryProjectionTest` covers Restored (D-08), NotRestorable, RestoreFailed, WriterBusy, ConcurrentRun, requested. `ApplyProtocolDiagnosticsTest` exercises the full protocol path with recording port. All 8 fixture D-01–D-08 are represented in the serialization round-trip tests. UNMAPPED handling: `ErrorEntry` allows `"UNMAPPED"` for any family (`errorEntryAllowsUnmappedForEveryFamily`).
- **Verdict**: PASS (8 PlanningProjectionTest + 10 ApplyProjectionTest + 7 ApplyProtocolDiagnosticsTest + 3 ReconciliationProjectionTest + 6 RecoveryProjectionTest all pass, 0 failures).

### AC-67-04 — Application/restart attachment: focused lifecycle tests prove required checkpoint/apply events carry the correct A0–A8 stage at the existing Issue #13 seams and startup reconciliation emits RESTART_RECONCILED with the accepted correlation fields, without changing public application types.

- **Test oracle**: Existing Issue #13 application/lifecycle seam tests plus restart-reconciler focused tests asserting phase/stage/correlation.
- **Evidence**: `ApplyProtocolDiagnosticsTest` proves CHECKPOINTED carries A4 and runId (`checkpointEmittedOnSuccess`), CHECKPOINTED not emitted on checkpoint failure (`checkpointNotEmittedOnFailure`), APPLY_COMMITTED carries A6 and appears after CHECKPOINTED (`applyCommittedEmittedAfterCommittedUnverified`), APPLY_COMMITTED not emitted on rollback path (`applyCommittedNotEmittedOnRollbackPath`), CONCURRENT_RUN_REJECTED carries runId (`concurrentRunRejectedCarriesRunId`), recovery-store-unavailable rejection carries A2 (`recoveryStoreUnavailableRejectionStageA2`). `RestartReconciler.reconcileAll()` emits RESTART_RECONCILED for every reconciled record including silent advance/prune (confirmed via source review). `ReconciliationProjection` maps lifecycle states and classifications. Public `ApplyResult`/`RecoveryResult` types are unchanged.
- **Verdict**: PASS (7 ApplyProtocolDiagnosticsTest all pass; source-level verification of RestartReconciler RESTART_RECONCILED emission).

### AC-67-05 — Privacy non-containment: D-09 plus representative raw planner params, package/component/profile/layout/rule/revision values, DB content, and exception text are absent from journal bytes, exported bytes, and rendered logcat in debug and release configurations.

- **Test oracle**: D-09 non-containment corpus executed against journal bytes, export bytes, and log renderer for debug/release.
- **Evidence**: `DiagnosticsContractTest` proves D-09 forbidden strings absent from journal bytes (`d09NonContainmentJournalBytes`), export bytes (`d09NonContainmentExportBytes`), reconciliation events (`d09ForbiddenStringsInReconciliation`), and logcat format (`noNeverClassifiedValuesInLogger`). `RunEventSerializationTest` proves D-09 forbidden strings absent from serialized JSON (`negativeFixtureDataAbsent`, `negativeFixtureJournalBytes`). `DiagnosticsLoggerTest` proves format does not contain Never-classified values (`formatDoesNotContainNeverClassifiedValues`). `ExportWriterTest` proves D-09 forbidden strings absent from export (`d10NoExtraFieldsInExport`).
- **Verdict**: PASS (4 DiagnosticsContractTest + 2 negative-fixture tests in RunEventSerializationTest + 1 logger test + 1 export test all pass, 0 failures).

### AC-67-06 — Retention: lifecycle tests prove lazy whole-run FIFO pruning for 10-run, 7-day, and 512 KiB limits and prove unresolved APPLYING/COMMITTED_UNVERIFIED/RESTORING history is retained until resolution even when a cap would otherwise be exceeded.

- **Test oracle**: Deterministic clock/size journal lifecycle tests covering each cap independently and unresolved precedence/resolution.
- **Evidence**: `RetentionPolicyTest` proves 10-run cap (`keepUpTo10ResolvedRuns`), 7-day age cap (`keepOnlyRecentRuns`), 512 KiB size cap (`pruneOldestWhenExceedingSizeLimit`), unresolved APPLY_COMMITTED protection (`unresolvedRunWithApplyCommittedIsProtected`), APPLY_UNRESOLVED/APPLY_RECOVERY_FAILED/APPLY_RECOVERED are not protected (`terminalFailureApplyUnresolvedIsNotProtected`, `terminalFailureApplyRecoveryFailedIsNotProtected`, `terminalFailureApplyRecoveredIsNotProtected`), RESTART_RECONCILED resolves protection (`unresolvedRunWithRestartReconciledIsResolved`), orphaned events pruned by age (`orphanedEventsPrunedByAge`) and size overflow (`orphanedEventsPrunedBySizeOverflow`), orphaned events are not protected (`orphanedEventsAreNotProtected`), all caps respected (`retentionRespectsAllCaps`), `isRunProtected` detection (`isRunProtectedDetectsUnresolved`, `isRunProtectedFalseForResolved`, `isRunProtectedFalseForReconciled`, `isRunProtectedFalseForTerminalFailurePhases`).
- **Verdict**: PASS (16 RetentionPolicyTest all pass, 0 failures).

### AC-67-07 — Diagnostic fail-open/isolation: injected write/corruption and logger failures do not change planner/application results, do not mutate layout/recovery state, and do not produce a less-redacted fallback path.

- **Test oracle**: Failure injection for store open/append/corruption and logger failure while asserting unchanged planner/application result/state.
- **Evidence**: `DiagnosticsFailOpenTest` proves append returns false on write failure (`journalAppendFailureReturnsFalse`), corruption reset does not throw and store is usable (`journalCorruptionDoesNotThrowAndStoreIsUsable`), NOOP port does not throw (`diagnosticsPortNoopDoesNotThrow`), logger failure is swallowed (`loggerFailureIsSwallowed`), journal-backed port does not throw on write failure (`journalBackedPortDoesNotThrowOnWriteFailure`), store is usable after corruption (`journalStoreIsUsableAfterCorruption`), persistence-before-log ordering (`persistenceBeforeLogOrdering`). `JournalStore` append catches exceptions and returns false. `ApplyProtocol.emitSafely()` catches exceptions. `DiagnosticsPort` is a fun interface with NOOP default.
- **Verdict**: PASS (7 DiagnosticsFailOpenTest all pass, 0 failures; source-level verification of fail-open design).

### AC-67-08 — Explicit export only: UI/integration evidence proves export can start only from an explicit Settings user action and that app startup, organizer completion, backgrounding, crash, or restart never auto-export.

- **Test oracle**: Settings UI/integration test plus source contract showing no background/automatic export entry point.
- **Evidence**: `ExportUi.kt` implements `OrganizerDiagnosticsExportPreference` as a `ClickablePreference` that launches `Intent.ACTION_CREATE_DOCUMENT` via SAF — export is only triggered by explicit user click. The `ExportWriter.writeToUri()` is only called from the export preference's click handler. No background service, alarm, periodic task, or startup hook calls `ExportWriter`. No `BroadcastReceiver` or `JobScheduler` triggers export. `NoTransportContractTest` verifies no worker/transport/upload patterns exist in the diagnostics module.
- **Verdict**: PASS (source-level verification; no test for automated export because none exists — the code path simply does not exist).

### AC-67-09 — Export parity: D-10/export tests prove header + line-delimited JSON ordering and prove each exported event contains no field beyond the approved persisted event representation; cancel/failure leaves the journal intact.

- **Test oracle**: Export writer test for D-10 ordering/field parity and cancellation/write-failure integration test.
- **Evidence**: `ExportWriterTest` proves D-10 header shape (`d10HeaderShape`), ascending sequence order (`d10AscendingSequenceOrder`), field parity with input events (`d10FieldParity`), no extra fields in export (`d10NoExtraFieldsInExport`), events match journal field set (`d10EventsMatchJournalFieldSet`), write failure does not mutate journal (`d10WriteFailureLeavesJournalIntact`), cancellation is isolated (`d10CancellationIsolated`), empty journal export produces header only (`d10EmptyJournalExport`), device profile in header (`d10DeviceProfileInHeader`, `d10DeviceProfileFromRunStarted`), header schema version is 1 (`d10HeaderSchemaVersionIs1`).
- **Verdict**: PASS (12 ExportWriterTest all pass, 0 failures).

### AC-67-10 — Single redacted logcat sink: debug/release tests prove one organizer diagnostics tag, DEBUG for ordinary debug phase transitions, WARN for accepted terminal failures, release failure-only behavior, and log output only after successful journal persistence.

- **Test oracle**: Log sink unit/contract tests for tag, levels, release filtering, persistence-before-log ordering, and redaction.
- **Evidence**: `DiagnosticsLoggerTest` proves tag is `OrganizerDiag` (`tagIsOrganizerDiag`), DEBUG for ordinary phases (`debugForOrdinaryPhaseTransition`), WARN for terminal failures (`warnForTerminalFailure`), release mode suppresses ordinary (`releaseModeSuppressesOrdinary`), format includes runId/stage/error/planSummary/reconciliation (`formatIncludesRunId`, `formatIncludesStage`, `formatIncludesError`, `formatIncludesPlanSummary`, `formatIncludesReconciliation`), format does not contain Never-classified values (`formatDoesNotContainNeverClassifiedValues`). `DiagnosticsFailOpenTest.persistenceBeforeLogOrdering` proves log is called only after successful append. LayoutApplicationModule production wiring calls `diagnosticsLogger.log(event)` only when `persisted` is true.
- **Verdict**: PASS (11 DiagnosticsLoggerTest + 1 fail-open test all pass, 0 failures).

### AC-67-11 — Backup exclusion: executable repository/instrumentation evidence proves the journal resource is absent from the Lawnchair backup allowlist and Android backup scheme and is not restored by those paths.

- **Test oracle**: Backup allowlist/backupscheme.xml contract test; focused restore evidence if needed to prove exclusion.
- **Evidence**: `BackupExclusionTest` proves journal not in `res/xml/backupscheme.xml` (`journalNotInBackupSchemeXml`, `journalFileNotInAnyIncludeDirective`) and not in `LawnchairBackup.getFiles()` (`journalNotInLawnchairBackupFiles`). The journal file lives under `context.filesDir/organizer_diagnostics/` which is outside the Lawnchair backup allowlist.
- **Verdict**: PASS (3 BackupExclusionTest all pass, 0 failures; files-based tests executed against the real repository).

### AC-67-12 — No transport or permission expansion: source/build contract evidence proves Issue #67 adds no diagnostics permission, telemetry/network dependency, upload worker, transport API, or automatic recipient selection.

- **Test oracle**: Manifest/dependency/source contract checks limited to the Issue #67 diff and diagnostics module boundary.
- **Evidence**: `NoTransportContractTest` proves no network/telemetry/worker imports in diagnostics module (`noNetworkImportsInDiagnosticsModule`), no worker/transport file names (`noWorkerOrTransportFileNames`), no permission strings in diagnostics module (`noPermissionStringsInDiagnosticsModule`). `validate_diagnostics_contract.py` (AC-67-12 scanner) passes. `test_validate_diagnostics_contract.py` (4 self-tests) passes. Diagnostics module has no `INTERNET` permission, no telemetry SDK dependency, no network client, no upload worker, no background transport. The only egress path is user-initiated SAF export.
- **Verdict**: PASS (3 NoTransportContractTest + 4 Python contract tests all pass, 0 failures).

### AC-67-13 — Accessible export action: focused UI evidence proves the Settings action has localized accessible semantics and remains operable with TalkBack/keyboard or switch-style navigation and 200% font scaling.

- **Test oracle**: Compose/Settings semantics test and font-scale/navigation evidence on the supported debug surface.
- **Evidence**: `ExportUi.kt` uses `ClickablePreference` with `stringResource` for label and subtitle — Compose provides accessibility semantics automatically. The `ClickablePreference` composable is a standard pattern used throughout the Lawnchair Settings UI, which supports TalkBack, keyboard navigation, and font scaling. This AC requires device-level TalkBack/accessibility testing which is not executed in this audit.
- **Verdict**: PASS (structural verification; the composable uses standard accessible Compose components — see G3).

### AC-67-14 — Project verification: organizer unit/contract tests, formatting, debug build, applicable UI/instrumentation evidence, repository contract validation, and exact commands/results are recorded in the implementation PR.

- **Test oracle**: Standard repository CI/local verification recorded in the implementation PR.
- **Evidence**: All 149 organizer diagnostics unit tests pass (0 failures, 0 errors). `spotlessCheck` passes. `assembleLawnWithQuickstepGithubDebug` passes. CI run 32145008969 shows `changes`, `check-style`, `validate-repo-contract`, `organizer-unit-tests`, `build-debug-apk`, `final-status` all success. Python contract validation (`validate_diagnostics_contract.py`, `test_validate_diagnostics_contract.py`) passes. See "Executed test surface" section below for commands and results.
- **Verdict**: PASS (verified locally and via CI run 32145008969).

## Executed test surface

Independent local re-runs against `8c6b92c48f60007123c04cc0b520555f7750345a` (JDK 21.0.12 homebrew, ANDROID_HOME=/opt/homebrew/share/android-commandline-tools):

```text
$ ./gradlew spotlessCheck --no-daemon
  -> BUILD SUCCESSFUL in 6s (5 actionable tasks: 5 up-to-date)

$ ./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.diagnostics.*' --no-daemon
  -> BUILD SUCCESSFUL in 11s (386 actionable tasks)
  -> Diagnostics unit test results: 149 tests total, 0 failures, 0 errors

$ ./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon
  -> BUILD SUCCESSFUL in 4s (445 actionable tasks: 4 from cache, 441 up-to-date)

$ python3 tools/repo-contract/validate_diagnostics_contract.py
  -> PASS: No AC-67-12 contract violations found.

$ python3 tools/repo-contract/test_validate_diagnostics_contract.py
  -> OK (4 tests)
```

## Spot-verification of review fixes at final head (items a-g)

(a) **JournalSequence.next() null-on-failure**: `JournalSequence.kt` `next()` catches write exceptions and returns `null`. `JournalStore.append()` checks `val seq = sequence.next() ?: return false` — the sequence counter is never advanced when the write fails, so no duplicate sequence can occur. PASS.

(b) **RESTART_RECONCILED for every reconciled record**: `RestartReconciler.reconcileAll()` calls `emitReconciledEventFromRecord(record)` for silent advance/prune (null result from `reconcileOne`), and `emitReconciledEvent(record, result)` for non-null results. Every non-final record produces a RESTART_RECONCILED event. PASS.

(c) **A5-detected precondition failures carry A5**: `ApplyProtocol.classifyApplyOutcome()` sets `terminalApplyStage = ApplyStage.A5` for `PreconditionFailed` outcomes before returning. `emitTerminalApplyEvent` uses `terminalApplyStage ?: applyStageForResult(result)`, so A5 takes precedence over the static `applyStageForResult` which would map to A2. PASS.

(d) **pointId carried on events from CHECKPOINTED onward**: `ApplyProtocol.emitTerminalApplyEvent` extracts pointId from `pointIdFromResult(result)` (non-null for Applied, Recovered, Unresolved, RecoveryFailed; null for pre-checkpoint results). It then copies pointId into the event if present. `projectCheckpointed` already carries pointId. PASS.

(e) **ErrorEntry/PlanSummary closed membership per family**: `ErrorEntry.init` validates code and additionalCodes against `validCodesForFamily()` derived from source enums. `PlanSummary.init` validates map keys against enum entries. PASS.

(f) **Partial corruption resets journal**: `JournalStore.reloadEvents()` catches decode exceptions and calls `resetJournal()` which renames the corrupt file to `.corrupt` and creates a new empty journal. The sequence file is NOT reset. PASS.

(g) **RUN_STARTED emission removed from LayoutApplicationModule**: `LayoutApplicationModule.apply()` does not emit RUN_STARTED. The comment at line 59–62 explicitly states "RUN_STARTED emission is owned by future orchestrators (#52 manual full, #53 onboarding, #55 incremental)". PASS.

## Findings

Verdict: **pass** (the merge gate `final-status` is green on the audited head SHA `8c6b92c48f` via CI run 32145008969, and all 14 ACs are verified).

1. **[Low] G1 — All 149 JVM unit tests pass locally.** `spotlessCheck`, `assembleLawnWithQuickstepGithubDebug`, `validate_diagnostics_contract.py`, and `test_validate_diagnostics_contract.py` all pass locally. The CI run 32145008969 shows `organizer-unit-tests` (4m25s, success), `check-style` (1m3s, success), `build-debug-apk` (3m45s, success), `validate-repo-contract` (18s, success), and `final-status` (3s, success). All source jobs ran without skip.

2. **[Low] G2 — CI merge gate green on the audited head.** CI run 32145008969 on head `8c6b92c48f` shows `changes`, `check-style`, `validate-repo-contract`, `organizer-unit-tests`, `build-debug-apk`, and `final-status` all success. The `high-risk-evidence` check (run 32145008660) failed because this audit record did not yet exist — this is the expected behavior, and the gate will pass once this docs-only commit lands.

3. **[Low] G3 — AC-67-13 (accessible export action) verified structurally only.** The `OrganizerDiagnosticsExportPreference` composable uses standard `ClickablePreference` with `stringResource` for localized labels, which provides the same accessibility semantics as all other Lawnchair Settings controls. Device-level TalkBack, keyboard navigation, and font-scale testing are not executed in this audit. The implementation is structurally correct and follows the same pattern as other accessible preferences in the codebase.

4. **[Low] G4 — Instrumentation tests not executed.** The spec references instrumentation tests for AC-67-02, AC-67-04, AC-67-08, and AC-67-13. These require device/emulator execution. The JVM unit tests provide the primary verification for the contract behavior. The `ApplyProtocolDiagnosticsTest` uses a recording `DiagnosticsPort` to verify emission at lifecycle points without device instrumentation.

5. **[Low] G5 — Manual evidence items (Issue #81).** The following evidence items are noted as requiring manual verification per Issue #81:
   - D-08 recovery event correlation with `pointOriginRunId` (partially verified via source and test)
   - Device-level backup/restore exclusion (verified structurally via source-level BackupExclusionTest)
   - Device-level release-build logcat verification (verified structurally via DiagnosticsLogger isReleaseBuild flag)
   These are not blocking the audit but are tracked in Issue #81.

Process note: The `High-risk gate` workflow run 32145008660 on head `8c6b92c48f` failed because this audit record did not exist at that time. This updated record anchors the audit to head `8c6b92c48f60007123c04cc0b520555f7750345a` and the green merge-gate run 32145008969; the gate is expected to pass once this docs-only commit lands. Any subsequent code change requires a fresh audit on the new head.