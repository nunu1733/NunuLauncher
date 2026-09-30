package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.LayoutStrategyRegistry
import app.lawnchair.organizer.planning.StrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInOrganizerPolicyBundleSourceTest {
    @Test
    fun activeBundleIsAcceptedV2AuthorityWithExplicitEmptyS3AndS4() {
        val result = BuiltInOrganizerPolicyBundleSource.readActive()
        assertTrue(result is BundleReadResult.Ready)
        val bundle = (result as BundleReadResult.Ready).bundle

        // Issue #235 published -v2.6 with both widget-relocating successors
        // enabled on one mainline (STABLE_PAGE_TIDY_V2, BOTTOM_FIRST_V2).
        // Issue #398 published -v2.7 with the lower-region successor
        // (BOTTOM_REGION_V1). Issue #451 published -v2.8 as a planner
        // behavior fix (duplicate surplus exclusion/warnings) with no
        // strategy or catalog change.
        assertEquals("organization-policy-v2.8", bundle.identity.semanticVersion)
        assertEquals("v2", bundle.rules.version.value)
        assertEquals("v1", bundle.taxonomy.version.value)
        assertEquals(34, bundle.taxonomy.allowedCategories.size)
        assertEquals("OTHER", bundle.taxonomy.fallbackCategory.value)
        assertTrue(bundle.classification.packageRules.isEmpty())
        assertTrue(bundle.classification.intentRules.isEmpty())
        assertEquals(bundle.identity.sha256, bundle.canonicalDigest())
        assertEquals(null, bundle.validate())
    }

    @Test
    fun issue451VersionBumpLeavesTheStrategyCatalogAndDefaultUnchanged() {
        // Spec 451 AC-6/N-7: the bundle's semantic version is the only
        // identity that moved; the runtime-supported catalog, the default
        // strategy, and every StrategyId are unchanged.
        val bundle = activeBundle()
        assertEquals(9, bundle.layoutStrategies.runtimeSupported.size)
        assertEquals(
            listOf(
                StrategyId("BOTTOM_FIRST_V1"),
                StrategyId("BOTTOM_FIRST_V2"),
                StrategyId("BOTTOM_REGION_V1"),
                StrategyId("CANONICAL_PAGE_COMPACT_V1"),
                StrategyId("CATEGORY_CONTIGUOUS_V1"),
                StrategyId("GLOBAL_COMPACT_V1"),
                StrategyId("GLOBAL_COMPACT_V2"),
                StrategyId("STABLE_PAGE_TIDY_V1"),
                StrategyId("STABLE_PAGE_TIDY_V2"),
            ),
            bundle.layoutStrategies.runtimeSupported.sorted(),
        )
        assertEquals(StrategyId("CANONICAL_PAGE_COMPACT_V1"), bundle.layoutStrategies.default)
    }

    @Test
    fun runtimeSupportedCatalogDeclaresOnlyImplementedStrategiesWithTheDefault() {
        val bundle = activeBundle()

        // Spec 182 / AC-9b: the bundle catalog must equal the registry's
        // runtime-enabled executable strategies exactly — no bundle-supported
        // strategy may exist without a planner implementation, and no
        // implemented strategy may hide from selection.
        val implemented = LayoutStrategyRegistry.acceptedIds
        assertTrue(implemented.isNotEmpty())
        val catalog = bundle.layoutStrategies
        assertTrue(catalog.runtimeSupported.all { it in implemented })
        assertEquals(implemented, catalog.runtimeSupported.toSet())
        assertTrue(catalog.default in catalog.runtimeSupported)
        assertEquals(catalog.default, bundle.rules.organizationStrategy)
    }

    @Test
    fun catalogIsPartOfTheBundleDigestButNotMutatedByItself() {
        val bundle = activeBundle()
        val expanded = bundle.copy(
            layoutStrategies = bundle.layoutStrategies.copy(
                runtimeSupported = bundle.layoutStrategies.runtimeSupported + StrategyId("FUTURE_STRATEGY_V1"),
            ),
        )

        // Enabling a strategy is bundle content: the digest changes (new bundle
        // identity per ADR-0007 §8), and the altered copy fails digest validation.
        assertNotEquals(bundle.canonicalDigest(), expanded.canonicalDigest())
        assertEquals(BundleReadResult.Corrupt, expanded.validate())
    }

    @Test
    fun composedRulesStrategyMustMatchTheDeclaredDefault() {
        val bundle = activeBundle()
        val altered = bundle.copy(
            rules = bundle.rules.copy(organizationStrategy = StrategyId("OTHER_STRATEGY_V1")),
        )

        assertNotEquals(bundle.canonicalDigest(), altered.canonicalDigest())
        assertEquals(BundleReadResult.Corrupt, altered.validate())
    }

    @Test
    fun validateRejectsRuleSemanticsChangedUnderOriginalIdentity() {
        val bundle = activeBundle()
        val altered = bundle.copy(
            rules = bundle.rules.copy(folderPolicy = bundle.rules.folderPolicy.copy(minGroupSize = 3)),
        )

        assertNotEquals(bundle.canonicalDigest(), altered.canonicalDigest())
        assertEquals(BundleReadResult.Corrupt, altered.validate())
    }

    @Test
    fun validateRejectsClassificationSemanticsChangedUnderOriginalIdentity() {
        val bundle = activeBundle()
        val altered = bundle.copy(
            classification = bundle.classification.copy(googleCategory = CategoryId("GAME")),
        )

        assertNotEquals(bundle.canonicalDigest(), altered.canonicalDigest())
        assertEquals(BundleReadResult.Corrupt, altered.validate())
    }

    private fun activeBundle(): OrganizerPolicyBundle = (BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready).bundle
}
