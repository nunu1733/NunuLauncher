package app.lawnchair.organizer.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * Trigger enumeration from the diagnostics contract §3.
 */
@Serializable
enum class Trigger {
    MANUAL_FULL,
    ONBOARDING_PROPOSAL,
    INCREMENTAL_PROPOSAL,
}
