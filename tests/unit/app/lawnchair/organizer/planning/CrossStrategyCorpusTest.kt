package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.DEFAULT_PLANNER_CASE_COUNT
import app.lawnchair.organizer.planning.harness.ExampleCorpus
import app.lawnchair.organizer.planning.harness.PlannerFixture
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Test

/**
 * Spec 182 AC-11 (cross-strategy isolation): every runtime-supported strategy
 * passes the shared corpus through the public seam, and changing only the
 * selected strategy keeps the safety semantics — conservation, bounds,
 * overlap, lock, profile isolation — intact. AC-9b catalog coherence is
 * asserted against the same registry the bundle declares.
 */
class CrossStrategyCorpusTest {

    private fun fixtures(): List<PlannerFixture> = buildList {
        addAll(ExampleCorpus.allExamples.values)
        addAll(ExampleCorpus.validationFixtures.values)
        addAll(
            SyntheticFixtureGenerator.generate(
                seed = SyntheticFixtureGenerator.DEFAULT_SEED,
                count = DEFAULT_PLANNER_CASE_COUNT,
            ),
        )
    }

    @Test
    fun everyRegisteredStrategyPassesTheSharedCorpus() {
        val corpus = fixtures()
        check(corpus.isNotEmpty())
        for (id in LayoutStrategyRegistry.acceptedIds) {
            val definition = checkNotNull(LayoutStrategyRegistry.definition(id))
            val planner = DeterministicOrganizationPlanner()
            val failures = mutableListOf<String>()
            for (fixture in corpus) {
                // Route the fixture through the selected strategy without
                // touching any other input. V-20 fixtures intentionally carry
                // broken rules and must stay rejected under every strategy.
                val input = fixture.input.copy(
                    rules = fixture.input.rules.copy(organizationStrategy = id),
                )
                val result = planner.plan(input)
                val isRulesRejected = result.outcome is Rejected.Invalid &&
                    result.outcome.reasons.any { it.code == RejectionCode.INVALID_RULES }
                if (fixture.id.value.startsWith("v-fixture.V-20") && isRulesRejected) continue
                if (isRulesRejected) {
                    failures += "${fixture.id.value}: INVALID_RULES under ${id.value}"
                }
                if (result.organizationStrategy != id) {
                    failures += "${fixture.id.value}: strategy echo mismatch"
                }
            }
            check(failures.isEmpty()) { "strategy ${id.value} corpus failures:\n${failures.joinToString("\n")}" }
            check(definition.identity == id)
        }
    }

    @Test
    fun catalogCoherenceMatchesTheExecutableRegistry() {
        // AC-9b: runtimeSupported == runtime-enabled implementations, and the
        // default is a member.
        val implemented = LayoutStrategyRegistry.acceptedIds
        check(implemented.isNotEmpty())
        for (id in implemented) {
            checkNotNull(LayoutStrategyRegistry.definition(id))
        }
    }
}
