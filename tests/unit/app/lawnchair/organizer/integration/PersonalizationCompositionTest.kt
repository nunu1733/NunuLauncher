package app.lawnchair.organizer.integration

import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.personalization.LauncherCountClass
import app.lawnchair.organizer.personalization.LauncherOriginAvailability
import app.lawnchair.organizer.personalization.LauncherOriginEntry
import app.lawnchair.organizer.personalization.LauncherOriginSection
import app.lawnchair.organizer.personalization.PersonalizationEntryKey
import app.lawnchair.organizer.personalization.PersonalizationSignalSnapshot
import app.lawnchair.organizer.personalization.PersonalizationSignalSnapshotSource
import app.lawnchair.organizer.personalization.RecencyClass
import app.lawnchair.organizer.personalization.SignalField
import app.lawnchair.organizer.personalization.SystemUsageSection
import app.lawnchair.organizer.personalization.UsageAccessState
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.WorkspaceOverlapToleranceSource
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult
import app.lawnchair.organizer.rules.LayoutStrategySelectionSnapshot
import app.lawnchair.organizer.rules.LayoutStrategySelectionSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #203: composition-side personalization contract. The optional source
 * is read once per attempt outside the mandatory dynamic cut; any source
 * failure degrades to unavailable sections while the composition stays
 * `Ready`, and neither `OrganizationInput.personalization` nor
 * `InputProvenance.personalization` is ever absent (AC-11 / AC-12 / U-4).
 */
class PersonalizationCompositionTest {

