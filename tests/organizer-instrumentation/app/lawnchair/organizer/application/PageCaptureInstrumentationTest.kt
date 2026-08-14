package app.lawnchair.organizer.application

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.planning.PageId
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.util.IntSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Page capture regression: desktop-rows-derived PageIds in deterministic
 * ascending order, model-only empty pages excluded by construction.
 *
 * Finding 1 (Stage B review).
 */
@RunWith(AndroidJUnit4::class)
class PageCaptureInstrumentationTest {

    private companion object {
        const val VALID_INTENT =
            "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;" +
                "component=com.android.settings/.Settings;launchFlags=0x10200000;end"
    }

    private lateinit var db: SQLiteDatabase
    private lateinit var model: LauncherModel
    private lateinit var writer: LauncherLayoutAdapter
    private var originalSnapshot: List<ContentValues>? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = LauncherAppState.getInstance(context)
        model = launcher.model
        writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        db = launcher.model.modelDbController.db
        originalSnapshot = snapshotFavorites()
    }

    @After
    fun tearDown() {
        originalSnapshot?.let {
            restoreFavorites(it)
            forceReloadAndWait()
        }
    }

    @Test
    fun captureReturnsDesktopRowsDerivedPageIdsInSortedAscendingOrder() {
        deleteAllFavorites()
        insertOnDesktop(screen = 5, rowId = 1)
        insertOnDesktop(screen = 2, rowId = 2)
        insertOnDesktop(screen = 8, rowId = 3)

        val snapshot = writer.captureCurrent(CaptureId("page-cap"))
        val pageIds = snapshot.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        }

        assertEquals(listOf(PageId("2"), PageId("5"), PageId("8")), pageIds)
    }

    @Test
    fun modelOnlyEmptyPagesExcludedFromCapture() {
        deleteAllFavorites()
        insertOnDesktop(screen = 0, rowId = 1)
        insertOnDesktop(screen = 1, rowId = 2)

        val snapshot = writer.captureCurrent(CaptureId("page-cap-2"))
        val pageIds = snapshot.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        }

        assertEquals(listOf(PageId("0"), PageId("1")), pageIds)
        assertTrue(
            "Page 3 has no desktop row and must be absent",
            pageIds.none { it.value == "3" },
        )
    }

    @Test
    fun repeatedCaptureReturnsDeterministicIdenticalPages() {
        deleteAllFavorites()
        insertOnDesktop(screen = 3, rowId = 1)
        insertOnDesktop(screen = 1, rowId = 2)

        val first = writer.captureCurrent(CaptureId("cap-1"))
        val second = writer.captureCurrent(CaptureId("cap-2"))
        val third = writer.recaptureDb()

        val expected = listOf(PageId("1"), PageId("3"))
        assertEquals(expected, first.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        })
        assertEquals(expected, second.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        })
        assertEquals(expected, third.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        })
    }

    private fun deleteAllFavorites() {
        db.delete(Favorites.TABLE_NAME, null, null)
    }

    private fun insertOnDesktop(screen: Long, rowId: Long) {
        val cv = ContentValues().apply {
            put(Favorites._ID, rowId)
            put(Favorites.TITLE, "app-$screen")
            put(Favorites.INTENT, VALID_INTENT)
            put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            put(Favorites.SCREEN, screen)
            put(Favorites.CELLX, 0)
            put(Favorites.CELLY, 0)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.APPWIDGET_ID, -1)
            put(Favorites.MODIFIED, 1000L)
            put(Favorites.RESTORED, 0)
            put(Favorites.PROFILE_ID, 0L)
            put(Favorites.RANK, 0)
            put(Favorites.OPTIONS, 0)
            put(Favorites.APPWIDGET_SOURCE, -1)
            put(Favorites.ORGANIZER_LOCK_STATE, 1)
        }
        db.insertOrThrow(Favorites.TABLE_NAME, null, cv)
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

    private fun forceReloadAndWait() {
        val latch = CountDownLatch(1)
        val callback = object : BgDataModel.Callbacks {
            override fun finishBindingItems(pagesBoundFirst: IntSet) {
                latch.countDown()
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.addCallbacks(callback) }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.forceReload() }
            check(latch.await(15L, TimeUnit.SECONDS)) { "Model reload timed out after favorites restoration" }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.removeCallbacks(callback) }
        }
    }
}
