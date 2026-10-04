package com.android.launcher3.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryDbVersionGate
import app.lawnchair.organizer.application.store.RecoveryManifestChunks
import app.lawnchair.organizer.application.store.RecoveryRecordCodec
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.application.store.RecoveryStoreFaultPort
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.planning.RevisionId
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Durability coverage through the production RecoveryStore API, including its close/reopen check. */
@RunWith(AndroidJUnit4::class)
class RecoveryStoreLifecycleTest {
    @After
    fun cleanup() {
        deleteRecoveryArtifacts(ApplicationProvider.getApplicationContext())
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

    @Test
    fun checkpointSurvivesProductionCloseAndReopenValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        val first = RecoveryStore(context) { 1000L }
        prepareForMutation(first)
        val result = first.checkpoint(
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
        )
        assertTrue(result is RecoveryStorePort.CheckpointResult.Ready)

        val reopened = RecoveryStore(context) { 2000L }
        val stored = (reopened.readRecord(pointId) as? RecoveryStorePort.RecordRead.Readable)?.record
        assertNotNull(stored)
        assertEquals(LifecycleState.READY, stored?.lifecycle)
        assertEquals(RevisionId("revision"), stored?.preRevision)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun newerRecoveryDatabaseIsRejectedWithoutWriteOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA user_version = 4")
        }
        val before = file.readBytes()
        val store = RecoveryStore(context) { 1_000L }
        assertEquals(
            RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION,
            store.availability(),
        )
        assertNull(store.listReconciliationCandidates())
        assertTrue(before.contentEquals(file.readBytes()))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun legacyV1LifecycleMatrixRemainsIncompatibleWithoutMutation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycles = listOf(
            LifecycleState.CREATING,
            LifecycleState.READY,
            LifecycleState.APPLYING,
            LifecycleState.COMMITTED_UNVERIFIED,
            LifecycleState.RESTORING,
            LifecycleState.VERIFIED,
        )
        lifecycles.forEachIndexed { index, lifecycle ->
            deleteRecoveryArtifacts(context)
            val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
            createLegacyStoreWithPoint(context, lifecycle, index)
            val before = file.readBytes()

            val store = RecoveryStore(context) { 1_000L }

            assertEquals("$lifecycle availability", RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION, store.availability())
            assertTrue("$lifecycle legacy DB changed", before.contentEquals(file.readBytes()))
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "PRAGMA user_version", null))
                assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM recovery_points", null))
            }
        }
    }

    @Test
    fun v2StoreIsRejectedByV1ReadOnlyGateWithoutMutation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteRecoveryArtifacts(context)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        createCheckpointed(store)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        val before = file.readBytes()

        assertEquals(
            RecoveryDbVersionGate.VersionDecision.Incompatible(RecoveryDbSchema.SCHEMA_VERSION),
            RecoveryDbVersionGate.probeForFormat(file, 1),
        )
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun legacyV1StoreWithUnexpiredTombstoneRemainsIncompatibleWithoutMutation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        createLegacyEmptyStore(context, tombstoneExpiresAt = 1_001L)

        val store = RecoveryStore(context) { 1_000L }

        assertEquals(RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION, store.availability())
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "PRAGMA user_version", null))
            assertEquals(
                1L,
                android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM recovery_tombstones", null),
            )
        }
    }

    @Test
    fun legacyV1StoreWithOnlyExpiredTombstoneMigratesToCurrentSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        createLegacyEmptyStore(context, tombstoneExpiresAt = 999L)

        val store = RecoveryStore(context) { 1_000L }

        assertEquals(RecoveryStorePort.StoreAvailability.READY, store.availability())
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // The empty v1 store advances straight to the current physical schema.
            assertEquals(RecoveryDbSchema.SCHEMA_VERSION.toLong(), android.database.DatabaseUtils.longForQuery(db, "PRAGMA user_version", null))
            assertEquals(
                0L,
                android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM recovery_tombstones", null),
            )
            // The physical structure must be the real schema-3 shape, not a
            // bumped pragma over the legacy tables.
            assertEquals(
                1L,
                android.database.DatabaseUtils.longForQuery(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'recovery_manifest_chunks'",
                    null,
                ),
            )
        }

        // End-to-end: the migrated store must actually be usable through the
        // production writer, not just version-compatible on paper.
        prepareForMutation(store)
        val pointId = RecoveryPointId("aabbccdd00112233445566778899aabb")
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId,
                RunId("aabbccdd00112233445566778899aabb"),
                empty,
                RevisionId("rev"),
                digest,
                digest,
                0,
                0,
            ),
        )
        assertTrue("checkpoint after v1 migration: $result", result is RecoveryStorePort.CheckpointResult.Ready)
        val reopened = RecoveryStore(context) { 2_000L }
        val read = reopened.readRecord(pointId)
        assertTrue("Expected Readable after close/reopen, got $read", read is RecoveryStorePort.RecordRead.Readable)
        assertEquals(LifecycleState.READY, (read as RecoveryStorePort.RecordRead.Readable).record.lifecycle)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun expiredReasonRemainsExactForTwentyFourHoursThenPurges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        var now = 1_000L
        val store = RecoveryStore(context) { now }
        prepareForMutation(store)
        val pointId = RecoveryPointId("1123456789abcdef0123456789abcdef")
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        assertTrue(
            store.checkpoint(
                RecoveryStorePort.CheckpointPayload(
                    pointId,
                    RunId("bbcdef0123456789abcdef0123456789"),
                    empty,
                    RevisionId("revision"),
                    digest,
                    digest,
                    0,
                    0,
                ),
            ) is RecoveryStorePort.CheckpointResult.Ready,
        )
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.EXPIRED))

        now += 86_400_000L
        assertEquals(RecoveryStorePort.RetentionOutcome.Applied, store.runRetention(now))
        val tombstone = store.readTombstone(pointId)
        assertEquals(RecoveryStorePort.TombstoneReason.EXPIRED, tombstone?.reason)
        assertEquals(now + 86_400_000L, tombstone?.expiresAtMs)

        now += 86_400_000L
        assertEquals(RecoveryStorePort.RetentionOutcome.Applied, store.runRetention(now))
        assertEquals(null, store.readTombstone(pointId))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun threeRestoredRecordsBecomeTombstonesAndAllowFourthCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        var now = 1_000L
        val store = RecoveryStore(context) { now }
        prepareForMutation(store)
        val manifest = emptyManifest()
        val digest = ByteArray(32)
        val pointIds = listOf(
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"),
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3"),
        )
        val tombstoneDeadlines = pointIds.mapIndexed { index, pointId ->
            createRestored(store, pointId, index)
        }

        now = 2_000L
        val fourthPoint = RecoveryPointId("ddddddddddddddddddddddddddddddd4")
        val fourth = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = fourthPoint,
                runId = RunId("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"),
                preManifest = manifest,
                preRevision = RevisionId("rev-fourth"),
                preDigest = digest,
                applyActionDigest = digest,
                itemCount = 0,
                resourceCount = 0,
            ),
        )
        assertTrue("fourth checkpoint must be admitted: $fourth", fourth is RecoveryStorePort.CheckpointResult.Ready)

        pointIds.forEachIndexed { index, pointId ->
            assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
            val tombstone = store.readTombstone(pointId)
            assertEquals(RecoveryStorePort.TombstoneReason.ALREADY_RESTORED, tombstone?.reason)
            assertEquals(tombstoneDeadlines[index], tombstone?.expiresAtMs)
        }
        assertEquals(
            LifecycleState.READY,
            ((store.readRecord(fourthPoint) as RecoveryStorePort.RecordRead.Readable).record.lifecycle),
        )

        val reopened = RecoveryStore(context) { now }
        pointIds.forEachIndexed { index, pointId ->
            assertEquals(RecoveryStorePort.RecordRead.Missing, reopened.readRecord(pointId))
            assertEquals(tombstoneDeadlines[index], reopened.readTombstone(pointId)?.expiresAtMs)
        }
        assertEquals(
            LifecycleState.READY,
            ((reopened.readRecord(fourthPoint) as RecoveryStorePort.RecordRead.Readable).record.lifecycle),
        )
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(1L, android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM recovery_points", null))
            assertEquals(3L, android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM recovery_tombstones", null))
            assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
        }
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun threeActiveRecordsRejectRepeatedCheckpointWithoutDirtyingInspectionFence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        var now = 1_000L
        val store = RecoveryStore(context) { now }
        prepareForMutation(store)
        val activePointIds = listOf(
            RecoveryPointId("1".padStart(32, 'a')),
            RecoveryPointId("2".padStart(32, 'a')),
            RecoveryPointId("3".padStart(32, 'a')),
        )
        activePointIds.forEachIndexed { index, pointId ->
            createApplying(store, pointId, index)
        }

        now = 2_000L
        val digest = ByteArray(32)
        fun checkpoint(pointId: RecoveryPointId) = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = pointId,
                runId = RunId(pointId.value),
                preManifest = emptyManifest(),
                preRevision = RevisionId("blocked-${pointId.value.takeLast(1)}"),
                preDigest = digest,
                applyActionDigest = digest,
                itemCount = 0,
                resourceCount = 0,
            ),
        )

        assertEquals(
            RecoveryStorePort.CheckpointResult.AdmissionBlocked,
            checkpoint(RecoveryPointId("4".padStart(32, 'a'))),
        )
        // A proven no-commit rejection must preserve the valid inspection
        // generation so a second ordinary mutation gets the same typed result.
        assertEquals(
            RecoveryStorePort.CheckpointResult.AdmissionBlocked,
            checkpoint(RecoveryPointId("5".padStart(32, 'a'))),
        )
        activePointIds.forEach { pointId ->
            assertEquals(LifecycleState.APPLYING, lifecycleOf(store, pointId))
        }
        assertEquals(
            RecoveryStorePort.RecordRead.Missing,
            store.readRecord(RecoveryPointId("4".padStart(32, 'a'))),
        )
        assertEquals(
            RecoveryStorePort.RecordRead.Missing,
            store.readRecord(RecoveryPointId("5".padStart(32, 'a'))),
        )
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun mixedActiveAndFinalAdmissionPreservesActiveAndExactTombstoneReasons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        var now = 1_000L
        val store = RecoveryStore(context) { now }
        prepareForMutation(store)

        val activePoint = RecoveryPointId("6".padStart(32, 'a'))
        createApplying(store, activePoint, 0)
        val restoredPoint = RecoveryPointId("7".padStart(32, 'a'))
        val restoredDeadline = createRestored(store, restoredPoint, 1)
        val corruptPoint = RecoveryPointId("8".padStart(32, 'a'))
        createCheckpointed(store, corruptPoint, 2)
        assertTrue(store.advance(corruptPoint, LifecycleState.CORRUPT))

        now = 2_000L
        val admissionPlan = RetentionPolicy.planCreate(store.listRetentionRecords(includeFinal = true), now)
        assertTrue("mixed admission plan: $admissionPlan", admissionPlan is RetentionPolicy.CreateDecision.Allowed)
        assertEquals(2, (admissionPlan as RetentionPolicy.CreateDecision.Allowed).toEvict.size)
        val fourthPoint = RecoveryPointId("a".padStart(32, 'b'))
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = fourthPoint,
                runId = RunId("b".padStart(32, 'c')),
                preManifest = emptyManifest(),
                preRevision = RevisionId("mixed-fourth"),
                preDigest = ByteArray(32),
                applyActionDigest = ByteArray(32),
                itemCount = 0,
                resourceCount = 0,
            ),
        )

        assertTrue("mixed admission must succeed: $result", result is RecoveryStorePort.CheckpointResult.Ready)
        assertEquals(LifecycleState.APPLYING, lifecycleOf(store, activePoint))
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(restoredPoint))
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(corruptPoint))
        assertEquals(RecoveryStorePort.TombstoneReason.ALREADY_RESTORED, store.readTombstone(restoredPoint)?.reason)
        assertEquals(restoredDeadline, store.readTombstone(restoredPoint)?.expiresAtMs)
        assertEquals(RecoveryStorePort.TombstoneReason.CORRUPT, store.readTombstone(corruptPoint)?.reason)
        assertEquals(1_000L + 86_400_000L, store.readTombstone(corruptPoint)?.expiresAtMs)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun incompatibleFinalRecordIsTombstonedWithExactReasonAtAdmission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        var now = 1_000L
        val store = RecoveryStore(context) { now }
        prepareForMutation(store)
        createApplying(store, RecoveryPointId("9".padStart(32, 'a')), 0)
        createApplying(store, RecoveryPointId("a".padStart(32, 'a')), 1)
        val incompatiblePoint = RecoveryPointId("b".padStart(32, 'a'))
        createCheckpointed(store, incompatiblePoint, 2)
        forceLifecycle(store, context, incompatiblePoint, LifecycleState.INCOMPATIBLE, now)

        now = 2_000L
        val fourthPoint = RecoveryPointId("c".padStart(32, 'b'))
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = fourthPoint,
                runId = RunId("d".padStart(32, 'c')),
                preManifest = emptyManifest(),
                preRevision = RevisionId("incompatible-fourth"),
                preDigest = ByteArray(32),
                applyActionDigest = ByteArray(32),
                itemCount = 0,
                resourceCount = 0,
            ),
        )

        assertTrue("incompatible admission must succeed: $result", result is RecoveryStorePort.CheckpointResult.Ready)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(incompatiblePoint))
        assertEquals(
            RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION,
            store.readTombstone(incompatiblePoint)?.reason,
        )
        assertEquals(1_000L + 86_400_000L, store.readTombstone(incompatiblePoint)?.expiresAtMs)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun failedFourthCheckpointRollsBackAdmissionTombstones() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        var now = 1_000L
        val store = RecoveryStore(context) { now }
        prepareForMutation(store)
        val pointIds = listOf(
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"),
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3"),
        )
        pointIds.forEachIndexed { index, pointId ->
            createRestored(store, pointId, index)
        }

        now = 2_000L
        val failingStore = RecoveryStore(
            context,
            { now },
            ThrowingFaultPort(RecoveryStoreFaultPort.Phase.CREATING, FaultTiming.BEFORE),
        )
        prepareForMutation(failingStore)
        val fourthPoint = RecoveryPointId("ddddddddddddddddddddddddddddddd4")
        val digest = ByteArray(32)
        val result = failingStore.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = fourthPoint,
                runId = RunId("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"),
                preManifest = emptyManifest(),
                preRevision = RevisionId("rev-fourth"),
                preDigest = digest,
                applyActionDigest = digest,
                itemCount = 0,
                resourceCount = 0,
            ),
        )

        assertEquals(RecoveryStorePort.CheckpointResult.CreateFailed, result)
        val physicalPointIds = SQLiteDatabase.openDatabase(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            val ids = mutableListOf<String>()
            db.rawQuery("SELECT point_id FROM recovery_points ORDER BY point_id", null).use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getString(0)
            }
            ids
        }
        assertEquals(
            "physical point IDs after rollback",
            pointIds.map { it.value },
            physicalPointIds,
        )
        pointIds.forEach { pointId ->
            val read = failingStore.readRecord(pointId)
            assertTrue("$pointId read after rollback: $read", read is RecoveryStorePort.RecordRead.Readable)
            val record = (read as RecoveryStorePort.RecordRead.Readable).record
            assertEquals(LifecycleState.RESTORED, record.lifecycle)
            assertNull(failingStore.readTombstone(pointId))
        }
        assertEquals(RecoveryStorePort.RecordRead.Missing, failingStore.readRecord(fourthPoint))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    // --- Finding 5b: reopen per lifecycle transition ---

    @Test
    fun applyingSurvivesProductionCloseAndReopen() {
        val (context, _, pointId) = checkpointedStore()
        // The store is already closed by the helper inside checkpointedStore();
        // open a new one to verify the persisted APPLYING state.
        val reopened = RecoveryStore(context) { 3000L }
        prepareForMutation(reopened)
        assertTrue(reopened.advance(pointId, LifecycleState.APPLYING))
        val rechecked = RecoveryStore(context) { 4000L }
        assertEquals(LifecycleState.APPLYING, lifecycleOf(rechecked, pointId))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun committedUnverifiedSurvivesProductionCloseAndReopen() {
        val (context, store, pointId) = checkpointedStore()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.COMMITTED_UNVERIFIED, lifecycleOf(reopened, pointId))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun verifiedSurvivesProductionCloseAndReopen() {
        val (context, store, pointId) = checkpointedStore()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.VERIFIED, lifecycleOf(reopened, pointId))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun restoringSurvivesProductionCloseAndReopen() {
        val (context, _, pointId) = checkpointedStore()
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        // Need to first advance to a state that allows RESTORING.
        val store = RecoveryStore(context) { 2000L }
        prepareForMutation(store)
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.markRestoring(pointId, empty, digest, digest))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.RESTORING, lifecycleOf(reopened, pointId))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun restoredSurvivesProductionCloseAndReopen() {
        val (context, _, pointId) = checkpointedStore()
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        val store = RecoveryStore(context) { 2000L }
        prepareForMutation(store)
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.markRestoring(pointId, empty, digest, digest))
        assertTrue(store.advance(pointId, LifecycleState.RESTORED))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.RESTORED, lifecycleOf(reopened, pointId))
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    // --- AC-14: typed pre/post-commit durability coverage ---

    @Test
    fun preCommitFailuresReturnTypedResultAndLeaveDurableStateUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (case in preCommitCases()) {
            deleteRecoveryArtifacts(context)
            val store = RecoveryStore(
                context,
                { 1000L },
                ThrowingFaultPort(case.phase, FaultTiming.BEFORE),
            )
            prepareForMutation(store)
            val pointId = case.setup(store)
            val result = case.action(store, pointId)
            assertEquals("${case.name} result", case.expectedResult, result)
            val reopened = RecoveryStore(context) { 1000L }
            assertEquals(
                "${case.name} lifecycle",
                case.expectedLifecycle,
                lifecycleOf(reopened, pointId),
            )
            if (case.expectsTombstone) {
                assertNotNull("${case.name} tombstone", reopened.readTombstone(pointId))
            }
        }
    }

    @Test
    fun postCommitAmbiguityReturnsTypedResultButDurableStateSurvives() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (case in postCommitCases()) {
            deleteRecoveryArtifacts(context)
            val store = RecoveryStore(
                context,
                { 1000L },
                ThrowingFaultPort(case.phase, FaultTiming.AFTER),
            )
            prepareForMutation(store)
            val pointId = case.setup(store)
            val result = case.action(store, pointId)
            assertEquals("${case.name} result", case.expectedResult, result)
            val reopened = RecoveryStore(context) { 1000L }
            assertEquals(
                "${case.name} lifecycle",
                case.expectedLifecycle,
                lifecycleOf(reopened, pointId),
            )
            if (case.expectsTombstone) {
                assertNotNull("${case.name} tombstone", reopened.readTombstone(pointId))
            }
        }
    }

    // --- Issue #178: fault injection on the quarantine transaction itself ---

    @Test
    fun quarantineFaultInjectionRollsBackBeforeCommitAndKeepsCanonicalTombstoneAfterCommit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (timing in listOf(FaultTiming.BEFORE, FaultTiming.AFTER)) {
            deleteRecoveryArtifacts(context)
            val store = RecoveryStore(
                context,
                { 1000L },
                ThrowingFaultPort(RecoveryStoreFaultPort.Phase.TOMBSTONE, timing),
            )
            prepareForMutation(store)
            val pointId = createCheckpointed(store)
            val chunksBefore = chunkRowCountFor(context, pointId)
            check(chunksBefore > 0)

            // The reconciliation issuer binds once per store instance, so the
            // quarantining session opens on a fresh instance that carries the
            // same fault port — injecting the exact quarantine transaction
            // (writeTombstone → child-first deletePoint), not the shared
            // retention/prune callers.
            val sessionStore = RecoveryStore(
                context,
                { 2000L },
                ThrowingFaultPort(RecoveryStoreFaultPort.Phase.TOMBSTONE, timing),
            )
            val mutex = RunMutex()
            val runId = RunId("cccccccccccccccccccccccccccccccc")
            assertTrue(mutex.tryAcquire(runId))
            val lease = requireNotNull(mutex.issueReconciliationLease(runId))
            val issuer = requireNotNull(sessionStore.bindReconciliationIssuer(mutex))
            val session = requireNotNull(issuer.openSession(lease))
            val quarantined = try {
                session.quarantineUnmutated(pointId, LifecycleState.READY)
            } finally {
                session.close()
                mutex.release(runId)
            }
            assertFalse("$timing quarantine must report the fault", quarantined)

            val reopened = RecoveryStore(context) { 3000L }
            if (timing == FaultTiming.BEFORE) {
                // The transaction rolled back: the record and its chunks stay
                // stored and no tombstone was written.
                assertEquals(
                    "$timing lifecycle",
                    LifecycleState.READY,
                    lifecycleOf(reopened, pointId),
                )
                assertNull("$timing tombstone", reopened.readTombstone(pointId))
                assertEquals("$timing chunks", chunksBefore, chunkRowCountFor(context, pointId))
            } else {
                // The commit succeeded before the ambiguous fault: the
                // QUARANTINED tombstone (canonical reason 6) survives and the
                // child-first chunk deletion committed with the record.
                assertEquals(
                    "$timing record",
                    RecoveryStorePort.RecordRead.Missing,
                    reopened.readRecord(pointId),
                )
                assertEquals(
                    "$timing tombstone reason",
                    RecoveryStorePort.TombstoneReason.QUARANTINED,
                    reopened.readTombstone(pointId)?.reason,
                )
                assertEquals("$timing chunks", 0, chunkRowCountFor(context, pointId))
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
            }
        }
    }

    private fun preCommitCases(): List<FaultCase> = listOf(
        FaultCase(
            name = "CREATING",
            phase = RecoveryStoreFaultPort.Phase.CREATING,
            setup = { RecoveryPointId("aabbccdd00112233445566778899aabb") },
            action = { store, id ->
                val digest = ByteArray(32)
                val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
                store.checkpoint(
                    RecoveryStorePort.CheckpointPayload(
                        id, RunId("aabbccdd00112233445566778899aabb"),
                        empty, RevisionId("rev"), digest, digest, 0, 0,
                    ),
                )
            },
            expectedResult = RecoveryStorePort.CheckpointResult.CreateFailed,
            expectedLifecycle = null,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "READY",
            phase = RecoveryStoreFaultPort.Phase.READY,
            setup = { RecoveryPointId("aabbccdd00112233445566778899aabb") },
            action = { store, id ->
                val digest = ByteArray(32)
                val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
                store.checkpoint(
                    RecoveryStorePort.CheckpointPayload(
                        id, RunId("aabbccdd00112233445566778899aabb"),
                        empty, RevisionId("rev"), digest, digest, 0, 0,
                    ),
                )
            },
            expectedResult = RecoveryStorePort.CheckpointResult.CreateFailed,
            expectedLifecycle = LifecycleState.CREATING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "APPLYING",
            phase = RecoveryStoreFaultPort.Phase.APPLYING,
            setup = { createCheckpointed(it) },
            action = { store, id ->
                store.markApplying(id, emptyManifest(), ByteArray(32), ByteArray(32), 0, 0)
            },
            expectedResult = false,
            expectedLifecycle = LifecycleState.READY,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "COMMITTED_UNVERIFIED",
            phase = RecoveryStoreFaultPort.Phase.COMMITTED_UNVERIFIED,
            setup = { createApplying(it) },
            action = { store, id ->
                store.advance(id, LifecycleState.COMMITTED_UNVERIFIED)
            },
            expectedResult = false,
            expectedLifecycle = LifecycleState.APPLYING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "VERIFIED",
            phase = RecoveryStoreFaultPort.Phase.VERIFIED,
            setup = { createCommittedUnverified(it) },
            action = { store, id -> store.advance(id, LifecycleState.VERIFIED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.COMMITTED_UNVERIFIED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "RESTORING",
            phase = RecoveryStoreFaultPort.Phase.RESTORING,
            setup = { createApplying(it) },
            action = { store, id ->
                store.markRestoring(id, emptyManifest(), ByteArray(32), ByteArray(32))
            },
            expectedResult = false,
            expectedLifecycle = LifecycleState.APPLYING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "RESTORED",
            phase = RecoveryStoreFaultPort.Phase.RESTORED,
            setup = { createRestoring(it) },
            action = { store, id -> store.advance(id, LifecycleState.RESTORED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.RESTORING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "CORRUPT",
            phase = RecoveryStoreFaultPort.Phase.CORRUPT,
            setup = { createCheckpointed(it) },
            action = { store, id -> store.advance(id, LifecycleState.CORRUPT) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.READY,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "EXPIRED",
            phase = RecoveryStoreFaultPort.Phase.EXPIRED,
            setup = { createVerified(it) },
            action = { store, id -> store.advance(id, LifecycleState.EXPIRED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.VERIFIED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "RETENTION_TOMBSTONE",
            phase = RecoveryStoreFaultPort.Phase.TOMBSTONE,
            setup = { createExpired(it) },
            action = { store, id -> store.runRetention(1_000L + 86_400_000L) },
            expectedResult = RecoveryStorePort.RetentionOutcome.Failed,
            expectedLifecycle = LifecycleState.EXPIRED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "PRUNE_TOMBSTONE",
            phase = RecoveryStoreFaultPort.Phase.TOMBSTONE,
            setup = { createCheckpointed(it) },
            action = { store, id -> store.pruneUnused(id) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.READY,
            expectsTombstone = false,
        ),
    )

    private fun postCommitCases(): List<FaultCase> = listOf(
        FaultCase(
            name = "CREATING",
            phase = RecoveryStoreFaultPort.Phase.CREATING,
            setup = { RecoveryPointId("aabbccdd00112233445566778899aabb") },
            action = { store, id ->
                val digest = ByteArray(32)
                val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
                store.checkpoint(
                    RecoveryStorePort.CheckpointPayload(
                        id, RunId("aabbccdd00112233445566778899aabb"),
                        empty, RevisionId("rev"), digest, digest, 0, 0,
                    ),
                )
            },
            expectedResult = RecoveryStorePort.CheckpointResult.CreateFailed,
            expectedLifecycle = LifecycleState.CREATING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "READY",
            phase = RecoveryStoreFaultPort.Phase.READY,
            setup = { RecoveryPointId("aabbccdd00112233445566778899aabb") },
            action = { store, id ->
                val digest = ByteArray(32)
                val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
                store.checkpoint(
                    RecoveryStorePort.CheckpointPayload(
                        id, RunId("aabbccdd00112233445566778899aabb"),
                        empty, RevisionId("rev"), digest, digest, 0, 0,
                    ),
                )
            },
            expectedResult = RecoveryStorePort.CheckpointResult.CreateFailed,
            expectedLifecycle = LifecycleState.READY,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "APPLYING",
            phase = RecoveryStoreFaultPort.Phase.APPLYING,
            setup = { createCheckpointed(it) },
            action = { store, id ->
                store.markApplying(id, emptyManifest(), ByteArray(32), ByteArray(32), 0, 0)
            },
            expectedResult = false,
            expectedLifecycle = LifecycleState.APPLYING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "COMMITTED_UNVERIFIED",
            phase = RecoveryStoreFaultPort.Phase.COMMITTED_UNVERIFIED,
            setup = { createApplying(it) },
            action = { store, id -> store.advance(id, LifecycleState.COMMITTED_UNVERIFIED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.COMMITTED_UNVERIFIED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "VERIFIED",
            phase = RecoveryStoreFaultPort.Phase.VERIFIED,
            setup = { createCommittedUnverified(it) },
            action = { store, id -> store.advance(id, LifecycleState.VERIFIED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.VERIFIED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "RESTORING",
            phase = RecoveryStoreFaultPort.Phase.RESTORING,
            setup = { createApplying(it) },
            action = { store, id ->
                store.markRestoring(id, emptyManifest(), ByteArray(32), ByteArray(32))
            },
            expectedResult = false,
            expectedLifecycle = LifecycleState.RESTORING,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "RESTORED",
            phase = RecoveryStoreFaultPort.Phase.RESTORED,
            setup = { createRestoring(it) },
            action = { store, id -> store.advance(id, LifecycleState.RESTORED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.RESTORED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "CORRUPT",
            phase = RecoveryStoreFaultPort.Phase.CORRUPT,
            setup = { createCheckpointed(it) },
            action = { store, id -> store.advance(id, LifecycleState.CORRUPT) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.CORRUPT,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "EXPIRED",
            phase = RecoveryStoreFaultPort.Phase.EXPIRED,
            setup = { createVerified(it) },
            action = { store, id -> store.advance(id, LifecycleState.EXPIRED) },
            expectedResult = false,
            expectedLifecycle = LifecycleState.EXPIRED,
            expectsTombstone = false,
        ),
        FaultCase(
            name = "RETENTION_TOMBSTONE",
            phase = RecoveryStoreFaultPort.Phase.TOMBSTONE,
            setup = { createExpired(it) },
            action = { store, id -> store.runRetention(1_000L + 86_400_000L) },
            expectedResult = RecoveryStorePort.RetentionOutcome.Failed,
            expectedLifecycle = null,
            expectsTombstone = true,
        ),
        FaultCase(
            name = "PRUNE_TOMBSTONE",
            phase = RecoveryStoreFaultPort.Phase.TOMBSTONE,
            setup = { createCheckpointed(it) },
            action = { store, id -> store.pruneUnused(id) },
            expectedResult = false,
            expectedLifecycle = null,
            expectsTombstone = true,
        ),
    )

    private data class FaultCase(
        val name: String,
        val phase: RecoveryStoreFaultPort.Phase,
        val setup: (RecoveryStore) -> RecoveryPointId,
        val action: (RecoveryStore, RecoveryPointId) -> Any,
        val expectedResult: Any,
        val expectedLifecycle: LifecycleState?,
        val expectsTombstone: Boolean,
    )

    private enum class FaultTiming { BEFORE, AFTER }

    private class ThrowingFaultPort(
        private val phase: RecoveryStoreFaultPort.Phase,
        private val timing: FaultTiming,
    ) : RecoveryStoreFaultPort by RecoveryStoreFaultPort.NOOP {
        override fun beforeCommit(phase: RecoveryStoreFaultPort.Phase, pointId: RecoveryPointId) {
            if (timing == FaultTiming.BEFORE && phase == this.phase) {
                throw TestFaultException("before ${phase.name} for $pointId")
            }
        }

        override fun afterCommit(phase: RecoveryStoreFaultPort.Phase, pointId: RecoveryPointId) {
            if (timing == FaultTiming.AFTER && phase == this.phase) {
                throw TestFaultException("after ${phase.name} for $pointId")
            }
        }
    }

    private class TestFaultException(message: String) : RuntimeException(message)

    private fun emptyManifest(): PersistenceManifest =
        PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)

    private fun createRestored(
        store: RecoveryStore,
        pointId: RecoveryPointId,
        index: Int,
    ): Long {
        val digest = ByteArray(32)
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = pointId,
                runId = RunId((index + 1).toString().padStart(32, 'b')),
                preManifest = emptyManifest(),
                preRevision = RevisionId("rev-$index"),
                preDigest = digest,
                applyActionDigest = ByteArray(32) { index.toByte() },
                itemCount = 0,
                resourceCount = 0,
            ),
        )
        check(result is RecoveryStorePort.CheckpointResult.Ready) { "setup checkpoint failed: $result" }
        check(store.advance(pointId, LifecycleState.APPLYING))
        check(store.markRestoring(pointId, emptyManifest(), digest, digest))
        check(store.advance(pointId, LifecycleState.RESTORED))
        val restored = (store.readRecord(pointId) as RecoveryStorePort.RecordRead.Readable).record
        return Math.addExact(restored.updatedAtMs, 86_400_000L)
    }

    private fun createCheckpointed(
        store: RecoveryStore,
        pointId: RecoveryPointId = RecoveryPointId("aabbccdd00112233445566778899aabb"),
        index: Int = 0,
    ): RecoveryPointId {
        val digest = ByteArray(32)
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId,
                RunId((index + 1).toString().padStart(32, 'b')),
                emptyManifest(),
                RevisionId("rev-$index"),
                digest,
                digest,
                0,
                0,
            ),
        )
        check(result is RecoveryStorePort.CheckpointResult.Ready) { "setup checkpoint failed: $result" }
        return pointId
    }

    private fun createApplying(store: RecoveryStore): RecoveryPointId {
        val pointId = createCheckpointed(store)
        check(store.markApplying(pointId, emptyManifest(), ByteArray(32), ByteArray(32), 0, 0))
        return pointId
    }

    private fun createApplying(store: RecoveryStore, pointId: RecoveryPointId, index: Int): RecoveryPointId {
        createCheckpointed(store, pointId, index)
        check(store.markApplying(pointId, emptyManifest(), ByteArray(32), ByteArray(32), 0, 0))
        return pointId
    }

    private fun forceLifecycle(
        store: RecoveryStore,
        context: Context,
        pointId: RecoveryPointId,
        lifecycle: LifecycleState,
        updatedAtMs: Long,
    ) {
        val current = (store.readRecord(pointId) as RecoveryStorePort.RecordRead.Readable).record
        val encoded = (current as RecoveryStore.StoredRecord).encoded
        val updated = encoded.copy(
            updatedAtMs = updatedAtMs,
            lifecycle = lifecycle,
            priorLifecycle = encoded.lifecycle,
        ).let { it.copy(payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(it)) }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL(
                "UPDATE recovery_points SET lifecycle = ?, prior_lifecycle = ?, updated_at_ms = ?, " +
                    "payload_checksum = ? WHERE point_id = ?",
                arrayOf<Any>(
                    updated.lifecycle.canonicalInt,
                    updated.priorLifecycle?.canonicalInt ?: -1,
                    updated.updatedAtMs,
                    updated.payloadChecksum,
                    pointId.value,
                ),
            )
        }
    }

    private fun createCommittedUnverified(store: RecoveryStore): RecoveryPointId {
        val pointId = createApplying(store)
        check(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        return pointId
    }

    private fun createVerified(store: RecoveryStore): RecoveryPointId {
        val pointId = createCommittedUnverified(store)
        check(store.advance(pointId, LifecycleState.VERIFIED))
        return pointId
    }

    private fun createRestoring(store: RecoveryStore): RecoveryPointId {
        val pointId = createApplying(store)
        check(store.markRestoring(pointId, emptyManifest(), ByteArray(32), ByteArray(32)))
        return pointId
    }

    private fun createExpired(store: RecoveryStore): RecoveryPointId {
        val pointId = createVerified(store)
        check(store.advance(pointId, LifecycleState.EXPIRED))
        return pointId
    }

    private fun createLegacyStoreWithPoint(context: Context, lifecycle: LifecycleState, index: Int) {
        createLegacyEmptyStore(context, tombstoneExpiresAt = null)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL(
                "INSERT INTO recovery_points (point_id, lifecycle) VALUES (?, ?)",
                arrayOf<Any>("legacy-${index.toString().padStart(2, '0')}", lifecycle.ordinal),
            )
        }
    }

    private fun createLegacyEmptyStore(context: Context, tombstoneExpiresAt: Long?) {
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE recovery_points (" +
                    "point_id TEXT PRIMARY KEY NOT NULL, lifecycle INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE recovery_tombstones (" +
                    "point_id TEXT PRIMARY KEY NOT NULL, reason INTEGER NOT NULL, " +
                    "format_version INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL)",
            )
            tombstoneExpiresAt?.let { expiresAt ->
                db.execSQL(
                    "INSERT INTO recovery_tombstones (point_id, reason, format_version, expires_at_ms) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>("legacy-tombstone", 1, 1, expiresAt),
                )
            }
            db.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun lifecycleOf(store: RecoveryStore, pointId: RecoveryPointId): LifecycleState? = (
        store.readRecord(pointId) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.lifecycle

    private fun chunkRowCountFor(context: Context, pointId: RecoveryPointId): Int = SQLiteDatabase
        .openDatabase(
            context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        .use { db ->
            android.database.DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} WHERE point_id = ?",
                arrayOf(pointId.value),
            ).toInt()
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

    private fun checkpointedStore(): Triple<Context, RecoveryStore, RecoveryPointId> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val store = RecoveryStore(context) { 1000L }
        prepareForMutation(store)
        val pointId = RecoveryPointId("fedcba0987654321fedcba0987654321")
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId, RunId("abcdef0987654321abcdef0987654321"),
                empty, RevisionId("rev"), digest, digest, 0, 0,
            ),
        )
        assertTrue(result is RecoveryStorePort.CheckpointResult.Ready)
        return Triple(context, store, pointId)
    }
}
