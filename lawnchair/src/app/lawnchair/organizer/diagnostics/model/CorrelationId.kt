package app.lawnchair.organizer.diagnostics.model

/**
 * Shared validation for correlation IDs in the diagnostics model.
 * All correlation IDs must be canonical 32 lowercase hex characters.
 */
internal val CORRELATION_ID_REGEX: Regex = Regex("^[0-9a-f]{32}$")

internal fun validateCorrelationId(id: String, fieldName: String) {
    require(id.matches(CORRELATION_ID_REGEX)) {
        "$fieldName must be exactly 32 lowercase hex characters, got '$id'"
    }
}
