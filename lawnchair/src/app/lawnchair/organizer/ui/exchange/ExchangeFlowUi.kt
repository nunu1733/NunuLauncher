package app.lawnchair.organizer.ui.exchange

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.ImportNormalizationFailure
import app.lawnchair.organizer.personalization.exchange.RecognizedImportFraming
import app.lawnchair.organizer.personalization.exchange.RecognizedImportInfo
import app.lawnchair.organizer.personalization.exchange.acceptsExchangeImportEnvelope
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    data class SelectingPrivacy(val replacementConfirmationRequired: Boolean) : ExchangeScreen

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
}

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

    fun openFlow() {
        status = null
        screen = ExchangeScreen.SelectingPrivacy(replacementConfirmationRequired = controller.activeSession() != null)
    }

    fun openImport() {
        status = null
        screen = ExchangeScreen.Importing("")
    }

    fun close() {
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
        screen = ExchangeScreen.Importing(text)
    }

    fun import(replyText: String) {
        scope.launch(Dispatchers.IO) {
            val outcome = controller.importReply(replyText)
            val pipeline = (outcome as? ExchangeImportOutcome.Pipeline)?.result
            if (pipeline is ExchangeImportResult.Validated) {
                // Issue #331: the run-in entry — the run is still holding the
                // selection surface, so the validated intent attaches to THAT
                // run instead of starting a fresh one. Zero-write either way;
                // a refusal (surface gone, intent already bound) is typed.
                val selecting = run.state is ManualOrganizationRun.State.Selecting
                if (selecting) {
                    val attached = run.attachIntent(pipeline.validated)
                    withContext(uiDispatcher) {
                        if (attached == ManualOrganizationRun.AttachIntentOutcome.Attached) {
                            status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_ACCEPTED)
                            screen = ExchangeScreen.Closed
                        } else {
                            status = ExchangeStatus(ExchangeStatus.Kind.RUN_BUSY)
                            screen = ExchangeScreen.Importing(replyText)
                        }
                    }
                    return@launch
                }
                // The fresh-run start performs capture/composition/planning
                // synchronously; every production entry runs it on IO (audit
                // P2-1), matching the plain start row's execute{} wrapper.
                when (run.start(intent = pipeline.validated)) {
                    is ManualOrganizationRun.StartOutcome.Started -> withContext(uiDispatcher) {
                        status = ExchangeStatus(ExchangeStatus.Kind.IMPORT_ACCEPTED)
                        screen = ExchangeScreen.Closed
                    }

                    ManualOrganizationRun.StartOutcome.Busy -> withContext(uiDispatcher) {
                        // Single-active-operation gate rejected the fresh run:
                        // typed guidance, zero-write, intent dropped.
                        status = ExchangeStatus(ExchangeStatus.Kind.RUN_BUSY)
                        screen = ExchangeScreen.Importing(replyText)
                    }
                }
            } else {
                withContext(uiDispatcher) {
                    // Issue #332 (spec AC-7): the imported text moves into the
                    // outcome surface's single ephemeral field for the
                    // collapsed raw detail; leaving the surface discards it.
                    screen = ExchangeScreen.ImportOutcomeScreen(outcome, rawText = replyText)
                }
            }
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
        IMPORT_ACCEPTED,
        RUN_BUSY,

        /** Issue #332 (spec AC-6): clipboard empty/unreadable on the explicit read. */
        CLIPBOARD_EMPTY,

        /** Issue #332 (spec AC-6): the clipboard carries no text item. */
        CLIPBOARD_NOT_TEXT,
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
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
) {
    exchangeFlowItems(holder, null, emptyMap(), clipboardTransport, shareTransport, fileTransport)
}

/**
 * Issue #331: the exchange items with the run-in (scope-composed) entry.
 * Hosted inside the selection surface while a run holds it; when
 * [scopedSelection] is non-null the generation composes the export from the
 * frozen selection instead of the idle full-organization scope.
 */
