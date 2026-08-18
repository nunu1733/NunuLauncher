package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RecoveryContext
import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Projection from [RecoveryResult] into [RunEvent].
 *
 * Every variant of [RecoveryResult] is handled explicitly.
 */
object RecoveryProjection {

    /**
     * Project a [RecoveryResult] into a [RunEvent].
     *
     * @param result the recovery result.
     * @param journalSequence the next journal sequence number.
     * @param pointId the recovery point ID (from the request).
     * @param pointOriginRunId optional origin run ID for the recovery point.
     * @return the projected [RunEvent].
     */
    @JvmStatic
    fun project(
        result: RecoveryResult,
        journalSequence: Long,
        pointId: String? = null,
        pointOriginRunId: String? = null,
    ): RunEvent {
        val base = RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.RUN_STARTED, // placeholder, overridden by copy()
            pointId = pointId,
        )

        return when (result) {
            is RecoveryResult.Restored -> base.copy(
                phase = PhaseCode.RECOVERY_RESTORED,
                recovery = RecoveryContext(
                    pointId = result.pointId.value,
                    pointOriginRunId = pointOriginRunId,
                ),
            )

            is RecoveryResult.NotRestorable -> base.copy(
                phase = PhaseCode.RECOVERY_REJECTED,
                error = ErrorEntry(
                    family = ErrorFamily.RECOVERY_REJECTION,
                    code = result.reason.name,
                ),
                recovery = if (pointId != null) RecoveryContext(pointId, pointOriginRunId) else null,
            )

            is RecoveryResult.RestoreFailed -> base.copy(
                phase = PhaseCode.RECOVERY_FAILED,
                error = ErrorEntry(
                    family = ErrorFamily.RECOVERY_FAILURE,
                    code = result.failure.name,
                ),
                recovery = if (pointId != null) RecoveryContext(pointId, pointOriginRunId) else null,
            )

            is RecoveryResult.WriterBusy -> base.copy(
                phase = PhaseCode.RECOVERY_WRITER_BUSY,
                error = ErrorEntry(
                    family = ErrorFamily.WRITER_BUSY,
                    code = "WRITER_BUSY",
                ),
                recovery = if (pointId != null) RecoveryContext(pointId, pointOriginRunId) else null,
            )

            is RecoveryResult.ConcurrentRun -> base.copy(
                phase = PhaseCode.RECOVERY_CONCURRENT,
                error = ErrorEntry(
                    family = ErrorFamily.CONCURRENT,
                    code = "CONCURRENT_RUN",
                ),
                recovery = if (pointId != null) RecoveryContext(pointId, pointOriginRunId) else null,
            )
        }
    }

    /**
     * Project a RECOVERY_REQUESTED event.
     */
    @JvmStatic
    fun projectRequested(
        pointId: String,
        pointOriginRunId: String?,
        journalSequence: Long,
    ): RunEvent {
        return RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.RECOVERY_REQUESTED,
            pointId = pointId,
            recovery = RecoveryContext(
                pointId = pointId,
                pointOriginRunId = pointOriginRunId,
            ),
        )
    }
}
