package app.lawnchair.ui.preferences.destinations

import android.content.Context
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PreviewCounts
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.integration.exchange.ClipboardExchangeTransport
import app.lawnchair.organizer.integration.exchange.ExchangeFlowModule
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.integration.exchange.PendingImportedIntentModule
import app.lawnchair.organizer.integration.exchange.ShareSheetExchangeTransport
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.ui.ManualOrganizationFace
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.ManualOrganizationRunFaceTrace
import app.lawnchair.organizer.ui.MissingAppSelectionState
import app.lawnchair.organizer.ui.OrganizationPreviewContent
import app.lawnchair.organizer.ui.OrganizationPreviewSection
import app.lawnchair.organizer.ui.OrganizationPreviewWording
import app.lawnchair.organizer.ui.RunUsageAccessJitDialogHost
import app.lawnchair.organizer.ui.UsageAccessJitGateProvider
import app.lawnchair.organizer.ui.exchange.ExchangeDiscardConfirmDialog
import app.lawnchair.organizer.ui.exchange.ExchangeFlowBackHandler
import app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolder
import app.lawnchair.organizer.ui.exchange.ExchangeImportDiscardConfirmDialog
import app.lawnchair.organizer.ui.exchange.exchangeFlowItems
import app.lawnchair.organizer.ui.manualOrganizationFace
import app.lawnchair.organizer.ui.missingAppSelectionItems
import app.lawnchair.organizer.ui.openUsageAccessSettings
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Issue #52 explicit manual/full organization surface. */
@Composable
fun ManualOrganizationPreferences(
    modifier: Modifier = Modifier,
    run: ManualOrganizationRun? = null,
    trigger: Trigger = Trigger.MANUAL_FULL,
    // Issue #376 (spec D5): set by the hub's restore CTA route — this
    // destination then owns the status-card entry's admission (see the
    // LaunchedEffect below the read block).
    durableRecovery: Boolean = false,
    onOpenDiagnostics: (() -> Unit)? = null,
    // Issue #371: injectable for the unsupported-settings instrumentation.
    usageAccessSettingsOpener: (Context) -> Boolean = ::openUsageAccessSettings,
    // Issue #371: injectable so instrumentation can host a real exchange JIT
    // waiter (cross-origin oracle) against a controlled holder.
    exchangeHolderOverride: ExchangeFlowStateHolder? = null,
    // Issue #371: injectable so the settings-return instrumentation can drive
    // the host's lifecycle deterministically.
    jitLifecycleOwner: androidx.lifecycle.LifecycleOwner? = null,
    // Issue #374 (spec 374 DI-AC-01/DI-AC-11): the hub status-card rows'
    // one-shot exchange pre-open argument (request → T-15, pendingReview →
    // ImportReview). Null — every legacy caller — does nothing.
    exchangeOpen: app.lawnchair.ui.preferences.navigation.ExchangeOpen? = null,
) {
    val context = LocalContext.current
    val coordinator = run ?: remember { ManualOrganizationModule.get(context) }
    val scope = rememberCoroutineScope()
    val state by coordinator.stateFlow.collectAsStateWithLifecycle()
    // Issue #369 (spec RD-7): the visible 検出 → capture → plan progression is
    // the coordinator's deterministic projection, never derived from State —
    // the legacy admission Capturing and the real composed capture are the
    // same State value, and conflation cannot hide intermediate publishes.
    val preparationPhase by coordinator.preparationPhase.collectAsStateWithLifecycle()
    // Issue #370: test-only render trace — report the face this composition
    // committed (SideEffect runs post-apply), so the admission guard records
    // "did the T-07 preamble ever render" deterministically. Production never
    // sets the recorder (ManualOrganizationRunFaceTrace doc).
    val committedFace = manualOrganizationFace(state)
    SideEffect { ManualOrganizationRunFaceTrace.recorder?.invoke(committedFace) }
    // Issue #205: the external agent exchange sub-flow. The entry surface is
    // hosted only while no run operation is active (spec 205 V1 rule), so it
    // is constructed unconditionally and rendered inside the Idle/Cancelled
    // branch only. #371: injectable so instrumentation can drive a real
    // exchange JIT waiter against a controlled holder.
    val exchangeHolder = remember(exchangeHolderOverride) {
        exchangeHolderOverride ?: ExchangeFlowStateHolder(
            controllerFactory = { ExchangeFlowModule.controller(context) },
            run = coordinator,
            scope = scope,
            // Issue #371: the run machine and the exchange holder share one
            // process-scoped JIT Usage Access request gate.
            usageAccessGate = UsageAccessJitGateProvider.get(context),
            // Issue #374 (spec 374): the durable pending imported intent
            // store — the validated proposal is saved at the import settle,
            // discarded through the tombstone two-phase commit, and deleted
            // when a new request's generation replaces the session.
            pendingImportStore = PendingImportedIntentModule.store(context),
            // Issue #375 (spec "exchange mutation gate"): the holder, the
            // controller and the rebind admission anchor share THE
            // process-wide serialization point.
            exchangeMutationGate = PendingImportedIntentModule.gate(),
        )
    }
    // Issue #371: the JIT Usage Access request dialog hosts at the run-state
    // observation point (the single dialog host for every composition trigger
    // path — start rows, onboarding admission, intent rebind, selection
    // confirmation and the D-06 empty-cut continuation all pause inside the
    // coordinator's composed-phase entry).
    RunUsageAccessJitDialogHost(
        run = coordinator,
        settingsOpener = usageAccessSettingsOpener,
        lifecycleOwner = jitLifecycleOwner ?: androidx.lifecycle.compose.LocalLifecycleOwner.current,
    )
    // Issue #368: the strategy picker moved to the materials surface T-05
    // (OrganizerStrategyPreferences). The run surface offers no strategy
    // selection — not even a read-only row — and the write-time restart
    // special case is gone with it: a strategy change applies to the next
    // run's composition, never to the live one.
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Issue #308: a stateFlow transition can be observed before the lazy-list
    // replacement target has attached. Keep readiness scoped to the state so a
    // focus request is made only after that state's target has been laid out.
    val focusTargetReady = remember(state) { mutableStateOf(false) }
    val focusTargetModifier = Modifier.onGloballyPositioned {
        focusTargetReady.value = true
    }

    // Issue #417: the method-choice face's process-local UI state — the
    // D-13 scope-bound discard confirmation (raised by system Back while a
    // request references the frozen scope) and its typed retryable failure.
    // Both reset per run.
    val scopeConfirmedState = state as? ManualOrganizationRun.State.ScopeConfirmed
    var scopeDiscardFailed by remember(scopeConfirmedState?.runId) { mutableStateOf(false) }
    var pendingScopeDiscard by remember { mutableStateOf(false) }

    // Issue #417: the entry/idle face hosts the exchange flow IMPORT-ONLY
    // (spec 205 V1 + the #417 Retire). A flow left open by an earlier
    // method-choice visit must never offer the creation UI here, so the face
    // resets the holder's host mode whenever it is shown.
    val idleLike = state is ManualOrganizationRun.State.Idle ||
        state is ManualOrganizationRun.State.Cancelled
    LaunchedEffect(idleLike) {
        if (idleLike) exchangeHolder.resetToImportOnlyHostMode()
    }

    // Issue #271: the durable status projection is rendered only while no run
    // operation is active (Idle/Cancelled). It is re-read on each transition
    // into those states — an in-place cancel re-reads, not only the first
    // composition — and any read maps to a fail-closed no-row outcome.
    // Issue #271 review: the read also re-runs whenever the application
    // module's startup readiness moves, so a fail-closed read taken while
    // startup reconciliation is still running recovers on the same surface
    // once reconciliation reaches a terminal state, without the user
    // navigating away. While no result is known yet, an explicit checking row
    // keeps the loading state visually distinct from "never organized".
    // Issue #376 (spec D6 read serialization): in durable-recovery mode the
    // status read is suppressed entirely — the destination's admission issues
    // the entry read, and both reads share the module's non-blocking mutex,
    // so running them concurrently would fail-close the admission against
    // its own display read.
    val showDurableStatus = !durableRecovery &&
        (state is ManualOrganizationRun.State.Idle || state is ManualOrganizationRun.State.Cancelled)
    val readinessState by coordinator.readinessState.collectAsStateWithLifecycle()
    var durableStatus by remember { mutableStateOf<OrganizerDurableStatus?>(null) }
    LaunchedEffect(showDurableStatus, readinessState) {
        durableStatus = if (showDurableStatus) {
            withContext(Dispatchers.IO) { coordinator.readDurableOrganizerStatus() }
        } else {
            null
        }
    }
    val showCheckingRow = showDurableStatus && (
        durableStatus == null ||
            (
                durableStatus == OrganizerDurableStatus.UNAVAILABLE &&
                    (
                        readinessState == ReadinessGate.State.IDLE ||
                            readinessState == ReadinessGate.State.RECONCILING
                        )
                )
        )

    // Issue #376 (spec D5): a hub-initiated durable entry hands admission
    // ownership to this destination. The read+inspection run here — in this
    // destination's own composition scope, so the hub's disposal can never
    // orphan the flow — and a silent rejection pops this face back to the
    // hub. NonCancellable closes the departure window: if the host went away
    // while admission was in flight (Back during the read), the live flow is
    // resolved back to the pre-entry state instead of being left as an
    // unseen pending preview.
    if (durableRecovery) {
        val navController = LocalNavController.current
        // Handoff discipline (spec D5/RS-AC-03): the hub CTA arms a
        // process-local launch marker; this destination consumes it once per
        // generation. A child-destination round trip (diagnostics push →
        // Back) re-runs this effect in the same process and must not
        // re-admit against a live terminal state; a process death loses the
        // marker entirely, so the restored route pops back to the hub and
        // the only restart path is the status card's CTA again.
        var lastHandledProcessId by androidx.compose.runtime.saveable.rememberSaveable {
            androidx.compose.runtime.mutableStateOf("")
        }
        LaunchedEffect(durableRecovery) {
            // The coordinator instance id is process-stable: the same
            // instance across a diagnostics round trip means already handled;
            // a different id means a fresh process whose handoff died with
            // its predecessor.
            val currentProcessId = coordinator.processInstanceId
            if (lastHandledProcessId == currentProcessId) {
                return@LaunchedEffect
            }
            lastHandledProcessId = currentProcessId
            if (!coordinator.consumeDurableEntryLaunchArm()) {
                navController.popBackStack()
                return@LaunchedEffect
            }
            val effectJob = coroutineContext.job
            val myEntryId = navController.currentBackStackEntry?.id
            withContext(NonCancellable) {
                val admitted = withContext(Dispatchers.IO) {
                    coordinator.beginRecoveryPreviewFromDurableEntry()
                }
                if (!admitted) {
                    // Only pop while this destination is still the current
                    // entry: a Back that raced the admission already popped
                    // it, and popping again would leave the hub too.
                    val stillCurrent = navController.currentBackStackEntry?.id == myEntryId
                    if (stillCurrent) navController.popBackStack()
                } else if (!effectJob.isActive) {
                    withContext(Dispatchers.IO) { coordinator.cancelRecoveryPreview() }
                }
            }
        }
    }

    fun execute(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
        }
    }

    val focusTargetIndex = when {
        state is ManualOrganizationRun.State.Selecting -> null

        manualOrganizationFace(state) == ManualOrganizationFace.PREAMBLE ->
            // T-07: checking + durable rows + the scope summary precede the
            // start row.
            1 + (if (showCheckingRow) 1 else 0) + durableStatusItemCount(durableStatus) + 1

        // Issue #417: the method-choice face's focus lands on its headline
        // (after the rejection/failure rows when one renders above it).
        manualOrganizationFace(state) == ManualOrganizationFace.METHOD_CHOICE ->
            1 + (if ((state as? ManualOrganizationRun.State.ScopeConfirmed)?.scopeRejection != null) 1 else 0) +
                (if (scopeDiscardFailed) 1 else 0)

        // T-09/T-13: the scroll reveals the face from its headline / cause;
        // the FocusRequester sits on the headline (T-09) and the cause row
        // (T-13) respectively.
        manualOrganizationFace(state) == ManualOrganizationFace.PREPARATION -> 1

        manualOrganizationFace(state) == ManualOrganizationFace.FAILURE -> 2

        else -> 1
    }

    // Issue #195: the concrete change list is planned once per preview state.
    // Expansion state is UI-local and resets when new details arrive.
    val previewDetails = (state as? ManualOrganizationRun.State.Preview)?.details
    val previewSections = remember(previewDetails, context) {
        previewDetails
            ?.let { OrganizationPreviewContent.sections(it, organizationPreviewWording(context)) }
            .orEmpty()
    }
    val expandedPreviewGroups = remember(previewDetails) { mutableStateOf(emptySet<Int>()) }

    // Issue #228: the selection surface's process-local state, keyed by the
    // owning run — a fresh detection cut always starts unchecked (D-1/AC-3),
    // even when the previous run's Selecting state is structurally equal.
    // Hoisted here because LazyListScope item builders are not composable
    // contexts.
    val selectingState = state as? ManualOrganizationRun.State.Selecting
    var missingAppSelection by remember(selectingState?.runId) {
        // Issue #375 (spec "選択復元初期値"): a PreviousExplicit rebind seeds
        // the surface with the request-time explicit selection (the resolvable
        // subset of the export scope). It is an INITIAL VALUE only — the run's
        // selection is committed solely by the explicit confirm (spec 228 D-1
        // is not weakened); non-rebind paths seed empty exactly as before.
        mutableStateOf(
            MissingAppSelectionState(
                selectingState?.candidates.orEmpty(),
                selectingState?.restoredSelection.orEmpty(),
            ),
        )
    }

    // Issue #369 (D-13, TO-BE §9): one confirmation gate shared by system Back
    // and the interrupt rows. 破棄 (irreversible) always confirms once; 中断
    // (zero-write) confirms once only when a selection or a proposal exists;
    // キャンセル (recovery preview close) never confirms. The exchange-side
    // pre-send discard confirmation lives in [pendingExchangeDiscard] (#372).
    var pendingInterrupt by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Issue #372 (D-13/EX-AC-11): the exchange flow's pre-send discard
    // confirmation, raised by the T-16 破棄 button AND by system Back on the
    // unsent request face — one dialog, two entries, per the accepted spec.
    // On dismissal the focus restores to the face's 破棄 action through this
    // requester (explicit, deterministic — platform dialog restore is not).
    var pendingExchangeDiscard by remember { mutableStateOf(false) }
    val exchangeDiscardFocus = remember { FocusRequester() }

    // Issue #374 (spec 328 rev.2 D-13): the import-discard confirmation,
    // raised by the 取り込み成功状態's 破棄して閉じる button AND by system Back —
    // one dialog, two entries. Confirm runs the holder's tombstone discard;
    // dismissal keeps the success state and restores focus to the face's
    // discard action through this requester.
    var pendingImportDiscard by remember { mutableStateOf(false) }
    val importDiscardFocus = remember { FocusRequester() }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var backCallback by remember { mutableStateOf<OnBackPressedCallback?>(null) }

    fun navigateBack() {
        backCallback?.isEnabled = false
        backDispatcher?.onBackPressed()
        backCallback?.isEnabled = true
    }

    fun interruptAndNavigate() {
        scope.launch {
            // Issue #376 (spec D5): a hub-origin recovery result leaves through
            // the explicit hub-return path (restores the pre-entry state so the
            // hub re-derives the durable status); every other state keeps the
            // plain dismissal. Host disposals never call this — result states
            // survive diagnostics pushes and recompositions.
            if (!coordinator.leaveRecoveryResultToHub()) {
                val outcome = withContext(Dispatchers.IO) { coordinator.dismiss() }
                // D-13: 中断 stops the run and returns to the hub — the same
                // navigation system Back takes. After the apply checkpoint the
                // coordinator's gate refuses (ApplicationInProgress): the surface
                // stays and the atomic-completion wording explains why.
                if (outcome == ManualOrganizationRun.DismissalOutcome.ApplicationInProgress) return@launch
            }
            withContext(Dispatchers.Main) { navigateBack() }
        }
    }

    fun onSystemBack() {
        // Issue #417: Back on the method-choice face. 候補0件のrunは選択面への
        // 復帰導線そのものを持たない — Back＝中断（zero-write）。依頼がこの面の
        // 確定scopeを参照している間は scope-bound 依頼破棄の確認（D-13）を1回
        // 挟み、破棄（Committed/NoMatch）が成立した後にだけ選択面へ戻る。
        // WriteFailed では面に残留し typed で再試行可能な失敗を表示する。
        // 依頼なしで候補がある場合は確認なしで選択面へ戻る（layout書込みなし）。
        val scopeConfirmed = state as? ManualOrganizationRun.State.ScopeConfirmed
        if (scopeConfirmed != null) {
            when {
                scopeConfirmed.candidates.isEmpty() -> interruptAndNavigate()
                coordinator.hasBoundScopeRequest() -> pendingScopeDiscard = true
                else -> execute { coordinator.reopenSelection() }
            }
            return
        }
        // 破棄を伴うときのみ1回確認（D-13）: a selection, a proposal, or an
        // apply not yet past its checkpoint. T-09 (nothing to lose), terminal
        // faces, and the recovery preview's no-confirm cancel go straight.
        val discardNeeded = when (state) {
            is ManualOrganizationRun.State.Selecting -> missingAppSelection.selected.isNotEmpty()
            is ManualOrganizationRun.State.Preview, is ManualOrganizationRun.State.PreviewUnavailable -> true
            ManualOrganizationRun.State.Applying -> true
            else -> false
        }
        if (discardNeeded) {
            pendingInterrupt = { interruptAndNavigate() }
        } else {
            interruptAndNavigate()
        }
    }

    // Issue #369 (D-13): the screen owns the Back callback so [navigateBack]
    // can disable it before re-dispatching — otherwise the re-dispatched Back
    // would re-enter [onSystemBack] and loop forever. The import-success
    // handler below composes later, so while enabled it takes the Back first.
    DisposableEffect(backDispatcher) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onSystemBack()
            }
        }
        backDispatcher?.addCallback(callback)
        backCallback = callback
        onDispose {
            callback.remove()
            backCallback = null
        }
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dismiss() }
    }
    // Issue #371 (review round 3): the exchange holder is remembered, so a
    // route change or activity recreation discards it silently. Any live JIT
    // pause must leave with the host — otherwise the process-wide gate keeps
    // a reservation/barrier nobody can resolve.
    DisposableEffect(exchangeHolder) {
        onDispose { exchangeHolder.dispose() }
    }

    // Issue #372 (EX-AC-11): the request faces' Back handler — ALWAYS-composed
    // at the hosting level (never inside a lazy item, whose composition can
    // leave the viewport under large font). Composed after the screen-level
    // gate above (an open flow takes Back before the dismiss/navigate
    // fallback) and before the import-success handler below (the success
    // state keeps Back priority).
    ExchangeFlowBackHandler(
        holder = exchangeHolder,
        onDiscardRequest = { pendingExchangeDiscard = true },
    )

    // Issue #372 (EX-AC-03): the T-15 pre-display re-reads the active request
    // on every lifecycle resume, so returning to the face (materials editing,
    // import, home) shows the store's current truth without a ticking clock.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exchangeHolder.refreshActiveRequest()
    }

    // Issue #374 (spec 374 DI-AC-01/DI-AC-11): the hub rows' one-shot exchange
    // pre-open. The navigation argument is consumed exactly once per
    // composition (recomposition-safe through the remembered flag; a
    // restored composition after process death re-opens the face, which is
    // the cold-process contract), and only while no exchange face is open —
    // a re-entry from the hub navigates a fresh back-stack entry, so the
    // pre-open fires on each deliberate row tap as intended.
    var exchangeOpenConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(exchangeOpen) {
        if (exchangeOpen == null || exchangeOpenConsumed) return@LaunchedEffect
        exchangeOpenConsumed = true
        if (exchangeHolder.screen !is app.lawnchair.organizer.ui.exchange.ExchangeScreen.Closed) return@LaunchedEffect
        when (exchangeOpen) {
            app.lawnchair.ui.preferences.navigation.ExchangeOpen.REQUEST -> exchangeHolder.openFlow()
            app.lawnchair.ui.preferences.navigation.ExchangeOpen.PENDING_REVIEW -> exchangeHolder.openPendingImportReview()
        }
    }

    // Issue #328 (spec 328 rev.2 D-13 / #374): the import success state
    // intercepts system Back at the ALWAYS-composed hosting level — never
    // inside the lazy item, whose composition can leave the viewport under
    // large font. Composed after the screen-level handler above, so while
    // enabled it takes the Back before the dismiss/navigate fallback. The
    // #374 D-13 contract: Back and the 破棄して閉じる button converge on the
    // host's ONE import-discard confirmation ([pendingImportDiscard]).
    app.lawnchair.organizer.ui.exchange.ExchangeImportSuccessBackHandler(
        exchangeHolder,
        onDiscardRequest = { pendingImportDiscard = true },
    )

    LaunchedEffect(state, focusTargetReady.value, focusTargetIndex) {
        // Issue #209 review: each run state is a fresh surface, but the lazy
        // list keeps its scroll offset across transitions (Applied's summary
        // offset used to leave the RecoveryPreview decision pair above the
        // viewport). Return to the head before restoring focus whenever the
        // target is already visible; otherwise reveal the target first so its
        // layout callback can run (Issue #308).
        runCatching {
            listState.scrollToItem(0)
            focusTargetIndex?.let { index ->
                // Issue #369: the PREAMBLE face mutates (checking row → durable
                // rows → scope summary); clamp to the live item count so the
                // reveal never races an out-of-range index mid-transition.
                val last = listState.layoutInfo.totalItemsCount - 1
                val target = index.coerceAtMost(last)
                if (target >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                    listState.scrollToItem(target)
                }
            }
        }
        if (!focusTargetReady.value) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    PreferenceScaffold(
        label = stringResource(R.string.manual_organization_title),
        modifier = modifier,
        isExpandedScreen = LocalIsExpandedScreen.current,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues, state = listState) {
            item {
                Text(
                    text = stringResource(R.string.manual_organization_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            when (val currentState = state) {
                // Issue #369 (TO-BE T-07) as reshaped by #417 (spec 417
                // Retire/Amend): the method-neutral entry face — the scope
                // summary (RD-5: no detection/composition lookahead) and ONE
                // start CTA that performs run admission (RUN lease). The AI
                // choice row is gone from this face: the AI arm lives on the
                // post-scope method-choice face so both arms consume the SAME
                // frozen scope. A durable import attempt no longer freezes the
                // start row either — a durable active request holds no RUN
                // lease and never blocks admission (AC-5/AC-8(d)).
                ManualOrganizationRun.State.Idle,
                ManualOrganizationRun.State.Cancelled,
                -> {
                    // Issue #271 review: loading is announced, never silently
                    // equated with "never organized". While startup
                    // reconciliation is still pending (IDLE/RECONCILING), an
                    // unavailable read is not yet the durable truth, so the
                    // checking row stays until the gate reaches a terminal
                    // state and the surface re-reads.
                    if (showCheckingRow) {
                        item { ProgressText(R.string.manual_organization_durable_status_checking) }
                    }
                    durableStatus?.let { durableStatusItems(it, onOpenDiagnostics) }
                    item {
                        SummaryText(stringResource(R.string.manual_organization_preamble_scope))
                    }
                    item {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_start),
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .focusable()
                                .then(focusTargetModifier),
                            onClick = { execute { coordinator.start(trigger) } },
                        )
                    }
                }

                // Issue #369 (TO-BE T-09): one integrated preparation face —
                // the phase row renders the coordinator's deterministic projection
                // (検出 → capture → plan), announced once per phase
                // (organization-run-ux §6). The face also hosts the internal
                // zero-candidate pass-through (D-06) dispatched by the face
                // mapping in the Selecting branch below.
                ManualOrganizationRun.State.Capturing,
                ManualOrganizationRun.State.CandidateDetection,
                ManualOrganizationRun.State.Planning,
                // Issue #371: the JIT pause and its resume claim are
                // preparation-phase waiting points — the request dialog is a
                // modal overlay hosted by RunUsageAccessJitDialogHost above,
                // and the phase row stays on the last published phase
                // (capture has not started while paused).
                is ManualOrganizationRun.State.AwaitingUsageAccessJit,
                is ManualOrganizationRun.State.ResumingUsageAccessJit,
                -> preparationFaceItems(
                    preparationPhase = preparationPhase,
                    focusRequester = focusRequester,
                    focusTargetModifier = focusTargetModifier,
                    onInterrupt = { interruptAndNavigate() },
                )

                // Issue #417 (spec 417, AC-1): the method-choice face. The
                // scope is frozen; the sibling arms consume the SAME confirmed
                // scope — 「このまま整理」 (the deterministic planner via
                // planWithConfirmedScope) and 「AIに相談」 (the scoped exchange
                // flow, whose creation/import states render on this face
                // through the hosting below; the holder enters METHOD_CHOICE
                // mode via openMethodChoiceFlow). A refused attach re-renders
                // the typed rejection row (zero-write); a failed scope-bound
                // discard keeps the face with a typed retryable failure row.
                is ManualOrganizationRun.State.ScopeConfirmed -> {
                    currentState.scopeRejection?.let { rejection ->
                        item(key = "method-choice-scope-mismatch") {
                            Text(
                                text = app.lawnchair.organizer.ui.exchange.exchangeContractFailureText(rejection),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .semantics { liveRegion = LiveRegionMode.Assertive }
                                    .testTag("method-choice-scope-mismatch"),
                            )
                        }
                    }
                    if (scopeDiscardFailed) {
                        // Issue #417 (AC-5): the scope-bound discard's store
                        // write failed — session, proposal and frozen scope
                        // are ALL kept; the retry is the discard confirmation
                        // again (Back). Assertive so the frozen state is
                        // announced without a focus change.
                        item(key = "scope-discard-failed") {
                            Text(
                                text = stringResource(R.string.exchange_scope_discard_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .semantics { liveRegion = LiveRegionMode.Assertive }
                                    .testTag("scope-discard-failed"),
                            )
                        }
                    }
                    item(key = "method-choice-headline") {
                        FocusTargetText(
                            text = stringResource(R.string.manual_organization_method_title),
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    item(key = "method-choice-plain") {
                        ClickablePreference(
                            label = stringResource(R.string.manual_organization_method_plain),
                            onClick = { execute { coordinator.planWithConfirmedScope() } },
                        )
                    }
                    item(key = "method-choice-consult") {
                        ClickablePreference(
                            label = stringResource(R.string.exchange_method_consult),
                            subtitle = stringResource(R.string.exchange_entry_subtitle),
                            onClick = exchangeHolder::openMethodChoiceFlow,
                        )
                    }
                    exchangeFlowItems(
                        holder = exchangeHolder,
                        onDiscardRequest = { pendingExchangeDiscard = true },
                        discardFocus = exchangeDiscardFocus,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onImportDiscardRequest = { pendingImportDiscard = true },
                        importDiscardFocus = importDiscardFocus,
                        clipboardTransport = { ctx: android.content.Context, text: String ->
                            ClipboardExchangeTransport(ctx).copy(text)
                        },
                        shareTransport = { ctx: android.content.Context, text: String ->
                            ShareSheetExchangeTransport().share(ctx, text)
                        },
                        fileTransport = FileExchangeTransport(context),
                    )
                }

                // Issue #369 (RD-7/D-06): the face mapping gates the selection surface
                // BEFORE any raw composition — the internal zero-candidate
                // pass-through composes the preparation face, so no collector
                // timing (StateFlow conflation) can render T-08 for an empty cut.
                is ManualOrganizationRun.State.Selecting -> {
                    if (manualOrganizationFace(currentState) == ManualOrganizationFace.PREPARATION) {
                        preparationFaceItems(
                            preparationPhase = preparationPhase,
                            focusRequester = focusRequester,
                            focusTargetModifier = focusTargetModifier,
                            onInterrupt = { interruptAndNavigate() },
                        )
                    } else {
                        // Issue #228: explicit scope selection (D-1: all
                        // candidates start unchecked). Selection survives query
                        // changes; Select all matches the filtered set, Clear all
                        // clears the whole candidate set (spec §2).
                        //
                        // Issue #417 (AC-5): the selection is never frozen by
                        // the exchange flow — the hosting (and the freeze it
                        // explained) left this face entirely; edits are always
                        // enabled here and the freeze reason renders on the
                        // method-choice face while a request references the
                        // frozen scope.
                        //
                        // Issue #331: the accepted typed SCOPE_MISMATCH failure from
                        // the scope binding gate (17th unified failure outcome),
                        // rendered with the re-export guidance.
                        currentState.scopeRejection?.let { rejection ->
                            item(key = "missing-app-selection-scope-mismatch") {
                                Text(
                                    text = app.lawnchair.organizer.ui.exchange.exchangeContractFailureText(rejection),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .semantics { liveRegion = LiveRegionMode.Assertive }
                                        .testTag("missing-app-selection-scope-mismatch"),
                                )
                            }
                        }
                        // Issue #375 (spec SR-AC-01): the selection diff
                        // against the export scope, derived by the pure
                        // scope-binding derivation and rendered as non-color
                        // row affordances.
                        val scopeDiff = currentState.intentScopeCandidates.takeIf { it.isNotEmpty() }?.let { scope ->
                            app.lawnchair.organizer.personalization.exchange.ScopeBindingCauseDerivation.deriveSelectionDiff(
                                sessionScope = scope,
                                detected = currentState.candidates.map { candidate ->
                                    app.lawnchair.organizer.personalization.exchange.DetectedCandidateScope(
                                        candidate.target,
                                        candidate.availability,
                                    )
                                },
                                selected = missingAppSelection.selected,
                            )
                        }
                        missingAppSelectionItems(
                            selection = missingAppSelection,
                            onSelectionChange = { missingAppSelection = it },
                            onConfirm = { selected -> execute { coordinator.confirmSelection(selected) } },
                            // D-13: 選択があるときは1回確認の「中断」。空選択のままの
                            // 離脱は何も壊さないため確認なし（キャンセル相当の離脱）。
                            onCancel = {
                                if (missingAppSelection.selected.isNotEmpty()) {
                                    pendingInterrupt = { interruptAndNavigate() }
                                } else {
                                    interruptAndNavigate()
                                }
                            },
                            intentScopeCount = currentState.intentScopeCount,
                            diff = scopeDiff,
                            // Issue #417 (AC-4): the explicit zero-selection
                            // disclosure — continuing with nothing selected is
                            // a deliberate scope, not an undecided surface.
                            showEmptySelectionNotice = missingAppSelection.selected.isEmpty(),
                        )
                    }
                }

                // Issue #369 (TO-BE T-13): one integrated failure face —
                // 見出し「実行できませんでした」＋原因（既存typed契約由来の
                // 文言。typed分類名は補助情報）＋次の手段（再試行/中断、
                // bug系では診断）。すべての経路でzero-write。

                is ManualOrganizationRun.State.InputUnavailable,
                is ManualOrganizationRun.State.ScopeMismatchFailed,
                is ManualOrganizationRun.State.CandidateResolutionFailed,
                is ManualOrganizationRun.State.PlanningRejected,
                -> {
                    item(key = "failure-headline") {
                        // The headline is static read-out; the face's single
                        // focus target stays the cause row below (base focus
                        // restoration semantics — one FocusRequester per face).
                        Text(
                            text = stringResource(R.string.manual_organization_failed),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    // 原因文言は既存のtyped契約由来のmappingをそのまま再利用する
                    // （spec 172のcopy split、spec 331のre-export案内、spec 228の
                    // 再検出案内、spec 52の原因件数）。typed分類名は主文言へ出さない。
                    item(key = "failure-cause") {
                        FocusTargetText(
                            text = when (currentState) {
                                is ManualOrganizationRun.State.InputUnavailable ->
                                    if (currentState.reason is app.lawnchair.organizer.integration.InputReadinessReason.StaleCandidateSelection) {
                                        // Issue #228 (review P2 #4): the selection was
                                        // cut against an older layout; re-detection
                                        // resolves it.
                                        stringResource(R.string.manual_organization_selection_stale)
                                    } else {
                                        stringResource(currentState.reason.copyKind())
                                    }

                                is ManualOrganizationRun.State.ScopeMismatchFailed ->
                                    // Issue #331 (D-5): the typed SCOPE_MISMATCH failure
                                    // for a run that could never open a selection
                                    // surface. The remedy is re-export.
                                    app.lawnchair.organizer.ui.exchange.exchangeContractFailureText(currentState.failure)

                                is ManualOrganizationRun.State.CandidateResolutionFailed ->
                                    // Issue #228 (review P2 #2): a selected app stopped
                                    // resolving; re-detection is the only recovery.
                                    stringResource(R.string.manual_organization_candidate_unresolved)

                                is ManualOrganizationRun.State.PlanningRejected ->
                                    stringResource(
                                        if (currentState.kind == ManualOrganizationRun.PlanningFailureKind.IMPOSSIBLE) {
                                            R.string.manual_organization_impossible
                                        } else {
                                            R.string.manual_organization_rejected
                                        },
                                    )

                                else -> stringResource(R.string.manual_organization_stale_proposal_not_reviewed)
                            },
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    // 原因件数（計画失敗の既存summary表示）は面統合後も変種として残す。
                    if (currentState is ManualOrganizationRun.State.PlanningRejected) {
                        summaryItems(currentState.summary)
                    }
                    item(key = "failure-actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_retry),
                                onClick = { execute { coordinator.start(trigger) } },
                            )
                            // D-13 §9: 終端には提案も選択もない — 中断は確認なしで
                            // runを止めてhubへ戻る（zero-write）。
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_interrupt),
                                onClick = { interruptAndNavigate() },
                            )
                            if (currentState is ManualOrganizationRun.State.InputUnavailable &&
                                currentState.reason !is app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending
                            ) {
                                ClickablePreference(
                                    label = stringResource(R.string.manual_organization_open_diagnostics),
                                    subtitle = stringResource(R.string.manual_organization_open_diagnostics_summary),
                                    onClick = { onOpenDiagnostics?.invoke() },
                                )
                            }
                        }
                    }
                }

                // Issue #369 (TO-BE T-12): 結果面の変種 — 成功/変更なし/適用されな
                // かった（stale等）/部分的失敗。typed結果ごとの異なるlocalized
                // outcome（spec 13/52 no false success）とspec 210の文言契約は不変。
                ManualOrganizationRun.State.NoChanges -> item {
                    FocusTargetText(
                        text = stringResource(R.string.manual_organization_no_changes),
                        focusRequester = focusRequester,
                        modifier = focusTargetModifier,
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_start_again),
                        onClick = { execute { coordinator.start(trigger) } },
                    )
                }

                is ManualOrganizationRun.State.Preview -> {
                    item {
                        FocusTargetText(
                            text = stringResource(R.string.manual_organization_preview),
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    if (currentState.details == null) {
                        // Issue #195 spec D1: environmental preview failures keep the
                        // existing count-only flow, but announce the missing details
                        // instead of silently equating them with a normal preview.
                        // Issue #209 review: the degraded announcement and the
                        // count-only summary precede the decision pair, so the user
                        // sees that the concrete list is missing before reaching the
                        // primary action (spec 195 D1 over leading placement).
                        item {
                            SummaryText(
                                stringResource(R.string.manual_organization_preview_details_unavailable),
                            )
                        }
                        summaryItems(currentState.summary)
                        item {
                            PreviewDecisionActions(
                                onConfirm = { execute(coordinator::confirm) },
                                // D-13: 提案があるため「中断」— 1回確認ののちrunを
                                // 止めてhubへ戻る。
                                onCancel = { pendingInterrupt = { interruptAndNavigate() } },
                            )
                        }
                    } else {
                        // Issue #209: the decision pair leads the concrete change
                        // list so Apply and Cancel are visible together no matter
                        // how much of the list is expanded.
                        item {
                            PreviewDecisionActions(
                                onConfirm = { execute(coordinator::confirm) },
                                // D-13: 提案があるため「中断」— 1回確認ののちrunを
                                // 止めてhubへ戻る。
                                onCancel = { pendingInterrupt = { interruptAndNavigate() } },
                            )
                        }
                        previewDetailsItems(
                            summary = currentState.summary,
                            counts = currentState.details.counts,
                            sections = previewSections,
                            expandedGroups = expandedPreviewGroups,
                        )
                    }
                }

                is ManualOrganizationRun.State.PreviewUnavailable -> {
                    // Issue #228 (spec AC-14): this run adds apps, so it cannot
                    // be confirmed from a count-only fallback. Nothing has
                    // been written; the only paths are re-preview (the layout
                    // may simply have moved) or cancel back to Idle.
                    item {
                        FocusTargetText(
                            text = stringResource(R.string.manual_organization_preview_unavailable_add),
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    summaryItems(currentState.summary)
                    item {
                        DecisionActionsRow {
                            Button(
                                onClick = { execute(coordinator::retryPlanPreview) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.manual_organization_preview_retry))
                            }
                            // D-13: 提案があるときのcancel側は「中断」— 1回確認ののち
                            // runを止めてhubへ戻る（decision pairの視覚構造は現行契約）。
                            OutlinedButton(
                                onClick = { pendingInterrupt = { interruptAndNavigate() } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.manual_organization_interrupt))
                            }
                        }
                    }
                }

                // Issue #369 (TO-BE T-11): 適用中面。checkpoint前のみ中断可
                // （zero-write、1回確認）。checkpoint後はBack・中断とも不受理で
                // あり、atomic完了までapplying文言が説明する（現行契約の維持）。
                ManualOrganizationRun.State.Applying -> item {
                    ProgressText(
                        R.string.manual_organization_applying,
                        focusTargetModifier,
                        focusRequester,
                    )
                    ClickablePreference(
                        label = stringResource(R.string.manual_organization_cancel_before_checkpoint),
                        onClick = { pendingInterrupt = { interruptAndNavigate() } },
                    )
                }

                is ManualOrganizationRun.State.Stale -> {
                    when (currentState.origin) {
                        // Issue #210: the stale surface must report the outcome of
                        // the blocked apply attempt, not only the layout change:
                        // nothing was applied, the reviewed proposal was discarded,
                        // and recapture starts a new review from the current layout.
                        // T-12結果面の変種（outcome文・詳細文・recapture文言不変）。
                        ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED -> {
                            item {
                                FocusTargetText(
                                    text = stringResource(R.string.manual_organization_stale_outcome),
                                    focusRequester = focusRequester,
                                    modifier = focusTargetModifier,
                                )
                            }
                            item {
                                Text(
                                    text = stringResource(R.string.manual_organization_stale_proposal_discarded),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            item {
                                ClickablePreference(
                                    label = stringResource(R.string.manual_organization_recapture),
                                    subtitle = stringResource(R.string.manual_organization_recapture_summary),
                                    onClick = { execute { coordinator.start(trigger) } },
                                )
                            }
                        }

                        // Issue #369 (D-12): the entry stale never reaches the
                        // confirmation face — it renders as the T-13 integrated
                        // failure face's variant (見出し＋原因＋次の手段).
                        ManualOrganizationRun.StaleOrigin.DETECTED_BEFORE_REVIEW -> {
                            item(key = "failure-headline") {
                                FocusTargetText(
                                    text = stringResource(R.string.manual_organization_failed),
                                    focusRequester = focusRequester,
                                    modifier = focusTargetModifier,
                                )
                            }
                            item(key = "failure-cause") {
                                // spec 210の入場前stale詳細文（文言不変）。
                                FocusTargetText(
                                    text = stringResource(R.string.manual_organization_stale_proposal_not_reviewed),
                                    focusRequester = focusRequester,
                                    modifier = focusTargetModifier,
                                )
                            }
                            item(key = "failure-actions") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ClickablePreference(
                                        label = stringResource(R.string.manual_organization_recapture),
                                        subtitle = stringResource(R.string.manual_organization_recapture_summary),
                                        onClick = { execute { coordinator.start(trigger) } },
                                    )
                                    // D-13 §9: 終端には提案も選択もない — 中断は
                                    // 確認なしでrunを止めてhubへ戻る（zero-write）。
                                    ClickablePreference(
                                        label = stringResource(R.string.manual_organization_interrupt),
                                        onClick = { interruptAndNavigate() },
                                    )
                                }
                            }
                        }
                    }
                }

                is ManualOrganizationRun.State.Applied -> {
                    item {
                        FocusTargetText(
                            text = applyMessage(currentState.result),
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    if (currentState.result is ApplyResult.Applied) {
                        // Issue #231: a verified apply reports its counts in the
                        // completed tense, never in the proposal's future tense.
                        appliedResultItems(currentState.summary)
                    } else {
                        summaryItems(currentState.summary)
                    }
                    if (currentState.result is ApplyResult.Applied) {
                        // Issue #209: the safety net must read as a control,
                        // not as a caption row among the summary lines.
                        item {
                            DecisionActionsRow {
                                FilledTonalButton(
                                    onClick = { execute(coordinator::beginRecoveryPreview) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = stringResource(R.string.manual_organization_recovery))
                                }
                            }
                        }
                    }
                    if (currentState.result.requiresSafeSupport()) {
                        item {
                            Text(
                                text = stringResource(R.string.manual_organization_safe_terminal),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_open_diagnostics),
                                subtitle = stringResource(R.string.manual_organization_open_diagnostics_summary),
                                onClick = { onOpenDiagnostics?.invoke() },
                            )
                        }
                    } else {
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_start_again),
                                onClick = { execute { coordinator.start(trigger) } },
                            )
                        }
                    }
                }

                ManualOrganizationRun.State.InspectingRecovery -> item {
                    ProgressText(
                        R.string.manual_organization_recovery_inspecting,
                        focusTargetModifier,
                        focusRequester,
                    )
                }

                is ManualOrganizationRun.State.RecoveryPreview -> {
                    item {
                        FocusTargetText(
                            text = recoveryPreviewMessage(currentState.result),
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    // Issue #230: describe the restore target with the apply
                    // history of the correlated verified apply. The counts are
                    // apply history, never a predicted restore diff, and the
                    // line is omitted when the correlation gate cleared the
                    // summary (confirm/cancel remain available).
                    val history = currentState.appliedSummary
                    if (history != null && (history.movedCount > 0 || history.newFolderCount > 0 || history.newPageCount > 0)) {
                        item { SummaryText(recoveryHistoryLine(history)) }
                    }
                    // Issue #209: restore-or-cancel renders as the same
                    // decision pair as the apply preview.
                    item {
                        DecisionActionsRow {
                            if (currentState.result is RecoveryPreviewResult.Restorable) {
                                Button(
                                    onClick = { execute(coordinator::confirmRecovery) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = stringResource(R.string.manual_organization_recovery_confirm))
                                }
                            }
                            OutlinedButton(
                                onClick = { execute(coordinator::cancelRecoveryPreview) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.manual_organization_cancel))
                            }
                        }
                    }
                }

                ManualOrganizationRun.State.Recovering -> item {
                    ProgressText(
                        R.string.manual_organization_recovering,
                        focusTargetModifier,
                        focusRequester,
                    )
                }

                is ManualOrganizationRun.State.RecoveryResultState -> {
                    item {
                        FocusTargetText(
                            text = recoveryResultMessage(currentState.result),
                            focusRequester = focusRequester,
                            modifier = focusTargetModifier,
                        )
                    }
                    if (currentState.result.requiresSafeSupport()) {
                        item {
                            Text(
                                text = stringResource(R.string.manual_organization_safe_terminal),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_open_diagnostics),
                                subtitle = stringResource(R.string.manual_organization_open_diagnostics_summary),
                                onClick = { onOpenDiagnostics?.invoke() },
                            )
                        }
                    } else {
                        item {
                            ClickablePreference(
                                label = stringResource(R.string.manual_organization_start_again),
                                onClick = { execute { coordinator.start(trigger) } },
                            )
                        }
                    }
                }
            }
            // Issue #205: the external agent exchange surface closes the list.
            // The entry is a secondary affordance hosted only while the run
            // is idle/cancelled (spec 205 V1 rule). Issue #417: the hosting
            // is IMPORT-ONLY here — the hoisted effect above keeps the
            // holder's host mode reset for this face.
            if (idleLike) {
                exchangeFlowItems(
                    holder = exchangeHolder,
                    onDiscardRequest = { pendingExchangeDiscard = true },
                    discardFocus = exchangeDiscardFocus,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onImportDiscardRequest = { pendingImportDiscard = true },
                    importDiscardFocus = importDiscardFocus,
                    clipboardTransport = { ctx: android.content.Context, text: String ->
                        ClipboardExchangeTransport(ctx).copy(text)
                    },
                    shareTransport = { ctx: android.content.Context, text: String ->
                        ShareSheetExchangeTransport().share(ctx, text)
                    },
                    fileTransport = FileExchangeTransport(context),
                )
            }
        }
    }

    // Issue #369 (D-13): the shared 破棄/中断 confirmation dialog. Focus moves
    // into the dialog; confirm and dismiss are explicit roles (organization-
    // run-ux §6). The discarding action itself runs after the confirmation.
    pendingInterrupt?.let { confirmedAction ->
        ManualOrganizationDiscardConfirmDialog(
            onConfirm = {
                pendingInterrupt = null
                confirmedAction()
            },
            onDismiss = { pendingInterrupt = null },
        )
    }

    // Issue #372 (D-13): the exchange pre-send discard confirmation. Confirm
    // goes through the holder's existing closeDisclosure structural gate
    // (cancelling → invalidate of exactly the unsent session → flow close);
    // dismiss keeps the T-16 face. The confirm runs after the confirmation,
    // so the transport gate is untouched while the dialog is up.
    if (pendingExchangeDiscard) {
        ExchangeDiscardConfirmDialog(
            onConfirm = {
                pendingExchangeDiscard = false
                exchangeHolder.closeDisclosure()
            },
            onDismiss = {
                pendingExchangeDiscard = false
                exchangeDiscardFocus.requestFocus()
            },
        )
    }

    // Issue #417 (spec 417 D-13): the scope-bound request discard
    // confirmation — raised by system Back on the method-choice face while a
    // request references the frozen scope. Confirm runs the run-owned ONE
    // seam (holder.discardScopeBoundRequest): `Discarded` clears the session
    // and the binding and re-opens the editable selection face;
    // `WriteFailed` keeps the session, the proposal and the frozen scope —
    // the face stays with the typed retryable failure row. Dismiss keeps the
    // face (the retry is Back again).
    if (pendingScopeDiscard) {
        ManualOrganizationDiscardConfirmDialog(
            onConfirm = {
                pendingScopeDiscard = false
                exchangeHolder.discardScopeBoundRequest { outcome ->
                    when (outcome) {
                        ManualOrganizationRun.ScopeDiscardOutcome.Discarded -> {
                            scopeDiscardFailed = false
                            execute { coordinator.reopenSelection() }
                        }

                        ManualOrganizationRun.ScopeDiscardOutcome.WriteFailed -> scopeDiscardFailed = true

                        ManualOrganizationRun.ScopeDiscardOutcome.NotDiscardable -> Unit
                    }
                }
            },
            onDismiss = { pendingScopeDiscard = false },
        )
    }

    // Issue #374 (spec 328 rev.2 D-13): the ONE import-discard confirmation —
    // the shared entry of the 取り込み成功状態's 破棄して閉じる button and system
    // Back. Confirm goes through the holder's tombstone discard (the face
    // closes only after the tombstone commit succeeds; a failed commit keeps
    // the success state with a typed notice); dismiss keeps the success state
    // and restores focus to the face's discard action.
    if (pendingImportDiscard) {
        ExchangeImportDiscardConfirmDialog(
            onConfirm = {
                pendingImportDiscard = false
                exchangeHolder.discardImport()
            },
            onDismiss = {
                pendingImportDiscard = false
                importDiscardFocus.requestFocus()
            },
        )
    }
}

/**
 * Issue #369 (D-13, TO-BE §9): the one confirmation dialog for 破棄 (Back on a
 * surface holding work) and 中断 (a selection or a proposal exists, or the
 * apply has not passed its checkpoint). Destructive-vocabulary confirm,
 * no-confirm キャンセル dismissal; no timeout auto-confirm/cancel.
 */
@Composable
private fun ManualOrganizationDiscardConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.manual_organization_discard_confirm_title)) },
        text = {
            Text(
                text = stringResource(R.string.manual_organization_discard_confirm_text),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.manual_organization_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.manual_organization_cancel))
            }
        },
    )
}

