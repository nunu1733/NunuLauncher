package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #377 auxiliary oracle: direct unit contract of the single
 * `ReconciliationDecisionTable` (path context × lifecycle × AuthoritativeClass
 * cells). The primary pre/post-refactor evidence is the production-seam
 * characterization in `ReconciliationDecisionMatrixTest`; this file only pins
 * the table's own cells, including the rule-(b) cross-path rows.
 */
class ReconciliationDecisionTableContractTest {

    @Test
    fun restartRowsMatchAdjudicatedMatrix() {
        // Prune rows.
        for (lifecycle in listOf(
            LifecycleState.CREATING,
            LifecycleState.READY,
            LifecycleState.APPLYING,
        )) {
            val d = ReconciliationDecisionTable.decideRestart(lifecycle, AuthoritativeClass.PRE_STATE)
            assertEquals("$lifecycle × PRE_STATE", ReconciliationDecision.Surface.SILENT, d.surface)
            assertEquals(true, d.pruneRecord)
        }
        // READY × non-PRE_STATE: keep lifecycle, fail-closed unresolved.
        val ready = ReconciliationDecisionTable.decideRestart(LifecycleState.READY, AuthoritativeClass.INTENDED_POST_STATE)
        assertEquals(LifecycleState.READY, ready.nextLifecycle)
        assertEquals(ReconciliationDecision.Surface.UNRESOLVED_KEEP, ready.surface)
        // APPLYING / COMMITTED_UNVERIFIED × INTENDED_POST: continue committed.
        assertEquals(
            ReconciliationDecision.Surface.CONTINUE_COMMITTED_APPLY,
            ReconciliationDecisionTable.decideRestart(LifecycleState.APPLYING, AuthoritativeClass.INTENDED_POST_STATE).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.CONTINUE_COMMITTED_APPLY,
            ReconciliationDecisionTable.decideRestart(LifecycleState.COMMITTED_UNVERIFIED, AuthoritativeClass.INTENDED_POST_STATE).surface,
        )
        // COMMITTED_UNVERIFIED × PRE_STATE: rolled back + prune.
        val rolledBack = ReconciliationDecisionTable.decideRestart(LifecycleState.COMMITTED_UNVERIFIED, AuthoritativeClass.PRE_STATE)
        assertEquals(ReconciliationDecision.Surface.ROLLED_BACK_RESTART, rolledBack.surface)
        assertEquals(true, rolledBack.pruneRecord)
        // Recovery rows.
        assertEquals(
            ReconciliationDecision.Surface.COMPLETE_RESTORE,
            ReconciliationDecisionTable.decideRestart(LifecycleState.RESTORING, AuthoritativeClass.RECOVERY_TARGET).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.UNRESOLVED_RESTART,
            ReconciliationDecisionTable.decideRestart(LifecycleState.RESTORING, AuthoritativeClass.REVIEWED_CURRENT_STATE).surface,
        )
        // VERIFIED: silent advance.
        assertEquals(
            ReconciliationDecision.Surface.SILENT,
            ReconciliationDecisionTable.decideRestart(LifecycleState.VERIFIED, AuthoritativeClass.PRE_STATE).surface,
        )
    }

    @Test
    fun inFlightRowsMatchAdjudicatedMatrix() {
        assertEquals(
            ReconciliationDecision.Surface.ROLLED_BACK_APPLY,
            ReconciliationDecisionTable.decideInFlightApply(AuthoritativeClass.PRE_STATE).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.CONTINUE_COMMITTED_APPLY,
            ReconciliationDecisionTable.decideInFlightApply(AuthoritativeClass.INTENDED_POST_STATE).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.ATTEMPT_RECOVERY,
            ReconciliationDecisionTable.decideInFlightApply(AuthoritativeClass.NEITHER).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.COMPLETE_RESTORE,
            ReconciliationDecisionTable.decideInFlightRecovery(AuthoritativeClass.RECOVERY_TARGET).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.RESTORE_NOT_COMMITTED,
            ReconciliationDecisionTable.decideInFlightRecovery(AuthoritativeClass.REVIEWED_CURRENT_STATE).surface,
        )
    }

    @Test
    fun ruleBCrossPathRowsDifferByPathContext() {
        // APPLYING × PRE_STATE: restart prunes silently; in-flight apply rolls back.
        assertEquals(
            ReconciliationDecision.Surface.SILENT,
            ReconciliationDecisionTable.decideRestart(LifecycleState.APPLYING, AuthoritativeClass.PRE_STATE).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.ROLLED_BACK_APPLY,
            ReconciliationDecisionTable.decideInFlightApply(AuthoritativeClass.PRE_STATE).surface,
        )
        // RESTORING × REVIEWED_CURRENT: restart resumes recovery; in-flight recovery
        // surfaces the typed failure.
        assertEquals(
            ReconciliationDecision.Surface.UNRESOLVED_RESTART,
            ReconciliationDecisionTable.decideRestart(LifecycleState.RESTORING, AuthoritativeClass.REVIEWED_CURRENT_STATE).surface,
        )
        assertEquals(
            ReconciliationDecision.Surface.RESTORE_NOT_COMMITTED,
            ReconciliationDecisionTable.decideInFlightRecovery(AuthoritativeClass.REVIEWED_CURRENT_STATE).surface,
        )
    }
}
