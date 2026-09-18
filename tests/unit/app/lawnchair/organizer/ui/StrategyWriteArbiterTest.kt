package app.lawnchair.organizer.ui

import app.lawnchair.organizer.planning.StrategyId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #328 (spec 328 "strategy書込との相互排他"): the single strategy-write
 * arbiter state machine. The write and restart seams are injected, so these
 * tests block each step deterministically (Unconfined dispatchers keep every
 * transition on the calling thread).
 */
class StrategyWriteArbiterTest {

    private val strategyA = StrategyId("a")
    private val strategyB = StrategyId("b")

    private inner class Fixture(
        val writeGate: CompletableDeferred<Boolean>? = null,
        val restartGate: CompletableDeferred<Unit>? = null,
        var restartNeeded: Boolean = true,
        var restartSuppressed: Boolean = false,
        var writeStartBlocked: Boolean = false,
        var restartThrows: Boolean = false,
    ) {
        val writes = AtomicInteger()
        val restarts = AtomicInteger()
        val committed = AtomicInteger()
        val unhandled = mutableListOf<Throwable>()

        fun select(id: StrategyId) {
            arbiter.onStrategySelected(id) { committed.incrementAndGet() }
        }

        val arbiter: StrategyWriteArbiter by lazy {
            StrategyWriteArbiter(
                scope = CoroutineScope(
                    Dispatchers.Unconfined + CoroutineExceptionHandler { _, throwable ->
                        synchronized(unhandled) { unhandled.add(throwable) }
                    },
                ),
                ioDispatcher = Dispatchers.Unconfined,
                mainDispatcher = Dispatchers.Unconfined,
                writeStrategy = {
                    writes.incrementAndGet()
                    writeGate?.await() ?: true
                },
                restartRun = {
                    restarts.incrementAndGet()
                    restartGate?.let { runBlockingAwait(it) }
                    if (restartThrows) throw IllegalStateException("restart failed")
                },
                writeStartBlocked = { writeStartBlocked },
                restartSuppressed = { restartSuppressed },
                restartNeeded = { restartNeeded },
            )
        }
    }

    /** Awaits a deferred on the test thread without suspending it. */
    private fun runBlockingAwait(gate: CompletableDeferred<Unit>) {
        var waited = 0
        while (!gate.isCompleted && waited < 5_000) {
            Thread.sleep(10)
            waited += 10
        }
    }

    @Test
    fun writesAreSingleFlightWhileNonIdle() {
        val gate = CompletableDeferred<Boolean>()
        val fixture = Fixture(writeGate = gate)
        fixture.select(strategyA)
        assertEquals(StrategyWriteArbiter.State.WRITING, fixture.arbiter.state)
        assertTrue(fixture.arbiter.busy)

        // A second tap while the first write is in flight: refused outright.
        fixture.select(strategyB)
        assertEquals("no second store write may start", 1, fixture.writes.get())

        gate.complete(true)
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
        assertFalse(fixture.arbiter.busy)
        assertEquals(1, fixture.restarts.get())
        assertEquals(1, fixture.committed.get())
    }

    @Test
    fun theEntrySpecificWriteStartGateRefusesBeforeAnyStoreCall() {
        val fixture = Fixture().apply { writeStartBlocked = true }
        fixture.select(strategyA)
        assertEquals(0, fixture.writes.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
    }

    @Test
    fun committedWithoutRestartReleasesToIdle() {
        // Idle entry: the run is inactive, so the committed selection applies
        // to later runs and the arbiter releases without a restart.
        val fixture = Fixture().apply { restartNeeded = false }
        fixture.select(strategyA)
        assertEquals(1, fixture.committed.get())
        assertEquals(0, fixture.restarts.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
    }

    @Test
    fun suppressedRestartKeepsTheCommitAndReleasesToIdle() {
        // The import continuation is active: the write commits but must not
        // dismiss/restart the run the import is bound to.
        val fixture = Fixture().apply { restartSuppressed = true }
        fixture.select(strategyA)
        assertEquals(1, fixture.committed.get())
        assertEquals(0, fixture.restarts.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
    }

    @Test
    fun nonCommittedWritesReleaseToIdle() {
        val fixture = Fixture(writeGate = CompletableDeferred(false))
        fixture.select(strategyA)
        assertEquals(0, fixture.committed.get())
        assertEquals(0, fixture.restarts.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
    }

    @Test
    fun aFailingRestartStillReleasesTheArbiter() {
        val fixture = Fixture().apply { restartThrows = true }
        fixture.select(strategyA)
        assertEquals(1, fixture.restarts.get())
        assertEquals("the release must survive a restart failure", StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
        assertFalse(fixture.arbiter.busy)
    }

    @Test
    fun theArbiterStaysBusyUntilTheRestartCompletes() {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(restartGate = gate)
        val worker = Thread { fixture.select(strategyA) }
        worker.start()
        var waited = 0
        while (fixture.restarts.get() == 0 && waited < 5_000) {
            Thread.sleep(10)
            waited += 10
        }
        assertEquals(1, fixture.restarts.get())
        assertTrue("the restart window stays busy", fixture.arbiter.busy)
        gate.complete(Unit)
        worker.join(5_000)
        assertEquals(StrategyWriteArbiter.State.IDLE, fixture.arbiter.state)
    }
}
