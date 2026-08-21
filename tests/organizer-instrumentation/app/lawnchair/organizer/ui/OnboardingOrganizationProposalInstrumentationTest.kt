package app.lawnchair.organizer.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.ui.preferences.PreferenceActivity
import com.android.launcher3.AbstractFloatingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingOrganizationProposalInstrumentationTest {
    @Test
    fun realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalFontScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        val store = FakeStore()
        try {
            runShellCommand("settings put system font_scale $TWO_HUNDRED_PERCENT_FONT_SCALE")
            runShellCommand(
                "am start -n ${ComponentName(context, LawnchairLauncher::class.java).flattenToString()} " +
                    "-a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}",
            )
            val launcher = awaitResumedLauncher()
            lateinit var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            lateinit var content: OrganizationOnboardingProposalContent
            instrumentation.runOnMainSync {
                AbstractFloatingView.closeOpenViews(
                    launcher,
                    false,
                    AbstractFloatingView.TYPE_ON_BOARD_POPUP,
                )
                proposal = OrganizationOnboardingProposal.OrganizationOnboardingProposalView(
                    launcher,
                    OrganizationOnboardingProposalController(store),
                )
                proposal.show()
                content = proposal.getChildAt(0) as OrganizationOnboardingProposalContent
            }
            awaitVisibleProposalActions(launcher, content)
            instrumentation.runOnMainSync {
                val viewport = Rect()
                assertTrue(launcher.dragLayer.getGlobalVisibleRect(viewport))
                listOf(content.laterButton, content.skipButton, content.reviewButton).forEach { button ->
                    val bounds = Rect()
                    assertTrue(button.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.top >= viewport.top)
                    assertTrue(bounds.bottom <= viewport.bottom)
                }
                assertTrue(proposal.canHandleBack())
                assertTrue(proposal.isOpen)
                assertTrue(content.laterButton.requestFocus())
            }

            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            SystemClock.sleep(100)
            instrumentation.runOnMainSync {
                assertTrue(
                    content.laterButton.hasFocus() || content.skipButton.hasFocus() || content.reviewButton.hasFocus(),
                )
                proposal.onBackInvoked()
                assertFalse(proposal.isOpen)
                assertEquals(OrganizationOnboardingProposalOutcome.DEFERRED, store.value)
            }
        } finally {
            runShellCommand("settings put system font_scale $originalFontScale")
        }
    }

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

    private fun awaitVisibleProposalActions(
        launcher: LawnchairLauncher,
        content: OrganizationOnboardingProposalContent,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var ready = false
            instrumentation.runOnMainSync {
                val viewport = Rect()
                ready = launcher.dragLayer.getGlobalVisibleRect(viewport) &&
                    content.isLaidOut &&
                    listOf(content.laterButton, content.skipButton, content.reviewButton).all { button ->
                        val bounds = Rect()
                        button.getGlobalVisibleRect(bounds) &&
                            bounds.top >= viewport.top &&
                            bounds.bottom <= viewport.bottom
                    }
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Organization onboarding proposal actions did not reach the launcher viewport")
    }

    private fun awaitResumedLauncher(): LawnchairLauncher {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(120) {
            var candidate: LawnchairLauncher? = null
            instrumentation.runOnMainSync {
                candidate = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<LawnchairLauncher>()
                    .singleOrNull()
            }
            candidate?.let { return it }
            SystemClock.sleep(100)
        }
        error("LawnchairLauncher did not reach RESUMED after HOME launch")
    }

    private fun runShellCommand(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private fun sendKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
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
