package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.ContractCheck
import app.lawnchair.organizer.planning.harness.DEFAULT_PLANNER_CASE_COUNT
import app.lawnchair.organizer.planning.harness.ExampleCorpus
import app.lawnchair.organizer.planning.harness.ExpectedOutcome
import app.lawnchair.organizer.planning.harness.FixtureId
import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.PlannerFixture
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Test

/**
 * Spec 182 AC-12 evidence: per-strategy performance sample using the accepted
 * spec 12 protocol (3 warm-ups, 10 measured passes, median and nearest-rank
 * p95, no pass/fail budget assertion — budgets stay with Issue #15).
 *
 * The workload matrix is a dedicated full-organization corpus with real
 * movable workloads that exercise the strategy differences: movable 1×1 and
 * non-1×1 items, fragmented fixed occupancy (locked items and widgets),
 * multiple pages with cross-page free space, and folder-forming category
 * mixtures (S1 GAMES signals over the built-in taxonomy).
 */
class StrategyPerformanceSampleTest {

    private val seed: Long = SyntheticFixtureGenerator.DEFAULT_SEED

    /**
     * Accepted spec-12 sample (unchanged from the pre-182 benchmark): the
     * Issue #11 generated corpus (seed `0x4E554E55L`, cases 0..63), 3 warm-ups
     * + 10 measured passes, median and nearest-rank p95, JVM/processors/heap
     * recorded. Strategy routing is covered by CrossStrategyCorpusTest; this
     * sample keeps the accepted baseline comparable across runs.
     */
    @Test
    fun recordSpec12PerformanceSample() {
        val fixtures = SyntheticFixtureGenerator.generate(seed, DEFAULT_PLANNER_CASE_COUNT)

        fun runCorpusPass(pass: Int) {
            val harness = PlannerContractHarness(DeterministicOrganizationPlanner())
            for (fixture in fixtures) {
                val report = harness.verify(fixture)
                check(report.isSuccess) { "Benchmark pass $pass failed for ${fixture.id.value}" }
            }
        }

        repeat(3) { runCorpusPass(-(it + 1)) }

        val durations = LongArray(10)
        for (pass in 0 until 10) {
            val start = System.nanoTime()
            runCorpusPass(pass)
            durations[pass] = System.nanoTime() - start
        }

        durations.sort()
        val median = durations[durations.size / 2]
        val p95Index = ((durations.size * 95 + 99) / 100).coerceAtMost(durations.size) - 1
        val p95 = durations[p95Index]

        val jvmVersion = System.getProperty("java.version", "unknown")
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val maxHeap = Runtime.getRuntime().maxMemory()
        println(
            """
            |=== PlannerBenchmarkTest (seed=$seed, cases=${DEFAULT_PLANNER_CASE_COUNT}) ===
            |JVM: $jvmVersion
            |Processors: $availableProcessors
            |Max heap: $maxHeap bytes
            |median: $median ns
            |p95 (nearest-rank): $p95 ns
            """.trimMargin(),
        )
    }

    /** Full-run movable matrix: [rows, columns, locked, widget, signals]. */
    private fun matrix(): List<PerfCase> = listOf(
        PerfCase("sparse-1page-6x5", 1, 6, 5, locked = 0, widget = false, games = 0, items = 10, secondPageItems = 0),
        PerfCase("fragmented-locks-6x5", 1, 6, 5, locked = 3, widget = true, games = 0, items = 10, secondPageItems = 0),
        PerfCase("dense-1page-4x5", 1, 4, 5, locked = 1, widget = true, games = 0, items = 10, secondPageItems = 0),
        PerfCase("two-pages-4x5", 2, 4, 5, locked = 2, widget = false, games = 0, items = 8, secondPageItems = 8),
        PerfCase("folder-candidates-4x5", 1, 4, 5, locked = 0, widget = false, games = 6, items = 10, secondPageItems = 0),
        PerfCase("mixed-cross-page-5x5", 2, 5, 5, locked = 1, widget = true, games = 4, items = 10, secondPageItems = 6),
    )

    private data class PerfCase(
        val name: String,
        val pages: Int,
        val columns: Int,
        val rows: Int,
        val locked: Int,
        val widget: Boolean,
        val games: Int,
        val items: Int,
        val secondPageItems: Int,
    )

    private val p0 = ProfileId("p0")

