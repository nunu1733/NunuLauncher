package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationOnboardingProposalTest {
    @Test
    fun unseenFreshInstallCanClaimPresentationOnlyOncePerProcess() {
        val controller = OrganizationOnboardingProposalController(FakeStore())

        assertTrue(controller.isEligible())
        assertTrue(controller.claimPresentation())
        assertFalse(controller.claimPresentation())
    }

    @Test
    fun deferredProposalIsSuppressedOnlyForTheCurrentProcess() {
        val store = FakeStore()
        val firstProcess = OrganizationOnboardingProposalController(store)

        firstProcess.defer()

        assertEquals(OrganizationOnboardingProposalOutcome.DEFERRED, store.value)
        assertFalse(firstProcess.isEligible())
        assertTrue(OrganizationOnboardingProposalController(store).isEligible())
    }

    @Test
    fun skippedAndReviewedOutcomesNeverAutoResurface() {
        val skipped = FakeStore()
        OrganizationOnboardingProposalController(skipped).skip()
        assertFalse(OrganizationOnboardingProposalController(skipped).isEligible())

        val reviewed = FakeStore()
        reviewed.record(OrganizationOnboardingProposalOutcome.REVIEWED)
        assertFalse(OrganizationOnboardingProposalController(reviewed).isEligible())
    }

    @Test
    fun productionProvenanceClassifierFailsClosedForRestoreUpgradeAndUnknownEvidence() {
        assertEquals(
            OrganizationOnboardingInstallProvenance.FRESH_INSTALL,
            classifyOrganizationOnboardingInstallProvenance(
                restorePending = false,
                firstInstallTime = 10L,
                lastUpdateTime = 10L,
            ),
        )
        assertEquals(
            OrganizationOnboardingInstallProvenance.RESTORE,
            classifyOrganizationOnboardingInstallProvenance(
                restorePending = true,
                firstInstallTime = 10L,
                lastUpdateTime = 10L,
            ),
        )
        assertEquals(
            OrganizationOnboardingInstallProvenance.UPGRADE,
            classifyOrganizationOnboardingInstallProvenance(
                restorePending = false,
                firstInstallTime = 10L,
                lastUpdateTime = 11L,
            ),
        )
        assertEquals(
            OrganizationOnboardingInstallProvenance.UNKNOWN,
            classifyOrganizationOnboardingInstallProvenance(
                restorePending = false,
                firstInstallTime = null,
                lastUpdateTime = null,
            ),
        )
    }

    @Test
    fun restoreSnapshotRemainsFailClosedAfterTheLoaderConsumesTransientMarkers() {
        assertEquals(
            OrganizationOnboardingInstallProvenance.RESTORE,
            classifyOrganizationOnboardingInstallProvenance(
                restorePending = false,
                firstInstallTime = 10L,
                lastUpdateTime = 10L,
                restoreSnapshot = true,
            ),
        )
    }

    @Test
    fun upgradeRestoreAndUnknownProvenanceFailClosedWhenOutcomeIsMissing() {
        listOf(
            OrganizationOnboardingInstallProvenance.UPGRADE,
            OrganizationOnboardingInstallProvenance.RESTORE,
            OrganizationOnboardingInstallProvenance.UNKNOWN,
        ).forEach { provenance ->
            assertFalse(OrganizationOnboardingProposalController(FakeStore(provenance = provenance)).isEligible())
        }
    }

    @Test
    fun busyAndRefusedOnboardingStartsPreserveDeferred() {
        // Issue #607: the proposal's 確認 no longer admits; the run surface's
        // start row owns admission. Busy and refused outcomes must leave the
        // stored outcome untouched so the proposal stays DEFERRED and
        // re-displayable per the cold-start rules.
        listOf(
            ManualOrganizationRun.StartOutcome.Busy,
            ManualOrganizationRun.StartOutcome.AdmissionRefused,
        ).forEach { outcome ->
            val store = FakeStore()
            store.record(OrganizationOnboardingProposalOutcome.DEFERRED)

            recordOnboardingReviewedOnStart(store, Trigger.ONBOARDING_PROPOSAL, outcome)

            assertEquals(OrganizationOnboardingProposalOutcome.DEFERRED, store.value)
        }
    }

    @Test
    fun startedOnboardingStartRecordsReviewedOnlyForTheOnboardingTrigger() {
        // Issue #607: REVIEWED is recorded only when an ONBOARDING_PROPOSAL
        // start actually returns Started. The same Started outcome under the
        // settings trigger (MANUAL_FULL) must not mark the proposal reviewed.
        val onboarding = FakeStore()
        onboarding.record(OrganizationOnboardingProposalOutcome.DEFERRED)
        recordOnboardingReviewedOnStart(
            onboarding,
            Trigger.ONBOARDING_PROPOSAL,
            ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)),
        )
        assertEquals(OrganizationOnboardingProposalOutcome.REVIEWED, onboarding.value)

        val manual = FakeStore()
        manual.record(OrganizationOnboardingProposalOutcome.DEFERRED)
        recordOnboardingReviewedOnStart(
            manual,
            Trigger.MANUAL_FULL,
            ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)),
        )
        assertEquals(OrganizationOnboardingProposalOutcome.DEFERRED, manual.value)
    }

    private class FakeStore(
        private val provenance: OrganizationOnboardingInstallProvenance = OrganizationOnboardingInstallProvenance.FRESH_INSTALL,
    ) : OrganizationOnboardingProposalStore {
        var value: OrganizationOnboardingProposalOutcome? = null

        override fun provenance(): OrganizationOnboardingInstallProvenance = provenance

        override fun outcome(): OrganizationOnboardingProposalOutcome? = value

        override fun record(outcome: OrganizationOnboardingProposalOutcome) {
            value = outcome
        }
    }

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
