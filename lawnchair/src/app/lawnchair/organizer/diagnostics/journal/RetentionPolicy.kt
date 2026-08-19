package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle
import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Pure-logic retention policy for the diagnostics journal.
 *
 * Evaluation is lazy (performed on append). The policy determines which
 * histories are prunable while preserving only contract-defined unresolved
 * recovery history.
 *
 * Caps (contract §8):
 * - Max 10 resolved run histories.
 * - Max 7 days for any non-protected history.
 * - Max 512 KiB after pruning non-protected history oldest-first.
 *
 * Protected history is limited to unresolved recovery lifecycle state
 * (APPLYING / COMMITTED_UNVERIFIED / RESTORING). Incomplete runs that never
 * reached one of those protected states remain eligible for age/size pruning.
 */
object RetentionPolicy {

    const val MAX_RESOLVED_RUNS: Int = 10
    const val MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val MAX_SIZE_BYTES: Long = 512L * 1024L

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

    private val TERMINAL_RECOVERY_PHASES: Set<PhaseCode> = setOf(
        PhaseCode.RECOVERY_RESTORED,
        PhaseCode.RECOVERY_FAILED,
        PhaseCode.RECOVERY_REJECTED,
        PhaseCode.RECOVERY_WRITER_BUSY,
        PhaseCode.RECOVERY_CONCURRENT,
    )

    private val UNRESOLVED_LIFECYCLE_PHASES: Set<PhaseCode> = setOf(
        PhaseCode.APPLY_COMMITTED,
    )

    private val RESOLVED_RESULTING_LIFECYCLES: Set<RecoveryLifecycle> = setOf(
        RecoveryLifecycle.CREATING,
        RecoveryLifecycle.READY,
        RecoveryLifecycle.VERIFIED,
        RecoveryLifecycle.RESTORED,
        RecoveryLifecycle.CORRUPT,
        RecoveryLifecycle.EXPIRED,
        RecoveryLifecycle.INCOMPATIBLE,
    )

    data class RunHistory(
        val runId: String,
        val events: List<RunEvent>,
        val totalBytes: Long,
    ) {
        val earliestSequence: Long get() = events.minOf { it.journalSequence }
        val earliestWallMillis: Long get() = events.minOf { it.recordedAtWallMillis }
        val hasTerminalEvent: Boolean get() = events.any { it.phase in TERMINAL_PHASES }
        val hasResolvingReconciledEvent: Boolean get() = events.any {
            it.phase == PhaseCode.RESTART_RECONCILED &&
                it.reconciliation?.resultingLifecycle in RESOLVED_RESULTING_LIFECYCLES
        }
        val isProtected: Boolean get() =
            events.any { it.phase in UNRESOLVED_LIFECYCLE_PHASES } &&
                !hasTerminalEvent && !hasResolvingReconciledEvent
        val isResolved: Boolean get() = hasTerminalEvent || hasResolvingReconciledEvent
    }

    data class RetentionResult(
        val pruneRunIds: Set<String>,
        val pruneOrphanedSequences: Set<Long> = emptySet(),
        val withinLimits: Boolean,
    )

    private fun protectedRecoverySequences(events: List<RunEvent>): Set<Long> {
        val recoveryEvents = events.filter { it.recovery != null }
        if (recoveryEvents.isEmpty()) return emptySet()

        val restartedPointIds = events
            .filter {
                it.phase == PhaseCode.RESTART_RECONCILED &&
                    it.pointId != null &&
                    it.reconciliation?.resultingLifecycle in RESOLVED_RESULTING_LIFECYCLES
            }
            .map { it.pointId!! }
            .toSet()

        return buildSet {
            for ((pointId, group) in recoveryEvents.groupBy { it.recovery!!.pointId }) {
                val hasTerminal = group.any { it.phase in TERMINAL_RECOVERY_PHASES }
                if (!hasTerminal && pointId !in restartedPointIds) {
                    addAll(group.map { it.journalSequence })
                }
            }
        }
    }