/**
 * Issue #271: the durable status projection row(s) for the Idle/Cancelled
 * surfaces. Only the three informative statuses render; `NEVER_ORGANIZED` and
 * the fail-closed `UNAVAILABLE` render nothing. The unresolved status reuses
 * the existing safe-support guidance (safe-terminal line plus the diagnostics
 * entry), matching the in-run unresolved surfaces.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.durableStatusItems(
    status: OrganizerDurableStatus,
    onOpenDiagnostics: (() -> Unit)?,
) {
    when (status) {
        OrganizerDurableStatus.ORGANIZED_RESTORABLE -> item {
            SummaryText(stringResource(R.string.manual_organization_durable_status_restorable))
        }

        OrganizerDurableStatus.RESTORED_OR_EXPIRED -> item {
            SummaryText(stringResource(R.string.manual_organization_durable_status_restored_or_expired))
        }

        OrganizerDurableStatus.UNRESOLVED -> {
            item {
                SummaryText(stringResource(R.string.manual_organization_durable_status_unresolved))
            }
            item {
                SummaryText(stringResource(R.string.manual_organization_safe_terminal))
            }
            item {
                ClickablePreference(
                    label = stringResource(R.string.manual_organization_open_diagnostics),
                    subtitle = stringResource(R.string.manual_organization_open_diagnostics_summary),
                    onClick = { onOpenDiagnostics?.invoke() },
                )
            }
        }

        OrganizerDurableStatus.NEVER_ORGANIZED,
        OrganizerDurableStatus.UNAVAILABLE,
        -> Unit
    }
}

private fun durableStatusItemCount(status: OrganizerDurableStatus?): Int = when (status) {
    OrganizerDurableStatus.ORGANIZED_RESTORABLE,
    OrganizerDurableStatus.RESTORED_OR_EXPIRED,
    -> 1

    OrganizerDurableStatus.UNRESOLVED -> 3

    null,
    OrganizerDurableStatus.NEVER_ORGANIZED,
    OrganizerDurableStatus.UNAVAILABLE,
    -> 0
}

internal fun strategyDisplayName(id: StrategyId): Int = when (id.value) {
    "CANONICAL_PAGE_COMPACT_V1" -> R.string.organization_strategy_canonical_name
    "STABLE_PAGE_TIDY_V1" -> R.string.organization_strategy_tidy_name
    "STABLE_PAGE_TIDY_V2" -> R.string.organization_strategy_tidy_v2_name
    "BOTTOM_FIRST_V1" -> R.string.organization_strategy_bottom_first_name
    "BOTTOM_FIRST_V2" -> R.string.organization_strategy_bottom_first_v2_name
    "BOTTOM_REGION_V1" -> R.string.organization_strategy_bottom_region_name
    "GLOBAL_COMPACT_V1" -> R.string.organization_strategy_global_name
    "GLOBAL_COMPACT_V2" -> R.string.organization_strategy_global_v2_name
    "CATEGORY_CONTIGUOUS_V1" -> R.string.organization_strategy_category_contiguous_name
    else -> R.string.organization_strategy_unknown_name
}

internal fun strategyDescription(id: StrategyId): Int = when (id.value) {
    "CANONICAL_PAGE_COMPACT_V1" -> R.string.organization_strategy_canonical_description
    "STABLE_PAGE_TIDY_V1" -> R.string.organization_strategy_tidy_description
    "STABLE_PAGE_TIDY_V2" -> R.string.organization_strategy_tidy_v2_description
    "BOTTOM_FIRST_V1" -> R.string.organization_strategy_bottom_first_description
    "BOTTOM_FIRST_V2" -> R.string.organization_strategy_bottom_first_v2_description
    "BOTTOM_REGION_V1" -> R.string.organization_strategy_bottom_region_description
    "GLOBAL_COMPACT_V1" -> R.string.organization_strategy_global_description
    "GLOBAL_COMPACT_V2" -> R.string.organization_strategy_global_v2_description
    "CATEGORY_CONTIGUOUS_V1" -> R.string.organization_strategy_category_contiguous_description
    else -> R.string.organization_strategy_unknown_description
}

/**
 * Issue #369 (TO-BE T-09, RD-7): the integrated preparation face — headline,
 * the phase row driven by the coordinator's deterministic projection (announced
 * once per phase via the polite live region), and the no-confirm interrupt row
 * (D-13 §9: no selection or proposal exists yet, zero-write). Shared by the
 * detection/capture/plan states and the internal zero-candidate `Selecting`
 * pass-through, so the D-06 guard composes exactly this face.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.preparationFaceItems(
    preparationPhase: ManualOrganizationRun.PreparationPhase,
    focusRequester: FocusRequester,
    focusTargetModifier: Modifier,
    onInterrupt: () -> Unit,
) {
    item(key = "preparation-headline") {
        FocusTargetText(
            text = stringResource(R.string.manual_organization_preparation),
            focusRequester = focusRequester,
            modifier = focusTargetModifier,
        )
    }
    item(key = "preparation-phase") {
        ProgressText(
            when (preparationPhase) {
                ManualOrganizationRun.PreparationPhase.DETECTION ->
                    R.string.manual_organization_detecting_missing_apps

                ManualOrganizationRun.PreparationPhase.CAPTURE ->
                    R.string.manual_organization_capturing

                ManualOrganizationRun.PreparationPhase.PLAN ->
                    R.string.manual_organization_planning
            },
        )
    }
    item(key = "preparation-interrupt") {
        ClickablePreference(
            label = stringResource(R.string.manual_organization_interrupt),
            onClick = onInterrupt,
        )
    }
}

@Composable
private fun ProgressText(
    @androidx.annotation.StringRes resourceId: Int,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val focusModifier = if (focusRequester == null) {
        Modifier
    } else {
        Modifier
            .focusRequester(focusRequester)
            .focusable()
    }
    Text(
        text = stringResource(resourceId),
        modifier = focusModifier
            .then(modifier)
            .padding(horizontal = 16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun FocusTargetText(
    text: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .then(modifier)
            .padding(horizontal = 16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * Issue #209: the preview decision pair (confirm / cancel) as Material3
 * buttons — the same emphasis split as the lawnchair confirmation bottom
 * sheet — instead of preference rows that read like plain text. In the
 * concrete-list mode it sits directly below the status heading so both paths
 * of the decision are visible together no matter how far the list is
 * expanded; the degraded mode keeps it after the missing-details announcement
 * and the count-only summary (spec 195 D1).
 */
