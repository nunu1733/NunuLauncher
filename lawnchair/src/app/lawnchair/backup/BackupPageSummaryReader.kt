package app.lawnchair.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.android.launcher3.LauncherSettings.Favorites
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Issue #233: read-only analysis of the `launcher.db` entry inside a backup
 * zip, producing a per-page summary of the saved workspace so the restore
 * confirmation screen can show what the single-page screenshot preview does
 * not cover. The backup zip is only read; nothing is persisted and the
 * launcher database is never written.
 */
sealed interface BackupPageSummaryResult {
    data class Available(val summary: BackupPageSummary) : BackupPageSummaryResult

    /** Typed failure: page info cannot be shown, but restore itself stays available. */
    data object Unavailable : BackupPageSummaryResult
}

data class BackupPageSummary(
    /** Non-empty workspace pages, ordered by screen id ascending. */
    val pages: List<BackupPageSummaryEntry>,
    /**
     * The screen the preview screenshot is expected to cover:
     * the workspaceScreens rank minimum, or the renderer's FIRST_SCREEN_ID (0)
     * when that table is absent.
     */
    val previewedScreenId: Long,
) {
    /** Pages exist that the screenshot may not show; caption and summary are shown. */
    val hasPagesOutsidePreview: Boolean get() = pages.any { it.screenId != previewedScreenId }
}

data class BackupPageSummaryEntry(
    val screenId: Long,
    val itemCount: Int,
    val folderCount: Int,
    val widgetCount: Int,
)

/** One row of the grouped favorites query: (screen, itemType, count). */
data class GroupedFavoritesRow(val screenId: Long, val itemType: Int, val count: Int)

private const val MAX_LAUNCHER_DB_BYTES = 64L * 1024 * 1024
private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
private const val MAX_ARCHIVE_COMPRESSED_BYTES = 256L * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 10_000
private const val CHUNK_SIZE_BYTES = 64 * 1024

/**
 * Pure aggregation of grouped favorites rows into a page summary. Folder
 * children are not double-counted (favorites rows own one row per folder);
 * hotseat rows are excluded by the query; empty screens never appear.
 */
object BackupPageAggregator {

    fun aggregate(rows: List<GroupedFavoritesRow>, firstWorkspaceScreenId: Long?): BackupPageSummary {
        val byScreen = rows.groupBy({ it.screenId }, { it })
        val pages = byScreen.keys.sorted().map { screen ->
            BackupPageSummaryEntry(
                screenId = screen,
                itemCount = rowsOfScreen(byScreen, screen).sumOf { it.count },
                folderCount = rowsOfScreen(byScreen, screen, Favorites.ITEM_TYPE_FOLDER).sumOf { it.count },
                widgetCount = (
                    rowsOfScreen(byScreen, screen, Favorites.ITEM_TYPE_APPWIDGET) +
                        rowsOfScreen(byScreen, screen, Favorites.ITEM_TYPE_CUSTOM_APPWIDGET)
                    ).sumOf { it.count },
            )
        }
        return BackupPageSummary(
            pages = pages,
            previewedScreenId = firstWorkspaceScreenId ?: FIRST_SCREEN_ID_FALLBACK,
        )
    }

    private fun rowsOfScreen(
        byScreen: Map<Long, List<GroupedFavoritesRow>>,
        screen: Long,
        itemType: Int? = null,
    ) = byScreen.getValue(screen).filter { itemType == null || it.itemType == itemType }

    /** LauncherPreviewRenderer draws FIRST_SCREEN_ID (0) when no screen table exists. */
    const val FIRST_SCREEN_ID_FALLBACK = 0L
}

internal sealed interface ZipExtractionResult {
    data object Extracted : ZipExtractionResult
    data object EntryNotFound : ZipExtractionResult
    data object DuplicateEntry : ZipExtractionResult
    data object EntryTooLarge : ZipExtractionResult
    data object ArchiveTooLarge : ZipExtractionResult
}

/**
 * Input stream that fails with [ArchiveBudgetExceededException] once more than
 * [maxBytes] have been read from the underlying compressed stream. ZipInputStream
 * output-side chunk checks cannot bound input consumed by a single read, so the
 * compressed budget is enforced here, on the raw stream.
 */
private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var bytesRead = 0L
    private var budgetThrown = false

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) count(read.toLong())
        return read
    }

    private fun count(bytes: Long) {
        bytesRead += bytes
        if (bytesRead > maxBytes && !budgetThrown) {
            // Throw exactly once. The extraction loop drains each entry fully
            // itself, but during use{} unwinding close() can still perform
            // reads; those must not mask the mapped result with a second
            // exception, and after the budget is spent there is no legitimate
            // consumer of further bytes.
            budgetThrown = true
            throw ArchiveBudgetExceededException
        }
    }
}

private object ArchiveBudgetExceededException : RuntimeException("compressed archive budget exceeded")

/**
 * Extracts the exact-name `launcher.db` entry from a zip stream into [target],
 * bounding the work done on unrelated entries too. Every entry (including
 * unrelated ones, which ZipInputStream would otherwise drain internally) is
 * read in [chunkSize]-byte chunks so cancellation is responsive and the
 * cumulative uncompressed budget is enforced; the compressed input budget is
 * enforced by [BoundedInputStream].
 */
