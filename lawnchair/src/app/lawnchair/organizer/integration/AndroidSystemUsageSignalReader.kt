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
import java.time.Instant
import java.time.ZoneId

/**
 * Issue #203: Android-only system usage reader. The usage access permission is
 * an app-op (`PACKAGE_USAGE_STATS`, not a runtime permission — see the
 * `SuggestionsPreference` precedent); an absent grant is the typed
 * `NOT_GRANTED` fast path that never queries. `GRANTED` still queries
 * per profile and fails closed per profile (`SYSTEM_USAGE_UNAVAILABLE`, spec
 * U-6): a launcher UID cannot read usage stats of another profile unless the
 * platform grants it there.
 *
 * The read honors the single window anchor passed by the aggregator — no clock
 * access of its own, so two reads on one anchor are byte-identical. Raw
 * millisecond aggregates never leave this reader; only normalized buckets do.
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
        val zone = ZoneId.systemDefault()
        val anchorDay = Instant.ofEpochMilli(windowAnchorElapsedMs).atZone(zone).toLocalDate()
        val window30StartMs = anchorDay.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        val window7StartMs = anchorDay.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
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
                        ?.let { SignalField.Value(PersonalizationBuckets.activeDaysClass(it.activeDays)) }
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
     * Queries one profile's daily usage stats and computes the per-package
     * aggregates plus the two nearest-rank boundaries. Returns `null` when the
     * profile is unreadable (fail closed, spec U-6).
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
        val intervals = statsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            window30StartMs,
            windowAnchorMs,
        ).orEmpty()
        val aggregates = mutableMapOf<app.lawnchair.organizer.planning.PackageName, MutablePackageUsageAggregates>()
        for (stats in intervals) {
            // Full-containment filter: partial edge intervals are excluded so
            // the aggregation stays deterministic across interval alignment
            // (spec #203 U-5; the boundary-day undercount is a known limitation).
            if (!isContained(stats, window30StartMs, windowAnchorMs)) continue
            val packageName = app.lawnchair.organizer.planning.PackageName(stats.packageName)
            val aggregate = aggregates.getOrPut(packageName) { MutablePackageUsageAggregates() }
            if (stats.totalTimeInForeground > 0) {
                aggregate.total30dMs += stats.totalTimeInForeground
                aggregate.activeDays += 1
                if (isContained(stats, window7StartMs, windowAnchorMs)) aggregate.total7dMs += stats.totalTimeInForeground
            }
            val lastUsed = stats.lastTimeUsed
            if (lastUsed > 0 && lastUsed > (aggregate.lastUsedElapsedMs ?: 0)) aggregate.lastUsedElapsedMs = lastUsed
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

    private fun isContained(stats: UsageStats, windowStartMs: Long, windowAnchorMs: Long): Boolean = stats.firstTimeStamp >= windowStartMs && stats.lastTimeStamp <= windowAnchorMs

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
}
