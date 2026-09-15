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
import app.lawnchair.organizer.personalization.SystemUsageAggregator
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
        // Spec #203 / 2026-09-15 re-review Blocking 1: the grant predicate is
        // the app-op semantics shared with the Settings surface, not a bare
        // permission check.
        if (!UsageAccess.isGranted(appContext)) {
            return SystemUsageRead(
                usageAccess = UsageAccessState.NOT_GRANTED,
                profileAvailability = request.profiles.associateWith { SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE },
                systemUsage = SystemUsageSection.Unavailable,
            )
        }
        val window30StartMs = windowAnchorElapsedMs - 30 * PersonalizationBuckets.DAY_MS
        val window7StartMs = windowAnchorElapsedMs - 7 * PersonalizationBuckets.DAY_MS
        val profileReads = request.profiles.associateWith { profile ->
            readProfile(
                profile,
                request.launchablePackages[profile].orEmpty().map { it.value }.toSet(),
                window30StartMs,
                window7StartMs,
                windowAnchorElapsedMs,
            )
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
                val usage = profileRead.usage30[packageName.value]
                val usage7 = profileRead.usage7[packageName.value]
                entries[PersonalizationEntryKey(profile, packageName)] = SystemUsageEntry(
                    foreground30dBucket = foregroundField(usage?.totalForegroundMs, profileRead.boundaries30d),
                    foreground7dBucket = foregroundField(usage7?.totalForegroundMs, profileRead.boundaries7d),
                    recencyBucket = usage?.lastUsedElapsedMs
                        ?.let { elapsedUsedMs: Long -> SignalField.Value(recencyClass(windowAnchorElapsedMs - elapsedUsedMs)) }
                        ?: SignalField.Absent,
                    activeDaysBucket = usage
                        ?.let { SignalField.Value(PersonalizationBuckets.activeDaysClass(minOf(it.activeIntervals, MAX_ACTIVE_DAYS))) }
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
     * per-package aggregates plus the two nearest-rank boundaries. The rank
     * universe is filtered to the profile's launchable package set — non-
     * launchable package usage must not move a launchable app's bucket (spec
     * U-5, 2026-09-15 re-review Blocking 2). Returns `null` when the profile
     * is unreadable (fail closed, spec U-6).
     */
    private fun readProfile(
        profile: app.lawnchair.organizer.planning.ProfileId,
        launchablePackageNames: Set<String>,
        window30StartMs: Long,
        window7StartMs: Long,
        windowAnchorMs: Long,
    ): ProfileUsageRead? = try {
        val serial = profile.value.toLongOrNull() ?: return null
        val user = userCache.getUserForSerialNumber(serial)
        if (userCache.getSerialNumberForUser(user) != serial) return null
        val statsManager = usageStatsManagerFor(user) ?: return null
        // Rolling windows per the U-5 probe amendment: aggregate the returned
        // intervals as-is (no edge filtering); each interval is a ~24h bucket.
        val usage30 = SystemUsageAggregator.aggregate(
            statsManager
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, window30StartMs, windowAnchorMs)
                .orEmpty()
                .map {
                    SystemUsageAggregator.RawInterval(it.packageName, it.totalTimeInForeground, it.lastTimeUsed)
                },
            launchablePackageNames,
        )
        val usage7 = SystemUsageAggregator.aggregate(
            statsManager
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, window7StartMs, windowAnchorMs)
                .orEmpty()
                .map {
                    SystemUsageAggregator.RawInterval(it.packageName, it.totalTimeInForeground, it.lastTimeUsed)
                },
            launchablePackageNames,
        )
        ProfileUsageRead(
            usage30 = usage30,
            usage7 = usage7,
            boundaries30d = SystemUsageAggregator.foregroundBoundaries(usage30),
            boundaries7d = SystemUsageAggregator.foregroundBoundaries(usage7),
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

    private data class ProfileUsageRead(
        val usage30: Map<String, SystemUsageAggregator.PackageUsage>,
        val usage7: Map<String, SystemUsageAggregator.PackageUsage>,
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
