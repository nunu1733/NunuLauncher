package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.MaterializationResult
import app.lawnchair.organizer.planning.harness.PostPlanMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun device(
        columns: Int = 4,
        rows: Int = 4,
        orientation: Orientation = Orientation.PORTRAIT,
    ) = DeviceCapabilities(
        columns,
        rows,
        4,
        4,
        4,
        orientation,
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
        orientation: Orientation = Orientation.PORTRAIT,
        pages: List<Page> = listOf(Page(PageId("p0"), PageOrder(0))),
        roles: (CapturedItem) -> ExistingRole = { ExistingRole.Movable },
        runMode: RunMode = RunMode.FullOrganization,
        additions: List<CandidateItem> = emptyList(),
        minGroupSize: Int = 2,
        signals: List<ClassificationSignal> = emptyList(),
        reservations: List<ReservedWorkspaceRegion> = emptyList(),
    ) = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            device(columns, rows, orientation),
            pages,
            items,
            reservations,
        ),
        rules = rules(strategy, minGroupSize),
        taxonomy = taxonomy(),
        signals = ClassificationSignals(signals),
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

    @Test
    fun tidyV2EligibleSingleCellWidgetMovesThroughTheWidgetStream() {
        // A 1×1 widget is eligible like any other span: it travels the widget
        // stream (WIDGET_UNIT, band = its own row), never the app stream.
        val source = input(listOf(widget("w", 3, 3, 1, 1), app("a1", 0, 0)), tidyV2)

        val result = planner.plan(source)

        assertEquals(GridCell(0, 3), wsTarget(result, "w").cell)
        assertEquals(GridSpan(1, 1), wsTarget(result, "w").span)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
        assertEquals(GridCell(0, 0), wsTarget(result, "a1").cell)
    }

    @Test
    fun tidyV2WidgetStreamIsDeterministicAcrossDeviceProfiles() {
        // Spec AC-5: the widget stream (band, invariant key order, degrade
        // absence) is deterministic on landscape and both two-panel
        // orientations, not just portrait. Per profile: two 2×2 widgets
        // (com.a key-packs first) plus one app; expected cells are
        // profile-specific and hand-derived.
        data class Profile(val columns: Int, val rows: Int, val orientation: Orientation, val w1: GridCell, val w2: GridCell, val app: GridCell)

        // LANDSCAPE 6×4: w1 captured (4,0), w2 (0,2), app (0,1). Band = all
        // rows; key order packs com.a at (0,0), com.b at (2,0); the app lifts
        // to the first free cell (4,0).
        // TWO_PANEL_LANDSCAPE 6×4: same geometry, different orientation.
        // TWO_PANEL_PORTRAIT 4×6: w1 4×2 at (0,1) keeps its cell (band start),
        // w2 2×2 at (1,4) consolidates to (0,3); the app lifts to (0,0).
        val profiles = listOf(
            Profile(6, 4, Orientation.LANDSCAPE, GridCell(0, 0), GridCell(2, 0), GridCell(4, 0)),
            Profile(6, 4, Orientation.TWO_PANEL_LANDSCAPE, GridCell(0, 0), GridCell(2, 0), GridCell(4, 0)),
            Profile(4, 6, Orientation.TWO_PANEL_PORTRAIT, GridCell(0, 1), GridCell(0, 3), GridCell(0, 0)),
        )
        for (profile in profiles) {
            val items = if (profile.orientation == Orientation.TWO_PANEL_PORTRAIT) {
                listOf(
                    widget("w1", 0, 1, 4, 2, provider = "com.a", appWidgetId = 1),
                    widget("w2", 1, 4, 2, 2, provider = "com.b", appWidgetId = 2),
                    app("a1", 0, 3),
                )
            } else {
                listOf(
                    widget("w1", 4, 0, 2, 2, provider = "com.a", appWidgetId = 1),
                    widget("w2", 0, 2, 2, 2, provider = "com.b", appWidgetId = 2),
                    app("a1", 0, 1),
                )
            }
            val source = input(items, tidyV2, columns = profile.columns, rows = profile.rows, orientation = profile.orientation)

            val result = planner.plan(source)

            assertEquals("${profile.orientation}: w1", profile.w1, wsTarget(result, "w1").cell)
            assertEquals("${profile.orientation}: w2", profile.w2, wsTarget(result, "w2").cell)
            assertEquals("${profile.orientation}: a1", profile.app, wsTarget(result, "a1").cell)
            assertEquals("${profile.orientation}: w2 rationale", Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w2").disposition)
            assertReplanIsEmptyDiff(result, source)
        }
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

    @Test
    fun bottomFirstV2WidgetStreamIsDeterministicAcrossDeviceProfiles() {
        // Spec AC-5: top-anchored widget placement over the bottom-up app
        // stream is deterministic on landscape and both two-panel
        // orientations. Per profile: one 2×2 widget plus one app (lifted, so
        // not an obstacle) — the widget takes the page's top-left rectangle
        // and the app stream reclaims the bottom-left cell.
        data class Profile(val columns: Int, val rows: Int, val orientation: Orientation, val widget: CapturedItem, val appCell: GridCell)

        val profiles = listOf(
            Profile(6, 4, Orientation.LANDSCAPE, widget("w", 2, 2, 2, 2, provider = "com.a", appWidgetId = 1), GridCell(0, 3)),
            Profile(6, 4, Orientation.TWO_PANEL_LANDSCAPE, widget("w", 2, 2, 2, 2, provider = "com.a", appWidgetId = 1), GridCell(0, 3)),
            Profile(4, 6, Orientation.TWO_PANEL_PORTRAIT, widget("w", 1, 1, 2, 2, provider = "com.a", appWidgetId = 1), GridCell(0, 5)),
        )
        for (profile in profiles) {
            val items = listOf(profile.widget, app("a1", 0, 0))
            val source = input(
                items,
                bottomFirstV2,
                columns = profile.columns,
                rows = profile.rows,
                orientation = profile.orientation,
                minGroupSize = 99,
            )

            val result = planner.plan(source)

            assertEquals("${profile.orientation}: w", GridCell(0, 0), wsTarget(result, "w").cell)
            assertEquals("${profile.orientation}: w span", GridSpan(2, 2), wsTarget(result, "w").span)
            assertEquals("${profile.orientation}: rationale", Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
            assertEquals("${profile.orientation}: a1", profile.appCell, wsTarget(result, "a1").cell)
            assertReplanIsEmptyDiff(result, source)
        }
    }

    @Test
    fun bottomFirstV2ScopeComposedCandidatesOverflowPastWidgetOccupancy() {
        // Spec app-stream note under the unchanged PREFERRED_THEN_NEW scope:
        // widget targets are occupancy for the candidate tail too — a full
        // captured page (widget + 12 apps on 4×4) leaves no free cell, so
        // scope-composed candidates open a new page instead of squeezing in.
        val items = buildList {
            add(widget("w", 0, 0, 2, 2, provider = "com.a", appWidgetId = 1))
            val cells = listOf(
                GridCell(2, 0), GridCell(3, 0), GridCell(2, 1), GridCell(3, 1),
                GridCell(0, 2), GridCell(1, 2), GridCell(2, 2), GridCell(3, 2),
                GridCell(0, 3), GridCell(1, 3), GridCell(2, 3), GridCell(3, 3),
            )
            cells.forEachIndexed { index, cell -> add(app("a$index", cell.x, cell.y)) }
        }
        val source = input(
            items,
            bottomFirstV2,
            runMode = RunMode.ScopeComposedOrganization,
            additions = listOf(candidate("c0"), candidate("c1"), candidate("c2")),
            minGroupSize = 99,
        )

        val result = planner.plan(source)

        val outcome = planned(result)
        assertEquals(GridCell(0, 0), wsTarget(result, "w").cell)
        assertEquals(1, outcome.newPages.size)
        for (index in 0 until 3) {
            val target = wsTarget(result, "c$index")
            assertTrue("candidate c$index must overflow to the new page: $target", target.page is NewPageRef)
        }
        assertTrue(outcome.unplaced.isEmpty())
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

    @Test
    fun bottomUpWindowedFirstFitScansFromTheWindowUpperBound() {
        // Review L-3: the mirrored traversal + window arm is not reachable
        // from this issue's policies (the widget stream scans top-left);
        // pin its semantics at the allocator seam for the child that first
        // uses it. The window's upper bound joins the candidate-y origins.
        val occupied = listOf(Rect(0, 0, 2, 2))

        // Window rows 2..5 on a 4×6 page: the unwindowed bottom-up scan
        // would start at y=4 (in-window), then y=2 (window origin) after
        // in-window exhaustion... y=4 is free, so it wins.
        assertEquals(GridCell(0, 4), findRowMajorFirstFit(occupied, 4, 6, GridSpan(2, 2), CellTraversal.BOTTOM_UP_ROW_MAJOR, rowWindow = 2..5))

        // Window rows 0..3: y=4 is out of window; the window-origin y=2 is
        // free.
        assertEquals(GridCell(0, 2), findRowMajorFirstFit(occupied, 4, 6, GridSpan(2, 2), CellTraversal.BOTTOM_UP_ROW_MAJOR, rowWindow = 0..3))

        // A span taller than the window can never fit.
        assertNull(findRowMajorFirstFit(emptyList(), 4, 6, GridSpan(2, 2), CellTraversal.BOTTOM_UP_ROW_MAJOR, rowWindow = 0..0))
    }

    // ------------------------------------------------------------------
    // Reserved workspace regions (owner review PR #296 High)
    // ------------------------------------------------------------------

    @Test
    fun tidyV2WidgetNeverLandsOnAnEmptyReservedRegionInsideItsBand() {
        // The reservation strip shares the widget's band top row and no
        // captured item overlaps it, so only the obstacle handling can keep
        // the widget off it: the first-fit stays on the widget's own (free)
        // columns instead of sliding onto the reserved cells.
        val reservation = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 4), GridSpan(2, 1))
        val items = listOf(
            widget("w", 2, 4, 2, 2, provider = "com.a", appWidgetId = 1),
            app("a1", 0, 0),
        )
        val source = input(items, tidyV2, rows = 6, reservations = listOf(reservation))

        val result = planner.plan(source)

        // (0,4) would be the unobstructed band-first fit — it must not win.
        assertEquals(GridCell(2, 4), wsTarget(result, "w").cell)
        assertEquals(GridSpan(2, 2), wsTarget(result, "w").span)
        assertEquals(Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), placement(result, "w").disposition)
        assertEquals(GridCell(0, 0), wsTarget(result, "a1").cell)
        assertReplanIsEmptyDiff(result, source)
    }

    @Test
    fun bottomFirstV2WidgetAvoidsTheTopReservedRegion() {
        // QSB-like top strip: the top-anchored first-fit must skip row 0 and
        // take the earliest free rectangle below it — never the reservation.
        val reservation = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1))
        val items = listOf(
            widget("w", 2, 2, 2, 2, provider = "com.a", appWidgetId = 1),
            app("a1", 0, 3),
        )
        val source = input(items, bottomFirstV2, rows = 4, reservations = listOf(reservation), minGroupSize = 99)

        val result = planner.plan(source)

        assertEquals(GridCell(0, 1), wsTarget(result, "w").cell)
        assertEquals(GridSpan(2, 2), wsTarget(result, "w").span)
        assertEquals(Disposition.Moved(PlacementCode.WIDGET_UNIT), placement(result, "w").disposition)
        // The app stream sees the reservation through the shared allocator.
        assertEquals(GridCell(0, 3), wsTarget(result, "a1").cell)
        assertReplanIsEmptyDiff(result, source)
    }
}
