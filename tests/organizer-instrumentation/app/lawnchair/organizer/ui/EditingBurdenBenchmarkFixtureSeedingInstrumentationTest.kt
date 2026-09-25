package app.lawnchair.organizer.ui

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ReservationOverlapAcceptance
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.IntSet
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #441 AC-2/AC-3/AC-4: the editing-burden benchmark fixture seeding
 * contract, verified through the production seams.
 *
 * - Seeding the same fixture input table twice yields the identical
 *   normalized workspace projection (same input -> same fixture).
 * - The fixture graph replacement preserves hotseat roots and their
 *   descendants plus rows overlapping the captured reserved workspace
 *   regions, and every fixture span stays disjoint from the reservations
 *   (`ReservationOverlapAcceptance`, the single production acceptance
 *   predicate, is false for every fixture row).
 * - The identity composition matches the accepted spec: distinct launch
 *   identities for regular icons and exactly two duplicate pairs keyed by
 *   component + profile.
 * - The default (restore) mode leaves no persistent residue. The measurement
 *   setup path runs the same seeding with `-e persist true` and skips the
 *   restore (spec plan A-12); it is intentionally not part of the oracle.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EditingBurdenBenchmarkFixtureSeedingInstrumentationTest {

    private lateinit var context: Context
    private lateinit var launcher: LauncherAppState
    private lateinit var preferenceManager: PreferenceManager2
    private var originalSmartspaceEnabled: Boolean = false
    private var originalRows: List<ContentValues> = emptyList()
    private var initialReservations: List<ReservedWorkspaceRegion> = emptyList()
    private var reloadLatch: CountDownLatch? = null
    private val modelCallbacks = object : BgDataModel.Callbacks {
        override fun finishBindingItems(pagesBoundFirst: IntSet) {
            reloadLatch?.countDown()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        preferenceManager = PreferenceManager2.getInstance(context)
        originalSmartspaceEnabled = preferenceManager.enableSmartspace.firstBlocking()
        originalRows = snapshotFavorites()
        preferenceManager.enableSmartspace.setBlocking(true)
        check(FeatureFlags.topQsbOnFirstScreenEnabled(context)) {
            "Issue #441 fixture requires the default QSB-on first screen"
        }
        // The instrumentation host may start the app process without the home
        // activity, so no ambient model load is guaranteed. Drive the
        // production reload seam explicitly instead of waiting for one.
        reloadAndWait()
    }

    @After
    fun tearDown() {
        try {
            if (!persistMode() && ::preferenceManager.isInitialized) {
                // Restore the original QSB semantics before making original
                // rows visible again (same ordering rationale as Issue #52).
                preferenceManager.enableSmartspace.setBlocking(originalSmartspaceEnabled)
                if (::launcher.isInitialized) {
                    restoreFavorites(originalRows)
                    reloadAndWait()
                }
            }
        } finally {
            if (::preferenceManager.isInitialized) {
                preferenceManager.enableSmartspace.setBlocking(originalSmartspaceEnabled)
            }
            if (::launcher.isInitialized) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    launcher.model.removeCallbacks(modelCallbacks)
                }
            }
        }
    }

    @Test
    fun fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations() {
        val capture = LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).captureCurrent(CaptureId("editing-burden-fixture-initial"))
        initialReservations = capture.layoutState.reservedWorkspaceRegions
        val reservations = initialReservations
        val columns = capture.layoutState.deviceCapabilities.columns
        val rows = capture.layoutState.deviceCapabilities.rows

        seedFixture(columns, rows, reservations)
        launcher.model.modelDbController.clearEmptyDbFlag()
        reloadAndWait()
        val preservedBefore = preservedRows()
        assertFixtureContract(columns, rows, reservations)
        assertReservationDisjoint(reservations)
        val first = normalizedProjection()

        seedFixture(columns, rows, reservations)
        launcher.model.modelDbController.clearEmptyDbFlag()
        reloadAndWait()
        val second = normalizedProjection()

        assertEquals(
            "Same fixture input must produce the identical normalized fixture",
            first,
            second,
        )
        assertEquals(
            "Preserved rows (hotseat + reserved) must survive both seedings",
            preservedBefore,
            preservedRows(),
        )

        if (!persistMode()) {
            restoreFavorites(originalRows)
            reloadAndWait()
            assertEquals(
                "Restore mode must leave no persistent residue",
                originalRows,
                snapshotFavorites(),
            )
        }
    }

    /** Data flow step 2 (plan A-11): replace the fixture graph deterministically. */
    private fun seedFixture(
        columns: Int,
        rows: Int,
        reservations: List<ReservedWorkspaceRegion>,
    ) {
        val db = launcher.model.modelDbController.db
        val current = snapshotFavorites()
        val preservedIds = preservedIds(current, reservations)
        db.beginTransaction()
        try {
            current.forEach { row ->
                val id = requireNotNull(row.getAsLong(Favorites._ID))
                if (id !in preservedIds) {
                    db.delete(
                        Favorites.TABLE_NAME,
                        "${Favorites._ID}=?",
                        arrayOf(id.toString()),
                    )
                }
            }
            val occupied = occupiedCells(reservations, current, preservedIds)
            val folderId = launcher.model.modelDbController.generateNewItemId()
            FIXTURE_LAYOUT.forEachIndexed { screen, screenItems ->
                screenItems.forEach { item ->
                    val cell = nextFreeCell(screen, columns, rows, occupied)
                        ?: error("Fixture does not fit on screen $screen (columns=$columns rows=$rows)")
                    if (item == FIXTURE_FOLDER) {
                        insertFolderRow(folderId, screen, cell.x, cell.y)
                    } else {
                        insertAppRow(alias(item.aliasIndex), screen, cell.x, cell.y)
                    }
                    occupy(occupied, screen, cell.x, cell.y, 1, 1)
                }
            }
            // F01/F35 are the designated folder content identities (A-10).
            // The loader converts a 1-item folder to a plain icon
            // (LAUNCHER_FOLDER_CONVERTED_TO_ICON), so the fixture folder
            // always holds two seeded items to stay stable.
            insertFolderChildRow(alias(1), folderId)
            insertFolderChildRow(alias(35), folderId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Issue #441 AC-4 + A-8/A-10: structure, identity, and capacity contract. */
    private fun assertFixtureContract(
        columns: Int,
        rows: Int,
        reservations: List<ReservedWorkspaceRegion>,
    ) {
        val current = snapshotFavorites()
        val desktopApps = current.filter {
            it.getAsInteger(Favorites.ITEM_TYPE) == Favorites.ITEM_TYPE_APPLICATION &&
                it.getAsInteger(Favorites.CONTAINER) == Favorites.CONTAINER_DESKTOP
        }
        val folders = current.filter {
            it.getAsInteger(Favorites.ITEM_TYPE) == Favorites.ITEM_TYPE_FOLDER
        }
        val folder = folders.single()
        val folderChildren = current.filter {
            it.getAsLong(Favorites.CONTAINER) == folder.getAsLong(Favorites._ID)
        }
        assertEquals("Fixture must contain 35 desktop app icons", 35, desktopApps.size)
        assertEquals(
            "Folder content must be the designated aliases F01/F35",
            setOf(alias(1).component.className, alias(35).component.className),
            folderChildren.mapNotNull { intentComponent(it) }.toSet(),
        )
        assertTrue(
            "Fixture folder must keep at least two items or the loader unwraps it",
            folderChildren.size >= 2,
        )

        // Identity composition: distinct component+profile identities with
        // exactly two duplicate pairs (page 0 and page 1, two rows each).
        val identityGroups = desktopApps.groupBy { launchIdentity(it) }
        val duplicates = identityGroups.filterValues { it.size > 1 }
        assertEquals("Fixture must contain exactly two duplicate identity groups", 2, duplicates.size)
        assertTrue(
            "Each duplicate group must hold exactly two rows",
            duplicates.values.all { it.size == 2 },
        )
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle())
        assertEquals(
            "Duplicate pairs must be the designated aliases F02/F03",
            setOf(
                "${alias(2).component.className}#$serial",
                "${alias(3).component.className}#$serial",
            ),
            duplicates.keys.toSet(),
        )
        assertEquals(
            "Fixture must use 33 distinct launch identities on the desktop",
            33,
            identityGroups.size,
        )

        // Page composition (A-8): page 0 = 15 icons + folder, page 1 = 12, page 2 = 8.
        fun pageOf(row: ContentValues): Int = row.getAsInteger(Favorites.SCREEN)
        val page0Roots = current.filter {
            it.getAsInteger(Favorites.CONTAINER) == Favorites.CONTAINER_DESKTOP && pageOf(it) == 0
        }
        val page1Roots = desktopApps.filter { pageOf(it) == 1 }
        val page2Roots = desktopApps.filter { pageOf(it) == 2 }
        assertEquals("Page 0 must hold 15 icons plus the folder", 16, page0Roots.size)
        assertEquals("Page 1 must hold 12 icons", 12, page1Roots.size)
        assertEquals("Page 2 must hold 8 icons", 8, page2Roots.size)
        if (columns == REFERENCE_GRID_COLUMNS && rows == REFERENCE_GRID_ROWS) {
            assertEquals(
                "Page 0 must be exactly full on the reference 4x5 grid (QSB reserves 4 cells)",
                columns * rows - reservedCellCount(reservations, 0),
                page0Roots.size,
            )
        }

        // B1 precondition (A-13): the first placement-candidate screen
        // (page 1) keeps at least one free cell for a newly installed app.
        val page1Occupied = page1Roots.sumOf { spanCells(it) }
        assertTrue(
            "Page 1 must keep a free cell for the B1 newly-installed app",
            columns * rows - page1Occupied >= 1,
        )
    }

    private fun assertReservationDisjoint(reservations: List<ReservedWorkspaceRegion>) {
        snapshotFavorites()
            .filter { it.getAsInteger(Favorites.CONTAINER) == Favorites.CONTAINER_DESKTOP }
            .forEach { row ->
                val overlaps = ReservationOverlapAcceptance.overlaps(
                    PageId(row.getAsInteger(Favorites.SCREEN).toString()),
                    GridCell(row.getAsInteger(Favorites.CELLX), row.getAsInteger(Favorites.CELLY)),
                    GridSpan(
                        row.getAsInteger(Favorites.SPANX) ?: 1,
                        row.getAsInteger(Favorites.SPANY) ?: 1,
                    ),
                    reservations,
                )
                assertTrue(
                    "Fixture row must not overlap a reserved workspace region",
                    !overlaps,
                )
            }
    }

    /** Normalized placement projection (plan data flow step 3). */
    private fun normalizedProjection(): List<String> {
        val rows = snapshotFavorites()
        val folderIds = rows
            .filter { it.getAsInteger(Favorites.ITEM_TYPE) == Favorites.ITEM_TYPE_FOLDER }
            .mapNotNull { it.getAsLong(Favorites._ID) }
            .toSet()
        return rows.mapNotNull { row ->
            val container = row.getAsLong(Favorites.CONTAINER)
            val containerKey = when {
                container == Favorites.CONTAINER_DESKTOP.toLong() -> "desktop"
                container in folderIds -> "fixture-folder"
                else -> return@mapNotNull null // preserved hotseat rows are out of scope
            }
            listOf(
                containerKey,
                row.getAsString(Favorites.SCREEN) ?: "?",
                row.getAsString(Favorites.CELLX) ?: "?",
                row.getAsString(Favorites.CELLY) ?: "?",
                row.getAsString(Favorites.SPANX) ?: "?",
                row.getAsString(Favorites.SPANY) ?: "?",
                row.getAsString(Favorites.ITEM_TYPE) ?: "?",
                row.getAsString(Favorites.INTENT) ?: "?",
                row.getAsString(Favorites.TITLE) ?: "?",
                row.getAsString(Favorites.RANK) ?: "?",
            ).joinToString("|")
        }.sorted()
    }

    private fun preservedIds(
        rows: List<ContentValues>,
        reservations: List<ReservedWorkspaceRegion>,
    ): Set<Long> {
        val preserved = mutableSetOf<Long>()
        rows.filter { it.getAsInteger(Favorites.CONTAINER) == Favorites.CONTAINER_HOTSEAT }
            .forEach { preserved.add(requireNotNull(it.getAsLong(Favorites._ID))) }
        rows.filter { it.getAsInteger(Favorites.CONTAINER) == Favorites.CONTAINER_DESKTOP }
            .forEach { row ->
                val overlaps = ReservationOverlapAcceptance.overlaps(
                    PageId(row.getAsInteger(Favorites.SCREEN).toString()),
                    GridCell(row.getAsInteger(Favorites.CELLX), row.getAsInteger(Favorites.CELLY)),
                    GridSpan(
                        row.getAsInteger(Favorites.SPANX) ?: 1,
                        row.getAsInteger(Favorites.SPANY) ?: 1,
                    ),
                    reservations,
                )
                if (overlaps) preserved.add(requireNotNull(row.getAsLong(Favorites._ID)))
            }
        // Keep descendants of preserved roots (e.g. a folder inside the dock).
        var grew = true
        while (grew) {
            grew = false
            rows.forEach { row ->
                val id = row.getAsLong(Favorites._ID) ?: return@forEach
                val container = row.getAsLong(Favorites.CONTAINER) ?: return@forEach
                if (id !in preserved && container in preserved) {
                    preserved.add(id)
                    grew = true
                }
            }
        }
        return preserved
    }

    private fun preservedRows(): List<ContentValues> {
        val ids = preservedIds(originalRows, initialReservations)
        return snapshotFavorites().filter { it.getAsLong(Favorites._ID) in ids }
    }

    private fun occupiedCells(
        reservations: List<ReservedWorkspaceRegion>,
        rows: List<ContentValues>,
        preservedIds: Set<Long>,
    ): MutableMap<Int, MutableSet<Long>> {
        val occupied = mutableMapOf<Int, MutableSet<Long>>()
        reservations.forEach { r ->
            val screen = r.page.pageId.value.toIntOrNull() ?: return@forEach
            occupy(occupied, screen, r.cell.x, r.cell.y, r.span.width, r.span.height)
        }
        rows.forEach { row ->
            val id = row.getAsLong(Favorites._ID) ?: return@forEach
            if (id !in preservedIds) return@forEach
            if (row.getAsInteger(Favorites.CONTAINER) == Favorites.CONTAINER_DESKTOP) {
                occupy(
                    occupied,
                    row.getAsInteger(Favorites.SCREEN),
                    row.getAsInteger(Favorites.CELLX),
                    row.getAsInteger(Favorites.CELLY),
                    row.getAsInteger(Favorites.SPANX) ?: 1,
                    row.getAsInteger(Favorites.SPANY) ?: 1,
                )
            }
        }
        return occupied
    }

    private fun nextFreeCell(
        screen: Int,
        columns: Int,
        rows: Int,
        occupied: MutableMap<Int, MutableSet<Long>>,
    ): GridCell? {
        val cells = occupied.getOrPut(screen) { mutableSetOf() }
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                if (x.toLong() * 1000L + y !in cells) return GridCell(x, y)
            }
        }
        return null
    }

    private fun occupy(
        occupied: MutableMap<Int, MutableSet<Long>>,
        screen: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val cells = occupied.getOrPut(screen) { mutableSetOf() }
        for (dx in 0 until w) {
            for (dy in 0 until h) {
                cells.add((x + dx).toLong() * 1000L + (y + dy))
            }
        }
    }

    private fun spanCells(row: ContentValues): Int =
        (row.getAsInteger(Favorites.SPANX) ?: 1) * (row.getAsInteger(Favorites.SPANY) ?: 1)

    private fun launchIdentity(row: ContentValues): String =
        "${intentComponent(row)}#${row.getAsLong(Favorites.PROFILE_ID)}"

    private fun intentComponent(row: ContentValues): String? =
        row.getAsString(Favorites.INTENT)?.let { intent ->
            runCatching { Intent.parseUri(intent, 0).component?.className }.getOrNull()
        }

    private fun alias(index: Int): Alias {
        val component = ComponentName(
            InstrumentationRegistry.getInstrumentation().context.packageName,
            "app.lawnchair.fixture.F%02d".format(index),
        )
        return Alias(component, "Fixture %02d".format(index))
    }

    private fun insertAppRow(alias: Alias, screen: Int, cellX: Int, cellY: Int) {
        val db = launcher.model.modelDbController.db
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(alias.component)
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, launcher.model.modelDbController.generateNewItemId())
                put(Favorites.TITLE, alias.label)
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 0L)
                put(Favorites.RESTORED, 0)
                put(
                    Favorites.PROFILE_ID,
                    UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
                )
                put(Favorites.RANK, screen)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, 0)
            },
        )
    }

    private fun insertFolderRow(folderId: Int, screen: Int, cellX: Int, cellY: Int) {
        val db = launcher.model.modelDbController.db
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, folderId)
                put(Favorites.TITLE, FOLDER_TITLE)
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 0L)
                put(Favorites.RESTORED, 0)
                put(
                    Favorites.PROFILE_ID,
                    UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
                )
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, 0)
            },
        )
    }

    private fun insertFolderChildRow(alias: Alias, folderId: Int) {
        val db = launcher.model.modelDbController.db
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(alias.component)
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, launcher.model.modelDbController.generateNewItemId())
                put(Favorites.TITLE, alias.label)
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, folderId)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 0L)
                put(Favorites.RESTORED, 0)
                put(
                    Favorites.PROFILE_ID,
                    UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
                )
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, 0)
            },
        )
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        launcher.model.modelDbController.db.query(
            Favorites.TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            Favorites._ID,
        ).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows += readRow(cursor, columns)
        }
        return rows
    }

    private fun restoreFavorites(rowsToRestore: List<ContentValues>) {
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            rowsToRestore.forEach { db.insertOrThrow(Favorites.TABLE_NAME, null, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readRow(cursor: Cursor, columns: Array<String>): ContentValues = ContentValues().also { values ->
        for (index in columns.indices) {
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(columns[index])
                Cursor.FIELD_TYPE_INTEGER -> values.put(columns[index], cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(columns[index], cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(columns[index], cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(columns[index], cursor.getBlob(index))
            }
        }
    }

    private fun waitForModel() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!launcher.model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not load for Issue #441 fixture" }
    }

    private fun reloadAndWait() {
        val latch = CountDownLatch(1)
        reloadLatch = latch
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.addCallbacks(modelCallbacks)
            launcher.model.forceReload()
        }
        check(latch.await(30, TimeUnit.SECONDS)) {
            "Launcher model reload did not finish for Issue #441 fixture"
        }
        waitForModel()
    }

    private fun persistMode(): Boolean =
        InstrumentationRegistry.getArguments().getString("persist", "false") == "true"

    private fun reservedCellCount(reservations: List<ReservedWorkspaceRegion>, screen: Int): Int =
        reservations
            .filter { it.page.pageId.value.toIntOrNull() == screen }
            .sumOf { it.span.width * it.span.height }

    private data class Alias(val component: ComponentName, val label: String)

    private companion object {
        const val FOLDER_TITLE = "Benchmark"
        const val REFERENCE_GRID_COLUMNS = 4
        const val REFERENCE_GRID_ROWS = 5
        val FIXTURE_FOLDER = FixtureItem("folder", 0)

        // Issue #441 A-8/A-10 fixture input table. The alias indices follow
        // the accepted identity table: F01 folder content, F02/F03 duplicate
        // pairs, F04-F08 B2 move targets, F09-F14 B4 remove targets,
        // F15-F18 B3 folder targets, F19-F34 regular icons.
        val FIXTURE_LAYOUT: List<List<FixtureItem>> = listOf(
            // Page 0: 15 icons + designated folder (16 roots on the 4x5 grid).
            listOf(
                FIXTURE_FOLDER,
                FixtureItem("move", 4),
                FixtureItem("move", 5),
                FixtureItem("move", 6),
                FixtureItem("move", 7),
                FixtureItem("move", 8),
                FixtureItem("remove", 9),
                FixtureItem("remove", 10),
                FixtureItem("remove", 11),
                FixtureItem("duplicate0", 2),
                FixtureItem("duplicate0", 2),
                FixtureItem("regular", 19),
                FixtureItem("regular", 20),
                FixtureItem("regular", 21),
                FixtureItem("regular", 22),
                FixtureItem("regular", 23),
            ),
            // Page 1: 12 icons (8 free cells remain for the B1 new app).
            listOf(
                FixtureItem("remove", 12),
                FixtureItem("remove", 13),
                FixtureItem("remove", 14),
                FixtureItem("b3", 15),
                FixtureItem("b3", 16),
                FixtureItem("duplicate1", 3),
                FixtureItem("duplicate1", 3),
                FixtureItem("regular", 24),
                FixtureItem("regular", 25),
                FixtureItem("regular", 26),
                FixtureItem("regular", 27),
                FixtureItem("regular", 28),
            ),
            // Page 2: 8 icons (receiving page for B2).
            listOf(
                FixtureItem("b3", 17),
                FixtureItem("b3", 18),
                FixtureItem("regular", 29),
                FixtureItem("regular", 30),
                FixtureItem("regular", 31),
                FixtureItem("regular", 32),
                FixtureItem("regular", 33),
                FixtureItem("regular", 34),
            ),
        )
    }
}

private data class FixtureItem(val role: String, val aliasIndex: Int)
