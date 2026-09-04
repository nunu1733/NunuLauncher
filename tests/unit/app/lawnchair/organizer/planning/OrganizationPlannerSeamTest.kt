package app.lawnchair.organizer.planning

import org.junit.Assert.assertSame
import org.junit.Test

class OrganizationPlannerSeamTest {

    @Test
    fun plannedOutcomeCrossesSeamUnchanged() {
        val expected = PlanningResult(
            revision = RevisionId("rev-1"),
            ruleVersion = RuleVersion("rv-1"),
            taxonomyVersion = TaxonomyVersion("tv-1"),
            organizationStrategy = StrategyId("s-1"),
            outcome = Planned(
                placements = emptyList(),
                newPages = emptyList(),
                newFolders = emptyList(),
                categories = emptyList(),
                warnings = emptyList(),
            ),
        )
        val adapter: OrganizationPlanner = OrganizationPlanner { expected }

        assertSame(expected, adapter.plan(minimalInput()))
    }

    @Test
    fun invalidOutcomeCrossesSeamUnchanged() {
        val expected = PlanningResult(
            revision = RevisionId("rev-1"),
            ruleVersion = RuleVersion("rv-1"),
            taxonomyVersion = TaxonomyVersion("tv-1"),
            organizationStrategy = StrategyId("s-1"),
            outcome = Rejected.Invalid(
                reasons = listOf(
                    RejectionReason(
                        RejectionCode.DUPLICATE_ITEM_ID,
                        listOf(DiagnosticParam.ItemParam(ItemId("dup"))),
                    ),
                ),
                warnings = emptyList(),
            ),
        )
        val adapter: OrganizationPlanner = OrganizationPlanner { expected }

        assertSame(expected, adapter.plan(minimalInput()))
    }

    @Test
    fun impossibleOutcomeCrossesSeamUnchanged() {
        val expected = PlanningResult(
            revision = RevisionId("rev-1"),
            ruleVersion = RuleVersion("rv-1"),
            taxonomyVersion = TaxonomyVersion("tv-1"),
            organizationStrategy = StrategyId("s-1"),
            outcome = Rejected.Impossible(
                unplaced = listOf(
                    UnplacedItem(
                        ItemId("big"),
                        GridSpan(10, 10),
                        UnplacedReason.EXCEEDS_GRID_DIMENSIONS,
                    ),
                ),
                warnings = emptyList(),
            ),
        )
        val adapter: OrganizationPlanner = OrganizationPlanner { expected }

        assertSame(expected, adapter.plan(minimalInput()))
    }

    private fun minimalInput(): OrganizationInput = OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("rev-1"),
            device = DeviceCapabilities(
                columns = 4,
                rows = 4,
                hotseatSlots = 4,
                folderMaxColumns = 4,
                folderMaxRows = 4,
                orientation = Orientation.PORTRAIT,
            ),
            pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
            items = emptyList(),
        ),
        rules = RuleSemantics(
            version = RuleVersion("rv-1"),
            folderPolicy = FolderPolicy(
                minGroupSize = 2,
                newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY,
            ),
            dockPolicy = DockPolicy.PRESERVE,
            overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        ),
        taxonomy = TaxonomyContract(
            version = TaxonomyVersion("tv-1"),
            allowedCategories = listOf(CategoryId("OTHER")),
            fallbackCategory = CategoryId("OTHER"),
        ),
        signals = ClassificationSignals(entries = emptyList()),
        targets = TargetSet(existing = emptyList(), additions = emptyList()),
        runMode = RunMode.FullOrganization,
    )
}
