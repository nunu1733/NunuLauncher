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
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * State machine for Issue #52's explicit manual/full run. State is observable
 * independently from the worker performing capture/planning/application. The
 * only retained write authorization is an in-memory opaque application
 * confirmation capability, and it is never serialized.
 */
class ManualOrganizationRun internal constructor(
    private val application: ManualOrganizationApplication,
    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner(),
) {
    sealed interface State {
        data object Idle : State
        data object Capturing : State
        data object Planning : State
        data class InputUnavailable(val reason: InputReadinessReason) : State
        data class PlanningRejected(val kind: PlanningFailureKind, val summary: Summary) : State
        data object NoChanges : State
        data class Preview(val summary: Summary) : State
        data object Applying : State
        data object Stale : State
        data class Applied(val result: ApplyResult, val summary: Summary) : State
        data object Cancelled : State
        data object InspectingRecovery : State
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
        val scope: Scope,
        val movedByReason: Map<PlacementCode, Int>,
        val preservedByReason: Map<PreserveReason, Int>,
        val rejectedByReason: Map<RejectionCode, Int>,
        val unplacedByReason: Map<UnplacedReason, Int>,
        val warningCounts: Map<WarningCode, Int>,
        val constraints: Constraints,
    ) {
        val warningCodes: Set<WarningCode>
            get() = warningCounts.keys

        data class Scope(
            val targetCount: Int,
            val targetProfileCount: Int,
            val pageCount: Int,
            val columns: Int,
            val rows: Int,
            val hotseatSlots: Int,
        )

        data class Constraints(
            val lockedCount: Int,
            val unavailableCount: Int,
            val widgetCount: Int,
            val appPairCount: Int,
            val legacyShortcutCount: Int,
            val emptyFolderCount: Int,
            val availabilityCounts: Map<Availability, Int>,
        )
    }

    private val stateHolder = MutableStateFlow<State>(State.Idle)
    val stateFlow: StateFlow<State> = stateHolder.asStateFlow()
    val state: State
        get() = stateHolder.value

    private val lock = Any()
    private var activeOperation: Operation? = null
    private var pending: PendingPlan? = null
    private var appliedPoint: RecoveryPointId? = null
    private var pendingRecovery: RecoveryPreviewResult.Restorable? = null
    private var lastVerifiedApply: State.Applied? = null

    fun start() {
        val operation = beginOperation() ?: return
        val runId = operation.runId
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
                finish(operation, State.InputUnavailable(composition.reason))
            }

            is OrganizationInputComposition.Ready -> {
                if (!isActive(operation)) return
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
                setIfActive(operation, State.Planning)
                if (!isActive(operation)) return
                val result = planner.plan(input)
                if (!isActive(operation)) return
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
                        val summary = outcome.summary(input)
                        if (summary.movedCount == 0 && summary.newFolderCount == 0 && summary.newPageCount == 0) {
                            finish(operation, State.NoChanges)
                        } else {
                            synchronized(lock) {
                                if (!isActiveLocked(operation)) return
                                pending = PendingPlan(operation, input, result, summary)
                                stateHolder.value = State.Preview(summary)
                            }
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

                    is app.lawnchair.organizer.planning.Rejected.Invalid -> finish(
                        operation,
                        State.PlanningRejected(PlanningFailureKind.INVALID, result.summary(input)),
                    )

                    is app.lawnchair.organizer.planning.Rejected.Impossible -> finish(
                        operation,
                        State.PlanningRejected(PlanningFailureKind.IMPOSSIBLE, result.summary(input)),
                    )
                }
            }
        }
    }

    fun cancel() {
        val operation = synchronized(lock) {
            val candidate = activeOperation ?: return
            if (candidate.applicationAdmitted.get()) return
            if (state !is State.Preview && state !is State.Capturing && state !is State.Planning && state !is State.Applying) return
            candidate.cancelled.set(true)
            pending = null
            activeOperation = null
            stateHolder.value = State.Cancelled
            candidate
        }
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = operation.runId.value,
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.USER_CANCELLED,
            ),
        )
    }

    fun confirm() {
        val (operation, pendingPlan) = synchronized(lock) {
            val currentOperation = activeOperation ?: return
            val currentPlan = pending ?: return
            if (state !is State.Preview) return
            stateHolder.value = State.Applying
            currentOperation to currentPlan
        }
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = operation.runId.value,
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.USER_CONFIRMED,
            ),
        )
        val materialized = application.materialize(pendingPlan.input, pendingPlan.result)
        val plan = (materialized as? OrganizationPlanMaterializer.Result.Ready)?.plan
        if (plan == null) {
            val stale = synchronized(lock) {
                if (!isActiveLocked(operation)) return
                pending = null
                activeOperation = null
                stateHolder.value = State.Stale
                true
            }
            if (stale) emitStaleRejection(pendingPlan.runId)
            return
        }
        val admitted = synchronized(lock) {
            if (!isActiveLocked(operation) || operation.cancelled.get()) {
                false
            } else {
                operation.applicationAdmitted.set(true)
                pending = null
                true
            }
        }
        if (!admitted) return

        val result = application.apply(plan, pendingPlan.runId)
        synchronized(lock) {
            if (!isActiveLocked(operation)) return
            activeOperation = null
            val nextState = when (result) {
                is ApplyResult.NoChanges -> State.NoChanges

                is ApplyResult.Rejected -> if (result.reason == PreWriteRejection.STALE_REVISION || result.reason == PreWriteRejection.EXACT_PRECONDITION_FAILED) {
                    State.Stale
                } else {
                    State.Applied(result, pendingPlan.summary)
                }

                else -> State.Applied(result, pendingPlan.summary)
            }
            if (nextState is State.Applied && result is ApplyResult.Applied) {
                appliedPoint = result.pointId
                lastVerifiedApply = nextState
            }
            stateHolder.value = nextState
        }
    }

    fun beginRecoveryPreview() {
        val (point, previous) = synchronized(lock) {
            val current = lastVerifiedApply ?: return
            val pointId = appliedPoint ?: return
            stateHolder.value = State.InspectingRecovery
            pointId to current
        }
        val preview = application.inspectRecovery(point)
        synchronized(lock) {
            if (lastVerifiedApply !== previous || state !is State.InspectingRecovery) return
            pendingRecovery = preview as? RecoveryPreviewResult.Restorable
            stateHolder.value = State.RecoveryPreview(preview)
        }
    }

    fun cancelRecoveryPreview() {
        synchronized(lock) {
            pendingRecovery = null
            stateHolder.value = lastVerifiedApply ?: State.Idle
        }
    }

    fun confirmRecovery() {
        val preview = synchronized(lock) {
            val current = pendingRecovery ?: return
            stateHolder.value = State.Recovering
            pendingRecovery = null
            current
        }
        val result = application.confirmRecovery(preview.pointId, preview.confirmation)
        synchronized(lock) {
            if (state is State.Recovering) stateHolder.value = State.RecoveryResultState(result)
        }
    }

    fun dismiss() {
        synchronized(lock) {
            val operation = activeOperation
            if (operation?.applicationAdmitted?.get() == true) return
            operation?.cancelled?.set(true)
            activeOperation = null
            pending = null
            pendingRecovery = null
            stateHolder.value = State.Idle
        }
    }

    private fun beginOperation(): Operation? = synchronized(lock) {
        if (activeOperation != null) return@synchronized null
        val operation = Operation(application.newRunId())
        activeOperation = operation
        pending = null
        pendingRecovery = null
        appliedPoint = null
        lastVerifiedApply = null
        stateHolder.value = State.Capturing
        operation
    }

    private fun isActive(operation: Operation): Boolean = synchronized(lock) { isActiveLocked(operation) }

    private fun isActiveLocked(operation: Operation): Boolean = activeOperation === operation && !operation.cancelled.get()

    private fun setIfActive(operation: Operation, nextState: State) {
        synchronized(lock) {
            if (isActiveLocked(operation)) stateHolder.value = nextState
        }
    }

    private fun finish(operation: Operation, nextState: State) {
        synchronized(lock) {
            if (!isActiveLocked(operation)) return
            activeOperation = null
            pending = null
            stateHolder.value = nextState
        }
    }

    private fun PlanningResult.summary(input: OrganizationInput): Summary {
        val planningOutcome = outcome
        val placements = (planningOutcome as? Planned)?.placements.orEmpty()
        val warnings = when (planningOutcome) {
            is Planned -> planningOutcome.warnings
            is app.lawnchair.organizer.planning.Rejected.Invalid -> planningOutcome.warnings
            is app.lawnchair.organizer.planning.Rejected.Impossible -> planningOutcome.warnings
        }
        val rejected = (planningOutcome as? app.lawnchair.organizer.planning.Rejected.Invalid)?.reasons.orEmpty()
        val unplaced = (planningOutcome as? app.lawnchair.organizer.planning.Rejected.Impossible)?.unplaced.orEmpty()
        return Summary(
            movedCount = placements.count { it.disposition is Disposition.Moved },
            preservedCount = placements.count { it.disposition is Disposition.Preserved },
            newFolderCount = (planningOutcome as? Planned)?.newFolders?.size ?: 0,
            newPageCount = (planningOutcome as? Planned)?.newPages?.size ?: 0,
            scope = input.summaryScope(),
            movedByReason = placements.mapNotNull { (it.disposition as? Disposition.Moved)?.rationale }.groupingBy { it }.eachCount(),
            preservedByReason = placements.mapNotNull { (it.disposition as? Disposition.Preserved)?.reason }.groupingBy { it }.eachCount(),
            rejectedByReason = rejected.groupingBy { it.code }.eachCount(),
            unplacedByReason = unplaced.groupingBy { it.reason }.eachCount(),
            warningCounts = warnings.groupingBy { it.code }.eachCount(),
            constraints = input.summaryConstraints(),
        )
    }

    private fun Planned.summary(input: OrganizationInput): Summary = PlanningResult(
        revision = input.snapshot.revision,
        ruleVersion = input.rules.version,
        taxonomyVersion = input.taxonomy.version,
        outcome = this,
    ).summary(input)

    private fun OrganizationInput.summaryScope() = Summary.Scope(
        targetCount = targets.existing.size + targets.additions.size,
        targetProfileCount = buildSet {
            val profilesByItem = snapshot.items.associate { it.id to it.profile }
            targets.existing.mapNotNullTo(this) { profilesByItem[it.item] }
            targets.additions.mapTo(this) { it.profile }
        }.size,
        pageCount = snapshot.pages.size,
        columns = snapshot.device.columns,
        rows = snapshot.device.rows,
        hotseatSlots = snapshot.device.hotseatSlots,
    )

    private fun OrganizationInput.summaryConstraints(): Summary.Constraints {
        val items = snapshot.items
        return Summary.Constraints(
            lockedCount = items.count { it.locked },
            unavailableCount = items.count { it.availability != Availability.AVAILABLE },
            widgetCount = items.count {
                it.kind == app.lawnchair.organizer.planning.ItemKind.APPWIDGET ||
                    it.kind == app.lawnchair.organizer.planning.ItemKind.CUSTOM_APPWIDGET
            },
            appPairCount = items.count { it.kind == app.lawnchair.organizer.planning.ItemKind.APP_PAIR },
            legacyShortcutCount = items.count { it.kind == app.lawnchair.organizer.planning.ItemKind.SHORTCUT_LEGACY },
            emptyFolderCount = items.count {
                it.kind == app.lawnchair.organizer.planning.ItemKind.FOLDER && it.members.isEmpty()
            },
            availabilityCounts = items.groupingBy { it.availability }.eachCount(),
        )
    }

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
        val operation: Operation,
        val input: OrganizationInput,
        val result: PlanningResult,
        val summary: Summary,
    ) {
        val runId: RunId
            get() = operation.runId
    }

    private data class Operation(
        val runId: RunId,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val applicationAdmitted: AtomicBoolean = AtomicBoolean(false),
    )
}
