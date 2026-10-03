package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreviewDiagramPageRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ProposalExclusionKey
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
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
 * Issue #508: the pure before/after diagram projection — `before` from the
 * plan's `sourceState`, `after` from its `intendedState` (planned pages,
 * folders, and candidates included), typed planned references, folder member
 * counts, dock order, reserved regions, the exclusion surface, determinism,
 * and the fail-closed join violations.
 */
class PlanDiagramProjectorTest {

    private val candidateKey = CandidateTarget.AppKey(ComponentKey("com.candidate/.Main"), ProfileId("personal"))

    private fun candidateItem() = CandidateItem(
        id = CandidatePlanningIds.planningId(candidateKey),
        profile = ProfileId("personal"),
        kind = CandidateKind.APPLICATION,
        target = candidateKey,
        availability = Availability.AVAILABLE,
        span = GridSpan(1, 1),
    )

    private fun input(targets: TargetSet): OrganizationInput = OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("revision-1"),
            device = DeviceCapabilities(6, 6, 5, 3, 4, Orientation.PORTRAIT),
            pages = listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items = emptyList(),
        ),
        rules = RuleSemantics(
            RuleVersion("v2"),
            FolderPolicy(2, app.lawnchair.organizer.planning.NewFolderProfileScope.SAME_PROFILE_ONLY),
            DockPolicy.PRESERVE,
            OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        ),
        taxonomy = TaxonomyContract(TaxonomyVersion("v1"), listOf(CategoryId("other")), CategoryId("other")),
        catalog = app.lawnchair.organizer.planning.ActiveCategoryCatalog(
            TaxonomyContract(TaxonomyVersion("v1"), listOf(CategoryId("other")), CategoryId("other")),
            emptyList(),
        ),
        signals = ClassificationSignals(emptyList()),
        targets = targets,
        runMode = RunMode.FullOrganization,
    )

    private fun targets(
        existing: List<ExistingTargetMembership>,
        additions: List<CandidateItem> = emptyList(),
    ) = TargetSet(existing, additions)

    private fun item(
        id: String,
        cell: GridCell = GridCell(0, 0),
        page: ApplicationPageRef = ApplicationPageRef.PersistentPage(PageId("p0")),
        kind: CanonicalItemKind = CanonicalItemKind.Application,
        structure: StructureState = StructureState.Plain,
    ): CanonicalItemState = CanonicalFixtures.appItem(
        itemId = id,
        title = OptionalText.Present("T$id"),
        page = page,
        cell = cell,
        kind = kind,
        structure = structure,
    )

    private fun plannedFolder(
        ordinal: Int,
        cell: GridCell,
        page: ApplicationPageRef,
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(ordinal)),
        kind = CanonicalItemKind.Folder,
        targetKey = TargetKey.FolderKey(FolderId("planned-folder-$ordinal")),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Workspace(page, cell, GridSpan(1, 1)),
        title = OptionalText.Present("Folder"),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.FolderMembers(emptyList()),
    )

    private fun plannedCandidate(cell: GridCell, page: ApplicationPageRef): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PlannedCandidate(candidateItem().id),
        kind = CanonicalItemKind.Application,
        targetKey = TargetKey.AppKey(ComponentKey("com.candidate/.Main"), ProfileId("personal")),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Workspace(page, cell, GridSpan(1, 1)),
        title = OptionalText.Present("Candidate"),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.Plain,
    )

    private fun plan(
        sourceItems: List<CanonicalItemState>,
        intendedItems: List<CanonicalItemState>,
        actions: List<ApplyAction>,
        intendedPages: List<Pair<NewPageOrdinal, Int>> = emptyList(),
        reserved: List<ReservedWorkspaceRegion> = emptyList(),
        newFolders: List<app.lawnchair.organizer.planning.NewFolder> = emptyList(),
    ): ValidatedLayoutPlan {
        val sourceState = LayoutState(
            pages = listOf(
                PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p1")), PageOrder(1)),
            ),
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 6, rows = 6),
            items = sourceItems,
            reservedWorkspaceRegions = reserved,
        )
        val intendedState = LayoutState(
            pages = listOf(
                PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p1")), PageOrder(1)),
            ) + intendedPages.map { (ordinal, order) ->
                PageState(ApplicationPageRef.PlannedPage(ordinal), PageOrder(order))
            },
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 6, rows = 6),
            items = intendedItems,
            reservedWorkspaceRegions = reserved,
        )
        return ValidatedLayoutPlan(
            sourceRevision = RevisionId("revision-1"),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = actions,
            newPages = intendedPages.map { (ordinal, order) -> app.lawnchair.organizer.planning.NewPage(ordinal, PageOrder(order)) },
            newFolders = newFolders,
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("v1"),
        )
    }

    @Test
    fun beforeAndAfterProjectTheSamePlanStatesWithTypedPlannedReferences() {
        val folder = plannedFolder(0, GridCell(2, 2), ApplicationPageRef.PlannedPage(NewPageOrdinal(0)))
        val candidate = plannedCandidate(GridCell(0, 0), ApplicationPageRef.PlannedPage(NewPageOrdinal(0)))
        val sourceApp = item("a", cell = GridCell(0, 0))
        val intendedItems = listOf(
            sourceApp.copy(placement = PlacementState.Workspace(ApplicationPageRef.PersistentPage(PageId("p1")), GridCell(0, 0), GridSpan(1, 1))),
            folder,
            candidate,
        )
        val plan = plan(
            sourceItems = listOf(sourceApp),
            intendedItems = intendedItems,
            actions = listOf(
                ApplyAction.Update(
                    ApplicationItemRef.PersistentItem(ItemId("a")),
                    sourceApp,
                    intendedItems.first(),
                ),
                ApplyAction.Insert(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folder),
                ApplyAction.Insert(ApplicationItemRef.PlannedCandidate(candidateItem().id), candidate),
            ),
            intendedPages = listOf(NewPageOrdinal(0) to 2),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    naming = app.lawnchair.organizer.planning.FolderNaming.FromCategory(CategoryId("other")),
                    workspacePlacement = app.lawnchair.organizer.planning.PlacementTarget.WorkspaceTarget(
                        app.lawnchair.organizer.planning.PageRef(PageId("p1")),
                        GridCell(2, 2),
                        GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a")),
                ),
            ),
        )

        val result = PlanDiagramProjector.project(plan, input(targets(listOf(ExistingTargetMembership(ItemId("a"), ExistingRole.Movable)), listOf(candidateItem())))) as PlanDiagramProjector.Result.Ready

        // Before: two persistent pages, the app on p0.
        assertEquals(2, result.diagrams.before.pages.size)
        assertTrue(result.diagrams.before.pages.all { it.ref is PreviewDiagramPageRef.Persistent })
        assertEquals(1, result.diagrams.before.pages.first().items.size)

        // After: three pages — the planned page is typed, never a persistent id.
        assertEquals(3, result.diagrams.after.pages.size)
        val plannedPage = result.diagrams.after.pages.last()
        assertEquals(PreviewDiagramPageRef.Planned(NewPageOrdinal(0)), plannedPage.ref)
        // The moved app went to p1; only the planned folder and the candidate
        // land on the new page.
        assertEquals(2, plannedPage.items.size)
        val folderItem = plannedPage.items.first { it.kind is CanonicalItemKind.Folder }
        assertEquals(PreviewLabel.Named("Folder"), folderItem.label)
        assertEquals(0, folderItem.memberCount)
        val candidateDiagramItem = plannedPage.items.first { it.kind is CanonicalItemKind.Application && it.label is PreviewLabel.Named && it.label.value == "Candidate" }
        assertEquals(GridCell(0, 0), candidateDiagramItem.cell)
    }

    @Test
    fun dockItemsSortByRankAndFolderMembersCountThroughTheFolder() {
        val dockLow = CanonicalFixtures.appItem(
            itemId = "dock2",
            title = OptionalText.Present("Tdock2"),
            cell = GridCell(0, 0),
        ).copy(placement = PlacementState.Dock(2))
        val dockHigh = CanonicalFixtures.appItem(
            itemId = "dock0",
            title = OptionalText.Present("Tdock0"),
            cell = GridCell(0, 0),
        ).copy(placement = PlacementState.Dock(0))
        val folder = item(
            "folder",
            cell = GridCell(1, 0),
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(
                listOf(
                    app.lawnchair.organizer.application.public.RankedMember(ApplicationItemRef.PersistentItem(ItemId("m1")), 0),
                    app.lawnchair.organizer.application.public.RankedMember(ApplicationItemRef.PersistentItem(ItemId("m2")), 1),
                ),
            ),
        )
        val plan = plan(
            sourceItems = listOf(dockLow, dockHigh, folder),
            intendedItems = listOf(dockLow, dockHigh, folder),
            actions = listOf(
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("dock2")), dockLow),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("dock0")), dockHigh),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("folder")), folder),
            ),
        )

        val result = PlanDiagramProjector.project(
            plan,
            input(targets(listOf(ExistingTargetMembership(ItemId("folder"), ExistingRole.Movable)))),
        ) as PlanDiagramProjector.Result.Ready

        assertEquals(listOf("Tdock0", "Tdock2"), result.diagrams.before.dockItems.map { label -> (label.label as PreviewLabel.Named).value })
        val folderItem = result.diagrams.before.pages
            .first { it.ref == PreviewDiagramPageRef.Persistent(PageId("p0")) }
            .items
            .first { it.kind is CanonicalItemKind.Folder }
        assertEquals(2, folderItem.memberCount)
    }

    @Test
    fun reservedRegionsProjectOnTheirPersistentPage() {
        val reservation = ReservedWorkspaceRegion(
            page = app.lawnchair.organizer.planning.PageRef(PageId("p0")),
            cell = GridCell(0, 0),
            span = GridSpan(4, 1),
        )
        val plan = plan(sourceItems = emptyList(), intendedItems = emptyList(), actions = emptyList(), reserved = listOf(reservation))

        val result = PlanDiagramProjector.project(plan, input(targets(emptyList()))) as PlanDiagramProjector.Result.Ready

        assertEquals(1, result.diagrams.before.reservedRegions.size)
        assertEquals(PreviewDiagramPageRef.Persistent(PageId("p0")), result.diagrams.before.reservedRegions.single().page)
        assertEquals(GridSpan(4, 1), result.diagrams.before.reservedRegions.single().span)
    }

    @Test
    fun exclusionSurfaceCoversMovableTopLevelAppsAndPlacedCandidates() {
        val sourceApp = item("a", cell = GridCell(0, 0))
        val folder = item("folder", cell = GridCell(1, 0), kind = CanonicalItemKind.Folder)
        val candidate = plannedCandidate(GridCell(0, 0), ApplicationPageRef.PersistentPage(PageId("p1")))
        val plan = plan(
            sourceItems = listOf(sourceApp, folder),
            intendedItems = listOf(sourceApp, folder, candidate),
            actions = listOf(ApplyAction.Insert(ApplicationItemRef.PlannedCandidate(candidateItem().id), candidate)),
        )
        val runInput = input(
            targets(
                listOf(
                    ExistingTargetMembership(ItemId("a"), ExistingRole.Movable),
                    ExistingTargetMembership(ItemId("folder"), ExistingRole.Movable),
                ),
                listOf(candidateItem()),
            ),
        )

        val result = PlanDiagramProjector.project(plan, runInput) as PlanDiagramProjector.Result.Ready

        assertEquals(
            setOf(
                ProposalExclusionKey.Existing(ItemId("a")),
                ProposalExclusionKey.Candidate(candidateItem().id),
            ),
            result.excludableItems.map { it.key }.toSet(),
        )
        assertTrue(result.excludableItems.first { it.key is ProposalExclusionKey.Candidate }.isCandidate)
        assertEquals(PreviewLabel.Named("Ta"), result.excludableItems.first { it.key is ProposalExclusionKey.Existing }.label)
    }

    @Test
    fun projectionIsDeterministic() {
        val sourceApp = item("a", cell = GridCell(0, 0))
        val plan = plan(
            sourceItems = listOf(sourceApp),
            intendedItems = listOf(sourceApp),
            actions = listOf(ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("a")), sourceApp)),
        )
        val runInput = input(targets(listOf(ExistingTargetMembership(ItemId("a"), ExistingRole.Movable))))

        val first = PlanDiagramProjector.project(plan, runInput)
        val second = PlanDiagramProjector.project(plan, runInput)

        assertEquals(first, second)
    }

    @Test
    fun aPlannedPageInTheSourceStateFailsClosed() {
        val sourceApp = item("a", cell = GridCell(0, 0), page = ApplicationPageRef.PlannedPage(NewPageOrdinal(0)))
        val plan = plan(sourceItems = listOf(sourceApp), intendedItems = listOf(sourceApp), actions = emptyList())

        val result = PlanDiagramProjector.project(plan, input(targets(emptyList())))

        assertEquals(PlanDiagramProjector.Result.Invalid, result)
    }

    @Test
    fun aMovableMemberMissingFromTheSourceStateFailsClosed() {
        val plan = plan(sourceItems = emptyList(), intendedItems = emptyList(), actions = emptyList())

        val result = PlanDiagramProjector.project(
            plan,
            input(targets(listOf(ExistingTargetMembership(ItemId("ghost"), ExistingRole.Movable)))),
        )

        assertEquals(PlanDiagramProjector.Result.Invalid, result)
    }

    @Test
    fun anUnplacedCandidateStaysExcludableWithItsPlanningKind() {
        // D-3: the additions set is the authoritative candidate surface — an
        // unplaced (overflow) candidate must stay excludable so the user can
        // remove it from the next replan even though this plan never places it.
        val plan = plan(sourceItems = emptyList(), intendedItems = emptyList(), actions = emptyList())

        val result = PlanDiagramProjector.project(plan, input(targets(emptyList(), listOf(candidateItem())))) as PlanDiagramProjector.Result.Ready

        val entry = result.excludableItems.single { it.key is ProposalExclusionKey.Candidate }
        assertTrue(entry.isCandidate)
        assertEquals(CanonicalItemKind.Application, entry.kind)
        assertEquals(PreviewLabel.KindFallback(CanonicalItemKind.Application), entry.label)
    }
}
