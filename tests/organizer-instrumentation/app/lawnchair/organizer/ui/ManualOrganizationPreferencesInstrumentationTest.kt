package app.lawnchair.organizer.ui

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
import app.lawnchair.organizer.diagnostics.model.Trigger
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
    fun inputUnavailableCopySplitsTryAgainLaterFromBugReport() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val pendingApplication = FakeApplication().apply {
            notReadyComposition = OrganizationInputComposition.NotReady(
                reason = app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending,
                diagnostic = app.lawnchair.organizer.integration.CompositionDiagnostic(
                    app.lawnchair.organizer.integration.InputCompositionCode.RECONCILIATION_PENDING,
                ),
            )
        }
        val pendingRunner = ManualOrganizationRun(
            pendingApplication,
            OrganizationPlanner { error("planner must not run") },
        )
        pendingRunner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = pendingRunner)
            }
        }
        composeRule.waitUntil { pendingRunner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_input_not_ready_yet),
        ).assertIsDisplayed()

        val bugApplication = FakeApplication().apply {
            notReadyComposition = OrganizationInputComposition.NotReady(
                reason = app.lawnchair.organizer.integration.InputReadinessReason.SourceUnavailable(
                    PolicySourceKind.ORGANIZER_POLICY_BUNDLE,
                ),
                diagnostic = app.lawnchair.organizer.integration.CompositionDiagnostic(
                    app.lawnchair.organizer.integration.InputCompositionCode.BUNDLE_MISSING,
                ),
            )
        }
        val bugRunner = ManualOrganizationRun(
            bugApplication,
            OrganizationPlanner { error("planner must not run") },
        )
        bugRunner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = bugRunner)
            }
        }
        composeRule.waitUntil { bugRunner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_input_unavailable_bug),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_retry),
        ).assertHasClickAction()
    }

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
        awaitPreview(runner, context)
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
    fun onboardingTriggerUsesTheSameReviewSurfaceWithoutWritingBeforeConfirmation() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start(Trigger.ONBOARDING_PROPOSAL)

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(
                    run = runner,
                    trigger = Trigger.ONBOARDING_PROPOSAL,
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        awaitPreview(runner, context)
        assertEquals(0, application.applyCalls)
        assertEquals(
            setOf(Trigger.ONBOARDING_PROPOSAL),
            application.diagnostics.events.mapNotNull { it.trigger }.toSet(),
        )
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
        awaitPreview(runner, context)
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).performClick()
        composeRule.waitUntil(5_000) { runner.state == ManualOrganizationRun.State.Cancelled }
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun previewHeadingRestoresFocusAndCancelReturnsFocusToStartAction() {
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
        awaitPreview(runner, context)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).performClick()
        composeRule.waitUntil(5_000) { runner.state == ManualOrganizationRun.State.Cancelled }
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.manual_organization_start)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    @Test
    fun previewRemainsReadableAtTwoHundredPercentFontScale() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    ManualOrganizationPreferences(run = runner)
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        awaitPreview(runner, context)
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_moved_single_placement, 1),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).assertIsDisplayed()
    }

    @Test
    fun previewControlsExposeBackAndAssistiveTechnologySemantics() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()
        var dispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        awaitPreview(runner, context)
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm))
            .assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel))
            .assertHasClickAction()

        composeRule.runOnIdle {
            checkNotNull(dispatcher).onBackPressed()
        }
        composeRule.waitUntil(5_000) { runner.state == ManualOrganizationRun.State.Cancelled }
    }

    @Test
    fun keyboardAndSwitchStyleTraversalExposesFocusableReviewActions() {
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
        awaitPreview(runner, context)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
            .assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
            .assertHasClickAction()
    }

    @Test
    fun capturesManualOrganizationReviewSurfaces() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        val displayedRun = mutableStateOf(runner)

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = displayedRun.value)
            }
        }
        captureReviewScreenshot(context, "start")

        runner.start()
        awaitPreview(runner, context)
        captureReviewScreenshot(context, "preview-confirm")

        runner.confirm()
        composeRule.waitForIdle()
        captureReviewScreenshot(context, "success")

        val staleApplication = FakeApplication().apply { materializationInvalid = true }
        val staleRunner = ManualOrganizationRun(
            staleApplication,
            OrganizationPlanner { planningResult() },
        )
        composeRule.runOnIdle {
            displayedRun.value = staleRunner
        }
        composeRule.waitForIdle()
        staleRunner.start()
        awaitPreview(staleRunner, context)
        staleRunner.confirm()
        composeRule.waitUntil(5_000) { staleRunner.state == ManualOrganizationRun.State.Stale }
        captureReviewScreenshot(context, "stale")

        val recoveryApplication = FakeApplication().apply {
            applyResult = ApplyResult.Unresolved(
                RunId(RUN_ID),
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
            )
        }
        val recoveryRunner = ManualOrganizationRun(
            recoveryApplication,
            OrganizationPlanner { planningResult() },
        )
        composeRule.runOnIdle {
            displayedRun.value = recoveryRunner
        }
        composeRule.waitForIdle()
        recoveryRunner.start()
        awaitPreview(recoveryRunner, context)
        recoveryRunner.confirm()
        composeRule.waitUntil(5_000) {
            (recoveryRunner.state as? ManualOrganizationRun.State.Applied)?.result is ApplyResult.Unresolved
        }
        captureReviewScreenshot(context, "recovery-failure")
    }

    @Test
    fun unresolvedResultOffersDiagnosticsInsteadOfStartingAnotherRun() {
        val application = FakeApplication().apply {
            applyResult = ApplyResult.Unresolved(
                RunId(RUN_ID),
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
            )
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Preview }
        runner.confirm()
        composeRule.waitUntil(5_000) {
            (runner.state as? ManualOrganizationRun.State.Applied)?.result is ApplyResult.Unresolved
        }
        var diagnosticsOpened = false
        val showScreen = mutableStateOf(true)

        composeRule.setContent {
            LawnchairTheme {
                if (showScreen.value) {
                    ManualOrganizationPreferences(
                        run = runner,
                        onOpenDiagnostics = {
                            diagnosticsOpened = true
                            showScreen.value = false
                        },
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_apply_unresolved)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_open_diagnostics)).performClick()
        assertEquals(true, diagnosticsOpened)

        // Diagnostics navigation disposes this screen; recreating it must retain the safe terminal state.
        composeRule.waitUntil(5_000) { !showScreen.value }
        composeRule.runOnIdle {
            showScreen.value = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_apply_unresolved)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.manual_organization_start_again)).assertCountEquals(0)
    }

    @Test
    fun recompositionRetainsPreviewWithoutReplayingWrite() {
        val application = FakeApplication()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )
        runner.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val showRecomposedContent = mutableStateOf(false)

        composeRule.setContent {
            LawnchairTheme {
                if (showRecomposedContent.value) {
                    Text("recomposed")
                }
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        // Recomposition must not replay the plan or perform a write.
        composeRule.runOnIdle {
            showRecomposedContent.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            showRecomposedContent.value = false
        }
        composeRule.waitForIdle()
        awaitPreview(runner, context)
        assertEquals(0, application.applyCalls)
    }

    private class FakeApplication : ManualOrganizationApplication {
        override val diagnostics = RecordingDiagnostics()
        var applyCalls = 0
        var applyResult: ApplyResult = ApplyResult.Applied(RunId(RUN_ID), RecoveryPointId(POINT_ID))
        var materializationInvalid = false

        /** Issue #172: override to simulate a NotReady composition for copy-split coverage. */
        var notReadyComposition: OrganizationInputComposition.NotReady? = null

        override fun newRunId() = RunId(RUN_ID)

        override fun composeFullOrganization(): OrganizationInputComposition {
            notReadyComposition?.let { return it }
            return ready()
        }

        private fun ready(): OrganizationInputComposition = OrganizationInputComposition.Ready(
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

        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result {
            if (materializationInvalid) return OrganizationPlanMaterializer.Result.Invalid
            return OrganizationPlanMaterializer.Result.Ready(
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
        }

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls++
            return applyResult
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
        val events = mutableListOf<RunEvent>()

        override fun emit(event: RunEvent) {
            events += event
        }

        override fun snapshot(): List<RunEvent> = events
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

    private fun awaitPreview(runner: ManualOrganizationRun, context: Context) {
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Preview }
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsDisplayed()
    }

    private fun captureReviewScreenshot(context: Context, name: String) {
        composeRule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Issue52-ui-evidence")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        )
        try {
            check(
                resolver.openOutputStream(uri).use { output ->
                    output != null && screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
                },
            )
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

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
