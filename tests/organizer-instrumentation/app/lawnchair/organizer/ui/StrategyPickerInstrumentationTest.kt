package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
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
import app.lawnchair.ui.preferences.destinations.OrganizerStrategyPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private companion object {
        const val STRATEGY_PICKER_TAG = "manual-organization-strategy-picker"
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun clearSelectionStore() {
        val directory = File(context().noBackupFilesDir, "organizer_strategy_selection")
        directory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun theRunSurfaceShowsNoStrategyPicker() {
        // Issue #368 AC-1 (negative observation): the manual-run surface
        // hosts no strategy section, radio rows, or frozen reason — the
        // picker's only home is the T-05 materials surface. The idle entry
        // is the representative state; the Selecting/Preview surfaces are
        // exercised picker-free by the run-surface suite itself.
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { ManualOrganizationPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithTag(STRATEGY_PICKER_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(
            context().getString(R.string.manual_organization_strategy_section),
        ).assertDoesNotExist()
    }

    @Test
    fun pickerListsTheOfferedIntentChoicesWithLocalizedNames() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithText(context().getString(R.string.manual_organization_strategy_section))
            .assertIsDisplayed()
        // Spec #453 (FR-023): the picker offers the three intent-level choices
        // in display order — canonical (default), tidy V2, bottom region. The
        // row ORDER is asserted from the semantics tree, not just presence.
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_tidy_v2_name,
            R.string.organization_strategy_bottom_region_name,
        )) {
            composeRule.onNode(hasScrollAction())
                .performScrollToNode(hasText(context().getString(name)))
            composeRule.onNodeWithText(context().getString(name)).assertIsDisplayed()
        }
        assertPickerRowLabels(
            context().getString(R.string.organization_strategy_canonical_name),
            context().getString(R.string.organization_strategy_tidy_v2_name),
            context().getString(R.string.organization_strategy_bottom_region_name),
        )
        // Hidden runtime-supported strategies are no longer composed as rows.
        for (name in listOf(
            R.string.organization_strategy_tidy_name,
            R.string.organization_strategy_bottom_first_name,
            R.string.organization_strategy_bottom_first_v2_name,
            R.string.organization_strategy_global_name,
            R.string.organization_strategy_global_v2_name,
            R.string.organization_strategy_category_contiguous_name,
        )) {
            composeRule.onNodeWithText(context().getString(name)).assertDoesNotExist()
        }
    }

    @Test
    fun firstRunShowsTheBundleDefaultAsTheEffectiveSelection() {
        // Spec 182: a valid absent selection means the planner uses the bundle
        // default, so the canonical row must be shown as selected even though
        // nothing is persisted yet.
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val canonicalName = context().getString(R.string.organization_strategy_canonical_name)
        composeRule.onNodeWithText(canonicalName).assertIsSelected()
        composeRule.onNodeWithText(
            context().getString(R.string.organization_strategy_tidy_v2_name),
        ).assertIsNotSelected()
    }

    @Test
    fun strategyRowsExposeRadioSemanticsInsideASelectableGroup() {
        // Issue #218 a11y contract: one mutually-exclusive radio group whose
        // rows announce name + selected state + description as single nodes.
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val sectionNode = composeRule.onNodeWithText(
            context().getString(R.string.manual_organization_strategy_section),
        )
        sectionNode.assertExists()
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_tidy_v2_name,
            R.string.organization_strategy_bottom_region_name,
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
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_canonical_name))
            .assertIsNotSelected()
        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_tidy_v2_name))
            .assertIsNotSelected()
    }

    @Test
    fun strategyPickerIsWrappedInASelectableGroup() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val groups = composeRule
            .onAllNodes(hasTestTag(STRATEGY_PICKER_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, groups.size)
        assertTrue(groups.first().config.contains(SemanticsProperties.SelectableGroup))
    }

    @Test
    fun strategyRowsKeepSelectionSemanticsOnTheParentRow() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val pickerClickTargets = composeRule.onAllNodes(
            inStrategyPicker(hasClickAction()),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        // Spec #453: the offered set is three rows.
        assertEquals(3, pickerClickTargets.size)
        assertTrue(pickerClickTargets.all { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })

        val pickerSelectableTargets = composeRule.onAllNodes(
            inStrategyPicker(isSelectable()),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals(pickerClickTargets.size, pickerSelectableTargets.size)
        assertTrue(pickerSelectableTargets.all { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })

        // A visual-only RadioButton(onClick = null) must not add a focus target.
        // Clickable/selectable rows may be focusable, but every focusable node
        // in this picker must still be one of the parent radio rows.
        val pickerFocusableTargets = composeRule.onAllNodes(
            inStrategyPicker(isFocusable()),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(pickerFocusableTargets.size <= pickerClickTargets.size)
        assertTrue(pickerFocusableTargets.all { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
    }

    @Test
    fun selectingAStrategyMovesTheSingleSelectedParentRow() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val canonical = context().getString(R.string.organization_strategy_canonical_name)
        val tidy = context().getString(R.string.organization_strategy_tidy_v2_name)
        composeRule.onNodeWithText(canonical).assertIsSelected()
        // The offered row may sit below the fold on small windows — scroll it
        // into view so the injected tap lands inside the window.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(tidy))
        composeRule.onNodeWithText(tidy).assertIsNotSelected().performClick()
        composeRule.waitUntil(5_000) {
            val read = LayoutStrategySelectionModule.store(context()).read()
            read is LayoutStrategySelectionReadResult.Ready &&
                read.snapshot.selection == StrategyId("STABLE_PAGE_TIDY_V2")
        }

        composeRule.onNodeWithText(canonical).assertIsNotSelected()
        composeRule.onNodeWithText(tidy).assertIsSelected()
        assertEquals(
            1,
            composeRule.onAllNodes(inStrategyPicker(isSelected())).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun selectingTheEffectiveStrategyIsAStoreAndVisualNoOp() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val before = LayoutStrategySelectionModule.store(context()).read()
            as LayoutStrategySelectionReadResult.Ready
        val canonical = context().getString(R.string.organization_strategy_canonical_name)
        composeRule.onNodeWithText(canonical).assertIsSelected().performClick()

        composeRule.waitUntil(5_000) {
            val after = LayoutStrategySelectionModule.store(context()).read()
            after is LayoutStrategySelectionReadResult.Ready && after.snapshot == before.snapshot
        }
        composeRule.onNodeWithText(canonical).assertIsSelected()
        assertEquals(
            1,
            composeRule.onAllNodes(inStrategyPicker(isSelected())).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun hiddenRuntimeSupportedSelectionStaysSelectedAsAnAppendedRowUntilChanged() {
        // Spec #453: a stored runtime-supported strategy hidden from the
        // offered set stays visible as one appended selected row until the
        // user changes it; the run keeps planning with it (composition
        // contract unchanged).
        clearSelectionStore()
        val committed = LayoutStrategySelectionModule.store(context())
            .select(StrategyId("STABLE_PAGE_TIDY_V1"))
        assertTrue(committed is LayoutStrategySelectionWriteResult.Committed)
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        val canonical = context().getString(R.string.organization_strategy_canonical_name)
        val hiddenName = context().getString(R.string.organization_strategy_tidy_name)
        // Three offered rows plus the appended selected row — the appended row
        // is the fourth (after the offered three), asserted from row order.
        assertEquals(
            4,
            composeRule.onAllNodes(inStrategyPicker(hasClickAction()), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertPickerRowLabels(
            context().getString(R.string.organization_strategy_canonical_name),
            context().getString(R.string.organization_strategy_tidy_v2_name),
            context().getString(R.string.organization_strategy_bottom_region_name),
            hiddenName,
        )
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(hiddenName))
        composeRule.onNodeWithText(hiddenName).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText(canonical).assertIsNotSelected()
        assertEquals(
            1,
            composeRule.onAllNodes(inStrategyPicker(isSelected())).fetchSemanticsNodes().size,
        )

        // Changing to an offered row commits and the appended row disappears.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(canonical))
        composeRule.onNodeWithText(canonical).assertIsNotSelected().performClick()
        composeRule.waitUntil(5_000) {
            val read = LayoutStrategySelectionModule.store(context()).read()
            read is LayoutStrategySelectionReadResult.Ready &&
                read.snapshot.selection == StrategyId("CANONICAL_PAGE_COMPACT_V1")
        }
        composeRule.onNodeWithText(hiddenName).assertDoesNotExist()
        assertEquals(
            3,
            composeRule.onAllNodes(inStrategyPicker(hasClickAction()), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            1,
            composeRule.onAllNodes(inStrategyPicker(isSelected())).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun unknownStoredSelectionAddsNoRowAndShowsNoSelection() {
        // Spec #453 fail-closed boundary: a stored selection outside the
        // runtime-supported set (unknown/removed ID, readable file with a
        // valid digest) adds no appended row and shows nothing selected —
        // the composer fails closed the same way.
        clearSelectionStore()
        val unknown = "FUTURE_STRATEGY_V9"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(unknown.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = File(context().noBackupFilesDir, "organizer_strategy_selection/selection-v1")
        file.parentFile?.mkdirs()
        file.writeText("schema=1\ngeneration=1\ndigest=$digest\nselection=$unknown\n")
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        // The offered three are composed, nothing is selected, and the
        // unknown value is not surfaced as a row (no "custom strategy" row).
        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_canonical_name))
            .assertExists()
            composeRule.onNodeWithText(context().getString(R.string.organization_strategy_bottom_region_name))
            .assertExists()
        assertEquals(
            0,
            composeRule.onAllNodes(inStrategyPicker(isSelected())).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_unknown_name))
            .assertDoesNotExist()
    }

    @Test
    fun pickerRemainsReadableAtTwoHundredPercentFontScale() {
        clearSelectionStore()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
            }
        }

        val context = context()
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_tidy_v2_name,
            R.string.organization_strategy_bottom_region_name,
        )) {
            val label = context.getString(name)
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun pickerWithHiddenSelectionRemainsReadableAtTwoHundredPercentFontScale() {
        // Spec #453: the 3+1-row composition (offered three plus the appended
        // hidden-selected row with its longer label) must stay reachable and
        // unclipped at 200% font scale.
        clearSelectionStore()
        val committed = LayoutStrategySelectionModule.store(context())
            .select(StrategyId("STABLE_PAGE_TIDY_V1"))
        assertTrue(committed is LayoutStrategySelectionWriteResult.Committed)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
            }
        }

        val context = context()
        val windowWidth = context.resources.displayMetrics.widthPixels
        for (name in listOf(
            R.string.organization_strategy_canonical_name,
            R.string.organization_strategy_tidy_v2_name,
            R.string.organization_strategy_bottom_region_name,
            R.string.organization_strategy_tidy_name,
        )) {
            val label = context.getString(name)
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertIsDisplayed()
            // Reachable by scrolling and rendered within the window width
            // (no clipping) at 200% font scale.
            composeRule.onAllNodes(hasText(label)).fetchSemanticsNodes().forEach { semNode ->
                assertTrue(
                    "row must not exceed the window width at 200% font scale",
                    semNode.boundsInRoot.right <= windowWidth,
                )
            }
        }
    }

    @Test
    fun canonicalStrategyDescriptionOmitsHistoricalWording() {
        val description = context().getString(R.string.organization_strategy_canonical_description)

        assertFalse(description.contains("The original organizer behavior."))
        assertFalse(description.contains("従来の整理動作です。"))
    }

    @Test
    fun selectingAStrategyPublishesThroughTheValidatedWriteCommand() {
        clearSelectionStore()
        composeRule.setContent {
            LawnchairTheme { OrganizerStrategyPreferences(run = previewlessRunner()) }
        }

        // The offered row may sit below the fold on small windows — scroll it
        // into view first so the injected tap lands inside the window.
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText(context().getString(R.string.organization_strategy_tidy_v2_name)),
        )
        composeRule.onNodeWithText(context().getString(R.string.organization_strategy_tidy_v2_name))
            .performClick()
        composeRule.waitUntil(5_000) {
            val read = LayoutStrategySelectionModule.store(context()).read()
            read is LayoutStrategySelectionReadResult.Ready &&
                read.snapshot.selection == StrategyId("STABLE_PAGE_TIDY_V2")
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

    private fun inStrategyPicker(matcher: SemanticsMatcher): SemanticsMatcher =
        hasAnyAncestor(hasTestTag(STRATEGY_PICKER_TAG)) and matcher

    /**
     * Spec #453: asserts the picker's selectable rows (merged tree — each row
     * announces name + state + description as one node) carry exactly these
     * strategy names, in this row order.
     */
    private fun assertPickerRowLabels(vararg orderedNames: String) {
        val labels = composeRule.onAllNodes(inStrategyPicker(isSelectable()))
            .fetchSemanticsNodes()
            .map { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                    .orEmpty()
            }
        assertEquals(orderedNames.size, labels.size)
        orderedNames.forEachIndexed { index, name ->
            assertTrue(
                "picker row $index must show \"$name\" but was \"${labels[index]}\"",
                labels[index].contains(name),
            )
        }
    }

    private class NotReadyManualOrganizationApplication : ManualOrganizationApplication {
        // Issue #449: the edit-surface read seam is out of scope here; fail-closed null.
        override fun inspectCapture(): app.lawnchair.organizer.application.protocol.CapturedSnapshot? = null

        // Issue #450: the undo receipt/recovery seams are out of scope for
        // this test; the fake fails fast if ever reached.
        override fun applyWithUndoReceipt(
            plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
            runId: app.lawnchair.organizer.application.public.RunId,
        ): Pair<app.lawnchair.organizer.application.public.ApplyResult, app.lawnchair.organizer.planning.RevisionId?> =
            error("not reached in this test")

        override fun recover(request: app.lawnchair.organizer.application.public.RecoveryRequest): app.lawnchair.organizer.application.public.RecoveryResult =
            error("not reached in this test")

        override val diagnostics = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot(): List<RunEvent> = emptyList()
        }

        override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

        // Issue #228: detection unavailable keeps the legacy full flow.
        override fun detectMissingAppCandidates() = app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
            app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): OrganizationInputComposition = composeFullOrganization()

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

        override fun readDurableOrganizerStatus() = app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED
        override fun readRestorableRecoveryEntry(): app.lawnchair.organizer.application.public.RestorableRecoveryEntry? = null

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)
    }
}
