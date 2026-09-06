package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.preview.PlanPreviewProjector
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyResult
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
 * Issue #212 R2, persisted leg: the destination the preview renders must be
 * the placement the apply commits and the post-apply workspace reports. The
 * chain runs through the real seams only — planner targets →
 * [OrganizationPlanMaterializer] → preview projection → [ApplyProtocol] →
 * post-apply capture rebuilt from persisted rows
 * ([FakeLayoutWriter] production-equivalent capture, so reads never echo the
 * write set's intended state).
 *
 * F-03 shape: both items land inside the same coarse band, so the rendered
 * destination labels collide while the committed cells stay distinct and
 * exact.
 */
class PreviewApplyPersistedPlacementEqualityTest {

    @Test
    fun previewDestinationApplyCommitAndPostApplyCaptureShareOneResolvedPlacement() {
        val fixture = fixture()

        val plan = when (
            val materialized = OrganizationPlanMaterializer.materialize(
                fixture.input,
                fixture.result,
                fixture.sourceState,
                RecordingFolderTitleResolver(),
            )
        ) {
            is OrganizationPlanMaterializer.Result.Ready -> materialized.plan

            is OrganizationPlanMaterializer.Result.Invalid ->
                throw AssertionError("materializer rejected the fixture")
        }

        // Preview destination: derived from the plan's intended placement.
        val preview = PlanPreviewProjector.project(plan, fixture.planned) as PlanPreviewProjector.Result.Ready
        val destinations = preview.details.changes
            .filterIsInstance<MoveChange>()
            .associate { it.item.value to it.destination as PreviewPosition.Workspace }
        // Same coarse band, same page, identical row ordinal — the F-03 display.
        assertEquals(
            PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 1),
            destinations.getValue("1"),
        )
        assertEquals(destinations.getValue("1"), destinations.getValue("2"))

        // Apply through the real protocol; the fake commits the write set and
        // rebuilds every later read from persisted rows.
        val writer = FakeLayoutWriter(fixture.sourceState).apply { productionEquivalentCapture = true }
        val protocol = ApplyProtocol(
            writer,
            FakeRecoveryStore { FakeClock.nowMillis() },
            FakeClock,
            FixedOperationIdSource(),
            RecordingFaultInjector(),
            RunMutex(),
        )
        val result = protocol.apply(plan)
        assertTrue("apply must succeed, got $result", result is ApplyResult.Applied)

        // Persisted placement: the post-apply capture must report the exact
        // previewed anchors — preview == apply == persisted, cell for cell.
        val persisted = writer.captureCurrent(CaptureId("issue212-post-apply"))
        assertEquals(GridCell(0, 0), workspaceCell(persisted.layoutState, "1"))
        assertEquals(GridCell(1, 0), workspaceCell(persisted.layoutState, "2"))
        assertEquals(GridCell(0, 0), workspaceCell(plan.intendedState, "1"))
        assertEquals(GridCell(1, 0), workspaceCell(plan.intendedState, "2"))

        // The rendered preview label is reproducible from the persisted cells:
        // recomputing the projection source from the post-apply capture lands
        // on the same band + row ordinal the user confirmed.
        (persisted.layoutState.items.map { it to destinations.getValue(refId(it)) }).forEach { (item, destination) ->
            val cell = (item.placement as PlacementState.Workspace).cell
            assertEquals(destination.rowOrdinal, cell.y + 1)
            assertEquals(destination.rowBand, band(cell.y, 6, RowBand.entries))
            assertEquals(destination.columnBand, band(cell.x, 4, ColumnBand.entries))
        }
    }

    private fun refId(item: app.lawnchair.organizer.application.public.CanonicalItemState): String = (item.ref as ApplicationItemRef.PersistentItem).itemId.value

    private fun workspaceCell(state: LayoutState, id: String): GridCell = (
        (state.items.single { it.ref == ApplicationItemRef.PersistentItem(ItemId(id)) })
            .placement as PlacementState.Workspace
        ).cell

    private fun <B : Enum<B>> band(coordinate: Int, dimension: Int, bands: List<B>): B = bands[((coordinate * 3) / dimension).coerceIn(0, bands.size - 1)]

    private class Fixture(
        val input: app.lawnchair.organizer.planning.OrganizationInput,
        val result: PlanningResult,
        val sourceState: LayoutState,
        val planned: Planned,
    )

    /** Two movable apps on one 4x6 page, planned onto distinct in-band anchors. */
    private fun fixture(): Fixture {
        val ruleVersion = RuleVersion("v2")
        val taxonomyVersion = TaxonomyVersion("tv1")
        val profile = ProfileId("personal")
        val page = PageRef(PageId("p0"))
        val ids = listOf("1", "2")
        val targets = mapOf(
            "1" to GridCell(0, 0),
            "2" to GridCell(1, 0),
        )

        val capturedItems = ids.map { id ->
            CapturedItem(
                id = ItemId(id),
                profile = profile,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), profile),
                placement = CapturedPlacement.Workspace(page, GridCell(0, ids.indexOf(id) + 3), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }

        val planned = Planned(
            placements = ids.map { id ->
                PlannedPlacement(
                    item = ItemId(id),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(page, targets.getValue(id), GridSpan(1, 1)),
                )
            },
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
                    cell = GridCell(0, ids.indexOf(id) + 3),
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
