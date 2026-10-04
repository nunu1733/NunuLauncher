package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.DEFAULT_PLANNER_CASE_COUNT
import app.lawnchair.organizer.planning.harness.ExampleCorpus
import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.PlannerFixture
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerGeneratedPropertyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()

    private val seed: Long = System.getProperty("planner.seed")?.toLongOrNull()
        ?: SyntheticFixtureGenerator.DEFAULT_SEED
    private val caseIndex: Int? = System.getProperty("planner.case")?.toIntOrNull()
    private val caseCount: Int = readPlannerCaseCount()

    @Test
    fun defaultGeneratedCaseCountIsBounded() {
        assertEquals(
            DEFAULT_PLANNER_CASE_COUNT,
            readPlannerCaseCount { null },
        )
    }

    @Test
    fun generatedCaseCountRejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            readPlannerCaseCount { "0" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            readPlannerCaseCount { "not-a-count" }
        }
    }

    @Test
    fun throwingPlannerPrintsReproductionBeforeRethrowing() {
        val fixture = SyntheticFixtureGenerator.generate(seed = 42, count = 512).last()
        val throwingPlanner = OrganizationPlanner {
            throw IllegalStateException("synthetic planner failure")
        }
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        val replacementErr = PrintStream(capturedErr, true, Charsets.UTF_8.name())
        val failure = try {
            System.setErr(replacementErr)
            assertThrows(IllegalStateException::class.java) {
                verifyPlannerFixtures(
                    planner = throwingPlanner,
                    label = "Throwing planner",
                    fixtures = listOf(fixture),
                    includeReproduction = true,
                )
            }
        } finally {
            System.setErr(originalErr)
            replacementErr.flush()
        }

        assertEquals("synthetic planner failure", failure.message)
        val reproduction = requireNotNull(fixture.reproduction)
        val output = capturedErr.toString(Charsets.UTF_8.name())
        assertTrue(output.contains("seed=42"))
        assertTrue(output.contains("case=511"))
        assertTrue(output.contains("Reproduce: $reproduction"))
    }

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
            val all = SyntheticFixtureGenerator.generate(seed, caseCount)
            listOf(SyntheticFixtureGenerator.selectCase(all, seed, caseIndex))
        } else {
            SyntheticFixtureGenerator.generate(seed, caseCount)
        }
        val caseDesc = if (caseIndex != null) " case=$caseIndex" else ""
        verifyFixtures(
            "Generated corpus (seed=$seed, count=$caseCount$caseDesc)",
            fixtures,
            includeReproduction = true,
        )
    }

    private fun verifyFixtures(
        label: String,
        fixtures: Iterable<PlannerFixture>,
        includeReproduction: Boolean = false,
    ) {
        verifyPlannerFixtures(planner, label, fixtures, includeReproduction)
    }

    /**
     * Issue #201 (FN-AC-07): every planned folder must carry the semantic
     * naming identity of its grouping — the non-fallback category shared by
     * exactly the members grouped into it — across the whole corpus.
     */
    @Test
    fun generatedCorpusFolderNamingMatchesGroupingCategory() {
        val fixtures = SyntheticFixtureGenerator.generate(seed, caseCount)
        for (fixture in fixtures) {
            val input = fixture.input
            val result = planner.plan(input)
            val planned = result.outcome as? Planned ?: continue
            val decisions = planned.categories.associate { it.item to it.category }
            val fallback = input.taxonomy.fallbackCategory
            for (folder in planned.newFolders) {
                val naming = folder.naming as? FolderNaming.FromCategory
                    ?: error("planned folder ${folder.ordinal.value} has no category naming")
                assertTrue("planned folder ${folder.ordinal.value} has no members", folder.members.isNotEmpty())
                val memberCategories = folder.members.map { decisions[it] ?: fallback }.toSet()
                assertEquals(
                    "planned folder ${folder.ordinal.value} naming must equal its grouping category",
                    setOf(naming.category),
                    memberCategories,
                )
                assertNotEquals(
                    "fallback category never forms folders (Issue #201 spec)",
                    fallback,
                    naming.category,
                )
            }
        }
    }
}

internal fun verifyPlannerFixtures(
    planner: OrganizationPlanner,
    label: String,
    fixtures: Iterable<PlannerFixture>,
    includeReproduction: Boolean = false,
) {
    val harness = PlannerContractHarness(planner)
    val failures = StringBuilder()
    for (fixture in fixtures) {
        val report = try {
            harness.verify(fixture)
        } catch (throwable: Throwable) {
            if (includeReproduction) {
                fixture.reproduction?.let { reproduction ->
                    System.err.println(
                        "Fixture ${fixture.id.value} threw ${throwable::class.qualifiedName} " +
                            "(seed=${reproduction.seed}, case=${reproduction.caseIndex}, " +
                            "count=${reproduction.corpusCount}). Reproduce: $reproduction",
                    )
                }
            }
            throw throwable
        }
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

internal fun readPlannerCaseCount(property: (String) -> String? = { key -> System.getProperty(key) }): Int {
    val raw = property("planner.count") ?: return DEFAULT_PLANNER_CASE_COUNT
    return raw.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("planner.count must be a positive integer: $raw")
}