@Composable
private fun PreviewDecisionActions(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DecisionActionsRow {
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.manual_organization_confirm))
        }
        // Issue #369 (D-13): the proposal exists, so the cancel side of the
        // decision pair is 中断 — one confirmation, then the run stops and the
        // user returns to the hub (spec 209 keeps the pair's visual structure).
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.manual_organization_interrupt))
        }
    }
}

/**
 * Issue #209: decision actions (confirm / cancel / restore) render as
 * Material3 buttons — the same emphasis split as the lawnchair confirmation
 * bottom sheet — instead of preference rows that read like plain text. The
 * actions sit directly below the state heading so both paths of a decision
 * are visible together regardless of how far the change list is expanded,
 * stacked full-width so keyboard/DPAD traversal visits them in the visual
 * confirm-then-cancel order and long labels wrap instead of clipping.
 */
@Composable
private fun DecisionActionsRow(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.summaryItems(
    summary: ManualOrganizationRun.Summary,
) {
    contextItems(summary)
    changeCountItems(summary)
    constraintItems(summary)
}

/**
 * Input-context lines. These describe the captured planning input, not the
 * changes, so both preview modes render them from [ManualOrganizationRun.Summary]
 * (spec §D2: the PreviewCounts truth split covers change counts only).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.contextItems(
    summary: ManualOrganizationRun.Summary,
) {
    item {
        SummaryText(
            stringResource(
                R.string.manual_organization_scope,
                summary.scope.targetCount,
                summary.scope.targetProfileCount,
                summary.scope.pageCount,
            ),
        )
    }
    item {
        SummaryText(
            stringResource(
                R.string.manual_organization_device_scope,
                summary.scope.columns,
                summary.scope.rows,
                summary.scope.hotseatSlots,
            ),
        )
    }
    item {
        SummaryText(
            stringResource(
                R.string.manual_organization_preview_strategy,
                stringResource(strategyDisplayName(summary.organizationStrategy)),
            ),
        )
    }
}

/**
 * Change-count lines from the planning [ManualOrganizationRun.Summary]. Only
 * the degraded count-only preview renders these; the concrete change list uses
 * [previewDetailsItems] whose header counts come from `PreviewCounts` so rows
 * and header always share one truth (spec 194).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.changeCountItems(
    summary: ManualOrganizationRun.Summary,
) {
    item { SummaryText(stringResource(R.string.manual_organization_moved_count, summary.movedCount)) }
    summary.movedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(movedReasonString(reason), count)) }
    }
    if (summary.addedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_added_count, summary.addedCount)) }
    }
    item { SummaryText(stringResource(R.string.manual_organization_preserved_count, summary.preservedCount)) }
    summary.preservedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(preservedReasonString(reason), count)) }
    }
    item { SummaryText(stringResource(R.string.manual_organization_new_folders_count, summary.newFolderCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_new_pages_count, summary.newPageCount)) }
    summary.rejectedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(rejectionReasonString(reason), count)) }
    }
    summary.unplacedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(unplacedReasonString(reason), count)) }
    }
    summary.warningCounts.forEach { (code, count) ->
        item { SummaryText(stringResource(warningString(code), count)) }
    }
}

/**
 * Issue #231: the verified-apply result surface. Same row order and
 * interleaving as [changeCountItems], but the four count lines use the new
 * past-tense plurals so a completed apply never reads as a pending proposal.
 * The planning-failure breakdowns iterate unchanged even though they are
 * always empty on this surface (`State.Applied` is reachable only from a
 * `Planned` outcome), keeping row parity if the planner contract ever carries
 * unplaced information (spec 52 requires applied/preserved/unplaced counts).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.appliedResultItems(
    summary: ManualOrganizationRun.Summary,
) {
    contextItems(summary)
    item {
        SummaryText(
            pluralStringResource(
                R.plurals.manual_organization_applied_moved_count,
                summary.movedCount,
                summary.movedCount,
            ),
        )
    }
    if (summary.addedCount > 0) {
        item {
            SummaryText(
                pluralStringResource(
                    R.plurals.manual_organization_applied_added_count,
                    summary.addedCount,
                    summary.addedCount,
                ),
            )
        }
    }
    summary.movedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(movedReasonString(reason), count)) }
    }
    item {
        SummaryText(
            pluralStringResource(
                R.plurals.manual_organization_applied_preserved_count,
                summary.preservedCount,
                summary.preservedCount,
            ),
        )
    }
    summary.preservedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(preservedReasonString(reason), count)) }
    }
    item {
        SummaryText(
            pluralStringResource(
                R.plurals.manual_organization_applied_new_folders_count,
                summary.newFolderCount,
                summary.newFolderCount,
            ),
        )
    }
    item {
        SummaryText(
            pluralStringResource(
                R.plurals.manual_organization_applied_new_pages_count,
                summary.newPageCount,
                summary.newPageCount,
            ),
        )
    }
    summary.rejectedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(rejectionReasonString(reason), count)) }
    }
    summary.unplacedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(unplacedReasonString(reason), count)) }
    }
    summary.warningCounts.forEach { (code, count) ->
        item { SummaryText(stringResource(warningString(code), count)) }
    }
    constraintItems(summary)
}

private fun androidx.compose.foundation.lazy.LazyListScope.constraintItems(
    summary: ManualOrganizationRun.Summary,
) {
    val constraints = summary.constraints
    if (constraints.lockedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_locked_constraint, constraints.lockedCount)) }
    }
    if (constraints.unavailableCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_unavailable_constraint, constraints.unavailableCount)) }
    }
    constraints.availabilityCounts.forEach { (availability, count) ->
        if (availability != Availability.AVAILABLE) {
            item { SummaryText(stringResource(availabilityString(availability), count)) }
        }
    }
    if (constraints.widgetCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_widget_constraint, constraints.widgetCount)) }
    }
    if (constraints.appPairCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_app_pair_constraint, constraints.appPairCount)) }
    }
    if (constraints.legacyShortcutCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_legacy_shortcut_constraint, constraints.legacyShortcutCount)) }
    }
    if (constraints.emptyFolderCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_empty_folder_constraint, constraints.emptyFolderCount)) }
    }
}

/**
 * Issue #195: the concrete change list. Header counts come from the
 * materialized-plan [PreviewCounts], groups render the projected rows in
 * deterministic order, and large groups truncate behind a per-group expand
 * action whose semantics carry the expansion state.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.previewDetailsItems(
    summary: ManualOrganizationRun.Summary,
    counts: PreviewCounts,
    sections: List<OrganizationPreviewSection>,
    expandedGroups: MutableState<Set<Int>>,
) {
    contextItems(summary)
    item { SummaryText(stringResource(R.string.manual_organization_moved_count, counts.movedCount)) }
    // Issue #235 (owner review): widget relocations get their own header line
    // on the concrete change list too — AC-8's separate widget count must be
    // visible wherever the details rows are shown, not only in the degraded
    // count-only summary (whose movedByReason rows carry it).
    if (counts.widgetMovedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_widget_moved_count, counts.widgetMovedCount)) }
    }
    if (counts.addedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_added_count, counts.addedCount)) }
    }
    // Issue #228 (review P1 follow-up): scope-unplaced candidates ride the
    // summary's unplaced vocabulary — informational context lines, like
    // contextItems, so the placed Adds and the overflow stay visible together.
    summary.unplacedByReason.forEach { (reason, count) ->
        item { SummaryText(stringResource(unplacedReasonString(reason), count)) }
    }
    item { SummaryText(stringResource(R.string.manual_organization_preserved_count, counts.preservedCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_new_folders_count, counts.newFolderCount)) }
    item { SummaryText(stringResource(R.string.manual_organization_new_pages_count, counts.newPageCount)) }
    if (counts.crossPageMovedCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_cross_page_moved_count, counts.crossPageMovedCount)) }
    }
    if (counts.preservedByStrategyCount > 0) {
        item { SummaryText(stringResource(R.string.manual_organization_preserved_by_strategy_count, counts.preservedByStrategyCount)) }
    }
    counts.warningCounts.forEach { (code, count) ->
        item { SummaryText(stringResource(warningString(code), count)) }
    }
    item {
        Text(
            text = stringResource(R.string.manual_organization_changes_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    sections.forEachIndexed { sectionIndex, section ->
        // Stable keys keep the toggle's node identity across the expansion
        // reflow so focus and item state survive the rows inserted before it.
        item(key = "preview-section-$sectionIndex") {
            Text(
                text = section.heading,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val expanded = sectionIndex in expandedGroups.value
        val visibleRows = if (expanded) section.rows else section.rows.take(PREVIEW_ROWS_BEFORE_EXPANSION)
        visibleRows.forEachIndexed { rowIndex, row ->
            item(key = "preview-row-$sectionIndex-$rowIndex") { SummaryText(row) }
        }
        if (section.rows.size > PREVIEW_ROWS_BEFORE_EXPANSION) {
            item(key = "preview-toggle-$sectionIndex") {
                val expandedNow = sectionIndex in expandedGroups.value
                val stateText = stringResource(
                    if (expandedNow) {
                        R.string.manual_organization_preview_expanded_state
                    } else {
                        R.string.manual_organization_preview_collapsed_state
                    },
                )
                val label = stringResource(
                    if (expandedNow) {
                        R.string.manual_organization_preview_show_fewer
                    } else {
                        R.string.manual_organization_preview_show_all
                    },
                    if (expandedNow) PREVIEW_ROWS_BEFORE_EXPANSION else section.totalCount,
                )
                val toggleFocus = remember { FocusRequester() }
                // Spec 52 focus restoration: the action that announced the extra
                // rows keeps focus after the list reflows around it.
                LaunchedEffect(expandedNow) {
                    if (expandedNow) {
                        withFrameNanos { }
                        runCatching { toggleFocus.requestFocus() }
                    }
                }
                ClickablePreference(
                    label = label,
                    modifier = Modifier
                        .focusRequester(toggleFocus)
                        .semantics { stateDescription = stateText },
                    onClick = {
                        expandedGroups.value = if (expandedNow) {
                            expandedGroups.value - sectionIndex
                        } else {
                            expandedGroups.value + sectionIndex
                        }
                    },
                )
            }
        }
    }
    constraintItems(summary)
}

/**
 * Resolves the change-list wording with the given context; row planning itself
 * stays in the pure [OrganizationPreviewContent] builder, which the caller
 * caches per preview details.
 */
private fun organizationPreviewWording(context: Context): OrganizationPreviewWording = ResourceOrganizationPreviewWording(
    groupMoved = context.getString(R.string.manual_organization_group_moved),
    groupAdded = context.getString(R.string.manual_organization_group_added),
    addDescriptor = context.getString(R.string.manual_organization_preview_add_descriptor),
    addRow = context.getString(R.string.manual_organization_preview_add_row),
    groupNewFolders = context.getString(R.string.manual_organization_group_new_folders),
    groupNewPages = context.getString(R.string.manual_organization_group_new_pages),
    groupPreserved = context.getString(R.string.manual_organization_group_preserved),
    groupWarnings = context.getString(R.string.manual_organization_group_warnings),
    moveRow = context.getString(R.string.manual_organization_preview_move_row),
    sameBandMoveRow = context.getString(R.string.manual_organization_preview_same_band_move_row),
    rowOrdinalNote = context.getString(R.string.manual_organization_preview_row_ordinal_note),
    workspaceDestination = context.getString(R.string.manual_organization_preview_workspace_destination),
    itemRow = context.getString(R.string.manual_organization_preview_item_row),
    itemDescriptor = context.getString(R.string.manual_organization_preview_item_descriptor),
    itemDescriptorWithoutKind = context.getString(R.string.manual_organization_preview_item_descriptor_without_kind),
    moveReasonSinglePlacement = context.getString(R.string.manual_organization_preview_move_reason_single_placement),
    moveReasonFolderMember = context.getString(R.string.manual_organization_preview_move_reason_folder_member),
    moveReasonFolderUnit = context.getString(R.string.manual_organization_preview_move_reason_folder_unit),
    moveReasonWidgetUnit = context.getString(R.string.manual_organization_preview_move_reason_widget_unit),
    moveReasonUnspecified = context.getString(R.string.manual_organization_preview_move_reason_unspecified),
    preservedReasonLocked = context.getString(R.string.manual_organization_preview_preserved_reason_locked),
    preservedReasonReservedRegion = context.getString(R.string.manual_organization_preview_preserved_reason_reserved_region),
    preservedReasonUnavailable = context.getString(R.string.manual_organization_preview_preserved_reason_unavailable),
    preservedReasonDock = context.getString(R.string.manual_organization_preview_preserved_reason_dock),
    preservedReasonWidget = context.getString(R.string.manual_organization_preview_preserved_reason_widget),
    preservedReasonAppPair = context.getString(R.string.manual_organization_preview_preserved_reason_app_pair),
    preservedReasonLegacyShortcut = context.getString(R.string.manual_organization_preview_preserved_reason_legacy_shortcut),
    preservedReasonNonTarget = context.getString(R.string.manual_organization_preview_preserved_reason_non_target),
    preservedReasonStrategyPreserved = context.getString(R.string.manual_organization_preview_preserved_reason_strategy),
    preservedReasonStructural = context.getString(R.string.manual_organization_preview_preserved_reason_structural),
    preservedReasonAlreadyCanonical = context.getString(R.string.manual_organization_preview_preserved_reason_already_canonical),
    warningLegacyShortcutReview = context.getString(R.string.manual_organization_preview_warning_legacy_shortcut_item),
    warningFallbackCategory = context.getString(R.string.manual_organization_preview_warning_fallback_category_item),
    warningUnavailablePreserved = context.getString(R.string.manual_organization_preview_warning_unavailable_item),
    pagePosition = context.getString(R.string.manual_organization_preview_page),
    newPagePosition = context.getString(R.string.manual_organization_preview_new_page_position),
    workspacePosition = context.getString(R.string.manual_organization_preview_position_workspace),
    regionTopLeft = context.getString(R.string.manual_organization_preview_region_top_left),
    regionTopCenter = context.getString(R.string.manual_organization_preview_region_top_center),
    regionTopRight = context.getString(R.string.manual_organization_preview_region_top_right),
    regionMiddleLeft = context.getString(R.string.manual_organization_preview_region_middle_left),
    regionMiddleCenter = context.getString(R.string.manual_organization_preview_region_middle_center),
    regionMiddleRight = context.getString(R.string.manual_organization_preview_region_middle_right),
    regionBottomLeft = context.getString(R.string.manual_organization_preview_region_bottom_left),
    regionBottomCenter = context.getString(R.string.manual_organization_preview_region_bottom_center),
    regionBottomRight = context.getString(R.string.manual_organization_preview_region_bottom_right),
    dockPosition = context.getString(R.string.manual_organization_preview_position_dock),
    folderPositionExisting = context.getString(R.string.manual_organization_preview_position_folder_existing),
    folderPositionPlanned = context.getString(R.string.manual_organization_preview_position_folder_planned),
    appPairPosition = context.getString(R.string.manual_organization_preview_position_app_pair),
    unidentifiedPosition = context.getString(R.string.manual_organization_preview_position_unidentified),
    positionWithSupplement = context.getString(R.string.manual_organization_preview_position_with_supplement),
    supplementCell = context.getString(R.string.manual_organization_preview_supplement_cell),
    supplementParentWithStage = context.getString(R.string.manual_organization_preview_supplement_parent_with_stage),
    supplementStageTop = context.getString(R.string.manual_organization_preview_supplement_stage_top),
    supplementStageBottom = context.getString(R.string.manual_organization_preview_supplement_stage_bottom),
    kindApplication = context.getString(R.string.manual_organization_preview_kind_application),
    kindDeepShortcut = context.getString(R.string.manual_organization_preview_kind_deep_shortcut),
    kindShortcutLegacy = context.getString(R.string.manual_organization_preview_kind_shortcut_legacy),
    kindFolder = context.getString(R.string.manual_organization_preview_kind_folder),
    kindAppWidget = context.getString(R.string.manual_organization_preview_kind_app_widget),
    kindCustomAppWidget = context.getString(R.string.manual_organization_preview_kind_custom_app_widget),
    kindAppPair = context.getString(R.string.manual_organization_preview_kind_app_pair),
    kindUnknown = context.getString(R.string.manual_organization_preview_kind_unknown),
    newFolderRow = context.getString(R.string.manual_organization_preview_new_folder_row),
    newPageRow = context.getString(R.string.manual_organization_preview_new_page_row),
)

/** Resource-backed [OrganizationPreviewWording]; all values resolved up front. */
private class ResourceOrganizationPreviewWording(
    override val groupMoved: String,
    override val groupAdded: String,
    override val addDescriptor: String,
    override val addRow: String,
    override val groupNewFolders: String,
    override val groupNewPages: String,
    override val groupPreserved: String,
    override val groupWarnings: String,
    override val moveRow: String,
    override val sameBandMoveRow: String,
    override val rowOrdinalNote: String,
    override val workspaceDestination: String,
    override val itemRow: String,
    override val itemDescriptor: String,
    override val itemDescriptorWithoutKind: String,
    override val moveReasonSinglePlacement: String,
    override val moveReasonFolderMember: String,
    override val moveReasonFolderUnit: String,
    override val moveReasonWidgetUnit: String,
    override val moveReasonUnspecified: String,
    override val preservedReasonLocked: String,
    override val preservedReasonReservedRegion: String,
    override val preservedReasonUnavailable: String,
    override val preservedReasonDock: String,
    override val preservedReasonWidget: String,
    override val preservedReasonAppPair: String,
    override val preservedReasonLegacyShortcut: String,
    override val preservedReasonNonTarget: String,
    override val preservedReasonStrategyPreserved: String,
    override val preservedReasonStructural: String,
    override val preservedReasonAlreadyCanonical: String,
    override val warningLegacyShortcutReview: String,
    override val warningFallbackCategory: String,
    override val warningUnavailablePreserved: String,
    override val pagePosition: String,
    override val newPagePosition: String,
    override val workspacePosition: String,
    override val regionTopLeft: String,
    override val regionTopCenter: String,
    override val regionTopRight: String,
    override val regionMiddleLeft: String,
    override val regionMiddleCenter: String,
    override val regionMiddleRight: String,
    override val regionBottomLeft: String,
    override val regionBottomCenter: String,
    override val regionBottomRight: String,
    override val dockPosition: String,
    override val folderPositionExisting: String,
    override val folderPositionPlanned: String,
    override val appPairPosition: String,
    override val unidentifiedPosition: String,
    override val positionWithSupplement: String,
    override val supplementCell: String,
    override val supplementParentWithStage: String,
    override val supplementStageTop: String,
    override val supplementStageBottom: String,
    override val kindApplication: String,
    override val kindDeepShortcut: String,
    override val kindShortcutLegacy: String,
    override val kindFolder: String,
    override val kindAppWidget: String,
    override val kindCustomAppWidget: String,
    override val kindAppPair: String,
    override val kindUnknown: String,
    override val newFolderRow: String,
    override val newPageRow: String,
) : OrganizationPreviewWording

/** Groups larger than this show the first rows plus a per-group expand action. */
private const val PREVIEW_ROWS_BEFORE_EXPANSION = 5

@Composable
private fun SummaryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private fun movedReasonString(reason: PlacementCode): Int = when (reason) {
    PlacementCode.SINGLE_PLACEMENT -> R.string.manual_organization_moved_single_placement
    PlacementCode.FOLDER_MEMBER -> R.string.manual_organization_moved_folder_member
    PlacementCode.FOLDER_UNIT -> R.string.manual_organization_moved_folder_unit
    PlacementCode.WIDGET_UNIT -> R.string.manual_organization_moved_widget_unit
}

private fun preservedReasonString(reason: PreserveReason): Int = when (reason) {
    PreserveReason.LOCKED -> R.string.manual_organization_preserved_locked
    PreserveReason.RESERVED_REGION -> R.string.manual_organization_preserved_reserved_region
    PreserveReason.UNAVAILABLE_TARGET -> R.string.manual_organization_preserved_unavailable
    PreserveReason.DOCK -> R.string.manual_organization_preserved_dock
    PreserveReason.WIDGET -> R.string.manual_organization_preserved_widget
    PreserveReason.APP_PAIR -> R.string.manual_organization_preserved_app_pair
    PreserveReason.LEGACY_SHORTCUT -> R.string.manual_organization_preserved_legacy_shortcut
    PreserveReason.NON_TARGET -> R.string.manual_organization_preserved_non_target
    PreserveReason.STRATEGY_PRESERVED -> R.string.manual_organization_preserved_strategy
    PreserveReason.STRUCTURAL -> R.string.manual_organization_preserved_structural
    PreserveReason.ALREADY_CANONICAL -> R.string.manual_organization_preserved_already_canonical
}

private fun unplacedReasonString(reason: UnplacedReason): Int = when (reason) {
    UnplacedReason.EXCEEDS_GRID_DIMENSIONS -> R.string.manual_organization_unplaced_grid
    UnplacedReason.TARGET_UNAVAILABLE -> R.string.manual_organization_unplaced_target
    UnplacedReason.STRATEGY_SCOPE_FULL -> R.string.manual_organization_unplaced_strategy_scope
}

private fun rejectionReasonString(reason: RejectionCode): Int = when (reason) {
    RejectionCode.UNKNOWN_ITEM_KIND -> R.string.manual_organization_rejection_unknown_item_kind

    RejectionCode.INVALID_CONTAINER -> R.string.manual_organization_rejection_invalid_container

    RejectionCode.UNKNOWN_PAGE -> R.string.manual_organization_rejection_unknown_page

    RejectionCode.BOUNDS_VIOLATION -> R.string.manual_organization_rejection_bounds

    RejectionCode.OVERLAP -> R.string.manual_organization_rejection_overlap

    RejectionCode.DANGLING_REFERENCE -> R.string.manual_organization_rejection_dangling_reference

    RejectionCode.MALFORMED_APP_PAIR -> R.string.manual_organization_rejection_malformed_app_pair

    RejectionCode.LOCKED_OUT_OF_BOUNDS -> R.string.manual_organization_rejection_locked_out_of_bounds

    RejectionCode.DUPLICATE_TARGET -> R.string.manual_organization_rejection_duplicate_target

    RejectionCode.MISSING_TARGET -> R.string.manual_organization_rejection_missing_target

    RejectionCode.INCOMPLETE_TARGET_PARTITION -> R.string.manual_organization_rejection_incomplete_target_partition

    RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION -> R.string.manual_organization_rejection_additions

    RejectionCode.INVALID_RULES -> R.string.manual_organization_rejection_invalid_rules

    RejectionCode.DUPLICATE_ITEM_ID -> R.string.manual_organization_rejection_duplicate_item

    RejectionCode.DUPLICATE_PAGE -> R.string.manual_organization_rejection_duplicate_page

    RejectionCode.INVALID_DIMENSIONS -> R.string.manual_organization_rejection_invalid_dimensions

    RejectionCode.KIND_TARGET_MISMATCH -> R.string.manual_organization_rejection_kind_target_mismatch

    RejectionCode.TARGET_PROFILE_MISMATCH -> R.string.manual_organization_rejection_target_profile_mismatch

    RejectionCode.UNKNOWN_SIGNAL_ITEM -> R.string.manual_organization_rejection_unknown_signal_item

    RejectionCode.UNKNOWN_CATEGORY -> R.string.manual_organization_rejection_unknown_category

    // Issue #336: no automatic-inference source may target a user-defined
    // category; the planner rejects such signals as a typed failure.
    RejectionCode.INVALID_CATEGORY_PROVENANCE -> R.string.manual_organization_rejection_invalid_category_provenance
}

private fun availabilityString(availability: Availability): Int = when (availability) {
    Availability.AVAILABLE -> R.string.manual_organization_available_constraint
    Availability.DISABLED -> R.string.manual_organization_disabled_constraint
    Availability.QUIET -> R.string.manual_organization_quiet_constraint
    Availability.LOCKED_PRIVATE_SPACE -> R.string.manual_organization_private_space_constraint
    Availability.UNAVAILABLE -> R.string.manual_organization_unavailable_item_constraint
}

private fun warningString(code: WarningCode): Int = when (code) {
    WarningCode.LEGACY_SHORTCUT_REVIEW -> R.string.manual_organization_warning_legacy_shortcut
    WarningCode.FALLBACK_CATEGORY -> R.string.manual_organization_warning_fallback_category
    WarningCode.UNAVAILABLE_PRESERVED -> R.string.manual_organization_warning_unavailable
}

/**
 * Issue #172: `ReconciliationPending` is the "model still loading / try again
 * later" family; every other readiness reason is a source/config problem that
 * warrants a bug report. The copy split follows the diagnostics contract §2
 * (user-facing reason is derived from typed results, never from the journal).
 */
private fun app.lawnchair.organizer.integration.InputReadinessReason.copyKind(): Int = when (this) {
    app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending -> R.string.manual_organization_input_not_ready_yet
    else -> R.string.manual_organization_input_unavailable_bug
}

@Composable
private fun applyMessage(result: ApplyResult): String = stringResource(
    when (result) {
        is ApplyResult.NoChanges -> R.string.manual_organization_no_changes
        is ApplyResult.Applied -> R.string.manual_organization_apply_success
        is ApplyResult.Rejected -> R.string.manual_organization_apply_rejected
        is ApplyResult.RolledBack -> R.string.manual_organization_apply_rolled_back
        is ApplyResult.Recovered -> R.string.manual_organization_apply_recovered
        is ApplyResult.Unresolved -> R.string.manual_organization_apply_unresolved
        is ApplyResult.RecoveryFailed -> R.string.manual_organization_apply_recovery_failed
        ApplyResult.ConcurrentRun -> R.string.manual_organization_apply_concurrent
    },
)

/**
 * Issue #230: one history line for the correlated verified apply, phrased as
 * apply history (spec D4). Only non-zero segments are joined; the caller
 * omits the line entirely when the apply changed nothing. Counts resolve
 * through plurals so singular counts read naturally.
 */
@Composable
private fun recoveryHistoryLine(summary: ManualOrganizationRun.Summary): String {
    val segments = listOfNotNull(
        summary.movedCount.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.manual_organization_recovery_history_moved, it, it) },
        summary.newFolderCount.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.manual_organization_recovery_history_new_folders, it, it) },
        summary.newPageCount.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.manual_organization_recovery_history_new_pages, it, it) },
    )
    return stringResource(R.string.manual_organization_recovery_history_prefix, segments.joinToString(" / "))
}

