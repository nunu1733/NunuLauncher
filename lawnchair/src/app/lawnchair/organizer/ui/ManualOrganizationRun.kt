package app.lawnchair.organizer.ui

import android.content.Context
import app.lawnchair.LawnchairApp
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.DeviceProfileSummary
import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.Orientation
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.RunMode
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.diagnostics.projection.PlanningProjection
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.WarningCode

/**
 * Narrow façade used by the manual run coordinator. It deliberately exposes
 * neither a database, layout writer, recovery store, nor recovery request.
 */
internal interface ManualOrganizationApplication {
    val diagnostics: DiagnosticsPort

    fun newRunId(): RunId
    fun composeFullOrganization(): OrganizationInputComposition
    fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result
    fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult
    fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult
    fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult
}

internal class ProductionManualOrganizationApplication(
    context: Context,
    private val module: LayoutApplicationModule<RecoveryStore>,
) : ManualOrganizationApplication {
    private val appContext = context.applicationContext

    override val diagnostics: DiagnosticsPort
        get() = module.diagnostics

    override fun newRunId(): RunId = module.newManualRunId()

    override fun composeFullOrganization(): OrganizationInputComposition = module.composeManualFullOrganizationInput(appContext)

    override fun materialize(
        input: OrganizationInput,
        result: PlanningResult,
    ): OrganizationPlanMaterializer.Result = module.materializeManualFullOrganizationPlan(input, result)

    override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = module.applyWithRunId(plan, runId)

    override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = module.inspectRecovery(pointId)

    override fun confirmRecovery(
        pointId: RecoveryPointId,
        confirmation: RecoveryPreviewConfirmation,
    ): RecoveryResult = module.confirmRecoveryPreview(pointId, confirmation)
}

/** Process-local composition holder. Construction itself is read-only. */
internal object ManualOrganizationModule {
    @Volatile private var instance: ManualOrganizationRun? = null

    fun get(context: Context): ManualOrganizationRun = instance ?: synchronized(this) {
        instance ?: ProductionManualOrganizationApplication(
            context.applicationContext,
            (context.applicationContext as LawnchairApp).layoutApplicationModule,
        ).let { application ->
            ManualOrganizationRun(application).also { instance = it }
        }
    }
}

/**
 * State machine for Issue #52's explicit manual/full run. All expensive calls
 * are synchronous by design; the Compose caller dispatches them off the main
 * thread. The only retained write authorization is an in-memory opaque
 * application confirmation capability, and it is never serialized.
 */
