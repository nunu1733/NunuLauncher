package app.lawnchair.organizer.ui

import android.app.Activity
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewSummary
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RestorableRecoveryEntry
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.DeviceCapabilities as PlannerDeviceCapabilities
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerHubPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerStrategyPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerUsageMaterialRows
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizer
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerDiagnostics
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerStrategy
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #366: the Organizer hub (T-01). Covers the status card's first phase
 * (durable status in the spec #271 closed vocabulary, checking row, run-active
 * hiding), the negative contract (no restore/AI/run-result affordances, no
 * start() from the hub), the navigation-only start CTA, and the shared
 * personalization material rows.
 */
@RunWith(AndroidJUnit4::class)
class OrganizerHubPreferencesInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Renders the hub as the start destination of a minimal typed graph that also hosts the run surface. */
    private fun setHubContent(
        runner: ManualOrganizationRun,
        fontScale: Float = 1f,
        captureDispatcher: ((OnBackPressedDispatcher?) -> Unit)? = null,
    ) {
        composeRule.setContent {
            if (captureDispatcher != null) {
                captureDispatcher(LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher)
            }
            CompositionLocalProvider(
                LocalDensity provides Density(context.resources.displayMetrics.density, fontScale),
            ) {
                val navController: NavHostController = rememberNavController()
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
                                )
                            }
                            composable<HomeScreenOrganizerDiagnostics> {
                                Text(text = DIAGNOSTICS_STUB_TEXT)
                            }
                            composable<HomeScreenOrganizerStrategy> {
                                OrganizerStrategyPreferences(run = runner)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * HUB-AC-01/02: at Idle with a restorable durable record, the status card
     * renders the closed-vocabulary row, the start CTA, the diagnostics entry,
     * and the full materials group.
     */
    @Test
    fun hubRendersStatusCardAndMaterialsAtIdle() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            runner.state is ManualOrganizationRun.State.Idle
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_start),
        ).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(
            context.getString(R.string.organizer_diagnostics_title),
        ).assertIsDisplayed().assertHasClickAction()

        // Materials group: the existing authoring surfaces. Lower rows can sit
        // below the fold on CI viewports, so scroll each into view before
        // asserting the rendered structure.
        listOf(
            R.string.organizer_hub_materials_heading,
            R.string.organizer_category_overrides_title,
            R.string.organizer_custom_category_title,
            R.string.organizer_lock_screen_title,
            R.string.organizer_strategy_title,
            R.string.organizer_personalization_recording_label,
            R.string.organizer_personalization_usage_access_label,
        ).forEach { res ->
            val text = context.getString(res)
            scrollTextIntoView(text)
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }

        // Not unresolved: no safe-support line on the restorable surface.
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_safe_terminal),
        ).assertDoesNotExist()
    }

    /** HUB-AC-02: the restored/expired status reuses the run surface's exact string. */
    @Test
    fun hubRendersRestoredOrExpiredStatusLine() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.RESTORED_OR_EXPIRED
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restored_or_expired),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restored_or_expired),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()
    }

    /** HUB-AC-02: unresolved keeps the existing safe-support guidance lines. */
    @Test
    fun hubRendersUnresolvedStatusWithSafeSupportGuidance() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.UNRESOLVED
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_unresolved),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_unresolved),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_safe_terminal),
        ).assertIsDisplayed()
        // The safe-support diagnostics entry is the standing hub row.
        composeRule.onNodeWithText(
            context.getString(R.string.organizer_diagnostics_title),
        ).assertHasClickAction()
    }

    /**
     * HUB-AC-02: never organized and the fail-closed unavailable read render
     * no durable status row at all (readiness is terminal, so no checking row
     * either).
     */
    @Test
    fun hubRendersNoStatusRowWhenNeverOrganized() {
        assertNoStatusRowRenders(OrganizerDurableStatus.NEVER_ORGANIZED)
    }

    @Test
    fun hubRendersNoStatusRowWhenFailClosedUnavailable() {
        assertNoStatusRowRenders(OrganizerDurableStatus.UNAVAILABLE)
    }

    private fun assertNoStatusRowRenders(status: OrganizerDurableStatus) {
        val application = FakeHubApplication().apply {
            durableStatus = status
            readiness.value = app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restored_or_expired),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_unresolved),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertDoesNotExist()
    }

    /**
     * HUB-AC-02: a fail-closed read taken while startup reconciliation is
     * running announces the checking row, and the status recovers on the same
     * surface once the gate reaches a terminal state.
     */
    @Test
    fun hubCheckingRowRecoversWhenReconciliationCompletesOnTheSameSurface() {
        val release = java.util.concurrent.CountDownLatch(1)
        val application = FakeHubApplication().apply {
            readiness.value = app.lawnchair.organizer.application.protocol.ReadinessGate.State.RECONCILING
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            readOverride = {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                OrganizerDurableStatus.UNAVAILABLE
            }
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertIsDisplayed()

        release.countDown()
        composeRule.waitForIdle()
        // UNAVAILABLE while the gate is still pending keeps the checking row.
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()

        composeRule.runOnIdle {
            application.readOverride = null
            application.readiness.value = app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertDoesNotExist()
    }

    /**
     * HUB-AC-02: a process-local run keeps precedence. The restorable row is
     * showing when the run starts, so the transition itself must hide it (the
     * re-review escape hole: composing the hub before the run starts catches
     * a stale durable row the "start first" ordering can never see).
     */
    @Test
    fun hubHidesStatusRowsWhileRunIsActiveAndReshowsAfterCancel() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
        }
        val runner = hubRunner(application, plannerResult = planningResult())
        setHubContent(runner)

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()

        // Start the run while the hub keeps showing the durable row.
        composeRule.runOnIdle { runner.start() }
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Preview }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertDoesNotExist()

        composeRule.runOnIdle { runner.cancel() }
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Cancelled }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
    }

    /**
     * HUB-AC-03 (as amended 2026-09-22 by accepted spec 376 / D-15): the
     * status card's restore CTA is the only run-result affordance on the hub;
     * no other run-result actions exist and the hub performs no run activity
     * on its own — `start()` stays exclusive to the run surface.
     */
    @Test
    fun hubExposesTheRestoreCtaAndNoOtherRunResultAffordancesAndStartsNothing() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.manual_organization_recovery_remaining_hours,
                5,
                5,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_start_again),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_safe_terminal),
        ).assertDoesNotExist()
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
        assertEquals(emptyList<RunEvent>(), application.diagnostics.events)
        assertEquals(0, application.applyCalls)
        assertEquals(0, application.previewRequests)
    }

    /**
     * Issue #376 (spec D6): with a fail-closed entry hint the restorable row
     * falls back to display-only — no remaining window and no CTA.
     */
    @Test
    fun hubRestorableRowStaysDisplayOnlyWhenTheEntryHintFailsClosed() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = null
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).assertDoesNotExist()
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
    }

    /**
     * Issue #376 (spec D6 read serialization): the hub reads the status first
     * and the entry hint only after a restorable status — the fake's widened
     * reads would register any concurrent execution as maxConcurrentReads > 1.
     */
    @Test
    fun hubReadsTheEntryHintOnlyAfterARestorableStatusAndNeverConcurrently() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) { application.entryReads >= 1 }

        val log = synchronized(application.readLog) { application.readLog.toList() }
        assertEquals("status", log.first())
        assertEquals("entry", log.last())
        assertEquals(1, application.maxConcurrentReads)
    }

    /** Issue #376 (spec D6): a non-restorable status never reads the entry hint. */
    @Test
    fun hubDoesNotReadTheEntryHintWhenTheStatusIsNotRestorable() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.RESTORED_OR_EXPIRED
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restored_or_expired),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        assertEquals(0, application.entryReads)
        assertEquals(0, synchronized(application.readLog) { application.readLog.count { it == "entry" } })
    }

    /**
     * Issue #376 (spec D5/D6, RS-AC-04): the restore CTA opens the existing
     * recovery flow on the run face; system Back returns to the hub side with
     * the coordinator restored to its pre-entry (Idle) state.
     */
    @Test
    fun restoreCtaOpensTheRecoveryFlowOnTheRunFaceAndBackReturnsToTheHub() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
            recoveryPreview = RecoveryPreviewResult.Restorable(
                pointId = RecoveryPointId(POINT_ID),
                summary = RecoveryPreviewSummary(),
                confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
            )
        }
        val runner = hubRunner(application)
        var dispatcher: OnBackPressedDispatcher? = null
        setHubContent(runner, captureDispatcher = { dispatcher = it })

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).performClick()

        // The flow was admitted: the confirmation face renders on the
        // existing run surface with the closed-vocabulary decision pair.
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery_confirm),
        ).assertIsDisplayed()
        // The entry coroutine publishes the preview before it returns; wait for
        // the inspection observation so Back can never race the admission.
        composeRule.waitUntil(5_000) { application.previewRequests >= 1 }

        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }

        composeRule.waitUntil(10_000) { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // Let the run-face destination leave composition and its back-stack
        // entry settle before teardown, or the NavHost lifecycle races the
        // activity destroy. (The preview-face body text is unique to the run
        // surface — the confirm label duplicates the hub's own CTA string.)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery_preview),
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    /**
     * Issue #376 (RS-AC-01/02, instrumentation side): a successful restore,
     * the explicit hub return, and the hub's durable-status re-derive — a
     * single valid point collapses the row to "restored or expired".
     */
    @Test
    fun restoreSuccessAndExplicitHubReturnReDeriveTheDurableStatus() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
            recoveryPreview = RecoveryPreviewResult.Restorable(
                pointId = RecoveryPointId(POINT_ID),
                summary = RecoveryPreviewSummary(),
                confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
            )
            confirmResult = RecoveryResult.Restored(RecoveryPointId(POINT_ID))
            durableStatusAfterConfirm = OrganizerDurableStatus.RESTORED_OR_EXPIRED
        }
        val runner = hubRunner(application)
        var dispatcher: OnBackPressedDispatcher? = null
        setHubContent(runner, captureDispatcher = { dispatcher = it })

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).performClick()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery_confirm),
        ).performClick()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryResultState }

        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }

        composeRule.waitUntil(5_000) {
            runner.state is ManualOrganizationRun.State.Idle &&
                composeRule.onAllNodesWithText(
                    context.getString(R.string.manual_organization_durable_status_restored_or_expired),
                ).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
    }

    /**
     * Issue #376 (RS-AC-02, instrumentation side): with a surviving older
     * point, restoring the latest and returning to the hub re-presents the
     * remaining point as the next restore target (row + CTA).
     */
    @Test
    fun restoreSuccessRepresentsTheRemainingPointAfterHubReturn() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
            recoveryPreview = RecoveryPreviewResult.Restorable(
                pointId = RecoveryPointId(POINT_ID),
                summary = RecoveryPreviewSummary(),
                confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
            )
            confirmResult = RecoveryResult.Restored(RecoveryPointId(POINT_ID))
            // The store keeps the older point; the re-derive still reads
            // restorable, and the selection now points at the survivor.
            durableStatusAfterConfirm = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntryAfterConfirm = RestorableRecoveryEntry(
                RecoveryPointId(OTHER_POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(2),
            )
        }
        val runner = hubRunner(application)
        var dispatcher: OnBackPressedDispatcher? = null
        setHubContent(runner, captureDispatcher = { dispatcher = it })

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).performClick()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }
        composeRule.waitUntil(5_000) { application.previewRequests >= 1 }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery_confirm),
        ).performClick()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryResultState }
        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }

        // The re-presented row names the surviving point's remaining window.
        composeRule.waitUntil(10_000) {
            runner.state is ManualOrganizationRun.State.Idle &&
                composeRule.onAllNodesWithText(
                    context.resources.getQuantityString(
                        R.plurals.manual_organization_recovery_remaining_hours,
                        2,
                        2,
                    ),
                ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).assertIsDisplayed()
    }

    /**
     * Issue #376 (RS-AC-04, instrumentation side): a failed restore keeps the
     * result/safe-support face across the diagnostics round trip — the host
     * disposal of the diagnostics push must not dissolve the terminal state.
     */
    @Test
    fun restoreFailureKeepsTheResultFaceAcrossTheDiagnosticsRoundTrip() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
            recoveryPreview = RecoveryPreviewResult.Restorable(
                pointId = RecoveryPointId(POINT_ID),
                summary = RecoveryPreviewSummary(),
                confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
            )
            confirmResult = RecoveryResult.RestoreFailed(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RecoveryFailure.RECOVERY_STORE_FAILED,
                app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
            )
        }
        val runner = hubRunner(application)
        var dispatcher: OnBackPressedDispatcher? = null
        setHubContent(runner, captureDispatcher = { dispatcher = it })

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).performClick()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }
        composeRule.waitUntil(5_000) { application.previewRequests >= 1 }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery_confirm),
        ).performClick()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryResultState }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_open_diagnostics),
        ).assertIsDisplayed().performClick()

        // The diagnostics destination is on top; the result face is preserved
        // underneath (state untouched by the push).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(DIAGNOSTICS_STUB_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_safe_terminal),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)
    }

    /**
     * Issue #376 (RS-AC-06, instrumentation side): a fail-closed entry hint
     * keeps the row display-only, and the next re-read trigger recovers the
     * CTA (no permanent CTA loss after transient read contention).
     */
    @Test
    fun failClosedEntryHintRecoversOnTheNextReReadTrigger() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = null
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_recovery),
        ).assertDoesNotExist()

        // Simulate the store gaining a valid point, then pulse the readiness
        // gate — the observable re-read trigger (spec 271 DS-AC-09 contract).
        composeRule.runOnIdle {
            application.restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        composeRule.runOnIdle {
            application.readiness.value =
                app.lawnchair.organizer.application.protocol.ReadinessGate.State.RECONCILING
        }
        composeRule.runOnIdle {
            application.readiness.value =
                app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY
        }

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_recovery),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The bounds top of the safe-support line, for round-trip ordering asserts. */
    private fun topOfSafeSupportLine(): Float = composeRule.onNodeWithText(
        context.getString(R.string.manual_organization_safe_terminal),
    ).fetchSemanticsNode().boundsInRoot.top

    /**
     * HUB-AC-04: the start CTA only navigates to the existing run surface
     * (no start() from the hub); the run starts from the run surface's own
     * start row with the MANUAL_FULL trigger.
     */
    @Test
    fun hubStartCtaNavigatesToRunSurfaceAndRunStartsOnlyFromItsStartRow() {
        val application = FakeHubApplication()
        val runner = hubRunner(application, plannerResult = planningResult())
        setHubContent(runner)

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_start),
        ).assertIsDisplayed().performClick()

        // The run surface is showing (its explainer is unique to it).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_explainer),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)

        // The run starts from the existing start row only.
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_start),
        ).performClick()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Preview }
        assertEquals(
            setOf(Trigger.MANUAL_FULL),
            application.diagnostics.events.mapNotNull { it.trigger }.toSet(),
        )
        assertEquals(0, application.applyCalls)
    }

    /**
     * Issue #368 (AC-1): the hub materials group carries the strategy entry
     * (T-05) and opening it shows the picker — the run surface keeps none.
     */
    @Test
    fun hubStrategyEntryOpensTheMaterialsStrategySurface() {
        val application = FakeHubApplication()
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Idle }
        val entry = context.getString(R.string.organizer_strategy_title)
        scrollTextIntoView(entry)
        composeRule.onNodeWithText(entry).assertIsDisplayed().performClick()

        // The T-05 surface is showing (its picker tag is unique to it).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("manual-organization-strategy-picker", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** HUB-AC-07: TalkBack order is state first, then the actions, then materials. */
    @Test
    fun hubStatusRowsPrecedeTheActionsInReadingOrder() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        fun topOf(text: String): Float = composeRule.onNodeWithText(text)
            .fetchSemanticsNode().boundsInRoot.top
        val statusTop = topOf(context.getString(R.string.manual_organization_durable_status_restorable))
        val remainingTop = topOf(
            context.resources.getQuantityString(
                R.plurals.manual_organization_recovery_remaining_hours,
                5,
                5,
            ),
        )
        val restoreCtaTop = topOf(context.getString(R.string.manual_organization_recovery))
        val startTop = topOf(context.getString(R.string.manual_organization_start))
        val diagnosticsTop = topOf(context.getString(R.string.organizer_diagnostics_title))
        val materialsTop = topOf(context.getString(R.string.organizer_hub_materials_heading))
        assert(statusTop < remainingTop) { "status row must precede the remaining window" }
        assert(remainingTop < restoreCtaTop) { "remaining window must precede the restore CTA (TO-BE 13-5)" }
        assert(restoreCtaTop < startTop) { "restore CTA must precede the start CTA" }
        assert(startTop < diagnosticsTop) { "start CTA must precede the diagnostics entry" }
        assert(diagnosticsTop < materialsTop) { "diagnostics entry must precede the materials" }
    }

    /** HUB-AC-07: the status card and its actions remain reachable at 200% font scale. */
    @Test
    fun hubStatusCardStaysReachableAtTwoHundredPercentFontScale() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        val runner = hubRunner(application)
        setHubContent(runner, fontScale = 2f)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_start),
        ).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(
            context.getString(R.string.organizer_diagnostics_title),
        ).assertIsDisplayed().assertHasClickAction()
        // Materials sit below the critical actions; presence at 200% is the
        // structure claim — reachability is exercised by traversal with
        // bring-into-view scrolling.
        scrollTextIntoView(context.getString(R.string.organizer_hub_materials_heading))
        composeRule.onNodeWithText(
            context.getString(R.string.organizer_hub_materials_heading),
        ).assertIsDisplayed()
    }

    /** Scrolls the list until [text] is composed and on screen. */
    private fun scrollTextIntoView(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    /**
     * HUB-AC-06: both surfaces render the same shared rows; toggling on one
     * flips the same preference the other one reads (one truth, two windows).
     */
    @Test
    fun recordingToggleSharesOnePreferenceAcrossSurfaces() {
        composeRule.setContent {
            LawnchairTheme {
                Column {
                    PreferenceGroup(heading = "hub") { OrganizerUsageMaterialRows() }
                    PreferenceGroup(heading = "settings") { OrganizerUsageMaterialRows() }
                }
            }
        }
        val label = context.getString(R.string.organizer_personalization_recording_label)
        composeRule.onAllNodesWithText(label).assertCountEquals(2)

        fun switchStates(): List<Boolean> = composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        )
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On }
        val before = switchStates()
        assertEquals(2, before.size)

        composeRule.onAllNodesWithText(label)[0].performClick()
        composeRule.waitUntil(5_000) { switchStates()[0] != before[0] }
        // The second surface reads the same shared preference.
        composeRule.waitUntil(5_000) { switchStates()[1] != before[0] }
        // Restore the original state so the fixture stays predictable.
        composeRule.onAllNodesWithText(label)[0].performClick()
        composeRule.waitUntil(5_000) { switchStates()[0] == before[0] }
    }

    /**
     * HUB-AC-07: every interactive row exposes its label as the accessible
     * name plus a click action, and the recording toggle reports the switch
     * role and its on/off state to assistive technology.
     */
    @Test
    fun hubRowsExposeNameRoleAndStateToAssistiveTechnology() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_durable_status_restorable),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        listOf(
            R.string.manual_organization_recovery,
            R.string.manual_organization_start,
            R.string.organizer_diagnostics_title,
            R.string.organizer_category_overrides_title,
            R.string.organizer_custom_category_title,
            R.string.organizer_lock_screen_title,
            R.string.organizer_personalization_usage_access_label,
        ).forEach { res ->
            val text = context.getString(res)
            scrollTextIntoView(text)
            composeRule.onNodeWithText(text).assertHasClickAction()
        }
        // Issue #376 (RS-AC-06): the restore CTA exposes an explicit Button
        // role, distinct from the row's plain status text and scoped to the
        // CTA (the scaffold's top bar hosts another Button role).
        scrollTextIntoView(context.getString(R.string.manual_organization_recovery))
        composeRule.onNode(
            hasText(context.getString(R.string.manual_organization_recovery))
                .and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)),
        ).assertExists()
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        ).assertExists()
    }

    /**
     * HUB-AC-07: keyboard traversal reaches status → start → diagnostics →
     * materials in the composed order, and every visited control exposes a
     * click action it can actually activate.
     */
    @Test
    fun hubTraversalReachesStartDiagnosticsAndMaterialsInOrder() {
        val application = FakeHubApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            restorableEntry = RestorableRecoveryEntry(
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
            )
        }
        val runner = hubRunner(application)
        setHubContent(runner)

        // The deterministic entry focus lands on the start CTA first. With the
        // restore CTA present (RS-AC-06) the composed order — and with it the
        // traversal order — carries 状態 → 残期限 → 復元CTA → 開始; the reading
        // order and role oracles pin that structure without depending on the
        // emulator's DPAD focus quirks.
        awaitFocused(context.getString(R.string.manual_organization_start))
        val order = listOf(
            R.string.organizer_diagnostics_title,
            R.string.organizer_category_overrides_title,
            R.string.organizer_custom_category_title,
            R.string.organizer_lock_screen_title,
            R.string.organizer_strategy_title,
            R.string.organizer_personalization_recording_label,
            R.string.organizer_personalization_usage_access_label,
        )
        order.forEach { res ->
            val text = context.getString(res)
            pressDownUntilFocused(text)
            composeRule.onNodeWithText(text).assertHasClickAction()
        }
    }

    /**
     * HUB-AC-07: keyboard activation of the focused start CTA opens the run
     * surface, and system Back returns to the hub with the focus restored
     * deterministically on the start CTA.
     */
    @Test
    fun hubFocusRestoresToStartAfterBackFromRunSurface() {
        val application = FakeHubApplication()
        val runner = hubRunner(application)
        var dispatcher: OnBackPressedDispatcher? = null
        setHubContent(runner, captureDispatcher = { dispatcher = it })

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Idle }
        awaitFocused(context.getString(R.string.manual_organization_start))

        ensureWindowFocusedForComposeHost()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_explainer),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)

        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        // Back lands on the hub again; its standing diagnostics row is always
        // present regardless of the durable state (the fixture is
        // never-organized, so no durable row is expected).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_diagnostics_title),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        awaitFocused(context.getString(R.string.manual_organization_start))
    }

    private fun awaitFocused(text: String) {
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(text).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    /** Issues real DPAD key presses until [text] owns focus; fails after too many steps. */
    private fun pressDownUntilFocused(text: String, maxPresses: Int = 12) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ensureWindowFocusedForComposeHost()
        var presses = 0
        while (presses < maxPresses) {
            composeRule.waitForIdle()
            val focused = try {
                composeRule.onNodeWithText(text).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
            if (focused) return
            ensureWindowFocusedForComposeHost()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            presses++
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text).assertIsFocused()
    }

    private fun ensureWindowFocusedForComposeHost() {
        var hosts: List<Activity> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            hosts = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .toList()
        }
        when (hosts.size) {
            1 -> InjectedInputEnvironment.ensureWindowFocused(hosts.single())
            0 -> error(
                "input environment gate could not resolve the compose host activity " +
                    "(no RESUMED activity); refusing real key injection without a focus observation",
            )
            else -> error(
                "input environment gate could not resolve the compose host activity uniquely " +
                    "(${hosts.size} RESUMED activities); refusing real key injection without a " +
                    "focus observation",
            )
        }
    }

    private fun hubRunner(
        application: ManualOrganizationApplication,
        plannerResult: PlanningResult? = null,
    ): ManualOrganizationRun = ManualOrganizationRun(
        application,
        OrganizationPlanner {
            checkNotNull(plannerResult) { "hub must not trigger planning" }
        },
    )

    private fun planningResult() = PlanningResult(
        revision = RevisionId(REVISION),
        ruleVersion = RuleVersion("v1"),
        taxonomyVersion = TaxonomyVersion("v1"),
        organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        outcome = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = app.lawnchair.organizer.planning.ItemId("item"),
                    disposition = app.lawnchair.organizer.planning.Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(
                        page = app.lawnchair.organizer.planning.PageRef(app.lawnchair.organizer.planning.PageId("page")),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                ),
            ),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = listOf(Warning(WarningCode.FALLBACK_CATEGORY, emptyList())),
        ),
    )

    private class RecordingDiagnostics : DiagnosticsPort {
        val events = mutableListOf<RunEvent>()

        override fun emit(event: RunEvent) {
            events += event
        }

        override fun snapshot(): List<RunEvent> = events
    }

    /**
     * Hub-focused stand-in for the run surface's FakeApplication. Run
     * operations mirror the minimal legacy count-only flow so the
     * navigation/hiding tests can drive a real preview; everything else the
     * hub must never call is left to fail loudly.
     */
    private class FakeHubApplication : ManualOrganizationApplication {
        override val diagnostics = RecordingDiagnostics()
        var applyCalls = 0

        var durableStatus: OrganizerDurableStatus = OrganizerDurableStatus.NEVER_ORGANIZED
        var readOverride: (() -> OrganizerDurableStatus)? = null
        var readiness = kotlinx.coroutines.flow.MutableStateFlow(
            app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY,
        )

        // Issue #376: the D-15 restore-entry hint and the read-order/concurrency
        // tracking for the spec D6 serialization oracle. Every read is widened
        // with a sleep so a parallel caller would register as concurrency.
        var restorableEntry: RestorableRecoveryEntry? = null
        var entryReads = 0
        var previewRequests = 0
        var recoveryPreview: RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            RecoveryPointId(POINT_ID),
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )
        var confirmResult: RecoveryResult = RecoveryResult.NotRestorable(
            RecoveryPointId(POINT_ID),
            RecoveryRejection.MISSING,
        )

        /** Simulates the recovery store changing under a successful restore. */
        var durableStatusAfterConfirm: OrganizerDurableStatus? = null
        var restorableEntryAfterConfirm: RestorableRecoveryEntry? = null

        val readLog = java.util.Collections.synchronizedList(mutableListOf<String>())
        private var activeReads = 0
        var maxConcurrentReads = 0
            private set

        private fun <T> trackRead(name: String, block: () -> T): T {
            synchronized(readLog) {
                activeReads += 1
                if (activeReads > maxConcurrentReads) maxConcurrentReads = activeReads
                readLog.add(name)
            }
            try {
                Thread.sleep(50)
                return block()
            } finally {
                synchronized(readLog) { activeReads -= 1 }
            }
        }

        override fun readDurableOrganizerStatus(): OrganizerDurableStatus = trackRead("status") {
            readOverride?.invoke() ?: durableStatus
        }

        override fun readRestorableRecoveryEntry(): RestorableRecoveryEntry? = trackRead("entry") {
            entryReads++
            restorableEntry
        }

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> = readiness

        override fun newRunId() = RunId(RUN_ID)

        override fun detectMissingAppCandidates(): CandidateDetectionResult = CandidateDetectionResult.Unavailable(
            DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

        override fun composeFullOrganization(): OrganizationInputComposition = OrganizationInputComposition.Ready(
            input = input(),
            provenance = InputProvenance(
                revision = RevisionId(REVISION),
                rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
                targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
                policyBundle = PolicyBundleIdentity("v1", SHA_256),
                layoutStrategySelection = policyIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION),
            ),
        )

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): OrganizationInputComposition = composeFullOrganization()

        override fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult = PlanPreviewResult.WriterBusy

        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result = OrganizationPlanMaterializer.Result.Ready(
            ValidatedLayoutPlan(
                sourceRevision = input.snapshot.revision,
                sourceState = emptyLayoutState(),
                intendedState = emptyLayoutState(),
                actions = emptyList(),
                newPages = emptyList(),
                newFolders = emptyList(),
                ruleVersion = input.rules.version,
                taxonomyVersion = input.taxonomy.version,
            ),
        )

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls++
            return ApplyResult.Applied(RunId(RUN_ID), RecoveryPointId(POINT_ID))
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult {
            previewRequests++
            return recoveryPreview
        }

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult {
            durableStatusAfterConfirm?.let { durableStatus = it }
            restorableEntryAfterConfirm?.let { restorableEntry = it }
            return confirmResult
        }

        private fun policyIdentity(source: PolicySourceKind) = PolicyInputIdentity(source, "v1", SHA_256)
    }

    private companion object {
        const val DIAGNOSTICS_STUB_TEXT = "issue376-diagnostics-stub"
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OTHER_POINT_ID = "dddddddddddddddddddddddddddddddd"
        const val REVISION = "revision"
        const val SHA_256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun input() = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId(REVISION),
                device = PlannerDeviceCapabilities(4, 5, 5, 3, 4, Orientation.PORTRAIT),
                pages = listOf(Page(PageId("page"), PageOrder(0))),
                items = emptyList(),
            ),
            rules = RuleSemantics(
                RuleVersion("v2"),
                app.lawnchair.organizer.planning.FolderPolicy(2, app.lawnchair.organizer.planning.NewFolderProfileScope.SAME_PROFILE_ONLY),
                DockPolicy.PRESERVE,
                OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = TaxonomyContract(
                TaxonomyVersion("v1"),
                listOf(CategoryId("other")),
                CategoryId("other"),
            ),
            catalog = ActiveCategoryCatalog(
                TaxonomyContract(
                    TaxonomyVersion("v1"),
                    listOf(CategoryId("other")),
                    CategoryId("other"),
                ),
                emptyList(),
            ),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(emptyList(), emptyList()),
            runMode = RunMode.FullOrganization,
        )

        fun emptyLayoutState() = LayoutState(
            pages = emptyList(),
            profiles = emptyList(),
            deviceCapabilities = DeviceCapabilities(
                4,
                5,
                5,
                3,
                4,
                DeviceOrientation.PORTRAIT,
            ),
            items = emptyList(),
        )
    }
}
