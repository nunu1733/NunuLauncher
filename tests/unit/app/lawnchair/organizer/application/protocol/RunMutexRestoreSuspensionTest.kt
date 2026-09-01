package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.RunId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #187 serialization contract (AC-2(d), ADR-0011 Decision 2):
 * while the restore critical section holds the module operation mutex
 * exclusively, snapshot publishers (reconciliation/apply/recover) fail fast
 * with their existing contention results and the readiness gate is untouched
 * — no republication can interleave between cleanup verification and the
 * databases wipe. The exclusive acquisition also drains finitely when an
 * in-flight module operation is held (lock-order test).
 */
class RunMutexRestoreSuspensionTest {

    private fun newModule(): LayoutApplicationModule<FakeRecoveryStore> {
        FakeClock.set(1_000L)
        return LayoutApplicationModule(
            writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem()))),
            store = FakeRecoveryStore { FakeClock.nowMillis() },
            clock = FakeClock,
            operationIds = FixedOperationIdSource(),
        )
    }

    @Test
    fun heldSuspensionBlocksReconcileWithoutTouchingTheGate() {
        val module = newModule()
        module.reconcileAtStart()
        assertEquals(ReadinessGate.State.READY, module.readinessGate.state)

        val sectionEntered = CountDownLatch(1)
        val releaseSection = CountDownLatch(1)
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        try {
            val section: Future<*> = executor.submit {
                module.runWithRecoveryOperationsSuspendedForRestore {
                    sectionEntered.countDown()
                    releaseSection.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(sectionEntered.await(5, TimeUnit.SECONDS))

            // Contending reconcile fails fast and leaves the gate READY.
            assertEquals(
                RestartReconciler.ReconciliationSummary.Failed,
                module.reconcileAtStart(),
            )
            assertEquals(ReadinessGate.State.READY, module.readinessGate.state)

            releaseSection.countDown()
            section.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun exclusiveAcquisitionDrainsAfterAnInFlightOperationReleases() {
        val module = newModule()
        val inFlightEntered = CountDownLatch(1)
        val releaseInFlight = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            // In-flight module operation using the PRODUCTION acquire/release path
            // (reconcileAtStart holds the mutex via tryAcquire until the protocol
            // finishes; here a raw tryAcquire/release stands in deterministically,
            // which is the same holder slot and the same release() notify path the
            // production code exercises).
            val inFlightRun = RunId("f".repeat(32))
            val inFlight: Future<*> = executor.submit {
                assertTrue(module.mutexForTest().tryAcquire(inFlightRun))
                inFlightEntered.countDown()
                releaseInFlight.await(5, TimeUnit.SECONDS)
                module.mutexForTest().release(inFlightRun)
            }
            assertTrue(inFlightEntered.await(5, TimeUnit.SECONDS))

            // The restore-side exclusive acquisition must wait (drain) — it must
            // not run while the in-flight holder is still active, and it must
            // complete once the production release() wakes it.
            val drained = CountDownLatch(1)
            val acquisition: Future<*> = executor.submit {
                module.runWithRecoveryOperationsSuspendedForRestore {
                    drained.countDown()
                }
            }
            assertFalse("drain must not complete while in-flight holder is active", drained.await(300, TimeUnit.MILLISECONDS))

            releaseInFlight.countDown()
            inFlight.get(5, TimeUnit.SECONDS)
            acquisition.get(5, TimeUnit.SECONDS)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun mutexReturnsToNormalAfterSuspensionReleases() {
        val mutex = RunMutex()
        val runA = RunId("a".repeat(32))
        val runB = RunId("b".repeat(32))
        mutex.withExclusive(runA) {
            assertFalse(mutex.tryAcquire(runB))
        }
        assertTrue(mutex.tryAcquire(runB))
        mutex.release(runB)
        assertSame(null, mutex.currentHolder())
    }
}
