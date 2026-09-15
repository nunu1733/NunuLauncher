package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId

/**
 * Issue #203: closed two-value entry field. Entry fields never carry `null` —
 * the fourth state does not exist (2026-09-15 review Blocking 1). Source
 * failure is expressed by section-level availability, never by a field value.
 */
sealed interface SignalField<out T> {
    data class Value<T>(val value: T) : SignalField<T>
    data object Absent : SignalField<Nothing>
}

/**
 * System usage section availability. `NOT_GRANTED` and `UNAVAILABLE` are kept
 * as distinct types for diagnostics and permission UI branching, but both mean
 * the system usage section is unavailable (sparse object contract). This state
 * never describes the launcher-origin section.
 */
enum class UsageAccessState {
    GRANTED,
    NOT_GRANTED,
    UNAVAILABLE,
}

enum class LauncherOriginAvailability {
    LAUNCHER_ORIGIN_AVAILABLE,
    LAUNCHER_ORIGIN_UNAVAILABLE,
}

/** Per-profile system usage availability (spec #203 U-6). */
enum class SystemUsageProfileAvailability {
    SYSTEM_USAGE_AVAILABLE,
    SYSTEM_USAGE_UNAVAILABLE,
}

/**
 * Common contract of every normalized signal value: a closed, small ordinal
 * range (no raw millisecond, absolute count, or timestamp).
 */
interface PersonalizationValue {
    val ordinal: Int
}

/** Relative foreground-time bucket within the window's rank universe (0 = least used). */
@JvmInline
value class ForegroundBucket(override val ordinal: Int) : PersonalizationValue {
    init {
        require(ordinal in 0..4)
    }
}

/**
 * Recency class on the shared half-open boundaries
 * `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)`. The launcher-origin section
 * projects the same boundaries through calendar-day quantization.
 */
@JvmInline
value class RecencyClass(override val ordinal: Int) : PersonalizationValue {
    init {
        require(ordinal in 0..3)
    }
}

/** Distinct active days observed inside the 30d window. */
@JvmInline
value class ActiveDaysClass(override val ordinal: Int) : PersonalizationValue {
    init {
        require(ordinal in 0..4)
    }
}

/** Lifetime cumulative launcher-origin launch count bucket (spec #203 U-3). */
@JvmInline
value class LauncherCountClass(override val ordinal: Int) : PersonalizationValue {
    init {
        require(ordinal in 0..4)
    }
}

data class PersonalizationEntryKey(
    val profile: ProfileId,
    val packageName: PackageName,
)

data class SystemUsageEntry(
    val foreground30dBucket: SignalField<ForegroundBucket>,
    val foreground7dBucket: SignalField<ForegroundBucket>,
    val recencyBucket: SignalField<RecencyClass>,
    val activeDaysBucket: SignalField<ActiveDaysClass>,
)

data class LauncherOriginEntry(
    val countClass: SignalField<LauncherCountClass>,
    val recencyClass: SignalField<RecencyClass>,
)

/**
 * Section-level structural absence is a sealed value, never `null` (spec #203,
 * 2026-09-15 (3rd) review Blocking 1). `Unavailable` carries no package-level
 * entries: the availability states of the snapshot carry the semantics.
 */
sealed interface SystemUsageSection {
    data class Available(val entries: Map<PersonalizationEntryKey, SystemUsageEntry>) : SystemUsageSection
    data object Unavailable : SystemUsageSection
}

sealed interface LauncherOriginSection {
    data class Available(val entries: Map<PersonalizationEntryKey, LauncherOriginEntry>) : LauncherOriginSection
    data object Unavailable : LauncherOriginSection
}
