package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.ContractCheck
import app.lawnchair.organizer.planning.harness.DEFAULT_PLANNER_CASE_COUNT
import app.lawnchair.organizer.planning.harness.ExampleCorpus
import app.lawnchair.organizer.planning.harness.ExpectedOutcome
import app.lawnchair.organizer.planning.harness.PlannerContractHarness
import app.lawnchair.organizer.planning.harness.PlannerFixture
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Test

/**
 * Spec 182 AC-9/AC-11 (cross-strategy isolation): every runtime-enabled
 * strategy passes the shared invariant checks through the public seam while
 * only the selected strategy changes. Canonical-specific expectations (exact
 * placements/preservations under CANONICAL_TIE_BREAK) are strategy-dependent
 * by design and are replaced by an outcome-family assertion; the shared
 * safety/property family — conservation, bounds, overlap, container
 * integrity, lock preservation, profile isolation, determinism,
 * idempotence, input permutation — always runs via
 * `PlannerContractHarness.verify`.
 */
class CrossStrategyCorpusTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()

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

    /**
     * Strategy-agnostic view of a fixture. Exact canonical-specific
     * expectations (required preservations/categories/cells) are strategy-
     * dependent by design and dropped, but the outcome *family* (Planned /
     * Invalid / Impossible) is kept so `Oracle.checkExpectation` — reached
     * through the retained `EXPECTATION` check — pins that a strategy can
     * never change a fixture's outcome family. The shared invariant checks
     * then run only when the family matches, exactly as in the canonical
     * harness.
     */
    private fun routed(fixture: PlannerFixture, strategy: StrategyId): PlannerFixture {
        val sharedExpectation = when (val outcome = fixture.expectation.outcome) {
            is ExpectedOutcome.Planned -> ExpectedOutcome.Planned()
            is ExpectedOutcome.Invalid -> ExpectedOutcome.Invalid(outcome.requiredCodes)
            is ExpectedOutcome.Impossible -> ExpectedOutcome.Impossible(outcome.requiredReasons)
        }
        return fixture.copy(
            input = fixture.input.copy(
                rules = fixture.input.rules.copy(organizationStrategy = strategy),
            ),
            expectation = fixture.expectation.copy(outcome = sharedExpectation),
        )
    }

    @Test
    fun everyRegisteredStrategyPassesTheSharedInvariantChecks() {
        val corpus = fixtures()
        check(corpus.isNotEmpty())
        for (id in LayoutStrategyRegistry.acceptedIds) {
            val definition = checkNotNull(LayoutStrategyRegistry.definition(id))
            val harness = PlannerContractHarness(DeterministicOrganizationPlanner())
            val failures = mutableListOf<String>()
            for (fixture in corpus) {
                val routed = routed(fixture, id)
                val report = harness.verify(routed)
                if (!report.isSuccess) {
                    failures += report.violations.map { violation ->
                        "${fixture.id.value}: ${violation.check} — ${violation.message}"
                    }
                }
                val result = planner.plan(routed.input)
                if (result.organizationStrategy != id) {
                    failures += "${fixture.id.value}: strategy echo mismatch"
                }
            }
            check(failures.isEmpty()) {
                "strategy ${id.value} shared-invariant failures:\n${failures.joinToString("\n")}"
            }
            check(definition.identity == id)
        }
    }

    @Test
    fun catalogCoherenceCoversEveryExecutableStrategy() {
        // Narrowed to what this test asserts: every registry entry resolves
        // and the set is non-empty. The full runtimeSupported/default
        // equality contract lives in BuiltInOrganizerPolicyBundleSourceTest.
        check(LayoutStrategyRegistry.acceptedIds.isNotEmpty())
        for (id in LayoutStrategyRegistry.acceptedIds) {
            checkNotNull(LayoutStrategyRegistry.definition(id))
        }
    }
}
