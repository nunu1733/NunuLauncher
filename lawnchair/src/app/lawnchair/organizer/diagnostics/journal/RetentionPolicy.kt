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
 * Protected runs: those whose recovery lifecycle is still in an unresolved
 * state (APPLYING / COMMITTED_UNVERIFIED / RESTORING per contract §8). At
 * the diagnosis event level, a run is protected if it has a phase that
 * indicates an unresolved lifecycle (APPLY_COMMITTED -> COMMITTED_UNVERIFIED)
 * and does NOT have a terminal event or RESTART_RECONCILED event.
 *
 * Protected in-flight recovery events: events with a RecoveryContext whose
 * pointId has no terminal resolution event (RECOVERY_RESTORED /
 * RECOVERY_FAILED / RECOVERY_REJECTED / RECOVERY_WRITER_BUSY /
 * RECOVERY_CONCURRENT) must survive age/size pruning. These events carry
 * runId=null and are correlated by their recovery.pointId. Once a terminal
 * resolution event exists for that pointId, they become prunable.
 *
 * Orphaned events (no runId) are never protected by run-level protection.
 * They are pruned under the age and size caps (oldest first) to keep the
 * journal bounded, except for protected in-flight recovery events.
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

    /**
     * Terminal recovery phases: these mark a recovery point as resolved.
     * In-flight recovery events (RECOVERY_REQUESTED) are protected when
     * no terminal recovery event exists for the same pointId.
     */
    private val TERMINAL_RECOVERY_PHASES: Set<PhaseCode> = setOf(
        PhaseCode.RECOVERY_RESTORED,
        PhaseCode.RECOVERY_FAILED,
        PhaseCode.RECOVERY_REJECTED,
        PhaseCode.RECOVERY_WRITER_BUSY,
        PhaseCode.RECOVERY_CONCURRENT,
    )

    /**
     * Phases that indicate an unresolved recovery lifecycle
     * (APPLYING / COMMITTED_UNVERIFIED / RESTORING per contract §8).
     * APPLY_COMMITTED is the diagnosis projection of COMMITTED_UNVERIFIED.
     */
    private val UNRESOLVED_LIFECYCLE_PHASES: Set<PhaseCode> = setOf(
        PhaseCode.APPLY_COMMITTED,
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

        /** Protected: has unresolved lifecycle indicator and no terminal/reconciled resolution. */
        val isProtected: Boolean get() =
            events.any { it.phase in UNRESOLVED_LIFECYCLE_PHASES } &&
                !hasTerminalEvent && !hasReconciledEvent
        val isResolved: Boolean get() = hasTerminalEvent || hasReconciledEvent
    }

    /**
     * Result of evaluating retention eligibility.
     */
    data class RetentionResult(
        /** The run IDs to prune (oldest eligible first). */
        val pruneRunIds: Set<String>,
        /** Journal sequences of orphaned events to prune. */
        val pruneOrphanedSequences: Set<Long> = emptySet(),
        /** Whether the journal is now within all limits. */
        val withinLimits: Boolean,
    )

    /**
     * Compute the set of event sequences that are protected because they
     * belong to an in-flight recovery operation (RECOVERY_REQUESTED without
     * a terminal recovery event for the same pointId). Protection is also
     * resolved by a RESTART_RECONCILED event that carries the same pointId,
     * since restart reconciliation resolves the recovery state without a
     * separate terminal RECOVERY_* event.
     */
    private fun protectedRecoverySequences(events: List<RunEvent>): Set<Long> {
        val recoveryEvents = events.filter { it.recovery != null }
        if (recoveryEvents.isEmpty()) return emptySet()

        // Collect the set of pointIds that have been resolved by a
        // RESTART_RECONCILED event. This handles the case where a
        // recovery point is resolved by restart reconciliation
        // rather than a terminal RECOVERY_* event.
        val restartedPointIds = events
            .filter { it.phase == PhaseCode.RESTART_RECONCILED && it.pointId != null }
            .map { it.pointId!! }
            .toSet()

        // Group by pointId to find terminal vs in-flight
        val byPointId = recoveryEvents.groupBy { it.recovery!!.pointId }
        val protectedSequences = mutableSetOf<Long>()
        for ((pointId, group) in byPointId) {
            val hasTerminal = group.any { it.phase in TERMINAL_RECOVERY_PHASES }
            val resolvedByRestart = pointId in restartedPointIds
            if (!hasTerminal && !resolvedByRestart) {
                // All events in this group are in-flight and protected
                protectedSequences.addAll(group.map { it.journalSequence })
            }
        }
        return protectedSequences
    }

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
        // Identify in-flight recovery events that are protected
        val protectedRecoverySeqs = protectedRecoverySequences(events)

        // Group events by runId (events without runId are orphaned)
        val orphanedEvents = events.filter { it.runId == null }
        val runGroups = events.filter { it.runId != null }.groupBy { it.runId!! }

        val histories = runGroups.map { (runId, runEvents) ->
            val bytes = runEvents.sumOf { eventByteSizes[it.journalSequence] ?: 0L }
            RunHistory(runId, runEvents, bytes)
        }

        val eligible = histories.filter { it.isResolved && !it.isProtected }
        val protected = histories.filter { it.isProtected }

        // Sort eligible runs by earliest sequence (oldest first) for FIFO pruning.
        val sortedEligible = eligible.sortedBy { it.earliestSequence }

        var toPrune = mutableSetOf<String>()
        var remainingEligible: List<RunHistory> = sortedEligible
        var orphanedToPrune = mutableSetOf<Long>()
        var remainingOrphaned = orphanedEvents.sortedBy { it.journalSequence }

        // 1. Cap by number of resolved runs: keep at most MAX_RESOLVED_RUNS
        val totalResolved = eligible.size
        if (totalResolved > MAX_RESOLVED_RUNS) {
            val excess = totalResolved - MAX_RESOLVED_RUNS
            val prune = sortedEligible.take(excess)
            toPrune.addAll(prune.map { it.runId })
            remainingEligible = remainingEligible.drop(excess)
        }

        // 2. Cap by age: keep events within MAX_AGE_MS.
        // Protected recovery events are excluded from age-based pruning.
        val ageThreshold = nowMillis - MAX_AGE_MS
        val oldRuns = remainingEligible.filter { it.earliestWallMillis > 0L && it.earliestWallMillis < ageThreshold }
        toPrune.addAll(oldRuns.map { it.runId })
        remainingEligible = remainingEligible.filter { it.runId !in toPrune }

        // Prune orphaned events older than MAX_AGE_MS, except protected recovery events
        val oldOrphaned = remainingOrphaned.filter {
            it.recordedAtWallMillis > 0L &&
                it.recordedAtWallMillis < ageThreshold &&
                it.journalSequence !in protectedRecoverySeqs
        }
        orphanedToPrune.addAll(oldOrphaned.map { it.journalSequence })
        remainingOrphaned = remainingOrphaned.filter { it.journalSequence !in orphanedToPrune }

        // 3. Cap by size: total remaining bytes must not exceed MAX_SIZE_BYTES.
        // Protected recovery events are excluded from the size calculation
        // for pruning purposes (they are not counted against the cap).
        val remainingEligibleBytes = remainingEligible.sumOf { it.totalBytes }
        val protectedBytes = protected.sumOf { it.totalBytes }
        val orphanedBytes = remainingOrphaned
            .filter { it.journalSequence !in protectedRecoverySeqs }
            .sumOf { eventByteSizes[it.journalSequence] ?: 0L }
        val protectedRecoveryBytes = remainingOrphaned
            .filter { it.journalSequence in protectedRecoverySeqs }
            .sumOf { eventByteSizes[it.journalSequence] ?: 0L }
        val totalBytes = remainingEligibleBytes + protectedBytes + orphanedBytes + protectedRecoveryBytes
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
            // If still over limit, prune non-protected orphaned events from oldest
            if (prunedBytes < excessBytes) {
                for (
                orphan in remainingOrphaned
                    .filter { it.journalSequence !in protectedRecoverySeqs }
                    .sortedBy { it.journalSequence }
                ) {
                    if (prunedBytes >= excessBytes) break
                    orphanedToPrune.add(orphan.journalSequence)
                    prunedBytes += eventByteSizes[orphan.journalSequence] ?: 0L
                }
            }
        }

        return RetentionResult(
            pruneRunIds = toPrune,
            pruneOrphanedSequences = orphanedToPrune,
            withinLimits = toPrune.isEmpty() || (remainingEligible.size + protected.size <= MAX_RESOLVED_RUNS),
        )
    }

    /**
     * Determine if a run is protected based on the diagnostics event data.
     * A run is protected if it has events with an unresolved lifecycle phase
     * (APPLY_COMMITTED -> COMMITTED_UNVERIFIED) and no terminal or
     * RESTART_RECONCILED event.
     */
    fun isRunProtected(events: List<RunEvent>): Boolean {
        val hasUnresolvedPhase = events.any { it.phase in UNRESOLVED_LIFECYCLE_PHASES }
        val hasTerminal = events.any { it.phase in TERMINAL_PHASES }
        val hasReconciled = events.any { it.phase == PhaseCode.RESTART_RECONCILED }
        return hasUnresolvedPhase && !hasTerminal && !hasReconciled
    }
}
