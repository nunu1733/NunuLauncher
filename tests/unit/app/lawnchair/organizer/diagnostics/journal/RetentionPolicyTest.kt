package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RecoveryContext
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
        runId: String? = "00000000000000000000000000000000",
        wallMillis: Long = now,
    ): RunEvent {
        val actualRunId = if (runId == "00000000000000000000000000000000") {
            java.lang.String.format("%032d", java.lang.Long.valueOf(seq))
        } else {
            runId
        }
        return RunEvent(journalSequence = seq, recordedAtWallMillis = wallMillis, phase = phase, runId = actualRunId)
    }

    private fun byteSizes(events: List<RunEvent>, size: Long = 100L): Map<Long, Long> = events.associate { it.journalSequence to size }

    @Test
    fun keepUpTo10ResolvedRuns() {
        // 12 resolved runs, each a RUN_STARTED + APPLY_VERIFIED pair sharing one runId.
        val events = (1L..12L).flatMap { run ->
            val runId = java.lang.String.format("%032x", run)
            listOf(
                event(run * 10 + 1, PhaseCode.RUN_STARTED, runId),
                event(run * 10 + 2, PhaseCode.APPLY_VERIFIED, runId),
            )
        }
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertEquals(
            "Exactly the two oldest runs must be pruned",
            setOf(
                java.lang.String.format("%032x", 1L),
                java.lang.String.format("%032x", 2L),
            ),
            result.pruneRunIds.toSet(),
        )
        val remaining = events.filter { it.runId !in result.pruneRunIds }
        assertEquals("10 resolved runs must remain", 10, remaining.mapNotNull { it.runId }.distinct().size)
    }

    @Test
    fun keepOnlyRecentRuns() {
        val old = now - 8L * 24L * 60L * 60L * 1000L
        val events = listOf(
            event(1, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000099", old),
            event(2, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000098", now),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertTrue(result.pruneRunIds.contains("00000000000000000000000000000099"))
        assertFalse(result.pruneRunIds.contains("00000000000000000000000000000098"))
    }

    @Test
    fun pruneOldestWhenExceedingSizeLimit() {
        val events = (1L..10L).map { event(it, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000000") }
        val sizes = events.associate { it.journalSequence to 100_000L }
        val result = RetentionPolicy.evaluate(events, sizes, now)
        assertTrue(result.pruneRunIds.contains("00000000000000000000000000000001"))
        val remaining = events.filter { it.runId !in result.pruneRunIds }
        assertTrue(remaining.sumOf { sizes[it.journalSequence] ?: 0L } <= RetentionPolicy.MAX_SIZE_BYTES)
    }

    @Test
    fun unresolvedRunWithApplyCommittedIsProtected() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "00000000000000000000000000000097"),
            event(2, PhaseCode.APPLY_COMMITTED, "00000000000000000000000000000097"),
            event(3, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000096"),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertFalse("Protected run must not be pruned", result.pruneRunIds.contains("00000000000000000000000000000097"))
    }

    @Test
    fun terminalFailureApplyUnresolvedIsNotProtected() {
        // APPLY_UNRESOLVED is a terminal failure — the run is resolved, not protected.
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            event(2, PhaseCode.APPLY_UNRESOLVED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            event(3, PhaseCode.APPLY_VERIFIED, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events.filter { it.runId == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }))
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // run-a is resolved and eligible for pruning
    }

    @Test
    fun terminalFailureApplyRecoveryFailedIsNotProtected() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            event(2, PhaseCode.APPLY_RECOVERY_FAILED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events))
    }

    @Test
    fun terminalFailureApplyRecoveredIsNotProtected() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            event(2, PhaseCode.APPLY_RECOVERED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events))
    }

    @Test
    fun unresolvedRunWithRestartReconciledIsResolved() {
        val events = listOf(
            event(1, PhaseCode.RUN_STARTED, "00000000000000000000000000000096"),
            event(2, PhaseCode.APPLY_COMMITTED, "00000000000000000000000000000096"),
            event(3, PhaseCode.RESTART_RECONCILED, "00000000000000000000000000000096"),
            event(4, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000095"),
        )
        assertFalse(RetentionPolicy.isRunProtected(events.filter { it.runId == "00000000000000000000000000000096" }))
    }

    @Test
    fun orphanedEventsPrunedByAge() {
        // Old orphaned events (no runId) should be pruned by age.
        val old = now - 10L * 24L * 60L * 60L * 1000L // 10 days ago
        val events = listOf(
            event(1, PhaseCode.CAPTURED, null, old), // old orphaned
            event(2, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002"), // recent resolved
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
            event(2, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002"),
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
        events.add(event(seq++, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000099", old))
        for (i in 1..10) events.add(event(seq++, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000000"))
        events.add(event(seq, PhaseCode.APPLY_COMMITTED, "00000000000000000000000000000093"))
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        assertTrue(result.pruneRunIds.contains("00000000000000000000000000000099"))
        assertFalse(result.pruneRunIds.contains("00000000000000000000000000000093"))
    }

    @Test
    fun isRunProtectedDetectsUnresolved() {
        assertTrue(RetentionPolicy.isRunProtected(listOf(event(1, PhaseCode.RUN_STARTED, "00000000000000000000000000000094"), event(2, PhaseCode.APPLY_COMMITTED, "00000000000000000000000000000094"))))
    }

    @Test
    fun isRunProtectedFalseForResolved() {
        assertFalse(RetentionPolicy.isRunProtected(listOf(event(1, PhaseCode.RUN_STARTED, "00000000000000000000000000000094"), event(2, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000094"))))
    }

    @Test
    fun isRunProtectedFalseForReconciled() {
        assertFalse(
            RetentionPolicy.isRunProtected(
                listOf(
                    event(1, PhaseCode.RUN_STARTED, "00000000000000000000000000000094"),
                    event(2, PhaseCode.APPLY_COMMITTED, "00000000000000000000000000000094"),
                    event(3, PhaseCode.RESTART_RECONCILED, "00000000000000000000000000000094"),
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
                RetentionPolicy.isRunProtected(listOf(event(1, PhaseCode.RUN_STARTED, "00000000000000000000000000000094"), event(2, phase, "00000000000000000000000000000094"))),
            )
        }
    }

    // --- In-flight recovery protection (Item 4) ---

    private fun recoveryEvent(
        seq: Long,
        phase: PhaseCode,
        pointId: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        originRunId: String? = "11111111111111111111111111111111",
        wallMillis: Long = now,
    ): RunEvent = RunEvent(
        journalSequence = seq,
        recordedAtWallMillis = wallMillis,
        phase = phase,
        runId = null,
        recovery = RecoveryContext(pointId = pointId, pointOriginRunId = originRunId),
    )

    @Test
    fun recoveryRequestedWithoutTerminalSurvivesAgePruning() {
        // Old RECOVERY_REQUESTED without a terminal recovery event must survive 7-day age pruning.
        val old = now - 10L * 24L * 60L * 60L * 1000L // 10 days ago
        val events = listOf(
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "cccccccccccccccccccccccccccccccc", wallMillis = old),
            event(2, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002", now), // recent resolved
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // The old RECOVERY_REQUESTED must NOT be pruned (no terminal for that pointId)
        assertFalse("In-flight RECOVERY_REQUESTED must not be pruned by age", result.pruneOrphanedSequences.contains(1L))
        assertEquals("Only the resolved run should be eligible, not pruned", emptySet<String>(), result.pruneRunIds)
    }

    @Test
    fun recoveryRequestedWithoutTerminalSurvivesSizePruning() {
        // RECOVERY_REQUESTED without terminal must survive size-based pruning.
        // Create a single RECOVERY_REQUESTED that is 600 KiB (over the 512 KiB limit).
        val events = listOf(
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "cccccccccccccccccccccccccccccccc"),
        )
        val sizes = mapOf(1L to 600_000L) // 600 KiB > 512 KiB
        val result = RetentionPolicy.evaluate(events, sizes, now)
        // In-flight RECOVERY_REQUESTED must NOT be pruned even though it exceeds the size cap
        assertFalse("In-flight RECOVERY_REQUESTED must not be pruned by size", result.pruneOrphanedSequences.contains(1L))
    }

    @Test
    fun recoveryRequestedWithTerminalIsPrunable() {
        // RECOVERY_REQUESTED with a terminal RECOVERY_RESTORED for the same pointId is prunable.
        val old = now - 10L * 24L * 60L * 60L * 1000L // 10 days ago
        val events = listOf(
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "cccccccccccccccccccccccccccccccc", wallMillis = old),
            recoveryEvent(2, PhaseCode.RECOVERY_RESTORED, "cccccccccccccccccccccccccccccccc", wallMillis = old),
            event(3, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002", now),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // Both old recovery events should be pruned by age (they have a terminal)
        assertTrue("Old RECOVERY_REQUESTED with terminal must be pruned by age", result.pruneOrphanedSequences.contains(1L))
        assertTrue("Old RECOVERY_RESTORED must be pruned by age", result.pruneOrphanedSequences.contains(2L))
    }

    @Test
    fun recoveryRequestedWithTerminalIsPrunableBySize() {
        // RECOVERY_REQUESTED with terminal RECOVERY_FAILED is prunable by size.
        // Both events are 300 KiB each = 600 KiB total, exceeding the 512 KiB cap.
        // The oldest (seq 1) is pruned first, bringing the total under the cap.
        val events = listOf(
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "cccccccccccccccccccccccccccccccc"),
            recoveryEvent(2, PhaseCode.RECOVERY_FAILED, "cccccccccccccccccccccccccccccccc"),
        )
        val sizes = mapOf(1L to 300_000L, 2L to 300_000L) // 600 KiB total > 512 KiB
        val result = RetentionPolicy.evaluate(events, sizes, now)
        // Since both are resolved (have terminal), they are prunable.
        // The oldest (seq 1) is pruned to bring size under the cap.
        assertTrue("Oldest resolved recovery event must be pruned by size", result.pruneOrphanedSequences.contains(1L))
        // seq 2 may or may not be pruned depending on how much space is needed.
        // After pruning seq 1 (300 KiB), remaining is 300 KiB < 512 KiB, so seq 2 stays.
        assertFalse("Newer resolved recovery event stays under cap", result.pruneOrphanedSequences.contains(2L))
    }

    @Test
    fun recoveryRequestedForDifferentPointIdsMixedProtection() {
        // One pointId has terminal, another doesn't. Only the unresolved one is protected.
        val old = now - 10L * 24L * 60L * 60L * 1000L
        val events = listOf(
            // Point A: in-flight (no terminal) — protected
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", wallMillis = old),
            // Point B: resolved (has terminal) — prunable
            recoveryEvent(2, PhaseCode.RECOVERY_REQUESTED, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", wallMillis = old),
            recoveryEvent(3, PhaseCode.RECOVERY_RESTORED, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", wallMillis = old),
            event(4, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002", now),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // Point A: not pruned (protected)
        assertFalse("In-flight RECOVERY_REQUESTED for point A must not be pruned", result.pruneOrphanedSequences.contains(1L))
        // Point B: pruned by age (resolved)
        assertTrue("Resolved RECOVERY_REQUESTED for point B must be pruned", result.pruneOrphanedSequences.contains(2L))
        assertTrue("Resolved RECOVERY_RESTORED for point B must be pruned", result.pruneOrphanedSequences.contains(3L))
    }

    // --- RESTART_RECONCILED resolves recovery point protection (Item 2) ---

    @Test
    fun recoveryRequestedWithRestartReconciledIsNotProtected() {
        // RECOVERY_REQUESTED with pointId X, followed by RESTART_RECONCILED
        // with the same pointId — the RECOVERY_REQUESTED should be prunable
        // because RESTART_RECONCILED resolves the in-flight recovery.
        val old = now - 10L * 24L * 60L * 60L * 1000L // 10 days ago
        val events = listOf(
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", wallMillis = old),
            RunEvent(
                journalSequence = 2L,
                recordedAtWallMillis = old,
                phase = PhaseCode.RESTART_RECONCILED,
                runId = "11111111111111111111111111111111",
                pointId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ),
            event(3, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002", now),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // The RECOVERY_REQUESTED should be prunable by age since RESTART_RECONCILED
        // resolves the protection for the same pointId
        assertTrue(
            "RECOVERY_REQUESTED must be pruned when RESTART_RECONCILED resolves the point",
            result.pruneOrphanedSequences.contains(1L),
        )
    }

    @Test
    fun recoveryRequestedWithoutMatchingRestartReconciledRemainsProtected() {
        // RECOVERY_REQUESTED with pointId X, RESTART_RECONCILED with different
        // pointId Y — the RECOVERY_REQUESTED for X remains protected.
        val old = now - 10L * 24L * 60L * 60L * 1000L // 10 days ago
        val events = listOf(
            recoveryEvent(1, PhaseCode.RECOVERY_REQUESTED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", wallMillis = old),
            RunEvent(
                journalSequence = 2L,
                recordedAtWallMillis = old,
                phase = PhaseCode.RESTART_RECONCILED,
                runId = "11111111111111111111111111111111",
                pointId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", // different point
            ),
            event(3, PhaseCode.APPLY_VERIFIED, "00000000000000000000000000000002", now),
        )
        val result = RetentionPolicy.evaluate(events, byteSizes(events), now)
        // The RECOVERY_REQUESTED for point A must remain protected
        // because the RESTART_RECONCILED resolves a different point
        assertFalse(
            "RECOVERY_REQUESTED must remain protected when RESTART_RECONCILED is for a different point",
            result.pruneOrphanedSequences.contains(1L),
        )
    }
}
