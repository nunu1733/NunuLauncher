package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
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
}
