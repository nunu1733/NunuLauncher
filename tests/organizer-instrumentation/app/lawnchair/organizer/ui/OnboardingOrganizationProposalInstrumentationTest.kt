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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.HomeScreen
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
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
            sendKey(launcher, KeyEvent.KEYCODE_DPAD_DOWN)
            awaitAnyInputFocus(launcher, content.laterButton, content.skipButton, content.reviewButton)
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
            // Issue #232: the deferred proposal shows the re-entry hint on the same host; the
            // hint must also fit the viewport and safe area at 200% font scale.
            lateinit var hint: OrganizationOnboardingReentryHint
            instrumentation.runOnMainSync {
                hint = OrganizationOnboardingReentryHint(launcher)
                hint.show()
            }
            awaitInputFocus({ hint }, "re-entry hint root")
            instrumentation.runOnMainSync {
                // The combined title + body must survive 200% font as one accessibility node.
                assertEquals(
                    OrganizationOnboardingReentryHint.combinedAccessibilityText(launcher),
                    hint.contentDescription,
                )
                val title = hint.getChildAt(0) as TextView
                val body = hint.getChildAt(1) as TextView
                assertTrue(title.isShown && body.isShown)
                assertTrue(title.height > 0 && body.height > 0)
            }
            instrumentation.runOnMainSync {
                val viewport = Rect()
                assertTrue(launcher.dragLayer.getGlobalVisibleRect(viewport))
                assertEquals(TWO_HUNDRED_PERCENT_FONT_SCALE, hint.resources.configuration.fontScale)
                val safeAreaBottom = viewport.bottom - launcher.windowManager.currentWindowMetrics.windowInsets
                    .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    .bottom
                val hintBounds = Rect()
                assertTrue(hint.getGlobalVisibleRect(hintBounds))
                assertTrue(
                    "re-entry hint must stay within the viewport (bounds=$hintBounds, viewport=$viewport)",
                    hintBounds.bottom <= viewport.bottom,
                )
                assertTrue(
                    "re-entry hint must stay above the system bar safe area " +
                        "(bottom=${hintBounds.bottom}, safeAreaBottom=$safeAreaBottom)",
                    hintBounds.bottom <= safeAreaBottom,
                )
                hint.close(false)
                assertFalse(hint.isOpen)
            }
            awaitInputFocus({ focusBeforeOpen }, "focus restored after hint close")
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
    fun laterTapShowsTheReentryHintAndPreservesTheDeferOutcome() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            gate.deliveredTap(gate.content.laterButton)

            // The hint replaces the proposal on the shared popup surface; the defer outcome was
            // recorded by the proposal before the hint appeared and must survive it.
            val hint = awaitOpenReentryHint(gate.launcher)
            assertEquals(
                OrganizationOnboardingProposalOutcome.DEFERRED,
                gate.store.value,
            )
            gate.instrumentation.runOnMainSync {
                assertEquals(2, hint.childCount)
                // The single accessibility announcement must carry the title AND the re-entry
                // path, assembled from the real settings labels (spec 232 review).
                val context = gate.launcher
                val expectedAnnouncement = OrganizationOnboardingReentryHint.combinedAccessibilityText(context)
                assertEquals(expectedAnnouncement, hint.contentDescription)
                listOf(
                    R.string.settings_button_text,
                    R.string.home_screen_label,
                    R.string.manual_organization_title,
                ).forEach { label ->
                    val pathLabel = context.getString(label)
                    assertTrue(
                        "hint announcement must name the real settings label: $pathLabel",
                        hint.contentDescription.contains(pathLabel),
                    )
                }
            }

            // Back closes the hint without writing any proposal outcome.
            gate.instrumentation.runOnMainSync { hint.onBackInvoked() }
            awaitClosedReentryHint(gate.launcher)
            assertEquals(
                OrganizationOnboardingProposalOutcome.DEFERRED,
                gate.store.value,
            )
        } finally {
            gate.restore()
        }
    }

    @Test
    fun reentryHintDismissesOnOutsideTouchWithoutTouchingTheProposalOutcome() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            gate.deliveredTap(gate.content.laterButton)
            val hint = awaitOpenReentryHint(gate.launcher)
            gate.instrumentation.runOnMainSync {
                assertEquals(
                    OrganizationOnboardingProposalOutcome.DEFERRED,
                    gate.store.value,
                )
            }

            // A real touch stream below the hint (outside its bounds) dismisses it and falls
            // through to the launcher; the outcome stays exactly `DEFERRED`.
            val injected = gate.deliveredTapOutside(hint)
            assertEquals(1, injected)
            awaitClosedReentryHint(gate.launcher)
            gate.instrumentation.runOnMainSync {
                assertEquals(
                    OrganizationOnboardingProposalOutcome.DEFERRED,
                    gate.store.value,
                )
            }
        } finally {
            gate.restore()
        }
    }

    @Test
    fun laterThenHintCloseRestoresThePreProposalFocusTargetInOneProductionPath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var focusTarget: View
        // The focus target must own focus BEFORE the proposal is shown, so the proposal's
        // focusBeforeOpen — and through the queued handoff, the hint's — is this target.
        val gate = TouchActivationGate(
            beforeShow = { launcher ->
                focusTarget = View(launcher).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                launcher.dragLayer.addView(focusTarget, FrameLayout.LayoutParams(1, 1))
                assertTrue(focusTarget.requestFocus())
            },
        )
        gate.show()
        try {
            gate.awaitInitialFocus()
            gate.deliveredTap(gate.content.laterButton)

            // The proposal hands focus back to the pre-proposal target via its queued restore,
            // and the hint (queued behind it) captures that same target as its own restore
            // point; the hint takes focus while visible.
            val hint = awaitOpenReentryHint(gate.launcher)
            awaitInputFocus({ hint }, "re-entry hint root")
            gate.instrumentation.runOnMainSync { hint.close(false) }
            awaitClosedReentryHint(gate.launcher)
            awaitInputFocus({ focusTarget }, "pre-proposal focus target restored after production Later→hint→close")
            instrumentation.runOnMainSync {
                gate.launcher.dragLayer.removeView(focusTarget)
            }
        } finally {
            gate.restore()
        }
    }

    @Test
    fun reentryHintAutoDismissesAfterTheTimeoutWithoutTouchingTheOutcome() {
        val gate = TouchActivationGate()
        gate.show()
        try {
            gate.awaitInitialFocus()
            gate.deliveredTap(gate.content.laterButton)
            val hint = awaitOpenReentryHint(gate.launcher)

            // The production postDelayed must close the hint on its own; the poll window
            // extends well past the 6s timeout to absorb emulator scheduling stalls.
            awaitClosedReentryHint(gate.launcher, iterations = AWAIT_HINT_TIMEOUT_ITERATIONS)
            gate.instrumentation.runOnMainSync {
                assertEquals(
                    OrganizationOnboardingProposalOutcome.DEFERRED,
                    gate.store.value,
                )
            }
        } finally {
            gate.restore()
        }
    }

    @Test
    fun hintDisplayFailureNeverUndoesTheDeferOutcomeOrCrashes() {
        val gate = TouchActivationGate(
            showHint = { launcher ->
                OrganizationOnboardingReentryHint.showOrganizationReentryHint(launcher) {
                    error("injected hint display failure")
                }
            },
        )
        try {
            gate.show()
            gate.awaitInitialFocus()
            gate.deliveredTap(gate.content.laterButton)
            // The click dispatches on the main thread after the injected UP; wait for the
            // proposal to close so the outcome assertion cannot race the defer recording.
            assertTrue("proposal must close after the Later tap", gate.awaitResolvedOrRecord())

            // The proposal still resolved as defer; the injected display failure was swallowed
            // by the production display path (runCatching) instead of crashing the launcher.
            assertEquals(
                OrganizationOnboardingProposalOutcome.DEFERRED,
                gate.store.value,
            )
            gate.instrumentation.runOnMainSync {
                assertFalse(gate.proposal.isOpen)
                assertFalse(
                    AbstractFloatingView.getTopOpenView(gate.launcher)
                        is OrganizationOnboardingReentryHint,
                )
            }
            gate.instrumentation.waitForIdleSync()
        } finally {
            gate.restore()
        }
    }

    @Test
    fun homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entryLabel = context.getString(R.string.manual_organization_title)
        val generalHeading = context.getString(R.string.general_label)

        context.startActivity(
            PreferenceActivity.createIntent(context, HomeScreen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val activity = awaitResumedPreferenceActivity()
        val entryBounds = awaitAccessibilityTextBounds(activity, entryLabel, "organizer entry")
        val generalBounds = awaitAccessibilityTextBounds(activity, generalHeading, "General heading")
        // The next group heading after General; the entry must sit above it so a regression
        // that parks the row back inside the (below-the-fold) Layout section cannot pass on
        // tall viewports where both sections happen to compose in the first screenful.
        val actionsBounds = awaitAccessibilityTextBounds(
            activity,
            context.getString(R.string.home_screen_actions),
            "Home screen actions heading",
        )
        instrumentation.runOnMainSync {
            val viewport = Rect()
            assertTrue(activity.window.decorView.getGlobalVisibleRect(viewport))
            assertTrue(
                "the organizer entry must render in the first viewport after the Issue #232 " +
                    "promotion (entry=$entryBounds, viewport=$viewport)",
                entryBounds.top < viewport.bottom,
            )
            assertTrue(
                "the organizer entry must sit inside the General section " +
                    "(heading=$generalBounds, entry=$entryBounds)",
                generalBounds.top <= entryBounds.top,
            )
            assertTrue(
                "the organizer entry must precede the first group after General " +
                    "(entry=$entryBounds, actions=$actionsBounds)",
                entryBounds.bottom <= actionsBounds.top,
            )
        }
    }

    /** Walks the real accessibility tree (Compose semantics included) for a text node's bounds. */
    private fun awaitAccessibilityTextBounds(
        activity: PreferenceActivity,
        text: String,
        description: String,
    ): Rect {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Issue #300: the scan assumes the target activity is the frontmost focused window; the
        // gate observes (and repairs) that premise before the walk (TS-AC-04).
        InjectedInputEnvironment.ensureWindowFocused(activity)
        repeat(50) {
            // rootInActiveWindow returns the sealed node tree of the frontmost window; plain
            // createAccessibilityNodeInfo children are unsealed on API 36 and refuse getChild.
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName == activity.packageName) {
                findAccessibilityTextBounds(root, text)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        // Issue #300: only a foreign frontmost window or a lost activity focus is an environment
        // anomaly; a missing node under a healthy frontmost window is the product regression the
        // test exists to detect and must not poison the run (TS-AC-04).
        val snapshot = InjectedInputEnvironment.currentSnapshot()
        var activityWindowFocused = false
        instrumentation.runOnMainSync {
            activityWindowFocused = activity.window.decorView.hasWindowFocus()
        }
        val deviceState = InjectedInputEnvironment.describeDeviceState()
        val baseMessage = "$description with text '$text' was not found in the accessibility tree"
        when (classifyAccessibilityTimeout(snapshot, activity.packageName, activityWindowFocused)) {
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY -> {
                val evidence = EnvironmentFailureEvidence(
                    label = "accessibility-frontmost-timeout",
                    deviceState = deviceState,
                    inputEnvironment = "$baseMessage; activityWindowFocused=$activityWindowFocused",
                )
                val retained = InjectedInputEnvironment.markEnvironmentFailure(evidence)
                error(
                    "$baseMessage; ${InjectedInputEnvironment.ACCESSIBILITY_ENVIRONMENT_PREFIX}; " +
                        "${buildGateFailureMessage(retained)}",
                )
            }
            EnvironmentFailureKind.NODE_NOT_FOUND, EnvironmentFailureKind.LOCAL_REGRESSION ->
                error("$baseMessage; frontmostPackage=${snapshot.frontmostPackage}, deviceState=$deviceState")
        }
    }

    private fun findAccessibilityTextBounds(node: AccessibilityNodeInfo, target: String): Rect? =
        walkAccessibilityNodes(node, target, depth = 0)

    private fun walkAccessibilityNodes(
        node: AccessibilityNodeInfo,
        target: String,
        depth: Int,
    ): Rect? {
        if (depth > MAX_ACCESSIBILITY_TRAVERSAL_DEPTH) return null
        if (node.text?.toString() == target) return Rect(node.boundsInScreen)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                walkAccessibilityNodes(child, target, depth + 1)?.let { return it }
            }
        }
        return null
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
            val preferenceActivity = awaitResumedPreferenceActivity()
            // PreferenceActivity reaching RESUMED does not prove the launcher already paused:
            // activity transitions overlap both RESUMED states, and invoking the owner inside
            // that window legitimately shows the proposal immediately (Issue #142).
            awaitLauncherBelowResumedState(launcher)
            instrumentation.runOnMainSync {
                owner.onLauncherResumed()
                owner.onInitialWorkspaceBound()
                assertNoProposalViewChild(
                    launcher,
                    "a launcher that surrendered RESUMED must defer the proposal bind",
                )
            }

            proposalPrefs.put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, "")
            // Finish the preferences activity instead of pressing HOME: a queued HOME intent
            // can land after the proposal is shown and legitimately close it through
            // closeAllOpenViewsExcept, which made this choreography racy (Issue #142).
            instrumentation.runOnMainSync { preferenceActivity.finish() }
            val resumedLauncher = awaitResumedLauncher()
            assertTrue(launcher === resumedLauncher)
            lateinit var proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView
            instrumentation.runOnMainSync {
                assertTrue(
                    "launcher must be RESUMED after the HOME relaunch",
                    launcher.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                )
                // Scan children instead of open views: a floating view added but not yet
                // attached stays invisible to getTopOpenView until onAttachedToWindow sets
                // mIsOpen, so an open-view assert can miss it (Issue #142).
                assertNoProposalViewChild(
                    launcher,
                    "no proposal may exist before the owner resumes after the HOME relaunch",
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
            // The admission runs on a lifecycleScope coroutine that hops to Dispatchers.IO;
            // under a loaded shared emulator that hop has stalled past a 5s window (Issue #142).
            fun reviewPipelineDiagnostics(): String {
                var state = "diagnostics unavailable"
                instrumentation.runOnMainSync {
                    state = "proposalOpen=${proposal.isOpen}, attached=${proposal.isAttachedToWindow}, " +
                        "reviewEnabled=${content.reviewButton.isEnabled}, " +
                        "lifecycle=${launcher.lifecycle.currentState}, launcherDestroyed=${launcher.isDestroyed}"
                }
                return state
            }
            awaitAdmissionCount(admissionCount, 1, ::reviewPipelineDiagnostics)
            awaitReviewActionEnabled(content)
            instrumentation.runOnMainSync {
                assertTrue(proposal.isOpen)
            }
            assertEquals("", proposalPrefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME))

            reviewOutcome.set(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)))
            instrumentation.runOnMainSync {
                content.reviewButton.performClick()
            }
            awaitAdmissionCount(admissionCount, 2, ::reviewPipelineDiagnostics)
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

    private fun awaitAnyInputFocus(launcher: LawnchairLauncher, vararg views: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = views.any(View::hasFocus)
            }
            if (focused) return
            SystemClock.sleep(100)
        }
        error(
            "No proposal action received input focus after DPAD traversal; " +
                describeInputEnvironment(launcher, null, views.firstOrNull()),
        )
    }

    /**
     * Dumps the input-relevant world state for failure diagnostics: window focus, focused view,
     * proposal attachment/openness, target geometry, and whatever floating view is on top.
     */
    private fun describeInputEnvironment(
        launcher: LawnchairLauncher,
        proposal: OrganizationOnboardingProposal.OrganizationOnboardingProposalView?,
        target: View?,
    ): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var description = "environment unavailable"
        instrumentation.runOnMainSync {
            val targetLocation = IntArray(2)
            target?.getLocationOnScreen(targetLocation)
            description = "launcherWindowFocus=${launcher.hasWindowFocus()}, " +
                "activityFocus=${launcher.currentFocus}, treeFocus=${proposal?.findFocus()}, " +
                "proposalOpen=${proposal?.isOpen}, proposalAttached=${proposal?.isAttachedToWindow}, " +
                "targetShown=${target?.isShown}, targetLocation=${targetLocation.contentToString()}, " +
                "targetSize=${target?.width}x${target?.height}, " +
                "topOpenView=${AbstractFloatingView.getTopOpenView(launcher)}, " +
                // Issue #300: merge the device-level state into one diagnostic format.
                "deviceState=${InjectedInputEnvironment.describeDeviceState()}"
        }
        return description
    }

    private fun awaitAdmissionCount(
        admissionCount: AtomicInteger,
        expected: Int,
        describeFailureContext: () -> String = { "" },
    ) {
        repeat(REVIEW_ADMISSION_ITERATIONS) {
            if (admissionCount.get() == expected) return
            SystemClock.sleep(100)
        }
        error(
            "Expected $expected onboarding review admissions, got ${admissionCount.get()} " +
                "after ${REVIEW_ADMISSION_ITERATIONS * 100}ms. ${describeFailureContext()}",
        )
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

    /** Waits for the Issue #232 hint that replaced the closed proposal on the popup surface. */
    private fun awaitOpenReentryHint(
        launcher: LawnchairLauncher,
    ): OrganizationOnboardingReentryHint {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(50) {
            var hint: OrganizationOnboardingReentryHint? = null
            instrumentation.runOnMainSync {
                hint = AbstractFloatingView.getTopOpenView(launcher) as? OrganizationOnboardingReentryHint
            }
            hint?.let { return it }
            SystemClock.sleep(100)
        }
        error("Organization re-entry hint was not shown after `Later`")
    }

    private fun awaitClosedReentryHint(
        launcher: LawnchairLauncher,
        iterations: Int = 50,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(iterations) {
            if (isHintClosed(launcher)) return
            SystemClock.sleep(100)
        }
        error("re-entry hint did not close")
    }

    /** The hint is closed when no drag-layer child is a re-entry hint (attached or stray). */
    private fun isHintClosed(launcher: LawnchairLauncher): Boolean {
        var closed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            closed = (0 until launcher.dragLayer.childCount)
                .none { launcher.dragLayer.getChildAt(it) is OrganizationOnboardingReentryHint }
        }
        return closed
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
        repeat(120) {
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

    /**
     * Waits until the launcher has surrendered RESUMED to the activity in front of it. Activity
     * transitions overlap RESUMED states, so a freshly resumed PreferenceActivity does not yet
     * prove the launcher paused; polling the lifecycle state removes that race (Issue #142).
     */
    private fun awaitLauncherBelowResumedState(launcher: LawnchairLauncher) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var state = Lifecycle.State.RESUMED
        repeat(120) {
            instrumentation.runOnMainSync {
                state = launcher.lifecycle.currentState
            }
            if (!state.isAtLeast(Lifecycle.State.RESUMED)) return
            SystemClock.sleep(100)
        }
        error(
            "LawnchairLauncher never left the RESUMED state after PreferenceActivity came " +
                "forward (last=$state)",
        )
    }

    /**
     * Asserts no proposal view exists as a drag-layer child at all. Open-view scans miss views
     * added but not yet attached: mIsOpen flips only in onAttachedToWindow, so such strays are
     * invisible to [AbstractFloatingView.getTopOpenView] (Issue #142).
     */
    private fun assertNoProposalViewChild(
        launcher: LawnchairLauncher,
        expectation: String,
    ) {
        val dragLayer = launcher.dragLayer
        val strays = mutableListOf<String>()
        for (index in 0 until dragLayer.childCount) {
            val child = dragLayer.getChildAt(index)
            if (child is OrganizationOnboardingProposal.OrganizationOnboardingProposalView) {
                strays += "open=${child.isOpen}, attached=${child.isAttachedToWindow}"
            }
        }
        assertTrue("$expectation; found proposal views: $strays", strays.isEmpty())
    }

    private fun awaitResumedLauncher(
        expectedFontScale: Float? = null,
        excluding: LawnchairLauncher? = null,
    ): LawnchairLauncher {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Issue #300: device-level repair before any window exists; also the sticky health entry.
        InjectedInputEnvironment.ensureInteractiveUnlocked()
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
        // Issue #300: classify the timeout. Only a broken environment premise (screen off,
        // keyguard, foreign frontmost window) poisons the run; a healthy-looking environment
        // means the launcher itself failed to resume and stays a local failure (TS-AC-04).
        val baseMessage = "LawnchairLauncher did not reach an attached, laid-out RESUMED state" +
            (expectedFontScale?.let { " with fontScale=$it" } ?: "") +
            " after HOME launch"
        val snapshot = InjectedInputEnvironment.currentSnapshot()
        when (classifyLauncherAwaitTimeout(snapshot, instrumentation.targetContext.packageName)) {
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY -> {
                val evidence = EnvironmentFailureEvidence(
                    label = "launcher-resume-timeout",
                    deviceState = InjectedInputEnvironment.describeDeviceState(),
                    inputEnvironment = baseMessage,
                )
                val retained = InjectedInputEnvironment.markEnvironmentFailure(evidence)
                error(
                    "${InjectedInputEnvironment.RESUME_ENVIRONMENT_PREFIX}; " +
                        "${buildGateFailureMessage(retained)}",
                )
            }
            EnvironmentFailureKind.LOCAL_REGRESSION, EnvironmentFailureKind.NODE_NOT_FOUND ->
                error("$baseMessage; deviceState=${InjectedInputEnvironment.describeDeviceState()}")
        }
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
        // Drain the output stream before closing: it makes the command synchronous so callers
        // observe its completed effect instead of racing the shell-side execution.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        java.io.FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
        descriptor.close()
    }

    private fun dispatchLauncherKey(launcher: LawnchairLauncher, keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            launcher.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun sendKey(launcher: LawnchairLauncher, keyCode: Int) {
        // Issue #300: real key streams only traverse focus inside a focused window (TS-AC-01).
        InjectedInputEnvironment.ensureWindowFocused(launcher)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private class Callbacks {
        var later = 0
        var skip = 0
        var review = 0
    }

    /**
     * Shows the real floating-host proposal on a resumed launcher and records the touch/focus
     * observations required by the Issue #137 Phase 0 gate. `showHint` overrides the Issue #232
     * re-entry hint entry point (default: the production display path).
     */
    private inner class TouchActivationGate(
        private val showHint: ((LawnchairLauncher) -> Unit)? = null,
        private val beforeShow: ((LawnchairLauncher) -> Unit)? = null,
    ) {
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
            // Issue #300: observe (and repair) the target window's focus before the proposal
            // surface exists, so a per-boot focus anomaly fails here with evidence instead of
            // being re-discovered by every later injection (TS-AC-01/02).
            InjectedInputEnvironment.ensureWindowFocused(launcher)
            instrumentation.runOnMainSync {
                // Start from a clean floating-view baseline regardless of cross-test ordering.
                AbstractFloatingView.closeOpenViews(launcher, false, AbstractFloatingView.TYPE_ALL)
                // Runs inside the same main-sync block as proposal.show() so state prepared
                // here (e.g. a focused pre-proposal target) is what the proposal captures.
                beforeShow?.invoke(launcher)
                proposal = OrganizationOnboardingProposal.OrganizationOnboardingProposalView(
                    launcher,
                    OrganizationOnboardingProposalController(store),
                    admitReview = {
                        admissions.incrementAndGet()
                        reviewOutcome.get()
                    },
                    showHint = showHint ?: { OrganizationOnboardingReentryHint.showOrganizationReentryHint(it) },
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
            // Issue #300 (review P1): every injectInputEvent re-observes the launcher window's
            // focus immediately before injecting — including each half of the DOWN/UP pair — so
            // retries inside deliveredTap can never inject into a lost window (TS-AC-01). A gate
            // failure between DOWN and UP aborts the attempt; the run is poisoned at that point
            // and the residual pressed state is harmless.
            InjectedInputEnvironment.ensureWindowFocused(launcher)
            val downInjected = instrumentation.uiAutomation.injectInputEvent(down, true)
            SystemClock.sleep(TOUCH_INJECTION_GAP_MILLIS)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            InjectedInputEnvironment.ensureWindowFocused(launcher)
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
            // Issue #300: only inject into a focused window; a poisoned run fails here at entry
            // instead of re-waiting per tap (TS-AC-01/03). Per-attempt re-observation lives in
            // tapCenterOf, the single injection point of this loop.
            InjectedInputEnvironment.ensureWindowFocused(launcher)
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
            error(
                "touch injection never reached the proposal after $attempts attempts; " +
                    "events=$touchLog; " +
                    describeInputEnvironment(launcher, proposal, view),
            )
        }

        /** Returns true once the proposal closed; otherwise records the unresolved state. */
        fun awaitResolvedOrRecord(): Boolean {
            repeat(50) {
                if (!isOpen()) return true
                SystemClock.sleep(100)
            }
            return false
        }

        /**
         * Injects a real touch stream at a point outside the hint's bounds to exercise the
         * outside-dismiss path. Delivery is judged by the hint's own openness flipping from
         * open to closed — the proposal is already closed at this point, so polling the
         * proposal would make the check vacuous.
         */
        fun deliveredTapOutside(hint: OrganizationOnboardingReentryHint): Int {
            // Issue #300: same focused-window premise as deliveredTap (TS-AC-01/03).
            InjectedInputEnvironment.ensureWindowFocused(launcher)
            var attempts = 0
            while (attempts < MAX_INJECTION_ATTEMPTS_PER_TAP) {
                attempts++
                val hintLocation = IntArray(2)
                var viewportHeight = 0
                instrumentation.runOnMainSync {
                    hint.getLocationOnScreen(hintLocation)
                    viewportHeight = launcher.dragLayer.height
                }
                val x = 24f
                val y = (viewportHeight - 24f).coerceAtMost(
                    (hintLocation[1].toFloat() - 8f).takeIf { it > 0f } ?: (viewportHeight - 24f).toFloat(),
                )
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                // Issue #300 (review P1): same per-injectInputEvent re-observation as tapCenterOf.
                InjectedInputEnvironment.ensureWindowFocused(launcher)
                val downInjected = instrumentation.uiAutomation.injectInputEvent(down, true)
                SystemClock.sleep(TOUCH_INJECTION_GAP_MILLIS)
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                InjectedInputEnvironment.ensureWindowFocused(launcher)
                val upInjected = instrumentation.uiAutomation.injectInputEvent(up, true)
                down.recycle()
                up.recycle()
                check(downInjected && upInjected) {
                    "real touch injection was rejected by the system (down=$downInjected, up=$upInjected)"
                }
                val deadline = SystemClock.uptimeMillis() + DELIVERY_TIMEOUT_MILLIS
                while (SystemClock.uptimeMillis() < deadline) {
                    if (isHintClosed(launcher)) return attempts
                    SystemClock.sleep(50)
                }
            }
            error("outside touch never dismissed the re-entry hint after $attempts attempts")
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

        /** Guard for the Issue #232 settings-tree walk so a broken tree cannot hang a test. */
        const val MAX_ACCESSIBILITY_TRAVERSAL_DEPTH = 80

        /**
         * 13s > the 6s production timeout, so the auto-dismiss poll cannot pass on scheduling
         * slack alone unless the timer actually fired.
         */
        const val AWAIT_HINT_TIMEOUT_ITERATIONS = 130

        /**
         * Admission waits span the click → lifecycleScope coroutine → Dispatchers.IO hop, whose
         * tail latency under a loaded shared emulator exceeded the previous fixed 5s window.
         */
        const val REVIEW_ADMISSION_ITERATIONS = 150
    }
}