@Composable
private fun recoveryPreviewMessage(result: RecoveryPreviewResult): String = stringResource(
    when (result) {
        is RecoveryPreviewResult.Restorable -> R.string.manual_organization_recovery_preview

        is RecoveryPreviewResult.NotRestorable -> R.string.manual_organization_recovery_not_available

        is RecoveryPreviewResult.Unavailable -> R.string.manual_organization_recovery_not_available

        RecoveryPreviewResult.WriterBusy,
        RecoveryPreviewResult.Concurrent,
        -> R.string.manual_organization_apply_concurrent
    },
)

@Composable
private fun recoveryResultMessage(result: RecoveryResult): String = stringResource(
    when (result) {
        is RecoveryResult.Restored -> R.string.manual_organization_recovery_restored

        is RecoveryResult.NotRestorable -> R.string.manual_organization_recovery_not_available

        is RecoveryResult.RestoreFailed -> R.string.manual_organization_recovery_failed

        RecoveryResult.WriterBusy,
        RecoveryResult.ConcurrentRun,
        -> R.string.manual_organization_apply_concurrent
    },
)

private fun ApplyResult.requiresSafeSupport(): Boolean = this is ApplyResult.Unresolved || this is ApplyResult.RecoveryFailed

private fun RecoveryResult.requiresSafeSupport(): Boolean = this is RecoveryResult.RestoreFailed
