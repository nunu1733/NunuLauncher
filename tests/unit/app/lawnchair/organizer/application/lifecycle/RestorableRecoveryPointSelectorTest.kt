package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RemainingWindow
import app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining
import app.lawnchair.organizer.application.public.RemainingWindow.LessThanOneHour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #376 (spec D1/RS-AC-02): pure selection of the restore entry — the
 * latest checksum-valid `VERIFIED` record within retention, with a
 * deterministic tie-break and a coarse floored remaining window.
 */
class RestorableRecoveryPointSelectorTest {

    private fun id(vararg chars: Char) = RecoveryPointId(chars.joinToString("").padEnd(32, '0'))

    private fun record(
        pointId: RecoveryPointId,
        createdAtMs: Long,
        lifecycle: LifecycleState = LifecycleState.VERIFIED,
        checksumValid: Boolean = true,
    ) = RestorableRecoveryPointSelector.CandidateRecord(
        pointId = pointId,
        lifecycle = lifecycle,
        createdAtMs = createdAtMs,
        checksumValid = checksumValid,
    )

    @Test
    fun selectsTheSingleValidVerifiedRecord() {
        val point = id('b')

        val entry = RestorableRecoveryPointSelector.select(
            records = listOf(record(point, createdAtMs = 0L)),
            nowMs = 0L,
        )

        assertEquals(point, entry?.pointId)
    }

    @Test
    fun selectsTheLatestOfMultipleVerifiedRecords() {
        val newest = id('c')

        val entry = RestorableRecoveryPointSelector.select(
            records = listOf(
                record(id('a'), createdAtMs = 1_000L),
                record(newest, createdAtMs = 9_000_000L),
                record(id('b'), createdAtMs = 5_000_000L),
            ),
            nowMs = 9_000_000L,
        )

        assertEquals(newest, entry?.pointId)
    }

    @Test
    fun breaksCreatedAtTiesByTheDeterministicPointIdOrder() {
        val low = id('1')
        val high = id('f')

        val entry = RestorableRecoveryPointSelector.select(
            records = listOf(record(low, createdAtMs = 5_000L), record(high, createdAtMs = 5_000L)),
            nowMs = 5_000L,
        )

        assertEquals(high, entry?.pointId)
    }

    @Test
    fun excludesNonVerifiedAndChecksumInvalidAndLapsedRecords() {
        val nowMs = RetentionPolicy.RETENTION_MILLIS + 1L
        val entry = RestorableRecoveryPointSelector.select(
            records = listOf(
                record(id('a'), createdAtMs = nowMs, lifecycle = LifecycleState.COMMITTED_UNVERIFIED),
                record(id('b'), createdAtMs = nowMs, checksumValid = false),
                record(id('c'), createdAtMs = 1L),
                record(id('d'), createdAtMs = nowMs, lifecycle = LifecycleState.RESTORED),
            ),
            nowMs = nowMs,
        )

        assertNull(entry)
    }

    @Test
    fun retentionBoundaryIsInclusiveAtExactlyTwentyFourHours() {
        // createdAt + 24h > now must hold: now == createdAt+24h-1ms survives,
        // now == createdAt+24h does not.
        val point = id('a')
        val inside = RestorableRecoveryPointSelector.select(
            records = listOf(record(point, createdAtMs = 0L)),
            nowMs = RetentionPolicy.RETENTION_MILLIS - 1,
        )
        val outside = RestorableRecoveryPointSelector.select(
            records = listOf(record(point, createdAtMs = 0L)),
            nowMs = RetentionPolicy.RETENTION_MILLIS,
        )

        assertEquals(point, inside?.pointId)
        assertNull(outside)
    }

    @Test
    fun remainingWindowFloorsWholeHoursAndClampsToTheRetentionBudget() {
        // 24h remaining right after creation.
        val fresh = RestorableRecoveryPointSelector.select(
            records = listOf(record(id('a'), createdAtMs = 0L)),
            nowMs = 0L,
        )
        // 3h 59m 59… remaining floors to 3.
        val partial = RestorableRecoveryPointSelector.select(
            records = listOf(record(id('a'), createdAtMs = 0L)),
            nowMs = RetentionPolicy.RETENTION_MILLIS - (4 * 3_600_000L) + 1,
        )
        // Exactly one hour remains.
        val oneHour = RestorableRecoveryPointSelector.select(
            records = listOf(record(id('a'), createdAtMs = 0L)),
            nowMs = RetentionPolicy.RETENTION_MILLIS - 3_600_000L,
        )

        assertEquals(HoursRemaining(24), fresh?.remainingWindow)
        assertEquals(HoursRemaining(3), partial?.remainingWindow)
        assertEquals(HoursRemaining(1), oneHour?.remainingWindow)
    }

    @Test
    fun remainingWindowUnderOneHourIsItsOwnClosedValue() {
        val almostGone = RestorableRecoveryPointSelector.select(
            records = listOf(record(id('a'), createdAtMs = 0L)),
            nowMs = RetentionPolicy.RETENTION_MILLIS - 1,
        )

        assertEquals(LessThanOneHour, almostGone?.remainingWindow)
        assertTrue(almostGone?.remainingWindow is RemainingWindow.LessThanOneHour)
    }

    @Test
    fun emptySelectionIsAClosedNull() {
        assertNull(RestorableRecoveryPointSelector.select(records = emptyList(), nowMs = 0L))
    }
}
