package app.lawnchair.organizer.integration

import android.content.Context
import android.content.SharedPreferences
import app.lawnchair.organizer.personalization.LauncherOriginAvailability
import app.lawnchair.organizer.personalization.LauncherOriginEntry
import app.lawnchair.organizer.personalization.LauncherOriginSection
import app.lawnchair.organizer.personalization.PersonalizationBuckets
import app.lawnchair.organizer.personalization.PersonalizationEntryKey
import app.lawnchair.organizer.personalization.SignalField
import app.lawnchair.organizer.personalization.UsageSignalRequest
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import java.util.Locale

/**
 * Issue #203: app-private persistence of the launcher-origin launch counter.
 * The persisted state is the minimum per (profile, package): the lifetime
 * cumulative count and a coarse absolute day anchor (epoch day in the device's
 * current timezone). Relative recency classes are never persisted; they are
 * projected at snapshot read time from the anchor (2026-09-14 review Required).
 *
 * The counter store is local-only and excluded from backup/restore and export
 * (spec #203 U-3). The snapshot itself is never persisted.
 */
interface LauncherOriginLaunchStore {
    /** Records one accepted launcher-origin launch (dispatch-time observation). */
    fun record(profile: ProfileId, packageName: PackageName, epochDay: Long)

    /** Returns the persisted state, or `null` when no launch was ever observed. */
    fun read(profile: ProfileId, packageName: PackageName): LauncherOriginRecord?

    /** User-visible erase (spec #203 U-3): every counter returns to no observation. */
    fun clear()

    /** Raised on unparseable persisted state; readers fail closed on this. */
    class CorruptCounterStateException : IllegalStateException()
}

data class LauncherOriginRecord(val count: Int, val lastLaunchEpochDay: Long)

class LauncherOriginLaunchCounterStore private constructor(private val prefs: SharedPreferences) : LauncherOriginLaunchStore {

    /** Records one accepted launcher-origin launch (dispatch-time observation). */
    override fun record(profile: ProfileId, packageName: PackageName, epochDay: Long) {
        val key = storageKey(profile, packageName)
        val current = readRecord(prefs, key)
        val nextCount = (current?.count ?: 0) + 1
        val nextAnchor = current?.lastLaunchEpochDay?.coerceAtLeast(epochDay) ?: epochDay
        prefs.edit().putString(key, encode(nextCount, nextAnchor)).apply()
    }

    /** Returns the persisted state, or `null` when no launch was ever observed. */
    override fun read(profile: ProfileId, packageName: PackageName): LauncherOriginRecord? = readRecord(prefs, storageKey(profile, packageName))

    /** User-visible erase (spec #203 U-3): every counter returns to no observation. */
    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun storageKey(profile: ProfileId, packageName: PackageName): String = "${profile.value}|${packageName.value}".lowercase(Locale.US)

    private fun readRecord(prefs: SharedPreferences, key: String): LauncherOriginRecord? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split(SEPARATOR)
        val count = parts.getOrNull(0)?.toIntOrNull() ?: throw LauncherOriginLaunchStore.CorruptCounterStateException()
        val epochDay = parts.getOrNull(1)?.toLongOrNull() ?: throw LauncherOriginLaunchStore.CorruptCounterStateException()
        return LauncherOriginRecord(count, epochDay)
    }

    private fun encode(count: Int, epochDay: Long): String = "$count$SEPARATOR$epochDay"

    companion object {
        private const val SEPARATOR = ":"
        private const val PREFS_NAME = "organizer_launcher_origin_v1"

        fun from(appContext: Context): LauncherOriginLaunchCounterStore = LauncherOriginLaunchCounterStore(
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
}

/**
 * Issue #203: reads the launcher-origin counter store and projects the
 * persisted minimum state into the normalized entry fields. Pure projection
 * apart from the store access; the recency class is derived from the stored
 * day anchor and the composition window anchor, never from a persisted class.
 */
class LauncherOriginSignalReader(private val store: LauncherOriginLaunchStore) {

    /**
     * Total: never throws. Counter corruption fails closed to
     * [LauncherOriginSection.Unavailable] (spec #203 — no speculative repair).
     */
    fun read(request: UsageSignalRequest, todayEpochDay: Long): LauncherOriginSection = try {
        val entries = mutableMapOf<PersonalizationEntryKey, LauncherOriginEntry>()
        for (profile in request.profiles) {
            for (packageName in request.launchablePackages[profile].orEmpty()) {
                val key = PersonalizationEntryKey(profile, packageName)
                val record = store.read(profile, packageName)
                entries[key] = LauncherOriginEntry(
                    countClass = if (record == null) {
                        SignalField.Absent
                    } else {
                        SignalField.Value(PersonalizationBuckets.launcherCountClass(record.count))
                    },
                    recencyClass = if (record == null) {
                        SignalField.Absent
                    } else {
                        SignalField.Value(
                            PersonalizationBuckets.launcherOriginRecencyClass(record.lastLaunchEpochDay, todayEpochDay),
                        )
                    },
                )
            }
        }
        LauncherOriginSection.Available(entries)
    } catch (_: LauncherOriginLaunchStore.CorruptCounterStateException) {
        LauncherOriginSection.Unavailable
    }

    /** The launcher-origin availability the reader itself can guarantee. */
    fun availability(): LauncherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE
}
