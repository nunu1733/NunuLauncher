/*
 * Issue #449: real-framework integration for the edit-surface apply path.
 * The oracle is the production adapter + protocol on the real Launcher DB:
 * a session-built plan applies as one transaction (physical DELETE of removed
 * rows via the manifest-absence delete pass, INSERT of the untitled folder,
 * UPDATE of the members), an injected write failure rolls back to the exact
 * pre-state, and a stale revision is rejected with zero writes at the module
 * boundary. The apply protocol's own contract tests (rollback matrix, exact
 * preconditions, recovery) remain owned by the existing suites; this class
 * owns only the edit-surface session → plan → apply integration.
 */
package app.lawnchair.homeedit

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.ApplyTxOutcome
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.protocol.WriterKind
import app.lawnchair.organizer.application.protocol.WriteSetPreparation
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditSurfaceApplyInstrumentationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var snapshotRows: List<ContentValues> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        snapshotRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        restoreFavorites(snapshotRows)
        launcher.model.forceReload()
        waitForModelLoaded()
    }

    @Test
    fun sessionAppliesAsOneTransactionWithPhysicalDeleteAndUntitledFolder() {
        seedDesktopApps(
            Triple(0, 2, 1),
            Triple(0, 0, 1),
            Triple(1, 0, 0),
        )
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val capture = writer.captureCurrent(CaptureId("edit-surface-apply"))
        val ids = capture.layoutState.items
            .filter { it.placement is app.lawnchair.organizer.application.public.PlacementState.Workspace }
            .associateBy { (it.placement as app.lawnchair.organizer.application.public.PlacementState.Workspace).let { p -> Triple(p.page.pageId.value.toInt(), p.cell.x, p.cell.y) } }
            .mapValues { (it.value.ref as app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem).itemId.value.toInt() }
        val aId = ids.getValue(Triple(0, 2, 1))
        val bId = ids.getValue(Triple(0, 0, 1))
        val cId = ids.getValue(Triple(1, 0, 0))

        // Session: create a folder from C and A (visual order: C on page 1
        // comes after A on page 0, so A is the visual head), then remove B.
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val afterFolder = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(cId, aId),
            PendingSessionAction.CreateFolder,
        )
        val session = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            (afterFolder as SessionPlanResult.Applied).session,
            listOf(bId),
            PendingSessionAction.RemoveFromHome,
        ) as SessionPlanResult.Applied

        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as app.lawnchair.organizer.rules.BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            session.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready
        val prepared = writer.prepareApplyWriteSet(capture, built.plan) as? WriteSetPreparation.Ready
            ?: error("session plan did not materialize")
        val outcome = writer.withLease(WriterKind.ORGANIZER, 449001L) {
            writer.applyWriteSet(it, prepared.writeSet, null, FaultInjector.NOOP)
        }
        assertTrue("expected Committed, got $outcome", outcome is ApplyTxOutcome.Committed)

        // The A7-equivalent oracle: the post-write recapture equals the exact
        // intended state. Without the delete pass the removed row survives and
        // this comparison fails.
        assertEquals(built.plan.intendedState, writer.recaptureDb().layoutState)

        // The removed row is physically deleted.
        db().query(Favorites.TABLE_NAME, arrayOf(Favorites._ID), "${Favorites._ID}=?", arrayOf(bId.toString()), null, null, null).use {
            assertFalse("the removed row must be deleted", it.moveToFirst())
        }
        // The new folder row is inserted untitled (TITLE null) at the visual
        // head's cell, and both members joined it in session order.
        val folderId = db().query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID),
            "${Favorites.ITEM_TYPE}=? AND ${Favorites.CONTAINER}=?",
            arrayOf(Favorites.ITEM_TYPE_FOLDER.toString(), Favorites.CONTAINER_DESKTOP.toString()),
            null,
            null,
            null,
        ).use {
            assertTrue("the new folder row is missing", it.moveToFirst())
            it.getLong(0)
        }
        fun queryRow(id: Int, column: String): Any? = db().query(
            Favorites.TABLE_NAME,
            arrayOf(column),
            "${Favorites._ID}=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use {
            assertTrue(it.moveToFirst())
            when (it.getType(0)) {
                android.database.Cursor.FIELD_TYPE_NULL -> null
                else -> it.getLong(0)
            }
        }
        assertEquals(null, queryRow(folderId, Favorites.TITLE))
        assertEquals(folderId, queryRow(aId, Favorites.CONTAINER))
        assertEquals(0L, queryRow(aId, Favorites.RANK))
        assertEquals(folderId, queryRow(cId, Favorites.CONTAINER))
        assertEquals(1L, queryRow(cId, Favorites.RANK))

        launcher.model.forceReload()
        waitForModelLoaded()
    }

    @Test
    fun injectedWriteFailureRollsBackToTheExactPreState() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1))
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val capture = writer.captureCurrent(CaptureId("edit-surface-rollback"))
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val firstId = capture.layoutState.items
            .map { (it.ref as app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem).itemId.value.toInt() }
            .min()
        val session = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(firstId),
            PendingSessionAction.MoveToPage(1),
        ) as SessionPlanResult.Applied
        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as app.lawnchair.organizer.rules.BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            session.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready
        val prepared = writer.prepareApplyWriteSet(capture, built.plan) as? WriteSetPreparation.Ready
            ?: error("session plan did not materialize")

        val failing = object : FaultInjector {
            override fun beforeLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?) {
                throw IllegalStateException("injected write failure")
            }
        }
        val outcome = writer.withLease(WriterKind.ORGANIZER, 449002L) {
            writer.applyWriteSet(it, prepared.writeSet, null, failing)
        }
        assertTrue("expected Failed, got $outcome", outcome is ApplyTxOutcome.Failed)
        // The transaction rolled back: the rows match the capture exactly.
        assertEquals(capture.layoutState, writer.recaptureDb().layoutState)
        assertEquals(capture.manifest, writer.recaptureDb().manifest)

        launcher.model.forceReload()
        waitForModelLoaded()
    }

    @Test
    fun staleRevisionIsRejectedWithZeroWrites() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1))
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val capture = writer.captureCurrent(CaptureId("edit-surface-stale"))
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val firstId = capture.layoutState.items
            .map { (it.ref as app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem).itemId.value.toInt() }
            .min()
        val session = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(firstId),
            PendingSessionAction.MoveToPage(1),
        ) as SessionPlanResult.Applied
        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as app.lawnchair.organizer.rules.BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            session.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready

        // The home changes after the session capture (a new icon appears).
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(0, 3, 1))

        val clock = SystemClock()
        val module = LayoutApplicationModule(
            LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model),
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
            folderTitleResolver = GeneratedFolderTitles.resolver(context),
        )
        module.reconcileAtStart()
        val result = module.apply(built.plan)
        assertTrue("expected STALE_REVISION, got $result", result is ApplyResult.Rejected && result.reason == PreWriteRejection.STALE_REVISION)
        // Zero writes: the moved row is still at its capture placement.
        val after = writer.recaptureDb()
        val movedRow = after.layoutState.items.first { item ->
            (item.ref as app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem).itemId.value.toInt() == firstId
        }
        assertEquals(capture.layoutState.items.first { it.ref == movedRow.ref }, movedRow)
        // The projection must never produce a NoChanges plan for a non-empty
        // session — the empty-diff guard is the pure builder's contract; here
        // we only assert the stale outcome carried no state change.
        assertFalse(after.layoutState == built.plan.intendedState)

        launcher.model.forceReload()
        waitForModelLoaded()
    }

    // --- fixture helpers ---

    private fun db() = launcher.model.modelDbController.db

    private fun seedDesktopApps(vararg placements: Triple<Int, Int, Int>) {
        db().beginTransaction()
        try {
            db().delete(Favorites.TABLE_NAME, null, null)
            for ((screen, cellX, cellY) in placements) {
                val id = launcher.model.modelDbController.generateNewItemId()
                db().insertOrThrow(Favorites.TABLE_NAME, null, desktopRowValues(id.toLong(), screen, cellX, cellY))
            }
            db().setTransactionSuccessful()
        } finally {
            db().endTransaction()
        }
        launcher.model.forceReload()
        waitForModelLoaded()
    }

    private fun desktopRowValues(id: Long, screen: Int, cellX: Int, cellY: Int): ContentValues = ContentValues().apply {
        put(Favorites._ID, id)
        put(Favorites.TITLE, "Edit surface fixture $id")
        put(
            Favorites.INTENT,
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
                .toUri(0),
        )
        put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
        put(Favorites.SCREEN, screen)
        put(Favorites.CELLX, cellX)
        put(Favorites.CELLY, cellY)
        put(Favorites.SPANX, 1)
        put(Favorites.SPANY, 1)
        put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
        put(Favorites.RANK, 0)
        put(
            Favorites.PROFILE_ID,
            com.android.launcher3.pm.UserCache.INSTANCE.get(context)
                .getSerialNumberForUser(android.os.Process.myUserHandle()),
        )
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        db().query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows.add(readRow(cursor, columns))
        }
        return rows
    }

    private fun readRow(cursor: android.database.Cursor, columns: Array<String>): ContentValues {
        val values = ContentValues()
        for (index in columns.indices) {
            when (cursor.getType(index)) {
                android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(columns[index])
                android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(columns[index], cursor.getLong(index))
                android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(columns[index], cursor.getDouble(index))
                android.database.Cursor.FIELD_TYPE_STRING -> values.put(columns[index], cursor.getString(index))
                android.database.Cursor.FIELD_TYPE_BLOB -> values.put(columns[index], cursor.getBlob(index))
            }
        }
        return values
    }

    private fun restoreFavorites(snapshot: List<ContentValues>) {
        db().beginTransaction()
        try {
            db().delete(Favorites.TABLE_NAME, null, null)
            for (row in snapshot) db().insertOrThrow(Favorites.TABLE_NAME, null, row)
            db().setTransactionSuccessful()
        } finally {
            db().endTransaction()
        }
    }

    private fun waitForModelLoaded() {
        val model = launcher.model
        val deadline = System.currentTimeMillis() + 10_000L
        while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }
}