    private fun app(
        id: String,
        x: Int,
        y: Int,
        page: String,
        locked: Boolean = false,
        spanW: Int = 1,
        spanH: Int = 1,
    ) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun widget(id: String, x: Int, y: Int) = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPWIDGET,
        target = TargetKey.WidgetKey(ComponentKey("com.example.$id/.Widget"), AppWidgetId(1), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(2, 2)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    /**
     * Cursor-based row-major packer. Locked items, the widget, and the
     * 2x1 movable (mixed case only) are placed first at deterministic
     * positions; remaining movable 1x1 items fill the next cells. Every
     * placement advances the cursor by the item's span width, wrapping to the
     * next row when the item doesn't fit — cells never overlap.
     */
    private fun itemsFor(case: PerfCase): List<CapturedItem> {
        val items = mutableListOf<CapturedItem>()
        var col = 0
        var row = 0
        val isMixed = case.name.startsWith("mixed")

        fun advance(spanW: Int) {
            col += spanW
            if (col >= case.columns) {
                col = 0
                row++
            }
        }

        fun fitsInRow(spanW: Int): Boolean = col + spanW <= case.columns

        // Locked items first.
        repeat(case.locked) { index ->
            if (!fitsInRow(1)) {
                col = 0
                row++
            }
            items += app("l$index", col, row, page = "p0", locked = true)
            advance(1)
        }
        // Widget (2x2) next.
        if (case.widget) {
            if (!fitsInRow(2)) {
                col = 0
                row++
            }
            items += widget("w1", col, row)
            advance(2)
            // Widgets occupy 2 rows too.
            row++
        }
        // Movable non-1x1 app (mixed case only) before the 1x1 stream.
        if (isMixed) {
            if (!fitsInRow(2)) {
                col = 0
                row++
            }
            items += app("a2x1", col, row, page = "p0", spanW = 2, spanH = 1)
            advance(2)
        }
        // GAMES-tagged movable 1x1 apps (folder candidates under canonical).
        val gamesCount = minOf(case.games, case.items)
        repeat(gamesCount) { index ->
            if (!fitsInRow(1)) {
                col = 0
                row++
            }
            items += app("a$index", col, row, page = "p0")
            advance(1)
        }
        // Remaining fallback-category movable 1x1 apps.
        val fallbackCount = case.items - gamesCount - (if (isMixed) 1 else 0)
        repeat(fallbackCount) { index ->
            if (!fitsInRow(1)) {
                col = 0
                row++
            }
            items += app("o$index", col, row, page = "p0")
            advance(1)
        }
        // Second page items.
        repeat(case.secondPageItems) { index ->
            items += app("b$index", index % case.columns, index / case.columns, page = "p1")
        }
        return items
    }

    private fun signalsFor(case: PerfCase): List<ClassificationSignal> {
        val movableOneByOne = case.items - (if (case.name.startsWith("mixed")) 1 else 0)
        val gamesCount = minOf(case.games, movableOneByOne)
        return (0 until gamesCount).map { index ->
            ClassificationSignal(ItemId("a$index"), SignalSource.S1, CategoryId("GAMES"))
        }
    }

    private fun inputFor(case: PerfCase): OrganizationInput {
        val items = itemsFor(case)
        val pages = (0 until case.pages).map { Page(PageId("p$it"), PageOrder(it)) }
        return OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), device(case), pages, items, emptyList()),
            rules = rules(),
            taxonomy = taxonomy(),
            signals = ClassificationSignals(signalsFor(case)),
            targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
            runMode = RunMode.FullOrganization,
        )
    }

    private fun rules() = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = LayoutStrategyRegistry.CANONICAL_PAGE_COMPACT_V1,
    )

    private fun taxonomy() = TaxonomyContract(
        TaxonomyVersion("tv1"),
        listOf(CategoryId("OTHER"), CategoryId("GAMES"), CategoryId("TOOLS")),
        CategoryId("OTHER"),
    )

    private fun device(case: PerfCase) = DeviceCapabilities(
        case.columns,
        case.rows,
        4,
        4,
        4,
        Orientation.PORTRAIT,
    )

    private fun routedFixture(case: PerfCase, strategy: StrategyId): PlannerFixture {
        val input = inputFor(case).copy(
            rules = rules().copy(organizationStrategy = strategy),
        )
        return PlannerFixture(
            id = FixtureId("${case.name}-${strategy.value}"),
            input = input,
            expectation = app.lawnchair.organizer.planning.harness.FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(
                ContractCheck.EXPECTATION,
                app.lawnchair.organizer.planning.harness.ContractCheck.CONSERVATION,
                app.lawnchair.organizer.planning.harness.ContractCheck.BOUNDS,
                app.lawnchair.organizer.planning.harness.ContractCheck.NO_OVERLAP,
                app.lawnchair.organizer.planning.harness.ContractCheck.LOCK_PRESERVATION,
            ),
        )
    }

    @Test
    fun recordPerStrategyPerformanceSamples() {
        val matrix = matrix()
        for (id in LayoutStrategyRegistry.acceptedIds) {
            for (case in matrix) {
                sample(id, case)
            }
        }
    }

    private fun sample(strategy: StrategyId, case: PerfCase) {
        val fixture = routedFixture(case, strategy)

        fun runPass(pass: Int) {
            val harness = PlannerContractHarness(DeterministicOrganizationPlanner())
            val report = harness.verify(fixture)
            if (!report.isSuccess) {
                val probe = DeterministicOrganizationPlanner().plan(fixture.input)
                check(probe.outcome is Planned) {
                    "fixture ${fixture.id.value} plans to Invalid: " +
                        (probe.outcome as? Rejected.Invalid)?.reasons?.joinToString { it.code.name }
                }
            }
            check(report.isSuccess) {
                "Benchmark pass $pass failed for ${fixture.id.value}:\n" +
                    report.violations.joinToString("\n") { "${it.check}: ${it.message}" }
            }
        }

        repeat(3) { runPass(it) }

        val durations = LongArray(10)
        for (pass in 0 until 10) {
            val start = System.nanoTime()
            runPass(pass)
            durations[pass] = System.nanoTime() - start
        }

        durations.sort()
        val median = durations[durations.size / 2]
        val p95Index = ((durations.size * 95 + 99) / 100).coerceAtMost(durations.size) - 1
        val p95 = durations[p95Index]

        val jvmVersion = System.getProperty("java.version", "unknown")
        println(
            """
            |=== StrategyPerformanceSampleTest ===
            |strategy=${strategy.value} case=${case.name}
            |JVM: $jvmVersion
            |median: $median ns
            |p95 (nearest-rank): $p95 ns
            """.trimMargin(),
        )
    }
}
