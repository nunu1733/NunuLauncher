package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.DEFAULT_PLANNER_CASE_COUNT
import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Test

/**
 * Spec 182 AC-12 evidence: per-strategy performance sample using the accepted
 * spec 12 protocol (3 warm-ups, 10 measured passes, median and nearest-rank
 * p95, no pass/fail budget assertion — budgets stay with Issue #15).
 *
 * Each runtime-enabled strategy is sampled over the same generated corpus so
 * the numbers are comparable across strategies and runs.
 */
class StrategyPerformanceSampleTest {

    private val seed: Long = SyntheticFixtureGenerator.DEFAULT_SEED

    @Test
    fun recordPerStrategyPerformanceSamples() {
        val fixtures = SyntheticFixtureGenerator.generate(seed, DEFAULT_PLANNER_CASE_COUNT)

        for (id in LayoutStrategyRegistry.acceptedIds) {
            sample(id)
        }
    }

    private fun sample(strategy: StrategyId) {
        val fixtures = SyntheticFixtureGenerator.generate(seed, DEFAULT_PLANNER_CASE_COUNT)

        fun runCorpusPass(pass: Int) {
            val harness = PlannerContractHarness(DeterministicOrganizationPlanner())
            for (fixture in fixtures) {
                val routed = fixture.input.copy(
                    rules = fixture.input.rules.copy(organizationStrategy = strategy),
                )
                val routedFixture = fixture.copy(input = routed)
                val report = harness.verify(routedFixture)
                check(report.isSuccess) {
                    "Benchmark pass $pass failed for ${fixture.id.value} under ${strategy.value}"
                }
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
        println(
            """
            |=== StrategyPerformanceSampleTest (strategy=${strategy.value}, seed=$seed, cases=${DEFAULT_PLANNER_CASE_COUNT}) ===
            |JVM: $jvmVersion
            |median: $median ns
            |p95 (nearest-rank): $p95 ns
            """.trimMargin(),
        )
    }
}
