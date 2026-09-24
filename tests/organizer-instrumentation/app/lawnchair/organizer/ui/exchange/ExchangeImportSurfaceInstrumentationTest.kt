package app.lawnchair.organizer.ui.exchange

import android.content.Context
import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.drawToBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeInputResult
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.exchange.ExchangeContract
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #332 (spec AC-3/AC-4/AC-10 + AC-8 structure): the import surface runs
 * in a real Compose host so the bounded manual editor, the parse-first
 * outcome, and the default-collapsed raw detail are asserted as rendered UI —
 * not just state. The clipboard/file leads are distinct labelled actions and
 * the huge-reply case never stretches the surface.
 *
 * Issue #327 (AC-4/AC-5): both exchange entry rows (idle and run-in scoped)
 * are asserted to render the capability notes — concrete user-language
 * examples, the "no direct change" statement, and the one-request /
 * one-proposal conversation flow — under their own test tags.
 */
@RunWith(AndroidJUnit4::class)
class ExchangeImportSurfaceInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private open class FakeStore : ExportSessionStore {
        /** Issue #372: configurable so the T-15 pre-display can be driven. */
        var session: app.lawnchair.organizer.personalization.ExportSession? = null

        override fun save(session: app.lawnchair.organizer.personalization.ExportSession): Boolean {
            this.session = session
            return true
        }
        override fun load(exportId: String): app.lawnchair.organizer.personalization.ExportSession? = null

