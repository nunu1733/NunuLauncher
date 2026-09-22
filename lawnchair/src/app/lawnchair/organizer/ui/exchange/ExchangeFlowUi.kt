package app.lawnchair.organizer.ui.exchange

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.integration.UsageAccess
import app.lawnchair.organizer.integration.exchange.ClipboardImportRead
import app.lawnchair.organizer.integration.exchange.ClipboardImportTransport
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.integration.exchange.ExchangeTransportFailure
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeRead
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.DiscardIfResult
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import app.lawnchair.organizer.personalization.durablePendingIntentFrom
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.ExchangeImportSummary
import app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate
import app.lawnchair.organizer.personalization.exchange.ImportNormalizationFailure
import app.lawnchair.organizer.personalization.exchange.PendingIntentReconcile
import app.lawnchair.organizer.personalization.exchange.RebindIntentRebuilder
import app.lawnchair.organizer.personalization.exchange.RecognizedImportFraming
import app.lawnchair.organizer.personalization.exchange.RecognizedImportInfo
import app.lawnchair.organizer.personalization.exchange.acceptsExchangeImportEnvelope
import app.lawnchair.organizer.personalization.exchange.durableImportSummary
import app.lawnchair.organizer.personalization.exchange.exchangeImportSummary
import app.lawnchair.organizer.personalization.exchange.reconcilePendingIntent
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.UsageAccessJitGate
import app.lawnchair.organizer.ui.UsageAccessJitGateProvider
import app.lawnchair.organizer.ui.UsageAccessJitRequestDialog
import app.lawnchair.organizer.ui.awaitUsageAccessGrant
import app.lawnchair.organizer.ui.openUsageAccessSettings
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Issue #205: the External Agent Exchange surface as LazyColumn items (spec
 * 205 behavior scenarios). The flow is user-driven end to end: privacy mode →
 * (explicit replacement confirmation when a live session exists) → generation
 * → pre-send disclosure of the generated immutable package → explicit send via
 * clipboard/share/file, or import of the agent's marked reply → validated
 * intent starts a fresh run through the normal preview/confirm path. Nothing
 * here writes to the layout DB.
 */
sealed interface ExchangeScreen {
    data object Closed : ExchangeScreen

    /**
     * T-15 (issue #372): the request-creation face. Besides the replacement
     * gate snapshot it carries the ACTIVE REQUEST PRE-DISPLAY projection from
     * the last read — existence plus the session's `expiresAtEpochMs` and the
     * read instant (the display root), never the session itself. Display
     * only: the effective gate re-reads the store at generation time
     * (spec 205 AC-13 unchanged).
     */
    data class SelectingPrivacy(
        val replacementConfirmationRequired: Boolean,
        val activeRequestExpiresAtEpochMs: Long? = null,
        val activeRequestReadAtEpochMs: Long? = null,
    ) : ExchangeScreen

    /** Explicit pre-generation confirmation (spec 205 AC-13). */
    data class ReplacementConfirm(
        val tier: PrivacyTier,
        /** Issue #331: the run-in scoped selection the generation continues with. */
        val scoped: Pair<List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>, Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>>? = null,
    ) : ExchangeScreen

    data object Generating : ExchangeScreen

    data class Disclosing(val state: ExchangeDisclosureState) : ExchangeScreen

    data class Importing(val replyText: String) : ExchangeScreen

    /**
     * Issue #332 (spec AC-7 retention boundary): the failure surface carries
     * the imported text for its collapsed raw detail. The text lives in this
     * ONE field only while the surface is shown — `openImport()`, `close()`
     * and any navigation replace the screen state and thereby discard it. It
     * is never written to diagnostics, logs, or storage.
     */
    data class ImportOutcomeScreen(
        val outcome: ExchangeImportOutcome,
        val rawText: String = "",
    ) : ExchangeScreen

    /**
     * Issue #328 (spec 328 "取り込み成功状態"): the post-validation success
     * state shown before any run connection. The validated intent itself
     * stays in the holder's process-local pending slot; this state carries
     * only what the surface renders and the settle anchors:
     *
     * - [attemptToken] is the import attempt's process-local generation
     *   (assigned when the import started, inherited by this state) — both
     *   the validation settle and the CTA settle are bound to it;
     * - [continuing] flips synchronously on the CTA press and blocks the
     *   CTA/discard affordances (and system Back) until the run-connection
     *   seam settles.
     */
    data class ImportSuccess(
        val summary: ExchangeImportSummary,
        val entryKind: ExchangeImportEntryKind,
        val attemptToken: Long,
        val continuing: Boolean = false,
    ) : ExchangeScreen

    /**
     * Issue #374 (spec 374 DI-AC-01 "ImportReview再開面", cold process到達範囲):
     * the resume face of an imported proposal (T-18's review form), reached
     * from the hub status card through
     * [ExchangeFlowStateHolder.openPendingImportReview]. Carries the
     * privacy-safe summary reconstructed from the durable record plus the
     * session ([durableImportSummary] — the same pure derivation and inputs
     * as the success face), the remaining-time display root (the SESSION's
     * expiry and the read instant — the session stays the display master),
     * and the persisted entry kind. It holds NO attempt anchor (the durable
     * record is the anchor) and offers NO continuation CTA — #375 owns the
     * rebind; the only actions are the D-13 discard and the zero-write close.
     */
    data class ImportReview(
        val summary: ExchangeImportSummary,
        val expiresAtEpochMs: Long,
        val readAtEpochMs: Long,
        val entryKind: ExchangeImportEntryKind,
        /**
         * Issue #375 (spec SR-AC-07): the rebind CTA single-flight flag —
         * flipped synchronously on the press; while true, the discard/Back
         * entry points are refused (spec 328 AC-3 discipline).
         */
        val continuing: Boolean = false,
    ) : ExchangeScreen

    /**
     * Issue #374 (spec 374 DI-AC-13): the typed failure of the durable
     * persistence step. It is NOT one of the 20 import validation classes —
     * validation already passed; only the durable save of the record failed.
     * The pending intent and its attempt stay anchored in the holder, so the
     * face's retry re-runs ONLY the store save (never validation); closing
     * interrupts without saving anything (the record is absent, so the status
     * card can never show this proposal — guaranteed by not saving).
     */
    data class ImportPersistenceFailure(
        val attemptToken: Long,
        val entryKind: ExchangeImportEntryKind,
        /** True while a retry save is in flight (the retry is single-flight). */
        val retrying: Boolean = false,
    ) : ExchangeScreen

    /**
     * Issue #371 (spec 371): the JIT Usage Access request pause for one
     * generation attempt. The attempt's [attemptToken] anchors the pending
     * generation: resume applies only when the current screen still carries
     * the same token, exactly once, so a close + regenerate sequence can
     * never be resumed by a stale callback. Owner destruction (close,
     * another transition, host navigation teardown) follows the gate's
     * owner-destruction rules — release while un-presented, abandon-resolve
     * once presented — so the process-wide barrier is never orphaned.
     */
    data class AwaitingUsageAccessJit(
        val attemptToken: Long,
        val tier: PrivacyTier,
        /** The run-in scoped selection the generation continues with. */
        val scoped: Pair<List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>, Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>>? = null,
        /** Whether this surface owns the presentation (gate decision at entry). */
        val isPresenter: Boolean,
    ) : ExchangeScreen
}

/** Issue #328: which exchange entry produced the import attempt. */
enum class ExchangeImportEntryKind { IDLE, RUN_IN }

/**
 * Issue #205 (PR review P1): the disclosure lifecycle of one generated package.
 * The generating session is bound here, so cancel always targets exactly this
 * disclosure's session — never whatever happens to be active. Once any
 * transport succeeded the disclosure is `sent` and closing it must NOT
 * invalidate the session (the package may already be outside the device, and
 * Share Sheet delivery is unobservable), so the reply stays importable.
 */
data class ExchangeDisclosureState(
    val session: app.lawnchair.organizer.personalization.ExportSession,
    val packageText: String,
    val tier: PrivacyTier,
    val sent: Boolean = false,
    /** A transport is running; pre-send cancel is suspended until it settles. */
    val transportInFlight: Boolean = false,
    /**
     * Terminal: a cancel was accepted on Main; no transport may start or
     * settle against this disclosure afterwards (review round 3 P1).
     */
    val cancelling: Boolean = false,
) {
    fun onTransportStarted(): ExchangeDisclosureState {
        if (sent || cancelling) return this // a cancelled/sent disclosure takes no transport
        return copy(transportInFlight = true)
    }

    fun onTransportResult(result: ExchangeTransportResult): ExchangeDisclosureState {
        if (cancelling) return this // late results of a pre-cancel transport are ignored
        return when (result) {
            ExchangeTransportResult.Success -> copy(sent = true, transportInFlight = false)
            is ExchangeTransportResult.Failure -> copy(transportInFlight = false)
            ExchangeTransportResult.InFlight -> copy(transportInFlight = true)
        }
    }

    /**
     * Spec 205 (review round 2 P1): cancel is the pre-send act of an unsent
     * package only, and never while a transport is in flight — the write may
     * still land outside the device after the invalidate.
     */
    val cancelable: Boolean get() = !sent && !transportInFlight && !cancelling

    /** Transports may only start on an idle, unsent, non-cancelling disclosure. */
    val transportAllowed: Boolean get() = cancelable
}

/**
 * Observable holder for the exchange sub-flow hosted by the preferences screen.
 * The controller factory is deferred until the flow is actually opened: the
 * production wiring touches `LauncherAppState` and the startup reconciliation
 * trigger, which hosted screens (and their instrumentation tests, which inject
 * an isolated run) must not pay for merely rendering the entry row.
 */
