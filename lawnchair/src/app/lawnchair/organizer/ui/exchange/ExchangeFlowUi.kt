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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.integration.exchange.ClipboardImportRead
import app.lawnchair.organizer.integration.exchange.ClipboardImportTransport
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.integration.exchange.ExchangeTransportFailure
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeRead
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.ExchangeImportSummary
import app.lawnchair.organizer.personalization.exchange.ImportNormalizationFailure
import app.lawnchair.organizer.personalization.exchange.RecognizedImportFraming
import app.lawnchair.organizer.personalization.exchange.RecognizedImportInfo
import app.lawnchair.organizer.personalization.exchange.acceptsExchangeImportEnvelope
import app.lawnchair.organizer.personalization.exchange.exchangeImportSummary
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
) {
    private val controller: ExchangeFlowController by lazy(LazyThreadSafetyMode.NONE) { controllerFactory() }

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
        invalidateImportAttempt()
        status = null
        screen = ExchangeScreen.Importing("")
    }

    fun close() {
        expiryReReadJob?.cancel()
        invalidateImportAttempt()
        status = null
        screen = ExchangeScreen.Closed
    }

    /**
     * Issue #331: when [scoped] is set (the run-in entry), generation composes
     * the export from the frozen selection via the scope-composed canonical
     * seam instead of the idle full-organization composition.
     */
    fun requestGeneration(
        replacementConfirmationRequired: Boolean,
        tier: PrivacyTier,
        scoped: Pair<List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>, Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>>? = null,
    ) {
        if (replacementConfirmationRequired) {
            screen = ExchangeScreen.ReplacementConfirm(tier, scoped)
        } else if (scoped != null) {
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
        if (scoped != null) generateScoped(tier, scoped.first, scoped.second) else generate(tier)
    }

    /** The user declined; the existing session stays untouched and importable. */
    fun declineReplacement() {
        close()
    }

    fun generate(tier: PrivacyTier) {
        screen = ExchangeScreen.Generating
        scope.launch(Dispatchers.IO) {
            val result = controller.generate(tier)
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
        screen = ExchangeScreen.Generating
        scope.launch(Dispatchers.IO) {
            val result = controller.generateForSelection(tier, selection, candidateLabels)
            withContext(uiDispatcher) { handleGeneration(result, tier) }
        }
    }

    private fun handleGeneration(result: ExchangeGenerationResult, tier: PrivacyTier) {
        when (result) {
            is ExchangeGenerationResult.Generated ->
                screen = ExchangeScreen.Disclosing(
                    ExchangeDisclosureState(
                        session = result.session,
                        packageText = result.packageText,
                        tier = tier,
                    ),
                )

            is ExchangeGenerationResult.InputNotReady -> {
                status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY)
                screen = ExchangeScreen.SelectingPrivacy(false)
            }

            ExchangeGenerationResult.SessionStoreFailure -> {
                status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_STORE_FAILURE)
                screen = ExchangeScreen.SelectingPrivacy(false)
            }

            is ExchangeGenerationResult.EncodeFailure -> {
                status = ExchangeStatus(ExchangeStatus.Kind.GENERATION_OVERSIZE)
                screen = ExchangeScreen.SelectingPrivacy(false)
            }
        }
    }

    /**
     * Closes the disclosure. Pre-send this is the spec 205 cancel: it
     * invalidates exactly the disclosure's own (unsent) session. After a
     * successful transport the package may already have left the device, so
     * the session survives and the reply stays importable (review P1).
     */
    fun closeDisclosure() {
        // Decide and mark synchronously on Main from the holder's CURRENT
        // disclosure state (review round 3 P1): the moment a cancel is
        // accepted the disclosure enters the terminal `cancelling` state, so
        // no further transport can start or settle against it, and the
        // session invalidated below is exactly the never-sent one.
        val disclosing = (screen as? ExchangeScreen.Disclosing)?.state
        if (disclosing == null || !disclosing.cancelable) {
            close()
            return
        }
        screen = ExchangeScreen.Disclosing(disclosing.copy(cancelling = true))
        scope.launch(Dispatchers.IO) {
            controller.cancelDisclosure(disclosing.session)
            withContext(uiDispatcher) { close() }
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
            invalidateImportAttempt()
        }
        screen = ExchangeScreen.Importing(text)
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

    private fun invalidateImportAttempt() {
        activeAttempt = null
        pendingValidated = null
    }

    /**
     * Numbers a fresh import attempt from the CURRENT run state: the run-in
     * entry is the one whose owning run holds the selection surface.
     * Main-confined (called from the receipt paths and the editor action).
     */
    private fun beginImportAttempt(): ImportAttempt {
        val selecting = run.state as? ManualOrganizationRun.State.Selecting
        val attempt = ImportAttempt(
            token = ++nextAttemptToken,
            entryKind = if (selecting != null) ExchangeImportEntryKind.RUN_IN else ExchangeImportEntryKind.IDLE,
            owningRunId = selecting?.runId,
        )
        activeAttempt = attempt
        pendingValidated = null
        return attempt
    }

    /**
     * Issue #328 (spec 328): the import runs validation only. On success the
     * flow stops at the [ExchangeScreen.ImportSuccess] state — nothing is
     * connected to a run until the user presses the continuation CTA. The
     * settle is bound to the attempt token (late settles after a cancel, a
     * newer import or an input edit are dropped).
     */
    fun import(replyText: String) {
        val attempt = beginImportAttempt()
        scope.launch(Dispatchers.IO) {
            val outcome = controller.importReply(replyText)
            withContext(uiDispatcher) { settleImport(attempt, outcome, replyText) }
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
            val scopeCount = if (attempt.entryKind == ExchangeImportEntryKind.RUN_IN) {
                pipeline.validated.session.scopeCandidates.size
            } else {
                0
            }
            screen = ExchangeScreen.ImportSuccess(
                summary = exchangeImportSummary(
                    pipeline.validated.completed,
                    scopeCount,
                    // Issue #337: the advertised ref kinds of the same accepted
                    // export, so the summary can tell an existing category from
                    // a run-scoped proposal.
                    categoryKindByRef = pipeline.validated.export.categories.associate { it.ref to it.kind },
                ),
                entryKind = attempt.entryKind,
                attemptToken = attempt.token,
            )
        } else {
            activeAttempt = null
            screen = ExchangeScreen.ImportOutcomeScreen(outcome, rawText = replyText)
        }
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
            // always on IO (audit P2-1).
            when (val started = run.start(intent = request.validated)) {
                is ManualOrganizationRun.StartOutcome.Started -> ContinueOutcome.Success(started.runId)
                ManualOrganizationRun.StartOutcome.Busy -> ContinueOutcome.Busy
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
     * Issue #328 (spec 328 D-2): the explicit discard. Refused while the CTA
     * is continuing — a started seam cannot be withdrawn, so the discard side
     * is the one that yields. The export session is NOT invalidated:
     * re-importing the same reply stays possible while the request is valid.
     */
    fun discardImport() {
        val current = screenState.value as? ExchangeScreen.ImportSuccess ?: return
        if (current.continuing) return
        activeAttempt = null
        pendingValidated = null
        status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_DISCARDED)
        screen = ExchangeScreen.Closed
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
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
) {
    exchangeFlowItems(holder, null, emptyMap(), onDiscardRequest, clipboardTransport, shareTransport, fileTransport)
}

/**
 * Issue #331: the exchange items with the run-in (scope-composed) entry.
 * Hosted inside the selection surface while a run holds it; when
 * [scopedSelection] is non-null the generation composes the export from the
 * frozen selection instead of the idle full-organization scope.
 * [onDiscardRequest] converges the T-16 破棄 button and system Back on the
 * host's one discard confirmation (issue #372, D-13).
 */
fun LazyListScope.exchangeFlowItems(
    holder: ExchangeFlowStateHolder,
    scopedSelection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>?,
    scopedLabels: Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>,
    onDiscardRequest: () -> Unit,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
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
                        onGenerate = { tier -> holder.requestGeneration(current.replacementConfirmationRequired, tier, scoped) },
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
                ExchangeImportOutcome(current.outcome, current.rawText, holder)
            }
        }

        is ExchangeScreen.ImportSuccess -> {
            item(key = "exchange-import-success") {
                ExchangeImportSuccess(
                    state = current,
                    onContinue = holder::continueImport,
                    onDiscard = holder::discardImport,
                )
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onGenerate(if (labelInclusive) PrivacyTier.EXTERNAL_WITH_LABELS else PrivacyTier.EXTERNAL_REDACTED)
                },
                modifier = Modifier.testTag("exchange-generate"),
            ) {
                Text(stringResource(R.string.exchange_generate))
            }
            OutlinedButton(onClick = onOpenImport) {
                Text(stringResource(R.string.exchange_entry_import))
            }
            OutlinedButton(onClick = onCancel) {
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
                modifier = Modifier.testTag("exchange-send-file"),
            ) {
                Text(stringResource(R.string.exchange_save_file))
            }
            OutlinedButton(
                onClick = { if (state.cancelable) onDiscardRequest() else holder.closeDisclosure() },
                enabled = if (state.cancelable) true else !state.transportInFlight,
                modifier = Modifier.testTag("exchange-discard"),
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
 * Issue #328 (spec 328 D-2): the system-Back interception for the import
 * success state. It MUST be composed at the always-composed hosting screen
 * level, AFTER the screen-level navigation handler — never inside the success
 * lazy item, whose composition can leave the viewport under large font. When
 * enabled (success state shown) it takes Back before the host fallback: a
 * non-continuing Back asks for the explicit discard confirmation; a
 * continuing Back is swallowed (a started run-connection seam cannot be
 * withdrawn mid-flight).
 */
@Composable
fun ExchangeImportSuccessBackHandler(holder: ExchangeFlowStateHolder) {
    val importSuccessState = holder.screen as? ExchangeScreen.ImportSuccess
    var showDiscardConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = importSuccessState != null) {
        if (importSuccessState?.continuing != true) {
            showDiscardConfirm = true
        }
    }
    if (showDiscardConfirm && importSuccessState != null) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.exchange_import_discard_confirm_title)) },
            text = { Text(stringResource(R.string.exchange_import_discard_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        holder.discardImport()
                    },
                ) {
                    Text(stringResource(R.string.exchange_import_discard_confirm_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.exchange_cancel))
                }
            },
        )
    }
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

    ExchangeScreen.Generating -> ExchangeBackAction.BLOCKED

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
 * run-ux §6).
 */
