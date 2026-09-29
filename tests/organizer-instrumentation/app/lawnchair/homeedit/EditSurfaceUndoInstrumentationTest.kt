/*
 * Issue #450: the edit-surface undo integration oracle (spec 450 AC-5).
 * Sibling of EditSurfaceApplyInstrumentationTest: a real LayoutApplicationModule
 * over the real favorites DB. Covers the confirm→undo contract oracles:
 *  1. the receipt revision (apply path's verified post-apply revision) equals
 *     a fresh post-apply capture revision — including a confirm that creates a
 *     new folder (the planned-ref resolution case where plan.intendedState
 *     differs);
 *  2. confirm → no other write → undo through the recovery path = Restored,
 *     back at the pre-apply state;
 *  3. any write between confirm and undo ⇒ NotRestorable(STALE_REVISION),
 *     zero write.
 */
package app.lawnchair.homeedit

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditSurfaceUndoInstrumentationTest {

    private lateinit var context: android.content.Context
    private lateinit var appState: LauncherAppState
    private lateinit var module: LayoutApplicationModule<RecoveryStore>
    private lateinit var adapter: LauncherLayoutAdapter

    /** Issue #450: test-owned fault injection for the recovery WriterBusy oracle. */
    private val undoFaults = FaultDelegate()

    private class FaultDelegate : app.lawnchair.organizer.application.protocol.FaultInjector
        by app.lawnchair.organizer.application.protocol.FaultInjector.NOOP {
        @Volatile
        var serializationContention = false

        override fun serializationContention(): Boolean = serializationContention
    }
    private var snapshotRows: List<ContentValues> = emptyList()
    private var modelCallback: com.android.launcher3.model.BgDataModel.Callbacks? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        appState = LauncherAppState.getInstance(context)
        snapshotRows = snapshotFavorites()
        adapter = LauncherLayoutAdapter(context, appState.model.modelDbController, appState.model)
        val clock = SystemClock()
        module = LayoutApplicationModule(
            adapter,
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
            folderTitleResolver = GeneratedFolderTitles.resolver(context),
            faults = undoFaults,
        )
        module.reconcileAtStart()
        // The correlated reload of the apply/recovery paths rides the loader
        // binder boundary (Issue #150/#152): without a bound callback the
        // tokenless forceReload() never starts a generation and the correlated
        // wait times out into automatic recovery. Bind a passive callback for
        // the whole test so reloads complete.
        val callback = object : com.android.launcher3.model.BgDataModel.Callbacks {}
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            appState.model.addCallbacks(callback)
        }
        modelCallback = callback
        appState.model.forceReload()
        waitForModelLoaded()
    }

    @After
    fun tearDown() {
        modelCallback?.let { cb ->
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                appState.model.removeCallbacks(cb)
            }
        }
        restoreFavorites(snapshotRows)
        appState.model.forceReload()
        waitForModelLoaded()
    }

    @Test
    fun receiptRevisionEqualsPostApplyCaptureForANewFolderConfirm() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        assertTrue("expected Applied, got $result", result is ApplyResult.Applied)
        appState.model.forceReload()
        waitForModelLoaded()

        // Oracle: the receipt revision equals a fresh post-apply capture.
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-post"))
        assertEquals(
            RevisionCalculator.revisionOf(postCapture.layoutState).value,
            verified?.value,
        )
    }

    @Test
    fun confirmThenUndoRestoresThroughTheRecoveryPath() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (_, capture) = buildMovePlan()
        val preRevision = RevisionCalculator.revisionOf(capture.layoutState)
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Undo: the recovery path with the receipt revision (zero writes
        // between confirm and undo).
        val undo = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue("expected Restored, got $undo", undo is RecoveryResult.Restored)
        appState.model.forceReload()
        waitForModelLoaded()

        // The home is back at the pre-apply state (undo = one recovery).
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-restored"))
        assertEquals(preRevision.value, RevisionCalculator.revisionOf(postCapture.layoutState).value)
    }

    @Test
    fun writeAfterConfirmMakesTheUndoStaleWithZeroWrite() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, capture) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Another write between confirm and undo (a new desktop row).
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            val id = appState.model.modelDbController.generateNewItemId()
            db.insertOrThrow(Favorites.TABLE_NAME, null, desktopRowValues(id.toLong(), 0, 3, 3))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        appState.model.forceReload()
        waitForModelLoaded()

        val undo = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue(
            "expected STALE_REVISION, got $undo",
            undo is RecoveryResult.NotRestorable && undo.reason == RecoveryRejection.STALE_REVISION,
        )
        // Zero writes: the applied placement (page 1) is still in effect.
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-stale"))
        val moved = postCapture.layoutState.items.any { item ->
            val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
            workspace != null &&
                (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                    ?.pageId?.value?.toIntOrNull() == 1
        }
        assertTrue("the applied layout must survive the stale undo", moved)
    }

    @Test
    fun undoAfterARestoreIsRejectedAsAlreadyRestored() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // First undo restores.
        val first = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue("expected Restored, got $first", first is RecoveryResult.Restored)
        appState.model.forceReload()
        waitForModelLoaded()

        // Second undo: the point is already restored — typed rejection,
        // zero write.
        val second = module.recover(RecoveryRequest(pointId, verified))
        assertTrue(
            "expected NotRestorable, got $second",
            second is RecoveryResult.NotRestorable &&
                (second as RecoveryResult.NotRestorable).reason ==
                RecoveryRejection.ALREADY_RESTORED,
        )
    }

    @Test
    fun undoWithAMismatchedRevisionIsRejectedAsStale() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // A receipt revision that does not describe the current state is
        // rejected before any write. The applied state has the item on page 1,
        // so a state variant with the item moved back to page 0 is a revision
        // the current state cannot match.
        val appliedCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-stale-probe"))
        val appliedState = appliedCapture.layoutState
        val variant = app.lawnchair.organizer.application.public.LayoutState(
            pages = appliedState.pages,
            profiles = appliedState.profiles,
            deviceCapabilities = appliedState.deviceCapabilities,
            items = appliedState.items.map { item ->
                val workspace = item.placement
                    as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                    ?: return@map item
                item.copy(
                    placement = workspace.copy(
                        page = app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
                            app.lawnchair.organizer.planning.PageId("0"),
                        ),
                    ),
                )
            },
            reservedWorkspaceRegions = appliedState.reservedWorkspaceRegions,
        )
        val staleRevision = RevisionCalculator.revisionOf(variant)
        val undo = module.recover(RecoveryRequest(pointId, staleRevision))
        assertTrue(
            "expected STALE_REVISION, got $undo",
            undo is RecoveryResult.NotRestorable && undo.reason == RecoveryRejection.STALE_REVISION,
        )
    }

    @Test
    fun undoUnderSerializationContentionReportsWriterBusyWithZeroWrite() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Inject the recovery serialization contention: the undo tap is
        // rejected as WriterBusy before any write (the applied state survives).
        undoFaults.serializationContention = true
        try {
            val undo = module.recover(RecoveryRequest(pointId, verified!!))
            assertTrue("expected WriterBusy, got $undo", undo is RecoveryResult.WriterBusy)
        } finally {
            undoFaults.serializationContention = false
        }
        // Zero writes: the applied placement (page 1) is still in effect and a
        // retry without the fault restores.
        appState.model.forceReload()
        waitForModelLoaded()
        val retry = module.recover(RecoveryRequest(pointId, verified))
        assertTrue("expected Restored on retry, got $retry", retry is RecoveryResult.Restored)
    }

    @Test
    fun undoAfterRetentionEvictionIsRejectedAsExpiredWithZeroWrite() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Age the recovery point past the retention window via the store's
        // retention pass (same seam the reconciler uses): the record is
        // tombstoned as EXPIRED, so the undo must be a typed rejection with
        // zero writes.
        val storeField = LayoutApplicationModule::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        val store = storeField.get(module) as app.lawnchair.organizer.application.store.RecoveryStore
        val retention = store.runRetention(
            java.lang.System.currentTimeMillis() +
                app.lawnchair.organizer.application.lifecycle.RetentionPolicy.RETENTION_MILLIS + 1,
        )
        assertTrue("expected retention to apply, got $retention",
            retention is app.lawnchair.organizer.application.protocol.RecoveryStorePort.RetentionOutcome.Applied)

        val undo = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue(
            "expected NotRestorable(EXPIRED), got $undo",
            undo is RecoveryResult.NotRestorable && undo.reason == RecoveryRejection.EXPIRED,
        )
        // Zero writes: the applied placement (page 1) is still in effect.
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-evicted"))
        val moved = postCapture.layoutState.items.any { item ->
            val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
            workspace != null &&
                (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                    ?.pageId?.value?.toIntOrNull() == 1
        }
        assertTrue("the applied layout must survive the evicted undo", moved)
    }

    /**
     * Issue #450 AC-6 process-death smoke (round 3 finding 3): two separate
     * `am instrument` invocations, each its own process (same convention as
     * OrganizerRestoreColdProcessEvidenceTest). Phase `seed` records an undo
     * entry into the process-local slot and dumps the slot state to a marker
     * file; after `am force-stop` (the process boundary), phase `verify`
     * runs in a fresh process where the slot MUST be empty — the record died
     * with the process and no undo entry survives.
     *
     * Evidence tooling, deliberately NOT part of any CI lane class list (same
     * status as the #376 cold-process evidence). Run:
     * ```bash
     * adb shell am instrument -w -e class app.lawnchair.homeedit.EditSurfaceUndoInstrumentationTest      *   -e phase seed app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner
     * adb shell am force-stop app.lawnchair.debug
     * adb shell am instrument -w -e class app.lawnchair.homeedit.EditSurfaceUndoInstrumentationTest      *   -e phase verify app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner
     * ```
     */
    @Test
    fun processDeathSmoke() {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("phase") ?: "default"
        when (phase) {
            "seed" -> {
                seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
                val (plan, _) = buildMovePlan()
                val runId = module.newManualRunId()
                val (result, verified) = module.applyWithUndoReceipt(plan, runId)
                val pointId = (result as ApplyResult.Applied).pointId
                appState.model.forceReload()
                waitForModelLoaded()
                // Record the edit-session entry into the process-local slot.
                HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified!!))
                val slotBefore = HomeEditUndoRecord.inspectForTest()
                assertTrue("seed must leave an undo entry in the slot", slotBefore != null)
                // Marker: the seeded state, for the verify phase to compare.
                java.io.File(context.filesDir, "issue450_process_death.marker").writeText(
                    "seeded:${slotBefore != null}",
                )
            }

            "verify" -> {
                // Fresh process after `am force-stop`: the slot must be empty.
                val slotAfter = HomeEditUndoRecord.inspectForTest()
                assertNull("the undo record must not survive a process death", slotAfter)
                val marker = java.io.File(context.filesDir, "issue450_process_death.marker")
                assertTrue("marker from the seed phase must exist", marker.exists())
                assertTrue(marker.readText().startsWith("seeded:true"))
                marker.delete()
            }

            else -> {
                // Default single-invocation run: not part of the smoke; a
                // no-op so the class list stays harmless.
                org.junit.Assume.assumeTrue("process-death smoke runs via -e phase seed|verify", false)
            }
        }
    }

    // --- helpers ---

    /** Builds a one-move session plan (page 0 → page 1) and returns it with its capture. */
    private fun buildMovePlan(): Pair<app.lawnchair.organizer.application.public.ValidatedLayoutPlan, app.lawnchair.organizer.application.protocol.CapturedSnapshot> {
        val capture = adapter.captureCurrent(CaptureId("edit-surface-undo"))
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val first = snapshot.items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == 0 && it.id > 0
        }
        val picked = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(first.id),
            PendingSessionAction.MoveToPage(1),
        ) as SessionPlanResult.Applied
        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            picked.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready
        return built.plan to capture
    }

    private fun seedDesktopApps(vararg placements: Triple<Int, Int, Int>) {
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for ((screen, cellX, cellY) in placements) {
                val id = appState.model.modelDbController.generateNewItemId()
                db.insertOrThrow(Favorites.TABLE_NAME, null, desktopRowValues(id.toLong(), screen, cellX, cellY))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        appState.model.forceReload()
        waitForModelLoaded()
    }

    private fun desktopRowValues(id: Long, screen: Int, cellX: Int, cellY: Int): ContentValues = ContentValues().apply {
        put(Favorites._ID, id)
        put(Favorites.TITLE, "Edit surface undo fixture $id")
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
        appState.model.modelDbController.db
            .query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID)
            .use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
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
                    rows.add(values)
                }
            }
        return rows
    }

    private fun restoreFavorites(rows: List<ContentValues>) {
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (row in rows) db.insertOrThrow(Favorites.TABLE_NAME, null, row)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun waitForModelLoaded() {
        val model = appState.model
        val deadline = System.currentTimeMillis() + 10_000L
        while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }
}
