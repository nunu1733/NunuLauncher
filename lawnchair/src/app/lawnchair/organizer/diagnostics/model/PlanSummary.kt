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
        // Closed sets from the accepted diagnostics contract §6.1
        // PreserveReason enum: LOCKED, UNAVAILABLE_TARGET, DOCK, WIDGET, APP_PAIR,
        //   LEGACY_SHORTCUT, NON_TARGET, STRUCTURAL, ALREADY_CANONICAL
        private val PRESERVED_BY_REASON_VALUES: Set<String> = setOf(
            "LOCKED", "UNAVAILABLE_TARGET", "DOCK", "WIDGET", "APP_PAIR",
            "LEGACY_SHORTCUT", "NON_TARGET", "STRUCTURAL", "ALREADY_CANONICAL",
        )

        // UnplacedReason enum: EXCEEDS_GRID_DIMENSIONS, TARGET_UNAVAILABLE
        private val UNPLACED_BY_REASON_VALUES: Set<String> = setOf(
            "EXCEEDS_GRID_DIMENSIONS",
            "TARGET_UNAVAILABLE",
        )

        // WarningCode enum: LEGACY_SHORTCUT_REVIEW, FALLBACK_CATEGORY, UNAVAILABLE_PRESERVED
        private val WARNING_BY_CODE_VALUES: Set<String> = setOf(
            "LEGACY_SHORTCUT_REVIEW",
            "FALLBACK_CATEGORY",
            "UNAVAILABLE_PRESERVED",
        )

        // Confidence enum: EXPLICIT, RULE, FALLBACK
        private val CONFIDENCE_COUNTS_VALUES: Set<String> = setOf(
            "EXPLICIT",
            "RULE",
            "FALLBACK",
        )
    }
}
