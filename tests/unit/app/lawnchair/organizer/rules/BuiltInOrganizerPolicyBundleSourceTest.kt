package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.CategoryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInOrganizerPolicyBundleSourceTest {
    @Test
    fun activeBundleIsAcceptedV1AuthorityWithExplicitEmptyS3AndS4() {
        val result = BuiltInOrganizerPolicyBundleSource.readActive()
        assertTrue(result is BundleReadResult.Ready)
        val bundle = (result as BundleReadResult.Ready).bundle

        assertEquals("organization-policy-v1", bundle.identity.semanticVersion)
        assertEquals("v1", bundle.rules.version.value)
        assertEquals("v1", bundle.taxonomy.version.value)
        assertEquals(34, bundle.taxonomy.allowedCategories.size)
        assertEquals("OTHER", bundle.taxonomy.fallbackCategory.value)
        assertTrue(bundle.classification.packageRules.isEmpty())
        assertTrue(bundle.classification.intentRules.isEmpty())
        assertEquals(bundle.identity.sha256, bundle.canonicalDigest())
        assertEquals(null, bundle.validate())
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
