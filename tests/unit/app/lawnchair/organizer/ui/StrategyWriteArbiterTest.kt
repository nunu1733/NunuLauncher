package app.lawnchair.organizer.ui

import app.lawnchair.organizer.planning.StrategyId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #368 (spec 368): the strategy-write arbiter on the materials surface
 * T-05. The write, the admission gate, and the run/recovery lifetime seam are
 * injected, so these tests exercise each refusal outcome and every terminal
 * release deterministically (Unconfined dispatchers keep every transition on
 * the calling thread).
 */
class StrategyWriteArbiterTest {

    private val strategyA = StrategyId("a")
    private val strategyB = StrategyId("b")

    /**
     * Controllable fake gate: [occupy] simulates the admission domain being
     * held (a run, recovery, or another authoring token); while held,
     * `tryAcquire` fails exactly like the real single-token lease.
     */
    private class FakeGate : OrganizationOperationGate {
        var occupied = false
        val acquisitions = AtomicInteger()

        override fun tryAcquire(kind: OrganizationOperationLease.Kind): AutoCloseable? {
            if (occupied) return null
            acquisitions.incrementAndGet()
            occupied = true
            return AutoCloseable { occupied = false }
        }
    }

    private inner class Fixture(
        val writeGate: CompletableDeferred<Boolean>? = null,
        writeFailure: Throwable? = null,
    ) {
        val gate = FakeGate()
        var runOrRecoveryActive = false
        val writes = AtomicInteger()
        val committed = AtomicInteger()
        val unhandled = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.Unconfined + CoroutineExceptionHandler { _, throwable ->
                synchronized(unhandled) { unhandled.add(throwable) }
            },
        )

        /** Outcomes of every selection attempt, in order. */
        val outcomes = mutableListOf<StrategyWriteArbiter.StartOutcome>()

        fun select(id: StrategyId): StrategyWriteArbiter.StartOutcome = arbiter.onStrategySelected(id) { committed.incrementAndGet() }.also { outcomes += it }

