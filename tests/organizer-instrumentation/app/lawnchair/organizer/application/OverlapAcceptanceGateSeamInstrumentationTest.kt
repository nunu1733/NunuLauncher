package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.adapter.overlapAcceptanceHolds
import app.lawnchair.organizer.application.protocol.ApplyTxOutcome
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.protocol.WriterKind
import app.lawnchair.organizer.application.protocol.WriteSetPreparation
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #185 / ADR-0010 seam coverage for the A5 overlap-acceptance gate
 * (independent-audit F1/F2). Kept in its own class so the preference flips
 * below (which trigger launcher reloads that briefly hold the shared writer
 * lease) cannot disturb the other real-adapter suites' ordering.
 */
@RunWith(AndroidJUnit4::class)
class OverlapAcceptanceGateSeamInstrumentationTest {
    private val runId = RunId("33333333333333333333333333333333")
    private val pointId = RecoveryPointId("44444444444444444444444444444444")
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

    /**
     * Issue #185 / ADR-0010 seam (audit F2): a plan whose intended state
     * introduces an authoritative-reservation overlap is rejected at A5 with
     * the typed [PreWriteRejection.OVERLAP_POLICY_REJECTED] and no write when
     * the current platform overlap policy would not accept it. The exact
     * precondition and stale paths are unchanged.
     */
    private fun snapshotFavorites(): List<ContentValues> {
        val db = launcher.model.modelDbController.db
        val rows = mutableListOf<ContentValues>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
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
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (row in snapshot) db.insertOrThrow(Favorites.TABLE_NAME, null, row)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private class FixedIds(
        private val runId: RunId,
        private val pointId: RecoveryPointId,
    ) : app.lawnchair.organizer.application.protocol.OperationIdSource {
        override fun newRunId(): RunId = runId
        override fun newPointId(): RecoveryPointId = pointId
    }

    @Test
    fun applyGateRejectsIntendedReservationOverlapWhenPolicyIntolerant() {
        val prefs = PreferenceManager2.getInstance(context)
        val originalSmartspace = prefs.enableSmartspace.firstBlocking()
        prefs.enableSmartspace.setBlocking(true)
        try {
            launcher.model.modelDbController.db.delete(Favorites.TABLE_NAME, null, null)
            val id = launcher.model.modelDbController.generateNewItemId()
            launcher.model.modelDbController.db.insertOrThrow(
                Favorites.TABLE_NAME,
                null,
                desktopRowValues(context, id.toLong(), cellX = 2, cellY = 1),
            )
            launcher.model.forceReload()
            waitForModelLoaded()

            val writer = LauncherLayoutAdapter(
                context,
                launcher.model.modelDbController,
                launcher.model,
                overlapToleranceSource = { false },
            )
            val capture = writer.captureCurrent(CaptureId("overlap-gate-capture"))
            org.junit.Assume.assumeTrue(capture.layoutState.reservedWorkspaceRegions.isNotEmpty())
            val sourceItem = capture.layoutState.items.single()
            val reservation = capture.layoutState.reservedWorkspaceRegions.first()
            val intendedItem = sourceItem.copy(
                placement = PlacementState.Workspace(
                    (sourceItem.placement as PlacementState.Workspace).page,
                    GridCell(reservation.cell.x, reservation.cell.y),
                    GridSpan(1, 1),
                ),
            )
            val intendedState = capture.layoutState.copy(
                items = capture.layoutState.items.map { if (it.ref == sourceItem.ref) intendedItem else it },
            )
            val plan = ValidatedLayoutPlan(
                capture.revision,
                capture.layoutState,
                intendedState,
                capture.layoutState.items.map {
                    if (it.ref == sourceItem.ref) ApplyAction.Update(it.ref, it, intendedItem) else ApplyAction.Preserve(it.ref, it)
                },
                emptyList(),
                emptyList(),
                RuleVersion("overlap-gate"),
                TaxonomyVersion("overlap-gate"),
            )
            val clock = SystemClock()
            val module = LayoutApplicationModule(
                writer,
                RecoveryStore(context, clock::nowMillis),
                clock,
                FixedIds(runId, pointId),
                folderTitleResolver = GeneratedFolderTitles.resolver(context),
            )
            module.reconcileAtStart()

            val result = module.apply(plan)

            assertEquals(ApplyResult.Rejected(runId, PreWriteRejection.OVERLAP_POLICY_REJECTED), result)
            // No write happened: the favorites rows still match the capture.
            assertEquals(capture.manifest.rows, writer.recaptureDb().manifest.rows)
        } finally {
            prefs.enableSmartspace.setBlocking(originalSmartspace)
        }
    }

    /**
     * Issue #185 / ADR-0010 seam (audit F1/F2): a recovery write set whose
     * target contains a reservation-overlapping row is rejected before any
     * write when the current policy would not accept it — the deleted row is
     * not resurrected into a state the loader would delete again.
     */
    @Test
    fun recoveryGateRejectsRestoringRowTheLoaderDeletedWhenPolicyIntolerant() {
        val prefs = PreferenceManager2.getInstance(context)
        val originalSmartspace = prefs.enableSmartspace.firstBlocking()
        // Audit C2: pin both platform policies explicitly. The fixture row sits
        // inside the QSB reservation, so the loader would delete it during the
        // first reload on a fresh emulator whose ambient allowWidgetOverlap is
        // the default false — pin tolerance for the fixture, then evaluate the
        // gate under the intolerant policy.
        val originalTolerance = prefs.allowWidgetOverlap.firstBlocking()
        prefs.enableSmartspace.setBlocking(true)
        prefs.allowWidgetOverlap.setBlocking(true)
        try {
            launcher.model.modelDbController.db.delete(Favorites.TABLE_NAME, null, null)
            val id = launcher.model.modelDbController.generateNewItemId()
            launcher.model.modelDbController.db.insertOrThrow(
                Favorites.TABLE_NAME,
                null,
                desktopRowValues(context, id.toLong(), cellX = 2, cellY = 0),
            )
            launcher.model.forceReload()
            waitForModelLoaded()

            val writer = LauncherLayoutAdapter(
                context,
                launcher.model.modelDbController,
                launcher.model,
                overlapToleranceSource = { false },
            )
            val target = writer.captureCurrent(CaptureId("overlap-recovery-target"))
            org.junit.Assume.assumeTrue(target.layoutState.reservedWorkspaceRegions.isNotEmpty())
            assertTrue(target.manifest.rows.isNotEmpty())

            // Simulate the loader deleting the overlapped row before recovery.
            launcher.model.modelDbController.db.delete(
                Favorites.TABLE_NAME,
                "${Favorites._ID}=?",
                arrayOf(id.toString()),
            )
            launcher.model.forceReload()
            waitForModelLoaded()
            val reviewed = writer.recaptureDb()
            assertTrue(reviewed.manifest.rows.none { it.rowId == id.toLong() })

            val prepared = writer.prepareRecoveryWriteSet(target.manifest, reviewed)
            assertTrue("recovery diff must materialize: $prepared", prepared is WriteSetPreparation.Ready)
            val probeWriteSet = (prepared as WriteSetPreparation.Ready).writeSet
            val probeReservations = reviewed.layoutState.reservedWorkspaceRegions
            // The gate must see the reservation overlap it is about to restore.
            assertTrue(
                "fixture must put the recovery target inside the reservation",
                overlapAcceptanceHolds(probeWriteSet.intendedManifest.rows, probeReservations, true),
            )
            val writeSet = (prepared as WriteSetPreparation.Ready).writeSet
            val outcome: ApplyTxOutcome = writer.withLease(WriterKind.ORGANIZER, 424242L) {
                writer.applyWriteSet(it, writeSet, null, FaultInjector.NOOP)
            } ?: error("organizer writer lease unavailable")
            assertTrue(
                "expected OVERLAP_POLICY_REJECTED, got $outcome",
                outcome is ApplyTxOutcome.PreconditionFailed &&
                    outcome.rejection == PreWriteRejection.OVERLAP_POLICY_REJECTED,
            )
            // The deleted row was not resurrected.
            launcher.model.modelDbController.db.query(
                Favorites.TABLE_NAME,
                arrayOf(Favorites._ID),
                "${Favorites._ID}=?",
                arrayOf(id.toString()),
                null,
                null,
                null,
            ).use { assertFalse(it.moveToFirst()) }
        } finally {
            prefs.enableSmartspace.setBlocking(originalSmartspace)
            prefs.allowWidgetOverlap.setBlocking(originalTolerance)
        }
    }

    private fun waitForModelLoaded() {
        val model = launcher.model
        val deadline = System.currentTimeMillis() + 10_000L
        while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    private fun desktopRowValues(
        context: android.content.Context,
        id: Long,
        cellX: Int,
        cellY: Int,
    ): ContentValues = ContentValues().apply {
        put(Favorites._ID, id)
        put(Favorites.TITLE, "Overlap gate fixture")
        put(
            Favorites.INTENT,
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
                .toUri(0),
        )
        put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
        put(Favorites.SCREEN, 0)
        put(Favorites.CELLX, cellX)
        put(Favorites.CELLY, cellY)
        put(Favorites.SPANX, 1)
        put(Favorites.SPANY, 1)
        put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
        put(Favorites.APPWIDGET_ID, -1)
        put(Favorites.MODIFIED, 1_000L)
        put(Favorites.RESTORED, 0)
        put(Favorites.PROFILE_ID, 0L)
        put(Favorites.RANK, 0)
        put(Favorites.OPTIONS, 0)
        put(Favorites.APPWIDGET_SOURCE, -1)
    }
}
