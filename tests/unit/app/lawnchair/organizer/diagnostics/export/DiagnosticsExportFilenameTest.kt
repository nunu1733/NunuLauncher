package app.lawnchair.organizer.diagnostics.export

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #288: deterministic, injective, privacy-safe suggested export
 * filename. Rendering is fixed to UTC with millisecond precision so distinct
 * instants never collide (a device-local rendering would collide on a DST
 * fall-back fold), and the name carries nothing but the generic prefix and
 * the timestamp.
 */
class DiagnosticsExportFilenameTest {

    /** 2026-09-11T23:02:43.969Z — the example from Issue #288. */
    private val exampleInstantMillis: Long = 1789167763969L

    @Test
    fun epochZeroRendersAsUtcWallTime() {
        assertEquals(
            "organizer_diagnostics_19700101_000000_000.jsonl",
            DiagnosticsExportFilename.format(0L),
        )
    }

    @Test
    fun issueExampleInstantRendersExpectedName() {
        assertEquals(
            "organizer_diagnostics_20260911_230243_969.jsonl",
            DiagnosticsExportFilename.format(exampleInstantMillis),
        )
    }

    @Test
    fun renderingIgnoresDeviceDefaultTimezone() {
        // The name must be a function of the instant alone: the same millis
        // render identically regardless of the device zone the JVM reports.
        val original = System.getProperty("user.timezone")
        try {
            System.setProperty("user.timezone", "Pacific/Kiritimati")
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
            val positiveOffsetName = DiagnosticsExportFilename.format(exampleInstantMillis)

            System.setProperty("user.timezone", "Pacific/Midway")
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Midway"))
            val negativeOffsetName = DiagnosticsExportFilename.format(exampleInstantMillis)

            assertEquals(positiveOffsetName, negativeOffsetName)
            assertEquals(
                "organizer_diagnostics_20260911_230243_969.jsonl",
                positiveOffsetName,
            )
        } finally {
            System.setProperty("user.timezone", original ?: "")
            java.util.TimeZone.setDefault(null)
        }
    }

    @Test
    fun dstFallBackFoldInstantsProduceDistinctNames() {
        // America/New_York 2026 fall-back: both instants render the same
        // local wall time 2026-11-01 01:30:00.123, so a device-local
        // rendering would collide. UTC rendering must not.
        val edtInstant = Instant.ofEpochMilli(1793511000123L) // 2026-11-01T05:30:00.123Z
        val estInstant = Instant.ofEpochMilli(1793514600123L) // 2026-11-01T06:30:00.123Z

        val newYork = ZoneId.of("America/New_York")
        val pattern = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        assertEquals(
            "Fixture validity: the pair must fold to one local wall time",
            LocalDateTime.ofInstant(edtInstant, newYork).format(pattern),
            LocalDateTime.ofInstant(estInstant, newYork).format(pattern),
        )

        assertNotEquals(
            "Distinct instants must never reuse a filename",
            DiagnosticsExportFilename.format(edtInstant.toEpochMilli()),
            DiagnosticsExportFilename.format(estInstant.toEpochMilli()),
        )
    }

    @Test
    fun sameInstantAlwaysProducesSameName() {
        repeat(3) {
            assertEquals(
                DiagnosticsExportFilename.format(exampleInstantMillis),
                DiagnosticsExportFilename.format(exampleInstantMillis),
            )
        }
    }

    @Test
    fun namesAreFilesystemSafeAndJsonlSuffixed() {
        val samples = listOf(
            0L,
            -1L, // pre-epoch, still renders a safe UTC name (19691231_235959_999)
            exampleInstantMillis,
            Instant.parse("2020-02-29T12:00:00.500Z").toEpochMilli(),
            Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli(),
        )
        val safe = Regex("[A-Za-z0-9_.]+")
        for (millis in samples) {
            val name = DiagnosticsExportFilename.format(millis)
            assertTrue("Name must be non-empty", name.isNotEmpty())
            assertTrue("Name must contain only [A-Za-z0-9_.]: $name", safe.matches(name))
            assertTrue("Name must end in .jsonl: $name", name.endsWith(".jsonl"))
            assertTrue("Name must not contain ':': $name", !name.contains(':'))
        }
    }

    @Test
    fun namesCarryNothingBeyondPrefixAndTimestamp() {
        val name = DiagnosticsExportFilename.format(exampleInstantMillis)
        val timestampSegment = name
            .removePrefix(DiagnosticsExportFilename.PREFIX + "_")
            .removeSuffix(DiagnosticsExportFilename.EXTENSION)

        assertTrue(
            "Timestamp segment must be date_time_millis digits: $timestampSegment",
            timestampSegment.matches(Regex("\\d{8}_\\d{6}_\\d{3}")),
        )
        assertEquals(
            "Name must be exactly prefix + '_' + timestamp + extension",
            DiagnosticsExportFilename.PREFIX + "_" + timestampSegment + DiagnosticsExportFilename.EXTENSION,
            name,
        )
    }
}
