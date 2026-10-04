package app.lawnchair.organizer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #300: deterministic verification of the environment health state machine, the timeout
 * classification, and the traversal diagnostic message builder. Drives fresh instances only and
 * never touches the process-static singleton or the runner-argument injection hook, so the class
 * can share an invocation (and a process) with the gated UI lanes without affecting them.
 */
@RunWith(AndroidJUnit4::class)
class InjectedInputEnvironmentStateInstrumentationTest {

    @Test
    fun healthStateKeepsOnlyTheFirstEvidenceAndRejectsEveryLaterEntry() {
        val state = EnvironmentHealthState()
        assertFalse(state.isUnhealthy())
        state.requireHealthyAtEntry()

        val first = EnvironmentFailureEvidence(
            label = "window-focus-gate:first",
            deviceState = "interactive=false",
            inputEnvironment = "boot lost focus",
        )
        val second = EnvironmentFailureEvidence(
            label = "window-focus-gate:second",
            deviceState = "interactive=true",
            inputEnvironment = "later anomaly",
        )
        // Issue #300 review P2: markUnhealthy always returns the retained winner, so a caller
        // (even one racing an earlier timeout) fails on the first evidence, never its own.
        assertEquals(first, state.markUnhealthy(first))
        assertEquals(first, state.markUnhealthy(second))

        assertTrue(state.isUnhealthy())
        assertEquals(first, state.unhealthyEvidence)
        val rejection = assertThrows(IllegalStateException::class.java) {
            state.requireHealthyAtEntry()
        }
        assertTrue(rejection.message!!.contains("already marked unhealthy"))
        assertTrue(rejection.message!!.contains("first"))
        assertFalse(rejection.message!!.contains("second"))
    }

    @Test
    fun launcherAwaitTimeoutClassifiesEnvironmentAnomaliesAndKeepsLocalRegressions() {
        val targetPackage = "app.lawnchair.debug"

        val screenOff = DeviceEnvironmentSnapshot(
            interactive = false,
            keyguardLocked = false,
            frontmostPackage = targetPackage,
        )
        assertEquals(
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY,
            classifyLauncherAwaitTimeout(screenOff, targetPackage),
        )

        val keyguardLocked = DeviceEnvironmentSnapshot(
            interactive = true,
            keyguardLocked = true,
            frontmostPackage = targetPackage,
        )
        assertEquals(
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY,
            classifyLauncherAwaitTimeout(keyguardLocked, targetPackage),
        )

        val foreignFrontmost = DeviceEnvironmentSnapshot(
            interactive = true,
            keyguardLocked = false,
            frontmostPackage = "com.android.systemui",
        )
        assertEquals(
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY,
            classifyLauncherAwaitTimeout(foreignFrontmost, targetPackage),
        )

        val healthy = DeviceEnvironmentSnapshot(
            interactive = true,
            keyguardLocked = false,
            frontmostPackage = targetPackage,
        )
        assertEquals(
            EnvironmentFailureKind.LOCAL_REGRESSION,
            classifyLauncherAwaitTimeout(healthy, targetPackage),
        )
        assertEquals(
            EnvironmentFailureKind.LOCAL_REGRESSION,
            classifyLauncherAwaitTimeout(healthy.copy(frontmostPackage = null), targetPackage),
        )
    }

    @Test
    fun accessibilityTimeoutClassifiesForeignFrontmostAndKeepsNodeNotFoundLocal() {
        val targetPackage = "app.lawnchair.debug"
        val healthy = DeviceEnvironmentSnapshot(
            interactive = true,
            keyguardLocked = false,
            frontmostPackage = targetPackage,
        )

        assertEquals(
            EnvironmentFailureKind.NODE_NOT_FOUND,
            classifyAccessibilityTimeout(healthy, targetPackage, activityWindowFocused = true),
        )

        assertEquals(
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY,
            classifyAccessibilityTimeout(
                healthy.copy(frontmostPackage = "com.android.systemui"),
                targetPackage,
                activityWindowFocused = true,
            ),
        )

        assertEquals(
            EnvironmentFailureKind.ENVIRONMENT_ANOMALY,
            classifyAccessibilityTimeout(healthy, targetPackage, activityWindowFocused = false),
        )
    }

    @Test
    fun traversalFailureMessageCarriesDeviceAndWindowStateFields() {
        val message = buildTraversalFailureMessage(
            base = "Focused = 'false'",
            deviceState = "interactive=true, keyguardLocked=false, " +
                "focusedWindow=Window{abc u0 app.lawnchair.debug}, frontmostPackage=app.lawnchair.debug",
            hostWindowFocused = true,
        )

        assertTrue(message.contains("Focused = 'false'"))
        assertTrue(message.contains("hostWindowFocused=true"))
        assertTrue(message.contains("interactive=true"))
        assertTrue(message.contains("keyguardLocked=false"))
        assertTrue(message.contains("focusedWindow="))
        assertTrue(message.contains("frontmostPackage="))
    }

    @Test
    fun gateFailureMessagesStayDistinguishableFromInjectionLosses() {
        val evidence = EnvironmentFailureEvidence(
            label = "window-focus-gate:probe",
            deviceState = "interactive=false",
            inputEnvironment = "target window never gained focus",
        )

        val gateMessage = buildGateFailureMessage(evidence)
        assertTrue(gateMessage.startsWith(InjectedInputEnvironment.GATE_FAILURE_PREFIX))
        assertTrue(gateMessage.contains("window-focus-gate:probe"))

        val alreadyMessage = buildAlreadyUnhealthyMessage(evidence)
        assertTrue(alreadyMessage.contains("already marked unhealthy"))
        assertTrue(alreadyMessage.contains("window-focus-gate:probe"))
    }
}
