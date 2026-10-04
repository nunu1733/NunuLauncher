package app.lawnchair.organizer.ui

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.CaptureFailureCategory
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.preferences.destinations.OrganizerStrategyPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #368 visual evidence capture (spec 283 AC-7 pattern, accepted plan
 * Verification step 8): renders the T-05 strategy surface and writes the
 * light/dark selected-unselected affordance, the 200% font reflow, and the
 * expanded (two-pane) simultaneous composition with an operation-active
 * frozen T-05 as PNGs into the device MediaStore
 * (Pictures/Issue368-t05-evidence). The PNGs are pulled and committed under
 * `docs/assessment/assets-368-strategy-picker-t05/`; this class is evidence
 * tooling and is not part of the CI instrumentation lanes.
 */
@RunWith(AndroidJUnit4::class)
class StrategyT05VisualEvidenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Issue368-t05-evidence")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("$name.png"),
        )
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { stream ->
            @Suppress("BlockingMethodInNonBlockingContext")
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun setT05(darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(context.resources.displayMetrics.density, fontScale),
            ) {
                LawnchairTheme(darkTheme = darkTheme) {
                    OrganizerStrategyPreferences(run = previewlessRunner())
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun captureLightAffordance() {
        setT05(darkTheme = false)
        // Scroll so a selected row and unselected rows are on screen at once.
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organization_strategy_tidy_name)),
        )
        capture("t05-light-selected-unselected")
    }

    @Test
    fun captureDarkAffordance() {
        setT05(darkTheme = true)
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context.getString(R.string.organization_strategy_tidy_name)),
        )
        capture("t05-dark-selected-unselected")
    }

    @Test
    fun captureTwoHundredPercentFontReflow() {
        setT05(fontScale = 2f)
        // Bounds evidence: every row is reachable by scrolling and renders
        // within the window width (no clipping) at 200% font scale.
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_category_contiguous_name,
        )) {
            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasText(context.getString(name)),
            )
            composeRule.onNodeWithText(context.getString(name)).assertIsDisplayed()
        }
        val windowWidth = context.resources.displayMetrics.widthPixels
        composeRule.onAllNodesWithTag("manual-organization-strategy-picker", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { node ->
                org.junit.Assert.assertTrue(
                    "picker row must not exceed the window width",
                    node.boundsInRoot.right <= windowWidth,
                )
            }
        capture("t05-200percent-font")
    }

    @Test
    fun captureTwoPaneOperationActiveFrozen() {
        // Expanded settings compose the run surface and the T-05 detail at
        // the same time; while the run operation is active (Selecting), T-05
        // renders the frozen affordance: disabled rows plus the live-region
        // reason line.
        val runner = selectingRunner()
        composeRule.setContent {
            CompositionLocalProvider(LocalIsExpandedScreen provides true) {
                LawnchairTheme {
                    Row(Modifier.testTag("t05-two-pane-host")) {
                        Box(Modifier.weight(1f)) {
                            ManualOrganizationPreferences(run = runner)
                        }
                        Box(Modifier.weight(1f)) {
                            OrganizerStrategyPreferences(run = runner)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Selecting }
        composeRule.onNodeWithTag("strategy-picker-frozen-reason")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite))
        composeRule.onNodeWithText(
            context.getString(R.string.organization_strategy_canonical_name),
        ).assertIsNotEnabled()
        capture("t05-twopane-operation-active-frozen")
        runner.cancel()
    }

    /** A runner parked on the selection surface — an active run operation. */
    private fun selectingRunner(): ManualOrganizationRun {
        val application = object : ManualOrganizationApplication {
            override val diagnostics = object : DiagnosticsPort {
                override fun emit(event: RunEvent) = Unit
                override fun snapshot(): List<RunEvent> = emptyList()
            }

            override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

            override fun detectMissingAppCandidates() = CandidateDetectionResult.Ready(emptyList())

            override fun composeFullOrganization() = notReady()
            override fun composeScopeComposedOrganization(
                selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
            ) = notReady()
            override fun inspectPlan(input: OrganizationInput, result: PlanningResult) =
                PlanPreviewResult.NotPlannable(PlanPreviewRejection.OUTCOME_NOT_PLANNED)
            override fun materialize(
                input: OrganizationInput,
                result: PlanningResult,
            ): OrganizationPlanMaterializer.Result = error("not reached")
            override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = error("not reached")
            override fun inspectRecovery(pointId: RecoveryPointId) = error("not reached")
            override fun confirmRecovery(
                pointId: RecoveryPointId,
                confirmation: RecoveryPreviewConfirmation,
            ) = error("not reached")
            override fun readDurableOrganizerStatus() = OrganizerDurableStatus.NEVER_ORGANIZED
            override val readinessState = MutableStateFlow(
                app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY,
            )

            private fun notReady() = OrganizationInputComposition.NotReady(
                InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
                CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
            )
        }
        return ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") }).also {
            it.start()
        }
    }

    private fun previewlessRunner(): ManualOrganizationRun = ManualOrganizationRun(
        object : ManualOrganizationApplication {
            override val diagnostics = object : DiagnosticsPort {
                override fun emit(event: RunEvent) = Unit
                override fun snapshot(): List<RunEvent> = emptyList()
            }

            override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

            override fun detectMissingAppCandidates() = CandidateDetectionResult.Unavailable(
                app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
            )
            override fun composeFullOrganization() = OrganizationInputComposition.NotReady(
                InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
                CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
            )
            override fun composeScopeComposedOrganization(
                selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
            ) = composeFullOrganization()
            override fun inspectPlan(input: OrganizationInput, result: PlanningResult) =
                PlanPreviewResult.NotPlannable(PlanPreviewRejection.OUTCOME_NOT_PLANNED)
            override fun materialize(
                input: OrganizationInput,
                result: PlanningResult,
            ): OrganizationPlanMaterializer.Result = error("not reached")
            override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = error("not reached")
            override fun inspectRecovery(pointId: RecoveryPointId) = error("not reached")
            override fun confirmRecovery(
                pointId: RecoveryPointId,
                confirmation: RecoveryPreviewConfirmation,
            ) = error("not reached")
            override fun readDurableOrganizerStatus() = OrganizerDurableStatus.NEVER_ORGANIZED
            override val readinessState = MutableStateFlow(
                app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY,
            )
        },
        OrganizationPlanner { error("planner must not run") },
    )
}
