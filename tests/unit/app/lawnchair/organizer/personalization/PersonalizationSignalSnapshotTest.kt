package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.PolicySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #203: snapshot identity and canonicalization contract tests. The
 * digest is derived from the canonical rows of the object itself, so equal
 * objects share a digest and the digest identifies the consumer-observable
 * projection completely (2026-09-15 review Blocking 2 / sparse object
 * contract).
 */
class PersonalizationSignalSnapshotTest {

    private val profile = ProfileId("0")
    private val otherProfile = ProfileId("10")
    private val key = PersonalizationEntryKey(profile, PackageName("com.example.a"))
    private val otherKey = PersonalizationEntryKey(profile, PackageName("com.example.b"))

    private fun fullSystemUsage(): SystemUsageEntry = SystemUsageEntry(
        foreground30dBucket = SignalField.Value(ForegroundBucket(2)),
        foreground7dBucket = SignalField.Value(ForegroundBucket(1)),
        recencyBucket = SignalField.Value(RecencyClass(0)),
        activeDaysBucket = SignalField.Value(ActiveDaysClass(3)),
    )

    private fun launcherOriginEntry(): LauncherOriginEntry = LauncherOriginEntry(
        countClass = SignalField.Value(LauncherCountClass(2)),
        recencyClass = SignalField.Value(RecencyClass(1)),
    )

    private fun grantedSnapshot(
        systemUsage: SystemUsageSection = SystemUsageSection.Available(mapOf(key to fullSystemUsage())),
        launcherOrigin: LauncherOriginSection = LauncherOriginSection.Available(mapOf(key to launcherOriginEntry())),
    ) = PersonalizationSignalSnapshot(
        usageAccess = UsageAccessState.GRANTED,
        launcherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE,
        profileAvailability = mapOf(profile to SystemUsageProfileAvailability.SYSTEM_USAGE_AVAILABLE),
        systemUsage = systemUsage,
        launcherOrigin = launcherOrigin,
    )

    @Test
    fun digestIsDeterministicAndIdentityIsContentAddressed() {
        val first = grantedSnapshot()
        val second = grantedSnapshot()
        assertEquals(first, second)
        assertEquals(first.contentDigest, second.contentDigest)
        assertEquals(first.canonicalRepresentation(), second.canonicalRepresentation())

        val identity = first.policyIdentity()
        assertEquals(PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT, identity.source)
        assertEquals("personalization-signals-v1", identity.versionOrGeneration)
        assertEquals(first.contentDigest, identity.sha256)
    }

    @Test
    fun notGrantedAndQueryFailureAreDifferentDigests() {
        val notGranted = grantedSnapshot().copy(
            usageAccess = UsageAccessState.NOT_GRANTED,
            systemUsage = SystemUsageSection.Unavailable,
            profileAvailability = mapOf(profile to SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE),
        )
        val unavailable = notGranted.copy(usageAccess = UsageAccessState.UNAVAILABLE)
        assertNotEquals(notGranted.contentDigest, unavailable.contentDigest)
    }

    @Test
    fun sectionUnavailableAndAllAbsentAreDifferentDigests() {
        val unavailableSection = grantedSnapshot(systemUsage = SystemUsageSection.Unavailable).copy(
            profileAvailability = mapOf(profile to SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE),
        )
        val availableAllAbsent = grantedSnapshot(
            systemUsage = SystemUsageSection.Available(
                mapOf(key to SystemUsageEntry(SignalField.Absent, SignalField.Absent, SignalField.Absent, SignalField.Absent)),
            ),
        )
        assertNotEquals(unavailableSection.contentDigest, availableAllAbsent.contentDigest)
    }

    @Test
    fun launcherOriginEntriesSurviveNotGranted() {
        // 2026-09-14 review Blocking 2: denying usage access must not erase the
        // launcher-origin signal — only the system usage section degrades.
        val notGranted = grantedSnapshot().copy(
            usageAccess = UsageAccessState.NOT_GRANTED,
            systemUsage = SystemUsageSection.Unavailable,
            profileAvailability = mapOf(profile to SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE),
        )
        val launcherOrigin = notGranted.launcherOrigin as LauncherOriginSection.Available
        assertEquals(LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE, notGranted.launcherOriginAvailability)
        assertEquals(launcherOriginEntry(), launcherOrigin.entries[key])
    }

