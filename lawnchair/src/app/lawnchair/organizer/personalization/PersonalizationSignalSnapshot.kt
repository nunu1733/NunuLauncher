package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.rules.sha256Canonical

/**
 * Issue #203: the normalized, provenance-bearing personalization signal
 * snapshot. The snapshot is ephemeral (rebuilt at every composition) and is
 * identified content-addressed — it holds no generation. [contentDigest] is
 * derived from [canonicalRepresentation], so the digest always identifies
 * exactly the consumer-observable projection of this object (sparse object
 * contract: unavailable sections carry no package-level entries).
 *
 * Planner and future AI adapters read this snapshot only; they never reach the
 * Android usage APIs.
 */
data class PersonalizationSignalSnapshot(
    val usageAccess: UsageAccessState,
    val launcherOriginAvailability: LauncherOriginAvailability,
    val profileAvailability: Map<ProfileId, SystemUsageProfileAvailability>,
    val systemUsage: SystemUsageSection,
    val launcherOrigin: LauncherOriginSection,
) {
    val schemaVersion: String get() = SCHEMA_VERSION

    val contentDigest: String by lazy { sha256Canonical(canonicalRepresentation()) }

    fun policyIdentity(): PolicyInputIdentity = PolicyInputIdentity(
        PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT,
        SCHEMA_VERSION,
        contentDigest,
    )

    /**
     * Canonical rows: header row, launcher-origin availability row, one row per
     * profile availability, and one row per entry field of available sections.
     * Rows are sorted and newline-joined (spec #203 canonicalization grammar).
     */
    fun canonicalRepresentation(): String {
        val rows = mutableListOf<String>()
        rows += "header|$SCHEMA_VERSION|${usageAccess.name}|${launcherOriginAvailability.name}"
        for ((profile, availability) in profileAvailability) {
            rows += "profile|${profile.value}|${availability.name}"
        }
        if (systemUsage is SystemUsageSection.Available) {
            for ((key, entry) in systemUsage.entries) {
                entry.foreground30dBucket.appendRow(rows, key, "foreground30d", SOURCE_SYSTEM_USAGE)
                entry.foreground7dBucket.appendRow(rows, key, "foreground7d", SOURCE_SYSTEM_USAGE)
                entry.recencyBucket.appendRow(rows, key, "recency", SOURCE_SYSTEM_USAGE)
                entry.activeDaysBucket.appendRow(rows, key, "activeDays", SOURCE_SYSTEM_USAGE)
            }
        }
        if (launcherOrigin is LauncherOriginSection.Available) {
            for ((key, entry) in launcherOrigin.entries) {
                entry.countClass.appendRow(rows, key, "count", SOURCE_LAUNCHER_ORIGIN)
                entry.recencyClass.appendRow(rows, key, "launcherRecency", SOURCE_LAUNCHER_ORIGIN)
            }
        }
        return rows.sorted().joinToString("\n")
    }

    private fun SignalField<*>.appendRow(
        rows: MutableList<String>,
        key: PersonalizationEntryKey,
        fieldName: String,
        source: String,
    ) {
        val state = when (this) {
            is SignalField.Value<*> -> "Value"
            SignalField.Absent -> "Absent"
        }
        val value = (this as? SignalField.Value<PersonalizationValue>)?.value?.ordinal?.toString() ?: "-"
        rows += "entry|${key.profile.value}|${key.packageName.value}|$fieldName|$state|$value|$source"
    }

    companion object {
        const val SCHEMA_VERSION = "personalization-signals-v1"
        const val SOURCE_SYSTEM_USAGE = "SYSTEM_USAGE_V1"
        const val SOURCE_LAUNCHER_ORIGIN = "LAUNCHER_ORIGIN_V1"

        /**
         * The all-unavailable snapshot: a valid value for every `Ready`
         * composition (spec #203 — never `null`). Used when no source is wired
         * or the source itself failed; the profile availability stays explicit.
         */
        fun unavailable(profiles: Set<ProfileId> = emptySet()): PersonalizationSignalSnapshot = PersonalizationSignalSnapshot(
            usageAccess = UsageAccessState.UNAVAILABLE,
            launcherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_UNAVAILABLE,
            profileAvailability = profiles.associateWith { SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE },
            systemUsage = SystemUsageSection.Unavailable,
            launcherOrigin = LauncherOriginSection.Unavailable,
        )
    }
}

/**
 * The single per-composition read request. `launchablePackages` is derived
 * from the capture and the selection only — never from the run's request set
 * alone — and the rank universe inside the source is independent of it
 * (spec #203 U-5: the request set must not move existing buckets).
 */
data class UsageSignalRequest(
    val profiles: Set<ProfileId>,
    val launchablePackages: Map<ProfileId, Set<PackageName>>,
)
