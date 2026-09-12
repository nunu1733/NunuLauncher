package app.lawnchair.organizer.ui

import android.content.Context
import app.lawnchair.LawnchairApp
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
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
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectedCandidate
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
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

    /** Issue #228: read-only missing-app detection (zero-write). */
    fun detectMissingAppCandidates(): CandidateDetectionResult

    /** Issue #228 (D-2): composition with the selected candidates as additions. */
    fun composeScopeComposedOrganization(selection: List<CandidateTarget.AppKey>): OrganizationInputComposition

    fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult
    fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result
    fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult
    fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult
    fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult

    /** Issue #271: read-only durable status projection owned by the application module. */
    fun readDurableOrganizerStatus(): OrganizerDurableStatus

    /**
     * Issue #271 review: observable startup-readiness state of the application
     * module. The Settings surface re-reads the durable status when this moves,
     * so a fail-closed read taken during startup reconciliation recovers
     * without the user navigating away.
     */
    val readinessState: StateFlow<ReadinessGate.State>
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

    override fun detectMissingAppCandidates(): CandidateDetectionResult = module.detectMissingAppCandidates(appContext)

    override fun composeScopeComposedOrganization(selection: List<CandidateTarget.AppKey>): OrganizationInputComposition = module.composeScopeComposedManualInput(appContext, selection)

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

    override fun readDurableOrganizerStatus(): OrganizerDurableStatus = module.durableOrganizerStatus()

    override val readinessState: StateFlow<ReadinessGate.State>
        get() = module.readinessGate.stateFlow
}

/** Process-local composition holder. Construction itself is read-only. */
internal object ManualOrganizationModule {
    @Volatile private var instance: ManualOrganizationRun? = null

