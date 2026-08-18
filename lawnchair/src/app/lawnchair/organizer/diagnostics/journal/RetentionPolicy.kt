package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Pure-logic retention policy for the diagnostics journal.
 *
 * Evaluation is lazy (performed on append). The policy determines which
 * run histories (groups of events sharing a runId) are eligible for
 * pruning, respecting the three caps and unresolved-run protection.
 *
 * Caps (contract §8):
 * - Max 10 resolved run histories.
 * - Max 7 days for eligible history.
 * - Max 512 KiB for eligible history.
 *
 * Protected runs: those whose last event is one of the unresolved
 * lifecycle phases (APPLYING/COMMITTED_UNVERIFIED/RESTORING) or
 * whose recovery lifecycle is in an unresolved state.
 *
 * "Eligible" means resolved (has a terminal event or RESTART_RECONCILED)
 * and not protected.
 */
object RetentionPolicy {

    /** Max resolved run histories to retain. */
    const val MAX_RESOLVED_RUNS: Int = 10

    /** Max age in milliseconds for eligible history (7 days). */
    const val MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

    /** Max journal size in bytes for eligible history. */
    const val MAX_SIZE_BYTES: Long = 512L * 1024L

    /** Terminal phases that mark a run as resolved. */
    private val TERMINAL_PHASES: Set<PhaseCode> = setOf(
        PhaseCode.PLANNING_REJECTED,
        PhaseCode.PLANNING_IMPOSSIBLE,
        PhaseCode.USER_CANCELLED,
        PhaseCode.CHECKPOINT_REJECTED,
        PhaseCode.APPLY_NO_CHANGES,
        PhaseCode.APPLY_REJECTED,
        PhaseCode.CONCURRENT_RUN_REJECTED,
        PhaseCode.APPLY_VERIFIED,
        PhaseCode.APPLY_ROLLED_BACK,
        PhaseCode.APPLY_RECOVERED,
        PhaseCode.APPLY_UNRESOLVED,
        PhaseCode.APPLY_RECOVERY_FAILED,
        PhaseCode.RECOVERY_REJECTED,
        PhaseCode.RECOVERY_RESTORED,
        PhaseCode.RECOVERY_FAILED,
        PhaseCode.RECOVERY_WRITER_BUSY,
        PhaseCode.RECOVERY_CONCURRENT,
    )

    /** Phases that indicate an unresolved run. */
    private val UNRESOLVED_PHASES: Set<PhaseCode> = setOf(
        PhaseCode.APPLY_COMMITTED,
        PhaseCode.APPLY_RECOVERED,
        PhaseCode.APPLY_UNRESOLVED,
        PhaseCode.APPLY_RECOVERY_FAILED,
    )

    /**
     * A run history: the events belonging to a single run,
     * identified by their shared runId.
     */
    data class RunHistory(
        val runId: String,
        val events: List<RunEvent>,
        val totalBytes: Long,
    ) {
        val earliestSequence: Long get() = events.minOf { it.journalSequence }
        val earliestWallMillis: Long get() = events.minOf { it.recordedAtWallMillis }
        val hasTerminalEvent: Boolean get() = events.any { it.phase in TERMINAL_PHASES }
        val hasReconciledEvent: Boolean get() = events.any { it.phase == PhaseCode.RESTART_RECONCILED }
        val isProtected: Boolean get() = !hasTerminalEvent && !hasReconciledEvent
        val isResolved: Boolean get() = hasTerminalEvent || hasReconciledEvent
    }

    /**
     * Result of evaluating retention eligibility.
     */
    data class RetentionResult(
        /** The run IDs to prune (oldest eligible first). */
        val pruneRunIds: Set<String>,
        /** Whether the journal is now within all limits. */
        val withinLimits: Boolean,
    )

    /**
     * Evaluate which runs to prune given the current journal state.
     *
     * @param events all events currently in the journal, in sequence order.
     * @param eventByteSizes map from journalSequence to byte size of each event.
     * @param nowMillis current wall clock time for age comparison.
     * @return the set of run IDs to prune.
     */
    fun evaluate(
        events: List<RunEvent>,
        eventByteSizes: Map<Long, Long>,
        nowMillis: Long,
    ): RetentionResult {
        // Group events by runId (events without runId are orphaned — prune first)
        val orphanedEvents = events.filter { it.runId == null }
        val runGroups = events.filter { it.runId != null }.groupBy { it.runId!! }

        val histories = runGroups.map { (runId, runEvents) ->
            val bytes = runEvents.sumOf { eventByteSizes[it.journalSequence] ?: 0L }
            RunHistory(runId, runEvents, bytes)
        }

        val eligible = histories.filter { it.isResolved && !it.isProtected }
        val protected = histories.filter { it.isProtected }

        // We need to keep protected runs regardless of limits.
        // Sort eligible runs by earliest sequence (oldest first) for FIFO pruning.
        val sortedEligible = eligible.sortedBy { it.earliestSequence }

        var toPrune = mutableSetOf<String>()
        var remainingEligible: List<RunHistory> = sortedEligible

        // 1. Cap by number of resolved runs: keep at most MAX_RESOLVED_RUNS
        val totalResolved = eligible.size
        if (totalResolved > MAX_RESOLVED_RUNS) {
            val excess = totalResolved - MAX_RESOLVED_RUNS
            val prune = sortedEligible.take(excess)
            toPrune.addAll(prune.map { it.runId })
            remainingEligible = remainingEligible.drop(excess)
        }

        // 2. Cap by age: keep events within MAX_AGE_MS
        val ageThreshold = nowMillis - MAX_AGE_MS
        val oldRuns = remainingEligible.filter { it.earliestWallMillis > 0L && it.earliestWallMillis < ageThreshold }
        toPrune.addAll(oldRuns.map { it.runId })
        remainingEligible = remainingEligible.filter { it.runId !in toPrune }

        // 3. Cap by size: total remaining bytes must not exceed MAX_SIZE_BYTES
        val remainingEligibleBytes = remainingEligible.sumOf { it.totalBytes }
        val protectedBytes = protected.sumOf { it.totalBytes }
        val orphanedBytes = orphanedEvents.sumOf { eventByteSizes[it.journalSequence] ?: 0L }
        val totalBytes = remainingEligibleBytes + protectedBytes + orphanedBytes
        if (totalBytes > MAX_SIZE_BYTES) {
            // Prune eligible runs from oldest until under limit
            val excessBytes = totalBytes - MAX_SIZE_BYTES
            var prunedBytes = 0L
            for (run in remainingEligible.sortedBy { it.earliestSequence }) {
                if (prunedBytes >= excessBytes) break
                toPrune.add(run.runId)
                prunedBytes += run.totalBytes
                remainingEligible = remainingEligible.filter { it.runId != run.runId }
            }
        }

        return RetentionResult(
            pruneRunIds = toPrune,
            withinLimits = toPrune.isEmpty() || (remainingEligible.size + protected.size <= MAX_RESOLVED_RUNS),
        )
    }

    /**
     * Determine if a run is protected based on the diagnostics event data.
     * A run is protected if it has events with APPLYING/COMMITTED_UNVERIFIED/RESTORING
     * lifecycle state and no terminal or RESTART_RECONCILED event.
     */
    fun isRunProtected(events: List<RunEvent>): Boolean {
        val hasUnresolvedPhase = events.any { it.phase in UNRESOLVED_PHASES }
        val hasTerminal = events.any { it.phase in TERMINAL_PHASES }
        val hasReconciled = events.any { it.phase == PhaseCode.RESTART_RECONCILED }
        return hasUnresolvedPhase && !hasTerminal && !hasReconciled
    }
}
