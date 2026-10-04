package app.lawnchair.organizer.ui.exchange

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeInputResult
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.ui.ManualOrganizationApplication
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #328 (spec AC-2/AC-6/AC-7/AC-8): the import success surface as
 * rendered UI, and the system-Back interception at the hosting level.
 *
 * The Back tests compose the interception exactly the way the hosting screen
 * does — after a competing screen-level handler that stands in for
 * `ManualOrganizationBackHandler` — and drive the success item out of the
 * viewport (large font / scrolled list) before pressing Back, proving the
 * interception is not carried by the lazy item's composition.
 */
@RunWith(AndroidJUnit4::class)
class ExchangeImportSuccessInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeStore : ExportSessionStore {
        var session: ExportSession? = null
        override fun save(session: ExportSession): Boolean {
            this.session = session
            return true
        }
        override fun load(exportId: String): ExportSession? = session?.takeIf { it.exportId == exportId }
        override fun active(nowEpochMs: Long): ExportSession? = session
        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
        }
    }

    private class ReplayAllocator(session: ExportSession) : RandomIdAllocator {
        private val ids = ArrayDeque(session.itemRefs.keys.toList() + listOf(session.exportId))
        override fun newId(): String = ids.removeFirst()
    }

    private fun newRun(blockDetection: java.util.concurrent.CountDownLatch? = null): ManualOrganizationRun =
        ManualOrganizationRun(
            application = object : ManualOrganizationApplication by NotReadyApplication() {
                override fun detectMissingAppCandidates(): app.lawnchair.organizer.integration.CandidateDetectionResult {
                    blockDetection?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return NotReadyApplication().detectMissingAppCandidates()
                }
            },
            planner = app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
        )

    private class NotReadyApplication : ManualOrganizationApplication {
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
        ) = app.lawnchair.organizer.application.public.PlanPreviewResult.WriterBusy
        override fun materialize(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = error("not reached")
        override fun apply(
            plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
            runId: app.lawnchair.organizer.application.public.RunId,
        ) = error("not reached")
        override fun inspectRecovery(pointId: app.lawnchair.organizer.application.public.RecoveryPointId) = error("not reached")
        override fun confirmRecovery(
            pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
            confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
        ) = error("not reached")
        override fun readDurableOrganizerStatus() = error("not reached")
        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)
        private fun notReady(): app.lawnchair.organizer.integration.OrganizationInputComposition =
            app.lawnchair.organizer.integration.OrganizationInputComposition.NotReady(
                app.lawnchair.organizer.integration.InputReadinessReason.InvalidCanonicalCapture(
                    app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                ),
                app.lawnchair.organizer.integration.CompositionDiagnostic(
                    app.lawnchair.organizer.integration.InputCompositionCode.CAPTURE_INVALID,
                ),
            )
    }

    private fun structural(): CanonicalStructuralInputs {
        val items = listOf(app("a"), app("b", x = 1))
        val snapshot = app.lawnchair.organizer.planning.LayoutSnapshot(
            app.lawnchair.organizer.planning.RevisionId("rev"),
            app.lawnchair.organizer.planning.DeviceCapabilities(4, 6, 5, 3, 5, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
            listOf(app.lawnchair.organizer.planning.Page(app.lawnchair.organizer.planning.PageId("p0"), app.lawnchair.organizer.planning.PageOrder(0))),
            items,
        )
        val targets = app.lawnchair.organizer.planning.TargetSet(
            items.map { app.lawnchair.organizer.planning.ExistingTargetMembership(it.id, app.lawnchair.organizer.planning.ExistingRole.Movable) },
            emptyList(),
        )
        return CanonicalStructuralInputs(snapshot, targets, emptyMap())
    }

    private fun app(id: String, x: Int = 0) = app.lawnchair.organizer.planning.CapturedItem(
        id = app.lawnchair.organizer.planning.ItemId(id),
        profile = app.lawnchair.organizer.planning.ProfileId("p0"),
        kind = app.lawnchair.organizer.planning.ItemKind.APPLICATION,
        target = app.lawnchair.organizer.planning.TargetKey.AppKey(
            app.lawnchair.organizer.planning.ComponentKey("com.example.$id"),
            app.lawnchair.organizer.planning.ProfileId("p0"),
        ),
        placement = app.lawnchair.organizer.planning.CapturedPlacement.Workspace(
            app.lawnchair.organizer.planning.PageRef(app.lawnchair.organizer.planning.PageId("p0")),
            app.lawnchair.organizer.planning.GridCell(x, 0),
            app.lawnchair.organizer.planning.GridSpan(1, 1),
        ),
        locked = false,
        availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
    )

    private fun newHolder(
        run: ManualOrganizationRun = newRun(),
        store: FakeStore = FakeStore(),
    ): Pair<ExchangeFlowStateHolder, ExchangeFlowController> {
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
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = run,
            scope = CoroutineScope(Dispatchers.Main),
        )
        return holder to controller
    }

    private fun replyFor(session: ExportSession): String {
        val s = structural()
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = s.snapshot, targets = s.targets, nowEpochMs = session.createdAtEpochMs),
            session.tier,
            ReplayAllocator(session),
        )
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = built.export.items.map { ItemIntent(ref = it.ref, preserve = true) },
        )
        return buildString {
            append(app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_END_MARKER)
        }
    }

    /** Drives the holder to the rendered success state through the real path. */
    private fun holderInSuccessState(
        run: ManualOrganizationRun = newRun(),
        store: FakeStore = FakeStore(),
    ): ExchangeFlowStateHolder {
        val (holder, controller) = newHolder(run, store)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED)
            as app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult.Generated
        composeRule.runOnUiThread { holder.openImport() }
        composeRule.runOnUiThread { holder.import(replyFor(generated.session)) }
        var waited = 0
        while (holder.screen !is ExchangeScreen.ImportSuccess && waited < 5_000) {
            composeRule.waitForIdle()
            Thread.sleep(20)
            waited += 20
        }
        assertTrue("fixture must reach the success state", holder.screen is ExchangeScreen.ImportSuccess)
        return holder
    }

    /** Renders at the PLATFORM font scale (no LocalDensity override). */
    private fun setSuccessContentAtPlatformFontScale(holder: ExchangeFlowStateHolder) {
        setSuccessContentInternal(holder, fontScale = null)
    }

    private fun setSuccessContent(holder: ExchangeFlowStateHolder, fontScale: Float = 1f) {
        setSuccessContentInternal(holder, fontScale = fontScale)
    }

    private fun setSuccessContentInternal(holder: ExchangeFlowStateHolder, fontScale: Float?) {
        composeRule.setContent {
            val content: @androidx.compose.runtime.Composable () -> Unit = {
                app.lawnchair.ui.theme.LawnchairTheme {
                    LazyColumn {
                        exchangeFlowItems(
                            holder = holder,
                            clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                            shareTransport = { _, _ -> ExchangeTransportResult.Success },
                            fileTransport = FileExchangeTransport(context),
                        )
                    }
                }
            }
            if (fontScale != null) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = fontScale)) {
                    content()
                }
            } else {
                content()
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun successSurfaceRendersTheSummaryNotAppliedLineAndBothActions() {
        val holder = holderInSuccessState()
        setSuccessContent(holder)
        composeRule.onNodeWithTag("exchange-import-success-title")
            .assertIsDisplayed()
            .assertTextContains(context.getString(R.string.exchange_import_success_title))
        composeRule.onNodeWithTag("exchange-import-summary-recognized").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-summary-keep").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-not-applied")
            .assertIsDisplayed()
            .assertTextContains(context.getString(R.string.exchange_import_not_applied))
        composeRule.onNodeWithTag("exchange-import-continue")
            .assertIsDisplayed()
            .assertTextContains(context.getString(R.string.exchange_import_cta_idle))
        composeRule.onNodeWithTag("exchange-import-discard")
            .assertIsDisplayed()
            .assertTextContains(context.getString(R.string.exchange_import_discard))
        // AC-6: the arrival is announced (Polite live region) and the
        // actions are enabled semantics — not merely visible.
        composeRule.onNodeWithTag("exchange-import-success-title")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithTag("exchange-import-continue").assertIsEnabled()
        composeRule.onNodeWithTag("exchange-import-discard").assertIsEnabled()
        // Audit D-6 regression guard: the arrival moves focus to the heading
        // (spec Accessibility), asserted directly on the focused semantics.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("exchange-import-success-title").assertIsFocused()
            }.isSuccess
        }
    }

    @Test
    fun receiptRefusedByTheStrategyArbiterStaysRetryableFromTheHeldText() {
        // Issue #328 implementation review (high): a clipboard receipt refused
        // by the arbiter gate keeps the held text VISIBLE for retry — the
        // fallback editor opens on the empty->non-empty transition, so the
        // user never has to re-read the source.
        val (realHolder, controller) = newHolder()
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED)
            as app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult.Generated
        val reply = replyFor(generated.session)
        realHolder.strategyArbiterBusy = { true }

        setSuccessContent(realHolder)
        composeRule.runOnUiThread { realHolder.openImport() }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            realHolder.importFromClipboard(
                app.lawnchair.organizer.integration.exchange.ClipboardImportTransport(context).apply {
                    readOverride = {
                        app.lawnchair.organizer.integration.exchange.ClipboardImportRead.Text(reply)
                    }
                },
            )
        }
        composeRule.waitForIdle()
        assertEquals(
            ExchangeStatus.Kind.IMPORT_STRATEGY_BUSY,
            realHolder.status!!.kind,
        )
        // The held text is visible in the (auto-opened) fallback editor.
        composeRule.onNodeWithTag("exchange-import-field").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-action").assertIsEnabled()

        // Retry from the SAME held text once the arbiter released.
        realHolder.strategyArbiterBusy = { false }
        composeRule.onNodeWithTag("exchange-import-action").performClick()
        var waited = 0
        while (realHolder.screen !is ExchangeScreen.ImportSuccess && waited < 5_000) {
            composeRule.waitForIdle()
            Thread.sleep(20)
            waited += 20
        }
        assertTrue(realHolder.screen is ExchangeScreen.ImportSuccess)
    }

    @Test
    fun platformMaximumFontScaleKeepsTheSummaryAndCtaInTheViewport() {
        // AC-8 structural evidence (review): render at the PLATFORM font
        // scale (the emulator is set to the platform maximum before this
        // suite runs) with no override — the CTA and the not-applied line
        // stay inside the viewport.
        val holder = holderInSuccessState()
        setSuccessContentAtPlatformFontScale(holder)
        composeRule.onNodeWithTag("exchange-import-not-applied").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-continue").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-discard").assertIsDisplayed()
    }

    @Test
    fun warningSuccessStateUsesTheDistinctHeading() {
        // A canonical no-judgment reply (bare item + unresolved marker) drives
        // the warning heading — distinct semantics, not just a visual tint.
        val (holder, controller) = newHolder()
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED)
            as app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult.Generated
        val s = structural()
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = s.snapshot, targets = s.targets, nowEpochMs = generated.session.createdAtEpochMs),
            generated.session.tier,
            ReplayAllocator(generated.session),
        )
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = built.export.items[0].ref)),
            unresolvedRefs = listOf(built.export.items[1].ref),
        )
        val warningReply = buildString {
            append(app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_END_MARKER)
        }
        composeRule.runOnUiThread { holder.openImport() }
        composeRule.runOnUiThread { holder.import(warningReply) }
        var waited = 0
        while (holder.screen !is ExchangeScreen.ImportSuccess && waited < 5_000) {
            composeRule.waitForIdle()
            Thread.sleep(20)
            waited += 20
        }
        setSuccessContent(holder)
        composeRule.onNodeWithTag("exchange-import-success-title")
            .assertTextContains(context.getString(R.string.exchange_import_success_warning_title))
        composeRule.onNodeWithTag("exchange-import-summary-no-judgment").assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsTheContinuationCtaReachableInsideTheViewport() {
        // AC-8 (structural precursor of the device evidence): at 2x font the
        // summary wraps and the CTA stays inside the viewport.
        val holder = holderInSuccessState()
        setSuccessContent(holder, fontScale = 2f)
        composeRule.onNodeWithTag("exchange-import-not-applied").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-continue").assertIsDisplayed()
        composeRule.onNodeWithTag("exchange-import-discard").assertIsDisplayed()
    }

    @Test
    fun backWithTheSuccessItemOutOfTheViewportIsInterceptedBeforeTheHostFallback() {
        // AC-7: the interception is composed at the hosting level, so Back
        // still reaches the discard confirmation after the success item was
        // scrolled out of composition (large font / long list).
        val holder = holderInSuccessState()
        var hostFallbackCalls = 0
        composeRule.setContent {
            app.lawnchair.ui.theme.LawnchairTheme {
                LazyColumn {
                    item { Spacer(modifier = androidx.compose.ui.Modifier.height(1_200.dp)) }
                    exchangeFlowItems(
                        holder = holder,
                        clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                        shareTransport = { _, _ -> ExchangeTransportResult.Success },
                        fileTransport = FileExchangeTransport(context),
                    )
                }
                // Stands in for ManualOrganizationBackHandler: registered
                // BEFORE the interception, as in the hosting screen.
                BackHandler(enabled = true) { hostFallbackCalls++ }
                ExchangeImportSuccessBackHandler(holder)
            }
        }
        composeRule.waitForIdle()
        // The success item is below the viewport (1,200dp spacer): the lazy
        // list never composed it, so no success node exists at all.
        assertEquals(
            "the success item must be outside the viewport for this oracle",
            0,
            composeRule.onAllNodesWithTag("exchange-import-success").fetchSemanticsNodes().size,
        )
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals("Back must not fall through to the host fallback", 0, hostFallbackCalls)
        composeRule.onNodeWithText(context.getString(R.string.exchange_import_discard_confirm_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.exchange_import_discard_confirm_confirm))
            .performClick()
        composeRule.waitForIdle()
        assertTrue("the confirmation discards the pending import", holder.screen is ExchangeScreen.Closed)
    }

    @Test
    fun continuingBackIsSwallowedWithoutADiscardDialog() {
        // AC-3: while the run-connection seam is running, Back is a no-op —
        // neither the host fallback nor a discard confirmation runs.
        val blocked = java.util.concurrent.CountDownLatch(1)
        val holder = holderInSuccessState(run = newRun(blockDetection = blocked))
        var hostFallbackCalls = 0
        composeRule.setContent {
            app.lawnchair.ui.theme.LawnchairTheme {
                LazyColumn {
                    exchangeFlowItems(
                        holder = holder,
                        clipboardTransport = { _, _ -> ExchangeTransportResult.Success },
                        shareTransport = { _, _ -> ExchangeTransportResult.Success },
                        fileTransport = FileExchangeTransport(context),
                    )
                }
                // Registered BEFORE the interception, as in the hosting screen.
                BackHandler(enabled = true) { hostFallbackCalls++ }
                ExchangeImportSuccessBackHandler(holder)
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { holder.continueImport() }
        composeRule.waitForIdle()
        assertTrue("the seam must still be running", holder.importContinuationActive)
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(0, hostFallbackCalls)
        assertEquals(
            "no discard confirmation while continuing",
            0,
            composeRule.onAllNodesWithText(context.getString(R.string.exchange_import_discard_confirm_title))
                .fetchSemanticsNodes().size,
        )
        // AC-6/AC-3: the actions are disabled semantics while continuing.
        composeRule.onNodeWithTag("exchange-import-continue").assertIsNotEnabled()
        composeRule.onNodeWithTag("exchange-import-discard").assertIsNotEnabled()
        assertTrue(holder.screen is ExchangeScreen.ImportSuccess)
        blocked.countDown()
    }

    @Test
    fun discardConfirmationCanBeCancelledWithoutLosingTheSuccessState() {
        // D-2: cancelling the confirmation keeps the success state (and the
        // pending intent) intact.
        val holder = holderInSuccessState()
        composeRule.setContent {
            app.lawnchair.ui.theme.LawnchairTheme {
                ExchangeImportSuccessBackHandler(holder)
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.exchange_cancel)).performClick()
        composeRule.waitForIdle()
        assertTrue("the success state survives a dismissed dialog", holder.screen is ExchangeScreen.ImportSuccess)
        assertTrue(holder.importAttemptActive)
    }
}
