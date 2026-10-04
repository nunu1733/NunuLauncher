package app.lawnchair.organizer.diagnostics.model

import app.lawnchair.organizer.planning.Confidence
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import kotlinx.serialization.Serializable

/**
 * Plan summary from the diagnostics contract §6.1.
 *
 * Maps use String keys (enum constant names) to avoid requiring
 * @Serializable on the source planning enums. Keys are validated
 * against the actual enum entries at construction.
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
            require(k in PRESERVED_BY_REASON_VALUES) {
                "PlanSummary.preservedByReason keys must be PreserveReason enum names, got '$k'"
            }
        }
        unplacedByReason.keys.forEach { k ->
            require(k in UNPLACED_BY_REASON_VALUES) {
                "PlanSummary.unplacedByReason keys must be UnplacedReason enum names, got '$k'"
            }
        }
        warningByCode.keys.forEach { k ->
            require(k in WARNING_BY_CODE_VALUES) {
                "PlanSummary.warningByCode keys must be WarningCode enum names, got '$k'"
            }
        }
        confidenceCounts.keys.forEach { k ->
            require(k in CONFIDENCE_COUNTS_VALUES) {
                "PlanSummary.confidenceCounts keys must be Confidence enum names, got '$k'"
            }
        }
    }

    companion object {
        // Derived from the real enum entries so they can never drift.
        private val PRESERVED_BY_REASON_VALUES: Set<String> =
            PreserveReason.entries.map { it.name }.toSet()
        private val UNPLACED_BY_REASON_VALUES: Set<String> =
            UnplacedReason.entries.map { it.name }.toSet()
        private val WARNING_BY_CODE_VALUES: Set<String> =
            WarningCode.entries.map { it.name }.toSet()
        private val CONFIDENCE_COUNTS_VALUES: Set<String> =
            Confidence.entries.map { it.name }.toSet()
    }
}
