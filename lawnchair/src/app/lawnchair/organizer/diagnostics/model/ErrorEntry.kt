package app.lawnchair.organizer.diagnostics.model

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
 * Error entry: family × code from the diagnostics contract §5.
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
        require(code.all { it.isUpperCase() || it == '_' || it.isDigit() } || code == "UNMAPPED") {
            "ErrorEntry.code must be an enum constant name or 'UNMAPPED', got '$code'"
        }
        additionalCodes.forEach { c ->
            require(c.isNotBlank()) { "ErrorEntry.additionalCodes entries must not be blank" }
        }
    }
}
