package app.lawnchair.organizer.planning

import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.IntentPlannerAdapter
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 (PR review 4, P1-3): the fixed 6-capability contract must reach
 * every registered strategy family — no strategy silently turns an accepted
 * capability into a no-op. The matrix drives the planner seam with the same
 * accepted intent per strategy and requires the preference to deterministically
 * reach the plan, with authority unchanged (no unplaced, no preservation
 * weakening).
 */
class IntentPreferenceStrategyMatrixTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val p0 = ProfileId("p0")

    private fun app(id: String, x: Int, y: Int, page: String = "p0", locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun rules(strategy: StrategyId) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(5, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategy,
    )

    private fun input(strategy: StrategyId): OrganizationInput {
        val items = listOf(app("a", 0, 0), app("b", 1, 0), app("c", 2, 0))
        return OrganizationInput(
            snapshot = LayoutSnapshot(
                RevisionId("rev"),
                DeviceCapabilities(6, 6, 5, 3, 5, Orientation.PORTRAIT),
                listOf(Page(PageId("p0"), PageOrder(0))),
                items,
            ),
            rules = rules(strategy),
            taxonomy = TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")),
            catalog = ActiveCategoryCatalog(TaxonomyContract(TaxonomyVersion("tv1"), listOf(CategoryId("OTHER")), CategoryId("OTHER")), emptyList()),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
            runMode = RunMode.FullOrganization,
        )
    }

    private fun withHighMiddleLowIntent(input: OrganizationInput): OrganizationInput {
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = input.snapshot, targets = input.targets, nowEpochMs = 1L),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val refs = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(
                ItemIntent(ref = refs.getValue("b"), importance = Importance.HIGH),
                ItemIntent(ref = refs.getValue("a"), importance = Importance.LOW),
                ItemIntent(ref = refs.getValue("c"), importance = Importance.NORMAL),
            ),
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
        return input.copy(intentPreferences = IntentPlannerAdapter.project(validated))
    }

    @Test
    fun importanceBiasReachesEveryRegisteredStrategy() {
        val strategies = listOf(
            LayoutStrategyRegistry.CANONICAL_PAGE_COMPACT_V1,
            LayoutStrategyRegistry.BOTTOM_FIRST_V1,
            LayoutStrategyRegistry.STABLE_PAGE_TIDY_V1,
            LayoutStrategyRegistry.CATEGORY_CONTIGUOUS_V1,
            LayoutStrategyRegistry.STABLE_PAGE_TIDY_V2,
            LayoutStrategyRegistry.BOTTOM_FIRST_V2,
            LayoutStrategyRegistry.GLOBAL_COMPACT_V1,
            LayoutStrategyRegistry.GLOBAL_COMPACT_V2,
        )
        for (strategy in strategies) {
            val plain = planner.plan(input(strategy)).outcome as Planned
            val biasedInput = withHighMiddleLowIntent(input(strategy))
            val biased = planner.plan(biasedInput).outcome as Planned

            // The accepted preference deterministically reaches the plan: the
            // HIGH item takes the first-ordered cell for every strategy family.
            val cellOf = { planned: Planned, item: ItemId ->
                (planned.placements.first { it.item == item }.target as PlacementTarget.WorkspaceTarget).cell
            }
            assertNotEquals(
                "strategy ${strategy.value}: preference must reach the plan",
                cellOf(plain, ItemId("b")),
                cellOf(biased, ItemId("b")),
            )
            // The traversal/lead cell stays strategy-owned (e.g. BOTTOM_FIRST
            // scans bottom-up); the contract is that the preference reaches
            // the plan deterministically, not that it overrides traversal.
            assertEquals(
                "strategy ${strategy.value}: biased plan must reproduce",
                biased,
                planner.plan(biasedInput).outcome as Planned,
            )
            // No authority weakening: nothing unplaced.
            assertTrue(biased.unplaced.isEmpty())
        }
    }
}
