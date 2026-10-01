package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RecoveryPreviewRejection
import app.lawnchair.organizer.application.public.RestorableRecoveryEntry
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.exchange.ExchangeSessionStoreModule
import app.lawnchair.organizer.integration.exchange.PendingImportedIntentModule
import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.DurableRefDecision
import app.lawnchair.organizer.personalization.DurableRefEntry
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerHubPreferences
import com.android.launcher3.R
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizer
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerDiagnostics
import app.lawnchair.ui.theme.LawnchairTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The #479 AC-2 focused oracle, isolated in its OWN instrumentation
 * invocation (see tools/ci/run-manual-organization-ui-instrumentation.sh):
 * its real system-level drag injection disturbs the key/focus delivery of
 * the tests that follow within the same instrumentation process (reproduced
 * 3/3 CI runs; bisected against the #493 baseline and an oracle-ignored
 * variant of this lane), so the oracle cannot share a process with the rest
 * of the manual-organization-ui suite.
 */
@RunWith(AndroidJUnit4::class)
class OrganizerHubDragGuardInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var seededSession: ExportSession? = null

    private val dragGuardSessionStore get() = ExchangeSessionStoreModule.store(context)
    private val dragGuardPendingStore get() = PendingImportedIntentModule.store(context)

    @After
    fun clearExchangeStoreSeeds() {
        dragGuardPendingStore.delete()
        seededSession?.let { dragGuardSessionStore.invalidate(it.exportId) }
        seededSession = null
    }

    /** Seeds the module session store with an ACTIVE request (the same 24h TTL as the real session). */
    private fun seedActiveSession(): ExportSession {
        val now = System.currentTimeMillis()
        val session = ExportSession(
            exportId = "drag-guard-export",
            itemRefs = (0 until 2).associate { "drag-guard-ref-$it" to app.lawnchair.organizer.planning.ItemId("drag-guard-item-$it") },
            tier = PrivacyTier.EXTERNAL_REDACTED,
            sourceContextDigest = DRAG_GUARD_DIGEST,
            signalProvenance = null,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 24L * 60L * 60L * 1000L,
        )
        assertTrue(dragGuardSessionStore.save(session))
        seededSession = session
        return session
    }

    /** Seeds the module pending store with a record for [session]'s reply (all-unresolved). */
    private fun seedPendingRecord(session: ExportSession): DurablePendingIntent {
        val record = DurablePendingIntent(
            exportId = session.exportId,
            intentIdentitySchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
            intentIdentityDigest = DRAG_GUARD_DIGEST,
            decisions = session.itemRefs.keys.map { DurableRefEntry(it, DurableRefDecision.UnresolvedByOmission) },
            minimizeMovement = false,
            expiresAtEpochMs = session.expiresAtEpochMs,
            entryKind = PendingImportEntryKind.IDLE,
            discarded = false,
            createdAtEpochMs = session.createdAtEpochMs,
        )
        assertTrue(dragGuardPendingStore.save(record))
        return record
    }

    private fun dragGuardRunner(): ManualOrganizationRun = ManualOrganizationRun(
        DragGuardFakeApplication(),
        OrganizationPlanner {
            error("the drag-guard oracle must not trigger planning")
        },
    )

    private fun setHubContent(runner: ManualOrganizationRun, restorationTester: StateRestorationTester) {
        // The StateRestorationTester must own the content: its
        // `emulateSavedInstanceStateRestore` re-composes through the same
        // SaveableStateHolder that setContent registered.
        restorationTester.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(context.resources.displayMetrics.density, fontScale = 2f),
            ) {
                val navController = rememberNavController()
                CompositionLocalProvider(LocalNavController provides navController) {
                    LawnchairTheme {
                        NavHost(navController = navController, startDestination = HomeScreenOrganizer) {
                            composable<HomeScreenOrganizer> {
                                OrganizerHubPreferences(run = runner)
                            }
                            composable<HomeScreenManualOrganization> { backStackEntry ->
                                val route = backStackEntry.toRoute<HomeScreenManualOrganization>()
                                ManualOrganizationPreferences(
                                    run = runner,
                                    trigger = route.trigger,
                                    durableRecovery = route.durableRecovery,
                                    onOpenDiagnostics = { navController.navigate(HomeScreenOrganizerDiagnostics) },
                                    exchangeOpen = route.exchangeOpen,
                                )
                            }
                            composable<HomeScreenOrganizerDiagnostics> {
                                Text(text = DRAG_GUARD_DIAGNOSTICS_STUB_TEXT)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Issue #479 (AC-2, focused): a real drag on the hub list arms the
     * re-anchor guard, and the armed guard keeps the user's scroll position
     * across a saved-instance-state restore. With a broken guard (never
     * armed, or armed by programmatic scrolls) the re-anchor effect fires
     * `scrollToItem(0)` and pulls the request row back to the first-visible
     * position, failing this oracle. No quarantine, no failure diagnostics:
     * the oracle is the request row's (non-)return alone.
     */
    @Test
    fun hubUserDragPositionIsNotReanchoredWhileExchangeRowsPresent() {
        val runner = dragGuardRunner()
        // The seeded active request — plus its pending proposal record, so the
        // list carries BOTH exchange rows (#374) — makes the list tall enough
        // that the first item can be scrolled fully out of composition at
        // fontScale 2f.
        val session = seedActiveSession()
        seedPendingRecord(session)
        val restorationTester = StateRestorationTester(composeRule)
        setHubContent(runner, restorationTester)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("organizer-hub-request").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("organizer-hub-request").assertIsDisplayed()

        // Arm the #479 guard with a REAL touch drag: user drags are the only
        // scrolls that dispatch nested scroll with UserInput, so the
        // production nested-scroll observer arms the guard on the consumed
        // delta. The exitUntilCollapsed app bar may consume part of the
        // swipe; as long as the list itself moved, the guard is armed.
        fun requestRowSnapshot(): Pair<Int, androidx.compose.ui.geometry.Rect?> {
            val nodes = composeRule.onAllNodesWithTag("organizer-hub-request").fetchSemanticsNodes()
            return nodes.size to nodes.firstOrNull()?.boundsInRoot
        }
        var swipes = 0
        // A system-level drag (uiautomator) — the reliable injection path for
        // a real touch drag. Safe here: this class owns its instrumentation
        // process, so UiDevice's accessibility registration cannot disturb
        // any other test.
        val device = androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        )
        while (swipes < 8) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 24)
            composeRule.waitForIdle()
            swipes++
            val (count, bounds) = requestRowSnapshot()
            if (count == 0 || bounds!!.top < 400) break
        }
        // The swipes must have scrolled the list: the request row left its
        // entry position (fully out of composition, or visibly moved up).
        val (countAfterSwipe, boundsAfterSwipe) = requestRowSnapshot()
        check(countAfterSwipe == 0 || boundsAfterSwipe!!.top < 400) {
            "the real drags never scrolled the hub list (row=$countAfterSwipe at $boundsAfterSwipe after $swipes swipes); " +
                "the AC-2 arming premise is broken"
        }

        // Move the anchor past index 0 deterministically through the list's
        // own scroll-into-view path (#366/#369 discipline): a programmatic
        // scroll dispatches no UserInput nested scroll, so the guard armed
        // by the drag above stays armed.
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organizer_strategy_title)),
        )
        composeRule.waitForIdle()
        // Scrolled past item 0: the request row sits fully outside the
        // viewport (first visible index > 0) and is therefore not composed.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("organizer-hub-request").fetchSemanticsNodes().isEmpty()
        }

        // Saved-instance-state restore: the saveable list state restores the
        // scrolled position, and the drag guard must restore with it
        // (`rememberSaveable`) or the re-anchor correction snaps the restored
        // position back to index 0.
        restorationTester.emulateSavedInstanceStateRestore()

        // The hub recomposed from the restored state (the scaffold label is
        // independent of the list scroll position), and the first frames —
        // where a lost guard would have snapped to index 0 — have settled.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.organizer_hub_label))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
        repeat(3) { composeRule.waitForIdle() }

        // AC-2: the user-dragged position is retained across the restoration
        // — the request row did NOT return to the first-visible position.
        composeRule.onAllNodesWithTag("organizer-hub-request").assertCountEquals(0)
    }

    private companion object {
        const val DRAG_GUARD_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DRAG_GUARD_DIAGNOSTICS_STUB_TEXT = "issue479-drag-guard-diagnostics-stub"
    }
}

