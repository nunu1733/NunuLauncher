package app.lawnchair.organizer.ui

import android.content.Context
import app.lawnchair.LawnchairApp
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlanPreview
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PlanPreviewRejection
import app.lawnchair.organizer.application.public.PlanPreviewResult
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
import app.lawnchair.organizer.diagnostics.projection.InputReadinessProjection
import app.lawnchair.organizer.diagnostics.projection.PlanningProjection
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.LayoutStrategyRegistry
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.StrategyId
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
    fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult
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

    override fun inspectPlan(
        input: OrganizationInput,
        result: PlanningResult,
    ): PlanPreviewResult = module.inspectPlan(input, result)

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
            ManualOrganizationRun(application, operationGate = OrganizationOperationLease).also { instance = it }
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
    private val operationGate: OrganizationOperationGate = NoopOrganizationOperationGate,
) {
    enum class DismissalOutcome {
        CancelledAndMayNavigate,
        NoActiveOperation,
        ApplicationInProgress,
    }

    sealed interface StartOutcome {
        data class Started(val runId: RunId) : StartOutcome

        data object Busy : StartOutcome
    }

    sealed interface State {
        data object Idle : State
        data object Capturing : State
        data object Planning : State
        data class InputUnavailable(val reason: InputReadinessReason) : State
        data class PlanningRejected(val kind: PlanningFailureKind, val summary: Summary) : State
        data object NoChanges : State
        data class Preview(val summary: Summary, val details: PlanPreviewDetails?) : State
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
        /** Spec 182: the effective strategy the preview was computed with. */
        val organizationStrategy: StrategyId,
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
    private var recoveryLease: AutoCloseable? = null
    private var lastVerifiedApply: State.Applied? = null

    fun start(trigger: Trigger = Trigger.MANUAL_FULL): StartOutcome {
        val operation = beginOperation(trigger) ?: return StartOutcome.Busy
        val runId = operation.runId
        val started = StartOutcome.Started(runId)
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = runId.value,
                trigger = operation.trigger,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.RUN_STARTED,
            ),
        )

        try {
            when (val composition = application.composeFullOrganization()) {
                is OrganizationInputComposition.NotReady -> {
                    emitInputNotReady(operation, composition)
                    finish(operation, State.InputUnavailable(composition.reason))
                }

                is OrganizationInputComposition.Ready -> {
                    if (!isActive(operation)) return started
                    val input = composition.input
                    emit(
                        RunEvent(
                            journalSequence = 0L,
                            runId = runId.value,
                            trigger = operation.trigger,
                            runMode = RunMode.FULL_ORGANIZATION,
                            phase = PhaseCode.CAPTURED,
                            deviceProfile = deviceSummary(input),
                        ),
                    )
                    setIfActive(operation, State.Planning)
                    if (!isActive(operation)) return started
                    val result = planner.plan(input)
                    if (!isActive(operation)) return started
                    emit(
                        PlanningProjection.project(
                            result = result,
                            journalSequence = 0L,
                            capturedItemCount = input.snapshot.items.size,
                            // Spec 182: the diagnostics echo must match the
                            // planner's runtime truth. The runtime-enabled set
                            // comes from the internal executable registry, so a
                            // strategy the binary does not implement is never
                            // recorded as effective.
                            runtimeStrategyIds = LayoutStrategyRegistry.acceptedIds
                                .map { it.value }
                                .toSet(),
                        ).copy(
                            runId = runId.value,
                            trigger = operation.trigger,
                            runMode = RunMode.FULL_ORGANIZATION,
                        ),
                    )
                    when (val outcome = result.outcome) {
                        is Planned -> {
                            val summary = outcome.summary(input)
                            if (summary.movedCount == 0 && summary.newFolderCount == 0 && summary.newPageCount == 0) {
                                finish(operation, State.NoChanges)
                            } else {
                                when (val preview = application.inspectPlan(input, result)) {
                                    is PlanPreviewResult.Previewed -> enterPreview(operation, input, result, summary, preview.preview)

                                    is PlanPreviewResult.Stale -> transitionToStale(operation, emitRejection = true)

                                    is PlanPreviewResult.NotPlannable -> when (preview.reason) {
                                        PlanPreviewRejection.CAPTURE_FAILED -> enterPreview(operation, input, result, summary, null)

                                        PlanPreviewRejection.OUTCOME_NOT_PLANNED,
                                        PlanPreviewRejection.MATERIALIZATION_INVALID,
                                        -> finish(operation, State.PlanningRejected(PlanningFailureKind.IMPOSSIBLE, summary))
                                    }

                                    is PlanPreviewResult.Unavailable,
                                    PlanPreviewResult.WriterBusy,
                                    PlanPreviewResult.Concurrent,
                                    -> enterPreview(operation, input, result, summary, null)
                                }
                                if (state is State.Preview) {
                                    emit(
                                        RunEvent(
                                            journalSequence = 0L,
                                            runId = runId.value,
                                            trigger = operation.trigger,
                                            runMode = RunMode.FULL_ORGANIZATION,
                                            phase = PhaseCode.PREVIEWED,
                                        ),
                                    )
                                }
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
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
        return started
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
        operation.lease.close()
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = operation.runId.value,
                trigger = operation.trigger,
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
        try {
            emit(
                RunEvent(
                    journalSequence = 0L,
                    runId = operation.runId.value,
                    trigger = operation.trigger,
                    runMode = RunMode.FULL_ORGANIZATION,
                    phase = PhaseCode.USER_CONFIRMED,
                ),
            )
            val materialized = if (pendingPlan.previewPlan != null) {
                OrganizationPlanMaterializer.Result.Ready(pendingPlan.previewPlan)
            } else {
                application.materialize(pendingPlan.input, pendingPlan.result)
            }
            val plan = (materialized as? OrganizationPlanMaterializer.Result.Ready)?.plan
            if (plan == null) {
                transitionToStale(operation, emitRejection = true)
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
            operation.lease.close()
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
    }

    fun beginRecoveryPreview() {
        val lease = operationGate.tryAcquire(OrganizationOperationLease.Kind.RECOVERY) ?: return
        val request = synchronized(lock) {
            val current = lastVerifiedApply
            val pointId = appliedPoint
            if (current == null || pointId == null || activeOperation != null || recoveryLease != null) {
                null
            } else {
                recoveryLease = lease
                stateHolder.value = State.InspectingRecovery
                pointId to current
            }
        }
        if (request == null) {
            lease.close()
            return
        }
        val (point, previous) = request
        val preview = try {
            application.inspectRecovery(point)
        } catch (failure: Throwable) {
            cancelRecoveryPreview()
            throw failure
        }
        val updated = synchronized(lock) {
            if (lastVerifiedApply !== previous || state !is State.InspectingRecovery) {
                false
            } else {
                pendingRecovery = preview as? RecoveryPreviewResult.Restorable
                stateHolder.value = State.RecoveryPreview(preview)
                true
            }
        }
        if (!updated) {
            val abandoned = synchronized(lock) { recoveryLease.also { recoveryLease = null } }
            abandoned?.close()
        }
    }

    fun cancelRecoveryPreview() {
        val lease = synchronized(lock) {
            pendingRecovery = null
            stateHolder.value = lastVerifiedApply ?: State.Idle
            recoveryLease.also { recoveryLease = null }
        }
        lease?.close()
    }

    fun confirmRecovery() {
        val preview = synchronized(lock) {
            val current = pendingRecovery ?: return
            stateHolder.value = State.Recovering
            pendingRecovery = null
            current
        }
        val result = try {
            application.confirmRecovery(preview.pointId, preview.confirmation)
        } catch (failure: Throwable) {
            cancelRecoveryPreview()
            throw failure
        }
        val lease = synchronized(lock) {
            if (state is State.Recovering) stateHolder.value = State.RecoveryResultState(result)
            recoveryLease.also { recoveryLease = null }
        }
        lease?.close()
    }

    fun dismiss(): DismissalOutcome {
        val recovery = synchronized(lock) {
            if (activeOperation == null && recoveryLease != null) {
                pendingRecovery = null
                stateHolder.value = lastVerifiedApply ?: State.Idle
                recoveryLease.also { recoveryLease = null }
            } else {
                null
            }
        }
        if (recovery != null) {
            recovery.close()
            return DismissalOutcome.CancelledAndMayNavigate
        }
        val operation = synchronized(lock) {
            val operation = activeOperation
            if (operation?.applicationAdmitted?.get() == true) {
                return@synchronized DismissalOutcome.ApplicationInProgress to null
            }
            if (operation == null) {
                return@synchronized DismissalOutcome.NoActiveOperation to null
            }
            operation.cancelled.set(true)
            activeOperation = null
            pending = null
            pendingRecovery = null
            stateHolder.value = State.Cancelled
            DismissalOutcome.CancelledAndMayNavigate to operation
        }
        operation.second?.lease?.close()
        operation.second?.let {
            emit(
                RunEvent(
                    journalSequence = 0L,
                    runId = it.runId.value,
                    trigger = it.trigger,
                    runMode = RunMode.FULL_ORGANIZATION,
                    phase = PhaseCode.USER_CANCELLED,
                ),
            )
        }
        return operation.first
    }

    /**
     * Issue #194: publishes the count summary together with the optional
     * change-level preview details. `preview == null` is the count-only
     * compatibility fallback; the previewed executable plan itself stays
     * private to [PendingPlan] and is never exposed through [State].
     */
    private fun enterPreview(
        operation: Operation,
        input: OrganizationInput,
        result: PlanningResult,
        summary: Summary,
        preview: PlanPreview?,
    ) {
        synchronized(lock) {
            if (!isActiveLocked(operation)) return
            pending = PendingPlan(operation, input, result, summary, preview?.plan)
            stateHolder.value = State.Preview(summary, preview?.details)
        }
    }

    /**
     * Ends the active run in [State.Stale]. [emitRejection] reproduces the
     * existing A2 stale-rejection run event for materialize-time staleness.
     */
    private fun transitionToStale(operation: Operation, emitRejection: Boolean) {
        val stale = synchronized(lock) {
            if (!isActiveLocked(operation)) {
                false
            } else {
                pending = null
                activeOperation = null
                stateHolder.value = State.Stale
                true
            }
        }
        if (stale) {
            operation.lease.close()
            if (emitRejection) emitStaleRejection(operation)
        }
    }

    private fun beginOperation(trigger: Trigger): Operation? {
        val lease = operationGate.tryAcquire(OrganizationOperationLease.Kind.RUN) ?: return null
        return synchronized(lock) {
            if (activeOperation != null || recoveryLease != null) {
                lease.close()
                return@synchronized null
            }
            val operation = Operation(application.newRunId(), trigger, lease)
            activeOperation = operation
            pending = null
            pendingRecovery = null
            appliedPoint = null
            lastVerifiedApply = null
            stateHolder.value = State.Capturing
            operation
        }
    }

    private fun isActive(operation: Operation): Boolean = synchronized(lock) { isActiveLocked(operation) }

    private fun isActiveLocked(operation: Operation): Boolean = activeOperation === operation && !operation.cancelled.get()

    private fun setIfActive(operation: Operation, nextState: State) {
        synchronized(lock) {
            if (isActiveLocked(operation)) stateHolder.value = nextState
        }
    }

    private fun finish(operation: Operation, nextState: State) {
        val completed = synchronized(lock) {
            if (!isActiveLocked(operation)) {
                false
            } else {
                activeOperation = null
                pending = null
                stateHolder.value = nextState
                true
            }
        }
        if (completed) operation.lease.close()
    }

    private fun abort(operation: Operation) {
        val aborted = synchronized(lock) {
            if (!isActiveLocked(operation)) {
                false
            } else {
                activeOperation = null
                pending = null
                stateHolder.value = State.Cancelled
                true
            }
        }
        if (aborted) operation.lease.close()
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
            organizationStrategy = organizationStrategy,
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
        organizationStrategy = input.rules.organizationStrategy,
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

    /**
     * Issue #172: a run that ends in `InputUnavailable` closes its journal
     * sequence with a terminal `INPUT_NOT_READY` record carrying the privacy-safe
     * readiness code, so exported diagnostics can distinguish capture/lock/bundle/
     * override/evidence reasons instead of trailing `RUN_STARTED`.
     */
    private fun emitInputNotReady(
        operation: Operation,
        composition: OrganizationInputComposition.NotReady,
    ) {
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = operation.runId.value,
                trigger = operation.trigger,
                runMode = RunMode.FULL_ORGANIZATION,
                phase = PhaseCode.INPUT_NOT_READY,
                error = InputReadinessProjection.projectError(composition),
            ),
        )
    }

    private fun emitStaleRejection(operation: Operation) {
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = operation.runId.value,
                trigger = operation.trigger,
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
        /** Previewed executable plan; null means the count-only compatibility fallback. */
        val previewPlan: ValidatedLayoutPlan?,
    ) {
        val runId: RunId
            get() = operation.runId
    }

    private data class Operation(
        val runId: RunId,
        val trigger: Trigger,
        val lease: AutoCloseable,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val applicationAdmitted: AtomicBoolean = AtomicBoolean(false),
    )
}