class ExchangeFlowStateHolder(
    controllerFactory: () -> ExchangeFlowController,
    private val run: ManualOrganizationRun,
    private val scope: CoroutineScope,
    /** Where transport results hop back to the UI (Main in production). */
    private val settleDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * Where display-state updates (generation results, import outcomes) hop
     * back (Main in production). Injectable for the same reason as
     * [settleDispatcher]: JVM holder tests assert the terminal display states
     * (issue #332 review R3) without an Android Main looper.
     */
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * Test-only seam (issue #205 ABA regression): invoked once per settle
     * attempt with the owning disclosure and whether it was applied, after the
     * settle decision completed. Production passes the no-op default.
     */
    internal var onSettleObserved: ((ExchangeDisclosureState, Boolean) -> Unit)? = null,
    /**
     * Issue #371: the process-wide JIT Usage Access request gate. Defaults to
     * an always-resolved gate so existing holder tests keep today's behavior;
     * production passes the shared singleton (UsageAccessJitGateProvider) so
     * run and exchange draw on one process-scoped request opportunity. Public
     * read access: the hosting surface collects the gate snapshot for waiter
     * wakeup and drives the dialog from it.
     */
    val usageAccessGate: UsageAccessJitGate = UsageAccessJitGate(isGranted = { true }),
    /**
     * Issue #374 (spec 374 "store契約"): the durable pending imported intent
     * store. Production injects the real store (`PendingImportedIntentModule`);
     * the benign default keeps hosts/fixtures that predate the durable contract
     * at today's behavior (a save/discard that "succeeds" without persisting,
     * so the success/discard lifecycles are unchanged where no durable
     * contract is under test).
     */
    private val pendingImportStore: PendingImportedIntentStore = NoopPendingImportedIntentStore,
    /**
     * Issue #375 (spec "exchange mutation gate"): the process-wide
     * serialization point shared by every durable-record / active-session
     * mutation and by the rebind admission anchor. The default (a fresh
     * instance) keeps existing holder fixtures self-contained; production
     * injects the single process-wide instance so holder and controller draw
     * on the same gate.
     */
    val exchangeMutationGate: ExchangeMutationGate = ExchangeMutationGate(),
) {
    private val controllerLazy = lazy(LazyThreadSafetyMode.NONE) { controllerFactory() }
    private val controller: ExchangeFlowController get() = controllerLazy.value

    /**
     * Issue #372 (AC-13 structural gate read): the ACTIVE REQUEST gate reads
     * the store fresh — but only through an ALREADY-initialized controller.
     * In production `requestGeneration` is only reachable from the open T-15
     * face, and `openFlow()` has initialized the controller by then, so the
     * fresh read always happens. An uninitialized holder means no request
     * flow was ever opened in this holder, so there is nothing to confirm
     * and touching the factory (whose fixtures may be hostile, issue #371)
     * is not part of the gate's contract.
     */
    private fun activeSessionForGate(): ExportSession? = if (controllerLazy.isInitialized()) {
        controller.activeSession()
    } else {
        null
    }

    /** Backing state; exposed as Compose state through [screenState] for tests. */
    private val screenState = mutableStateOf<ExchangeScreen>(ExchangeScreen.Closed)

    var screen: ExchangeScreen
        get() = screenState.value
        private set(value) {
            screenState.value = value
        }

    private val statusState = mutableStateOf<ExchangeStatus?>(null)

    var status: ExchangeStatus?
        get() = statusState.value
        private set(value) {
            statusState.value = value
        }

    /** The one expiry-scheduled T-15 re-read (issue #372); replaced on every read. */
    private var expiryReReadJob: Job? = null

    fun openFlow() {
        abandonAwaitingUsageAccessJit()
        status = null
        screen = readActiveRequestIntoSelecting()
    }

    /**
     * Issue #372 (T-15): re-read the active request and refresh the pre-display
     * projection (existence, remaining time, replacement-confirmation need)
     * from the same `activeSession()` seam the generation gate uses. Fired on
     * the face's lifecycle resume and by the expiry-scheduled re-read; never
     * mutates any other face.
     */
    fun refreshActiveRequest() {
        if (screen !is ExchangeScreen.SelectingPrivacy) return
        screen = readActiveRequestIntoSelecting()
    }

    private fun readActiveRequestIntoSelecting(): ExchangeScreen.SelectingPrivacy {
        val readAt = controller.nowEpochMs()
        val active = controller.activeSession()
        scheduleExpiryReRead(active?.expiresAtEpochMs)
        return ExchangeScreen.SelectingPrivacy(
            replacementConfirmationRequired = active != null,
            activeRequestExpiresAtEpochMs = active?.expiresAtEpochMs,
            activeRequestReadAtEpochMs = if (active != null) readAt else null,
        )
    }

    /**
     * Issue #372 (review round 2): ONE re-read scheduled at the displayed
     * session's expiry, so a T-15 face kept in the foreground crosses the TTL
     * with no lifecycle event and its display still matches the store
     * (`active()` reads expired sessions as absent). No continuous ticking;
     * the pending job is cancelled and replaced on every read, and it dies
     * with the holder's scope on screen exit (the next entry re-reads).
     */
    private fun scheduleExpiryReRead(expiresAtEpochMs: Long?) {
        expiryReReadJob?.cancel()
        expiryReReadJob = if (expiresAtEpochMs == null) {
            null
        } else {
            scope.launch {
                delay((expiresAtEpochMs - controller.nowEpochMs()).coerceAtLeast(0L))
                withContext(uiDispatcher) { refreshActiveRequest() }
            }
        }
    }

    fun openImport() {
        abandonAwaitingUsageAccessJit()
        requestAttemptInvalidation {
            status = null
            screen = ExchangeScreen.Importing("")
        }
    }

    fun close() {
        expiryReReadJob?.cancel()
        abandonAwaitingUsageAccessJit()
        requestAttemptInvalidation {
            status = null
            screen = ExchangeScreen.Closed
        }
    }

    /**
     * Issue #371: owner-destruction rules for the JIT pause, applied whenever
     * the awaiting screen leaves the machine (close, a newer transition, host
     * teardown). The gate's atomic [UsageAccessJitGate.abandon] completes the
     * state-specific action under one monitor — release while un-presented,
     * abandon-resolve once presented — so a racing presentation can never
     * orphan the barrier; the abandoned attempt's generation is never resumed.
     */
    private fun abandonAwaitingUsageAccessJit() {
        val awaiting = screenState.value as? ExchangeScreen.AwaitingUsageAccessJit ?: return
        usageAccessGate.abandon(ExchangeJitAttemptOwner(awaiting.attemptToken))
    }

    /**
     * Issue #371 (review round 3): host-teardown hook. The hosting surface
     * must call this from its `DisposableEffect` onDispose — the holder is
     * `remember`ed, so a route change or activity recreation discards it
     * without any other lifecycle signal, and a live JIT pause would
     * otherwise strand the process-wide gate (a `Reserved` reservation never
     * re-acquirable, a `Presented` barrier never resolved).
     */
    fun dispose() {
        abandonAwaitingUsageAccessJit()
        // Invalidate the pending attempt itself: a stale resume callback must
        // find no awaiting screen to match its token against. Only when the
        // pause is still on screen — the normal-resolution unmount (screen
        // already `Generating`/past) must not be clobbered.
        if (screen is ExchangeScreen.AwaitingUsageAccessJit) {
            screen = ExchangeScreen.Closed
        }
    }

    /**
     * Issue #371 (review round 4): attempt-bound teardown for the JIT dialog
     * host's unmount. Acts only when the CURRENT screen is still the SAME
     * attempt's awaiting state — a stale host unmounting after the pending
     * generation moved to a newer attempt must never abandon the new one
     * (JIT-AC-05 identity binding).
     */
    fun disposeUsageAccessJitAttempt(attemptToken: Long) {
        val awaiting = screenState.value as? ExchangeScreen.AwaitingUsageAccessJit ?: return
        if (awaiting.attemptToken != attemptToken) return
        abandonAwaitingUsageAccessJit()
        if (screen is ExchangeScreen.AwaitingUsageAccessJit) {
            screen = ExchangeScreen.Closed
        }
    }

    /**
     * Issue #331: when [scoped] is set (the run-in entry), generation composes
     * the export from the frozen selection via the scope-composed canonical
     * seam instead of the idle full-organization composition.
     *
     * Issue #372 (implementation review, AC-13 structural gate): the gate is
     * decided from a FRESH `activeSession()` read here — never from the face's
     * snapshot. The snapshot is display-only: a failure settle (or any other
     * store change) after it was captured must not let an unconfirmed
     * generation start replace an active session.
     */
    fun requestGeneration(
        tier: PrivacyTier,
        scoped: Pair<List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>, Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>>? = null,
    ) {
        // Issue #371: starting a new request abandons any prior JIT pause.
        abandonAwaitingUsageAccessJit()
        if (activeSessionForGate() != null) {
            screen = ExchangeScreen.ReplacementConfirm(tier, scoped)
            return
        }
        if (scoped != null) {
            generateScoped(tier, scoped.first, scoped.second)
        } else {
            generate(tier)
        }
    }

    /** The user confirmed discarding the existing exchange (spec 205 AC-13). */
    fun confirmReplacementAndGenerate(
        tier: PrivacyTier,
        scoped: Pair<List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>, Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>>? = null,
    ) {
        abandonAwaitingUsageAccessJit()
        if (scoped != null) generateScoped(tier, scoped.first, scoped.second) else generate(tier)
    }

    /** The user declined; the existing session stays untouched and importable. */
    fun declineReplacement() {
        close()
    }

    fun generate(tier: PrivacyTier) {
        val attemptToken = nextJitAttemptToken()
        when (val decision = usageAccessGate.evaluate(ExchangeJitAttemptOwner(attemptToken))) {
            UsageAccessJitGate.Decision.Proceed -> startGeneration(tier, scoped = null)

            else -> screen = ExchangeScreen.AwaitingUsageAccessJit(
                attemptToken = attemptToken,
                tier = tier,
                scoped = null,
                isPresenter = decision == UsageAccessJitGate.Decision.Present,
            )
        }
    }

    /**
     * Issue #371: resolves the JIT request for [attemptToken] and continues
     * the pending generation. Applies only when the current screen is still
     * the same attempt's awaiting state (a close + regenerate sequence mints
     * a new token, so a stale resume is dropped), and exactly once — after
     * the resume the screen has moved on and a late callback finds nothing to
     * continue.
     */
    fun continueUsageAccessJit(attemptToken: Long) {
        val awaiting = screenState.value as? ExchangeScreen.AwaitingUsageAccessJit ?: return
        if (awaiting.attemptToken != attemptToken) return
        usageAccessGate.resolve(ExchangeJitAttemptOwner(attemptToken))
        if (awaiting.scoped != null) {
            startGeneration(awaiting.tier, scoped = awaiting.scoped)
        } else {
            startGeneration(awaiting.tier, scoped = null)
        }
    }

    /**
     * Issue #371: re-checks whether this awaiting surface can (still) present
     * the request dialog — used when the gate state observed by the host
     * changes (e.g. the previous owner released before presenting and this
     * waiter may now acquire the presentation right). Returns whether this
     * attempt currently holds the reservation.
     */
    fun tryAcquireJitPresentation(attemptToken: Long): Boolean {
        val awaiting = screenState.value as? ExchangeScreen.AwaitingUsageAccessJit ?: return false
        if (awaiting.attemptToken != attemptToken) return false
        return when (usageAccessGate.ownedPhase(ExchangeJitAttemptOwner(attemptToken))) {
            UsageAccessJitGate.Phase.Reserved -> true

            UsageAccessJitGate.Phase.Available -> {
                val decision = usageAccessGate.evaluate(ExchangeJitAttemptOwner(attemptToken))
                decision == UsageAccessJitGate.Decision.Present
            }

            else -> false
        }
    }

    private fun startGeneration(tier: PrivacyTier, scoped: Pair<List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>, Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>>?) {
        screen = ExchangeScreen.Generating
        scope.launch(Dispatchers.IO) {
            val result =
                if (scoped != null) {
                    controller.generateForSelection(tier, scoped.first, scoped.second)
                } else {
                    controller.generate(tier)
                }
            withContext(uiDispatcher) { handleGeneration(result, tier) }
        }
    }

    /**
     * Issue #331: run-in (scope-composed) generation from the selection
     * surface. The export scope is the frozen selection composed by the same
     * canonical seam the planner consumes; the ordering contract (gate →
     * build → save → compose → disclose) is the controller's.
     */
    fun generateScoped(
        tier: PrivacyTier,
        selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        candidateLabels: Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>,
    ) {
        val scoped = selection to candidateLabels
        val attemptToken = nextJitAttemptToken()
        when (val decision = usageAccessGate.evaluate(ExchangeJitAttemptOwner(attemptToken))) {
            UsageAccessJitGate.Decision.Proceed -> startGeneration(tier, scoped = scoped)

            else -> screen = ExchangeScreen.AwaitingUsageAccessJit(
                attemptToken = attemptToken,
                tier = tier,
                scoped = scoped,
                isPresenter = decision == UsageAccessJitGate.Decision.Present,
            )
        }
    }

    private fun handleGeneration(result: ExchangeGenerationResult, tier: PrivacyTier) {
        when (result) {
            is ExchangeGenerationResult.Generated -> {
                // Issue #374 (spec 374 DI-AC-03): a saved new session means the
                // user approved the replacement — the imported proposal is
                // discarded in ALL of its three holding places. The durable
                // record was already deleted by the controller right after the
                // new session's save (write order 「新session保存 → 旧pending
                // 無効化」); here the in-process places follow: the pending slot
                // and the showing success/persistence-failure state. This is
                // replacement, not a user discard — no IMPORT_DISCARDED status.
                invalidateImportedIntentForReplacement()
                screen = ExchangeScreen.Disclosing(
                    ExchangeDisclosureState(
                        session = result.session,
                        packageText = result.packageText,
                        tier = tier,
                    ),
                )
            }

            is ExchangeGenerationResult.InputNotReady -> {
                status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY)
                // Issue #372 (implementation review, AC-13): failure settles
                // re-read the store truth — E1 may still be active here (the
                // failed attempt replaced nothing), so the next start must see
                // the confirmation requirement again.
                screen = readActiveRequestIntoSelecting()
            }

            ExchangeGenerationResult.SessionStoreFailure -> {
                status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_STORE_FAILURE)
                screen = readActiveRequestIntoSelecting()
            }

            is ExchangeGenerationResult.EncodeFailure -> {
                // Issue #374 (review finding 4, DI-AC-03): the controller
                // commits the replacement BEFORE the encode runs — the new
                // session is durably saved and the old proposal's durable
                // record is already deleted. An encode failure can therefore
                // only occur AFTER the replacement commit, so the in-process
                // half of the invalidation must follow it exactly like the
                // Generated settle: the pending slot and any showing
                // success/persistence face are discarded here (the durable
                // side is gone; nothing may keep referencing it). The typed
                // oversize guidance still surfaces, and the failed attempt
                // replaced nothing session-wise (the store was re-read below).
                invalidateImportedIntentForReplacement()
                status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_OVERSIZE)
                screen = readActiveRequestIntoSelecting()
            }
        }
    }

    /**
     * Issue #374 (spec 374 DI-AC-03): the in-process half of the replacement
     * invalidation — clears the pending slot and any showing import success /
     * persistence-failure state when a new request's generation got PAST the
     * replacement commit (the new session's durable save, which is also the
     * point where the controller deletes the old durable record). Applied at
     * BOTH post-commit terminals: the Generated settle and the EncodeFailure
     * settle (review finding 4 — the encode runs after the commit, so its
     * failure cannot un-commit the replacement). No-op when no imported
     * proposal exists in-process (the controller-side durable delete is
     * unconditional and cheap on an absent record).
     */
    private fun invalidateImportedIntentForReplacement() {
        if (pendingValidated == null &&
            screen !is ExchangeScreen.ImportSuccess &&
            screen !is ExchangeScreen.ImportPersistenceFailure
        ) {
            return
        }
        requestAttemptInvalidation {
            status = null
            screen = ExchangeScreen.Closed
        }
    }

    /**
     * Closes the disclosure. Pre-send this is the spec 205 cancel: it
     * invalidates exactly the disclosure's own (unsent) session. After a
     * successful transport the package may already have left the device, so
     * the session survives and the reply stays importable (review P1).
     *
     * Issue #372 (implementation review): a BUSY unsent disclosure — transport
     * in flight, or a cancel already accepted and its invalidate still in
     * flight — refuses to close. Closing it would let the user leave (and
     * dispose the holder's composition-owned scope) before the settle, which
     * is exactly the race the Back contract's BLOCKED action guards; the
     * visible action must not re-open it.
     */
    fun closeDisclosure() {
        // Decide and mark synchronously on Main from the holder's CURRENT
        // disclosure state (review round 3 P1): the moment a cancel is
        // accepted the disclosure enters the terminal `cancelling` state, so
        // no further transport can start or settle against it, and the
        // session invalidated below is exactly the never-sent one.
        val disclosing = (screen as? ExchangeScreen.Disclosing)?.state
        if (disclosing == null) {
            close()
            return
        }
        when {
            disclosing.cancelable -> {
                screen = ExchangeScreen.Disclosing(disclosing.copy(cancelling = true))
                scope.launch(Dispatchers.IO) {
                    controller.cancelDisclosure(disclosing.session)
                    withContext(uiDispatcher) { close() }
                }
            }

            disclosing.sent -> close()

            // In flight or cancelling: refuse — wait for the settle.
            else -> Unit
        }
    }

    fun onTransportResult(result: ExchangeTransportResult) {
        settleTransport(result)
    }

    /**
     * Settles a transport result against the disclosure it belongs to (review
     * round 5 P1): results are only ever applied while the screen still shows
     * the SAME disclosure instance the transport started from. A late result
     * arriving after the screen closed — or after a newer disclosure was
     * generated — is dropped, so an old write can neither flip a newer
     * disclosure's in-flight flag nor mark it sent.
     */
    private fun settleTransport(result: ExchangeTransportResult) {
        when (result) {
            ExchangeTransportResult.InFlight -> Unit

            ExchangeTransportResult.Success -> status = ExchangeStatus(ExchangeStatus.Kind.TRANSPORT_SUCCESS)

            is ExchangeTransportResult.Failure ->
                status = ExchangeStatus(ExchangeStatus.transportFailure(result.kind))
        }
        val disclosing = screen as? ExchangeScreen.Disclosing ?: return
        screen = ExchangeScreen.Disclosing(disclosing.state.onTransportResult(result))
    }

    /**
     * Serializes every transport start through the disclosure state on Main.
     * The single begin gate for every transport (review round 4 P1): atomically
     * checks the CURRENT disclosure's `transportAllowed` and flips it to
     * in-flight. Returns the disclosure state the transport is bound to, or
     * null when the start was refused (no disclosure, already in flight / sent
     * / cancelling) — a refused transport never runs and never settles.
     */
    private fun beginTransport(): ExchangeDisclosureState? {
        val disclosing = (screenState.value as? ExchangeScreen.Disclosing)?.state ?: return null
        if (!disclosing.transportAllowed) return null
        val inFlight = disclosing.onTransportResult(ExchangeTransportResult.InFlight)
        if (!inFlight.transportInFlight) return null
        screen = ExchangeScreen.Disclosing(inFlight)
        return inFlight
    }

    /**
     * A settle is valid only while the screen still shows a disclosure of the
     * SAME generation — the exportId is immutable within one disclosure and
     * unique per generation, while the state object itself is copied on every
     * transition (identity anchor: review round 5 P1).
     */
    private fun settleBelongsTo(disclosure: ExchangeDisclosureState): Boolean = (screenState.value as? ExchangeScreen.Disclosing)?.state?.session?.exportId == disclosure.session.exportId

    fun startTransport(transport: () -> ExchangeTransportResult) {
        val disclosure = beginTransport() ?: return
        if (!settleBelongsTo(disclosure)) {
            onSettleObserved?.invoke(disclosure, false)
            return
        }
        onTransportResult(transport())
    }

    fun importFromFile(context: Context, fileTransport: FileExchangeTransport, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val read = fileTransport.read(uri)
            // The read result hops back through the same settle dispatcher as
            // every other transport result (writeFile), keeping one convention
            // for "where results return to the UI".
            withContext(settleDispatcher) {
                onFileRead(read)
            }
        }
    }

    /**
     * Issue #332: the typed SAF-callback receipt branch, internal so holder
     * tests exercise the exact file-source path without a framework [Uri]
     * (the JVM unit-test classpath cannot construct one).
     */
    internal fun onFileRead(read: FileExchangeRead) {
        when (read) {
            // Issue #332 (spec AC-2/AC-5): the file read flows into the
            // SAME receipt helper as the clipboard — one operation from
            // the SAF pick to the common import path's parse result.
            is FileExchangeRead.Text -> receiveAndImport(read.text)

            FileExchangeRead.Oversize -> status = ExchangeStatus(ExchangeStatus.Kind.INPUT_OVERSIZE)

            FileExchangeRead.Failure -> status = ExchangeStatus(ExchangeStatus.Kind.FILE_READ_FAILED)
        }
    }

    /**
     * Issue #332 (spec D-7): the explicit one-tap clipboard read. The read
     * itself is a single synchronous `getPrimaryClip()` inside the transport —
     * no listener, no automatic read. A successful text receipt replaces the
     * manual paste content and immediately enters the common import path;
     * typed failures keep the screen and any existing input (zero-write).
     */
    fun importFromClipboard(transport: ClipboardImportTransport) {
        when (val read = transport.read()) {
            is ClipboardImportRead.Text -> receiveAndImport(read.text)

            ClipboardImportRead.EmptyOrUnavailable ->
                status = ExchangeStatus(ExchangeStatus.Kind.CLIPBOARD_EMPTY)

            ClipboardImportRead.NotText ->
                status = ExchangeStatus(ExchangeStatus.Kind.CLIPBOARD_NOT_TEXT)
        }
    }

    /**
     * Issue #332: the shared receipt helper for the clipboard and file
     * sources (AC-5: one common import path). Same envelope gate as
     * `onImportTextChange` (spec 205 Decision 6); the received text replaces
     * the editor content and the import runs without a further press.
     */
    private fun receiveAndImport(text: String) {
        if (!acceptsExchangeImportEnvelope(text)) {
            status = ExchangeStatus(ExchangeStatus.Kind.INPUT_OVERSIZE)
            return
        }
        screen = ExchangeScreen.Importing(text)
        import(text)
    }

    fun writeFile(fileTransport: FileExchangeTransport, packageText: String, uri: Uri?) {
        // The async write goes through the same begin gate as the synchronous
        // transports (review round 4 P1): a second writeFile while one is in
        // flight, or a start against a sent/cancelling disclosure, is refused
        // before any IO happens — so exactly one write can ever settle and the
        // busy flag cannot be cleared while another write is still running.
        val disclosure = beginTransport() ?: return
        scope.launch(Dispatchers.IO) {
            val result = fileTransport.write(packageText, uri)
            withContext(settleDispatcher) {
                // Settle is bound to the disclosure the write started from
                // (review round 5 P1): a result arriving after this disclosure
                // was closed or replaced never touches a newer one.
                val applied = settleBelongsTo(disclosure)
                if (applied) onTransportResult(result)
                onSettleObserved?.invoke(disclosure, applied)
            }
        }
    }

    fun onImportTextChange(text: String) {
        // Spec 205 Decision 6 / plan: the envelope limit applies at UI receipt
        // too — oversized text is never adopted into Compose state.
        if (!acceptsExchangeImportEnvelope(text)) {
            status = ExchangeStatus(ExchangeStatus.Kind.INPUT_OVERSIZE)
            return
        }
        // Issue #328 (spec 328 attempt anchor): an input edit that changes the
        // shown text invalidates the active attempt BEFORE the new text is
        // adopted, so a late settle of the replaced text can never surface as
        // a success state. The Clear affordance runs through this same path.
        val shown = (screenState.value as? ExchangeScreen.Importing)?.replyText
        if (text != shown) {
            // The supersede (new text adoption) commits only after the
            // replaced attempt's invalidation landed (spec 375: WriteFailed
            // keeps the old text/face and stays retryable).
            requestAttemptInvalidation {
                screen = ExchangeScreen.Importing(text)
            }
        } else {
            screen = ExchangeScreen.Importing(text)
        }
    }

    /**
     * Issue #328: process-local settle anchor for one import attempt —
     * assigned when the import starts (before validation runs), captured with
     * the entry kind and, for the run-in entry, the owning run id.
     */
    private data class ImportAttempt(
        val token: Long,
        val entryKind: ExchangeImportEntryKind,
        val owningRunId: RunId?,
    )

    private var nextAttemptToken = 0L

    /**
     * Issue #371: JIT request attempt tokens come from the process-wide
     * counter (UsageAccessJitGateProvider), never an instance-local one — a
     * recreated holder must never re-emit a token a disposed holder used
     * (stale-owner ABA).
     */
    private fun nextJitAttemptToken(): Long = UsageAccessJitGateProvider.nextAttemptToken()

    /**
     * Snapshot-backed so the hosting screen's freeze predicate (the idle
     * start row) recomposes the moment an attempt starts or ends, including
     * the editor-press path where the screen state itself does not change.
     */
    private val activeAttemptState = mutableStateOf<ImportAttempt?>(null)
    private var activeAttempt: ImportAttempt?
        get() = activeAttemptState.value
        set(value) {
            activeAttemptState.value = value
        }

    private var pendingValidated: ValidatedPersonalizedIntent? = null

    /**
     * Issue #374: the durable record built at the validated settle. Kept
     * anchored with the attempt so [retryPendingIntentSave] re-runs ONLY the
     * store save of the identical record (byte-stable retry). Cleared the
     * moment the save settles as ADOPTED — from then on the record belongs to
     * the proposal lifecycle (its disappearance paths are discard / expiry /
     * replacement only), and an attempt invalidation must never fence it away.
     */
    private var pendingDurableRecord: DurablePendingIntent? = null

    /**
     * Issue #328 (spec: import attempt生存中の競合freeze): true from the
     * moment an attempt is numbered until the attempt reaches its terminal
     * (failure surface shown, success state closed/discarded/replaced, or a
     * stale settle dropped). The hosting screen freezes the idle start row
     * while this is true.
     */
    val importAttemptActive: Boolean get() = activeAttempt != null

    /**
     * Issue #328 (spec: CTA処理中): true from the CTA flip until the
     * run-connection seam settles. Import starts, CTA starts and the discard
     * entry points are refused while true (the seam mutates run state before
     * it settles, so a mid-flight discard could not be withdrawn).
     */
    val importContinuationActive: Boolean
        get() = (screenState.value as? ExchangeScreen.ImportSuccess)?.continuing == true

    /**
     * Issue #375 (spec "gate上への線形化統一"): the outstanding invalidation
     * operation. While one exists, EVERY subsequent invalidating action's
     * transition is queued behind it — it commits only when the gate-held
     * conditional tombstone lands `Committed`/`NoMatch`; a `WriteFailed`
     * keeps the old face/state, surfaces the typed persistence notice, and
     * the SAME commit stays retryable. The rebind admission anchor refuses a
     * record whose invalidation operation is outstanding.
     */
    private var invalidationOperation: InvalidationOperation? = null

    private class InvalidationOperation(val expected: DurablePendingIntent) {
        val continuations = mutableListOf<() -> Unit>()

        @Volatile
        var failed: Boolean = false
    }

    private fun invalidateImportAttempt() {
        requestAttemptInvalidation(continuation = {})
    }

    /**
     * Issue #375: requests the current attempt's INVALIDATION COMMIT as ONE
     * result-carrying operation. The caller's transition ([continuation] —
     * close / supersede face / new attempt / review open) is queued behind
     * any outstanding operation and commits only when the gate-held
     * conditional tombstone lands `Committed`/`NoMatch`; a `WriteFailed`
     * preserves the old face/state, surfaces the typed persistence notice,
     * and leaves the SAME commit retryable through
     * [retryPendingInvalidation]. The gate hold is short (one AtomicFile
     * read-compare-tombstone; no suspension).
     */
    private fun requestAttemptInvalidation(continuation: () -> Unit) {
        // The in-memory attempt slots drop immediately (the attempt is dead
        // either way — its late settles are token-fenced); what is GATED is
        // the caller's transition: it runs only after the durable tombstone
        // landed.
        val record = pendingDurableRecord
        activeAttempt = null
        pendingValidated = null
        pendingDurableRecord = null

        val outstanding = invalidationOperation
        if (outstanding != null) {
            // A commit is in flight or failed-unresolved: the caller's
            // transition queues behind it — it must not run before the
            // outstanding commit lands. The queue is LAST-WINS: a later
            // invalidating action (close / edit / newer import) supersedes
            // the queued one, so a drained supersede can never be re-invalidated
            // by an action it already replaced (spec 375 queuing contract).
            synchronized(outstanding) {
                outstanding.continuations.clear()
                outstanding.continuations += continuation
            }
            if (outstanding.failed) retryPendingInvalidation()
            return
        }
        if (record == null) {
            retryPendingInvalidation()
            continuation()
            return
        }
        val operation = InvalidationOperation(record)
        synchronized(operation) { operation.continuations += continuation }
        invalidationOperation = operation
        scope.launch(Dispatchers.IO) {
            val result = exchangeMutationGate.withGate { pendingImportStore.discardIf(record) }
            withContext(uiDispatcher) { settleInvalidation(operation, result) }
        }
    }

    /** Applies one invalidation commit result: terminal on Committed/NoMatch, retryable on WriteFailed. */
    private fun settleInvalidation(operation: InvalidationOperation, result: DiscardIfResult) {
        when (result) {
            DiscardIfResult.Committed, DiscardIfResult.NoMatch -> {
                invalidationOperation = null
                val queued = synchronized(operation) {
                    val list = operation.continuations.toList()
                    operation.continuations.clear()
                    list
                }
                retryPendingInvalidation()
                queued.forEach { it() }
            }

            DiscardIfResult.WriteFailed -> {
                // The invalidation did NOT take effect: the proposal stays
                // valid, the old face/state is preserved (queued transitions
                // do not run), the typed persistence notice surfaces, and the
                // SAME commit stays retryable.
                operation.failed = true
                status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_PERSIST_FAILED)
            }
        }
    }

    /**
     * Issue #375: re-runs an outstanding invalidation commit (a previous
     * `WriteFailed`) inside the gate; terminal on `Committed`/`NoMatch` (the
     * queued transitions then run).
     */
    private fun retryPendingInvalidation() {
        val operation = invalidationOperation ?: return
        scope.launch(Dispatchers.IO) {
            val result = exchangeMutationGate.withGate { pendingImportStore.discardIf(operation.expected) }
            withContext(uiDispatcher) { settleInvalidation(operation, result) }
        }
    }

    /** True while an invalidation commit of [record] is outstanding (in flight or failed-unresolved). */
    private fun isInvalidationPending(record: DurablePendingIntent?): Boolean = record != null && invalidationOperation?.expected == record

    /**
     * Numbers a fresh import attempt from the CURRENT run state: the run-in
     * entry is the one whose owning run holds the selection surface.
     * Main-confined (called from the receipt paths and the editor action).
     * The previous attempt's invalidation is the fenced one — an uncommitted
     * record of a superseded attempt is scheduled for removal, never inherited.
     */
    private fun beginImportAttempt(onBegun: (ImportAttempt) -> Unit) {
        requestAttemptInvalidation {
            val selecting = run.state as? ManualOrganizationRun.State.Selecting
            val attempt = ImportAttempt(
                token = ++nextAttemptToken,
                entryKind = if (selecting != null) ExchangeImportEntryKind.RUN_IN else ExchangeImportEntryKind.IDLE,
                owningRunId = selecting?.runId,
            )
            activeAttempt = attempt
            onBegun(attempt)
        }
    }

    /**
     * Issue #328 (spec 328): the import runs validation only. On success the
     * flow stops at the [ExchangeScreen.ImportSuccess] state — nothing is
     * connected to a run until the user presses the continuation CTA. The
     * settle is bound to the attempt token (late settles after a cancel, a
     * newer import or an input edit are dropped).
     */
    fun import(replyText: String) {
        beginImportAttempt { attempt ->
            scope.launch(Dispatchers.IO) {
                val outcome = controller.importReply(replyText)
                withContext(uiDispatcher) { settleImport(attempt, outcome, replyText) }
            }
        }
    }

    /**
     * Issue #328: applies one validation settle, but only while the SAME
     * attempt is still current — a late `Validated` after cancel / a newer
     * import / an input edit is dropped and never (re)creates a success
     * state (spec 328 attempt anchor). Failures keep the established outcome
     * surface (spec: 既存失敗経路の無変更).
     */
    private fun settleImport(attempt: ImportAttempt, outcome: ExchangeImportOutcome, replyText: String) {
        if (activeAttempt?.token != attempt.token) return
        val pipeline = (outcome as? ExchangeImportOutcome.Pipeline)?.result
        if (pipeline is ExchangeImportResult.Validated) {
            // Run-in entries: the owning run must still hold its selection
            // surface (defense-in-depth — the hosting freeze normally makes a
            // replacement impossible). Otherwise the settle drops silently.
            if (attempt.entryKind == ExchangeImportEntryKind.RUN_IN) {
                val selecting = run.state as? ManualOrganizationRun.State.Selecting
                if (attempt.owningRunId == null || selecting?.runId != attempt.owningRunId) {
                    activeAttempt = null
                    return
                }
            }
            pendingValidated = pipeline.validated
            // Issue #374 (spec 374 DI-AC-01/DI-AC-13): the durable save is part
            // of this settle — ImportSuccess is adopted only after the record
            // is durably saved; a failed save adopts the retryable
            // ImportPersistenceFailure face instead (never a success state).
            persistPendingImport(attempt, pipeline.validated)
        } else {
            activeAttempt = null
            screen = ExchangeScreen.ImportOutcomeScreen(outcome, rawText = replyText)
        }
    }

    /**
     * Issue #374: builds the durable record of the validated proposal and
     * saves it through the attempt-fenced write below. The settle anchors to
     * the attempt token exactly like the validation settle — a late save
     * result after a cancel / a newer import / an input edit is dropped AND
     * its record is fenced out of the store.
     */
    private fun persistPendingImport(attempt: ImportAttempt, validated: ValidatedPersonalizedIntent) {
        val record = durablePendingIntentFrom(
            completed = validated.completed,
            identity = validated.identity,
            entryKind = when (attempt.entryKind) {
                ExchangeImportEntryKind.IDLE -> PendingImportEntryKind.IDLE
                ExchangeImportEntryKind.RUN_IN -> PendingImportEntryKind.RUN_IN
            },
            nowEpochMs = controller.nowEpochMs(),
            expiresAtEpochMs = validated.session.expiresAtEpochMs,
        )
        pendingDurableRecord = record
        launchDurablePendingIntentSave(attempt, record)
    }

    /**
     * Issue #374/#375: the attempt-fenced durable write, executed as ONE
     * exchange-gate-held critical section on IO. The gate is the process-wide
     * serialization point shared with session replacement, discard and the
     * rebind admission anchor; holding it here means a save, a stale fence
     * and the anchor's fresh read can never interleave.
     *
     * - Fence 1 (pre-write currency): an attempt already invalidated before
     *   the gate was granted never writes at all;
     * - Fence 2 (post-write currency): a write that LANDED while its attempt
     *   went stale is INVALIDATED by `discardIf(record)` (conditional
     *   tombstone — `Committed`/`NoMatch` settle it; `WriteFailed` keeps the
     *   proposal valid as the invalidation retry anchor) inside the SAME gate
     *   hold — never a newer attempt's record;
     * - the run-in owning-run re-check also completes inside the gate (its
     *   `discardIf` tombstone too), so every durable mutation of the save
     *   path happens under one gate hold.
     *
     * The gate is RELEASED before the UI settle runs: the settle is a pure
     * screen/state projection (it never touches the store — spec 375
     * "gate解放後のUI settleは純粋投影"). No suspension point exists inside
     * the gate hold.
     */
    private fun launchDurablePendingIntentSave(attempt: ImportAttempt, record: DurablePendingIntent) {
        scope.launch(Dispatchers.IO) {
            val outcome: DurableSaveOutcome = exchangeMutationGate.withGate<DurableSaveOutcome> {
                // Fence 1.
                if (activeAttempt?.token != attempt.token) {
                    return@withGate DurableSaveOutcome.FencedBeforeWrite
                }
                val saved = pendingImportStore.save(record)
                val stillCurrent = activeAttempt?.token == attempt.token
                if (!stillCurrent) {
                    // Fence 2: a stale landed write is INVALIDATED inside the
                    // same gate hold via the conditional tombstone (the
                    // accepted spec's invalidation commit — `Committed`/
                    // `NoMatch` are invalidation successes; `WriteFailed`
                    // leaves the proposal valid and stays typed/retryable).
                    // A newer attempt's record is never the victim (full
                    // equality). The in-memory slots already belong to the
                    // successor attempt — the settle must not touch them.
                    val invalidation = if (saved) pendingImportStore.discardIf(record) else DiscardIfResult.NoMatch
                    return@withGate DurableSaveOutcome.FencedAfterWrite(record, invalidation)
                }
                // Run-in entries: the owning run must still hold its selection
                // surface at the adoption moment (defense-in-depth — the same
                // check as the validation settle). A mismatch drops the attempt
                // AFTER its write, and the committed record is fenced away
                // inside this gate hold — a dropped run-in proposal must never
                // resurface as the durable status-card truth (Issue #374
                // attempt-fence contract).
                if (attempt.entryKind == ExchangeImportEntryKind.RUN_IN) {
                    val selecting = run.state as? ManualOrganizationRun.State.Selecting
                    if (attempt.owningRunId == null || selecting?.runId != attempt.owningRunId) {
                        val invalidation = if (saved) pendingImportStore.discardIf(record) else DiscardIfResult.NoMatch
                        return@withGate DurableSaveOutcome.OwningRunDropped(record, invalidation)
                    }
                }
                DurableSaveOutcome.Written(saved)
            }
            withContext(uiDispatcher) { settlePendingIntentSave(attempt, outcome) }
        }
    }

    /** Pure result of the gate-held durable save critical section. */
    private sealed interface DurableSaveOutcome {
        /** The attempt was already invalidated before the write; nothing written. */
        data object FencedBeforeWrite : DurableSaveOutcome

        /** The write landed but the attempt went stale mid-flight; invalidated inside the gate. */
        data class FencedAfterWrite(val record: DurablePendingIntent, val invalidation: DiscardIfResult) : DurableSaveOutcome

        /** The run-in owning run lost its surface; the write was invalidated inside the gate. */
        data class OwningRunDropped(val record: DurablePendingIntent, val invalidation: DiscardIfResult) : DurableSaveOutcome

        /** The write landed for a still-current attempt; settle adopts/fails per [saved]. */
        data class Written(val saved: Boolean) : DurableSaveOutcome
    }

    /**
     * Issue #374/#375: applies the PURE result of the gate-held durable save.
     * Runs on the UI dispatcher AFTER the exchange gate was released, and is a
     * projection only — it never touches the store (spec 375: gate解放後の
     * UI settleは純粋投影; all durable mutations happened inside the gate
     * hold in [launchDurablePendingIntentSave]).
     */
    private fun settlePendingIntentSave(attempt: ImportAttempt, outcome: DurableSaveOutcome) {
        when (outcome) {
            DurableSaveOutcome.FencedBeforeWrite -> {
                // The attempt was already invalidated and that path cleared
                // the in-memory slots; nothing left to project.
                pendingDurableRecord = null
                return
            }

            is DurableSaveOutcome.FencedAfterWrite -> {
                // The write landed but the attempt went stale mid-flight: the
                // record was invalidated (tombstone) INSIDE the gate; the
                // in-memory slots already belong to the successor attempt —
                // the settle must not touch them. A `WriteFailed`
                // invalidation leaves the record valid: it becomes the
                // invalidation RETRY ANCHOR (the rebind anchor refuses it as
                // invalidation-pending until the commit lands).
                if (outcome.invalidation is DiscardIfResult.WriteFailed && invalidationOperation == null) {
                    invalidationOperation = InvalidationOperation(outcome.record)
                }
                return
            }

            is DurableSaveOutcome.OwningRunDropped -> {
                // The run-in owning run lost its surface before adoption: the
                // record was invalidated INSIDE the gate; the dropped
                // attempt's slots clear here (pure in-memory projection). A
                // `WriteFailed` keeps the record as the invalidation RETRY
                // ANCHOR (the same commit is re-run; until it lands, the
                // rebind anchor refuses this record as invalidation-pending).
                pendingDurableRecord = null
                pendingValidated = null
                activeAttempt = null
                if (outcome.invalidation is DiscardIfResult.WriteFailed && invalidationOperation == null) {
                    invalidationOperation = InvalidationOperation(outcome.record)
                    status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_PERSIST_FAILED)
                }
                return
            }

            is DurableSaveOutcome.Written -> Unit
        }
        val saved = outcome.saved
        if (activeAttempt?.token != attempt.token) return
        val isRetry = screenState.value is ExchangeScreen.ImportPersistenceFailure
        if (saved) {
            // The record is durably committed and the success state adopts it:
            // the disappearance paths are now discard / expiry / replacement
            // only (spec 374 Contract notes 6), so the attempt-fenced cleanup
            // must never target it again.
            pendingDurableRecord = null
            adoptImportSuccess(attempt)
        } else {
            if (isRetry) {
                // The face did not change on a failed retry — surface the
                // typed notice so the failure is observable.
                status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_PERSIST_FAILED)
            }
            screen = ExchangeScreen.ImportPersistenceFailure(
                attemptToken = attempt.token,
                entryKind = attempt.entryKind,
            )
        }
    }

    /** The pre-#374 ImportSuccess adoption, unchanged (summary + anchors). */
    private fun adoptImportSuccess(attempt: ImportAttempt) {
        val validated = pendingValidated ?: return
        val scopeCount = if (attempt.entryKind == ExchangeImportEntryKind.RUN_IN) {
            validated.session.scopeCandidates.size
        } else {
            0
        }
        screen = ExchangeScreen.ImportSuccess(
            summary = exchangeImportSummary(
                validated.completed,
                scopeCount,
                // Issue #337: the advertised ref kinds of the same accepted
                // export, so the summary can tell an existing category from a
                // run-scoped proposal.
                categoryKindByRef = validated.export.categories.associate { it.ref to it.kind },
            ),
            entryKind = attempt.entryKind,
            attemptToken = attempt.token,
        )
    }

    /**
     * Issue #374 (spec 374 DI-AC-13): the primary remedy of the persistence
     * failure face — re-runs ONLY the store save of the anchored record
     * (validation is never repeated). Single-flight through the synchronous
     * [ExchangeScreen.ImportPersistenceFailure.retrying] flip; valid only
     * while the failure face and the attempt are current.
     */
    fun retryPendingIntentSave() {
        val failure = screenState.value as? ExchangeScreen.ImportPersistenceFailure ?: return
        if (failure.retrying) return
        val attempt = activeAttempt ?: return
        if (attempt.token != failure.attemptToken) return
        val record = pendingDurableRecord ?: return
        screen = failure.copy(retrying = true)
        // The retry runs through the same attempt-fenced write: an interrupt
        // (close / editor change) between the launch and the settle leaves no
        // record behind.
        launchDurablePendingIntentSave(attempt, record)
    }

    /** The single continuation settle outcomes (spec 328 CTA scenario). */
    internal sealed interface ContinueOutcome {
        data class Success(val startedRunId: RunId?) : ContinueOutcome
        data object Busy : ContinueOutcome
        data object NotAttachable : ContinueOutcome
        data object Failed : ContinueOutcome
    }

    /** One run-connection request (the CTA's seam input). */
    internal data class RunConnectionRequest(
        val entryKind: ExchangeImportEntryKind,
        val owningRunId: RunId?,
        val validated: ValidatedPersonalizedIntent,
    )

    /**
     * Package-internal test seam for the run-connection step (spec 328 review:
     * deterministic exception/ABA oracles). Production leaves it null and the
     * real run seams below run unchanged.
     */
    internal var connectRunOverride: (suspend (RunConnectionRequest) -> ContinueOutcome)? = null

    /**
     * Issue #375 test seam (SR-AC-07/08 race oracles): invoked on the rebind
     * path after [RebindIntentRebuilder] succeeded and BEFORE `run.start` —
     * the exact window the admission anchor guards. Tests use it to complete
     * a REAL world mutation (e.g. the controller's session replacement) on
     * another execution context and await it, deterministically, before the
     * anchor's fresh re-read. Production leaves it null.
     */
    internal var preAdmissionBarrier: (() -> Unit)? = null

    /**
     * The real run-connection seam: run-in entries attach to the owning run
     * (after re-checking its identity), idle entries start a fresh run.
     */
    private fun connectRun(request: RunConnectionRequest): ContinueOutcome {
        return if (request.entryKind == ExchangeImportEntryKind.RUN_IN) {
            val selecting = run.state as? ManualOrganizationRun.State.Selecting
            if (request.owningRunId == null || selecting?.runId != request.owningRunId) {
                // Owning run replaced: never attach into a different run
                // (spec 328 defense-in-depth).
                ContinueOutcome.Failed
            } else {
                when (run.attachIntent(request.validated)) {
                    ManualOrganizationRun.AttachIntentOutcome.Attached -> ContinueOutcome.Success(null)
                    ManualOrganizationRun.AttachIntentOutcome.NotAttachable -> ContinueOutcome.NotAttachable
                }
            }
        } else {
            // `start` is synchronously heavy (capture/composition/planning) —
            // always on IO (audit P2-1). No admission anchor on this legacy
            // in-process path (spec 328 flow); a refusal cannot occur.
            when (val started = run.start(intent = request.validated)) {
                is ManualOrganizationRun.StartOutcome.Started -> ContinueOutcome.Success(started.runId)
                ManualOrganizationRun.StartOutcome.Busy -> ContinueOutcome.Busy
                ManualOrganizationRun.StartOutcome.AdmissionRefused -> ContinueOutcome.Busy
            }
        }
    }

    /**
     * Issue #328: the explicit continuation CTA. Single-flight — the
     * synchronous `continuing` flip closes the re-entry window (a second
     * press is refused before any seam call), the CTA/discard affordances and
     * system Back are blocked while it is set, and the settle is applied only
     * while the screen still shows the same attempt token with `continuing`.
     */
    fun continueImport() {
        val current = screenState.value as? ExchangeScreen.ImportSuccess ?: return
        if (current.continuing) return
        val attempt = activeAttempt ?: return
        if (attempt.token != current.attemptToken) return
        val validated = pendingValidated ?: return
        screen = current.copy(continuing = true)
        scope.launch(Dispatchers.IO) {
            val request = RunConnectionRequest(attempt.entryKind, attempt.owningRunId, validated)
            val outcome = try {
                connectRunOverride?.invoke(request) ?: connectRun(request)
            } catch (failure: Throwable) {
                // A live-context failure keeps the success state operable
                // (start() aborts the operation, then rethrows). A cancelled
                // calling coroutine must not touch the UI.
                if (!currentCoroutineContext().isActive) throw failure
                ContinueOutcome.Failed
            }
            withContext(uiDispatcher) { settleContinue(attempt, outcome) }
        }
    }

    private fun settleContinue(attempt: ImportAttempt, outcome: ContinueOutcome) {
        val current = screenState.value as? ExchangeScreen.ImportSuccess ?: return
        if (current.attemptToken != attempt.token || !current.continuing) return
        when (outcome) {
            is ContinueOutcome.Success -> {
                // Defense-in-depth: a start whose run id no longer matches the
                // live selection surface is treated as a failure settle.
                if (!runIdMatches(outcome.startedRunId)) {
                    status = ExchangeStatus(ExchangeStatus.Kind.CTA_START_FAILED)
                    screen = current.copy(continuing = false)
                    return
                }
                activeAttempt = null
                pendingValidated = null
                pendingDurableRecord = null
                // Issue #374 / #375 contract (spec 374 Contract notes 6): the
                // durable record is deliberately NOT written or deleted on a
                // successful continuation settle — "継続成功は提案を消費しない"
                // (a successful continue does not consume the proposal). The
                // disappearance paths are discard / expiry / replacement only;
                // the record outlives the run for #375's rebind.
                // A refused-CTA guidance must not linger on the next surface.
                status = null
                screen = ExchangeScreen.Closed
            }

            ContinueOutcome.Busy, ContinueOutcome.NotAttachable -> {
                status = ExchangeStatus(ExchangeStatus.Kind.RUN_BUSY)
                screen = current.copy(continuing = false)
            }

            ContinueOutcome.Failed -> {
                status = ExchangeStatus(ExchangeStatus.Kind.CTA_START_FAILED)
                screen = current.copy(continuing = false)
            }
        }
    }

    /**
     * The run id comparison only exists where the run state exposes one
     * (`State.Selecting.runId`); composed phases hide it, so those settle
     * without the comparison (spec 328: 「取得でき、かつ一致しない場合」).
     */
    private fun runIdMatches(startedRunId: RunId?): Boolean {
        if (startedRunId == null) return true
        val selecting = run.state as? ManualOrganizationRun.State.Selecting ?: return true
        return selecting.runId == startedRunId
    }

    /**
     * Issue #374 (spec 374 DI-AC-01 "ImportReview再開面"): opens the
     * cold-process resume face from the hub status card. Loads the durable
     * record plus the ACTIVE export session on IO, applies the read-time
     * reconcile (the master validity defense), and:
     * - Valid → adopts [ExchangeScreen.ImportReview] with the summary
     *   reconstructed by [durableImportSummary] (the same derivation and
     *   inputs as the success face) and the SESSION's expiry as the
     *   remaining-time display root;
     * - Invalid → fail-closed: the record is cleaned (`delete`) and the typed
     *   [ExchangeStatus.Kind.IMPORT_REVIEW_UNAVAILABLE] status surfaces while
     *   the screen stays Closed (nothing is invented);
     * - Absent → the same typed status.
     *
     * The run state is never touched and no CTA exists on the adopted face
     * (#375 owns the rebind). The adoption is anchored to the Closed state
     * this open leaves — a face that moved on while the read was in flight is
     * never clobbered by a late review adoption (the Invalid cleanup is
     * store-state and still applies).
     */
    fun openPendingImportReview() {
        abandonAwaitingUsageAccessJit()
        status = null
        requestAttemptInvalidation {
            scope.launch(Dispatchers.IO) {
                // Issue #375: the read + reconcile + Invalid-cleanup mutation
                // holds the exchange mutation gate (short AtomicFile ops only —
                // the face adoption below runs after release).
                val (now, decision, session) = exchangeMutationGate.withGate {
                    val now = controller.nowEpochMs()
                    val record = pendingImportStore.load()
                    val session = controller.activeSession()
                    val decision = reconcilePendingIntent(record, session, now)
                    if (decision is PendingIntentReconcile.Invalid) {
                        pendingImportStore.delete()
                    }
                    Triple(now, decision, session)
                }
                withContext(uiDispatcher) {
                    if (screenState.value !is ExchangeScreen.Closed) return@withContext
                    when {
                        decision is PendingIntentReconcile.Valid && session != null -> {
                            val proposal = decision.proposal
                            screen = ExchangeScreen.ImportReview(
                                summary = durableImportSummary(proposal, session),
                                expiresAtEpochMs = session.expiresAtEpochMs,
                                readAtEpochMs = now,
                                entryKind = when (proposal.entryKind) {
                                    PendingImportEntryKind.IDLE -> ExchangeImportEntryKind.IDLE
                                    PendingImportEntryKind.RUN_IN -> ExchangeImportEntryKind.RUN_IN
                                },
                            )
                        }

                        else -> status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_REVIEW_UNAVAILABLE)
                    }
                }
            }
        }
    }

    /**
     * Issue #375 (spec "再開面CTA"): the rebind continuation CTA on the
     * ImportReview resume face. Sequence per the accepted contract:
     *
     * 1. single-flight (`ImportReview.continuing` — while true, discard and
     *    Back are refused; spec 328 AC-3 discipline);
     * 2. OUTSIDE the exchange mutation gate: fresh record/session/clock read,
     *    reconcile, and the rebuild seam (structural-digest re-verification
     *    included; identity injected from the record);
     * 3. `run.start(intent, admissionAnchor, selectionRestore)` — the caller
     *    does NOT hold the gate. The anchor acquires the gate itself at the
     *    admission instant, re-reads record/session/clock fresh, requires
     *    reconcile-Valid + full-record equality + unexpired, and on Admit runs
     *    the operation creation INSIDE the gate hold, releasing it before the
     *    run's detection begins;
     * 4. settle is a pure projection: `Started` closes the face (the run
     *    surface takes over); `Busy`/`AdmissionRefused` are typed refusals —
     *    a refusal re-reads the resume face so a replaced/invalidated record
     *    never lingers as stale display.
     *
     * The durable record is never written or deleted on the success settle
     * ("継続成功は提案を消費しない"); failures leave it untouched.
     */
    fun continuePendingImport() {
        val current = screenState.value as? ExchangeScreen.ImportReview ?: return
        if (current.continuing) return
        screen = current.copy(continuing = true)
        scope.launch(Dispatchers.IO) {
            // (2) Gate-out: preliminary read + reconcile + rebuild.
            val now = controller.nowEpochMs()
            val record = pendingImportStore.load()
            val session = controller.activeSession()
            val outcome = RebindIntentRebuilder.rebuild(
                record = record,
                session = session,
                currentStructural = when (val structural = controller.currentStructural()) {
                    is ExchangeStructuralResult.Ready -> structural.structural

                    // Composition not ready (transient): fail-closed, retryable —
                    // the proposal is untouched and the face stays operable.
                    is ExchangeStructuralResult.NotReady -> return@launch settleRebindNotReady()
                },
                nowEpochMs = now,
            )
            when (outcome) {
                is RebindIntentRebuilder.Outcome.InvalidProposal -> {
                    // Fail-closed per #374. The preliminary judgment was made
                    // OUTSIDE the gate, so the cleanup re-reads the current
                    // record/session/clock INSIDE the gate and re-runs the
                    // reconcile: only a record that is STILL invalid is
                    // removed; a record a concurrent path saved as valid is
                    // re-adopted into the face instead of being destroyed
                    // (single-active durable truth; spec "reconcile清掃もgate配下").
                    val reAdopted: Pair<DurablePendingIntent, ExportSession>? = exchangeMutationGate.withGate {
                        val freshNow = controller.nowEpochMs()
                        val freshRecord = pendingImportStore.load()
                        val freshSession = controller.activeSession()
                        when (reconcilePendingIntent(freshRecord, freshSession, freshNow)) {
                            is PendingIntentReconcile.Invalid -> {
                                pendingImportStore.delete()
                                null
                            }

                            else -> if (freshRecord != null && freshSession != null) freshRecord to freshSession else null
                        }
                    }
                    withContext(uiDispatcher) {
                        if (reAdopted != null) {
                            val (proposal, adoptSession) = reAdopted
                            screen = ExchangeScreen.ImportReview(
                                summary = durableImportSummary(proposal, adoptSession),
                                expiresAtEpochMs = adoptSession.expiresAtEpochMs,
                                readAtEpochMs = controller.nowEpochMs(),
                                entryKind = when (proposal.entryKind) {
                                    PendingImportEntryKind.IDLE -> ExchangeImportEntryKind.IDLE
                                    PendingImportEntryKind.RUN_IN -> ExchangeImportEntryKind.RUN_IN
                                },
                            )
                        } else {
                            status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_REVIEW_UNAVAILABLE)
                            screen = ExchangeScreen.Closed
                        }
                    }
                    return@launch
                }

                is RebindIntentRebuilder.Outcome.ContextStale -> {
                    withContext(uiDispatcher) {
                        status = ExchangeStatus(ExchangeStatus.Kind.REBIND_CONTEXT_STALE)
                        screen = (screenState.value as? ExchangeScreen.ImportReview)?.copy(continuing = false) ?: screenState.value
                    }
                    return@launch
                }

                is RebindIntentRebuilder.Outcome.Rebuilt -> Unit
            }
            val rebuilt = outcome.intent
            val sourceRecord = outcome.sourceRecord
            // Test seam: the deterministic point between "rebuild succeeded"
            // and "admission begins" — race oracles complete a REAL world
            // mutation here (another execution context) before admitting.
            try {
                preAdmissionBarrier?.invoke()
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) throw failure
                withContext(uiDispatcher) {
                    status = ExchangeStatus(ExchangeStatus.Kind.REBIND_START_FAILED)
                    screen = (screenState.value as? ExchangeScreen.ImportReview)?.copy(continuing = false) ?: screenState.value
                }
                return@launch
            }
            val restoreMode = when (sourceRecord.entryKind) {
                PendingImportEntryKind.RUN_IN -> ManualOrganizationRun.SelectionRestore.PreviousExplicit
                else -> ManualOrganizationRun.SelectionRestore.None
            }
            // (3) Admission — the anchor acquires the exchange gate itself at
            // the admission instant (fresh read → verdict → operation creation
            // inside one gate hold), so a replacement, a discard tombstone or
            // an expiry can never slip between the verification and the
            // admission.
            val started = try {
                run.start(
                    intent = rebuilt,
                    admissionAnchor = ManualOrganizationRun.StartAdmissionAnchor { complete ->
                        val admitted = exchangeMutationGate.withGate {
                            val fresh = pendingImportStore.load()
                            val freshSession = controller.activeSession()
                            val verdict = reconcilePendingIntent(fresh, freshSession, controller.nowEpochMs())
                            val matches = verdict is PendingIntentReconcile.Valid &&
                                fresh == sourceRecord &&
                                // An outstanding invalidation commit of this
                                // very record (a landed `WriteFailed`) is an
                                // invalidation-pending state — never Valid
                                // for continuation.
                                !isInvalidationPending(fresh)
                            if (matches) complete()
                            matches
                        }
                        admitted
                    },
                    selectionRestore = restoreMode,
                )
            } catch (failure: Throwable) {
                if (!currentCoroutineContext().isActive) throw failure
                withContext(uiDispatcher) {
                    status = ExchangeStatus(ExchangeStatus.Kind.REBIND_START_FAILED)
                    screen = (screenState.value as? ExchangeScreen.ImportReview)?.copy(continuing = false) ?: screenState.value
                }
                return@launch
            }
            // (4) Pure projection settle.
            withContext(uiDispatcher) {
                when (started) {
                    is ManualOrganizationRun.StartOutcome.Started -> {
                        // The run surface takes over; the proposal is NOT
                        // consumed (継続成功は提案を消費しない).
                        status = null
                        screen = ExchangeScreen.Closed
                    }

                    ManualOrganizationRun.StartOutcome.Busy -> {
                        status = ExchangeStatus(ExchangeStatus.Kind.RUN_BUSY)
                        screen = (screenState.value as? ExchangeScreen.ImportReview)?.copy(continuing = false) ?: screenState.value
                    }

                    ManualOrganizationRun.StartOutcome.AdmissionRefused -> {
                        status = ExchangeStatus(ExchangeStatus.Kind.REBIND_ANCHOR_REFUSED)
                        // The refusal means the durable world changed; re-read
                        // it so stale display never lingers. Same gate-held
                        // read/reconcile/cleanup as the open path.
                        scope.launch(Dispatchers.IO) {
                            val (reDecision, reSession, reNow) = exchangeMutationGate.withGate {
                                val reNow = controller.nowEpochMs()
                                val reRecord = pendingImportStore.load()
                                val reSession = controller.activeSession()
                                val reDecision = reconcilePendingIntent(reRecord, reSession, reNow)
                                if (reDecision is PendingIntentReconcile.Invalid) {
                                    pendingImportStore.delete()
                                }
                                Triple(reDecision, reSession, reNow)
                            }
                            withContext(uiDispatcher) {
                                when {
                                    reDecision is PendingIntentReconcile.Valid && reSession != null -> {
                                        val proposal = reDecision.proposal
                                        // The anchor-refusal notice persists: the
                                        // re-adopted face shows the CURRENT truth.
                                        screen = ExchangeScreen.ImportReview(
                                            summary = durableImportSummary(proposal, reSession),
                                            expiresAtEpochMs = reSession.expiresAtEpochMs,
                                            readAtEpochMs = reNow,
                                            entryKind = when (proposal.entryKind) {
                                                PendingImportEntryKind.IDLE -> ExchangeImportEntryKind.IDLE
                                                PendingImportEntryKind.RUN_IN -> ExchangeImportEntryKind.RUN_IN
                                            },
                                        )
                                    }

                                    else -> {
                                        // The invalidation took effect: fail-closed
                                        // cleanup and close. The anchor-refusal
                                        // notice stays (it explains the close).
                                        screen = ExchangeScreen.Closed
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Projects the transient "composition not ready" refusal — the face stays, retryable. */
    private fun settleRebindNotReady() {
        status = ExchangeStatus(ExchangeStatus.Kind.REBIND_START_FAILED)
        screen = (screenState.value as? ExchangeScreen.ImportReview)?.copy(continuing = false) ?: screenState.value
    }

    /**
     * Issue #328 (spec 328 rev.2 D-13 / spec 374 DI-AC-08): the explicit
     * discard — generalized by #374 to cover BOTH faces that hold an imported
     * proposal: the 取り込み成功状態 (anchored to its attempt token) and the
     * ImportReview resume face (anchored to the durable record itself — no
     * attempt exists there). Refused while the CTA is continuing — a started
     * seam cannot be withdrawn, so the discard side is the one that yields.
     * The durable record is discarded through the tombstone two-phase commit
     * (`store.discard()` on IO): the face closes ONLY after the tombstone
     * commit succeeds; a failed commit is a typed notice and keeps the face
     * and the proposal (retryable). The export session is NOT invalidated:
     * re-importing the same reply stays possible while the request is valid.
     */
    fun discardImport() {
        val current = screenState.value
        val attemptToken: Long? = when (current) {
            is ExchangeScreen.ImportSuccess -> {
                if (current.continuing) return
                current.attemptToken
            }

            is ExchangeScreen.ImportReview -> null

            else -> return
        }
        scope.launch(Dispatchers.IO) {
            // Issue #375: the tombstone commit is a durable-record mutation —
            // it holds the exchange mutation gate (the same serialization
            // point as the rebind admission anchor), and the gate is released
            // before the settle projects the result (no store calls there).
            val discarded = exchangeMutationGate.withGate { pendingImportStore.discard() }
            withContext(uiDispatcher) { settleDiscardImport(attemptToken, discarded) }
        }
    }

    /**
     * Issue #374 (DI-AC-08): applies the tombstone commit result. On the
     * success face the settle stays bound to the attempt token and refused
     * while continuing, exactly like the CTA settle; on the ImportReview face
     * the durable record IS the anchor, so the result applies directly — a
     * late result never closes a replaced face in either case.
     */
    private fun settleDiscardImport(attemptToken: Long?, discarded: Boolean) {
        val current = screenState.value
        val applies = when (current) {
            is ExchangeScreen.ImportSuccess -> current.attemptToken == attemptToken && !current.continuing
            is ExchangeScreen.ImportReview -> true
            else -> false
        }
        if (!applies) return
        if (!discarded) {
            // Tombstone commit failed: the proposal stays valid and shown;
            // the discard is retryable (the confirmation can be re-raised).
            status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_DISCARD_FAILED)
            return
        }
        requestAttemptInvalidation {
            status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_DISCARDED)
            screen = ExchangeScreen.Closed
        }
    }
}

/** Inline typed status line content. */
data class ExchangeStatus(val kind: Kind) {
    enum class Kind {
        TRANSPORT_SUCCESS,
        TRANSPORT_CLIPBOARD_FAILED,
        TRANSPORT_SHARE_ABSENT,
        TRANSPORT_FILE_FAILED,
        FILE_READ_FAILED,
        GENERATION_INPUT_NOT_READY,
        GENERATION_STORE_FAILURE,
        GENERATION_OVERSIZE,
        INPUT_OVERSIZE,
        RUN_BUSY,

        /** Issue #332 (spec AC-6): clipboard empty/unreadable on the explicit read. */
        CLIPBOARD_EMPTY,

        /** Issue #332 (spec AC-6): the clipboard carries no text item. */
        CLIPBOARD_NOT_TEXT,

        /**
         * Issue #328: the run-connection seam failed (exception, or a
         * started run that no longer matches). The success state stays
         * operable (retry / discard).
         */
        CTA_START_FAILED,

        /** Issue #328: the pending import was explicitly discarded. */
        IMPORT_DISCARDED,

        /**
         * Issue #374 (spec 374 DI-AC-13): the durable save of the imported
         * proposal failed on a retry — a persistence-step typed failure, not
         * one of the 20 import validation classes. The face stays; the retry
         * remains possible.
         */
        IMPORT_PERSIST_FAILED,

        /**
         * Issue #374 (spec 374 DI-AC-08): the discard's tombstone commit
         * failed — the proposal stays valid and shown, the discard retryable.
         */
        IMPORT_DISCARD_FAILED,

        /**
         * Issue #374 (spec 374 DI-AC-01 "ImportReview再開面"): the hub's
         * ImportReview open found no valid proposal (expired, invalidated, or
         * absent) — the typed notice of the fail-closed open; no review face
         * is invented.
         */
        IMPORT_REVIEW_UNAVAILABLE,

        /**
         * Issue #375 (spec SR-AC-08): the rebind's pre-admission structural
         * re-verification failed (`CONTEXT_STALE` semantics — the home
         * structure changed since the export, or reconstruction diverged).
         * The proposal survives; the remedy is re-creating the request.
         */
        REBIND_CONTEXT_STALE,

        /**
         * Issue #375 (spec "rebind admission anchor"): the admission anchor
         * refused — the durable proposal was invalidated, replaced, or
         * expired between rebuild and admission. No run admission occurred;
         * the resume face re-reads the current truth.
         */
        REBIND_ANCHOR_REFUSED,

        /**
         * Issue #375: the rebind CTA could not start the run (a live-context
         * failure of the connection seam). The resume face stays operable.
         */
        REBIND_START_FAILED,
    }

    companion object {
        fun transportFailure(kind: ExchangeTransportFailure): Kind = when (kind) {
            ExchangeTransportFailure.CLIPBOARD_UNAVAILABLE -> Kind.TRANSPORT_CLIPBOARD_FAILED
            ExchangeTransportFailure.SHARE_TARGET_ABSENT -> Kind.TRANSPORT_SHARE_ABSENT
            ExchangeTransportFailure.FILE_WRITE_FAILED -> Kind.TRANSPORT_FILE_FAILED
        }
    }
}

/** The exchange items. Hosted only while the run is not active (Idle/Cancelled). */
fun LazyListScope.exchangeFlowItems(
    holder: ExchangeFlowStateHolder,
    onDiscardRequest: () -> Unit,
    discardFocus: FocusRequester? = null,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
    onOpenDiagnostics: (() -> Unit)? = null,
    onImportDiscardRequest: () -> Unit = {},
    importDiscardFocus: FocusRequester? = null,
) {
    exchangeFlowItems(
        holder, null, emptyMap(), onDiscardRequest, discardFocus,
        clipboardTransport, shareTransport, fileTransport, onOpenDiagnostics,
        onImportDiscardRequest, importDiscardFocus,
    )
}

/**
 * Issue #331: the exchange items with the run-in (scope-composed) entry.
 * Hosted inside the selection surface while a run holds it; when
 * [scopedSelection] is non-null the generation composes the export from the
 * frozen selection instead of the idle full-organization scope.
 * [onDiscardRequest] converges the T-16 破棄 button and system Back on the
 * host's one discard confirmation (issue #372, D-13).
 * [onImportDiscardRequest] is the #374 equivalent for the import success
 * state's 破棄して閉じる button: it converges with system Back on the host's
 * ONE import-discard confirmation (spec 328 rev.2 D-13), and
 * [importDiscardFocus] is where the host restores focus after the dialog is
 * dismissed. [onOpenDiagnostics] reaches the existing diagnostics route from
 * the failure face's 診断を開く (issue #373); null hides the row where no
 * route exists.
 */
fun LazyListScope.exchangeFlowItems(
    holder: ExchangeFlowStateHolder,
    scopedSelection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>?,
    scopedLabels: Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>,
    onDiscardRequest: () -> Unit,
    discardFocus: FocusRequester?,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
    onOpenDiagnostics: (() -> Unit)? = null,
    onImportDiscardRequest: () -> Unit = {},
    importDiscardFocus: FocusRequester? = null,
) {
    val scoped = scopedSelection?.let { it to scopedLabels }
    when (val current = holder.screen) {
        ExchangeScreen.Closed -> {
            // Issue #372 (D-04): the idle entry row is gone — the AI method
            // lives in the T-07 method choice. Only the run-in scoped entry
            // remains (spec 331 contract unchanged).
            if (scoped != null) {
                item(key = "exchange-entry") {
                    ExchangeScopedEntryRow(
                        onOpenFlow = holder::openFlow,
                        onOpenImport = holder::openImport,
                    )
                }
            }
        }

        is ExchangeScreen.SelectingPrivacy -> {
            item(key = "exchange-privacy") {
                Column {
                    if (scoped != null) {
                        // Issue #331: announce the frozen selection (a11y).
                        Text(
                            text = stringResource(R.string.exchange_scoped_freeze_notice),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .semantics { liveRegion = LiveRegionMode.Assertive }
                                .testTag("exchange-scoped-freeze-notice"),
                        )
                    }
                    ExchangePrivacySelection(
                        activeRequestExpiresAtEpochMs = current.activeRequestExpiresAtEpochMs,
                        activeRequestReadAtEpochMs = current.activeRequestReadAtEpochMs,
                        requiresConfirmation = current.replacementConfirmationRequired,
                        onGenerate = { tier -> holder.requestGeneration(tier, scoped) },
                        onCancel = holder::close,
                        onOpenImport = holder::openImport,
                    )
                }
            }
        }

        is ExchangeScreen.ReplacementConfirm -> {
            item(key = "exchange-replacement-confirm") {
                ExchangeReplacementConfirm(
                    onConfirm = { holder.confirmReplacementAndGenerate(current.tier, current.scoped) },
                    onDecline = holder::declineReplacement,
                )
            }
        }

        ExchangeScreen.Generating -> {
            item(key = "exchange-generating") {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
                    Text(stringResource(R.string.exchange_generating))
                }
            }
        }

        is ExchangeScreen.Disclosing -> {
            item(key = "exchange-disclosure") {
                ExchangeDisclosure(
                    holder = holder,
                    state = current.state,
                    onDiscardRequest = onDiscardRequest,
                    discardFocus = discardFocus,
                    clipboardTransport = clipboardTransport,
                    shareTransport = shareTransport,
                    fileTransport = fileTransport,
                )
            }
        }

        is ExchangeScreen.Importing -> {
            item(key = "exchange-import") {
                ExchangeImportField(
                    replyText = current.replyText,
                    holder = holder,
                    fileTransport = fileTransport,
                )
            }
        }

        is ExchangeScreen.ImportOutcomeScreen -> {
            item(key = "exchange-import-outcome") {
                ExchangeImportOutcome(current.outcome, current.rawText, holder, onOpenDiagnostics)
            }
        }

        is ExchangeScreen.ImportSuccess -> {
            item(key = "exchange-import-success") {
                ExchangeImportSuccess(
                    state = current,
                    onContinue = holder::continueImport,
                    // Issue #374 (spec 328 rev.2 D-13): the explicit discard
                    // button raises the SAME confirmation dialog as system
                    // Back — the confirm itself runs holder.discardImport().
                    onDiscard = onImportDiscardRequest,
                    discardFocus = importDiscardFocus,
                )
            }
        }

        is ExchangeScreen.ImportPersistenceFailure -> {
            item(key = "exchange-import-persist-failed") {
                ExchangeImportPersistenceFailure(
                    state = current,
                    onRetry = holder::retryPendingIntentSave,
                    onInterrupt = holder::close,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
            }
        }

        is ExchangeScreen.ImportReview -> {
            item(key = "exchange-import-review") {
                ExchangeImportReview(
                    state = current,
                    // Issue #375 (spec "再開面CTA"): the rebind continuation.
                    onContinue = holder::continuePendingImport,
                    // Issue #374 (spec 328 rev.2 D-13): the review face's
                    // 破棄して閉じる button converges with system Back priority
                    // on the HOST's ONE import-discard confirmation — exactly
                    // the same dialog entry the success face's button uses.
                    onDiscard = onImportDiscardRequest,
                    discardFocus = importDiscardFocus,
                )
            }
        }

        is ExchangeScreen.AwaitingUsageAccessJit -> {
            item(key = "exchange-usage-access-jit") {
                ExchangeUsageAccessJitDialogHost(state = current, holder = holder)
            }
        }
    }
    holder.status?.let { status ->
        item(key = "exchange-status") {
            Text(
                text = exchangeStatusText(status.kind),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("exchange-status"),
            )
        }
    }
}

/**
 * Issue #371: exchange-surface host of the JIT request dialog. Presentation
 * ownership mirrors the run surface: only the attempt holding the gate
 * reservation presents; a waiter wakes on the gate's resolution or
 * re-acquires when the reservation was released. Returning from the system
 * settings runs the bounded grant re-read before the pending generation
 * resumes (granted or not — the decline is never a failure).
 */
@Composable
private fun ExchangeUsageAccessJitDialogHost(
    state: ExchangeScreen.AwaitingUsageAccessJit,
    holder: ExchangeFlowStateHolder,
) {
    val context = LocalContext.current
    val gateSnapshot by holder.usageAccessGate.snapshot.collectAsStateWithLifecycle()
    var presenter by remember(state.attemptToken) { mutableStateOf(state.isPresenter) }
    var settingsRequested by remember(state.attemptToken) { mutableStateOf(false) }
    var settingsLaunchFailed by remember(state.attemptToken) { mutableStateOf(false) }
    var grantCheckTick by remember(state.attemptToken) { mutableIntStateOf(0) }
    val owner = ExchangeJitAttemptOwner(state.attemptToken)

    // Unmount while the pause is unresolved (navigation away, a run admission
    // leaving the Idle face, host teardown): apply the owner-destruction
    // rules — release while un-presented, abandon-resolve once presented — so
    // the process barrier is never orphaned. No-op on the normal-resolution
    // unmount (the screen has already moved past the awaiting state).
    DisposableEffect(state.attemptToken) {
        // Bind the cleanup to THIS attempt's identity: a stale host unmount
        // (the screen already moved to a newer attempt) must not act on it.
        val ownToken = state.attemptToken
        onDispose { holder.disposeUsageAccessJitAttempt(ownToken) }
    }

    // Waiter wakeup: deterministic observation of the gate snapshot.
    LaunchedEffect(gateSnapshot, state.attemptToken) {
        if (presenter) return@LaunchedEffect
        when (gateSnapshot.phase) {
            UsageAccessJitGate.Phase.Resolved -> holder.continueUsageAccessJit(state.attemptToken)

            UsageAccessJitGate.Phase.Available ->
                if (holder.tryAcquireJitPresentation(state.attemptToken)) presenter = true

            else -> Unit
        }
    }
    // Presenting consumes the re-presentation right exactly once.
    LaunchedEffect(presenter, state.attemptToken) {
        if (presenter) holder.usageAccessGate.markPresented(owner)
    }
    // Returning from the system settings: observe ON_RESUME, run the bounded
    // grant re-read, then resume the pending generation (granted or not).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && settingsRequested) {
                settingsRequested = false
                grantCheckTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(grantCheckTick, state.attemptToken) {
        if (grantCheckTick == 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            awaitUsageAccessGrant(isGranted = { UsageAccess.isGranted(context) })
        }
        holder.continueUsageAccessJit(state.attemptToken)
    }

    if (presenter) {
        UsageAccessJitRequestDialog(
            settingsLaunchFailed = settingsLaunchFailed,
            onOpenSettings = {
                if (openUsageAccessSettings(context)) {
                    settingsRequested = true
                } else {
                    settingsLaunchFailed = true
                }
            },
            onContinue = { holder.continueUsageAccessJit(state.attemptToken) },
        )
    }
}

/**
 * Issue #327: the user-facing capability explanation shared by the run-in
 * exchange entry and the T-15 request face (issue #372 relocated the idle
 * entry's copy here). It describes what the AI can do in concrete user-language
 * examples (never schema terms), states that the AI never changes the home
 * screen directly, and explains the expected conversation flow — the
 * interview happens inside the external AI app, the conversation never
 * travels through NunuLauncher, and the launcher↔AI handoffs stay one
 * request and one final proposal.
 */
@Composable
private fun ExchangeCapabilityNotes(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.exchange_capability_title),
            style = MaterialTheme.typography.titleSmall,
        )
        for (res in exchangeCapabilityExampleResourceIds()) {
            Text(
                text = "• " + stringResource(res),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(R.string.exchange_capability_no_direct_change),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.exchange_capability_flow),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The concrete example lines of the capability notes (test surface for AC-4). */
internal fun exchangeCapabilityExampleResourceIds(): List<Int> = listOf(
    R.string.exchange_capability_example_frequent,
    R.string.exchange_capability_example_group,
    R.string.exchange_capability_example_keep,
    R.string.exchange_capability_example_front,
    R.string.exchange_capability_example_minimal_change,
)

/**
 * Issue #331: the run-in entry row. The export scope is the frozen selection
 * (existing placements plus the selected missing apps), so the reply can
 * advise the candidates the user is about to organize.
 */
@Composable
private fun ExchangeScopedEntryRow(onOpenFlow: () -> Unit, onOpenImport: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_scoped_entry_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-scoped-entry-title"),
        )
        Text(
            text = stringResource(R.string.exchange_scoped_entry_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )
        ExchangeCapabilityNotes(
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("exchange-scoped-entry-capability"),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenFlow, modifier = Modifier.testTag("exchange-scoped-entry-open")) {
                Text(stringResource(R.string.exchange_scoped_entry_open))
            }
            OutlinedButton(onClick = onOpenImport, modifier = Modifier.testTag("exchange-scoped-entry-import")) {
                Text(stringResource(R.string.exchange_entry_import))
            }
        }
    }
}

/**
 * Issue #372 (T-15「依頼を作る」): the request-creation face. It carries the
 * active-request pre-display (existence + remaining time, D-02), the D-09
 * expectation statement, the two-choice tier vocabulary (D-14), the create CTA
 * (through the replacement confirmation when an active request exists), and
 * the import lead-in the removed idle entry row used to own. Cancel is the
 * zero-write 「キャンセル」 (nothing to discard yet, D-13 §9).
 */
@Composable
private fun ExchangePrivacySelection(
    activeRequestExpiresAtEpochMs: Long?,
    activeRequestReadAtEpochMs: Long?,
    requiresConfirmation: Boolean,
    onGenerate: (PrivacyTier) -> Unit,
    onCancel: () -> Unit,
    onOpenImport: () -> Unit,
) {
    var labelInclusive by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_request_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-request-title"),
        )
        if (activeRequestExpiresAtEpochMs != null && activeRequestReadAtEpochMs != null) {
            ActiveRequestPreDisplay(
                expiresAtEpochMs = activeRequestExpiresAtEpochMs,
                readAtEpochMs = activeRequestReadAtEpochMs,
            )
        }
        ExchangeCapabilityNotes(
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("exchange-request-capability"),
        )
        Text(
            text = stringResource(R.string.exchange_expectation_fixed_home),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("exchange-expectation"),
        )
        Text(
            text = stringResource(R.string.exchange_privacy_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("exchange-privacy-title"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            RadioButton(selected = !labelInclusive, onClick = { labelInclusive = false })
            Text(stringResource(R.string.exchange_privacy_redacted))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(selected = labelInclusive, onClick = { labelInclusive = true })
            Text(stringResource(R.string.exchange_privacy_labels))
        }
        if (labelInclusive) {
            Text(
                text = stringResource(R.string.exchange_privacy_labels_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (requiresConfirmation) {
            Text(
                text = stringResource(R.string.exchange_replacement_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        // Issue #372 (implementation review, EX-AC-10): the three actions
        // stack vertically so 200% font scale reflows the column instead of
        // clipping a fixed-width row (ja copy is the widest case).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onGenerate(if (labelInclusive) PrivacyTier.EXTERNAL_WITH_LABELS else PrivacyTier.EXTERNAL_REDACTED)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("exchange-generate"),
            ) {
                Text(stringResource(R.string.exchange_generate))
            }
            OutlinedButton(
                onClick = onOpenImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.exchange_entry_import))
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.exchange_cancel))
            }
        }
    }
}

/**
 * Issue #372 (T-15, D-02): the active-request pre-display — existence plus the
 * remaining time. Both values come from the holder's read (the projection's
 * display root): no second clock touches the face, and the holder's reads
 * (entry, resume, expiry-scheduled) keep it consistent with the store, so
 * this row never needs a ticking clock.
 */
@Composable
private fun ActiveRequestPreDisplay(expiresAtEpochMs: Long, readAtEpochMs: Long) {
    val remaining = requestRemainingDisplay(expiresAtEpochMs, readAtEpochMs)
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .testTag("exchange-request-active"),
    ) {
        Text(
            text = stringResource(R.string.exchange_request_active_line),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = when (remaining) {
                is RequestRemaining.Hours -> pluralStringResource(R.plurals.exchange_request_remaining_hours, remaining.count, remaining.count)
                RequestRemaining.UnderOneHour -> stringResource(R.string.exchange_request_remaining_under_hour)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ExchangeReplacementConfirm(
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_replacement_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-replacement-title"),
        )
        Text(
            text = stringResource(R.string.exchange_replacement_warning),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onConfirm, modifier = Modifier.testTag("exchange-replacement-confirm")) {
                Text(stringResource(R.string.exchange_replacement_confirm))
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.testTag("exchange-replacement-decline")) {
                Text(stringResource(R.string.exchange_replacement_decline))
            }
        }
    }
}

/**
 * Issue #372 (T-16「送信前確認」, D-10): the pre-send confirmation as a
 * summary-first face. The informed-consent summary (what kinds, how many
 * items, what ceiling) is the primary surface; the generated package's full
 * text is collapsed by default and expandable for review (the expandable text
 * is the identical immutable value the transports hand out — spec 205 AC-12).
 * The D-09 expectation statement is repeated here, one consent point before
 * any external disclosure. The unsent cancel is the 「破棄」 vocabulary with
 * ONE confirmation dialog (D-13), shared with system Back through
 * [onDiscardRequest]; after a send the same slot becomes the no-confirm
 * 閉じる and the request survives.
 */
@Composable
private fun ExchangeDisclosure(
    holder: ExchangeFlowStateHolder,
    state: ExchangeDisclosureState,
    onDiscardRequest: () -> Unit,
    discardFocus: FocusRequester?,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
) {
    val context = LocalContext.current
    val fileSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) holder.writeFile(fileTransport, state.packageText, uri)
    }
    // Collapsed by default (issue #372 review: the full text never forces
    // itself on the user); the expand state is announced to TalkBack through
    // the state description and the toggle label, keyed per generated package.
    var expanded by remember(state.session.exportId) { mutableStateOf(false) }
    val expandedStateText = stringResource(R.string.exchange_disclosure_collapse)
    val collapsedStateText = stringResource(R.string.exchange_disclosure_expand)
    val summary = exchangeDisclosureSummary(state)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_disclosure_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-disclosure-title"),
        )
        Text(
            text = if (state.tier == PrivacyTier.EXTERNAL_WITH_LABELS) {
                stringResource(R.string.exchange_disclosure_labels_included)
            } else {
                stringResource(R.string.exchange_disclosure_redacted)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = stringResource(R.string.exchange_disclosure_summary_items, summary.itemCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("exchange-disclosure-summary-items"),
        )
        Text(
            text = stringResource(R.string.exchange_disclosure_summary_limit),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("exchange-disclosure-summary-limit"),
        )
        Text(
            text = stringResource(R.string.exchange_expectation_fixed_home),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("exchange-expectation"),
        )
        Text(
            text = stringResource(R.string.exchange_disclosure_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("exchange-disclosure-expand")
                .semantics { stateDescription = if (expanded) expandedStateText else collapsedStateText },
        ) {
            Text(
                stringResource(
                    if (expanded) R.string.exchange_disclosure_collapse else R.string.exchange_disclosure_expand,
                ),
            )
        }
        if (expanded) {
            Text(
                text = state.packageText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("exchange-disclosure-package"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { holder.startTransport { clipboardTransport(context, state.packageText) } },
                enabled = state.transportAllowed,
                modifier = Modifier.testTag("exchange-send-clipboard"),
            ) {
                Text(stringResource(R.string.exchange_copy))
            }
            FilledTonalButton(
                onClick = { holder.startTransport { shareTransport(context, state.packageText) } },
                enabled = state.transportAllowed,
                modifier = Modifier.testTag("exchange-send-share"),
            ) {
                Text(stringResource(R.string.exchange_share))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { if (state.transportAllowed) fileSaver.launch("nunu-launcher-exchange.txt") },
                enabled = state.transportAllowed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("exchange-send-file"),
            ) {
                Text(stringResource(R.string.exchange_save_file))
            }
            // Issue #372 (implementation review): during `cancelling` the
            // invalidate is still in flight — the close slot disables, so the
            // face cannot be left (nor the flow closed) before the settle;
            // this mirrors the Back contract's BLOCKED action.
            OutlinedButton(
                onClick = { if (state.cancelable) onDiscardRequest() else holder.closeDisclosure() },
                enabled = when {
                    state.cancelable -> true
                    state.cancelling -> false
                    state.sent -> true
                    else -> !state.transportInFlight
                },
                // Issue #372: the host owns the dialog-dismiss focus
                // restoration through this requester (deterministic restore).
                modifier = Modifier
                    .weight(1f)
                    .then(discardFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .testTag("exchange-discard"),
            ) {
                Text(
                    stringResource(
                        if (state.cancelable) R.string.exchange_discard else R.string.exchange_close,
                    ),
                )
            }
        }
    }
}

/**
 * Issue #332 (spec D-1/D-2/D-4): the clipboard/file-first import surface.
 * The primary actions read the AI reply from the clipboard or a file and run
 * the common import path in one operation; the manual paste editor is the
 * collapsed fallback ("詳細 / うまく読み込めない場合"). The editor is height
 * bounded (D-4 provisional values below; to be fixed from 200% font /
 * TalkBack evidence, AC-8/AC-9) and scrolls internally, so a tens-of-KB reply
 * never stretches the hosting screen.
 */
@Composable
private fun ExchangeImportField(
    replyText: String,
    holder: ExchangeFlowStateHolder,
    fileTransport: FileExchangeTransport,
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) holder.importFromFile(context, fileTransport, uri)
    }
    // Issue #332: the explicit clipboard read transport; constructed per
    // composition, read only inside the button's synchronous call (D-7).
    val clipboardReader = remember { ClipboardImportTransport(context) }
    // D-2 (a): the fallback editor is collapsed until requested (or until an
    // input already exists, e.g. a run-busy restore).
    var manualOpen by remember { mutableStateOf(replyText.isNotEmpty()) }
    // Issue #328 (review): a clipboard/file receipt that was refused by the
    // arbiter gate must stay retryable from the SAME held text — open the
    // editor whenever the text transitions in, so the import CTA is visible
    // without re-reading the source. An explicit user collapse is preserved
    // for already-non-empty text.
    var lastSeenReplyText by remember { mutableStateOf(replyText) }
    if (replyText != lastSeenReplyText) {
        if (lastSeenReplyText.isEmpty() && replyText.isNotEmpty()) {
            manualOpen = true
        }
        lastSeenReplyText = replyText
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_import_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-import-title"),
        )
        Button(
            onClick = { holder.importFromClipboard(clipboardReader) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("exchange-import-clipboard"),
        ) {
            Text(stringResource(R.string.exchange_import_from_clipboard))
        }
        OutlinedButton(
            onClick = {
                // D-3 accepted types: plain text + JSON; the bound and the
                // types are stated next to the action (spec file scenario).
                filePicker.launch(arrayOf("text/plain", "application/json"))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("exchange-import-file"),
        ) {
            Text(stringResource(R.string.exchange_import_from_file))
        }
        Text(
            text = stringResource(R.string.exchange_import_file_types),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { manualOpen = !manualOpen },
            modifier = Modifier.testTag("exchange-import-fallback-toggle"),
        ) {
            Text(stringResource(R.string.exchange_import_fallback_toggle))
        }
        if (manualOpen) {
            OutlinedTextField(
                value = replyText,
                onValueChange = holder::onImportTextChange,
                label = { Text(stringResource(R.string.exchange_import_hint)) },
                minLines = 4,
                maxLines = IMPORT_EDITOR_MAX_LINES,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .heightIn(max = IMPORT_EDITOR_MAX_HEIGHT)
                    .testTag("exchange-import-field"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { holder.import(replyText) },
                    enabled = replyText.isNotBlank(),
                    modifier = Modifier.testTag("exchange-import-action"),
                ) {
                    Text(stringResource(R.string.exchange_import_action))
                }
                OutlinedButton(
                    onClick = { holder.onImportTextChange("") },
                    enabled = replyText.isNotEmpty(),
                    modifier = Modifier.testTag("exchange-import-clear"),
                ) {
                    Text(stringResource(R.string.exchange_import_clear))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            OutlinedButton(onClick = holder::close) {
                Text(stringResource(R.string.exchange_cancel))
            }
        }
    }
}

/**
 * D-4 provisional editor bounds (spec: to be fixed from 200% font / TalkBack
 * evidence in the AC-8/AC-9 evidence pass; parameterized until then).
 */
private val IMPORT_EDITOR_MAX_HEIGHT = 200.dp

private const val IMPORT_EDITOR_MAX_LINES = 8

/**
 * Issue #328 (spec 328 rev.2 D-13 / #374): the system-Back interception for
 * the import success state. It MUST be composed at the always-composed
 * hosting screen level, AFTER the screen-level navigation handler — never
 * inside the success lazy item, whose composition can leave the viewport
 * under large font. When enabled (success state shown) it takes Back before
 * the host fallback: a non-continuing Back asks for the explicit discard
 * confirmation; a continuing Back is swallowed (a started run-connection
 * seam cannot be withdrawn mid-flight).
 *
 * [onDiscardRequest] routes Back into the HOST's shared import-discard
 * confirmation — the same dialog state the 破棄して閉じる button raises
 * (spec 328 rev.2 D-13: both entries confirm exactly once). When null (hosts
 * that only need the Back contract), the confirmation is hosted here.
 */
@Composable
fun ExchangeImportSuccessBackHandler(
    holder: ExchangeFlowStateHolder,
    onDiscardRequest: (() -> Unit)? = null,
) {
    val importSuccessState = holder.screen as? ExchangeScreen.ImportSuccess
    var showDiscardConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = importSuccessState != null) {
        if (importSuccessState?.continuing != true) {
            val request = onDiscardRequest
            if (request != null) {
                request()
            } else {
                showDiscardConfirm = true
            }
        }
    }
    if (onDiscardRequest == null && showDiscardConfirm && importSuccessState != null) {
        ExchangeImportDiscardConfirmDialog(
            onConfirm = {
                showDiscardConfirm = false
                holder.discardImport()
            },
            onDismiss = { showDiscardConfirm = false },
        )
    }
}

/**
 * Issue #374 (spec 328 rev.2 D-13): the ONE import-discard confirmation,
 * shared by the 砄棄して閉じる button and system Back. Confirm runs the
 * holder's [ExchangeFlowStateHolder.discardImport] (the durable tombstone
 * two-phase commit — the face closes only after the commit succeeds);
 * dismiss keeps the success state and the pending proposal. No timeout
 * auto-confirm/cancel (organization-run-ux §6). Focus ownership is
 * deterministic: the SAFE action (dismiss / keep) takes focus when the dialog
 * opens, and the CALLER restores focus to the face's 破棄 action on dismissal
 * via its own FocusRequester (explicit restoration — platform dialog focus
 * restore is not deterministic).
 */
@Composable
fun ExchangeImportDiscardConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val safeActionFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.exchange_import_discard_confirm_title))
        },
        text = {
            Text(
                text = stringResource(R.string.exchange_import_discard_confirm_body),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.exchange_import_discard_confirm_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(safeActionFocus),
            ) {
                Text(stringResource(R.string.exchange_cancel))
            }
        },
    )
    LaunchedEffect(Unit) { safeActionFocus.requestFocus() }
}

