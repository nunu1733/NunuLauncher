package app.lawnchair.organizer.application.public

import app.lawnchair.organizer.planning.RevisionId

/**
 * Closed, read-only recovery-preview result surface.
 *
 * The result deliberately exposes only a recovery-point identifier and safe,
 * user-visible status. Recovery-store records, manifests, digests, rows,
 * revisions, and item/profile identities remain application-internal.
 *
 * Issue #84.
 */
sealed interface RecoveryPreviewResult {
    data class Restorable(
        val pointId: RecoveryPointId,
        val summary: RecoveryPreviewSummary,
        val confirmation: RecoveryPreviewConfirmation,
    ) : RecoveryPreviewResult

    data class NotRestorable(
        val pointId: RecoveryPointId,
        val reason: RecoveryPreviewRejection,
    ) : RecoveryPreviewResult

    data class Unavailable(
        val pointId: RecoveryPointId,
        val reason: RecoveryPreviewUnavailable,
    ) : RecoveryPreviewResult

    data object WriterBusy : RecoveryPreviewResult
    data object Concurrent : RecoveryPreviewResult
}

/** The only safe effect summary supplied by a successful preview. */
data class RecoveryPreviewSummary(
    val effect: RecoveryPreviewEffect = RecoveryPreviewEffect.RESTORE_SAVED_LAYOUT,
    val confirmationRequired: Boolean = true,
    val conditionalOnCurrentRevision: Boolean = true,
)

enum class RecoveryPreviewEffect {
    RESTORE_SAVED_LAYOUT,
}

enum class RecoveryPreviewRejection {
    MISSING,
    EXPIRED,
    CORRUPT,
    INCOMPATIBLE_VERSION,
    ALREADY_RESTORED,
    UNRESOLVED,
    LOCK_STATE_UNAVAILABLE,
}

enum class RecoveryPreviewUnavailable {
    RECONCILIATION_PENDING,
    RECOVERY_STORE_UNAVAILABLE,
}

/**
 * Opaque in-memory capability emitted only for a fresh successful preview.
 *
 * Its constructor and all recovery inputs are internal. The UI/coordinator may
 * hold this value until explicit confirmation but cannot read or reconstruct
 * the request revision that the application boundary passes to recovery.
 */
class RecoveryPreviewConfirmation internal constructor(
    private val pointId: RecoveryPointId,
    private val expectedCurrentRevision: RevisionId,
) {
    /**
     * Internal-only conversion used by the application boundary at explicit
     * confirmation time. The constructed request never crosses that boundary.
     */
    internal fun recoveryRequest(): RecoveryRequest = RecoveryRequest(pointId, expectedCurrentRevision)
}
