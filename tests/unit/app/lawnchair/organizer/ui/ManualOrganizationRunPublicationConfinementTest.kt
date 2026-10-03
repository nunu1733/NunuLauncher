package app.lawnchair.organizer.ui

import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #418 (spec 418 AC-1 / AC-3a / AC-3b): the organizer run's UI state
 * publications are confined to the publication thread while the machine — and
 * its journal appends — stay on the caller's worker thread.
 *
 * Red-first evidence: with the seam+bus wired but the publish helpers not yet
 * hopping (pre-fix head), the same oracles observe the writes on the machine
 * thread and fail. The hop commit turns them green; both logs are in the PR.
 */
class ManualOrganizationRunPublicationConfinementTest {

    private class RecordingDiagnostics : DiagnosticsPort {
        val emitted = Collections.synchronizedList(mutableListOf<Pair<PhaseCode, Thread>>())

        override fun emit(event: RunEvent) {
            emitted.add(event.phase to Thread.currentThread())
        }

        override fun snapshot(): List<RunEvent> = emptyList()
    }

    private class Recorder {
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())
        fun tracker(field: String, thread: Thread) {
            writes.add(field to thread)
        }
    }

    /** Drive [block] on a plain worker thread and join, failing on timeout. */
    private fun onWorker(block: () -> Unit) {
        val worker = Thread(block, "machine-driver")
        worker.start()
        worker.join(15_000)
        assertFalse("worker did not finish", worker.isAlive)
    }

    @Test
    fun statePublicationsLandOnThePublicationThreadWhileTheMachineStaysOnItsWorker() {
        val publication = DedicatedThreadPublication("confinement-pub")
        val recorder = Recorder()
        val runner = ManualOrganizationRunTestSupport.newReadyDetectionRun(
            publicationThread = publication,
            writeThreadTracker = recorder::tracker,
        )

        onWorker {
            runner.start()
            runner.confirmSelection(setOf(ManualOrganizationRunTestSupport.readyDetectionCandidate))
            runner.planWithConfirmedScope()
        }

        assertTrue("no state writes were recorded", recorder.writes.isNotEmpty())
        recorder.writes.forEach { (field, thread) ->
            assertEquals(
                "field '$field' was published off the publication thread",
                publication.thread,
                thread,
            )
        }
        // Behavior is unchanged: the run still reaches its typed terminal.
        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
    }

    @Test
    fun journalAppendsStayOnTheMachineThreadAndOffThePublicationThread() {
        val publication = DedicatedThreadPublication("confinement-pub")
        val recorder = Recorder()
        val journal = RecordingDiagnostics()
        val runner = ManualOrganizationRunTestSupport.newReadyDetectionRun(
            publicationThread = publication,
            writeThreadTracker = recorder::tracker,
            diagnostics = journal,
        )

        onWorker {
            runner.start()
            runner.confirmSelection(setOf(ManualOrganizationRunTestSupport.readyDetectionCandidate))
            runner.planWithConfirmedScope()
        }

        assertTrue("no journal events were emitted", journal.emitted.isNotEmpty())
        val workerThread = journal.emitted.first().second
        journal.emitted.forEach { (phase, thread) ->
            assertEquals(
                "journal event $phase was appended off the machine thread",
                workerThread,
                thread,
            )
            assertFalse(
                "journal append ran on the publication thread (fsync would block main)",
                publication.isCurrentOn(thread),
            )
        }
        assertTrue("state writes were expected", recorder.writes.isNotEmpty())
        recorder.writes.forEach { (_, thread) ->
            assertEquals(publication.thread, thread)
        }
    }

    @Test
    fun machineEntryFailsFastOnThePublicationThread() {
        val publication = DedicatedThreadPublication("confinement-pub")
        val runner = ManualOrganizationRunTestSupport.newReadyDetectionRun(
            publicationThread = publication,
        )

        val failure = publication.run {
            runCatching { runner.start() }
        }.exceptionOrNull()

        assertTrue(
            "machine entry on the publication thread must fail fast",
            failure is IllegalStateException,
        )
        // The guard fires before any state was published.
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
    }

    @Test
    fun publicationJoinDoesNotUnwindBeforeCompletionEvenWhenInterrupted() {
        // Spec 375 amendment blocker (Phase2 review): a caller that may hold
        // the run lock + exchange gate must never unwind before its queued
        // publication completes — a late publication would invert the
        // gate-release linearization. Both RunPublicationThread implementations
        // share ONE uninterruptible join primitive
        // (CountDownLatch.awaitPublicationCompletion), so this oracle pins the
        // exact primitive production uses, not a test-side copy.
        val publication = DedicatedThreadPublication("join-pub")
        val publicationStarted = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val publicationCompleted = AtomicBoolean(false)
        val joinOutcome = AtomicReference<Result<Unit>?>(null)

        val worker = Thread {
            joinOutcome.set(
                runCatching {
                    publication.run {
                        publicationStarted.countDown()
                        releasePublication.await()
                        publicationCompleted.set(true)
                    }
                },
            )
        }
        worker.start()
        assertTrue(publicationStarted.await(15_000, TimeUnit.MILLISECONDS))

        // The worker is blocked in the join; the publication is held back.
        // Interrupting must neither unwind the caller nor cancel the queued
        // publication.
        worker.interrupt()
        awaitWorkerBlockedAgainInJoin(worker)

        releasePublication.countDown()
        worker.join(15_000)
        assertFalse(worker.isAlive)

        val outcome = joinOutcome.get()
        assertTrue("interrupted join must still complete the publication", outcome?.isSuccess == true)
        assertTrue("publication block did not run", publicationCompleted.get())
        assertTrue(
            "interruption must be re-asserted on the caller after completion",
            worker.isInterrupted,
        )
    }

    /**
     * Deterministic (no sleeps): wait until the worker is back in a
     * WAITING/TIMED_WAITING state after the interrupt — the uninterruptible
     * join re-enters the latch await; a broken implementation would instead
     * terminate the worker, which also exits this loop and fails the
     * subsequent assertions.
     */
    private fun awaitWorkerBlockedAgainInJoin(worker: Thread) {
        var spins = 0
        while (spins < 100_000) {
            val state = worker.state
            if (
                state == Thread.State.TERMINATED ||
                state == Thread.State.WAITING ||
                state == Thread.State.TIMED_WAITING
            ) {
                return
            }
            Thread.yield()
            spins++
        }
    }

    private fun DedicatedThreadPublication.isCurrentOn(thread: Thread): Boolean = thread === this.thread
}