/**
 * Issue #372 (EX-AC-11): what system Back does on an exchange face. The
 * accepted TO-BE §5.3/§9 contract ("Back always returns to the previous face;
 * work-destroying Back confirms once") made structural for the request faces:
 * the T-15 faces close zero-write, the unsent T-16 goes through the discard
 * confirmation, a sent T-16 closes with the request surviving, and a busy face
 * (generating / in-flight transport / cancelling) is CONSUMED — leaving it
 * would dispose the composition and cancel the holder's `rememberCoroutineScope`
 * operations (generation, file write, invalidate), contradicting the
 * "operations continue and settle" contract. Import faces stay on the current
 * contracts (spec 328/332, #373 owns their rework).
 */
enum class ExchangeBackAction { CLOSE, REQUEST_DISCARD, BLOCKED, NONE }

internal fun exchangeBackAction(screen: ExchangeScreen): ExchangeBackAction = when (screen) {
    ExchangeScreen.Closed,
    is ExchangeScreen.Importing,
    is ExchangeScreen.ImportOutcomeScreen,
    is ExchangeScreen.ImportSuccess,
    -> ExchangeBackAction.NONE

    // Issue #374 (spec 374 DI-AC-13): Back on the persistence-failure face is
    // the zero-write 中断 — the record was never saved, so there is no
    // durable proposal to lose and no confirmation is needed (D-13 §9; the
    // face body already states the re-import recovery path).
    is ExchangeScreen.ImportPersistenceFailure -> ExchangeBackAction.CLOSE

    // Issue #374 (spec 374 "ImportReview再開面"): Back on the resume face is
    // the PLAIN zero-write close — closing keeps the durable record (opening
    // the face discards nothing), so no D-13 confirmation. The flow-level
    // handler (always composed at the hosting level) owns it.
    is ExchangeScreen.ImportReview -> ExchangeBackAction.CLOSE

    ExchangeScreen.Generating -> ExchangeBackAction.BLOCKED

    // Issue #371 integration: the usage-access JIT pause is a generation
    // pending resume — Back is consumed (the dialog's own Back, when
    // presented, is the #371 「続行」 affordance and never reaches here).
    is ExchangeScreen.AwaitingUsageAccessJit -> ExchangeBackAction.BLOCKED

    is ExchangeScreen.SelectingPrivacy, is ExchangeScreen.ReplacementConfirm -> ExchangeBackAction.CLOSE

    is ExchangeScreen.Disclosing -> when {
        screen.state.cancelable -> ExchangeBackAction.REQUEST_DISCARD
        screen.state.sent -> ExchangeBackAction.CLOSE
        else -> ExchangeBackAction.BLOCKED
    }
}