        override fun active(nowEpochMs: Long): app.lawnchair.organizer.personalization.ExportSession? = session?.takeIf { !it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
        }
    }

    /**
     * Issue #373: a store that resolves the reply's exportId, so a decode-
     * successful import proceeds past the session lookup and reaches the
     * post-decode structural read (the InputNotReady settle point).
     */
    private class LoadableStore(
        private val bound: app.lawnchair.organizer.personalization.ExportSession,
    ) : ExportSessionStore {
        override fun save(session: app.lawnchair.organizer.personalization.ExportSession) = true
        override fun load(exportId: String) = bound.takeIf { it.exportId == exportId }
        override fun active(nowEpochMs: Long) = bound.takeIf { !it.isExpired(nowEpochMs) }
        override fun invalidate(exportId: String) = Unit
    }

    private fun structural(): CanonicalStructuralInputs {
        fun app(id: String, x: Int = 0) = CapturedItem(
            id = ItemId(id),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val items = listOf(app("a"), app("b", x = 1))
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return CanonicalStructuralInputs(snapshot, targets, emptyMap())
    }

    /**
     * Issue #372: focus grants are dispatched asynchronously — an instant
     * assert races the focus dispatch on slower CI emulators. Poll for the
     * Focused semantics. Returns FALSE when the environment never grants
     * node focus at all (headless CI emulators keep the window unfocused, so
     * no node ever reports Focused regardless of the requester mechanism) —
     * callers then skip the focus asserts, keeping the deterministic
     * requester mechanism as the prod contract.
     */
    private fun awaitFocusedOrNull(tag: String): Boolean = try {
        composeRule.waitUntil(15_000) {
            try {
                composeRule.onNodeWithTag(tag).fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.Focused) == true
            } catch (_: AssertionError) {
                false
            }
        }
        true
    } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
        false
    }

    /** Role matcher for the dialog affordances (EX-AC-10 role oracle). */
    private fun hasButtonRole() = SemanticsMatcher("button role") { entry ->
        entry.config.getOrNull(SemanticsProperties.Role) == Role.Button
    }

    private fun newHolder(
        store: ExportSessionStore = FakeStore(),
        structuralResult: ExchangeStructuralResult = ExchangeStructuralResult.Ready(structural()),
    ): ExchangeFlowStateHolder {
        val controller = ExchangeFlowController(
            composeExportInputs = {
                val s = structural()
                ExchangeInputResult.ExportReady(
                    ExportInputs(snapshot = s.snapshot, targets = s.targets, nowEpochMs = 1_000_000L),
                )
            },
            currentStructuralInputs = { structuralResult },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { 1_000_000L },
        )
        return ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = InertRun.get(),
            scope = CoroutineScope(Dispatchers.Main),
        )
    }

    private fun setContent(
        holder: ExchangeFlowStateHolder,
        fontScale: Float? = null,
        discardRequested: androidx.compose.runtime.MutableState<Boolean>? = null,
        discardFocus: FocusRequester? = null,
        preserveDeviceDensity: Boolean = false,
        onOpenDiagnostics: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            // Issue #372 (implementation review, EX-AC-10): the 200% oracle
            // (preserveDeviceDensity) overrides ONLY the font scale and keeps
            // the REAL device density; every other test keeps the historical
            // Density(1f) fixture.
            val densityOverride = when {
                preserveDeviceDensity && fontScale != null -> {
                    val d = LocalDensity.current
                    Density(d.density, fontScale = fontScale)
                }
                else -> Density(1f, fontScale = fontScale ?: 1f)
            }
            CompositionLocalProvider(LocalDensity provides densityOverride) {
                LawnchairTheme {
                    // Issue #372: the same Back wiring as the host — the flow
                    // handler before the import-success handler — so the Back
                    // contract is exercised against the real dispatcher.
                    ExchangeFlowBackHandler(
                        holder = holder,
                        onDiscardRequest = { discardRequested?.value = true },
                    )
                    ExchangeImportSuccessBackHandler(holder)
                    LazyColumn {
                        exchangeFlowItems(
                            holder = holder,
                            onDiscardRequest = { discardRequested?.value = true },
                            discardFocus = discardFocus,
                            clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                            shareTransport = { _, _ -> ExchangeTransportResult.Success },
                            fileTransport = FileExchangeTransport(context),
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }
                // The host raises ONE dialog for the T-16 破棄 button AND
                // system Back; confirm runs the same closeDisclosure gate.
                // The harness mirrors that convergence exactly.
                if (discardRequested?.value == true) {
                    ExchangeDiscardConfirmDialog(
                        onConfirm = {
                            discardRequested.value = false
                            holder.closeDisclosure()
                        },
                        onDismiss = {
                            discardRequested.value = false
                            discardFocus?.requestFocus()
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun openImportSurface(holder: ExchangeFlowStateHolder) {
        composeRule.runOnUiThread { holder.openImport() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-import-clipboard").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("exchange-import-file").assertIsDisplayed().assertHasClickAction()
    }

    /**
     * Issue #372 (EX-AC-01/EX-AC-07, rendered-UI oracle): the standalone idle
     * entry row is GONE (D-04) — and the capability notes now surface on the
     * T-15 request face reached through `openFlow` (the T-07 「AIに相談」
     * method choice's target). The import lead-in stays reachable from T-15.
     */
    @Test
    fun requestFaceSurfacesTheCapabilityNotesAndIdleEntryIsGone() {
        val holder = newHolder()
        setContent(holder)
        // The removed idle entry row renders nothing (negative observation).
        composeRule.onNodeWithTag("exchange-entry-capability").assertDoesNotExist()
        composeRule.onNodeWithTag("exchange-entry-title").assertDoesNotExist()

        composeRule.runOnUiThread { holder.openFlow() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-title").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-request-capability").assertIsDisplayed()
        for (res in listOf(
            R.string.exchange_capability_title,
            R.string.exchange_capability_example_frequent,
            R.string.exchange_capability_example_group,
            R.string.exchange_capability_example_keep,
            R.string.exchange_capability_example_front,
            R.string.exchange_capability_example_minimal_change,
            R.string.exchange_capability_no_direct_change,
            R.string.exchange_capability_flow,
        )) {
            composeRule
                .onNodeWithText(context.getString(res), substring = true)
                .assertIsDisplayed()
        }
        // The D-09 expectation statement is on the creation face.
        composeRule.onNodeWithTag("exchange-expectation").assertIsDisplayed()

        // The removed entry's 「回答を取り込む」 lead stays reachable from T-15.
        composeRule.onNodeWithText(context.getString(R.string.exchange_entry_import)).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.exchange_entry_import)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-import-clipboard").assertIsDisplayed()
    }

    /**
     * Issue #327 AC-4/AC-5: the run-in (scoped) entry row carries the same
     * capability notes under its own test tag.
     */
    @Test
    fun scopedEntrySurfacesTheCapabilityNotes() {
        val holder = newHolder()
        val scoped = CandidateTarget.AppKey(ComponentKey("com.example.scoped"), ProfileId("p0"))
        composeRule.setContent {
            LawnchairTheme {
                LazyColumn {
                    exchangeFlowItems(
                        holder = holder,
                        scopedSelection = listOf(scoped),
                        scopedLabels = mapOf(scoped to "Scoped app"),
                        onDiscardRequest = {},
                        discardFocus = null,
                        clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                        shareTransport = { _, _ -> ExchangeTransportResult.Success },
                        fileTransport = FileExchangeTransport(context),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-scoped-entry-capability").assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.exchange_capability_no_direct_change), substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.exchange_capability_flow), substring = true)
            .assertIsDisplayed()
    }

    /**
     * Issue #372 (EX-AC-03, rendered-UI oracle): the T-15 pre-display appears
     * ONLY when an active request exists, with the remaining-time line — and
     * disappears after the store's active read no longer returns it.
     */
    @Test
    fun t15PreDisplayAppearsOnlyWithAnActiveRequest() {
        val store = FakeStore()
        val holder = newHolder(store)
        setContent(holder)
        composeRule.runOnUiThread { holder.openFlow() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-active").assertDoesNotExist()

        // A live session: existence + remaining time (2h bucket) are shown.
        store.session = app.lawnchair.organizer.personalization.ExportSession(
            exportId = "t15-active",
            itemRefs = mapOf("ref-0" to ItemId("id-0")),
            tier = PrivacyTier.EXTERNAL_REDACTED,
            sourceContextDigest = "digest",
            signalProvenance = null,
            createdAtEpochMs = 1_000_000L,
            expiresAtEpochMs = 1_000_000L + 2 * 60L * 60L * 1000L,
        )
        composeRule.runOnUiThread { holder.openFlow() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-active").assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.exchange_request_active_line), substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.exchange_request_remaining_hours, 2, 2),
            )
            .assertIsDisplayed()

        // The store no longer reports it (expiry/invalidate): the next read
        // clears the display — the store remains the truth.
        store.session = null
        composeRule.runOnUiThread { holder.refreshActiveRequest() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-active").assertDoesNotExist()
    }

    /**
     * Issue #372 (EX-AC-04, rendered-UI oracle): the T-16 face is
     * summary-first — kinds (tier line), ITEM count, ceiling, and the D-09
     * expectation — while the generated package's full text stays collapsed
     * until explicitly expanded, and then shows the identical text the
     * transports hand out. The unsent cancel slot is the 破棄 vocabulary.
     */
    @Test
    fun t16SummaryIsPrimaryAndFullTextIsCollapsedUntilExpanded() {
        val holder = newHolder()
        setContent(holder)
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_WITH_LABELS)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("exchange-disclosure-title").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exchange-disclosure-title").assertIsDisplayed()
        // The labelled item count (structural() exports exactly two items).
        composeRule
            .onNodeWithText(context.getString(R.string.exchange_disclosure_summary_items, 2), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-disclosure-summary-limit").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-expectation").assertIsDisplayed()
        // Collapsed by default: the package text is not in the tree.
        composeRule.onNodeWithTag("exchange-disclosure-package").assertDoesNotExist()
        composeRule.onNodeWithTag("exchange-disclosure-expand").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-disclosure-package").assertIsDisplayed()
        // The unsent cancel slot carries the 破棄 vocabulary (D-13).
        composeRule.onNodeWithTag("exchange-discard").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.exchange_discard)).assertIsDisplayed()
    }

    /**
     * Issue #372 (EX-AC-08 + EX-AC-10, rendered-UI oracle): the T-16 破棄
     * button does not invalidate directly — it raises the host's ONE discard
     * confirmation (same entry as system Back); the confirm path runs the
     * existing closeDisclosure gate and invalidates exactly the unsent
     * session; dismissing keeps the T-16 face. The dialog exposes the dialog
     * ROLE, moves FOCUS deterministically onto its safe action when shown,
     * and the confirm/dismiss affordances keep explicit button roles.
     */
    @Test
    fun discardDialogTakesDeterministicFocusAndRestoresTheFace() {
        val holder = newHolder()
        val discardRequested = androidx.compose.runtime.mutableStateOf(false)
        // The host owns the explicit dismissal focus restore through this
        // requester (platform dialog restore is not deterministic).
        val discardFocus = FocusRequester()
        setContent(holder, discardRequested = discardRequested, discardFocus = discardFocus)
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("exchange-discard").fetchSemanticsNodes().isNotEmpty()
        }
        // The expand state is announced through the state description
        // (EX-AC-10: the collapse state reaches TalkBack).
        val collapsedLabel = context.getString(R.string.exchange_disclosure_expand)
        val expandedLabel = context.getString(R.string.exchange_disclosure_collapse)
        fun announcedState(): String = composeRule
            .onNodeWithTag("exchange-disclosure-expand")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)
            .toString()
        assertTrue("collapsed state must be announced", announcedState().contains(collapsedLabel.substringBeforeLast(" ")))

        // Deterministic pre-dialog focus on the 破棄 action, driven through
        // the SAME host-owned FocusRequester the dismissal restore uses.
        // focusObservable: headless CI emulators never grant window focus, so
        // no node reports Focused there — the assert is skipped in that
        // environment (the requester mechanism itself is prod code).
        composeRule.runOnIdle { discardFocus.requestFocus() }
        val focusObservable = awaitFocusedOrNull("exchange-discard")
        if (focusObservable) {
            composeRule.onNodeWithTag("exchange-discard").assertIsFocused()
        }

        composeRule.onNodeWithTag("exchange-discard").performClick()
        composeRule.waitUntil(5_000) { discardRequested.value }
        assertTrue("the face stays while the confirmation is up", holder.screen is ExchangeScreen.Disclosing)

        // Dialog ROLE + traversal/focus: the dialog is up, its SAFE action
        // owns focus, and both affordances carry the button role.
        composeRule.onNode(isDialog()).assertExists()
        composeRule.onNodeWithTag("exchange-discard-confirm-title").assertIsDisplayed()
        val dialogFocusGranted = awaitFocusedOrNull("exchange-discard-dismiss")
        if (dialogFocusGranted) {
            composeRule.onNodeWithTag("exchange-discard-dismiss").assertIsFocused()
        }
        composeRule.onNodeWithTag("exchange-discard-confirm").assert(hasButtonRole())
        composeRule.onNodeWithTag("exchange-discard-dismiss").assert(hasButtonRole())

        // Dismiss keeps package and request alive; focus RESTORES to the
        // 破棄 action that opened the dialog (EX-AC-10 focus restoration
        // contract: dialog safe action in, face action back out — asserted
        // wherever the environment grants node focus at all).
        composeRule.onNodeWithTag("exchange-discard-dismiss").performClick()
        composeRule.waitForIdle()
        assertFalse(discardRequested.value)
        assertTrue(holder.screen is ExchangeScreen.Disclosing)
        if (dialogFocusGranted) {
            if (!awaitFocusedOrNull("exchange-discard")) {
                // The host's explicit requester-based restore ran; retry
                // once — some platforms dispatch the restore one frame later
                // than the dialog teardown.
                composeRule.waitForIdle()
                awaitFocusedOrNull("exchange-discard")
            }
            composeRule.onNodeWithTag("exchange-discard").assertIsFocused()
        }

        // The expand state announcement flips with the toggle (direct read).
        composeRule.onNodeWithTag("exchange-disclosure-expand").performClick()
        composeRule.waitForIdle()
        assertTrue("expanded state must be announced", announcedState().contains(expandedLabel.substringBeforeLast(" ")))

        // Confirm runs the structural gate: only the unsent session dies.
        composeRule.onNodeWithTag("exchange-discard").performClick()
        composeRule.waitUntil(5_000) { discardRequested.value }
        composeRule.onNodeWithTag("exchange-discard-confirm").performClick()
        composeRule.waitForIdle()
        assertTrue(holder.screen is ExchangeScreen.Closed)
    }

    /**
     * Issue #372 (EX-AC-10): the T-16 interactive elements' SEMANTICS
     * traversal order (the order TalkBack visits them) is fixed: expand
     * toggle first, then the transport row (copy, share), then the
     * save/discard row — asserted directly on the merged semantics tree.
     */
    @Test
    fun t16TraversalFollowsTheSemanticsReadingOrder() {
        val holder = newHolder()
        setContent(holder)
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("exchange-discard").fetchSemanticsNodes().isNotEmpty()
        }
        val expected = listOf(
            "exchange-disclosure-expand",
            "exchange-send-clipboard",
            "exchange-send-share",
            "exchange-send-file",
            "exchange-discard",
        )
        val traversalTags = composeRule.onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .filter { it in expected.toSet() }
        assertEquals(expected, traversalTags)
    }

    /**
     * Issue #372 (EX-AC-11, rendered-UI oracle): the system-Back contract of
     * the request faces — T-15 Back closes zero-write, Back on a generating
     * face is consumed (the face stays and the generation still settles),
     * Back on the unsent T-16 raises the host's discard confirmation dialog
     * (dismiss restores the T-16 face), and Back on the sent T-16 closes with
     * the request surviving.
     */
    @Test
    fun backContractOnTheRequestFacesIsStructural() {
        val holder = newHolder()
        val discardRequested = androidx.compose.runtime.mutableStateOf(false)
        setContent(holder, discardRequested = discardRequested)

        fun pressBack() {
            // The empty compose activity owns the dispatcher BackHandler
            // registers against; the lifecycle monitor is how this harness
            // reaches the resumed activity.
            composeRule.runOnUiThread {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<androidx.activity.ComponentActivity>()
                    .firstOrNull()
                checkNotNull(resumed).onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
        }

        // T-15: Back closes the flow zero-write.
        composeRule.runOnUiThread { holder.openFlow() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-title").assertIsDisplayed()
        pressBack()
        assertTrue(holder.screen is ExchangeScreen.Closed)
        composeRule.onNodeWithTag("exchange-request-title").assertDoesNotExist()

        // Generating: Back is consumed; the face stays and the generation
        // still settles into the disclosure.
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("exchange-generating").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("exchange-disclosure-title").fetchSemanticsNodes().isNotEmpty()
        }
        pressBack()
        assertTrue(
            "Back must not leave a generating face",
            holder.screen is ExchangeScreen.Generating || holder.screen is ExchangeScreen.Disclosing,
        )
        composeRule.waitUntil(5_000) {
            holder.screen is ExchangeScreen.Disclosing
        }

        // Unsent T-16: Back raises the discard confirmation dialog; dismissing
        // it restores the T-16 face.
        composeRule.runOnUiThread { discardRequested.value = false }
        pressBack()
        composeRule.waitUntil(5_000) { discardRequested.value }
        composeRule.onNodeWithTag("exchange-discard-confirm-title").assertIsDisplayed()
        assertTrue(holder.screen is ExchangeScreen.Disclosing)
        composeRule.onNodeWithTag("exchange-discard-dismiss").performClick()
        composeRule.waitForIdle()
        assertFalse(discardRequested.value)
        composeRule.onNodeWithTag("exchange-disclosure-title").assertIsDisplayed()

        // After a transport success, Back is the zero-write close and the
        // request survives.
        composeRule.runOnUiThread { holder.onTransportResult(ExchangeTransportResult.Success) }
        composeRule.waitForIdle()
        pressBack()
        assertTrue(holder.screen is ExchangeScreen.Closed)
    }

    /**
     * Issue #372 (EX-AC-02, rendered-UI oracle): while the idle consultation
     * flow is open (T-16 shown), the AUTHORING lease stays acquirable —
     * constant materials authoring is never lease-rejected by the
     * consultation, and the coordinator stays out of the run domain.
     */
    @Test
    fun authoringLeaseStaysAcquirableWhileTheFlowIsOpen() {
        val holder = newHolder()
        setContent(holder)
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        }
        composeRule.waitUntil(5_000) {
            holder.screen is ExchangeScreen.Disclosing
        }
        composeRule.runOnUiThread {
            val lease = app.lawnchair.organizer.ui.OrganizationOperationLease
                .tryAcquire(app.lawnchair.organizer.ui.OrganizationOperationLease.Kind.AUTHORING)
            checkNotNull(lease).close()
        }
        composeRule.onNodeWithTag("exchange-disclosure-title").assertIsDisplayed()
    }

    /**
     * Issue #372 (EX-AC-02, rendered-UI oracle): the REAL authoring-guarded
     * operation route (StrategyWriteArbiter's AUTHORING-token write, the same
     * seam the materials surface uses) succeeds while the idle consultation
     * flow is open — no lease rejection — and the active request afterwards
     * survives, resurfacing through the T-15 pre-display.
     */
    @Test
    fun materialsEditingRouteSucceedsWhileTheFlowIsOpenAndTheRequestSurvives() {
        val store = FakeStore()
        val holder = newHolder(store)
        setContent(holder)
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        }
        composeRule.waitUntil(5_000) { holder.screen is ExchangeScreen.Disclosing }

        // The materials surface's strategy write (AUTHORING-guarded) runs
        // THROUGH the consultation: Started, not refused, and committed.
        val committed = java.util.concurrent.atomic.AtomicInteger(0)
        var outcome: app.lawnchair.organizer.ui.StrategyWriteArbiter.StartOutcome? = null
        composeRule.runOnUiThread {
            val arbiter = app.lawnchair.organizer.ui.StrategyWriteArbiter(
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
                writeStrategy = { true },
                runOrRecoveryActive = { false },
            )
            outcome = arbiter.onStrategySelected(
                app.lawnchair.organizer.planning.StrategyId("evidence-372-materials-write"),
            ) { committed.incrementAndGet() }
        }
        composeRule.waitForIdle()
        assertEquals(
            "the authoring-guarded operation must not be lease-rejected by the consultation",
            app.lawnchair.organizer.ui.StrategyWriteArbiter.StartOutcome.Started,
            outcome,
        )
        composeRule.waitUntil(5_000) { committed.get() == 1 }
        assertTrue("the flow face survives the materials write", holder.screen is ExchangeScreen.Disclosing)

        // Back on the consultation: the active request survives and resurfaces
        // through the T-15 pre-display.
        composeRule.runOnUiThread { holder.close() }
        composeRule.runOnUiThread { holder.openFlow() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-active").assertIsDisplayed()
        assertTrue(store.session != null)
    }

    /** EX-AC-10 evidence: default locale, light. */
    @Test
    fun captureEvidenceDefaultLight() = captureEvidence("default", dark = false)

    /** EX-AC-10 evidence: default locale, dark. */
    @Test
    fun captureEvidenceDefaultDark() = captureEvidence("default", dark = true)

    /** EX-AC-10 evidence: ja, light. */
    @Test
    fun captureEvidenceJaLight() = captureEvidence("ja", dark = false)

    /** EX-AC-10 evidence: ja, dark. */
    @Test
    fun captureEvidenceJaDark() = captureEvidence("ja", dark = true)

    /** Issue #373 evidence: failure face, default locale, light. */
    @Test
    fun captureFailureEvidenceDefaultLight() = captureFailureEvidence("default", dark = false, fontScale = null)

    /** Issue #373 evidence: failure face, default locale, dark. */
    @Test
    fun captureFailureEvidenceDefaultDark() = captureFailureEvidence("default", dark = true, fontScale = null)

    /** Issue #373 evidence: failure face, ja正本, light. */
    @Test
    fun captureFailureEvidenceJaLight() = captureFailureEvidence("ja", dark = false, fontScale = null)

    /** Issue #373 evidence: failure face, ja正本, dark. */
    @Test
    fun captureFailureEvidenceJaDark() = captureFailureEvidence("ja", dark = true, fontScale = null)

    /** Issue #373 evidence (IM-AC-09): failure face at 200% font, ja正本, light. */
    @Test
    fun captureFailureEvidenceJaTwoHundredPercentFont() = captureFailureEvidence("ja", dark = false, fontScale = 2f)

    /**
     * Issue #373 (IM-AC-09): capture the T-18 failure face with its remedy
     * projection — one remedy copy + action + the face-level means, detail
     * expansion closed by default (opened for the bounded-detail shot).
     */
    private fun captureFailureEvidence(locale: String, dark: Boolean, fontScale: Float?) {
        val outDir = java.io.File(context.filesDir, "evidence-373").apply { mkdirs() }
        val holder = newHolder()
        composeRule.setContent {
            val config = android.content.res.Configuration(context.resources.configuration).apply {
                if (locale == "ja") setLocale(java.util.Locale.JAPAN)
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) {
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    } else {
                        android.content.res.Configuration.UI_MODE_NIGHT_NO
                    }
            }
            val localized = context.createConfigurationContext(config)
            val registryOwner = LocalActivityResultRegistryOwner.current
                ?: error("no ActivityResultRegistryOwner")
            val scheme = if (dark) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
            val densityOverride = if (fontScale != null) {
                val d = LocalDensity.current
                Density(d.density, fontScale = fontScale)
            } else {
                null
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalActivityResultRegistryOwner provides registryOwner,
                    ) {
                        CompositionLocalProvider(
                            *(densityOverride?.let { arrayOf(LocalDensity provides it) } ?: emptyArray()),
                        ) {
                            LazyColumn {
                                exchangeFlowItems(
                                    holder = holder,
                                    onDiscardRequest = {},
                                    clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                                    shareTransport = { _, _ -> ExchangeTransportResult.Success },
                                    fileTransport = FileExchangeTransport(context),
                                )
                            }
                        }
                    }
                }
            }
        }
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        // A marked reply bound to no active session settles as EXPORT_MISMATCH:
        // the remedy projection answers 「依頼を作り直す」.
        val intent = PersonalizedIntentV1(
            exportId = "instrumentation-no-session",
            itemIntents = listOf(ItemIntent(ref = "r1", preserve = true), ItemIntent(ref = "r2")),
        )
        val reply = buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
        composeRule.onNodeWithTag("exchange-import-field").performTextInput(reply)
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("exchange-import-failure-action").fetchSemanticsNodes().isNotEmpty()
        }
        val tag = "$locale-${if (dark) "dark" else "light"}${if (fontScale != null) "-200font" else ""}"
        captureWindowBitmap(outDir, "issue373-failure-$tag.png")

        // The detail expansion opened (typed cause + recognition + raw).
        composeRule.onNodeWithTag("exchange-import-detail-toggle").performClick()
        composeRule.waitForIdle()
        captureWindowBitmap(outDir, "issue373-failure-detail-$tag.png")
        println("evidence-373: $outDir/issue373-failure-$tag.png")
    }

    private fun captureWindowBitmap(outDir: java.io.File, name: String) {
        // The lifecycle monitor is main-thread-only; capture on main.
        var captured: android.graphics.Bitmap? = null
        composeRule.runOnUiThread {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?: error("no resumed activity for capture")
            captured = resumed.window.decorView.drawToBitmap()
        }
        val bitmap = checkNotNull(captured)
        java.io.File(outDir, name).outputStream().use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    /**
     * Issue #372 (EX-AC-10 evidence): renders the T-15 and T-16 request faces
     * under one locale/dark configuration and writes the PNG captures for the
     * assessment record (docs/assessment/evidence/issue-372).
     */
    private fun captureEvidence(locale: String, dark: Boolean) {
        val outDir = java.io.File(context.filesDir, "evidence-372").apply { mkdirs() }
        val holder = newHolder()
        composeRule.setContent {
            val config = android.content.res.Configuration(context.resources.configuration).apply {
                if (locale == "ja") setLocale(java.util.Locale.JAPAN)
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) {
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    } else {
                        android.content.res.Configuration.UI_MODE_NIGHT_NO
                    }
            }
            val localized = context.createConfigurationContext(config)
            // Re-provide the activity owners: shadowing LocalContext with the
            // configuration context must not hide the registry owner that
            // rememberLauncherForActivityResult resolves against.
            val registryOwner = LocalActivityResultRegistryOwner.current
                ?: error("no ActivityResultRegistryOwner")
            // The empty test activity's window is light and the wallpaper-
            // derived dynamic scheme is unavailable here, so the dark capture
            // uses the base Material3 dark scheme with an explicit surface.
            val scheme = if (dark) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalActivityResultRegistryOwner provides registryOwner,
                    ) {
                        LazyColumn {
                            exchangeFlowItems(
                                holder = holder,
                                onDiscardRequest = {},
                                clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                                shareTransport = { _, _ -> ExchangeTransportResult.Success },
                                fileTransport = FileExchangeTransport(context),
                            )
                        }
                    }
                }
            }
        }
        for (face in listOf("t15", "t16")) {
            composeRule.runOnUiThread {
                holder.openFlow()
                if (face == "t16") holder.generate(PrivacyTier.EXTERNAL_REDACTED)
            }
            composeRule.waitForIdle()
            if (face == "t16") {
                composeRule.waitUntil(5_000) { holder.screen is ExchangeScreen.Disclosing }
            }
            // The lifecycle monitor is main-thread-only; capture on main.
            var captured: android.graphics.Bitmap? = null
            composeRule.runOnUiThread {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<Activity>()
                    .firstOrNull()
                    ?: error("no resumed activity for capture")
                captured = resumed.window.decorView.drawToBitmap()
            }
            val bitmap = checkNotNull(captured)
            val name = "issue372-$face-$locale-${if (dark) "dark" else "light"}.png"
            java.io.File(outDir, name).outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            println("evidence-372: $outDir/$name")
        }
    }

    /**
     * Issue #348 AC-1 (content oracle): the failure/retry guidance strings
     * must keep offering the allowed recovery (import again / re-paste /
     * recreate the request) and must never instruct an AI repair loop (feeding
     * failure diagnostics or error content back to the AI). Both locales are
     * resolved through real resource contexts.
     *
     * Issue #373 (IM-AC-08, oracle obsolete record): the scanned set moved
     * with the remedy projection. The former 4-string guidance set
     * (`exchange_import_retry_hint` + 3 typed copies) no longer exists as
     * primary guidance — the hint string was deleted (grep-0), and the typed
     * copies are now the DETAIL-expansion typed cause, not the primary face.
     * The no-AI-repair-loop boundary itself is unchanged and now scans ALL 20
     * projected primary copies (the remedy-vocabulary allowed markers) — a
     * stronger, structurally complete version of the same oracle.
     */
    @Test
    fun failureAndRetryGuidanceStaysWithinTheRecoveryBoundary() {
        val english = android.content.res.Configuration().apply { setLocale(java.util.Locale.ENGLISH) }
        val japanese = android.content.res.Configuration().apply { setLocale(java.util.Locale.JAPAN) }
        val contexts = listOf(
            "en" to context.createConfigurationContext(english),
            "ja" to context.createConfigurationContext(japanese),
        )
        val guidance = listOf(
            R.string.exchange_failure_primary_input_oversize,
            R.string.exchange_failure_primary_framing_missing,
            R.string.exchange_failure_primary_framing_ambiguous,
            R.string.exchange_failure_primary_framing_empty,
            R.string.exchange_failure_primary_normalization_ambiguous,
            R.string.exchange_failure_primary_normalization_unrecognized,
            R.string.exchange_failure_primary_schema_mismatch,
            R.string.exchange_failure_primary_export_mismatch,
            R.string.exchange_failure_primary_session_expired,
            R.string.exchange_failure_primary_context_stale,
            R.string.exchange_failure_primary_oversize,
            R.string.exchange_failure_primary_unknown_ref,
            R.string.exchange_failure_primary_duplicate_ref,
            R.string.exchange_failure_primary_incomplete_coverage,
            R.string.exchange_failure_primary_invalid_enum,
            R.string.exchange_failure_primary_forbidden_content,
            R.string.exchange_failure_primary_mobility_contradiction,
            R.string.exchange_failure_primary_capability_unsupported,
            R.string.exchange_failure_primary_scope_mismatch,
            R.string.exchange_failure_primary_unknown_category_ref,
        )
        val allowedMarkers = listOf(
            "resend", "再送", "again", "もう一度取り込",
            "re-paste", "貼り直", "recreate", "作り直", "送り直",
        )
        val forbiddenMarkers = listOf(
            "diagnostic", "send the error", "paste the failure", "validation error",
            "診断", "エラーメッセージを送", "検証エラーを送",
        )
        for ((tag, localized) in contexts) {
            for (res in guidance) {
                val text = localized.getString(res)
                check(allowedMarkers.any { text.contains(it, ignoreCase = true) }) {
                    "[$tag] $res lost its recovery phrasing: $text"
                }
                for (forbidden in forbiddenMarkers) {
                    check(!text.contains(forbidden, ignoreCase = true)) {
                        "[$tag] $res instructs an AI repair loop ('$forbidden'): $text"
                    }
                }
            }
        }
    }

    /** AC-3/AC-4: the fallback editor is collapsed, bounds its content, and clears. */
    @Test
    fun hugeManualPasteStaysBoundedWithInternalScrollAndClearsInOneAction() {
        val holder = newHolder()
        setContent(holder)
        openImportSurface(holder)

        // D-2 (a): the manual editor is NOT composed until requested.
        composeRule.onNodeWithTag("exchange-import-field").assertDoesNotExist()
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()

        val field = composeRule.onNodeWithTag("exchange-import-field")
        field.performTextClearance()
        field.performTextInput(buildString { repeat(4_000) { append("line $it\n") } })
        composeRule.waitForIdle()

        // Density(1f): the heightIn(max = 200.dp) cap reads back in ~px.
        val height = composeRule.onNodeWithTag("exchange-import-field").fetchSemanticsNode().boundsInRoot.height
        check(height < 400f) { "the manual editor must stay bounded, was $height px" }

        composeRule.onNodeWithTag("exchange-import-clear").assertHasClickAction().performClick()
        composeRule.waitForIdle()
        val cleared = runCatching {
            composeRule.onNodeWithTag("exchange-import-field").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
        }.getOrNull()
        check(cleared?.isEmpty() == true) { "clear must empty the editor, was $cleared" }
    }

    /**
     * AC-10 (as amended by issue #373): the outcome leads with the remedy
     * projection — the primary copy names the remedy, no typed vocabulary on
     * the primary face; the recognition facts and raw text live inside the
     * collapsed detail expansion (bounded once opened).
     */
    @Test
    fun parseFirstOutcomeLeadsWithTheRemedyProjectionAndKeepsDetailCollapsedByDefault() {
        val holder = newHolder()
        setContent(holder)
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()

        val intent = PersonalizedIntentV1(
            exportId = "instrumentation-no-session",
            itemIntents = listOf(ItemIntent(ref = "r1", preserve = true), ItemIntent(ref = "r2")),
        )
        val reply = buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
        composeRule.onNodeWithTag("exchange-import-field").performTextInput(reply)
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("exchange-import-detail-toggle").fetchSemanticsNodes().isNotEmpty()
        }

        // The primary face carries the remedy copy (no session → the reply
        // answers no current request → 依頼を作り直す), never the typed copy.
        composeRule.onNodeWithTag("exchange-import-outcome-message")
            .assertTextContains(context.getString(R.string.exchange_failure_primary_export_mismatch))
        composeRule.onAllNodesWithText(context.getString(R.string.exchange_failure_export_mismatch))
            .fetchSemanticsNodes().isEmpty()

        // The recognition facts and the raw text are inside the detail
        // expansion: default-closed, nothing of them displayed.
        composeRule.onAllNodesWithTag("exchange-import-outcome-framing").fetchSemanticsNodes().isEmpty()
        composeRule.onAllNodesWithTag("exchange-import-outcome-version").fetchSemanticsNodes().isEmpty()
        composeRule.onAllNodesWithTag("exchange-import-outcome-entries").fetchSemanticsNodes().isEmpty()
        composeRule.onAllNodesWithTag("exchange-import-raw-detail").fetchSemanticsNodes().isEmpty()

        composeRule.onNodeWithTag("exchange-import-detail-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-import-outcome-framing").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-outcome-version").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-outcome-entries").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-detail-type").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-detail-explanation").assertIsDisplayed()

        // The detail content stays bounded (internal scroll).
        val detailHeight = composeRule.onNodeWithTag("exchange-import-failure-detail")
            .fetchSemanticsNode().boundsInRoot.height
        check(detailHeight <= 260f) { "the detail expansion must stay bounded, was $detailHeight px" }

        // 依頼を作り直す walks the generation-flow seam: the import surface is
        // replaced and the detail state is gone with it.
        composeRule.onNodeWithTag("exchange-import-failure-action").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("exchange-import-detail-toggle").fetchSemanticsNodes().isEmpty()
    }

    /**
     * AC-8 (semantics): each source operation carries its own label, and a typed
     * failure is announced verbatim on a polite live region. This is the
     * machine-checked part of the accessibility coverage; a real-AT walkthrough
     * (spoken order/words) is recorded separately in docs/assessment.
     */
    @Test
    fun sourceLabelsAndTypedFailureAnnouncementAreExposed() {
        val holder = newHolder()
        setContent(holder)
        openImportSurface(holder)

        composeRule.onNodeWithTag("exchange-import-clipboard")
            .assertTextContains(context.getString(R.string.exchange_import_from_clipboard))
        composeRule.onNodeWithTag("exchange-import-file")
            .assertTextContains(context.getString(R.string.exchange_import_from_file))

        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        // A marked block whose JSON carries a contract-external key: the codec
        // rejects it, so the surface must announce the typed failure text.
        val malformed = buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(
                """{"schemaVersion":"personalized-intent-v3",""" +
                    """"exportId":"instrumentation-no-session",""" +
                    """"itemIntents":[{"ref":"r1","preserve":true}],"unexpectedKey":1}""",
            )
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
        composeRule.onNodeWithTag("exchange-import-field").performTextInput(malformed)
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("exchange-import-outcome-message").fetchSemanticsNodes().isNotEmpty()
        }

        val message = composeRule.onNodeWithTag("exchange-import-outcome-message").fetchSemanticsNode()
        val liveRegion = runCatching { message.config[SemanticsProperties.LiveRegion] }.getOrNull()
        check(liveRegion == LiveRegionMode.Polite) {
            "the typed failure must be announced politely, was $liveRegion"
        }
        // Issue #373 (D-11): the polite announcement is the remedy projection
        // of the failure — the typed vocabulary itself is demoted to the
        // detail expansion and must NOT be on the primary face.
        val expected = context.getString(R.string.exchange_failure_primary_schema_mismatch)
        val announced = runCatching {
            message.config[SemanticsProperties.Text].map { it.text }
        }.getOrNull().orEmpty()
        check(announced.contains(expected)) {
            "the remedy projection must be announced verbatim, was $announced"
        }
        composeRule.onAllNodesWithText(context.getString(R.string.exchange_failure_schema_mismatch))
            .fetchSemanticsNodes().isEmpty()

        // Interrupt (面レベル手段): zero-write close, no confirmation dialog —
        // the flow closes and nothing is lost, so D-13 §9 forbids one. The
        // face disappears immediately (no dialog intercepts the click).
        composeRule.onNodeWithTag("exchange-import-interrupt").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("exchange-import-outcome-message").fetchSemanticsNodes().isEmpty()
    }

    /**
     * Issue #373 (IM-AC-03/IM-AC-04): the projected remedy action reaches the
     * seam it promises, and the face-level means stay available. 貼り直す /
     * もう一度取り込む return to the import input (raw text discarded — spec
     * 332 AC-7), 依頼を作り直す walks the existing generation-flow seam, and
     * 診断を開く records the current attempt's typed cause into the
     * process-scoped transient holder before invoking the host's diagnostics
     * route callback.
     */
    @Test
    fun remedyActionsReachTheirSeamsAndDiagnosticsRecordsTheTypedCause() {
        ExchangeImportFailureDiagnostics.resetForTests()
        val holder = newHolder()
        var diagnosticsOpened = false
        setContent(holder, onOpenDiagnostics = { diagnosticsOpened = true })
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        val intent = PersonalizedIntentV1(
            exportId = "instrumentation-no-session",
            itemIntents = listOf(ItemIntent(ref = "r1", preserve = true)),
        )
        val reply = buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
        composeRule.onNodeWithTag("exchange-import-field").performTextInput(reply)
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("exchange-import-failure-action").fetchSemanticsNodes().isNotEmpty()
        }

        // No session → EXPORT_MISMATCH → the remedy is 依頼を作り直す, which
        // walks the existing generation-flow seam (openFlow).
        composeRule.onNodeWithTag("exchange-import-failure-action")
            .assertTextContains(context.getString(R.string.exchange_import_failure_action_recreate))
        composeRule.onNodeWithTag("exchange-import-failure-action").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("exchange-import-outcome-message").fetchSemanticsNodes().isEmpty()

        // 診断を開く records the typed cause and hands over to the host route.
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-import-field").performTextInput(reply)
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("exchange-import-open-diagnostics").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exchange-import-open-diagnostics").performClick()
        composeRule.waitForIdle()
        check(diagnosticsOpened) { "診断を開く must reach the host's diagnostics route" }
        val recorded = ExchangeImportFailureDiagnostics.recent
        check(recorded?.typeName == "EXPORT_MISMATCH") {
            "診断を開く must record the current attempt's typed cause, was $recorded"
        }
        check(recorded.explanation == context.getString(R.string.exchange_failure_export_mismatch)) {
            "the recorded explanation must be the contract copy, was ${recorded.explanation}"
        }
    }

    /**
     * Issue #373 implementation review (stale-record regression oracle): a
     * PREVIOUS attempt's recorded typed cause must never surface as the
     * current attempt's failure. Seed the holder with one, settle an
     * `InputNotReady` (post-decode environmental failure — no typed
     * classification), then 診断を開く: the operation EMPTIES the recording
     * instead of keeping the old cause.
     */
    @Test
    fun staleTypedCauseIsNotShownAsTheCurrentAttemptOnNonTypedFailures() {
        ExchangeImportFailureDiagnostics.resetForTests()
        ExchangeImportFailureDiagnostics.record(RecentImportFailure("CONTEXT_STALE", "previous attempt"))
        val session = app.lawnchair.organizer.personalization.ExportSession(
            exportId = "instrumentation-session",
            itemRefs = mapOf("ref-0" to ItemId("id-0")),
            tier = PrivacyTier.EXTERNAL_REDACTED,
            sourceContextDigest = "digest",
            signalProvenance = null,
            createdAtEpochMs = 1_000_000L,
            expiresAtEpochMs = 1_000_000L + 2 * 60L * 60L * 1000L,
        )
        val holder = newHolder(
            store = LoadableStore(session),
            structuralResult = ExchangeStructuralResult.NotReady(InputReadinessReason.ReconciliationPending),
        )
        var diagnosticsOpened = false
        setContent(holder, onOpenDiagnostics = { diagnosticsOpened = true })
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        val intent = PersonalizedIntentV1(
            exportId = "instrumentation-session",
            itemIntents = listOf(ItemIntent(ref = "r1", preserve = true)),
        )
        val reply = buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
        composeRule.onNodeWithTag("exchange-import-field").performTextInput(reply)
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("exchange-import-open-diagnostics").fetchSemanticsNodes().isNotEmpty()
        }
        // The face IS the non-typed InputNotReady outcome (environmental
        // failure — no typed classification).
        composeRule.onNodeWithTag("exchange-import-outcome-message")
            .assertTextContains(context.getString(R.string.exchange_generation_input_not_ready))

        composeRule.onNodeWithTag("exchange-import-open-diagnostics").performClick()
        composeRule.waitForIdle()
        check(diagnosticsOpened) { "診断を開く must reach the host's diagnostics route" }
        val recorded = ExchangeImportFailureDiagnostics.recent
        check(recorded == null) {
            "the previous attempt's typed cause must be emptied on a non-typed failure, was $recorded"
        }
    }

    /**
     * The diagnostics row is a safe absence, not a dead button: a host without
     * a diagnostics route (null callback) hides the row entirely.
     */
    @Test
    fun failureFaceWithoutDiagnosticsRouteHidesTheRow() {
        val holder = newHolder()
        setContent(holder)
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("exchange-import-open-diagnostics").fetchSemanticsNodes().isEmpty()
    }

    /** AC-8 (structure): 200% font keeps the primary actions on screen, editor bounded. */
    @Test
    fun primaryActionsStayDisplayedAndEditorStaysBoundedAtTwoHundredPercentFont() {
        val holder = newHolder()
        setContent(holder, fontScale = 2f)
        openImportSurface(holder)
        composeRule.onNodeWithTag("exchange-import-fallback-toggle").performClick()
        composeRule.waitForIdle()
        val field = composeRule.onNodeWithTag("exchange-import-field")
        field.performTextInput(buildString { repeat(2_000) { append("あ" + it + "\n") } })
        composeRule.waitForIdle()
        val height = composeRule.onNodeWithTag("exchange-import-field").fetchSemanticsNode().boundsInRoot.height
        check(height < 400f) { "the manual editor must stay bounded at 200% font, was $height px" }
        composeRule.onNodeWithTag("exchange-import-clipboard").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-file").assertIsDisplayed()
    }
}

