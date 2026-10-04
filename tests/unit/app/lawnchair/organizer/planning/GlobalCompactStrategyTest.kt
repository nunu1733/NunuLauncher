package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 182 GLOBAL_COMPACT_V1 behavioral tests, driven strictly through the
 * public `OrganizationPlanner.plan` seam (no internal mocks).
 */
class GlobalCompactStrategyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val strategy = StrategyId("GLOBAL_COMPACT_V1")
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

    private fun device(columns: Int, rows: Int) = DeviceCapabilities(
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
        locked: Boolean = false,
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun twoPageInput(items: List<CapturedItem>, columns: Int, rows: Int) = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            device(columns, rows),
            listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items,
            emptyList(),
        ),
        rules = rules(),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun targetOf(result: PlanningResult, id: String): PlacementTarget {
        val planned = result.outcome as Planned
        return planned.placements.single { it.item == ItemId(id) }.target
    }

    private fun wsPageId(target: PlacementTarget): String = ((target as PlacementTarget.WorkspaceTarget).page as PageRef).pageId.value

    @Test
    fun crossPageCompactionFillsEarlierPagesInGlobalVisualOrder() {
        // 2×2 pages: page 1 has one fixed cell at (0,0) → free (1,0),(0,1),(1,1).
        // Page 2 holds three movable 1×1 singletons. GLOBAL_COMPACT_V1 fills
        // page 1's free cells in global captured visual order before creating
        // any new page.
        val items = listOf(
            app("fixed", 0, 0, page = "p0", locked = true),
            app("p2a", 0, 0, page = "p1"),
            app("p2b", 1, 0, page = "p1"),
            app("p2c", 0, 1, page = "p1"),
        )
        val result = planner.plan(twoPageInput(items, 2, 2))

        val planned = result.outcome as Planned
        assertEquals(Disposition.Preserved(PreserveReason.LOCKED), planned.placements.single { it.item == ItemId("fixed") }.disposition)
        assertEquals("p0", wsPageId(targetOf(result, "p2a")))
        assertEquals(GridCell(1, 0), wsCell(targetOf(result, "p2a")))
        assertEquals("p0", wsPageId(targetOf(result, "p2b")))
        assertEquals(GridCell(0, 1), wsCell(targetOf(result, "p2b")))
        assertEquals("p0", wsPageId(targetOf(result, "p2c")))
        assertEquals(GridCell(1, 1), wsCell(targetOf(result, "p2c")))
        assertTrue(planned.newPages.isEmpty())
        // Cross-page moves happened (3 units left page 2).
        assertTrue(planned.placements.count { it.disposition is Disposition.Moved } == 3)
    }

    @Test
    fun nonSquareMovableUnitsAreStrategyPreservedAndNeverCompact() {
        // Spec counterexample fixture (3×2 pages): page 1 has a fixed 1×1 at
        // (1,0), a movable 2×1 app at (0,1), and a movable 1×1 app at (2,1);
        // page 2 has a movable 1×2 app at (0,0). Both non-1×1 apps are
        // STRATEGY_PRESERVED; only the 1×1 app compacts (to the earliest free
        // page-1 cell), and a replan of the materialized result is empty.
        val items = listOf(
            app("pin", 1, 0, page = "p0", locked = true),
            app("wide", 0, 1, page = "p0", spanW = 2, spanH = 1),
            app("one", 2, 1, page = "p0"),
            app("tall", 0, 0, page = "p1", spanW = 1, spanH = 2),
        )
        val result = planner.plan(twoPageInput(items, 3, 2))

        val planned = result.outcome as Planned
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), planned.placements.single { it.item == ItemId("wide") }.disposition)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), planned.placements.single { it.item == ItemId("tall") }.disposition)
        // The 1×1 app compacts onto page 1's earliest free cell.
        assertEquals("p0", wsPageId(targetOf(result, "one")))
        assertEquals(GridCell(0, 0), wsCell(targetOf(result, "one")))
        assertTrue(planned.newFolders.isEmpty())

        // Materialize and replan: the diff must be empty.
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
        val second = planner.plan(twoPageInput(applied, 3, 2))
        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun foldersFormOverEligibleCandidatesOnlyAndArePlacedAfterTheSingletonStream() {
        // Two same-profile GAMES 1×1 apps (canonical grouping would apply) and
        // one fallback singleton, spread over two pages. The folder forms from
        // the eligible 1×1 candidates, is placed after the compacting units,
        // and its members become FOLDER_MEMBER placements.
        val signals = ClassificationSignals(
            listOf(
                ClassificationSignal(ItemId("g1"), SignalSource.S1, CategoryId("GAMES")),
                ClassificationSignal(ItemId("g2"), SignalSource.S1, CategoryId("GAMES")),
            ),
        )
        val items = listOf(
            app("g1", 0, 3, page = "p1"),
            app("g2", 1, 3, page = "p1"),
            app("s1", 0, 0, page = "p1"),
        )
        val base = twoPageInput(items, 4, 4)
        val result = planner.plan(base.copy(signals = signals))

        val planned = result.outcome as Planned
        assertEquals(1, planned.newFolders.size)
        val folder = planned.newFolders.single()
        assertEquals(listOf(ItemId("g1"), ItemId("g2")), folder.members)
        // The singleton stream compacts first: s1 takes page 1's earliest cell,
        // then the folder takes the next earliest free cell.
        assertEquals(GridCell(0, 0), wsCell(targetOf(result, "s1")))
        val folderPlacement = planned.placements.single { it.item == ItemId("g1") }
        assertEquals(Disposition.Moved(PlacementCode.FOLDER_MEMBER), folderPlacement.disposition)

        // Materialize and replan: the formed folder is a captured folder on
        // replan and joins the fixed set (STRATEGY_PRESERVED); its members
        // become STRUCTURAL folder members; every singleton reclaims its cell.
        val targets = planned.placements.associate { it.item to it.target }
        val folderTarget = planned.newFolders.single().workspacePlacement
        val folderPage = folderTarget.page as PageRef
        val materializedFolder = CapturedItem(
            id = ItemId("fold0"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(FolderId("fold0")),
            placement = CapturedPlacement.Workspace(folderPage, folderTarget.cell, folderTarget.span),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = FolderId("fold0"),
            members = listOf(ItemId("g1"), ItemId("g2")),
        )
        val applied = items.map { item ->
            when (item.id) {
                ItemId("g1") -> app("g1", 0, 0).copy(
                    placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold0")), 0),
                )

                ItemId("g2") -> app("g2", 0, 0).copy(
                    placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold0")), 1),
                )

                else -> {
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
            }
        }
        val second = planner.plan(
            twoPageInput(applied + listOf(materializedFolder), 4, 4).copy(signals = signals),
        )
        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
        assertTrue(secondPlanned.newFolders.isEmpty())
    }

    @Test
    fun existingFoldersNeverCompactCrossPage() {
        // An existing folder on page 2 must not move to page 1 even though
        // page 1 has free cells — existing folders are STRATEGY_PRESERVED.
        val items = listOf(
            app("fixed", 0, 0, page = "p0", locked = true),
            app("p2solo", 0, 0, page = "p1"),
            folderOnPage2(),
            CapturedItem(
                id = ItemId("foldchild"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.foldchild"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold")), 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val result = planner.plan(twoPageInput(items, 2, 2))

        val planned = result.outcome as Planned
        assertEquals(
            Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
            planned.placements.single { it.item == ItemId("fold") }.disposition,
        )
        assertEquals("p1", wsPageId(targetOf(result, "fold")))
    }

    @Test
    fun planEchoesTheGlobalCompactStrategyIdentity() {
        val result = planner.plan(twoPageInput(listOf(app("a", 0, 0, page = "p1")), 2, 2))

        assertEquals(strategy, result.organizationStrategy)
    }

    private fun folderOnPage2() = CapturedItem(
        id = ItemId("fold"),
        profile = p0,
        kind = ItemKind.FOLDER,
        target = TargetKey.FolderKey(FolderId("fold")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p1")), GridCell(1, 1), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
        folderId = FolderId("fold"),
        members = listOf(ItemId("foldchild")),
    )

    private fun wsCell(target: PlacementTarget): GridCell = (target as PlacementTarget.WorkspaceTarget).cell
}
