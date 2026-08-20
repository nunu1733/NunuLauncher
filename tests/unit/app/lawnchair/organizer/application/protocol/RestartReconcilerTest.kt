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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
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
        assertNull(store.readRecord(pointId))
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
        assertEquals(LifecycleState.RESTORED, store.readRecord(pointId)?.lifecycle)
        assertEquals(1, writer.reloadCount)
    }

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
        assertNull(store.readRecord(pointId))
    }

    @Test
    fun committedUnverifiedAtPreStateIsIdempotentOnRepeatedRestart() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)
        val firstSummary = reconciler.reconcileAll(session)
        assertTrue(firstSummary is RestartReconciler.ReconciliationSummary.Resolved)
        assertNull(store.readRecord(pointId))
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
        assertEquals(LifecycleState.COMMITTED_UNVERIFIED, store.readRecord(pointId)?.lifecycle)
    }

    @Test
    fun committedUnverifiedAtPreStateWithPruneFailureSurfacesUnresolved() {
        seedReady()
        store.advance(pointId, LifecycleState.APPLYING)
        store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)
        store.pruneUnusedFails = true
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary.hasUnresolvedFailures())
        assertEquals(LifecycleState.READY, store.readRecord(pointId)?.lifecycle)
    }

    @Test
    fun retentionFailureKeepsSummaryFailed() {
        store.retentionOutcome = RecoveryStorePort.RetentionOutcome.Failed
        assertEquals(RestartReconciler.ReconciliationSummary.Failed, reconciler.reconcileAll(session))
    }
}
