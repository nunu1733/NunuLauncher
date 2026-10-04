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
import app.lawnchair.organizer.application.public.RestorableRecoveryEntry
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
import app.lawnchair.organizer.integration.exchange.PendingImportedIntentModule
import app.lawnchair.organizer.personalization.CandidateScopeProjection
import app.lawnchair.organizer.personalization.ScopeMismatchCause
import app.lawnchair.organizer.personalization.exchange.DetectedCandidateScope
import app.lawnchair.organizer.personalization.exchange.ScopeBindingCauseDerivation
import app.lawnchair.organizer.personalization.exchange.ScopeBindingCurrentScope
import app.lawnchair.organizer.personalization.exchange.ScopeBindingGate
import app.lawnchair.organizer.personalization.exchange.ScopeBindingOutcome
import app.lawnchair.organizer.personalization.exchange.ScopeBindingSessionScope
import app.lawnchair.organizer.personalization.policyIdentity
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidatePlanningIds
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

    /**
     * Issue #205 (spec 205 run connection): full-target composition carrying
     * an accepted intent. The default injects the pure projection into the
     * composed input and replaces the no-intent sentinel identity in the
     * provenance; implementers that compose directly can override this with
     * an equivalent single-pass composition.
     */
    fun composeFullOrganizationWithIntent(
        intent: app.lawnchair.organizer.personalization.PersonalizedIntentProjection?,
    ): OrganizationInputComposition = applyIntent(composeFullOrganization(), intent)

    /** Issue #228: read-only missing-app detection (zero-write). */
    fun detectMissingAppCandidates(): CandidateDetectionResult

    /** Issue #228 (D-2): composition with the selected candidates as additions. */
    fun composeScopeComposedOrganization(selection: List<CandidateTarget.AppKey>): OrganizationInputComposition

    /** Issue #205: scope-composed composition carrying an accepted intent. */
    fun composeScopeComposedOrganizationWithIntent(
        selection: List<CandidateTarget.AppKey>,
        intent: app.lawnchair.organizer.personalization.PersonalizedIntentProjection?,
    ): OrganizationInputComposition = applyIntent(composeScopeComposedOrganization(selection), intent)

    fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult
    fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result
    fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult
    fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult
    fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult

    /** Issue #271: read-only durable status projection owned by the application module. */
    fun readDurableOrganizerStatus(): OrganizerDurableStatus

    /**
     * Issue #376 (D-15): read-only selection of the latest restorable recovery
     * point. Same fail-closed contract as [readDurableOrganizerStatus]; `null`
     * means no valid point or a transient read failure (no CTA).
     */
    fun readRestorableRecoveryEntry(): RestorableRecoveryEntry?

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

    override fun readRestorableRecoveryEntry(): RestorableRecoveryEntry? = module.readRestorableRecoveryEntry()

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
                // Issue #371: the process-wide JIT Usage Access request gate is
                // created here so the run machine and the exchange holder
                // share one instance (the request opportunity is process-
                // scoped, spec 371).
                val usageAccessGate = UsageAccessJitGateProvider.get(app)
                ManualOrganizationRun(
                    application,
                    operationGate = OrganizationOperationLease,
                    usageAccessGate = usageAccessGate,
                    // Issue #417 (spec 417, oracle (s)): the run's gate-held
                    // transaction capability shares THE process-wide exchange
                    // mutation gate (PendingImportedIntentModule.gate() is the
                    // process-wide singleton the exchange holder/controller
                    // also pass), so the atomic generation commit, the bound
                    // export cleanup and the scope-bound discard always run
                    // their bind/clear inside the same gate hold that
                    // serializes every other durable exchange mutation —
                    // lock order run lock → gate, never the reverse.
                    exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(
                        PendingImportedIntentModule.gate(),
                    ),
                ).also { instance = it }
            }
        }
    }
}

