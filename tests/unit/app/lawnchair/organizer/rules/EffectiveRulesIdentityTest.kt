package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.StrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-6 contract (spec 182): the effective rules identity is the identity of
 * the effective `RuleSemantics` and uses exactly the accepted formula
 * `hash(bundleIdentity.semanticVersion || bundleIdentity.sha256 ||
 * selectionIdentity.sha256 || canonical(effectiveRuleSemantics))`.
 */
class EffectiveRulesIdentityTest {

    private val bundleA = PolicyBundleIdentity("organization-policy-v2", "a".repeat(64))
    private val selectionDefault = PolicyInputIdentity(
        PolicySourceKind.LAYOUT_STRATEGY_SELECTION,
        "schema-1-generation-0",
        sha256Canonical(""),
    )

    private fun rules(strategy: String) = BuiltInOrganizerPolicyBundleSource.readActive()
        .let { it as BundleReadResult.Ready }.bundle
        .rules
        .copy(organizationStrategy = StrategyId(strategy))

    @Test
    fun sameSelectionContentYieldsTheSameRulesIdentity() {
        val first = effectiveRulesIdentity(bundleA, selectionDefault, rules("CANONICAL_PAGE_COMPACT_V1"))
        val second = effectiveRulesIdentity(bundleA, selectionDefault, rules("CANONICAL_PAGE_COMPACT_V1"))

        assertEquals(first, second)
        assertEquals("v2", first.versionOrGeneration)
    }

    @Test
    fun differentSelectedStrategyContentYieldsADifferentRulesIdentity() {
        // Both are runtime-supported strategies since child 4; the identity-
        // content contract must distinguish their effective rule semantics.
        val canonical = effectiveRulesIdentity(bundleA, selectionDefault, rules("CANONICAL_PAGE_COMPACT_V1"))
        val tidy = effectiveRulesIdentity(bundleA, selectionDefault, rules("STABLE_PAGE_TIDY_V1"))

        assertNotEquals(canonical, tidy)
    }

    @Test
    fun differentSelectionContentYieldsADifferentRulesIdentityForTheSameEffectiveRules() {
        val otherSelection = PolicyInputIdentity(
            PolicySourceKind.LAYOUT_STRATEGY_SELECTION,
            "schema-1-generation-1",
            sha256Canonical("FUTURE_TIDY_V1"),
        )
        val sameRules = rules("CANONICAL_PAGE_COMPACT_V1")

        assertNotEquals(
            effectiveRulesIdentity(bundleA, selectionDefault, sameRules),
            effectiveRulesIdentity(bundleA, otherSelection, sameRules),
        )
    }

    @Test
    fun differentBundleIdentityYieldsADifferentRulesIdentity() {
        val bundleB = PolicyBundleIdentity("organization-policy-v2", "b".repeat(64))

        assertNotEquals(
            effectiveRulesIdentity(bundleA, selectionDefault, rules("CANONICAL_PAGE_COMPACT_V1")),
            effectiveRulesIdentity(bundleB, selectionDefault, rules("CANONICAL_PAGE_COMPACT_V1")),
        )
    }

    @Test
    fun selectionGenerationAloneDoesNotChangeTheRulesIdentity() {
        val regenerated = selectionDefault.copy(versionOrGeneration = "schema-1-generation-9")

        assertEquals(
            effectiveRulesIdentity(bundleA, selectionDefault, rules("CANONICAL_PAGE_COMPACT_V1")),
            effectiveRulesIdentity(bundleA, regenerated, rules("CANONICAL_PAGE_COMPACT_V1")),
        )
    }

    @Test
    fun effectiveStrategyEchoCarriesTheSelectedCatalogMember() {
        val bundle = (BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready).bundle

        assertTrue(bundle.rules.organizationStrategy in bundle.layoutStrategies.runtimeSupported)
        assertEquals(bundle.layoutStrategies.default, bundle.rules.organizationStrategy)
    }
}
