package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.ApplySummary
import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Projection from [ApplyResult] into [RunEvent].
 *
 * Every variant of [ApplyResult] is handled explicitly.
 */
object ApplyProjection {

    /**
     * Project an [ApplyResult] into a [RunEvent].
     *
     * @param result the apply result from the protocol.
     * @param journalSequence the next journal sequence number.
     * @param applyStage the apply stage at which this result was produced.
     * @param applySummary optional summary counts for APPLY_VERIFIED.
     * @return the projected [RunEvent].
     */
    @JvmStatic
    fun project(
        result: ApplyResult,
        journalSequence: Long,
        applyStage: ApplyStage? = null,
        applySummary: ApplySummary? = null,
        pointId: String? = null,
    ): RunEvent {
        val base = RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.RUN_STARTED, // placeholder, overridden by copy()
            runId = runIdFromResult(result),
            pointId = pointId ?: pointIdFromResult(result),
        )

        return when (result) {
            is ApplyResult.NoChanges -> base.copy(
                phase = PhaseCode.APPLY_NO_CHANGES,
            )

            is ApplyResult.Applied -> base.copy(
                phase = PhaseCode.APPLY_VERIFIED,
                applyStage = applyStage ?: ApplyStage.A8,
                applySummary = applySummary,
            )

            is ApplyResult.Rejected -> projectRejected(base, result, applyStage)

            is ApplyResult.RolledBack -> projectRolledBack(base, result, applyStage)

            is ApplyResult.Recovered -> base.copy(
                phase = PhaseCode.APPLY_RECOVERED,
                applyStage = applyStage,
                error = ErrorEntry(
                    family = ErrorFamily.APPLY_FAILURE,
                    code = result.failure.name,
                ),
            )

            is ApplyResult.Unresolved -> base.copy(
                phase = PhaseCode.APPLY_UNRESOLVED,
                applyStage = applyStage,
                error = ErrorEntry(
                    family = ErrorFamily.APPLY_FAILURE,
                    code = result.failure.name,
                ),
            )

            is ApplyResult.RecoveryFailed -> base.copy(
                phase = PhaseCode.APPLY_RECOVERY_FAILED,
                applyStage = applyStage,
                error = ErrorEntry(
                    family = ErrorFamily.APPLY_FAILURE,
                    code = result.failure.name,
                ),
            )

            is ApplyResult.ConcurrentRun -> base.copy(
                phase = PhaseCode.CONCURRENT_RUN_REJECTED,
                error = ErrorEntry(
                    family = ErrorFamily.CONCURRENT,
                    code = "CONCURRENT_RUN",
                ),
            )
        }
    }

    private fun projectRejected(
        base: RunEvent,
        rejected: ApplyResult.Rejected,
        applyStage: ApplyStage?,
    ): RunEvent {
        val (phase, errorFamily) = when (rejected.reason) {
            PreWriteRejection.CHECKPOINT_CREATE_FAILED,
            PreWriteRejection.CHECKPOINT_VALIDATE_FAILED,
            -> PhaseCode.CHECKPOINT_REJECTED to ErrorFamily.PRE_WRITE_REJECTED

            else -> PhaseCode.APPLY_REJECTED to ErrorFamily.PRE_WRITE_REJECTED
        }
        return base.copy(
            phase = phase,
            applyStage = applyStage,
            error = ErrorEntry(
                family = errorFamily,
                code = rejected.reason.name,
            ),
        )
    }

    private fun projectRolledBack(
        base: RunEvent,
        rolledBack: ApplyResult.RolledBack,
        applyStage: ApplyStage?,
    ): RunEvent {
        return base.copy(
            phase = PhaseCode.APPLY_ROLLED_BACK,
            applyStage = applyStage,
            error = ErrorEntry(
                family = ErrorFamily.APPLY_FAILURE,
                code = rolledBack.failure.name,
            ),
        )
    }

    /**
     * Project a checkpoint success event.
     */
    @JvmStatic
    fun projectCheckpointed(
        runId: String?,
        pointId: String?,
        journalSequence: Long,
        applyStage: ApplyStage = ApplyStage.A4,
    ): RunEvent {
        return RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.CHECKPOINTED,
            applyStage = applyStage,
            runId = runId,
            pointId = pointId,
        )
    }

    /**
     * Project an APPLY_COMMITTED event.
     */
    @JvmStatic
    fun projectCommitted(
        runId: String?,
        pointId: String?,
        journalSequence: Long,
    ): RunEvent {
        return RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.APPLY_COMMITTED,
            applyStage = ApplyStage.A6,
            runId = runId,
            pointId = pointId,
        )
    }

    private fun pointIdFromResult(result: ApplyResult): String? = when (result) {
        is ApplyResult.Applied -> result.pointId.value
        is ApplyResult.Recovered -> result.pointId.value
        is ApplyResult.Unresolved -> result.pointId.value
        is ApplyResult.RecoveryFailed -> result.pointId.value
        else -> null
    }

    private fun runIdFromResult(result: ApplyResult): String? = when (result) {
        is ApplyResult.NoChanges -> result.runId.value
        is ApplyResult.Applied -> result.runId.value
        is ApplyResult.Rejected -> result.runId.value
        is ApplyResult.RolledBack -> result.runId.value
        is ApplyResult.Recovered -> result.runId.value
        is ApplyResult.Unresolved -> result.runId.value
        is ApplyResult.RecoveryFailed -> result.runId.value
        is ApplyResult.ConcurrentRun -> null
    }
}
