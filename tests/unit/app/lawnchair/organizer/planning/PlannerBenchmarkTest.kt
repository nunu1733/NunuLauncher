package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Test

class PlannerBenchmarkTest {

    private val seed: Long = SyntheticFixtureGenerator.DEFAULT_SEED

    @Test
    fun recordPerformanceSample() {
        val fixtures = SyntheticFixtureGenerator.generate(seed, 64)

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
        val totalMemory = Runtime.getRuntime().totalMemory()
        val freeMemory = Runtime.getRuntime().freeMemory()
        val usedMemory = totalMemory - freeMemory

        println(
            """
            |=== PlannerBenchmarkTest (seed=$seed, cases=64) ===
            |JVM: $jvmVersion
            |Processors: $availableProcessors
            |Max heap: $maxHeap bytes
            |Used heap: $usedMemory bytes
            |Median: $median ns
            |P95 (nearest-rank): $p95 ns
            |All durations (ns): ${durations.joinToString(", ")}
            """.trimMargin(),
        )
    }
}
