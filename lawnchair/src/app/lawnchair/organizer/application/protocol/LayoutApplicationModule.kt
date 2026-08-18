package app.lawnchair.organizer.application.protocol

import android.content.Context
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
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
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.RunMode
import app.lawnchair.organizer.diagnostics.model.RunVersions
import app.lawnchair.organizer.diagnostics.model.Trigger
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
    private val diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP,
) {

    private val mutex: RunMutex = RunMutex()
    private val applyProtocol: ApplyProtocol = ApplyProtocol(writer, store, clock, operationIds, faults, mutex, diagnosticsPort)
    private val recoveryProtocol: RecoveryProtocol = RecoveryProtocol(writer, store, clock, operationIds, faults, mutex)
    private val restartReconciler: RestartReconciler = RestartReconciler(writer, store, faults, diagnosticsPort)
    val readinessGate: ReadinessGate = ReadinessGate()

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
        val runId = operationIds.newRunId()
        emitRunStarted(runId, plan)
        applyProtocol.apply(plan)
    }

    private fun emitRunStarted(runId: RunId, plan: ValidatedLayoutPlan) {
        try {
            val event = RunEvent(
                journalSequence = 0L,
                phase = PhaseCode.RUN_STARTED,
                runId = runId.value,
                versions = RunVersions(
                    ruleVersion = plan.ruleVersion?.value ?: "",
                    taxonomyVersion = plan.taxonomyVersion?.value ?: "",
                ),
            )
            diagnosticsPort.emit(event)
        } catch (_: Exception) {
            // Fail-open
        }
    }

    fun recover(request: RecoveryRequest): RecoveryResult = readinessGate.runWhenReady(
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
        recoveryProtocol.recover(request)
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
            diagnosticsPort.emit(event)
        } catch (_: Exception) {
            // Fail-open
        }
    }

    fun reconcileAtStart(): RestartReconciler.ReconciliationSummary = readinessGate.reconcile(
        block = restartReconciler::reconcileAll,
        succeeded = { summary -> !summary.hasUnresolvedFailures() },
        failed = { RestartReconciler.ReconciliationSummary.Failed },
    )

    fun failStartupReconciliation() {
        readinessGate.failBeforeReconciliation()
    }

    companion object {
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
            val diagnosticsLogger = DiagnosticsLogger()
            val diagnosticsPort = DiagnosticsPort { event ->
                val persisted = journalStore.append(event)
                if (persisted) {
                    diagnosticsLogger.log(event)
                }
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
