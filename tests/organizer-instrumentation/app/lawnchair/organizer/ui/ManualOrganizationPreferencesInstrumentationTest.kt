package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.DeviceCapabilities as PlannerDeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualOrganizationPreferencesInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewRendersScopeReasonWarningAndNoWriteBeforeConfirmation() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_scope, 0, 0, 1),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_moved_single_placement, 1),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_warning_fallback_category, 1),
        ).assertIsDisplayed()
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun cancellingPreviewIsReachableAndDoesNotWrite() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).performClick()
        composeRule.waitUntil(5_000) { runner.state == ManualOrganizationRun.State.Cancelled }
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun recreationLikeRecompositionRetainsPreviewWithoutReplayingWrite() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsDisplayed()

        // The coordinator is the retained owner; rebuilding the screen must not replay the plan.
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsDisplayed()
        assertEquals(0, application.applyCalls)
    }

    private class FakeApplication : ManualOrganizationApplication {
        override val diagnostics = RecordingDiagnostics()
        var applyCalls = 0

        override fun newRunId() = RunId(RUN_ID)

        override fun composeFullOrganization(): OrganizationInputComposition = OrganizationInputComposition.Ready(
            input = input(),
            provenance = InputProvenance(
                revision = RevisionId(REVISION),
                rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
                targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
                policyBundle = PolicyBundleIdentity("v1", SHA_256),
            ),
        )

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
            return ApplyResult.Applied(runId, RecoveryPointId(POINT_ID))
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            pointId,
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult = RecoveryResult.NotRestorable(
            pointId,
            app.lawnchair.organizer.application.public.RecoveryRejection.MISSING,
        )
    }

    private class RecordingDiagnostics : DiagnosticsPort {
        override fun emit(event: RunEvent) = Unit
        override fun snapshot(): List<RunEvent> = emptyList()
    }

    private fun planningResult() = PlanningResult(
        revision = RevisionId(REVISION),
        ruleVersion = RuleVersion("v1"),
        taxonomyVersion = TaxonomyVersion("v1"),
        outcome = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = ItemId("item"),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(
                        page = PageRef(PageId("page")),
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

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
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
            RuleVersion("v1"),
            FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
            DockPolicy.PRESERVE,
            OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            app.lawnchair.organizer.planning.OrderingPolicy.CANONICAL_V1,
        ),
        taxonomy = TaxonomyContract(
            TaxonomyVersion("v1"),
            listOf(app.lawnchair.organizer.planning.CategoryId("other")),
            app.lawnchair.organizer.planning.CategoryId("other"),
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
            app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT,
        ),
        items = emptyList(),
    )

        fun policyIdentity(source: PolicySourceKind) = PolicyInputIdentity(source, "v1", SHA_256)
    }
}
