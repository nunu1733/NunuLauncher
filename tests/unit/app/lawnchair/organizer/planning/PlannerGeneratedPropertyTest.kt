package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.ExampleCorpus
import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.PlannerFixture
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerGeneratedPropertyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()

    private val seed: Long = System.getProperty("planner.seed")?.toLongOrNull()
        ?: SyntheticFixtureGenerator.DEFAULT_SEED
    private val caseIndex: Int? = System.getProperty("planner.case")?.toIntOrNull()

    @Test
    fun exampleCorpusConformsToContract() {
        verifyFixtures("Example corpus", ExampleCorpus.allExamples.values)
    }

    @Test
    fun validationCorpusConformsToContract() {
        verifyFixtures("Validation corpus", ExampleCorpus.validationFixtures.values)
    }

    @Test
    fun generatedCorpusConformsToContract() {
        val fixtures = if (caseIndex != null) {
            val all = SyntheticFixtureGenerator.generate(seed, 64)
            listOf(SyntheticFixtureGenerator.selectCase(all, seed, caseIndex))
        } else {
            SyntheticFixtureGenerator.generate(seed, 64)
        }
        val caseDesc = if (caseIndex != null) " case=$caseIndex" else ""
        verifyFixtures("Generated corpus (seed=$seed$caseDesc)", fixtures, includeReproduction = true)
    }

    private fun verifyFixtures(
        label: String,
        fixtures: Iterable<PlannerFixture>,
        includeReproduction: Boolean = false,
    ) {
        val harness = PlannerContractHarness(planner)
        val failures = StringBuilder()
        for (fixture in fixtures) {
            val report = harness.verify(fixture)
            if (!report.isSuccess) {
                failures.append("Fixture ${fixture.id.value}:\n")
                if (includeReproduction) {
                    fixture.reproduction?.let { failures.append("  Reproduce: $it\n") }
                }
                report.violations.forEach { failures.append("  ${it.message}\n") }
            }
        }
        assertTrue("$label violations:\n$failures", failures.isEmpty())
    }
}
