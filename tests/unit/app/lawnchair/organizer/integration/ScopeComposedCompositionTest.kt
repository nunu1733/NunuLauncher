package app.lawnchair.organizer.integration

import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.WorkspaceOverlapToleranceSource
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.LayoutStrategySelectionReadResult
import app.lawnchair.organizer.rules.LayoutStrategySelectionSnapshot
import app.lawnchair.organizer.rules.LayoutStrategySelectionSource
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #228 spec §4 / AC-13: the scope-composed composition seam — additions
 * with deterministic planning IDs, candidate classification through the same
 * policy authority (bundle + user override), and selection-sensitive target
 * provenance.
 */
class ScopeComposedCompositionTest {

    private val newApp = CandidateTarget.AppKey(ComponentKey("com.example.new/.Main"), ProfileId("personal"))
    private val otherApp = CandidateTarget.AppKey(ComponentKey("com.example.other/.Main"), ProfileId("personal"))

    private fun state() = CanonicalFixtures.state(
        profiles = listOf(CanonicalFixtures.profile("personal")),
        items = listOf(
            CanonicalFixtures.appItem(
                itemId = "1",
                profile = "personal",
                target = TargetKey.AppKey(ComponentKey("com.example.existing/.Main"), ProfileId("personal")),
                availability = ItemAvailability.AVAILABLE,
                lockState = OrganizerLockState.UNLOCKED,
            ),
        ),
    )

    private fun composer(
        overrides: CategoryOverrideSnapshot = emptyOverrideSnapshot(),
        evidence: PlatformClassificationEvidence = emptyEvidence(),
    ) = DefaultOrganizationInputComposer(
        captureSource = CanonicalCaptureSource {
            CanonicalCaptureReadResult.Ready(FakeLayoutWriter(state()).captureCurrent(CaptureId("test")))
        },
        bundleSource = object : OrganizerPolicyBundleSource {
            override fun readActive(): BundleReadResult = BuiltInOrganizerPolicyBundleSource.readActive()
        },
        overrides = StaticOverrides(overrides),
        layoutStrategySelections = StaticSelections(),
        platformEvidence = StaticEvidence(evidence),
        overlapTolerance = WorkspaceOverlapToleranceSource { true },
    )

    @Test
    fun emptySelectionKeepsFullOrganizationIdentityAndShape() {
        val composer = composer()
        val full = composer.composeFullOrganization() as OrganizationInputComposition.Ready
        val composed = composer.composeScopeComposedOrganization(emptyList()) as OrganizationInputComposition.Ready

        // Empty selection is the plain full organization: same additions
        // (none) and byte-identical targets provenance (spec AC-13).
        assertEquals(full.input.targets, composed.input.targets)
        assertEquals(full.provenance.targets, composed.provenance.targets)
        assertEquals(full.provenance.signals, composed.provenance.signals)
        assertEquals(RunMode.FullOrganization, full.input.runMode)
        assertEquals(RunMode.ScopeComposedOrganization, composed.input.runMode)
    }

    @Test
    fun selectionBecomesAdditionsWithDeterministicPlanningIds() {
        val composed = composer().composeScopeComposedOrganization(listOf(newApp)) as OrganizationInputComposition.Ready

        val additions = composed.input.targets.additions
        assertEquals(1, additions.size)
        val addition = additions.single()
        assertEquals(CandidatePlanningIds.planningId(newApp), addition.id)
        assertEquals(newApp, addition.target)
        assertEquals(ProfileId("personal"), addition.profile)
        assertEquals(CandidateKind.APPLICATION, addition.kind)
        assertEquals(GridSpan(1, 1), addition.span)
        assertEquals(Availability.AVAILABLE, addition.availability)
        assertEquals(RunMode.ScopeComposedOrganization, composed.input.runMode)
        // The existing partition is untouched.
        assertEquals(1, composed.input.targets.existing.size)
    }

