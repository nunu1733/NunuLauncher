package app.lawnchair.organizer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.public.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingOrganizationProposalInstrumentationTest {
    @Test
    fun deferredProposalRemainsSuppressedInThisProcessAndMayReturnAfterColdStart() {
        val store = FakeStore()
        val currentProcess = OrganizationOnboardingProposalController(store)

        assertTrue(currentProcess.claimPresentation())
        currentProcess.defer()

        assertFalse(currentProcess.isEligible())
        assertTrue(OrganizationOnboardingProposalController(store).isEligible())
    }

    @Test
    fun reviewBusyDoesNotConsumeTheProposalOutcome() {
        val store = FakeStore()
        val controller = OrganizationOnboardingProposalController(store)

        val outcome = controller.review { ManualOrganizationRun.StartOutcome.Busy }

        assertEquals(ManualOrganizationRun.StartOutcome.Busy, outcome)
        assertEquals(null, store.value)
        assertTrue(controller.isEligible())
    }

    @Test
    fun reviewRecordsReviewedOnlyWhenTheFreshRunIsAdmitted() {
        val store = FakeStore()
        val controller = OrganizationOnboardingProposalController(store)

        val outcome = controller.review {
            ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID))
        }

        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)), outcome)
        assertEquals(OrganizationOnboardingProposalOutcome.REVIEWED, store.value)
    }

    private class FakeStore : OrganizationOnboardingProposalStore {
        var value: OrganizationOnboardingProposalOutcome? = null

        override fun provenance(): OrganizationOnboardingInstallProvenance = OrganizationOnboardingInstallProvenance.FRESH_INSTALL

        override fun outcome(): OrganizationOnboardingProposalOutcome? = value

        override fun record(outcome: OrganizationOnboardingProposalOutcome) {
            value = outcome
        }
    }

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