/**
 * Hub-focused stand-in for the run surface's FakeApplication, trimmed to the
 * drag-guard oracle: reads return the never-organized defaults, and every
 * planning/apply member fails fast — the oracle must never trigger them.
 */
private class DragGuardFakeApplication : ManualOrganizationApplication {
    override val diagnostics = object : DiagnosticsPort {
        override fun emit(event: RunEvent) = Unit
        override fun snapshot(): List<RunEvent> = emptyList()
    }

    override fun newRunId() = RunId("dragguard-run-id-16c")

    override fun composeFullOrganization(): OrganizationInputComposition =
        error("not reached in the drag-guard oracle")

    override fun detectMissingAppCandidates(): CandidateDetectionResult =
        CandidateDetectionResult.Unavailable(DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE)

    override fun composeScopeComposedOrganization(selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>): OrganizationInputComposition =
        error("not reached in the drag-guard oracle")

    override fun inspectPlan(input: app.lawnchair.organizer.planning.OrganizationInput, result: app.lawnchair.organizer.planning.PlanningResult): PlanPreviewResult =
        error("not reached in the drag-guard oracle")

    override fun materialize(
        input: app.lawnchair.organizer.planning.OrganizationInput,
        result: app.lawnchair.organizer.planning.PlanningResult,
    ): OrganizationPlanMaterializer.Result = error("not reached in the drag-guard oracle")

    override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult =
        error("not reached in the drag-guard oracle")

    override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult =
        RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.MISSING)

    override fun confirmRecovery(pointId: RecoveryPointId, confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation): RecoveryResult =
        error("not reached in the drag-guard oracle")

    override fun readDurableOrganizerStatus(): OrganizerDurableStatus = OrganizerDurableStatus.NEVER_ORGANIZED

    override fun readRestorableRecoveryEntry(): RestorableRecoveryEntry? = null

    override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
        MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)

    override fun inspectCapture(): app.lawnchair.organizer.application.protocol.CapturedSnapshot? = null

    override fun applyWithUndoReceipt(plan: ValidatedLayoutPlan, runId: RunId): Pair<ApplyResult, app.lawnchair.organizer.planning.RevisionId?> =
        error("not reached in the drag-guard oracle")

    override fun recover(request: app.lawnchair.organizer.application.public.RecoveryRequest): RecoveryResult =
        error("not reached in the drag-guard oracle")
}