    @Test
    fun provenanceIdentityTracksTheSelectionSet() {
        val composer = composer()
        val full = composer.composeFullOrganization() as OrganizationInputComposition.Ready
        val withNew = composer.composeScopeComposedOrganization(listOf(newApp)) as OrganizationInputComposition.Ready
        val withNewAndOther = composer.composeScopeComposedOrganization(listOf(newApp, otherApp)) as OrganizationInputComposition.Ready

        assertNotEquals(full.provenance.targets, withNew.provenance.targets)
        assertNotEquals(withNew.provenance.targets, withNewAndOther.provenance.targets)

        // Same selection set (different input order, duplicated entry) keeps
        // one identity (spec AC-13 / AC-15).
        val repeated = composer.composeScopeComposedOrganization(listOf(otherApp, newApp, newApp)) as OrganizationInputComposition.Ready
        assertEquals(withNewAndOther.input.targets, repeated.input.targets)
        assertEquals(withNewAndOther.provenance.targets, repeated.provenance.targets)
    }

    @Test
    fun candidatesClassifyThroughUserOverrideAndPlatformEvidence() {
        // S1 user override for the candidate's (package, profile).
        val override = overrideSnapshot(
            mapOf(CategoryOverrideKey(PackageName("com.example.new"), ProfileId("personal")) to CategoryId("GAME")),
        )
        // S2 platform evidence keyed by the OTHER candidate's planning id.
        val evidence = evidence(s2 = mapOf(CandidatePlanningIds.planningId(otherApp) to CategoryId("MAPS")))

        val composed = composer(override, evidence)
            .composeScopeComposedOrganization(listOf(newApp, otherApp)) as OrganizationInputComposition.Ready

        val signals = composed.input.signals.entries
        assertEquals(
            CategoryId("GAME"),
            signals.single { it.item == CandidatePlanningIds.planningId(newApp) }.candidate,
        )
        assertEquals(SignalSource.S1, signals.single { it.item == CandidatePlanningIds.planningId(newApp) }.source)
        assertEquals(
            CategoryId("MAPS"),
            signals.single { it.item == CandidatePlanningIds.planningId(otherApp) }.candidate,
        )
        assertEquals(SignalSource.S2, signals.single { it.item == CandidatePlanningIds.planningId(otherApp) }.source)
    }

    @Test
    fun candidateSignalOutsideTaxonomyFailsClosedLikeCapturedSignals() {
        // The bundle taxonomy is closed; an override naming an unknown
        // category must fail the composition exactly as it does for captured
        // items.
        val badOverride = overrideSnapshot(
            mapOf(CategoryOverrideKey(PackageName("com.example.new"), ProfileId("personal")) to CategoryId("NOT_A_CATEGORY")),
        )

        val composition = composer(badOverride).composeScopeComposedOrganization(listOf(newApp))

        assertTrue(composition is OrganizationInputComposition.NotReady)
        assertEquals(
            InputReadinessReason.ContradictorySource(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT),
            (composition as OrganizationInputComposition.NotReady).reason,
        )
    }

    private fun emptyOverrideSnapshot() = overrideSnapshot(emptyMap())

    private fun overrideSnapshot(assignments: Map<CategoryOverrideKey, CategoryId>) = CategoryOverrideSnapshot(
        schemaVersion = 1,
        generation = 0L,
        assignments = assignments,
        identity = PolicyInputIdentity(PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT, "schema-1-generation-0", "a".repeat(64)),
    )

    private fun emptyEvidence() = evidence()

    private fun evidence(
        s2: Map<ItemId, CategoryId> = emptyMap(),
        s5: Map<ItemId, CategoryId> = emptyMap(),
    ) = PlatformClassificationEvidence(
        s2,
        s5,
        PolicyInputIdentity(PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE, "platform-evidence-v1", "b".repeat(64)),
    )

    private class StaticOverrides(private val snapshot: CategoryOverrideSnapshot) : CategoryOverrideSnapshotSource {
        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(snapshot)
    }

    private class StaticEvidence(private val evidence: PlatformClassificationEvidence) : ClassificationSignalSnapshotSource {
        override fun read(
            requests: List<ClassificationEvidenceRequest>,
            policy: app.lawnchair.organizer.rules.ClassificationPolicy,
        ): PlatformEvidenceReadResult = PlatformEvidenceReadResult.Ready(evidence)
    }

    private class StaticSelections : LayoutStrategySelectionSource {
        private val snapshot = LayoutStrategySelectionSnapshot(
            schemaVersion = 1,
            generation = 0L,
            selection = null,
            identity = PolicyInputIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION, "schema-1-generation-0", "c".repeat(64)),
        )

        override fun read(): LayoutStrategySelectionReadResult = LayoutStrategySelectionReadResult.Ready(snapshot)
    }
}
