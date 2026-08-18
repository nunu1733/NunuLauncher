package app.lawnchair.organizer.diagnostics.model

import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.UnplacedReason
import kotlinx.serialization.Serializable

/**
 * Error families from the diagnostics contract §5.
 */
@Serializable
enum class ErrorFamily {
    PLANNING_INVALID,
    PLANNING_IMPOSSIBLE,
    PRE_WRITE_REJECTED,
    APPLY_FAILURE,
    RECOVERY_REJECTION,
    RECOVERY_FAILURE,
    CONCURRENT,
    WRITER_BUSY,
}

/**
 * Error entry: family x code from the diagnostics contract §5.
 *
 * - `code`: the source enum constant name or `"UNMAPPED"` for unknown values.
 * - `reasonTotal`: total number of reasons for PLANNING_INVALID.
 * - `additionalCodes`: additional codes from the same family, max 8 entries.
 */
@Serializable
data class ErrorEntry(
    val family: ErrorFamily,
    val code: String,
    val reasonTotal: Int? = null,
    val additionalCodes: List<String> = emptyList(),
) {
    init {
        require(code.isNotBlank()) { "ErrorEntry.code must not be blank" }
        val validCodes = validCodesForFamily(family)
        require(code == "UNMAPPED" || code in validCodes) {
            "ErrorEntry.code must be a valid code for family ${family.name} or 'UNMAPPED', " +
                "got '$code'. Valid codes: $validCodes"
        }
        require(additionalCodes.size <= 8) {
            "ErrorEntry.additionalCodes must have at most 8 entries, got ${additionalCodes.size}"
        }
        additionalCodes.forEach { c ->
            require(c.isNotBlank()) { "ErrorEntry.additionalCodes entries must not be blank" }
            require(c in validCodes) {
                "ErrorEntry.additionalCodes entries must be valid codes for family ${family.name}, " +
                    "got '$c'. Valid codes: $validCodes"
            }
        }
    }

    companion object {
        /**
         * Returns the set of accepted code strings for a given [ErrorFamily].
         * Derived from the real source enums so the sets can never drift.
         */
        fun validCodesForFamily(family: ErrorFamily): Set<String> = when (family) {
            ErrorFamily.PLANNING_INVALID -> RejectionCode.entries.map { it.name }.toSet()
            ErrorFamily.PLANNING_IMPOSSIBLE -> UnplacedReason.entries.map { it.name }.toSet()
            ErrorFamily.PRE_WRITE_REJECTED -> PreWriteRejection.entries.map { it.name }.toSet()
            ErrorFamily.APPLY_FAILURE -> ApplyFailure.entries.map { it.name }.toSet()
            ErrorFamily.RECOVERY_REJECTION -> RecoveryRejection.entries.map { it.name }.toSet()
            ErrorFamily.RECOVERY_FAILURE -> RecoveryFailure.entries.map { it.name }.toSet()
            ErrorFamily.CONCURRENT -> setOf("CONCURRENT_RUN")
            ErrorFamily.WRITER_BUSY -> setOf("WRITER_BUSY")
        }
    }
}
