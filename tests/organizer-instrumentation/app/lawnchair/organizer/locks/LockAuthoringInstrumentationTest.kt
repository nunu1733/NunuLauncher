package app.lawnchair.organizer.locks

import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.adapter.LockStateDbAdapter
import app.lawnchair.organizer.planning.ItemId
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.LayoutWriteCoordinator
import com.android.launcher3.pm.UserCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-DB lock authoring through the production composition: lock/unlock
 * round-trips across folder, folder child, Dock, widget, and app pair;
 * UNKNOWN single/batch review with intent; stale/precondition rejection;
 * coordinator lease contention; and single-column write verification.
 *
 * Issue #38 spec §“Behavior scenarios”.
 */
@RunWith(AndroidJUnit4::class)
class LockAuthoringInstrumentationTest {

    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var snapshotRows: List<ContentValues> = emptyList()
    private val intent = UserReviewedIntent("instrumentation_confirm")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        snapshotRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        try {
            restoreFavorites(snapshotRows)
            launcher.model.forceReload()
            val model = launcher.model
            val deadline = System.currentTimeMillis() + 5_000L
            while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } finally {
            Unit
        }
    }

    @Test
    fun lockUnlockRoundTripsAcrossFolderChildDockWidgetAndAppPair() {
        val db = launcher.model.modelDbController.db
        val profile = UserCache.INSTANCE.get(context).getSerialNumberForUser(android.os.Process.myUserHandle())
        val folderId = insertRow(
            type = Favorites.ITEM_TYPE_FOLDER,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            lock = OrganizerLockState.UNLOCKED,
            title = "Test Folder",
            profile = profile,
        )
        val childId = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = folderId.toInt(),
            rank = 0,
            lock = OrganizerLockState.UNLOCKED,
            title = "Folder Child",
            profile = profile,
        )
        val dockId = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_HOTSEAT,
            rank = 2,
            lock = OrganizerLockState.UNLOCKED,
            title = "Dock App",
            profile = profile,
        )
        val widgetId = insertRow(
            type = Favorites.ITEM_TYPE_APPWIDGET,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            cellX = 2,
            lock = OrganizerLockState.UNLOCKED,
            title = null,
            profile = profile,
            appWidgetProvider = "${context.packageName}/WidgetProvider",
        )
        val pairId = insertRow(
            type = Favorites.ITEM_TYPE_APP_PAIR,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 1,
            lock = OrganizerLockState.UNLOCKED,
            title = null,
            profile = profile,
        )
        val memberA = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = pairId.toInt(),
            rank = 0,
            lock = OrganizerLockState.UNLOCKED,
            title = "Pair A",
            profile = profile,
        )
        val memberB = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = pairId.toInt(),
            rank = 1,
            lock = OrganizerLockState.UNLOCKED,
            title = "Pair B",
            profile = profile,
        )
        val module = OrganizerLocks.get(context)
        for (id in listOf(folderId, childId, dockId, widgetId, pairId, memberA, memberB)) {
            val locked = module.setLock(
                LockStateChangeRequest(ItemId(id.toString()), LockTargetState.LOCKED, intent),
            )
            assertTrue("lock $id failed: $locked", locked is LockChangeResult.Changed)
            assertEquals(2, lockColumn(db, id))
            val unlocked = module.setLock(
                LockStateChangeRequest(ItemId(id.toString()), LockTargetState.UNLOCKED, intent),
            )
            assertTrue("unlock $id failed: $unlocked", unlocked is LockChangeResult.Changed)
            assertEquals(1, lockColumn(db, id))
        }
        // Single-column proof: identity columns of the folder row are untouched.
        db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites.TITLE, Favorites.INTENT),
            "${Favorites._ID}=?",
            arrayOf(folderId.toString()),
            null,
            null,
            null,
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Test Folder", it.getString(0))
        }
    }

    @Test
    fun unknownReviewRequiresIntentAndResolvesSinglyAndInBatch() {
        val db = launcher.model.modelDbController.db
        val a = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            lock = OrganizerLockState.UNKNOWN,
            title = "Unknown A",
        )
        val b = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            cellX = 1,
            lock = OrganizerLockState.UNKNOWN,
            title = "Unknown B",
        )
        val module = OrganizerLocks.get(context)
        assertEquals(listOf(a.toString(), b.toString()), module.reviewListing().entries.map { it.item.value })

        // Without intent the change rejects and the DB keeps UNKNOWN.
        val rejected = module.setLock(LockStateChangeRequest(ItemId(a.toString()), LockTargetState.UNLOCKED, intent = null))
        assertEquals(LockChangeResult.Rejected(LockRejection.INTENT_REQUIRED), rejected)
        assertEquals(0, lockColumn(db, a))

        // With intent, single review resolves exactly that row.
        val single = module.setLock(LockStateChangeRequest(ItemId(a.toString()), LockTargetState.UNLOCKED, intent))
        assertTrue(single is LockChangeResult.Changed)
        assertEquals(1, lockColumn(db, a))
        assertEquals(0, lockColumn(db, b))
        assertEquals(listOf(b.toString()), module.reviewListing().entries.map { it.item.value })

        // Batch review resolves the remaining UNKNOWN rows in one transaction.
        val batch = module.reviewBatch(LockBatchReviewRequest(listOf(ItemId(b.toString())), LockTargetState.LOCKED, intent))
        assertTrue(batch is LockChangeResult.Changed)
        assertEquals(2, lockColumn(db, b))
        assertTrue(module.reviewListing().entries.isEmpty())
    }

    @Test
    fun staleRevisionRejectsWithoutMutation() {
        val adapter = LockStateDbAdapter.production(context)
        val rowId = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            lock = OrganizerLockState.UNLOCKED,
            title = "Stale",
        )
        val capture = adapter.capture()
        val decision = LockAuthoringDecision.evaluateChange(
            capture,
            LockStateChangeRequest(ItemId(rowId.toString()), LockTargetState.LOCKED, intent),
        ) as LockDecision.Ready
        // Mutate the layout after the plan was built: the revision must reject.
        val db = launcher.model.modelDbController.db
        db.update(
            Favorites.TABLE_NAME,
            ContentValues().apply { put(Favorites.MODIFIED, 987_654L) },
            "${Favorites._ID}=?",
            arrayOf(rowId.toString()),
        )
        val outcome = adapter.write(decision.plan)
        assertEquals(LockWriteOutcome.Rejected(LockWriteRejection.STALE_REVISION), outcome)
        assertEquals(1, lockColumn(db, rowId))
    }

    @Test
    fun preconditionMismatchRejectsWithoutMutation() {
        val adapter = LockStateDbAdapter.production(context)
        val rowId = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            lock = OrganizerLockState.UNLOCKED,
            title = "Precondition",
        )
        val capture = adapter.capture()
        val ready = LockAuthoringDecision.evaluateChange(
            capture,
            LockStateChangeRequest(ItemId(rowId.toString()), LockTargetState.LOCKED, intent),
        ) as LockDecision.Ready
        // Same revision, tampered expected state: exact precondition must reject.
        val tampered = ready.plan.copy(
            writes = ready.plan.writes.map { it.copy(expected = it.expected.copy(title = OptionalText.Absent)) },
        )
        val outcome = adapter.write(tampered)
        assertEquals(LockWriteOutcome.Rejected(LockWriteRejection.PRECONDITION_FAILED), outcome)
        assertEquals(1, lockColumn(launcher.model.modelDbController.db, rowId))
    }

    @Test
    fun coordinatorLeaseContentionReportsBusyWithoutMutation() {
        val rowId = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            lock = OrganizerLockState.UNLOCKED,
            title = "Busy",
        )
        val adapter = LockStateDbAdapter.production(context)
        val capture = adapter.capture()
        val ready = LockAuthoringDecision.evaluateChange(
            capture,
            LockStateChangeRequest(ItemId(rowId.toString()), LockTargetState.LOCKED, intent),
        ) as LockDecision.Ready
        LayoutWriteCoordinator.getInstance()
            .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER)
            .use { lease ->
                assertTrue(lease != null)
                val outcome = adapter.write(ready.plan)
                assertEquals(LockWriteOutcome.Rejected(LockWriteRejection.WRITER_BUSY), outcome)
            }
        assertEquals(1, lockColumn(launcher.model.modelDbController.db, rowId))
        // After release the same plan still guards on the current revision.
        val retry = adapter.write(ready.plan)
        assertTrue(
            "retry after release must commit or reject stale: $retry",
            retry is LockWriteOutcome.Committed || retry is LockWriteOutcome.Rejected,
        )
    }

    @Test
    fun transactionFailureRollsBackEveryRow() {
        val db = launcher.model.modelDbController.db
        val a = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            lock = OrganizerLockState.UNKNOWN,
            title = "Batch A",
        )
        val b = insertRow(
            type = Favorites.ITEM_TYPE_APPLICATION,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            cellX = 1,
            lock = OrganizerLockState.UNKNOWN,
            title = "Batch B",
        )
        // Deterministic mid-transaction failure without touching production code:
        // a BEFORE UPDATE trigger aborts the second row's write.
        db.execSQL(
            "CREATE TEMP TRIGGER fail_second BEFORE UPDATE ON ${Favorites.TABLE_NAME} " +
                "WHEN new.${Favorites._ID} = $b BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )
        try {
            val adapter = LockStateDbAdapter.production(context)
            val capture = adapter.capture()
            val ready = LockAuthoringDecision.evaluateReviewBatch(
                capture,
                LockBatchReviewRequest(
                    listOf(ItemId(a.toString()), ItemId(b.toString())),
                    LockTargetState.LOCKED,
                    intent,
                ),
            ) as LockDecision.Ready
            val outcome = adapter.write(ready.plan)
            assertTrue("expected failure: $outcome", outcome is LockWriteOutcome.Failed)
            assertEquals(0, lockColumn(db, a))
            assertEquals(0, lockColumn(db, b))
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_second")
        }
    }

    private fun insertRow(
        type: Int,
        container: Int,
        lock: OrganizerLockState,
        title: String?,
        profile: Long = UserCache.INSTANCE.get(context).getSerialNumberForUser(android.os.Process.myUserHandle()),
        screen: Long? = null,
        cellX: Int = 0,
        rank: Int = 0,
        appWidgetProvider: String? = null,
    ): Long {
        val controller = launcher.model.modelDbController
        val id = controller.generateNewItemId()
        val intent = if (type == Favorites.ITEM_TYPE_APPLICATION || type == Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(android.content.ComponentName(context.packageName, LawnchairLauncher::class.java.name))
                .toUri(0)
        } else {
            null
        }
        launcher.model.modelDbController.db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, title)
                put(Favorites.INTENT, intent)
                put(Favorites.CONTAINER, container)
                screen?.let { put(Favorites.SCREEN, it) }
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, type)
                put(Favorites.APPWIDGET_ID, if (type == Favorites.ITEM_TYPE_APPWIDGET) 77 else -1)
                appWidgetProvider?.let { put(Favorites.APPWIDGET_PROVIDER, it) }
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, profile)
                put(Favorites.RANK, rank)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, lock.ordinal)
            },
        )
        return id.toLong()
    }

    private fun lockColumn(db: android.database.sqlite.SQLiteDatabase, id: Long): Int = db.query(
        Favorites.TABLE_NAME,
        arrayOf(Favorites.ORGANIZER_LOCK_STATE),
        "${Favorites._ID}=?",
        arrayOf(id.toString()),
        null,
        null,
        null,
    ).use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val db = launcher.model.modelDbController.db
        val rows = mutableListOf<ContentValues>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows.add(readRow(cursor, columns))
        }
        return rows
    }

    private fun readRow(cursor: Cursor, columns: Array<String>): ContentValues {
        val values = ContentValues()
        for (index in columns.indices) {
            val name = columns[index]
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(name, cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(index))
            }
        }
        return values
    }

    private fun restoreFavorites(snapshot: List<ContentValues>) {
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (row in snapshot) {
                db.insertOrThrow(Favorites.TABLE_NAME, null, row)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
