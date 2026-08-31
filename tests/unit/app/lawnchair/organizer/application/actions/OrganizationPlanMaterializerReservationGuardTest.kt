package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.LayoutState
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
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrderingPolicy
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Orientation
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
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #185 / ADR-0010: the materializer's reservation guard accepts a target
 * that reproduces the item's captured workspace placement (the in-place
 * preservation the composer projects for a reservation-overlapping item) and
 * still rejects anything that moves into a reserved cell.
 */
class OrganizationPlanMaterializerReservationGuardTest {

    private val page = PageRef(PageId("p0"))
    private val reservation = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1))

    @Test
    fun inPlacePreservedTargetOverlappingReservationMaterializesReady() {
        val capturedCell = GridCell(2, 0)
        val (input, result, sourceState) = fixture(capturedCell, preserved = true)

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState)

        assertTrue("in-place preservation must pass the guard: $materialized", materialized is OrganizationPlanMaterializer.Result.Ready)
        val plan = (materialized as OrganizationPlanMaterializer.Result.Ready).plan
        val workspace = plan.intendedState.items.single().placement as app.lawnchair.organizer.application.public.PlacementState.Workspace
        assertEquals(capturedCell, workspace.cell)
    }

    @Test
    fun movedTargetIntoReservationStillInvalid() {
        val (input, result, sourceState) = fixture(capturedCell = GridCell(2, 1), preserved = false)

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState)

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    private fun fixture(
        capturedCell: GridCell,
        preserved: Boolean,
    ): Triple<OrganizationInput, PlanningResult, LayoutState> {
        val profile = ProfileId("personal")
        val target = TargetKey.AppKey(ComponentKey("com.example.qsb/.Main"), profile)

        val capturedItems = listOf(
            CapturedItem(
                id = ItemId("qsb"),
                profile = profile,
                kind = app.lawnchair.organizer.planning.ItemKind.APPLICATION,
                target = target,
                placement = CapturedPlacement.Workspace(page, capturedCell, GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val base = CanonicalFixtures.state()
        val sourceState = LayoutState(
            pages = base.pages,
            profiles = base.profiles,
            deviceCapabilities = base.deviceCapabilities,
            items = listOf(
                CanonicalFixtures.appItem(itemId = "qsb", cell = capturedCell, target = target),
            ),
        )
        val revision = RevisionCalculator.revisionOf(sourceState)
        val ruleVersion = RuleVersion("v1")
        val taxonomyVersion = TaxonomyVersion("tv1")

        val plannedTarget = PlacementTarget.WorkspaceTarget(page, GridCell(2, 0), GridSpan(1, 1))
        val disposition = if (preserved) {
            Disposition.Preserved(PreserveReason.RESERVED_REGION)
        } else {
            Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)
        }
        val result = PlanningResult(
            revision = revision,
            ruleVersion = ruleVersion,
            taxonomyVersion = taxonomyVersion,
            outcome = Planned(
                placements = listOf(PlannedPlacement(ItemId("qsb"), disposition, plannedTarget)),
                newPages = emptyList(),
                newFolders = emptyList(),
                categories = emptyList(),
                warnings = emptyList(),
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = revision,
                device = DeviceCapabilities(4, 5, 4, 4, 4, Orientation.PORTRAIT),
                pages = listOf(Page(PageId("p0"), PageOrder(0))),
                items = capturedItems,
                reservedWorkspaceRegions = listOf(reservation),
            ),
            rules = RuleSemantics(
                version = ruleVersion,
                folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                dockPolicy = DockPolicy.PRESERVE,
                overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                orderingPolicy = OrderingPolicy.CANONICAL_V1,
            ),
            taxonomy = TaxonomyContract(
                version = taxonomyVersion,
                allowedCategories = listOf(app.lawnchair.organizer.planning.CategoryId("tools")),
                fallbackCategory = app.lawnchair.organizer.planning.CategoryId("tools"),
            ),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(listOf(ExistingTargetMembership(ItemId("qsb"), ExistingRole.Preserved)), emptyList()),
            runMode = RunMode.FullOrganization,
        )
        return Triple(input, result, sourceState)
    }
}
