package app.lawnchair.organizer.ui

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.util.Pair
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.OrganizationEntry
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.provider.RestoreDbTask
import com.android.launcher3.util.OnboardingPrefs
import com.android.launcher3.util.Themes
import com.android.launcher3.views.BaseDragLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only persisted state owned by the organization proposal. None of these choices starts an
 * organization run or represents preview/confirmation authority.
 */
internal enum class OrganizationOnboardingProposalOutcome {
    SKIPPED,
    DEFERRED,
    REVIEWED,
}

/** Provenance must be known before a missing proposal outcome may be considered eligible. */
internal enum class OrganizationOnboardingInstallProvenance {
    FRESH_INSTALL,
    UPGRADE,
    RESTORE,
    UNKNOWN,
}

internal fun classifyOrganizationOnboardingInstallProvenance(
    restorePending: Boolean,
    firstInstallTime: Long?,
    lastUpdateTime: Long?,
    restoreSnapshot: Boolean = false,
): OrganizationOnboardingInstallProvenance = when {
    restorePending || restoreSnapshot -> OrganizationOnboardingInstallProvenance.RESTORE
    firstInstallTime == null || lastUpdateTime == null -> OrganizationOnboardingInstallProvenance.UNKNOWN
    firstInstallTime <= 0L || lastUpdateTime <= 0L -> OrganizationOnboardingInstallProvenance.UNKNOWN
    lastUpdateTime == firstInstallTime -> OrganizationOnboardingInstallProvenance.FRESH_INSTALL
    lastUpdateTime > firstInstallTime -> OrganizationOnboardingInstallProvenance.UPGRADE
    else -> OrganizationOnboardingInstallProvenance.UNKNOWN
}

internal interface OrganizationOnboardingProposalStore {
    fun provenance(): OrganizationOnboardingInstallProvenance

    fun outcome(): OrganizationOnboardingProposalOutcome?

    fun record(outcome: OrganizationOnboardingProposalOutcome)
}

/** Process-local state intentionally disappears on a qualifying cold start. */
internal class OrganizationOnboardingProposalProcessState {
    private var presentationClaimed = false

    fun isSuppressed(): Boolean = presentationClaimed

    fun claimPresentation(): Boolean {
        if (presentationClaimed) return false
        presentationClaimed = true
        return true
    }

    fun suppressForProcess() {
        presentationClaimed = true
    }
}

internal class OrganizationOnboardingProposalController(
    private val store: OrganizationOnboardingProposalStore,
    private val processState: OrganizationOnboardingProposalProcessState = OrganizationOnboardingProposalProcessState(),
) {
    fun isEligible(): Boolean = when (store.outcome()) {
        OrganizationOnboardingProposalOutcome.SKIPPED,
        OrganizationOnboardingProposalOutcome.REVIEWED,
        -> false

        OrganizationOnboardingProposalOutcome.DEFERRED,
        null,
        -> store.provenance() == OrganizationOnboardingInstallProvenance.FRESH_INSTALL && !processState.isSuppressed()
    }

    /** Captures the launcher-owned provenance before the model lifecycle may clear restore flags. */
    fun captureProvenance() {
        store.provenance()
    }

    /** Marks this process as shown before a view is added, preventing duplicate presentation. */
    fun claimPresentation(): Boolean = isEligible() && processState.claimPresentation()

    fun skip() {
        store.record(OrganizationOnboardingProposalOutcome.SKIPPED)
    }

    fun defer() {
        processState.suppressForProcess()
        store.record(OrganizationOnboardingProposalOutcome.DEFERRED)
    }

    /**
     * Starts the shared coordinator before marking the proposal reviewed or opening its screen.
     * A busy coordinator remains untouched and the proposal stays actionable.
     */
    fun review(admit: () -> ManualOrganizationRun.StartOutcome): ManualOrganizationRun.StartOutcome {
        val outcome = admit()
        if (outcome is ManualOrganizationRun.StartOutcome.Started) {
            store.record(OrganizationOnboardingProposalOutcome.REVIEWED)
        }
        return outcome
    }
}

