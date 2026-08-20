package app.lawnchair.organizer.application.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
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
        assertTrue(store.withReconciliationScope { store.rebuildInspectionSnapshotForReconciliation() })
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
        assertTrue(store.withReconciliationScope { store.rebuildInspectionSnapshotForReconciliation() })
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
    fun invalidExistingDatabaseInspectionReturnsUnavailableWithoutHelperRepairOrWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val dbFile = recoveryDatabaseFile(context)
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("not a sqlite database")
        val sizeBefore = dbFile.length()
        val store = RecoveryStore(context) { 1_000L }

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

    private fun physicalState(context: Context, dbFile: File): List<Pair<String, String>> = buildList {
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm"),
        ).forEach { file -> if (file.isFile) add(file.name to fileDigest(file)) }
        snapshotDirectory(context).listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isFile) add("snapshot/${file.name}" to fileDigest(file))
        }
    }

    private fun fileDigest(file: File): String = buildString {
        append(file.length())
        append(':')
        append(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) })
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
