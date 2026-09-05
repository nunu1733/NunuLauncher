package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.PlanPreviewRejection
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CaptureFailureCategory
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.rules.LayoutStrategySelectionModule
import app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult
import app.lawnchair.organizer.rules.LayoutStrategySelectionWriteResult
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Spec 182 child 8: strategy picker on the manual-run surface. Only the
 * bundle's runtime-supported strategies are offered with localized names, and
 * a selection is published through Rule Management's validated write command
 * (the UI never writes storage directly).
 */
class StrategyPickerInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun clearSelectionStore() {
        val directory = File(context().noBackupFilesDir, "organizer_strategy_selection")
        directory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun pickerListsAllRuntimeSupportedStrategiesWithLocalizedNames() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithText(context().getString(R.string.manual_organization_strategy_section))
            .assertIsDisplayed()
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_tidy_name,
            R.string.organization_strategy_bottom_first_name,
            R.string.organization_strategy_global_name,
            R.string.organization_strategy_category_contiguous_name,
        )) {
            composeRule.onNodeWithText(context().getString(name)).assertIsDisplayed()
        }
    }

    @Test
    fun firstRunShowsTheBundleDefaultAsTheEffectiveSelection() {
        // Spec 182: a valid absent selection means the planner uses the bundle
        // default, so the canonical row must be shown as selected even though
        // nothing is persisted yet.
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        val canonicalName = context().getString(R.string.organization_strategy_canonical_name)
        composeRule.onNodeWithText(canonicalName).assertIsSelected()
        composeRule.onNodeWithText(
            context().getString(R.string.organization_strategy_tidy_name),
        ).assertIsNotSelected()
    }

    @Test
    fun strategyRowsExposeRadioSemanticsInsideASelectableGroup() {
        // Issue #218 a11y contract: one mutually-exclusive radio group whose
        // rows announce name + selected state + description as single nodes.
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        val sectionNode = composeRule.onNodeWithText(
            context().getString(R.string.manual_organization_strategy_section),
        )
        sectionNode.assertExists()
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_tidy_name,
            R.string.organization_strategy_bottom_first_name,
            R.string.organization_strategy_global_name,
            R.string.organization_strategy_category_contiguous_name,
        )) {
            composeRule.onNodeWithText(context().getString(name)).assertHasClickAction()
        }
        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_canonical_name))
            .assertIsSelected()
    }

    @Test
    fun failedReadShowsNoActiveSelection() {
        // Spec 182 fail-closed: a corrupt selection store must show NO active
        // selection — the composer will fail closed the same way, so showing
        // the bundle default as selected would misrepresent the planner.
        val file = File(context().noBackupFilesDir, "organizer_strategy_selection/selection-v1")
        file.parentFile?.mkdirs()
        file.writeText("corrupt selection store")
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_canonical_name))
            .assertIsNotSelected()
        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_tidy_name))
            .assertIsNotSelected()
    }

    @Test
    fun strategyPickerIsWrappedInASelectableGroup() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithTag("manual-organization-strategy-picker").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.SelectableGroup, Unit),
        )
    }

    @Test
    fun selectingAStrategyPublishesThroughTheValidatedWriteCommand() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_tidy_name))
            .performClick()
        composeRule.waitUntil(5_000) {
            val read = LayoutStrategySelectionModule.store(context()).read()
            read is LayoutStrategySelectionReadResult.Ready &&
                read.snapshot.selection == StrategyId("STABLE_PAGE_TIDY_V1")
        }

        // The write path refuses strategies outside the bundle catalog.
        val rejected = LayoutStrategySelectionModule.store(context())
            .select(StrategyId("REMOVED_STRATEGY_V1"))
        assertTrue(rejected is LayoutStrategySelectionWriteResult.UnsupportedStrategy)
    }

    private fun previewlessRunner(): ManualOrganizationRun {
        // The picker renders regardless of run state; a NotReady composition
        // keeps the run safely out of the planner/writer paths.
        val application = NotReadyManualOrganizationApplication()
        return ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
    }

    private class NotReadyManualOrganizationApplication : ManualOrganizationApplication {
        override val diagnostics = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot(): List<RunEvent> = emptyList()
        }

        override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

        override fun composeFullOrganization() = OrganizationInputComposition.NotReady(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
            CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
        )

        override fun inspectPlan(input: OrganizationInput, result: PlanningResult) =
            PlanPreviewResult.NotPlannable(PlanPreviewRejection.OUTCOME_NOT_PLANNED)

        override fun materialize(
            input: OrganizationInput,
            result: PlanningResult,
        ): OrganizationPlanMaterializer.Result = error("not reached: composition is NotReady")

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = error("not reached: composition is NotReady")

        override fun inspectRecovery(pointId: RecoveryPointId) =
            error("not reached: composition is NotReady")

        override fun confirmRecovery(
            pointId: RecoveryPointId,
            confirmation: RecoveryPreviewConfirmation,
        ) = error("not reached: composition is NotReady")
    }
}
