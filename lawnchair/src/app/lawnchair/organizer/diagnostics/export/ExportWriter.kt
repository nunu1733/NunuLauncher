package app.lawnchair.organizer.diagnostics.export

import android.content.Context
import android.net.Uri
import app.lawnchair.organizer.diagnostics.journal.RunEventSerializer
import app.lawnchair.organizer.diagnostics.model.DeviceProfileSummary
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import com.android.launcher3.BuildConfig
import java.io.File
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
        ignoreUnknownKeys = true
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
     * @param events All journal events in ascending sequence order (typically
     *   from [app.lawnchair.organizer.diagnostics.journal.JournalStore.readAllEvents]).
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

            // Write each event in ascending sequence order
            for (event in events) {
                val line = RunEventSerializer.encodeToString(event) + "\n"
                writer.write(line)
            }
            writer.flush()
        }
    }

    /**
     * Read all events from the journal file at the standard diagnostics path.
     *
     * @param context Application context for resolving [Context.filesDir].
     * @return List of events in ascending journal sequence order, or empty
     *   list if the journal file does not exist or cannot be read.
     */
    @JvmStatic
    fun readJournalEvents(context: Context): List<RunEvent> {
        val journalFile = getJournalFile(context)
        if (!journalFile.exists() || journalFile.length() == 0L) {
            return emptyList()
        }
        return try {
            journalFile.readLines().mapNotNull { line ->
                if (line.isBlank()) {
                    null
                } else {
                    try {
                        RunEventSerializer.decode(line.toByteArray(Charsets.UTF_8))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Write the journal export to a content [Uri] via SAF.
     *
     * Reads events from the standard journal file, then writes the export
     * to the URI. The journal is not mutated.
     *
     * @param context Application context.
     * @param uri The content URI to write to (from SAF CreateDocument or
     *   similar).
     * @throws java.io.IOException if writing fails.
     * @throws java.io.FileNotFoundException if the URI cannot be opened.
     */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun writeToUri(context: Context, uri: Uri) {
        val events = readJournalEvents(context)
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            write(events, outputStream)
        } ?: throw java.io.FileNotFoundException("Cannot open output stream for $uri")
    }

    /**
     * Get the standard journal file path for the given context.
     */
    internal fun getJournalFile(context: Context): File {
        val diagnosticsDir = File(context.filesDir, "organizer_diagnostics")
        return File(diagnosticsDir, "organizer_diagnostics.journal")
    }
}
