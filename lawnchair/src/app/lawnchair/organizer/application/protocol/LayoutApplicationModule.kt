package app.lawnchair.organizer.application.protocol

import android.content.Context
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.public.ApplyResult
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
    private val faults: FaultInjector = FaultInjector.NOOP,
    diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP,
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
    private val applyProtocol: ApplyProtocol = ApplyProtocol(writer, store, clock, operationIds, faults, ordinaryMutex, diagnosticsPort)
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
    private val restartReconciler: RestartReconciler = RestartReconciler(
        writer,
        faults,
        diagnosticsPort,
    )
    val readinessGate: ReadinessGate = ReadinessGate()

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

    /** Internal, policy-owned composition for a fresh manual full-organization input. */
    internal fun composeManualFullOrganizationInput(context: Context): OrganizationInputComposition = ProductionOrganizationInputComposer(context.applicationContext, writer).composeFullOrganization()

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
        OrganizationPlanMaterializer.materialize(input, result, capture.layoutState)
    }

    /** Internal run identity factory for the manual orchestration protocol. */
    internal fun newManualRunId(): RunId = operationIds.newRunId()

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
            val pointOriginRunId = record?.runId?.value
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
            val pointOriginRunId = record?.runId?.value
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
        fun production(context: Context): LayoutApplicationModule<RecoveryStore> = production(context, LauncherAppState.getInstance(context.applicationContext))

        @JvmStatic
        fun production(
            context: Context,
            launcher: LauncherAppState,
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
                diagnosticsPort = diagnosticsPort,
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
