package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CandidateApplicationResolution
import app.lawnchair.organizer.application.protocol.CandidateApplicationResolver
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CandidateResolutionFailure
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.ImmutableByteString
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.NewFolderRef
import app.lawnchair.organizer.planning.OrganizationInput
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
 * Issue #228 spec §6: the candidate partition of the plan materializer —
 * typed create actions through the application-owned resolver, with no
 * partial adoption on failure.
 */
class OrganizationPlanMaterializerCandidateTest {

    private val profile = ProfileId("personal")
    private val candidateTarget = CandidateTarget.AppKey(ComponentKey("com.example.new/.Main"), profile)
    private val candidateId = CandidatePlanningIds.planningId(candidateTarget)

    private class FakeResolver(var resolution: CandidateApplicationResolution) : CandidateApplicationResolver {
        override fun resolve(target: CandidateTarget.AppKey): CandidateApplicationResolution = resolution
    }

    private fun readyResolution(title: String = "New App") = CandidateApplicationResolution.Ready(
        title = title,
        intentText = "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=com.example.new/.Main;end",
        icon = OptionalBytes.Present(ImmutableByteString.copyFrom(byteArrayOf(1, 2, 3))),
        itemAvailability = ItemAvailability.AVAILABLE,
    )

    @Test
    fun candidatePlacementMaterializesAsTypedInsertWithResolvedState() {
        val resolver = FakeResolver(readyResolution())
        val (input, result, sourceState) = fixture(candidatePlacement = PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(3, 3), GridSpan(1, 1)))

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, resolver)

        assertTrue("expected Ready: $materialized", materialized is OrganizationPlanMaterializer.Result.Ready)
        val plan = (materialized as OrganizationPlanMaterializer.Result.Ready).plan
        val insert = plan.actions.filterIsInstance<ApplyAction.Insert>().single()
        assertEquals(ApplicationItemRef.PlannedCandidate(candidateId), insert.ref)
        assertEquals(CanonicalItemKind.Application, insert.intended.kind)
        assertEquals(TargetKey.AppKey(candidateTarget.component, profile), insert.intended.targetKey)
        val workspace = insert.intended.placement as PlacementState.Workspace
        assertEquals(GridCell(3, 3), workspace.cell)
        assertEquals("New App", (insert.intended.title as app.lawnchair.organizer.application.public.OptionalText.Present).value)
        // The captured item keeps its own action (preserve against source).
        assertEquals(2, plan.actions.size)
        assertTrue(plan.actions.any { it is ApplyAction.Preserve || it is ApplyAction.Update })
    }

    @Test
    fun folderMemberCandidateBuildsPlannedFolderMembership() {
        val resolver = FakeResolver(readyResolution())
        val folderTarget = PlacementTarget.FolderMember(NewFolderRef(NewFolderOrdinal(0)), 0)
        val (input, result, sourceState) = fixture(candidatePlacement = folderTarget, withFolder = true)

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, StaticTitleResolver("Games"), resolver)

        assertTrue("expected Ready: $materialized", materialized is OrganizationPlanMaterializer.Result.Ready)
        val plan = (materialized as OrganizationPlanMaterializer.Result.Ready).plan
        val candidateInsert = plan.actions.filterIsInstance<ApplyAction.Insert>()
            .single { it.ref is ApplicationItemRef.PlannedCandidate }
        val folderChild = candidateInsert.intended.placement as PlacementState.FolderChild
        assertEquals(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folderChild.parent)
        assertEquals(0, folderChild.rank)
        val folderInsert = plan.actions.filterIsInstance<ApplyAction.Insert>()
            .single { it.ref is ApplicationItemRef.PlannedFolder }
        assertEquals(
            listOf(ApplicationItemRef.PlannedCandidate(candidateId)),
            (folderInsert.intended.structure as app.lawnchair.organizer.application.public.StructureState.FolderMembers).members.map { it.item },
        )
    }

    @Test
    fun unresolvableCandidateFailsClosedWithoutPartialAdoption() {
        val resolver = FakeResolver(CandidateApplicationResolution.Unavailable(CandidateResolutionFailure.COMPONENT_NOT_FOUND))
        val (input, result, sourceState) = fixture()

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, resolver)

        // Review P2 #2: the typed resolution failure survives to the caller so
        // the run can direct the user to re-detection (never partial adoption).
        assertEquals(
            OrganizationPlanMaterializer.Result.CandidateResolutionFailed(CandidateResolutionFailure.COMPONENT_NOT_FOUND),
            materialized,
        )
    }

    @Test
    fun blankResolvedTitleFailsClosed() {
        val resolver = FakeResolver(readyResolution(title = " "))
        val (input, result, sourceState) = fixture()

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, resolver)

        assertEquals(
            OrganizationPlanMaterializer.Result.CandidateResolutionFailed(CandidateResolutionFailure.LABEL_UNAVAILABLE),
            materialized,
        )
    }

    @Test
    fun nonAvailableResolutionFailsClosed() {
        val resolver = FakeResolver(readyResolution().copy(itemAvailability = ItemAvailability.DISABLED))
        val (input, result, sourceState) = fixture()

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, resolver)

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    @Test
    fun candidatePlanWithoutResolverFailsClosed() {
        val (input, result, sourceState) = fixture()

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, null)

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    @Test
    fun partiallyPlacedCandidatesMaterializeOnlyThePlacedOnes() {
        // Review P1 follow-up: the strategy's page/folder scope can leave
        // selected candidates unplaced (`Planned.unplaced`). The spec's
        // overflow contract makes them a reported warning — the materializer
        // must accept `placed ∪ unplaced == additions` (disjoint) and insert
        // only the placed ones.
        val secondTarget = CandidateTarget.AppKey(ComponentKey("com.example.second/.Main"), profile)
        val secondId = app.lawnchair.organizer.planning.CandidatePlanningIds.planningId(secondTarget)
        val second = app.lawnchair.organizer.planning.CandidateItem(
            id = secondId,
            profile = profile,
            kind = CandidateKind.APPLICATION,
            target = secondTarget,
            availability = Availability.AVAILABLE,
            span = GridSpan(1, 1),
        )
        val unplaced = app.lawnchair.organizer.planning.UnplacedItem(
            secondId,
            GridSpan(1, 1),
            app.lawnchair.organizer.planning.UnplacedReason.STRATEGY_SCOPE_FULL,
        )
        val (input, result, sourceState) = fixture(extraCandidate = second, extraUnplaced = unplaced)

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, FakeResolver(readyResolution()))

        assertTrue("expected Ready: $materialized", materialized is OrganizationPlanMaterializer.Result.Ready)
        val plan = (materialized as OrganizationPlanMaterializer.Result.Ready).plan
        val candidateInserts = plan.actions.filterIsInstance<ApplyAction.Insert>()
            .filter { it.ref is ApplicationItemRef.PlannedCandidate }
        assertEquals(listOf(ApplicationItemRef.PlannedCandidate(candidateId)), candidateInserts.map { it.ref })
        // The unplaced candidate appears nowhere in the intended state.
        assertTrue(plan.intendedState.items.none { it.ref == ApplicationItemRef.PlannedCandidate(secondId) })
    }

    @Test
    fun candidateNeitherPlacedNorUnplacedIsInvalid() {
        val secondTarget = CandidateTarget.AppKey(ComponentKey("com.example.second/.Main"), profile)
        val secondId = app.lawnchair.organizer.planning.CandidatePlanningIds.planningId(secondTarget)
        val second = app.lawnchair.organizer.planning.CandidateItem(
            id = secondId,
            profile = profile,
            kind = CandidateKind.APPLICATION,
            target = secondTarget,
            availability = Availability.AVAILABLE,
            span = GridSpan(1, 1),
        )
        // Declared addition with neither a placement nor an unplaced entry.
        val (input, result, sourceState) = fixture(extraCandidate = second)

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, FakeResolver(readyResolution()))

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    @Test
    fun nonStrategyScopeUnplacedEntriesAreInvalid() {
        // Only STRATEGY_SCOPE_FULL is a legitimate Planned-outcome unplaced
        // reason; anything else means the planner produced an inconsistent
        // artifact.
        val unplaced = app.lawnchair.organizer.planning.UnplacedItem(
            candidateId,
            GridSpan(1, 1),
            app.lawnchair.organizer.planning.UnplacedReason.TARGET_UNAVAILABLE,
        )
        val (input, result, sourceState) = fixture(candidatePlacement = null, extraUnplaced = unplaced)

        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, FakeResolver(readyResolution()))

        assertEquals(OrganizationPlanMaterializer.Result.Invalid, materialized)
    }

    @Test
    fun candidateFreePlanMaterializesIdenticallyWithoutAResolver() {
        // Regression: plans whose placements cover only captured items never
        // consult the candidate resolver (legacy seam keeps its behavior).
        val (input, result, sourceState) = fixture(candidatePlacement = null, withCandidate = false)

        val withNull = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, null)
        val withFake = OrganizationPlanMaterializer.materialize(input, result, sourceState, NoopTitleResolver, FakeResolver(readyResolution()))

        assertEquals(withFake, withNull)
        assertTrue(withNull is OrganizationPlanMaterializer.Result.Ready)
    }

    private object NoopTitleResolver : app.lawnchair.organizer.application.public.FolderTitleResolver {
        override fun resolve(naming: FolderNaming): String = "Folder"
    }

    private class StaticTitleResolver(private val title: String) : app.lawnchair.organizer.application.public.FolderTitleResolver {
        override fun resolve(naming: FolderNaming): String = title
    }

    private fun fixture(
        candidatePlacement: PlacementTarget? = PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(3, 3), GridSpan(1, 1)),
        withCandidate: Boolean = true,
        withFolder: Boolean = false,
        extraCandidate: app.lawnchair.organizer.planning.CandidateItem? = null,
        extraUnplaced: app.lawnchair.organizer.planning.UnplacedItem? = null,
    ): Triple<OrganizationInput, PlanningResult, LayoutState> {
        val capturedItems = listOf(
            CapturedItem(
                id = ItemId("1"),
                profile = profile,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.existing/.Main"), profile),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
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
                CanonicalFixtures.appItem(
                    itemId = "1",
                    profile = "personal",
                    target = TargetKey.AppKey(ComponentKey("com.example.existing/.Main"), profile),
                ),
            ),
        )
        val revision = RevisionCalculator.revisionOf(sourceState)
        val additions = if (withCandidate) {
            listOf(
                CandidateItem(
                    id = candidateId,
                    profile = profile,
                    kind = CandidateKind.APPLICATION,
                    target = candidateTarget,
                    availability = Availability.AVAILABLE,
                    span = GridSpan(1, 1),
                ),
            )
        } else {
            emptyList()
        } + listOfNotNull(extraCandidate)
        val placements = mutableListOf(
            PlannedPlacement(
                ItemId("1"),
                Disposition.Preserved(PreserveReason.LOCKED),
                capturedItems.single().placement.let { target ->
                    when (target) {
                        is CapturedPlacement.Workspace -> PlacementTarget.WorkspaceTarget(PageRef(target.page.pageId), target.cell, target.span)
                        else -> error("fixture only supports workspace")
                    }
                },
            ),
        )
        if (candidatePlacement != null) {
            placements += PlannedPlacement(candidateId, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), candidatePlacement)
        }
        val newFolders = if (withFolder) {
            listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = profile,
                    naming = FolderNaming.FromCategory(app.lawnchair.organizer.planning.CategoryId("GAMES")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(1, 3), GridSpan(1, 1)),
                    members = listOf(candidateId),
                ),
            )
        } else {
            emptyList()
        }
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(revision, base.deviceCapabilities.toPlannerCaps(), listOf(Page(PageId("p0"), PageOrder(0))), capturedItems),
            rules = RuleSemantics(
                version = RuleVersion("v2"),
                folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                dockPolicy = DockPolicy.PRESERVE,
                overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = TaxonomyContract(TaxonomyVersion("tv1"), listOf(app.lawnchair.organizer.planning.CategoryId("OTHER")), app.lawnchair.organizer.planning.CategoryId("OTHER")),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(capturedItems.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, additions),
            runMode = RunMode.ScopeComposedOrganization,
        )
        val result = PlanningResult(
            revision = revision,
            ruleVersion = input.rules.version,
            taxonomyVersion = input.taxonomy.version,
            organizationStrategy = input.rules.organizationStrategy,
            outcome = Planned(
                placements = placements,
                newPages = emptyList(),
                newFolders = newFolders,
                categories = emptyList(),
                warnings = emptyList(),
                unplaced = listOfNotNull(extraUnplaced),
            ),
        )
        return Triple(input, result, sourceState)
    }

    private fun app.lawnchair.organizer.application.public.DeviceCapabilities.toPlannerCaps() = app.lawnchair.organizer.planning.DeviceCapabilities(
        columns,
        rows,
        hotseatSlots,
        folderMaxColumns,
        folderMaxRows,
        app.lawnchair.organizer.planning.Orientation.PORTRAIT,
    )
}
