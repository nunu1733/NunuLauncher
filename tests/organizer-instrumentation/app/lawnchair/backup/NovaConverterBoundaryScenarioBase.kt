/*
 * Copyright 2026, NunuLauncher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.lawnchair.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DeviceGridState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before

/**
 * Issue #532 rebase Phase 2 / G4 T8 added oracle base (#522 assessment
 * §test表): drives the Nova converter's normal entry
 * ([NovaBackupConverter.parseInfo] and [NovaBackupConverter.convertAndRestore])
 * with hand-written boundary fixtures and asserts the converted DB and the
 * committed grid against written-down expected values.
 *
 * Ownership boundary: the #299 A/B classes own UI connection, cross-process
 * staging and process separation; NovaRestoreGridApplicationTest owns the
 * grid-apply/cleanup application seams. This base owns only the converter's
 * coordinate conversion contract: fractional rounding of all four placement
 * fields, the smartspace ON/OFF cellY shift and rows+1 compensation, bounds
 * clamping and out-of-bounds skipping, and the desktop/hotseat/folder
 * placements. The subgrid warning driver is asserted at the
 * [NovaBackupConverter.NovaBackupInfo.isSubgrid] level; the smartspace
 * conflict toggle is the consumed enable_smartspace value this base pins.
 *
 * Execution contract (#299): one restore per instrumentation process. The
 * smartspace ON and OFF scenarios are separate classes so each lane
 * invocation runs exactly one restore in a fresh process. [parseInfo] is
 * read-only and may run alongside the single restore.
 *
 * The shift/bound pair compensates exactly: a raw placement survives iff
 * round(cellY) + round(spanY) <= nova rows, in both states — what changes is
 * the committed position (+1 under ON) and the committed row count (+1).
 *
 * Not routed: abstract base without tests. Route the concrete scenario
 * classes per class (tools/ci/run-restore-capture-instrumentation.sh).
 */
abstract class NovaConverterBoundaryScenarioBase : NovaRestoreCaptureTestBase() {

    /** The enable_smartspace value this scenario restores under. */
    protected abstract val smartspaceEnabled: Boolean

    private var originalSmartspace: Boolean? = null

    protected fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Fixture grid: Nova desktop_grid "6x5" and dock_grid_cols 5. */
    protected val fixtureColumns = 5
    protected val fixtureNovaRows = 6
    protected val fixtureHotseat = 5

    /** Rows the converter commits for smartspace (ON: nova rows + 1). */
    protected val expectedCommittedRows: Int
        get() = if (smartspaceEnabled) fixtureNovaRows + 1 else fixtureNovaRows

    /** The live serial the converter stamps on every converted row. */
    protected fun expectedProfileId(): Long = launcher.model.modelDbController
        .getSerialNumberForUser(android.os.Process.myUserHandle())

    /**
     * The converter reads enable_smartspace from the DataStore at restore
     * time. The value is written directly through the DataStore (not the
     * typed setter) because the typed setter's onSet restarts launcher
     * activities, which is out of scope for a converter boundary fixture.
     */
    @Before
    fun pinSmartspaceForScenario() {
        val prefs2 = PreferenceManager2.getInstance(context())
        if (originalSmartspace == null) {
            originalSmartspace = runBlocking { prefs2.enableSmartspace.first() }
        }
        runBlocking {
            prefs2.preferencesDataStore.edit {
                it[booleanPreferencesKey("enable_smartspace")] = smartspaceEnabled
            }
        }
    }

