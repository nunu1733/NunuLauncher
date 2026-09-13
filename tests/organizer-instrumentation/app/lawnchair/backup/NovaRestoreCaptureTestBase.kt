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
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.diagnostics.logger.DiagnosticsLogger
import app.lawnchair.organizer.integration.CanonicalCaptureReadResult
import app.lawnchair.organizer.integration.CaptureFailureObserver
import app.lawnchair.organizer.integration.LayoutWriterCanonicalCaptureSource
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.util.IntSet
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before

/**
 * Issue #299 I-1 evidence base: Nova restore -> Organizer capture through the
 * production restore path ([NovaBackupConverter.convertAndRestore]) and the
 * production capture source ([LayoutWriterCanonicalCaptureSource]).
 *
 * Execution contract: one restore scenario per instrumentation process. Each
 * scenario class runs a single restore, and the CI/local invocation runs one
 * class per `am instrument` (fresh process). Restoring twice inside one
 * process overlaps reload generations (the restore's own forceReload, the
 * grid-apply listeners' reloads, and the test's barrier reloads), which makes
 * a generation-agnostic latch barrier unreliable — that overlap is itself a
 * production observation, not something the harness should paper over.
 *
 * The synthetic Nova backup is privacy-equivalent by construction: it carries
 * only the test application's own component and synthetic identity strings,
 * so no user layout content exists in the fixture.
 *
 * Bounded state-matrix observations (plan I-1) log counts and categories
 * only — never titles, intents, coordinates, or other layout content.
 */
abstract class NovaRestoreCaptureTestBase {

    protected lateinit var context: Context
    protected lateinit var launcher: LauncherAppState
    protected lateinit var originalGrid: DeviceProfileOverrides.DBGridInfo
    private var reloadLatch: CountDownLatch? = null

    // Investigation-only observability. `finishBindingItems` firing is
    // recorded but is NOT a completion signal: a generation that is then
    // cancelled can fire (bind completion is not the terminal commit —
    // LauncherModel.LoaderTransaction increments mLastLoadId in its
    // constructor and commit() only sets mModelLoaded). The settle check is
    // `LauncherModel.isModelLoaded()` (mModelLoaded && mLoaderTask == null &&
    // !mModelDestroyed): the model is loaded and no loader is active at the
    // observation instant. This is a heuristic without generation identity —
    // it must not be used for generation-level causal attribution.
    private val windowBindingFirings = java.util.concurrent.atomic.AtomicInteger(0)
    private val modelCallbacks = object : BgDataModel.Callbacks {
        override fun finishBindingItems(pagesBoundFirst: IntSet) {
            windowBindingFirings.incrementAndGet()
            reloadLatch?.countDown()
        }
    }