@Composable
fun ExchangeDiscardConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.exchange_discard_confirm_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.exchange_cancel))
            }
        },
    )
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
        if (state.entryKind == ExchangeImportEntryKind.RUN_IN && summary.scopeCandidateCount > 0) {
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
                .testTag("exchange-import-discard"),
        ) {
            Text(stringResource(R.string.exchange_import_discard))
        }
    }
}

@Composable
private fun ExchangeImportOutcome(outcome: ExchangeImportOutcome, rawText: String, holder: ExchangeFlowStateHolder) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_import_result_title),
            style = MaterialTheme.typography.titleMedium,
        )
        val pipeline = (outcome as? ExchangeImportOutcome.Pipeline)?.result
        val message = when {
            pipeline is ExchangeImportResult.Failure -> exchangeFailureText(pipeline.failure)

            outcome is ExchangeImportOutcome.InputNotReady ->
                stringResource(R.string.exchange_generation_input_not_ready)

            else -> stringResource(R.string.exchange_import_result_unknown)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("exchange-import-outcome-message"),
        )
        // Issue #332 (spec D-5 parse-first presentation): the recognized
        // framing / accepted version / authored entry count lead the display;
        // the raw text stays collapsed by default (bounded, internal scroll).
        // InputNotReady settles AFTER the decode, so its recognition facts
        // display exactly like a pipeline failure's (spec D-6).
        val info = when (outcome) {
            is ExchangeImportOutcome.Pipeline -> exchangeImportDisplayInfo(outcome.result)
            is ExchangeImportOutcome.InputNotReady -> exchangeImportDisplayInfo(outcome.recognized)
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
            var rawOpen by remember { mutableStateOf(false) }
            TextButton(
                onClick = { rawOpen = !rawOpen },
                modifier = Modifier.testTag("exchange-import-raw-toggle"),
            ) {
                Text(
                    stringResource(
                        if (rawOpen) R.string.exchange_import_raw_hide else R.string.exchange_import_raw_show,
                    ),
                )
            }
            if (rawOpen) {
                Text(
                    text = rawText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("exchange-import-raw-detail"),
                )
            }
        }
        Text(
            text = stringResource(R.string.exchange_import_retry_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = holder::openImport, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.exchange_import_retry))
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
}

