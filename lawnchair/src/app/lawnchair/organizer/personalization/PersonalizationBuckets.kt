package app.lawnchair.organizer.personalization

/**
 * Issue #203: pure bucket computation for the personalization snapshot.
 * Deterministic functions of the raw aggregates and the single window anchor;
 * no clock access, no Android types (purity guard, spec AC-6).
 */
object PersonalizationBuckets {

    const val DAY_MS: Long = 24L * 60 * 60 * 1000
    const val WINDOW_30D_MS: Long = 30 * DAY_MS
    const val WINDOW_7D_MS: Long = 7 * DAY_MS

    /** Below this universe size the relative foreground buckets are not rankable. */
    const val MIN_RANK_UNIVERSE = 5

    /**
     * Nearest-rank quintile boundaries of the rank universe (spec #203 U-5):
     * ascending totals `x_1 ≤ … ≤ x_N`, boundary `B_k = x_⌈k·N/5⌉` (k = 1..4).
     * Returns `null` when the universe is smaller than [MIN_RANK_UNIVERSE] —
     * the caller emits `Absent` (not rankable) for that window.
     */
    fun nearestRankBoundaries(foregroundTotalsMs: Collection<Long>): List<Long>? {
        if (foregroundTotalsMs.size < MIN_RANK_UNIVERSE) return null
        val sorted = foregroundTotalsMs.sorted()
        return (1..4).map { k ->
            val n = sorted.size
            val oneBased = (k * n + 4) / 5
            sorted[oneBased - 1]
        }
    }

    /**
     * `bucket(v) = |{k : B_k < v}|` ∈ 0..4 — boundary-equal values classify to
     * the lower bucket, so distinct N=5 universes map `x_1→0 … x_5→4` and
     * equal totals always share a bucket (2026-09-15 (2nd)/(4th) review).
     */
    fun foregroundBucket(boundaries: List<Long>, totalMs: Long): ForegroundBucket = ForegroundBucket(boundaries.count { it < totalMs })

    /** Shared half-open recency boundaries `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)`. */
    fun recencyClass(elapsedMs: Long): RecencyClass = when {
        elapsedMs < DAY_MS -> RecencyClass(0)
        elapsedMs < 7 * DAY_MS -> RecencyClass(1)
        elapsedMs < 30 * DAY_MS -> RecencyClass(2)
        else -> RecencyClass(3)
    }

    /**
     * Launcher-origin recency: the same boundaries quantized to calendar days
     * (the persisted day anchor has day resolution). `todayEpochDay` comes from
     * the composition's single window anchor.
     */
    fun launcherOriginRecencyClass(lastLaunchEpochDay: Long, todayEpochDay: Long): RecencyClass {
        val elapsedDays = (todayEpochDay - lastLaunchEpochDay).coerceAtLeast(0)
        return recencyClass(elapsedDays * DAY_MS)
    }

    /** Observed active days → 0 / 1–3 / 4–9 / 10–19 / 20–30. */
    fun activeDaysClass(observedDays: Int): ActiveDaysClass = when {
        observedDays <= 0 -> ActiveDaysClass(0)
        observedDays <= 3 -> ActiveDaysClass(1)
        observedDays <= 9 -> ActiveDaysClass(2)
        observedDays <= 19 -> ActiveDaysClass(3)
        else -> ActiveDaysClass(4)
    }

    /** Lifetime cumulative launch count → 0 / 1–4 / 5–19 / 20–99 / ≥100 (spec #203 U-3). */
    fun launcherCountClass(count: Int): LauncherCountClass = when {
        count <= 0 -> LauncherCountClass(0)
        count <= 4 -> LauncherCountClass(1)
        count <= 19 -> LauncherCountClass(2)
        count <= 99 -> LauncherCountClass(3)
        else -> LauncherCountClass(4)
    }
}
