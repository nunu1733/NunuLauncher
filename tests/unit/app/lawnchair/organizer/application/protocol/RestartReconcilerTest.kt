package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.ReconciliationPublicResult
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RestartReconcilerTest {
    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var reconciler: RestartReconciler
    private lateinit var mutex: RunMutex
    private lateinit var session: RecoveryStoreReconciliationSession
    private val reconciliationRunId = RunId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val pointId = RecoveryPointId("22222222222222222222222222222222")

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore(FakeClock::nowMillis)
        mutex = RunMutex()
        assertTrue(mutex.tryAcquire(reconciliationRunId))
        val lease = requireNotNull(mutex.issueReconciliationLease(reconciliationRunId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        session = requireNotNull(issuer.openSession(lease))
        reconciler = RestartReconciler(writer, RecordingFaultInjector())
    }

    @After
    fun tearDown() {
        session.close()
        mutex.release(reconciliationRunId)
    }

    @Test
    fun readyCheckpointAtPreStateIsActuallyPruned() {
        seedReady()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, reconciler.reconcileAll(session))
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
    }

    @Test
    fun restoringAtRecoveryTargetReloadsVerifiesAndMarksRestored() {
        val capture = seedReady()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        assertTrue(store.markRestoring(pointId, capture.manifest, capture.digest, ByteArray(32)))

        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        assertEquals(LifecycleState.RESTORED, storedLifecycleOf(pointId))
        assertEquals(1, writer.reloadCount)
    }

    // Issue #152 (AC-152-03): restart reconciliation must verify the model leg
    // before resuming Restored. The DB recapture matches the checkpoint
    // pre-state; the divergent snapshot of the reconciliation reload must fail
    // closed and keep the record in RESTORING.

    @Test
    fun restoringWithDivergentModelSnapshotIsNotFalseSuccess() {
        val capture = seedReady()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        assertTrue(store.markRestoring(pointId, capture.manifest, capture.digest, ByteArray(32)))
        writer.modelSnapshotTransform = { snapshot -> snapshot.copy(items = snapshot.items.dropLast(1)) }

        val summary = reconciler.reconcileAll(session)

        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        assertEquals(
            "A model-divergent reconciliation must never mark RESTORED",
            LifecycleState.RESTORING,
            storedLifecycleOf(pointId),
        )
    }

    private fun storedLifecycleOf(id: RecoveryPointId): LifecycleState? = (
        store.readRecord(id) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.lifecycle

    private fun seedReady(): CapturedSnapshot {
        val capture = writer.captureCurrent(CaptureId("restart"))
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId,
                RunId("11111111111111111111111111111111"),
                capture.manifest,
                capture.revision,
                capture.digest,
                ByteArray(32),
                capture.manifest.rowCount,
                capture.manifest.resources.size,
            ),
        )
        assertTrue(result is RecoveryStorePort.CheckpointResult.Ready)
        return capture
    }

    @Test
    fun creatingAtPreStateWithAdvanceFailureSurfacesUnresolvedNotClean() {
        seedReady()
        store.advance(pointId, LifecycleState.READY)
        store.advance(pointId, LifecycleState.APPLYING)
        store.advanceFails = true
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        assertTrue(summary.hasUnresolvedFailures())
    }

    @Test
    fun readyAtPreStateWithPruneFailureSurfacesUnresolvedNotClean() {
        seedReady()
        store.pruneUnusedFails = true
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        assertTrue(summary.hasUnresolvedFailures())
    }

    @Test
    fun applyingAtPreStateWithAdvanceFailureSurfacesUnresolvedNotClean() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advanceFails = true
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        assertTrue(summary.hasUnresolvedFailures())
    }

    @Test
    fun committedUnverifiedAtPreStateAdvancesToReadyAndPrunes() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        val results = (summary as RestartReconciler.ReconciliationSummary.Resolved).publicResults
        assertTrue(results.any { it is ReconciliationPublicResult.ResumeApply })
        val applyResult = results.filterIsInstance<ReconciliationPublicResult.ResumeApply>().first().outcome
        assertTrue(applyResult is ApplyResult.RolledBack)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
    }

    @Test
    fun committedUnverifiedAtPreStateIsIdempotentOnRepeatedRestart() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)
        val firstSummary = reconciler.reconcileAll(session)
        assertTrue(firstSummary is RestartReconciler.ReconciliationSummary.Resolved)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
        val secondSummary = reconciler.reconcileAll(session)
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, secondSummary)
    }

    @Test
    fun committedUnverifiedAtPreStateWithAdvanceFailureSurfacesUnresolved() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)
        store.advanceFails = true
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        assertTrue(summary.hasUnresolvedFailures())
        assertEquals(LifecycleState.COMMITTED_UNVERIFIED, storedLifecycleOf(pointId))
    }

    @Test
    fun committedUnverifiedAtPreStateWithPruneFailureSurfacesUnresolved() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)
        store.pruneUnusedFails = true
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary.hasUnresolvedFailures())
        assertEquals(LifecycleState.READY, storedLifecycleOf(pointId))
    }

    @Test
    fun retentionFailureKeepsSummaryFailed() {
        store.retentionOutcome = RecoveryStorePort.RetentionOutcome.Failed
        assertEquals(RestartReconciler.ReconciliationSummary.Failed, reconciler.reconcileAll(session))
    }

    private fun seedUnreadableRecord(
        id: RecoveryPointId,
        recordRunId: RunId,
        lifecycle: LifecycleState,
        createdAtMs: Long = FakeClock.nowMillis(),
    ) {
        store.seedRecord(
            object : RecoveryStorePort.StoredRecord {
                override val pointId: RecoveryPointId = id
                override val runId: RunId = recordRunId
                override val lifecycle: LifecycleState = lifecycle
                override val priorLifecycle: LifecycleState? = null
                override val createdAtMs: Long = createdAtMs
                override val updatedAtMs: Long = createdAtMs
                override val preManifest: app.lawnchair.organizer.application.canonical.PersistenceManifest =
                    app.lawnchair.organizer.application.canonical.PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
                override val preRevision: app.lawnchair.organizer.planning.RevisionId =
                    app.lawnchair.organizer.planning.RevisionId("rev")
                override val preDigest: ByteArray = ByteArray(32)
                override val intendedManifest: app.lawnchair.organizer.application.canonical.PersistenceManifest =
                    app.lawnchair.organizer.application.canonical.PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
                override val intendedDigest: ByteArray = ByteArray(32)
                override val applyActionDigest: ByteArray = ByteArray(32)
                override val reviewedManifest: app.lawnchair.organizer.application.canonical.PersistenceManifest? = null
                override val reviewedDigest: ByteArray? = null
                override val recoveryActionDigest: ByteArray? = null
                override val itemCount: Int = 0
                override val resourceCount: Int = 0
                override val checksumValid: Boolean = true
                override val formatVersion: Int = 2
            },
        )
        store.unreadablePointIds.add(id.value)
    }

    @Test
    fun unreadableCreatingRecordIsQuarantinedWhileHealthyRecordStillReconciles() {
        seedReady()
        val unreadableId = RecoveryPointId("33333333333333333333333333333333")
        seedUnreadableRecord(unreadableId, RunId("44444444444444444444444444444444"), LifecycleState.CREATING)

        val summary = reconciler.reconcileAll(session)

        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertFalse(summary.hasUnresolvedFailures())
        assertEquals(1, store.quarantineCalls)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(unreadableId))
        val tombstone = store.readTombstone(unreadableId)
        assertEquals(RecoveryStorePort.TombstoneReason.QUARANTINED, tombstone?.reason)
    }

    @Test
    fun unreadableReadyRecordIsQuarantined() {
        val unreadableId = RecoveryPointId("33333333333333333333333333333333")
        seedUnreadableRecord(unreadableId, RunId("44444444444444444444444444444444"), LifecycleState.READY)

        val summary = reconciler.reconcileAll(session)

        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertEquals(1, store.quarantineCalls)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(unreadableId))
        assertEquals(RecoveryStorePort.TombstoneReason.QUARANTINED, store.readTombstone(unreadableId)?.reason)
    }

    @Test
    fun unreadableActiveRecordsArePreservedAndKeepTheSummaryUnresolved() {
        val lifecycles = listOf(
            LifecycleState.APPLYING,
            LifecycleState.COMMITTED_UNVERIFIED,
            LifecycleState.RESTORING,
        )
        lifecycles.forEachIndexed { index, lifecycle ->
            val unreadableId = RecoveryPointId("3333333333333333333333333333333$index")
            seedUnreadableRecord(unreadableId, RunId("4444444444444444444444444444444$index"), lifecycle)

            val summary = reconciler.reconcileAll(session)

            assertTrue(
                "$lifecycle must surface unresolved, got $summary",
                summary is RestartReconciler.ReconciliationSummary.Resolved,
            )
            assertTrue("$lifecycle must keep the aggregate fail-closed", summary.hasUnresolvedFailures())
            assertEquals("$lifecycle must not be deleted", 0, store.quarantineCalls)
            assertTrue(
                "$lifecycle record must be preserved",
                store.readRecord(unreadableId) is RecoveryStorePort.RecordRead.Unreadable,
            )
            store.clear()
        }
    }

    @Test
    fun unreadableVerifiedRecordRemainsStoredAndReconcilesSilently() {
        val unreadableId = RecoveryPointId("33333333333333333333333333333333")
        seedUnreadableRecord(unreadableId, RunId("44444444444444444444444444444444"), LifecycleState.VERIFIED)

        val summary = reconciler.reconcileAll(session)

        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertFalse(summary.hasUnresolvedFailures())
        assertEquals(0, store.quarantineCalls)
        assertTrue(store.readRecord(unreadableId) is RecoveryStorePort.RecordRead.Unreadable)
    }

    @Test
    fun malformedMetadataIsPreservedAndFailsThePass() {
        seedReady()
        store.malformedPointIds.add(pointId.value)

        val summary = reconciler.reconcileAll(session)

        assertEquals(RestartReconciler.ReconciliationSummary.Failed, summary)
        assertEquals(0, store.quarantineCalls)
        assertTrue(
            "Malformed row must be preserved, never speculatively deleted",
            store.readRecord(pointId) is RecoveryStorePort.RecordRead.Readable,
        )
    }
}
