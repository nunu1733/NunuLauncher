package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 182 STABLE_PAGE_TIDY_V1 behavioral tests, driven strictly through the
 * public `OrganizationPlanner.plan` seam (no internal mocks).
 */
class StablePageTidyStrategyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val strategy = StrategyId("STABLE_PAGE_TIDY_V1")
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

    private fun device(columns: Int = 4, rows: Int = 4) = DeviceCapabilities(
        columns,
        rows,
        4,
        4,
        4,
        Orientation.PORTRAIT,
    )

    private fun app(
        id: String,
        x: Int,
        y: Int,
        page: String = "p0",
        spanW: Int = 1,
        spanH: Int = 1,
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun folder(
        id: String,
        x: Int,
        y: Int,
        page: String = "p0",
        children: List<String>,
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.FOLDER,
        target = TargetKey.FolderKey(FolderId(id)),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
        folderId = FolderId(id),
        members = children.map { ItemId(it) },
    )

    private fun widget(id: String, x: Int, y: Int, spanW: Int, spanH: Int) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPWIDGET,
        target = TargetKey.WidgetKey(ComponentKey("com.example.$id/.Widget"), AppWidgetId(1), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun input(items: List<CapturedItem>, pages: List<Page> = listOf(Page(PageId("p0"), PageOrder(0)))) = OrganizationInput(
        snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages, items, emptyList()),
        rules = rules(),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun plannedTarget(result: PlanningResult, id: String): PlacementTarget {
        val planned = result.outcome as Planned
        return planned.placements.single { it.item == ItemId(id) }.target
    }

    private fun wsCell(target: PlacementTarget): GridCell = (target as PlacementTarget.WorkspaceTarget).cell

    @Test
    fun pageLocalTidyClosesHolesInCapturedVisualOrder() {
        // Spec representative fixture: apps at (0,0), (2,0), (3,0) with no fixed
        // occupant at (1,0) become (0,0), (1,0), (2,0).
        val result = planner.plan(input(listOf(app("a", 0, 0), app("b", 2, 0), app("c", 3, 0))))

        val planned = result.outcome as Planned
        assertTrue(planned.newPages.isEmpty() && planned.newFolders.isEmpty())
        assertEquals(GridCell(0, 0), wsCell(plannedTarget(result, "a")))
        assertEquals(GridCell(1, 0), wsCell(plannedTarget(result, "b")))
        assertEquals(GridCell(2, 0), wsCell(plannedTarget(result, "c")))
        assertTrue(
            planned.placements.filter { it.disposition is Disposition.Moved }.all {
                it.disposition == Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)
            },
        )
    }

    @Test
    fun unitsNeverLeaveTheirCapturedPage() {
        val pages = listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1)))
        val items = listOf(
            app("p0a", 0, 0),
            app("p0b", 2, 0),
            app("p1a", 0, 0, page = "p1"),
            app("p1b", 2, 0, page = "p1"),
            app("p1c", 3, 0, page = "p1"),
        )
        val result = planner.plan(input(items, pages = pages))

        val planned = result.outcome as Planned
        assertTrue(planned.newPages.isEmpty())
        val pageOf: (PlacementTarget) -> String = {
            (((it as PlacementTarget.WorkspaceTarget).page) as PageRef).pageId.value
        }
        assertEquals("p0", pageOf(plannedTarget(result, "p0a")))
        assertEquals("p0", pageOf(plannedTarget(result, "p0b")))
        assertEquals("p1", pageOf(plannedTarget(result, "p1a")))
        assertEquals("p1", pageOf(plannedTarget(result, "p1b")))
        assertEquals("p1", pageOf(plannedTarget(result, "p1c")))
    }

    @Test
    fun strategyPreservedItemsAreTruthfulAndRemainOccupancyConstraints() {
        // A non-1x1 movable app, an existing folder, and a widget on one page.
        // The widget keeps WIDGET; the folder and the 2x1 app keep
        // STRATEGY_PRESERVED — never ALREADY_CANONICAL, never NON_TARGET.
        val result = planner.plan(
            input(
                listOf(
                    app("big", 0, 0, spanW = 2, spanH = 1),
                    folder("fold", 0, 2, children = listOf("child")),
                    widget("w1", 2, 0, 2, 1),
                    app("free", 3, 3),
                    CapturedItem(
                        id = ItemId("child"),
                        profile = p0,
                        kind = ItemKind.APPLICATION,
                        target = TargetKey.AppKey(ComponentKey("com.example.child"), p0),
                        placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold")), 0),
                        locked = false,
                        availability = Availability.AVAILABLE,
                    ),
                ),
            ),
        )

        val planned = result.outcome as Planned
        val dispositionOf: (String) -> Disposition = { id ->
            planned.placements.single { it.item == ItemId(id) }.disposition
        }
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), dispositionOf("big"))
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), dispositionOf("fold"))
        assertEquals(Disposition.Preserved(PreserveReason.WIDGET), dispositionOf("w1"))
        assertEquals(Disposition.Preserved(PreserveReason.STRUCTURAL), dispositionOf("child"))
        // The 1x1 app tidies into the earliest free cell (row 1, below the
        // fully occupied top row).
        val moved = planned.placements.single { it.item == ItemId("free") }
        assertEquals(Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), moved.disposition)
        assertEquals(GridCell(0, 1), wsCell(moved.target))
        // No new folders even though category grouping could apply.
        assertTrue(planned.newFolders.isEmpty())
        // Strategy-preserved items must not be double-reported as ALREADY_CANONICAL.
        assertTrue(
            planned.placements.none {
                it.disposition == Disposition.Preserved(PreserveReason.ALREADY_CANONICAL) &&
                    (it.item == ItemId("big") || it.item == ItemId("fold"))
            },
        )
    }

    @Test
    fun alreadyTidyPageYieldsEmptyDiff() {
        val result = planner.plan(
            input(
                listOf(
                    app("a", 0, 0),
                    app("b", 1, 0),
                    app("c", 2, 0),
                    app("d", 3, 0),
                ),
            ),
        )

        val planned = result.outcome as Planned
        assertTrue(planned.placements.all { it.disposition == Disposition.Preserved(PreserveReason.ALREADY_CANONICAL) })
        assertTrue(planned.newPages.isEmpty() && planned.newFolders.isEmpty())
    }

    @Test
    fun replanningTheMaterializedTidyResultYieldsAnEmptyDiff() {
        val items = listOf(
            app("w1", 2, 0, spanW = 2, spanH = 2),
            app("a", 0, 0),
            app("b", 0, 3),
            app("c", 1, 3),
            app("d", 3, 2),
        )
        val first = planner.plan(input(items))
        val planned = first.outcome as Planned

        // Materialize: apply every workspace target onto the captured snapshot.
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
        val second = planner.plan(input(applied))

        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
        assertTrue(secondPlanned.newPages.isEmpty() && secondPlanned.newFolders.isEmpty())
        assertEquals(planned.placements.size, secondPlanned.placements.size)
    }

    @Test
    fun fullGridPageKeepsEveryEligibleUnitAtItsCapturedCell() {
        // A page with no free cells: lift-then-place must return every unit to
        // its own captured cell (its own cell is the only free one at its turn).
        val items = (0 until 4).flatMap { y -> (0 until 4).map { x -> app("a$y$x", x, y) } }
        val result = planner.plan(input(items))

        val planned = result.outcome as Planned
        assertTrue(planned.placements.all { it.disposition == Disposition.Preserved(PreserveReason.ALREADY_CANONICAL) })
        assertNull(planned.newPages.singleOrNull())
        assertTrue(planned.newPages.isEmpty())
    }

    @Test
    fun deepShortcutsAreEligibleTidyUnitsToo() {
        val shortcut = CapturedItem(
            id = ItemId("s1"),
            profile = p0,
            kind = ItemKind.DEEP_SHORTCUT,
            target = TargetKey.ShortcutKey(PackageName("com.example.shortcut"), ShortcutId("sc1"), p0),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(3, 3), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val result = planner.plan(input(listOf(app("a", 0, 0), app("b", 1, 0), app("c", 2, 0), shortcut)))

        val planned = result.outcome as Planned
        val moved = planned.placements.single { it.item == ItemId("s1") }
        assertEquals(Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), moved.disposition)
        assertEquals(GridCell(3, 0), wsCell(moved.target))
    }

    @Test
    fun planEchoesTheTidyStrategyIdentity() {
        val result = planner.plan(input(listOf(app("a", 1, 1))))

        assertEquals(strategy, result.organizationStrategy)
    }
}
