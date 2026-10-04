package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for non-protected incomplete run retention. */
class RetentionIncompleteRunTest {

    private val now = 1_000_000_000_000L
    private val old = now - 8L * 24L * 60L * 60L * 1000L

    private fun event(
        sequence: Long,
        phase: PhaseCode,
        runId: String,
        wallMillis: Long = now,
    ) = RunEvent(
        journalSequence = sequence,
        recordedAtWallMillis = wallMillis,
        phase = phase,
        runId = runId,
    )

    @Test
    fun oldRunStartedOnlyRunIsPrunableByAge() {
        val runId = "11111111111111111111111111111111"
        val events = listOf(event(1L, PhaseCode.RUN_STARTED, runId, old))

        val result = RetentionPolicy.evaluate(
            events = events,
            eventByteSizes = mapOf(1L to 100L),
            nowMillis = now,
        )

        assertTrue(
            "Incomplete RUN_STARTED-only history must be age-prunable",
            runId in result.pruneRunIds,
        )
    }

    @Test
    fun checkpointedReadyRunIsNotTreatedAsProtectedRecoveryHistory() {
        val runId = "22222222222222222222222222222222"
        val events = listOf(
            event(1L, PhaseCode.RUN_STARTED, runId, old),
            event(2L, PhaseCode.CHECKPOINTED, runId, old),
        )

        assertFalse(
            "CHECKPOINTED/READY without APPLY_COMMITTED is not a protected unresolved lifecycle",
            RetentionPolicy.isRunProtected(events),
        )

        val result = RetentionPolicy.evaluate(
            events = events,
            eventByteSizes = mapOf(1L to 100L, 2L to 100L),
            nowMillis = now,
        )
        assertTrue(
            "Old CHECKPOINTED/READY history must remain age-prunable",
            runId in result.pruneRunIds,
        )
    }

    @Test
    fun incompleteRunBytesCountTowardSizeCapAndArePrunedBeforeProtectedHistory() {
        val incompleteRunId = "33333333333333333333333333333333"
        val protectedRunId = "44444444444444444444444444444444"
        val events = listOf(
            event(1L, PhaseCode.RUN_STARTED, incompleteRunId),
            event(2L, PhaseCode.APPLY_COMMITTED, protectedRunId),
        )
        val sizes = mapOf(
            1L to 300_000L,
            2L to 300_000L,
        )

        val result = RetentionPolicy.evaluate(events, sizes, now)

        assertTrue(
            "Incomplete non-protected history bytes must participate in size pruning",
            incompleteRunId in result.pruneRunIds,
        )
        assertFalse(
            "Protected unresolved history must survive size pruning",
            protectedRunId in result.pruneRunIds,
        )
    }
}
