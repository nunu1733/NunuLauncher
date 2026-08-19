package app.lawnchair.organizer.diagnostics.export

import android.content.Context
import android.net.Uri
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.journal.RunEventSerializer
import app.lawnchair.organizer.diagnostics.model.DeviceProfileSummary
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import com.android.launcher3.BuildConfig
import java.io.OutputStream
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Export writer for the organizer diagnostics journal.
 *
 * Serializes a journal snapshot to the accepted export format:
 * - A header line (JSON object with appVersion, journalSchemaVersion,
 *   exportedAtWallMillis, and optional deviceProfile).
 * - One line-delimited JSON event per journal entry, in ascending
 *   [RunEvent.journalSequence] order.
 *
 * The export does not mutate or prune the journal. Cancellation or
 * write failure leaves the journal intact.
 *
 * Contract: docs/engineering/organizer-diagnostics.md §9, D-10.
 */
object ExportWriter {

    private val exportJson: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    /**
     * The export header content (wrapped in a {"header": ...} envelope).
     */
    @Serializable
    data class ExportHeader(
        val appVersion: String,
        @EncodeDefault
        val journalSchemaVersion: Int = 1,
        val exportedAtWallMillis: Long,
        val deviceProfile: DeviceProfileSummary? = null,
    )

    /**
     * The top-level export line envelope. Creates the {"header": {...}}
     * shape required by D-10.
     */
    @Serializable
    data class ExportLine(
        val header: ExportHeader,
    )

    /**
     * Write the journal export to [outputStream].
     *
     * Events are sorted by [RunEvent.journalSequence] ascending before
     * serialization, so callers are not required to supply pre-sorted input.
     *
     * @param events All journal events (any order; will be sorted).
     * @param outputStream The writable destination (e.g. a SAF content URI
     *   opened via [android.content.ContentResolver.openOutputStream]).
     * @param deviceProfile An optional device profile to include in the header.
     *   If null, the writer searches for the last [PhaseCode.RUN_STARTED] event
     *   in [events] whose [RunEvent.deviceProfile] is non-null.
     */
    @Throws(java.io.IOException::class)
    fun write(
        events: List<RunEvent>,
        outputStream: OutputStream,
        deviceProfile: DeviceProfileSummary? = null,
    ) {
        val resolvedProfile = deviceProfile
            ?: events.lastOrNull { it.phase == PhaseCode.RUN_STARTED && it.deviceProfile != null }
                ?.deviceProfile

        val header = ExportHeader(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAtWallMillis = System.currentTimeMillis(),
            deviceProfile = resolvedProfile,
        )

        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            // Write header line wrapped in {"header": {...}} per D-10
            val headerLine = exportJson.encodeToString(ExportLine(header = header)) + "\n"
            writer.write(headerLine)

            // Write each event in ascending journalSequence order.
            // Sort here so callers are not required to supply pre-sorted input.
            for (event in events.sortedBy { it.journalSequence }) {
                val line = RunEventSerializer.encodeToString(event) + "\n"
                writer.write(line)
            }
            writer.flush()
        }
    }

    /**
     * Read all events from the journal using the live [DiagnosticsPort.snapshot].
     *
     * Delegates to the production [DiagnosticsPort] so that the snapshot is
     * obtained under the same synchronization as concurrent [DiagnosticsPort.emit]
     * calls. Export does not mutate or prune the journal.
     *
     * @param diagnosticsPort The live diagnostics port (production instance).
     * @return List of events in ascending journal sequence order, or empty
     *   list if the journal is empty or unavailable.
     */
    @JvmStatic
    fun readJournalEvents(diagnosticsPort: DiagnosticsPort): List<RunEvent> {
        return try {
            diagnosticsPort.snapshot()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Write the journal export to a content [Uri] via SAF.
     *
     * Reads events from the live [DiagnosticsPort] snapshot, then writes the
     * export to the URI. The journal is not mutated.
     *
     * @param context Application context.
     * @param diagnosticsPort The live diagnostics port for stable snapshot.
     * @param uri The content URI to write to (from SAF CreateDocument or
     *   similar).
     * @throws java.io.IOException if writing fails.
     * @throws java.io.FileNotFoundException if the URI cannot be opened.
     */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun writeToUri(context: Context, diagnosticsPort: DiagnosticsPort, uri: Uri) {
        val events = readJournalEvents(diagnosticsPort)
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            write(events, outputStream)
        } ?: throw java.io.FileNotFoundException("Cannot open output stream for $uri")
    }
}
