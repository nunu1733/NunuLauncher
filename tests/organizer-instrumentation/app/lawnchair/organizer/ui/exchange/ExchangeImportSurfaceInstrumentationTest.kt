package app.lawnchair.organizer.ui.exchange

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private class FakeStore : ExportSessionStore {
        /** Issue #372: configurable so the T-15 pre-display can be driven. */
        var session: app.lawnchair.organizer.personalization.ExportSession? = null

        override fun save(session: app.lawnchair.organizer.personalization.ExportSession) = true
        override fun load(exportId: String): app.lawnchair.organizer.personalization.ExportSession? = null

        override fun active(nowEpochMs: Long): app.lawnchair.organizer.personalization.ExportSession? = session?.takeIf { !it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
        }
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

    private fun newHolder(store: FakeStore = FakeStore()): ExchangeFlowStateHolder {
        val controller = ExchangeFlowController(
            composeExportInputs = {
                val s = structural()
                ExchangeInputResult.ExportReady(
                    ExportInputs(snapshot = s.snapshot, targets = s.targets, nowEpochMs = 1_000_000L),
                )
            },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural()) },
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

    private fun setContent(holder: ExchangeFlowStateHolder, fontScale: Float = 1f, onDiscardRequest: () -> Unit = {}) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = fontScale)) {
                LawnchairTheme {
                    // Issue #372: the same Back wiring as the host — the flow
                    // handler before the import-success handler — so the Back
                    // contract is exercised against the real dispatcher.
                    ExchangeFlowBackHandler(holder = holder, onDiscardRequest = onDiscardRequest)
                    ExchangeImportSuccessBackHandler(holder)
                    LazyColumn {
                        exchangeFlowItems(
                            holder = holder,
                            onDiscardRequest = onDiscardRequest,
                            clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                            shareTransport = { _, _ -> ExchangeTransportResult.Success },
                            fileTransport = FileExchangeTransport(context),
                        )
                    }
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
     * Issue #372 (EX-AC-08, rendered-UI oracle): the T-16 破棄 button does not
     * invalidate directly — it raises the host's ONE discard confirmation
     * (same entry as system Back); the confirm path runs the existing
     * closeDisclosure gate.
     */
    @Test
    fun discardButtonRoutesThroughTheHostConfirmation() {
        val holder = newHolder()
        var discardRequested = false
        setContent(holder, onDiscardRequest = { discardRequested = true })
        composeRule.runOnUiThread {
            holder.openFlow()
            holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("exchange-discard").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exchange-discard").performClick()
        composeRule.waitForIdle()
        assertTrue("the 破棄 button must request the confirmation, not discard", discardRequested)
        assertTrue("the face stays until the confirmation is accepted", holder.screen is ExchangeScreen.Disclosing)
    }

    /**
     * Issue #372 (EX-AC-11, rendered-UI oracle): the system-Back contract of
     * the request faces — T-15 Back closes zero-write, Back on a generating
     * face is consumed (the face stays and the generation still settles),
     * Back on the unsent T-16 requests the discard confirmation, and Back on
     * the sent T-16 closes with the request surviving.
     */
    @Test
    fun backContractOnTheRequestFacesIsStructural() {
        val holder = newHolder()
        var discardRequested = false
        setContent(holder, onDiscardRequest = { discardRequested = true })

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

        // Unsent T-16: Back raises the discard confirmation request and keeps
        // the face.
        discardRequested = false
        pressBack()
        assertTrue(discardRequested)
        assertTrue(holder.screen is ExchangeScreen.Disclosing)

        // After a transport success, Back is the zero-write close and the
        // request survives.
        composeRule.runOnUiThread { holder.onTransportResult(ExchangeTransportResult.Success) }
        composeRule.waitForIdle()
        pressBack()
        assertTrue(holder.screen is ExchangeScreen.Closed)
    }

    /**
     * Issue #372 (EX-AC-10): the restructured T-15/T-16 faces keep every
     * critical action reachable and unclipped at 200% font scale (the
     * summary-first face replaces the always-expanded package text, so the
     * reflow stays bounded by design).
     */
    @Test
    fun requestFacesKeepCriticalActionsReachableAtTwoHundredPercentFontScale() {
        val holder = newHolder()
        setContent(holder, fontScale = 2f)
        composeRule.runOnUiThread { holder.openFlow() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("exchange-request-title").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-generate").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("exchange-request-active").assertDoesNotExist()

        composeRule.runOnUiThread { holder.generate(PrivacyTier.EXTERNAL_REDACTED) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("exchange-discard").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exchange-send-clipboard").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("exchange-discard").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("exchange-disclosure-expand").assertIsDisplayed().assertHasClickAction()
    }

    /**
     * Issue #348 AC-1 (content oracle): the failure/retry guidance strings
     * must keep offering the allowed recovery (re-copy / re-paste / ask the
     * AI to resend the final JSON) and must never instruct an AI repair loop
     * (feeding failure diagnostics or error content back to the AI). Both
     * locales are resolved through real resource contexts.
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
            R.string.exchange_import_retry_hint,
            R.string.exchange_failure_framing_missing,
            R.string.exchange_failure_framing_empty,
            R.string.exchange_failure_normalization_unrecognized,
        )
        val allowedMarkers = listOf("resend", "再送", "import again", "再度取り込")
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

    /** AC-10: the parse-first outcome leads with recognition facts; raw is collapsed. */
    @Test
    fun parseFirstOutcomeShowsRecognitionAndKeepsRawCollapsedByDefault() {
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
            composeRule.onAllNodesWithTag("exchange-import-raw-toggle").fetchSemanticsNodes().isNotEmpty()
        }

        // Recognition facts lead the display: recognized framing, accepted
        // version, authored entry count (bare `r2` counts → 2).
        composeRule.onNodeWithTag("exchange-import-outcome-framing").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-outcome-version").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-outcome-entries").assertIsDisplayed()

        // The raw detail is default-closed and, once opened, bounded.
        composeRule.onAllNodesWithTag("exchange-import-raw-detail").fetchSemanticsNodes().isEmpty()
        composeRule.onNodeWithText(context.getString(R.string.exchange_import_raw_show)).performClick()
        composeRule.waitForIdle()
        val detailHeight = composeRule.onNodeWithTag("exchange-import-raw-detail").fetchSemanticsNode().boundsInRoot.height
        check(detailHeight <= 260f) { "the raw detail must stay bounded, was $detailHeight px" }

        // AC-7: retry replaces the surface, discarding the raw text.
        composeRule.onNodeWithText(context.getString(R.string.exchange_import_retry)).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("exchange-import-raw-toggle").fetchSemanticsNodes().isEmpty()
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
        val expected = context.getString(R.string.exchange_failure_schema_mismatch)
        val announced = runCatching {
            message.config[SemanticsProperties.Text].map { it.text }
        }.getOrNull().orEmpty()
        check(announced.contains(expected)) { "the typed failure must be announced verbatim, was $announced" }
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
