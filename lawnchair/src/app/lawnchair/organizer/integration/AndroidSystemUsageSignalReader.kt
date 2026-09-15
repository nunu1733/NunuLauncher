package app.lawnchair.organizer.integration

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import app.lawnchair.organizer.personalization.ActiveDaysClass
import app.lawnchair.organizer.personalization.ForegroundBucket
import app.lawnchair.organizer.personalization.PersonalizationBuckets
import app.lawnchair.organizer.personalization.PersonalizationEntryKey
import app.lawnchair.organizer.personalization.RecencyClass
import app.lawnchair.organizer.personalization.SignalField
import app.lawnchair.organizer.personalization.SystemUsageEntry
import app.lawnchair.organizer.personalization.SystemUsageProfileAvailability
import app.lawnchair.organizer.personalization.SystemUsageSection
import app.lawnchair.organizer.personalization.UsageAccessState
import app.lawnchair.organizer.personalization.UsageSignalRequest
import com.android.launcher3.pm.UserCache

/**
 * Issue #203: Android-only system usage reader. The usage access permission is
 * an app-op (`PACKAGE_USAGE_STATS`, not a runtime permission — see the
 * `SuggestionsPreference` precedent); an absent grant is the typed
 * `NOT_GRANTED` fast path that never queries. `GRANTED` still queries
 * per profile and fails closed per profile (`SYSTEM_USAGE_UNAVAILABLE`, spec
 * U-6): a launcher UID cannot read usage stats of another profile unless the
 * platform grants it there.
 *
 * Windows follow the U-5 probe amendment (2026-09-15,
 * `docs/assessment/pr-321-u5-probe-evidence.md`): the platform's
 * `INTERVAL_DAILY` intervals are ~24h rolling buckets that do not align to
 * local calendar days, so windows are rolling `[anchor − N·24h, anchor]`
 * ranges and the returned intervals are aggregated as-is — no edge filtering,
 * which would drop the majority of intervals. The 30d and 7d aggregates come
 * from two separate queries; the probe observed no interval extending beyond
 * the requested range and no overlap, so each sum is faithful. Raw millisecond
 * aggregates never leave this reader; only normalized buckets do.
 */
