package app.lawnchair.organizer.application.store

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for Issue #84's no-create inspection contract. These
 * tests run against the production SQLite adapter, not FakeRecoveryStore.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryStoreInspectionInstrumentationTest {

    private val pointId = RecoveryPointId("22222222222222222222222222222222")

    @Test
    fun missingDatabaseAndSnapshotInspectionDoesNotCreateSchemaWalOrRecoveryFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val dbFile = recoveryDatabaseFile(context)
        val snapshotDirectory = snapshotDirectory(context)
        val store = RecoveryStore(context) { 1_000L }

        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))
        assertFalse(dbFile.exists())
        assertFalse(File("${dbFile.absolutePath}-wal").exists())
        assertFalse(File("${dbFile.absolutePath}-shm").exists())
        assertFalse(snapshotDirectory.exists())
    }

    @Test
    fun productionCheckpointInspectionLeavesStoreSidecarsAndSnapshotInventoryUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val payload = RecoveryStorePort.CheckpointPayload(
            pointId = pointId,
            runId = RunId("abcdef0123456789abcdef0123456789"),
            preManifest = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L),
            preRevision = RevisionId("revision"),
            preDigest = ByteArray(32),
            applyActionDigest = ByteArray(32),
            itemCount = 0,
            resourceCount = 0,
        )
        assertTrue(store.checkpoint(payload) is RecoveryStorePort.CheckpointResult.Ready)
        val dbFile = recoveryDatabaseFile(context)
        assertFalse(File("${dbFile.absolutePath}-wal").exists())
        assertFalse(File("${dbFile.absolutePath}-shm").exists())
        val before = physicalState(context, dbFile)

        val result = store.readInspectionProjection(pointId)

        assertTrue(result is RecoveryStorePort.InspectionProjectionRead.Value)
        assertEquals(before, physicalState(context, dbFile))
    }

    @Test
    fun sidecarsPresentInspectionLeavesProductionWalFilesUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val helper = RecoveryDbHelper(context)
        helper.writableDatabase
        assertTrue(
            store.checkpoint(
                RecoveryStorePort.CheckpointPayload(
                    pointId = pointId,
                    runId = RunId("abcdef0123456789abcdef0123456789"),
                    preManifest = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L),
                    preRevision = RevisionId("revision"),
                    preDigest = ByteArray(32),
                    applyActionDigest = ByteArray(32),
                    itemCount = 0,
                    resourceCount = 0,
                ),
            ) is RecoveryStorePort.CheckpointResult.Ready,
        )
        val dbFile = recoveryDatabaseFile(context)
        assertTrue(File("${dbFile.absolutePath}-wal").exists())
        assertTrue(File("${dbFile.absolutePath}-shm").exists())
        val before = physicalState(context, dbFile)

        val result = store.readInspectionProjection(pointId)

        assertTrue(result is RecoveryStorePort.InspectionProjectionRead.Value)
        assertEquals(before, physicalState(context, dbFile))
        helper.close()
    }

    @Test
    fun residualValidSnapshotWithMissingMainDatabaseFailsStartupWithoutCreatingRecoveryStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val original = RecoveryStore(context) { 1_000L }
        prepareForMutation(original)
        assertTrue(original.checkpoint(checkpointPayload()) is RecoveryStorePort.CheckpointResult.Ready)
        val dbFile = recoveryDatabaseFile(context)
        val snapshotBefore = physicalState(context, dbFile).filter { it.name.startsWith("snapshot") }
        assertTrue(dbFile.delete())
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        File("${dbFile.absolutePath}-journal").delete()

        val restarted = RecoveryStore(context) { 2_000L }
        assertFalse(rebuildAtStartup(restarted))
        assertFalse(dbFile.exists())
        assertEquals(snapshotBefore, physicalState(context, dbFile).filter { it.name.startsWith("snapshot") })
        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, restarted.readInspectionProjection(pointId))
    }

    @Test
    fun residualSnapshotWithCorruptMainDatabaseFailsStartupWithoutRepairOrClassificationSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val original = RecoveryStore(context) { 1_000L }
        prepareForMutation(original)
        assertTrue(original.checkpoint(checkpointPayload()) is RecoveryStorePort.CheckpointResult.Ready)
        val dbFile = recoveryDatabaseFile(context)
        val snapshotBefore = physicalState(context, dbFile).filter { it.name.startsWith("snapshot") }
        dbFile.writeText("not a sqlite database")
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()

        val restarted = RecoveryStore(context) { 2_000L }
        assertFalse(rebuildAtStartup(restarted))
        assertEquals(snapshotBefore, physicalState(context, dbFile).filter { it.name.startsWith("snapshot") })
        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, restarted.readInspectionProjection(pointId))
    }

    @Test
    fun invalidExistingDatabaseInspectionReturnsUnavailableWithoutHelperRepairOrWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val dbFile = recoveryDatabaseFile(context)
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("not a sqlite database")
        val sizeBefore = dbFile.length()
        val store = RecoveryStore(context) { 1_000L }

        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))
        assertFalse(rebuildAtStartup(store))
        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))
        assertTrue(dbFile.exists())
        assertTrue(dbFile.length() == sizeBefore)
        assertFalse(File("${dbFile.absolutePath}-wal").exists())
        assertFalse(File("${dbFile.absolutePath}-shm").exists())
        deleteRecoveryDatabaseArtifacts(context)
    }

    private fun recoveryDatabaseFile(context: Context): File =
        context.applicationContext.getDatabasePath(RecoveryDbSchema.FILE_NAME)

    private fun snapshotDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)

    private fun prepareForMutation(store: RecoveryStore) {
        assertTrue(rebuildAtStartup(store))
    }

    private fun rebuildAtStartup(store: RecoveryStore): Boolean {
        val mutex = RunMutex()
        val runId = RunId("dddddddddddddddddddddddddddddddd")
        assertTrue(mutex.tryAcquire(runId))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        val session = requireNotNull(issuer.openSession(lease))
        return try {
            session.rebuildInspectionSnapshot()
        } finally {
            session.close()
            mutex.release(runId)
        }
    }

    private fun checkpointPayload(): RecoveryStorePort.CheckpointPayload = RecoveryStorePort.CheckpointPayload(
        pointId = pointId,
        runId = RunId("abcdef0123456789abcdef0123456789"),
        preManifest = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L),
        preRevision = RevisionId("revision"),
        preDigest = ByteArray(32),
        applyActionDigest = ByteArray(32),
        itemCount = 0,
        resourceCount = 0,
    )

    private data class PhysicalFileState(
        val name: String,
        val exists: Boolean,
        val regularFile: Boolean,
        val length: Long?,
        val sha256: String?,
        val modifiedAtMs: Long?,
        val changedAtSeconds: Long?,
    )

    /** Complete closed-file-set inventory; atime is intentionally excluded. */
    private fun physicalState(context: Context, dbFile: File): List<PhysicalFileState> = buildList {
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm"),
            File("${dbFile.absolutePath}-journal"),
        ).forEach { file -> add(fileState(file.name, file)) }
        val snapshot = snapshotDirectory(context)
        add(fileState("snapshot-directory", snapshot))
        snapshot.listFiles()?.sortedBy { it.name }?.forEach { file ->
            add(fileState("snapshot/${file.name}", file))
        }
    }

    private fun fileState(name: String, file: File): PhysicalFileState {
        if (!file.exists()) return PhysicalFileState(name, false, false, null, null, null, null)
        val regular = file.isFile
        val stat = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        return PhysicalFileState(
            name = name,
            exists = true,
            regularFile = regular,
            length = if (regular) file.length() else null,
            sha256 = if (regular) fileDigest(file) else null,
            modifiedAtMs = file.lastModified(),
            changedAtSeconds = stat?.st_ctime,
        )
    }

    private fun fileDigest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun deleteRecoveryDatabaseArtifacts(context: Context) {
        val dbFile = recoveryDatabaseFile(context)
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-journal"),
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm"),
        ).forEach { file ->
            if (file.exists()) check(file.delete()) { "Unable to delete ${file.absolutePath}" }
        }
        snapshotDirectory(context).listFiles()?.forEach { file ->
            if (file.exists()) check(file.delete()) { "Unable to delete ${file.absolutePath}" }
        }
        snapshotDirectory(context).delete()
    }
}
