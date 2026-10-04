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
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewFolderRef
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
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertTrue

/**
 * Deterministic full-organization fixtures for Issue #164. The plans under
 * test are produced by the real [OrganizationPlanMaterializer] from an
 * `OrganizationInput` + `PlanningResult` pair — never hand-assembled — so the
 * oracles exercise the exact planner→materializer output shape, including the
 * materializer's append-last behavior for new folders.
 */
internal object NewFolderPlanFixtures {

    data class Fixture(
        val input: app.lawnchair.organizer.planning.OrganizationInput,
        val result: PlanningResult,
        val sourceState: LayoutState,
    )

    /**
     * Default-workspace shape from the issue: nine existing items with ids
     * 1..9 (planner order = byte order for existing items); items 8 and 9 move
     * into one new folder appended last by the materializer. Fixture identity
     * allocates folder id 10 (max row id 9 + 1), which byte-sorts between
     * "1" and "2" — the defect's mid-list shape.
     */
    fun singleFolder(): Fixture = fixture(
        ids = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"),
        folders = listOf(
            PlannedFolderSpec(
                ordinal = 0,
                workspaceCell = GridCell(1, 0),
                members = listOf("8", "9"),
            ),
        ),
    )

    /**
     * Multi-folder + id-boundary shape required by AC-164-03: five existing
     * items (ids 1, 9, 19, 91, 99) and two planned folders allocated at
     * max+1 (100, 101) so both byte-sort mid-list — covering the 99→100
     * boundary and two new folders mixing into one canonical order.
     */
    fun multiFolderWithBoundaryIds(): Fixture = fixture(
        ids = listOf("1", "9", "19", "91", "99"),
        folders = listOf(
            PlannedFolderSpec(ordinal = 0, workspaceCell = GridCell(1, 0), members = listOf("9", "19")),
            PlannedFolderSpec(ordinal = 1, workspaceCell = GridCell(1, 1), members = listOf("91", "99")),
        ),
    )

    /** Runs the real materializer; the oracles only ever consume its Ready plan. */
    fun materializeReady(
        fixture: Fixture,
        titleResolver: app.lawnchair.organizer.application.public.FolderTitleResolver = app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
    ): app.lawnchair.organizer.application.public.ValidatedLayoutPlan = when (
        val materialized =
            OrganizationPlanMaterializer.materialize(fixture.input, fixture.result, fixture.sourceState, titleResolver)
    ) {
        is OrganizationPlanMaterializer.Result.Ready -> materialized.plan

        is OrganizationPlanMaterializer.Result.Invalid ->
            throw AssertionError("materializer rejected the fixture")
    }

    data class PlannedFolderSpec(
        val ordinal: Int,
        val workspaceCell: GridCell,
        val members: List<String>,
    )

    private fun fixture(ids: List<String>, folders: List<PlannedFolderSpec>): Fixture {
        val ruleVersion = RuleVersion("v2")
        val taxonomyVersion = TaxonomyVersion("tv1")
        val profile = ProfileId("personal")
        val page = PageRef(PageId("p0"))

        val capturedItems = ids.mapIndexed { index, id ->
            CapturedItem(
                id = ItemId(id),
                profile = profile,
                kind = app.lawnchair.organizer.planning.ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.a$id/.Main"), profile),
                placement = CapturedPlacement.Workspace(page, GridCell(0, index), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }
        val folderByMember = folders.flatMap { folder -> folder.members.map { it to folder } }.toMap()
        val rankByMember = folders.flatMap { folder ->
            folder.members.mapIndexed { rank, member -> member to rank }
        }.toMap()

        val placements = ids.map { id ->
            val folder = folderByMember[id]
            val target = if (folder != null) {
                PlacementTarget.FolderMember(NewFolderRef(NewFolderOrdinal(folder.ordinal)), rankByMember.getValue(id))
            } else {
                PlacementTarget.WorkspaceTarget(page, GridCell(0, ids.indexOf(id)), GridSpan(1, 1))
            }
            val disposition = if (folder != null) {
                Disposition.Moved(PlacementCode.FOLDER_MEMBER)
            } else {
                Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
            }
            PlannedPlacement(ItemId(id), disposition, target)
        }

        val base = CanonicalFixtures.state()
        val sourceState = LayoutState(
            pages = base.pages,
            profiles = base.profiles,
            deviceCapabilities = base.deviceCapabilities,
            items = ids.map { id ->
                CanonicalFixtures.appItem(
                    itemId = id,
                    cell = GridCell(0, ids.indexOf(id)),
                    target = TargetKey.AppKey(ComponentKey("com.example.a$id/.Main"), profile),
                )
            },
        )
        // The materializer carries the snapshot revision into the plan's
        // sourceRevision, so the snapshot revision must be the canonical
        // revision of the source state the writer will capture (A2 check).
        val revision = RevisionCalculator.revisionOf(sourceState)
        val result = PlanningResult(
            revision = revision,
            ruleVersion = ruleVersion,
            taxonomyVersion = taxonomyVersion,
            organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Planned(
                placements = placements,
                newPages = emptyList(),
                newFolders = folders.map { folder ->
                    NewFolder(
                        ordinal = NewFolderOrdinal(folder.ordinal),
                        profile = profile,
                        naming = app.lawnchair.organizer.planning.FolderNaming.FromCategory(
                            app.lawnchair.organizer.planning.CategoryId("CATEGORY_${folder.ordinal}"),
                        ),
                        workspacePlacement = PlacementTarget.WorkspaceTarget(page, folder.workspaceCell, GridSpan(1, 1)),
                        members = folder.members.map { ItemId(it) },
                    )
                },
                categories = emptyList(),
                warnings = emptyList(),
            ),
        )

        val input = app.lawnchair.organizer.planning.OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = revision,
                device = DeviceCapabilities(4, 5, 4, 4, 4, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
                pages = listOf(Page(PageId("p0"), PageOrder(0))),
                items = capturedItems,
            ),
            rules = app.lawnchair.organizer.planning.RuleSemantics(
                version = ruleVersion,
                folderPolicy = FolderPolicy(2, app.lawnchair.organizer.planning.NewFolderProfileScope.SAME_PROFILE_ONLY),
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

        return Fixture(input, result, sourceState)
    }
}
