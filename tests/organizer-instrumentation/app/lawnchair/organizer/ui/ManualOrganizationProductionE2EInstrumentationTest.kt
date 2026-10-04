package app.lawnchair.organizer.ui

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.adapter.RowManifestCodec
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.Planned
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.pm.UserCache
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.util.IntSet
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Issue #52 MFO-AC-01/05/09 evidence through the complete production seam.
 *
 * The fixture only seeds canonical Launcher rows and an app-private category
 * override. The run itself uses the production writer, policy composer,
 * planner, materializer, checkpoint/apply protocol, reload/verification, and
 * opaque recovery-preview handoff.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ManualOrganizationProductionE2EInstrumentationTest {
    private lateinit var context: Context
    private lateinit var launcher: LauncherAppState
    private lateinit var overridePreferences: android.content.SharedPreferences
    private lateinit var preferenceManager: PreferenceManager2
    private var originalSmartspaceEnabled: Boolean = false
    private var originalRows: List<ContentValues> = emptyList()
    private var originalOverrides: Map<String, *> = emptyMap<String, Any?>()
    private var reloadLatch: CountDownLatch? = null
    private val modelCallbacks = object : BgDataModel.Callbacks {
        override fun finishBindingItems(pagesBoundFirst: IntSet) {
            reloadLatch?.countDown()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        preferenceManager = PreferenceManager2.getInstance(context)
        originalSmartspaceEnabled = preferenceManager.enableSmartspace.firstBlocking()
        originalRows = snapshotFavorites()
        overridePreferences = context.getSharedPreferences(OVERRIDE_STORE, Context.MODE_PRIVATE)
        originalOverrides = overridePreferences.all
        clearRecoveryStoreArtifacts()

        val serial = UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle())
        check(
            overridePreferences.edit()
                .putInt("schema", 1)
                .putLong("generation", 1L)
                .putString("entries", "${context.packageName}|$serial|TOOLS")
                .commit(),
        ) { "Unable to install deterministic test classification override" }

        val db = launcher.model.modelDbController.db
        db.delete(Favorites.TABLE_NAME, null, null)
        // Enable QSB only after preserving and clearing the original layout.
        // This prevents the Loader from sanitizing a user's pre-test QSB-off
        // first-screen row while the test preference is being changed.
        preferenceManager.enableSmartspace.setBlocking(true)
        check(FeatureFlags.topQsbOnFirstScreenEnabled(context)) {
            "Issue #155 E2E requires QSB on the first workspace screen"
        }
        // These movable rows make page 0 the planner's preferred target. The
        // old allocator selected (0,0); a reservation-aware plan must instead
        // select a non-overlapping cell that survives the real A7 reload.
        insertFixtureRow(db, 0, 1, "Issue155 E2E A")
        insertFixtureRow(db, 0, 2, "Issue155 E2E B")
        launcher.model.modelDbController.clearEmptyDbFlag()
        // Drive the real LauncherModel loader directly. This is the same
        // production callback/reload seam used by the existing instrumentation
        // tests, without starting an Activity that also launches startup
        // reconciliation concurrently with this fixture.
        reloadAndWait()
    }

    @After
    fun tearDown() {
        try {
            // Restore the original QSB semantics before making original rows
            // visible again. Otherwise the test's QSB-on Loader could delete
            // a pre-test QSB-off item at the reserved first-screen cells.
            if (::preferenceManager.isInitialized) {
                preferenceManager.enableSmartspace.setBlocking(originalSmartspaceEnabled)
            }
            restoreFavorites(originalRows)
            reloadAndWait()
            if (::overridePreferences.isInitialized) {
                overridePreferences.edit().clear().apply()
                originalOverrides.forEach { (key, value) ->
                    val editor = overridePreferences.edit()
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                    editor.apply()
                }
            }
        } finally {
            if (::preferenceManager.isInitialized) {
                preferenceManager.enableSmartspace.setBlocking(originalSmartspaceEnabled)
            }
            if (::launcher.isInitialized) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    launcher.model.removeCallbacks(modelCallbacks)
                }
            }
            clearRecoveryStoreArtifacts()
        }
    }

    @Test
    fun manualRunUsesProductionCaptureApplyVerificationAndRecovery() {
        val before = LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue52-before"))
        assertEquals(2, before.layoutState.items.size)
        val qsbReservation = before.layoutState.reservedWorkspaceRegions.single()

        val module = LayoutApplicationModule.production(context, GeneratedFolderTitles.resolver(context), launcher)
        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        val run = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))

        run.start()
        val preview = run.state as? ManualOrganizationRun.State.Preview
            ?: error("Production manual run did not reach preview: ${run.state}")
        assertEquals(1, preview.summary.newFolderCount)
        assertEquals(2, preview.summary.movedCount)
        assertEquals(before.layoutState, LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue52-preview"))
            .layoutState)

        assertEquals(
            "Production DB changed between preview and confirmation",
            before.layoutState,
            LauncherLayoutAdapter(
                context,
                launcher.model.modelDbController,
                launcher.model,
            ).captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue52-before-confirm"))
                .layoutState,
        )
        run.confirm()
        val applied = run.state as? ManualOrganizationRun.State.Applied
            ?: error("Production manual run did not reach applied result: ${run.state}")
        val applyResult = applied.result as? ApplyResult.Applied
            ?: error("Production manual run returned non-success result: ${applied.result}")
        val afterApply = LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue52-after-apply"))
        assertEquals(3, afterApply.layoutState.items.size)
        assertEquals(listOf(qsbReservation), afterApply.layoutState.reservedWorkspaceRegions)
        assertTrue(
            "A7 reload must retain every planned workspace item outside the QSB reservation",
            afterApply.layoutState.items
                .mapNotNull { it.placement as? PlacementState.Workspace }
                .none { workspace ->
                    workspace.page == app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
                        qsbReservation.page.pageId,
                    ) && rectanglesOverlap(workspace, qsbReservation)
                },
        )
        val beforeIds = before.layoutState.items.mapNotNull { it.ref.itemId() }.toSet()
        val insertedFolder = afterApply.layoutState.items.single { it.ref.itemId() !in beforeIds }
        val folderPlacement = insertedFolder.placement as? PlacementState.Workspace
            ?: error("Planner folder was not materialized on the workspace")
        assertEquals(
            app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(qsbReservation.page.pageId),
            folderPlacement.page,
        )
        assertTrue(
            "The page-0 folder must be relocated away from the historical (0,0) QSB collision",
            folderPlacement.cell.x >= qsbReservation.span.width || folderPlacement.cell.y >= qsbReservation.span.height,
        )

        run.beginRecoveryPreview()
        assertTrue(run.state is ManualOrganizationRun.State.RecoveryPreview)
        assertTrue((run.state as ManualOrganizationRun.State.RecoveryPreview).result is RecoveryPreviewResult.Restorable)

        run.confirmRecovery()
        val recovery = run.state as? ManualOrganizationRun.State.RecoveryResultState
            ?: error("Production recovery did not reach terminal result: ${run.state}")
        assertEquals(RecoveryResult.Restored(applyResult.pointId), recovery.result)
        assertEquals(before.layoutState, LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue52-after-recovery"))
            .layoutState)
    }

    @Test
    fun reservationlessLegacyTargetIsDeletedByProductionLoaderAndMismatchesManifest() {
        val adapter = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val source = adapter.captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue155-red-source"))
        val reservation = source.layoutState.reservedWorkspaceRegions.single()
        val module = LayoutApplicationModule.production(context, GeneratedFolderTitles.resolver(context), launcher)
        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        val input = (module.composeManualFullOrganizationInput(context) as? OrganizationInputComposition.Ready)?.input
            ?: error("Production manual input was unavailable")
        val unreserved = input.copy(
            snapshot = input.snapshot.copy(reservedWorkspaceRegions = emptyList()),
        )
        val legacyPlan = DeterministicOrganizationPlanner().plan(unreserved).outcome as? Planned
            ?: error("Reservationless legacy fixture did not plan")
        val legacyFolder = legacyPlan.newFolders.single()
        assertEquals(reservation.page, legacyFolder.workspacePlacement.page)
        assertEquals(GridCell(0, 0), legacyFolder.workspacePlacement.cell)

        // The current adapter intentionally rejects a reservation-overlapping
        // target. Insert only the legacy target row here to exercise the real
        // correlated Loader cleanup that formerly followed A6.
        insertFixtureRow(launcher.model.modelDbController.db, 0, 0, "Issue155 legacy target")
        val legacyIntended = RowManifestCodec.capture(
            launcher.model.modelDbController.db,
            source.layoutState.deviceCapabilities,
            source.layoutState.pages.map { page ->
                (page.ref as? ApplicationPageRef.PersistentPage)?.pageId
                    ?: error("Legacy fixture source page was not persistent")
            },
            source.layoutState.profiles,
            emptyList(),
        ).manifest
        assertEquals(3, legacyIntended.rowCount)

        reloadAndWait()

        val after = LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).recaptureDb().manifest
        assertEquals(2, after.rowCount)
        assertNotEquals(
            "Loader deletion of the legacy reserved-cell target must break A7 manifest equality",
            legacyIntended,
            after,
        )
        assertEquals(source.manifest, after)
    }

    @Test
    fun qsbDisabledRowlessFirstScreenCaptureAndPlanningRemainNoChanges() {
        preferenceManager.enableSmartspace.setBlocking(false)
        check(!FeatureFlags.topQsbOnFirstScreenEnabled(context)) {
            "Issue #155 disabled control requires QSB to be disabled"
        }
        launcher.model.modelDbController.db.delete(Favorites.TABLE_NAME, null, null)
        launcher.model.modelDbController.clearEmptyDbFlag()
        reloadAndWait()

        val capture = LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        ).captureCurrent(app.lawnchair.organizer.application.protocol.CaptureId("issue155-qsb-off-rowless"))
        assertEquals(1, capture.layoutState.pages.size)
        assertTrue(capture.layoutState.items.isEmpty())
        assertTrue(capture.layoutState.reservedWorkspaceRegions.isEmpty())

        val module = LayoutApplicationModule.production(context, GeneratedFolderTitles.resolver(context), launcher)
        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        val run = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))
        run.start()
        assertEquals(ManualOrganizationRun.State.NoChanges, run.state)
        assertTrue(snapshotFavorites().isEmpty())
    }

    @Test
    fun manualActionWaitsForStartupReconciliationBeforeAcceptingCapture() {
        val module = LayoutApplicationModule.production(context, GeneratedFolderTitles.resolver(context), launcher)
        val run = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))

        run.start()

        val blocked = run.state as? ManualOrganizationRun.State.InputUnavailable
            ?: error("Manual action was accepted before startup reconciliation: ${run.state}")
        assertEquals(InputReadinessReason.ReconciliationPending, blocked.reason)
        assertEquals(2, snapshotFavorites().size)

        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        run.start()
        assertTrue(run.state is ManualOrganizationRun.State.Preview)
    }

    @Test
    fun staleProductionConfirmationDoesNotWrite() {
        val module = LayoutApplicationModule.production(context, GeneratedFolderTitles.resolver(context), launcher)
        assertEquals(
            app.lawnchair.organizer.application.protocol.RestartReconciler.ReconciliationSummary.Clean,
            module.reconcileAtStart(),
        )
        val run = ManualOrganizationRun(ProductionManualOrganizationApplication(context, module))

        run.start()
        assertTrue(run.state is ManualOrganizationRun.State.Preview)

        val beforeMutation = snapshotFavorites()
        val rowId = requireNotNull(beforeMutation.first().getAsLong(Favorites._ID))
        val updatedRows = launcher.model.modelDbController.db.update(
            Favorites.TABLE_NAME,
            ContentValues().apply { put(Favorites.MODIFIED, 9_999_999L) },
            "${Favorites._ID}=?",
            arrayOf(rowId.toString()),
        )
        assertEquals(1, updatedRows)
        val expectedAfterMutation = snapshotFavorites()

        run.confirm()

        assertEquals(ManualOrganizationRun.State.Stale, run.state)
        assertEquals(
            "Stale confirmation must not apply a second Launcher write",
            expectedAfterMutation,
            snapshotFavorites(),
        )
    }

    private fun rectanglesOverlap(
        workspace: PlacementState.Workspace,
        reservation: app.lawnchair.organizer.planning.ReservedWorkspaceRegion,
    ): Boolean = workspace.cell.x.toLong() < reservation.cell.x.toLong() + reservation.span.width.toLong() &&
        reservation.cell.x.toLong() < workspace.cell.x.toLong() + workspace.span.width.toLong() &&
        workspace.cell.y.toLong() < reservation.cell.y.toLong() + reservation.span.height.toLong() &&
        reservation.cell.y.toLong() < workspace.cell.y.toLong() + workspace.span.height.toLong()

    private fun insertFixtureRow(
        db: android.database.sqlite.SQLiteDatabase,
        screen: Int,
        cellY: Int,
        title: String,
    ) {
        val id = launcher.model.modelDbController.generateNewItemId()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, title)
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L + screen)
                put(Favorites.RESTORED, 0)
                put(
                    Favorites.PROFILE_ID,
                    UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
                )
                put(Favorites.RANK, screen)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
            },
        )
    }

    private fun clearRecoveryStoreArtifacts() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val snapshotDirectory = File(
            context.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        )
        snapshotDirectory.listFiles()?.forEach { check(it.delete()) { "Unable to delete ${it.name}" } }
        if (snapshotDirectory.exists()) check(snapshotDirectory.delete()) { "Unable to delete $snapshotDirectory" }
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

    private fun waitForModel() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!launcher.model.isModelLoaded && System.nanoTime() < deadline) {
            Thread.sleep(25L)
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not load for Issue #52 E2E" }
    }

    private fun reloadAndWait() {
        val latch = CountDownLatch(1)
        reloadLatch = latch
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.addCallbacks(modelCallbacks)
            launcher.model.forceReload()
        }
        check(latch.await(30, TimeUnit.SECONDS)) {
            "Launcher model reload did not finish for Issue #52 E2E"
        }
        waitForModel()
    }

    private fun app.lawnchair.organizer.application.public.ApplicationItemRef.itemId(): ItemId? =
        (this as? app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem)?.itemId

    private companion object {
        const val OVERRIDE_STORE = "organizer_category_overrides"
    }
}
