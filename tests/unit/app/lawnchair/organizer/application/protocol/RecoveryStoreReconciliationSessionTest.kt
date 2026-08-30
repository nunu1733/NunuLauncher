package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryStoreReconciliationSessionTest {
    private val runId = RunId("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
    private val pointId = RecoveryPointId("ffffffffffffffffffffffffffffffff")

    @Test
    fun sessionRejectsCallsAfterCloseOrLeaseRelease() {
        val store = FakeRecoveryStore()
        val mutex = RunMutex()
        assertTrue(mutex.tryAcquire(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val session = requireNotNull(issuer.openSession(lease))

        session.close()
        assertFalse(session.isActive())
        assertFalse(session.advance(pointId, LifecycleState.CORRUPT))
        assertFalse(session.rebuildInspectionSnapshot())

        val active = requireNotNull(issuer.openSession(lease))
        mutex.release(runId)
        assertFalse(active.isActive())
        assertFalse(active.advance(pointId, LifecycleState.CORRUPT))
    }

    @Test
    fun storeRejectsForeignMutexIssuerAndInactiveLease() {
        val store = FakeRecoveryStore()
        val owner = RunMutex()
        val foreign = RunMutex()
        assertTrue(owner.tryAcquire(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(owner))
        assertNull(store.bindReconciliationIssuer(foreign))

        val lease = requireNotNull(owner.issueReconciliationLease(runId))
        assertTrue(requireNotNull(issuer.openSession(lease)).isActive())
        owner.release(runId)
        assertNull(issuer.openSession(lease))
    }

    @Test
    fun quarantineRequiresAProvenNoMutationLifecycleAndRechecksIt() {
        val store = FakeRecoveryStore()
        val mutex = RunMutex()
        assertTrue(mutex.tryAcquire(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val session = requireNotNull(issuer.openSession(lease))
        try {
            val empty = app.lawnchair.organizer.application.canonical.PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
            val checkpointed = store.checkpoint(
                RecoveryStorePort.CheckpointPayload(
                    pointId,
                    runId,
                    empty,
                    app.lawnchair.organizer.planning.RevisionId("rev"),
                    ByteArray(32),
                    ByteArray(32),
                    0,
                    0,
                ),
            )
            assertTrue(checkpointed is RecoveryStorePort.CheckpointResult.Ready)

            // Active lifecycles are never quarantine candidates and write no tombstone.
            assertFalse(session.quarantineUnmutated(pointId, LifecycleState.APPLYING))
            assertFalse(session.quarantineUnmutated(pointId, LifecycleState.VERIFIED))
            assertNull(store.readTombstone(pointId))

            // Expected-lifecycle recheck: a READY record cannot be quarantined as CREATING.
            assertFalse(session.quarantineUnmutated(pointId, LifecycleState.CREATING))
            assertNull(store.readTombstone(pointId))

            // Proven no-mutation lifecycle: READY quarantines with the typed tombstone.
            assertTrue(session.quarantineUnmutated(pointId, LifecycleState.READY))
            assertEquals(1, store.quarantineCalls)
            assertEquals(
                RecoveryStorePort.RecordRead.Missing,
                store.readRecord(pointId),
            )
            assertEquals(
                RecoveryStorePort.TombstoneReason.QUARANTINED,
                store.readTombstone(pointId)?.reason,
            )

            // Unknown point is refused.
            assertFalse(
                session.quarantineUnmutated(
                    RecoveryPointId("99999999999999999999999999999999"),
                    LifecycleState.READY,
                ),
            )
        } finally {
            session.close()
            mutex.release(runId)
        }
    }
}