class AndroidSystemUsageSignalReader(
    private val appContext: Context,
    private val userCache: UserCache = UserCache.INSTANCE.get(appContext),
) {

    fun read(request: UsageSignalRequest, windowAnchorElapsedMs: Long): SystemUsageRead {
        val granted = appContext.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return SystemUsageRead(
                usageAccess = UsageAccessState.NOT_GRANTED,
                profileAvailability = request.profiles.associateWith { SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE },
                systemUsage = SystemUsageSection.Unavailable,
            )
        }
        val window30StartMs = windowAnchorElapsedMs - 30 * PersonalizationBuckets.DAY_MS
        val window7StartMs = windowAnchorElapsedMs - 7 * PersonalizationBuckets.DAY_MS
        val profileReads = request.profiles.associateWith { profile ->
            readProfile(profile, window30StartMs, window7StartMs, windowAnchorElapsedMs)
        }
        val anyProfileAvailable = profileReads.values.any { it != null }
        val profileAvailability = profileReads.mapValues { (_, read) ->
            if (read == null) SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE else SystemUsageProfileAvailability.SYSTEM_USAGE_AVAILABLE
        }
        if (!anyProfileAvailable) {
            return SystemUsageRead(
                usageAccess = UsageAccessState.UNAVAILABLE,
                profileAvailability = profileAvailability,
                systemUsage = SystemUsageSection.Unavailable,
            )
        }
        val entries = mutableMapOf<PersonalizationEntryKey, SystemUsageEntry>()
        for (profile in request.profiles) {
            val profileRead = profileReads[profile] ?: continue
            for (packageName in request.launchablePackages[profile].orEmpty()) {
                val aggregates = profileRead.perPackage[packageName]
                entries[PersonalizationEntryKey(profile, packageName)] = SystemUsageEntry(
                    foreground30dBucket = foregroundField(aggregates?.total30dMs, profileRead.boundaries30d),
                    foreground7dBucket = foregroundField(aggregates?.total7dMs, profileRead.boundaries7d),
                    recencyBucket = aggregates?.lastUsedElapsedMs
                        ?.let { elapsedUsedMs: Long -> SignalField.Value(recencyClass(windowAnchorElapsedMs - elapsedUsedMs)) }
                        ?: SignalField.Absent,
                    activeDaysBucket = aggregates
                        ?.let { SignalField.Value(PersonalizationBuckets.activeDaysClass(minOf(it.activeDays, MAX_ACTIVE_DAYS))) }
                        ?: SignalField.Absent,
                )
            }
        }
        return SystemUsageRead(
            usageAccess = UsageAccessState.GRANTED,
            profileAvailability = profileAvailability,
            systemUsage = SystemUsageSection.Available(entries),
        )
    }

    private fun recencyClass(elapsedMs: Long): RecencyClass = PersonalizationBuckets.recencyClass(elapsedMs)

    /**
     * Foreground buckets exist only for packages inside the window's rank
     * universe (usage record present); everyone else is `Absent`. The universe
     * is the window's usage records among launchable apps — never the request
     * set alone.
     */
    private fun foregroundField(totalMs: Long?, boundaries: List<Long>?): SignalField<ForegroundBucket> {
        if (totalMs == null || boundaries == null) return SignalField.Absent
        return SignalField.Value(PersonalizationBuckets.foregroundBucket(boundaries, totalMs))
    }

    /**
     * Queries one profile's 30d and 7d usage windows and computes the
     * per-package aggregates plus the two nearest-rank boundaries. Returns
     * `null` when the profile is unreadable (fail closed, spec U-6).
     */
    private fun readProfile(
        profile: app.lawnchair.organizer.planning.ProfileId,
        window30StartMs: Long,
        window7StartMs: Long,
        windowAnchorMs: Long,
    ): ProfileUsageRead? = try {
        val serial = profile.value.toLongOrNull() ?: return null
        val user = userCache.getUserForSerialNumber(serial)
        if (userCache.getSerialNumberForUser(user) != serial) return null
        val statsManager = usageStatsManagerFor(user) ?: return null
        val aggregates = mutableMapOf<app.lawnchair.organizer.planning.PackageName, MutablePackageUsageAggregates>()
        // Rolling windows per the U-5 probe amendment: aggregate the returned
        // intervals as-is (no edge filtering); each interval is a ~24h bucket.
        for (stats in statsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, window30StartMs, windowAnchorMs).orEmpty()) {
            val packageName = app.lawnchair.organizer.planning.PackageName(stats.packageName)
            val aggregate = aggregates.getOrPut(packageName) { MutablePackageUsageAggregates() }
            if (stats.totalTimeInForeground > 0) {
                aggregate.total30dMs += stats.totalTimeInForeground
                aggregate.activeDays += 1
            }
            val lastUsed = stats.lastTimeUsed
            if (lastUsed > 0 && lastUsed > (aggregate.lastUsedElapsedMs ?: 0)) aggregate.lastUsedElapsedMs = lastUsed
        }
        for (stats in statsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, window7StartMs, windowAnchorMs).orEmpty()) {
            val packageName = app.lawnchair.organizer.planning.PackageName(stats.packageName)
            if (stats.totalTimeInForeground > 0) {
                aggregates.getOrPut(packageName) { MutablePackageUsageAggregates() }.total7dMs += stats.totalTimeInForeground
            }
        }
        // Rank universes are per window over packages with a usage record.
        val universe30 = aggregates.values.filter { it.total30dMs > 0 }.map { it.total30dMs }
        val universe7 = aggregates.values.filter { it.total7dMs > 0 }.map { it.total7dMs }
        ProfileUsageRead(
            perPackage = aggregates,
            boundaries30d = PersonalizationBuckets.nearestRankBoundaries(universe30),
            boundaries7d = PersonalizationBuckets.nearestRankBoundaries(universe7),
        )
    } catch (_: Exception) {
        null
    }

    private fun usageStatsManagerFor(user: android.os.UserHandle): UsageStatsManager? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return appContext.createContextAsUser(user, 0).getSystemService(UsageStatsManager::class.java)
        }
        return if (user == android.os.Process.myUserHandle()) {
            appContext.getSystemService(UsageStatsManager::class.java)
        } else {
            null
        }
    }

    /** Raw per-package aggregates; raw milliseconds never leave this reader. */
    private data class MutablePackageUsageAggregates(
        var total30dMs: Long = 0,
        var total7dMs: Long = 0,
        var activeDays: Int = 0,
        var lastUsedElapsedMs: Long? = null,
    )

    private data class ProfileUsageRead(
        val perPackage: Map<app.lawnchair.organizer.planning.PackageName, MutablePackageUsageAggregates>,
        val boundaries30d: List<Long>?,
        val boundaries7d: List<Long>?,
    )

    data class SystemUsageRead(
        val usageAccess: UsageAccessState,
        val profileAvailability: Map<app.lawnchair.organizer.planning.ProfileId, SystemUsageProfileAvailability>,
        val systemUsage: SystemUsageSection,
    )

    private companion object {
        /** Active-days are bucketed with 30 as the ceiling (30d window). */
        const val MAX_ACTIVE_DAYS = 30
    }
}
