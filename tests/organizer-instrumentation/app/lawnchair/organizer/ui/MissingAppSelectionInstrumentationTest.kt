package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectedCandidate
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #228 spec AC-2/AC-3: the missing-app selection surface — icon/label
 * rows, unchecked-by-default multi-select, whole-selection count, search,
 * Select all / Clear all, confirm forwarding the selected identities, and
 * cancel with zero writes.
 */
@RunWith(AndroidJUnit4::class)
class MissingAppSelectionInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val mail = DetectedCandidate(
        target = CandidateTarget.AppKey(ComponentKey("com.example.mail/.Main"), ProfileId("0")),
        label = "Selection Mail",
        availability = Availability.AVAILABLE,
    )
    private val maps = DetectedCandidate(
        target = CandidateTarget.AppKey(ComponentKey("com.example.maps/.Main"), ProfileId("0")),
        label = "Selection Maps",
        availability = Availability.AVAILABLE,
    )
    private val music = DetectedCandidate(
        target = CandidateTarget.AppKey(ComponentKey("com.example.music/.Main"), ProfileId("0")),
        label = "Selection Music",
        availability = Availability.AVAILABLE,
    )

    private class SelectingFakeApplication(
        val candidates: List<DetectedCandidate>,
    ) : ManualOrganizationApplication {
        override val diagnostics = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot(): List<RunEvent> = emptyList()
        }
        var scopeComposeCalls = 0
        var scopeSelection: List<CandidateTarget.AppKey>? = null
        var plainComposeCalls = 0
        var applyCalls = 0

        override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

        override fun detectMissingAppCandidates(): CandidateDetectionResult =
            CandidateDetectionResult.Ready(candidates)

        override fun composeScopeComposedOrganization(selection: List<CandidateTarget.AppKey>): OrganizationInputComposition {
            scopeComposeCalls += 1
            scopeSelection = selection
            return notReady()
        }

        override fun composeFullOrganization(): OrganizationInputComposition {
            plainComposeCalls += 1
            return notReady()
        }

        private fun notReady() = OrganizationInputComposition.NotReady(
            app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending,
            app.lawnchair.organizer.integration.CompositionDiagnostic(
                app.lawnchair.organizer.integration.InputCompositionCode.RECONCILIATION_PENDING,
            ),
        )

        override fun inspectPlan(input: app.lawnchair.organizer.planning.OrganizationInput, result: app.lawnchair.organizer.planning.PlanningResult): PlanPreviewResult =
            PlanPreviewResult.WriterBusy

        override fun materialize(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ): OrganizationPlanMaterializer.Result = error("not reached in the selection tests")

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls += 1
            return ApplyResult.Applied(runId, RecoveryPointId("0123456789abcdef0123456789abcdef"))
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult =
            error("not reached in the selection tests")

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult =
            error("not reached in the selection tests")

        override fun readDurableOrganizerStatus() = app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)
    }

    private fun selectedCountText(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.manual_organization_missing_apps_selected_count, count, count)

    private fun launch(application: SelectingFakeApplication): ManualOrganizationRun {
        val runner = ManualOrganizationRun(
            application,
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run in the selection tests") },
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        runner.start()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.Selecting }
        return runner
    }

    @Test
    fun selectionSurfaceRendersUncheckedRowsWithSearchAndBulkActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = launch(SelectingFakeApplication(listOf(mail, maps, music)))

        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        assertEquals(3, selecting.candidates.size)

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_title)).assertIsDisplayed()
        composeRule.onNodeWithText(selectedCountText(context, 0)).assertIsDisplayed()
        composeRule.onNodeWithText(mail.label).assertIsDisplayed()
        composeRule.onNodeWithText(maps.label).assertIsDisplayed()
        composeRule.onNodeWithText(music.label).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_search_hint)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_select_all)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_clear_all)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_continue)).assertIsDisplayed()
    }

    @Test
    fun togglingRowsUpdatesTheWholeSelectionCount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        launch(SelectingFakeApplication(listOf(mail, maps, music)))

        composeRule.onNodeWithText(mail.label).performClick()
        composeRule.onNodeWithText(music.label).performClick()
        composeRule.onNodeWithText(selectedCountText(context, 2)).assertIsDisplayed()

        // Toggling again deselects.
        composeRule.onNodeWithText(mail.label).performClick()
        composeRule.onNodeWithText(selectedCountText(context, 1)).assertIsDisplayed()
    }

    @Test
    fun selectAllUnderFilterAndClearAllFollowTheSpecRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        launch(SelectingFakeApplication(listOf(mail, maps, music)))

        // Pre-select Mail, then filter to Maps only.
        composeRule.onNodeWithText(mail.label).performClick()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_search_hint))
            .performTextInput("Selection Maps")

        // Select all adds exactly the displayed set (Maps) and keeps Mail.
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_select_all)).performClick()
        composeRule.onNodeWithText(selectedCountText(context, 2)).assertIsDisplayed()

        // Clear all clears the whole set even under the active filter.
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_clear_all)).performClick()
        composeRule.onNodeWithText(selectedCountText(context, 0)).assertIsDisplayed()
    }

    @Test
    fun confirmForwardsTheSelectedIdentitiesToTheScopeComposedCompose() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = SelectingFakeApplication(listOf(mail, maps, music))
        launch(application)

        composeRule.onNodeWithText(maps.label).performClick()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_continue)).performClick()

        composeRule.waitUntil { application.scopeComposeCalls == 1 }
        assertEquals(listOf(maps.target), application.scopeSelection)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun cancelFromTheSelectionSurfaceEndsTheRunWithoutWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = SelectingFakeApplication(listOf(mail, maps, music))
        val runner = launch(application)

        composeRule.onNodeWithText(maps.label).performClick()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).performClick()

        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.Cancelled }
        assertEquals(0, application.scopeComposeCalls)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun zeroCandidatesShowsTheEmptyNoticeAndStillContinues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = SelectingFakeApplication(emptyList())
        val runner = launch(application)

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_missing_apps_continue)).performClick()

        // An empty confirmed selection continues as the plain full organize
        // (the fake composition is NotReady, so the run surfaces that state).
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        org.junit.Assert.assertEquals(1, application.plainComposeCalls)
        org.junit.Assert.assertEquals(0, application.scopeComposeCalls)
    }
}
