package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadinessGateTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var module: LayoutApplicationModule<FakeRecoveryStore>
    private val pointId = RecoveryPointId("22222222222222222222222222222222")
    private val runId = RunId("11111111111111111111111111111111")

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        module = LayoutApplicationModule(
            writer,
            store,
            FakeClock,
            FixedOperationIdSource(),
            app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
            RecordingFaultInjector(),
        )
    }

    @Test
    fun applyReturnsWriterBusyBeforeReconciliation() {
        val plan = noChangePlan()
        val result = module.apply(plan)
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.WRITER_BUSY,
            (result as ApplyResult.Rejected).reason,
        )
    }

    @Test
    fun applyArrivingDuringReconciliationWaitsThenRunsAfterReady() {
        val faults = BlockingRestartFaultInjector()
        module = newModule(faults)
        seedReady()
        val reconciliation = Thread { module.reconcileAtStart() }
        reconciliation.start()
        assertTrue(faults.entered.await(1, TimeUnit.SECONDS))

        val result = AtomicReference<ApplyResult>()
        val completed = CountDownLatch(1)
        Thread {
            result.set(module.apply(noChangePlan()))
            completed.countDown()
        }.start()
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))

        faults.release.countDown()
        reconciliation.join(1_000)
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertTrue(result.get() is ApplyResult.NoChanges)
    }

    @Test
    fun applyProceedsAfterSuccessfulReconciliation() {
        module.reconcileAtStart()
        assertEquals(ReadinessGate.State.READY, module.readinessGate.state)
        val plan = mutatingPlan()
        val result = module.apply(plan)
        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)
    }

    @Test
    fun recoverReturnsWriterBusyBeforeReconciliation() {
        val request = RecoveryRequest(pointId, RevisionId("test"))
        val result = module.recover(request)
        assertEquals(RecoveryResult.WriterBusy, result)
    }

    @Test
    fun recoverProceedsAfterSuccessfulReconciliation() {
        module.reconcileAtStart()
        val request = RecoveryRequest(
            RecoveryPointId("00000000000000000000000000000000"),
            RevisionId("missing"),
        )
        val result = module.recover(request)
        assertTrue(result is RecoveryResult.NotRestorable)
        assertEquals(
            RecoveryRejection.MISSING,
            (result as RecoveryResult.NotRestorable).reason,
        )
    }

    @Test
    fun recoverArrivingDuringReconciliationWaitsThenRunsAfterReady() {
        val faults = BlockingRestartFaultInjector()
        module = newModule(faults)
        seedReady()
        val reconciliation = Thread { module.reconcileAtStart() }
        reconciliation.start()
        assertTrue(faults.entered.await(1, TimeUnit.SECONDS))

        val result = AtomicReference<RecoveryResult>()
        val completed = CountDownLatch(1)
        Thread {
            result.set(
                module.recover(
                    RecoveryRequest(RecoveryPointId("00000000000000000000000000000000"), RevisionId("missing")),
                ),
            )
            completed.countDown()
        }.start()
        assertFalse(completed.await(100, TimeUnit.MILLISECONDS))

        faults.release.countDown()
        reconciliation.join(1_000)
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertEquals(
            RecoveryRejection.MISSING,
            (result.get() as RecoveryResult.NotRestorable).reason,
        )
    }

    @Test
    fun applyReturnsStoreUnavailableAfterFailedReconciliation() {
        seedUnresolvableRecord()
        module.reconcileAtStart()
        assertEquals(ReadinessGate.State.FAILED, module.readinessGate.state)
        val result = module.apply(noChangePlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.RECOVERY_STORE_UNAVAILABLE,
            (result as ApplyResult.Rejected).reason,
        )
    }

    @Test
    fun recoverReturnsStoreFailureAfterFailedReconciliation() {
        seedUnresolvableRecord()
        module.reconcileAtStart()
        assertEquals(ReadinessGate.State.FAILED, module.readinessGate.state)
        val request = RecoveryRequest(pointId, RevisionId("test"))
        val result = module.recover(request)
        assertTrue(result is RecoveryResult.RestoreFailed)
        assertEquals(
            app.lawnchair.organizer.application.public.RecoveryFailure.RECOVERY_STORE_FAILED,
            (result as RecoveryResult.RestoreFailed).failure,
        )
    }

    @Test
    fun failedGateIsRetryableViaSecondReconciliation() {
        seedUnresolvableRecord()
        module.reconcileAtStart()
        assertEquals(ReadinessGate.State.FAILED, module.readinessGate.state)
        store.clear()
        module.reconcileAtStart()
        assertEquals(ReadinessGate.State.READY, module.readinessGate.state)
        val result = module.apply(noChangePlan())
        assertTrue(result is ApplyResult.NoChanges)
    }

    @Test
    fun reconcileAtStartWithUnresolvedFailuresSetsGateToFailed() {
        seedUnresolvableRecord()
        val summary = module.reconcileAtStart()
        assertTrue(summary.hasUnresolvedFailures())
        assertEquals(ReadinessGate.State.FAILED, module.readinessGate.state)
    }

    @Test
    fun reconcileAtStartOnEmptyStoreSetsGateToReady() {
        val summary = module.reconcileAtStart()
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertEquals(ReadinessGate.State.READY, module.readinessGate.state)
    }

    @Test
    fun reconciliationExceptionFailsClosedAndRemainsRetryable() {
        module = newModule(object : FaultInjector by FaultInjector.NOOP {
            override fun restartBoundary(phase: FaultInjector.RestartPhase): FaultInjector.RestartDirective {
                error("injected restart failure")
            }
        })
        seedReady()

        assertEquals(RestartReconciler.ReconciliationSummary.Failed, module.reconcileAtStart())
        assertEquals(ReadinessGate.State.FAILED, module.readinessGate.state)
        val rejected = module.apply(noChangePlan()) as ApplyResult.Rejected
        assertEquals(PreWriteRejection.RECOVERY_STORE_UNAVAILABLE, rejected.reason)

        module = newModule(FaultInjector.NOOP)
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, module.reconcileAtStart())
        assertEquals(ReadinessGate.State.READY, module.readinessGate.state)
    }

    @Test
    fun modelLoadTimeoutFailsStartupGateClosed() {
        module.failStartupReconciliation()
        assertEquals(ReadinessGate.State.FAILED, module.readinessGate.state)
        val rejected = module.apply(noChangePlan()) as ApplyResult.Rejected
        assertEquals(PreWriteRejection.RECOVERY_STORE_UNAVAILABLE, rejected.reason)
    }

    private fun seedUnresolvableRecord() {
        val capture = writer.captureCurrent(CaptureId("gate-test"))
        store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = pointId,
                runId = runId,
                preManifest = capture.manifest,
                preRevision = capture.revision,
                preDigest = capture.digest,
                applyActionDigest = ByteArray(32),
                itemCount = capture.manifest.rowCount,
                resourceCount = capture.manifest.resources.size,
            ),
        )
        store.advance(pointId, LifecycleState.APPLYING)
        writer.setCurrentState(
            CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(5, 5)))),
        )
    }

    private fun seedReady() {
        val capture = writer.captureCurrent(CaptureId("ready"))
        store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = pointId,
                runId = runId,
                preManifest = capture.manifest,
                preRevision = capture.revision,
                preDigest = capture.digest,
                applyActionDigest = ByteArray(32),
                itemCount = capture.manifest.rowCount,
                resourceCount = capture.manifest.resources.size,
            ),
        )
    }

    private fun newModule(faults: FaultInjector): LayoutApplicationModule<FakeRecoveryStore> {
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        return LayoutApplicationModule(
            writer,
            store,
            FakeClock,
            FixedOperationIdSource(),
            app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
            faults,
        )
    }

    private fun noChangePlan(): ValidatedLayoutPlan {
        val capture = writer.captureCurrent(CaptureId("nochange"))
        return ValidatedLayoutPlan(
            sourceRevision = capture.revision,
            sourceState = capture.layoutState,
            intendedState = capture.layoutState,
            actions = capture.layoutState.items.map { ApplyAction.Preserve(it.ref, it) },
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }

    private fun mutatingPlan(): ValidatedLayoutPlan {
        val sourceState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(0, 0))))
        val intendedState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(1, 1))))
        val ref = sourceState.items.first().ref
        return ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = listOf(
                ApplyAction.Update(ref, CanonicalFixtures.appItem(cell = GridCell(0, 0)), CanonicalFixtures.appItem(cell = GridCell(1, 1))),
            ),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }

    private class BlockingRestartFaultInjector : FaultInjector by FaultInjector.NOOP {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun restartBoundary(phase: FaultInjector.RestartPhase): FaultInjector.RestartDirective {
            if (phase == FaultInjector.RestartPhase.BEFORE_RECONCILE) {
                entered.countDown()
                check(release.await(1, TimeUnit.SECONDS)) { "Timed out waiting to release reconciliation" }
            }
            return FaultInjector.RestartDirective.CONTINUE
        }
    }
}
