package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.rules.LayoutStrategySelectionModule
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerStrategyPreferences
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerStrategy
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance

/**
 * Issue #368 runtime navigation ordering oracle.
 *
 * Why not the real `PreferenceNavigation` singleton wiring: measured on this
 * environment, a real run started through the production
 * `ManualOrganizationModule` singleton inside the instrumentation process
 * never reaches an active operation — the application module answers
 * `InputUnavailable(ReconciliationPending)` even after the readiness gate
 * settles (T05NAV log evidence; recorded in the evidence README). The
 * destinations therefore bind an injected runner, while the NavHost is
 * configured exactly like the production `PreferenceNavigation` (same
 * shared-axis enter/exit/pop transitions, same destination routes), so the
 * transition-window mechanics under test are the production mechanics.
 *
 * Pins the composition / `onDispose` / `operationActive` ordering the
 * accepted plan's two-pane question asks about: during the shared-axis
 * transition the outgoing run surface and the incoming T-05 transiently
 * coexist, and while the operation is still active T-05 renders its frozen
 * affordance; once the transition finishes the run surface's `onDispose` ->
 * `dismiss()` has run, `operationActive` is false, and T-05 renders unfrozen
 * and writable. Evidence tooling; not part of the CI instrumentation lanes.
 */
@RunWith(AndroidJUnit4::class)
class StrategyT05ProductionNavigationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val composeRule = createComposeRule()

    /** A runner parked on the selection surface — an active run operation. */
    private fun selectingRunner(): ManualOrganizationRun {
        val application = object : ManualOrganizationApplication {
            override val diagnostics = object : app.lawnchair.organizer.diagnostics.DiagnosticsPort {
                override fun emit(event: app.lawnchair.organizer.diagnostics.model.RunEvent) = Unit
                override fun snapshot() = emptyList<app.lawnchair.organizer.diagnostics.model.RunEvent>()
            }

            override fun newRunId() = app.lawnchair.organizer.application.public.RunId(
                "0123456789abcdef0123456789abcdef",
            )

            override fun detectMissingAppCandidates() =
                app.lawnchair.organizer.integration.CandidateDetectionResult.Ready(
                    listOf(
                        app.lawnchair.organizer.integration.DetectedCandidate(
                            target = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                                app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
                                app.lawnchair.organizer.planning.ProfileId("personal"),
                            ),
                            label = "Example",
                            availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
                        ),
                    ),
                )

            override fun composeFullOrganization() = notReady()

            override fun composeScopeComposedOrganization(
                selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
            ) = notReady()

            override fun inspectPlan(
                input: app.lawnchair.organizer.planning.OrganizationInput,
                result: app.lawnchair.organizer.planning.PlanningResult,
            ) = app.lawnchair.organizer.application.public.PlanPreviewResult.NotPlannable(
                app.lawnchair.organizer.application.public.PlanPreviewRejection.OUTCOME_NOT_PLANNED,
            )

            override fun materialize(
                input: app.lawnchair.organizer.planning.OrganizationInput,
                result: app.lawnchair.organizer.planning.PlanningResult,
            ): app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer.Result =
                error("not reached")

            override fun apply(
                plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
                runId: app.lawnchair.organizer.application.public.RunId,
            ) = error("not reached")

            override fun inspectRecovery(
                pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
            ) = error("not reached")

            override fun confirmRecovery(
                pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
                confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
            ) = error("not reached")

            override fun readDurableOrganizerStatus() =
                app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

            override val readinessState: MutableStateFlow<ReadinessGate.State> =
                MutableStateFlow(ReadinessGate.State.READY)

            private fun notReady() =
                app.lawnchair.organizer.integration.OrganizationInputComposition.NotReady(
                    app.lawnchair.organizer.integration.InputReadinessReason.InvalidCanonicalCapture(
                        app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                    ),
                    app.lawnchair.organizer.integration.CompositionDiagnostic(
                        app.lawnchair.organizer.integration.InputCompositionCode.CAPTURE_INVALID,
                    ),
                )
        }
        return ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") }).also {
            it.start()
        }
    }

    @Test
    fun productionTransitionShowsFrozenT05OnlyWhileTheOperationOutlivesIt() {
        val runner = selectingRunner()
        assertTrue(runner.operationActive.value)

        var navController: NavHostController? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalIsExpandedScreen provides true) {
                LawnchairTheme {
                    val controller = rememberNavController()
                    navController = controller
                    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    val slideDistance = rememberSlideDistance()
                    // The production PreferenceNavigation transition setup,
                    // with the destinations bound to the injected runner.
                    NavHost(
                        navController = controller,
                        startDestination = HomeScreenManualOrganization(),
                        enterTransition = { materialSharedAxisXIn(!isRtl, slideDistance) },
                        exitTransition = { materialSharedAxisXOut(!isRtl, slideDistance) },
                        popEnterTransition = { materialSharedAxisXIn(isRtl, slideDistance) },
                        popExitTransition = { materialSharedAxisXOut(isRtl, slideDistance) },
                    ) {
                        composable<HomeScreenManualOrganization> {
                            ManualOrganizationPreferences(
                                run = runner,
                                trigger = Trigger.MANUAL_FULL,
                            )
                        }
                        composable<HomeScreenOrganizerStrategy> {
                            OrganizerStrategyPreferences(run = runner)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithTag("manual-organization-strategy-picker", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )

        // Pin the animation clock so the shared-axis transition holds, then
        // navigate to T-05 exactly like the two-pane dashboard row would.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            navController!!.navigate(HomeScreenOrganizerStrategy)
        }
        composeRule.mainClock.advanceTimeBy(64L)

        // Mid-transition: the outgoing run surface and the incoming T-05
        // coexist, the operation is still active, and T-05 renders frozen.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("manual-organization-strategy-picker", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue("the operation must still be alive mid-transition", runner.operationActive.value)
        composeRule.onNodeWithTag("strategy-picker-frozen-reason", useUnmergedTree = true)
            .assertIsDisplayed()

        // Let the transition finish: the run surface disposes, dismiss() runs,
        // and T-05 renders unfrozen and writable.
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertFalse(runner.operationActive.value)
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("strategy-picker-frozen-reason", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )

        // The supported path leaves T-05 writable: a selection publishes.
        val tidy = context.getString(R.string.organization_strategy_tidy_name)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(tidy))
        composeRule.onNodeWithText(tidy).performClick()
        composeRule.waitUntil(5_000) {
            val read = LayoutStrategySelectionModule.store(context).read()
            read is app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult.Ready &&
                read.snapshot.selection == StrategyId("STABLE_PAGE_TIDY_V1")
        }
    }
}