@Composable
private fun exchangeStatusText(kind: ExchangeStatus.Kind): String = stringResource(exchangeStatusTextResource(kind))

@Composable
private fun exchangeFailureText(failure: ExchangeImportFailure): String = when (failure) {
    is ExchangeImportFailure.Envelope -> when (failure.failure) {
        ExchangeEnvelopeFailure.InputOversize -> stringResource(R.string.exchange_failure_input_oversize)
        ExchangeEnvelopeFailure.FramingMissing -> stringResource(R.string.exchange_failure_framing_missing)
        ExchangeEnvelopeFailure.FramingAmbiguous -> stringResource(R.string.exchange_failure_framing_ambiguous)
        ExchangeEnvelopeFailure.FramingEmpty -> stringResource(R.string.exchange_failure_framing_empty)
    }

    // Spec 329: normalizer failures settle before the codec — their guidance
    // is about the recognizable import formats, not the intent content.
    is ExchangeImportFailure.Normalization -> when (failure.failure) {
        ImportNormalizationFailure.AmbiguousBlocks ->
            stringResource(R.string.exchange_failure_normalization_ambiguous)

        ImportNormalizationFailure.UnrecognizedFormat ->
            stringResource(R.string.exchange_failure_normalization_unrecognized)
    }

    is ExchangeImportFailure.Contract -> exchangeContractFailureText(failure.failure)
}

/**
 * The 14-class #204 contract failure mapping (spec 204 + spec 331 D-5 + spec
 * 337 D-8). The
 * exhaustive `when` is the compile-time guarantee that every contract class —
 * including the 17th unified outcome `SCOPE_MISMATCH`, raised by the run-side
 * scope binding gate — reaches the failure UI.
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
    is IntentValidationFailure.ScopeMismatch -> stringResource(R.string.exchange_failure_scope_mismatch)

    is IntentValidationFailure.UnknownCategoryRef -> stringResource(R.string.exchange_failure_unknown_category_ref)
}