    fun evaluate(
        events: List<RunEvent>,
        eventByteSizes: Map<Long, Long>,
        nowMillis: Long,
    ): RetentionResult {
        val protectedRecoverySeqs = protectedRecoverySequences(events)
        val orphanedEvents = events.filter { it.runId == null }
        val runGroups = events.filter { it.runId != null }.groupBy { it.runId!! }

        val histories = runGroups.map { (runId, runEvents) ->
            RunHistory(
                runId = runId,
                events = runEvents,
                totalBytes = runEvents.sumOf { eventByteSizes[it.journalSequence] ?: 0L },
            )
        }

        val protected = histories.filter { it.isProtected }
        val nonProtected = histories.filterNot { it.isProtected }
        val resolved = nonProtected.filter { it.isResolved }.sortedBy { it.earliestSequence }

        val toPrune = mutableSetOf<String>()
        val orphanedToPrune = mutableSetOf<Long>()

        // 1. Resolved-run count cap applies only to resolved histories.
        if (resolved.size > MAX_RESOLVED_RUNS) {
            toPrune.addAll(
                resolved.take(resolved.size - MAX_RESOLVED_RUNS).map { it.runId },
            )
        }

        var remainingNonProtected = nonProtected
            .filter { it.runId !in toPrune }
            .sortedBy { it.earliestSequence }
        var remainingOrphaned = orphanedEvents.sortedBy { it.journalSequence }

        // 2. Age cap applies to every non-protected run history, including
        // incomplete runs with no terminal/reconciliation event.
        val ageThreshold = nowMillis - MAX_AGE_MS
        val oldRuns = remainingNonProtected.filter {
            it.earliestWallMillis > 0L && it.earliestWallMillis < ageThreshold
        }
        toPrune.addAll(oldRuns.map { it.runId })
        remainingNonProtected = remainingNonProtected.filter { it.runId !in toPrune }

        val oldOrphaned = remainingOrphaned.filter {
            it.recordedAtWallMillis > 0L &&
                it.recordedAtWallMillis < ageThreshold &&
                it.journalSequence !in protectedRecoverySeqs
        }
        orphanedToPrune.addAll(oldOrphaned.map { it.journalSequence })
        remainingOrphaned = remainingOrphaned.filter {
            it.journalSequence !in orphanedToPrune
        }

        // 3. Size cap counts all retained bytes. Protected histories can force
        // the journal above the cap, but every non-protected history remains
        // a pruning candidate, regardless of whether it has resolved yet.
        val protectedBytes = protected.sumOf { it.totalBytes }
        val protectedRecoveryBytes = remainingOrphaned
            .filter { it.journalSequence in protectedRecoverySeqs }
            .sumOf { eventByteSizes[it.journalSequence] ?: 0L }
        val nonProtectedOrphanBytes = remainingOrphaned
            .filter { it.journalSequence !in protectedRecoverySeqs }
            .sumOf { eventByteSizes[it.journalSequence] ?: 0L }
        val nonProtectedRunBytes = remainingNonProtected.sumOf { it.totalBytes }
        val totalBytes =
            protectedBytes + protectedRecoveryBytes + nonProtectedOrphanBytes + nonProtectedRunBytes

        if (totalBytes > MAX_SIZE_BYTES) {
            val excessBytes = totalBytes - MAX_SIZE_BYTES
            var prunedBytes = 0L

            for (run in remainingNonProtected.sortedBy { it.earliestSequence }) {
                if (prunedBytes >= excessBytes) break
                toPrune.add(run.runId)
                prunedBytes += run.totalBytes
            }
            remainingNonProtected = remainingNonProtected.filter { it.runId !in toPrune }

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

        val remainingResolvedCount = histories.count {
            it.isResolved && !it.isProtected && it.runId !in toPrune
        }
        val remainingPrunableBytes = remainingNonProtected.sumOf { it.totalBytes } +
            remainingOrphaned
                .filter {
                    it.journalSequence !in protectedRecoverySeqs &&
                        it.journalSequence !in orphanedToPrune
                }
                .sumOf { eventByteSizes[it.journalSequence] ?: 0L }
        val protectedTotalBytes = protectedBytes + protectedRecoveryBytes

        return RetentionResult(
            pruneRunIds = toPrune,
            pruneOrphanedSequences = orphanedToPrune,
            withinLimits = remainingResolvedCount <= MAX_RESOLVED_RUNS &&
                (protectedTotalBytes + remainingPrunableBytes <= MAX_SIZE_BYTES || remainingPrunableBytes == 0L),
        )
    }

    fun isRunProtected(events: List<RunEvent>): Boolean {
        val hasUnresolvedPhase = events.any { it.phase in UNRESOLVED_LIFECYCLE_PHASES }
        val hasTerminal = events.any { it.phase in TERMINAL_PHASES }
        val hasReconciled = events.any {
            it.phase == PhaseCode.RESTART_RECONCILED &&
                it.reconciliation?.resultingLifecycle in RESOLVED_RESULTING_LIFECYCLES
        }
        return hasUnresolvedPhase && !hasTerminal && !hasReconciled
    }
}
