package app.lawnchair.organizer.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #203: pure bucket contract tests. The nearest-rank quantile algorithm
 * and the recency boundaries are accepted spec values (U-5); the expected
 * values here are the interface contract for any implementation.
 */
class PersonalizationBucketsTest {

    @Test
    fun nearestRankBoundariesFollowCeilKTimesNOver5() {
        // Spec examples: N=5 → x_1..x_4, N=6 → x_2,x_3,x_4,x_5,
        // N=7 → x_2,x_3,x_5,x_6, N=11 → x_3,x_5,x_7,x_9 (1-based).
        assertEquals(listOf(10L, 20L, 30L, 40L), PersonalizationBuckets.nearestRankBoundaries(listOf(10L, 20L, 30L, 40L, 50L)))
        assertEquals(listOf(20L, 30L, 40L, 50L), PersonalizationBuckets.nearestRankBoundaries(listOf(10L, 20L, 30L, 40L, 50L, 60L)))
        assertEquals(listOf(20L, 30L, 50L, 60L), PersonalizationBuckets.nearestRankBoundaries(listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L)))
        assertEquals(listOf(30L, 50L, 70L, 90L), PersonalizationBuckets.nearestRankBoundaries(listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L, 110L)))
    }

    @Test
    fun universeBelowFiveIsNotRankable() {
        assertNull(PersonalizationBuckets.nearestRankBoundaries(emptyList()))
        assertNull(PersonalizationBuckets.nearestRankBoundaries(listOf(1L, 2L, 3L, 4L)))
    }

    @Test
    fun distinctUniverseOfFiveMapsAscendingValuesToBucketsZeroThroughFour() {
        val boundaries = checkNotNull(PersonalizationBuckets.nearestRankBoundaries(listOf(10L, 20L, 30L, 40L, 50L)))
        assertEquals(0, PersonalizationBuckets.foregroundBucket(boundaries, 10L).ordinal)
        assertEquals(1, PersonalizationBuckets.foregroundBucket(boundaries, 20L).ordinal)
        assertEquals(2, PersonalizationBuckets.foregroundBucket(boundaries, 30L).ordinal)
        assertEquals(3, PersonalizationBuckets.foregroundBucket(boundaries, 40L).ordinal)
        assertEquals(4, PersonalizationBuckets.foregroundBucket(boundaries, 50L).ordinal)
    }

    @Test
    fun boundaryEqualValuesClassifyToTheLowerBucket() {
        // bucket(v) = |{k : B_k < v}| — a value equal to a boundary joins the
        // bucket below it, keeping "same value → same bucket" deterministic.
        val boundaries = listOf(20L, 30L, 40L, 50L)
        assertEquals(0, PersonalizationBuckets.foregroundBucket(boundaries, 19L).ordinal)
        // A value equal to the smallest boundary joins bucket 0 (B_1 < v is false).
        assertEquals(0, PersonalizationBuckets.foregroundBucket(boundaries, 20L).ordinal)
        assertEquals(1, PersonalizationBuckets.foregroundBucket(boundaries, 21L).ordinal)
        assertEquals(1, PersonalizationBuckets.foregroundBucket(boundaries, 30L).ordinal)
        assertEquals(2, PersonalizationBuckets.foregroundBucket(boundaries, 40L).ordinal)
        assertEquals(4, PersonalizationBuckets.foregroundBucket(boundaries, Long.MAX_VALUE).ordinal)
    }

    @Test
    fun equalTotalsShareOneBucket() {
        val boundaries = checkNotNull(PersonalizationBuckets.nearestRankBoundaries(listOf(5L, 5L, 5L, 5L, 5L, 5L, 9L)))
        val bucketForTies = PersonalizationBuckets.foregroundBucket(boundaries, 5L)
        assertEquals(bucketForTies, PersonalizationBuckets.foregroundBucket(boundaries, 5L))
        assertTrue(PersonalizationBuckets.foregroundBucket(boundaries, 9L).ordinal > bucketForTies.ordinal)
    }

    @Test
    fun recencyClassesFollowTheSharedHalfOpenBoundaries() {
        val day = PersonalizationBuckets.DAY_MS
        assertEquals(0, PersonalizationBuckets.recencyClass(0L).ordinal)
        assertEquals(0, PersonalizationBuckets.recencyClass(day - 1).ordinal)
        assertEquals(1, PersonalizationBuckets.recencyClass(day).ordinal)
        assertEquals(1, PersonalizationBuckets.recencyClass(7 * day - 1).ordinal)
        assertEquals(2, PersonalizationBuckets.recencyClass(7 * day).ordinal)
        assertEquals(2, PersonalizationBuckets.recencyClass(30 * day - 1).ordinal)
        assertEquals(3, PersonalizationBuckets.recencyClass(30 * day).ordinal)
        assertEquals(3, PersonalizationBuckets.recencyClass(Long.MAX_VALUE / 2).ordinal)
    }

    @Test
    fun launcherOriginRecencyQuantizesTheSameBoundariesToCalendarDays() {
        // Day-anchor resolution: same day → [0,1d); 1–6 days ago → [1d,7d);
        // 7–29 days ago → [7d,30d); ≥30 days ago → [30d,∞).
        assertEquals(0, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 2000, todayEpochDay = 2000).ordinal)
        assertEquals(1, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 1999, todayEpochDay = 2000).ordinal)
        assertEquals(1, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 1994, todayEpochDay = 2000).ordinal)
        assertEquals(2, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 1993, todayEpochDay = 2000).ordinal)
        assertEquals(2, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 1971, todayEpochDay = 2000).ordinal)
        assertEquals(3, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 1970, todayEpochDay = 2000).ordinal)
        assertEquals(3, PersonalizationBuckets.launcherOriginRecencyClass(lastLaunchEpochDay = 1900, todayEpochDay = 2000).ordinal)
    }

    @Test
    fun activeDaysClassesCoverTheThirtyDayWindow() {
        assertEquals(0, PersonalizationBuckets.activeDaysClass(0).ordinal)
        assertEquals(1, PersonalizationBuckets.activeDaysClass(1).ordinal)
        assertEquals(1, PersonalizationBuckets.activeDaysClass(3).ordinal)
        assertEquals(2, PersonalizationBuckets.activeDaysClass(4).ordinal)
        assertEquals(2, PersonalizationBuckets.activeDaysClass(9).ordinal)
        assertEquals(3, PersonalizationBuckets.activeDaysClass(10).ordinal)
        assertEquals(3, PersonalizationBuckets.activeDaysClass(19).ordinal)
        assertEquals(4, PersonalizationBuckets.activeDaysClass(20).ordinal)
        assertEquals(4, PersonalizationBuckets.activeDaysClass(30).ordinal)
    }

    @Test
    fun launcherCountClassesAreLifetimeBoundedBuckets() {
        assertEquals(0, PersonalizationBuckets.launcherCountClass(0).ordinal)
        assertEquals(1, PersonalizationBuckets.launcherCountClass(1).ordinal)
        assertEquals(1, PersonalizationBuckets.launcherCountClass(4).ordinal)
        assertEquals(2, PersonalizationBuckets.launcherCountClass(5).ordinal)
        assertEquals(2, PersonalizationBuckets.launcherCountClass(19).ordinal)
        assertEquals(3, PersonalizationBuckets.launcherCountClass(20).ordinal)
        assertEquals(3, PersonalizationBuckets.launcherCountClass(99).ordinal)
        assertEquals(4, PersonalizationBuckets.launcherCountClass(100).ordinal)
        assertEquals(4, PersonalizationBuckets.launcherCountClass(10_000).ordinal)
    }
}