    @After
    fun restoreSmartspaceForScenario() {
        originalSmartspace?.let { original ->
            runBlocking {
                PreferenceManager2.getInstance(context()).preferencesDataStore.edit {
                    it[booleanPreferencesKey("enable_smartspace")] = original
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Boundary fixture: fractional all four fields, clamp and skip
    // candidates, hotseat fractional rounding, folder + folder child.
    // Row ids double as assertion handles (assertConvertedRow /
    // assertSkippedRow).
    // -----------------------------------------------------------------

    protected fun buildBoundaryZip(desktopGrid: String = "${fixtureNovaRows}x$fixtureColumns"): File {
        val context = context()
        val dir = File(context.cacheDir, "nova_boundary_${UUID.randomUUID()}").apply { mkdirs() }
        val novaDb = File(dir, "nova.db")
        SQLiteDatabase.openOrCreateDatabase(novaDb, null).use { db ->
            db.execSQL(
                "CREATE TABLE favorites (" +
                    "_id INTEGER PRIMARY KEY, container INTEGER, itemType INTEGER, title TEXT, " +
                    "intent TEXT, cellX REAL, cellY REAL, screen INTEGER, spanX REAL, spanY REAL, " +
                    "icon BLOB, appWidgetProvider TEXT)",
            )
            val appIntent = launcherIntent()

            // id1: fractional rounding on all four fields; kept under both
            // smartspace states (the committed cellY differs by the shift).
            insertRow(
                db, id = 1, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 0.4, cellY = 0.6, screen = 0, spanX = 1.4, spanY = 1.2,
            )
            // id2: half-up rounding boundary on all four fields (x.5 rounds up).
            insertRow(
                db, id = 2, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 1.5, cellY = 1.5, screen = 0, spanX = 2.5, spanY = 2.5,
            )
            // id3: column overflow — spanX clamps to the grid, then the
            // placement skips.
            insertRow(
                db, id = 3, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 3.4, cellY = 0.4, screen = 0, spanX = 4.6, spanY = 1.0,
            )
            // id4: row-bound skip on the last grid row (skips in both
            // smartspace states — see the class doc on the exact
            // shift/bound compensation).
            insertRow(
                db, id = 4, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 0.0, cellY = 6.4, screen = 0, spanX = 1.0, spanY = 1.2,
            )
            // id5: oversized spans clamp to the grid bounds and are kept.
            insertRow(
                db, id = 5, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 0.0, cellY = 2.4, screen = 0, spanX = 9.0, spanY = 2.2,
            )
            // id6: hotseat fractional position rounds into the screen slot.
            insertRow(
                db, id = 6, container = Favorites.CONTAINER_HOTSEAT,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 0.6, cellY = 0.0, screen = 0, spanX = 1.0, spanY = 1.0,
            )
            // id7: hotseat overflow at the last slot skips.
            insertRow(
                db, id = 7, container = Favorites.CONTAINER_HOTSEAT,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 4.7, cellY = 0.0, screen = 0, spanX = 1.0, spanY = 1.0,
            )
            // id8/id9: folder and its child keep desktop/hotseat/folder
            // coverage; the child's rank derives from screen/cellX/cellY.
            insertRow(
                db, id = 8, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_FOLDER, intent = null,
                cellX = 1.2, cellY = 2.2, screen = 0, spanX = 1.0, spanY = 1.0,
            )
            insertRow(
                db, id = 9, container = 8,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 0.5, cellY = 0.4, screen = 0, spanX = 1.0, spanY = 1.0,
            )
            // id10: second desktop page passes the screen id through.
            insertRow(
                db, id = 10, container = Favorites.CONTAINER_DESKTOP,
                itemType = Favorites.ITEM_TYPE_APPLICATION, intent = appIntent,
                cellX = 0.0, cellY = 0.0, screen = 1, spanX = 1.0, spanY = 1.0,
            )
        }

        val novaXml = File(dir, "nova.xml")
        novaXml.writeText(
            "<map>" +
                "<string name=\"desktop_grid\">$desktopGrid</string>" +
                "<int name=\"dock_grid_cols\" value=\"$fixtureHotseat\"/>" +
                "</map>",
        )

        val zip = File(context.cacheDir, "nova_boundary_${UUID.randomUUID()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            listOf("nova.xml", "nova.db").forEach { name ->
                out.putNextEntry(ZipEntry(name))
                File(dir, name).inputStream().copyTo(out)
                out.closeEntry()
            }
        }
        dir.deleteRecursively()
        return zip
    }

    /** Restores the boundary fixture through the converter's normal entry. */
    protected fun restoreBoundaryBackup(zip: File): NovaBackupConverter.NovaBackupInfo {
        val converter = NovaBackupConverter(context(), Uri.fromFile(zip))
        val info = runBlocking { converter.parseInfo() }
        runBlocking { converter.convertAndRestore(info) }
        return info
    }

    /** Hand-written expectation for one converted favorites row. */
    protected fun assertConvertedRow(
        id: Int,
        container: Int,
        itemType: Int,
        screen: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int,
        rank: Int = 0,
    ) {
        rowCursor(id).use { cursor ->
            assertEquals("row $id must survive the conversion", true, cursor.moveToFirst())
            assertEquals("row $id container", container, cursor.getInt(0))
            assertEquals("row $id itemType", itemType, cursor.getInt(1))
            assertEquals("row $id screen", screen, cursor.getInt(2))
            assertEquals("row $id cellX", cellX, cursor.getInt(3))
            assertEquals("row $id cellY", cellY, cursor.getInt(4))
            assertEquals("row $id spanX", spanX, cursor.getInt(5))
            assertEquals("row $id spanY", spanY, cursor.getInt(6))
            assertEquals("row $id rank", rank, cursor.getInt(7))
            assertEquals("row $id profileId", expectedProfileId(), cursor.getLong(8))
        }
    }

    /** The converter must have skipped this row entirely (out of bounds). */
    protected fun assertSkippedRow(id: Int) {
        rowCursor(id).use { cursor ->
            assertFalse("row $id must have been skipped by the converter", cursor.moveToFirst())
        }
    }

    /** The committed grid binding: migration_src prefs plus the live IDP db file. */
    protected fun assertCommittedGrid() {
        val context = context()
        assertEquals(
            "the committed workspace grid size must carry the smartspace compensation",
            "$fixtureColumns,$expectedCommittedRows",
            LauncherPrefs.getPrefs(context).getString(DeviceGridState.KEY_WORKSPACE_SIZE, null),
        )
        assertEquals(
            fixtureHotseat,
            LauncherPrefs.getPrefs(context).getInt(DeviceGridState.KEY_HOTSEAT_COUNT, -1),
        )
        val expectedDbFile = DeviceProfileOverrides.DBGridInfo(
            numHotseatColumns = fixtureHotseat,
            numRows = expectedCommittedRows,
            numColumns = fixtureColumns,
        ).dbFile
        assertEquals(
            "the live IDP dbFile must bind the converted grid",
            expectedDbFile,
            InvariantDeviceProfile.INSTANCE.get(context).dbFile,
        )
    }

    /** parseInfo (read-only) must parse a normal grid without the warning flag. */
    protected fun assertParseInfoNormalGrid(zip: File) {
        val info = runBlocking {
            NovaBackupConverter(context(), Uri.fromFile(zip)).parseInfo()
        }
        assertFalse("subgrid warning must stay hidden for a normal grid", info.isSubgrid)
        assertEquals(fixtureColumns, info.columns)
        assertEquals(fixtureNovaRows, info.rows)
        assertEquals(fixtureHotseat, info.hotseatCount)
    }

    /** parseInfo must flag subgrid backups (the SubgridWarning display driver). */
    protected fun assertParseInfoFlagsSubgrid() {
        val info = runBlocking {
            NovaBackupConverter(
                context(),
                Uri.fromFile(
                    buildBoundaryZip(
                        desktopGrid = "subgrid ${fixtureNovaRows}x$fixtureColumns",
                    ),
                ),
            ).parseInfo()
        }
        assertEquals("subgrid warning must be flagged for a subgrid backup", true, info.isSubgrid)
        assertEquals(fixtureColumns, info.columns)
        assertEquals(fixtureNovaRows, info.rows)
        assertEquals(fixtureHotseat, info.hotseatCount)
    }

    private fun rowCursor(id: Int): Cursor = launcher.model.modelDbController.db.query(
        Favorites.TABLE_NAME,
        arrayOf(
            Favorites.CONTAINER, Favorites.ITEM_TYPE, Favorites.SCREEN,
            Favorites.CELLX, Favorites.CELLY, Favorites.SPANX, Favorites.SPANY,
            Favorites.RANK, Favorites.PROFILE_ID,
        ),
        Favorites._ID + "=?",
        arrayOf(id.toString()),
        null,
        null,
        null,
    )

    private fun launcherIntent(): String = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(context().packageName, LawnchairLauncher::class.java.name))
        .toUri(0)

    private fun insertRow(
        db: SQLiteDatabase,
        id: Int,
        container: Int,
        itemType: Int,
        intent: String?,
        cellX: Double,
        cellY: Double,
        screen: Int,
        spanX: Double,
        spanY: Double,
    ) {
        db.execSQL(
            "INSERT INTO favorites (_id, container, itemType, title, intent, cellX, cellY, " +
                "screen, spanX, spanY, appWidgetProvider) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, container, itemType, "boundary_$id", intent, cellX, cellY, screen,
                spanX, spanY, null,
            ),
        )
    }
}
