package app.lawnchair.organizer.application.protocol

import android.content.Context
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.lifecycle.OrganizerDurableStatusDeriver
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.FolderTitleResolver
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.PlanPreviewUnavailable
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewUnavailable
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.journal.JournalSequence
import app.lawnchair.organizer.diagnostics.journal.JournalStore
import app.lawnchair.organizer.diagnostics.logger.DiagnosticsLogger
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.AndroidCandidateApplicationResolver
import app.lawnchair.organizer.integration.AndroidCandidateAvailabilityPort
import app.lawnchair.organizer.integration.AndroidMissingAppCandidateSource
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.CaptureFailureObserver
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.NoopCaptureFailureObserver
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.ProductionOrganizationInputComposer
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.RevisionId
import com.android.launcher3.LauncherAppState
import java.io.File
import java.security.SecureRandom

/**
 * The composition root. Constructs the production
 * [LayoutWriterPort]/[RecoveryStorePort]/[ModelReloadPort] adapters (supplied
 * by Step 5), wires [FaultInjector.NOOP], and exposes the `apply`/`recover`
 * functions. Single instance per process.
 *
 * Spec §“Public seam”.
 *
 * Issue #14 Stage B step 4 (shape); Step 5 wires production adapters.
 */
internal class LayoutApplicationModule<S>(
    private val writer: LayoutWriterPort,
    private val store: S,
    private val clock: Clock,
    private val operationIds: OperationIdSource,
    // Issue #201: injected by the outer composition (LawnchairApp). Required —
    // no default, so production can never silently fall back to a fixed name.
    private val folderTitleResolver: FolderTitleResolver,
    private val faults: FaultInjector = FaultInjector.NOOP,
    diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP,
    private val captureFailureObserver: CaptureFailureObserver = NoopCaptureFailureObserver,
    // Issue #228: candidate construction resolution (plan/preview time) and
    // apply-time availability re-verification. Nullable for legacy test
    // wiring; a plan that carries candidates fails closed when either is null.
    private val candidateApplicationResolver: CandidateApplicationResolver? = null,
    private val candidateAvailability: CandidateAvailabilityPort? = null,
) where S : RecoveryStorePort, S : RecoveryStoreReconciliationPort {

    private val mutex: RunMutex = RunMutex()
    private val ordinaryMutex: RunMutexPort = mutex
    private val reconciliationStore: RecoveryStoreReconciliationPort = store
    private val reconciliationIssuer: RecoveryStoreReconciliationIssuer =
        requireNotNull(reconciliationStore.bindReconciliationIssuer(mutex)) {
            "Recovery store is already bound to a different reconciliation mutex"
        }
    private val confirmationRandom: SecureRandom = SecureRandom()
    private val pendingPreviewConfirmations: java.util.IdentityHashMap<RecoveryPreviewConfirmation, PendingPreviewConfirmation> =
        java.util.IdentityHashMap()
    private val applyProtocol: ApplyProtocol = ApplyProtocol(writer, store, clock, operationIds, faults, ordinaryMutex, diagnosticsPort, candidateAvailability)
    private val recoveryProtocol: RecoveryProtocol = RecoveryProtocol(writer, store, clock, operationIds, faults, ordinaryMutex)
    private val recoveryPreviewProtocol: RecoveryPreviewProtocol = RecoveryPreviewProtocol(
        writer,
        store,
        clock,
        operationIds,
        faults,
        ordinaryMutex,
        confirmationIssuer = ::issuePreviewConfirmation,
    )
    private val planPreviewProtocol: PlanPreviewProtocol = PlanPreviewProtocol(
        writer,
        operationIds,
        faults,
        ordinaryMutex,
        folderTitleResolver,
        candidateApplicationResolver,
    )
    private val restartReconciler: RestartReconciler = RestartReconciler(
        writer,
        faults,
        diagnosticsPort,
    )
    val readinessGate: ReadinessGate = ReadinessGate()

    /**
     * Issue #187: serialization contract for the backup-restore artifact reset.
     * Blocks until any in-flight recovery operation (reconciliation, apply,
     * recovery, preview) drains, then runs [block] while the module operation
     * mutex is exclusively held — so no inspection snapshot publication
     * (`rebuildInspectionSnapshot`) can interleave into the section. The
     * contract scope is recovery mutation/reconciliation exclusivity: manual
     * compose/capture does not pass through this mutex (read-only, not a
     * snapshot publisher) and is out of scope. Lock order is one-way
     * (restore-family coordinator lease -> module mutex); no module-mutex
     * holder blocks on the coordinator, so no cycle exists (ADR-0011
     * Decision 2). Fails fast with the rethrown exception when [block] throws
     * — the restore path turns that into its pre-state-preserving abort.
     */
    internal fun <T> runWithRecoveryOperationsSuspendedForRestore(block: () -> T) = mutex.withExclusive(operationIds.newRunId(), block)

    /** Test-only access to the module operation mutex (issue #187 drain tests). */
    internal fun mutexForTest(): RunMutex = mutex

    /** The diagnostics port, available for export (e.g. debug menu). */
    val diagnostics: DiagnosticsPort = diagnosticsPort

    fun apply(plan: ValidatedLayoutPlan): ApplyResult = applyWithRunId(plan, operationIds.newRunId())

    /**
     * Issue #52 internal orchestration path. It preserves the public apply
     * protocol while allowing pre-apply and application diagnostics to share
     * one opaque run identifier.
     */
    internal fun applyWithRunId(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = readinessGate.runWhenReady(
        unavailable = { state ->
            ApplyResult.Rejected(
                runId,
                if (state == ReadinessGate.State.FAILED) {
                    PreWriteRejection.RECOVERY_STORE_UNAVAILABLE
                } else {
                    PreWriteRejection.WRITER_BUSY
                },
            )
        },
    ) {
        applyProtocol.apply(plan, runId)
    }

    /**
     * Internal, policy-owned composition for a fresh manual full-organization
     * input. Capture is itself a new manual action, so it is fail-closed until
     * startup reconciliation has reached a terminal state.
     */
    internal fun composeManualFullOrganizationInput(context: Context): OrganizationInputComposition = readinessGate.runWhenReady(
        unavailable = { state ->
            val failed = state == ReadinessGate.State.FAILED
            OrganizationInputComposition.NotReady(
                reason = if (failed) InputReadinessReason.ReconciliationFailed else InputReadinessReason.ReconciliationPending,
                diagnostic = CompositionDiagnostic(
                    code = if (failed) InputCompositionCode.RECONCILIATION_FAILED else InputCompositionCode.RECONCILIATION_PENDING,
                ),
            )
        },
    ) {
        ProductionOrganizationInputComposer(context.applicationContext, writer, captureFailureObserver).composeFullOrganization()
    }

    /**
     * Issue #228: read-only missing-app detection. Captures the current
     * canonical state (read-only, no lease beyond the capture itself) and
     * diffs the launchable installed inventory against it. Never writes.
     */
    internal fun detectMissingAppCandidates(context: Context): CandidateDetectionResult = readinessGate.runWhenReady(
        unavailable = {
            CandidateDetectionResult.Unavailable(DetectionUnavailableReason.CAPTURE_FAILED)
        },
    ) {
        val capture = try {
            writer.captureCurrent(CaptureId("missing-app-detection"))
        } catch (_: RuntimeException) {
            return@runWhenReady CandidateDetectionResult.Unavailable(DetectionUnavailableReason.CAPTURE_FAILED)
        }
        AndroidMissingAppCandidateSource(context.applicationContext).detect(capture)
    }

    /**
     * Issue #228 (D-2): composition for a scope-composed manual run — the
     * selected missing-app candidates join the full re-organization as
     * `TargetSet.additions`. Gated like the plain manual composition.
     */
    internal fun composeScopeComposedManualInput(
        context: Context,
        selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
    ): OrganizationInputComposition = readinessGate.runWhenReady(
        unavailable = { state ->
            val failed = state == ReadinessGate.State.FAILED
            OrganizationInputComposition.NotReady(
                reason = if (failed) InputReadinessReason.ReconciliationFailed else InputReadinessReason.ReconciliationPending,
                diagnostic = CompositionDiagnostic(
                    code = if (failed) InputCompositionCode.RECONCILIATION_FAILED else InputCompositionCode.RECONCILIATION_PENDING,
                ),
            )
        },
    ) {
        ProductionOrganizationInputComposer(context.applicationContext, writer, captureFailureObserver)
            .composeScopeComposedOrganization(selection)
    }

    /**
     * Captures the current canonical state only to materialize the exact planner
     * result for a prior input. Any capture failure or revision mismatch is a
     * safe invalid result; it never falls back to a cached layout.
     */
    internal fun materializeManualFullOrganizationPlan(
        input: OrganizationInput,
        result: PlanningResult,
    ): OrganizationPlanMaterializer.Result = readinessGate.runWhenReady(
        unavailable = { OrganizationPlanMaterializer.Result.Invalid },
    ) {
        val capture = try {
            writer.captureCurrent(CaptureId("manual-full-materialize"))
        } catch (_: RuntimeException) {
            return@runWhenReady OrganizationPlanMaterializer.Result.Invalid
        }
        if (capture.revision != input.snapshot.revision) return@runWhenReady OrganizationPlanMaterializer.Result.Invalid
        OrganizationPlanMaterializer.materialize(input, result, capture.layoutState, folderTitleResolver, candidateApplicationResolver)
    }

    /** Internal run identity factory for the manual orchestration protocol. */
    internal fun newManualRunId(): RunId = operationIds.newRunId()

    /**
     * Read-only plan preview (Issue #194). Captures authoritative current state
     * under a short non-blocking lease, verifies the planning snapshot revision,
     * materializes the exact plan for this input/result pair, and projects it
     * into the UI-facing change list. Silent diagnostically; every result is a
     * non-write outcome.
     */
    internal fun inspectPlan(
        input: OrganizationInput,
        result: PlanningResult,
    ): PlanPreviewResult = readinessGate.runWhenReady(
        unavailable = { state ->
            PlanPreviewResult.Unavailable(
                if (state == ReadinessGate.State.FAILED) {
                    PlanPreviewUnavailable.RECONCILIATION_FAILED
                } else {
                    PlanPreviewUnavailable.RECONCILIATION_PENDING
                },
            )
        },
    ) {
        planPreviewProtocol.inspect(input, result)
    }

    /**
     * Existing public mutation entry. All explicit recovery, including an
     * accepted preview confirmation, uses [recoverWithApplicationBehavior].
     */
    fun recover(request: RecoveryRequest): RecoveryResult = recoverWithApplicationBehavior(request)

    /**
     * Read-only application-owned inspection. It is silent diagnostically and
     * never enters the recovery mutation protocol.
     */
    fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = readinessGate.runWhenReady(
        unavailable = { state ->
            RecoveryPreviewResult.Unavailable(
                pointId,
                if (state == ReadinessGate.State.FAILED) {
                    RecoveryPreviewUnavailable.RECOVERY_STORE_UNAVAILABLE
                } else {
                    RecoveryPreviewUnavailable.RECONCILIATION_PENDING
                },
            )
        },
    ) {
        recoveryPreviewProtocol.inspect(pointId)
    }

    /**
     * Issue #271: read-only durable status projection for the re-opened
     * Settings surface. The application module owns the recovery store, so it
     * owns this projection; the UI only reads the closed, field-free
     * [OrganizerDurableStatus]. Gated on startup reconciliation like every
     * other seam, and serialized against writers through the same non-blocking
     * run-mutex lease as the recovery preview (spec 89 inspection concurrency):
     * contention, an unready gate, an unreadable snapshot, or any read failure
     * maps to [OrganizerDurableStatus.UNAVAILABLE] — fail-closed, silent
     * diagnostically, no write and no lifecycle mutation.
     */
    fun durableOrganizerStatus(): OrganizerDurableStatus = readinessGate.runWhenReady(
        unavailable = { OrganizerDurableStatus.UNAVAILABLE },
    ) {
        val runId = operationIds.newRunId()
        if (!ordinaryMutex.tryAcquire(runId)) return@runWhenReady OrganizerDurableStatus.UNAVAILABLE
        try {
            when (val read = store.readInspectionSnapshot()) {
                is RecoveryStorePort.InspectionSnapshotRead.Value -> OrganizerDurableStatusDeriver.derive(
                    records = read.records.map {
                        OrganizerDurableStatusDeriver.DurableRecord(
                            lifecycle = it.lifecycle,
                            createdAtMs = it.createdAtMs,
                            updatedAtMs = it.updatedAtMs,
                            checksumValid = it.checksumValid,
                        )
                    },
                    tombstones = read.tombstones.map {
                        OrganizerDurableStatusDeriver.DurableTombstone(
                            reason = it.reason,
                            expiresAtMs = it.expiresAtMs,
                        )
                    },
                    nowMs = clock.nowMillis(),
                )

                RecoveryStorePort.InspectionSnapshotRead.Unavailable -> OrganizerDurableStatus.UNAVAILABLE
            }
        } catch (_: RuntimeException) {
            OrganizerDurableStatus.UNAVAILABLE
        } finally {
            ordinaryMutex.release(runId)
        }
    }

    /**
     * Application-boundary confirmation entry for an opaque preview
     * capability. It deliberately returns only [RecoveryResult] and shares
     * all readiness and diagnostics behavior with public [recover].
     */
    internal fun confirmRecoveryPreview(
        pointId: RecoveryPointId,
        confirmation: RecoveryPreviewConfirmation,
    ): RecoveryResult {
        val request = consumePreviewConfirmation(pointId, confirmation)
            ?: return RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING)
        return recoverWithApplicationBehavior(request)
    }

    @Synchronized
    private fun issuePreviewConfirmation(
        pointId: RecoveryPointId,
        expectedCurrentRevision: RevisionId,
    ): RecoveryPreviewConfirmation {
        val token = ByteArray(PREVIEW_CONFIRMATION_TOKEN_BYTES)
        confirmationRandom.nextBytes(token)
        val confirmation = RecoveryPreviewConfirmation.issue(token)
        pendingPreviewConfirmations[confirmation] = PendingPreviewConfirmation(pointId, expectedCurrentRevision)
        return confirmation
    }

    @Synchronized
    private fun consumePreviewConfirmation(
        pointId: RecoveryPointId,
        confirmation: RecoveryPreviewConfirmation,
    ): RecoveryRequest? = pendingPreviewConfirmations.remove(confirmation)
        ?.takeIf { pending -> pending.pointId == pointId }
        ?.let { pending -> RecoveryRequest(pending.pointId, pending.expectedCurrentRevision) }

    private fun recoverWithApplicationBehavior(request: RecoveryRequest): RecoveryResult = readinessGate.runWhenReady(
        unavailable = { state ->
            if (state == ReadinessGate.State.FAILED) {
                RecoveryResult.RestoreFailed(
                    request.pointId,
                    app.lawnchair.organizer.application.public.RecoveryFailure.RECOVERY_STORE_FAILED,
                    app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
                )
            } else {
                RecoveryResult.WriterBusy
            }
        },
    ) {
        emitRecoveryRequested(request)
        val result = recoveryProtocol.recover(request)
        emitRecoveryResult(result, request)
        result
    }

    private fun emitRecoveryResult(result: RecoveryResult, request: RecoveryRequest) {
        try {
            val record = store.readRecord(request.pointId)
            val pointOriginRunId = when (record) {
                is RecoveryStorePort.RecordRead.Readable -> record.record.runId.value
                is RecoveryStorePort.RecordRead.Unreadable -> record.metadata.runId.value
                else -> null
            }
            val event = app.lawnchair.organizer.diagnostics.projection.RecoveryProjection.project(
                result = result,
                journalSequence = 0L,
                pointId = request.pointId.value,
                pointOriginRunId = pointOriginRunId,
            )
            diagnostics.emit(event)
        } catch (_: Exception) {
            // Fail-open
        }
    }

    private fun emitRecoveryRequested(request: RecoveryRequest) {
        try {
            val record = store.readRecord(request.pointId)
            val pointOriginRunId = when (record) {
                is RecoveryStorePort.RecordRead.Readable -> record.record.runId.value
                is RecoveryStorePort.RecordRead.Unreadable -> record.metadata.runId.value
                else -> null
            }
            val event = app.lawnchair.organizer.diagnostics.projection.RecoveryProjection.projectRequested(
                pointId = request.pointId.value,
                pointOriginRunId = pointOriginRunId,
                journalSequence = 0L,
            )
            diagnostics.emit(event)
        } catch (_: Exception) {
            // Fail-open
        }
    }

    internal fun reconcileAtStart(): RestartReconciler.ReconciliationSummary {
        val runId = operationIds.newRunId()
        if (!mutex.tryAcquire(runId)) return RestartReconciler.ReconciliationSummary.Failed
        val lease = mutex.issueReconciliationLease(runId)
            ?: run {
                mutex.release(runId)
                return RestartReconciler.ReconciliationSummary.Failed
            }
        val session = reconciliationIssuer.openSession(lease)
            ?: run {
                mutex.release(runId)
                return RestartReconciler.ReconciliationSummary.Failed
            }
        return try {
            readinessGate.reconcile(
                block = { restartReconciler.reconcileAll(session) },
                succeeded = { summary -> !summary.hasUnresolvedFailures() },
                failed = { RestartReconciler.ReconciliationSummary.Failed },
            )
        } finally {
            session.close()
            mutex.release(runId)
        }
    }

    fun failStartupReconciliation() {
        readinessGate.failBeforeReconciliation()
    }

    private data class PendingPreviewConfirmation(
        val pointId: RecoveryPointId,
        val expectedCurrentRevision: RevisionId,
    )

    companion object {
        private const val PREVIEW_CONFIRMATION_TOKEN_BYTES: Int = 32

        fun defaultOperationIdSource(): OperationIdSource = SecureRandomOperationIdSource()

        /** Issue #14 production composition; this is the only Android construction path. */
        @JvmStatic
        fun production(context: Context, folderTitleResolver: FolderTitleResolver): LayoutApplicationModule<RecoveryStore> = production(context, folderTitleResolver, LauncherAppState.getInstance(context.applicationContext))

        @JvmStatic
        fun production(
            context: Context,
            folderTitleResolver: FolderTitleResolver,
            launcher: LauncherAppState,
            candidateApplicationResolver: CandidateApplicationResolver? = null,
            candidateAvailability: CandidateAvailabilityPort? = null,
        ): LayoutApplicationModule<RecoveryStore> {
            val appContext = context.applicationContext
            val clock = SystemClock()
            val diagnosticsDir = File(appContext.filesDir, "organizer_diagnostics")
            diagnosticsDir.mkdirs()
            val journalFile = File(diagnosticsDir, "organizer_diagnostics.journal")
            val seqFile = File(diagnosticsDir, "journal_seq")
            val journalSequence = JournalSequence(seqFile)
            val journalStore = JournalStore(journalFile, journalSequence, clock::nowMillis)
            val diagnosticsLogger = DiagnosticsLogger(isReleaseBuild = !com.android.launcher3.BuildConfig.DEBUG)
            // Issue #172: capture-side failures surface as the exception class
            // name on the single organizer tag, debug builds only.
            val captureFailureObserver = CaptureFailureObserver { exceptionClass ->
                diagnosticsLogger.logCaptureFailure(exceptionClass)
            }
            val diagnosticsPort = object : DiagnosticsPort {
                override fun emit(event: RunEvent) {
                    val persisted = journalStore.append(event)
                    if (persisted) {
                        diagnosticsLogger.log(event)
                    }
                }
                override fun snapshot(): List<RunEvent> = journalStore.snapshot()
            }
            // Open the journal store eagerly so it's ready for use
            try {
                journalStore.open()
            } catch (_: Exception) {
                // Fail-open
            }
            val recoveryStore = RecoveryStore(appContext, clock::nowMillis)
            val module = LayoutApplicationModule(
                writer = LauncherLayoutAdapter(
                    appContext,
                    launcher.model.modelDbController,
                    launcher.model,
                ),
                store = recoveryStore,
                clock = clock,
                operationIds = defaultOperationIdSource(),
                folderTitleResolver = folderTitleResolver,
                diagnosticsPort = diagnosticsPort,
                captureFailureObserver = captureFailureObserver,
                candidateApplicationResolver = candidateApplicationResolver
                    ?: AndroidCandidateApplicationResolver(appContext),
                candidateAvailability = candidateAvailability
                    ?: AndroidCandidateAvailabilityPort(appContext),
            )
            return module
        }
    }
}

/**
 * Production [OperationIdSource] backed by [SecureRandom]. Emits canonical
 * 32-character lowercase hex run/point IDs.
 *
 * Issue #14 Stage B step 4.
 */
class SecureRandomOperationIdSource(
    private val random: SecureRandom = SecureRandom(),
) : OperationIdSource {

    override fun newRunId(): RunId = randomHexId().let(::RunId)

    override fun newPointId(): RecoveryPointId = RecoveryPointId(randomHexId())

    private fun randomHexId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    private val hexChars: CharArray = "0123456789abcdef".toCharArray()
}

/** System-clock backed [Clock]. */
class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