    fun get(context: Context): ManualOrganizationRun = instance ?: synchronized(this) {
        instance ?: run {
            // Issue #271 review: this surface can be the first screen of a
            // fresh process (the exported PreferenceActivity accepts
            // APPLICATION_PREFERENCES directly). Make LauncherAppState — and
            // with it the application module — exist before it is read, and
            // start the shared reconciliation trigger: with no bound Launcher
            // it also drives the model load itself, so the durable status on
            // this surface reaches its derived value without opening the
            // Launcher (fail-closed only on a genuine load failure/timeout).
            val app = context.applicationContext as LawnchairApp
            com.android.launcher3.LauncherAppState.getInstance(app)
            app.ensureOrganizerStartupReconciliation()
            ProductionManualOrganizationApplication(
                app,
                app.layoutApplicationModule,
            ).let { application ->
                ManualOrganizationRun(application, operationGate = OrganizationOperationLease).also { instance = it }
            }
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

        /** Issue #228: missing-app detection in progress (zero-write). */
        data object CandidateDetection : State

        /**
         * Issue #228: explicit selection of missing apps. [candidates] is the
         * detection-time cut (deterministic display order); an empty list
         * renders the zero-candidates notice and a plain continue. [runId]
         * identifies the owning run so the selection surface resets its
         * process-local state for every new run (D-1). Selection state never
         * persists.
         */
        data class Selecting(val runId: RunId, val candidates: List<DetectedCandidate>) : State

        data object Planning : State
        data class InputUnavailable(val reason: InputReadinessReason) : State

        /**
         * Issue #228 (review P2 #2): a selected candidate stopped resolving
         * between detection and materialization (uninstalled or platform read
         * failure). Typed re-detect outcome — nothing was written; the only
         * paths are starting a fresh detection or navigating away.
         */
        data class CandidateResolutionFailed(
            val failure: app.lawnchair.organizer.application.public.CandidateResolutionFailure,
        ) : State

        data class PlanningRejected(val kind: PlanningFailureKind, val summary: Summary) : State
        data object NoChanges : State
        data class Preview(val summary: Summary, val details: PlanPreviewDetails?) : State

        /**
         * Issue #228 (spec AC-14): a run whose plan contains Add rows cannot
         * be confirmed from a count-only fallback — when the concrete preview
         * is not obtainable, the run stops here and offers re-preview. No
         * write has happened.
         */
        data class PreviewUnavailable(val summary: Summary) : State

        data object Applying : State

        /**
         * Issue #210: carries where staleness was detected, because the
         * user-facing outcome differs. [StaleOrigin.APPLY_BLOCKED] means the
         * user attempted to apply a reviewed proposal and was blocked;
         * [StaleOrigin.DETECTED_BEFORE_REVIEW] means the proposal never reached
         * the confirmation surface. Both are zero-write terminal states.
         */
        data class Stale(val origin: StaleOrigin) : State

        data class Applied(val result: ApplyResult, val summary: Summary) : State
        data object Cancelled : State
        data object InspectingRecovery : State

        /**
         * Issue #230: [appliedSummary] carries the apply-time [Summary] of the
         * verified apply that produced this preview's recovery point. It is
         * populated only when the correlation gate matches (the retained
         * `lastVerifiedApply.result` narrows to `ApplyResult.Applied` with the
         * same pointId); otherwise it is null and the confirmation renders the
         * restore target without a history line. Null never blocks confirm.
         */
        data class RecoveryPreview(val result: RecoveryPreviewResult, val appliedSummary: Summary? = null) : State

        data object Recovering : State
        data class RecoveryResultState(val result: RecoveryResult) : State
    }

    enum class StaleOrigin { APPLY_BLOCKED, DETECTED_BEFORE_REVIEW }

    enum class PlanningFailureKind { INVALID, IMPOSSIBLE }

    data class Summary(
        val movedCount: Int,
        val preservedCount: Int,
        val newFolderCount: Int,
        val newPageCount: Int,
        /** Issue #228: selected candidates this plan creates placements for. */
        val addedCount: Int = 0,
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
        // Issue #228 (review P2 #3): the diagnostics run-mode identity must be
        // constant for the run's whole journal, but it is only known after the
        // selection surface closes (empty selection → full organization,
        // non-empty → scope-composed). RUN_STARTED is therefore emitted when
        // the composed phase begins with the resolved mode, and the pre-select
        // detection/selection window carries no run-mode-bearing events at
        // all. USER_CANCELLED before a selection uses the mode the run would
        // have had — the plain full organization — which is exact because no
        // scope-composed event exists for that runId.
        try {
            // Issue #228: the manual run opens with read-only missing-app
            // detection. Detection itself writes nothing; its failure never
            // blocks the plain full organize (spec §7) — it only skips the
            // selection surface.
            setIfActive(operation, State.CandidateDetection)
            when (val detection = application.detectMissingAppCandidates()) {
                is CandidateDetectionResult.Ready -> setIfActive(operation, State.Selecting(runId, detection.candidates))
                is CandidateDetectionResult.Unavailable -> runComposedPhase(operation, selection = null)
            }
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
        return started
    }

    /**
     * Issue #228: confirms the selection surface and continues the run. An
     * empty selection is valid and composes the plain full organization; a
     * non-empty selection composes the scope-composed run. The state
     * transition happens under the lock so a second confirmation of the same
     * surface cannot double-run the compose/plan/preview phase.
     */
    fun confirmSelection(selection: Set<CandidateTarget.AppKey>) {
        val sortedSelection = selection.sortedWith(
            compareBy({ it.component.value }, { it.profile.value }),
        )
        val operation = synchronized(lock) {
            if (state !is State.Selecting) return
            val current = activeOperation ?: return
            // An empty confirmed selection is the plain full organize —
            // the null selection keeps the legacy composition path.
            if (sortedSelection.isNotEmpty()) {
                current.diagnosticsRunMode = RunMode.SCOPE_COMPOSED_ORGANIZATION
            }
            stateHolder.value = State.Capturing
            current
        }
        try {
            runComposedPhase(operation, selection = sortedSelection.ifEmpty { null })
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
    }

    /**
     * Issue #228 (spec AC-14): re-runs the read-only preview for a retained
     * Add-run whose concrete preview was unavailable. The layout may have
     * moved since planning — staleness surfaces through the same inspect
     * seam as the first attempt.
     */
    fun retryPlanPreview() {
        val retained = synchronized(lock) {
            if (state !is State.PreviewUnavailable) return
            val operation = activeOperation ?: return
            val plan = pending ?: return
            operation to plan
        }
        try {
            handlePlanPreview(retained.first, retained.second.input, retained.second.result, retained.second.summary)
        } catch (failure: Throwable) {
            abort(retained.first)
            throw failure
        }
    }

    private fun runComposedPhase(operation: Operation, selection: List<CandidateTarget.AppKey>?) {
        val runId = operation.runId
        // Issue #228 (review P2 #3): the run's diagnostics mode is resolved
        // here — before this point no run-mode-bearing event exists for the
        // runId — and stays constant for every event that follows
        // (RUN_STARTED through terminal).
        val diagnosticsRunMode = if (selection != null) RunMode.SCOPE_COMPOSED_ORGANIZATION else RunMode.FULL_ORGANIZATION
        operation.diagnosticsRunMode = diagnosticsRunMode
        emit(
            RunEvent(
                journalSequence = 0L,
                runId = runId.value,
                trigger = operation.trigger,
                runMode = diagnosticsRunMode,
                phase = PhaseCode.RUN_STARTED,
            ),
        )
        operation.journalStarted = true
        // The composition performs its own canonical capture (plan §5), so the
        // run re-enters the capturing phase after the selection surface.
        setIfActive(operation, State.Capturing)
        when (
            val composition = if (selection == null) {
                application.composeFullOrganization()
            } else {
                application.composeScopeComposedOrganization(selection)
            }
        ) {
            is OrganizationInputComposition.NotReady -> {
                emitInputNotReady(operation, composition)
                finish(operation, State.InputUnavailable(composition.reason))
            }

            is OrganizationInputComposition.Ready -> {
                if (!isActive(operation)) return
                val input = composition.input
                emit(
                    RunEvent(
                        journalSequence = 0L,
                        runId = runId.value,
                        trigger = operation.trigger,
                        runMode = diagnosticsRunMode,
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
                        candidateItemCount = input.targets.additions.size,
                        candidateItemIds = input.targets.additions.map { it.id.value }.toSet(),
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
                        runMode = diagnosticsRunMode,
                    ),
                )
                when (val outcome = result.outcome) {
                    is Planned -> {
                        val summary = outcome.summary(input)
                        // Review P1 follow-up: scope-unplaced candidates are
                        // a reported overflow, never a failure — but they are
                        // also not "no changes". With no other changes and
                        // nothing placeable, the run ends in the existing
                        // Impossible surface (unplaced counts rendered); with
                        // partial placement it previews the placed Adds and
                        // carries the unplaced counts in the summary.
                        if (summary.movedCount == 0 && summary.newFolderCount == 0 && summary.newPageCount == 0 && summary.addedCount == 0) {
                            if (outcome.unplaced.isEmpty()) {
                                finish(operation, State.NoChanges)
                            } else {
                                finish(operation, State.PlanningRejected(PlanningFailureKind.IMPOSSIBLE, summary))
                            }
                        } else {
                            handlePlanPreview(operation, input, result, summary)
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

    /**
     * Issue #194 + #228: obtains (or re-obtains) the read-only preview. A run
     * without Add rows keeps the count-only compatibility fallback for
     * environmental preview failures; a run with Add rows must never be
     * confirmable without the concrete change list, so the same failures stop
     * at [State.PreviewUnavailable] with a re-preview action instead (spec
     * AC-14).
     */
    private fun handlePlanPreview(
        operation: Operation,
        input: OrganizationInput,
        result: PlanningResult,
        summary: Summary,
    ) {
        val includesAdditions = input.targets.additions.isNotEmpty()
        when (val preview = application.inspectPlan(input, result)) {
            is PlanPreviewResult.Previewed -> enterPreview(operation, input, result, summary, preview.preview)

            is PlanPreviewResult.Stale -> transitionToStale(
                operation,
                emitRejection = true,
                origin = StaleOrigin.DETECTED_BEFORE_REVIEW,
            )

            // Review P2: the typed candidate-resolution failure from the
            // preview seam keeps its identity — re-detect outcome, zero-write.
            is PlanPreviewResult.CandidateResolutionFailed ->
                finish(operation, State.CandidateResolutionFailed(preview.failure))

            is PlanPreviewResult.NotPlannable -> when (preview.reason) {
                PlanPreviewRejection.CAPTURE_FAILED ->
                    if (includesAdditions) {
                        enterPreviewUnavailable(operation, input, result, summary)
                    } else {
                        enterPreview(operation, input, result, summary, null)
                    }

                PlanPreviewRejection.OUTCOME_NOT_PLANNED,
                PlanPreviewRejection.MATERIALIZATION_INVALID,
                -> finish(operation, State.PlanningRejected(PlanningFailureKind.IMPOSSIBLE, summary))
            }

            is PlanPreviewResult.Unavailable,
            PlanPreviewResult.WriterBusy,
            PlanPreviewResult.Concurrent,
            -> if (includesAdditions) {
                enterPreviewUnavailable(operation, input, result, summary)
            } else {
                enterPreview(operation, input, result, summary, null)
            }
        }
        if (state is State.Preview) {
            emit(
                RunEvent(
                    journalSequence = 0L,
                    runId = operation.runId.value,
                    trigger = operation.trigger,
                    runMode = diagnosticsRunModeOf(input),
                    phase = PhaseCode.PREVIEWED,
                ),
            )
        }
    }

    private fun diagnosticsRunModeOf(input: OrganizationInput): RunMode = when (input.runMode) {
        app.lawnchair.organizer.planning.RunMode.ScopeComposedOrganization -> RunMode.SCOPE_COMPOSED_ORGANIZATION
        else -> RunMode.FULL_ORGANIZATION
    }

    private fun enterPreviewUnavailable(
        operation: Operation,
        input: OrganizationInput,
        result: PlanningResult,
        summary: Summary,
    ) {
        synchronized(lock) {
            if (!isActiveLocked(operation)) return
            pending = PendingPlan(operation, input, result, summary, previewPlan = null)
            stateHolder.value = State.PreviewUnavailable(summary)
        }
    }

    fun cancel() {
        val operation = synchronized(lock) {
            val candidate = activeOperation ?: return
            if (candidate.applicationAdmitted.get()) return
            if (state !is State.Preview && state !is State.Capturing && state !is State.CandidateDetection &&
                state !is State.Selecting && state !is State.Planning && state !is State.Applying &&
                state !is State.PreviewUnavailable
            ) {
                return
            }
            candidate.cancelled.set(true)
            pending = null
            activeOperation = null
            stateHolder.value = State.Cancelled
            candidate
        }
        operation.lease.close()
        // Review P2 (runMode correlation): before the composed phase there is
        // no RUN_STARTED for this runId, so the journal must stay empty —
        // USER_CANCELLED without its RUN_STARTED would violate the contract.
        if (operation.journalStarted) {
            emit(
                RunEvent(
                    journalSequence = 0L,
                    runId = operation.runId.value,
                    trigger = operation.trigger,
                    runMode = operation.diagnosticsRunMode,
                    phase = PhaseCode.USER_CANCELLED,
                ),
            )
        }
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
                    runMode = operation.diagnosticsRunMode,
                    phase = PhaseCode.USER_CONFIRMED,
                ),
            )
            val materialized = if (pendingPlan.previewPlan != null) {
                OrganizationPlanMaterializer.Result.Ready(pendingPlan.previewPlan)
            } else {
                application.materialize(pendingPlan.input, pendingPlan.result)
            }
            // Issue #228 (review P2 #2): a candidate that stopped resolving
            // between preview and confirm is a typed re-detect outcome, not a
            // stale-layout one — nothing was written either way.
            val resolutionFailure = (materialized as? OrganizationPlanMaterializer.Result.CandidateResolutionFailed)?.failure
            if (resolutionFailure != null) {
                finish(operation, State.CandidateResolutionFailed(resolutionFailure))
                return
            }
            val plan = (materialized as? OrganizationPlanMaterializer.Result.Ready)?.plan
            if (plan == null) {
                transitionToStale(operation, emitRejection = true, origin = StaleOrigin.APPLY_BLOCKED)
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
                        State.Stale(StaleOrigin.APPLY_BLOCKED)
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
                // Issue #230 correlation gate: expose the retained apply's
                // summary only when it is an ApplyResult.Applied sharing the
                // preview's pointId; any other shape renders without history.
                val retained = lastVerifiedApply
                val correlated = (preview as? RecoveryPreviewResult.Restorable)
                    ?.let { restorable ->
                        (retained?.result as? ApplyResult.Applied)
                            ?.takeIf { it.pointId == restorable.pointId }
                            ?.let { retained.summary }
                    }
                stateHolder.value = State.RecoveryPreview(preview, correlated)
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

    /**
     * Issue #271: read-only projection of the durable recovery-store state for
     * the re-opened Settings surface. No run state is mutated and no lock is
     * involved; the projection itself is owned by the application module.
     */
    fun readDurableOrganizerStatus(): OrganizerDurableStatus = application.readDurableOrganizerStatus()

    /** Observable startup readiness of the application module (see the façade). */
    val readinessState: StateFlow<ReadinessGate.State>
        get() = application.readinessState

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
            // Same journal rule as cancel(): no RUN_STARTED → no events.
            if (it.journalStarted) {
                emit(
                    RunEvent(
                        journalSequence = 0L,
                        runId = it.runId.value,
                        trigger = it.trigger,
                        runMode = it.diagnosticsRunMode,
                        phase = PhaseCode.USER_CANCELLED,
                    ),
                )
            }
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
     * [origin] records whether the user had attempted to apply (Issue #210).
     */
    private fun transitionToStale(operation: Operation, emitRejection: Boolean, origin: StaleOrigin) {
        val stale = synchronized(lock) {
            if (!isActiveLocked(operation)) {
                false
            } else {
                pending = null
                activeOperation = null
                stateHolder.value = State.Stale(origin)
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
        // Review P1 follow-up: strategy-scoped runs can also succeed with
        // scope-unplaced candidates (`Planned.unplaced`) — they surface
        // through the same unplaced-by-reason vocabulary as Impossible.
        val plannedUnplaced = (planningOutcome as? app.lawnchair.organizer.planning.Planned)?.unplaced.orEmpty()
        // Issue #228: candidate placements are Adds, not moves — they leave the
        // moved/preserved vocabulary and surface as their own count.
        val candidateIds = input.targets.additions.map { it.id }.toSet()
        return Summary(
            movedCount = placements.count { it.disposition is Disposition.Moved && it.item !in candidateIds },
            preservedCount = placements.count { it.disposition is Disposition.Preserved },
            newFolderCount = (planningOutcome as? Planned)?.newFolders?.size ?: 0,
            newPageCount = (planningOutcome as? Planned)?.newPages?.size ?: 0,
            addedCount = placements.count { it.item in candidateIds },
            organizationStrategy = organizationStrategy,
            scope = input.summaryScope(),
            movedByReason = placements.mapNotNull { (it.disposition as? Disposition.Moved)?.rationale }.groupingBy { it }.eachCount(),
            preservedByReason = placements.mapNotNull { (it.disposition as? Disposition.Preserved)?.reason }.groupingBy { it }.eachCount(),
            rejectedByReason = rejected.groupingBy { it.code }.eachCount(),
            unplacedByReason = (unplaced + plannedUnplaced).groupingBy { it.reason }.eachCount(),
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
                runMode = operation.diagnosticsRunMode,
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
                runMode = operation.diagnosticsRunMode,
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
    ) {
        /**
         * Issue #228: diagnostics run-mode identity of this operation. Starts
         * as the plain full organization and moves to the scope-composed mode
         * the moment the user confirms a non-empty selection, so terminal
         * events (USER_CANCELLED) never contradict the phases between them.
         */
        @Volatile
        var diagnosticsRunMode: RunMode = RunMode.FULL_ORGANIZATION

        /**
         * Review P2 (runMode correlation): true once the composed phase has
         * emitted RUN_STARTED for this runId. The diagnostics contract starts
         * a run's journal with RUN_STARTED (trigger/runMode anchor), so a
         * cancel during the pre-select detection/selection window — before
         * the mode exists — must leave NO events for the runId, not a
         * USER_CANCELLED without its RUN_STARTED.
         */
        @Volatile
        var journalStarted: Boolean = false
    }
}
