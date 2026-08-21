package app.lawnchair.organizer.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.ui.preferences.PreferenceActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingOrganizationProposalInstrumentationTest {
    @Test
    fun realProposalContentKeepsAllActionsReachableAtTwoHundredPercentFontScale() {
        val callbacks = Callbacks()
        ActivityScenario.launch(PreferenceActivity::class.java).use { scenario ->
            lateinit var content: OrganizationOnboardingProposalContent
            scenario.onActivity { activity ->
                val scaledConfiguration = Configuration(activity.resources.configuration).apply {
                    fontScale = TWO_HUNDRED_PERCENT_FONT_SCALE
                }
                content = OrganizationOnboardingProposalContent(
                    context = activity.createConfigurationContext(scaledConfiguration),
                    onLater = { callbacks.later++ },
                    onSkip = { callbacks.skip++ },
                    onReview = { callbacks.review++ },
                )
                activity.setContentView(
                    FrameLayout(activity).apply {
                        addView(
                            content,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    },
                )
                assertEquals(TWO_HUNDRED_PERCENT_FONT_SCALE, content.resources.configuration.fontScale)
                assertTrue(content.title.isFocusable)
                assertTrue(content.laterButton.isShown)
                assertTrue(content.skipButton.isShown)
                assertTrue(content.reviewButton.isShown)
                assertTrue(content.laterButton.bottom <= content.height)
                assertTrue(content.skipButton.bottom <= content.height)
                assertTrue(content.reviewButton.bottom <= content.height)
                assertTrue(content.title.requestFocus())
                assertTrue(content.title.hasFocus())
                assertTrue(content.laterButton.requestFocus())
            }

            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            SystemClock.sleep(100)
            scenario.onActivity {
                assertTrue(
                    content.laterButton.hasFocus() || content.skipButton.hasFocus() || content.reviewButton.hasFocus(),
                )
                content.laterButton.performClick()
                content.skipButton.performClick()
                content.reviewButton.performClick()
            }

            assertEquals(1, callbacks.later)
            assertEquals(1, callbacks.skip)
            assertEquals(1, callbacks.review)

            scenario.recreate()
            scenario.onActivity { activity ->
                content = attachProposalContent(activity, callbacks)
                assertTrue(content.laterButton.isShown)
                assertTrue(content.skipButton.isShown)
                assertTrue(content.reviewButton.isShown)
                assertEquals(1, callbacks.later)
                assertEquals(1, callbacks.skip)
                assertEquals(1, callbacks.review)
            }
        }
    }

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

    @Test
    fun productionProvenanceBoundaryFailsClosedOutsideFreshInstall() {
        assertEquals(
            OrganizationOnboardingInstallProvenance.FRESH_INSTALL,
            classifyOrganizationOnboardingInstallProvenance(false, 1L, 1L),
        )
        assertEquals(
            OrganizationOnboardingInstallProvenance.RESTORE,
            classifyOrganizationOnboardingInstallProvenance(true, 1L, 1L),
        )
        assertEquals(
            OrganizationOnboardingInstallProvenance.UPGRADE,
            classifyOrganizationOnboardingInstallProvenance(false, 1L, 2L),
        )
        assertEquals(
            OrganizationOnboardingInstallProvenance.UNKNOWN,
            classifyOrganizationOnboardingInstallProvenance(false, null, null),
        )
    }

    private fun attachProposalContent(
        activity: PreferenceActivity,
        callbacks: Callbacks,
    ): OrganizationOnboardingProposalContent {
        val scaledConfiguration = Configuration(activity.resources.configuration).apply {
            fontScale = TWO_HUNDRED_PERCENT_FONT_SCALE
        }
        return OrganizationOnboardingProposalContent(
            context = activity.createConfigurationContext(scaledConfiguration),
            onLater = { callbacks.later++ },
            onSkip = { callbacks.skip++ },
            onReview = { callbacks.review++ },
        ).also { content ->
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(
                        content,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
            )
        }
    }

    private fun sendKey(keyCode: Int) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private class Callbacks {
        var later = 0
        var skip = 0
        var review = 0
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
        const val TWO_HUNDRED_PERCENT_FONT_SCALE = 2f
    }
}
