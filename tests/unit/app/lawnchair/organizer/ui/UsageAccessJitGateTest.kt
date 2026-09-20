package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.RunId
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #371 (spec 371): contract tests for the process-scoped JIT Usage
 * Access request gate — the `Available -> Reserved -> Presented -> Resolved`
 * opportunity state machine, its owner-destruction rules, the deterministic
 * observation seam, and the bounded grant re-read.
 */
class UsageAccessJitGateTest {

    private val ownerId = RunId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val waiterId = RunId("cccccccccccccccccccccccccccccccc")

    @Test
    fun `ungranted first evaluation acquires the reservation`() {
        val gate = UsageAccessJitGate(isGranted = { false })

        assertEquals(UsageAccessJitGate.Decision.Present, gate.evaluate(ownerId))
        assertEquals(UsageAccessJitGate.Phase.Reserved, gate.ownedPhase(ownerId))
    }

    @Test
    fun `granted first evaluation consumes and resolves the opportunity without presenting`() {
        val gate = UsageAccessJitGate(isGranted = { true })

        assertEquals(UsageAccessJitGate.Decision.Proceed, gate.evaluate(ownerId))
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.ownedPhase(ownerId))
        // The moment is past: a later trigger (even after a revoke simulation —
        // the predicate flipping false) never presents again.
        assertEquals(UsageAccessJitGate.Decision.Proceed, gate.evaluate(waiterId))
        assertNull(gate.ownedPhase(waiterId))
    }

    @Test
    fun `presentation consumes the re-presentation right but keeps waiters blocked`() {
        val gate = UsageAccessJitGate(isGranted = { false })
        gate.evaluate(ownerId)

        assertEquals(UsageAccessJitGate.Decision.Wait, gate.evaluate(waiterId))

        gate.markPresented(ownerId)
        assertEquals(UsageAccessJitGate.Phase.Presented, gate.ownedPhase(ownerId))
        // Presented, not resolved: the waiter still may not compose.
        assertEquals(UsageAccessJitGate.Decision.Wait, gate.evaluate(waiterId))
        // Re-presentation is impossible this process.
        assertEquals(UsageAccessJitGate.Decision.Wait, gate.evaluate(ownerId))
    }

    @Test
    fun `resolution unblocks the waiter exactly once`() {
        val gate = UsageAccessJitGate(isGranted = { false })
        gate.evaluate(ownerId)
        gate.markPresented(ownerId)
        gate.evaluate(waiterId)

        gate.resolve(ownerId)

        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
        assertEquals(UsageAccessJitGate.Decision.Proceed, gate.evaluate(waiterId))
        // Already consumed: the second requester proceeds without presenting.
        assertEquals(UsageAccessJitGate.Decision.Proceed, gate.evaluate(RunId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))
    }

    @Test
    fun `release before presentation keeps the opportunity unconsumed for the next trigger`() {
        val gate = UsageAccessJitGate(isGranted = { false })
        gate.evaluate(ownerId)
        gate.release(ownerId)

        assertEquals(UsageAccessJitGate.Phase.Available, gate.snapshot.value.phase)
        assertNull(gate.ownedPhase(ownerId))
        // The next trigger acquires again — exactly one winner.
        assertEquals(UsageAccessJitGate.Decision.Present, gate.evaluate(waiterId))
        assertEquals(UsageAccessJitGate.Decision.Wait, gate.evaluate(ownerId))
    }

    @Test
    fun `release after presentation is refused`() {
        val gate = UsageAccessJitGate(isGranted = { false })
        gate.evaluate(ownerId)
        gate.markPresented(ownerId)

        gate.release(ownerId)

        assertEquals(UsageAccessJitGate.Phase.Presented, gate.snapshot.value.phase)
    }

    @Test
    fun `owner destruction follows the state specific rules`() {
        val gate = UsageAccessJitGate(isGranted = { false })

        // Reserved destruction releases.
        gate.evaluate(ownerId)
        gate.release(ownerId)
        assertEquals(UsageAccessJitGate.Phase.Available, gate.snapshot.value.phase)

        // Presented destruction resolves (abandon resolution).
        gate.evaluate(ownerId)
        gate.markPresented(ownerId)
        gate.resolve(ownerId)
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
        // Resolved destruction is a no-op.
        gate.resolve(ownerId)
        gate.release(ownerId)
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
    }

    @Test
    fun `stale owner operations never act on a newer attempt`() {
        val gate = UsageAccessJitGate(isGranted = { false })
        // Old attempt: reserved, then released (destroyed before presenting).
        gate.evaluate(ownerId)
        gate.release(ownerId)
        // New attempt: the waiter acquires.
        gate.evaluate(waiterId)

        // The stale owner's late operations must not touch the new attempt.
        gate.release(ownerId)
        gate.markPresented(ownerId)
        gate.resolve(ownerId)
        assertEquals(UsageAccessJitGate.Phase.Reserved, gate.ownedPhase(waiterId))
        assertEquals(UsageAccessJitGate.Phase.Reserved, gate.snapshot.value.phase)
    }

    @Test
    fun `snapshot revision advances on every transition for deterministic observation`() = runBlocking {
        val gate = UsageAccessJitGate(isGranted = { false })
        val before = gate.snapshot.value.revision

        gate.evaluate(ownerId)
        gate.markPresented(ownerId)
        gate.resolve(ownerId)

        val resolved = gate.snapshot.filter { it.phase == UsageAccessJitGate.Phase.Resolved }.first()
        assertTrue(resolved.revision >= before + 3)
    }

    @Test
    fun `concurrent evaluation produces exactly one presenter`() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val barrier = CyclicBarrier(2)
        val results = mutableListOf<UsageAccessJitGate.Decision>()

        val workers = (0 until 2).map {
            thread {
                barrier.await(5, TimeUnit.SECONDS)
                val decision = gate.evaluate(ownerId)
                synchronized(results) { results += decision }
            }
        }
        workers.forEach { it.join(5_000) }

        assertEquals(listOf(UsageAccessJitGate.Decision.Present, UsageAccessJitGate.Decision.Wait).sortedBy { it.toString() }, results.sortedBy { it.toString() })
        assertEquals(1, results.count { it == UsageAccessJitGate.Decision.Present })
    }

    @Test
    fun `production grant wait limit stays inside the accepted spec range`() {
        // Spec 371 JIT-AC-03: 0.5s..2s. The constant is the single source of
        // truth for both the production wait and the test boundaries.
        assertTrue(USAGE_ACCESS_JIT_GRANT_WAIT_LIMIT_MS >= 500L)
        assertTrue(USAGE_ACCESS_JIT_GRANT_WAIT_LIMIT_MS <= 2_000L)
        assertTrue(USAGE_ACCESS_JIT_GRANT_POLL_INTERVAL_MS in 1 until USAGE_ACCESS_JIT_GRANT_WAIT_LIMIT_MS)
    }

    @Test
    fun `bounded re read returns as soon as the predicate observes the grant`() = runBlocking {
        val reads = mutableListOf(false, false, true)
        val sleeps = mutableListOf<Long>()

        val granted = awaitUsageAccessGrant(
            isGranted = { reads.removeFirstOrNull() ?: true },
            limitMs = 1_000L,
            pollIntervalMs = 250L,
            sleep = { slept ->
                sleeps += slept
                // A fake clock: the predicate flips after the second sleep.
            },
        )

        assertTrue(granted)
        assertEquals(listOf(250L, 250L), sleeps)
        assertFalse(sleeps.sum() >= 1_000L)
        Unit
    }

    @Test
    fun `bounded re read keeps waiting just below the limit and falls back at the limit`() = runBlocking {
        val sleeps = mutableListOf<Long>()

        val granted = awaitUsageAccessGrant(
            isGranted = { false },
            limitMs = 1_000L,
            pollIntervalMs = 250L,
            sleep = { sleeps += it },
        )

        assertFalse(granted)
        // `limit - ε` kept waiting; reaching the limit fell back without a
        // final pointless poll past it.
        assertEquals(1_000L, sleeps.sum())
    }

    @Test
    fun `bounded re read with a granted predicate never sleeps`() = runBlocking {
        var polled = 0

        val granted = awaitUsageAccessGrant(
            isGranted = {
                polled++
                true
            },
            limitMs = 1_000L,
            pollIntervalMs = 250L,
            sleep = { error("must not sleep when the grant is already observable") },
        )

        assertTrue(granted)
        assertEquals(1, polled)
        Unit
    }
}
