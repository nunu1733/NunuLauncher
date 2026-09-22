package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #398 AC-9: the preview projection must carry the BOTTOM_REGION_V1
 * spatial semantics — every movable destination row inside the lower
 * preferred region, zero upper-region destinations — plus the consequence
 * counts (new pages, cross-page moves, strategy-preserved), projected through
 * the same `PlanPreviewProjector` the confirmation UI consumes. The plan rows
 * come from the real planner over the public seam; only the executable-plan
 * wrapping follows the projector test harness.
 */
class PlanPreviewProjectorBottomRegionTest {

    private val planner = DeterministicOrganizationPlanner()
    private val region = StrategyId("BOTTOM_REGION_V1")
    private val p0 = ProfileId("p0")

    private fun rules(strategyId: StrategyId) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategyId,
    )

    private fun captured(
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
        target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun plannerInput(items: List<CapturedItem>): OrganizationInput = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(6, 6, 4, 4, 4, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items,
            emptyList(),
        ),
        rules = rules(region),
        taxonomy = TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")),
        catalog = ActiveCategoryCatalog(
            TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")),
            emptyList(),
        ),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun canonicalState(item: CapturedItem, page: ApplicationPageRef, cell: GridCell): CanonicalItemState = CanonicalFixtures.appItem(
        itemId = item.id.value,
        profile = "p0",
        page = page,
        cell = cell,
        span = (item.placement as CapturedPlacement.Workspace).span,
        target = item.target,
    )

    @Test
    fun bottomRegionPreviewDestinationsStayInsideTheLowerRegionWithFixedCounts() {
        // 6×6 grids: captured rows 0-1 of p0 (12 apps) plus two apps and one
        // 2×1 movable on p1. The region is the bottom ceil(6/2)=3 rows
        // (0-based rows 3-5): the 12 p0 units sweep into p0's region, and the
        // 2 p1 units pull forward into p0's remaining region cells
        // (cross-page moves); the 2×1 movable is STRATEGY_PRESERVED.
        val rows = 6
        val regionFirstRow = rows - (rows + 1) / 2
        val items = buildList {
            for (y in 0 until 2) {
                for (x in 0 until 6) {
                    add(captured("a_${x}_$y", x, y, page = "p0"))
                }
            }
            add(captured("t1", 0, 0, page = "p1"))
            add(captured("t2", 1, 0, page = "p1"))
            add(captured("wide", 0, 1, page = "p1", spanW = 2, spanH = 1))
        }
        val plannerResult = planner.plan(plannerInput(items))
        val plannedResult = plannerResult.outcome as Planned
        assertEquals(14, plannedResult.placements.count { it.disposition is Disposition.Moved })
        assertEquals(
            1,
            plannedResult.placements.count {
                it.disposition == Disposition.Preserved(app.lawnchair.organizer.planning.PreserveReason.STRATEGY_PRESERVED)
            },
        )

        // Wrap the real planner rows into the projector harness: source items
        // at their captured states, one Update action per Moved row.
        val capturedById = items.associateBy { it.id }
        val actions = plannedResult.placements.mapNotNull { placement ->
            placement.disposition as? Disposition.Moved ?: return@mapNotNull null
            val target = placement.target as PlacementTarget.WorkspaceTarget
            val targetPage = when (val page = target.page) {
                is PageRef -> ApplicationPageRef.PersistentPage(page.pageId)
                is app.lawnchair.organizer.planning.NewPageRef -> error("fixture has no new pages")
            }
            val capturedItem = capturedById.getValue(placement.item)
            val capturedWs = capturedItem.placement as CapturedPlacement.Workspace
            val expected = canonicalState(capturedItem, ApplicationPageRef.PersistentPage(capturedWs.page.pageId), capturedWs.cell)
            val intended = canonicalState(capturedItem, targetPage, target.cell)
            ApplyAction.Update(ApplicationItemRef.PersistentItem(capturedItem.id), expected, intended)
        }

        // Preserved rows need their Preserve actions (projector join contract).
        val preserveActions = plannedResult.placements.mapNotNull { placement ->
            val disposition = placement.disposition as? Disposition.Preserved ?: return@mapNotNull null
            if (disposition.reason == app.lawnchair.organizer.planning.PreserveReason.ALREADY_CANONICAL) return@mapNotNull null
            val item = capturedById.getValue(placement.item)
            val ws = item.placement as CapturedPlacement.Workspace
            ApplyAction.Preserve(
                ApplicationItemRef.PersistentItem(item.id),
                canonicalState(item, ApplicationPageRef.PersistentPage(ws.page.pageId), ws.cell),
            )
        }
        val allActions = actions + preserveActions
        val sourceItems = items.map { item ->
            val ws = item.placement as CapturedPlacement.Workspace
            canonicalState(item, ApplicationPageRef.PersistentPage(ws.page.pageId), ws.cell)
        }
        val sourceState = LayoutState(
            pages = listOf(
                PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p1")), PageOrder(1)),
            ),
            profiles = listOf(ProfileState(ProfileId("p0"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 6, rows = 6),
            items = sourceItems,
        )
        val plan = ValidatedLayoutPlan(
            sourceRevision = RevisionId("revision-1"),
            sourceState = sourceState,
            intendedState = sourceState,
            actions = allActions,
            newPages = plannedResult.newPages,
            newFolders = plannedResult.newFolders,
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("v1"),
        )

        val projection = PlanPreviewProjector.project(plan, plannedResult) as PlanPreviewProjector.Result.Ready

        // Spatial oracle: every movable destination inside the region, zero
        // above it.
        val moves = projection.details.changes.filterIsInstance<MoveChange>()
        assertEquals(14, moves.size)
        for (move in moves) {
            val destination = move.destination as PreviewPosition.Workspace
            assertTrue(
                "destination of ${move.item} above the lower region: $destination",
                destination.rowOrdinal >= regionFirstRow + 1,
            )
        }

        // Counts fixed to expected values.
        assertEquals(14, projection.details.counts.movedCount)
        assertEquals(0, projection.details.counts.newPageCount)
        assertEquals(2, projection.details.counts.crossPageMovedCount)
        assertEquals(1, projection.details.counts.preservedByStrategyCount)
    }
}
