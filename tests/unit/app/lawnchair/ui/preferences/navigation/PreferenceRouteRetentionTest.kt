package app.lawnchair.ui.preferences.navigation

import app.lawnchair.ui.preferences.components.search.SearchProviderId
import app.lawnchair.ui.preferences.destinations.SearchRoute
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #116: typed Navigation resolves route arguments by the default
 * fully-qualified kotlinx.serialization serialName at runtime. R8 minification
 * must not rename or remove that class identity, which the `androidx.annotation.Keep`
 * annotation guarantees for the enum types carried as preference route arguments.
 *
 * `Keep` uses `RetentionPolicy.CLASS`, so its presence is asserted against the
 * compiled class bytecode rather than via runtime reflection.
 */
class PreferenceRouteRetentionTest {
    private fun assertCarriesKeepAnnotation(type: Class<*>) {
        val bytecode = type.getResourceAsStream("${type.simpleName}.class")
            ?.use { it.readBytes() }
            ?: error("compiled class resource not found for ${type.name}")
        assertTrue(
            "@Keep is required on ${type.name} so minification preserves the fully qualified name typed Navigation resolves at runtime",
            String(bytecode, StandardCharsets.ISO_8859_1).contains("Landroidx/annotation/Keep;"),
        )
    }

    @Test
    fun organizationEntryKeepsItsDefaultFullyQualifiedNameForTypedNavigation() {
        assertEquals(
            "app.lawnchair.ui.preferences.navigation.OrganizationEntry",
            OrganizationEntry::class.java.name,
        )
        assertEquals(
            "app.lawnchair.ui.preferences.navigation.OrganizationEntry",
            OrganizationEntry.serializer().descriptor.serialName,
        )
    }

    @Test
    fun typedNavigationEnumArgumentsCarryTheKeepRetentionAnnotation() {
        listOf(
            OrganizationEntry::class.java,
            SearchRoute::class.java,
            SearchProviderId::class.java,
        ).forEach(::assertCarriesKeepAnnotation)
    }
}
