package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 182 CATEGORY_CONTIGUOUS_V1 behavioral tests, driven strictly through
 * the public `OrganizationPlanner.plan` seam (no internal mocks).
 */
class CategoryContiguousStrategyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val strategy = StrategyId("CATEGORY_CONTIGUOUS_V1")
    private val p0 = ProfileId("p0")

    private fun rules() = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategy,
    )

    private fun taxonomy() = TaxonomyContract(
        TaxonomyVersion("tv1"),
        listOf(CategoryId("OTHER"), CategoryId("GAMES"), CategoryId("TOOLS")),
        CategoryId("OTHER"),
    )

    private fun device(columns: Int = 4, rows: Int = 3) = DeviceCapabilities(
        columns,
        rows,
        4,
        4,
        4,
        Orientation.PORTRAIT,
    )

    private fun app(id: String, x: Int, y: Int, page: String = "p0", spanW: Int = 1, spanH: Int = 1) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun folder(id: String, x: Int, y: Int, children: List<String>) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.FOLDER,
        target = TargetKey.FolderKey(FolderId(id)),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
        folderId = FolderId(id),
        members = children.map { ItemId(it) },
    )

    private fun signal(id: String, category: String) = ClassificationSignal(ItemId(id), SignalSource.S1, CategoryId(category))

    private fun input(
        items: List<CapturedItem>,
        signals: List<ClassificationSignal> = emptyList(),
        pages: List<Page> = listOf(Page(PageId("p0"), PageOrder(0))),
    ) = OrganizationInput(
        snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages, items, emptyList()),
        rules = rules(),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(signals),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun wsCell(result: PlanningResult, id: String): GridCell {
        val planned = result.outcome as Planned
        val target = planned.placements.single { it.item == ItemId(id) }.target
        return (target as PlacementTarget.WorkspaceTarget).cell
    }

    private fun wsPage(result: PlanningResult, id: String): String {
        val planned = result.outcome as Planned
        val target = planned.placements.single { it.item == ItemId(id) }.target
        return ((target as PlacementTarget.WorkspaceTarget).page as PageRef).pageId.value
    }

    @Test
    fun categoriesOccupyContiguousBlocksInOrderWithFallbackLast() {
        // Six 1x1 apps on 4×3: GAMES g1,g2 — TOOLS t1 — fallback o1,o2,o3.
        // Ordering (profile, category with fallback last, target key, ItemId)
        // fills the earliest row-major cells as GAMES | TOOLS | OTHER blocks.
        val items = listOf(
            app("g1", 0, 0),
            app("g2", 2, 0),
            app("t1", 0, 1),
            app("o1", 2, 1),
            app("o2", 0, 2),
            app("o3", 2, 2),
        )
        val result = planner.plan(
            input(
                items,
                signals = listOf(signal("g1", "GAMES"), signal("g2", "GAMES"), signal("t1", "TOOLS")),
            ),
        )

        val planned = result.outcome as Planned
        assertEquals(GridCell(0, 0), wsCell(result, "g1"))
        assertEquals(GridCell(1, 0), wsCell(result, "g2"))
        assertEquals(GridCell(2, 0), wsCell(result, "t1"))
        // Fallback category comes last.
        assertEquals(GridCell(3, 0), wsCell(result, "o1"))
        assertEquals(GridCell(0, 1), wsCell(result, "o2"))
        assertEquals(GridCell(1, 1), wsCell(result, "o3"))
        assertTrue(planned.newFolders.isEmpty() && planned.newPages.isEmpty())
    }

    @Test
    fun tidyFormsNoFoldersEvenWhenCanonicalGroupingWouldApply() {
        // Contrast control: the same input under CANONICAL_PAGE_COMPACT_V1
        // forms one GAMES folder — proving this test cannot pass by the
        // fallback-category accident.
        val items = listOf(app("g1", 0, 0), app("g2", 2, 0), app("o1", 3, 0))
        val signals = listOf(signal("g1", "GAMES"), signal("g2", "GAMES"))
        val result = planner.plan(input(items, signals))

        val planned = result.outcome as Planned
        assertTrue(planned.newFolders.isEmpty())
        assertTrue(planned.placements.none { it.disposition == Disposition.Moved(PlacementCode.FOLDER_MEMBER) })
        for (id in listOf("g1", "g2", "o1")) {
            assertTrue(planned.placements.single { it.item == ItemId(id) }.target is PlacementTarget.WorkspaceTarget)
        }

        val canonicalResult = planner.plan(
            input(items, signals).copy(
                rules = input(items, signals).rules.copy(
                    organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
                ),
            ),
        )
        assertEquals(1, (canonicalResult.outcome as Planned).newFolders.size)
    }

    @Test
    fun strategyPreservedFixedItemsRemainOccupancyConstraints() {
        // An existing folder and a 2x1 movable app are STRATEGY_PRESERVED and
        // their cells block the category-contiguous placement.
        val items = listOf(
            folder("fold", 0, 2, children = listOf("child")),
            app("big", 2, 0, spanW = 2, spanH = 1),
            app("a", 0, 0),
            app("b", 1, 0),
            app("c", 0, 1),
            CapturedItem(
                id = ItemId("child"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.child"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold")), 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val result = planner.plan(input(items))

        val planned = result.outcome as Planned
        assertEquals(
            Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
            planned.placements.single { it.item == ItemId("fold") }.disposition,
        )
        assertEquals(
            Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
            planned.placements.single { it.item == ItemId("big") }.disposition,
        )
        assertEquals(Disposition.Preserved(PreserveReason.STRUCTURAL), planned.placements.single { it.item == ItemId("child") }.disposition)
        // Free cells (0,0),(1,0),(0,1),(1,1),(2,1),(3,1): the three apps take
        // the first three row-major cells.
        assertEquals(GridCell(0, 0), wsCell(result, "a"))
        assertEquals(GridCell(1, 0), wsCell(result, "b"))
        assertEquals(GridCell(0, 1), wsCell(result, "c"))
        assertTrue(planned.newFolders.isEmpty())
    }

    @Test
    fun categoriesAreNeverPulledAcrossPageBoundaries() {
        // GAMES apps on two different pages stay on their captured pages; the
        // page-2 GAMES app is not pulled to sit next to the page-1 pair.
        val pages = listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1)))
        val items = listOf(
            app("g1", 0, 0),
            app("g2", 1, 0),
            app("g3", 0, 0, page = "p1"),
        )
        val result = planner.plan(
            input(
                items,
                signals = listOf(signal("g1", "GAMES"), signal("g2", "GAMES"), signal("g3", "GAMES")),
                pages = pages,
            ),
        )

        val planned = result.outcome as Planned
        assertEquals("p0", wsPage(result, "g1"))
        assertEquals("p0", wsPage(result, "g2"))
        assertEquals("p1", wsPage(result, "g3"))
        assertEquals(GridCell(1, 0), wsCell(result, "g2"))
        assertTrue(planned.newPages.isEmpty())
    }

    @Test
    fun replanningTheMaterializedResultYieldsAnEmptyDiff() {
        val items = listOf(
            app("g1", 2, 0),
            app("g2", 0, 1),
            app("t1", 3, 2),
            app("o1", 1, 1),
        )
        val signals = listOf(signal("g1", "GAMES"), signal("g2", "GAMES"), signal("t1", "TOOLS"))
        val first = planner.plan(input(items, signals))
        val planned = first.outcome as Planned

        val targets = planned.placements.associate { it.item to it.target }
        val applied = items.map { item ->
            val target = targets.getValue(item.id)
            if (target is PlacementTarget.WorkspaceTarget && target.page is PageRef) {
                item.copy(
                    placement = CapturedPlacement.Workspace(
                        target.page as PageRef,
                        target.cell,
                        target.span,
                    ),
                )
            } else {
                item
            }
        }
        val second = planner.plan(input(applied, signals))

        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
        assertTrue(secondPlanned.newPages.isEmpty() && secondPlanned.newFolders.isEmpty())
    }

    @Test
    fun planEchoesTheCategoryContiguousStrategyIdentity() {
        val result = planner.plan(input(listOf(app("a", 1, 1))))

        assertEquals(strategy, result.organizationStrategy)
    }
}
