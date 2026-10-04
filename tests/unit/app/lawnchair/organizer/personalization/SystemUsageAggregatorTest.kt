package app.lawnchair.organizer.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #203: the rank universe must be the **launchable app set only**.
 * Usage of system components or other non-launchable packages must never move
 * a launchable app's foreground bucket (spec #203 U-5, 2026-09-15 re-review
 * Blocking 2).
 */
class SystemUsageAggregatorTest {

    private fun interval(packageName: String, foregroundMs: Long, lastUsed: Long = 100L) = SystemUsageAggregator.RawInterval(packageName, foregroundMs, lastUsed)

    @Test
    fun nonLaunchableUsageIsExcludedBeforeBoundaryComputation() {
        val launchable = setOf("com.example.a", "com.example.b", "com.example.c", "com.example.d", "com.example.e")
        val launchableIntervals = listOf(
            interval("com.example.a", 100L),
            interval("com.example.b", 200L),
            interval("com.example.c", 300L),
            interval("com.example.d", 400L),
            interval("com.example.e", 500L),
        )

        val withoutNoise = SystemUsageAggregator.aggregate(launchableIntervals, launchable)
        val withNoise = SystemUsageAggregator.aggregate(
            launchableIntervals + listOf(
                interval("com.android.systemui", 1_000_000L),
                interval("com.google.android.gms", 2_000_000L),
                interval("android", 5_000_000L),
            ),
            launchable,
        )

        val boundariesWithoutNoise = SystemUsageAggregator.foregroundBoundaries(withoutNoise)
        val boundariesWithNoise = SystemUsageAggregator.foregroundBoundaries(withNoise)
        assertEquals(boundariesWithoutNoise, boundariesWithNoise)

        // The launchable apps' buckets are identical under both inputs.
        for (packageName in launchable) {
            assertEquals(
                PersonalizationBuckets.foregroundBucket(boundariesWithoutNoise!!, withoutNoise.getValue(packageName).totalForegroundMs).ordinal,
                PersonalizationBuckets.foregroundBucket(boundariesWithNoise!!, withNoise.getValue(packageName).totalForegroundMs).ordinal,
            )
        }
    }

    @Test
    fun aggregateKeepsOnlyLaunchablePackages() {
        val usage = SystemUsageAggregator.aggregate(
            listOf(
                interval("com.example.a", 100L),
                interval("com.android.systemui", 999L),
            ),
            setOf("com.example.a"),
        )

        assertEquals(setOf("com.example.a"), usage.keys)
        assertEquals(100L, usage.getValue("com.example.a").totalForegroundMs)
        assertEquals(1, usage.getValue("com.example.a").activeIntervals)
        assertNull(usage["com.android.systemui"])
    }

    @Test
    fun foregroundCarryingIntervalsDefineTheActiveDaysApproximation() {
        val usage = SystemUsageAggregator.aggregate(
            listOf(
                interval("com.example.a", 0L),
                interval("com.example.a", 50L),
                interval("com.example.a", 0L),
                interval("com.example.a", 70L),
            ),
            setOf("com.example.a"),
        )

        // Zero-foreground intervals are records but not active days.
        assertEquals(2, usage.getValue("com.example.a").activeIntervals)
        assertEquals(120L, usage.getValue("com.example.a").totalForegroundMs)
        assertEquals(100L, usage.getValue("com.example.a").lastUsedElapsedMs)
    }

    @Test
    fun zeroForegroundUniverseIsNotRankable() {
        val usage = SystemUsageAggregator.aggregate(
            listOf(
                interval("com.example.a", 0L),
                interval("com.example.b", 0L),
            ),
            setOf("com.example.a", "com.example.b", "com.example.c", "com.example.d", "com.example.e"),
        )

        assertNull(SystemUsageAggregator.foregroundBoundaries(usage))
    }
}
