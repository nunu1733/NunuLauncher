package app.lawnchair.organizer.planning

import app.lawnchair.organizer.personalization.PersonalizationSignalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Issue #508: the pure derivation from a run's base input to a replanning
 * input — role flips for excluded existing members, additions/signal removal
 * for excluded candidates, base-derived determinism (an exclusion plus its
 * reversal reproduces the base input exactly), and typed rejection of keys
 * outside the base excludable surface.
 */
class ProposalExclusionDerivationTest {

    private fun baseInput(): OrganizationInput {
        val app = CapturedItem(
            id = ItemId("app"),
            profile = ProfileId("0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("app/.Main"), ProfileId("0")),
            placement = CapturedPlacement.Workspace(PageRef(PageId("page")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val folder = CapturedItem(
            id = ItemId("folder"),
            profile = ProfileId("0"),
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(FolderId("folder")),
            placement = CapturedPlacement.Workspace(PageRef(PageId("page")), GridCell(1, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        return OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("revision"),
                device = DeviceCapabilities(4, 5, 5, 3, 4, Orientation.PORTRAIT),
                pages = listOf(Page(PageId("page"), PageOrder(0))),
                items = listOf(app, folder),
            ),
            rules = RuleSemantics(
                RuleVersion("v2"),
                FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                DockPolicy.PRESERVE,
                OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = TaxonomyContract(TaxonomyVersion("v1"), listOf(CategoryId("other")), CategoryId("other")),
            catalog = ActiveCategoryCatalog(
                TaxonomyContract(TaxonomyVersion("v1"), listOf(CategoryId("other")), CategoryId("other")),
                emptyList(),
            ),
            signals = ClassificationSignals(
                listOf(
                    ClassificationSignal(ItemId("app"), SignalSource.S2, CategoryIdentity.BuiltIn(CategoryId("other"))),
                    ClassificationSignal(CandidatePlanningIds.planningId(candidateAppKey()), SignalSource.S6, CategoryIdentity.BuiltIn(CategoryId("other"))),
                ),
            ),
            targets = TargetSet(
                existing = listOf(
                    ExistingTargetMembership(ItemId("app"), ExistingRole.Movable),
                    ExistingTargetMembership(ItemId("folder"), ExistingRole.Movable),
                ),
                additions = listOf(candidateItem()),
            ),
            runMode = RunMode.ScopeComposedOrganization,
            personalization = PersonalizationSignalSnapshot.unavailable(),
        )
    }

    private fun candidateAppKey() = CandidateTarget.AppKey(ComponentKey("candidate/.Main"), ProfileId("0"))

    private fun candidateItem() = CandidateItem(
        id = CandidatePlanningIds.planningId(candidateAppKey()),
        profile = ProfileId("0"),
        kind = CandidateKind.APPLICATION,
        target = candidateAppKey(),
        availability = Availability.AVAILABLE,
        span = GridSpan(1, 1),
    )

    @Test
    fun excludableKeysCoverMovableTopLevelAppsAndCandidatesOnly() {
        val keys = ProposalExclusionDerivation.excludableKeys(baseInput())

        assertEquals(
            setOf(ProposalExclusionKey.Existing(ItemId("app")), ProposalExclusionKey.Candidate(candidateItem().id)),
            keys,
        )
    }

    @Test
    fun excludingAnExistingMemberFlipsOnlyItsRoleToPreserved() {
        val base = baseInput()

        val derived = ProposalExclusionDerivation.derive(base, setOf(ProposalExclusionKey.Existing(ItemId("app"))))

        val ready = derived as ProposalExclusionDerivation.Result.Ready
        assertEquals(
            listOf(
                ExistingTargetMembership(ItemId("app"), ExistingRole.Preserved),
                ExistingTargetMembership(ItemId("folder"), ExistingRole.Movable),
            ),
            ready.input.targets.existing,
        )
        // Everything else is shared with the base input, never recomputed.
        assertSame(base.snapshot, ready.input.snapshot)
        assertSame(base.rules, ready.input.rules)
        assertEquals(base.runMode, ready.input.runMode)
    }

    @Test
    fun excludingACandidateRemovesItAndItsSignalEntryTogether() {
        val base = baseInput()
        val candidateId = candidateItem().id

        val derived = ProposalExclusionDerivation.derive(base, setOf(ProposalExclusionKey.Candidate(candidateId)))

        val ready = derived as ProposalExclusionDerivation.Result.Ready
        assertEquals(emptyList<CandidateItem>(), ready.input.targets.additions)
        assertEquals(
            listOf(ItemId("app")),
            ready.input.signals.entries.map { it.item },
        )
    }

    @Test
    fun emptyExclusionSetReturnsTheBaseInputItself() {
        val base = baseInput()

        val derived = ProposalExclusionDerivation.derive(base, emptySet())

        assertSame(base, (derived as ProposalExclusionDerivation.Result.Ready).input)
    }

    @Test
    fun restoringEveryExclusionReproducesTheBaseInput() {
        val base = baseInput()
        val exclusions = setOf(ProposalExclusionKey.Existing(ItemId("app")), ProposalExclusionKey.Candidate(candidateItem().id))
        val excluded = ProposalExclusionDerivation.derive(base, exclusions) as ProposalExclusionDerivation.Result.Ready

        val restored = ProposalExclusionDerivation.derive(base, emptySet<ProposalExclusionKey>()) as ProposalExclusionDerivation.Result.Ready

        assertEquals(base.targets, restored.input.targets)
        assertEquals(base.signals, restored.input.signals)
        // And the derivation from the base is deterministic: deriving the same
        // exclusions twice produces equal inputs (never derived-from-derived).
        val again = ProposalExclusionDerivation.derive(base, exclusions) as ProposalExclusionDerivation.Result.Ready
        assertEquals(excluded.input.targets, again.input.targets)
        assertEquals(excluded.input.signals, again.input.signals)
    }

    @Test
    fun aKeyOutsideTheBaseSurfaceFailsClosed() {
        val base = baseInput()
        val folderKey = ProposalExclusionKey.Existing(ItemId("folder"))

        val derived = ProposalExclusionDerivation.derive(base, setOf(folderKey))

        assertEquals(ProposalExclusionDerivation.Result.Invalid, derived)
    }
}
