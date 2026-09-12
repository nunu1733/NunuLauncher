package app.lawnchair.organizer.application.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RestartReconciler
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import com.android.launcher3.LauncherAppState
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #271 restart coverage: the derived durable status projection across a
 * production close/reopen (process-death surrogate) plus module-level restart
 * reconciliation, through the public seam only.
 */
@RunWith(AndroidJUnit4::class)
class OrganizerDurableStatusInstrumentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        deleteRecoveryArtifacts(context)
    }

    private fun deleteRecoveryArtifacts(context: Context) {
        val dbFile = context.applicationContext.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-journal"),
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm"),
        ).forEach { file ->
            if (file.exists()) check(file.delete()) { "Unable to delete ${file.absolutePath}" }
        }
        File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).let { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.exists()) check(file.delete()) { "Unable to delete ${file.absolutePath}" }
            }
            directory.delete()
        }
    }

    private fun freshModule(): LayoutApplicationModule<RecoveryStore> {
        val launcher = LauncherAppState.getInstance(context)
        val writer = app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter(
            context,
            launcher.model.modelDbController,
            launcher.model,
        )
        val clock = SystemClock()
        return LayoutApplicationModule(
            writer,
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
            folderTitleResolver = GeneratedFolderTitles.resolver(context),
        )
    }

    private fun prepareForMutation(store: RecoveryStore) {
        val mutex = RunMutex()
        val runId = RunId("cccccccccccccccccccccccccccccccc")
        assertTrue(mutex.tryAcquire(runId))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        val session = requireNotNull(issuer.openSession(lease))
        try {
            assertTrue(session.rebuildInspectionSnapshot())
        } finally {
            session.close()
            mutex.release(runId)
        }
    }

    private fun checkpoint(store: RecoveryStore, pointId: RecoveryPointId): Boolean {
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        val digest = ByteArray(32)
        return store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId,
                RunId("abcdef0123456789abcdef0123456789"),
                empty,
                RevisionId("revision"),
                digest,
                digest,
                0,
                0,
            ),
        ) is RecoveryStorePort.CheckpointResult.Ready
    }

    private fun advanceThrough(store: RecoveryStore, pointId: RecoveryPointId, vararg states: LifecycleState) {
        states.forEach { state ->
            assertTrue(store.advance(pointId, state))
        }
    }

    @Test
    fun emptyStorePresentsNeverOrganizedAfterReconciliation() {
        deleteRecoveryArtifacts(context)
        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.NEVER_ORGANIZED, module.durableOrganizerStatus())
    }

    @Test
    fun verifiedPointIsRestorableAfterProcessDeath() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        assertTrue(checkpoint(store, pointId))
        advanceThrough(store, pointId, LifecycleState.APPLYING, LifecycleState.COMMITTED_UNVERIFIED, LifecycleState.VERIFIED)

        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.ORGANIZED_RESTORABLE, module.durableOrganizerStatus())
    }

    @Test
    fun unreadableStoreBeforeReconciliationFailsClosed() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        assertTrue(checkpoint(store, pointId))
        advanceThrough(store, pointId, LifecycleState.APPLYING, LifecycleState.COMMITTED_UNVERIFIED, LifecycleState.VERIFIED)

        // Fresh process, restart reconciliation has not completed: fail-closed.
        val module = freshModule()
        assertEquals(OrganizerDurableStatus.UNAVAILABLE, module.durableOrganizerStatus())
    }

    @Test
    fun restoredRowPresentsRestoredOrExpiredAfterProcessDeath() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        assertTrue(checkpoint(store, pointId))
        advanceThrough(store, pointId, LifecycleState.APPLYING)
        assertTrue(store.markRestoring(pointId, empty, digest, digest))
        advanceThrough(store, pointId, LifecycleState.RESTORED)

        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.RESTORED_OR_EXPIRED, module.durableOrganizerStatus())
    }

    @Test
    fun expiredRowsEvictedToTombstonesPresentRestoredOrExpired() {
        deleteRecoveryArtifacts(context)
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        // Three EXPIRED rows are collapsed into EXPIRED tombstones by the
        // fourth checkpoint's admission transaction (existing capacity rule).
        repeat(3) { index ->
            val pointId = RecoveryPointId("aaaa%02d%s".format(index, "0".repeat(26)))
            assertTrue(checkpoint(store, pointId))
            advanceThrough(store, pointId, LifecycleState.EXPIRED)
        }
        // The fourth checkpoint's admission collapses the three EXPIRED rows
        // into EXPIRED tombstones; its own row is advanced to EXPIRED too so
        // restart reconciliation has no non-final candidate left.
        val fourth = RecoveryPointId("bbbb00" + "0".repeat(26))
        assertTrue(checkpoint(store, fourth))
        advanceThrough(store, fourth, LifecycleState.EXPIRED)

        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.RESTORED_OR_EXPIRED, module.durableOrganizerStatus())
    }

    @Test
    fun corruptRowPresentsUnresolvedAfterProcessDeath() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        assertTrue(checkpoint(store, pointId))
        advanceThrough(store, pointId, LifecycleState.CORRUPT)

        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.UNRESOLVED, module.durableOrganizerStatus())
    }

    @Test
    fun unreadableVerifiedRowPresentsUnresolvedAfterProcessDeath() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        assertTrue(checkpoint(store, pointId))
        advanceThrough(store, pointId, LifecycleState.APPLYING, LifecycleState.COMMITTED_UNVERIFIED, LifecycleState.VERIFIED)

        // Destroy the manifest chunks so the record assembles to `Unreadable`
        // (checksum-invalid projection): reconciliation contains it silently
        // and the snapshot projects checksumValid = false.
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        db.use {
            it.delete(RecoveryDbSchema.TABLE_MANIFEST_CHUNKS, "point_id = ?", arrayOf(pointId.value))
        }

        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.UNRESOLVED, module.durableOrganizerStatus())
    }

    @Test
    fun statusRecoversOnTheSameModuleAfterReconciliationCompletes() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        assertTrue(checkpoint(store, pointId))
        advanceThrough(store, pointId, LifecycleState.APPLYING, LifecycleState.COMMITTED_UNVERIFIED, LifecycleState.VERIFIED)

        // Issue #271 review: one module instance, read before and after
        // reconciliation completes — the fail-closed first read must recover
        // on the same instance (the Settings re-open race).
        val module = freshModule()
        assertEquals(OrganizerDurableStatus.UNAVAILABLE, module.durableOrganizerStatus())
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.ORGANIZED_RESTORABLE, module.durableOrganizerStatus())
    }

    @Test
    fun prunedCheckpointPresentsNeverOrganized() {
        deleteRecoveryArtifacts(context)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val store = RecoveryStore(context, SystemClock()::nowMillis)
        prepareForMutation(store)
        assertTrue(checkpoint(store, pointId))
        assertTrue(store.pruneUnused(pointId))

        val module = freshModule()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(OrganizerDurableStatus.NEVER_ORGANIZED, module.durableOrganizerStatus())
    }
}