    private class FixedOverrides : CategoryOverrideSnapshotSource {
        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = 1,
                generation = 0L,
                assignments = emptyMap(),
                identity = PolicyInputIdentity(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, "schema-1-generation-0", DIGEST('a')),
            ),
        )
    }

    private class FixedSelections : LayoutStrategySelectionSource {
        override fun read(): LayoutStrategySelectionReadResult = LayoutStrategySelectionReadResult.Ready(
            LayoutStrategySelectionSnapshot(
                schemaVersion = 1,
                generation = 0L,
                selection = null,
                identity = PolicyInputIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION, "schema-1-generation-0", DIGEST('c')),
            ),
        )
    }

    private class ThrowingPersonalizationSource : PersonalizationSignalSnapshotSource {
        override fun read(request: app.lawnchair.organizer.personalization.UsageSignalRequest): PersonalizationSignalSnapshot {
            throw IllegalStateException("bug in the source")
        }
    }

    private class CountingPersonalizationSource(
        private val snapshot: PersonalizationSignalSnapshot,
    ) : PersonalizationSignalSnapshotSource {
        var reads = 0
            private set

        override fun read(request: app.lawnchair.organizer.personalization.UsageSignalRequest): PersonalizationSignalSnapshot {
            reads += 1
            return snapshot
        }
    }

    private fun composer(
        personalizationSource: PersonalizationSignalSnapshotSource?,
    ): DefaultOrganizationInputComposer {
        val state = CanonicalFixtures.state(
            profiles = listOf(CanonicalFixtures.profile("0")),
            items = listOf(
                CanonicalFixtures.appItem(
                    itemId = "a",
                    profile = "0",
                    target = TargetKey.AppKey(app.lawnchair.organizer.planning.ComponentKey("com.example.a/.Main"), ProfileId("0")),
                ),
            ),
        )
        val evidence = PlatformClassificationEvidence(
            emptyMap(),
            emptyMap(),
            PolicyInputIdentity(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE, "platform-evidence-v1", DIGEST('b')),
        )
        return DefaultOrganizationInputComposer(
            captureSource = CanonicalCaptureSource {
                CanonicalCaptureReadResult.Ready(FakeLayoutWriter(state).captureCurrent(CaptureId("test")))
            },
            bundleSource = BuiltInOrganizerPolicyBundleSource,
            overrides = FixedOverrides(),
            layoutStrategySelections = FixedSelections(),
            platformEvidence = object : ClassificationSignalSnapshotSource {
                override fun read(
                    requests: List<ClassificationEvidenceRequest>,
                    policy: app.lawnchair.organizer.rules.ClassificationPolicy,
                ): PlatformEvidenceReadResult = PlatformEvidenceReadResult.Ready(evidence)
            },
            overlapTolerance = WorkspaceOverlapToleranceSource { true },
            personalizationSource = personalizationSource,
        )
    }

    private fun notGrantedSnapshot(): PersonalizationSignalSnapshot {
        val key = PersonalizationEntryKey(ProfileId("0"), app.lawnchair.organizer.planning.PackageName("com.example.a"))
        return PersonalizationSignalSnapshot(
            usageAccess = UsageAccessState.NOT_GRANTED,
            launcherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE,
            profileAvailability = mapOf(ProfileId("0") to app.lawnchair.organizer.personalization.SystemUsageProfileAvailability.SYSTEM_USAGE_UNAVAILABLE),
            systemUsage = SystemUsageSection.Unavailable,
            launcherOrigin = LauncherOriginSection.Available(
                mapOf(
                    key to LauncherOriginEntry(
                        countClass = SignalField.Value(LauncherCountClass(3)),
                        recencyClass = SignalField.Value(RecencyClass(2)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun notGrantedSnapshotComposesReadyAndKeepsLauncherOriginEntries() {
        val source = CountingPersonalizationSource(notGrantedSnapshot())

        val ready = composer(source).composeFullOrganization() as OrganizationInputComposition.Ready

        assertEquals(UsageAccessState.NOT_GRANTED, ready.input.personalization.usageAccess)
        assertTrue(ready.input.personalization.launcherOrigin is LauncherOriginSection.Available)
        assertEquals(ready.input.personalization.contentDigest, ready.provenance.personalization.sha256)
        assertEquals(PolicySourceKind.PERSONALIZATION_SIGNAL_SNAPSHOT, ready.provenance.personalization.source)
        assertEquals(1, source.reads)
    }

    @Test
    fun throwingSourceComposesReadyWithUnavailableSnapshot() {
        val ready = composer(ThrowingPersonalizationSource()).composeFullOrganization() as OrganizationInputComposition.Ready

        assertTrue(ready.input.personalization.systemUsage is SystemUsageSection.Unavailable)
        assertEquals(UsageAccessState.UNAVAILABLE, ready.input.personalization.usageAccess)
        assertTrue(ready.input.personalization.launcherOrigin is LauncherOriginSection.Unavailable)
        assertEquals(ready.input.personalization.contentDigest, ready.provenance.personalization.sha256)
    }

    @Test
    fun unwiredSourceComposesReadyWithTheUnavailableDefault() {
        val ready = composer(null).composeFullOrganization() as OrganizationInputComposition.Ready

        assertTrue(ready.input.personalization.systemUsage is SystemUsageSection.Unavailable)
        assertTrue(ready.input.personalization.launcherOrigin is LauncherOriginSection.Unavailable)
        assertEquals(ready.input.personalization.contentDigest, ready.provenance.personalization.sha256)
    }

    @Test
    fun mandatoryDynamicCutIdentityIsUnchangedByPersonalizationPresence() {
        // AC-10: the dynamic cut covers bundle / overrides / evidence /
        // selection only. Two compositions with the same mandatory inputs and
        // different personalization results share the same provenance rows for
        // the mandatory sources.
        val withoutSource = composer(null).composeFullOrganization() as OrganizationInputComposition.Ready
        val withSource = composer(CountingPersonalizationSource(notGrantedSnapshot())).composeFullOrganization() as OrganizationInputComposition.Ready

        assertEquals(withoutSource.provenance.rules, withSource.provenance.rules)
        assertEquals(withoutSource.provenance.taxonomy, withSource.provenance.taxonomy)
        assertEquals(withoutSource.provenance.signals, withSource.provenance.signals)
        assertEquals(withoutSource.provenance.targets, withSource.provenance.targets)
        assertEquals(withoutSource.provenance.policyBundle, withSource.provenance.policyBundle)
        assertEquals(withoutSource.provenance.layoutStrategySelection, withSource.provenance.layoutStrategySelection)
    }

    private companion object {
        val DIGEST: (Char) -> String = { char -> char.toString().repeat(64) }
    }
}
