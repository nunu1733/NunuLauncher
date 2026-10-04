package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.AuthoritativeClass
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.ReconciliationClassification
import app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AC-67-03, D-07: Restart reconciliation projection tests.
 */
class ReconciliationProjectionTest {

    private val runId = RunId("ab311234567890abcdef1234567890ab")

    @Test
    fun reconciledProjection() {
        val event = ReconciliationProjection.project(
            subjectRunId = runId,
            priorLifecycle = LifecycleState.COMMITTED_UNVERIFIED,
            classification = AuthoritativeClass.INTENDED_POST_STATE,
            resultingLifecycle = LifecycleState.VERIFIED,
            journalSequence = 1L,
        )
        assertEquals(PhaseCode.RESTART_RECONCILED, event.phase)
        assertEquals(runId.value, event.runId)
        assertNotNull(event.reconciliation)
        assertEquals(runId.value, event.reconciliation?.subjectRunId)
        assertEquals(RecoveryLifecycle.COMMITTED_UNVERIFIED, event.reconciliation?.priorLifecycle)
        assertEquals(ReconciliationClassification.INTENDED_POST_STATE, event.reconciliation?.classification)
        assertEquals(RecoveryLifecycle.VERIFIED, event.reconciliation?.resultingLifecycle)
    }

    @Test
    fun mapLifecycle() {
        assertEquals(RecoveryLifecycle.CREATING, ReconciliationProjection.mapLifecycle(LifecycleState.CREATING))
        assertEquals(RecoveryLifecycle.READY, ReconciliationProjection.mapLifecycle(LifecycleState.READY))
        assertEquals(RecoveryLifecycle.APPLYING, ReconciliationProjection.mapLifecycle(LifecycleState.APPLYING))
        assertEquals(RecoveryLifecycle.COMMITTED_UNVERIFIED, ReconciliationProjection.mapLifecycle(LifecycleState.COMMITTED_UNVERIFIED))
        assertEquals(RecoveryLifecycle.VERIFIED, ReconciliationProjection.mapLifecycle(LifecycleState.VERIFIED))
        assertEquals(RecoveryLifecycle.RESTORING, ReconciliationProjection.mapLifecycle(LifecycleState.RESTORING))
        assertEquals(RecoveryLifecycle.RESTORED, ReconciliationProjection.mapLifecycle(LifecycleState.RESTORED))
        assertEquals(RecoveryLifecycle.CORRUPT, ReconciliationProjection.mapLifecycle(LifecycleState.CORRUPT))
        assertEquals(RecoveryLifecycle.EXPIRED, ReconciliationProjection.mapLifecycle(LifecycleState.EXPIRED))
        assertEquals(RecoveryLifecycle.INCOMPATIBLE, ReconciliationProjection.mapLifecycle(LifecycleState.INCOMPATIBLE))
    }

    @Test
    fun mapClassification() {
        assertEquals(ReconciliationClassification.PRE_STATE, ReconciliationProjection.mapClassification(AuthoritativeClass.PRE_STATE))
        assertEquals(ReconciliationClassification.INTENDED_POST_STATE, ReconciliationProjection.mapClassification(AuthoritativeClass.INTENDED_POST_STATE))
        assertEquals(ReconciliationClassification.RECOVERY_TARGET_STATE, ReconciliationProjection.mapClassification(AuthoritativeClass.RECOVERY_TARGET))
        assertEquals(ReconciliationClassification.NEITHER_RECOGNIZED, ReconciliationProjection.mapClassification(AuthoritativeClass.REVIEWED_CURRENT_STATE))
        assertEquals(ReconciliationClassification.NEITHER_RECOGNIZED, ReconciliationProjection.mapClassification(AuthoritativeClass.NEITHER))
    }
}
