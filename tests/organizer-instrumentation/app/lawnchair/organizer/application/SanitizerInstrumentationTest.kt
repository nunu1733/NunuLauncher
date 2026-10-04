package app.lawnchair.organizer.application

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.OrganizerModelReloadAdapter
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.LayoutWriteCoordinator
import com.android.launcher3.util.IntSet
import com.android.launcher3.widget.LauncherWidgetHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit-level gate verification: the organizer-requested LoaderTask carries a
 * non-zero mOrganizerLeaseToken and therefore skips the three sanitizer families
 * (sanitizeFolders, sanitizeAppPairs, sanitizeWidgetsShortcutsAndPackages)
 * that a normal reload executes. Verified by observing the DB effect of a
 * synchronous correlated reload through the production OrganizerModelReloadAdapter.
 *
 * Finding 1 (Stage B review).
 */
@RunWith(AndroidJUnit4::class)
class SanitizerInstrumentationTest {

    companion object {
        private const val EMPTY_FOLDER_ID = 9001L
        private const val MALFORMED_APP_PAIR_ID = 9002L
        private const val UNPARENTED_APP_ID = 9003L
        private const val UNPARENTED_SHORTCUT_ID = 9004L
        private const val INVALID_PACKAGE_ID = 9005L
        private const val UNPARENTED_CONTAINER_ID = 9999L
        private const val RELOAD_TIMEOUT_SECONDS = 15L

        // A real installed package so the fixture survives loadWorkspace and is only
        // removed by the sanitizer pass.
        private const val VALID_INTENT =
            "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;" +
                "component=com.android.settings/.Settings;launchFlags=0x10200000;end"
    }

