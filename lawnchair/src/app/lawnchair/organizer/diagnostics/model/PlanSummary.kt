package app.lawnchair.organizer.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * Plan summary from the diagnostics contract §6.1.
 *
 * Maps use String keys (enum constant names) to avoid requiring
 * @Serializable on the source planning enums.
 */
@Serializable
data class PlanSummary(
    val capturedItemCount: Int = 0,
    val candidateItemCount: Int = 0,
    val movedCount: Int = 0,
    val preservedCount: Int = 0,
    val preservedByReason: Map<String, Int> = emptyMap(),
    val newFolderCount: Int = 0,
    val newPageCount: Int = 0,
    val unplacedCount: Int = 0,
    val unplacedByReason: Map<String, Int> = emptyMap(),
    val warningByCode: Map<String, Int> = emptyMap(),
    val confidenceCounts: Map<String, Int> = emptyMap(),
) {
    init {
        preservedByReason.keys.forEach { k ->
            require(k.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                "PlanSummary.preservedByReason keys must be enum constant names, got '$k'"
            }
        }
        unplacedByReason.keys.forEach { k ->
            require(k.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                "PlanSummary.unplacedByReason keys must be enum constant names, got '$k'"
            }
        }
        warningByCode.keys.forEach { k ->
            require(k.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                "PlanSummary.warningByCode keys must be enum constant names, got '$k'"
            }
        }
        confidenceCounts.keys.forEach { k ->
            require(k.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                "PlanSummary.confidenceCounts keys must be enum constant names, got '$k'"
            }
        }
    }
}
