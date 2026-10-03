package app.lawnchair.organizer.ui

import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.Trigger
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
 * Issue #418 (spec 418 AC-3c/AC-3d, spec 375 SR-AC-08 amendment): the
 * admission anchor's gate-held publication keeps its linearization point —
 * `State.Capturing` becomes visible inside the gate hold — while the state
 * writes themselves land on the publication thread through the lock-free hop.
 * A refused anchor publishes nothing, and a cancel racing the gate hold keeps
 * the journal empty (the pre-RUN_STARTED invariant).
 */
class ManualOrganizationRunAdmissionPublicationTest {

    private class RecordingDiagnostics : DiagnosticsPort {
        val emitted = Collections.synchronizedList(mutableListOf<PhaseCode>())

        override fun emit(event: RunEvent) {
            emitted.add(event.phase)
        }

        override fun snapshot(): List<RunEvent> = emptyList()
    }

    private class Recorder {
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())
        val wroteDuringGateHold = AtomicBoolean(false)
        var gateStillHeld: AtomicBoolean? = null

        fun tracker(field: String, thread: Thread) {
            writes.add(field to thread)
            if (gateStillHeld?.get() == true) {
                wroteDuringGateHold.set(true)
            }
        }
    }

    private fun onWorker(block: () -> Unit) {
        val worker = Thread(block, "admission-driver")
        worker.start()
        worker.join(15_000)
        assertFalse("worker did not finish", worker.isAlive)
    }

    @Test
    fun admittedAnchorPublishesCapturingOnThePublicationThreadInsideTheGateHold() {
        val publication = DedicatedThreadPublication("admission-pub")
        val recorder = Recorder()
        val runner = ManualOrganizationRunTestSupport.newReadyDetectionRun(
            publicationThread = publication,
            writeThreadTracker = recorder::tracker,
        )

        val gateHeld = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val gateStillHeld = AtomicBoolean(false)
        recorder.gateStillHeld = gateStillHeld
        val stateInsideHold = AtomicReference<ManualOrganizationRun.State?>(null)

        val worker = Thread {
            val outcome = runner.start(
                trigger = Trigger.MANUAL_FULL,
                intent = null,
                admissionAnchor = ManualOrganizationRun.StartAdmissionAnchor { complete ->
                    gateStillHeld.set(true)
                    gateHeld.countDown()
                    assertTrue(releaseGate.await(15_000, TimeUnit.SECONDS))
                    complete()
                    // Still inside the simulated gate hold: the admission's
                    // first publication must already be visible.
                    stateInsideHold.set(runner.state)
                    gateStillHeld.set(false)
                    true
                },
            )
            assertTrue(outcome is ManualOrganizationRun.StartOutcome.Started)
        }
        worker.start()
        assertTrue("anchor never reached the gate hold", gateHeld.await(15_000, TimeUnit.SECONDS))
        releaseGate.countDown()
        worker.join(15_000)
        assertFalse(worker.isAlive)

        // The admission writes landed on the publication thread.
        assertTrue("no publication was recorded", recorder.writes.isNotEmpty())
        recorder.writes.forEach { (field, thread) ->
            assertEquals("field '$field' published off the publication thread", publication.thread, thread)
        }
        // Spec 375 amendment exception invariant: the hop completed while the
        // gate was held, without the publication thread taking any lock.
        assertTrue("publication hop did not complete during the gate hold", recorder.wroteDuringGateHold.get())
        // Linearization: Capturing was visible before the gate released.
        assertEquals(ManualOrganizationRun.State.Capturing, stateInsideHold.get())
    }

    @Test
    fun refusedAnchorPublishesNothingAndKeepsTheJournalEmpty() {
        val publication = DedicatedThreadPublication("admission-pub")
        val recorder = Recorder()
        val journal = RecordingDiagnostics()
        val runner = ManualOrganizationRunTestSupport.newReadyDetectionRun(
            publicationThread = publication,
            writeThreadTracker = recorder::tracker,
            diagnostics = journal,
        )

        onWorker {
            val outcome = runner.start(
                trigger = Trigger.MANUAL_FULL,
                intent = null,
                admissionAnchor = { false },
            )
            assertTrue(outcome is ManualOrganizationRun.StartOutcome.AdmissionRefused)
        }

        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
        assertTrue("a refused admission must not publish UI state", recorder.writes.isEmpty())
        assertTrue("a refused admission must not open the journal", journal.emitted.isEmpty())
    }

    @Test
    fun cancelRacingTheGateHoldKeepsTheJournalEmptyAndSettlesCancelled() {
        val publication = DedicatedThreadPublication("admission-pub")
        val journal = RecordingDiagnostics()
        val runner = ManualOrganizationRunTestSupport.newRun(
            publicationThread = publication,
            diagnostics = journal,
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )

        val gateHeld = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val started = Thread {
            runner.start(
                trigger = Trigger.MANUAL_FULL,
                intent = null,
                admissionAnchor = ManualOrganizationRun.StartAdmissionAnchor { complete ->
                    gateHeld.countDown()
                    assertTrue(releaseGate.await(15_000, TimeUnit.SECONDS))
                    complete()
                    true
                },
            )
        }
        started.start()
        assertTrue(gateHeld.await(15_000, TimeUnit.SECONDS))

        // The cancel thread blocks on the run lock the admission worker holds;
        // the lock ordering (not timing) decides that it runs after admission.
        val cancelled = Thread { runner.cancel() }
        cancelled.start()
        releaseGate.countDown()
        started.join(15_000)
        cancelled.join(15_000)
        assertFalse(started.isAlive)
        assertFalse(cancelled.isAlive)

        // The pause sits before the journal opens, so the cancel winner keeps
        // the journal empty (spec 369 pre-RUN_STARTED invariant).
        assertTrue(journal.emitted.isEmpty())
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
    }

    @Test
    fun grantedFlowKeepsRunStartedBeforeItsTerminalJournalEvent() {
        val publication = DedicatedThreadPublication("admission-pub")
        val journal = RecordingDiagnostics()
        val runner = ManualOrganizationRunTestSupport.newReadyDetectionRun(
            publicationThread = publication,
            diagnostics = journal,
        )

        onWorker {
            runner.start()
            runner.confirmSelection(setOf(ManualOrganizationRunTestSupport.readyDetectionCandidate))
            runner.planWithConfirmedScope()
        }

        val phases = journal.emitted
        assertTrue(phases.contains(PhaseCode.RUN_STARTED))
        val startedAt = phases.indexOf(PhaseCode.RUN_STARTED)
        val terminal = phases.indexOfFirst { it == PhaseCode.INPUT_NOT_READY || it == PhaseCode.USER_CANCELLED }
        assertTrue("terminal journal event missing", terminal >= 0)
        assertTrue(
            "RUN_STARTED must precede the terminal journal event (RD-7 ordering)",
            startedAt < terminal,
        )
        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
    }
}
