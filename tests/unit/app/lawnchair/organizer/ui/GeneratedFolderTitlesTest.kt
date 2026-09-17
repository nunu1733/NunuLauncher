package app.lawnchair.organizer.ui

import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.UserCategoryId
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #201 (FN-AC-03, unit side): the production generated-folder title
 * resolver maps taxonomy categories through the total presentation lookup and
 * unknown categories to the generic fallback — via `null`, never by catching
 * exceptions and never by exposing a raw category ID. Actual resource /
 * locale integration is covered by organizer instrumentation (FN-AC-15).
 *
 * Issue #336: user-defined categories resolve through the injected total
 * snapshot lookup — a known ID resolves its display name, an unknown ID the
 * generic fallback, and a blank display name propagates so the materializer's
 * blank check fails closed.
 */
class GeneratedFolderTitlesTest {

    private val userId = UserCategoryId("3f2b8c4e-1234-4abc-9de0-1234567890ab")
    private class RecordingStringProvider : GeneratedFolderTitles.StringProvider {
        val requested = mutableListOf<Int>()

        override fun string(resId: Int): String {
            requested += resId
            return "title:$resId"
        }
    }

    @Test
    fun knownCategoryResolvesThroughTotalPresentationLookup() {
        val provider = RecordingStringProvider()
        val expected = CategoryOverrideCategoryPresentations.findForCategory(CategoryId("COMMUNICATION"))!!.labelRes

        val title = GeneratedFolderTitles.resolver(provider)
            .resolve(FolderNaming.FromCategory(CategoryId("COMMUNICATION")))

        assertEquals(listOf(expected), provider.requested)
        assertEquals("title:$expected", title)
    }

    @Test
    fun everyBundleCategoryResolvesWithoutFallback() {
        val provider = RecordingStringProvider()
        val resolver = GeneratedFolderTitles.resolver(provider)
        val fallbackRes = R.string.organizer_generated_folder_fallback_name

        val categories = CategoryOverrideCategoryPresentations.mappedIdsForTest().map(::CategoryId)
        for (category in categories) {
            resolver.resolve(FolderNaming.FromCategory(category))
        }

        assertTrue(
            "bundle categories must never reach the generic fallback",
            provider.requested.none { it == fallbackRes },
        )
    }

    @Test
    fun unknownCategoryFallsBackWithoutRawIdentifier() {
        val provider = RecordingStringProvider()
        val unknown = CategoryId("NOT_IN_V1_TAXONOMY")

        val title = GeneratedFolderTitles.resolver(provider)
            .resolve(FolderNaming.FromCategory(unknown))

        assertEquals(R.string.organizer_generated_folder_fallback_name, provider.requested.single())
        assertEquals("title:${R.string.organizer_generated_folder_fallback_name}", title)
        assertTrue("raw category id must not leak", !title.contains(unknown.value))
    }

    @Test
    fun knownUserCategoryResolvesThroughTheInjectedSnapshotLookup() {
        val provider = RecordingStringProvider()
        val resolver = GeneratedFolderTitles.resolver(provider) { id ->
            if (id == userId) "Commute tools" else null
        }

        val title = resolver.resolve(FolderNaming.FromUserCategory(userId))

        assertEquals("the display name resolves without touching resources", emptyList<Int>(), provider.requested)
        assertEquals("Commute tools", title)
    }

    @Test
    fun unknownUserCategoryResolvesTheGenericFallbackWithoutRawIdentifier() {
        val provider = RecordingStringProvider()
        val resolver = GeneratedFolderTitles.resolver(provider) { null }

        val title = resolver.resolve(FolderNaming.FromUserCategory(userId))

        assertEquals(R.string.organizer_generated_folder_fallback_name, provider.requested.single())
        assertEquals("title:${R.string.organizer_generated_folder_fallback_name}", title)
        assertTrue("raw user id must not leak", !title.contains(userId.value))
    }

    @Test
    fun blankUserCategoryNamePropagatesToTheMaterializersFailClosedCheck() {
        val provider = RecordingStringProvider()
        val resolver = GeneratedFolderTitles.resolver(provider) { "   " }

        val title = resolver.resolve(FolderNaming.FromUserCategory(userId))

        // A blank display name is a port violation: it is never silently
        // replaced — the materializer's blank check fails closed on it.
        assertEquals("   ", title)
        assertTrue(provider.requested.isEmpty())
    }
}
