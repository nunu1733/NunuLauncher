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

/**
 * Version identifier regex: non-blank, 1-32 characters, restricted to
 * [A-Za-z0-9_-]. Dots are excluded to prevent package and component
 * identity strings (e.g. "com.example.private") from being carried as
 * version identifiers.
 */
internal val VERSION_ID_REGEX: Regex = Regex("^[A-Za-z0-9_-]{1,32}$")

internal fun validateVersionId(id: String, fieldName: String) {
    // The empty string is the default value; non-empty values must match the
    // accepted version-identifier charset.
    if (id.isNotEmpty()) {
        require(id.matches(VERSION_ID_REGEX)) {
            "$fieldName must be 1-32 characters of [A-Za-z0-9_-], got '$id'"
        }
    }
}
