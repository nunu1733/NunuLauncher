package app.lawnchair.organizer.application.protocol

import android.content.Context
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
class LayoutApplicationModule(
    private val writer: LayoutWriterPort,
    private val store: RecoveryStorePort,
    private val clock: Clock,
    private val operationIds: OperationIdSource,
    private val faults: FaultInjector = FaultInjector.NOOP,
    diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP,
) {

    private val mutex: RunMutex = RunMutex()
    private val ordinaryMutex: RunMutexPort = mutex
    private val reconciliationStore: RecoveryStoreReconciliationPort =
        requireNotNull(store as? RecoveryStoreReconciliationPort) {
            "Recovery store must provide the private startup reconciliation seam"
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
        store,
        reconciliationStore,
        faults,
        diagnosticsPort,
    )
    val readinessGate: ReadinessGate = ReadinessGate()

    /** The diagnostics port, available for export (e.g. debug menu). */
    val diagnostics: DiagnosticsPort = diagnosticsPort

    fun apply(plan: ValidatedLayoutPlan): ApplyResult = readinessGate.runWhenReady(
        unavailable = { state ->
            ApplyResult.Rejected(
                operationIds.newRunId(),
                if (state == ReadinessGate.State.FAILED) {
                    PreWriteRejection.RECOVERY_STORE_UNAVAILABLE
                } else {
                    PreWriteRejection.WRITER_BUSY
                },
            )
        },
    ) {
        // RUN_STARTED emission is owned by future orchestrators
        // (#52 manual full, #53 onboarding, #55 incremental) which have
        // the full contract context (trigger, runMode, deviceProfile,
        // recoveryFormatVersion). Keep runId for apply-event correlation.
        val runId = operationIds.newRunId()
        applyProtocol.apply(plan, runId)
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
        return try {
            readinessGate.reconcile(
                block = { reconciliationStore.withReconciliationScope { restartReconciler.reconcileAll() } },
                succeeded = { summary -> !summary.hasUnresolvedFailures() },
                failed = { RestartReconciler.ReconciliationSummary.Failed },
            )
        } finally {
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
        fun production(context: Context): LayoutApplicationModule = production(context, LauncherAppState.getInstance(context.applicationContext))

        @JvmStatic
        fun production(
            context: Context,
            launcher: LauncherAppState,
        ): LayoutApplicationModule {
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
            val module = LayoutApplicationModule(
                writer = LauncherLayoutAdapter(
                    appContext,
                    launcher.model.modelDbController,
                    launcher.model,
                ),
                store = RecoveryStore(appContext, clock::nowMillis),
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