/**
 * Issue #372 (EX-AC-11): the always-composed host-level Back handler for the
 * request faces — the same placement principle as
 * [ExchangeImportSuccessBackHandler] (never inside a lazy item, whose
 * composition can leave the viewport under large font). Composed BEFORE the
 * import-success handler in the host, so the success state keeps Back
 * priority; composed AFTER the screen-level D-13 gate, so an open flow takes
 * Back before the dismiss/navigate fallback. [onDiscardRequest] lets the host
 * converge the Back path and the T-16 破棄 button on ONE confirmation dialog.
 */
@Composable
fun ExchangeFlowBackHandler(holder: ExchangeFlowStateHolder, onDiscardRequest: () -> Unit) {
    BackHandler(enabled = exchangeBackAction(holder.screen) != ExchangeBackAction.NONE) {
        when (exchangeBackAction(holder.screen)) {
            ExchangeBackAction.CLOSE -> holder.close()
            ExchangeBackAction.REQUEST_DISCARD -> onDiscardRequest()
            ExchangeBackAction.BLOCKED, ExchangeBackAction.NONE -> Unit
        }
    }
}

/**
 * Issue #372 (D-13): the ONE pre-send discard confirmation, shared by the
 * T-16 破棄 button and system Back. Confirm invalidates exactly the unsent
 * session (through the holder's existing `closeDisclosure` structural gate);
 * dismiss keeps the T-16 face. No timeout auto-confirm/cancel (organization-
 * run-ux §6). Focus ownership is deterministic: the SAFE action (dismiss /
 * keep) takes focus when the dialog opens, and the CALLER restores focus to
 * the face's 破棄 action on dismissal via its own FocusRequester (explicit
 * restoration — platform dialog focus restore is not deterministic).
 */
