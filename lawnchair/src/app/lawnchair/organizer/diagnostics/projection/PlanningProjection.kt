package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.PlanSummary
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.planning.Confidence
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.Rejected
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode

/**
 * Projection from [PlanningResult] into [RunEvent].
 *
 * Every variant of [PlanningResult] is handled explicitly.
 * The extend-this-function pattern is used so that adding a new
 * projection variant later requires updating this exhaustive when.
 */
object PlanningProjection {

    /**
     * Project a [PlanningResult] into a [RunEvent].
     *
     * @param result the planning result.
     * @param journalSequence the next journal sequence number.
     * @param capturedItemCount total items captured (caller provides if known).
     * @param candidateItemCount total candidate items (caller provides if known).
     * @return the projected [RunEvent], or null if no event should be emitted.
     */
    @JvmStatic
    fun project(
        result: PlanningResult,
        journalSequence: Long,
        capturedItemCount: Int = 0,
        candidateItemCount: Int = 0,
    ): RunEvent {
        val baseEvent = RunEvent(
            journalSequence = journalSequence,
            phase = PhaseCode.RUN_STARTED, // placeholder, overridden by copy()
            versions = null,
            deviceProfile = null,
        )

        return when (val outcome = result.outcome) {
            is Planned -> projectPlanned(baseEvent, outcome, capturedItemCount, candidateItemCount)
            is Rejected.Invalid -> projectInvalid(baseEvent, outcome)
            is Rejected.Impossible -> projectImpossible(baseEvent, outcome, capturedItemCount, candidateItemCount)
        }
    }

    private fun projectPlanned(
        base: RunEvent,
        planned: Planned,
        capturedItemCount: Int,
        candidateItemCount: Int,
    ): RunEvent {
        val placements = planned.placements
        val movedCount = placements.count { it.disposition is app.lawnchair.organizer.planning.Disposition.Moved }
        val preservedCount = placements.count { it.disposition is app.lawnchair.organizer.planning.Disposition.Preserved }
        val preservedByReason = placements
            .mapNotNull { p ->
                val disp = p.disposition
                if (disp is app.lawnchair.organizer.planning.Disposition.Preserved) disp.reason.name else null
            }
            .groupingBy { it }
            .eachCount()

        val unplacedCount = 0 // Planned has no unplaced items
        val confidenceCounts = planned.categories
            .map { it.confidence.name }
            .groupingBy { it }
            .eachCount()

        val warningByCode = planned.warnings
            .map { it.code.name }
            .groupingBy { it }
            .eachCount()

        return base.copy(
            phase = PhaseCode.PLANNED,
            planSummary = PlanSummary(
                capturedItemCount = capturedItemCount,
                candidateItemCount = candidateItemCount,
                movedCount = movedCount,
                preservedCount = preservedCount,
                preservedByReason = preservedByReason,
                newFolderCount = planned.newFolders.size,
                newPageCount = planned.newPages.size,
                unplacedCount = unplacedCount,
                confidenceCounts = confidenceCounts,
                warningByCode = warningByCode,
            ),
        )
    }

    private fun projectInvalid(base: RunEvent, invalid: Rejected.Invalid): RunEvent {
        val reasons = invalid.reasons
        val codes = reasons.map { it.code.name }
        val distinctCodes = codes.distinct()
        val primaryCode = distinctCodes.firstOrNull() ?: "UNMAPPED"
        val additional = distinctCodes.drop(1).take(8)

        return base.copy(
            phase = PhaseCode.PLANNING_REJECTED,
            error = ErrorEntry(
                family = ErrorFamily.PLANNING_INVALID,
                code = primaryCode,
                reasonTotal = reasons.size,
                additionalCodes = additional,
            ),
        )
    }

    private fun projectImpossible(
        base: RunEvent,
        impossible: Rejected.Impossible,
        capturedItemCount: Int = 0,
        candidateItemCount: Int = 0,
    ): RunEvent {
        val unplaced = impossible.unplaced
        val unplacedByReason = unplaced
            .map { it.reason.name }
            .groupingBy { it }
            .eachCount()
        val warningByCode = impossible.warnings
            .map { it.code.name }
            .groupingBy { it }
            .eachCount()

        return base.copy(
            phase = PhaseCode.PLANNING_IMPOSSIBLE,
            planSummary = PlanSummary(
                capturedItemCount = capturedItemCount,
                candidateItemCount = candidateItemCount,
                unplacedCount = unplaced.size,
                unplacedByReason = unplacedByReason,
                warningByCode = warningByCode,
            ),
        )
    }
}
