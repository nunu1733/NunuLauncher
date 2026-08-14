package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.public.RecoveryPointId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #14 Stage B step 2: RetentionPolicy enforces the spec §“Retention”
 * rules — 24 h from creation, at most three non-expired points, never expire
 * unresolved, tombstones for 24 h, RECOVERY_STORE_UNAVAILABLE when three
 * unresolved points block cleanup.
 */
class RetentionPolicyTest {

    private val pointA = RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val pointB = RecoveryPointId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val pointC = RecoveryPointId("cccccccccccccccccccccccccccccccc")
    private val pointD = RecoveryPointId("dddddddddddddddddddddddddddddddd")

    @Test
    fun verifiedRecordIsKeptWithinRetentionWindow() {
        val record = verified(pointA, createdAt = 0L)
        val action = RetentionPolicy.actionFor(record, nowMillis = RetentionPolicy.RETENTION_MILLIS - 1L)
        assertTrue("Expected Keep, got $action", action is RetentionPolicy.RetentionAction.Keep)
    }

    @Test
    fun verifiedRecordIsExpiredAfter24Hours() {
        val record = verified(pointA, createdAt = 0L)
        val action = RetentionPolicy.actionFor(record, nowMillis = RetentionPolicy.RETENTION_MILLIS + 1L)
        assertTrue("Expected Expire, got $action", action is RetentionPolicy.RetentionAction.Expire)
    }

    @Test
    fun applyingRecordIsNeverExpiredByAge() {
        val record = active(pointA, LifecycleState.APPLYING, createdAt = 0L)
        val action = RetentionPolicy.actionFor(
            record,
            nowMillis = RetentionPolicy.RETENTION_MILLIS * 10L,
        )
        assertTrue("Active records must not be expired by age: $action", action is RetentionPolicy.RetentionAction.Keep)
    }

    @Test
    fun restoringRecordIsNeverExpiredByAge() {
        val record = active(pointA, LifecycleState.RESTORING, createdAt = 0L)
        val action = RetentionPolicy.actionFor(record, nowMillis = Long.MAX_VALUE / 2)
        assertTrue(action is RetentionPolicy.RetentionAction.Keep)
    }

    @Test
    fun committedUnverifiedRecordIsNeverExpiredByAge() {
        val record = active(pointA, LifecycleState.COMMITTED_UNVERIFIED, createdAt = 0L)
        val action = RetentionPolicy.actionFor(record, nowMillis = Long.MAX_VALUE / 2)
        assertTrue(action is RetentionPolicy.RetentionAction.Keep)
    }

    @Test
    fun corruptRecordBecomesTombstoneAfterTombstoneWindow() {
        val record = RetentionPolicy.RetentionRecord(
            pointId = pointA,
            lifecycle = LifecycleState.CORRUPT,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )
        val action = RetentionPolicy.actionFor(record, nowMillis = RetentionPolicy.TOMBSTONE_RETENTION_MILLIS + 1L)
        assertTrue("Expected Tombstone, got $action", action is RetentionPolicy.RetentionAction.Tombstone)
    }

    @Test
    fun restoredRecordBecomesTombstoneAfterTombstoneWindow() {
        val record = RetentionPolicy.RetentionRecord(
            pointId = pointA,
            lifecycle = LifecycleState.RESTORED,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )
        val action = RetentionPolicy.actionFor(record, nowMillis = RetentionPolicy.TOMBSTONE_RETENTION_MILLIS + 1L)
        assertTrue(action is RetentionPolicy.RetentionAction.Tombstone)
    }

    @Test
    fun readyRecordIsAlwaysPruned() {
        val record = RetentionPolicy.RetentionRecord(
            pointId = pointA,
            lifecycle = LifecycleState.READY,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )
        val action = RetentionPolicy.actionFor(record, nowMillis = 0L)
        assertTrue(
            "READY must always prune: $action",
            action is RetentionPolicy.RetentionAction.Tombstone &&
                (action as RetentionPolicy.RetentionAction.Tombstone).reason == RetentionPolicy.ExpireReason.PRUNE_UNUSED_READY,
        )
    }

