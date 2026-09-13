package app.lawnchair.organizer.locks

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.integration.CaptureFailureCategory
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.ProductionManualOrganizationApplication
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.EncryptionType
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.IntSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #287 AC-4: the issue reproduction as an executable same-process
 * regression through the production seams — organizer apply, recovery,
 * a real in-process grid change (grid preferences + IDP recompute driving the
 * LoaderTask's tryMigrateDB → GridSizeMigrationUtil.migrateGridInTransaction),
 * the composer's CAPTURE_UNKNOWN_LOCK fail-closed, the placement lock review
 * resolving every UNKNOWN row (folder members beyond the new grid's one-page
 * folder capacity included), and a fresh-capture preview on the new profile.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GridChangeUnknownLockRecoveryInstrumentationTest {

    private lateinit var context: Context
    private lateinit var launcher: LauncherAppState
    private lateinit var prefs: PreferenceManager
    private var snapshotRows: List<ContentValues> = emptyList()
    private var originalGrid: DeviceProfileOverrides.DBGridInfo? = null
    private var reloadLatch: CountDownLatch? = null
    private val intent = UserReviewedIntent("instrumentation_confirm")
    private val modelCallbacks = object : BgDataModel.Callbacks {
        override fun finishBindingItems(pagesBoundFirst: IntSet) {
            reloadLatch?.countDown()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        prefs = PreferenceManager.getInstance(context)
        originalGrid = DeviceProfileOverrides.INSTANCE.get(context).getGridInfo()
        // Hermetic in-process migration: the LoaderTask only performs the real
        // GridSizeMigrationUtil path when the target db neither exists nor
        // carries the EMPTY_DATABASE_CREATED flag (either makes
        // ModelDbController.migrateGridIfNeeded skip the migration). The target
        // grid db is not open at this point, so its artifacts can go.
        removeTargetDbArtifacts()
        snapshotRows = snapshotFavorites()
        val db = launcher.model.modelDbController.db
        db.delete(Favorites.TABLE_NAME, null, null)
        launcher.model.modelDbController.clearEmptyDbFlag()
        seedFolderWithMembers(folderCellY = 2, memberCount = 40)
        reloadAndWait()
    }

    @After
    fun tearDown() {
        try {
            // Restore the fixture rows on the current grid db, then restore the
            // grid preferences without another reload: a back-migration here
            // would re-enter the migration journal path this suite does not
            // own. The next app start re-derives the active db from prefs.
            restoreFavorites(snapshotRows)
            reloadAndWait()
            originalGrid?.let { grid ->
                prefs.hotseatColumns.set(grid.numHotseatColumns)
                prefs.workspaceColumns.set(grid.numColumns)
                prefs.workspaceRows.set(grid.numRows)
                LauncherAppState.getIDP(context).onPreferencesChanged(context)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                launcher.model.removeCallbacks(modelCallbacks)
            }
        }
    }

    @Test
    fun organizerRecoversThroughReviewAfterInProcessGridChange() {
        val adapter = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val before = adapter.captureCurrent(CaptureId("issue287-before"))
        assertTrue("fixture folder with members must be captured", before.layoutState.items.size > 40)

        val module = LayoutApplicationModule.production(context, GeneratedFolderTitles.resolver(context), launcher)
        // Same startup seam as the settings surface: the readiness gate stays
        // ReconciliationPending until startup reconciliation completes.
        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        val run1 = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))
        run1.startPlain()
        assertTrue(
            "run 1 must reach preview: ${run1.state}",
            run1.state is ManualOrganizationRun.State.Preview,
        )
        run1.confirm()
        val applied = run1.state as? ManualOrganizationRun.State.Applied
            ?: error("run 1 did not reach applied: ${run1.state}")
        assertTrue(
            "apply must succeed: ${applied.result}",
            applied.result is ApplyResult.Applied,
        )
        run1.beginRecoveryPreview()
        val confirmation = run1.state as? ManualOrganizationRun.State.RecoveryPreview
            ?: error("recovery preview not reached: ${run1.state}")
        assertTrue(confirmation.result is RecoveryPreviewResult.Restorable)
        run1.confirmRecovery()
        val recovery = run1.state as? ManualOrganizationRun.State.RecoveryResultState
            ?: error("recovery result not reached: ${run1.state}")
        assertTrue("recovery must restore: ${recovery.result}", recovery.result is RecoveryResult.Restored)
        assertEquals(
            "recovery must return the exact pre-apply state",
            before.layoutState,
            adapter.captureCurrent(CaptureId("issue287-after-recovery")).layoutState,
        )

        // Real in-process grid change — the issue's trigger. Production path:
        // grid preferences + IDP recompute (same as the settings screen's
        // apply), then the model reload's LoaderTask → tryMigrateDB →
        // GridSizeMigrationUtil.migrateGridInTransaction. The target grid is
        // 5x5 (preset `5_by_5`): the DB file name must change, the migration
        // must run, and every row must come back UNKNOWN.
        val idpBefore = LauncherAppState.getIDP(context).dbFile
        setGrid(rows = 5, columns = 5, hotseat = 5)
        val idpAfter = LauncherAppState.getIDP(context).dbFile
        assertNotEquals("the grid change must switch the launcher db file", idpBefore, idpAfter)
        val after = adapter.captureCurrent(CaptureId("issue287-after-grid-change"))
        assertNotEquals(
            "the grid change must produce a new layout generation (revision)",
            before.revision,
            after.revision,
        )
        val capacity = after.layoutState.deviceCapabilities.let {
            it.folderMaxColumns * it.folderMaxRows
        }
        assertTrue(
            "persisted member ranks must reach beyond the new one-page folder capacity (got $capacity)",
            capacity <= 39,
        )
        val unknownAfterMigration = after.layoutState.items.count { it.lockState == OrganizerLockState.UNKNOWN }
        assertEquals(
            "the grid migration must mark every captured row UNKNOWN",
            after.layoutState.items.size,
            unknownAfterMigration,
        )
        // Grid migration assigns fresh row ids to copied rows (ADR-0004), so
        // post-migration members are identified by placement, not by the
        // seeded row ids.
        val beyondCapacity = after.layoutState.items.filter { item ->
            val placement = item.placement as? PlacementState.FolderChild
            placement != null && placement.rank >= capacity
        }
        assertTrue(
            "seeded members beyond the new one-page capacity must exist (capacity=$capacity)",
            beyondCapacity.isNotEmpty(),
        )

        // The composer must fail closed with the exact issue reason.
        val run2 = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))
        run2.startPlain()
        val unavailable = run2.state as? ManualOrganizationRun.State.InputUnavailable
            ?: error("run 2 must end in input-unavailable: ${run2.state}")
        assertEquals(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.UNKNOWN_LOCK),
            unavailable.reason,
        )

        // The designed recovery path: resolve every UNKNOWN row, folder
        // members beyond the new one-page capacity included.
        val lockModule = OrganizerLocks.get(context)
        val listing = lockModule.reviewListing()
        assertTrue("review listing must surface the unknown rows", listing.entries.isNotEmpty())
        val listingIds = listing.entries.map { it.item.value }.toSet()
        val beyondCapacityIds = beyondCapacity.mapNotNull {
            (it.ref as? ApplicationItemRef.PersistentItem)?.itemId?.value
        }
        assertTrue(
            "the review listing must include the capacity-exceeding folder members " +
                "(beyond=$beyondCapacityIds, listing=$listingIds)",
            beyondCapacityIds.all { it in listingIds },
        )
        val resolved = lockModule.reviewBatch(
            LockBatchReviewRequest(listing.entries.map { it.item }, LockTargetState.UNLOCKED, intent),
        )
        assertTrue("batch review must commit: $resolved", resolved is LockChangeResult.Changed)
        assertEquals(
            (resolved as LockChangeResult.Changed).writes.size,
            listing.entries.size,
        )
        assertTrue("review listing must be empty after resolution", lockModule.reviewListing().entries.isEmpty())
        assertTrue(
            "recapture must be free of UNKNOWN rows",
            adapter.captureCurrent(CaptureId("issue287-after-review"))
                .layoutState.items.none { it.lockState == OrganizerLockState.UNKNOWN },
        )

        // Same process, fresh capture, new profile: the organizer must reach
        // the preview again.
        val run3 = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))
        run3.startPlain()
        assertTrue(
            "run 3 must reach preview after review: ${run3.state}",
            run3.state is ManualOrganizationRun.State.Preview,
        )
    }

    private fun setGrid(rows: Int, columns: Int, hotseat: Int) {
        prefs.hotseatColumns.set(hotseat)
        prefs.workspaceColumns.set(columns)
        prefs.workspaceRows.set(rows)
        // Same production trigger as the settings screen's apply button
        // (HomeScreenGridPreferences): recompute the IDP so the next model
        // load migrates to launcher_<rows>_<cols>_<hotseat>.db.
        LauncherAppState.getIDP(context).onPreferencesChanged(context)
        reloadAndWait()
    }

    private fun removeTargetDbArtifacts() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            context.getDatabasePath(TARGET_DB + suffix).delete()
        }
        LauncherPrefs.get(context).removeSync(
            LauncherPrefs.backedUpItem(EMPTY_DB_FLAG_KEY, false, EncryptionType.ENCRYPTED),
        )
    }

    private var memberIds: List<Long> = emptyList()

    private fun seedFolderWithMembers(folderCellY: Int, memberCount: Int) {
        val db = launcher.model.modelDbController.db
        val profile = UserCache.INSTANCE.get(context).getSerialNumberForUser(android.os.Process.myUserHandle())
        val folderId = insertRow(
            type = Favorites.ITEM_TYPE_FOLDER,
            container = Favorites.CONTAINER_DESKTOP,
            screen = 0,
            cellX = 0,
            cellY = folderCellY,
            title = "Issue 287 Folder",
            profile = profile,
        )
        memberIds = (0 until memberCount).map { rank ->
            insertRow(
                type = Favorites.ITEM_TYPE_APPLICATION,
                container = folderId.toInt(),
                rank = rank,
                title = "Member $rank",
                profile = profile,
            )
        }
    }

    private fun insertRow(
        type: Int,
        container: Int,
        title: String?,
        profile: Long,
        screen: Long? = null,
        cellX: Int = 0,
        cellY: Int = 0,
        rank: Int = 0,
    ): Long {
        val id = launcher.model.modelDbController.generateNewItemId()
        val intent = if (type == Favorites.ITEM_TYPE_APPLICATION) {
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
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, type)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, profile)
                put(Favorites.RANK, rank)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
            },
        )
        return id.toLong()
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        launcher.model.modelDbController.db.query(
            Favorites.TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            Favorites._ID,
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

    /**
     * Issue #228: plain full-organization flow — pass through the selection
     * surface with an empty selection (same seam as the pre-#228 flow and the
     * ManualOrganizationProductionE2EInstrumentationTest harness).
     */
    private fun ManualOrganizationRun.startPlain() {
        start()
        (state as? ManualOrganizationRun.State.Selecting)?.let { confirmSelection(emptySet()) }
    }

    private fun reloadAndWait() {
        val latch = CountDownLatch(1)
        reloadLatch = latch
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.addCallbacks(modelCallbacks)
            launcher.model.forceReload()
        }
        check(latch.await(60, TimeUnit.SECONDS)) {
            "Launcher model reload did not finish for Issue #287"
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!launcher.model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not load for Issue #287" }
    }

    private companion object {
        const val TARGET_DB = "launcher_5_5_5.db"
        const val EMPTY_DB_FLAG_KEY = "EMPTY_DATABASE_CREATED@$TARGET_DB"
    }
}
