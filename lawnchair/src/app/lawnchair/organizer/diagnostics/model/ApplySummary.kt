package app.lawnchair.organizer.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * Apply summary from the diagnostics contract §6.2.
 */
@Serializable
data class ApplySummary(
    val preserveActionCount: Int = 0,
    val updateActionCount: Int = 0,
    val insertActionCount: Int = 0,
)
