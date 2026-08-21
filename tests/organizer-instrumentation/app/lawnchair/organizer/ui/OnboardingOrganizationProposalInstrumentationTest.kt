package app.lawnchair.organizer.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.ui.preferences.PreferenceActivity
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.provider.RestoreDbTask
import com.android.launcher3.util.OnboardingPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingOrganizationProposalInstrumentationTest {
    @Test
    fun realLauncherFloatingHostKeepsAllActionsWithinViewportAtTwoHundredPercentFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalFontScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        val proposalPrefs = LauncherPrefs.get(context)
        val originalProposalOutcome = proposalPrefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME)
        val store = FakeStore()
        try {
            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, OrganizationOnboardingProposalOutcome.SKIPPED.name)
            runShellCommand("settings put system font_scale $TWO_HUNDRED_PERCENT_FONT_SCALE")
            runShellCommand(
                "am start -n ${ComponentName(context, LawnchairLauncher::class.java).flattenToString()} " +
                    "-a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}",
            )
            val launcher = awaitResumedLauncher()
            lateinit var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            lateinit var content: OrganizationOnboardingProposalContent
            instrumentation.runOnMainSync {
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
                assertEquals(TWO_HUNDRED_PERCENT_FONT_SCALE, launcher.resources.configuration.fontScale)
                assertEquals(TWO_HUNDRED_PERCENT_FONT_SCALE, proposal.resources.configuration.fontScale)
                listOf(content.laterButton, content.skipButton, content.reviewButton).forEach { button ->
                    val bounds = Rect()
                    assertTrue(button.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.top >= viewport.top)
                    assertTrue(bounds.bottom <= viewport.bottom)
                }
                assertTrue(proposal.canHandleBack())
                assertTrue(proposal.isOpen)
                assertTrue(content.title.isFocusable)
                assertTrue(content.laterButton.isFocusable)
                assertTrue(content.skipButton.isFocusable)
                assertTrue(content.reviewButton.isFocusable)
                proposal.onBackInvoked()
                assertFalse(proposal.isOpen)
                assertEquals(OrganizationOnboardingProposalOutcome.DEFERRED, store.value)
            }
        } finally {
            runShellCommand("settings put system font_scale $originalFontScale")
            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, originalProposalOutcome)
        }
    }

    @Test
    fun productionOwnerDefersBindWhilePausedThenShowsAndRoutesReviewAfterResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val proposalPrefs = LauncherPrefs.get(context)
        val originalProposalOutcome = proposalPrefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME)
        val reviewOutcome = AtomicReference<ManualOrganizationRun.StartOutcome>(ManualOrganizationRun.StartOutcome.Busy)
        val admissionCount = AtomicInteger()
        try {
            // Prevent the launcher-owned singleton owner from claiming the shared process slot.
            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, OrganizationOnboardingProposalOutcome.SKIPPED.name)
            startLauncher(context)
            val launcher = awaitResumedLauncher()
            lateinit var owner: OrganizationOnboardingProposal
            instrumentation.runOnMainSync {
                owner = OrganizationOnboardingProposal(
                    launcher = launcher,
                    controller = OrganizationOnboardingProposalController(
                        OrganizationOnboardingProposal.LauncherProposalStore(
                            launcher,
                            OrganizationOnboardingInstallProvenance.FRESH_INSTALL,
                        ),
                        OrganizationOnboardingProposalProcessState(),
                    ),
                    admitReview = {
                        admissionCount.incrementAndGet()
                        reviewOutcome.get()
                    },
                    isWorkspaceReady = { true },
                )
            }

            startPreferenceActivity(context)
            awaitResumedPreferenceActivity()
            instrumentation.runOnMainSync {
                owner.onLauncherResumed()
                owner.onInitialWorkspaceBound()
                assertFalse(
                    AbstractFloatingView.getTopOpenView(launcher) is
                        OrganizationOnboardingProposal.OrganizationOnboardingProposalView,
                )
            }

            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, "")
            sendKey(KeyEvent.KEYCODE_BACK)
            // An explicit HOME launch reliably resumes the same Launcher activity in CI.
            startLauncher(context)
            val resumedLauncher = awaitResumedLauncher()
            assertTrue(launcher === resumedLauncher)
            lateinit var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            instrumentation.runOnMainSync {
                assertTrue(launcher.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                assertFalse(
                    AbstractFloatingView.getTopOpenView(launcher) is
                        OrganizationOnboardingProposal.OrganizationOnboardingProposalView,
                )
                AbstractFloatingView.closeOpenViews(launcher, false, AbstractFloatingView.TYPE_ALL)
                assertEquals(null, AbstractFloatingView.getTopOpenView(launcher))
                owner.onLauncherResumed()
            }
            proposal = awaitProductionProposal(launcher)
            lateinit var content: OrganizationOnboardingProposalContent
            instrumentation.runOnMainSync {
                content = proposal.getChildAt(0) as OrganizationOnboardingProposalContent
                content.reviewButton.performClick()
            }
            awaitAdmissionCount(admissionCount, 1)
            instrumentation.runOnMainSync {
                assertTrue(proposal.isOpen)
            }
            assertEquals("", proposalPrefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME))

            reviewOutcome.set(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)))
            instrumentation.runOnMainSync {
                content.reviewButton.performClick()
            }
            awaitAdmissionCount(admissionCount, 2)
            awaitResumedPreferenceActivity()
            assertEquals(
                OrganizationOnboardingProposalOutcome.REVIEWED.name,
                proposalPrefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME),
            )
        } finally {
            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, originalProposalOutcome)
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
            }
            awaitWindowFocus { content.title }
            scenario.onActivity {
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
    fun restoreSnapshotRemainsFailClosedAfterTheLoaderConsumesTransientMarkers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = LauncherPrefs.get(context)
        try {
            prefs.removeSync(LauncherPrefs.RESTORE_DEVICE)
            prefs.putSync(LauncherPrefs.IS_FIRST_LOAD_AFTER_RESTORE.to(false))
            prefs.putSync(OnboardingPrefs.ORGANIZATION_PROPOSAL_RESTORE_SEEN.to(false))

            RestoreDbTask.setPending(context)

            assertTrue(prefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_RESTORE_SEEN))
            // Mirror the loader's one-shot marker consumption before proposal eligibility runs.
            prefs.removeSync(LauncherPrefs.RESTORE_DEVICE)
            prefs.putSync(LauncherPrefs.IS_FIRST_LOAD_AFTER_RESTORE.to(false))
            assertEquals(
                OrganizationOnboardingInstallProvenance.RESTORE,
                classifyOrganizationOnboardingInstallProvenance(
                    restorePending = false,
                    firstInstallTime = 1L,
                    lastUpdateTime = 1L,
                    restoreSnapshot = prefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_RESTORE_SEEN),
                ),
            )
        } finally {
            prefs.removeSync(LauncherPrefs.RESTORE_DEVICE)
            prefs.putSync(LauncherPrefs.IS_FIRST_LOAD_AFTER_RESTORE.to(false))
            prefs.putSync(OnboardingPrefs.ORGANIZATION_PROPOSAL_RESTORE_SEEN.to(false))
        }
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

    private fun awaitWindowFocus(viewProvider: () -> View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var ready = false
            instrumentation.runOnMainSync {
                val view = viewProvider()
                ready = view.isAttachedToWindow && view.rootView.hasWindowFocus()
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Proposal window did not gain focus before keyboard accessibility verification")
    }

    private fun awaitAdmissionCount(admissionCount: AtomicInteger, expected: Int) {
        repeat(50) {
            if (admissionCount.get() == expected) return
            SystemClock.sleep(100)
        }
        error("Expected $expected onboarding review admissions, got ${admissionCount.get()}")
    }

    private fun awaitProductionProposal(
        launcher: LawnchairLauncher,
    ): OrganizationOnboardingProposal.OrganizationOnboardingProposalView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView? = null
            instrumentation.runOnMainSync {
                proposal = AbstractFloatingView.getTopOpenView(launcher) as?
                    OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            }
            proposal?.let { return it }
            SystemClock.sleep(100)
        }
        error("Organization onboarding proposal was not shown through its production owner")
    }

    private fun awaitVisibleProposalActions(
        launcher: LawnchairLauncher,
        content: OrganizationOnboardingProposalContent,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var diagnostic = "not measured"
        repeat(50) {
            var ready = false
            instrumentation.runOnMainSync {
                val viewport = Rect()
                val viewportVisible = launcher.dragLayer.getGlobalVisibleRect(viewport)
                val actionBounds = listOf(content.laterButton, content.skipButton, content.reviewButton).map { button ->
                    val bounds = Rect()
                    "${button.text}: visible=${button.getGlobalVisibleRect(bounds)}, bounds=$bounds"
                }
                ready = viewportVisible &&
                    content.isLaidOut &&
                    listOf(content.laterButton, content.skipButton, content.reviewButton).all { button ->
                        val bounds = Rect()
                        button.getGlobalVisibleRect(bounds) &&
                            bounds.top >= viewport.top &&
                            bounds.bottom <= viewport.bottom
                    }
                diagnostic = "viewportVisible=$viewportVisible, viewport=$viewport, laidOut=${content.isLaidOut}, " +
                    actionBounds.joinToString()
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Organization onboarding proposal actions did not reach the launcher viewport: $diagnostic")
    }

    private fun awaitResumedPreferenceActivity(): PreferenceActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var candidate: PreferenceActivity? = null
            instrumentation.runOnMainSync {
                candidate = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PreferenceActivity>()
                    .singleOrNull()
            }
            candidate?.let { return it }
            SystemClock.sleep(100)
        }
        error("PreferenceActivity did not resume after onboarding review admission")
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

    private fun startLauncher(context: android.content.Context) {
        runShellCommand(
            "am start -n ${ComponentName(context, LawnchairLauncher::class.java).flattenToString()} " +
                "-a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}",
        )
    }

    private fun startPreferenceActivity(context: android.content.Context) {
        runShellCommand("am start -n ${ComponentName(context, PreferenceActivity::class.java).flattenToString()}")
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
