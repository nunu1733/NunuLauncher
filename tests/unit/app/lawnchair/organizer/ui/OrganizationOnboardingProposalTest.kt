package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.RunId
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
        val controller = OrganizationOnboardingProposalController(reviewed)
        controller.review { ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)) }
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
    fun busyReviewDoesNotRecordReviewedOrNavigateAsAnAdmittedRun() {
        val store = FakeStore()
        val controller = OrganizationOnboardingProposalController(store)
        var admissions = 0

        val outcome = controller.review {
            admissions++
            ManualOrganizationRun.StartOutcome.Busy
        }

        assertEquals(ManualOrganizationRun.StartOutcome.Busy, outcome)
        assertEquals(1, admissions)
        assertEquals(null, store.value)
        assertTrue(controller.isEligible())
    }

    @Test
    fun admittedReviewRecordsReviewedOnlyAfterFreshRunAdmission() {
        val store = FakeStore()
        val controller = OrganizationOnboardingProposalController(store)

        val outcome = controller.review {
            ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID))
        }

        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)), outcome)
        assertEquals(OrganizationOnboardingProposalOutcome.REVIEWED, store.value)
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
