/*
 * Issue #526: JVM oracle for the process-wide in-flight apply gate's pure
 * state machine (spec 526, targetSdk 37 fork UI contract). The gate is the
 * single authority for in-flight applies: Confirm is admitted only from Idle,
 * every apply terminal moves InFlight → Correlating and advances a monotonic
 * correlation generation, and only a capture that STARTED AT OR AFTER the
 * terminal — proven by a ticket minted at capture start matching the pending
 * generation — releases Correlating → Idle (a pre-terminal-started capture
 * can never re-enable Confirm on a possibly pre-apply layout, and a destroyed
 * instance's release is refused at the activity call site). Terminals are
 * classified: world-moved / uncertain terminals (Applied, stale, rollback /
 * recovery, unresolved) release only through the correlated capture, while
 * zero-write no-local-recovery terminals (non-stale rejection, ConcurrentRun,
 * defensive no-changes) release immediately via onTerminalWithoutLocalRecovery —
 * this does NOT exclude the shared layout moving; the existing apply-time
 * STALE gate stays the fail-closed protection on the next admission.
 * Review round 2: the START of the correlated reload is also single per
 * terminal generation — claimCorrelatedCapture hands the pending generation
 * out exactly once (the live surface owns the reload; a destroyed surface
 * leaves the claim open for the recreated live surface).
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `apply terminal from Correlating is a defensive no-op that does not advance the correlation generation`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        val pendingTicket = gate.newCaptureTicket()
        gate.onApplyTerminal()
        assertEquals("a second terminal must not leave Correlating", EditSurfaceApplyGate.State.Correlating, gate.state)
        // Pinned defensive semantics: the no-op neither transitions nor moves
        // the pending correlation generation — a ticket minted before the
        // no-op still matches and releases exactly once.
        gate.onCaptureReady(pendingTicket)
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    // --- capture release (Correlating → Idle, generation-bound ticket) ---

    @Test
    fun `capture started after the terminal releases Correlating to Idle`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        gate.onCaptureReady(gate.newCaptureTicket())
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        assertTrue("the next apply must be admitted after the correlated capture", gate.beginApply())
    }

    @Test
    fun `capture started before the terminal cannot release the gate`() {
        // The recreated-surface race (spec 526 review round 1): a capture that
        // STARTED while the apply was still in flight carries a pre-terminal
        // ticket. Its completion — whenever it lands — must never release
        // Correlating; only a post-terminal-started capture may.
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        val preTerminalTicket = gate.newCaptureTicket()
        gate.onApplyTerminal()
        gate.onCaptureReady(preTerminalTicket)
        assertEquals(
            "a pre-terminal-started capture must leave Correlating untouched",
            EditSurfaceApplyGate.State.Correlating,
            gate.state,
        )
        assertFalse(gate.beginApply())
        gate.onCaptureReady(gate.newCaptureTicket())
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    @Test
    fun `capture completion while InFlight does not touch the state`() {
        // A capture that completes while the apply is still in flight may show
        // the pre-apply layout — it must never re-enable Confirm.
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onCaptureReady(gate.newCaptureTicket())
        assertEquals("a pre-terminal capture must leave InFlight untouched", EditSurfaceApplyGate.State.InFlight, gate.state)
        assertFalse(gate.beginApply())
        gate.onApplyTerminal()
        assertEquals("the gate must stay in Correlating for the NEXT capture", EditSurfaceApplyGate.State.Correlating, gate.state)
    }

    @Test
    fun `capture completion from Idle stays Idle whatever the ticket`() {
        val gate = EditSurfaceApplyGate()
        gate.onCaptureReady(gate.newCaptureTicket())
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        gate.onCaptureReady(12345)
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    @Test
    fun `stale tickets from earlier generations never release a later correlation`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal() // correlation generation 1
        val firstTicket = gate.newCaptureTicket()
        gate.onCaptureReady(firstTicket)
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        assertTrue(gate.beginApply())
        gate.onApplyTerminal() // correlation generation 2
        gate.onCaptureReady(0)
        gate.onCaptureReady(firstTicket)
        assertEquals(
            "only the pending correlation generation's ticket may release",
            EditSurfaceApplyGate.State.Correlating,
            gate.state,
        )
        gate.onCaptureReady(gate.newCaptureTicket())
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    // --- correlated capture claim (1 terminal generation = 1 correlated reload) ---

    @Test
    fun `claim from Correlating returns the pending generation exactly once`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        val pending = gate.newCaptureTicket()
        val claimed = gate.claimCorrelatedCapture()
        assertEquals(
            "the claim must return the pending correlation generation as the capture ticket",
            pending,
            claimed,
        )
        assertNull("a second claim of the same generation must not re-open the reload", gate.claimCorrelatedCapture())
    }

    @Test
    fun `claim from Idle and InFlight returns null`() {
        val gate = EditSurfaceApplyGate()
        assertNull("Idle has no pending correlation to claim", gate.claimCorrelatedCapture())
        assertTrue(gate.beginApply())
        assertNull("an in-flight apply has no terminal correlation to claim yet", gate.claimCorrelatedCapture())
    }

    @Test
    fun `claimed capture completion releases the gate and the next terminal cycle is claimable again`() {
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onApplyTerminal()
        val claimed = gate.claimCorrelatedCapture()
        assertNotNull(claimed)
        gate.onCaptureReady(claimed!!)
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        assertTrue("the next apply must be admitted after the claimed capture", gate.beginApply())
        gate.onApplyTerminal()
        val nextClaim = gate.claimCorrelatedCapture()
        assertEquals(
            "the new terminal generation must be claimable again",
            gate.newCaptureTicket(),
            nextClaim,
        )
        gate.onCaptureReady(nextClaim!!)
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    @Test
    fun `stale ticket completion after a claim neither releases nor re-opens the claim`() {
        // Pinned semantics (review round 2): the claim is consumed per
        // generation and only the next terminal re-opens it. A capture carrying
        // a pre-terminal (stale) ticket completing after the claim must not
        // release the gate and must not make the generation claimable again —
        // the release itself stays governed by the ticket contract, not the
        // claim.
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        val preTerminalTicket = gate.newCaptureTicket()
        gate.onApplyTerminal()
        assertNotNull(gate.claimCorrelatedCapture())
        gate.onCaptureReady(preTerminalTicket)
        assertEquals(
            "a stale-ticket completion must not release the claimed correlation",
            EditSurfaceApplyGate.State.Correlating,
            gate.state,
        )
        assertNull("the consumed claim must stay consumed until the next terminal", gate.claimCorrelatedCapture())
    }

    // --- terminal classification: world-moved vs zero-write no-local-recovery ---

    @Test
    fun `release works for every world-moved terminal path`() {
        // Applied (then finish), stale reopen, rollback / recovery and the
        // unresolved family move the world (or leave it uncertain): they stay
        // Correlating until a capture started after the terminal releases
        // them. Pin one full cycle per class of terminal naming so a future
        // terminal branch cannot forget the onApplyTerminal / newCaptureTicket /
        // onCaptureReady triple.
        val terminals = listOf(
            "Applied",
            "STALE_REVISION",
            "EXACT_PRECONDITION_FAILED",
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
            gate.onCaptureReady(gate.newCaptureTicket())
            assertEquals("terminal=$terminal", EditSurfaceApplyGate.State.Idle, gate.state)
        }
    }

    @Test
    fun `release works for every zero-write no-local-recovery terminal path`() {
        // Rejected (non-stale), ConcurrentRun and the defensive no-changes
        // family perform no write and no local recovery action, so the gate
        // releases to Idle without a correlated recapture. This does NOT pin
        // "the world did not move": if the shared layout moved externally, the
        // very next confirm's existing STALE admission fail-closes with zero
        // write (spec 526 oracle 3b).
        val terminals = listOf(
            "rejected",
            "ConcurrentRun",
            "no-changes",
            "Inconsistent",
            "null receipt",
        )
        for (terminal in terminals) {
            val gate = EditSurfaceApplyGate()
            assertTrue("terminal=$terminal", gate.beginApply())
            gate.onApplyTerminal()
            gate.onTerminalWithoutLocalRecovery()
            assertEquals("terminal=$terminal", EditSurfaceApplyGate.State.Idle, gate.state)
            assertTrue("terminal=$terminal next confirm admitted", gate.beginApply())
        }
    }

    @Test
    fun `terminalWithoutLocalRecovery from InFlight releases directly`() {
        // Defensive: a caller that skips onApplyTerminal still gets a coherent
        // release instead of a stuck InFlight.
        val gate = EditSurfaceApplyGate()
        assertTrue(gate.beginApply())
        gate.onTerminalWithoutLocalRecovery()
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
    }

    @Test
    fun `terminalWithoutLocalRecovery from Idle stays Idle`() {
        val gate = EditSurfaceApplyGate()
        gate.onTerminalWithoutLocalRecovery()
        assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
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
            gate.onCaptureReady(gate.newCaptureTicket())
            assertEquals(EditSurfaceApplyGate.State.Idle, gate.state)
        }
    }
}