    @Before
    fun setUpBase() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        if (classOriginalGrid == null) {
            classOriginalGrid = DeviceProfileOverrides.INSTANCE.get(context).getGridInfo()
            classOriginalRows = snapshotFavorites()
        }
        originalGrid = classOriginalGrid!!
        // Return to the class-original grid before anything else: the Nova
        // converter commits rows+1 (smartspace) on every restore, so a
        // previous process state can have left a shifted grid behind.
        restoreGridToOriginal()
        reloadAndWait("setUp grid")
        launcher.model.modelDbController.db.delete(Favorites.TABLE_NAME, null, null)
        launcher.model.modelDbController.clearEmptyDbFlag()
        reloadAndWait("setUp baseline")
    }

    @After
    fun tearDownBase() {
        try {
            restoreGridToOriginal()
            reloadAndWait("tearDown grid")
            restoreFavorites(classOriginalRows!!)
            reloadAndWait("tearDown rows")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                launcher.model.removeCallbacks(modelCallbacks)
            }
        }
    }

    // ---------------------------------------------------------------
    // Restore driver: real NovaBackupConverter production path. The
    // barrier latch must be registered BEFORE convertAndRestore because
    // the restore's internal forceReload dispatches reload generations
    // whose settle state the heuristic observes.
    // ---------------------------------------------------------------
    protected data class RestoredWorkspace(
        val info: NovaBackupConverter.NovaBackupInfo,
        val barrier: CountDownLatch,
    )

    protected fun restoreSyntheticBackup(
        includeWidget: Boolean,
        widgetProvider: String? = null,
    ): RestoredWorkspace {
        val converter = buildConverter(includeWidget, widgetProvider)
        val info = runBlocking { converter.parseInfo() }
        Log.i(
            TAG,
            "restore/parseInfo: apps=${info.appCount} widgets=${info.widgetCount} folders=${info.folderCount} " +
                "shortcuts=${info.shortcutCount} grid=${info.columns}x${info.rows} hotseat=${info.hotseatCount}",
        )
        val barrier = CountDownLatch(1)
        reloadLatch = barrier
        windowBindingFirings.set(0)
        runBlocking { converter.convertAndRestore(info) }
        Log.i(TAG, "restore/convertAndRestore returned (reloadAfterRestore already dispatched)")
        return RestoredWorkspace(info, barrier)
    }

    protected fun buildConverter(includeWidget: Boolean, widgetProvider: String?): NovaBackupConverter {
        val zip = buildNovaBackupZip(includeWidget, widgetProvider)
        return NovaBackupConverter(context, Uri.fromFile(zip))
    }

    /**
     * Investigation settle heuristic (not the contract-grade CI-AC-02 oracle,
     * and not a generation identity): reached when a `finishBindingItems`
     * fired after the restore dispatch and [LauncherModel.isModelLoaded] then
     * holds — the model is loaded with no active loader at the observation
     * instant. Bind firings are not completions and the heuristic carries no
     * generation identity; I-3 must not use it for generation-level causal
     * attribution. Every plain reload generation runs the repair-carrying
     * sanitize (the existing organizer token path skips it and cannot be
     * reused as-is), so at this settle point pending repair work has settled.
     */
    protected fun awaitRestoreReloadBarrier(label: String, restored: RestoredWorkspace) {
        assertTrue("$label: restore reload did not reach the completion barrier", restored.barrier.await(30, TimeUnit.SECONDS))
        awaitModelLoaded(label)
        Log.i(
            TAG,
            "barrier[$label]: settled (isModelLoaded=true, no active loader) " +
                "bindCompleteFiringsInWindow=${windowBindingFirings.get()} (firings are not completions)",
        )
    }

    /** Registers a fresh barrier latch and drives one reload generation to the settle point. */
    protected fun forceReloadAndAwaitBarrier(label: String) {
        val latch = CountDownLatch(1)
        reloadLatch = latch
        windowBindingFirings.set(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.forceReload()
        }
        assertTrue("$label: reload did not reach the completion barrier", latch.await(30, TimeUnit.SECONDS))
        awaitModelLoaded(label)
        Log.i(
            TAG,
            "barrier[$label]: settled (isModelLoaded=true, no active loader) " +
                "bindCompleteFiringsInWindow=${windowBindingFirings.get()} (firings are not completions)",
        )
    }

    protected fun awaitModelLoaded(label: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!launcher.model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        assertTrue("$label: model must report loaded at the barrier", isModelLoaded())
    }

    protected fun isModelLoaded(): Boolean = launcher.model.isModelLoaded

    /** Capture through the production composer source, recording failure identities. */
    protected fun captureThroughProductionSource(
        observerExceptions: MutableList<Class<out Throwable>> = mutableListOf(),
    ): Boolean {
        val source = LayoutWriterCanonicalCaptureSource(
            LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model),
            CaptureFailureObserver { exceptionClass ->
                observerExceptions += exceptionClass
                DiagnosticsLogger().logCaptureFailure(exceptionClass)
            },
        )
        return source.capture() is CanonicalCaptureReadResult.Ready
    }

    /** Canonical item count captured through the production adapter. */
    protected fun captureItemCount(captureId: String): Int =
        LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
            .captureCurrent(CaptureId(captureId)).layoutState.items.size

    /** Returns (negative-id rows, valid-id rows) for widget-kind rows. */
    protected fun widgetRowCount(label: String): Pair<Int, Int> {
        var negative = 0
        var valid = 0
        launcher.model.modelDbController.db.query(
            Favorites.TABLE_NAME,
            null,
            "${Favorites.ITEM_TYPE} IN (${Favorites.ITEM_TYPE_APPWIDGET}, ${Favorites.ITEM_TYPE_CUSTOM_APPWIDGET})",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Favorites.APPWIDGET_ID)
            while (cursor.moveToNext()) {
                if (cursor.getInt(idIdx) < 0) negative++ else valid++
            }
        }
        Log.i(TAG, "matrix[$label]: widgetIdNegative=$negative widgetIdValid=$valid")
        return negative to valid
    }

    // ---------------------------------------------------------------
    // Bounded state matrix (plan I-1): counts and categories only.
    // ---------------------------------------------------------------
    protected fun logMatrix(label: String, info: NovaBackupConverter.NovaBackupInfo) {
        val db = launcher.model.modelDbController.db
        val kindCounts = mutableMapOf<Int, Int>()
        var widgetRows = 0
        var widgetIdNegative = 0
        var widgetIdValid = 0
        var widgetProviderPresent = 0
        val widgetRestoredCategories = mutableMapOf<Int, Int>()
        val profileIds = mutableSetOf<Long>()
        val desktopScreens = mutableSetOf<Int>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, null).use { cursor ->
            val typeIdx = cursor.getColumnIndexOrThrow(Favorites.ITEM_TYPE)
            val widgetIdIdx = cursor.getColumnIndexOrThrow(Favorites.APPWIDGET_ID)
            val providerIdx = cursor.getColumnIndexOrThrow(Favorites.APPWIDGET_PROVIDER)
            val restoredIdx = cursor.getColumnIndexOrThrow(Favorites.RESTORED)
            val profileIdx = cursor.getColumnIndexOrThrow(Favorites.PROFILE_ID)
            val containerIdx = cursor.getColumnIndexOrThrow(Favorites.CONTAINER)
            val screenIdx = cursor.getColumnIndexOrThrow(Favorites.SCREEN)
            while (cursor.moveToNext()) {
                val kind = cursor.getInt(typeIdx)
                kindCounts[kind] = (kindCounts[kind] ?: 0) + 1
                profileIds += cursor.getLong(profileIdx)
                if (cursor.getInt(containerIdx) == Favorites.CONTAINER_DESKTOP) {
                    desktopScreens += cursor.getInt(screenIdx)
                }
                if (kind == Favorites.ITEM_TYPE_APPWIDGET || kind == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET) {
                    widgetRows++
                    val widgetId = cursor.getInt(widgetIdIdx)
                    when {
                        widgetId < 0 -> widgetIdNegative++
                        else -> widgetIdValid++
                    }
                    if (!cursor.isNull(providerIdx)) widgetProviderPresent++
                    val restored = cursor.getInt(restoredIdx)
                    widgetRestoredCategories[restored] = (widgetRestoredCategories[restored] ?: 0) + 1
                }
            }
        }
        Log.i(
            TAG,
            "matrix[$label]: rowsByKind=$kindCounts profiles=${profileIds.size} desktopPages=${desktopScreens.size} " +
                "parsedWidgets=${info.widgetCount}",
        )
        Log.i(
            TAG,
            "matrix[$label]: widgetRows=$widgetRows widgetIdNegative=$widgetIdNegative widgetIdValid=$widgetIdValid " +
                "widgetProviderPresent=$widgetProviderPresent widgetRestoredFlagCategories=$widgetRestoredCategories",
        )
    }

    // ---------------------------------------------------------------
    // Synthetic Nova backup fixture (privacy-equivalent by construction).
    // Desktop placements stay inside the original grid so the converter's
    // bounds clamp never skips a row.
    // ---------------------------------------------------------------
    private fun buildNovaBackupZip(includeWidget: Boolean, widgetProvider: String?): File {
        val dir = File(context.cacheDir, "nova_fixture_${UUID.randomUUID()}").apply { mkdirs() }
        val novaDb = File(dir, "nova.db")
        SQLiteDatabase.openOrCreateDatabase(novaDb, null).use { db ->
            db.execSQL(
                "CREATE TABLE favorites (" +
                    "_id INTEGER PRIMARY KEY, container INTEGER, itemType INTEGER, title TEXT, " +
                    "intent TEXT, cellX REAL, cellY REAL, screen INTEGER, spanX REAL, spanY REAL, " +
                    "icon BLOB, appWidgetProvider TEXT)",
            )
            val appIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
                .toUri(0)
            val shortcutIntent = "#Intent;package=com.example.issue299.fixture;S.shortcut_id=fixture_shortcut;end"
            val lastColumn = originalGrid.numColumns - 1

            insertNovaRow(db, 1, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 0)
            insertNovaRow(db, 2, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 1, 0, 0)
            insertNovaRow(db, 3, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 2, 0, 0)
            insertNovaRow(db, 4, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 1)
            insertNovaRow(db, 5, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_FOLDER, null, lastColumn, 0, 0)
            insertNovaRow(db, 6, 5, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 0)
            insertNovaRow(db, 7, 5, Favorites.ITEM_TYPE_APPLICATION, appIntent, 1, 0, 0)
            insertNovaRow(db, 8, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_DEEP_SHORTCUT, shortcutIntent, 0, 1, 0)
            insertNovaRow(db, 9, Favorites.CONTAINER_HOTSEAT, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 0)
            insertNovaRow(db, 10, Favorites.CONTAINER_HOTSEAT, Favorites.ITEM_TYPE_APPLICATION, appIntent, 1, 0, 0)
            if (includeWidget) {
                checkNotNull(widgetProvider) { "widget variant requires a provider flatten string" }
                check(originalGrid.numColumns >= 3) { "widget fixture needs at least 3 columns" }
                insertNovaRow(
                    db, 11, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPWIDGET, null,
                    1, 0, 1, spanX = 2, spanY = 2, appWidgetProvider = widgetProvider,
                )
            }
        }

        val novaXml = File(dir, "nova.xml")
        novaXml.writeText(
            "<map>" +
                "<string name=\"desktop_grid\">${originalGrid.numRows}x${originalGrid.numColumns}</string>" +
                "<int name=\"dock_grid_cols\" value=\"${originalGrid.numHotseatColumns}\"/>" +
                "</map>",
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
            "INSERT INTO favorites (_id, container, itemType, title, intent, cellX, cellY, screen, spanX, spanY, appWidgetProvider) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, container, itemType, "fixture_$id", intent,
                cellX.toDouble(), cellY.toDouble(), screen,
                spanX.toDouble(), spanY.toDouble(), appWidgetProvider,
            ),
        )
    }

    protected fun firstWidgetProviderFlatten(): String {
        val manager = AppWidgetManager.getInstance(context)
        val providers = manager.getInstalledProvidersForProfile(Process.myUserHandle())
            .map { it.provider.flattenToString() }
            .sorted()
        Log.i(TAG, "fixture/widgetProvider resolved count=${providers.size} (identity withheld from fixture log)")
        return providers.firstOrNull() ?: error("no widget provider available on the test device")
    }

    // ---------------------------------------------------------------
    // Grid restoration: the converter commits rows+1 (smartspace) grid
    // prefs on every restore, so both the lawnchair grid prefs and the
    // device grid state must be written back before the IDP rebind.
    // ---------------------------------------------------------------
    private fun restoreGridToOriginal() {
        val prefs = PreferenceManager.getInstance(context)
        prefs.batchEdit {
            prefs.workspaceColumns.set(originalGrid.numColumns)
            prefs.workspaceRows.set(originalGrid.numRows)
            prefs.hotseatColumns.set(originalGrid.numHotseatColumns)
        }
        DeviceGridState(
            originalGrid.numColumns,
            originalGrid.numRows,
            originalGrid.numHotseatColumns,
            InvariantDeviceProfile.TYPE_PHONE,
            originalGrid.dbFile,
        ).writeToPrefs(context, true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            InvariantDeviceProfile.INSTANCE.get(context).applyGridInfo(context, originalGrid)
        }
    }

    // ---------------------------------------------------------------
    // Snapshot / restore helpers (ManualOrganizationProductionE2E pattern).
    // ---------------------------------------------------------------
    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        launcher.model.modelDbController.db.query(
            Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID,
        ).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows += readRow(cursor, columns)
        }
        return rows
    }

    private fun restoreFavorites(rows: List<ContentValues>) {
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            rows.forEach { db.insertOrThrow(Favorites.TABLE_NAME, null, it) }
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

    private fun reloadAndWait(label: String) {
        val latch = CountDownLatch(1)
        reloadLatch = latch
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.addCallbacks(modelCallbacks)
            launcher.model.forceReload()
        }
        check(latch.await(30, TimeUnit.SECONDS)) { "$label: launcher model reload did not finish" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!launcher.model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        check(launcher.model.isModelLoaded) { "$label: launcher model did not load" }
    }

    protected companion object {
        const val TAG = "Issue299Harness"

        // Class-level originals: every restore commits a rows+1 grid, so the
        // per-test grid would creep if each setUp re-read the current state.
        @JvmStatic
        protected var classOriginalGrid: DeviceProfileOverrides.DBGridInfo? = null

        @JvmStatic
        protected var classOriginalRows: List<ContentValues>? = null
    }
}
