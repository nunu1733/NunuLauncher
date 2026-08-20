package com.android.launcher3.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.application.store.RecoveryStoreFaultPort
import app.lawnchair.organizer.planning.RevisionId
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
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
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(RecoveryDbSchema.FILE_NAME)
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
        val record = reopened.readRecord(pointId)
        assertNotNull(record)
        assertEquals(LifecycleState.READY, record?.lifecycle)
        assertEquals(RevisionId("revision"), record?.preRevision)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun newerRecoveryDatabaseIsRejectedWithoutWriteOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA user_version = 2")
        }
        val before = file.readBytes()
        val store = RecoveryStore(context) { 1_000L }
        assertEquals(
            RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION,
            store.availability(),
        )
        assertTrue(store.listNonFinalRecords().isEmpty())
        assertTrue(before.contentEquals(file.readBytes()))
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

    // --- Finding 5b: reopen per lifecycle transition ---

    @Test
    fun applyingSurvivesProductionCloseAndReopen() {
        val (context, _, pointId) = checkpointedStore()
        // The store is already closed by the helper inside checkpointedStore();
        // open a new one to verify the persisted APPLYING state.
        val reopened = RecoveryStore(context) { 3000L }
        assertTrue(reopened.advance(pointId, LifecycleState.APPLYING))
        val rechecked = RecoveryStore(context) { 4000L }
        assertEquals(LifecycleState.APPLYING, rechecked.readRecord(pointId)?.lifecycle)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun committedUnverifiedSurvivesProductionCloseAndReopen() {
        val (context, store, pointId) = checkpointedStore()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.COMMITTED_UNVERIFIED, reopened.readRecord(pointId)?.lifecycle)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun verifiedSurvivesProductionCloseAndReopen() {
        val (context, store, pointId) = checkpointedStore()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.VERIFIED, reopened.readRecord(pointId)?.lifecycle)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun restoringSurvivesProductionCloseAndReopen() {
        val (context, _, pointId) = checkpointedStore()
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        // Need to first advance to a state that allows RESTORING.
        val store = RecoveryStore(context) { 2000L }
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.markRestoring(pointId, empty, digest, digest))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.RESTORING, reopened.readRecord(pointId)?.lifecycle)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    @Test
    fun restoredSurvivesProductionCloseAndReopen() {
        val (context, _, pointId) = checkpointedStore()
        val digest = ByteArray(32)
        val empty = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        val store = RecoveryStore(context) { 2000L }
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.markRestoring(pointId, empty, digest, digest))
        assertTrue(store.advance(pointId, LifecycleState.RESTORED))
        val reopened = RecoveryStore(context) { 3000L }
        assertEquals(LifecycleState.RESTORED, reopened.readRecord(pointId)?.lifecycle)
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
    }

    // --- AC-14: typed pre/post-commit durability coverage ---

    @Test
    fun preCommitFailuresReturnTypedResultAndLeaveDurableStateUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (case in preCommitCases()) {
            context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
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
                reopened.readRecord(pointId)?.lifecycle,
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
            context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
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
                reopened.readRecord(pointId)?.lifecycle,
            )
            if (case.expectsTombstone) {
                assertNotNull("${case.name} tombstone", reopened.readTombstone(pointId))
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

    private fun createCheckpointed(store: RecoveryStore): RecoveryPointId {
        val pointId = RecoveryPointId("aabbccdd00112233445566778899aabb")
        val digest = ByteArray(32)
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId,
                RunId("aabbccdd00112233445566778899aabb"),
                emptyManifest(),
                RevisionId("rev"),
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

    private fun prepareForMutation(store: RecoveryStore) {
        assertTrue(store.withReconciliationScope { store.rebuildInspectionSnapshotForReconciliation() })
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