/** The real proposal content shared by the Launcher floating host and connected UI tests. */
internal class OrganizationOnboardingProposalContent(
    context: android.content.Context,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onReview: () -> Unit,
) : LinearLayout(context) {
    val title = TextView(context).apply {
        setText(R.string.organization_onboarding_proposal_title)
        textSize = 20f
        // Issue #123: colors resolve from the activity theme so light/dark both render correctly.
        setTextColor(Themes.getAttrColor(context, android.R.attr.textColorPrimary))
        isFocusable = true
        isFocusableInTouchMode = true
    }
    val laterButton = actionButton(R.string.organization_onboarding_proposal_defer, onLater)
    val skipButton = actionButton(R.string.organization_onboarding_proposal_skip, onSkip)
    val reviewButton = actionButton(R.string.organization_onboarding_proposal_review, onReview)

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        addView(title)
        addView(
            TextView(context).apply {
                setText(R.string.organization_onboarding_proposal_summary)
                setTextColor(Themes.getAttrColor(context, android.R.attr.textColorSecondary))
                setPadding(0, dp(8), 0, dp(12))
            },
        )
        addView(laterButton)
        addView(skipButton)
        addView(reviewButton)
        configureKeyboardTraversal()
    }

    private fun configureKeyboardTraversal() {
        title.id = View.generateViewId()
        laterButton.id = View.generateViewId()
        skipButton.id = View.generateViewId()
        reviewButton.id = View.generateViewId()

        title.nextFocusDownId = laterButton.id
        title.nextFocusForwardId = laterButton.id
        laterButton.nextFocusUpId = title.id
        laterButton.nextFocusDownId = skipButton.id
        laterButton.nextFocusForwardId = skipButton.id
        skipButton.nextFocusUpId = laterButton.id
        skipButton.nextFocusDownId = reviewButton.id
        skipButton.nextFocusForwardId = reviewButton.id
        reviewButton.nextFocusUpId = skipButton.id
        reviewButton.nextFocusDownId = laterButton.id
        reviewButton.nextFocusForwardId = laterButton.id
    }

    private fun actionButton(
        textId: Int,
        onClick: () -> Unit,
    ) = Button(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setText(textId)
        // Action buttons must stay out of touch mode: focusableInTouchMode turns the first tap
        // into a focus change instead of a click (Issue #137). Keyboard traversal still reaches
        // them because any key press ends touch mode.
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * Launcher-owned proposal host. Construction is safe from onCreate, but presentation is allowed
 * only after both the launcher resume and initial workspace-binding callbacks have fired.
 */
internal class OrganizationOnboardingProposal(
    private val launcher: LawnchairLauncher,
    private val controller: OrganizationOnboardingProposalController = OrganizationOnboardingProposalController(
        LauncherProposalStore(launcher),
        processState,
    ),
    private val admitReview: () -> ManualOrganizationRun.StartOutcome = {
        ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)
    },
    private val isWorkspaceReady: () -> Boolean = { !launcher.isWorkspaceLoading },
) {
    private var resumed = false
    private var initialWorkspaceBound = false

    fun captureProvenance() {
        controller.captureProvenance()
    }

    fun onLauncherResumed() {
        resumed = true
        showIfReady()
    }

    fun onInitialWorkspaceBound() {
        initialWorkspaceBound = true
        showIfReady()
    }

    private fun showIfReady() {
        if (
            !resumed ||
            !launcher.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !initialWorkspaceBound ||
            !isWorkspaceReady()
        ) {
            return
        }
        if (AbstractFloatingView.getTopOpenView(launcher) != null) return
        if (!controller.claimPresentation()) return
        OrganizationOnboardingProposalView(launcher, controller, admitReview).show()
    }

    internal class LauncherProposalStore(
        private val launcher: LawnchairLauncher,
        private val installProvenance: OrganizationOnboardingInstallProvenance = classifyInstallProvenance(launcher),
    ) : OrganizationOnboardingProposalStore {
        override fun provenance(): OrganizationOnboardingInstallProvenance = installProvenance

        override fun outcome(): OrganizationOnboardingProposalOutcome? = LauncherPrefs.get(launcher).get(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME)
            .takeIf { it.isNotEmpty() }
            ?.let { value -> OrganizationOnboardingProposalOutcome.entries.firstOrNull { it.name == value } }

        override fun record(outcome: OrganizationOnboardingProposalOutcome) {
            LauncherPrefs.get(launcher).put(OnboardingPrefs.ORGANIZATION_PROPOSAL_OUTCOME, outcome.name)
        }
    }

    internal class OrganizationOnboardingProposalView(
        private val launcher: LawnchairLauncher,
        private val controller: OrganizationOnboardingProposalController,
        private val admitReview: () -> ManualOrganizationRun.StartOutcome = {
            ManualOrganizationModule.get(launcher).start(Trigger.ONBOARDING_PROPOSAL)
        },
    ) : AbstractFloatingView(launcher, null) {
        private var resolved = false
        private var reviewInFlight = false
        private var focusBeforeOpen: View? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = context.getString(R.string.organization_onboarding_proposal_title)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Themes.getAttrColor(context, android.R.attr.colorBackground))
                cornerRadius = resources.getDimensionPixelSize(R.dimen.default_dialog_corner_radius).toFloat()
            }
            elevation = dp(8).toFloat()

            addView(
                OrganizationOnboardingProposalContent(
                    context = context,
                    onLater = {
                        resolved = true
                        controller.defer()
                        close(false)
                    },
                    onSkip = {
                        resolved = true
                        controller.skip()
                        close(false)
                    },
                    onReview = ::beginReview,
                ),
            )
        }

        fun show() {
            focusBeforeOpen = launcher.currentFocus ?: launcher.workspace
            // Build the params as BaseDragLayer.LayoutParams directly: dragLayer's
            // generateLayoutParams conversion replaces plain FrameLayout.LayoutParams with
            // defaults that drop gravity, which pinned this popup to the area under the status
            // bar instead of the intended bottom sheet position. Insets stay enabled: the
            // parent adds the system-bar insets to the margins, so the bottom edge keeps
            // clearing the navigation bar.
            launcher.dragLayer.addView(
                this,
                BaseDragLayer.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    bottomMargin = dp(32)
                    marginStart = dp(16)
                    marginEnd = dp(16)
                },
            )
            // Accessibility announcement alone does not assign ordinary keyboard focus. Request the
            // proposal's declared initial target after attachment so DPAD and switch users enter
            // the popup deterministically; handleClose restores the pre-open target.
            launcher.dragLayer.post {
                if (isAttachedToWindow) getAccessibilityInitialFocusView().requestFocus()
            }
            announceAccessibilityChanges()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            mIsOpen = true
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            mIsOpen = false
        }

        /** Back-handler selection must not dismiss the proposal before Back is committed. */
        override fun canHandleBack(): Boolean = true

        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val content = getChildAt(0) as OrganizationOnboardingProposalContent
                val focused = findFocus()
                val next = when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_TAB,
                    -> when (focused) {
                        content.title -> content.laterButton
                        content.laterButton -> content.skipButton
                        content.skipButton -> content.reviewButton
                        content.reviewButton -> content.laterButton
                        else -> null
                    }

                    android.view.KeyEvent.KEYCODE_DPAD_UP -> when (focused) {
                        content.laterButton -> content.title
                        content.skipButton -> content.laterButton
                        content.reviewButton -> content.skipButton
                        else -> null
                    }

                    else -> null
                }
                if (next != null) {
                    next.requestFocus()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean = false

        override fun handleClose(animate: Boolean) {
            if (!resolved) controller.defer()
            launcher.dragLayer.removeView(this)
            val focusTarget = focusBeforeOpen?.takeIf(View::isAttachedToWindow) ?: launcher.workspace
            launcher.dragLayer.post { focusTarget.requestFocus() }
        }

        override fun isOfType(type: Int): Boolean = (type and TYPE_ON_BOARD_POPUP) != 0

        override fun getAccessibilityTarget(): Pair<View, String> = Pair.create(
            this,
            context.getString(R.string.organization_onboarding_proposal_title),
        )

        override fun getAccessibilityInitialFocusView(): View = (getChildAt(0) as OrganizationOnboardingProposalContent).title

        private fun beginReview() {
            if (reviewInFlight) return
            reviewInFlight = true
            val reviewButton = (getChildAt(0) as OrganizationOnboardingProposalContent).reviewButton
            reviewButton.isEnabled = false
            launcher.lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    controller.review(admitReview)
                }
                if (outcome is ManualOrganizationRun.StartOutcome.Started) {
                    resolved = true
                    close(false)
                    launcher.startActivity(
                        PreferenceActivity.createIntent(
                            launcher,
                            HomeScreenManualOrganization(OrganizationEntry.ONBOARDING),
                        ),
                    )
                } else {
                    reviewInFlight = false
                    reviewButton.isEnabled = true
                }
            }
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        val processState = OrganizationOnboardingProposalProcessState()

        fun classifyInstallProvenance(launcher: LawnchairLauncher): OrganizationOnboardingInstallProvenance {
            val prefs = LauncherPrefs.get(launcher)
            val packageInfo = runCatching {
                @Suppress("DEPRECATION")
                launcher.packageManager.getPackageInfo(launcher.packageName, 0)
            }.getOrNull()
            return classifyOrganizationOnboardingInstallProvenance(
                restorePending = RestoreDbTask.isPending(launcher) || prefs.get(LauncherPrefs.IS_FIRST_LOAD_AFTER_RESTORE),
                firstInstallTime = packageInfo?.firstInstallTime,
                lastUpdateTime = packageInfo?.lastUpdateTime,
                restoreSnapshot = prefs.get(OnboardingPrefs.ORGANIZATION_PROPOSAL_RESTORE_SEEN),
            )
        }
    }
}
