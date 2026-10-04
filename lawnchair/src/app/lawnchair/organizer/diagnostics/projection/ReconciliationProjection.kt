package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.AuthoritativeClass
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.ReconciliationClassification
import app.lawnchair.organizer.diagnostics.model.ReconciliationContext
import app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle
import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Projection from restart reconciliation results into [RunEvent].
 */
object ReconciliationProjection {

    /**
     * Project a RESTART_RECONCILED event.
     *
     * @param subjectRunId the run ID being reconciled.
     * @param priorLifecycle the lifecycle state before reconciliation.
     * @param classification the authoritative digest classification.
     * @param resultingLifecycle the lifecycle state after reconciliation.
     * @param journalSequence the next journal sequence number.
     * @param pointId optional recovery point ID for the reconciled record.
     *   When set, allows the retention policy to resolve in-flight recovery
     *   protection for this point (RESTART_RECONCILED resolves the recovery
     *   state without a separate terminal RECOVERY_* event).
     * @return the projected [RunEvent].
     */
    @JvmStatic
    fun project(
        subjectRunId: RunId,
        priorLifecycle: LifecycleState,
        classification: AuthoritativeClass,
        resultingLifecycle: LifecycleState,
        journalSequence: Long,
        pointId: String? = null,
    ): RunEvent {
        return RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.RESTART_RECONCILED,
            runId = subjectRunId.value,
            pointId = pointId,
            reconciliation = ReconciliationContext(
                subjectRunId = subjectRunId.value,
                priorLifecycle = mapLifecycle(priorLifecycle),
                classification = mapClassification(classification),
                resultingLifecycle = mapLifecycle(resultingLifecycle),
            ),
        )
    }

    /**
     * Map an application [LifecycleState] to a diagnostics [RecoveryLifecycle].
     */
    @JvmStatic
    fun mapLifecycle(state: LifecycleState): RecoveryLifecycle = when (state) {
        LifecycleState.CREATING -> RecoveryLifecycle.CREATING
        LifecycleState.READY -> RecoveryLifecycle.READY
        LifecycleState.APPLYING -> RecoveryLifecycle.APPLYING
        LifecycleState.COMMITTED_UNVERIFIED -> RecoveryLifecycle.COMMITTED_UNVERIFIED
        LifecycleState.VERIFIED -> RecoveryLifecycle.VERIFIED
        LifecycleState.RESTORING -> RecoveryLifecycle.RESTORING
        LifecycleState.RESTORED -> RecoveryLifecycle.RESTORED
        LifecycleState.CORRUPT -> RecoveryLifecycle.CORRUPT
        LifecycleState.EXPIRED -> RecoveryLifecycle.EXPIRED
        LifecycleState.INCOMPATIBLE -> RecoveryLifecycle.INCOMPATIBLE
    }

    /**
     * Map an [AuthoritativeClass] to a [ReconciliationClassification].
     */
    @JvmStatic
    fun mapClassification(authClass: AuthoritativeClass): ReconciliationClassification = when (authClass) {
        AuthoritativeClass.PRE_STATE -> ReconciliationClassification.PRE_STATE
        AuthoritativeClass.INTENDED_POST_STATE -> ReconciliationClassification.INTENDED_POST_STATE
        AuthoritativeClass.RECOVERY_TARGET -> ReconciliationClassification.RECOVERY_TARGET_STATE
        AuthoritativeClass.REVIEWED_CURRENT_STATE -> ReconciliationClassification.NEITHER_RECOGNIZED
        AuthoritativeClass.NEITHER -> ReconciliationClassification.NEITHER_RECOGNIZED
    }
}