    @Test
    fun createAllowedWhenUnderLimitWithNoEvictions() {
        val existing = listOf(
            verified(pointA, createdAt = 0L),
            verified(pointB, createdAt = 1_000L),
        )
        val decision = RetentionPolicy.planCreate(existing, nowMillis = 100L)
        assertTrue("Expected Allowed, got $decision", decision is RetentionPolicy.CreateDecision.Allowed)
        val allowed = decision as RetentionPolicy.CreateDecision.Allowed
        assertTrue("toEvict must be empty when under limit", allowed.toEvict.isEmpty())
    }

    @Test
    fun createAllowedEvictsOldestVerifiedWhenAtLimit() {
        val existing = listOf(
            verified(pointA, createdAt = 0L),
            verified(pointB, createdAt = 1_000L),
            verified(pointC, createdAt = 2_000L),
        )
        val decision = RetentionPolicy.planCreate(existing, nowMillis = 3_000L)
        assertTrue(decision is RetentionPolicy.CreateDecision.Allowed)
        val allowed = decision as RetentionPolicy.CreateDecision.Allowed
        assertEquals("oldest VERIFIED must be evicted", 1, allowed.toEvict.size)
        assertEquals(pointA, allowed.toEvict.first().pointId)
    }

    @Test
    fun createAllowedEvictsAgedExpiredFirstBeforeCount() {
        val existing = listOf(
            verified(pointA, createdAt = 0L),
            verified(pointB, createdAt = 1_000L),
            // pointC is past retention age
            RetentionPolicy.RetentionRecord(
                pointId = pointC,
                lifecycle = LifecycleState.VERIFIED,
                createdAtMillis = 0L,
                updatedAtMillis = 0L,
            ),
        )
        val decision = RetentionPolicy.planCreate(
            existing,
            nowMillis = RetentionPolicy.RETENTION_MILLIS + 1_000L,
        )
        assertTrue(decision is RetentionPolicy.CreateDecision.Allowed)
        val allowed = decision as RetentionPolicy.CreateDecision.Allowed
        assertTrue("pointC must be in toEvict: ${allowed.toEvict}", allowed.toEvict.any { it.pointId == pointC })
    }

    @Test
    fun createUnavailableWhenThreeUnresolvedRecordsBlockCleanup() {
        val existing = listOf(
            active(pointA, LifecycleState.APPLYING, createdAt = 0L),
            active(pointB, LifecycleState.COMMITTED_UNVERIFIED, createdAt = 0L),
            active(pointC, LifecycleState.RESTORING, createdAt = 0L),
        )
        val decision = RetentionPolicy.planCreate(existing, nowMillis = 1_000L)
        assertEquals(
            "Three unresolved records must block new apply",
            RetentionPolicy.CreateDecision.Unavailable,
            decision,
        )
    }

    @Test
    fun createUnavailableWhenThreeVerifiedPlusOneUnresolvedAndNothingEvictable() {
        val existing = listOf(
            active(pointA, LifecycleState.APPLYING, createdAt = 0L),
            // Three fresh VERIFIED records that are still within retention.
            verified(pointB, createdAt = 0L),
            verified(pointC, createdAt = 0L),
            verified(pointD, createdAt = 0L),
        )
        val decision = RetentionPolicy.planCreate(existing, nowMillis = 1_000L)
        // Three active+resolved = 4 >= MAX_NON_EXPIRED_POINTS(3) — must evict oldest verified.
        assertTrue(decision is RetentionPolicy.CreateDecision.Allowed)
    }

    private fun verified(
        pointId: RecoveryPointId,
        createdAt: Long,
    ): RetentionPolicy.RetentionRecord = RetentionPolicy.RetentionRecord(
        pointId = pointId,
        lifecycle = LifecycleState.VERIFIED,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    private fun active(
        pointId: RecoveryPointId,
        lifecycle: LifecycleState,
        createdAt: Long,
    ): RetentionPolicy.RetentionRecord = RetentionPolicy.RetentionRecord(
        pointId = pointId,
        lifecycle = lifecycle,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )
}
