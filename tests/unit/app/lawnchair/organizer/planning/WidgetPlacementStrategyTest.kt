package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.MaterializationResult
import app.lawnchair.organizer.planning.harness.PostPlanMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #235 behavioral tests for the widget-relocating successor strategies
 * `STABLE_PAGE_TIDY_V2` (widget band) and `BOTTOM_FIRST_V2` (top-anchored),
 * driven strictly through the public `OrganizationPlanner.plan` seam.
 *
 * Folder formation is disabled (minGroupSize above folder capacity) in the
 * geometry fixtures so app outcomes are category-independent, matching the
 * spec's folder-formation-free representative fixtures.
 */
class WidgetPlacementStrategyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val tidyV2 = StrategyId("STABLE_PAGE_TIDY_V2")
    private val bottomFirstV2 = StrategyId("BOTTOM_FIRST_V2")
    private val p0 = ProfileId("p0")

    private fun rules(strategy: StrategyId, minGroupSize: Int = 2) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(minGroupSize, NewFolderProfileScope.SAME_PROFILE_ONLY),
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
        locked: Boolean = false,
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun wideApp(id: String, x: Int, y: Int, width: Int, height: Int) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(width, height)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun folder(id: String, x: Int, y: Int, children: List<String>): List<CapturedItem> {
        val folderItem = CapturedItem(
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
        val memberItems = children.mapIndexed { rank, child ->
            CapturedItem(
                id = ItemId(child),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.$child"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId(id)), rank),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }
        return listOf(folderItem) + memberItems
    }

    private fun widget(
        id: String,
        x: Int,
        y: Int,
        spanW: Int,
        spanH: Int,
        provider: String = "com.example.$id",
        appWidgetId: Int = 1,
        locked: Boolean = false,
        page: String = "p0",
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPWIDGET,
        target = TargetKey.WidgetKey(ComponentKey("$provider/.Widget"), AppWidgetId(appWidgetId), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun input(
        items: List<CapturedItem>,
        strategy: StrategyId,
        columns: Int = 4,
        rows: Int = 4,
        pages: List<Page> = listOf(Page(PageId("p0"), PageOrder(0))),
        roles: (CapturedItem) -> ExistingRole = { ExistingRole.Movable },
        runMode: RunMode = RunMode.FullOrganization,
        additions: List<CandidateItem> = emptyList(),
        minGroupSize: Int = 2,
    ) = OrganizationInput(
        snapshot = LayoutSnapshot(RevisionId("rev"), device(columns, rows), pages, items, emptyList()),
        rules = rules(strategy, minGroupSize),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, roles(it)) }, additions),
        runMode = runMode,
    )

    private fun planned(result: PlanningResult): Planned = result.outcome as Planned

    private fun placement(result: PlanningResult, id: String): PlannedPlacement = planned(result).placements.single { it.item == ItemId(id) }

    private fun wsTarget(result: PlanningResult, id: String): PlacementTarget.WorkspaceTarget = placement(result, id).target as PlacementTarget.WorkspaceTarget

    /** Materializes the plan (production recapture roles) and replans it. */
    private fun replan(result: PlanningResult, source: OrganizationInput): PlanningResult {
        val materialized = PostPlanMaterializer.materialize(source, planned(result))
        assertTrue("materialization failed: $materialized", materialized is MaterializationResult.Success)
        return planner.plan((materialized as MaterializationResult.Success).input)
    }

    private fun assertReplanIsEmptyDiff(result: PlanningResult, source: OrganizationInput) {
        val replanned = replan(result, source)
        val outcome = planned(replanned)
        assertTrue(
            "replan moved: ${outcome.placements.filter { it.disposition is Disposition.Moved }}",
            outcome.placements.none { it.disposition is Disposition.Moved },
        )
        assertTrue(outcome.newPages.isEmpty())
        assertTrue(outcome.newFolders.isEmpty())
    }

    // ------------------------------------------------------------------
    // STABLE_PAGE_TIDY_V2 — widget band (spec normative fixture (a))
    // ------------------------------------------------------------------

    @Test
    fun tidyV2ConsolidatesWidgetsIntoTheirBandAndClosesTheIconGap() {
        // 4×6 page: a 4×2 widget on rows 1-2 and a 2×2 widget on rows 4-5.
        // Band = rows 1..5. The 4×2 keeps its cell (band first-fit start); the
        // 2×2 moves up to (0,3) directly below it; the icon that sat between
        // them is lifted out of the band to the earliest free cell.
        val items = listOf(
            widget("w1", 0, 1, 4, 2, provider = "com.a", appWidgetId = 1),
            widget("w2", 1, 4, 2, 2, provider = "com.b", appWidgetId = 2),
            app("a1", 0, 3),
        )
        val source = input(items, tidyV2, rows = 6)

        val result = planner.plan(source)

        val w1 = wsTarget(result, "w1")
        val w2 = wsTarget(result, "w2")
        assertEquals(GridCell(0, 1), w1.cell)
        assertEquals(GridSpan(4, 2), w1.span)
        assertEquals(GridCell(0, 3), w2.cell)
        assertEquals(GridSpan(2, 2), w2.span)
        assertEquals(Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), placement(result, "w1").disposition)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w2").disposition)
        assertEquals(GridCell(0, 0), wsTarget(result, "a1").cell)
        assertEquals(Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), placement(result, "a1").disposition)
        assertTrue(planned(result).newPages.isEmpty() && planned(result).newFolders.isEmpty())
    }

    @Test
    fun tidyV2LoneWidgetOnlyLeftAlignsWithinItsOwnRows() {
        // Spec fixture (b): a lone widget's band is its own rows, so it can
        // only slide left within them.
        val source = input(listOf(widget("w", 2, 2, 2, 2)), tidyV2, rows = 5)

        val result = planner.plan(source)

        assertEquals(GridCell(0, 2), wsTarget(result, "w").cell)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
    }

    @Test
    fun tidyV2BandFragmentationDegradesTheWholePageTruthfully() {
        // Spec fixture (c): the key-first 2×3 widget takes the 3×2 widget's
        // captured cells, and the locked cells deny every remaining band
        // rectangle — the page degrades and BOTH widgets keep their captured
        // positions with the truthful STRATEGY_PRESERVED reason.
        val items = listOf(
            widget("w1", 3, 1, 2, 3, provider = "com.a", appWidgetId = 1),
            widget("w2", 0, 0, 3, 2, provider = "com.b", appWidgetId = 2),
            app("lockA", 3, 0, locked = true),
            app("lockB", 4, 0, locked = true),
            app("lockC", 2, 2, locked = true),
        )
        val source = input(items, tidyV2, columns = 5, rows = 4)

        val result = planner.plan(source)

        assertEquals(GridCell(3, 1), wsTarget(result, "w1").cell)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), placement(result, "w1").disposition)
        assertEquals(GridCell(0, 0), wsTarget(result, "w2").cell)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), placement(result, "w2").disposition)
    }

    @Test
    fun tidyV2StrategyFixedItemsInsideTheBandAreObstaclesAndNeverOverlapped() {
        // Spec fixture (d) / review M1: an existing folder and a non-1×1 app
        // inside the band are occupancy for the widget stream — no overlap,
        // and they stay STRATEGY_PRESERVED like under V1.
        val items = listOf(
            widget("w", 2, 2, 2, 2, provider = "com.a", appWidgetId = 1),
            wideApp("wide", 1, 0, 2, 1),
            app("a1", 3, 0),
        ) + folder("f", 0, 2, listOf("m1", "m2"))
        val source = input(items, tidyV2)

        val result = planner.plan(source)

        assertEquals(GridCell(1, 2), wsTarget(result, "w").cell)
        assertEquals(GridSpan(2, 2), wsTarget(result, "w").span)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
        assertEquals(GridCell(0, 2), wsTarget(result, "f").cell)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), placement(result, "f").disposition)
        assertEquals(GridCell(1, 0), wsTarget(result, "wide").cell)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), placement(result, "wide").disposition)

        // No two workspace targets on the page overlap.
        val rects = planned(result).placements
            .mapNotNull { it.target as? PlacementTarget.WorkspaceTarget }
            .map { Pair(it.cell, it.span) }
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val (c1, s1) = rects[i]
                val (c2, s2) = rects[j]
                val overlaps = c1.x < c2.x + s2.width && c2.x < c1.x + s1.width &&
                    c1.y < c2.y + s2.height && c2.y < c1.y + s1.height
                assertTrue("rects overlap: $c1/$s1 vs $c2/$s2", !overlaps)
            }
        }
    }

    @Test
    fun tidyV2WidgetOutsideTheTargetSetStaysNonTarget() {
        // Spec common rules: parameterizing the widget branch only yields at
        // that precedence step — an out-of-target-set widget still reaches
        // NON_TARGET and never moves (spec 10 role discipline preserved).
        val items = listOf(widget("w", 0, 2, 2, 2), app("a1", 0, 0))
        val source = input(items, tidyV2, roles = { item ->
            if (item.kind == ItemKind.APPWIDGET) ExistingRole.Preserved else ExistingRole.Movable
        })

        val result = planner.plan(source)

        assertEquals(GridCell(0, 2), wsTarget(result, "w").cell)
        assertEquals(Disposition.Preserved(PreserveReason.NON_TARGET), placement(result, "w").disposition)
    }

    @Test
    fun tidyV2LockedWidgetNeverMoves() {
        val source = input(listOf(widget("w", 0, 2, 2, 2, locked = true)), tidyV2)

        val result = planner.plan(source)

        assertEquals(Disposition.Preserved(PreserveReason.LOCKED), placement(result, "w").disposition)
        assertEquals(GridCell(0, 2), wsTarget(result, "w").cell)
    }

    @Test
    fun tidyV2InvariantKeyOrderPacksWidgetsDeterministicallyAndIsReplanStable() {
        // The invariant key (span desc, target key, ItemId) — not the
        // captured visual order — drives packing: com.a packs left even
        // though it was captured right of com.b. The order is
        // position-independent, so the replan reproduces it exactly.
        val items = listOf(
            widget("wa", 2, 2, 2, 2, provider = "com.a", appWidgetId = 1),
            widget("wb", 0, 2, 2, 2, provider = "com.b", appWidgetId = 2),
        )
        val source = input(items, tidyV2)

        val result = planner.plan(source)

        assertEquals(GridCell(0, 2), wsTarget(result, "wa").cell)
        assertEquals(GridCell(2, 2), wsTarget(result, "wb").cell)
        assertReplanIsEmptyDiff(result, source)
    }

    @Test
    fun tidyV2BandConsolidationReplansToAnEmptyDiff() {
        val items = listOf(
            widget("w1", 0, 1, 4, 2, provider = "com.a", appWidgetId = 1),
            widget("w2", 1, 4, 2, 2, provider = "com.b", appWidgetId = 2),
            app("a1", 0, 3),
        )
        val source = input(items, tidyV2, rows = 6)

        assertReplanIsEmptyDiff(planner.plan(source), source)
    }

    @Test
    fun tidyV2SpanIsAlwaysTheCapturedSpan() {
        val items = listOf(
            widget("w1", 0, 1, 4, 2, provider = "com.a", appWidgetId = 1),
            widget("w2", 1, 4, 2, 2, provider = "com.b", appWidgetId = 2),
        )
        val result = planner.plan(input(items, tidyV2, rows = 6))

        assertEquals(GridSpan(4, 2), wsTarget(result, "w1").span)
        assertEquals(GridSpan(2, 2), wsTarget(result, "w2").span)
    }

    // ------------------------------------------------------------------
    // BOTTOM_FIRST_V2 — top-anchored widgets over the bottom-first apps
    // ------------------------------------------------------------------

    @Test
    fun bottomFirstV2PlacesWidgetsAboveTheBottomFirstAppRegion() {
        // Spec representative fixture (folder-formation-free): the 4×2
        // widget anchors at (0,0) and the eight apps fill the bottom rows.
        val items = listOf(
            widget("w", 0, 2, 4, 2, provider = "com.a", appWidgetId = 1),
        ) + (0 until 8).map { index ->
            app("a$index", index % 4, index / 4)
        }
        val source = input(items, bottomFirstV2, rows = 5, minGroupSize = 99)

        val result = planner.plan(source)

        assertEquals(GridCell(0, 0), wsTarget(result, "w").cell)
        assertEquals(GridSpan(4, 2), wsTarget(result, "w").span)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
        // Bottom-up row-major consumption in canonical singleton order:
        // a0..a3 take row 4, a4..a7 take row 3.
        for (index in 0 until 8) {
            assertEquals(
                GridCell(index % 4, 4 - index / 4),
                wsTarget(result, "a$index").cell,
            )
        }
        assertReplanIsEmptyDiff(result, source)
    }

    @Test
    fun bottomFirstV2DegradesWhenFragmentationDeniesAWidgetFit() {
        // Same geometry as the tidy degrade fixture (whole-page region
        // instead of the band): the key-first 2×3 widget takes the 3×2
        // widget's captured cells and the locked cells deny every remaining
        // rectangle — the page degrades and both widgets stay.
        val items = listOf(
            widget("w1", 3, 1, 2, 3, provider = "com.a", appWidgetId = 1),
            widget("w2", 0, 0, 3, 2, provider = "com.b", appWidgetId = 2),
            app("lockA", 3, 0, locked = true),
            app("lockB", 4, 0, locked = true),
            app("lockC", 2, 2, locked = true),
        )
        val source = input(items, bottomFirstV2, columns = 5, rows = 4)

        val result = planner.plan(source)

        assertEquals(GridCell(3, 1), wsTarget(result, "w1").cell)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), placement(result, "w1").disposition)
        assertEquals(GridCell(0, 0), wsTarget(result, "w2").cell)
        assertEquals(Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), placement(result, "w2").disposition)
    }

    @Test
    fun bottomFirstV2KeepsCanonicalFolderFormationOverWidgetOccupancy() {
        // createsFolders semantics are V1's: three same-category apps form a
        // folder placed by the bottom-up app stream under the widget.
        // (Fallback-category apps never form folders, so the apps carry an
        // explicit GAMES signal.)
        val items = listOf(
            app("a", 0, 3),
            app("b", 1, 3),
            app("c", 2, 3),
            widget("w", 1, 1, 2, 2, provider = "com.a", appWidgetId = 1),
        )
        val signals = listOf("a", "b", "c").map {
            ClassificationSignal(ItemId(it), SignalSource.S1, CategoryId("GAMES"))
        }
        val source = input(items, bottomFirstV2).copy(
            signals = ClassificationSignals(signals),
        )

        val result = planner.plan(source)

        assertEquals(GridCell(0, 0), wsTarget(result, "w").cell)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)

        val outcome = planned(result)
        assertEquals(1, outcome.newFolders.size)
        val newFolder = outcome.newFolders.single()
        assertEquals(GridCell(0, 3), (newFolder.workspacePlacement as PlacementTarget.WorkspaceTarget).cell)
        for (member in listOf("a", "b", "c")) {
            assertEquals(Disposition.Moved(PlacementCode.FOLDER_MEMBER), placement(result, member).disposition)
        }
        assertReplanIsEmptyDiff(result, source)
    }

    // ------------------------------------------------------------------
    // Scope-composed runs (spec D-5)
    // ------------------------------------------------------------------

    @Test
    fun scopeComposedRunWithTidyV2RelocatesWidgetsThenAppendsCandidates() {
        val items = listOf(
            widget("w", 1, 2, 2, 2, provider = "com.a", appWidgetId = 1),
            app("a", 0, 0),
        )
        val source = input(
            items,
            tidyV2,
            rows = 5,
            runMode = RunMode.ScopeComposedOrganization,
            additions = listOf(candidate("c1")),
        )

        val result = planner.plan(source)

        assertEquals(GridCell(0, 2), wsTarget(result, "w").cell)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
        assertEquals(GridCell(1, 0), wsTarget(result, "c1").cell)
        assertEquals(Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), placement(result, "c1").disposition)
        assertTrue(planned(result).unplaced.isEmpty())
    }

    private fun candidate(id: String) = CandidateItem(
        id = ItemId(id),
        profile = p0,
        kind = CandidateKind.APPLICATION,
        target = CandidateTarget.AppKey(ComponentKey("com.example.$id"), p0),
        availability = Availability.AVAILABLE,
        span = GridSpan(1, 1),
    )
}
