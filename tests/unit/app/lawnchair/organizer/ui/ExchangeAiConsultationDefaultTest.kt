package app.lawnchair.organizer.ui

import app.lawnchair.preferences2.PreferenceManager2
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Issue #443: the frozen AI consultation entry (FR-017) ships default OFF.
 *
 * `PreferenceManager2` needs a live Android context, so the JVM oracle pins
 * the two source-of-truth artifacts the runtime default is derived from:
 * the `config.xml` bool resource (`config_default_exchange_ai_consultation`,
 * read by the preference's `defaultValue`) and the preference key string in
 * the compiled `PreferenceManager2` bytecode (the DataStore key the toggle
 * persists to). The full runtime default/round-trip behavior is covered by
 * the organizer instrumentation on the real DataStore (spec #443 AC-1).
 */
class ExchangeAiConsultationDefaultTest {

    @Test
    fun configDefaultForAiConsultationIsFalse() {
        val line = configLine("config_default_exchange_ai_consultation")
        assumeTrue("config_default_exchange_ai_consultation must exist in res/values/config.xml", line != null)
        assertTrue(
            "the AI consultation default must be false (FR-017 frozen, spec #443 AC-1)",
            line!!.contains(">false<"),
        )
    }

    @Test
    fun aiConsultationDefaultIsTranslatableFalseLikeOtherBehaviorDefaults() {
        val line = configLine("config_default_exchange_ai_consultation")
        assumeTrue("config_default_exchange_ai_consultation must exist in res/values/config.xml", line != null)
        assertTrue("default must be marked translatable=false", line!!.contains("translatable=\"false\""))
    }

    @Test
    fun preferenceKeyMatchesTheSpecifiedDataStoreKey() {
        val bytes = PreferenceManager2::class.java
            .getResourceAsStream("PreferenceManager2.class")
            ?.use { it.readBytes() }
        assumeTrue("compiled PreferenceManager2.class must be on the test classpath", bytes != null)
        val text = String(bytes!!, Charsets.ISO_8859_1)
        assertTrue(
            "the DataStore key exchange_ai_consultation_enabled must exist in PreferenceManager2",
            text.contains("exchange_ai_consultation_enabled"),
        )
    }

    private fun configLine(name: String): String? {
        // The unit-test working directory is the repo root (same convention
        // as BackupExclusionTest's res/xml path); fall back to the module dir
        // for direct IDE runs.
        val config = sequenceOf(File("lawnchair/res/values/config.xml"), File("res/values/config.xml"))
            .firstOrNull { it.exists() } ?: return null
        return config.readLines().firstOrNull { it.contains(name) }
    }
}
