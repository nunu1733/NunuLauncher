package app.lawnchair.organizer.personalization

/**
 * Issue #203: pure aggregation of the raw usage intervals into per-package
 * usage and the rank-universe boundaries. The rank universe is the **launchable
 * app set only** (spec #203 U-5): usage of system components or other
 * non-launchable packages must never move a launchable app's bucket, so the
 * aggregation filters the input to the launchable package set before any
 * boundary computation (2026-09-15 re-review Blocking 2).
 */
object SystemUsageAggregator {

    data class RawInterval(
        val packageName: String,
        val foregroundMs: Long,
        val lastUsedElapsedMs: Long,
    )

    data class PackageUsage(
        val totalForegroundMs: Long,
        /** Foreground-carrying interval count — the active-days approximation. */
        val activeIntervals: Int,
        val lastUsedElapsedMs: Long?,
    )

    /**
     * Aggregates the raw intervals of one query window, restricted to
     * [launchablePackages]. Non-launchable packages are dropped before any
     * aggregation, not merely filtered from the output.
     */
    fun aggregate(
        intervals: List<RawInterval>,
        launchablePackages: Set<String>,
    ): Map<String, PackageUsage> {
        val usage = mutableMapOf<String, MutablePackageUsage>()
        for (interval in intervals) {
            if (interval.packageName !in launchablePackages) continue
            val packageUsage = usage.getOrPut(interval.packageName) { MutablePackageUsage() }
            if (interval.foregroundMs > 0) {
                packageUsage.totalForegroundMs += interval.foregroundMs
                packageUsage.activeIntervals += 1
            }
            if (interval.lastUsedElapsedMs > (packageUsage.lastUsedElapsedMs ?: 0)) {
                packageUsage.lastUsedElapsedMs = interval.lastUsedElapsedMs
            }
        }
        return usage.mapValues { (_, value) ->
            PackageUsage(
                totalForegroundMs = value.totalForegroundMs,
                activeIntervals = value.activeIntervals,
                lastUsedElapsedMs = value.lastUsedElapsedMs,
            )
        }
    }

    /**
     * The window's foreground rank universe: foreground totals of launchable
     * packages with at least one foreground-carrying interval.
     */
    fun foregroundUniverse(aggregates: Map<String, PackageUsage>): List<Long> = aggregates.values.filter { it.totalForegroundMs > 0 }.map { it.totalForegroundMs }

    /** Nearest-rank quintile boundaries over [foregroundUniverse]; `null` if not rankable. */
    fun foregroundBoundaries(aggregates: Map<String, PackageUsage>): List<Long>? = PersonalizationBuckets.nearestRankBoundaries(foregroundUniverse(aggregates))

    private data class MutablePackageUsage(
        var totalForegroundMs: Long = 0,
        var activeIntervals: Int = 0,
        var lastUsedElapsedMs: Long? = null,
    )
}