class ManualOrganizationRun internal constructor(
    private val application: ManualOrganizationApplication,
    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner(),
) {
    sealed interface State {
        data object Idle : State
        data object Capturing : State
        data class InputUnavailable(val reason: InputReadinessReason) : State
        data class PlanningRejected(val kind: PlanningFailureKind) : State
        data object NoChanges : State
        data class Preview(val summary: Summary) : State
        data object Applying : State
        data object Stale : State
        data class Applied(val result: ApplyResult) : State
        data object Cancelled : State
        data class RecoveryPreview(val result: RecoveryPreviewResult) : State
        data object Recovering : State
        data class RecoveryResultState(val result: RecoveryResult) : State
    }

    enum class PlanningFailureKind { INVALID, IMPOSSIBLE }

    data class Summary(
        val movedCount: Int,
        val preservedCount: Int,
        val newFolderCount: Int,
        val newPageCount: Int,
        val warningCodes: Set<WarningCode>,
    )

    @Volatile
    var state: State = State.Idle
        private set

    private var pending: PendingPlan? = null
    private var activeRunId: RunId? = null
    private var appliedPoint: RecoveryPointId? = null
    private var pendingRecovery: RecoveryPreviewResult.Restorable? = null

    @Synchronized
    fun start() {
        val runId = application.newRunId()
        activeRunId = runId
        pending = null
        pendingRecovery = null
        appliedPoint = null
        state = State.Capturing
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = runId.value,
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.RUN_STARTED,
            ),
        )

        when (val composition = application.composeFullOrganization()) {
            is OrganizationInputComposition.NotReady -> {
                state = State.InputUnavailable(composition.reason)
            }

            is OrganizationInputComposition.Ready -> {
                val input = composition.input
                emit(
                    RunEvent(
                        journalSequence = 0L,
                        runId = runId.value,
                        trigger = Trigger.MANUAL_FULL,
                        runMode = RunMode.FULL_ORGANIZATION,
                        phase = PhaseCode.CAPTURED,
                        deviceProfile = deviceSummary(input),
                    ),
                )
                val result = planner.plan(input)
                emit(
                    PlanningProjection.project(
                        result = result,
                        journalSequence = 0L,
                        capturedItemCount = input.snapshot.items.size,
                    ).copy(
                        runId = runId.value,
                        trigger = Trigger.MANUAL_FULL,
                        runMode = RunMode.FULL_ORGANIZATION,
                    ),
                )
                when (val outcome = result.outcome) {
                    is Planned -> {
                        val summary = outcome.summary()
                        if (summary.movedCount == 0 && summary.newFolderCount == 0 && summary.newPageCount == 0) {
                            state = State.NoChanges
                        } else {
                            pending = PendingPlan(runId, input, result, summary)
                            state = State.Preview(summary)
                            emit(
                                RunEvent(
                                    journalSequence = 0L,
                                    runId = runId.value,
                                    trigger = Trigger.MANUAL_FULL,
                                    runMode = RunMode.FULL_ORGANIZATION,
                                    phase = PhaseCode.PREVIEWED,
                                ),
                            )
                        }
                    }

                    is app.lawnchair.organizer.planning.Rejected.Invalid -> state = State.PlanningRejected(PlanningFailureKind.INVALID)

                    is app.lawnchair.organizer.planning.Rejected.Impossible -> state = State.PlanningRejected(PlanningFailureKind.IMPOSSIBLE)
                }
            }
        }
    }

    @Synchronized
    fun cancel() {
        val runId = activeRunId ?: return
        if (state !is State.Preview && state !is State.Capturing) return
        pending = null
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = runId.value,
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.USER_CANCELLED,
            ),
        )
        state = State.Cancelled
    }

    @Synchronized
    fun confirm() {
        val pendingPlan = pending ?: return
        if (state !is State.Preview) return
        state = State.Applying
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = pendingPlan.runId.value,
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.USER_CONFIRMED,
            ),
        )
        val materialized = application.materialize(pendingPlan.input, pendingPlan.result)
        val plan = (materialized as? OrganizationPlanMaterializer.Result.Ready)?.plan
        if (plan == null) {
            pending = null
            emitStaleRejection(pendingPlan.runId)
            state = State.Stale
            return
        }
        pending = null
        val result = application.apply(plan, pendingPlan.runId)
        if (result is ApplyResult.Applied) appliedPoint = result.pointId
        state = when (result) {
            is ApplyResult.NoChanges -> State.NoChanges

            is ApplyResult.Rejected -> if (result.reason == PreWriteRejection.STALE_REVISION || result.reason == PreWriteRejection.EXACT_PRECONDITION_FAILED) {
                State.Stale
            } else {
                State.Applied(result)
            }

            else -> State.Applied(result)
        }
    }

    @Synchronized
    fun beginRecoveryPreview() {
        val point = appliedPoint ?: return
        val preview = application.inspectRecovery(point)
        pendingRecovery = preview as? RecoveryPreviewResult.Restorable
        state = State.RecoveryPreview(preview)
    }

    @Synchronized
    fun cancelRecoveryPreview() {
        pendingRecovery = null
        state = State.Idle
    }

    @Synchronized
    fun confirmRecovery() {
        val preview = pendingRecovery ?: return
        state = State.Recovering
        pendingRecovery = null
        state = State.RecoveryResultState(application.confirmRecovery(preview.pointId, preview.confirmation))
    }

    @Synchronized
    fun dismiss() {
        pending = null
        pendingRecovery = null
        state = State.Idle
    }

    private fun Planned.summary() = Summary(
        movedCount = placements.count { it.disposition is Disposition.Moved },
        preservedCount = placements.count { it.disposition is Disposition.Preserved },
        newFolderCount = newFolders.size,
        newPageCount = newPages.size,
        warningCodes = warnings.map { it.code }.toSet(),
    )

    private fun deviceSummary(input: OrganizationInput): DeviceProfileSummary = DeviceProfileSummary(
        columns = input.snapshot.device.columns,
        rows = input.snapshot.device.rows,
        hotseatSlots = input.snapshot.device.hotseatSlots,
        orientation = when (input.snapshot.device.orientation) {
            app.lawnchair.organizer.planning.Orientation.PORTRAIT,
            app.lawnchair.organizer.planning.Orientation.TWO_PANEL_PORTRAIT,
            -> Orientation.PORTRAIT

            app.lawnchair.organizer.planning.Orientation.LANDSCAPE,
            app.lawnchair.organizer.planning.Orientation.TWO_PANEL_LANDSCAPE,
            -> Orientation.LANDSCAPE
        },
    )

    private fun emitStaleRejection(runId: RunId) {
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = runId.value,
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.APPLY_REJECTED,
                applyStage = ApplyStage.A2,
                error = ErrorEntry(ErrorFamily.PRE_WRITE_REJECTED, PreWriteRejection.STALE_REVISION.name),
            ),
        )
    }

    private fun emit(event: RunEvent) {
        try {
            application.diagnostics.emit(event)
        } catch (_: Exception) {
            // Diagnostics are intentionally fail-open.
        }
    }

    private data class PendingPlan(
        val runId: RunId,
        val input: OrganizationInput,
        val result: PlanningResult,
        val summary: Summary,
    )
}
