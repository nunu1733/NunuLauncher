package app.lawnchair.organizer.planning

import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.IntentPlannerAdapter
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 (PR review P1-1): the planner consumes the accepted intent's
 * preference as a deterministic ordering/placement bias, while preservation
 * (`determinePreservation`), the allocator constraints, and strategy semantics
 * are never weakened by the intent.
 */
class IntentPreferenceConsumptionTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val p0 = ProfileId("p0")

    private fun defaultRules() = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
    )

    private fun defaultTaxonomy() = TaxonomyContract(
        TaxonomyVersion("tv1"),
        listOf(CategoryId("OTHER"), CategoryId("GAMES")),
        CategoryId("OTHER"),
    )

    private fun app(id: String, x: Int = 0, y: Int = 0, page: String = "p0", locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun baseInput(items: List<CapturedItem>): OrganizationInput = OrganizationInput(
        snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(6, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1))),
            items,
        ),
        rules = defaultRules(),
        taxonomy = defaultTaxonomy(),
        catalog = ActiveCategoryCatalog(defaultTaxonomy(), emptyList()),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun buildExport(input: OrganizationInput): app.lawnchair.organizer.personalization.BuiltExport = ContextExportBuilder.build(
        ExportInputs(snapshot = input.snapshot, targets = input.targets, nowEpochMs = 1L),
        PrivacyTier.LOCAL_FULL,
        SequentialIdAllocator(),
    )

    private fun withIntent(input: OrganizationInput, itemIntents: List<ItemIntent>): Pair<OrganizationInput, ValidatedPersonalizedIntent> {
        val built = buildExport(input)
        val refs = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val mappedIntents = itemIntents.map { itemIntent ->
            itemIntent.copy(
                ref = refs.getValue(itemIntent.ref),
                desiredGroupRefs = itemIntent.desiredGroupRefs?.map { refs.getValue(it) },
            )
        }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = mappedIntents,
            unresolvedRefs = built.export.items.map { it.ref }.filter { ref -> mappedIntents.none { it.ref == ref } },
        )
        val validation = app.lawnchair.organizer.personalization.IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = 2L,
            currentStructuralDigest = built.session.sourceContextDigest,
        )
        val validated = when (validation) {
            is app.lawnchair.organizer.personalization.IntentValidation.Validated -> validation.validated

            is app.lawnchair.organizer.personalization.IntentValidation.Failure ->
                throw IllegalStateException("intent validation failed: ${validation.failure}")
        }
        checkNotNull(validated)
        return input.copy(intentPreferences = IntentPlannerAdapter.project(validated)) to validated
    }

    @Test
    fun importanceOrderingIsDeterministicallyReflectedInPlacement() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val input = baseInput(items)

        val plain = planner.plan(input).outcome as Planned

        val highB = withIntent(
            input,
            listOf(
                ItemIntent(ref = "b", importance = app.lawnchair.organizer.personalization.Importance.HIGH),
                ItemIntent(ref = "a", importance = app.lawnchair.organizer.personalization.Importance.LOW),
            ),
        ).first
        val biased = planner.plan(highB).outcome as Planned

        // The different accepted intent produces a different, deterministic
        // singleton ordering on the page: b (HIGH) takes the first cell.
        val cellOf = { planned: Planned, item: ItemId ->
            (planned.placements.first { it.item == item }.target as PlacementTarget.WorkspaceTarget).cell
        }
        assertNotEquals(
            cellOf(plain, ItemId("b")),
            cellOf(biased, ItemId("b")),
        )
        assertEquals(GridCell(0, 0), cellOf(biased, ItemId("b")))

        // Same accepted intent + same inputs → same plan (NFR-003).
        assertEquals(biased, planner.plan(highB).outcome as Planned)
    }

    @Test
    fun pageAffinityPreferenceMovesThePlacementPage() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 0, y = 1))
        val input = baseInput(items)

        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "a", pageAffinity = 1),
                ItemIntent(ref = "b"),
            ),
        ).first
        val planned = planner.plan(inputWithIntent).outcome as Planned

        val aPlacement = planned.placements.first { it.item == ItemId("a") }
        val target = aPlacement.target as PlacementTarget.WorkspaceTarget
        assertEquals(PageRef(PageId("p1")), target.page)
    }

    @Test
    fun intentNeverWeakensPreservationConstraints() {
        val items = listOf(app("a", x = 0, y = 0), app("locked", x = 1, y = 0, locked = true))
        val input = baseInput(items)

        val withoutIntent = planner.plan(input).outcome as Planned
        val with = withIntent(
            input,
            // The locked item is NOT addressable by the intent (export
            // mobility FIXED + validator reject); it stays in unresolvedRefs.
            listOf(
                ItemIntent(ref = "a", pageAffinity = 1),
            ),
        ).first
        val withPlan = planner.plan(with).outcome as Planned

        val lockedPlacement = withPlan.placements.first { it.item == ItemId("locked") }
        assertTrue(lockedPlacement.disposition is Disposition.Preserved)
        // The locked item is not moved by the intent's page preference.
        val lockedTarget = lockedPlacement.target as PlacementTarget.WorkspaceTarget
        assertEquals(PageRef(PageId("p0")), lockedPlacement.target.let { (it as PlacementTarget.WorkspaceTarget).page })
        assertEquals(
            withoutIntent.placements.first { it.item == ItemId("locked") }.disposition,
            lockedPlacement.disposition,
        )
    }

    @Test
    fun regionAffinityPreferenceDeterministicallyReordersWithinThePage() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val input = baseInput(items)
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "b", regionAffinity = app.lawnchair.organizer.personalization.ExportRegionKind.TOP),
                ItemIntent(ref = "a"),
            ),
        ).first
        val biased = planner.plan(inputWithIntent).outcome as Planned
        // TOP-affinity b takes the first cell; the plan is deterministic.
        val cellOf = { planned: Planned, item: ItemId ->
            (planned.placements.first { it.item == item }.target as PlacementTarget.WorkspaceTarget).cell
        }
        assertEquals(GridCell(0, 0), cellOf(biased, ItemId("b")))
        val plain = planner.plan(input).outcome as Planned
        assertNotEquals(cellOf(plain, ItemId("b")), cellOf(biased, ItemId("b")))
    }

    @Test
    fun sparsePreserveKeepsTheTargetItemAtItsCapturedCell() {
        // PR review 4 P1-1 counterexample: in a sparse layout the plain plan
        // moves z from (5,0) to (2,0); preserve=true on z must NOT worsen that
        // displacement — z keeps its captured cell via the allocation hint.
        val items = listOf(app("a", x = 0, y = 0), app("z", x = 5, y = 0), app("b", x = 0, y = 1))
        val input = baseInput(items)
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "z", preserve = true),
                ItemIntent(ref = "a"),
                ItemIntent(ref = "b"),
            ),
        ).first
        val biased = planner.plan(inputWithIntent).outcome as Planned
        val cellOf = { planned: Planned, item: String ->
            (planned.placements.first { it.item == ItemId(item) }.target as PlacementTarget.WorkspaceTarget).cell
        }
        // The preserved item keeps its captured cell.
        assertEquals(GridCell(5, 0), cellOf(biased, "z"))
        // It is not farther from the captured cell than the plain run.
        val plainDistance = kotlin.math.abs(cellOf(planner.plan(input).outcome as Planned, "z").x - 5)
        val biasedDistance = kotlin.math.abs(cellOf(biased, "z").x - 5)
        assertTrue(biasedDistance <= plainDistance)
    }

    @Test
    fun oneDirectionalGroupCohesivesAlongsideAnotherGroup() {
        // PR review 4 P1-2: A -> [Z] is a valid one-directional declaration;
        // the referenced member Z must cohere with A even without a reciprocal
        // declaration, and a second group (M <-> N) must not interleave.
        val items = listOf(
            app("a", x = 0, y = 0),
            app("z", x = 1, y = 0),
            app("m", x = 2, y = 0),
            app("n", x = 3, y = 0),
        )
        val input = baseInput(items).copy(
            rules = defaultRules().copy(
                folderPolicy = FolderPolicy(5, NewFolderProfileScope.SAME_PROFILE_ONLY),
            ),
        )
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "a", desiredGroupRefs = listOf("z")),
                ItemIntent(ref = "m", desiredGroupRefs = listOf("n")),
                ItemIntent(ref = "n", desiredGroupRefs = listOf("m")),
            ),
        ).first
        val biased = planner.plan(inputWithIntent).outcome as Planned
        val cellOf = { planned: Planned, item: String ->
            (planned.placements.first { it.item == ItemId(item) }.target as PlacementTarget.WorkspaceTarget).cell
        }
        val aX = cellOf(biased, "a").x
        val zX = cellOf(biased, "z").x
        val mX = cellOf(biased, "m").x
        val nX = cellOf(biased, "n").x
        assertTrue((aX == zX + 1) || (zX == aX + 1))
        assertTrue((mX == nX + 1) || (nX == mX + 1))
        assertEquals(biased, planner.plan(inputWithIntent).outcome as Planned)
    }

    @Test
    fun globalMinimizeMovementAloneKeepsCapturedLayout() {
        val items = listOf(app("a", x = 1, y = 0), app("b", x = 0, y = 0))
        val input = baseInput(items)
        val built = buildExport(input)
        val refs = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = built.export.items.map { ItemIntent(ref = it.ref) },
            globalPreference = app.lawnchair.organizer.personalization.GlobalPreference(minimizeMovement = true),
        )
        val validation = app.lawnchair.organizer.personalization.IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = 2L,
            currentStructuralDigest = built.session.sourceContextDigest,
        )
        val validated = when (validation) {
            is app.lawnchair.organizer.personalization.IntentValidation.Validated -> validation.validated

            is app.lawnchair.organizer.personalization.IntentValidation.Failure ->
                throw IllegalStateException("intent validation failed: ${validation.failure}")
        }
        val biased = planner.plan(input.copy(intentPreferences = IntentPlannerAdapter.project(validated))).outcome as Planned
        // Global minimizeMovement alone reproduces the captured layout.
        assertEquals(GridCell(1, 0), (biased.placements.first { it.item == ItemId("a") }.target as PlacementTarget.WorkspaceTarget).cell)
        assertEquals(GridCell(0, 0), (biased.placements.first { it.item == ItemId("b") }.target as PlacementTarget.WorkspaceTarget).cell)
    }

    @Test
    fun regionAffinityBandsOrderTopMiddleBottom() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0), app("c", x = 2, y = 0))
        val input = baseInput(items)
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "a", regionAffinity = app.lawnchair.organizer.personalization.ExportRegionKind.BOTTOM),
                ItemIntent(ref = "b", regionAffinity = app.lawnchair.organizer.personalization.ExportRegionKind.MIDDLE),
                ItemIntent(ref = "c", regionAffinity = app.lawnchair.organizer.personalization.ExportRegionKind.TOP),
            ),
        ).first
        val biased = planner.plan(inputWithIntent).outcome as Planned
        // The region hint places each unit in its requested band (rows are
        // thirds of the 6-row grid: TOP 0-1, MIDDLE 2-3, BOTTOM 4-5).
        val bandOf = { planned: Planned, item: String ->
            (planned.placements.first { it.item == ItemId(item) }.target as PlacementTarget.WorkspaceTarget).cell.y
        }
        assertTrue(bandOf(biased, "c") < 2) // TOP band
        assertTrue(bandOf(biased, "b") in 2..3) // MIDDLE band
        assertTrue(bandOf(biased, "a") >= 4) // BOTTOM band
    }

    @Test
    fun multipleDistinctGroupsCohereWithinEachGroup() {
        val items = listOf(
            app("a", x = 0, y = 0),
            app("b", x = 1, y = 0),
            app("m", x = 2, y = 0),
            app("n", x = 3, y = 0),
        )
        val input = baseInput(items).copy(
            rules = defaultRules().copy(
                folderPolicy = FolderPolicy(5, NewFolderProfileScope.SAME_PROFILE_ONLY),
            ),
        )
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "a", desiredGroupRefs = listOf("b")),
                ItemIntent(ref = "b", desiredGroupRefs = listOf("a")),
                ItemIntent(ref = "m", desiredGroupRefs = listOf("n")),
                ItemIntent(ref = "n", desiredGroupRefs = listOf("m")),
            ),
        ).first
        val biased = planner.plan(inputWithIntent).outcome as Planned
        val cellOf = { planned: Planned, item: String ->
            (planned.placements.first { it.item == ItemId(item) }.target as PlacementTarget.WorkspaceTarget).cell
        }
        // Each group's members are cell-adjacent: {a,b} and {m,n} do not interleave.
        val aX = cellOf(biased, "a").x
        val bX = cellOf(biased, "b").x
        val mX = cellOf(biased, "m").x
        val nX = cellOf(biased, "n").x
        assertTrue((aX == bX + 1) || (bX == aX + 1))
        assertTrue((mX == nX + 1) || (nX == mX + 1))
        // Deterministic reproduction.
        assertEquals(biased, planner.plan(inputWithIntent).outcome as Planned)
    }

    @Test
    fun standaloneGroupSemanticConsumesThroughFolderPlacementSemantics() {
        // Two apps classified as fallback but grouped by the intent's
        // groupSemantic.category (a taxonomy-allowed category): the existing
        // folder placement semantics form one new folder for them.
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val input = baseInput(items)
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(
                    ref = "a",
                    groupSemantic = app.lawnchair.organizer.personalization.GroupSemantic(
                        category = "GAMES",
                        freeText = null,
                    ),
                ),
                ItemIntent(
                    ref = "b",
                    groupSemantic = app.lawnchair.organizer.personalization.GroupSemantic(
                        category = "GAMES",
                        freeText = null,
                    ),
                ),
            ),
        ).first
        val planned = planner.plan(inputWithIntent).outcome as Planned
        // The new folder carries both members (existing strategy semantics,
        // not a new intent-side folder mechanism).
        assertEquals(1, planned.newFolders.size)
        val members = planned.newFolders.first().members
        assertTrue(ItemId("a") in members && ItemId("b") in members)
        // Deterministic reproduction.
        assertEquals(planned, planner.plan(inputWithIntent).outcome as Planned)
    }

    @Test
    fun desiredGroupCohesionIsReflectedInPlacementOrder() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0), app("c", x = 2, y = 0))
        // High folder threshold: the intent group must express as ordering
        // cohesion (adjacent placement), not strategy folder formation.
        val input = baseInput(items).copy(
            rules = defaultRules().copy(
                folderPolicy = FolderPolicy(5, NewFolderProfileScope.SAME_PROFILE_ONLY),
            ),
        )
        val inputWithIntent = withIntent(
            input,
            listOf(
                ItemIntent(ref = "a", desiredGroupRefs = listOf("c")),
                ItemIntent(ref = "b"),
                ItemIntent(ref = "c", desiredGroupRefs = listOf("a")),
            ),
        ).first
        val biased = planner.plan(inputWithIntent).outcome as Planned
        // Group members a and c are co-ordered: they occupy adjacent cells.
        val cellOf = { planned: Planned, item: String ->
            (planned.placements.first { it.item == ItemId(item) }.target as PlacementTarget.WorkspaceTarget).cell
        }
        assertTrue((cellOf(biased, "a").x == cellOf(biased, "c").x + 1) || (cellOf(biased, "c").x == cellOf(biased, "a").x + 1))
        // Deterministic reproduction.
        assertEquals(biased, planner.plan(inputWithIntent).outcome as Planned)
    }

    @Test
    fun preserveAndMinimizeMovementPreferencesReduceDisplacement() {
        // Captured order: b at (0,0), a at (1,0) — the canonical (profile,
        // category, id) ordering moves BOTH items into swapped cells; the
        // movement-minimization bias reproduces the captured layout instead.
        val items = listOf(app("a", x = 1, y = 0), app("b", x = 0, y = 0))
        val input = baseInput(items)
        val built = buildExport(input)
        val refs = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = refs.getValue("b"), preserve = true)),
            unresolvedRefs = built.export.items.map { it.ref }.filter { ref -> ref != refs.getValue("b") },
            globalPreference = app.lawnchair.organizer.personalization.GlobalPreference(minimizeMovement = true),
        )
        val validation = app.lawnchair.organizer.personalization.IntentValidator.validate(
            intent = intent,
            export = built.export,
            session = built.session,
            nowEpochMs = 2L,
            currentStructuralDigest = built.session.sourceContextDigest,
        )
        val validated = when (validation) {
            is app.lawnchair.organizer.personalization.IntentValidation.Validated -> validation.validated

            is app.lawnchair.organizer.personalization.IntentValidation.Failure ->
                throw IllegalStateException("intent validation failed: ${validation.failure}")
        }
        val inputWithIntent = input.copy(intentPreferences = IntentPlannerAdapter.project(validated))
        val biased = planner.plan(inputWithIntent).outcome as Planned
        val cellOf = { planned: Planned, item: ItemId ->
            (planned.placements.first { it.item == item }.target as PlacementTarget.WorkspaceTarget).cell
        }
        val plain = planner.plan(input).outcome as Planned

        // The preserve target stays at its captured cell (never drifts farther).
        assertEquals(GridCell(0, 0), cellOf(biased, ItemId("b")))
        // The movement-minimization bias keeps every item at its captured cell.
        assertEquals(GridCell(1, 0), cellOf(biased, ItemId("a")))
        // Plain plan (without the preference) would have swapped both items.
        assertNotEquals(cellOf(plain, ItemId("b")), cellOf(biased, ItemId("b")))
        assertNotEquals(cellOf(plain, ItemId("a")), cellOf(biased, ItemId("a")))
        // Deterministic reproduction.
        assertEquals(biased, planner.plan(inputWithIntent).outcome as Planned)
    }

    private fun orderedSingletonItems(planned: Planned): List<ItemId> = planned.placements
        .filter { it.target is PlacementTarget.WorkspaceTarget && it.disposition is Disposition.Moved }
        .sortedWith(
            compareBy({ (it.target as PlacementTarget.WorkspaceTarget).page.toString() }, { (it.target as PlacementTarget.WorkspaceTarget).cell.y }),
        )
        .map { it.item }
}
