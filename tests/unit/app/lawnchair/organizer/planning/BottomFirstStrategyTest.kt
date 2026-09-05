package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 182 BOTTOM_FIRST_V1 behavioral tests, driven strictly through the
 * public `OrganizationPlanner.plan` seam (no internal mocks).
 */
class BottomFirstStrategyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val strategy = StrategyId("BOTTOM_FIRST_V1")
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

    private fun device(
        columns: Int,
        rows: Int,
        orientation: Orientation = Orientation.PORTRAIT,
    ) = DeviceCapabilities(columns, rows, 4, 4, 4, orientation)

    private fun app(id: String, x: Int, y: Int, page: String = "p0", locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun folder(id: String, x: Int, y: Int, spanW: Int, spanH: Int, children: List<String>) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.FOLDER,
        target = TargetKey.FolderKey(FolderId(id)),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = false,
        availability = Availability.AVAILABLE,
        folderId = FolderId(id),
        members = children.map { ItemId(it) },
    )

    private fun input(items: List<CapturedItem>, device: DeviceCapabilities) = OrganizationInput(
        snapshot = LayoutSnapshot(RevisionId("rev"), device, listOf(Page(PageId("p0"), PageOrder(0))), items, emptyList()),
        rules = rules(),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    // Distinct captured cells within the smallest fixture grid (4×3); every
    // strategy re-allocates the movable set, so captured cells only need to be
    // valid input geometry.
    private fun tenApps(): List<CapturedItem> = listOf(
        0 to 0, 1 to 0, 2 to 0, 3 to 0,
        0 to 1, 1 to 1, 2 to 1, 3 to 1,
        0 to 2, 1 to 2,
    ).mapIndexed { index, (x, y) -> app("a$index", x, y) }

    private fun wsCell(result: PlanningResult, id: String): GridCell {
        val planned = result.outcome as Planned
        val target = planned.placements.single { it.item == ItemId(id) }.target
        return (target as PlacementTarget.WorkspaceTarget).cell
    }

    @Test
    fun portraitFillRunsFromTheBottomRowsFirst() {
        // Spec scenario: 4×5 portrait, ten movable 1×1 apps → bottom rows
        // first: (0,4)..(3,4), then (0,3)..(3,3), then (0,2)..(1,2).
        val result = planner.plan(input(tenApps(), device(4, 5)))

        val expected = (0 until 10).map { i ->
            when {
                i < 4 -> GridCell(i, 4)
                i < 8 -> GridCell(i - 4, 3)
                else -> GridCell(i - 8, 2)
            }
        }
        expected.forEachIndexed { index, cell -> assertEquals(cell, wsCell(result, "a$index")) }
    }

    @Test
    fun landscapeFillRunsFromTheBottomRowFirst() {
        val result = planner.plan(input(tenApps(), device(4, 3, Orientation.LANDSCAPE)))

        val expected = (0 until 10).map { i ->
            when {
                i < 4 -> GridCell(i, 2)
                i < 8 -> GridCell(i - 4, 1)
                else -> GridCell(i - 8, 0)
            }
        }
        expected.forEachIndexed { index, cell -> assertEquals(cell, wsCell(result, "a$index")) }
    }

    @Test
    fun tabletFillRunsFromTheBottomRowFirst() {
        val result = planner.plan(input(tenApps(), device(6, 5)))

        val expected = (0 until 10).map { i ->
            if (i < 6) GridCell(i, 4) else GridCell(i - 6, 3)
        }
        expected.forEachIndexed { index, cell -> assertEquals(cell, wsCell(result, "a$index")) }
    }

    @Test
    fun twoPanelOrientationFillsFromTheBottomRowFirst() {
        val result = planner.plan(input(tenApps(), device(6, 4, Orientation.TWO_PANEL_LANDSCAPE)))

        val expected = (0 until 10).map { i ->
            if (i < 6) GridCell(i, 3) else GridCell(i - 6, 2)
        }
        expected.forEachIndexed { index, cell -> assertEquals(cell, wsCell(result, "a$index")) }
    }

    @Test
    fun lockedCellsAreSkippedByTheBottomUpScan() {
        // L-17 variant: locked app at (3,4) on 4×5 is preserved; the ten apps
        // fill the remaining cells bottom-first without ever touching it.
        val items = tenApps() + app("locked", 3, 4, locked = true)
        val result = planner.plan(input(items, device(4, 5)))

        val planned = result.outcome as Planned
        assertEquals(
            Disposition.Preserved(PreserveReason.LOCKED),
            planned.placements.single { it.item == ItemId("locked") }.disposition,
        )
        val expected = (0 until 10).map { i ->
            when {
                i < 3 -> GridCell(i, 4)
                i < 7 -> GridCell(i - 3, 3)
                else -> GridCell(i - 7, 2)
            }
        }
        expected.forEachIndexed { index, cell -> assertEquals(cell, wsCell(result, "a$index")) }
    }

    @Test
    fun multiSpanUnitsOccupyTheirBottomCellsAndBlockTheScan() {
        // A 2×2 existing folder on 6×5: it moves as one unit (canonical
        // unit policy) and its full region blocks the bottom-up scan.
        val apps = listOf(
            0 to 0,
            1 to 0,
            2 to 0,
            3 to 0,
            4 to 0,
            5 to 0,
            0 to 1,
            1 to 1,
        ).mapIndexed { index, (x, y) -> app("a$index", x, y) }
        val items = apps + folder("fold", 2, 3, 2, 2, listOf("f1")) +
            CapturedItem(
                id = ItemId("f1"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.f1"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold")), 0),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        val result = planner.plan(input(items, device(6, 5)))

        val planned = result.outcome as Planned
        // The folder (moved as one 2×2 unit) lands at the bottom-left 2×2 area.
        assertEquals(GridCell(0, 3), wsCell(result, "fold"))
        // Eight apps fill around it, bottom-first: row 4's free cells
        // (2,4),(3,4),(4,4),(5,4), then row 3's free right cells
        // (2,3),(3,3),(4,3),(5,3).
        val expected = listOf(
            GridCell(2, 4),
            GridCell(3, 4),
            GridCell(4, 4),
            GridCell(5, 4),
            GridCell(2, 3),
            GridCell(3, 3),
            GridCell(4, 3),
            GridCell(5, 3),
        )
        expected.forEachIndexed { index, cell ->
            assertEquals("app a$index", cell, wsCell(result, "a$index"))
        }
        assertEquals(0, planned.newFolders.size)
    }

    @Test
    fun replanningTheMaterializedBottomFirstResultYieldsAnEmptyDiff() {
        val items = tenApps()
        val first = planner.plan(input(items, device(4, 5)))
        val planned = first.outcome as Planned

        val targets = planned.placements.associate { it.item to it.target }
        val applied = items.map { item ->
            val target = targets.getValue(item.id) as PlacementTarget.WorkspaceTarget
            item.copy(
                placement = CapturedPlacement.Workspace(
                    target.page as PageRef,
                    target.cell,
                    target.span,
                ),
            )
        }
        val second = planner.plan(input(applied, device(4, 5)))

        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
        assertTrue(secondPlanned.newPages.isEmpty() && secondPlanned.newFolders.isEmpty())
    }

    @Test
    fun planEchoesTheBottomFirstStrategyIdentity() {
        val result = planner.plan(input(listOf(app("a", 1, 1)), device(4, 5)))

        assertEquals(strategy, result.organizationStrategy)
    }
}
