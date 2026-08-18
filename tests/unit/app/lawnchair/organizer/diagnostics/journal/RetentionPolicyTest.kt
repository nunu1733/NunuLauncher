package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-06: Retention lifecycle tests.
 */
class RetentionPolicyTest {

    private val now = 1_000_000_000_000L

    private fun event(
        seq: Long,
        phase: PhaseCode,
        runId: String? = "run-$seq",
        wallMillis: Long = now,
    ) = RunEvent(journalSequence = seq, recordedAtWallMillis = wallMillis, phase = phase, runId = runId)

    private fun byteSizes(events: List<RunEvent>, size: Long = 100L): Map<Long, Long> = events.associate { it.journalSequence to size }

    @Test
    fun keepUpTo10ResolvedRuns() {
        val events = (1L..12L).flatMap { seq ->
            listOf(
                event(seq * 10 + 1, PhaseCode.RUN_STARTED, "run-$seq"),
                event(seq * 10 + 2, PhaseCode.APPLY_VERIFIED, "run-$seq"),
            )
        }
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertEquals(setOf("run-1", "run-2"), result.pruneRunIds)
    }

    @Test
    fun keepOnlyRecentRuns() {
        val old = now - 8L * 24L * 60L * 60L * 1000L
        val events = listOf(
            event(1, PhaseCode.APPLY_VERIFIED, "old-run", old),
            event(2, PhaseCode.APPLY_VERIFIED, "recent-run", now),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertTrue(result.pruneRunIds.contains("old-run"))
        assertFalse(result.pruneRunIds.contains("recent-run"))
    }

    @Test
    fun pruneOldestWhenExceedingSizeLimit() {
        val events = (1L..10L).map { event(it, PhaseCode.APPLY_VERIFIED, "run-$it") }
        val sizes = events.associate { it.journalSequence to 100_000L }
        val result = RetentionPolicy.evaluate(events, sizes, now)
        assertTrue(result.pruneRunIds.contains("run-1"))
        val remaining = events.filter { it.runId !in result.pruneRunIds }
        assertTrue(remaining.sumOf { sizes[it.journalSequence] ?: 0L } <= RetentionPolicy.MAX_SIZE_BYTES)
    }

    @Test
    fun unresolvedRunWithApplyCommittedIsProtected() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "protected-run"),
            event(2, PhaseCode.APPLY_COMMITTED, "protected-run"),
            event(3, PhaseCode.APPLY_VERIFIED, "resolved-run"),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertFalse("Protected run must not be pruned", result.pruneRunIds.contains("protected-run"))
    }

    @Test
    fun terminalFailureApplyUnresolvedIsNotProtected() {
        // APPLY_UNRESOLVED is a terminal failure — the run is resolved, not protected.
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "run-a"),
            event(2, PhaseCode.APPLY_UNRESOLVED, "run-a"),
            event(3, PhaseCode.APPLY_VERIFIED, "run-b"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events.filter { it.runId == "run-a" }))
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // run-a is resolved and eligible for pruning
    }

    @Test
    fun terminalFailureApplyRecoveryFailedIsNotProtected() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "run-a"),
            event(2, PhaseCode.APPLY_RECOVERY_FAILED, "run-a"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events))
    }

    @Test
    fun terminalFailureApplyRecoveredIsNotProtected() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "run-a"),
            event(2, PhaseCode.APPLY_RECOVERED, "run-a"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events))
    }

    @Test
    fun unresolvedRunWithRestartReconciledIsResolved() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "resolved-run"),
            event(2, PhaseCode.APPLY_COMMITTED, "resolved-run"),
            event(3, PhaseCode.RESTART_RECONCILED, "resolved-run"),
            event(4, PhaseCode.APPLY_VERIFIED, "other-run"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events.filter { it.runId == "resolved-run" }))
    }

    @Test
    fun orphanedEventsPrunedByAge() {
        // Old orphaned events (no runId) should be pruned by age.
        val old = now - 10L * 24L * 60L * 60L * 1000L // 10 days ago
        val events = listOf(
            event(1, PhaseCode.CAPTURED, null, old), // old orphaned
            event(2, PhaseCode.APPLY_VERIFIED, "run-2"), // recent resolved
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // The old orphaned event should be in pruneOrphanedSequences
        assertTrue("Old orphaned event must be pruned by age", result.pruneOrphanedSequences.contains(1L))
        // The resolved run should not be pruned
        assertEquals(emptySet<String>(), result.pruneRunIds)
    }

    @Test
    fun orphanedEventsPrunedBySizeOverflow() {
        // Only an orphaned event exceeding the cap — no eligible runs to prune first.
        val events = listOf(
            event(1, PhaseCode.CAPTURED, null), // orphaned, 600 KiB
        )
        val sizes = mapOf(1L to 600_000L) // 600 KiB > 512 KiB
        val result = RetentionPolicy.evaluate(events, sizes, now)
        // The orphaned event must be pruned to bring size under cap
        assertTrue("Orphaned event must be pruned by size overflow", result.pruneOrphanedSequences.contains(1L))
    }

    @Test
    fun orphanedEventsAreNotProtected() {
        // Orphaned events (no runId) are never protected.
        val events = listOf(
            event(1, PhaseCode.CAPTURED, null),
            event(2, PhaseCode.APPLY_VERIFIED, "run-2"),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // No run IDs to prune (orphaned events are tracked separately)
        assertEquals(0, result.pruneRunIds.size)
    }

    @Test
    fun retentionRespectsAllCaps() {
        val events = mutableListOf<RunEvent>()
        var seq = 1L
        val old = now - 10L * 24L * 60L * 60L * 1000L
        events.add(event(seq++, PhaseCode.APPLY_VERIFIED, "old-run", old))
        for (i in 1..10) events.add(event(seq++, PhaseCode.APPLY_VERIFIED, "recent-$i"))
        events.add(event(seq, PhaseCode.APPLY_COMMITTED, "protected"))
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertTrue(result.pruneRunIds.contains("old-run"))
        assertFalse(result.pruneRunIds.contains("protected"))
    }

    @Test
    fun isRunProtectedDetectsUnresolved() {
        assertTrue(RetentionPolicy.isRunProtected(listOf(event(1, PhaseCode.RUN_STARTED, "test"), event(2, PhaseCode.APPLY_COMMITTED, "test"))))
    }

    @Test
    fun isRunProtectedFalseForResolved() {
        assertFalse(RetentionPolicy.isRunProtected(listOf(event(1, PhaseCode.RUN_STARTED, "test"), event(2, PhaseCode.APPLY_VERIFIED, "test"))))
    }

    @Test
    fun isRunProtectedFalseForReconciled() {
        assertFalse(
            RetentionPolicy.isRunProtected(
                listOf(
                    event(1, PhaseCode.RUN_STARTED, "test"),
                    event(2, PhaseCode.APPLY_COMMITTED, "test"),
                    event(3, PhaseCode.RESTART_RECONCILED, "test"),
                ),
            ),
        )
    }

    @Test
    fun isRunProtectedFalseForTerminalFailurePhases() {
        // APPLY_UNRESOLVED, APPLY_RECOVERY_FAILED, APPLY_RECOVERED are terminal — not protected
        for (phase in listOf(PhaseCode.APPLY_UNRESOLVED, PhaseCode.APPLY_RECOVERY_FAILED, PhaseCode.APPLY_RECOVERED)) {
            assertFalse(
                "Terminal phase $phase must not be protected",
                RetentionPolicy.isRunProtected(listOf(event(1, PhaseCode.RUN_STARTED, "test"), event(2, phase, "test"))),
            )
        }
    }
}