@Composable
fun ExchangeDiscardConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val safeActionFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.exchange_discard_confirm_title),
                modifier = Modifier.testTag("exchange-discard-confirm-title"),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.exchange_discard_confirm_body),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("exchange-discard-confirm"),
            ) {
                Text(stringResource(R.string.exchange_discard_confirm_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .focusRequester(safeActionFocus)
                    .testTag("exchange-discard-dismiss"),
            ) {
                Text(stringResource(R.string.exchange_cancel))
            }
        },
    )
    LaunchedEffect(Unit) { safeActionFocus.requestFocus() }
}

/**
 * Issue #372 (T-16, D-10): the informed-consent summary derived from the
 * generated immutable session — the exported ITEM count (labelled as the item
 * count, not a total record count; the tier line already explains the
 * category collection), the tier, and the V1 content limits as the contract
 * ceiling. Live state is never re-read here (AC-12 identity: the summary
 * describes exactly the value the transports hand out).
 */
data class ExchangeDisclosureSummary(val itemCount: Int, val tier: PrivacyTier)

internal fun exchangeDisclosureSummary(state: ExchangeDisclosureState): ExchangeDisclosureSummary = ExchangeDisclosureSummary(
    itemCount = state.session.itemRefs.size,
    tier = state.tier,
)