    private lateinit var model: LauncherModel
    private lateinit var db: SQLiteDatabase
    private lateinit var widgetHolder: LauncherWidgetHolder
    private var ghostWidgetId: Int? = null
    private var originalSnapshot: List<ContentValues>? = null
    private val addedCallbacks = mutableListOf<BgDataModel.Callbacks>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = LauncherAppState.getInstance(context)
        model = launcher.model
        db = model.modelDbController.db
        widgetHolder = LauncherWidgetHolder.newInstance(context)
        originalSnapshot = snapshotFavorites()
    }

    @After
    fun tearDown() {
        ArrayList(addedCallbacks).forEach { removeModelCallback(it) }
        ghostWidgetId?.let { id ->
            if (id in widgetHolder.appWidgetIds) widgetHolder.deleteAppWidgetId(id)
        }
        widgetHolder.destroy()
        originalSnapshot?.let {
            restoreFavorites(it)
            forceReloadAndWait()
        }
    }

    @Test
    fun ordinaryReloadSanitizesEmptyFolderMalformedAppPairAndInvalidPackageShortcut() {
        insertFixtures()
        val ghostId = insertGhostWidgetFixture()
        insertInvalidPackage(INVALID_PACKAGE_ID, Favorites.CONTAINER_DESKTOP, 0, 4, 0)
        model.modelDbController.clearEmptyDbFlag()
        forceReloadAndWait()

        val afterSnapshot = snapshotFavorites()
        val afterIds = afterSnapshot.map { it.getAsLong(Favorites._ID) }

        assertTrue("Empty folder should be sanitized", EMPTY_FOLDER_ID !in afterIds)
        assertTrue("Malformed app pair should be sanitized", MALFORMED_APP_PAIR_ID !in afterIds)
        assertTrue("Unparented app should be sanitized", UNPARENTED_APP_ID !in afterIds)
        assertTrue("Unparented shortcut should be sanitized", UNPARENTED_SHORTCUT_ID !in afterIds)
        assertTrue("Invalid package should be sanitized", INVALID_PACKAGE_ID !in afterIds)
        assertTrue("Ghost widget should be sanitized", ghostId !in widgetHolder.appWidgetIds)
        assertEquals(
            "Only the pre-existing rows should remain after sanitization",
            originalSnapshot?.size ?: 0,
            afterSnapshot.size,
        )
    }

    @Test
    fun correlatedReloadPreservesGhostWidgetAndLeavesDatabaseUnchanged() {
        model.modelDbController.clearEmptyDbFlag()

        val dummyCallback = object : BgDataModel.Callbacks {}
        addModelCallback(dummyCallback)
        try {
            forceReloadAndWait()
            waitForModelLoaded()
            val ghostId = insertGhostWidgetFixture()
            val expectedSnapshot = snapshotFavorites()
            val lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER)
            assertNotNull("Must obtain organizer lease", lease)
            lease!!

            val adapter = OrganizerModelReloadAdapter(model, Handler(Looper.getMainLooper()))
            val outcome = try {
                adapter.requestAndWait(lease.token())
            } finally {
                lease.close()
            }
            assertEquals(
                "Correlated reload must complete successfully",
                OrganizerModelReloadAdapter.Outcome.COMPLETED,
                outcome,
            )

            val afterSnapshot = snapshotFavorites()
            assertTrue("Ghost widget should survive organizer reload", ghostId in widgetHolder.appWidgetIds)
            assertEquals(expectedSnapshot.size, afterSnapshot.size)
            assertTrue(expectedSnapshot.zip(afterSnapshot).all { (expected, actual) ->
                equalContentValues(expected, actual)
            })
        } finally {
            removeModelCallback(dummyCallback)
        }
    }

    private fun fixtureIds(): List<Long> = listOf(
        EMPTY_FOLDER_ID,
        MALFORMED_APP_PAIR_ID,
        UNPARENTED_APP_ID,
        UNPARENTED_SHORTCUT_ID,
    )

    private fun insertFixtures() {
        insertFolder(EMPTY_FOLDER_ID, "Empty Folder", Favorites.CONTAINER_DESKTOP, 0, 0, 0)
        insertAppPair(MALFORMED_APP_PAIR_ID, "Malformed Pair", Favorites.CONTAINER_DESKTOP, 0, 1, 0)
        insertApplication(UNPARENTED_APP_ID, VALID_INTENT, UNPARENTED_CONTAINER_ID, 0, 2, 0)
        insertShortcut(UNPARENTED_SHORTCUT_ID, UNPARENTED_CONTAINER_ID, 0, 3, 0)
    }

    private fun insertGhostWidgetFixture(): Int = widgetHolder.allocateAppWidgetId().also {
        ghostWidgetId = it
    }

    private fun insertFolder(
        id: Long,
        title: String,
        container: Int,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ) {
        val cv = baseContentValues(id, title, container, screen, cellX, cellY)
        cv.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
        cv.put(Favorites.INTENT, null as String?)
        db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
    }

    private fun insertAppPair(
        id: Long,
        title: String,
        container: Int,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ) {
        val cv = baseContentValues(id, title, container, screen, cellX, cellY)
        cv.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APP_PAIR)
        cv.put(Favorites.INTENT, null as String?)
        db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
    }

    private fun insertApplication(
        id: Long,
        intent: String,
        container: Long,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ) {
        val cv = baseContentValues(id, "Unparented App", container.toInt(), screen, cellX, cellY)
        cv.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
        cv.put(Favorites.INTENT, intent)
        db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
    }

    private fun insertShortcut(
        id: Long,
        container: Long,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ) {
        val cv = baseContentValues(id, "Unparented Shortcut", container.toInt(), screen, cellX, cellY)
        cv.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_SHORTCUT)
        cv.put(Favorites.INTENT, VALID_INTENT)
        db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
    }

    private fun insertInvalidPackage(
        id: Long,
        container: Int,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ) {
        val cv = baseContentValues(id, "Invalid Package", container, screen, cellX, cellY)
        cv.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
        cv.put(Favorites.INTENT, null as String?)
        db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
    }

    private fun baseContentValues(
        id: Long,
        title: String,
        container: Int,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ): ContentValues {
        val cv = ContentValues()
        cv.put(Favorites._ID, id)
        cv.put(Favorites.TITLE, title)
        cv.put(Favorites.CONTAINER, container)
        cv.put(Favorites.SCREEN, screen)
        cv.put(Favorites.CELLX, cellX)
        cv.put(Favorites.CELLY, cellY)
        cv.put(Favorites.SPANX, 1)
        cv.put(Favorites.SPANY, 1)
        cv.put(Favorites.APPWIDGET_ID, -1)
        cv.put(Favorites.APPWIDGET_PROVIDER, null as String?)
        cv.put(Favorites.MODIFIED, 1000L)
        cv.put(Favorites.RESTORED, 0)
        cv.put(Favorites.PROFILE_ID, 0L)
        cv.put(Favorites.RANK, 0)
        cv.put(Favorites.OPTIONS, 0)
        cv.put(Favorites.APPWIDGET_SOURCE, -1)
        cv.put(Favorites.ORGANIZER_LOCK_STATE, 1)
        return cv
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) {
                rows.add(readRow(cursor, columns))
            }
        }
        return rows
    }

    private fun readRow(cursor: Cursor, columns: Array<String>): ContentValues {
        val cv = ContentValues()
        for (i in columns.indices) {
            val name = columns[i]
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> cv.putNull(name)
                Cursor.FIELD_TYPE_INTEGER -> cv.put(name, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> cv.put(name, cursor.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> cv.put(name, cursor.getString(i))
                Cursor.FIELD_TYPE_BLOB -> cv.put(name, cursor.getBlob(i))
            }
        }
        return cv
    }

    private fun restoreFavorites(snapshot: List<ContentValues>) {
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (cv in snapshot) {
                db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun equalContentValues(a: ContentValues, b: ContentValues): Boolean {
        if (a.size() != b.size()) return false
        for ((key, valueA) in a.valueSet()) {
            val valueB = b.get(key)
            if (!equalValues(valueA, valueB)) return false
        }
        return true
    }

    private fun equalValues(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a is Number && b is Number) return a.toLong() == b.toLong()
        if (a is ByteArray && b is ByteArray) return a.contentEquals(b)
        return a == b
    }

    private fun forceReloadAndWait() {
        val latch = CountDownLatch(1)
        val callback = object : BgDataModel.Callbacks {
            override fun finishBindingItems(pagesBoundFirst: IntSet) {
                latch.countDown()
            }
        }
        addModelCallback(callback)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.forceReload()
            }
            if (!latch.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw AssertionError("Ordinary reload did not complete within timeout")
            }
        } finally {
            removeModelCallback(callback)
        }
    }

    private fun waitForModelLoaded() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RELOAD_TIMEOUT_SECONDS)
        while (!model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        check(model.isModelLoaded) { "Launcher model did not become idle before correlated reload" }
    }

    private fun addModelCallback(callback: BgDataModel.Callbacks) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.addCallbacks(callback)
        }
        addedCallbacks.add(callback)
    }

    private fun removeModelCallback(callback: BgDataModel.Callbacks) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.removeCallbacks(callback)
        }
        addedCallbacks.remove(callback)
    }
}
