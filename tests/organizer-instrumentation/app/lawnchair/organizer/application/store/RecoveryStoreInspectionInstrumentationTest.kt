package app.lawnchair.organizer.application.store

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @After
    fun tearDown() {
        deleteRecoveryDatabaseArtifacts(ApplicationProvider.getApplicationContext())
    }

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
    fun invalidUnpublishedSnapshotInspectionReturnsUnavailableWithoutArtifactCleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        assertTrue(store.checkpoint(checkpointPayload()) is RecoveryStorePort.CheckpointResult.Ready)
        val dbFile = recoveryDatabaseFile(context)
        val finalSnapshot = File(snapshotDirectory(context), RecoveryInspectionSnapshotReader.FINAL_FILE_NAME)
        finalSnapshot.writeText("invalid unpublished snapshot")
        val before = physicalState(context, dbFile)

        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))
        assertEquals(before, physicalState(context, dbFile))
    }

    @Test
    fun snapshotNewBakAndUnexpectedEntriesReturnUnavailableWithoutCleanup() {
        listOf("recovery-inspection.v1.new", "recovery-inspection.v1.bak", "unexpected-entry").forEach { artifactName ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            deleteRecoveryDatabaseArtifacts(context)
            val store = RecoveryStore(context) { 1_000L }
            prepareForMutation(store)
            assertTrue(store.checkpoint(checkpointPayload()) is RecoveryStorePort.CheckpointResult.Ready)
            val dbFile = recoveryDatabaseFile(context)
            File(snapshotDirectory(context), artifactName).writeText("unpublished")
            val before = physicalState(context, dbFile)

            assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))
            assertEquals(before, physicalState(context, dbFile))
        }
    }

    @Test
    fun residualSnapshotWithIncompatibleAuthoritativeDatabaseReportsIncompatibleWithoutInspectionWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val original = RecoveryStore(context) { 1_000L }
        prepareForMutation(original)
        assertTrue(original.checkpoint(checkpointPayload()) is RecoveryStorePort.CheckpointResult.Ready)
        val helper = RecoveryDbHelper(context)
        try {
            helper.writableDatabase.execSQL("PRAGMA user_version = 999")
        } finally {
            helper.close()
        }
        val restarted = RecoveryStore(context) { 2_000L }
        assertFalse(rebuildAtStartup(restarted))
        val dbFile = recoveryDatabaseFile(context)
        val before = physicalState(context, dbFile)

        assertEquals(RecoveryStorePort.InspectionProjectionRead.Incompatible, restarted.readInspectionProjection(pointId))
        assertEquals(before, physicalState(context, dbFile))
    }

    @Test
    fun inspectionDuringBlockedWriterIsUnavailableAndDoesNotTouchFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        val writerReachedCommit = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        var blockCheckpoint = false
        val store = RecoveryStore(
            context,
            { 1_000L },
            object : RecoveryStoreFaultPort {
                override fun beforeCommit(phase: RecoveryStoreFaultPort.Phase, pointId: RecoveryPointId) {
                    if (blockCheckpoint && phase == RecoveryStoreFaultPort.Phase.CREATING) {
                        writerReachedCommit.countDown()
                        check(releaseWriter.await(10, TimeUnit.SECONDS)) { "writer release timed out" }
                    }
                }

                override fun afterCommit(phase: RecoveryStoreFaultPort.Phase, pointId: RecoveryPointId) = Unit
            },
        )
        prepareForMutation(store)
        blockCheckpoint = true
        var writerResult: RecoveryStorePort.CheckpointResult? = null
        val writer = Thread { writerResult = store.checkpoint(checkpointPayload()) }
        writer.start()
        assertTrue(writerReachedCommit.await(10, TimeUnit.SECONDS))
        val before = physicalState(context, recoveryDatabaseFile(context))

        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))
        assertEquals(before, physicalState(context, recoveryDatabaseFile(context)))

        releaseWriter.countDown()
        writer.join(10_000L)
        assertFalse(writer.isAlive)
        assertTrue(writerResult is RecoveryStorePort.CheckpointResult.Ready)
    }

    @Test
    fun unknownFenceOrdinaryMutationsRejectBeforeVersionProbe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        var probeCalls = 0
        val store = RecoveryStore(context, { 1_000L }) {
            probeCalls += 1
            error("ordinary mutation reached version probe while fence was unknown")
        }
        val manifest = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)

        assertEquals(RecoveryStorePort.CheckpointResult.StoreUnavailable, store.checkpoint(checkpointPayload()))
        assertFalse(store.markApplying(pointId, manifest, ByteArray(32), ByteArray(32), 0, 0))
        assertFalse(store.advance(pointId, LifecycleState.APPLYING))
        assertFalse(store.markRestoring(pointId, manifest, ByteArray(32), ByteArray(32)))
        assertEquals(RecoveryStorePort.RetentionOutcome.StoreUnavailable, store.runRetention(1_000L))
        assertFalse(store.pruneUnused(pointId))
        assertEquals(0, probeCalls)
    }

    @Test
    fun availabilityFailureAfterLegalMutationStartStaysDirtyAndNextMutationSkipsVersionProbe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryDatabaseArtifacts(context)
        var probeCalls = 0
        var failVersionProbe = false
        val store = RecoveryStore(context, { 1_000L }) { file ->
            probeCalls += 1
            if (failVersionProbe) {
                RecoveryDbVersionGate.VersionDecision.ReadFailed(
                    IllegalStateException("simulated authoritative availability failure"),
                )
            } else {
                RecoveryDbVersionGate.probe(file)
            }
        }
        prepareForMutation(store)
        assertTrue(store.checkpoint(checkpointPayload()) is RecoveryStorePort.CheckpointResult.Ready)
        assertTrue(store.readInspectionProjection(pointId) is RecoveryStorePort.InspectionProjectionRead.Value)

        failVersionProbe = true
        assertFalse(store.advance(pointId, LifecycleState.APPLYING))
        assertEquals(RecoveryStorePort.InspectionProjectionRead.Unavailable, store.readInspectionProjection(pointId))

        probeCalls = 0
        assertEquals(
            RecoveryStorePort.CheckpointResult.StoreUnavailable,
            store.checkpoint(checkpointPayload(RecoveryPointId("33333333333333333333333333333333"))),
        )
        assertEquals(0, probeCalls)
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

    private fun checkpointPayload(
        pointId: RecoveryPointId = this.pointId,
    ): RecoveryStorePort.CheckpointPayload = RecoveryStorePort.CheckpointPayload(
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