/** The T-15 remaining-time display root (issue #372). Pure and unit-tested. */
internal sealed interface RequestRemaining {
    data class Hours(val count: Int) : RequestRemaining
    data object UnderOneHour : RequestRemaining
}

internal fun requestRemainingDisplay(expiresAtEpochMs: Long, nowMs: Long): RequestRemaining {
    val remainingMs = expiresAtEpochMs - nowMs
    if (remainingMs <= 0L) return RequestRemaining.UnderOneHour
    val hours = (remainingMs / MILLIS_PER_HOUR).toInt()
    return if (hours >= 1) RequestRemaining.Hours(hours) else RequestRemaining.UnderOneHour
}

internal const val MILLIS_PER_HOUR = 60L * 60L * 1000L

/**
 * Issue #328: the import success state (spec 328 "取り込み成功状態"). Shows
 * what was imported (privacy-safe counts only — no labels/refs/free text),
 * states that nothing has been applied to the home screen yet, and offers
 * the explicit continuation CTA plus a discard entry point labelled as a
 * discard. Success and success-with-no-judgment differ in the heading text
 * and the live-region announcement (semantics, never color alone).
 */
@Composable
private fun ExchangeImportSuccess(
    state: ExchangeScreen.ImportSuccess,
    onContinue: () -> Unit,
    onDiscard: () -> Unit,
    discardFocus: FocusRequester? = null,
) {
    val summary = state.summary
    val warning = summary.noJudgmentCount > 0
    // Spec 328 Accessibility: the arrival moves focus to the state heading
    // (existing FocusTargetText pattern) in addition to the live region.
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(state.attemptToken) {
        runCatching { headingFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("exchange-import-success"),
    ) {
        Text(
            text = stringResource(
                if (warning) R.string.exchange_import_success_warning_title else R.string.exchange_import_success_title,
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .focusRequester(headingFocus)
                .focusable()
                .testTag("exchange-import-success-title"),
        )
        ExchangeImportSummaryContent(
            summary = summary,
            showScopeCandidates = state.entryKind == ExchangeImportEntryKind.RUN_IN && summary.scopeCandidateCount > 0,
        )
        Button(
            onClick = onContinue,
            enabled = !state.continuing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("exchange-import-continue"),
        ) {
            Text(
                stringResource(
                    if (state.entryKind == ExchangeImportEntryKind.RUN_IN) {
                        R.string.exchange_import_cta_run_in
                    } else {
                        R.string.exchange_import_cta_idle
                    },
                ),
            )
        }
        OutlinedButton(
            onClick = onDiscard,
            enabled = !state.continuing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                // Issue #374: the host restores focus here after the shared
                // import-discard confirmation is dismissed (deterministic
                // restore, mirroring the T-16 破棄 slot).
                .then(discardFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                .testTag("exchange-import-discard"),
        ) {
            Text(stringResource(R.string.exchange_import_discard))
        }
    }
}

/**
 * Issue #374: the privacy-safe summary block shared verbatim by the 取り込み
 * 成功状態 ([ExchangeImportSuccess], spec 328 AC-4) and the ImportReview
 * resume face ([ExchangeImportReview]) — recognized count, the no-judgment
 * total, the four-kind breakdown plus proposed groups, the run-in scope count
 * (caller-gated), the whole-policy line, and the not-yet-applied statement.
 * Extracted from the success face unchanged (same styles, same test tags);
 * the two faces are mutually exclusive states of one flow, so the shared tags
 * identify exactly the face that is showing.
 */
@Composable
private fun ExchangeImportSummaryContent(
    summary: ExchangeImportSummary,
    showScopeCandidates: Boolean,
) {
    // Single top-level emitter (compose lint); a plain zero-spacing Column
    // keeps the extracted block visually identical to its inline original.
    Column {
        val warning = summary.noJudgmentCount > 0
        Text(
            text = pluralStringResource(
                R.plurals.exchange_import_summary_recognized,
                summary.recognizedCount,
                summary.recognizedCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("exchange-import-summary-recognized"),
        )
        if (warning) {
            Text(
                text = pluralStringResource(
                    R.plurals.exchange_import_summary_no_judgment,
                    summary.noJudgmentCount,
                    summary.noJudgmentCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("exchange-import-summary-no-judgment"),
            )
        }
        if (showScopeCandidates) {
            Text(
                text = pluralStringResource(
                    R.plurals.exchange_import_summary_scope_candidates,
                    summary.scopeCandidateCount,
                    summary.scopeCandidateCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("exchange-import-summary-scope"),
            )
        }
        val breakdown = listOf(
            Triple("exchange-import-summary-priority", R.plurals.exchange_import_summary_priority, summary.priorityCount),
            Triple("exchange-import-summary-group", R.plurals.exchange_import_summary_group, summary.groupCount),
            // Issue #337 (spec 337 AC-10): the grouping breakdown distinguishes
            // an existing category (built-in or persisted user-defined) from a
            // run-scoped proposal, which nothing saves.
            Triple(
                "exchange-import-summary-existing-category",
                R.plurals.exchange_import_summary_existing_category,
                summary.builtInCategoryCount + summary.userCategoryCount,
            ),
            Triple(
                "exchange-import-summary-proposed-group",
                R.plurals.exchange_import_summary_proposed_group,
                summary.proposedGroupCount,
            ),
            Triple("exchange-import-summary-placement", R.plurals.exchange_import_summary_placement, summary.placementCount),
            Triple("exchange-import-summary-keep", R.plurals.exchange_import_summary_keep, summary.keepCount),
        )
        for ((tag, res, count) in breakdown) {
            if (count > 0) {
                Text(
                    text = pluralStringResource(res, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(tag),
                )
            }
        }
        if (summary.minimizeMovement) {
            Text(
                text = stringResource(R.string.exchange_import_summary_global_minimize),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("exchange-import-summary-global"),
            )
        }
        Text(
            text = stringResource(R.string.exchange_import_not_applied),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("exchange-import-not-applied"),
        )
    }
}

/**
 * Issue #374 (spec 374 DI-AC-01 "ImportReview再開面", cold process到達範囲):
 * the resume face of an imported proposal — the SAME privacy-safe summary as
 * the success face (reconstructed from the durable record plus the session),
 * the remaining time of the underlying request (the session's expiry — the
 * same vocabulary and derivation as T-15: `requestRemainingDisplay` over the
 * same plurals, no ticking clock), and the D-13 破棄して閉じる routed through
 * the host's ONE import-discard confirmation. **NO continuation CTA** — not
 * even a disabled or placeholder one (#375 owns the rebind; the #366
 * capability-先取り禁止 principle). System Back is the plain zero-write
 * close ([exchangeBackAction] → CLOSE): closing keeps the record.
 */
@Composable
private fun ExchangeImportReview(
    state: ExchangeScreen.ImportReview,
    onContinue: () -> Unit,
    onDiscard: () -> Unit,
    discardFocus: FocusRequester? = null,
) {
    val summary = state.summary
    val warning = summary.noJudgmentCount > 0
    // The arrival moves focus to the face heading (the success face's
    // FocusTargetText pattern) in addition to the live-region announcement.
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { headingFocus.requestFocus() }
    }
    val remaining = requestRemainingDisplay(state.expiresAtEpochMs, state.readAtEpochMs)
    // Issue #375: the face grew (summary + remaining + CTA + discard) and can
    // exceed the viewport at large font scales — the internal scroll keeps
    // every action reachable (spec SR-AC-10 a11y) and lets tests scroll the
    // CTA into view.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("exchange-import-review"),
    ) {
        Text(
            text = stringResource(
                if (warning) R.string.exchange_import_success_warning_title else R.string.exchange_import_success_title,
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .focusRequester(headingFocus)
                .focusable()
                .testTag("exchange-import-review-title"),
        )
        ExchangeImportSummaryContent(
            summary = summary,
            showScopeCandidates = state.entryKind == ExchangeImportEntryKind.RUN_IN && summary.scopeCandidateCount > 0,
        )
        Text(
            text = when (remaining) {
                is RequestRemaining.Hours -> pluralStringResource(R.plurals.exchange_request_remaining_hours, remaining.count, remaining.count)
                RequestRemaining.UnderOneHour -> stringResource(R.string.exchange_request_remaining_under_hour)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("exchange-import-review-remaining"),
        )
        // Issue #375 (spec "再開面CTAの有効化"): the rebind continuation —
        // TO-BE T-18 vocabulary (spec 328 rev.2 D-3 unified copy). Shown for
        // every reconcile-passed proposal (the #374 open gate guarantees it);
        // single-flight via `continuing` (the press flips it synchronously and
        // discard/Back are refused while set).
        Button(
            onClick = onContinue,
            enabled = !state.continuing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("exchange-import-review-continue"),
        ) {
            Text(stringResource(R.string.exchange_import_continue))
        }
        OutlinedButton(
            onClick = onDiscard,
            enabled = !state.continuing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                // Issue #374: the host restores focus here after the shared
                // import-discard confirmation is dismissed (deterministic
                // restore, mirroring the success face's 破棄 slot).
                .then(discardFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                .testTag("exchange-import-review-discard"),
        ) {
            Text(stringResource(R.string.exchange_import_discard))
        }
    }
}

/**
 * Issue #374 (spec 374 DI-AC-13, #373 D-11 projection style): the typed
 * failure face of the durable persistence step. Validation already passed —
 * this is not one of the 20 validation classes — so the primary remedy is
 * ONE action (save again, retrying only the store save of the anchored
 * record); the face-level means are always present: 中断する closes without
 * saving (nothing durable exists, so no confirmation — D-13 §9) and 診断を開く
 * walks the existing diagnostics route where one exists. The body states the
 * interruption consequence and the re-import recovery path.
 */
@Composable
private fun ExchangeImportPersistenceFailure(
    state: ExchangeScreen.ImportPersistenceFailure,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
) {
    // The arrival moves focus to the face heading (FocusTargetText pattern)
    // in addition to the live-region announcement.
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(state.attemptToken) {
        runCatching { headingFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("exchange-import-persist-failed"),
    ) {
        Text(
            text = stringResource(R.string.exchange_import_persist_failed_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .focusRequester(headingFocus)
                .focusable()
                .testTag("exchange-import-persist-failed-title"),
        )
        Text(
            text = stringResource(R.string.exchange_import_persist_failed_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("exchange-import-persist-failed-body"),
        )
        Button(
            onClick = onRetry,
            enabled = !state.retrying,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("exchange-import-persist-retry"),
        ) {
            Text(stringResource(R.string.exchange_import_persist_retry))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onInterrupt,
                modifier = Modifier.testTag("exchange-import-persist-interrupt"),
            ) {
                Text(stringResource(R.string.exchange_import_interrupt))
            }
            if (onOpenDiagnostics != null) {
                TextButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.testTag("exchange-import-persist-open-diagnostics"),
                ) {
                    Text(stringResource(R.string.exchange_import_open_diagnostics))
                }
            }
        }
    }
}

@Composable
private fun ExchangeImportOutcome(
    outcome: ExchangeImportOutcome,
    rawText: String,
    holder: ExchangeFlowStateHolder,
    onOpenDiagnostics: (() -> Unit)?,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_import_result_title),
            style = MaterialTheme.typography.titleMedium,
        )
        // Issue #373 (TO-BE D-11): the primary face carries only the remedy
        // projection — one remedy copy and one action. The typed cause (the
        // classification name and the inherited typed copy), the recognition
        // facts and the raw text are auxiliary information, shown only inside
        // the collapsed detail expansion below.
        val pipeline = (outcome as? ExchangeImportOutcome.Pipeline)?.result
        val display = (pipeline as? ExchangeImportResult.Failure)?.failure
            ?.let { exchangeImportFailureDisplay(it) }
        val primaryText = when {
            display != null -> stringResource(display.primaryTextRes)

            outcome is ExchangeImportOutcome.InputNotReady ->
                stringResource(R.string.exchange_generation_input_not_ready)

            else -> stringResource(R.string.exchange_import_result_unknown)
        }
        // InputNotReady / unknown have no typed classification — the remedy is
        // re-running the import itself (the former 再取り込み path).
        val actionRes = when (display) {
            null -> R.string.exchange_import_failure_action_reimport
            else -> display.primaryActionLabelRes
        }
        Text(
            text = primaryText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("exchange-import-outcome-message"),
        )
        OutlinedButton(
            onClick = {
                if (display?.remedy == ImportFailureRemedy.RECREATE_REQUEST) {
                    // 依頼を作り直す: the existing generation-flow seam — the
                    // replacement gate (spec 205 AC-13) still applies at the
                    // request face; this action writes nothing (zero-write).
                    holder.openFlow()
                } else {
                    // もう一度取り込む / 貼り直す: back to the import input;
                    // the raw text is discarded here (spec 332 AC-7 boundary).
                    holder.openImport()
                }
            },
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("exchange-import-failure-action"),
        ) {
            Text(stringResource(actionRes))
        }

        // Face-level means (spec #373): typed-failure-independent remedies,
        // always present on the failure face. 中断する closes zero-write —
        // nothing can be lost, so no confirmation (D-13 §9); the request
        // (export session) survives. 診断を開く walks the existing diagnostics
        // route after recording the current attempt's typed cause into the
        // process-scoped transient holder (never persisted).
        val detailExplanation = display?.let { stringResource(it.detailTextRes) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = holder::close,
                modifier = Modifier.testTag("exchange-import-interrupt"),
            ) {
                Text(stringResource(R.string.exchange_import_interrupt))
            }
            if (onOpenDiagnostics != null) {
                TextButton(
                    onClick = {
                        // Record the CURRENT attempt's typed cause; an attempt
                        // without one (InputNotReady / unknown) empties the
                        // recording instead — a previous attempt's cause is
                        // never presented as the current failure (issue #373
                        // implementation review).
                        display?.let {
                            ExchangeImportFailureDiagnostics.record(
                                RecentImportFailure(
                                    typeName = it.detailTypeName,
                                    explanation = detailExplanation ?: "",
                                ),
                            )
                        } ?: ExchangeImportFailureDiagnostics.clear()
                        onOpenDiagnostics()
                    },
                    modifier = Modifier.testTag("exchange-import-open-diagnostics"),
                ) {
                    Text(stringResource(R.string.exchange_import_open_diagnostics))
                }
            }
        }

        val info = when (outcome) {
            is ExchangeImportOutcome.Pipeline -> exchangeImportDisplayInfo(outcome.result)
            is ExchangeImportOutcome.InputNotReady -> exchangeImportDisplayInfo(outcome.recognized)
        }
        val hasDetail = display != null ||
            info.framing != null ||
            info.intentSchemaVersion != null ||
            info.authoredEntryCount != null ||
            rawText.isNotEmpty()
        if (hasDetail) {
            var detailOpen by remember { mutableStateOf(false) }
            val detailStateText = stringResource(
                if (detailOpen) {
                    R.string.exchange_import_failure_detail_hide
                } else {
                    R.string.exchange_import_failure_detail_show
                },
            )
            TextButton(
                onClick = { detailOpen = !detailOpen },
                modifier = Modifier
                    .semantics { stateDescription = detailStateText }
                    .testTag("exchange-import-detail-toggle"),
            ) {
                Text(
                    stringResource(
                        if (detailOpen) {
                            R.string.exchange_import_failure_detail_hide
                        } else {
                            R.string.exchange_import_failure_detail_show
                        },
                    ),
                )
            }
            if (detailOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("exchange-import-failure-detail"),
                ) {
                    display?.let { d ->
                        Text(
                            text = stringResource(
                                R.string.exchange_import_failure_detail_type_format,
                                d.detailTypeName,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("exchange-import-detail-type"),
                        )
                        Text(
                            text = stringResource(d.detailTextRes),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("exchange-import-detail-explanation"),
                        )
                    }
                    if (info.framing != null) {
                        ExchangeImportInfoRow(
                            label = stringResource(R.string.exchange_recognized_framing),
                            value = exchangeFramingText(info.framing),
                            tag = "exchange-import-outcome-framing",
                        )
                    }
                    if (info.intentSchemaVersion != null) {
                        ExchangeImportInfoRow(
                            label = stringResource(R.string.exchange_recognized_version),
                            value = info.intentSchemaVersion,
                            tag = "exchange-import-outcome-version",
                        )
                    }
                    if (info.authoredEntryCount != null) {
                        ExchangeImportInfoRow(
                            label = stringResource(R.string.exchange_recognized_entries),
                            value = info.authoredEntryCount.toString(),
                            tag = "exchange-import-outcome-entries",
                        )
                    }
                    if (rawText.isNotEmpty()) {
                        Text(
                            text = rawText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("exchange-import-raw-detail"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExchangeImportInfoRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun exchangeFramingText(framing: RecognizedImportFraming): String = when (framing) {
    RecognizedImportFraming.MARKER -> stringResource(R.string.exchange_framing_marker)
    RecognizedImportFraming.FENCED_JSON -> stringResource(R.string.exchange_framing_fenced_json)
    RecognizedImportFraming.STANDALONE_JSON -> stringResource(R.string.exchange_framing_standalone_json)
}

/**
 * Issue #332 (spec D-5): pure projection from a pipeline result to the
 * parse-first display model. The UI never derives recognition facts from the
 * text itself — a null field simply means the failure settled before that
 * stage (oversize/pre-recognition → nothing; framing failure → framing only;
 * decode failure → framing only; post-decode failures → all three).
 */
data class ExchangeImportDisplayInfo(
    val framing: RecognizedImportFraming? = null,
    val intentSchemaVersion: String? = null,
    val authoredEntryCount: Int? = null,
)

fun exchangeImportDisplayInfo(recognized: RecognizedImportInfo?): ExchangeImportDisplayInfo = if (recognized == null) {
    ExchangeImportDisplayInfo()
} else {
    ExchangeImportDisplayInfo(
        framing = recognized.framing,
        intentSchemaVersion = recognized.intentSchemaVersion,
        authoredEntryCount = recognized.authoredEntryCount,
    )
}

fun exchangeImportDisplayInfo(result: ExchangeImportResult?): ExchangeImportDisplayInfo = exchangeImportDisplayInfo((result as? ExchangeImportResult.Failure)?.recognized)

/**
 * Pure status-kind → string resource mapping (issue #332 AC-6 regression
 * guard): the import-source failures resolve to their OWN guidance strings —
 * in particular [ExchangeStatus.Kind.FILE_READ_FAILED] resolves to the
 * dedicated read-failure guidance, never back to the export-side
 * `exchange_transport_file_failed` copy. Unit tested in
 * `ExchangeFlowStateHolderTest`.
 */
fun exchangeStatusTextResource(kind: ExchangeStatus.Kind): Int = when (kind) {
    ExchangeStatus.Kind.TRANSPORT_SUCCESS -> R.string.exchange_transport_success

    ExchangeStatus.Kind.TRANSPORT_CLIPBOARD_FAILED -> R.string.exchange_transport_clipboard_failed

    ExchangeStatus.Kind.TRANSPORT_SHARE_ABSENT -> R.string.exchange_transport_share_absent

    ExchangeStatus.Kind.TRANSPORT_FILE_FAILED -> R.string.exchange_transport_file_failed

    ExchangeStatus.Kind.FILE_READ_FAILED -> R.string.exchange_status_file_read_failed

    ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY -> R.string.exchange_generation_input_not_ready

    ExchangeStatus.Kind.GENERATION_STORE_FAILURE -> R.string.exchange_generation_store_failure

    ExchangeStatus.Kind.GENERATION_OVERSIZE -> R.string.exchange_generation_oversize

    ExchangeStatus.Kind.INPUT_OVERSIZE -> R.string.exchange_failure_input_oversize

    ExchangeStatus.Kind.RUN_BUSY -> R.string.exchange_run_busy

    ExchangeStatus.Kind.CLIPBOARD_EMPTY -> R.string.exchange_status_clipboard_empty

    ExchangeStatus.Kind.CLIPBOARD_NOT_TEXT -> R.string.exchange_status_clipboard_not_text

    ExchangeStatus.Kind.CTA_START_FAILED -> R.string.exchange_import_cta_failed

    ExchangeStatus.Kind.IMPORT_DISCARDED -> R.string.exchange_import_discarded_guidance

    ExchangeStatus.Kind.IMPORT_PERSIST_FAILED -> R.string.exchange_import_persist_failed_title

    ExchangeStatus.Kind.IMPORT_DISCARD_FAILED -> R.string.exchange_import_discard_failed

    ExchangeStatus.Kind.IMPORT_REVIEW_UNAVAILABLE -> R.string.exchange_import_review_unavailable

    // Issue #375: rebind typed notices (spec SR-AC-07/08).
    ExchangeStatus.Kind.REBIND_CONTEXT_STALE -> R.string.exchange_rebind_context_stale

    ExchangeStatus.Kind.REBIND_ANCHOR_REFUSED -> R.string.exchange_rebind_anchor_refused

    ExchangeStatus.Kind.REBIND_START_FAILED -> R.string.exchange_rebind_start_failed
}

@Composable
private fun exchangeStatusText(kind: ExchangeStatus.Kind): String = stringResource(exchangeStatusTextResource(kind))

/**
 * The 14-class #204 contract failure mapping (spec 204 + spec 331 D-5 + spec
 * 337 D-8). Since issue #373 the T-18 failure face goes through the
 * remedy-projection table ([exchangeImportFailureDisplay]) instead — this
 * mapping remains for its two run-side call sites (the selection surface's
 * `scopeRejection` row and the `ScopeMismatchFailed` state, issues
 * #369/#375) and the exhaustive `when` keeps its compile-time guarantee that
 * every contract class reaches a failure UI.
 */
@Composable
fun exchangeContractFailureText(failure: IntentValidationFailure): String = when (failure) {
    IntentValidationFailure.SchemaMismatch -> stringResource(R.string.exchange_failure_schema_mismatch)

    IntentValidationFailure.ExportMismatch -> stringResource(R.string.exchange_failure_export_mismatch)

    IntentValidationFailure.SessionExpired -> stringResource(R.string.exchange_failure_session_expired)

    IntentValidationFailure.ContextStale -> stringResource(R.string.exchange_failure_context_stale)

    IntentValidationFailure.Oversize -> stringResource(R.string.exchange_failure_oversize)

    is IntentValidationFailure.UnknownRef -> stringResource(R.string.exchange_failure_unknown_ref)

    IntentValidationFailure.DuplicateRef -> stringResource(R.string.exchange_failure_duplicate_ref)

    IntentValidationFailure.IncompleteCoverage -> stringResource(R.string.exchange_failure_incomplete_coverage)

    IntentValidationFailure.InvalidEnum -> stringResource(R.string.exchange_failure_invalid_enum)

    IntentValidationFailure.ForbiddenContent -> stringResource(R.string.exchange_failure_forbidden_content)

    is IntentValidationFailure.MobilityContradiction -> stringResource(R.string.exchange_failure_mobility_contradiction)

    IntentValidationFailure.CapabilityUnsupported -> stringResource(R.string.exchange_failure_capability_unsupported)

    // Issue #331 (17th outcome): the scope binding gate's typed rejection.
    is IntentValidationFailure.ScopeMismatch -> when (failure.cause) {
        // Issue #375 (spec SR-AC-01): a set mismatch is fixable on this very
        // surface — the same proposal continues once the selection matches.
        app.lawnchair.organizer.personalization.ScopeMismatchCause.SET_MISMATCH ->
            stringResource(R.string.exchange_scope_set_mismatch)

        // Issue #375 (spec SR-AC-02): unresolvable/projection drift cannot be
        // fixed by editing the selection — the only remedy is a new request.
        app.lawnchair.organizer.personalization.ScopeMismatchCause.CANDIDATE_UNRESOLVED ->
            stringResource(R.string.exchange_scope_unresolvable_mismatch)

        app.lawnchair.organizer.personalization.ScopeMismatchCause.PROJECTION_MISMATCH ->
            stringResource(R.string.exchange_scope_unresolvable_mismatch)
    }

    is IntentValidationFailure.UnknownCategoryRef -> stringResource(R.string.exchange_failure_unknown_category_ref)
}

/** Issue #371: gate owner identity of one generation attempt's JIT request. */
internal data class ExchangeJitAttemptOwner(val attemptToken: Long)

/**
 * Issue #374: the benign default of the holder's [PendingImportedIntentStore]
 * seam — success without persisting, so holders/fixtures that do not wire the
 * durable store keep the pre-#374 lifecycles. Production always injects the
 * real store through `PendingImportedIntentModule`.
 */
private object NoopPendingImportedIntentStore : PendingImportedIntentStore {
    override fun save(proposal: DurablePendingIntent): Boolean = true
    override fun load(): DurablePendingIntent? = null
    override fun discard(): Boolean = true
    override fun delete() = Unit
    override fun deleteIf(proposal: DurablePendingIntent): Boolean = false
    override fun discardIf(expected: DurablePendingIntent): DiscardIfResult = DiscardIfResult.Committed
}
