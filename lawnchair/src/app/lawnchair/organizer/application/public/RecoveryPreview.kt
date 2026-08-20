package app.lawnchair.organizer.application.public

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
 * Opaque in-memory, one-shot-capable handle emitted for a fresh successful
 * preview. The random token has no recovery semantics by itself: the private
 * application registry owns the point/revision binding and consumes it before
 * delegation to the existing recovery application behavior.
 *
 * The constructor is private, and this type deliberately exposes no point ID,
 * revision, [RecoveryRequest], serialization contract, or conversion method.
 */
class RecoveryPreviewConfirmation private constructor(
    @Suppress("unused") private val opaqueToken: ByteArray,
) {
    companion object {
        /** Issued only by the application-owned pending-confirmation registry. */
        internal fun issue(opaqueToken: ByteArray): RecoveryPreviewConfirmation = RecoveryPreviewConfirmation(opaqueToken.copyOf())
    }
}
