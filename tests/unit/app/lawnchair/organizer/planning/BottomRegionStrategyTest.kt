package app.lawnchair.organizer.planning

import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.IntentPlannerAdapter
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.planning.harness.ContractCheck
import app.lawnchair.organizer.planning.harness.ExpectedOutcome
import app.lawnchair.organizer.planning.harness.FixtureExpectation
import app.lawnchair.organizer.planning.harness.FixtureId
import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.PlannerFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #398 BOTTOM_REGION_V1 behavioral tests (spec 398), driven strictly
 * through the public `OrganizationPlanner.plan` seam. Covers the accepted
 * normative rules: lower-preferred-region geometry, region-only sweep with
 * next-page overflow, GLOBAL_COMPACT_V1-shaped fixed set, canonical-family
 * folder policy with folders placed after the units, soft preserve hint,
 * intent ordering biases, and the dense-input identity non-degeneracy
 * required by the strategy objective.
 */
class BottomRegionStrategyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val region = StrategyId("BOTTOM_REGION_V1")
    private val canonical = StrategyId("CANONICAL_PAGE_COMPACT_V1")
    private val globalV2 = StrategyId("GLOBAL_COMPACT_V2")
    private val p0 = ProfileId("p0")

    private fun rules(strategyId: StrategyId) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategyId,
    )

    private fun taxonomy() = TaxonomyContract(
        TaxonomyVersion("tv1"),
        listOf(CategoryId("OTHER"), CategoryId("GAMES")),
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

    private fun pages(count: Int) = (0 until count).map { Page(PageId("p$it"), PageOrder(it)) }

    private fun input(
        items: List<CapturedItem>,
        columns: Int,
        rows: Int,
        strategyId: StrategyId = region,
        pageList: List<Page> = pages(2),
    ) = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            device(columns, rows),
            pageList,
            items,
            emptyList(),
        ),
        rules = rules(strategyId),
        taxonomy = taxonomy(),
        catalog = ActiveCategoryCatalog(taxonomy(), emptyList()),
        signals = ClassificationSignals(emptyList()),
        // Production-realistic target roles: folder members are preserved;
        // top-level items are movable.
        targets = TargetSet(
            items.map {
                ExistingTargetMembership(
                    it.id,
                    if (it.placement is CapturedPlacement.FolderMember) ExistingRole.Preserved else ExistingRole.Movable,
                )
            },
            emptyList(),
        ),
        runMode = RunMode.FullOrganization,
    )

    private fun planned(result: PlanningResult) = result.outcome as Planned

    private fun targetOf(result: PlanningResult, id: String): PlacementTarget {
        return planned(result).placements.single { it.item == ItemId(id) }.target
    }

    private fun cellOf(result: PlanningResult, id: String): GridCell = (targetOf(result, id) as PlacementTarget.WorkspaceTarget).cell

    private fun pageIdOf(result: PlanningResult, id: String): String = ((targetOf(result, id) as PlacementTarget.WorkspaceTarget).page as PageRef).pageId.value

    /** Region first row for a `rows`-tall grid (bottom `ceil(rows/2)` rows). */
    private fun regionFirstRow(rows: Int) = rows - (rows + 1) / 2

    /**
     * Production-like materialization: workspace placements are written back,
     * created new pages become captured pages with their planned order. The
     * result feeds the replan (fixed-point) checks.
     */
    private fun materialize(
        items: List<CapturedItem>,
        basePages: List<Page>,
        result: PlanningResult,
    ): Pair<List<CapturedItem>, List<Page>> {
        val plannedResult = planned(result)
        val newPages = plannedResult.newPages
            .map { Page(PageId("np${it.ordinal.value}"), it.order) }
            .sortedBy { it.order.value }
        val newPageIdByOrdinal = plannedResult.newPages
            .associate { it.ordinal.value to PageId("np${it.ordinal.value}") }
        val targets = plannedResult.placements.associate { it.item to it.target }
        val mapped = items.map { item ->
            val target = targets.getValue(item.id)
            if (target is PlacementTarget.WorkspaceTarget) {
                val pageRef = when (val page = target.page) {
                    is PageRef -> page
                    is NewPageRef -> PageRef(newPageIdByOrdinal.getValue(page.ordinal.value))
                }
                item.copy(placement = CapturedPlacement.Workspace(pageRef, target.cell, target.span))
            } else {
                item
            }
        }
        return mapped to basePages + newPages
    }

    private fun intentInput(
        base: OrganizationInput,
        intents: Map<String, ItemIntent>,
    ): OrganizationInput {
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = base.snapshot, targets = base.targets, nowEpochMs = 1L),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val refs = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = intents.map { (id, itemIntent) -> itemIntent.copy(ref = refs.getValue(id)) },
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
        return base.copy(intentPreferences = IntentPlannerAdapter.project(validated))
    }

    @Test
    fun denseInputKeepsTheLowerRegionIdentity() {
        // Spec 398 evaluation scenario 1 (dense reset + all apps): 4×5 grids,
        // 20 apps per captured page (rows 0-3 fully occupied per page). The
        // lower region holds ceil(5/2)=3 rows, so the region strategy needs
        // new pages where canonical/global compaction fit into the captured
        // ones — the identities stay pairwise distinct on identical input.
        val rows = 5
        val regionFirst = regionFirstRow(rows)
        val dense = (0 until 40).map { index ->
            app(
                "a$index",
                x = index % 4,
                y = (index / 4) % 5,
                page = if (index < 20) "p0" else "p1",
            )
        }
        val canonicalResult = planner.plan(input(dense, 4, rows, canonical))
        val globalResult = planner.plan(input(dense, 4, rows, globalV2))
        val regionResult = planner.plan(input(dense, 4, rows, region))

        val canonicalPlanned = planned(canonicalResult)
        val globalPlanned = planned(globalResult)
        val regionPlanned = planned(regionResult)

        // Identity non-degeneracy: three pairwise-distinct outcomes on the
        // identical input — the strategy difference does not collapse into
        // item ordering alone.
        assertNotEquals(canonicalPlanned, globalPlanned)
        assertNotEquals(canonicalPlanned, regionPlanned)
        assertNotEquals(globalPlanned, regionPlanned)

        // Page growth: canonical and global compaction fit the captured
        // pages; the region-only sweep needs more pages. Page growth is a
        // legitimate outcome when it preserves the lower-region intent.
        assertEquals(0, canonicalPlanned.newPages.size)
        assertEquals(0, globalPlanned.newPages.size)
        assertTrue(regionPlanned.newPages.size > canonicalPlanned.newPages.size)

        // No movable placement lands above the region; every page keeps its
        // upper whitespace.
        for (placement in regionPlanned.placements) {
            val target = placement.target as? PlacementTarget.WorkspaceTarget ?: continue
            assertTrue(
                "movable unit ${placement.item} placed above the lower region",
                target.cell.y >= regionFirst,
            )
        }

        // Replan of the materialized result: empty diff.
        val (applied, appliedPages) = materialize(dense, pages(2), regionResult)
        val second = planner.plan(input(applied, 4, rows, region, pageList = appliedPages))
        assertTrue(planned(second).placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun fullRegionOverflowsToTheNextPageRegionNotUpperRows() {
        // 4×4 page with a fragmented lock in the region: free region cells =
        // (0,3),(1,3),(2,3),(0,2),(1,2),(2,2),(3,2) — 7. Twelve movable units
        // overflow to a new page's region; nothing lands on rows 0-1.
        val rows = 4
        val regionFirst = regionFirstRow(rows)
        val items = buildList {
            add(app("lock", 3, 3, locked = true))
            for (y in 0 until 3) {
                for (x in 0 until 4) {
                    add(app("a_${x}_$y", x, y))
                }
            }
        }
        val result = planner.plan(input(items, 4, rows, region, pageList = pages(1)))
        val plannedResult = planned(result)

        assertEquals(
            Disposition.Preserved(PreserveReason.LOCKED),
            plannedResult.placements.single { it.item == ItemId("lock") }.disposition,
        )
        assertEquals(1, plannedResult.newPages.size)

        var capturedRegionUnits = 0
        var newPageUnits = 0
        for (placement in plannedResult.placements) {
            val target = placement.target as? PlacementTarget.WorkspaceTarget ?: continue
            if (placement.item == ItemId("lock")) continue
            assertTrue(
                "movable unit ${placement.item} placed above the lower region",
                target.cell.y >= regionFirst,
            )
            if (target.page is PageRef) {
                capturedRegionUnits++
            } else {
                newPageUnits++
            }
        }
        assertEquals(7, capturedRegionUnits)
        assertEquals(5, newPageUnits)
    }

    @Test
    fun nonSquareMovablesAreStrategyPreservedAndNeverSweep() {
        // Mirror of the spec 182 GLOBAL_COMPACT_V1 counterexample: non-1×1
        // movables stay fixed with STRATEGY_PRESERVED; only 1×1 units sweep;
        // replan is empty.
        val items = listOf(
            app("pin", 1, 0, page = "p0", locked = true),
            app("wide", 0, 1, page = "p0", spanW = 2, spanH = 1),
            app("one", 2, 1, page = "p0"),
            app("tall", 0, 0, page = "p1", spanW = 1, spanH = 2),
        )
        val result = planner.plan(input(items, 3, 2))
        val plannedResult = planned(result)

        assertEquals(
            Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
            plannedResult.placements.single { it.item == ItemId("wide") }.disposition,
        )
        assertEquals(
            Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
            plannedResult.placements.single { it.item == ItemId("tall") }.disposition,
        )
        // The 1×1 unit keeps the only free region cell (rows=2 → region row 1).
        assertEquals(GridCell(2, 1), cellOf(result, "one"))
        assertTrue(plannedResult.newFolders.isEmpty())

        val (applied, appliedPages) = materialize(items, pages(2), result)
        val second = planner.plan(input(applied, 3, 2, pageList = appliedPages))
        assertTrue(planned(second).placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun existingFoldersAreStrategyPreservedAndNewFoldersFormFromCandidates() {
        // Existing folder stays fixed; the canonical grouping forms a new
        // folder from same-category 1×1 candidates and the folder unit is
        // placed after the sweeping singletons.
        val signals = ClassificationSignals(
            listOf(
                ClassificationSignal(ItemId("g1"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
                ClassificationSignal(ItemId("g2"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
            ),
        )
        val existingFolder = CapturedItem(
            id = ItemId("fold"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(FolderId("fold")),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p1")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = FolderId("fold"),
            members = listOf(ItemId("m1"), ItemId("m2")),
        )
        val folderMembers = listOf(
            CapturedItem(
                id = ItemId("m1"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.m1"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold")), 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("m2"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.m2"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId("fold")), 1),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val items = listOf(existingFolder) + folderMembers + listOf(
            app("g1", 1, 0, page = "p1"),
            app("g2", 2, 0, page = "p1"),
            app("s1", 3, 0, page = "p1"),
        )
        val base = input(items, 4, 4)
        val result = planner.plan(base.copy(signals = signals))
        val plannedResult = planned(result)

        assertEquals(
            Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
            plannedResult.placements.single { it.item == ItemId("fold") }.disposition,
        )
        assertEquals(1, plannedResult.newFolders.size)
        assertEquals(listOf(ItemId("g1"), ItemId("g2")), plannedResult.newFolders.single().members)
        // The singleton stream sweeps first: page p0 is the first captured
        // page and empty, so the remaining singleton s1 takes (0,3) there and
        // the formed folder takes the next free region cell (1,3) — folders
        // are placed after the units.
        val folderCell = (plannedResult.newFolders.single().workspacePlacement as PlacementTarget.WorkspaceTarget).cell
        assertEquals(GridCell(1, 3), folderCell)
        assertEquals("p0", pageIdOf(result, "s1"))
        assertEquals(GridCell(0, 3), cellOf(result, "s1"))
    }

    @Test
    fun existingSparsePagesSweepEarlierPageRegionsFirst() {
        // Spec 398 evaluation scenario 2: sparse pages sweep the earliest
        // page's region first; upper rows stay empty.
        val rows = 4
        val regionFirst = regionFirstRow(rows)
        val items = listOf(
            app("a", 0, 0, page = "p0"),
            app("b", 0, 0, page = "p1"),
            app("c", 0, 0, page = "p2"),
        )
        val result = planner.plan(input(items, 4, rows, region, pageList = pages(3)))
        val plannedResult = planned(result)

        assertEquals("p0", pageIdOf(result, "a"))
        assertEquals("p0", pageIdOf(result, "b"))
        assertEquals("p0", pageIdOf(result, "c"))
        for (id in listOf("a", "b", "c")) {
            assertTrue(cellOf(result, id).y >= regionFirst)
        }
        assertEquals(GridCell(0, 3), cellOf(result, "a"))
        assertEquals(GridCell(1, 3), cellOf(result, "b"))
        assertEquals(GridCell(2, 3), cellOf(result, "c"))
        // All three moved: even "a" left its upper-region captured cell for
        // the region sweep.
        assertEquals(3, plannedResult.placements.count { it.disposition is Disposition.Moved })
        assertTrue(plannedResult.newPages.isEmpty())

        val (applied, appliedPages) = materialize(items, pages(3), result)
        val second = planner.plan(input(applied, 4, rows, region, pageList = appliedPages))
        assertTrue(planned(second).placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun bottomAffinityIsNotAPageAffinity() {
        // Spec 398 evaluation scenario 3(c): REGION_AFFINITY BOTTOM biases the
        // consumption order only — it must not keep a later-page item on its
        // captured page while an earlier page's region has room.
        val items = listOf(
            app("anchor", 0, 3, page = "p0"),
            app("moved", 0, 3, page = "p2"),
        )
        val base = input(items, 4, 4, region, pageList = pages(3))
        val withIntent = intentInput(
            base,
            mapOf("moved" to ItemIntent(ref = "pending", regionAffinity = ExportRegionKind.BOTTOM)),
        )
        val result = planner.plan(withIntent)

        assertEquals("p0", pageIdOf(result, "moved"))
        assertEquals(GridCell(0, 3), cellOf(result, "moved"))
        assertEquals(GridCell(1, 3), cellOf(result, "anchor"))
    }

    @Test
    fun preserveHintKeepsTheCapturedCellWhenFreeAndNeverWorsensDisplacement() {
        // Spec 398: preserve is a soft captured-cell hint. A region-captured
        // item stays exactly (displacement 0 vs the moved no-preserve run); an
        // upper-region captured cell is deterministically ignored (fallback
        // identical to the no-preserve run).
        val rows = 4
        val regionFirst = regionFirstRow(rows)
        val lowerItems = listOf(
            app("anchor", 0, 3, page = "p0"),
            app("kept", 1, 3, page = "p1"),
        )
        val baseLower = input(lowerItems, 4, rows, region, pageList = pages(2))

        val withoutIntent = planner.plan(baseLower)
        assertEquals("p0", pageIdOf(withoutIntent, "kept"))

        val withIntent = planner.plan(
            intentInput(
                baseLower,
                mapOf("kept" to ItemIntent(ref = "pending", preserve = true)),
            ),
        )
        // The hint keeps "kept" at its captured cell (inside the region).
        assertEquals("p1", pageIdOf(withIntent, "kept"))
        assertEquals(GridCell(1, 3), cellOf(withIntent, "kept"))
        assertEquals(
            Disposition.Preserved(PreserveReason.ALREADY_CANONICAL),
            planned(withIntent).placements.single { it.item == ItemId("kept") }.disposition,
        )

        // Upper-region captured cell: the hint is deterministically ignored —
        // the run is identical to the no-preference run.
        val upperItems = listOf(
            app("anchor", 0, 3, page = "p0"),
            app("upper", 0, 0, page = "p1"),
        )
        val baseUpper = input(upperItems, 4, rows, region, pageList = pages(2))
        val plainUpper = planner.plan(baseUpper)
        val hintedUpper = planner.plan(
            intentInput(
                baseUpper,
                mapOf("upper" to ItemIntent(ref = "pending", preserve = true)),
            ),
        )
        assertEquals(
            planned(plainUpper).placements.single { it.item == ItemId("upper") }.target,
            planned(hintedUpper).placements.single { it.item == ItemId("upper") }.target,
        )
        assertTrue(regionFirst > 0)

        // Replan of the hinted lower run: empty diff (the hint reclaims its
        // own captured cell).
        val (applied, appliedPages) = materialize(lowerItems, pages(2), withIntent)
        val second = planner.plan(
            intentInput(
                input(applied, 4, rows, region, pageList = appliedPages),
                mapOf("kept" to ItemIntent(ref = "pending", preserve = true)),
            ),
        )
        assertTrue(planned(second).placements.none { it.disposition is Disposition.Moved })
    }

    @Test
    fun importanceBiasConsumesEarlierRegionCells() {
        // Spec 398 evaluation scenario 3(b): importance HIGH biases the
        // consumption order — HIGH items take the bottom row before the rest.
        val rows = 4
        val items = (0 until 6).map { index -> app("a$index", index % 4, index / 4) }
        val base = input(items, 4, rows, region, pageList = pages(1))
        val withIntent = intentInput(
            base,
            mapOf(
                "a0" to ItemIntent(ref = "pending", importance = Importance.HIGH),
                "a1" to ItemIntent(ref = "pending", importance = Importance.HIGH),
            ),
        )
        val result = planner.plan(withIntent)

        assertEquals(GridCell(0, 3), cellOf(result, "a0"))
        assertEquals(GridCell(1, 3), cellOf(result, "a1"))
        // The structural identity is untouched: nothing above the region.
        for (placement in planned(result).placements) {
            val target = placement.target as? PlacementTarget.WorkspaceTarget ?: continue
            assertTrue(target.cell.y >= regionFirstRow(rows))
        }
    }

    @Test
    fun regionIsDerivedFromDeviceRowsAcrossFormFactors() {
        // Spec 398 evaluation scenario 6: the region derives from
        // DeviceCapabilities.rows — no hardcoded row count, consistent
        // lower-region semantics across form factors.
        val formFactors = listOf(
            4 to 5, // portrait phone
            4 to 3, // landscape phone
            6 to 5, // tablet portrait
            6 to 4, // tablet landscape
        )
        for ((columns, rows) in formFactors) {
            val regionFirst = regionFirstRow(rows)
            assertEquals((rows + 1) / 2, rows - regionFirst)
            val items = (0 until 6).map { index -> app("a$index", index % columns, index / columns) }
            val result = planner.plan(input(items, columns, rows, region, pageList = pages(1)))
            for (placement in planned(result).placements) {
                val target = placement.target as? PlacementTarget.WorkspaceTarget ?: continue
                assertTrue(
                    "grid ${columns}x$rows: unit ${placement.item} placed above the derived region",
                    target.cell.y >= regionFirst,
                )
            }
        }
    }

    @Test
    fun scopeComposedCandidatesStayInsideTheRegionOrReportUnplaced() {
        // Spec 398: the scope-composed candidate tail honors the region — a
        // 1×1 candidate places into the region; a candidate the region cannot
        // fit (span taller than the region rows) is reported unplaced with
        // STRATEGY_SCOPE_FULL instead of breaking the shape.
        val rows = 4
        val regionFirst = regionFirstRow(rows)
        val items = listOf(app("resident", 0, 0, page = "p0"))
        val additions = listOf(
            CandidateItem(
                id = ItemId("c1"),
                profile = p0,
                kind = CandidateKind.APPLICATION,
                target = CandidateTarget.AppKey(ComponentKey("com.example.c1"), p0),
                availability = Availability.AVAILABLE,
                span = GridSpan(1, 1),
            ),
            CandidateItem(
                id = ItemId("big"),
                profile = p0,
                kind = CandidateKind.APPLICATION,
                target = CandidateTarget.AppKey(ComponentKey("com.example.big"), p0),
                availability = Availability.AVAILABLE,
                span = GridSpan(2, 3),
            ),
        )
        val existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }
        val composedInput = OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), device(4, rows), pages(1), items, emptyList()),
            rules = rules(region),
            taxonomy = taxonomy(),
            catalog = ActiveCategoryCatalog(taxonomy(), emptyList()),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(existing, additions),
            runMode = RunMode.ScopeComposedOrganization,
        )
        val result = planner.plan(composedInput)
        val plannedResult = planned(result)

        // The 1×1 candidate landed inside the region.
        val candidateTarget = plannedResult.placements.single { it.item == ItemId("c1") }.target
            as PlacementTarget.WorkspaceTarget
        assertTrue(candidateTarget.cell.y >= regionFirst)
        // The oversized candidate is unplaced, not placed outside the region.
        val unplaced = plannedResult.unplaced.single { it.item == ItemId("big") }
        assertEquals(UnplacedReason.STRATEGY_SCOPE_FULL, unplaced.reason)
    }

    /**
     * Spec 398 AC-5 transition case 1: run 1 hint failure (the preserved
     * captured cell is consumed by an earlier HIGH-class unit) falls back to
     * the sweep; after materialization the run 2 hint succeeds at the new
     * captured cell. The success set flips, the final assignment does not.
     */
    @Test
    fun runOneHintFailureFallsBackAndRunTwoHintSuccessKeepsTheFixedPoint() {
        val items = listOf(
            // HIGH class consumes before the un-biased preserve item.
            app("early", 1, 3),
            app("keeper", 0, 3),
        )
        val base = input(items, 4, 4, pageList = pages(1))
        val intent = intentInput(
            base,
            mapOf(
                "early" to ItemIntent(ref = "pending", importance = Importance.HIGH),
                "keeper" to ItemIntent(ref = "pending", preserve = true),
            ),
        )
        val first = planner.plan(intent)
        // Run 1: "early" (HIGH) sweeps to the first region cell — exactly the
        // keeper's captured cell — so the keeper's hint fails and it falls
        // back to the next region cell.
        assertEquals(GridCell(0, 3), cellOf(first, "early"))
        assertEquals(GridCell(1, 3), cellOf(first, "keeper"))
        assertEquals(
            Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
            planned(first).placements.single { it.item == ItemId("keeper") }.disposition,
        )

        val (applied, appliedPages) = materialize(items, pages(1), first)
        val second = planner.plan(
            intentInput(
                input(applied, 4, 4, pageList = appliedPages),
                mapOf(
                    "early" to ItemIntent(ref = "pending", importance = Importance.HIGH),
                    "keeper" to ItemIntent(ref = "pending", preserve = true),
                ),
            ),
        )
        assertTrue(planned(second).placements.none { it.disposition is Disposition.Moved })
        assertEquals(GridCell(0, 3), cellOf(second, "early"))
        assertEquals(GridCell(1, 3), cellOf(second, "keeper"))
    }

    /**
     * Spec 398 AC-5 transition case 2: a successful back-cell hint leaves a
     * front hole that the unit-phase-later formed folder occupies. Replan is
     * still an empty diff (the folder cell is disjoint from every unit cell).
     */
    @Test
    fun successfulBackHintLeavesAFrontHoleTheFormedFolderOccupies() {
        val signals = ClassificationSignals(
            listOf(
                ClassificationSignal(ItemId("g1"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
                ClassificationSignal(ItemId("g2"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
            ),
        )
        val items = listOf(
            app("keeper", 3, 3),
            app("g1", 0, 0),
            app("g2", 1, 0),
        )
        val base = input(items, 4, 4, pageList = pages(1)).copy(signals = signals)
        val intent = intentInput(
            base,
            mapOf("keeper" to ItemIntent(ref = "pending", preserve = true)),
        )
        val first = planner.plan(intent)
        // Run 1: the hint takes the back-most region cell; the g1/g2 pair
        // forms a folder whose unit is placed after the units — into the
        // front hole (0,3).
        assertEquals(GridCell(3, 3), cellOf(first, "keeper"))
        assertEquals(1, planned(first).newFolders.size)
        val folderCell = (planned(first).newFolders.single().workspacePlacement as PlacementTarget.WorkspaceTarget).cell
        assertEquals(GridCell(0, 3), folderCell)

        // Materialize via the production-like harness path: replanning the
        // formed-folder state must reproduce the assignment (IDEMPOTENCE).
        val fixture = PlannerFixture(
            id = FixtureId("398-front-hole-folder"),
            input = intent,
            expectation = FixtureExpectation(ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.IDEMPOTENCE, ContractCheck.DETERMINISM),
        )
        val report = PlannerContractHarness(DeterministicOrganizationPlanner()).verify(fixture)
        assertTrue("contract violations: ${report.violations}", report.violations.isEmpty())
    }

    /**
     * Spec 398 AC-5 transition case 3: within one bias class the run 2
     * processing order reverses (the preserve unit's back cell reads after
     * the fallback unit's front cell), yet every unit reclaims its run 1
     * cell — the class order, not the stream order, is the invariant.
     */
    @Test
    fun withinClassOrderSwapStillReclaimsEveryCell() {
        val items = listOf(
            app("keeper", 1, 3), // preserve hint onto c2
            app("mover", 2, 3), // fallback onto c1
        )
        val base = input(items, 4, 4, pageList = pages(1))
        val intent = intentInput(
            base,
            mapOf("keeper" to ItemIntent(ref = "pending", preserve = true)),
        )
        val first = planner.plan(intent)
        // Run 1: keeper hint-succeeds on c2=(1,3); mover falls back to the
        // front hole c1=(0,3). Materialized reverse-visual order now reads
        // mover before keeper.
        assertEquals(GridCell(1, 3), cellOf(first, "keeper"))
        assertEquals(GridCell(0, 3), cellOf(first, "mover"))

        val (applied, appliedPages) = materialize(items, pages(1), first)
        val second = planner.plan(
            intentInput(
                input(applied, 4, 4, pageList = appliedPages),
                mapOf("keeper" to ItemIntent(ref = "pending", preserve = true)),
            ),
        )
        assertTrue(planned(second).placements.none { it.disposition is Disposition.Moved })
        assertEquals(GridCell(1, 3), cellOf(second, "keeper"))
        assertEquals(GridCell(0, 3), cellOf(second, "mover"))
    }

    @Test
    fun formationInclusiveCounterexampleFixturePassesTheFullContractSuite() {
        // Spec 398 AC-5: the dedicated counterexample fixture the shared suite
        // does not exercise — multiple existing folders + new-folder
        // formation + non-1×1 movables + fragmented locks + multiple pages +
        // intent bias items (preserve in/out of the region, HIGH importance,
        // BOTTOM affinity). Every harness contract check, including
        // IDEMPOTENCE via the production-like materializer, must pass.
        val rows = 4
        val signals = ClassificationSignals(
            listOf(
                ClassificationSignal(ItemId("g1"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
                ClassificationSignal(ItemId("g2"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
                ClassificationSignal(ItemId("g3"), SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
            ),
        )
        fun folderItem(id: String, x: Int, y: Int, page: String, memberIds: List<String>) = CapturedItem(
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
        fun folderMember(id: String, folderId: String, rank: Int) = CapturedItem(
            id = ItemId(id),
            profile = p0,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
            placement = CapturedPlacement.FolderMember(FolderRef(FolderId(folderId)), rank),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val items = listOf(
            // Fragmented locks, one inside and one above the region.
            app("lockLow", 3, 3, page = "p0", locked = true),
            app("lockHigh", 0, 0, page = "p0", locked = true),
            // Existing folders on two pages.
            folderItem("fold1", 1, 1, "p0", listOf("fm1", "fm2")),
            folderItem("fold2", 2, 0, "p1", listOf("fm3", "fm4")),
            folderMember("fm1", "fold1", 0),
            folderMember("fm2", "fold1", 1),
            folderMember("fm3", "fold2", 0),
            folderMember("fm4", "fold2", 1),
            // Non-1×1 movables (strategy-fixed).
            app("wide", 1, 3, page = "p0", spanW = 2, spanH = 1),
            app("tall", 3, 0, page = "p1", spanW = 1, spanH = 2),
            // Formation candidates + plain units spread over the pages.
            app("g1", 0, 2, page = "p0"),
            app("g2", 1, 2, page = "p0"),
            app("g3", 2, 2, page = "p0"),
            app("s1", 0, 1, page = "p1"),
            app("s2", 1, 1, page = "p1"),
            app("hinted", 2, 3, page = "p1"),
        )
        val base = input(items, 4, rows, region).copy(signals = signals)
        val withIntent = intentInput(
            base,
            mapOf(
                "hinted" to ItemIntent(ref = "pending", preserve = true),
                "g1" to ItemIntent(ref = "pending", importance = Importance.HIGH, regionAffinity = ExportRegionKind.BOTTOM),
                "s1" to ItemIntent(ref = "pending", regionAffinity = ExportRegionKind.BOTTOM),
                "s2" to ItemIntent(ref = "pending", importance = Importance.LOW),
            ),
        )
        val fixture = PlannerFixture(
            id = FixtureId("398-bottom-region-counterexample"),
            input = withIntent,
            expectation = FixtureExpectation(
                ExpectedOutcome.Planned(
                    requiredPreservations = mapOf(
                        ItemId("lockLow") to PreserveReason.LOCKED,
                        ItemId("lockHigh") to PreserveReason.LOCKED,
                        ItemId("fold1") to PreserveReason.STRATEGY_PRESERVED,
                        ItemId("fold2") to PreserveReason.STRATEGY_PRESERVED,
                        ItemId("wide") to PreserveReason.STRATEGY_PRESERVED,
                        ItemId("tall") to PreserveReason.STRATEGY_PRESERVED,
                    ),
                ),
            ),
            checks = setOf(
                ContractCheck.EXPECTATION,
                ContractCheck.CONSERVATION,
                ContractCheck.BOUNDS,
                ContractCheck.NO_OVERLAP,
                ContractCheck.CONTAINER_INTEGRITY,
                ContractCheck.LOCK_PRESERVATION,
                ContractCheck.PROFILE_ISOLATION,
                ContractCheck.DETERMINISM,
                ContractCheck.IDEMPOTENCE,
            ),
        )
        val report = PlannerContractHarness(DeterministicOrganizationPlanner()).verify(fixture)
        assertTrue(
            "contract violations: ${report.violations}",
            report.violations.isEmpty(),
        )

        // Region identity on the fixture: nothing moved above the region.
        val first = planner.plan(withIntent)
        for (placement in planned(first).placements) {
            val target = placement.target as? PlacementTarget.WorkspaceTarget ?: continue
            if (placement.disposition is Disposition.Moved) {
                assertTrue(
                    "moved unit ${placement.item} placed above the lower region",
                    target.cell.y >= regionFirstRow(rows),
                )
            }
        }
    }
}
