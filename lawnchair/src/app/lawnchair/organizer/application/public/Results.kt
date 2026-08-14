package app.lawnchair.organizer.application.public

/**
 * Canonical run identifier. Exactly 32 lowercase hex characters.
 *
 * Issue #14 Stage B step 1.
 */
@JvmInline
value class RunId(val value: String) {
    init {
        require(RUN_ID_REGEX.matches(value)) {
            "RunId must be exactly 32 lowercase hex characters, got '$value'"
        }
    }

    companion object {
        private val RUN_ID_REGEX: Regex = Regex("^[0-9a-f]{32}$")
    }
}

/**
 * Canonical recovery point identifier. Exactly 32 lowercase hex characters.
 *
 * Issue #14 Stage B step 1.
 */
@JvmInline
value class RecoveryPointId(val value: String) {
    init {
        require(POINT_ID_REGEX.matches(value)) {
            "RecoveryPointId must be exactly 32 lowercase hex characters, got '$value'"
        }
    }

    companion object {
        private val POINT_ID_REGEX: Regex = Regex("^[0-9a-f]{32}$")
    }
}

/**
 * Public apply result variants. Exactly the variants from spec.md §“Results”;
 * the protocol may not add new public variants without a spec change.
 *
 * Issue #14 Stage B step 1.
 */
sealed interface ApplyResult {
    data class NoChanges(val runId: RunId) : ApplyResult
    data class Applied(val runId: RunId, val pointId: RecoveryPointId) : ApplyResult
    data class Rejected(val runId: RunId, val reason: PreWriteRejection) : ApplyResult
    data class RolledBack(val runId: RunId, val failure: ApplyFailure) : ApplyResult
    data class Recovered(
        val runId: RunId,
        val pointId: RecoveryPointId,
        val failure: ApplyFailure,
    ) : ApplyResult
    data class Unresolved(
        val runId: RunId,
        val pointId: RecoveryPointId,
        val failure: ApplyFailure,
        val authoritativeState: AuthoritativeState,
    ) : ApplyResult
    data class RecoveryFailed(
        val runId: RunId,
        val pointId: RecoveryPointId,
        val failure: ApplyFailure,
        val recoveryFailure: RecoveryFailure,
        val authoritativeState: AuthoritativeState,
    ) : ApplyResult
    data object ConcurrentRun : ApplyResult
}

enum class PreWriteRejection {
    INVALID_PLAN,
    STALE_REVISION,
    EXACT_PRECONDITION_FAILED,
    LOCK_STATE_UNAVAILABLE,
    IDENTITY_EXHAUSTED,
    CHECKPOINT_CREATE_FAILED,
    CHECKPOINT_VALIDATE_FAILED,
    RECOVERY_STORE_UNAVAILABLE,
    WRITER_BUSY,
}

enum class ApplyFailure {
    WRITE_FAILED,
    COMMIT_OUTCOME_UNKNOWN,
    MODEL_RELOAD_FAILED,
    VERIFICATION_FAILED,
    RECOVERY_STORE_FAILED,
}

/**
 * Public recovery result variants. Exactly the variants from spec.md §“Results”.
 *
 * Issue #14 Stage B step 1.
 */
sealed interface RecoveryResult {
    data class Restored(val pointId: RecoveryPointId) : RecoveryResult
    data class NotRestorable(val pointId: RecoveryPointId, val reason: RecoveryRejection) : RecoveryResult
    data class RestoreFailed(
        val pointId: RecoveryPointId,
        val failure: RecoveryFailure,
        val authoritativeState: AuthoritativeState,
    ) : RecoveryResult
    data object WriterBusy : RecoveryResult
    data object ConcurrentRun : RecoveryResult
}

enum class RecoveryRejection {
    MISSING,
    EXPIRED,
    CORRUPT,
    INCOMPATIBLE_VERSION,
    STALE_REVISION,
    LOCK_STATE_UNAVAILABLE,
    ALREADY_RESTORED,
}

enum class RecoveryFailure {
    WRITE_FAILED,
    COMMIT_OUTCOME_UNKNOWN,
    MODEL_RELOAD_FAILED,
    VERIFICATION_FAILED,
    RECOVERY_STORE_FAILED,
}

/**
 * Authoritative Launcher DB/model state classification reported on uncertain
 * outcomes. Exactly the variants from spec.md §“Results”.
 */
enum class AuthoritativeState {
    PRE_APPLY_DB_AND_MODEL,
    PRE_APPLY_DB_MODEL_UNVERIFIED,
    POST_APPLY_DB_AND_MODEL,
    POST_APPLY_DB_MODEL_UNVERIFIED,
    REVIEWED_CURRENT_DB_AND_MODEL,
    REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
    UNKNOWN,
}
