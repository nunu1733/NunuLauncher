package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.RecordingFaultInjector
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RestartReconciler
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle
import app.lawnchair.organizer.diagnostics.model.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AC-67-04: RestartReconciler must leave a RESTART_RECONCILED event for every
 * reconciled record — including silent advance/prune outcomes — with the
 * actual post-reconciliation lifecycle, not a pre-reconciliation fallback.
 */
class RestartReconcilerDiagnosticsTest {
    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var recordedEvents: MutableList<RunEvent>
    private lateinit var reconciler: RestartReconciler
    private val pointId = RecoveryPointId("33333333333333333333333333333333")

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore(FakeClock::nowMillis)
        recordedEvents = mutableListOf()
        val port = DiagnosticsPort { event -> recordedEvents.add(event) }
        reconciler = RestartReconciler(writer, store, RecordingFaultInjector(), port)
    }

    @Test
    fun silentPruneEmitsRestartReconciledWithPostReconciliationLifecycle() {
        seedReady()
        val summary = reconciler.reconcileAll()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)

        val event = recordedEvents.singleOrNull()
        assertNotNull("Silent prune must still emit RESTART_RECONCILED", event)
        assertEquals(PhaseCode.RESTART_RECONCILED, event!!.phase)
        assertEquals(
            "Resulting lifecycle must be the post-reconciliation state (READY), not the pre-reconciliation record state",
            RecoveryLifecycle.READY,
            event.reconciliation?.resultingLifecycle,
        )
    }

    @Test
    fun silentAdvanceEmitsRestartReconciledWithPostReconciliationLifecycle() {
        seedReady()
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        val summary = reconciler.reconcileAll()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)

        val event = recordedEvents.singleOrNull()
        assertNotNull("Silent advance must still emit RESTART_RECONCILED", event)
        assertEquals(PhaseCode.RESTART_RECONCILED, event!!.phase)
        assertEquals(
            "Resulting lifecycle must be the advanced state (VERIFIED), not an earlier pre-reconciliation state",
            RecoveryLifecycle.VERIFIED,
            event.reconciliation?.resultingLifecycle,
        )
    }

    private fun seedReady(): CapturedSnapshot {
        val capture = writer.captureCurrent(CaptureId("restart-diagnostics"))
        val result = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId,
                RunId("44444444444444444444444444444444"),
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
}
