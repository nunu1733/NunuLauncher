package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.OperationIdSource
import app.lawnchair.organizer.application.protocol.WriteSetPreparation
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RestartReconciler
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.application.store.RecoveryStoreFaultPort
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.lawnchair.LawnchairLauncher

/** Real Launcher DB/recovery-store coverage through the accepted public seam. */
@RunWith(AndroidJUnit4::class)
class ProductionPublicSeamInstrumentationTest {
    private val runId = RunId("11111111111111111111111111111111")
    private val pointId = RecoveryPointId("22222222222222222222222222222222")
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var snapshotRows: List<ContentValues> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
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
            context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        }
    }

    @Test
    fun contextMismatchIsRejectedByRealAdapterBeforeAnyWrite() {
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val current = writer.captureCurrent(CaptureId("context-current"))
        val resource = current.manifest.resources.first()
        val changedPayload = resource.payload.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val target = current.manifest.copy(
            resources = current.manifest.resources.toMutableList().also {
                it[0] = resource.copy(payload = changedPayload)
            },
        )
        assertTrue(writer.prepareRecoveryWriteSet(target, current) is WriteSetPreparation.ContextMismatch)
    }

    @Test
    fun noChangeApplyAndMissingRecoveryUseProductionAdapters() {
        val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
        val capture = writer.captureCurrent(CaptureId("instrumentation"))
        val clock = SystemClock()
        val module = LayoutApplicationModule(
            writer,
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
        )
        module.reconcileAtStart()
        val plan = ValidatedLayoutPlan(
            capture.revision,
            capture.layoutState,
            capture.layoutState,
            capture.layoutState.items.map { ApplyAction.Preserve(it.ref, it) },
            emptyList(),
            emptyList(),
            RuleVersion("instrumentation"),
            TaxonomyVersion("instrumentation"),
        )
        assertTrue(module.apply(plan) is ApplyResult.NoChanges)
        val missing = module.recover(
            RecoveryRequest(
                RecoveryPointId("00000000000000000000000000000000"),
                RevisionId("missing"),
            ),
        )
        assertTrue(missing is RecoveryResult.NotRestorable)
    }

    @Test
    fun committedCreatingBoundariesReconcileThroughPublicApplyAndRestartSeams() {
        val cases = listOf(
            RecoveryStoreFaultPort.Phase.CREATING to FaultTiming.AFTER,
            RecoveryStoreFaultPort.Phase.READY to FaultTiming.BEFORE,
        )
        for ((phase, timing) in cases) {
            context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
            ensureLauncherRow(context, launcher)
            val writer = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)
            val capture = writer.captureCurrent(CaptureId("creating-$phase-$timing"))
            val sourceItem = capture.layoutState.items.first()
            val intendedItem = sourceItem.copy(title = OptionalText.Present("creating-boundary"))
            val plan = ValidatedLayoutPlan(
                capture.revision,
                capture.layoutState,
                capture.layoutState.copy(
                    items = capture.layoutState.items.map { if (it.ref == sourceItem.ref) intendedItem else it },
                ),
                listOf(ApplyAction.Update(sourceItem.ref, sourceItem, intendedItem)),
                emptyList(),
                emptyList(),
                RuleVersion("instrumentation"),
                TaxonomyVersion("instrumentation"),
            )
            val clock = SystemClock()
            val firstModule = LayoutApplicationModule(
                writer,
                RecoveryStore(context, clock::nowMillis, ThrowingStoreFault(phase, timing)),
                clock,
                FixedIds(runId, pointId),
                FaultInjector.NOOP,
            )
            firstModule.reconcileAtStart()
            val result = firstModule.apply(plan)

            assertEquals(ApplyResult.Rejected(runId, PreWriteRejection.CHECKPOINT_CREATE_FAILED), result)
            val reopenedStore = RecoveryStore(context, clock::nowMillis)
            assertEquals(LifecycleState.CREATING, reopenedStore.readRecord(pointId)?.lifecycle)
            assertEquals(capture.manifest, LauncherLayoutAdapter(
                context,
                launcher.model.modelDbController,
                launcher.model,
            ).recaptureDb().manifest)

            val restartModule = LayoutApplicationModule(
                LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model),
                reopenedStore,
                clock,
                FixedIds(runId, pointId),
            )
            assertEquals(RestartReconciler.ReconciliationSummary.Clean, restartModule.reconcileAtStart())
            assertNull(RecoveryStore(context, clock::nowMillis).readRecord(pointId))
            assertEquals(
                RecoveryStorePort.TombstoneReason.PRUNED_UNUSED,
                RecoveryStore(context, clock::nowMillis).readTombstone(pointId)?.reason,
            )
        }
    }

    private enum class FaultTiming { BEFORE, AFTER }

    private class ThrowingStoreFault(
        private val selectedPhase: RecoveryStoreFaultPort.Phase,
        private val timing: FaultTiming,
    ) : RecoveryStoreFaultPort by RecoveryStoreFaultPort.NOOP {
        override fun beforeCommit(phase: RecoveryStoreFaultPort.Phase, pointId: RecoveryPointId) {
            if (phase == selectedPhase && timing == FaultTiming.BEFORE) error("before $phase")
        }

        override fun afterCommit(phase: RecoveryStoreFaultPort.Phase, pointId: RecoveryPointId) {
            if (phase == selectedPhase && timing == FaultTiming.AFTER) error("after $phase")
        }
    }

    private class FixedIds(
        private val runId: RunId,
        private val pointId: RecoveryPointId,
    ) : OperationIdSource {
        override fun newRunId(): RunId = runId
        override fun newPointId(): RecoveryPointId = pointId
    }

    private fun ensureLauncherRow(
        context: android.content.Context,
        launcher: LauncherAppState,
    ) {
        val db = launcher.model.modelDbController.db
        db.query(Favorites.TABLE_NAME, arrayOf(Favorites._ID), null, null, null, null, null).use {
            if (it.moveToFirst()) return
        }
        val id = launcher.model.modelDbController.generateNewItemId()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, "Creating boundary")
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_HOTSEAT)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()))
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, 1)
            },
        )
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
