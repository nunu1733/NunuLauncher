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

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DeviceGridState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #299 I-4 residual: cross-process persistence, designed to observe the
 * persisted launcher DB in a FRESH process BEFORE any model reload (which is
 * where the repair/delete would otherwise run). These classes deliberately do
 * NOT extend [NovaRestoreCaptureTestBase]: the base's `@Before`/`@After` and
 * its model-touching `LauncherAppState.getInstance` would run a reload —
 * destroying the exact pre-repair state under test. Each runs in its own
 * `am instrument` invocation (A then B); the instrumentation runner
 * force-stops the app between them, which IS the process death.
 *
 * Stage A runs the production converter WITHOUT ever creating the
 * `LauncherAppState`, so `ReloadAfterRestore`'s `LauncherAppState.getNoCreate`
 * returns null and NO reload generation is dispatched in this process. The
 * sanitized restored DB — with the unbound widget row — is therefore
 * durably committed by `performRestore`'s transaction and survives into the
 * next process untouched. Stage A records the persisted state through a
 * SEPARATE read-only DB connection (never touching the live model) so the
 * observation reflects only committed, not in-flight, writes.
 */
class NovaRestoreCaptureCrossProcessStageATest {

    @Test
    fun stageA_persistSanitizedUnboundRow_withoutDispatchingReload() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // No LauncherAppState.getInstance: the restore runs but no reload is
        // dispatched, so nothing repairs the unbound row in this process.
        val provider = firstInstalledProvider(context)
        val zip = buildFixtureZip(context, includeWidget = true, widgetProvider = provider)

        val converter = NovaBackupConverter(context, Uri.fromFile(zip))
        val info = runBlocking { converter.parseInfo() }
        runBlocking { converter.convertAndRestore(info) }

        // Read the committed state on a separate read-only connection: the
        // grid prefs were committed to the converted (rows+1 smartspace) grid
        // by the converter, so DeviceGridState reports the file the restore
        // actually wrote and committed.
        val restoredDbName = DeviceGridState(context).dbFile
        val restoredDb = context.getDatabasePath(restoredDbName)
        Log.i(TAG, "crossProcess/A: persisted dbFile=$restoredDbName exists=${restoredDb.exists()}")
        val persisted = queryWidgetState(readOnlyOpen(restoredDb))
        val persistedDbNameBefore = restoredDbName
        Log.i(TAG, "crossProcess/A: persisted widget state after restore, no reload = $persisted")

        // Hand the file name to stage B via a persistent marker (cache dir
        // survives the app process; the DB file itself is app-private and
        // persists across the instrumentation force-stop).
        File(context.cacheDir, "i299_xp_dbfile").writeText(persistedDbNameBefore)
        File(context.cacheDir, "i299_xp_state").writeText(persisted.joinToString(","))

        // Stage A's own claim: the sanitized unbound widget row is present in
        // the committed DB, and the model was never touched in this process.
        assertEquals("exactly one unbound (appWidgetId<0) widget row must be committed", 1, persisted.count { it < 0 })
        assertEquals("no bound widget row should exist before any reload", 0, persisted.count { it >= 0 })
    }

    private companion object {
        const val TAG = "Issue299Harness"
    }
}

/**
 * Stage B (fresh process): read the persisted launcher DB on a read-only
 * connection BEFORE touching the model, proving what actually survived the
 * process death, then drive reload activity to the settle heuristic and observe
 * the repair.
 */
class NovaRestoreCaptureCrossProcessStageBTest {

    @Test
    fun stageB_readsPersistedUnboundRow_beforeModelInit_thenRecovers() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbFileName = File(context.cacheDir, "i299_xp_dbfile").takeIf { it.exists() }?.readText()
            ?: error("run stage A first (no i299_xp_dbfile marker)")
        val stageAState = File(context.cacheDir, "i299_xp_state").takeIf { it.exists() }?.readText()
        val db = context.getDatabasePath(dbFileName)

        // (1) Pre-model-init persisted observation: only a committed read of
        // the file, no LauncherAppState, no loader, no repair.
        val persisted = queryWidgetState(readOnlyOpen(db))
        Log.i(TAG, "crossProcess/B: persisted widget state (pre-model-init)=$persisted; stageA=$stageAState")
        val survivedUnbound = persisted.count { it < 0 }
        val survivedBound = persisted.count { it >= 0 }
        Log.i(
            TAG,
            "crossProcess/B: after process death unbound=$survivedUnbound bound=$survivedBound " +
                "rows=${persisted.size}",
        )
        // I-4 primary claim: an unbound widget row committed by performRestore
        // and never touched by a reload survives the instrumentation
        // force-stop into a fresh process.
        assertTrue(
            "stage A's unbound widget row must survive the process death into the fresh process",
            survivedUnbound >= 1,
        )

