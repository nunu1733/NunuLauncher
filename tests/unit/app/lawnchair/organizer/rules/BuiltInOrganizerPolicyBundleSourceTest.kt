package app.lawnchair.organizer.rules

import org.junit.Assert.assertEquals
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
        assertEquals(null, bundle.validate())
    }
}
