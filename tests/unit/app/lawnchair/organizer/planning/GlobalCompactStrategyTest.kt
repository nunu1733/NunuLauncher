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
    private val strategyV2 = StrategyId("GLOBAL_COMPACT_V2")
    private val p0 = ProfileId("p0")

    private fun rules(strategyId: StrategyId = strategy) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategyId,
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

    private fun folder(
        id: String,
        x: Int,
        y: Int,
        page: String = "p1",
        memberIds: List<String>,
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.FOLDER,
        target = TargetKey.FolderKey(FolderId(id)),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
        folderId = FolderId(id),
        members = memberIds.map(::ItemId),
    )

    private fun folderMember(id: String, folderId: String, rank: Int) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.FolderMember(FolderRef(FolderId(folderId)), rank),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun twoPageInput(
        items: List<CapturedItem>,
        columns: Int,
        rows: Int,
        strategyId: StrategyId = strategy,
    ) = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            device(columns, rows),
            listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items,
            emptyList(),
        ),
        rules = rules(strategyId),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun threePageInput(
        items: List<CapturedItem>,
        columns: Int,
        rows: Int,
        strategyId: StrategyId = strategy,
    ) = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            device(columns, rows),
            listOf(
                Page(PageId("p0"), PageOrder(0)),
                Page(PageId("p1"), PageOrder(1)),
                Page(PageId("p2"), PageOrder(2)),
            ),
            items,
            emptyList(),
        ),
        rules = rules(strategyId),
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

    @Test
    fun v2ExistingFolderJoinsCrossPageCompaction() {
        // Spec 237 AC-2: an existing unlocked 1×1 folder on page 2 relocates
        // into page 1's free cells in global captured visual order — the
        // behavior the "compact across screens" name promises. V1 pins this
        // unit as STRATEGY_PRESERVED-fixed; V2 moves it as FOLDER_UNIT while
        // its member stays STRUCTURAL and untouched.
        val items = listOf(
            app("fixed", 0, 0, page = "p0", locked = true),
            folder("fold", 0, 0, page = "p1", memberIds = listOf("foldchild")),
            folderMember("foldchild", "fold", 0),
            app("p2a", 1, 0, page = "p1"),
            app("p2b", 0, 1, page = "p1"),
        )
        val result = planner.plan(twoPageInput(items, 2, 2, strategyV2))

        val planned = result.outcome as Planned
        assertEquals(Disposition.Preserved(PreserveReason.LOCKED), planned.placements.single { it.item == ItemId("fixed") }.disposition)
        assertEquals("p0", wsPageId(targetOf(result, "fold")))
        assertEquals(GridCell(1, 0), wsCell(targetOf(result, "fold")))
        assertEquals(Disposition.Moved(PlacementCode.FOLDER_UNIT), planned.placements.single { it.item == ItemId("fold") }.disposition)
        assertEquals("p0", wsPageId(targetOf(result, "p2a")))
        assertEquals(GridCell(0, 1), wsCell(targetOf(result, "p2a")))
        assertEquals("p0", wsPageId(targetOf(result, "p2b")))
        assertEquals(GridCell(1, 1), wsCell(targetOf(result, "p2b")))
        val memberRow = planned.placements.single { it.item == ItemId("foldchild") }
        assertEquals(Disposition.Preserved(PreserveReason.STRUCTURAL), memberRow.disposition)
        assertEquals(
            PlacementTarget.FolderMember(FolderRef(FolderId("fold")), 0),
            memberRow.target,
        )
        assertTrue(planned.newPages.isEmpty())
        assertTrue(planned.newFolders.isEmpty())

        // Materialize and replan: the diff must be empty.
        val second = planner.plan(twoPageInput(materialize(items, planned), 2, 2, strategyV2))
        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun v2FormationThenApplyThenRecaptureThenReplanIsAnEmptyDiff() {
        // Spec 237 AC-5: the formation-inclusive state transition. Run 1
        // forms a new folder from same-category singletons; after
        // materialization and recapture that folder is an ordinary movable
        // 1×1 folder under V2 — it reclaims its cell as ALREADY_CANONICAL
        // (never STRATEGY_PRESERVED, which is V1's pin) and the replan diff
        // is empty (formation is replan-stable: no new folders).
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
        val result = planner.plan(twoPageInput(items, 4, 4, strategyV2).copy(signals = signals))

        val planned = result.outcome as Planned
        assertEquals(1, planned.newFolders.size)
        val folder = planned.newFolders.single()
        assertEquals(listOf(ItemId("g1"), ItemId("g2")), folder.members)

        val folderTarget = folder.workspacePlacement
        val materializedFolder = CapturedItem(
            id = ItemId("fold0"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(FolderId("fold0")),
            placement = CapturedPlacement.Workspace(
                folderTarget.page as PageRef,
                folderTarget.cell,
                folderTarget.span,
            ),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = FolderId("fold0"),
            members = listOf(ItemId("g1"), ItemId("g2")),
        )
        val applied = items.map { item ->
            when (item.id) {
                ItemId("g1") -> folderMember("g1", "fold0", 0)

                ItemId("g2") -> folderMember("g2", "fold0", 1)

                else -> {
                    val target = planned.placements.single { it.item == item.id }.target as PlacementTarget.WorkspaceTarget
                    item.copy(placement = CapturedPlacement.Workspace(target.page as PageRef, target.cell, target.span))
                }
            }
        }
        val second = planner.plan(
            twoPageInput(applied + materializedFolder, 4, 4, strategyV2).copy(signals = signals),
        )
        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
        assertTrue(secondPlanned.newFolders.isEmpty())
        val foldRow = secondPlanned.placements.single { it.item == ItemId("fold0") }
        assertEquals(Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), foldRow.disposition)
    }

    @Test
    fun v2NonSquareMovableUnitsStayStrategyPreserved() {
        // Spec 237: the spec 182 counterexample fixture still holds under V2 —
        // non-1×1 movable units never compact and never become folder
        // candidates, so the heterogeneous-span INV-8 violation cannot occur.
        val items = listOf(
            app("pin", 1, 0, page = "p0", locked = true),
            app("wide", 0, 1, page = "p0", spanW = 2, spanH = 1),
            app("one", 2, 1, page = "p0"),
            app("tall", 0, 0, page = "p1", spanW = 1, spanH = 2),
        )
        val result = planner.plan(twoPageInput(items, 3, 2, strategyV2))

        val planned = result.outcome as Planned
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), planned.placements.single { it.item == ItemId("wide") }.disposition)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), planned.placements.single { it.item == ItemId("tall") }.disposition)
        assertEquals("p0", wsPageId(targetOf(result, "one")))
        assertEquals(GridCell(0, 0), wsCell(targetOf(result, "one")))
        assertTrue(planned.newFolders.isEmpty())

        val second = planner.plan(twoPageInput(materialize(items, planned), 3, 2, strategyV2))
        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun v2NonUnitSpanFolderIsStrategyPreserved() {
        // Spec 237: a captured folder whose workspace span is not 1×1 never
        // joins the compaction stream — every mover stays 1×1 (the
        // homogeneous-span idempotence precondition).
        val bigFold = CapturedItem(
            id = ItemId("bigfold"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(FolderId("bigfold")),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p1")), GridCell(0, 0), GridSpan(2, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = FolderId("bigfold"),
            members = listOf(ItemId("bigchild")),
        )
        val items = listOf(
            app("fixed", 0, 0, page = "p0", locked = true),
            bigFold,
            folderMember("bigchild", "bigfold", 0),
            app("solo", 0, 1, page = "p1"),
        )
        val result = planner.plan(twoPageInput(items, 2, 2, strategyV2))

        val planned = result.outcome as Planned
        val foldRow = planned.placements.single { it.item == ItemId("bigfold") }
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), foldRow.disposition)
        assertEquals("p1", wsPageId(foldRow.target))
        assertEquals(GridCell(0, 0), wsCell(foldRow.target))
        assertEquals("p0", wsPageId(targetOf(result, "solo")))
        assertEquals(GridCell(1, 0), wsCell(targetOf(result, "solo")))
    }

    @Test
    fun v2ThreePageFolderHeavyLayoutFillsEarlierPagesFirst() {
        // Spec 237 AC-2/AC-3/AC-10: the 2026-09-06 device shape — a later
        // page dominated by existing folders must compact into earlier free
        // cells, and no movable 1×1 top-level unit may remain behind while an
        // earlier page still has a placeable free cell.
        val items = listOf(
            app("s", 0, 0, page = "p0"),
            app("locked", 1, 1, page = "p0", locked = true),
            folder("f1", 0, 0, page = "p1", memberIds = listOf("m1")),
            folder("f2", 1, 0, page = "p1", memberIds = listOf("m2")),
            folder("f3", 0, 1, page = "p1", memberIds = listOf("m3")),
            folderMember("m1", "f1", 0),
            folderMember("m2", "f2", 0),
            folderMember("m3", "f3", 0),
            app("a", 1, 1, page = "p1"),
            app("b", 0, 0, page = "p2"),
        )
        val result = planner.plan(threePageInput(items, 2, 2, strategyV2))

        val planned = result.outcome as Planned
        // Page 1 fills first (f1, f2 relocate; the locked cell stays), then
        // page 2's app follows into page 2's vacated cells — page p2 ends up
        // empty of top-level units.
        assertEquals("p0", wsPageId(targetOf(result, "f1")))
        assertEquals(GridCell(1, 0), wsCell(targetOf(result, "f1")))
        assertEquals("p0", wsPageId(targetOf(result, "f2")))
        assertEquals(GridCell(0, 1), wsCell(targetOf(result, "f2")))
        assertEquals("p1", wsPageId(targetOf(result, "b")))
        assertTrue(planned.newPages.isEmpty())
        assertTrue(planned.newFolders.isEmpty())
        val crossPageMoves = planned.placements.count { row ->
            val disposition = row.disposition
            if (disposition !is Disposition.Moved) {
                return@count false
            }
            val target = row.target as? PlacementTarget.WorkspaceTarget ?: return@count false
            val item = items.single { it.id == row.item }
            val captured = item.placement as CapturedPlacement.Workspace
            (target.page as PageRef).pageId != captured.page.pageId
        }
        assertEquals(3, crossPageMoves)

        // Materialize and replan: the diff must be empty.
        val second = planner.plan(threePageInput(materialize(items, planned), 2, 2, strategyV2))
        val secondPlanned = second.outcome as Planned
        assertTrue(secondPlanned.placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun planEchoesTheGlobalCompactV2StrategyIdentity() {
        val result = planner.plan(twoPageInput(listOf(app("a", 0, 0, page = "p1")), 2, 2, strategyV2))

        assertEquals(strategyV2, result.organizationStrategy)
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

    /** Rebuilds captured items from a planned output (workspace rows only). */
    private fun materialize(items: List<CapturedItem>, planned: Planned): List<CapturedItem> = items.map { item ->
        when (val target = planned.placements.associate { it.item to it.target }.getValue(item.id)) {
            is PlacementTarget.WorkspaceTarget -> item.copy(
                placement = CapturedPlacement.Workspace(
                    target.page as PageRef,
                    target.cell,
                    target.span,
                ),
            )

            else -> item
        }
    }

    private fun wsCell(target: PlacementTarget): GridCell = (target as PlacementTarget.WorkspaceTarget).cell
}
