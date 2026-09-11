package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #228 (D-2): the `ScopeComposedOrganization` run mode — full
 * re-organization of the captured layout plus the selected candidates as
 * incremental-style units, in one allocator.
 */
class ScopeComposedPlannerTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val p0 = ProfileId("p0")

    private fun defaultDevice(
        columns: Int = 4,
        rows: Int = 4,
        hotseatSlots: Int = 4,
        folderMaxColumns: Int = 4,
        folderMaxRows: Int = 4,
    ) = DeviceCapabilities(columns, rows, hotseatSlots, folderMaxColumns, folderMaxRows, Orientation.PORTRAIT)

    private fun defaultRules(strategy: StrategyId = StrategyId("CANONICAL_PAGE_COMPACT_V1"), minGroupSize: Int = 2) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(minGroupSize, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategy,
    )

    private fun defaultTaxonomy(
        allowed: List<CategoryId> = listOf(CategoryId("OTHER"), CategoryId("GAMES"), CategoryId("TOOLS")),
        fallback: CategoryId = CategoryId("OTHER"),
    ) = TaxonomyContract(TaxonomyVersion("tv1"), allowed, fallback)

    private fun app(id: String, x: Int = 0, y: Int = 0, page: String = "p0") = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun candidate(id: String) = CandidateItem(
        id = ItemId(id),
        profile = p0,
        kind = CandidateKind.APPLICATION,
        target = CandidateTarget.AppKey(ComponentKey("com.example.$id"), p0),
        availability = Availability.AVAILABLE,
        span = GridSpan(1, 1),
    )

    private fun input(
        items: List<CapturedItem>,
        additions: List<CandidateItem>,
        signals: List<ClassificationSignal> = emptyList(),
        device: DeviceCapabilities = defaultDevice(),
        rules: RuleSemantics = defaultRules(),
    ): OrganizationInput {
        val existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }
        return OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), device, listOf(Page(PageId("p0"), PageOrder(0))), items),
            rules = rules,
            taxonomy = defaultTaxonomy(),
            signals = ClassificationSignals(signals),
            targets = TargetSet(existing, additions),
            runMode = RunMode.ScopeComposedOrganization,
        )
    }

    private fun fullVariant(input: OrganizationInput) = input.copy(runMode = RunMode.FullOrganization)

    @Test
    fun emptyAdditionsReproduceTheFullOrganizationRun() {
        val items = listOf(
            app("a", x = 0, y = 0),
            app("b", x = 3, y = 3),
            app("c", x = 1, y = 2),
        )

        val composed = planner.plan(input(items, additions = emptyList()))
        val full = planner.plan(fullVariant(input(items, additions = emptyList())))

        assertEquals((full.outcome as Planned), composed.outcome as Planned)
    }

    @Test
    fun fullOrganizationStillRejectsAdditions() {
        val items = listOf(app("a"))
        val composed = planner.plan(fullVariant(input(items, additions = listOf(candidate("c1")))))

        val outcome = composed.outcome as Rejected.Invalid
        assertTrue(outcome.reasons.any { it.code == RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION })
    }

    @Test
    fun candidatesPlaceIntoTheComposedLayoutWithoutOverlap() {
        val items = listOf(
            app("a", x = 0, y = 0),
            app("b", x = 1, y = 0),
        )
        val additions = (0 until 6).map { candidate("c$it") }

        val planned = (planner.plan(input(items, additions)).outcome as Planned)

        // Every captured item and every candidate has exactly one placement.
        val placementItems = planned.placements.map { it.item }
        assertEquals(
            (items.map { it.id } + additions.map { it.id }).toSet(),
            placementItems.toSet(),
        )
        assertEquals(placementItems.size, placementItems.distinct().size)

        // Workspace placements stay in bounds and never overlap on one page.
        val workspace = planned.placements
            .mapNotNull { p -> (p.target as? PlacementTarget.WorkspaceTarget)?.let { p.item to it } }
        workspace.forEach { (_, target) ->
            assertTrue(target.cell.x >= 0 && target.cell.y >= 0)
            assertTrue(target.cell.x + target.span.width <= 4)
            assertTrue(target.cell.y + target.span.height <= 4)
        }
        val byPage = workspace.groupBy({ it.second.page }) { it.second }
        byPage.forEach { (_, targets) ->
            for (i in targets.indices) {
                for (j in i + 1 until targets.size) {
                    val a = targets[i]
                    val b = targets[j]
                    val overlaps = a.cell.x < b.cell.x + b.span.width &&
                        b.cell.x < a.cell.x + a.span.width &&
                        a.cell.y < b.cell.y + b.span.height &&
                        b.cell.y < a.cell.y + a.span.height
                    assertTrue("placements ${targets[i]} and ${targets[j]} overlap", !overlaps)
                }
            }
        }
    }

    @Test
    fun emptyWorkspaceWithSelectionPlansAndAppliesWholeScope() {
        // Spec AC-8: zero existing placements is a supported input.
        val additions = (0 until 5).map { candidate("solo$it") }

        val planned = (planner.plan(input(items = emptyList(), additions = additions)).outcome as Planned)

        assertEquals(additions.map { it.id }.toSet(), planned.placements.map { it.item }.toSet())
        assertTrue(planned.placements.all { it.disposition is Disposition.Moved })
    }

    @Test
    fun candidateFoldersContinueTheOrdinalSequenceAfterExistingFolders() {
        // Two existing same-category apps form one folder; three same-category
        // candidates form another. The candidate folder's ordinal must follow
        // the existing folder's, never collide.
        val existingSignals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S2, CategoryId("GAMES")),
            ClassificationSignal(ItemId("b"), SignalSource.S2, CategoryId("GAMES")),
        )
        val candidateSignals = listOf(
            ClassificationSignal(ItemId("c0"), SignalSource.S2, CategoryId("TOOLS")),
            ClassificationSignal(ItemId("c1"), SignalSource.S2, CategoryId("TOOLS")),
            ClassificationSignal(ItemId("c2"), SignalSource.S2, CategoryId("TOOLS")),
        )
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val additions = listOf(candidate("c0"), candidate("c1"), candidate("c2"))

        val planned = (
            planner.plan(input(items, additions, signals = existingSignals + candidateSignals))
                .outcome as Planned
            )

        assertEquals(2, planned.newFolders.size)
        assertEquals(
            listOf(NewFolderOrdinal(0), NewFolderOrdinal(1)),
            planned.newFolders.map { it.ordinal },
        )
        val existingFolder = planned.newFolders.first { it.members.contains(ItemId("a")) }
        val candidateFolder = planned.newFolders.first { it.members.contains(ItemId("c0")) }
        assertTrue(existingFolder.ordinal < candidateFolder.ordinal)
        assertEquals(
            setOf(ItemId("c0"), ItemId("c1"), ItemId("c2")),
            candidateFolder.members.toSet(),
        )
    }

    @Test
    fun planningIsDeterministicAndSelectionOrderInsensitive() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 2, y = 3))
        val additions = listOf(candidate("c0"), candidate("c1"), candidate("c2"))

        val first = planner.plan(input(items, additions))
        val second = planner.plan(input(items, additions))
        val shuffled = planner.plan(input(items, additions.shuffled(java.util.Random(3))))

        assertEquals((first.outcome as Planned), second.outcome as Planned)
        assertEquals((first.outcome as Planned), shuffled.outcome as Planned)
    }

    @Test
    fun everyStrategyKeepsTheComposedInvariantsAndEmptySelectionEquality() {
        // Plan §11/R-1: the composed tail must hold for every catalog
        // strategy's full-run executor, and an empty selection must keep the
        // strategy's plain full-organization output byte for byte.
        val strategies = listOf(
            "CANONICAL_PAGE_COMPACT_V1",
            "STABLE_PAGE_TIDY_V1",
            "BOTTOM_FIRST_V1",
            "GLOBAL_COMPACT_V1",
            "GLOBAL_COMPACT_V2",
            "CATEGORY_CONTIGUOUS_V1",
        )
        val items = listOf(
            app("a", x = 0, y = 0),
            app("b", x = 3, y = 2),
            app("c", x = 1, y = 3),
        )
        val additions = (0 until 5).map { candidate("s$it") }

        for (strategyId in strategies.map(::StrategyId)) {
            val rules = defaultRules(strategy = strategyId)
            val composed = planner.plan(input(items, additions, rules = rules))
            val planned = composed.outcome as Planned

            // Conservation: every captured item and candidate placed exactly once.
            val placementItems = planned.placements.map { it.item }
            assertEquals(
                "strategy $strategyId lost an item",
                (items.map { it.id } + additions.map { it.id }).toSet(),
                placementItems.toSet(),
            )
            assertEquals(placementItems.size, placementItems.distinct().size)

            // Bounds + no overlap per page.
            val workspace = planned.placements
                .mapNotNull { p -> (p.target as? PlacementTarget.WorkspaceTarget)?.let { p.item to it } }
            workspace.forEach { (_, target) ->
                assertTrue(target.cell.x >= 0 && target.cell.y >= 0)
                assertTrue(target.cell.x + target.span.width <= 4)
                assertTrue(target.cell.y + target.span.height <= 4)
            }
            workspace.groupBy({ it.second.page }, { it.second }).forEach { (_, targets) ->
                for (i in targets.indices) {
                    for (j in i + 1 until targets.size) {
                        val a = targets[i]
                        val b = targets[j]
                        val overlaps = a.cell.x < b.cell.x + b.span.width &&
                            b.cell.x < a.cell.x + a.span.width &&
                            a.cell.y < b.cell.y + b.span.height &&
                            b.cell.y < a.cell.y + a.span.height
                        assertTrue("strategy $strategyId overlap: $a vs $b", !overlaps)
                    }
                }
            }

            // Determinism.
            assertEquals(planned, planner.plan(input(items, additions, rules = rules)).outcome as Planned)

            // Empty selection equals the plain full run of the same strategy.
            val composedEmpty = planner.plan(input(items, emptyList(), rules = rules))
            val fullEmpty = planner.plan(input(items, emptyList(), rules = rules).copy(runMode = RunMode.FullOrganization))
            assertEquals(
                "strategy $strategyId diverged on empty selection",
                (fullEmpty.outcome as Planned),
                composedEmpty.outcome as Planned,
            )
        }
    }

    @Test
    fun planningOscillatesNothingAfterTheCandidatesArePlaced() {
        // Spec §4 idempotence: once the candidates are on the workspace, the
        // next full organize over the post-state proposes the same layout (all
        // placements preserve their target), so the applied diff is empty.
        val items = listOf(app("a", x = 0, y = 0))
        val additions = listOf(candidate("c0"), candidate("c1"))

        val applied = planner.plan(input(items, additions)).outcome as Planned

        // Rebuild the post-apply capture: every planned placement becomes a
        // captured workspace/folder placement with the same item id.
        val pages = listOf(Page(PageId("p0"), PageOrder(0))) + applied.newPages.map {
            Page(PageId("new-${it.ordinal.value}"), it.order)
        }
        fun pageOf(target: PlacementTarget.WorkspaceTarget): PageRef = when (val pageRef = target.page) {
            is PageRef -> pageRef
            is NewPageRef -> PageRef(PageId("new-${pageRef.ordinal.value}"))
        }
        val postItems = applied.placements.map { placement ->
            val workspace = placement.target as PlacementTarget.WorkspaceTarget
            CapturedItem(
                id = placement.item,
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.${placement.item.value}"), p0),
                placement = CapturedPlacement.Workspace(
                    pageOf(workspace),
                    workspace.cell,
                    workspace.span,
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }
        val postFull = OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev2"), defaultDevice(), pages, postItems),
            rules = defaultRules(),
            taxonomy = defaultTaxonomy(),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(postItems.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
            runMode = RunMode.FullOrganization,
        )

        val replanned = planner.plan(postFull).outcome as Planned

        // The full organize of the already-canonical layout keeps every item
        // where the composed run placed it.
        applied.placements.associateBy { it.item }.forEach { (id, placement) ->
            val again = replanned.placements.single { it.item == id }
            assertEquals("item $id moved on re-organize", placement.target, again.target)
        }
    }
}
