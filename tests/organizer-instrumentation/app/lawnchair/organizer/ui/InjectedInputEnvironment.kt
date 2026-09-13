package app.lawnchair.organizer.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #300: shared environment seam for the api36 UI lanes. Real input injection is only
 * delivered to a focused window, so every injection site routes through [InjectedInputEnvironment]
 * which observes (and repairs) the device/window focus state before injecting, collects a single
 * evidence bundle when the environment premise is broken, and keeps later gated executions from
 * re-waiting on a lost environment.
 */

/** The single evidence bundle captured by the first environment failure of an invocation. */
data class EnvironmentFailureEvidence(
    val label: String,
    val deviceState: String,
    val inputEnvironment: String,
) {
    override fun toString(): String = "evidence=$label; $deviceState; $inputEnvironment"
}

/** How a timeout should be reported: an environment premise failure or a plain test failure. */
enum class EnvironmentFailureKind {
    ENVIRONMENT_ANOMALY,
    LOCAL_REGRESSION,
    NODE_NOT_FOUND,
}

/** Observed device/window state at one instant; pure data so classification stays deterministic. */
data class DeviceEnvironmentSnapshot(
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val frontmostPackage: String?,
)

/**
 * Run-scoped health state: the first [markUnhealthy] wins, every later entry check fails fast
 * referencing that first evidence instead of re-waiting. Process-static usage lives in
 * [InjectedInputEnvironment]; tests drive fresh instances.
 */
class EnvironmentHealthState {
    private val firstEvidence = AtomicReference<EnvironmentFailureEvidence?>()

    val unhealthyEvidence: EnvironmentFailureEvidence?
        get() = firstEvidence.get()

    fun isUnhealthy(): Boolean = firstEvidence.get() != null

    fun markUnhealthy(evidence: EnvironmentFailureEvidence): EnvironmentFailureEvidence {
        // Always hand back the retained winner: under concurrent gate timeouts the loser's
        // failure must reference the first evidence, never its own (Issue #300 review P2).
        while (true) {
            val retained = firstEvidence.get()
            if (retained != null) return retained
            if (firstEvidence.compareAndSet(null, evidence)) return evidence
        }
    }

    /** Returns normally while healthy; throws referencing the captured evidence once unhealthy. */
    fun requireHealthyAtEntry() {
        firstEvidence.get()?.let { evidence ->
            throw IllegalStateException(buildAlreadyUnhealthyMessage(evidence))
        }
    }
}

/**
 * Classifies an `awaitResumedLauncher` timeout. Environment anomalies (screen off, keyguard,
 * a foreign frontmost window) poison the run; a healthy-looking environment means the launcher
 * itself failed to resume, which must stay a local failure so product regressions remain visible.
 */
fun classifyLauncherAwaitTimeout(
    snapshot: DeviceEnvironmentSnapshot,
    targetPackage: String,
): EnvironmentFailureKind =
    if (!snapshot.interactive ||
        snapshot.keyguardLocked ||
        (snapshot.frontmostPackage != null && snapshot.frontmostPackage != targetPackage)
    ) {
        EnvironmentFailureKind.ENVIRONMENT_ANOMALY
    } else {
        EnvironmentFailureKind.LOCAL_REGRESSION
    }

/**
 * Classifies an accessibility-tree timeout. When the target activity owns window focus and is the
 * frontmost window, a missing node is the product regression the test exists to detect and must
 * not be reported as an environment failure.
 */
fun classifyAccessibilityTimeout(
    snapshot: DeviceEnvironmentSnapshot,
    targetPackage: String,
    activityWindowFocused: Boolean,
): EnvironmentFailureKind =
    if (!activityWindowFocused ||
        (snapshot.frontmostPackage != null && snapshot.frontmostPackage != targetPackage)
    ) {
        EnvironmentFailureKind.ENVIRONMENT_ANOMALY
    } else {
        EnvironmentFailureKind.NODE_NOT_FOUND
    }

/** Appends the device/window state fields to a focus-traversal failure message (issue52 lane). */
fun buildTraversalFailureMessage(
    base: String,
    deviceState: String,
    hostWindowFocused: Boolean,
): String = "$base; traversalFailureContext=hostWindowFocused=$hostWindowFocused, $deviceState"

internal fun buildGateFailureMessage(evidence: EnvironmentFailureEvidence): String =
    "input environment never reached a focused window; $evidence"

internal fun buildAlreadyUnhealthyMessage(evidence: EnvironmentFailureEvidence): String =
    "input environment already marked unhealthy by an earlier gate failure; " +
        "reusing the original evidence: $evidence"

object InjectedInputEnvironment {
    /**
     * Failure-injection seam for the AC-2 wiring verification: only present when the runner
     * argument is passed explicitly (`am instrument -e nunuInjectEnvironmentHealthFailure <label>`).
     * CI lanes never set it.
     */
    const val INJECTION_ARGUMENT_KEY = "nunuInjectEnvironmentHealthFailure"

