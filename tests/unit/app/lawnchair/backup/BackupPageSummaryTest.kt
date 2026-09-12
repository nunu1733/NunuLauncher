package app.lawnchair.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// LauncherSettings.Favorites item types, kept as literals: they mirror the
// favorites db rows this analysis reads and keep the test JVM-pure.
private const val ITEM_TYPE_APPLICATION = 0
private const val ITEM_TYPE_FOLDER = 2
private const val ITEM_TYPE_APPWIDGET = 4
private const val ITEM_TYPE_CUSTOM_APPWIDGET = 5
private const val ITEM_TYPE_DEEP_SHORTCUT = 6
private const val LAUNCHER_DB_FILE_NAME = "launcher.db"

/**
 * Issue #233 spec AC-1..AC-5: the pure JVM surfaces of the backup page
 * summary analysis — the aggregation of grouped favorites rows and the
 * bounded, exact-name extraction of the `launcher.db` zip entry.
 */
class BackupPageSummaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun row(screen: Long, itemType: Int, count: Int) = GroupedFavoritesRow(screen, itemType, count)

    // ---- Aggregation (spec 集計定義) ----

    @Test
    fun `multi page backup aggregates per page counts`() {
        val summary = BackupPageAggregator.aggregate(
            listOf(
                row(0, ITEM_TYPE_APPLICATION, 10),
                row(0, ITEM_TYPE_FOLDER, 1),
                row(0, ITEM_TYPE_APPWIDGET, 1),
                row(1, ITEM_TYPE_APPLICATION, 4),
                row(1, ITEM_TYPE_DEEP_SHORTCUT, 2),
                row(1, ITEM_TYPE_FOLDER, 2),
            ),
            firstWorkspaceScreenId = 0,
        )
        assertEquals(2, summary.pages.size)
        assertEquals(BackupPageSummaryEntry(0, 12, 1, 1), summary.pages[0])
        assertEquals(BackupPageSummaryEntry(1, 8, 2, 0), summary.pages[1])
        assertTrue(summary.hasPagesOutsidePreview)
    }

    @Test
    fun `sparse screen ids are compacted to rank order`() {
        val summary = BackupPageAggregator.aggregate(
            listOf(
                row(25, ITEM_TYPE_APPLICATION, 1),
                row(3, ITEM_TYPE_APPLICATION, 2),
            ),
            firstWorkspaceScreenId = 3,
        )
        assertEquals(listOf(3L, 25L), summary.pages.map { it.screenId })
    }

    @Test
    fun `custom widgets count as widgets`() {
        val summary = BackupPageAggregator.aggregate(
            listOf(
                row(0, ITEM_TYPE_APPWIDGET, 1),
                row(0, ITEM_TYPE_CUSTOM_APPWIDGET, 2),
            ),
            firstWorkspaceScreenId = 0,
        )
        assertEquals(3, summary.pages[0].widgetCount)
        assertEquals(3, summary.pages[0].itemCount)
    }

    @Test
    fun `single page on first screen hides caption`() {
        val summary = BackupPageAggregator.aggregate(
            listOf(row(0, ITEM_TYPE_APPLICATION, 5)),
            firstWorkspaceScreenId = 0,
        )
        assertFalse(summary.hasPagesOutsidePreview)
    }

    @Test
    fun `empty first screen with items on later screen shows caption`() {
        // workspaceScreens absent: renderer draws FIRST_SCREEN_ID (0).
        val summary = BackupPageAggregator.aggregate(
            listOf(row(1, ITEM_TYPE_APPLICATION, 5)),
            firstWorkspaceScreenId = null,
        )
        assertEquals(0L, summary.previewedScreenId)
        assertTrue(summary.hasPagesOutsidePreview)
    }

    @Test
    fun `workspaceScreens first screen drives the coverage predicate`() {
        // Rank minimum 7 while items live on 7 and 9: page 9 is outside coverage.
        val summary = BackupPageAggregator.aggregate(
            listOf(
                row(7, ITEM_TYPE_APPLICATION, 1),
                row(9, ITEM_TYPE_APPLICATION, 1),
            ),
            firstWorkspaceScreenId = 7,
        )
        assertTrue(summary.hasPagesOutsidePreview)
    }

    @Test
    fun `single page matching previewed screen still hides caption with table`() {
        val summary = BackupPageAggregator.aggregate(
            listOf(row(1, ITEM_TYPE_APPLICATION, 5)),
            firstWorkspaceScreenId = 1,
        )
        assertFalse(summary.hasPagesOutsidePreview)
    }

    // ---- Zip extraction (spec zip 読み取りの安全契約) ----

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, data) ->
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun extract(
        zipBytes: ByteArray,
        maxEntryBytes: Long = 64L * 1024 * 1024,
        maxArchiveUncompressedBytes: Long = 512L * 1024 * 1024,
        maxArchiveCompressedBytes: Long = 512L * 1024 * 1024,
        maxEntries: Int = 10_000,
    ): Pair<ZipExtractionResult, File> {
        val target = tempFolder.newFile("extracted.db")
        val result = runBlocking {
            extractLauncherDbEntry(
                ByteArrayInputStream(zipBytes),
                target,
                maxEntryBytes = maxEntryBytes,
                maxArchiveUncompressedBytes = maxArchiveUncompressedBytes,
                maxArchiveCompressedBytes = maxArchiveCompressedBytes,
                maxEntries = maxEntries,
            )
        }
        return result to target
    }

    @Test
    fun `exact launcher dot db entry is extracted byte for byte`() {
        val dbBytes = ByteArray(1024) { it.toByte() }
        val (result, target) = extract(
            zip(
                mapOf(
                    "info.pb" to byteArrayOf(1),
                    LAUNCHER_DB_FILE_NAME to dbBytes,
                    "wallpaper.png" to ByteArray(256),
                ),
            ),
        )
        assertEquals(ZipExtractionResult.Extracted, result)
        assertTrue(target.readBytes().contentEquals(dbBytes))
    }

    @Test
    fun `similar but non matching entry names are not extracted`() {
        val (result, _) = extract(zip(mapOf("../launcher.db" to byteArrayOf(1), "x/launcher.db" to byteArrayOf(2))))
        assertEquals(ZipExtractionResult.EntryNotFound, result)
    }

    @Test
    fun `duplicate launcher db entry is unavailable`() {
        // ZipOutputStream rejects duplicate names, so a hostile zip is written
        // as raw STORED local-file records (ZipInputStream reads those fine).
        val bytes = java.io.ByteArrayOutputStream()
        repeat(2) { i ->
            bytes.writeStoredEntry(LAUNCHER_DB_FILE_NAME, byteArrayOf(i.toByte()))
        }
        val (result, _) = extract(bytes.toByteArray())
        assertEquals(ZipExtractionResult.DuplicateEntry, result)
    }

    /** One STORED local-file record, little-endian, without a central directory. */
    private fun java.io.ByteArrayOutputStream.writeStoredEntry(name: String, data: ByteArray) {
        val nameBytes = name.toByteArray()
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        val header = java.nio.ByteBuffer
            .allocate(30 + nameBytes.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x04034b50) // local file header signature
        header.putShort(20) // version needed
        header.putShort(0) // flags
        header.putShort(0) // method: STORED
        header.putShort(0) // time
        header.putShort(0) // date
        header.putInt(crc.toInt())
        header.putInt(data.size) // compressed size
        header.putInt(data.size) // uncompressed size
        header.putShort(nameBytes.size.toShort())
        header.putShort(0) // extra length
        header.put(nameBytes)
        write(header.array())
        write(data)
    }

    @Test
    fun `missing launcher db entry is unavailable`() {
        val (result, _) = extract(zip(mapOf("info.pb" to byteArrayOf(1))))
        assertEquals(ZipExtractionResult.EntryNotFound, result)
    }

    @Test
    fun `oversized launcher db entry is rejected before writing beyond the cap`() {
        val (result, target) = extract(
            zip(mapOf(LAUNCHER_DB_FILE_NAME to ByteArray(1024))),
            maxEntryBytes = 512,
        )
        assertEquals(ZipExtractionResult.EntryTooLarge, result)
        assertTrue(target.length() <= 512)
    }

    @Test
    fun `archive wide uncompressed budget bounds unrelated entries`() {
        val (result, _) = extract(
            zip(
                mapOf(
                    "big.bin" to ByteArray(2048),
                    LAUNCHER_DB_FILE_NAME to ByteArray(16),
                ),
            ),
            maxArchiveUncompressedBytes = 1024,
        )
        assertEquals(ZipExtractionResult.ArchiveTooLarge, result)
    }

    @Test
    fun `entry count limit bounds the scan`() {
        val entries = (1..20).associate { "f$it" to byteArrayOf(it.toByte()) }
        val (result, _) = extract(zip(entries), maxEntries = 5)
        assertEquals(ZipExtractionResult.ArchiveTooLarge, result)
    }

    @Test
    fun `compressed input budget bounds the scan`() {
        val (result, _) = extract(
            zip(mapOf("big.bin" to ByteArray(4096), LAUNCHER_DB_FILE_NAME to ByteArray(16))),
            maxArchiveCompressedBytes = 128,
        )
        assertEquals(ZipExtractionResult.ArchiveTooLarge, result)
    }

    @Test
    fun `truncated zip stream surfaces as an exception for the reader to map to unavailable`() {
        val full = zip(mapOf(LAUNCHER_DB_FILE_NAME to ByteArray(2048) { it.toByte() }))
        val target = tempFolder.newFile("truncated.db")
        val truncated = full.copyOf(full.size / 2)
        val failure = runCatching {
            runBlocking {
                extractLauncherDbEntry(ByteArrayInputStream(truncated), target)
            }
        }
        // Truncation surfaces as ZipException or EOFException depending on where
        // the stream is cut; either way the reader maps it to Unavailable.
        assertTrue(
            "expected zip/EOF exception on truncated stream, got $failure",
            failure.exceptionOrNull() is java.util.zip.ZipException ||
                failure.exceptionOrNull() is java.io.EOFException,
        )
    }

    @Test
    fun `temporary target file lives under caller controlled path`() {
        val dir = tempFolder.newFolder("cache")
        val target = File(dir, "work.db")
        runBlocking {
            extractLauncherDbEntry(
                ByteArrayInputStream(zip(mapOf(LAUNCHER_DB_FILE_NAME to byteArrayOf(7)))),
                target,
            )
        }
        assertEquals(1, dir.listFiles()!!.size)
        assertEquals(target, dir.listFiles()!![0])
    }
}