        // (2) Recovery half: now construct the model, drive reload activity
        // to the settle heuristic, and confirm the row is bound (or deleted) and
        // capture turns Ready. This is the in-process repair the death window
        // deferred, now running in the new process.
        val launcher = com.android.launcher3.LauncherAppState.getInstance(context)
        val controller = launcher.model.modelDbController
        // Drive a reload to the settle heuristic and observe via the same
        // production codec + capture source used elsewhere.
        settleOneGeneration(launcher)
        val afterRepair = queryWidgetState(readOnlyOpen(context.getDatabasePath(DeviceGridState(context).dbFile)))
        val afterUnbound = afterRepair.count { it < 0 }
        val afterBound = afterRepair.count { it >= 0 }
        Log.i(
            TAG,
            "crossProcess/B: after new-process settle unbound=$afterUnbound bound=$afterBound",
        )
        // The new-process repair activity must have closed the window: either
        // bound the row or removed it — no capture-invalid unbound row left.
        assertEquals(
            "after the settle heuristic the persisted unbound row must be bound or deleted",
            0,
            afterUnbound,
        )
    }

    private fun settleOneGeneration(launcher: com.android.launcher3.LauncherAppState) {
        // Investigation settle heuristic (generation identity is NOT claimed):
        // finishBindingItems firing + isModelLoaded (model loaded, no active
        // loader at the observation instant). Bind firings are not completions;
        // the settled state is what is asserted, never which generation ran.
        val latch = java.util.concurrent.CountDownLatch(1)
        val callbacks = object : com.android.launcher3.model.BgDataModel.Callbacks {
            override fun finishBindingItems(pagesBoundFirst: com.android.launcher3.util.IntSet?) { latch.countDown() }
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.addCallbacks(callbacks)
            launcher.model.forceReload()
        }
        assertTrue("reload did not reach the settle point (bind firing)", latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while (!launcher.model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        assertTrue(
            "model must reach the settle point (isModelLoaded=true, no active loader) within 30s",
            launcher.model.isModelLoaded,
        )
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.removeCallbacks(callbacks)
        }
    }

    private companion object {
        const val TAG = "Issue299Harness"
    }
}

// ---------------------------------------------------------------
// Shared self-contained helpers (privacy-equivalent fixture; bounded
// read-only DB observation). Kept file-local so the cross-process tests do
// not inherit the base model-touching lifecycle.
// ---------------------------------------------------------------

private const val NOVA_TABLE = "favorites"
private const val LAST_COLUMN = 4

internal fun firstInstalledProvider(context: Context): String =
    AppWidgetManager.getInstance(context)
        .getInstalledProvidersForProfile(Process.myUserHandle())
        .map { it.provider.flattenToString() }
        .sorted()
        .firstOrNull()
        ?: error("no widget provider available")

internal fun buildFixtureZip(context: Context, includeWidget: Boolean, widgetProvider: String?): File {
    val dir = File(context.cacheDir, "nova_fixture_${UUID.randomUUID()}").apply { mkdirs() }
    val novaDb = File(dir, "nova.db")
    SQLiteDatabase.openOrCreateDatabase(novaDb, null).use { db ->
        db.execSQL(
            "CREATE TABLE $NOVA_TABLE (_id INTEGER PRIMARY KEY, container INTEGER, itemType INTEGER, " +
                "title TEXT, intent TEXT, cellX REAL, cellY REAL, screen INTEGER, spanX REAL, spanY REAL, " +
                "icon BLOB, appWidgetProvider TEXT)",
        )
        val appIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
            .toUri(0)
        val shortcutIntent = "#Intent;package=com.example.issue299.fixture;S.shortcut_id=fixture_shortcut;end"
        insertNovaRow(db, 1, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 0)
        insertNovaRow(db, 2, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 1, 0, 0)
        insertNovaRow(db, 3, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_FOLDER, null, 3, 0, 0)
        insertNovaRow(db, 4, 3, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 0)
        insertNovaRow(db, 5, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_DEEP_SHORTCUT, shortcutIntent, 0, 1, 0)
        if (includeWidget) {
            insertNovaRow(
                db, 6, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPWIDGET, null,
                1, 0, 1, spanX = 2, spanY = 2, appWidgetProvider = widgetProvider,
            )
        }
    }
    File(dir, "nova.xml").writeText(
        "<map><string name=\"desktop_grid\">${5}x${LAST_COLUMN}</string>" +
            "<int name=\"dock_grid_cols\" value=\"${4}\"/></map>",
    )
    val zip = File(context.cacheDir, "nova_backup_fixture_${UUID.randomUUID()}.zip")
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

private fun insertNovaRow(
    db: SQLiteDatabase,
    id: Int,
    container: Int,
    itemType: Int,
    intent: String?,
    cellX: Int,
    cellY: Int,
    screen: Int,
    spanX: Int = 1,
    spanY: Int = 1,
    appWidgetProvider: String? = null,
) {
    db.execSQL(
        "INSERT INTO $NOVA_TABLE (_id, container, itemType, title, intent, cellX, cellY, screen, " +
            "spanX, spanY, appWidgetProvider) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<Any?>(id, container, itemType, "fixture_$id", intent, cellX.toDouble(), cellY.toDouble(), screen, spanX.toDouble(), spanY.toDouble(), appWidgetProvider),
    )
}

/** Opens the launcher favorites DB read-only and returns per-widget-row appWidgetIds (negative = unbound). */
internal fun queryWidgetState(db: SQLiteDatabase?): List<Int> {
    if (db == null) return emptyList()
    return try {
        db.query(Favorites.TABLE_NAME, arrayOf(Favorites.ITEM_TYPE, Favorites.APPWIDGET_ID), null, null, null, null, null)
            .use { cursor ->
                val typeIdx = cursor.getColumnIndexOrThrow(Favorites.ITEM_TYPE)
                val idIdx = cursor.getColumnIndexOrThrow(Favorites.APPWIDGET_ID)
                buildList {
                    while (cursor.moveToNext()) {
                        val kind = cursor.getInt(typeIdx)
                        if (kind == Favorites.ITEM_TYPE_APPWIDGET || kind == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET) {
                            add(cursor.getInt(idIdx))
                        }
                    }
                }
            }
    } finally {
        db.close()
    }
}

internal fun readOnlyOpen(file: File): SQLiteDatabase? =
    if (file.exists()) SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) else null
