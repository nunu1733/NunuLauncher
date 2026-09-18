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

    @Test
    fun transportSuccessCopyStaysTransportNeutral() {
        // Spec 327 AC-5 + review fix: TRANSPORT_SUCCESS is shared by the
        // clipboard, share, and file-save paths, so it must never claim the
        // request was already sent to the AI — the pre-send disclosure
        // contract says nothing is sent until the user hands it over.
        val ja = lawnchairStringsXml("values-ja").readText()
        val en = lawnchairStringsXml("values").readText()
        val jaSuccess = ja.substringAfter("name=\"exchange_transport_success\"").substringBefore("</string>")
        val enSuccess = en.substringAfter("name=\"exchange_transport_success\"").substringBefore("</string>")
        assertTrue("ja success must list the local handoffs", jaSuccess.contains("コピー・共有・保存しました"))
        assertTrue("en success must list the local handoffs", enSuccess.contains("copied, shared, or saved"))
        assertTrue("ja success must not claim the AI already received it", !jaSuccess.contains("送信しました"))
        assertTrue("en success must not claim the AI already received it", !enSuccess.startsWith("Sent"))
    }

    /**
     * Issue #337 (spec 337 D-2, review finding): the pre-send disclosure and
     * the privacy-mode label must describe what the v4 package actually
     * contains — the category catalog (including user-defined entries) is
     * always disclosed, and the user-defined category NAMES follow the same
     * tier as app labels.
     */
    @Test
    fun disclosureCopyStatesTheV4CategoryReferenceDisclosure() {
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            val redacted = xml.substringAfter("name=\"exchange_disclosure_redacted\"").substringBefore("</string>")
            val labels = xml.substringAfter("name=\"exchange_disclosure_labels_included\"").substringBefore("</string>")
            val privacyLabel = xml.substringAfter("name=\"exchange_privacy_labels\"").substringBefore("</string>")
            val categoryWord = if (localeDir == "values-ja") "カテゴリ" else "categor"
            assertTrue(
                "$localeDir redacted copy must disclose the category list without names",
                redacted.contains(categoryWord) && (redacted.contains("含まれません") || redacted.contains("without their names")),
            )
            assertTrue(
                "$localeDir label-inclusive copy must disclose user-defined category names",
                labels.contains(categoryWord) &&
                    (labels.contains("ユーザー定義") || labels.contains("user-defined")),
            )
            assertTrue(
                "$localeDir privacy-mode label must not promise app-name-only disclosure",
                privacyLabel.contains(categoryWord),
            )
        }
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