    const val GATE_FAILURE_PREFIX = "input environment never reached a focused window"
    const val RESUME_ENVIRONMENT_PREFIX = "input environment prevented the launcher from resuming"
    const val ACCESSIBILITY_ENVIRONMENT_PREFIX =
        "input environment blocked the frontmost-window accessibility scan"

    const val WINDOW_FOCUS_GATE_TIMEOUT_MILLIS = 15_000L
    private const val POLL_INTERVAL_MILLIS = 100L

    /** Process-static: one instrumentation invocation == one process == one lane job. */
    private val processState = EnvironmentHealthState()

    fun markEnvironmentFailure(evidence: EnvironmentFailureEvidence): EnvironmentFailureEvidence =
        processState.markUnhealthy(evidence)

    /**
     * Device-level repair for waits that run before any window exists (e.g. `awaitResumedLauncher`).
     * Performs no waiting of its own; entry check runs first so a poisoned run never repairs.
     */
    fun ensureInteractiveUnlocked() {
        entryCheck()
        repairInteractiveUnlockedOnce()
    }

    /**
     * Waits (with wake/keyguard repair) for [activity]'s window to hold focus. Timeout means the
     * gate's own premise collapsed: capture the evidence bundle, poison the run, fail once.
     */
    fun ensureWindowFocused(
        activity: Activity,
        timeoutMillis: Long = WINDOW_FOCUS_GATE_TIMEOUT_MILLIS,
    ) {
        entryCheck()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decorView = activity.window.decorView
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            var focused = false
            instrumentation.runOnMainSync { focused = decorView.hasWindowFocus() }
            if (focused) return
            if (SystemClock.uptimeMillis() >= deadline) break
            repairInteractiveUnlockedOnce()
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        val evidence = EnvironmentFailureEvidence(
            label = "window-focus-gate:${activity.componentName.flattenToString()}",
            deviceState = describeDeviceState(),
            inputEnvironment = "target window never gained focus within ${timeoutMillis}ms",
        )
        // Fail on the retained evidence so a concurrent earlier timeout wins the message.
        val retained = processState.markUnhealthy(evidence)
        throw IllegalStateException(buildGateFailureMessage(retained))
    }

    /** One-line summary of the device/window state for failure messages. */
    fun describeDeviceState(): String {
        val snapshot = currentSnapshot()
        return "interactive=${snapshot.interactive}, keyguardLocked=${snapshot.keyguardLocked}, " +
            "focusedWindow=${focusedWindowLine()}, frontmostPackage=${snapshot.frontmostPackage}"
    }

    fun currentSnapshot(): DeviceEnvironmentSnapshot {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val frontmost = runCatching {
            // AccessibilityNodeInfo.getPackageName() is a CharSequence on every API level.
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.packageName?.toString()
        }.getOrNull()
        return DeviceEnvironmentSnapshot(
            interactive = power.isInteractive,
            keyguardLocked = keyguard.isKeyguardLocked,
            frontmostPackage = frontmost,
        )
    }

    fun runShellCommand(command: String) {
        // Drain the output stream before closing: it makes the command synchronous so callers
        // observe its completed effect instead of racing the shell-side execution.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
        descriptor.close()
    }

    /**
     * Every environment operation starts here: the runner-argument hook (when explicitly set)
     * pins the state unhealthy, then the sticky check rejects the operation before any repair
     * or wait happens, reusing the first evidence.
     */
    private fun entryCheck() {
        val injectedLabel = InstrumentationRegistry.getArguments().getString(INJECTION_ARGUMENT_KEY)
        if (injectedLabel != null) {
            processState.markUnhealthy(
                EnvironmentFailureEvidence(
                    label = injectedLabel,
                    deviceState = "injected by runner argument $INJECTION_ARGUMENT_KEY=$injectedLabel (no live observation)",
                    inputEnvironment = "failure-injection hook: state fixed unhealthy before the first environment operation",
                ),
            )
        }
        processState.requireHealthyAtEntry()
    }

    private fun repairInteractiveUnlockedOnce() {
        val snapshot = currentSnapshot()
        if (!snapshot.interactive) runShellCommand("input keyevent ${KeyEvent.KEYCODE_WAKEUP}")
        if (snapshot.keyguardLocked) runShellCommand("wm dismiss-keyguard")
    }

    private fun focusedWindowLine(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys window")
        try {
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                for (line in stream.bufferedReader().lineSequence()) {
                    if (line.contains("mCurrentFocus") || line.contains("mFocusedWindow")) {
                        return line.trim()
                    }
                }
            }
            return "not reported"
        } catch (failure: Throwable) {
            return "unavailable ($failure)"
        } finally {
            descriptor.close()
        }
    }
}
