package app.lawnchair.organizer.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * Apply protocol stages A0–A8 from the diagnostics contract §4.2.
 */
@Serializable
enum class ApplyStage {
    A0,
    A1,
    A2,
    A3,
    A4,
    A5,
    A6,
    A7,
    A8,
}
