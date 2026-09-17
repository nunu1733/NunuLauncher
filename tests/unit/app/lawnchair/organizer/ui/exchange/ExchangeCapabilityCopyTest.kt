package app.lawnchair.organizer.ui.exchange

import com.android.launcher3.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #327 AC-4/AC-5: the exchange entries carry a capability explanation
 * in user-language concrete examples with the "no direct change" statement
 * and the expected conversation flow (one request out, one final proposal
 * back, interview inside the external AI app). The composable renders the
 * copy through [exchangeCapabilityExampleResourceIds] plus the fixed keys,
 * so this test pins that structure and verifies both locale resource files
 * declare every key (ja正本 + en, spec 123 contract) with the two contract
 * elements the spec fixes.
 */
class ExchangeCapabilityCopyTest {

    @Test
    fun capabilityExampleListIsThePinnedUserLanguageStructure() {
        // Spec 327 AC-4: concrete user-language examples, never schema terms.
        // The list is exactly what the entry rows render (single source).
        assertEquals(
            listOf(
                R.string.exchange_capability_example_frequent,
                R.string.exchange_capability_example_group,
                R.string.exchange_capability_example_keep,
                R.string.exchange_capability_example_front,
                R.string.exchange_capability_example_minimal_change,
            ),
            exchangeCapabilityExampleResourceIds(),
        )
        assertEquals(5, exchangeCapabilityExampleResourceIds().distinct().size)
    }

    @Test
    fun capabilityKeysExistInBothLocales() {
        val names = resourceNames(
            listOf(R.string.exchange_capability_title) +
                exchangeCapabilityExampleResourceIds() +
                listOf(
                    R.string.exchange_capability_no_direct_change,
                    R.string.exchange_capability_flow,
                    R.string.exchange_transport_success,
                ),
        )
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            for (name in names) {
                assertTrue("$name must exist in $localeDir", xml.contains("name=\"$name\""))
            }
        }
    }

    @Test
    fun fixedContractElementsArePresentInTheCopy() {
        // Spec 327 AC-4: "the AI never changes the home screen directly".
        // Spec 327 AC-5: one request out, one final proposal back.
        val ja = lawnchairStringsXml("values-ja").readText()
        val en = lawnchairStringsXml("values").readText()
        assertTrue(
            "ja no-direct-change statement",
            ja.contains("AIがホーム画面を直接変更することはありません"),
        )
        assertTrue(
            "en no-direct-change statement",
            en.contains("never changes your home screen directly"),
        )
        val jaFlow = ja.substringAfter("name=\"exchange_capability_flow\"").substringBefore("</string>")
        assertEquals(2, Regex("1回").findAll(jaFlow).count())
        val enFlow = en.substringAfter("name=\"exchange_capability_flow\"").substringBefore("</string>")
        assertTrue("en flow states the single final proposal", enFlow.contains("one final proposal"))
    }

    private fun resourceNames(ids: List<Int>): List<String> {
        val fields = R.string::class.java.declaredFields
        val byId = fields.associate { it.name to it.getInt(null) }
        return ids.map { id -> byId.entries.first { it.value == id }.key }
    }

    private fun lawnchairStringsXml(localeDir: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("lawnchair strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }
}