internal suspend fun extractLauncherDbEntry(
    input: InputStream,
    target: File,
    maxEntryBytes: Long = MAX_LAUNCHER_DB_BYTES,
    maxArchiveUncompressedBytes: Long = MAX_ARCHIVE_UNCOMPRESSED_BYTES,
    maxArchiveCompressedBytes: Long = MAX_ARCHIVE_COMPRESSED_BYTES,
    maxEntries: Int = MAX_ARCHIVE_ENTRIES,
    chunkSize: Int = CHUNK_SIZE_BYTES,
): ZipExtractionResult {
    var sawLauncherDb = false
    var archiveUncompressedBytes = 0L
    var entryCount = 0
    val boundedInput = BoundedInputStream(input, maxArchiveCompressedBytes)
    boundedInput.use { stream ->
        java.util.zip.ZipInputStream(stream.buffered()).use { zip ->
            val chunk = ByteArray(chunkSize)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > maxEntries) return ZipExtractionResult.ArchiveTooLarge
                    val isLauncherDb = entry.name == LawnchairBackup.LAUNCHER_DB_FILE_NAME
                    if (isLauncherDb) {
                        if (sawLauncherDb) return ZipExtractionResult.DuplicateEntry
                        sawLauncherDb = true
                        if (entry.size > maxEntryBytes) return ZipExtractionResult.EntryTooLarge
                        target.outputStream().use { out ->
                            var written = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = zip.read(chunk)
                                if (read < 0) break
                                written += read
                                archiveUncompressedBytes += read
                                if (written > maxEntryBytes) return ZipExtractionResult.EntryTooLarge
                                if (archiveUncompressedBytes > maxArchiveUncompressedBytes) {
                                    return ZipExtractionResult.ArchiveTooLarge
                                }
                                out.write(chunk, 0, read)
                            }
                        }
                    } else {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = zip.read(chunk)
                            if (read < 0) break
                            archiveUncompressedBytes += read
                            if (archiveUncompressedBytes > maxArchiveUncompressedBytes) {
                                return ZipExtractionResult.ArchiveTooLarge
                            }
                        }
                    }
                }
            } catch (e: ArchiveBudgetExceededException) {
                return ZipExtractionResult.ArchiveTooLarge
            }
        }
    }
    return if (sawLauncherDb) ZipExtractionResult.Extracted else ZipExtractionResult.EntryNotFound
}

class BackupPageSummaryReader(
    private val context: Context,
    private val uri: Uri,
) {
    suspend fun read(): BackupPageSummaryResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var outcome: BackupPageSummaryResult = BackupPageSummaryResult.Unavailable
        try {
            tempFile = File.createTempFile("backup_page_summary", ".db", context.cacheDir)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val extraction = pfd.use {
                    java.io.FileInputStream(it.fileDescriptor).use { stream ->
                        extractLauncherDbEntry(stream, tempFile)
                    }
                }
                outcome = if (extraction != ZipExtractionResult.Extracted) {
                    Log.w(TAG, "launcher.db entry not analyzable: $extraction")
                    BackupPageSummaryResult.Unavailable
                } else {
                    val summary: BackupPageSummaryResult? = readSummaryFromDb(tempFile)
                    summary ?: BackupPageSummaryResult.Unavailable
                }
            } else {
                Log.w(TAG, "backup zip cannot be opened for page summary analysis")
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "failed to analyze backup pages", t)
        } finally {
            tempFile?.let { file ->
                if (!file.delete()) {
                    Log.w(TAG, "failed to delete temporary analysis file ${file.path}")
                }
            }
        }
        outcome
    }

    private fun readSummaryFromDb(dbFile: File): BackupPageSummaryResult? {
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val groupedRows = mutableListOf<GroupedFavoritesRow>()
                db.rawQuery(
                    """
                    SELECT screen, itemType, COUNT(*) FROM ${Favorites.TABLE_NAME}
                    WHERE container = ${Favorites.CONTAINER_DESKTOP}
                    GROUP BY screen, itemType
                    """.trimIndent(),
                    null,
                ).use { cursor -> cursor.forEachRow { groupedRows += cursor.toGroupedRow() } }
                val firstScreenId = firstWorkspaceScreenId(db)
                return BackupPageSummaryResult.Available(
                    BackupPageAggregator.aggregate(groupedRows.toList(), firstScreenId),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to read page summary from backup db", t)
            return null
        }
    }

    /**
     * workspaceScreens rank minimum. The table being absent is the fallback
     * case (the renderer draws FIRST_SCREEN_ID, 0); a present but malformed
     * table is a schema failure and propagates so the result becomes
     * Unavailable instead of silently suppressing the caption.
     */
    private fun firstWorkspaceScreenId(db: SQLiteDatabase): Long? {
        val tableExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(WORKSPACE_SCREENS_TABLE),
        ).use { it.moveToFirst() }
        if (!tableExists) return null
        db.rawQuery(
            "SELECT ${Favorites._ID} FROM $WORKSPACE_SCREENS_TABLE ORDER BY screenRank LIMIT 1",
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else BackupPageAggregator.FIRST_SCREEN_ID_FALLBACK
        }
    }

    private fun Cursor.toGroupedRow() = GroupedFavoritesRow(
        screenId = getLong(0),
        itemType = getInt(1),
        count = getInt(2),
    )

    private inline fun <T> Cursor.forEachRow(block: () -> T) {
        while (moveToNext()) block()
    }

    private companion object {
        const val TAG = "BackupPageSummaryReader"
        const val WORKSPACE_SCREENS_TABLE = "workspaceScreens"
    }
}