    @Test
    fun partialAvailabilityIsExpressibleWithoutNulls() {
        // system usage unavailable + launcher-origin available: the sealed
        // section types express the structural absence (2026-09-15 (3rd)
        // review Blocking 1).
        val partial = grantedSnapshot(systemUsage = SystemUsageSection.Unavailable)
        assertTrue(partial.systemUsage is SystemUsageSection.Unavailable)
        val origin = partial.launcherOrigin
        assertTrue(origin is LauncherOriginSection.Available)
        val availableOrigin = origin as LauncherOriginSection.Available
        assertTrue(availableOrigin.entries[key] != null)
    }

    @Test
    fun differentPackageSetsOfAnUnavailableSectionShareTheDigestAndTheObject() {
        // The package set of an unavailable section is not consumer-observable:
        // two snapshots that differ only there are the same object and the same
        // digest (sparse object contract).
        val first = grantedSnapshot().copy(
            usageAccess = UsageAccessState.NOT_GRANTED,
            systemUsage = SystemUsageSection.Unavailable,
            launcherOrigin = LauncherOriginSection.Unavailable,
            launcherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_UNAVAILABLE,
        )
        val second = first.copy()
        assertEquals(first, second)
        assertEquals(first.contentDigest, second.contentDigest)
    }

    @Test
    fun digestTracksEveryConsumerObservableChange() {
        val base = grantedSnapshot()
        val differentEntryKey = grantedSnapshot(
            systemUsage = SystemUsageSection.Available(mapOf(otherKey to fullSystemUsage())),
        )
        assertNotEquals(base.contentDigest, differentEntryKey.contentDigest)

        val differentLauncherOrigin = grantedSnapshot(
            launcherOrigin = LauncherOriginSection.Available(
                mapOf(
                    key to LauncherOriginEntry(
                        countClass = SignalField.Value(LauncherCountClass(4)),
                        recencyClass = SignalField.Value(RecencyClass(2)),
                    ),
                ),
            ),
        )
        assertNotEquals(base.contentDigest, differentLauncherOrigin.contentDigest)

        val differentProfileAvailability = base.copy(
            profileAvailability = mapOf(
                profile to SystemUsageProfileAvailability.SYSTEM_USAGE_AVAILABLE,
                otherProfile to SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE,
            ),
        )
        assertNotEquals(base.contentDigest, differentProfileAvailability.contentDigest)
    }

    @Test
    fun canonicalRowsCarryHeaderProfileAndEntryRowsWithSourceIdentity() {
        val rows = grantedSnapshot().canonicalRepresentation().split("\n")
        assertTrue(rows.contains("header|personalization-signals-v1|GRANTED|LAUNCHER_ORIGIN_AVAILABLE"))
        assertTrue(rows.contains("profile|0|SYSTEM_USAGE_AVAILABLE"))
        assertTrue(
            rows.contains("entry|0|com.example.a|foreground30d|Value|2|SYSTEM_USAGE_V1") ||
                rows.contains("entry|0|com.example.a|foreground30d|Value|2|SYSTEM_USAGE_V1"),
        )
        assertTrue(rows.contains("entry|0|com.example.a|count|Value|2|LAUNCHER_ORIGIN_V1"))
        assertTrue(rows.contains("entry|0|com.example.a|launcherRecency|Value|1|LAUNCHER_ORIGIN_V1"))
        // The joined string is the sorted row list, so re-sorting is a no-op.
        assertEquals(rows.sorted().joinToString("\n"), grantedSnapshot().canonicalRepresentation())
    }

    @Test
    fun absentFieldsSerializeWithoutAValue() {
        val absent = grantedSnapshot(
            systemUsage = SystemUsageSection.Available(
                mapOf(
                    key to SystemUsageEntry(SignalField.Absent, SignalField.Absent, SignalField.Absent, SignalField.Absent),
                ),
            ),
        )
        val rows = absent.canonicalRepresentation().split("\n")
        assertTrue(rows.contains("entry|0|com.example.a|recency|Absent|-|SYSTEM_USAGE_V1"))
    }

    @Test
    fun unavailableDefaultCarriesNoEntries() {
        val unavailable = PersonalizationSignalSnapshot.unavailable(setOf(profile))
        assertTrue(unavailable.systemUsage is SystemUsageSection.Unavailable)
        assertTrue(unavailable.launcherOrigin is LauncherOriginSection.Unavailable)
        assertEquals(UsageAccessState.UNAVAILABLE, unavailable.usageAccess)
        assertEquals(LauncherOriginAvailability.LAUNCHER_ORIGIN_UNAVAILABLE, unavailable.launcherOriginAvailability)
        assertEquals(
            SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE,
            unavailable.profileAvailability[profile],
        )
    }
}
