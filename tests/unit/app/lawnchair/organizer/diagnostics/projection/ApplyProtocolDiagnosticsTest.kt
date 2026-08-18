package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.ApplyProtocol
import app.lawnchair.organizer.application.protocol.FixedOperationIdSource
import app.lawnchair.organizer.application.protocol.RecordingFaultInjector
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
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
        val port = DiagnosticsPort { event -> recordedEvents.add(event) }
        val ids = FixedOperationIdSource()
        return ApplyProtocol(writer, store, FakeClock, ids, faults, mutex, port)
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
    fun markApplyingFailureTerminalCarriesA4AndCheckpointPointId() {
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
        assertEquals("Post-checkpoint markApplying failure is detected at A4", ApplyStage.A4, terminal!!.applyStage)
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
}
