package app.lawnchair.organizer.diagnostics.export

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure formatter for the suggested organizer diagnostics export filename.
 *
 * Renders `organizer_diagnostics_yyyyMMdd_HHmmss_SSS.jsonl` from the export
 * timestamp captured once per export (the same value written to the header's
 * `exportedAtWallMillis`).
 *
 * Rendering is fixed to UTC with millisecond precision so the mapping from
 * instant to filename is injective: distinct instants never collide, unlike
 * device-local rendering, where a DST fall-back fold maps two instants to the
 * same wall-clock time. The result contains only `[A-Za-z0-9_.]` and carries
 * nothing but the generic prefix and the timestamp.
 *
 * Contract: docs/engineering/organizer-diagnostics.md §9 (Issue #288).
 */
object DiagnosticsExportFilename {

    const val PREFIX = "organizer_diagnostics"
    const val EXTENSION = ".jsonl"

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)

    fun format(exportedAtWallMillis: Long): String = PREFIX + "_" + timestampFormat.format(Instant.ofEpochMilli(exportedAtWallMillis)) + EXTENSION
}