/** Issue #205: pure intent injection shared by the WithIntent default methods. */
private fun applyIntent(
    composition: OrganizationInputComposition,
    intent: app.lawnchair.organizer.personalization.PersonalizedIntentProjection?,
): OrganizationInputComposition {
    if (intent == null) return composition
    return when (composition) {
        is OrganizationInputComposition.NotReady -> composition

        is OrganizationInputComposition.Ready -> OrganizationInputComposition.Ready(
            composition.input.copy(intentPreferences = intent),
            composition.provenance.copy(
                personalizedIntent = intent.identity.policyIdentity(),
            ),
        )
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
    // Issue #371: process-scoped just-in-time Usage Access request gate. The
    // default grants immediately (equivalent to an always-resolved
    // opportunity), so existing tests and embedders see today's behavior.
    // Public read access: the hosting surface collects the gate snapshot for
    // waiter wakeup and drives the dialog from it (RunUsageAccessJitDialogHost).
    val usageAccessGate: UsageAccessJitGate = UsageAccessJitGate(isGranted = { true }),
    // Issue #417 (spec 417): the gate-held transaction capability owned by the
    // exchange side. The run keeps its lock held and hands the durable
    // mutation to this capability, which acquires the exchange mutation gate
    // and invokes the run-provided bind/clear callback inside the gate hold —
    // lock order run lock → gate, never the reverse. The default executes the
    // mutation without a gate so embedders/tests that never wire the exchange
    // side keep today's behavior.
    private val exchangeGateTransaction: ExchangeGateTransaction = DirectExchangeGateTransaction,
) {
    enum class DismissalOutcome {
        CancelledAndMayNavigate,
        NoActiveOperation,
        ApplicationInProgress,
    }

    sealed interface StartOutcome {
        data class Started(val runId: RunId) : StartOutcome

        data object Busy : StartOutcome

        /**
         * Issue #375 (spec "rebind admission anchor"): the caller-supplied
         * admission anchor refused the admission after a fresh re-verification
         * (the durable proposal was invalidated, replaced, or expired between
         * rebuild and admission). Nothing was published — no RUN operation, no
         * [State.Capturing], no journal event; the provisional RUN lease was
         * released. Typed so the caller can distinguish it from [Busy].
         */
        data object AdmissionRefused : StartOutcome
    }

    /**
     * Issue #375 (spec "rebind admission anchor"): the seam the exchange flow
     * hands to [start] so the rebind's validity verdict and the run admission
     * share one exclusive boundary. Invoked inside the run's lock section
     * before any operation is created; implementations acquire the exchange
     * mutation gate, re-read the durable record / active session / clock fresh,
     * and either run [complete] — which creates the operation and publishes
     * the first run state inside the same gate hold — and return `true`, or
     * return `false` without running it. When the anchor returns `false` the
     * run publishes nothing and reports [StartOutcome.AdmissionRefused].
     */
    fun interface StartAdmissionAnchor {
        fun verifyAndAdmit(complete: () -> Unit): Boolean
    }

    /**
     * Issue #375 (spec "選択復元初期値"): the rebind entry's selection-surface
     * initial-value mode. [PreviousExplicit] restores the request-time
     * explicit selection (the resolvable subset of the export scope) as the
     * surface's initial values; the values become the run's selection only
     * through the user's explicit confirm (spec 228 D-1 untouched). Idle
     * rebinds and every non-rebind path use [None] (unchecked by default).
     */
    sealed interface SelectionRestore {
        data object None : SelectionRestore

        data object PreviousExplicit : SelectionRestore
    }

    /**
     * Issue #369 (spec RD-7): the user-visible preparation phase behind T-09's
     * phase row (検出 → capture → plan). The [State] enumeration cannot serve
     * this purpose: the legacy admission publish and the real composed capture
     * are the same `State.Capturing`, and StateFlow conflation does not hide
     * intermediate values from a main collector. Updates happen under [lock],
     * always *before* the state publish they describe (phase-before-state
     * ordering), so no collector ever observes the new state with the previous
     * phase and the visible column stays canonical (検出 → [選択] → capture →
     * plan) including the return path from the selection surface.
     */
    enum class PreparationPhase {
        /** Admission through detection, including the legacy admission `Capturing`. */
        DETECTION,

        /** The composed phase (capture + plan projection window until planning). */
        CAPTURE,

        /** Plan execution after a successful composition. */
        PLAN,
    }

    /**
     * Issue #417 (spec 417): process-local authority token for one generation
     * on this run's frozen scope. Owned by the active [Operation]
     * ([Operation.generationEpoch]); claimed/advanced under the run lock at
     * generation start and replacement ([claimGenerationEpoch]), invalidated
     * by interruption, run end and the scope-bound request discard
     * ([invalidateGenerationEpoch]). A generation completion whose epoch is
     * not the operation's current one ([isCurrentEpoch]) is discarded
     * zero-write — no session save, no binding update. The record carries the
     * runId, operationId and the frozen scope identity, so record equality IS
     * the same-operation/same-scope check.
     */
    data class GenerationEpoch(
        val runId: RunId,
        val operationId: String,
        /** The frozen scope identity (the sorted confirmed selection) the generation was claimed against. */
        val scopeIdentity: List<CandidateTarget.AppKey>,
        val epoch: Long,
    )

    /**
     * Issue #417 (spec 417, oracle (s)): the gate-held transaction capability
     * injected from the exchange side. The run calls [withinGate] while
     * HOLDING its run lock; the implementation acquires the process-wide
     * exchange mutation gate, runs [withinGate]'s `durableMutation` inside the
     * gate, invokes `onGateHeld` with the mutation's result while the gate is
     * still held, and returns the result after releasing it. The lock order
     * run lock → gate is therefore structural: the run never takes a lock
     * inside a gate hold, and the bind/clear callbacks run on the same thread
     * that already owns the run lock — a gate→run-lock acquisition cannot
     * exist.
     */
    interface ExchangeGateTransaction {
        fun <R> withinGate(durableMutation: () -> R, onGateHeld: (R) -> Unit): R
    }

    /**
     * Issue #417 (spec 417): outcome of the durable mutation the exchange
     * side supplies to [commitGeneratedSession] (the session save inside the
     * gate). [Committed] is the only outcome that binds.
     */
    enum class PersistOutcome { Committed, WriteFailed }

    /**
     * Issue #417 (spec 417): outcome of the failure-aware store invalidation
     * (`invalidateIf(expectedExportId) -> Committed / NoMatch / WriteFailed`,
     * spec 204 Amend) the exchange side supplies to [cleanupBoundExport] /
     * [discardScopeBoundRequest].
     */
    enum class StoreInvalidationOutcome { Committed, NoMatch, WriteFailed }

    /** Issue #417 (spec 417): typed result of [commitGeneratedSession]. */
    sealed interface GenerationCommitOutcome {
        data object Committed : GenerationCommitOutcome

        data class Rejected(val reason: GenerationCommitRejection) : GenerationCommitOutcome

        data object WriteFailed : GenerationCommitOutcome
    }

    enum class GenerationCommitRejection {
        /** No live confirmed scope on this run (wrong state or dead operation). */
        NOT_CONFIRMED,

        /** [GenerationEpoch] is not the operation's current one — zero-write discard. */
        STALE_EPOCH,
    }

    /** Issue #417 (spec 417): typed result of [cleanupBoundExport]. */
    sealed interface ExportCleanupOutcome {
        /** `Committed` (invalidated) or `NoMatch` (already gone) against the matching binding — the binding was cleared. */
        data object Cleared : ExportCleanupOutcome

        /** Stale cleanup — the current binding is another export (or none): typed no-op, current binding kept. */
        data object Superseded : ExportCleanupOutcome

        /** Store write failed — session and binding kept; retryable with the same expectedExportId. */
        data object WriteFailed : ExportCleanupOutcome
    }

    /** Issue #417 (spec 417): typed result of [discardScopeBoundRequest]. */
    sealed interface ScopeDiscardOutcome {
        /** `Committed`/`NoMatch` on the current binding — binding cleared; the epoch was already invalidated. */
        data object Discarded : ScopeDiscardOutcome

        /** No live confirmed scope on this run — typed no-op. */
        data object NotDiscardable : ScopeDiscardOutcome

        /** Store write failed — session, binding and frozen scope kept; retryable. */
        data object WriteFailed : ScopeDiscardOutcome
    }

    companion object {
        /**
         * Issue #417 (spec 417): the safe default capability — the durable
         * mutation runs directly and `onGateHeld` right after it, with no
         * process-wide gate. Embedders and tests that never wire the exchange
         * side keep today's single-machine semantics.
         */
        val DirectExchangeGateTransaction: ExchangeGateTransaction = object : ExchangeGateTransaction {
            override fun <R> withinGate(durableMutation: () -> R, onGateHeld: (R) -> Unit): R {
                val result = durableMutation()
                onGateHeld(result)
                return result
            }
        }

        /**
         * Issue #417 (spec 417, oracle (s)): the gate-held capability built
         * over a caller-owned [gate] — the production wiring of
         * [exchangeGateTransaction]. The same shape as [DirectExchangeGateTransaction]
         * with the process-wide exchange mutation gate acquired around the
         * durable mutation and the run-provided `onGateHeld` callback, so the
         * bind/clear callbacks always execute while the gate is held and the
         * lock order stays run lock → gate.
         */
        fun gateHeldExchangeGateTransaction(gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate): ExchangeGateTransaction = object : ExchangeGateTransaction {
            override fun <R> withinGate(durableMutation: () -> R, onGateHeld: (R) -> Unit): R = gate.withGate {
                val result = durableMutation()
                onGateHeld(result)
                result
            }
        }
    }

    sealed interface State {
        data object Idle : State
        data object Capturing : State

        /** Issue #228: missing-app detection in progress (zero-write). */
        data object CandidateDetection : State

        /**
         * Issue #228: explicit selection of missing apps. [candidates] is the
         * detection-time cut (deterministic display order). [runId]
         * identifies the owning run so the selection surface resets its
         * process-local state for every new run (D-1). Selection state never
         * persists.
         *
         * Issue #369 (TO-BE D-06): an empty [candidates] list with no
         * [intentScopeCount] candidates and no [scopeRejection] is the internal
         * zero-candidate pass-through — the machine enters this state but
         * `continueWithEmptySelection` immediately continues the composed
         * phase, and the face mapping renders it as the T-09 preparation face,
         * never as the selection surface. The surface only shows for a
         * non-empty cut, or for an empty cut under an intent-bound run whose
         * export scope still holds candidates (the spec 331 mismatch path).
         *
         * Issue #417 (spec 417, AC-3): the empty-cut pass-through remains for
         * `ONBOARDING_PROPOSAL` runs only (D-16). A manual run with an empty
         * cut never enters this state — it publishes [ScopeConfirmed] directly
         * and waits for the method choice.
         *
         * Issue #331: [intentScopeCount] is the export scope's candidate
         * count when a validated intent is bound to this run (guidance only —
         * the user still selects explicitly, D-1); [scopeRejection] carries
         * the accepted typed `SCOPE_MISMATCH` failure when a confirmation was
         * rejected by the scope binding gate (zero-write — the surface
         * re-opens with the re-export guidance).
         *
         * Issue #375: [intentScopeCandidates] is the export scope's identity
         * set (empty = no bound intent) so the surface can highlight the diff
         * against the current selection without reaching into the coordinator;
         * [restoredSelection] carries the rebind's restored initial values
         * ([SelectionRestore.PreviousExplicit] entry only — never persisted,
         * never auto-confirmed).
         */
        data class Selecting(
            val runId: RunId,
            val candidates: List<DetectedCandidate>,
            val intentScopeCount: Int = 0,
            val scopeRejection: app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch? = null,
            val intentScopeCandidates: Set<CandidateTarget.AppKey> = emptySet(),
            val restoredSelection: Set<CandidateTarget.AppKey> = emptySet(),
        ) : State

        /**
         * Issue #417 (spec 417, AC-1): the frozen organization scope of a
         * manual run — the method-choice face's state. Published by
         * [confirmSelection] (manual trigger, no bound intent) or directly by
         * detection completion on an empty cut (AC-3); the run stops here
         * until the method choice consumes it: [planWithConfirmedScope]
         * ("このまま整理") or the scoped exchange flow ("AIに相談"). Nothing
         * composes and nothing is written on entry.
         *
         * [candidates] is the detection-time cut (deterministic display order)
         * and [candidateLabels] its display labels, supplied to exchange
         * generation; [selection] is the confirmed (sorted) selection — an
         * empty one is an explicit zero-selection scope, never an undecided
         * surface (AC-4).
         *
         * The direct attach authority of this state lives on the active
         * operation ([Operation.boundExportId] / [Operation.generationEpoch]);
         * it is process-local and never part of the published state.
         *
         * [intentScopeCount]/[intentScopeCandidates] mirror [Selecting]'s
         * display fields once an intent is attached here; [scopeRejection]
         * carries the typed refusal of [attachIntent] for the face's rejection
         * display (zero-write — the face re-renders with the re-export
         * guidance).
         */
        data class ScopeConfirmed(
            val runId: RunId,
            val candidates: List<DetectedCandidate>,
            val selection: List<CandidateTarget.AppKey>,
            val candidateLabels: Map<CandidateTarget.AppKey, String>,
            val intentScopeCount: Int = 0,
            val intentScopeCandidates: Set<CandidateTarget.AppKey> = emptySet(),
            val scopeRejection: app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch? = null,
        ) : State

        /**
         * Issue #331 (D-5): the scope binding gate rejected a run that could
         * never open a selection surface (detection unavailable). Typed
         * zero-write terminal — the remedy is re-export.
         */
        data class ScopeMismatchFailed(
            val failure: app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch,
        ) : State

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

        /**
         * Issue #371 (spec 371): the JIT Usage Access request pause. Entered
         * only from the composed-phase entry ([runComposedPhase]) when the
         * process-scoped gate returns `Present`/`Wait` — i.e. strictly before
         * the journal opens, so `RUN_STARTED`, composition and every journal
         * event stay unissued while paused. The RUN lease stays held (a second
         * start is `Busy`); cancel/dismiss follow the existing pre-admission
         * rules with the gate's owner-destruction rules applied. Never mapped
         * to a new user-visible state (face mapping renders the T-09
         * preparation face; the dialog is a modal overlay).
         *
         * [selection] is the confirmed selection the composed phase resumes
         * with (`null` = plain full organization). [isOwner] mirrors the gate
         * decision at entry: `true` = this surface presents the request
         * dialog; `false` = another owner is presenting and this host waits
         * for the gate's resolution.
         */
        data class AwaitingUsageAccessJit(
            val runId: RunId,
            val selection: List<CandidateTarget.AppKey>?,
            val isOwner: Boolean,
        ) : State

        /**
         * Issue #371: internal single-shot claim between the JIT resolution
         * and the composed phase. Committing this state under the run lock is
         * what makes [continueAfterUsageAccessGate] idempotent — a second
         * resolution callback finds the state already past
         * [AwaitingUsageAccessJit] and no-ops — while the user-visible capture
         * commit (`Capturing` + `preparationPhase=CAPTURE`) stays inside
         * [runComposedPhase]'s RD-6 lock section, exactly as for the
         * non-JIT paths.
         */
        data class ResumingUsageAccessJit(
            val runId: RunId,
            val selection: List<CandidateTarget.AppKey>?,
        ) : State
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

    // Issue #368: run/recovery operation lifetime, independent of the display
    // State enumeration. Terminal states (Applied, NoChanges, Stale, …) stay
    // visible after the operation ends, so the strategy surface must not read
    // them as "run active"; this projection tracks the actual lifetime
    // (activeOperation / recoveryLease). It is not equivalent to admission
    // domain occupancy: another AUTHORING token can hold the domain.
    private val operationActiveHolder = MutableStateFlow(false)

    /** True while a run or recovery operation is alive (spec #368). */
    val operationActive: StateFlow<Boolean> = operationActiveHolder.asStateFlow()

    // Issue #369 (spec RD-7): deterministic user-visible preparation phase for
    // T-09. Reset to DETECTION on every admission and only advanced under the
    // lock, before the state publish it describes.
    private val preparationPhaseHolder = MutableStateFlow(PreparationPhase.DETECTION)

    /** Issue #369 (spec RD-7): the visible 検出 → capture → plan progression. */
    val preparationPhase: StateFlow<PreparationPhase> = preparationPhaseHolder.asStateFlow()

    private val lock = Any()
    private var activeOperation: Operation? = null
    private var pending: PendingPlan? = null
    private var appliedPoint: RecoveryPointId? = null
    private var pendingRecovery: RecoveryPreviewResult.Restorable? = null
    private var recoveryLease: AutoCloseable? = null
    private var lastVerifiedApply: State.Applied? = null

    /**
     * Issue #376 (spec D5): which surface opened the live recovery flow, and
     * the display state to restore when the flow leaves through a
     * status-card-origin cancel (preview cancel/dismiss) or the explicit
     * result-face hub return. Process-local, never persisted; the entry
     * origin also decides the cancel return target so a hub-origin flow can
     * never land on a stale `State.Applied` face. Cleared by every flow exit
     * (cancel, hub return) and by a new run admission.
     */
    private enum class RecoveryEntryOrigin {
        AppliedSurface,
        HubStatusCard,
    }

    private var recoveryEntryOrigin: RecoveryEntryOrigin? = null
    private var recoveryEntryReturnState: State? = null

    /**
     * Issue #376 (spec D5): process-local handoff for the hub CTA's
     * navigation. The tap arms it; the durable-recovery run destination
     * consumes it exactly once before admitting. Being process-local, it
     * dies with the process — after a process death the restored route finds
     * nothing to consume and pops back to the hub, so the only restart path
     * is the status card's CTA again (RS-AC-03). Never persisted.
     */
    @Volatile private var durableEntryLaunchArmed = false

    /**
     * Process-stable identity of this coordinator instance (random per
     * instance, never persisted). The durable-recovery destination compares
     * it against what it has already handled: the same instance across a
     * child-destination round trip means "already handled", a different id
     * means a fresh process whose arm/handoff died with its predecessor.
     */
    val processInstanceId: String = java.util.UUID.randomUUID().toString()

    fun armDurableEntryLaunch() {
        durableEntryLaunchArmed = true
    }

    fun consumeDurableEntryLaunchArm(): Boolean {
        val armed = durableEntryLaunchArmed
        durableEntryLaunchArmed = false
        return armed
    }

    private fun updateOperationActiveLocked() {
        operationActiveHolder.value = activeOperation != null || recoveryLease != null
    }

    fun start(trigger: Trigger = Trigger.MANUAL_FULL): StartOutcome = start(trigger, intent = null)

    /**
     * Issue #205: run entry from an imported, validated personalization intent
     * (spec 205 "process recreation後のrun再構築"). The connection is a fresh
     * run — a new `RunId` through the same single-active-operation gate and
     * the normal flow (detection → selection → planning); the intent rides
     * along as the pure planner projection and never bypasses preview or
     * confirmation. A `Busy` outcome tells the caller to re-import after the
     * active run ends (the validated intent is not retained here).
     *
     * Issue #375: the rebind entry passes [admissionAnchor] so the proposal's
     * validity verdict and the admission share one exclusive boundary, and
     * [selectionRestore] = [SelectionRestore.PreviousExplicit] to restore the
     * request-time explicit selection as the surface's initial values. The
     * anchor is invoked before any operation exists; a refusal publishes
     * nothing and reports [StartOutcome.AdmissionRefused]. Existing callers
     * (null anchor, [SelectionRestore.None]) keep today's behavior exactly.
     */
    fun start(
        trigger: Trigger = Trigger.MANUAL_FULL,
        intent: app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent?,
        admissionAnchor: StartAdmissionAnchor? = null,
        selectionRestore: SelectionRestore = SelectionRestore.None,
    ): StartOutcome {
        when (val attempt = beginAdmission(trigger, intent, admissionAnchor)) {
            is StartAttempt.Busy -> return StartOutcome.Busy
            is StartAttempt.Refused -> return StartOutcome.AdmissionRefused
            is StartAttempt.Admitted -> return startAdmitted(attempt.operation, selectionRestore)
        }
    }

    /**
     * Issue #375: the admission boundary. Acquires the provisional RUN lease
     * (as today), then under the run lock either creates the operation
     * directly (no anchor) or delegates the create/verify pairing to the
     * anchor, which may refuse — publishing nothing.
     */
    private fun beginAdmission(
        trigger: Trigger,
        intent: app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent?,
        admissionAnchor: StartAdmissionAnchor?,
    ): StartAttempt {
        val lease = operationGate.tryAcquire(OrganizationOperationLease.Kind.RUN) ?: return StartAttempt.Busy
        return synchronized(lock) {
            if (activeOperation != null || recoveryLease != null) {
                lease.close()
                return@synchronized StartAttempt.Busy
            }
            var created: Operation? = null
            val complete = {
                val operation = Operation(application.newRunId(), trigger, lease, intent)
                activeOperation = operation
                pending = null
                pendingRecovery = null
                appliedPoint = null
                lastVerifiedApply = null
                // Issue #376 (spec D5): a fresh run dissolves any live recovery
                // flow identity — the entry origin never outlives its flow.
                recoveryEntryOrigin = null
                recoveryEntryReturnState = null
                // Issue #369 (RD-7): a fresh run always starts the visible
                // progression at detection — the legacy admission Capturing below
                // projects as 検出, so the first visible phase is never capture.
                preparationPhaseHolder.value = PreparationPhase.DETECTION
                stateHolder.value = State.Capturing
                updateOperationActiveLocked()
                created = operation
            }
            val admitted = admissionAnchor?.verifyAndAdmit(complete) ?: run {
                complete()
                true
            }
            if (admitted) {
                StartAttempt.Admitted(created ?: error("anchor reported admission without completing it"))
            } else {
                lease.close()
                StartAttempt.Refused
            }
        }
    }

    private sealed interface StartAttempt {
        data class Admitted(val operation: Operation) : StartAttempt

        data object Busy : StartAttempt

        data object Refused : StartAttempt

        fun operationOrNull(): Operation? = (this as? Admitted)?.operation
    }

    private fun startAdmitted(operation: Operation, selectionRestore: SelectionRestore): StartOutcome {
        val started = StartOutcome.Started(operation.runId)
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
                is CandidateDetectionResult.Ready -> {
                    // Issue #369 (RD-6): the detection cut is accepted under
                    // the lock with an active re-check, so a cancel during
                    // detection can never publish a selection surface for a
                    // dead operation.
                    if (!acceptDetection(operation, detection)) return started
                    val exportedScopeCandidates = operation.intent?.session?.scopeCandidates
                    if (detection.candidates.isEmpty() && exportedScopeCandidates.isNullOrEmpty()) {
                        if (operation.trigger != Trigger.ONBOARDING_PROPOSAL && operation.intent == null) {
                            // Issue #417 (spec 417, AC-3): a manual run's empty
                            // cut skips the selection surface at the STATE
                            // level — `ScopeConfirmed(empty)` publishes
                            // directly and the composed phase waits for the
                            // method choice. Zero write, zero composition
                            // here; an empty confirmed scope is the explicit
                            // zero-selection scope (AC-4), never undecided.
                            setIfActive(
                                operation,
                                State.ScopeConfirmed(
                                    runId = operation.runId,
                                    candidates = emptyList(),
                                    selection = emptyList(),
                                    candidateLabels = emptyMap(),
                                ),
                            )
                        } else {
                            // Issue #369 (TO-BE D-06, spec RD-3): an empty cut
                            // never shows the selection surface. The machine still
                            // enters `Selecting` (transition contract unchanged —
                            // disposition §3.3) and the coordinator itself drives
                            // the continuation an explicit empty confirmation
                            // would take; the composed-phase gate below re-checks
                            // cancellation.
                            //
                            // Issue #417 (spec 417): since the manual empty cut
                            // publishes [State.ScopeConfirmed] directly, this
                            // continuation is reached only by an onboarding run
                            // (D-16 fixed path).
                            setIfActive(operation, State.Selecting(operation.runId, detection.candidates, intentScopeCount = 0))
                            continueWithEmptySelection(operation)
                        }
                    } else {
                        // Issue #375: a PreviousExplicit rebind restores the
                        // resolvable subset of the export scope as the
                        // surface's initial values (initial state only — the
                        // explicit confirm is what commits it).
                        val restored = if (selectionRestore == SelectionRestore.PreviousExplicit && exportedScopeCandidates != null) {
                            ScopeBindingCauseDerivation.deriveRestoredSelection(
                                sessionScope = exportedScopeCandidates.toSet(),
                                detected = detection.candidates.map { candidate ->
                                    DetectedCandidateScope(candidate.target, candidate.availability)
                                },
                            )
                        } else {
                            emptySet()
                        }
                        setIfActive(
                            operation,
                            State.Selecting(
                                operation.runId,
                                detection.candidates,
                                intentScopeCount = exportedScopeCandidates?.size ?: 0,
                                intentScopeCandidates = exportedScopeCandidates.orEmpty().toSet(),
                                restoredSelection = restored,
                            ),
                        )
                    }
                }

                is CandidateDetectionResult.Unavailable -> {
                    if (!acceptDetection(operation, detection)) return started
                    runComposedPhase(operation, selection = null)
                }
            }
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
        return started
    }

    /**
     * Issue #369 (spec RD-6): accepts the detection result under the lock with
     * an active re-check. Returns false when the operation was cancelled while
     * the detector ran — the caller must then return without publishing any
     * state or touching the journal (a cancelled run keeps its journal empty
     * and its lease released exactly once by [cancel]).
     */
    private fun acceptDetection(operation: Operation, detection: CandidateDetectionResult): Boolean = synchronized(lock) {
        if (!isActiveLocked(operation)) return false
        when (detection) {
            // Issue #331: retain the detection cut so a scope binding
            // rejection can restore the selection surface.
            is CandidateDetectionResult.Ready -> operation.detectedCandidates = detection.candidates

            is CandidateDetectionResult.Unavailable -> Unit
        }
        true
    }

    /**
     * Issue #369 (TO-BE D-06, spec RD-3): continues an empty detection cut
     * into the composed phase. The state machine already entered
     * [State.Selecting]; this internal continuation takes the same path an
     * explicit empty confirmation would — without faking a user action, since
     * there is nothing to select (spec 228 D-1 covers candidates). The
     * composed-phase gate re-checks cancellation, so a cancel during detection
     * still wins; a cancel after the gate sees a started journal and emits
     * `USER_CANCELLED` per the existing contract.
     *
     * Issue #417 (spec 417, AC-3): reached by onboarding runs only (D-16) —
     * a manual run's empty cut publishes [State.ScopeConfirmed] directly and
     * waits for the method choice instead of continuing here.
     */
    private fun continueWithEmptySelection(operation: Operation) {
        synchronized(lock) {
            if (!isActiveLocked(operation)) return
        }
        try {
            // Issue #371: the user-visible capture commit moved into
            // runComposedPhase's RD-6 lock section so the JIT pause (which can
            // only happen at that entry) never shows a capture that has not
            // started. Phase-before-state ordering is preserved there.
            runComposedPhase(operation, selection = null)
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
    }

    /**
     * Issue #228: confirms the selection surface and continues the run. An
     * empty selection is valid (the explicit zero-selection scope, spec AC-4);
     * a non-empty selection scopes the run to the selected candidates. The
     * state transition happens under the lock so a second confirmation of the
     * same surface cannot double-run the compose/plan/preview phase.
     *
     * Issue #417 (spec 417, AC-1): only an onboarding run (D-16) and an
     * intent-bound manual run (rebind / import continuation) continue straight
     * into the composed phase from here. A manual run without a bound intent
     * stops at [State.ScopeConfirmed] — the frozen scope is published and the
     * method choice decides when (and whether) the composed phase runs.
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
            // Issue #331 (spec D-2): the early scope equality gate. A run
            // consuming a validated intent may only organize the exported
            // candidate set — missing and extra selections are both a
            // `SCOPE_MISMATCH` (zero-write; the surface re-opens with the
            // re-export guidance). Resolvability and the projection digest
            // are re-checked against the composition below.
            // Issue #375: the mismatch cause is now derived by the shared
            // pure derivation (unresolvable-before-set-mismatch, same
            // ordering as the composed-phase gate), so the remedy guidance
            // can distinguish "fix the selection" from "re-create the
            // request". The pass/fail outcome is identical to the previous
            // sorted-list equality.
            val intent = current.intent
            val earlyCause = intent?.let {
                ScopeBindingCauseDerivation.deriveConfirmMismatch(
                    sessionScope = it.session.scopeCandidates,
                    detected = current.detectedCandidates.orEmpty().map { candidate ->
                        DetectedCandidateScope(candidate.target, candidate.availability)
                    },
                    selected = selection,
                )
            }
            if (earlyCause != null) {
                stateHolder.value = State.Selecting(
                    current.runId,
                    current.detectedCandidates.orEmpty(),
                    intentScopeCount = intent!!.session.scopeCandidates.size,
                    scopeRejection = app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(earlyCause),
                    intentScopeCandidates = intent.session.scopeCandidates.toSet(),
                )
                null
            } else if (current.trigger == Trigger.ONBOARDING_PROPOSAL || current.intent != null) {
                // Issue #417 (spec 417, D-16): an onboarding run — regardless
                // of intent — and an intent-bound manual run (the rebind /
                // import continuation) never reach the method-choice face;
                // the early gate's pass flows straight into the composed
                // phase, unchanged.
                //
                // Issue #371: the visible capture commit moved into
                // runComposedPhase's RD-6 lock section (authoritative for all
                // entry paths), so the JIT pause at that entry can never
                // show a capture that has not started. The state stays
                // `Selecting` until the composed phase actually begins; the
                // phase-before-state ordering (RD-7) is preserved in the
                // entry's lock section.
                current
            } else {
                // Issue #417 (spec 417, AC-1): a manual run without a bound
                // intent stops here — the frozen scope is published and the
                // method choice (このまま整理 / AIに相談) happens on the
                // [State.ScopeConfirmed] face. No composition, no write;
                // [planWithConfirmedScope] and [attachIntent] continue from
                // here.
                val cut = current.detectedCandidates.orEmpty()
                stateHolder.value = State.ScopeConfirmed(
                    runId = current.runId,
                    candidates = cut,
                    selection = sortedSelection,
                    candidateLabels = cut.associate { it.target to it.label },
                )
                null
            }
        } ?: return
        try {
            runComposedPhase(operation, selection = sortedSelection.ifEmpty { null })
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
    }

    /**
     * Issue #417 (spec 417, AC-1): the "このまま整理" arm of the method-choice
     * face. From [State.ScopeConfirmed] the composed phase runs with the
     * frozen selection — an empty selection keeps the plain full organization
     * (the null-selection legacy composition path). A wrong state or a dead
     * operation is a no-op: nothing is composed, nothing is written.
     */
    fun planWithConfirmedScope() {
        val claimed = synchronized(lock) {
            val current = state as? State.ScopeConfirmed ?: return
            val operation = activeOperation ?: return
            if (operation.runId != current.runId || !isActiveLocked(operation)) return
            operation to current.selection
        }
        val (operation, selection) = claimed
        try {
            runComposedPhase(operation, selection = selection.ifEmpty { null })
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
    }

    /**
     * Issue #417 (spec 417, AC-5): re-opens the selection surface from
     * [State.ScopeConfirmed], republishing [State.Selecting] with the same
     * detection cut — the UI state keeps the selection; no layout write. Only
     * a NON-EMPTY cut re-opens: an empty-cut `ScopeConfirmed` always refuses
     * (Back from the method-choice face is an interruption there, zero-write).
     * Whether an active AI request must be discarded first is NOT decided
     * here — the hosting surface calls this only after the scope-bound request
     * discard ([discardScopeBoundRequest]) succeeded.
     */
    fun reopenSelection(): Boolean = synchronized(lock) {
        val current = state as? State.ScopeConfirmed ?: return@synchronized false
        val operation = activeOperation ?: return@synchronized false
        if (operation.runId != current.runId || !isActiveLocked(operation)) return@synchronized false
        if (current.candidates.isEmpty()) return@synchronized false
        stateHolder.value = State.Selecting(
            current.runId,
            current.candidates,
            intentScopeCount = current.intentScopeCount,
            intentScopeCandidates = current.intentScopeCandidates,
        )
        true
    }

    /**
     * Issue #371 (spec 371): continues the composed phase after the JIT
     * Usage Access request resolved. Single-shot by construction: the run
     * lock commits [State.ResumingUsageAccessJit] (consuming the pause), so a
     * second resolution callback — a racing `ON_RESUME`, a double-tap, a late
     * host retry — finds no pause to claim and returns without running
     * anything. The user-visible capture commit is left entirely to
     * [runComposedPhase]'s RD-6 lock section, exactly as for the non-JIT
     * paths.
     */
    fun continueAfterUsageAccessGate() {
        val claimed = synchronized(lock) {
            val current = state as? State.AwaitingUsageAccessJit ?: return
            val op = activeOperation ?: return
            if (op.runId != current.runId || !isActiveLocked(op)) return
            // Resolution is idempotent and owner-checked; safe to call for the
            // non-owner (waiter) path too, where it is a no-op.
            usageAccessGate.resolve(current.runId)
            stateHolder.value = State.ResumingUsageAccessJit(current.runId, current.selection)
            op to current.selection
        }
        val (operation, selection) = claimed
        try {
            runComposedPhase(operation, selection = selection)
        } catch (failure: Throwable) {
            abort(operation)
            throw failure
        }
    }

    /**
     * Issue #371: applies the spec's owner-destruction rules to the gate when
     * the paused/resuming operation goes away (cancel, dismiss). The gate's
     * [UsageAccessJitGate.abandon] completes the state-specific action —
     * release an un-presented reservation, abandon-resolve a presented
     * request — under one monitor, so a racing dialog presentation can never
     * orphan the barrier.
     */
    private fun destroyUsageAccessGateOwnership(runId: RunId) {
        usageAccessGate.abandon(runId)
    }

    /**
     * Issue #331 (spec §5): connects a validated intent to the run already
     * holding the selection surface (the run-in exchange entry). Single-shot:
     * a run binds at most one intent, and only while the selection surface is
     * open. Zero-write; the validated intent is not retained on refusal.
     *
     * Issue #417 (spec 417): also accepts while the run holds the frozen
     * scope ([State.ScopeConfirmed] — the method-choice face). There the same
     * pure derivation as the confirm-time early gate
     * ([ScopeBindingCauseDerivation.deriveConfirmMismatch]) runs against the
     * confirmed selection: a mismatch is a typed
     * [AttachIntentOutcome.Rejected] (zero-write; the face re-renders with
     * [State.ScopeConfirmed.scopeRejection]), a match binds the intent and
     * proceeds straight into the composed phase — the import-success CTA is
     * the explicit consent point, so no additional confirmation is inserted.
     */
    fun attachIntent(intent: app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent): AttachIntentOutcome {
        val claimed: Triple<AttachIntentOutcome, Operation?, List<CandidateTarget.AppKey>> = synchronized(lock) {
            when (val current = state) {
                is State.Selecting -> {
                    val operation = activeOperation
                    if (operation == null || !isActiveLocked(operation) || operation.intent != null) {
                        Triple(AttachIntentOutcome.NotAttachable, null, emptyList<CandidateTarget.AppKey>())
                    } else {
                        operation.intent = intent
                        stateHolder.value = current.copy(
                            intentScopeCount = intent.session.scopeCandidates.size,
                            intentScopeCandidates = intent.session.scopeCandidates.toSet(),
                        )
                        Triple(AttachIntentOutcome.Attached, null, emptyList<CandidateTarget.AppKey>())
                    }
                }

                is State.ScopeConfirmed -> {
                    val operation = activeOperation
                    if (operation == null || !isActiveLocked(operation) || operation.intent != null ||
                        operation.runId != current.runId
                    ) {
                        Triple(AttachIntentOutcome.NotAttachable, null, emptyList<CandidateTarget.AppKey>())
                    } else {
                        val cause = ScopeBindingCauseDerivation.deriveConfirmMismatch(
                            sessionScope = intent.session.scopeCandidates,
                            detected = current.candidates.map { candidate ->
                                DetectedCandidateScope(candidate.target, candidate.availability)
                            },
                            selected = current.selection.toSet(),
                        )
                        if (cause != null) {
                            val failure =
                                app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(cause)
                            stateHolder.value = current.copy(scopeRejection = failure)
                            Triple(AttachIntentOutcome.Rejected(failure), null, emptyList<CandidateTarget.AppKey>())
                        } else {
                            operation.intent = intent
                            stateHolder.value = current.copy(
                                intentScopeCount = intent.session.scopeCandidates.size,
                                intentScopeCandidates = intent.session.scopeCandidates.toSet(),
                                scopeRejection = null,
                            )
                            Triple(AttachIntentOutcome.Attached, operation, current.selection)
                        }
                    }
                }

                else -> Triple(AttachIntentOutcome.NotAttachable, null, emptyList<CandidateTarget.AppKey>())
            }
        }
        val (outcome, operation, selection) = claimed
        if (operation != null) {
            try {
                runComposedPhase(operation, selection = selection.ifEmpty { null })
            } catch (failure: Throwable) {
                abort(operation)
                throw failure
            }
        }
        return outcome
    }

    sealed interface AttachIntentOutcome {
        data object Attached : AttachIntentOutcome

        data object NotAttachable : AttachIntentOutcome

        /**
         * Issue #417 (spec 417): the confirmed-scope attach refusal — typed,
         * zero-write; [State.ScopeConfirmed] was republished with
         * [State.ScopeConfirmed.scopeRejection] for the method-choice face.
         */
        data class Rejected(
            val failure: app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch,
        ) : AttachIntentOutcome
    }

    /**
     * Issue #417 (spec 417, AC-6): claims the generation epoch for a new (or
     * replacement) generation against the frozen scope. Each claim ADVANCES
     * the counter, so a new claim invalidates the previous epoch — a delayed
     * completion of the old generation is rejected zero-write later. Returns
     * null when there is no live [State.ScopeConfirmed] on this run or
     * [scopeIdentity] is not exactly the confirmed selection (fail-closed).
     */
    fun claimGenerationEpoch(scopeIdentity: List<CandidateTarget.AppKey>): GenerationEpoch? = synchronized(lock) {
        val current = state as? State.ScopeConfirmed ?: return@synchronized null
        val operation = activeOperation ?: return@synchronized null
        if (operation.runId != current.runId || !isActiveLocked(operation)) return@synchronized null
        if (scopeIdentity != current.selection) return@synchronized null
        val next = GenerationEpoch(
            runId = operation.runId,
            operationId = operation.operationId,
            scopeIdentity = scopeIdentity,
            epoch = (operation.generationEpoch?.epoch ?: 0L) + 1L,
        )
        operation.generationEpoch = next
        next
    }

    /**
     * Issue #417 (spec 417, AC-6): invalidates the current generation epoch.
     * Interruption and run end invalidate the epoch with the whole operation;
     * this explicit invalidation exists for the paths that keep the operation
     * alive — most notably [discardScopeBoundRequest], which runs it under the
     * run lock BEFORE the capability call. A fresh generation simply claims
     * (and advances) again.
     */
    fun invalidateGenerationEpoch() {
        synchronized(lock) {
            activeOperation?.generationEpoch = null
        }
    }

    /**
     * Issue #417 (spec 417): whether [epoch] is the active operation's current
     * generation epoch. The commit path re-verifies this under the run lock;
     * this read is the cheap stale-check for completion/cleanup paths.
     */
    fun isCurrentEpoch(epoch: GenerationEpoch): Boolean = synchronized(lock) {
        activeOperation?.generationEpoch == epoch
    }

    /**
     * Issue #417 (spec 417, AC-6): the atomic generation commit — session
     * save, epoch re-verification and the [Operation.boundExportId] update in
     * ONE critical section (run lock → exchange mutation gate; there is no
     * save→bind window, so a saved session always has its binding). Requires
     * the live [State.ScopeConfirmed] of this run. Under the run lock the
     * epoch is re-verified first — record equality covers runId, operationId
     * and the frozen scope identity (the same-operation/same-scope check); a
     * stale epoch or a dead confirmed scope is a zero-write
     * [GenerationCommitOutcome.Rejected] and [commit] is never invoked. On
     * success the capability runs [commit] inside the gate and — still inside
     * the gate hold, via the run-provided callback — binds [exportId] ONLY on
     * [PersistOutcome.Committed]; a store save failure never binds.
     */
    fun commitGeneratedSession(
        epoch: GenerationEpoch,
        exportId: String,
        commit: ExchangeGateTransaction.() -> PersistOutcome,
    ): GenerationCommitOutcome = synchronized(lock) {
        val current = state as? State.ScopeConfirmed
        val operation = activeOperation
        if (current == null || operation == null || !isActiveLocked(operation) || operation.runId != current.runId) {
            return@synchronized GenerationCommitOutcome.Rejected(GenerationCommitRejection.NOT_CONFIRMED)
        }
        if (operation.generationEpoch != epoch) {
            return@synchronized GenerationCommitOutcome.Rejected(GenerationCommitRejection.STALE_EPOCH)
        }
        val persisted = exchangeGateTransaction.withinGate(
            durableMutation = { exchangeGateTransaction.commit() },
            onGateHeld = { outcome ->
                if (outcome == PersistOutcome.Committed) {
                    operation.boundExportId = exportId
                }
            },
        )
        when (persisted) {
            PersistOutcome.Committed -> GenerationCommitOutcome.Committed
            PersistOutcome.WriteFailed -> GenerationCommitOutcome.WriteFailed
        }
    }

    /**
     * Issue #417 (spec 417, AC-6 oracle (t)): the cleanup seam for paths that
     * retire the CURRENTLY bound session itself (encode failure, pre-send
     * discard). The conditional invalidation and the binding clear run only
     * when the current binding equals [expectedExportId]; a stale cleanup (the
     * run already moved to another export, or none is bound) is an
     * [ExportCleanupOutcome.Superseded] typed no-op — the store is not touched
     * and the current binding is kept. `NoMatch` counts as cleared ONLY here,
     * against the current binding (never generalized to historical cleanup).
     * A write failure keeps session and binding — the caller retries with the
     * same [expectedExportId] instead of settling (the UI owner is not lost).
     */
    fun cleanupBoundExport(
        expectedExportId: String,
        commit: ExchangeGateTransaction.() -> StoreInvalidationOutcome,
    ): ExportCleanupOutcome = synchronized(lock) {
        val operation = activeOperation
        val bound = operation?.boundExportId
        if (operation == null || state !is State.ScopeConfirmed || bound != expectedExportId) {
            return@synchronized ExportCleanupOutcome.Superseded
        }
        val outcome = exchangeGateTransaction.withinGate(
            durableMutation = { exchangeGateTransaction.commit() },
            onGateHeld = { result ->
                when (result) {
                    StoreInvalidationOutcome.Committed, StoreInvalidationOutcome.NoMatch -> operation.boundExportId = null
                    StoreInvalidationOutcome.WriteFailed -> Unit
                }
            },
        )
        when (outcome) {
            StoreInvalidationOutcome.Committed, StoreInvalidationOutcome.NoMatch -> ExportCleanupOutcome.Cleared
            StoreInvalidationOutcome.WriteFailed -> ExportCleanupOutcome.WriteFailed
        }
    }

    /**
     * Issue #417 (spec 417, AC-5 / oracle (v)): the scope-bound request
     * discard — the one seam that retires the AI request created from this
     * frozen scope so the selection can be re-edited. Order fixed by the
     * accepted plan: (a) under the run lock and BEFORE the capability call the
     * current generation epoch is invalidated (a same-run generation commit
     * needs the run lock, so it cannot interleave — the epoch invalidation
     * therefore precedes the gate-held store invalidation; it is not rolled
     * back on failure: a retry discards, a fresh request claims anew);
     * (b) the capability runs `invalidateIf(boundExportId)` inside the gate;
     * (c) on `Committed`/`NoMatch` the gate-held callback clears
     * [Operation.boundExportId] — the `NoMatch`-as-success proof is limited to
     * this current-binding discard. A write failure keeps the session, the
     * binding and [State.ScopeConfirmed] (typed retryable failure; the
     * method-choice face stays). The dependent imported-proposal handling
     * stays with the exchange side, which proceeds only on
     * [ScopeDiscardOutcome.Discarded]; the hosting surface calls
     * [reopenSelection] only afterwards.
     */
    fun discardScopeBoundRequest(
        commit: ExchangeGateTransaction.(boundExportId: String) -> StoreInvalidationOutcome,
    ): ScopeDiscardOutcome = synchronized(lock) {
        val current = state as? State.ScopeConfirmed
        val operation = activeOperation
        if (current == null || operation == null || !isActiveLocked(operation) || operation.runId != current.runId) {
            return@synchronized ScopeDiscardOutcome.NotDiscardable
        }
        operation.generationEpoch = null
        val bound = operation.boundExportId
        if (bound == null) {
            // Nothing durable is bound: an uncommitted generation was already
            // cut off by the epoch invalidation above — no store call needed.
            return@synchronized ScopeDiscardOutcome.Discarded
        }
        val outcome = exchangeGateTransaction.withinGate(
            durableMutation = { exchangeGateTransaction.commit(bound) },
            onGateHeld = { result ->
                when (result) {
                    StoreInvalidationOutcome.Committed, StoreInvalidationOutcome.NoMatch -> operation.boundExportId = null
                    StoreInvalidationOutcome.WriteFailed -> Unit
                }
            },
        )
        when (outcome) {
            StoreInvalidationOutcome.Committed, StoreInvalidationOutcome.NoMatch -> ScopeDiscardOutcome.Discarded
            StoreInvalidationOutcome.WriteFailed -> ScopeDiscardOutcome.WriteFailed
        }
    }

    /**
     * Issue #417 (spec 417, AC-6 oracles (l)/(s); implementation-review fix):
     * the live-owner pending-import save — the run-owned twin of
     * [commitGeneratedSession]. The run lock is acquired FIRST and kept for
     * the whole critical section; under it the gate-held transaction
     * capability runs the exchange-side [save] (the attempt-fenced durable
     * write) inside the exchange mutation gate, and — still inside the gate
     * hold, via the run-provided [onGateHeld] callback — hands the callback
     * the save result TOGETHER WITH the ownership re-verification (same
     * runId, live [State.ScopeConfirmed], `boundExportId ==
     * expectedExportId`), so a holder-side conditional tombstone for a
     * dropped owner lands in the SAME critical section. The verdict cannot go
     * stale under the hold — every binding mutation ([Operation.boundExportId]
     * and the confirmed-scope lifetime) requires this very run lock, which the
     * calling thread keeps throughout — and no code path acquires the run
     * lock while holding the gate: the lock order stays run lock → gate (the
     * former gate-held `isLiveScopeOwner` read from the holder's own
     * `withGate` block was the gate→run-lock inversion a rebind admission
     * could ABBA-deadlock against).
     */
    fun <T, R> savePendingImportForLiveOwner(
        ownerRunId: RunId,
        expectedExportId: String,
        save: ExchangeGateTransaction.() -> T,
        onGateHeld: ExchangeGateTransaction.(saveResult: T, ownedByLiveScope: Boolean) -> R,
    ): R {
        var settled: R? = null
        synchronized(lock) {
            exchangeGateTransaction.withinGate(
                durableMutation = { exchangeGateTransaction.save() },
                onGateHeld = { saveResult ->
                    settled = exchangeGateTransaction.onGateHeld(saveResult, isLiveScopeOwnerLocked(ownerRunId, expectedExportId))
                },
            )
        }
        // `withinGate` always invokes the gate-held callback before returning
        // (both capability implementations do), so the outcome is settled.
        return settled ?: error("the gate-held save callback did not settle an outcome")
    }

    /**
     * Issue #417 (spec 417, fences (a)/(c)): read-only direct-attach
     * authority check for an import of a RUN_IN-origin session — true only
     * when this run's CURRENT frozen scope is live ([State.ScopeConfirmed])
     * AND owned by [runId] AND the process-local binding equals
     * [expectedExportId] (the exact exportId this run's confirmed scope
     * generated and is still bound to). Scope equality alone never
     * establishes same-run ([GenerationEpoch]/`boundExportId` carry the
     * authority); this query is the small additive hook that lets the
     * exchange side ASK without reaching into the run-private
     * `Operation.boundExportId`.
     */
    fun isLiveScopeOwner(runId: RunId, expectedExportId: String): Boolean = synchronized(lock) {
        isLiveScopeOwnerLocked(runId, expectedExportId)
    }

    /** The [isLiveScopeOwner] verdict; callers hold [lock]. */
    private fun isLiveScopeOwnerLocked(runId: RunId, expectedExportId: String): Boolean {
        val current = state as? State.ScopeConfirmed ?: return false
        val operation = activeOperation ?: return false
        return operation.runId == runId && isActiveLocked(operation) && operation.boundExportId == expectedExportId
    }

    /**
     * Issue #417 (spec 417, AC-5): read-only UI routing fact — whether the
     * live [State.ScopeConfirmed]'s operation currently holds a scope-bound
     * request (a session generated from this very frozen scope). The
     * method-choice face raises the scope-bound discard confirmation on Back
     * ONLY while this is true: a durable legacy IDLE request never references
     * the confirmed scope, so it must never freeze it (the Back path then
     * re-opens the selection without a 破棄確認). Pure read — no mutation, no
     * store access.
     */
    fun hasBoundScopeRequest(): Boolean = synchronized(lock) {
        val current = state as? State.ScopeConfirmed ?: return@synchronized false
        val operation = activeOperation ?: return@synchronized false
        operation.runId == current.runId && isActiveLocked(operation) && operation.boundExportId != null
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

    /**
     * Issue #331 (spec D-4): evaluates the projection digest half of the scope
     * binding gate against the composed planning input. Set equality was
     * checked at confirm; this re-derives the authority-bearing candidate
     * projection (identity + availability + resolved category) from the run's
     * detection cut and the composition output. Returns the mismatch cause, or
     * null when the gate passes.
     */
    private fun evaluateScopeBinding(
        operation: Operation,
        input: OrganizationInput,
        selection: List<CandidateTarget.AppKey>,
    ): ScopeMismatchCause? {
        val intent = operation.intent ?: return null
        val sessionScope = ScopeBindingSessionScope(
            scopeCandidates = intent.session.scopeCandidates,
            scopeCandidateDigest = intent.session.scopeCandidateDigest,
        )
        val detectedById = operation.detectedCandidates.orEmpty().associateBy { it.target }
        // Issue #336: the projection carries the resolved CategoryIdentity
        // itself — built-in candidates keep the pre-336 raw-value digest
        // input byte for byte, and a user-defined identity contributes its
        // kind-discriminated canonical form inside the one-way digest only
        // (never as a persisted field or an export surface). A→B reassignment
        // and assigned-category deletion therefore change the digest exactly
        // as a built-in resolved-category change does.
        val identitiesById = input.signals.entries.associate { it.item to it.candidate }
        val current = ScopeBindingCurrentScope(
            detected = detectedById.values.map { DetectedCandidateScope(it.target, it.availability) },
            selectedTargets = selection.toSet(),
            candidateProjections = selection.map { target ->
                CandidateScopeProjection(
                    target = target,
                    availability = detectedById[target]?.availability ?: Availability.AVAILABLE,
                    category = identitiesById[CandidatePlanningIds.planningId(target)],
                )
            },
        )
        val outcome = ScopeBindingGate.evaluate(sessionScope, current)
        return (outcome as? ScopeBindingOutcome.Mismatch)?.cause
    }

    private fun runComposedPhase(operation: Operation, selection: List<CandidateTarget.AppKey>?) {
        val runId = operation.runId
        // Issue #228 (review P2 #3): the run's diagnostics mode is resolved
        // here — before this point no run-mode-bearing event exists for the
        // runId — and stays constant for every event that follows
        // (RUN_STARTED through terminal).
        val diagnosticsRunMode = if (selection != null) RunMode.SCOPE_COMPOSED_ORGANIZATION else RunMode.FULL_ORGANIZATION
        // Issue #371 (spec 371): the JIT Usage Access request pauses here —
        // the single choke point all three composition paths flow through —
        // strictly before the journal opens, when the process has not yet
        // consumed its one request opportunity. If the pause engages, this
        // call returns without touching the journal; the resolved host calls
        // [continueAfterUsageAccessGate], which re-enters this method.
        val gateDecision = usageAccessGate.evaluate(runId)
        if (gateDecision != UsageAccessJitGate.Decision.Proceed) {
            synchronized(lock) {
                if (!isActiveLocked(operation)) {
                    // The cancel won the race before any state was
                    // published: undo a just-acquired reservation so the
                    // opportunity stays unconsumed, and leave the gate
                    // alone when another owner holds it (a `Wait` runner
                    // never touches the gate on cancellation).
                    usageAccessGate.release(runId)
                    return
                }
                stateHolder.value = State.AwaitingUsageAccessJit(
                    runId,
                    selection,
                    isOwner = gateDecision == UsageAccessJitGate.Decision.Present,
                )
            }
            return
        }
        // Issue #369 (spec RD-6): the entry gate decides start-vs-abandon
        // atomically with the RUN_STARTED emission. T-09's interruption
        // affordance makes cancel during detection user-reachable; a cancelled
        // operation must never open its journal or run a composition. Either
        // the cancel wins first (the gate returns, journal stays empty, lease
        // already closed exactly once) or the gate commits first
        // (USER_CANCELLED follows RUN_STARTED per the existing contract).
        synchronized(lock) {
            if (!isActiveLocked(operation)) return
            operation.diagnosticsRunMode = diagnosticsRunMode
            // Issue #369 (RD-7) / #371: authoritative for ALL entry paths
            // (detection-unavailable continuation, the D-06 empty-cut
            // continuation, selection confirmation and the JIT resume) — the
            // visible capture commit happens here in ONE critical section,
            // phase BEFORE state, before the journal opens. A collector can
            // never observe RUN_STARTED issued while the run still shows
            // Selecting/Resuming, and a paused run never shows a capture
            // that has not started.
            preparationPhaseHolder.value = PreparationPhase.CAPTURE
            stateHolder.value = State.Capturing
            operation.journalStarted = true
            emit(
                RunEvent(
                    journalSequence = 0L,
                    runId = runId.value,
                    trigger = operation.trigger,
                    runMode = diagnosticsRunMode,
                    phase = PhaseCode.RUN_STARTED,
                ),
            )
        }
        when (
            val composition = if (selection == null) {
                application.composeFullOrganizationWithIntent(
                    operation.intent?.let(app.lawnchair.organizer.personalization.IntentPlannerAdapter::project),
                )
            } else {
                application.composeScopeComposedOrganizationWithIntent(
                    selection,
                    operation.intent?.let(app.lawnchair.organizer.personalization.IntentPlannerAdapter::project),
                )
            }
        ) {
            is OrganizationInputComposition.NotReady -> {
                emitInputNotReady(operation, composition)
                finish(operation, State.InputUnavailable(composition.reason))
            }

            is OrganizationInputComposition.Ready -> {
                if (!isActive(operation)) return
                val input = composition.input
                // Issue #331 (spec D-4): the projection digest gate. Set
                // equality was checked at confirm; here the authority-bearing
                // candidate projection (availability + resolved category) is
                // re-derived from the same composition the planner consumes
                // and compared with the session's export-time digest. A
                // mismatch (classification authority drift invisible to the
                // placed-item digest, or a candidate that stopped resolving)
                // is a typed zero-write `SCOPE_MISMATCH`. Per the accepted
                // plan: when a selection surface exists the run RETURNS to it
                // (stale intent discarded so re-export + re-attach is
                // possible); only a run whose detection never opened the
                // surface terminates typed.
                if (operation.intent != null) {
                    val cause = evaluateScopeBinding(operation, input, selection.orEmpty())
                    if (cause != null) {
                        // The accepted typed contract failure (spec 204
                        // taxonomy, 13th class / spec 331 D-5) carries the
                        // rejection through both production surfaces.
                        val failure =
                            app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(cause)
                        val restored = synchronized(lock) {
                            val detected = operation.detectedCandidates
                            if (isActiveLocked(operation) && detected != null) {
                                operation.intent = null
                                stateHolder.value = State.Selecting(
                                    operation.runId,
                                    detected,
                                    intentScopeCount = 0,
                                    scopeRejection = failure,
                                )
                                true
                            } else {
                                false
                            }
                        }
                        if (restored) return
                        emit(
                            RunEvent(
                                journalSequence = 0L,
                                runId = operation.runId.value,
                                trigger = operation.trigger,
                                runMode = diagnosticsRunMode,
                                phase = PhaseCode.INPUT_NOT_READY,
                                error = ErrorEntry(
                                    ErrorFamily.INPUT_READINESS,
                                    app.lawnchair.organizer.integration.InputCompositionCode.SCOPE_BINDING_MISMATCH.name,
                                ),
                            ),
                        )
                        finish(operation, State.ScopeMismatchFailed(failure))
                        return
                    }
                }
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
                // Issue #369 (RD-7): the visible phase commits before the
                // Planning publish, in the same lock section.
                synchronized(lock) {
                    if (isActiveLocked(operation)) {
                        preparationPhaseHolder.value = PreparationPhase.PLAN
                        stateHolder.value = State.Planning
                    }
                }
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
                state !is State.PreviewUnavailable && state !is State.AwaitingUsageAccessJit &&
                state !is State.ResumingUsageAccessJit &&
                // Issue #417: the method-choice face is interruptible — an
                // empty-cut run's Back is an interruption (zero-write).
                state !is State.ScopeConfirmed
            ) {
                return
            }
            candidate.cancelled.set(true)
            pending = null
            activeOperation = null
            stateHolder.value = State.Cancelled
            updateOperationActiveLocked()
            candidate
        }
        operation.lease.close()
        // Issue #371: the pause is pre-RUN_STARTED, so cancelling during it
        // must keep the journal empty (the journalStarted guard below already
        // does) — and the gate ownership must follow the owner-destruction
        // rules: un-presented reservations are released so the opportunity
        // stays unconsumed; an already-presented request resolves as an
        // abandon resolution so waiters are never orphaned.
        destroyUsageAccessGateOwnership(operation.runId)
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
                updateOperationActiveLocked()
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
                // Issue #376 (spec D5): the legacy entry belongs to the Applied
                // success face; its cancel keeps restoring `lastVerifiedApply`.
                recoveryEntryOrigin = RecoveryEntryOrigin.AppliedSurface
                recoveryEntryReturnState = null
                recoveryLease = lease
                stateHolder.value = State.InspectingRecovery
                updateOperationActiveLocked()
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
            val abandoned = synchronized(lock) {
                recoveryEntryOrigin = null
                recoveryEntryReturnState = null
                recoveryLease.also { recoveryLease = null }
                    .also { updateOperationActiveLocked() }
            }
            abandoned?.close()
        }
    }

    /**
     * Issue #376 (spec D5): the status-card (hub) recovery entry. Unlike
     * [beginRecoveryPreview] it needs no process-local apply context: the
     * application module selects the latest restorable point (D1) and the
     * existing #84 inspection stays the authoritative gate. Admission also
     * requires the durable row's own visibility condition (display state
     * `Idle`/`Cancelled`) and fails silently — the lease is closed and the
     * state is untouched. The entry origin records the pre-entry display
     * state so every cancel/back/dismiss of this flow returns to the hub
     * side; it never restores `lastVerifiedApply`. Because a non-null
     * `lastVerifiedApply` structurally excludes `Idle`/`Cancelled`, the
     * published preview never carries a correlated apply-history summary.
     *
     * Returns `true` when the confirmation face went live
     * (`State.RecoveryPreview` published); `false` on any silent rejection
     * (lease busy, wrong display state, fail-closed selection read) — the
     * hosting surface navigates only on `true` so a rejected tap never opens
     * an empty run face.
     */
    fun beginRecoveryPreviewFromDurableEntry(): Boolean {
        val lease = operationGate.tryAcquire(OrganizationOperationLease.Kind.RECOVERY) ?: return false
        val admitted = synchronized(lock) {
            val current = stateHolder.value
            if (activeOperation != null || recoveryLease != null ||
                !(current is State.Idle || current is State.Cancelled)
            ) {
                null
            } else {
                recoveryEntryOrigin = RecoveryEntryOrigin.HubStatusCard
                recoveryEntryReturnState = current
                recoveryLease = lease
                stateHolder.value = State.InspectingRecovery
                updateOperationActiveLocked()
                current
            }
        }
        if (admitted == null) {
            lease.close()
            return false
        }
        // Admission re-reads the selection (spec D5): a fail-closed null
        // rejects silently and restores the pre-entry display state.
        val entry = try {
            application.readRestorableRecoveryEntry()
        } catch (failure: Throwable) {
            cancelRecoveryPreview()
            throw failure
        }
        if (entry == null) {
            cancelRecoveryPreview()
            return false
        }
        val preview = try {
            application.inspectRecovery(entry.pointId)
        } catch (failure: Throwable) {
            cancelRecoveryPreview()
            throw failure
        }
        val updated = synchronized(lock) {
            if (state !is State.InspectingRecovery || recoveryEntryOrigin != RecoveryEntryOrigin.HubStatusCard) {
                false
            } else {
                pendingRecovery = preview as? RecoveryPreviewResult.Restorable
                // spec 230 D2 correlation gate, reused unchanged: with no
                // retained verified apply this always renders without history.
                stateHolder.value = State.RecoveryPreview(preview, appliedSummary = null)
                true
            }
        }
        if (!updated) {
            val abandoned = synchronized(lock) {
                recoveryEntryOrigin = null
                recoveryEntryReturnState = null
                recoveryLease.also { recoveryLease = null }
                    .also { updateOperationActiveLocked() }
            }
            abandoned?.close()
        }
        return updated
    }

    /**
     * Issue #376 (spec D5): the explicit hub return from a hub-origin recovery
     * result. Only the result face's system-Back path calls this; generic
     * dismissals (host dispose, diagnostics push) keep the terminal state so
     * the result/safe-support surface survives a child-destination round
     * trip. Restores the pre-entry display state (`Idle`/`Cancelled`) and
     * reports whether this call resolved the flow.
     */
    fun leaveRecoveryResultToHub(): Boolean {
        val restored = synchronized(lock) {
            if (state !is State.RecoveryResultState || recoveryEntryOrigin != RecoveryEntryOrigin.HubStatusCard) {
                false
            } else {
                stateHolder.value = recoveryEntryReturnState ?: State.Idle
                recoveryEntryOrigin = null
                recoveryEntryReturnState = null
                true
            }
        }
        return restored
    }

    fun cancelRecoveryPreview() {
        val lease = synchronized(lock) {
            pendingRecovery = null
            stateHolder.value = recoveryCancelTargetLocked()
            recoveryEntryOrigin = null
            recoveryEntryReturnState = null
            recoveryLease.also { recoveryLease = null }
                .also { updateOperationActiveLocked() }
        }
        lease?.close()
    }

    /**
     * Issue #376 (spec D5): the cancel return target is bound to the entry
     * origin. The legacy Applied-surface entry keeps restoring the retained
     * verified apply; the status-card entry returns to its pre-entry display
     * state and never lands on a stale `State.Applied` face.
     */
    private fun recoveryCancelTargetLocked(): State = when (recoveryEntryOrigin) {
        RecoveryEntryOrigin.HubStatusCard -> recoveryEntryReturnState ?: State.Idle
        null, RecoveryEntryOrigin.AppliedSurface -> lastVerifiedApply ?: State.Idle
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
                .also { updateOperationActiveLocked() }
        }
        lease?.close()
    }

    /**
     * Issue #271: read-only projection of the durable recovery-store state for
     * the re-opened Settings surface. No run state is mutated and no lock is
     * involved; the projection itself is owned by the application module.
     */
    fun readDurableOrganizerStatus(): OrganizerDurableStatus = application.readDurableOrganizerStatus()

    /**
     * Issue #376 (D-15): read-only restore-entry hint (latest restorable
     * point + coarse remaining window) for the hub status card. Same
     * fail-closed, no-write contract as [readDurableOrganizerStatus]; callers
     * must not run the two reads concurrently (spec D6 read serialization).
     */
    fun readRestorableRecoveryEntry(): RestorableRecoveryEntry? = application.readRestorableRecoveryEntry()

    /** Observable startup readiness of the application module (see the façade). */
    val readinessState: StateFlow<ReadinessGate.State>
        get() = application.readinessState

    fun dismiss(): DismissalOutcome {
        val recovery = synchronized(lock) {
            if (activeOperation == null && recoveryLease != null) {
                pendingRecovery = null
                stateHolder.value = recoveryCancelTargetLocked()
                recoveryEntryOrigin = null
                recoveryEntryReturnState = null
                recoveryLease.also { recoveryLease = null }
                    .also { updateOperationActiveLocked() }
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
            updateOperationActiveLocked()
            DismissalOutcome.CancelledAndMayNavigate to operation
        }
        operation.second?.lease?.close()
        operation.second?.let {
            // Issue #371: same owner-destruction rules as cancel() — release
            // an un-presented reservation, resolve a presented request as an
            // abandon resolution so waiters are never orphaned.
            destroyUsageAccessGateOwnership(it.runId)
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
                updateOperationActiveLocked()
                true
            }
        }
        if (stale) {
            operation.lease.close()
            if (emitRejection) emitStaleRejection(operation)
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
                updateOperationActiveLocked()
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
                updateOperationActiveLocked()
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
        /**
         * Issue #205: the accepted intent this run was started from, if any.
         * Issue #331: an intent may also attach to a run already holding the
         * selection surface (the run-in exchange entry); the attach action is
         * single-shot (see [attachIntent]).
         */
        var intent: app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent? = null,
        /**
         * Issue #331: the detection-time candidate cut this run surfaced, kept
         * so a scope binding rejection can restore the selection surface.
         */
        var detectedCandidates: List<DetectedCandidate>? = null,
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

        /**
         * Issue #417 (spec 417): process-local id distinguishing this
         * operation's authority records ([GenerationEpoch.operationId]).
         */
        val operationId: String = java.util.UUID.randomUUID().toString()

        /**
         * Issue #417 (spec 417, AC-6): the exact exportId this run's frozen
         * scope generated and is still bound to — the direct attach authority.
         * Set ONLY inside the gate-held callback of [commitGeneratedSession]
         * (this operation's run lock is held by the binding thread), cleared
         * by [cleanupBoundExport] / [discardScopeBoundRequest]. Process-local,
         * never persisted.
         */
        var boundExportId: String? = null

        /**
         * Issue #417 (spec 417, AC-6): the current generation epoch — claimed
         * under the run lock at generation start/replacement, invalidated by
         * interruption, run end and the scope-bound request discard. Null
         * while no generation is claimed.
         */
        var generationEpoch: GenerationEpoch? = null
    }
}
