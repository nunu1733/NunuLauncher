package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.preview.PlanPreviewProjector
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
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
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
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
 * Issue #212 R2 characterization: the preview destination, the apply
 * destination, and the persisted write-set input must be one resolved
 * placement. The chain is exercised through the real seams —
 * [OrganizationPlanMaterializer] freezes the planner targets into the plan's
 * intended state, [PlanPreviewProjector] derives the rendered destination from
 * that same intended state, and [IntendedStateResolution] (the write-set
 * input) substitutes persistent identities only, never coordinates.
 *
 * F-03 shape: two items land on distinct anchors inside the same coarse band,
 * so their rendered destination labels collide while the persisted cells stay
 * exact and distinct.
 */
class PreviewApplyPlacementEqualityTest {

    @Test
    fun previewDestinationApplyDestinationAndWriteSetInputShareOneResolvedPlacement() {
        val fixture = fixture()

        val plan = when (
            val materialized = OrganizationPlanMaterializer.materialize(
                fixture.input,
                fixture.result,
                fixture.sourceState,
                app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
            )
        ) {
            is OrganizationPlanMaterializer.Result.Ready -> materialized.plan

            is OrganizationPlanMaterializer.Result.Invalid ->
                throw AssertionError("materializer rejected the fixture")
        }

        // Apply destination source of truth: the materializer froze the planner
        // targets into the intended state verbatim.
        assertEquals(GridCell(1, 0), intendedCell(plan, "a"))
        assertEquals(GridCell(1, 1), intendedCell(plan, "b"))

        val first = PlanPreviewProjector.project(plan, fixture.planned)
        val second = PlanPreviewProjector.project(plan, fixture.planned)
        val ready = first as PlanPreviewProjector.Result.Ready
        assertEquals("projection is deterministic", ready, second)

        val destinations = ready.details.changes
            .filterIsInstance<MoveChange>()
            .associate { it.item.value to it.destination as PreviewPosition.Workspace }
        // Both anchors sit inside the same coarse band on the same page: the
        // rendered destination labels collide (F-03 display), yet each derives
        // from its own intended cell — band TOP/LEFT, row ordinals 1 and 2.
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 1), destinations.getValue("a"))
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 2), destinations.getValue("b"))
        assertEquals(destinations.getValue("a").copy(rowOrdinal = destinations.getValue("b").rowOrdinal), destinations.getValue("b"))

        // Persisted placement: the write-set input resolves identities only —
        // every workspace cell passes through exactly as previewed.
        val resolved = IntendedStateResolution.resolveAndFinalize(plan.intendedState, emptyMap(), emptyMap())
        assertTrue("resolution must succeed", resolved != null)
        assertEquals(GridCell(1, 0), workspaceCell(resolved!!, "a"))
        assertEquals(GridCell(1, 1), workspaceCell(resolved!!, "b"))
    }

    private fun intendedCell(
        plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
        id: String,
    ): GridCell = (
        (plan.intendedState.items.single { it.ref == ApplicationItemRef.PersistentItem(ItemId(id)) })
            .placement as PlacementState.Workspace
        ).cell

    private fun workspaceCell(state: LayoutState, id: String): GridCell = (
        (state.items.single { it.ref == ApplicationItemRef.PersistentItem(ItemId(id)) })
            .placement as PlacementState.Workspace
        ).cell

    private class Fixture(
        val input: app.lawnchair.organizer.planning.OrganizationInput,
        val result: PlanningResult,
        val sourceState: LayoutState,
        val planned: Planned,
    )

    /** Two movable apps on one 4x6 page, planned onto distinct top-left-band anchors. */
    private fun fixture(): Fixture {
        val ruleVersion = RuleVersion("v2")
        val taxonomyVersion = TaxonomyVersion("tv1")
        val profile = ProfileId("personal")
        val page = PageRef(PageId("p0"))
        val ids = listOf("a", "b")
        val targets = mapOf(
            "a" to GridCell(1, 0),
            "b" to GridCell(1, 1),
        )

        val capturedItems = ids.map { id ->
            CapturedItem(
                id = ItemId(id),
                profile = profile,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), profile),
                placement = CapturedPlacement.Workspace(page, GridCell(0, ids.indexOf(id)), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }

        val placements = ids.map { id ->
            PlannedPlacement(
                item = ItemId(id),
                disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                target = PlacementTarget.WorkspaceTarget(page, targets.getValue(id), GridSpan(1, 1)),
            )
        }
        val planned = Planned(
            placements = placements,
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = emptyList(),
        )

        val sourceState = LayoutState(
            pages = listOf(PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0))),
            profiles = listOf(ProfileState(profile, ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 4, rows = 6),
            items = ids.map { id ->
                CanonicalFixtures.appItem(
                    itemId = id,
                    cell = GridCell(0, ids.indexOf(id)),
                    target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), profile),
                )
            },
        )
        val revision = RevisionCalculator.revisionOf(sourceState)
        val result = PlanningResult(
            revision = revision,
            ruleVersion = ruleVersion,
            taxonomyVersion = taxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = planned,
        )

        val input = app.lawnchair.organizer.planning.OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = revision,
                device = DeviceCapabilities(4, 6, 4, 4, 4, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
                pages = listOf(Page(PageId("p0"), PageOrder(0))),
                items = capturedItems,
            ),
            rules = RuleSemantics(
                version = ruleVersion,
                folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                dockPolicy = DockPolicy.PRESERVE,
                overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = TaxonomyContract(
                version = taxonomyVersion,
                allowedCategories = listOf(app.lawnchair.organizer.planning.CategoryId("tools")),
                fallbackCategory = app.lawnchair.organizer.planning.CategoryId("tools"),
            ),
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = ids.map { ExistingTargetMembership(ItemId(it), ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )

        return Fixture(input, result, sourceState, planned)
    }
}