fun LazyListScope.exchangeFlowItems(
    holder: ExchangeFlowStateHolder,
    scopedSelection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>?,
    scopedLabels: Map<app.lawnchair.organizer.planning.CandidateTarget.AppKey, String>,
    clipboardTransport: (Context, String) -> ExchangeTransportResult,
    shareTransport: (Context, String) -> ExchangeTransportResult,
    fileTransport: FileExchangeTransport,
) {
    val scoped = scopedSelection?.let { it to scopedLabels }
    when (val current = holder.screen) {
        ExchangeScreen.Closed -> {
            item(key = "exchange-entry") {
                if (scoped != null) {
                    ExchangeScopedEntryRow(
                        onOpenFlow = holder::openFlow,
                        onOpenImport = holder::openImport,
                    )
                } else {
                    ExchangeEntryRow(
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
                        requiresConfirmation = current.replacementConfirmationRequired,
                        onGenerate = { tier -> holder.requestGeneration(current.replacementConfirmationRequired, tier, scoped) },
                        onCancel = holder::close,
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

@Composable
private fun ExchangeEntryRow(onOpenFlow: () -> Unit, onOpenImport: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_entry_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-entry-title"),
        )
        Text(
            text = stringResource(R.string.exchange_entry_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onOpenFlow, modifier = Modifier.testTag("exchange-entry-open")) {
                Text(stringResource(R.string.exchange_entry_open))
            }
            OutlinedButton(onClick = onOpenImport, modifier = Modifier.testTag("exchange-entry-import")) {
                Text(stringResource(R.string.exchange_entry_import))
            }
        }
    }
}

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

@Composable
private fun ExchangePrivacySelection(
    requiresConfirmation: Boolean,
    onGenerate: (PrivacyTier) -> Unit,
    onCancel: () -> Unit,
) {
    var labelInclusive by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.exchange_privacy_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("exchange-privacy-title"),
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
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.exchange_cancel))
            }
        }
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

@Composable
private fun ExchangeDisclosure(
    holder: ExchangeFlowStateHolder,
    state: ExchangeDisclosureState,
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
            text = stringResource(R.string.exchange_disclosure_hint),
            style = MaterialTheme.typography.bodySmall,
        )
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
                onClick = holder::closeDisclosure,
                enabled = !state.transportInFlight,
                modifier = Modifier.testTag("exchange-cancel"),
            ) {
                Text(
                    stringResource(
                        if (state.cancelable) R.string.exchange_cancel else R.string.exchange_close,
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

@Composable
private fun exchangeStatusText(kind: ExchangeStatus.Kind): String = when (kind) {
    ExchangeStatus.Kind.TRANSPORT_SUCCESS -> stringResource(R.string.exchange_transport_success)
    ExchangeStatus.Kind.TRANSPORT_CLIPBOARD_FAILED -> stringResource(R.string.exchange_transport_clipboard_failed)
    ExchangeStatus.Kind.TRANSPORT_SHARE_ABSENT -> stringResource(R.string.exchange_transport_share_absent)
    ExchangeStatus.Kind.TRANSPORT_FILE_FAILED -> stringResource(R.string.exchange_transport_file_failed)
    ExchangeStatus.Kind.FILE_READ_FAILED -> stringResource(R.string.exchange_status_file_read_failed)
    ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY -> stringResource(R.string.exchange_generation_input_not_ready)
    ExchangeStatus.Kind.GENERATION_STORE_FAILURE -> stringResource(R.string.exchange_generation_store_failure)
    ExchangeStatus.Kind.GENERATION_OVERSIZE -> stringResource(R.string.exchange_generation_oversize)
    ExchangeStatus.Kind.INPUT_OVERSIZE -> stringResource(R.string.exchange_failure_input_oversize)
    ExchangeStatus.Kind.IMPORT_ACCEPTED -> stringResource(R.string.exchange_import_accepted)
    ExchangeStatus.Kind.RUN_BUSY -> stringResource(R.string.exchange_run_busy)
    ExchangeStatus.Kind.CLIPBOARD_EMPTY -> stringResource(R.string.exchange_status_clipboard_empty)
    ExchangeStatus.Kind.CLIPBOARD_NOT_TEXT -> stringResource(R.string.exchange_status_clipboard_not_text)
}

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
 * The 13-class #204 contract failure mapping (spec 204 + spec 331 D-5). The
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
}