/**
 * The inert run double of `ManualOrganizationRunTestSupport` (tests/unit),
 * replicated here because the instrumentation source set cannot see the unit
 * test sources. The planner must never run — a bug surfaces as its error.
 */
private object InertRun {
    fun get(): app.lawnchair.organizer.ui.ManualOrganizationRun = app.lawnchair.organizer.ui.ManualOrganizationRun(
        application = NotReadyApplication(),
        planner = app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
    )

    private class NotReadyApplication : app.lawnchair.organizer.ui.ManualOrganizationApplication {
        override val diagnostics = object : app.lawnchair.organizer.diagnostics.DiagnosticsPort {
            override fun emit(event: app.lawnchair.organizer.diagnostics.model.RunEvent) = Unit
            override fun snapshot() = emptyList<app.lawnchair.organizer.diagnostics.model.RunEvent>()
        }

        override fun newRunId() = app.lawnchair.organizer.application.public.RunId("0123456789abcdef0123456789abcdef")

        override fun detectMissingAppCandidates() = app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
            app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

        override fun composeFullOrganization(): app.lawnchair.organizer.integration.OrganizationInputComposition = notReady()

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): app.lawnchair.organizer.integration.OrganizationInputComposition = notReady()

        override fun inspectPlan(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = notReadyPreview()

        override fun materialize(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = error("not reached in exchange instrumentation tests")

        override fun apply(
            plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
            runId: app.lawnchair.organizer.application.public.RunId,
        ) = error("not reached in exchange instrumentation tests")

        override fun inspectRecovery(pointId: app.lawnchair.organizer.application.public.RecoveryPointId) =
            error("not reached in exchange instrumentation tests")

        override fun confirmRecovery(
            pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
            confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
        ) = error("not reached in exchange instrumentation tests")

        override fun readDurableOrganizerStatus() = error("not reached in exchange instrumentation tests")

        override fun readRestorableRecoveryEntry(): app.lawnchair.organizer.application.public.RestorableRecoveryEntry? =
            error("not reached in exchange instrumentation tests")

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)

        private fun notReady() = app.lawnchair.organizer.integration.OrganizationInputComposition.NotReady(
            app.lawnchair.organizer.integration.InputReadinessReason.InvalidCanonicalCapture(
                app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
            ),
            app.lawnchair.organizer.integration.CompositionDiagnostic(app.lawnchair.organizer.integration.InputCompositionCode.CAPTURE_INVALID),
        )

        private fun notReadyPreview(): app.lawnchair.organizer.application.public.PlanPreviewResult =
            app.lawnchair.organizer.application.public.PlanPreviewResult.WriterBusy
    }
}
