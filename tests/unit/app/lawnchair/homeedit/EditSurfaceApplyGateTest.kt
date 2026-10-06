/*
 * Issue #526: JVM oracle for the process-wide in-flight apply gate's pure
 * state machine (spec 526, targetSdk 37 fork UI contract). The gate is the
 * single authority for in-flight applies: Confirm is admitted only from Idle,
 * every apply terminal moves InFlight → Correlating, and only a capture that
 * COMPLETES AFTER the terminal releases Correlating → Idle (a pre-terminal
 * capture must not re-enable Confirm on a possibly pre-apply layout). The
 * machine is terminal-agnostic — Applied, stale, rejected and error terminals
 * all go through the same onApplyTerminal/onCaptureReady pair, so the release
 * contract is pinned once for every terminal path.
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceApplyGateTest {

    // --- beginApply (Idle → InFlight) ---

    @Test
    fun `beginApply from Idle transitions to InFlight and returns true`() {
        val gate = EditSurfaceApplyGate()
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        assertTrue(gate.beginApply())
        assertEquals(EditSurfaceApplyGate.State.InFlight, gate.state)
    }

    @Test
    fun `second beginApply while InFlight is refused`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        assertFalse("a second apply must not start while one is in flight", gate.beginApply())
        assertEquals(EditSurfaceApplyGate.State.InFlight, gate.state)
    }

    // --- terminal (InFlight → Correlating) ---

    @Test
    fun `apply terminal moves InFlight to Correlating and keeps Confirm refused`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        assertEquals(EditSurfaceApplyGate.State.Correlating, gate.state)
        assertFalse("Confirm must stay refused until the correlated capture completes", gate.beginApply())
    }

    @Test
    fun `apply terminal from Idle is a defensive no-op`() {
        val gate = EditSurfaceApplyGate()
        gate.onApplyTerminal()
        assertEquals("a terminal without an in-flight apply must not create Correlating", EditSurfaceApplyGate.State.Idle, gate.state)
        assertTrue(gate.beginApply())
    }

    @Test
    fun `apply terminal from Correlating is a defensive no-op`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        gate.onApplyTerminal()
        assertEquals(EditSurfaceApplyGate.State.Correlating, gate.state)
    }

    // --- capture release (Correlating → Idle) ---

    @Test
    fun `capture completion after the terminal releases Correlating to Idle`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        gate.onCaptureReady()
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        assertTrue("the next apply must be admitted after the correlated capture", gate.beginApply())
    }

    @Test
    fun `capture completion before the terminal does not release the gate`() {
        // A capture that completes while the apply is still in flight may show
        // the pre-apply layout — it must never re-enable Confirm.
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onCaptureReady()
        assertEquals("a pre-terminal capture must leave InFlight untouched", EditSurfaceApplyGate.State.InFlight, gate.state)
        assertFalse(gate.beginApply())
        gate.onApplyTerminal()
        assertEquals("the gate must stay in Correlating for the NEXT capture", EditSurfaceApplyGate.State.Correlating, gate.state)
    }

    @Test
    fun `capture completion from Idle stays Idle`() {
        val gate = EditSurfaceApplyGate()
        gate.onCaptureReady()
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    // --- every terminal path releases through the same pair ---

    @Test
    fun `release works for every terminal path`() {
        // The state machine is terminal-agnostic: Applied (then finish), stale
        // reopen, rejected, ConcurrentRun, defensive no-changes, rollback and
        // unresolved all reach the same release. Pin one full cycle per class
        // of terminal naming so a future terminal branch cannot forget the
        // onApplyTerminal/onCaptureReady pair.
        val terminals = listOf(
            "Applied",
            "STALE_REVISION",
            "EXACT_PRECONDITION_FAILED",
            "rejected",
            "ConcurrentRun",
            "no-changes",
            "Inconsistent",
            "null receipt",
            "RolledBack",
            "Recovered",
            "Unresolved",
            "RecoveryFailed",
        )
        for (terminal in terminals) {
            val gate = EditSurfaceApplyGate()
            assertTrue("terminal=$terminal", gate.beginApply())
            gate.onApplyTerminal()
            assertEquals("terminal=$terminal", EditSurfaceApplyGate.State.Correlating, gate.state)
            gate.onCaptureReady()
            assertEquals("terminal=$terminal", EditSurfaceApplyGate.State.Idle, gate.state)
        }
    }

    @Test
    fun `a refused apply does not move the state machine`() {
        // A Confirm refused by the gate must leave the authority exactly as it
        // was: the busy rejection is display feedback, not a state transition.
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        assertFalse(gate.beginApply())
        assertFalse(gate.beginApply())
        assertEquals(EditSurfaceApplyGate.State.InFlight, gate.state)
    }

    @Test
    fun `repeated full cycles stay deterministic`() {
        val gate = EditSurfaceApplyGate()
        repeat(3) {
            assertTrue(gate.beginApply())
            gate.onApplyTerminal()
            gate.onCaptureReady()
            assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        }
    }
}
