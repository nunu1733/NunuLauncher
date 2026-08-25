package app.lawnchair.organizer.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
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
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingOrganizationProposalInstrumentationTest {
    @Before
    fun keepProductionProposalOwnerFailClosedForThisProcess() {
        // The production singleton freezes its install provenance at first construction, which
        // happens at the first launcher start of this process. Marking the restore snapshot
        // before any test starts HOME keeps that singleton fail-closed (RESTORE provenance), so
        // tests exercise their own injected-store owners instead of racing an auto-shown
        // proposal on freshly installed debug builds.
        LauncherPrefs.get(InstrumentationRegistry.getInstrumentation().targetContext)
            .putSync(OnboardingPrefs.ORGANIZATION_PROPOSAL_RESTORE_SEEN.to(true))
    }

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
            val launcherBeforeRecreation = awaitResumedLauncher(
                expectedFontScale = TWO_HUNDRED_PERCENT_FONT_SCALE,
            )
            instrumentation.runOnMainSync {
                launcherBeforeRecreation.recreate()
            }
            val launcher = awaitResumedLauncher(
                expectedFontScale = TWO_HUNDRED_PERCENT_FONT_SCALE,
                excluding = launcherBeforeRecreation,
            )
            lateinit var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            lateinit var content: OrganizationOnboardingProposalContent
            lateinit var focusBeforeOpen: View
            instrumentation.runOnMainSync {
                focusBeforeOpen = View(launcher).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                launcher.dragLayer.addView(focusBeforeOpen, FrameLayout.LayoutParams(1, 1))
                assertTrue(focusBeforeOpen.requestFocus())
                proposal = OrganizationOnboardingProposal.OrganizationOnboardingProposalView(
                    launcher,
                    OrganizationOnboardingProposalController(store),
                )
                proposal.show()
                content = proposal.getChildAt(0) as OrganizationOnboardingProposalContent
            }
            awaitVisibleProposalActions(launcher, content)
            awaitInputFocus({ content.title }, "proposal title")
            // Injected through the real input pipeline (not a direct activity dispatch) so the
            // key press ends touch mode exactly like hardware DPAD input does.
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitAnyInputFocus(content.laterButton, content.skipButton, content.reviewButton)
            instrumentation.runOnMainSync {
                val viewport = Rect()
                assertTrue(launcher.dragLayer.getGlobalVisibleRect(viewport))
                assertEquals(TWO_HUNDRED_PERCENT_FONT_SCALE, launcher.resources.configuration.fontScale)
                assertEquals(TWO_HUNDRED_PERCENT_FONT_SCALE, proposal.resources.configuration.fontScale)
                val safeAreaBottom = viewport.bottom - launcher.windowManager.currentWindowMetrics.windowInsets
                    .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    .bottom
                listOf(content.laterButton, content.skipButton, content.reviewButton).forEach { button ->
                    val bounds = Rect()
                    assertTrue(button.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.top >= viewport.top)
                    assertTrue(bounds.bottom <= viewport.bottom)
                    // The popup must respect the system-bar safe area instead of relying on a
                    // fixed margin smaller than the navigation bar inset (PR #144 review).
                    assertTrue(
                        "proposal action must stay above the system bar safe area " +
                            "(bottom=${bounds.bottom}, safeAreaBottom=$safeAreaBottom)",
                        bounds.bottom <= safeAreaBottom,
                    )
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
            awaitInputFocus({ focusBeforeOpen }, "pre-proposal focus target")
            instrumentation.runOnMainSync {
                launcher.dragLayer.removeView(focusBeforeOpen)
            }
        } finally {
            runShellCommand("settings put system font_scale $originalFontScale")
            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, originalProposalOutcome)
        }
    }

    @Test
    fun realTouchStreamActivatesLaterWithASingleTap() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            activateByTouchesUntilResolved(gate, gate.content.laterButton, "Later")
        } finally {
            gate.restore()
        }
        assertEquals(
            OrganizationOnboardingProposalOutcome.DEFERRED,
            gate.store.value,
        )
    }

    @Test
    fun realTouchStreamActivatesSkipWithASingleTap() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            activateByTouchesUntilResolved(gate, gate.content.skipButton, "Skip")
        } finally {
            gate.restore()
        }
        assertEquals(
            OrganizationOnboardingProposalOutcome.SKIPPED,
            gate.store.value,
        )
    }

    /**
     * Injects real touch streams (never `performClick()`) and records focus owners plus the
     * DOWN/UP/CANCEL flow observed by the action buttons, so a pre-fix failure doubles as the
     * Issue #137 Phase 0 go/no-go evidence.
     */
    private fun activateByTouchesUntilResolved(
        gate: TouchActivationGate,
        target: Button,
        targetName: String,
    ) {
        var observations = ""
        var resolvedAttempt = 0
        for (attempt in 1..MAX_TOUCH_ACTIVATION_TAPS) {
            val focusBefore = gate.describeFocus()
            val geometry = gate.describeGeometry(target)
            val injections = gate.deliveredTap(target)
            if (gate.awaitResolvedOrRecord()) {
                resolvedAttempt = attempt
                break
            }
            observations += "[tap $attempt on $targetName] $geometry " +
                "focusBefore=$focusBefore " +
                "focusAfter=${gate.describeFocus()} events=${gate.touchLog.joinToString()} " +
                "open=${gate.isOpen()} outcome=${gate.store.value} injections=$injections; "
        }
        assertEquals(
            "A single ordinary touch must activate $targetName without keyboard help: $observations",
            1,
            resolvedAttempt,
        )
    }

    @Test
    fun recreatingLauncherWhileProposalIsShownLeavesNoDuplicateOrOrganizerRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val proposalPrefs = LauncherPrefs.get(context)
        val originalProposalOutcome = proposalPrefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME)
        // Production shares one companion process-presentation state across activity instances;
        // mirroring it here exercises the real claim lifecycle across recreation.
        val sharedProcessState = OrganizationOnboardingProposalProcessState()
        val store = FakeStore()
        val admissions = AtomicInteger()

        fun makeOwner(launcher: LawnchairLauncher) = OrganizationOnboardingProposal(
            launcher = launcher,
            controller = OrganizationOnboardingProposalController(store, sharedProcessState),
            admitReview = {
                admissions.incrementAndGet()
                ManualOrganizationRun.StartOutcome.Busy
            },
            isWorkspaceReady = { true },
        )

        try {
            // Keep the production singleton from claiming the shared slot while this test owns it.
            proposalPrefs.put(
                OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME,
                OrganizationOnboardingProposalOutcome.SKIPPED.name,
            )
            startLauncher(context)
            val launcherBeforeRecreation = awaitResumedLauncher()
            instrumentation.runOnMainSync {
                AbstractFloatingView.closeOpenViews(launcherBeforeRecreation, false, AbstractFloatingView.TYPE_ALL)
                val ownerBefore = makeOwner(launcherBeforeRecreation)
                ownerBefore.onLauncherResumed()
                ownerBefore.onInitialWorkspaceBound()
            }
            awaitProductionProposal(launcherBeforeRecreation)

            instrumentation.runOnMainSync { launcherBeforeRecreation.recreate() }
            val launcherAfterRecreation = awaitResumedLauncher(excluding = launcherBeforeRecreation)
            instrumentation.runOnMainSync {
                val ownerAfter = makeOwner(launcherAfterRecreation)
                ownerAfter.onLauncherResumed()
                ownerAfter.onInitialWorkspaceBound()
                assertFalse(
                    "recreation must not leave a duplicate or stuck proposal on the new launcher",
                    AbstractFloatingView.getTopOpenView(launcherAfterRecreation) is
                        OrganizationOnboardingProposal.OrganizationOnboardingProposalView,
                )
            }
            assertEquals(null, store.value)
            assertEquals(0, admissions.get())
        } finally {
            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, originalProposalOutcome)
        }
    }

    @Test
    fun busyReviewKeepsProposalOutcomeUntouchedAndRetryableByRealTouch() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            gate.deliveredTap(gate.content.reviewButton)
            awaitAdmissionCount(gate.admissions, 1)
            awaitReviewActionEnabled(gate.content)
            assertTrue(gate.isOpen())
            assertEquals(null, gate.store.value)

            gate.deliveredTap(gate.content.reviewButton)
            awaitAdmissionCount(gate.admissions, 2)
            awaitReviewActionEnabled(gate.content)
            assertTrue("a busy admission must keep the proposal touch-retryable", gate.isOpen())
            assertEquals(null, gate.store.value)
        } finally {
            gate.restore()
        }
    }

    @Test
    fun realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            gate.reviewOutcome.set(
                ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)),
            )
            gate.deliveredTap(gate.content.reviewButton)
            awaitResumedPreferenceActivity()
        } finally {
            gate.restore()
        }
        assertEquals(
            OrganizationOnboardingProposalOutcome.REVIEWED,
            gate.store.value,
        )
        assertFalse(gate.isOpen())
    }

    @Test
    fun skippedAndReviewedOutcomesNeverResurfaceAfterAColdStart() {
        val store = FakeStore()

        store.value = OrganizationOnboardingProposalOutcome.SKIPPED
        assertFalse(OrganizationOnboardingProposalController(store).isEligible())

        store.value = OrganizationOnboardingProposalOutcome.REVIEWED
        assertFalse(OrganizationOnboardingProposalController(store).isEligible())
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
                assertTrue(
                    "launcher must be RESUMED after the HOME relaunch",
                    launcher.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                )
                assertFalse(
                    "no proposal may be open before the owner resumes; open=" +
                        AbstractFloatingView.getTopOpenView(launcher),
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
            awaitReviewActionEnabled(content)
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
            scenario.onActivity {
                assertTrue(content.title.requestFocus())
                assertTrue(content.title.hasFocus())
                // Action buttons no longer take programmatic focus while the device is in touch
                // mode (Issue #137 fix); keyboard reach is covered by the DPAD traversal tests.
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

    private fun awaitInputFocus(viewProvider: () -> View, description: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = viewProvider().hasFocus()
            }
            if (focused) return
            SystemClock.sleep(100)
        }
        error("$description did not receive input focus")
    }

    private fun awaitAnyInputFocus(vararg views: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = views.any(View::hasFocus)
            }
            if (focused) return
            SystemClock.sleep(100)
        }
        error("No proposal action received input focus after DPAD traversal")
    }

    private fun awaitAdmissionCount(admissionCount: AtomicInteger, expected: Int) {
        repeat(50) {
            if (admissionCount.get() == expected) return
            SystemClock.sleep(100)
        }
        error("Expected $expected onboarding review admissions, got ${admissionCount.get()}")
    }

    private fun awaitReviewActionEnabled(content: OrganizationOnboardingProposalContent) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var enabled = false
            instrumentation.runOnMainSync {
                enabled = content.reviewButton.isEnabled
            }
            if (enabled) return
            SystemClock.sleep(100)
        }
        error("Onboarding review action did not become enabled after admission completed")
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

    private fun awaitResumedLauncher(
        expectedFontScale: Float? = null,
        excluding: LawnchairLauncher? = null,
    ): LawnchairLauncher {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(120) {
            var candidate: LawnchairLauncher? = null
            instrumentation.runOnMainSync {
                candidate = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<LawnchairLauncher>()
                    .singleOrNull()
                    ?.takeIf { launcher ->
                        launcher !== excluding &&
                            !launcher.isFinishing &&
                            !launcher.isDestroyed &&
                            launcher.dragLayer.isAttachedToWindow &&
                            launcher.dragLayer.isLaidOut &&
                            (expectedFontScale == null ||
                                launcher.resources.configuration.fontScale == expectedFontScale)
                    }
            }
            candidate?.let { return it }
            SystemClock.sleep(100)
        }
        error(
            "LawnchairLauncher did not reach an attached, laid-out RESUMED state" +
                (expectedFontScale?.let { " with fontScale=$it" } ?: "") +
                " after HOME launch",
        )
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

    private fun dispatchLauncherKey(launcher: LawnchairLauncher, keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            launcher.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun sendKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private class Callbacks {
        var later = 0
        var skip = 0
        var review = 0
    }

    /**
     * Shows the real floating-host proposal on a resumed launcher and records the touch/focus
     * observations required by the Issue #137 Phase 0 gate.
     */
    private inner class TouchActivationGate {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = FakeStore()
        val touchLog: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val admissions = AtomicInteger()
        val reviewOutcome = AtomicReference<ManualOrganizationRun.StartOutcome>(
            ManualOrganizationRun.StartOutcome.Busy,
        )
        lateinit var launcher: LawnchairLauncher
            private set
        lateinit var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            private set
        lateinit var content: OrganizationOnboardingProposalContent
            private set

        private lateinit var originalOutcome: String

        fun show() {
            originalOutcome = LauncherPrefs.get(instrumentation.targetContext)
                .get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME)
            // Keep the production owner from claiming the shared process presentation slot.
            LauncherPrefs.get(instrumentation.targetContext).put(
                OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME,
                OrganizationOnboardingProposalOutcome.SKIPPED.name,
            )
            startLauncher(instrumentation.targetContext)
            launcher = awaitResumedLauncher()
            instrumentation.runOnMainSync {
                // Start from a clean floating-view baseline regardless of cross-test ordering.
                AbstractFloatingView.closeOpenViews(launcher, false, AbstractFloatingView.TYPE_ALL)
                proposal = OrganizationOnboardingProposal.OrganizationOnboardingProposalView(
                    launcher,
                    OrganizationOnboardingProposalController(store),
                    admitReview = {
                        admissions.incrementAndGet()
                        reviewOutcome.get()
                    },
                )
                content = proposal.getChildAt(0) as OrganizationOnboardingProposalContent
                listOf(content.laterButton, content.skipButton, content.reviewButton).forEach { button ->
                    button.setOnTouchListener { view, event ->
                        if (touchLog.size < MAX_RECORDED_TOUCH_EVENTS) {
                            touchLog.add("${(view as Button).text}:${touchActionName(event)}")
                        }
                        false
                    }
                }
                proposal.show()
            }
            awaitVisibleProposalActions(launcher, content)
        }

        fun awaitInitialFocus() {
            awaitInputFocus({ content.title }, "proposal title")
        }

        fun describeFocus(): String {
            var description = "unknown"
            instrumentation.runOnMainSync {
                description = when (val focused = proposal.findFocus()) {
                    null -> "none"
                    content.title -> "title"
                    content.laterButton -> "laterButton"
                    content.skipButton -> "skipButton"
                    content.reviewButton -> "reviewButton"
                    proposal -> "proposalRoot"
                    else -> focused.javaClass.simpleName
                }
            }
            return description
        }

        fun describeGeometry(target: View): String {
            var description = "geometry unavailable"
            instrumentation.runOnMainSync {
                val targetRect = Rect().also { target.getGlobalVisibleRect(it) }
                val proposalRect = Rect().also { proposal.getGlobalVisibleRect(it) }
                val layerOrigin = IntArray(2).also { launcher.dragLayer.getLocationOnScreen(it) }
                    .contentToString()
                description =
                    "target=${targetRect}, proposal=${proposalRect}, layerOrigin=$layerOrigin, " +
                        "attached=${target.isAttachedToWindow}"
            }
            return description
        }

        fun isOpen(): Boolean {
            var open = false
            instrumentation.runOnMainSync { open = proposal.isOpen }
            return open
        }

        fun tapCenterOf(view: View) {
            val location = IntArray(2)
            var width = 0
            var height = 0
            var attached = false
            instrumentation.runOnMainSync {
                view.getLocationOnScreen(location)
                width = view.width
                height = view.height
                attached = view.isAttachedToWindow
            }
            check(attached && width > 0 && height > 0) {
                "cannot tap a detached or unsized view (attached=$attached, ${width}x$height); " +
                    "the proposal surface disappeared before the touch stream"
            }
            val x = (location[0] + width / 2).toFloat()
            val y = (location[1] + height / 2).toFloat()
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val downInjected = instrumentation.uiAutomation.injectInputEvent(down, true)
            SystemClock.sleep(TOUCH_INJECTION_GAP_MILLIS)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            val upInjected = instrumentation.uiAutomation.injectInputEvent(up, true)
            down.recycle()
            up.recycle()
            check(downInjected && upInjected) {
                "real touch injection was rejected by the system (down=$downInjected, up=$upInjected)"
            }
        }

        /**
         * Injects a tap and retries until the touch stream is provably delivered to the view.
         * Injections lost to launcher-startup input races never reach the window and must not
         * count as taps; the caller's "single tap" requirement applies to delivered taps only.
         */
        fun deliveredTap(view: View): Int {
            val eventsBefore = touchLog.size
            var attempts = 0
            while (attempts < MAX_INJECTION_ATTEMPTS_PER_TAP) {
                attempts++
                tapCenterOf(view)
                val deadline = SystemClock.uptimeMillis() + DELIVERY_TIMEOUT_MILLIS
                while (SystemClock.uptimeMillis() < deadline) {
                    if (touchLog.size > eventsBefore) return attempts
                    SystemClock.sleep(50)
                }
            }
            error("touch injection never reached the proposal after $attempts attempts")
        }

        /** Returns true once the proposal closed; otherwise records the unresolved state. */
        fun awaitResolvedOrRecord(): Boolean {
            repeat(50) {
                if (!isOpen()) return true
                SystemClock.sleep(100)
            }
            return false
        }

        fun restore() {
            LauncherPrefs.get(instrumentation.targetContext).put(
                OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME,
                originalOutcome,
            )
        }

        private fun touchActionName(event: MotionEvent): String = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_MOVE -> "MOVE"
            else -> "ACTION_${event.actionMasked}"
        }
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
        const val MAX_TOUCH_ACTIVATION_TAPS = 2
        const val MAX_RECORDED_TOUCH_EVENTS = 60
        const val TOUCH_INJECTION_GAP_MILLIS = 60L
        const val MAX_INJECTION_ATTEMPTS_PER_TAP = 3
        const val DELIVERY_TIMEOUT_MILLIS = 1500L
    }
}