        val arbiter: StrategyWriteArbiter by lazy {
            StrategyWriteArbiter(
                scope = scope,
                ioDispatcher = Dispatchers.Unconfined,
                mainDispatcher = Dispatchers.Unconfined,
                writeStrategy = {
                    writes.incrementAndGet()
                    writeFailure?.let { throw it }
                    writeGate?.await() ?: true
                },
                operationGate = gate,
                runOrRecoveryActive = { runOrRecoveryActive },
            )
        }
    }

    @Test
    fun aSelectionStartsAHeldTokenWriteAndReportsStarted() {
        val gate = CompletableDeferred<Boolean>()
        val fixture = Fixture(writeGate = gate)

        val outcome = fixture.select(strategyA)

        assertEquals(StrategyWriteArbiter.StartOutcome.Started, outcome)
        assertEquals(1, fixture.writes.get())
        assertEquals(StrategyWriteArbiter.State.WRITING, fixture.arbiter.state)
        assertTrue(fixture.arbiter.busy)
        assertTrue("the AUTHORING token is held during the write", fixture.gate.occupied)

        gate.complete(true)
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
        assertFalse(fixture.arbiter.busy)
        assertEquals(1, fixture.committed.get())
    }

    @Test
    fun aSecondSelectionWhileWritingIsRefusedAsWriteBusyWithoutAStoreCall() {
        val gate = CompletableDeferred<Boolean>()
        val fixture = Fixture(writeGate = gate)
        fixture.select(strategyA)

        val outcome = fixture.select(strategyB)

        assertEquals(StrategyWriteArbiter.StartOutcome.RefusedWriteBusy, outcome)
        assertEquals("no second store write may start", 1, fixture.writes.get())
        assertEquals(StrategyWriteArbiter.State.WRITING, fixture.arbiter.state)

        gate.complete(true)
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
        assertEquals(1, fixture.committed.get())
    }

    @Test
    fun anOccupiedDomainWithAnActiveRunOrRecoveryIsRefusedAsRunOrRecoveryActive() {
        val fixture = Fixture()
        fixture.gate.occupied = true
        fixture.runOrRecoveryActive = true

        val outcome = fixture.select(strategyA)

        assertEquals(StrategyWriteArbiter.StartOutcome.RefusedRunOrRecoveryActive, outcome)
        assertEquals("a run/recovery refusal is a typed non-write", 0, fixture.writes.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
        assertFalse(fixture.arbiter.busy)
    }

    @Test
    fun anOccupiedDomainWithoutRunOrRecoveryIsRefusedAsAuthoringBusy() {
        // Another AUTHORING token (category authoring, …) holds the domain:
        // the run/recovery projection is false, so the refusal classifies as
        // authoring-busy. Still a typed non-write.
        val fixture = Fixture()
        fixture.gate.occupied = true
        fixture.runOrRecoveryActive = false

        val outcome = fixture.select(strategyA)

        assertEquals(StrategyWriteArbiter.StartOutcome.RefusedAuthoringBusy, outcome)
        assertEquals(0, fixture.writes.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
    }

    @Test
    fun everyTerminalPathReleasesTheArbiterAndTheAdmissionDomain() {
        // Table-driven over the terminals: commit, non-commit, storage
        // failure, thrown failure. After each, the arbiter is Idle and the
        // next write can acquire the domain again.
        val failingGate = CompletableDeferred<Boolean>()
        failingGate.completeExceptionally(IllegalStateException("storage"))
        val scenarios = listOf(
            "commit" to CompletableDeferred(true),
            "non-commit" to CompletableDeferred(false),
            "storage failure" to failingGate,
        )
        for ((name, gate) in scenarios) {
            val fixture = Fixture(writeGate = gate)
            fixture.select(strategyA)
            // Unconfined + settled gate: the write already reached its terminal.
            assertEquals("$name: released to Idle", StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
            assertFalse("$name: domain released", fixture.gate.occupied)
            assertEquals("$name: retryable", StrategyWriteArbiter.StartOutcome.Started, fixture.select(strategyB))
        }
        // Thrown failure: the exception handler records it, and the
        // finally-equivalent still releases.
        val failing = Fixture(writeFailure = IllegalStateException("write failed"))
        failing.select(strategyA)
        assertEquals("thrown failure: released to Idle", StrategyWriteArbiter.State.IDLE, failing.arbiter.state)
        assertFalse("thrown failure: domain released", failing.gate.occupied)
        assertEquals(1, failing.unhandled.size)
        assertEquals("thrown failure: retryable", StrategyWriteArbiter.StartOutcome.Started, failing.select(strategyB))
    }

    @Test
    fun cancellingTheScopeDuringAWriteStillReleasesTheArbiterAndTheDomain() {
        val fixture = Fixture(writeGate = CompletableDeferred())
        fixture.select(strategyA)
        assertTrue(fixture.arbiter.busy)
        assertTrue(fixture.gate.occupied)

        fixture.scope.cancel()

        assertEquals("cancel must release the arbiter", StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
        assertFalse("cancel must release the admission domain", fixture.gate.occupied)
    }

    /**
     * Issue #368 review (high): a scope cancelled while the write coroutine
     * body is still QUEUED must not leak the AUTHORING token or leave the
     * arbiter at WRITING. The write starts undispatched, so the body always
     * enters its release path even when the scope dies in the same turn as
     * the selection (e.g. the T-05 host leaving composition).
     */
    @Test
    fun aScopeCancelledBeforeTheBodyRunsStillReleasesTheArbiterAndTheDomain() {
        val io = ManualQueueDispatcher()
        val gate = CompletableDeferred<Boolean>()
        val gate2 = CompletableDeferred<Boolean>()
        val occupied = java.util.concurrent.atomic.AtomicBoolean(false)
        val controlledGate = object : OrganizationOperationGate {
            override fun tryAcquire(kind: OrganizationOperationLease.Kind): AutoCloseable? {
                if (!occupied.compareAndSet(false, true)) return null
                return AutoCloseable { occupied.set(false) }
            }
        }
        val scope = CoroutineScope(io)
        val arbiter = StrategyWriteArbiter(
            scope = scope,
            ioDispatcher = io,
            mainDispatcher = Dispatchers.Unconfined,
            writeStrategy = { gate.await() },
            operationGate = controlledGate,
            runOrRecoveryActive = { false },
        )

        // The undispatched start enters the body inline and returns without
        // executing the write (it is queued behind the IO step).
        assertEquals(StrategyWriteArbiter.StartOutcome.Started, arbiter.onStrategySelected(strategyA))
        assertTrue(arbiter.busy)
        assertTrue(occupied.get())

        // The host scope dies BEFORE the write could complete; drain whatever
        // step the coroutine was parked on so the release path runs.
        scope.cancel()
        io.drainAll()

        assertEquals("pre-start cancellation must release the arbiter", StrategyWriteArbiter.State.IDLE, arbiter.state)
        assertFalse(arbiter.busy)
        assertFalse("pre-start cancellation must release the admission domain", occupied.get())

        // The freed domain admits a fresh write again.
        val retry = StrategyWriteArbiter(
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            writeStrategy = { gate2.await() },
            operationGate = controlledGate,
            runOrRecoveryActive = { false },
        )
        assertEquals(StrategyWriteArbiter.StartOutcome.Started, retry.onStrategySelected(strategyB))
    }

    /** A dispatcher that only runs its blocks when the test drains it. */
    private class ManualQueueDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

        val pendingCount: Int get() = queue.size

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drainAll() {
            while (true) {
                val block = queue.poll() ?: break
                block.run()
            }
        }
    }
}
