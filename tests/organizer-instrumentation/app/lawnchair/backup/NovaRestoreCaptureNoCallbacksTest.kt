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
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.integration.CanonicalCaptureReadResult
import app.lawnchair.organizer.integration.LayoutWriterCanonicalCaptureSource
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.DeviceGridState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #299 re-review regression (CI-AC-02, inactive-model branch): a Nova
 * restore performed with NO model callbacks registered (settings-only
 * process shape) must return with the restore-correlated repair generation
 * already committed — no unbound widget row remains — and the immediately
 * following production authoritative capture must be Ready.
 *
 * The restore path routes the empty-callback-list reload through
 * startLoaderWithoutCallbacks (LauncherModel.dispatchRestoreReload), so the
 * completion barrier holds even here. Run in its own instrumentation
 * process: this test intentionally registers no BgDataModel callbacks, so
 * it must not share a process with tests that do.
 */
@RunWith(AndroidJUnit4::class)
class NovaRestoreCaptureNoCallbacksTest {

    @Test
    fun restoreReloadDispatchFromWorkerTerminalizesWithoutUiThreadViolation() {
        val model = LauncherAppState.getInstance(context()).model
        val terminal = CountDownLatch(1)
        val terminalOutcome = AtomicReference<String?>()
        val terminalCallbacks = AtomicInteger(0)
        val callbackFailure = AtomicReference<Throwable?>()
        val workerFailure = AtomicReference<Throwable?>()
        val requestId = model.beginRestoreReload()

        fun recordTerminal(outcome: String) {
            if (terminalCallbacks.incrementAndGet() != 1) {
                callbackFailure.compareAndSet(
                    null,
                    AssertionError("restore reload signaled more than once"),
                )
            } else {
                terminalOutcome.set(outcome)
            }
            terminal.countDown()
        }

        val worker = Thread {
            try {
                model.dispatchRestoreReload(
                    requestId,
                    { recordTerminal("completed") },
                    { recordTerminal("cancelled") },
                )
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        worker.isDaemon = true

        worker.start()
        try {
            worker.join(TimeUnit.SECONDS.toMillis(30))
            assertFalse("worker dispatch must not hang", worker.isAlive)
        } finally {
            if (worker.isAlive) worker.interrupt()
        }

        assertNull("worker dispatch must not call the UI-thread-only loader directly", workerFailure.get())
        assertTrue(
            "worker-dispatched reload did not reach a terminal outcome",
            terminal.await(30, TimeUnit.SECONDS),
        )
        assertNull("restore reload callback must be signaled exactly once", callbackFailure.get())
        assertEquals(
            "worker-dispatched reload must complete in a quiescent no-callback process",
            "completed",
            terminalOutcome.get(),
        )
    }

    @Test
    fun novaRestoreWithoutCallbacks_returnsWithRepairedWorkspace_andImmediateCaptureIsReady() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Creating the app instance does NOT register model callbacks; assert
        // the inactive-model precondition this test exists for.
        val app = LauncherAppState.getInstance(context)
        check(!app.getModel().hasCallbacks()) {
            "this test requires a process with no model callbacks; run it in its own instrumentation process"
        }

        val provider = firstInstalledWidgetProvider(context)
        val zip = buildNovaBackupZip(context, includeWidget = true, widgetProvider = provider)
        val converter = NovaBackupConverter(context, Uri.fromFile(zip))
        val info = runBlocking { converter.parseInfo() }
        runBlocking { converter.convertAndRestore(info) }

        // At return time the completion barrier has held the restore until
        // the repair generation (routed through startLoaderWithoutCallbacks)
        // committed: the widget row is bound and the production capture is
        // Ready — no unbound-row window may survive the restore call.
        val restoredDbName = DeviceGridState(context).dbFile
        val widgetState = queryWidgetState(context, restoredDbName)
        Log.i(TAG, "noCallbacks/postRestore: persisted widget state=$widgetState")
        assertEquals(
            "the restore must return with its widget row already repaired",
            0 to 1,
            widgetState,
        )
        assertTrue(
            "the immediately following authoritative capture must be Ready",
            captureThroughProductionSource(),
        )
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun captureThroughProductionSource(): Boolean {
        val app = LauncherAppState.getInstance(context())
        val source = LayoutWriterCanonicalCaptureSource(
            LauncherLayoutAdapter(context(), app.model.modelDbController, app.model),
        )
        return source.capture() is CanonicalCaptureReadResult.Ready
    }

    /** Returns (unbound-id rows, valid-id rows) for widget-kind rows. */
    private fun queryWidgetState(context: Context, dbName: String): Pair<Int, Int> {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        var negative = 0
        var valid = 0
        db.use { readable ->
            readable.query(
                Favorites.TABLE_NAME,
                arrayOf(Favorites.ITEM_TYPE, Favorites.APPWIDGET_ID),
                "${Favorites.ITEM_TYPE} IN (${Favorites.ITEM_TYPE_APPWIDGET}, ${Favorites.ITEM_TYPE_CUSTOM_APPWIDGET})",
                null, null, null, null,
            ).use { cursor ->
                val typeIdx = cursor.getColumnIndexOrThrow(Favorites.ITEM_TYPE)
                val idIdx = cursor.getColumnIndexOrThrow(Favorites.APPWIDGET_ID)
                while (cursor.moveToNext()) {
                    val kind = cursor.getInt(typeIdx)
                    if (kind == Favorites.ITEM_TYPE_APPWIDGET || kind == Favorites.ITEM_TYPE_CUSTOM_APPWIDGET) {
                        if (cursor.getInt(idIdx) < 0) negative++ else valid++
                    }
                }
            }
        }
        return negative to valid
    }

    private fun firstInstalledWidgetProvider(context: Context): String =
        AppWidgetManager.getInstance(context)
            .getInstalledProvidersForProfile(Process.myUserHandle())
            .map { it.provider.flattenToString() }
            .sorted()
            .firstOrNull()
            ?: error("no widget provider available on the test device")

    private fun buildNovaBackupZip(context: Context, includeWidget: Boolean, widgetProvider: String?): File {
        val dir = File(context.cacheDir, "nova_fixture_${UUID.randomUUID()}").apply { mkdirs() }
        val novaDb = File(dir, "nova.db")
        SQLiteDatabase.openOrCreateDatabase(novaDb, null).use { db ->
            db.execSQL(
                "CREATE TABLE favorites (_id INTEGER PRIMARY KEY, container INTEGER, itemType INTEGER, " +
                    "title TEXT, intent TEXT, cellX REAL, cellY REAL, screen INTEGER, spanX REAL, " +
                    "spanY REAL, icon BLOB, appWidgetProvider TEXT)",
            )
            val appIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
                .toUri(0)
            insertNovaRow(db, 1, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 0, 0, 0)
            insertNovaRow(db, 2, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPLICATION, appIntent, 1, 0, 0)
            if (includeWidget) {
                insertNovaRow(
                    db, 3, Favorites.CONTAINER_DESKTOP, Favorites.ITEM_TYPE_APPWIDGET, null,
                    1, 0, 1, spanX = 2, spanY = 2, appWidgetProvider = widgetProvider,
                )
            }
        }
        File(dir, "nova.xml").writeText(
            "<map><string name=\"desktop_grid\">4x4</string>" +
                "<int name=\"dock_grid_cols\" value=\"4\"/></map>",
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
            "INSERT INTO favorites (_id, container, itemType, title, intent, cellX, cellY, screen, " +
                "spanX, spanY, appWidgetProvider) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id, container, itemType, "fixture_$id", intent,
                cellX.toDouble(), cellY.toDouble(), screen,
                spanX.toDouble(), spanY.toDouble(), appWidgetProvider,
            ),
        )
    }

    private companion object {
        const val TAG = "Issue299Harness"
    }
}
