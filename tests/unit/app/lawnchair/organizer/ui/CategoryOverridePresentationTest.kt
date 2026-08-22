package app.lawnchair.organizer.ui

import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOverridePresentationTest {
    @Test
    fun activeV1BundleCategoryIdsHaveAnExhaustiveLocalizedPresentationMapping() {
        val bundle = (BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready).bundle
        val bundleIds = bundle.taxonomy.allowedCategories.mapTo(linkedSetOf()) { it.value }

        assertEquals(bundleIds, CategoryOverrideCategoryPresentations.mappedIdsForTest())
        bundle.taxonomy.allowedCategories.forEach { category ->
            val presentation = CategoryOverrideCategoryPresentations.forCategory(category)
            assertTrue(presentation.labelRes != 0)
            assertTrue(presentation.descriptionRes != 0)
        }
    }
}
