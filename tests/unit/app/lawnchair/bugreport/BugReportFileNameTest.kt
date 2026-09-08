package app.lawnchair.bugreport

import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the Issue #242 crash report file name: locale-independent, free
 * of path separators and portable characters only, deterministic for the same
 * (appName, timestamp, default timezone), with a human-readable header.
 */
class BugReportFileNameTest {

    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    private fun fixedDate(): Date = GregorianCalendar(
        2026,
        Calendar.SEPTEMBER,
        7,
        19,
        9,
        17,
    ).apply { timeZone = TimeZone.getTimeZone("Asia/Tokyo") }.time

    /**
     * Reproduces the Issue #242 device observation: under the ja locale the
     * current-locale date-time instance renders "2026/09/07 19:09:17", whose
     * slashes make File.createNewFile() throw inside save().
     */
    @Test
    fun `ja locale does not reintroduce path separators in the file name`() {
        Locale.setDefault(Locale.JAPAN)

        val fileName = buildReportFileName("Lawnchair", fixedDate())

        assertFalse(fileName.contains("/"))
        assertFalse(fileName.contains(File.separatorChar))
    }

    @Test
    fun `file name stays safe across locales that style dates with separators`() {
        val locales = listOf(
            Locale.JAPAN,
            Locale.US,
            Locale.GERMANY,
            Locale("fi", "FI"),
            Locale("ar", "SA"),
        )

        for (locale in locales) {
            Locale.setDefault(locale)

            val fileName = buildReportFileName("Lawnchair", fixedDate())

            assertFalse("$locale contained /: $fileName", fileName.contains("/"))
            for (char in fileName) {
                assertTrue(
                    "$locale used non-portable char ${char.code} in $fileName",
                    char == ' ' ||
                        char in 'a'..'z' ||
                        char in 'A'..'Z' ||
                        char in '0'..'9' ||
                        char == '-' ||
                        char == '_' ||
                        char == '.',
                )
            }
        }
    }

    @Test
    fun `file name is deterministic for the same app name timestamp and timezone`() {
        val zones = listOf(TimeZone.getTimeZone("UTC"), TimeZone.getTimeZone("Asia/Tokyo"))

        for (zone in zones) {
            TimeZone.setDefault(zone)
            Locale.setDefault(Locale.JAPAN)

            val first = buildReportFileName("Lawnchair", fixedDate())
            val second = buildReportFileName("Lawnchair", fixedDate())

            assertEquals(first, second)
        }
    }

    @Test
    fun `header keeps a human readable timestamp`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

        val fileName = buildReportFileName("Lawnchair", fixedDate())

        assertEquals("Lawnchair bug report 2026-09-07_19-09-17", fileName)
    }
}
