package app.lawnchair.organizer.ui

import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.NewFolderChange
import app.lawnchair.organizer.application.public.NewPageChange
import app.lawnchair.organizer.application.public.PlanPreview
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PreservedChange
import app.lawnchair.organizer.application.public.PreviewCounts
import app.lawnchair.organizer.application.public.PreviewFolderRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.PreviewPlacementIdentity
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewSummary
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.CategoryId
import org.junit.Assert.assertFalse
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
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
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
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
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
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualOrganizationPreferencesInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reconciliationPendingShowsTryAgainLaterCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            notReadyComposition = OrganizationInputComposition.NotReady(
                reason = app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending,
                diagnostic = app.lawnchair.organizer.integration.CompositionDiagnostic(
                    app.lawnchair.organizer.integration.InputCompositionCode.RECONCILIATION_PENDING,
                ),
            )
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { error("planner must not run") },
        )
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_input_not_ready_yet),
        ).assertIsDisplayed()
    }

    @Test
    fun sourceUnavailableShowsBugReportCopyWithRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            notReadyComposition = OrganizationInputComposition.NotReady(
                reason = app.lawnchair.organizer.integration.InputReadinessReason.SourceUnavailable(
                    PolicySourceKind.ORGANIZER_POLICY_BUNDLE,
                ),
                diagnostic = app.lawnchair.organizer.integration.CompositionDiagnostic(
                    app.lawnchair.organizer.integration.InputCompositionCode.BUNDLE_MISSING,
                ),
            )
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { error("planner must not run") },
        )
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_input_unavailable_bug),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_retry),
        ).assertHasClickAction()
    }

    /**
     * Issue #271 (DS-AC-07): the durable status projection renders on the
     * Idle surface and never leaks anything beyond the closed wording.
     */
    @Test
    fun durableRestorableStatusRendersWhileIdle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { error("planner must not run") },
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertIsDisplayed()
    }

    /**
     * Issue #271 review (P1/P2): a durable-status read taken while startup
     * reconciliation is still running fails closed; the surface announces the
     * loading state instead of looking "never organized", and once readiness
     * reaches a terminal state on the same Idle surface the status re-reads
     * and recovers without navigation.
     */
    @Test
    fun durableStatusRecoversWhenReconciliationCompletesOnTheSameSurface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val release = java.util.concurrent.CountDownLatch(1)
        val application = FakeApplication().apply {
            readiness.value = app.lawnchair.organizer.application.protocol.ReadinessGate.State.RECONCILING
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
            readOverride = {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                OrganizerDurableStatus.UNAVAILABLE
            }
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { error("planner must not run") },
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.Idle }
        // Loading is announced, never silently equated with "never organized".
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertIsDisplayed()

        release.countDown()
        composeRule.waitForIdle()
        // The read returned UNAVAILABLE while the gate is still pending: the
        // checking row persists (the durable truth is not yet known) and no
        // durable status row renders.
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_checking),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()

        // Reconciliation completes while the surface stays open: the status
        // re-reads and recovers on the same surface.
        composeRule.runOnIdle {
            application.readOverride = null
            application.readiness.value = app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY
        }
        composeRule.waitUntil {
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
     * Issue #271 (DS-AC-07): the unresolved durable status reuses the existing
     * safe-support guidance rows.
     */
    @Test
    fun durableUnresolvedStatusRendersSafeSupportGuidance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            durableStatus = OrganizerDurableStatus.UNRESOLVED
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { error("planner must not run") },
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_unresolved),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_safe_terminal),
        ).assertIsDisplayed()
    }

    /**
     * Issue #271 (DS-AC-07): never-organized and fail-closed unavailable
     * render no durable status row at all.
     */
    private fun assertNoDurableRowRenders(status: OrganizerDurableStatus) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = ManualOrganizationRun(
            FakeApplication().apply { durableStatus = status },
            OrganizationPlanner { error("planner must not run") },
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.Idle }
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restored_or_expired),
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_unresolved),
        ).assertDoesNotExist()
    }

    @Test
    fun neverOrganizedRendersNoDurableRow() {
        assertNoDurableRowRenders(OrganizerDurableStatus.NEVER_ORGANIZED)
    }

    @Test
    fun failClosedUnavailableRendersNoDurableRow() {
        assertNoDurableRowRenders(OrganizerDurableStatus.UNAVAILABLE)
    }

    /**
     * Issue #271 (DS-AC-07): an active process-local run keeps precedence —
     * no durable status row renders next to run states.
     */
    @Test
    fun durableStatusIsHiddenWhileARunIsActive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            durableStatus = OrganizerDurableStatus.ORGANIZED_RESTORABLE
        }
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
        awaitPreview(runner, context)
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_durable_status_restorable),
        ).assertDoesNotExist()
        assertEquals(0, application.applyCalls)
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
        // Issue #201: the named new-folder row itself must stay displayed /
        // reachable at 200% font scale, so the concrete preview is injected.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ -> previewed(concreteChangeListDetails()) }
        }
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

        awaitPreview(runner, context)
        // Concrete details follow spec 195 §D2: the header count is the
        // PreviewCounts total and Summary-derived count lines never render.
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_moved_count, 2)).assertIsDisplayed()
        composeRule.onAllNodesWithText(
            context.getString(R.string.manual_organization_moved_single_placement, 1),
        ).assertCountEquals(0)
        composeRule.onNodeWithText(
            context.getString(
                R.string.manual_organization_preview_new_folder_row,
                "Communication",
                workspacePosition(context, 2, RowBand.CENTER, ColumnBand.LEFT),
                "game, maps",
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).assertIsDisplayed()
    }

    @Test
    fun previewDetailsHeaderShowsTheSeparateWidgetMoveCount() {
        // Issue #235 (owner review): AC-8's separate widget count must be
        // visible on the concrete change list — a dedicated header line next
        // to the move total, not folded into it.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val widgetMove = MoveChange(
            item = ItemId("clock"),
            label = PreviewLabel.Named("clock"),
            identity = PreviewPlacementIdentity.Workspace(1, false, 2, 0),
            kind = CanonicalItemKind.AppWidget,
            source = workspace(1, RowBand.TOP, ColumnBand.LEFT, 1),
            destination = workspace(1, RowBand.TOP, ColumnBand.LEFT, 2),
            rationale = PlacementCode.WIDGET_UNIT,
        )
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = listOf(
                            move("game", sourceRowOrdinal = 2, destinationRowOrdinal = 1),
                            widgetMove,
                        ),
                        counts = PreviewCounts(
                            movedCount = 2,
                            preservedCount = 0,
                            newFolderCount = 0,
                            newPageCount = 0,
                            warningCounts = emptyMap(),
                            widgetMovedCount = 1,
                        ),
                    ),
                )
            }
        }
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

        awaitPreview(runner, context)
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_moved_count, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_widget_moved_count, 1)).assertIsDisplayed()
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun generatedFolderTitlesResolveActualResourcesAndFallback() {
        // Issue #201 (FN-AC-15): the production resolver must resolve the v1
        // taxonomy categories from actual localized resources and map unknown
        // categories to the generic fallback via the total lookup — never a
        // raw category ID and never an exception path.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val japanese = context.createConfigurationContext(
            Configuration().apply { setLocale(Locale.JAPAN) },
        )
        val english = context.createConfigurationContext(
            Configuration().apply { setLocale(Locale.ENGLISH) },
        )
        // Exercise the production resolver(Context) adapter itself with a
        // locale-aware context (FN-AC-15), not only the provider overload.
        val japaneseResolver = GeneratedFolderTitles.resolver(japanese)
        val englishResolver = GeneratedFolderTitles.resolver(english)
        val communication = FolderNaming.FromCategory(CategoryId("COMMUNICATION"))

        assertEquals("通信", japaneseResolver.resolve(communication))
        assertEquals("Communication", englishResolver.resolve(communication))

        val unknown = FolderNaming.FromCategory(CategoryId("NOT_IN_V1_TAXONOMY"))
        val fallback = japaneseResolver.resolve(unknown)
        assertEquals(japanese.getString(R.string.organizer_generated_folder_fallback_name), fallback)
        assertFalse("raw category id must not leak: $fallback", fallback.contains("NOT_IN_V1_TAXONOMY"))
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
    fun previewDetailsRenderConcreteChangeListMatchingPreviewCounts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ -> previewed(concreteChangeListDetails()) }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_changes_heading)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_group_moved, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_group_new_folders, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_group_new_pages, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_group_preserved, 1)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.manual_organization_group_warnings, 0)).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_moved_count, 2)).assertIsDisplayed()
        // Spec §D2: Summary-derived change-count lines never mix with PreviewCounts truth.
        composeRule.onAllNodesWithText(
            context.getString(R.string.manual_organization_moved_single_placement, 1),
        ).assertCountEquals(0)

        // Same-band row change: the region words cannot express it, so the
        // row-ordinal note rides on the destination (assessment §6.1).
        composeRule.onNodeWithText(gameMoveRow(context)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.manual_organization_preview_move_row,
                context.getString(
                    R.string.manual_organization_preview_item_descriptor,
                    "maps",
                    context.getString(R.string.manual_organization_preview_kind_application),
                    workspacePosition(context, 1, RowBand.TOP, ColumnBand.CENTER),
                ),
                context.getString(R.string.manual_organization_preview_position_dock, 2),
                context.getString(R.string.manual_organization_preview_move_reason_single_placement),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.manual_organization_preview_new_folder_row,
                "Communication",
                workspacePosition(context, 2, RowBand.CENTER, ColumnBand.LEFT),
                "game, maps",
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_preview_new_page_row, 3),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.manual_organization_preview_item_row,
                context.getString(
                    R.string.manual_organization_preview_item_descriptor,
                    "clock",
                    context.getString(R.string.manual_organization_preview_kind_application),
                    context.getString(R.string.manual_organization_preview_position_dock, 3),
                ),
                context.getString(R.string.manual_organization_preview_preserved_reason_locked),
            ),
        ).assertIsDisplayed()
        assertEquals(0, application.applyCalls)
    }

    /**
     * Issue #212 R1/R3 evidence, inverted by Issue #234: two moves whose
     * resolved anchors differ only inside one coarse band (page 2, (0,0) vs
     * (1,0)) now render DIFFERENT destination texts on the actual Organizer
     * card — each identifies its resolved anchor exactly ("top left, page 2,
     * row 1, column 1" / "… column 2"). The R1 FAIL state (identical
     * "top left, page 2" for both rows) no longer reproduces.
     */
    @Test
    fun distinctAnchorsInsideOneBandRenderDistinctDestinationTextOnTheCard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = listOf(
                            move(
                                "game",
                                sourceRowOrdinal = 1,
                                destinationRowOrdinal = 1,
                                destination = workspace(2, RowBand.TOP, ColumnBand.LEFT, 1, columnOrdinal = 1),
                            ),
                            move(
                                "maps",
                                sourceRowOrdinal = 1,
                                destinationRowOrdinal = 1,
                                destination = workspace(2, RowBand.TOP, ColumnBand.LEFT, 1, columnOrdinal = 2),
                            ),
                        ),
                        counts = PreviewCounts(movedCount = 2, preservedCount = 0, newFolderCount = 0, newPageCount = 0, warningCounts = emptyMap()),
                    ),
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        // Both rows render with anchor-specific destinations: the F-03
        // identical "top left, page 2" wording is gone, and each destination
        // narrows the candidate anchors to exactly one.
        val kind = context.getString(R.string.manual_organization_preview_kind_application)
        fun moveRow(label: String, columnOrdinal: Int) = context.getString(
            R.string.manual_organization_preview_move_row,
            context.getString(
                R.string.manual_organization_preview_item_descriptor,
                label,
                kind,
                workspacePosition(context, 1, RowBand.TOP, ColumnBand.CENTER),
            ),
            workspaceDestination(context, 2, RowBand.TOP, ColumnBand.LEFT, 1, columnOrdinal),
            context.getString(R.string.manual_organization_preview_move_reason_single_placement),
        )
        composeRule.onNodeWithText(moveRow("game", 1)).assertIsDisplayed()
        composeRule.onNodeWithText(moveRow("maps", 2)).assertIsDisplayed()
        assertEquals(0, application.applyCalls)
    }

    /**
     * Issue #208 (F8, 終了条件 2): same-named placements spanning the Move and
     * Preserve buckets — a home-screen icon that moves, a folder child that is
     * kept, and a widget with the same title. Every row must carry the source
     * placement's distinguishing elements (kind word + current position) so a
     * person can tell the rows apart on the rendered card; asserting only
     * whole-row inequality would pass on the original F-01 display.
     */
    @Test
    fun sameNamedPlacementsAcrossBucketsStayDistinguishableOnTheCard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = listOf(
                            // icon (home): moves as a single placement.
                            MoveChange(
                                item = ItemId("gmail.icon"),
                                label = PreviewLabel.Named("Gmail"),
                                identity = PreviewPlacementIdentity.Workspace(2, false, 0, 4),
                                kind = CanonicalItemKind.Application,
                                source = workspace(2, RowBand.BOTTOM, ColumnBand.LEFT, 5),
                                destination = workspace(2, RowBand.TOP, ColumnBand.LEFT, 1),
                                rationale = PlacementCode.SINGLE_PLACEMENT,
                            ),
                            // folder child: kept, inside a named folder.
                            PreservedChange(
                                item = ItemId("gmail.child"),
                                label = PreviewLabel.Named("Gmail"),
                                identity = PreviewPlacementIdentity.FolderChild(
                                    PreviewPlacementIdentity.Workspace(1, false, 3, 3),
                                    0,
                                ),
                                kind = CanonicalItemKind.Application,
                                current = PreviewPosition.InFolder(
                                    PreviewFolderRef.Existing(PreviewLabel.Named("Work")),
                                    1,
                                ),
                                reason = PreserveReason.NON_TARGET,
                            ),
                            // widget: kept, kind word distinguishes the icon.
                            PreservedChange(
                                item = ItemId("gmail.widget"),
                                label = PreviewLabel.Named("Gmail"),
                                identity = PreviewPlacementIdentity.Workspace(1, false, 0, 0),
                                kind = CanonicalItemKind.AppWidget,
                                current = workspace(1, RowBand.TOP, ColumnBand.LEFT, 1),
                                reason = PreserveReason.WIDGET,
                            ),
                            // folder unit: kept at its own workspace cell.
                            PreservedChange(
                                item = ItemId("gmail.folder"),
                                label = PreviewLabel.Named("Gmail"),
                                identity = PreviewPlacementIdentity.Workspace(1, false, 2, 0),
                                kind = CanonicalItemKind.Folder,
                                current = workspace(1, RowBand.TOP, ColumnBand.CENTER, 1),
                                reason = PreserveReason.NON_TARGET,
                            ),
                            // dock icon: kept, dock slot word.
                            PreservedChange(
                                item = ItemId("gmail.dock"),
                                label = PreviewLabel.Named("Gmail"),
                                identity = PreviewPlacementIdentity.Dock(4),
                                kind = CanonicalItemKind.Application,
                                current = PreviewPosition.DockRank(4),
                                reason = PreserveReason.DOCK,
                            ),
                            // app pair: kept, pair name word.
                            PreservedChange(
                                item = ItemId("gmail.pair"),
                                label = PreviewLabel.Named("Gmail"),
                                identity = PreviewPlacementIdentity.AppPairChild(
                                    PreviewPlacementIdentity.Workspace(1, false, 3, 0),
                                    SplitStage.TOP_OR_LEFT,
                                ),
                                kind = CanonicalItemKind.AppPair,
                                current = PreviewPosition.InAppPair(PreviewLabel.Named("Gmail")),
                                reason = PreserveReason.APP_PAIR,
                            ),
                        ),
                        counts = PreviewCounts(movedCount = 1, preservedCount = 5, newFolderCount = 0, newPageCount = 0, warningCounts = emptyMap()),
                    ),
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        val appKind = context.getString(R.string.manual_organization_preview_kind_application)
        val widgetKind = context.getString(R.string.manual_organization_preview_kind_app_widget)
        val folderKind = context.getString(R.string.manual_organization_preview_kind_folder)
        val pairKind = context.getString(R.string.manual_organization_preview_kind_app_pair)
        fun descriptor(name: String, kind: String, position: String) = context.getString(
            R.string.manual_organization_preview_item_descriptor,
            name,
            kind,
            position,
        )
        val moveRow = context.getString(
            R.string.manual_organization_preview_move_row,
            descriptor(
                "Gmail",
                appKind,
                workspacePosition(context, 2, RowBand.BOTTOM, ColumnBand.LEFT),
            ),
            workspaceDestination(context, 2, RowBand.TOP, ColumnBand.LEFT, 1),
            context.getString(R.string.manual_organization_preview_move_reason_single_placement),
        )
        val folderChildRow = context.getString(
            R.string.manual_organization_preview_item_row,
            descriptor(
                "Gmail",
                appKind,
                context.getString(
                    R.string.manual_organization_preview_position_folder_existing,
                    "Work",
                    1,
                ),
            ),
            context.getString(R.string.manual_organization_preview_preserved_reason_non_target),
        )
        val widgetRow = context.getString(
            R.string.manual_organization_preview_item_row,
            descriptor(
                "Gmail",
                widgetKind,
                workspacePosition(context, 1, RowBand.TOP, ColumnBand.LEFT),
            ),
            context.getString(R.string.manual_organization_preview_preserved_reason_widget),
        )
        val folderUnitRow = context.getString(
            R.string.manual_organization_preview_item_row,
            descriptor(
                "Gmail",
                folderKind,
                workspacePosition(context, 1, RowBand.TOP, ColumnBand.CENTER),
            ),
            context.getString(R.string.manual_organization_preview_preserved_reason_non_target),
        )
        val dockRow = context.getString(
            R.string.manual_organization_preview_item_row,
            descriptor(
                "Gmail",
                appKind,
                context.getString(R.string.manual_organization_preview_position_dock, 5),
            ),
            context.getString(R.string.manual_organization_preview_preserved_reason_dock),
        )
        val appPairRow = context.getString(
            R.string.manual_organization_preview_item_row,
            descriptor(
                "Gmail",
                pairKind,
                context.getString(R.string.manual_organization_preview_position_app_pair, "Gmail"),
            ),
            context.getString(R.string.manual_organization_preview_preserved_reason_app_pair),
        )

        // AC-9: every placement kind (icon / folder unit / folder child /
        // widget / app pair / dock) with the same display name renders, and
        // the source descriptors (kind word + current position) actually
        // differ — the F-01 ambiguity is fixed at the rendered surface, not
        // just in the projection model.
        composeRule.onNodeWithText(moveRow).assertIsDisplayed()
        composeRule.onNodeWithText(folderChildRow).assertIsDisplayed()
        composeRule.onNodeWithText(widgetRow).assertIsDisplayed()
        composeRule.onNodeWithText(folderUnitRow).assertIsDisplayed()
        composeRule.onNodeWithText(dockRow).assertIsDisplayed()
        composeRule.onNodeWithText(appPairRow).assertIsDisplayed()
        assertEquals(6, setOf(moveRow, folderChildRow, widgetRow, folderUnitRow, dockRow, appPairRow).size)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun sameBandAdjustmentMovesAreAnnouncedAsPositionAdjustments() {        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = listOf(
                            move("game", 1, 1),
                            move("maps", 2, 1),
                        ),
                        counts = PreviewCounts(movedCount = 2, preservedCount = 0, newFolderCount = 0, newPageCount = 0, warningCounts = emptyMap()),
                    ),
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        composeRule.onNodeWithText(
            context.getString(
                R.string.manual_organization_preview_same_band_move_row,
                context.getString(
                    R.string.manual_organization_preview_item_descriptor,
                    "game",
                    context.getString(R.string.manual_organization_preview_kind_application),
                    workspacePosition(context, 1, RowBand.TOP, ColumnBand.CENTER),
                ),
                workspaceDestination(context, 1, RowBand.TOP, ColumnBand.CENTER, 1),
                "",
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.manual_organization_preview_same_band_move_row,
                context.getString(
                    R.string.manual_organization_preview_item_descriptor,
                    "maps",
                    context.getString(R.string.manual_organization_preview_kind_application),
                    workspacePosition(context, 1, RowBand.TOP, ColumnBand.CENTER),
                ),
                workspaceDestination(context, 1, RowBand.TOP, ColumnBand.CENTER, 1),
                context.getString(R.string.manual_organization_preview_row_ordinal_note, 2, 1),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun largeChangeGroupsTruncateBehindExpandAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = (1..6).map { index -> crossBandMove("app$index") },
                        counts = PreviewCounts(movedCount = 6, preservedCount = 0, newFolderCount = 0, newPageCount = 0, warningCounts = emptyMap()),
                    ),
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        // Truncated to the first five rows; the group total stays in the action.
        composeRule.onAllNodesWithText(concreteMoveRow(context, "app6")).assertCountEquals(0)
        composeRule.onNodeWithText(concreteMoveRow(context, "app5")).assertExists()
        val expand = composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview_show_all, 6))
        expand.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.manual_organization_preview_collapsed_state),
            ),
        )

        expand.clickVisibleCenter()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(concreteMoveRow(context, "app6")).fetchSemanticsNodes().isNotEmpty()
        }
        val showFewer = context.getString(R.string.manual_organization_preview_show_fewer, 5)
        composeRule.onNodeWithText(showFewer)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.manual_organization_preview_expanded_state),
                ),
            )
            .bringAboveGestureArea()
            .clickVisibleCenter()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(concreteMoveRow(context, "app6")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun changeListTraversalReachesExpandAndReviewActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = (1..6).map { index -> crossBandMove("app$index") },
                        counts = PreviewCounts(movedCount = 6, preservedCount = 0, newFolderCount = 0, newPageCount = 0, warningCounts = emptyMap()),
                    ),
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        awaitPreview(runner, context)
        composeRule.waitUntil(5_000) {
            try {
                composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assertIsFocused()
                true
            } catch (_: AssertionError) {
                false
            }
        }

        // Issue #209: the decision pair leads the screen, so traversal reaches
        // confirm and cancel before the change-list expand action.
        pressDownUntilFocused(context.getString(R.string.manual_organization_confirm))
        pressDownUntilFocused(context.getString(R.string.manual_organization_cancel))
        pressDownUntilFocused(context.getString(R.string.manual_organization_preview_show_all, 6))
        // Activating it with a keyboard action expands the group...
        // Issue #300: same focused-window premise for the ENTER activation.
        ensureWindowFocusedForComposeHost()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(concreteMoveRow(context, "app6")).fetchSemanticsNodes().isNotEmpty()
        }
        // ...and the action keeps focus after the list reflows (spec 52 restoration).
        assertFocusedWithTraversalDiagnostics(context.getString(R.string.manual_organization_preview_show_fewer, 5))
    }

    /**
     * Issues #209/#209-CI: a list row can sit mostly below the fold on tall
     * low-row-count screens, where `performClick` (node center) lands off the
     * window and silently misses. Clicking the center of the visible part
     * keeps the toggle interaction about the widget, not the scroll position.
     */
    /**
     * Issue #209 CI follow-up: after expansion the focus-restored toggle can
     * be scrolled flush against the window bottom, inside the gesture-nav
     * area where (at screen-center x) taps land on the system pill instead of
     * the app. Scroll the list up until the row sits well above that area.
     */
    private fun SemanticsNodeInteraction.bringAboveGestureArea(): SemanticsNodeInteraction {
        val metrics = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics
        val safeBottom = metrics.heightPixels - 300f
        repeat(10) {
            val node = fetchSemanticsNode()
            val nodeCenterYInWindow = (node.boundsInWindow.top + node.boundsInWindow.bottom) / 2f
            if (nodeCenterYInWindow <= safeBottom) return this
            composeRule.onNode(hasScrollAction())
                .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, 300f) }
            composeRule.waitForIdle()
        }
        return this
    }

    private fun SemanticsNodeInteraction.clickVisibleCenter() {
        val node = fetchSemanticsNode()
        val windowBottom = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.heightPixels.toFloat()
        val nodeTop = node.boundsInWindow.top
        val visibleBottom = minOf(node.boundsInWindow.bottom, windowBottom)
        val localY = (nodeTop + visibleBottom) / 2f - nodeTop
        performTouchInput { click(Offset(centerX, localY)) }
    }
    /**
     * Issues real (window-dispatched) keyboard focus down until [text] owns it,
     * so the traversal exercises the same DPAD fallback path Switch Access and
     * hardware keyboards rely on; fails after too many steps.
     */
    private fun pressDownUntilFocused(text: String, maxPresses: Int = 12) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Issue #300: real key streams only move focus inside a focused window; observe (and
        // repair) that premise before the first press (TS-AC-05/06).
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
            // Issue #300 (review P1): re-observe the host window focus before every real key
            // press, not only before the first one (TS-AC-01).
            ensureWindowFocusedForComposeHost()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            presses++
        }
        composeRule.waitForIdle()
        assertFocusedWithTraversalDiagnostics(text)
    }

    /**
     * Issue #300: single resumed activity of the compose host (the rule's own activity in this
     * lane), used as the focus-gate target for real key injection.
     */
    private fun resumedHostActivityOrNull(): Activity? {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .singleOrNull()
        }
        return activity
    }

    /**
     * Issue #300 (review P1): refuses to run real key injection without a focus observation.
     * Host resolution must succeed exactly once; zero or multiple RESUMED activities are an
     * explicit fail-closed error, never a silent gate bypass.
     */
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

    /**
     * Issue #300: focus-traversal failures under a healthy gate keep their normal semantics but
     * carry the device/window state, so a Compose traversal regression stays distinguishable from
     * a lost-window environment at read time (TS-AC-06).
     */
    private fun assertFocusedWithTraversalDiagnostics(text: String) {
        try {
            composeRule.onNodeWithText(text).assertIsFocused()
        } catch (failure: AssertionError) {
            var hostWindowFocused = false
            resumedHostActivityOrNull()?.let { host ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    hostWindowFocused = host.window.decorView.hasWindowFocus()
                }
            }
            throw AssertionError(
                buildTraversalFailureMessage(
                    base = failure.message ?: "focus traversal failed to reach '$text'",
                    deviceState = InjectedInputEnvironment.describeDeviceState(),
                    hostWindowFocused = hostWindowFocused,
                ),
                failure,
            )
        }
    }

    @Test
    fun degradedCountOnlyPreviewAnnouncesMissingDetailsAndKeepsConfirm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview_details_unavailable)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_moved_single_placement, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.manual_organization_changes_heading)).assertCountEquals(0)
        assertEquals(0, application.applyCalls)
    }

    /**
     * Issue #209: the decision pair must be visible together on entering the
     * preview and remain visible after the change list expands — expansion
     * must never push a decision path out of the viewport.
     */
    @Test
    fun decisionPairStaysDisplayedTogetherAcrossExpansionStates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ ->
                previewed(
                    PlanPreviewDetails(
                        changes = (1..6).map { index -> crossBandMove("app$index") },
                        counts = PreviewCounts(movedCount = 6, preservedCount = 0, newFolderCount = 0, newPageCount = 0, warningCounts = emptyMap()),
                    ),
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        val confirm = context.getString(R.string.manual_organization_confirm)
        val cancel = context.getString(R.string.manual_organization_cancel)
        // Collapsed: both decisions render in the leading viewport.
        composeRule.onNodeWithText(confirm).assertIsDisplayed()
        composeRule.onNodeWithText(cancel).assertIsDisplayed()

        // Expanded: extra rows join the list; the pair must not be pushed out.
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview_show_all, 6))
            .clickVisibleCenter()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(concreteMoveRow(context, "app6")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(confirm).assertIsDisplayed()
        composeRule.onNodeWithText(cancel).assertIsDisplayed()
        assertEquals(0, application.applyCalls)
    }

    /**
     * Issue #209 AC-1a (degraded path, owner review): the count-only fallback
     * renders the missing-details announcement and the summary BEFORE the
     * decision pair (spec 195 D1 takes precedence over leading placement), and
     * the pair still shows together on the same screen.
     */
    @Test
    fun degradedFallbackAnnouncesMissingDetailsBeforeTheDecisionPair() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        val confirm = composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm))
        val cancel = composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel))
        confirm.assertIsDisplayed()
        cancel.assertIsDisplayed()

        val warning = composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_preview_details_unavailable),
        ).assertIsDisplayed().fetchSemanticsNode()
        val summary = composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_moved_single_placement, 1),
        ).fetchSemanticsNode()
        assert(warning.boundsInRoot.top < confirm.fetchSemanticsNode().boundsInRoot.top) {
            "degraded announcement must be rendered above the Apply action"
        }
        assert(summary.boundsInRoot.top < confirm.fetchSemanticsNode().boundsInRoot.top) {
            "count-only summary must be rendered above the Apply action"
        }
    }

    /**
     * Issue #209 AC-3: the decision actions report the button role to
     * assistive technology instead of plain clickable text.
     */
    @Test
    fun decisionActionsReportButtonRoleToAssistiveTechnology() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        fun buttonRole() = SemanticsMatcher("role is Button") { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button
        }
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).assert(buttonRole())
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).assert(buttonRole())
    }

    /**
     * Issue #209 AC-2: after a verified apply the safety net renders as an
     * emphasized control, and the recovery preview renders the same
     * restore/cancel decision pair.
     *
     * Issue #230 AC-1/AC-2: the confirmation also renders the correlated
     * apply history line, and a mismatched preview pointId falls back to the
     * target-only surface without losing the decision pair.
     */
    @Test
    fun recoverySurfacesRenderDecisionButtons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            recoveryPreview = RecoveryPreviewResult.Restorable(
                pointId = RecoveryPointId(POINT_ID),
                summary = RecoveryPreviewSummary(),
                confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
            )
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)
        runner.confirm()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Applied }

        fun buttonRole() = SemanticsMatcher("role is Button") { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button
        }
        val restore = context.getString(R.string.manual_organization_recovery)
        composeRule.onNodeWithText(restore).assertIsDisplayed().assert(buttonRole())

        composeRule.onNodeWithText(restore).performClick()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }

        val recoveryConfirm = context.getString(R.string.manual_organization_recovery_confirm)
        // Issue #209 review: the screen returns its lazy list to the head on
        // every state transition, so the heading and the decision pair must be
        // visible right after the transition — no corrective scrolling.
        composeRule.onNodeWithText(recoveryConfirm).assertIsDisplayed().assert(buttonRole())
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).assertIsDisplayed()
            .assert(buttonRole())

        // Issue #230: the correlated apply history renders above the decision
        // pair (the fixture plan moves exactly one placement). The singular
        // plural form must resolve too.
        val history = context.resources.getQuantityString(
            R.plurals.manual_organization_recovery_history_moved,
            1,
            1,
        ).let { context.getString(R.string.manual_organization_recovery_history_prefix, it) }
        composeRule.onNodeWithText(history).assertIsDisplayed()

        // Issue #230 AC-2: a preview whose pointId does not match the
        // retained apply renders the target-only surface; confirm/cancel stay
        // available.
        application.recoveryPreview = RecoveryPreviewResult.Restorable(
            pointId = RecoveryPointId(OTHER_POINT_ID),
            summary = RecoveryPreviewSummary(),
            confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(2)),
        )
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).performClick()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.Applied }
        composeRule.onNodeWithText(restore).performClick()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }

        composeRule.onNodeWithText(recoveryConfirm).assertIsDisplayed()
        composeRule.onAllNodesWithText(history).assertCountEquals(0)
    }

    @Test
    fun noChangesStateOffersNoConfirmationAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { emptyPlannedResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        composeRule.waitUntil(5_000) { runner.state == ManualOrganizationRun.State.NoChanges }

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_no_changes)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.manual_organization_confirm)).assertCountEquals(0)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun changeRowsArePlainReadableNodesWithoutLiveRegion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ -> previewed(concreteChangeListDetails()) }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        awaitPreview(runner, context)

        val preservedRow = context.getString(
            R.string.manual_organization_preview_item_row,
            context.getString(
                R.string.manual_organization_preview_item_descriptor,
                "clock",
                context.getString(R.string.manual_organization_preview_kind_application),
                context.getString(R.string.manual_organization_preview_position_dock, 3),
            ),
            context.getString(R.string.manual_organization_preview_preserved_reason_locked),
        )
        composeRule.onNodeWithText(preservedRow).assertIsDisplayed().assert(
            SemanticsMatcher("change rows are not live regions") { node ->
                node.config.getOrNull(SemanticsProperties.LiveRegion) == null
            },
        )
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_preview)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun changeListRemainsReadableAtTwoHundredPercentFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            inspectPlanOverride = { _, _ -> previewed(concreteChangeListDetails()) }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        runner.start()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme {
                    ManualOrganizationPreferences(run = runner)
                }
            }
        }
        awaitPreview(runner, context)

        composeRule.onNodeWithText(gameMoveRow(context)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_cancel)).assertIsDisplayed()
    }

    @Test
    fun japaneseResourcesResolveEveryConcretePreviewString() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val japanese = context.createConfigurationContext(
            Configuration().apply { setLocale(Locale.JAPAN) },
        )
        val addedPreviewStrings = listOf(
            R.string.manual_organization_stale_outcome,
            R.string.manual_organization_stale_proposal_discarded,
            R.string.manual_organization_stale_proposal_not_reviewed,
            R.string.manual_organization_recapture_summary,
            R.string.manual_organization_preview_details_unavailable,
            // Issue #230: the recovery confirmation's apply-history prefix.
            // The three count segments are plurals and are asserted below.
            R.string.manual_organization_recovery_history_prefix,
            R.string.manual_organization_changes_heading,
            R.string.manual_organization_group_moved,
            R.string.manual_organization_group_new_folders,
            R.string.manual_organization_group_new_pages,
            R.string.manual_organization_group_preserved,
            R.string.manual_organization_group_warnings,
            R.string.manual_organization_preview_show_all,
            R.string.manual_organization_preview_show_fewer,
            R.string.manual_organization_preview_expanded_state,
            R.string.manual_organization_preview_collapsed_state,
            R.string.manual_organization_preview_move_row,
            R.string.manual_organization_preview_same_band_move_row,
            R.string.manual_organization_preview_workspace_destination,
            R.string.manual_organization_preview_row_ordinal_note,
            R.string.manual_organization_preview_item_row,
            R.string.manual_organization_preview_item_descriptor,
            R.string.manual_organization_preview_item_descriptor_without_kind,
            R.string.manual_organization_preview_position_unidentified,
            R.string.manual_organization_preview_position_with_supplement,
            R.string.manual_organization_preview_supplement_cell,
            R.string.manual_organization_preview_supplement_parent_with_stage,
            R.string.manual_organization_preview_supplement_stage_top,
            R.string.manual_organization_preview_supplement_stage_bottom,
            R.string.manual_organization_preview_move_reason_single_placement,
            R.string.manual_organization_preview_move_reason_folder_member,
            R.string.manual_organization_preview_move_reason_folder_unit,
            R.string.manual_organization_preview_move_reason_unspecified,
            R.string.manual_organization_preview_preserved_reason_locked,
            R.string.manual_organization_preview_preserved_reason_reserved_region,
            R.string.manual_organization_preview_preserved_reason_unavailable,
            R.string.manual_organization_preview_preserved_reason_dock,
            R.string.manual_organization_preview_preserved_reason_widget,
            R.string.manual_organization_preview_preserved_reason_app_pair,
            R.string.manual_organization_preview_preserved_reason_legacy_shortcut,
            R.string.manual_organization_preview_preserved_reason_non_target,
            R.string.manual_organization_preview_preserved_reason_structural,
            R.string.manual_organization_preview_preserved_reason_already_canonical,
            R.string.manual_organization_preview_warning_legacy_shortcut_item,
            R.string.manual_organization_preview_warning_fallback_category_item,
            R.string.manual_organization_preview_warning_unavailable_item,
            R.string.manual_organization_preview_page,
            R.string.manual_organization_preview_new_page_position,
            R.string.manual_organization_preview_position_workspace,
            R.string.manual_organization_preview_region_top_left,
            R.string.manual_organization_preview_region_top_center,
            R.string.manual_organization_preview_region_top_right,
            R.string.manual_organization_preview_region_middle_left,
            R.string.manual_organization_preview_region_middle_center,
            R.string.manual_organization_preview_region_middle_right,
            R.string.manual_organization_preview_region_bottom_left,
            R.string.manual_organization_preview_region_bottom_center,
            R.string.manual_organization_preview_region_bottom_right,
            R.string.manual_organization_preview_position_dock,
            R.string.manual_organization_preview_position_folder_existing,
            R.string.manual_organization_preview_position_folder_planned,
            R.string.manual_organization_preview_position_app_pair,
            R.string.manual_organization_preview_kind_application,
            R.string.manual_organization_preview_kind_deep_shortcut,
            R.string.manual_organization_preview_kind_shortcut_legacy,
            R.string.manual_organization_preview_kind_folder,
            R.string.manual_organization_preview_kind_app_widget,
            R.string.manual_organization_preview_kind_custom_app_widget,
            R.string.manual_organization_preview_kind_app_pair,
            R.string.manual_organization_preview_kind_unknown,
            R.string.manual_organization_preview_new_folder_row,
            R.string.manual_organization_preview_new_page_row,
        )

        addedPreviewStrings.forEach { id ->
            assertNotEquals(
                "string resource $id falls back to English under a Japanese locale",
                context.getString(id),
                japanese.getString(id),
            )
        }

        // Placeholders survive the translation (issue #208: the move row
        // carries source descriptor, destination, reason).
        val japaneseMoveRow = japanese.getString(
            R.string.manual_organization_preview_move_row,
            "A",
            "B",
            "C",
        )
        assert(japaneseMoveRow.contains("A") && japaneseMoveRow.contains("C"))

        // Issue #230: the recovery history plurals resolve in Japanese (the
        // ja locale carries only the `other` quantity).
        listOf(
            R.plurals.manual_organization_recovery_history_moved,
            R.plurals.manual_organization_recovery_history_new_folders,
            R.plurals.manual_organization_recovery_history_new_pages,
        ).forEach { id ->
            val resolved = japanese.resources.getQuantityString(id, 2, 2)
            assertNotEquals(
                "plurals resource $id falls back to English under a Japanese locale",
                context.resources.getQuantityString(id, 2, 2),
                resolved,
            )
        }

        // Issue #231: the applied-result plurals resolve in Japanese too, and
        // the English quantities stay distinct for one vs other.
        listOf(
            R.plurals.manual_organization_applied_moved_count,
            R.plurals.manual_organization_applied_preserved_count,
            R.plurals.manual_organization_applied_new_folders_count,
            R.plurals.manual_organization_applied_new_pages_count,
        ).forEach { id ->
            val resolved = japanese.resources.getQuantityString(id, 2, 2)
            assertNotEquals(
                "plurals resource $id falls back to English under a Japanese locale",
                context.resources.getQuantityString(id, 2, 2),
                resolved,
            )
        }
        listOf(
            R.plurals.manual_organization_applied_moved_count,
            R.plurals.manual_organization_applied_preserved_count,
            R.plurals.manual_organization_applied_new_folders_count,
            R.plurals.manual_organization_applied_new_pages_count,
        ).forEach { id ->
            assertNotEquals(
                "plurals resource $id ignores the one/other quantity distinction",
                context.resources.getQuantityString(id, 1, 1),
                context.resources.getQuantityString(id, 2, 2),
            )
        }
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
        composeRule.waitUntil(5_000) { staleRunner.state is ManualOrganizationRun.State.Stale }
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
    fun staleApplyAttemptExplainsOutcomeAndNextStep() {
        // Issue #210: a stale apply attempt must report that nothing was
        // applied, that the reviewed proposal was discarded, and what the
        // recapture action does — from the screen wording alone.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            applyResult = ApplyResult.Rejected(RunId(RUN_ID), PreWriteRejection.STALE_REVISION)
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()
        awaitPreview(runner, context)
        runner.confirm()

        assertEquals(
            ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED),
            runner.state,
        )
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_stale_outcome)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_stale_proposal_discarded)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_recapture)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_recapture_summary)).assertIsDisplayed()
    }

    @Test
    fun staleDetectionBeforeReviewDoesNotClaimReviewedProposalWasDiscarded() {
        // Issue #210: when staleness is detected before the proposal was ever
        // shown, the detail must say the proposal was discarded before review,
        // not claim a reviewed proposal was discarded.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.Stale }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult() },
        )

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()

        assertEquals(
            ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.DETECTED_BEFORE_REVIEW),
            runner.state,
        )
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_stale_outcome)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_stale_proposal_not_reviewed)).assertIsDisplayed()
    }

    @Test
    fun verifiedSuccessSurfaceReportsAppliedOutcomeInPastTense() {
        // Issue #231 AC-1/AC-2/AC-6: after a verified apply the result surface
        // reports the outcome in the completed tense with the applied plan's
        // counts, and never re-renders the proposal's future-tense count lines.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        runner.start()
        awaitPreview(runner, context)
        runner.confirm()
        composeRule.waitUntil(5_000) {
            (runner.state as? ManualOrganizationRun.State.Applied)?.result is ApplyResult.Applied
        }

        val applied = runner.state as ManualOrganizationRun.State.Applied
        // Completed-tense count lines derived from the applied plan's summary.
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.manual_organization_applied_moved_count,
                applied.summary.movedCount,
                applied.summary.movedCount,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.manual_organization_applied_preserved_count,
                applied.summary.preservedCount,
                applied.summary.preservedCount,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.manual_organization_applied_new_folders_count,
                applied.summary.newFolderCount,
                applied.summary.newFolderCount,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(
                R.plurals.manual_organization_applied_new_pages_count,
                applied.summary.newPageCount,
                applied.summary.newPageCount,
            ),
        ).assertIsDisplayed()

        // The proposal's future-tense count lines must not appear as a result.
        // Pair each resource with the applied summary's real count so the
        // absence check covers the exact rendering a regression would show
        // (review: a fixed value would only exclude the moved=1 variant).
        listOf(
            R.string.manual_organization_moved_count to applied.summary.movedCount,
            R.string.manual_organization_preserved_count to applied.summary.preservedCount,
            R.string.manual_organization_new_folders_count to applied.summary.newFolderCount,
            R.string.manual_organization_new_pages_count to applied.summary.newPageCount,
        ).forEach { (id, count) ->
            composeRule.onAllNodesWithText(context.getString(id, count)).assertCountEquals(0)
        }

        // Unchanged rows keep their parity: heading, reason breakdown, and the
        // persisting warning line (fixture plans one FALLBACK_CATEGORY warning).
        composeRule.onNodeWithText(context.getString(R.string.manual_organization_apply_success)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_moved_single_placement, applied.summary.movedCount),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.manual_organization_warning_fallback_category, 1),
        ).assertIsDisplayed()
    }

    @Test
    fun verifiedSuccessKeepsAppliedWordingAfterRecoveryPreviewCancel() {
        // Issue #231 AC-3: returning to the applied surface (recovery preview
        // cancel, or revisiting the screen after checking Home) re-renders the
        // same completed-tense counts — the semantics never revert to proposal
        // wording.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication()
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        runner.start()
        awaitPreview(runner, context)
        runner.confirm()
        composeRule.waitUntil(5_000) {
            (runner.state as? ManualOrganizationRun.State.Applied)?.result is ApplyResult.Applied
        }
        val appliedMovedLine = context.resources.getQuantityString(
            R.plurals.manual_organization_applied_moved_count,
            1,
            1,
        )
        composeRule.onNodeWithText(appliedMovedLine).assertIsDisplayed()

        runner.beginRecoveryPreview()
        composeRule.waitUntil(5_000) { runner.state is ManualOrganizationRun.State.RecoveryPreview }
        runner.cancelRecoveryPreview()
        composeRule.waitUntil(5_000) {
            (runner.state as? ManualOrganizationRun.State.Applied)?.result is ApplyResult.Applied
        }
        composeRule.onNodeWithText(appliedMovedLine).assertIsDisplayed()
    }

    @Test
    fun nonSuccessApplyResultKeepsProposalSummaryWording() {
        // Issue #231 AC-5: non-success results carried by State.Applied must not
        // claim completed changes; they keep the existing outcome heading and
        // proposal summary rendering.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = FakeApplication().apply {
            applyResult = ApplyResult.RolledBack(
                RunId(RUN_ID),
                app.lawnchair.organizer.application.public.ApplyFailure.WRITE_FAILED,
            )
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })

        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }
        runner.start()
        awaitPreview(runner, context)
        runner.confirm()
        composeRule.waitUntil(5_000) {
            (runner.state as? ManualOrganizationRun.State.Applied)?.result is ApplyResult.RolledBack
        }
        val applied = runner.state as ManualOrganizationRun.State.Applied

        composeRule.onNodeWithText(context.getString(R.string.manual_organization_apply_rolled_back)).assertIsDisplayed()
        // Proposal count lines keep rendering for non-success outcomes, paired
        // with the plan summary's real counts (review: symmetry with the
        // success-surface absence check).
        listOf(
            R.string.manual_organization_moved_count to applied.summary.movedCount,
            R.string.manual_organization_preserved_count to applied.summary.preservedCount,
            R.string.manual_organization_new_folders_count to applied.summary.newFolderCount,
            R.string.manual_organization_new_pages_count to applied.summary.newPageCount,
        ).forEach { (id, count) ->
            composeRule.onNodeWithText(context.getString(id, count)).assertIsDisplayed()
        }
        // Completed-tense lines must not claim completed changes here.
        listOf(
            R.plurals.manual_organization_applied_moved_count to applied.summary.movedCount,
            R.plurals.manual_organization_applied_preserved_count to applied.summary.preservedCount,
            R.plurals.manual_organization_applied_new_folders_count to applied.summary.newFolderCount,
            R.plurals.manual_organization_applied_new_pages_count to applied.summary.newPageCount,
        ).forEach { (id, count) ->
            composeRule.onAllNodesWithText(
                context.resources.getQuantityString(id, count, count),
            ).assertCountEquals(0)
        }
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

        /** Issue #195: override to publish a concrete change-list preview; default stays count-only. */
        var inspectPlanOverride: ((OrganizationInput, PlanningResult) -> PlanPreviewResult)? = null

        /** Issue #209: overridable recovery preview for the decision-button rendering test. */
        var recoveryPreview: RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            RecoveryPointId(POINT_ID),
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )

        override fun newRunId() = RunId(RUN_ID)

        /**
         * Issue #228: default keeps the legacy flow — detection is unavailable
         * so start() proceeds straight to the plain full compose. Tests of the
         * selection surface override this.
         */
        var detection: app.lawnchair.organizer.integration.CandidateDetectionResult =
            app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
                app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
            )

        override fun detectMissingAppCandidates(): app.lawnchair.organizer.integration.CandidateDetectionResult = detection

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): OrganizationInputComposition = ready()

        override fun composeFullOrganization(): OrganizationInputComposition {
            notReadyComposition?.let { return it }
            return ready()
        }

        /**
         * Issue #194: this fixture keeps the legacy count-only flow, so the
         * preview seam reports busy and confirm materializes as before.
         */
        override fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult = inspectPlanOverride?.invoke(input, result)
            ?: PlanPreviewResult.WriterBusy

        private fun ready(): OrganizationInputComposition = OrganizationInputComposition.Ready(
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

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = recoveryPreview

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult = RecoveryResult.NotRestorable(
            pointId,
            app.lawnchair.organizer.application.public.RecoveryRejection.MISSING,
        )

        /** Issue #271: overridable durable status projection for the Idle/Cancelled render tests. */
        var durableStatus: app.lawnchair.organizer.application.public.OrganizerDurableStatus =
            app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

        /** Issue #271 review: blocks the durable read to observe the loading state. */
        var readOverride: (() -> app.lawnchair.organizer.application.public.OrganizerDurableStatus)? = null

        override fun readDurableOrganizerStatus(): app.lawnchair.organizer.application.public.OrganizerDurableStatus = readOverride?.invoke() ?: durableStatus

        /** Issue #271 review: overridable readiness for the re-read race test. */
        var readiness = kotlinx.coroutines.flow.MutableStateFlow(
            app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY,
        )

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> = readiness
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
        organizationStrategy = app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
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

    /** Issue #195: empty diff keeps the No changes surface without a confirm action. */
    private fun emptyPlannedResult() = PlanningResult(
        revision = RevisionId(REVISION),
        ruleVersion = RuleVersion("v1"),
        taxonomyVersion = TaxonomyVersion("v1"),
        organizationStrategy = app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        outcome = Planned(
            placements = emptyList(),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = emptyList(),
        ),
    )

    /** Issue #195: publishes a `Previewed` plan whose details the confirmation UI renders. */
    private fun previewed(details: PlanPreviewDetails): PlanPreviewResult = PlanPreviewResult.Previewed(
        PlanPreview(
            plan = ValidatedLayoutPlan(
                sourceRevision = RevisionId(REVISION),
                sourceState = emptyLayoutState(),
                intendedState = emptyLayoutState(),
                actions = emptyList(),
                newPages = emptyList(),
                newFolders = emptyList(),
                ruleVersion = RuleVersion("v1"),
                taxonomyVersion = TaxonomyVersion("v1"),
            ),
            details = details,
        ),
    )

    /**
     * Representative concrete preview: a cross-region move whose row changed
     * inside the same row band, a move into the Dock, a preserved item, a new
     * folder with two members, and a new page.
     */
    private fun concreteChangeListDetails() = PlanPreviewDetails(
        changes = listOf(
            move("game", sourceRowOrdinal = 2, destinationRowOrdinal = 1, destination = workspace(1, RowBand.TOP, ColumnBand.LEFT, 1)),
            move("maps", sourceRowOrdinal = 1, destinationRowOrdinal = 1, destination = PreviewPosition.DockRank(1)),
            preserved("clock"),
            NewFolderChange(
                ordinal = NewFolderOrdinal(0),
                name = PreviewLabel.Named("Communication"),
                placement = workspace(2, RowBand.CENTER, ColumnBand.LEFT, 3),
                memberLabels = listOf(PreviewLabel.Named("game"), PreviewLabel.Named("maps")),
            ),
            NewPageChange(ordinal = NewPageOrdinal(0), displayPosition = 3),
        ),
        counts = PreviewCounts(movedCount = 2, preservedCount = 1, newFolderCount = 1, newPageCount = 1, warningCounts = emptyMap()),
    )

    /** Same-band adjustment within top-center: row ordinals decide the note. */
    private fun move(label: String, sourceRowOrdinal: Int, destinationRowOrdinal: Int) = MoveChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        identity = PreviewPlacementIdentity.Workspace(1, false, 1, sourceRowOrdinal - 1),
        kind = CanonicalItemKind.Application,
        source = workspace(1, RowBand.TOP, ColumnBand.CENTER, sourceRowOrdinal),
        destination = workspace(1, RowBand.TOP, ColumnBand.CENTER, destinationRowOrdinal),
        rationale = PlacementCode.SINGLE_PLACEMENT,
    )

    private fun move(label: String, sourceRowOrdinal: Int, destinationRowOrdinal: Int, destination: PreviewPosition) = MoveChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        identity = PreviewPlacementIdentity.Workspace(1, false, 1, sourceRowOrdinal - 1),
        kind = CanonicalItemKind.Application,
        source = workspace(1, RowBand.TOP, ColumnBand.CENTER, sourceRowOrdinal),
        destination = destination,
        rationale = PlacementCode.SINGLE_PLACEMENT,
    )

    private fun preserved(label: String, reason: PreserveReason = PreserveReason.LOCKED) = PreservedChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        identity = PreviewPlacementIdentity.Dock(2),
        kind = CanonicalItemKind.Application,
        current = PreviewPosition.DockRank(2),
        reason = reason,
    )

    private fun workspace(
        page: Int,
        rowBand: RowBand,
        columnBand: ColumnBand,
        rowOrdinal: Int,
        columnOrdinal: Int = 1,
    ) = PreviewPosition.Workspace(
        pageDisplayOrdinal = page,
        isNewPage = false,
        rowBand = rowBand,
        columnBand = columnBand,
        rowOrdinal = rowOrdinal,
        columnOrdinal = columnOrdinal,
    )

    /** Issue #234: the anchor-specific destination text of a move row. */
    private fun workspaceDestination(
        context: Context,
        page: Int,
        rowBand: RowBand,
        columnBand: ColumnBand,
        rowOrdinal: Int,
        columnOrdinal: Int = 1,
    ): String = context.getString(
        R.string.manual_organization_preview_workspace_destination,
        context.getString(regionString(rowBand, columnBand)),
        context.getString(R.string.manual_organization_preview_page, page),
        rowOrdinal,
        columnOrdinal,
    )

    /** Expected render of a move whose source and destination are plain workspaces. */
    private fun concreteMoveRow(context: Context, label: String): String = context.getString(
        R.string.manual_organization_preview_move_row,
        context.getString(
            R.string.manual_organization_preview_item_descriptor,
            label,
            context.getString(R.string.manual_organization_preview_kind_application),
            workspacePosition(context, 1, RowBand.TOP, ColumnBand.LEFT),
        ),
        workspaceDestination(context, 1, RowBand.TOP, ColumnBand.RIGHT, 1),
        context.getString(R.string.manual_organization_preview_move_reason_single_placement),
    )

    /** The "game" fixture move: top-center → top-left with a row-ordinal note. */
    private fun gameMoveRow(context: Context): String = context.getString(
        R.string.manual_organization_preview_move_row,
        context.getString(
            R.string.manual_organization_preview_item_descriptor,
            "game",
            context.getString(R.string.manual_organization_preview_kind_application),
            workspacePosition(context, 1, RowBand.TOP, ColumnBand.CENTER),
        ),
        workspaceDestination(context, 1, RowBand.TOP, ColumnBand.LEFT, 1) +
            context.getString(R.string.manual_organization_preview_row_ordinal_note, 2, 1),
        context.getString(R.string.manual_organization_preview_move_reason_single_placement),
    )

    private fun crossBandMove(label: String) = MoveChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        identity = PreviewPlacementIdentity.Workspace(1, false, 0, 0),
        kind = CanonicalItemKind.Application,
        source = workspace(1, RowBand.TOP, ColumnBand.LEFT, 1),
        destination = workspace(1, RowBand.TOP, ColumnBand.RIGHT, 1),
        rationale = PlacementCode.SINGLE_PLACEMENT,
    )

    private fun workspacePosition(context: Context, page: Int, rowBand: RowBand, columnBand: ColumnBand): String = context.getString(
        R.string.manual_organization_preview_position_workspace,
        context.getString(R.string.manual_organization_preview_page, page),
        context.getString(regionString(rowBand, columnBand)),
    )

    private fun regionString(rowBand: RowBand, columnBand: ColumnBand): Int = when (rowBand) {
        RowBand.TOP -> when (columnBand) {
            ColumnBand.LEFT -> R.string.manual_organization_preview_region_top_left
            ColumnBand.CENTER -> R.string.manual_organization_preview_region_top_center
            ColumnBand.RIGHT -> R.string.manual_organization_preview_region_top_right
        }

        RowBand.CENTER -> when (columnBand) {
            ColumnBand.LEFT -> R.string.manual_organization_preview_region_middle_left
            ColumnBand.CENTER -> R.string.manual_organization_preview_region_middle_center
            ColumnBand.RIGHT -> R.string.manual_organization_preview_region_middle_right
        }

        RowBand.BOTTOM -> when (columnBand) {
            ColumnBand.LEFT -> R.string.manual_organization_preview_region_bottom_left
            ColumnBand.CENTER -> R.string.manual_organization_preview_region_bottom_center
            ColumnBand.RIGHT -> R.string.manual_organization_preview_region_bottom_right
        }
    }

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
            FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
            DockPolicy.PRESERVE,
            OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
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
