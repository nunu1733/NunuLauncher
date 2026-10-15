package app.lawnchair.organizer.application

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.model.BgDataModel
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
 * Issue #581: QSB-off normalization must preserve every row-backed page,
 * including screen 0, while retaining #155's enabled and empty-set fallback.
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
    private lateinit var preferences: PreferenceManager2
    private var originalSnapshot: List<ContentValues>? = null
    private var originalSmartspaceEnabled: Boolean? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = LauncherAppState.getInstance(context)
        preferences = PreferenceManager2.getInstance(context)
        originalSmartspaceEnabled = preferences.enableSmartspace.firstBlocking()
        model = launcher.model
        writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        db = launcher.model.modelDbController.db
        originalSnapshot = snapshotFavorites()
    }

    @After
    fun tearDown() {
        try {
            // Restore rows while the permissive QSB-off loader rule is active,
            // then restore the original setting and reload the original layout.
            if (::preferences.isInitialized) setSmartspacePreference(false)
            originalSnapshot?.let(::restoreFavorites)
        } finally {
            originalSmartspaceEnabled?.let(::setSmartspacePreference)
            if (originalSnapshot != null) forceReloadAndWait()
        }
    }

    @Test
    fun captureReturnsDesktopRowsDerivedPageIdsInSortedAscendingOrder() {
        setQsbEnabled(false)
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
        setQsbEnabled(false)
        deleteAllFavorites()
        insertOnDesktop(screen = 0, rowId = 1)
        insertOnDesktop(screen = 1, rowId = 2)

        val snapshot = writer.captureCurrent(CaptureId("page-cap-2"))
        val pageIds = snapshot.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        }

        assertEquals(listOf(PageId("0"), PageId("1")), pageIds)
        assertTrue("Row-backed page 0 must remain in the page inventory", pageIds.contains(PageId("0")))
        assertTrue("Row-backed page 1 must remain in the page inventory", pageIds.contains(PageId("1")))
        assertTrue(
            "Page 3 has no desktop row and must be absent",
            pageIds.none { it.value == "3" },
        )
    }

    @Test
    fun qsbOffKeepsFirstScreenWhenItIsTheOnlyRowBackedPage() {
        setQsbEnabled(false)
        deleteAllFavorites()
        insertOnDesktop(screen = 0, rowId = 1)

        val snapshot = writer.captureCurrent(CaptureId("page-cap-off-first-only"))
        val pageIds = snapshot.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        }

        assertEquals(listOf(PageId("0")), pageIds)
    }

    @Test
    fun qsbOffEmptyWorkspaceRetainsLogicalFirstScreenFallback() {
        setQsbEnabled(false)
        deleteAllFavorites()

        val snapshot = writer.captureCurrent(CaptureId("page-cap-off-empty"))
        val pageIds = snapshot.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        }

        assertEquals(listOf(PageId("0")), pageIds)
    }

    @Test
    fun qsbOnSynthesizesFirstScreenForRowlessFirstPage() {
        deleteAllFavorites()
        setQsbEnabled(true)
        insertOnDesktop(screen = 1, rowId = 1)

        val snapshot = writer.captureCurrent(CaptureId("page-cap-on-synthetic-first"))
        val pageIds = snapshot.layoutState.pages.map {
            (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
        }

        assertEquals(listOf(PageId("0"), PageId("1")), pageIds)
    }

    @Test
    fun repeatedCaptureReturnsDeterministicIdenticalPages() {
        setQsbEnabled(false)
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

    private fun setSmartspacePreference(enabled: Boolean) {
        // The real preference callback recreates the active Launcher activity.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            preferences.enableSmartspace.setBlocking(enabled)
        }
    }

    private fun setQsbEnabled(enabled: Boolean) {
        setSmartspacePreference(enabled)
        assertEquals(
            "The real preference seam must control Launcher first-screen QSB",
            enabled,
            FeatureFlags.topQsbOnFirstScreenEnabled(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )
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
            override fun bindCompleteModel(itemIdMap: com.android.launcher3.model.data.WorkspaceData, isBindingSync: Boolean) {
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
