package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.ApplyProtocol
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.FixedOperationIdSource
import app.lawnchair.organizer.application.protocol.RecordingFaultInjector
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AC-67-04: ApplyProtocol diagnostics attachment tests.
 *
 * Injects a recording DiagnosticsPort and verifies that events are emitted
 * at the correct lifecycle points with the correct stage/runId/phase.
 */
class ApplyProtocolDiagnosticsTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var faults: RecordingFaultInjector
    private lateinit var recordedEvents: MutableList<RunEvent>

    private fun createProtocol(mutex: RunMutex = RunMutex()): ApplyProtocol {
        recordedEvents = mutableListOf()
        val port = object : DiagnosticsPort {
            override fun emit(event: RunEvent) {
                recordedEvents.add(event)
            }
            override fun snapshot(): List<RunEvent> = emptyList()
        }
        val ids = FixedOperationIdSource()
        return ApplyProtocol(writer, store, FakeClock, ids, faults, mutex, port)
    }

    private fun createProtocolWithFaults(
        mutex: RunMutex,
        customFaults: FaultInjector,
        ids: FixedOperationIdSource = FixedOperationIdSource(),
    ): ApplyProtocol {
        recordedEvents = mutableListOf()
        val port = object : DiagnosticsPort {
            override fun emit(event: RunEvent) {
                recordedEvents.add(event)
            }
            override fun snapshot(): List<RunEvent> = emptyList()
        }
        return ApplyProtocol(writer, store, FakeClock, ids, customFaults, mutex, port)
    }

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        faults = RecordingFaultInjector()
    }

    private fun mutatingPlan(): ValidatedLayoutPlan {
        val sourceState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem()))
        val intendedState = CanonicalFixtures.state(
            items = listOf(CanonicalFixtures.appItem(cell = GridCell(0, 1))),
        )
        val updateAction = ApplyAction.Update(
            ref = app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                app.lawnchair.organizer.planning.ItemId("app.a"),
            ),
            expected = CanonicalFixtures.appItem(),
            intended = CanonicalFixtures.appItem(cell = GridCell(0, 1)),
        )
        val revision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(sourceState)
        return ValidatedLayoutPlan(
            sourceRevision = revision,
            sourceState = sourceState,
            intendedState = intendedState,
            actions = listOf(updateAction),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v1"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }

    @Test
    fun checkpointEmittedOnSuccess() {
        val protocol = createProtocol()
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)

        // Find CHECKPOINTED event
        val checkpointed = recordedEvents.firstOrNull { it.phase == PhaseCode.CHECKPOINTED }
        assertNotNull("CHECKPOINTED must be emitted on checkpoint success", checkpointed)
        assertEquals("CHECKPOINTED must carry A4 stage", ApplyStage.A4, checkpointed!!.applyStage)
        // Must carry the runId from the result
        assertEquals((result as ApplyResult.Applied).runId.value, checkpointed.runId)
    }

    @Test
    fun checkpointNotEmittedOnFailure() {
        store.checkpointCreateFails = true
        val protocol = createProtocol()
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected Rejected, got $result", result is ApplyResult.Rejected)

        val checkpointed = recordedEvents.firstOrNull { it.phase == PhaseCode.CHECKPOINTED }
        assertTrue(
            "CHECKPOINTED must NOT be emitted when checkpoint fails",
            checkpointed == null || recordedEvents.indexOf(checkpointed) < 0,
        )
    }

    @Test
    fun applyCommittedEmittedAfterCommittedUnverified() {
        val protocol = createProtocol()
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)

        val committed = recordedEvents.firstOrNull { it.phase == PhaseCode.APPLY_COMMITTED }
        assertNotNull("APPLY_COMMITTED must be emitted after COMMITTED_UNVERIFIED advance", committed)
        assertEquals("APPLY_COMMITTED must carry A6 stage", ApplyStage.A6, committed!!.applyStage)
        // Verify it appears after CHECKPOINTED
        val checkpointed = recordedEvents.first { it.phase == PhaseCode.CHECKPOINTED }
        assertTrue(
            "APPLY_COMMITTED must appear after CHECKPOINTED",
            recordedEvents.indexOf(committed) > recordedEvents.indexOf(checkpointed),
        )
    }

    @Test
    fun applyCommittedNotEmittedOnRollbackPath() {
        // Make verification fail so the path goes through rollback, not COMMITTED_UNVERIFIED
        val protocol = createProtocol()
        // Use a plan that causes A5 precondition failure (rollback without COMMITTED_UNVERIFIED)
        // A stale revision at A2 will be caught before commit
        val plan = mutatingPlan()
        writer.setCurrentState(
            CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(9, 9)))),
        )
        val result = protocol.apply(plan)
        assertTrue("Expected Rejected (stale), got $result", result is ApplyResult.Rejected)

        val committed = recordedEvents.firstOrNull { it.phase == PhaseCode.APPLY_COMMITTED }
        assertTrue("APPLY_COMMITTED must NOT be emitted on rollback path", committed == null)
    }

    @Test
    fun concurrentRunRejectedCarriesRunId() {
        val mutex = RunMutex()
        val outerRunId = app.lawnchair.organizer.application.public.RunId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        mutex.tryAcquire(outerRunId)
        try {
            val protocol = createProtocol(mutex)
            val result = protocol.apply(mutatingPlan())
            assertTrue("Expected ConcurrentRun, got $result", result is ApplyResult.ConcurrentRun)
        } finally {
            mutex.release(outerRunId)
        }

        val concurrent = recordedEvents.firstOrNull { it.phase == PhaseCode.CONCURRENT_RUN_REJECTED }
        assertNotNull("CONCURRENT_RUN_REJECTED must be emitted", concurrent)
        assertNotNull("CONCURRENT_RUN_REJECTED must carry a runId", concurrent!!.runId)
    }

    @Test
    fun recoveryStoreUnavailableRejectionStageA2() {
        // Make the store unavailable to trigger RECOVERY_STORE_UNAVAILABLE
        store.storeAvailability = app.lawnchair.organizer.application.protocol.RecoveryStorePort.StoreAvailability.READ_FAILED
        val protocol = createProtocol()
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected Rejected, got $result", result is ApplyResult.Rejected)
        assertEquals(
            app.lawnchair.organizer.application.public.PreWriteRejection.RECOVERY_STORE_UNAVAILABLE,
            (result as ApplyResult.Rejected).reason,
        )

        // The terminal event should carry stage A2 (the detection point for store availability)
        // But we can't directly observe the stage from the ApplyResult alone.
        // The stage is set by applyStageForResult which maps RECOVERY_STORE_UNAVAILABLE to A2.
        // We verify this through the terminal event emitted by ApplyProtocol.
        val terminal = recordedEvents.lastOrNull()
        assertNotNull("Terminal event must be emitted", terminal)
        // The terminal event should have applyStage=A2
        assertEquals(ApplyStage.A2, terminal!!.applyStage)
    }

    @Test
    fun crossRunStageLeakage() {
        val protocol = createProtocol()
        // Run 1 completes fully: terminal event is APPLY_VERIFIED at A8.
        val run1 = protocol.apply(mutatingPlan())
        assertTrue(run1 is ApplyResult.Applied)
        val run1Terminal = recordedEvents.last()
        assertEquals(PhaseCode.APPLY_VERIFIED, run1Terminal.phase)
        assertEquals(ApplyStage.A8, run1Terminal.applyStage)

        // Run 2 is rejected pre-checkpoint: its terminal stage must be A2,
        // not leaked A8 from run 1.
        store.storeAvailability = app.lawnchair.organizer.application.protocol.RecoveryStorePort.StoreAvailability.READ_FAILED
        val run2 = protocol.apply(mutatingPlan())
        assertTrue(run2 is ApplyResult.Rejected)
        val run2Terminal = recordedEvents.last()
        assertEquals(PhaseCode.APPLY_REJECTED, run2Terminal.phase)
        assertEquals("Run 2 stage must be the detection stage, not leaked from run 1", ApplyStage.A2, run2Terminal.applyStage)
    }

    @Test
    fun markApplyingFailureTerminalCarriesA5AndCheckpointPointId() {
        store.markApplyingFails = true
        val protocol = createProtocol()
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected Rejected, got $result", result is ApplyResult.Rejected)
        assertEquals(
            app.lawnchair.organizer.application.public.PreWriteRejection.RECOVERY_STORE_UNAVAILABLE,
            (result as ApplyResult.Rejected).reason,
        )

        val terminal = recordedEvents.lastOrNull()
        assertNotNull("Terminal event must be emitted", terminal)
        assertEquals("Post-checkpoint markApplying failure is detected at A5", ApplyStage.A5, terminal!!.applyStage)
        assertEquals(
            "Terminal event must carry the checkpoint's pointId",
            store.checkpointPointIds.single().value,
            terminal.pointId,
        )
    }

    @Test
    fun rollbackTerminalCarriesCheckpointPointId() {
        writer.nextTxOutcome = app.lawnchair.organizer.application.protocol.ApplyTxOutcome.Failed(
            IllegalStateException("write failed"),
        )
        val protocol = createProtocol()
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected RolledBack, got $result", result is ApplyResult.RolledBack)

        val terminal = recordedEvents.lastOrNull()
        assertNotNull("Terminal event must be emitted", terminal)
        assertEquals(PhaseCode.APPLY_ROLLED_BACK, terminal!!.phase)
        assertEquals(ApplyStage.A6, terminal.applyStage)
        assertEquals(
            "APPLY_ROLLED_BACK must correlate to the checkpoint's pointId",
            store.checkpointPointIds.single().value,
            terminal.pointId,
        )
    }

    /**
     * Item 1 interleaving test: concurrent apply() must not destroy the active
     * run's diagnostic context. Run A is paused mid-flight (holds RunMutex,
     * before markApplying). Run B calls apply() and gets ConcurrentRun.
     * Run A then completes, and its terminal event must still carry the correct
     * stage and pointId.
     */
    @Test
    fun concurrentApplyDoesNotDestroyActiveRunDiagnosticContext() {
        val mutex = RunMutex()
        // BlockingFaultInjector: Run A pauses at beforeRecoveryLifecycleCommit(APPLYING),
        // allowing Run B to attempt apply(). Run A resumes after Run B is rejected.
        val blockLatch = CountDownLatch(1)
        val resumeLatch = CountDownLatch(1)
        val blockingFaults = object : FaultInjector by faults {
            override fun beforeRecoveryLifecycleCommit(
                phase: FaultInjector.RecoveryLifecyclePhase,
                pointId: RecoveryPointId,
            ) {
                if (phase == FaultInjector.RecoveryLifecyclePhase.APPLYING) {
                    // Signal that Run A is paused so Run B can attempt apply()
                    blockLatch.countDown()
                    // Wait for Run B to be rejected before resuming Run A
                    resumeLatch.await(5, TimeUnit.SECONDS)
                }
                faults.beforeRecoveryLifecycleCommit(phase, pointId)
            }
        }

        val runAIds = FixedOperationIdSource(
            runIds = listOf("111111111111111111111111111111aa"),
            pointIds = listOf("222222222222222222222222222222aa"),
        )
        val protocolA = createProtocolWithFaults(mutex, blockingFaults, runAIds)

        // Run A in a background thread
        val plan = mutatingPlan()
        var runAResult: ApplyResult? = null
        val runAThread = Thread {
            runAResult = protocolA.apply(plan)
        }
        runAThread.start()

        // Wait for Run A to pause at the blocking hook
        assertTrue("Run A must reach the pause hook", blockLatch.await(5, TimeUnit.SECONDS))

        // Verify Run A is still inside apply() and holds the mutex
        assertNotNull("RunMutex must be held by Run A", mutex.currentHolder())

        // Run B: a different protocol instance on the same mutex, trying to apply
        val runBIds = FixedOperationIdSource(
            runIds = listOf("333333333333333333333333333333bb"),
            pointIds = listOf("444444444444444444444444444444bb"),
        )
        val runBRecordedEvents = mutableListOf<RunEvent>()
        val runBPort = object : DiagnosticsPort {
            override fun emit(event: RunEvent) {
                runBRecordedEvents.add(event)
            }
            override fun snapshot(): List<RunEvent> = emptyList()
        }
        val protocolB = ApplyProtocol(writer, store, FakeClock, runBIds, faults, mutex, runBPort)

        val runBResult = protocolB.apply(plan)
        assertTrue("Run B must be ConcurrentRun, got $runBResult", runBResult is ApplyResult.ConcurrentRun)

        val runBConcurrent = runBRecordedEvents.firstOrNull { it.phase == PhaseCode.CONCURRENT_RUN_REJECTED }
        assertNotNull("Run B must emit CONCURRENT_RUN_REJECTED", runBConcurrent)
        assertNotNull("Run B CONCURRENT_RUN_REJECTED must carry a runId", runBConcurrent!!.runId)

        // Verify Run B did NOT emit any event that belongs to Run A's context
        val runBEventPhases = runBRecordedEvents.map { it.phase }.toSet()
        assertTrue("Run B must not emit CHECKPOINTED", PhaseCode.CHECKPOINTED !in runBEventPhases)
        assertTrue("Run B must not emit APPLY_COMMITTED", PhaseCode.APPLY_COMMITTED !in runBEventPhases)
        assertTrue("Run B must not emit APPLY_VERIFIED", PhaseCode.APPLY_VERIFIED !in runBEventPhases)

        // Resume Run A
        resumeLatch.countDown()
        runAThread.join(5_000)

        assertNotNull("Run A must complete", runAResult)
        assertTrue("Run A must be Applied, got $runAResult", runAResult is ApplyResult.Applied)

        // Run A's terminal event must carry the correct stage and pointId
        val runATerminal = recordedEvents.lastOrNull()
        assertNotNull("Run A must emit a terminal event", runATerminal)
        val runAApplied = runAResult as ApplyResult.Applied
        assertEquals(
            "Run A terminal event must be APPLY_VERIFIED",
            PhaseCode.APPLY_VERIFIED,
            runATerminal!!.phase,
        )
        assertEquals(
            "Run A terminal event must carry A8 stage",
            ApplyStage.A8,
            runATerminal.applyStage,
        )
        assertEquals(
            "Run A terminal event must carry the correct runId",
            runAApplied.runId.value,
            runATerminal.runId,
        )
        assertEquals(
            "Run A terminal event must carry the correct pointId",
            runAApplied.pointId.value,
            runATerminal.pointId,
        )
        assertNotNull("Run A terminal event must have a pointId", runATerminal.pointId)
    }

    /**
     * Variant with the defect actually reproducible: both runs share ONE
     * ApplyProtocol instance (matching the production wiring), and run A
     * terminates on a path where the tracked context is load-bearing —
     * markApplying failure must report the tracked A5 stage and the
     * checkpoint's pointId, whereas the static fallbacks would produce A2 and
     * a null pointId for Rejected(RECOVERY_STORE_UNAVAILABLE). Under the
     * pre-fix instance-field implementation, run B's apply() entry reset
     * wiped run A's context, degrading the terminal event to those fallbacks.
     */
    @Test
    fun concurrentApplyOnSharedInstanceKeepsLoadBearingContext() {
        val mutex = RunMutex()
        val blockLatch = CountDownLatch(1)
        val resumeLatch = CountDownLatch(1)
        val blockingFaults = object : FaultInjector by faults {
            override fun beforeRecoveryLifecycleCommit(
                phase: FaultInjector.RecoveryLifecyclePhase,
                pointId: RecoveryPointId,
            ) {
                if (phase == FaultInjector.RecoveryLifecyclePhase.APPLYING) {
                    blockLatch.countDown()
                    resumeLatch.await(5, TimeUnit.SECONDS)
                }
                faults.beforeRecoveryLifecycleCommit(phase, pointId)
            }
        }

        val runAIds = FixedOperationIdSource(
            runIds = listOf("111111111111111111111111111111aa"),
            pointIds = listOf("222222222222222222222222222222aa"),
        )
        recordedEvents = mutableListOf()
        val port = object : DiagnosticsPort {
            override fun emit(event: RunEvent) {
                recordedEvents.add(event)
            }
            override fun snapshot(): List<RunEvent> = emptyList()
        }
        // Single shared instance, as production wiring uses.
        val protocol = ApplyProtocol(writer, store, FakeClock, runAIds, blockingFaults, mutex, port)

        val plan = mutatingPlan()
        var runAResult: ApplyResult? = null
        val runAThread = Thread {
            runAResult = protocol.apply(plan)
        }
        runAThread.start()
        assertTrue("Run A must reach the pause hook", blockLatch.await(5, TimeUnit.SECONDS))
        assertNotNull("RunMutex must be held by Run A", mutex.currentHolder())

        // Run B on the SAME instance: rejected ConcurrentRun, but its apply()
        // entry must not wipe run A's diagnostic context.
        val runBResult = protocol.apply(plan)
        assertTrue("Run B must be ConcurrentRun, got $runBResult", runBResult is ApplyResult.ConcurrentRun)

        // Make run A's resumption terminate on the markApplying-failure path,
        // where the tracked A5 stage and checkpoint pointId are load-bearing.
        store.markApplyingFails = true
        resumeLatch.countDown()
        runAThread.join(5_000)

        assertNotNull("Run A must complete", runAResult)
        assertTrue("Run A must be Rejected, got $runAResult", runAResult is ApplyResult.Rejected)

        val runATerminal = recordedEvents.lastOrNull { it.phase == PhaseCode.APPLY_REJECTED }
        assertNotNull("Run A must emit an APPLY_REJECTED terminal event", runATerminal)
        assertEquals(
            "Tracked A5 stage must survive run B's apply() entry (fallback would be A2)",
            ApplyStage.A5,
            runATerminal!!.applyStage,
        )
        assertEquals(
            "Checkpoint pointId must survive run B's apply() entry (fallback would be null)",
            store.checkpointPointIds.single().value,
            runATerminal.pointId,
        )
    }
}
