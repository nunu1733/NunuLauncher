package app.lawnchair.organizer.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * Closed set of phase codes from the diagnostics contract §4.1.
 * Terminal phases are marked in comments.
 */
@Serializable
enum class PhaseCode {
    RUN_STARTED,
    INPUT_NOT_READY, // terminal (issue #172)
    CAPTURED,
    PREVIEWED,
    PLANNED,
    PLANNING_REJECTED, // terminal
    PLANNING_IMPOSSIBLE, // terminal
    USER_CONFIRMED,
    USER_CANCELLED, // terminal
    CHECKPOINTED,
    CHECKPOINT_REJECTED, // terminal
    APPLY_NO_CHANGES, // terminal
    APPLY_REJECTED, // terminal
    CONCURRENT_RUN_REJECTED, // terminal
    APPLY_COMMITTED,
    APPLY_VERIFIED, // terminal
    APPLY_ROLLED_BACK, // terminal
    APPLY_RECOVERED, // terminal
    APPLY_UNRESOLVED, // terminal
    APPLY_RECOVERY_FAILED, // terminal
    RECOVERY_REQUESTED,
    RECOVERY_REJECTED, // terminal
    RECOVERY_RESTORED, // terminal
    RECOVERY_FAILED, // terminal
    RECOVERY_WRITER_BUSY, // terminal
    RECOVERY_CONCURRENT, // terminal
    RESTART_RECONCILED,
}
