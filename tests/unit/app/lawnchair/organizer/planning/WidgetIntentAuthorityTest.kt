package app.lawnchair.organizer.planning

import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.IntentPlannerAdapter
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 (PR review 5, P1): widget `pageAffinity` / `regionAffinity` stay
 * strictly inside the #235 page-local widget policies — an intent can never
 * move a widget off its captured page nor re-anchor a top-anchored stream.
 */
class WidgetIntentAuthorityTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val p0 = ProfileId("p0")

    private fun app(id: String, x: Int, y: Int) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun widget(id: String, x: Int, y: Int, page: String, w: Int = 2, h: Int = 2, widgetId: Int = 1) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPWIDGET,
        target = TargetKey.WidgetKey(ComponentKey("$id/.Widget"), AppWidgetId(widgetId), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(w, h)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun input(strategy: StrategyId, items: List<CapturedItem>): OrganizationInput = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(6, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items,
        ),
        rules = RuleSemantics(
            version = RuleVersion("v2"),
            folderPolicy = FolderPolicy(5, NewFolderProfileScope.SAME_PROFILE_ONLY),
            dockPolicy = DockPolicy.PRESERVE,
            overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            organizationStrategy = strategy,
        ),
        taxonomy = TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")),
        catalog = ActiveCategoryCatalog(TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")), emptyList()),
        signals = ClassificationSignals(emptyList()),
        // #235: widgets are never user-selected organization targets — the
        // production composer marks every widget `ExistingRole.Preserved`.
        targets = TargetSet(
            items.map { ExistingTargetMembership(it.id, if (it.kind is ItemKind.APPWIDGET) ExistingRole.Preserved else ExistingRole.Movable) },
            emptyList(),
        ),
        runMode = RunMode.FullOrganization,
    )

    private fun withWidgetIntent(
        input: OrganizationInput,
        widgetId: String,
        pageAffinity: Int? = null,
        regionAffinity: ExportRegionKind? = null,
    ): OrganizationInput {
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = input.snapshot, targets = input.targets, nowEpochMs = 1L),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val refs = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(
                    ref = refs.getValue(widgetId),
                    pageAffinity = pageAffinity,
                    regionAffinity = regionAffinity,
                ),
            ),
            unresolvedRefs = built.export.items.map { it.ref }.filter { it != refs.getValue(widgetId) },
        )
        val validation = IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = 2L,
            currentStructuralDigest = built.session.sourceContextDigest,
        )
        val validated = when (validation) {
            is IntentValidation.Validated -> validation.validated
            is IntentValidation.Failure -> throw IllegalStateException("validation failed: ${validation.failure}")
        }
        return input.copy(intentPreferences = IntentPlannerAdapter.project(validated))
    }

    @Test
    fun stablePageTidyV2WidgetPageAffinityCannotLeaveTheCapturedPage() {
        val items = listOf(app("a", 0, 0), widget("w", 0, 0, page = "p1"))
        val input = input(LayoutStrategyRegistry.STABLE_PAGE_TIDY_V2, items)
        val with = withWidgetIntent(input, widgetId = "w", pageAffinity = 0)

        val planned = planner.plan(with).outcome as Planned
        val widgetPlacement = planned.placements.first { it.item == ItemId("w") }
        val target = widgetPlacement.target as PlacementTarget.WorkspaceTarget
        // Page-local policy: the widget stays on its captured page.
        assertEquals(PageRef(PageId("p1")), target.page)
    }

    @Test
    fun bottomFirstV2WidgetPageAffinityCannotLeaveTheCapturedPage() {
        val items = listOf(app("a", 0, 0), widget("w", 0, 0, page = "p1"))
        val input = input(LayoutStrategyRegistry.BOTTOM_FIRST_V2, items)
        val with = withWidgetIntent(input, widgetId = "w", pageAffinity = 0)

        val planned = planner.plan(with).outcome as Planned
        val widgetPlacement = planned.placements.first { it.item == ItemId("w") }
        val target = widgetPlacement.target as PlacementTarget.WorkspaceTarget
        assertEquals(PageRef(PageId("p1")), target.page)
    }

    @Test
    fun topAnchoredWidgetStreamIsNotReAnchoredByRegionAffinity() {
        // BOTTOM_FIRST_V2 is top-anchored by strategy declaration; a BOTTOM
        // regionAffinity must not re-anchor the stream into the bottom band.
        val items = listOf(
            app("a", 2, 0),
            widget("w", 0, 0, page = "p0", w = 2, h = 2, widgetId = 7),
        )
        val input = input(LayoutStrategyRegistry.BOTTOM_FIRST_V2, items)
        val with = withWidgetIntent(input, widgetId = "w", regionAffinity = ExportRegionKind.BOTTOM)

        val outcome = planner.plan(with).outcome
        check(outcome is Planned) { "expected Planned but got $outcome" }
        val planned = outcome
        val widgetPlacement = planned.placements.first { it.item == ItemId("w") }
        val target = widgetPlacement.target as PlacementTarget.WorkspaceTarget
        // Top-anchored: the widget stays in the top rows of its captured page.
        assertTrue(
            "top-anchored stream must not be re-anchored by the intent",
            target.cell.y + target.span.height - 1 < 6 / 3,
        )
    }

    @Test
    fun pageLocalBandWidgetRegionAffinityStaysInsideTheCapturedBandIntersection() {
        // STABLE_PAGE_TIDY_V2: the band hint may only narrow the captured band
        // (intersection); the widget never leaves its captured band or page.
        val widgetItem = widget("w", 0, 3, page = "p0", w = 2, h = 2, widgetId = 9)
        val items = listOf(app("a", 2, 3), widgetItem)
        val input = input(LayoutStrategyRegistry.STABLE_PAGE_TIDY_V2, items)
        val with = withWidgetIntent(input, widgetId = "w", regionAffinity = ExportRegionKind.TOP)

        val planned = planner.plan(with).outcome as Planned
        val widgetPlacement = planned.placements.first { it.item == ItemId("w") }
        val target = widgetPlacement.target as PlacementTarget.WorkspaceTarget
        // Captured page and captured band (rows 3-4 of a 6-row grid) are kept.
        assertEquals(PageRef(PageId("p0")), target.page)
        assertTrue(target.cell.y >= 3)
    }
}
