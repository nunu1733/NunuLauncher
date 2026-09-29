package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AC-3, AC-4, AC-6, AC-7, AC-13, AC-15 plus SA-01..SA-14 happy/stale/empty/
 * Nth-write/outcome-unknown/reload-failure/verification-failure/concurrent/
 * writer-busy coverage. Pure JVM through the public seam.
 *
 * Issue #14 Stage B step 4.
 */
class ApplyProtocolTest {

    private fun storedLifecycleOf(id: RecoveryPointId): LifecycleState? = (
        store.readRecord(id) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.lifecycle

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var faults: RecordingFaultInjector
    private lateinit var protocol: ApplyProtocol

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        faults = RecordingFaultInjector()
        val mutex = RunMutex()
        val ids = FixedOperationIdSource()
        protocol = ApplyProtocol(writer, store, FakeClock, ids, faults, mutex)
    }

    /**
     * Issue #450 (review round 2 finding 1): the undo receipt must be
     * invocation-local. Two applies back to back — A completing first, B
     * completing before A's caller reads its receipt — must still leave A's
     * receipt intact, because the revision is captured inside A's own
     * ApplyContext while A holds the run mutex. Regression oracle for the
     * removed shared-slot implementation, where B overwrote A's revision
     * before A could read it.
     */
    @Test
    fun undoReceiptSurvivesAConcurrentApplyCompletingInsideTheReceiptWindow() {
        // True-concurrency regression oracle for the removed shared-slot
        // implementation (round 1 finding 1 / round 3 finding 1). A hook fires
        // at A's A5 boundary — BEFORE A's Applied result (and receipt) is
        // assembled — and blocks until B has fully completed its own
        // applyWithUndoReceipt invocation, including its receipt read. With
        // the old shared-slot code, B's completion overwrote the slot keyed
        // by run id before A's caller could read it, so A's receipt read
        // returned null; with the invocation-local context, A's receipt is
        // already decided and must survive B's interleaving untouched.
        val planA = mutatingPlan()
        val planB = mutatingPlan(
            CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(1, 1)))),
        )
        val aReceiptAssemblyStarted = java.util.concurrent.CountDownLatch(1)
        val bCompleted = java.util.concurrent.CountDownLatch(1)
        val bOutcome = java.util.concurrent.atomic.AtomicReference<Pair<ApplyResult, app.lawnchair.organizer.planning.RevisionId?>?>()
        val aOutcome = java.util.concurrent.atomic.AtomicReference<Pair<ApplyResult, app.lawnchair.organizer.planning.RevisionId?>?>()
        val threadB = Thread {
            // B's mutex acquisition is non-blocking: while A holds the run
            // mutex B is rejected with ConcurrentRun, so B retries until A's
            // release lets it in. This reproduces the old shared-slot race
            // (B completing right at A's release/receipt boundary) without
            // depending on scheduler timing.
            var attempt = 0
            while (attempt < 200) {
                val outcome = protocol.applyWithUndoReceipt(planB, RunId("b123456789abcdef0123456789abcdef"))
                if (outcome.first is ApplyResult.Applied) {
                    bOutcome.set(outcome)
                    break
                }
                attempt += 1
                Thread.sleep(25)
            }
            bCompleted.countDown()
        }
        threadB.isDaemon = true
        writer.onApplyA5Reread = {
            // A is inside its apply (mutex held, at the A5 boundary — before
            // its Applied result and receipt are assembled). Signal the main
            // thread to START B here: B's mutex acquisition blocks until A
            // releases, which reproduces the removed shared-slot code's race
            // window (A releases → A reads the shared slot → B overwrote it).
            // With the invocation-local context, A's receipt is decided before
            // its mutex release and must survive B's interleaving untouched.
            aReceiptAssemblyStarted.countDown()
        }
        // A runs on its own thread; B is started from the main thread the
        // moment A reaches its A5 boundary (inside A's apply window), so B's
        // apply+receipt cycle races A's release/receipt boundary.
        val threadA = Thread {
            aOutcome.set(protocol.applyWithUndoReceipt(planA, RunId("a123456789abcdef0123456789abcdef")))
        }
        threadA.isDaemon = true
        threadA.start()
        assertTrue("A never reached its A5 boundary", aReceiptAssemblyStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // Start B while A is still inside its apply; B blocks on the mutex
        // until A releases and then completes.
        threadB.start()
        threadA.join(10_000)
        threadB.join(10_000)
        val (resultA, receiptA) = aOutcome.get()!!
        val (resultB, receiptB) = bOutcome.get()!!
        assertTrue("expected Applied for B, got $resultB", resultB is ApplyResult.Applied)
        assertNotNull("B's receipt must carry its own verified post revision", receiptB)
        assertTrue("expected Applied for A, got $resultA", resultA is ApplyResult.Applied)
        // THE regression assertion: A's receipt survives B's completion that
        // raced A's release/receipt boundary. Under the shared-slot code the
        // post-release read returned null (run-id mismatch after B overwrote
        // the slot).
        assertNotNull("A's receipt must survive B's concurrent completion", receiptA)
        // B ran last (it waited for A's release), so the final state is B's
        // post-state; what the oracle pins is that BOTH receipts were decided
        // inside their own invocations — A's from A's intended state, B's from
        // B's — and neither was lost or cross-wired by the interleaving.
        assertEquals(planB.intendedState, writer.currentState())
        assertTrue("receipts must be per-invocation", receiptA != receiptB)
        writer.onApplyA5Reread = null
    }

    /**
     * Issue #450 (round 4 finding 1): the receipt race oracle through the
     * production module seam — `LayoutApplicationModule.applyWithUndoReceipt`
     * — which is where the round-1 bug lived (the module read a shared slot
     * AFTER the protocol released the mutex). A hook at A's A5 boundary
     * starts B; B retries the run mutex until A's release lets it in and
     * completes a full apply+receipt cycle. Under the removed shared-slot
     * implementation, B's completion overwrote the slot before the module's
     * post-release read, so A's receipt came back null; with the
     * invocation-local context the receipt is decided before the release and
     * must survive.
     */
    @Test
    fun moduleReceiptSurvivesAConcurrentApplyRacingTheReleaseBoundary() {
        val module = LayoutApplicationModule(
            writer = writer,
            store = store,
            clock = FakeClock,
            operationIds = FixedOperationIdSource(),
            folderTitleResolver = app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
        )
        module.reconcileAtStart()

        val planA = mutatingPlan()
        val planB = mutatingPlan(
            CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(1, 1)))),
        )
        val aInApply = java.util.concurrent.CountDownLatch(1)
        val bCompleted = java.util.concurrent.CountDownLatch(1)
        val bOutcome = java.util.concurrent.atomic.AtomicReference<Pair<ApplyResult, app.lawnchair.organizer.planning.RevisionId?>?>()
        val aOutcome = java.util.concurrent.atomic.AtomicReference<Pair<ApplyResult, app.lawnchair.organizer.planning.RevisionId?>?>()

        writer.onApplyA5Reread = {
            // A holds the run mutex at its A5 boundary. Signal the main
            // thread to start B here — B retries the mutex until A releases,
            // so B's completion races A's release/receipt boundary.
            aInApply.countDown()
        }
        val threadB = Thread {
            var attempt = 0
            while (attempt < 400) {
                val outcome = module.applyWithUndoReceipt(planB, RunId("b123456789abcdef0123456789abcdef"))
                if (outcome.first is ApplyResult.Applied) {
                    bOutcome.set(outcome)
                    break
                }
                attempt += 1
                Thread.sleep(25)
            }
            bCompleted.countDown()
        }
        threadB.isDaemon = true

        val threadA = Thread {
            aOutcome.set(module.applyWithUndoReceipt(planA, RunId("a123456789abcdef0123456789abcdef")))
        }
        threadA.isDaemon = true
        threadA.start()
        assertTrue("A never reached its A5 boundary", aInApply.await(10, java.util.concurrent.TimeUnit.SECONDS))
        threadB.start()
        threadA.join(20_000)
        assertTrue("B never completed", bCompleted.await(20, java.util.concurrent.TimeUnit.SECONDS))

        val (resultA, receiptA) = aOutcome.get()!!
        val (resultB, receiptB) = bOutcome.get()!!
        assertTrue("expected Applied for A, got $resultA", resultA is ApplyResult.Applied)
        assertTrue("expected Applied for B, got $resultB", resultB is ApplyResult.Applied)
        // THE regression assertions: both receipts decided inside their own
        // invocations. Under the shared-slot implementation A's post-release
        // read returned null (B overwrote the slot first).
        assertNotNull("A's receipt must survive B's concurrent completion", receiptA)
        assertNotNull("B's receipt must carry its own verified post revision", receiptB)
        assertTrue("receipts must be per-invocation", receiptA != receiptB)
        writer.onApplyA5Reread = null
    }

    @Test
    fun undoReceiptIsNullForANonAppliedResult() {
        // A concurrent run holds the mutex: the receipt invocation fails
        // closed with ConcurrentRun and no revision.
        val blocking = RunMutex()
        assertTrue(blocking.tryAcquire(RunId("c123456789abcdef0123456789abcdef")))
        val protocol2 = ApplyProtocol(writer, store, FakeClock, FixedOperationIdSource(), faults, blocking)
        val (result, receipt) = protocol2.applyWithUndoReceipt(
            mutatingPlan(),
            RunId("d123456789abcdef0123456789abcdef"),
        )
        assertTrue("expected ConcurrentRun, got $result", result is ApplyResult.ConcurrentRun)
        assertNull("a non-Applied result carries no receipt revision", receipt)
    }

    @Test
    fun sa01ValidMutatingPlanIsApplied() {
        val plan = mutatingPlan()
        val result = protocol.apply(plan)
        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)
        assertEquals(plan.intendedState, writer.currentState())
        assertEquals(1, writer.appliedWriteSets)
        assertEquals(1, writer.reloadCount)
    }

    @Test
    fun materializedNonIdentityDriftIsRejectedByProtocol() {
        writer.materializedIntendedStateOverride = { state ->
            state.copy(
                items = state.items.map { it.copy(lockState = OrganizerLockState.LOCKED) },
            )
        }

        val result = protocol.apply(mutatingPlan())

        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.INVALID_PLAN, (result as ApplyResult.Rejected).reason)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
    }

    @Test
    fun sa02EmptyDiffIsNoChanges() {
        val sourceState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem()))
        val preserveAction = ApplyAction.Preserve(
            ref = app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                app.lawnchair.organizer.planning.ItemId("app.a"),
            ),
            expected = CanonicalFixtures.appItem(),
        )
        val plan = ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = sourceState,
            actions = listOf(preserveAction),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
        val result = protocol.apply(plan)
        assertEquals(ApplyResult.NoChanges::class, result::class)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
    }

    @Test
    fun sa03RevisionMismatchAfterA2IsStaleRejection() {
        val plan = mutatingPlan()
        // Mutate the source state so captureCurrent sees a different revision.
        writer.setCurrentState(
            CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(9, 9)))),
        )
        val result = protocol.apply(plan)
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.STALE_REVISION, (result as ApplyResult.Rejected).reason)
        assertEquals(0, writer.appliedWriteSets)
    }

    @Test
    fun sa04CheckpointWriteFailurePreconditionsBeforeLauncherMutation() {
        store.checkpointCreateFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.CHECKPOINT_CREATE_FAILED,
            (result as ApplyResult.Rejected).reason,
        )
        assertEquals(0, writer.appliedWriteSets)
    }

    @Test
    fun sa04CheckpointValidateFailurePreconditionsBeforeLauncherMutation() {
        store.checkpointValidateFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.CHECKPOINT_VALIDATE_FAILED,
            (result as ApplyResult.Rejected).reason,
        )
    }

    @Test
    fun checkpointPointIdCollisionRetriesThreeTimesWithFreshIds() {
        store.checkpointCollisionsRemaining = 3
        val pointIds = listOf(
            "22222222222222222222222222222221",
            "22222222222222222222222222222222",
            "22222222222222222222222222222223",
            "22222222222222222222222222222224",
        )
        protocol = ApplyProtocol(
            writer,
            store,
            FakeClock,
            FixedOperationIdSource(pointIds = pointIds),
            faults,
            RunMutex(),
        )

        val result = protocol.apply(mutatingPlan())

        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)
        assertEquals(RecoveryPointId(pointIds.last()), (result as ApplyResult.Applied).pointId)
        assertEquals(pointIds.map(::RecoveryPointId), store.checkpointPointIds)
    }

    @Test
    fun checkpointPointIdCollisionFailsAfterThreeRetries() {
        store.checkpointCollisionsRemaining = 4
        val pointIds = listOf(
            "22222222222222222222222222222221",
            "22222222222222222222222222222222",
            "22222222222222222222222222222223",
            "22222222222222222222222222222224",
        )
        protocol = ApplyProtocol(
            writer,
            store,
            FakeClock,
            FixedOperationIdSource(pointIds = pointIds),
            faults,
            RunMutex(),
        )

        val result = protocol.apply(mutatingPlan())

        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.CHECKPOINT_CREATE_FAILED, (result as ApplyResult.Rejected).reason)
        assertEquals(pointIds.map(::RecoveryPointId), store.checkpointPointIds)
        assertEquals(0, writer.appliedWriteSets)
    }

    @Test
    fun sa07NthWriteFailureLeavesLauncherPreStateAndIsRolledBack() {
        writer.setFailOnNthWrite(1L)
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.RolledBack)
        assertEquals(ApplyFailure.WRITE_FAILED, (result as ApplyResult.RolledBack).failure)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(RecoveryPointId("22222222222222222222222222222222")))
    }

    @Test
    fun sa07RollbackLifecycleAdvanceFailureIsUnresolved() {
        writer.setFailOnNthWrite(1L)
        store.advanceFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Unresolved)
        assertEquals(ApplyFailure.RECOVERY_STORE_FAILED, (result as ApplyResult.Unresolved).failure)
        assertEquals(
            LifecycleState.APPLYING,
            storedLifecycleOf(RecoveryPointId("22222222222222222222222222222222")),
        )
    }

    @Test
    fun sa07RollbackPruneFailureIsUnresolvedWithReadyCheckpoint() {
        writer.setFailOnNthWrite(1L)
        store.pruneUnusedFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Unresolved)
        assertEquals(ApplyFailure.RECOVERY_STORE_FAILED, (result as ApplyResult.Unresolved).failure)
        assertEquals(
            LifecycleState.READY,
            storedLifecycleOf(RecoveryPointId("22222222222222222222222222222222")),
        )
    }

    @Test
    fun sa08CommitUnknownWithPostStateClassifiesAsCommittedAndApplies() {
        writer.nextTxOutcome = ApplyTxOutcome.OutcomeUnknown
        val plan = mutatingPlan()
        val result = protocol.apply(plan)
        assertTrue(result is ApplyResult.Applied)
    }

    @Test
    fun sa09CommitUnknownWithNeitherTriggersAutomaticRecovery() {
        writer.nextTxOutcome = ApplyTxOutcome.OutcomeUnknown
        val plan = mutatingPlan()
        val result = protocol.apply(plan)
        assertTrue(result is ApplyResult.Applied)
    }

    @Test
    fun sa10ReloadFailureTriggersAutomaticRecovery() {
        writer.reloadResult = ReloadResult.Failed
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.RecoveryFailed)
    }

    @Test
    fun sa11EarlyReloadCompletionRecaptureMismatchTriggersAutomaticRecovery() {
        val plan = mutatingPlan()
        writer.onReloadRequest = { requestNumber ->
            if (requestNumber == 1) {
                writer.setCurrentState(plan.sourceState)
            }
        }

        val result = protocol.apply(plan)
        assertTrue("Expected automatic recovery, got $result", result is ApplyResult.Recovered)
        assertEquals("Initial and recovery reloads must both complete", 2, writer.reloadCount)
        assertEquals(plan.sourceState, writer.currentState())
    }

    @Test
    fun sa11AutomaticRecoveryVerificationMismatchIsNotFalseSuccess() {
        val plan = mutatingPlan()
        writer.onReloadRequest = { requestNumber ->
            when (requestNumber) {
                1 -> writer.setCurrentState(plan.sourceState)
                2 -> writer.setCurrentState(CanonicalFixtures.state())
            }
        }

        val result = protocol.apply(plan)

        assertTrue("Expected recovery verification failure, got $result", result is ApplyResult.RecoveryFailed)
        assertEquals(ApplyFailure.VERIFICATION_FAILED, (result as ApplyResult.RecoveryFailed).failure)
        assertEquals("Recovery verification must use its reload completion", 2, writer.reloadCount)
    }

    // Issue #152: DB/model convergence on the model-verifiable projection.
    // These are the AC-152-01 regression cases — on pre-#152 code the DB leg
    // alone decided Applied, so a divergent model generation returned a false
    // success whenever the DB recapture matched.

    @Test
    fun sa12ModelDivergenceWithMatchingDbIsNeverApplied() {
        val plan = mutatingPlan()
        // The DB leg matches (echo semantics); the model leg of the apply
        // reload diverges, as if the loader dropped a row relative to the
        // committed DB content.
        writer.modelSnapshotTransform = { snapshot -> snapshot.copy(items = snapshot.items.dropLast(1)) }

        val result = protocol.apply(plan)

        assertEquals("Only the apply reload must have completed", 1, writer.reloadCount)
        assertTrue("A divergent model with a matching DB must never return Applied: $result", result is ApplyResult.RecoveryFailed)
        assertEquals(ApplyFailure.VERIFICATION_FAILED, (result as ApplyResult.RecoveryFailed).failure)
        assertEquals(
            "A model-divergent run must never reach VERIFIED",
            LifecycleState.RESTORING,
            storedLifecycleOf((result as ApplyResult.RecoveryFailed).pointId),
        )
    }

    @Test
    fun sa12ReloadSupersessionIsNotFalseSuccess() {
        writer.reloadResult = ReloadResult.Superseded
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected RecoveryFailed, got $result", result is ApplyResult.RecoveryFailed)
        assertEquals(
            ApplyFailure.MODEL_RELOAD_FAILED,
            (result as ApplyResult.RecoveryFailed).failure,
        )
    }

    @Test
    fun sa12ReloadTimeoutIsNotFalseSuccess() {
        writer.reloadResult = ReloadResult.Timeout
        val result = protocol.apply(mutatingPlan())
        assertTrue("Expected RecoveryFailed, got $result", result is ApplyResult.RecoveryFailed)
    }

    @Test
    fun sa12LegacyShortcutLaunchIntentDivergenceIsNeverApplied() {
        // Issue #152 (re-review P1): the legacy shortcut's faithful launch
        // identity is part of the projection; a loader generation that
        // transforms only the launch target must fail verification even though
        // the DB recapture still matches.
        val legacyItem = CanonicalFixtures.appItem(
            itemId = "shortcut.legacy",
            kind = CanonicalItemKind.ShortcutLegacy,
            target = TargetKey.LegacyShortcutKey,
            intent = OptionalText.Present("#Intent;action=android.intent.action.VIEW;end"),
        )
        val appItem = CanonicalFixtures.appItem()
        val movedApp = appItem.copy(
            placement = app.lawnchair.organizer.application.public.PlacementState.Workspace(
                app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
                    app.lawnchair.organizer.planning.PageId("p0"),
                ),
                GridCell(1, 1),
                GridSpan(1, 1),
            ),
        )
        val sourceState = CanonicalFixtures.state(items = listOf(appItem, legacyItem))
        val intendedState = sourceState.copy(items = listOf(movedApp, legacyItem))
        val plan = ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = listOf(ApplyAction.Update(ref = appItem.ref, expected = appItem, intended = movedApp)),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
        writer.setCurrentState(sourceState)
        writer.modelSnapshotTransform = { snapshot ->
            snapshot.copy(
                items = snapshot.items.map { item ->
                    if (item.kind is CanonicalItemKind.ShortcutLegacy) {
                        item.copy(legacyLaunchIdentity = "diverged-launch-target")
                    } else {
                        item
                    }
                },
            )
        }

        val result = protocol.apply(plan)

        assertTrue("Expected RecoveryFailed, got $result", result is ApplyResult.RecoveryFailed)
        assertEquals(ApplyFailure.VERIFICATION_FAILED, (result as ApplyResult.RecoveryFailed).failure)
        assertEquals(
            "A transformed legacy launch target must never reach VERIFIED",
            LifecycleState.RESTORING,
            storedLifecycleOf((result as ApplyResult.RecoveryFailed).pointId),
        )
    }

    @Test
    fun sa23ConcurrentRunIsRejected() {
        val mutex = RunMutex()
        val ids = FixedOperationIdSource()
        val p1 = ApplyProtocol(writer, store, FakeClock, ids, faults, mutex)
        val p2 = ApplyProtocol(writer, store, FakeClock, FixedOperationIdSource(listOf("33333333333333333333333333333333")), faults, mutex)
        // Hold the mutex from a different run-id to simulate a concurrent apply.
        assertTrue(mutex.tryAcquire(app.lawnchair.organizer.application.public.RunId("44444444444444444444444444444444")))
        val result = p2.apply(mutatingPlan())
        assertEquals(ApplyResult.ConcurrentRun, result)
    }

    @Test
    fun sa24WriterLeaseBusyReturnsRejectedWriterBusy() {
        writer.refuseLease = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.WRITER_BUSY, (result as ApplyResult.Rejected).reason)
    }

    @Test
    fun sa15LockStateUnavailableIsRejectedBeforeWrite() {
        // UNKNOWN lock state fails closed.
        val lockedUnknown = CanonicalFixtures.appItem(lockState = app.lawnchair.organizer.application.public.OrganizerLockState.UNKNOWN)
        writer.setCurrentState(CanonicalFixtures.state(items = listOf(lockedUnknown)))
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.LOCK_STATE_UNAVAILABLE,
            (result as ApplyResult.Rejected).reason,
        )
    }

    @Test
    fun recoveryStoreUnavailableAfterCheckpointYieldsRecoveryFailed() {
        store.advanceFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.RecoveryFailed)
    }

    @Test
    fun serializationContentionFaultInjectorReturnsWriterBusy() {
        faults.serializationContention = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.WRITER_BUSY, (result as ApplyResult.Rejected).reason)
    }

    @Test
    fun threeUnresolvedPointsRejectCheckpointWithoutLauncherWrite() {
        val capture = writer.captureCurrent(CaptureId("seed"))
        repeat(3) { index ->
            val pointId = RecoveryPointId(index.toString().padStart(32, 'a'))
            val checkpoint = store.checkpoint(
                RecoveryStorePort.CheckpointPayload(
                    pointId,
                    app.lawnchair.organizer.application.public.RunId(index.toString().padStart(32, 'b')),
                    capture.manifest,
                    capture.revision,
                    capture.digest,
                    ByteArray(32) { index.toByte() },
                    capture.manifest.rowCount,
                    capture.manifest.resources.size,
                ),
            )
            assertTrue(checkpoint is RecoveryStorePort.CheckpointResult.Ready)
            assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        }

        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.RECOVERY_POINT_ADMISSION_BLOCKED,
            (result as ApplyResult.Rejected).reason,
        )
        assertEquals(0, writer.appliedWriteSets)
    }

    @Test
    fun threeRestoredPointsAreTombstonedAndAllowFourthCheckpoint() {
        val capture = writer.captureCurrent(CaptureId("seed"))
        val restoredIds = listOf(
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"),
            RecoveryPointId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa3"),
        )
        val restoredDeadlines = restoredIds.mapIndexed { index, pointId ->
            val checkpoint = store.checkpoint(
                RecoveryStorePort.CheckpointPayload(
                    pointId = pointId,
                    runId = app.lawnchair.organizer.application.public.RunId(
                        index.toString().padStart(32, 'b'),
                    ),
                    preManifest = capture.manifest,
                    preRevision = capture.revision,
                    preDigest = capture.digest,
                    applyActionDigest = ByteArray(32) { index.toByte() },
                    itemCount = capture.manifest.rowCount,
                    resourceCount = capture.manifest.resources.size,
                ),
            )
            assertTrue(checkpoint is RecoveryStorePort.CheckpointResult.Ready)
            assertTrue(store.advance(pointId, LifecycleState.APPLYING))
            assertTrue(
                store.markRestoring(
                    pointId,
                    capture.manifest,
                    capture.digest,
                    capture.digest,
                ),
            )
            assertTrue(store.advance(pointId, LifecycleState.RESTORED))
            val record = (store.readRecord(pointId) as RecoveryStorePort.RecordRead.Readable).record
            record.updatedAtMs + app.lawnchair.organizer.application.lifecycle.RetentionPolicy.TOMBSTONE_RETENTION_MILLIS
        }

        val fourthPoint = RecoveryPointId("ddddddddddddddddddddddddddddddd4")
        val fourth = store.checkpoint(
            RecoveryStorePort.CheckpointPayload(
                pointId = fourthPoint,
                runId = app.lawnchair.organizer.application.public.RunId("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"),
                preManifest = capture.manifest,
                preRevision = capture.revision,
                preDigest = capture.digest,
                applyActionDigest = ByteArray(32),
                itemCount = capture.manifest.rowCount,
                resourceCount = capture.manifest.resources.size,
            ),
        )

        assertTrue("Fourth checkpoint must be admitted: $fourth", fourth is RecoveryStorePort.CheckpointResult.Ready)
        restoredIds.forEachIndexed { index, pointId ->
            assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
            val tombstone = store.readTombstone(pointId)
            assertEquals(RecoveryStorePort.TombstoneReason.ALREADY_RESTORED, tombstone?.reason)
            assertEquals(restoredDeadlines[index], tombstone?.expiresAtMs)
        }
    }

    // --- Finding 6: per-dimension A5 race tests (SA-04) ---

    @Test fun sa04ItemCellChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(9, 9)))),
    )

    @Test fun sa04ProfileAvailabilityChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            profiles = listOf(CanonicalFixtures.profile("personal", ProfileAvailability.UNAVAILABLE)),
            items = listOf(CanonicalFixtures.appItem()),
        ),
    )

    @Test fun sa04ProfileInventoryChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            profiles = listOf(
                CanonicalFixtures.profile("personal", ProfileAvailability.AVAILABLE),
                CanonicalFixtures.profile("work", ProfileAvailability.AVAILABLE),
            ),
            items = listOf(CanonicalFixtures.appItem()),
        ),
    )

    @Test fun sa04DeviceCapabilitiesChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            device = CanonicalFixtures.deviceCapabilities(columns = 5),
            items = listOf(CanonicalFixtures.appItem()),
        ),
    )

    @Test fun sa04LockStateChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            items = listOf(
                CanonicalFixtures.appItem(lockState = app.lawnchair.organizer.application.public.OrganizerLockState.LOCKED),
            ),
        ),
    )

    @Test fun sa04WidgetMetadataChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            items = listOf(
                CanonicalFixtures.widgetItem(restored = 0, itemId = "widget.1"),
            ),
        ),
    )

    @Test fun sa04FolderStructureChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            items = listOf(
                CanonicalFixtures.appItem(
                    kind = app.lawnchair.organizer.application.public.CanonicalItemKind.Folder,
                    structure = app.lawnchair.organizer.application.public.StructureState.FolderMembers(
                        listOf(
                            app.lawnchair.organizer.application.public.RankedMember(
                                app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                                    app.lawnchair.organizer.planning.ItemId("app.b"),
                                ),
                                0,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test fun sa04AppPairStructureChangedA5Rejects() = assertA5RaceRejected(
        CanonicalFixtures.state(
            items = listOf(
                CanonicalFixtures.appItem(
                    itemId = "pair.a",
                    kind = app.lawnchair.organizer.application.public.CanonicalItemKind.AppPair,
                    structure = CanonicalFixtures.appPairStructure(
                        app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                            app.lawnchair.organizer.planning.ItemId("child.a"),
                        ),
                        app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
                            app.lawnchair.organizer.planning.ItemId("child.b"),
                        ),
                    ),
                ),
            ),
        ),
    )

    // --- AC-1: extended per-dimension A5 race tests (shared matrix) ---

    @Test
    fun allPersistedDimensionsRejectAtA2Capture() {
        for (dimension in RevisionRaceDimensions.all) {
            writer.setCurrentState(dimension.mutated)
            val result = protocol.apply(mutatingPlan(dimension.source))
            assertTrue("Expected Rejected for ${dimension.name}, got $result", result is ApplyResult.Rejected)
            assertEquals(
                "Expected STALE_REVISION for ${dimension.name}",
                PreWriteRejection.STALE_REVISION,
                (result as ApplyResult.Rejected).reason,
            )
            assertEquals("Zero committed writes for ${dimension.name}", 0, writer.appliedWriteSets)
        }
    }

    @Test
    fun allPersistedDimensionsRejectAtA5TransactionReread() {
        RevisionRaceDimensions.all.forEach(::assertA5RaceRejectedDim)
    }

    @Test fun sa04PageAddedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "page added" })

    @Test fun sa04PageOrderChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "page order changed" })

    @Test fun sa04PageRemovedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "page removed" })

    @Test fun sa04SpanChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "span changed" })

    @Test fun sa04DockRankChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "dock rank changed" })

    @Test fun sa04ContainerChangedToDockA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "container changed to dock" })

    @Test fun sa04ContainerChangedToFolderChildA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "container changed to folder child" })

    @Test fun sa04TargetComponentChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "target component changed" })

    @Test fun sa04TargetProfileChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "target profile changed" })

    @Test fun sa04ItemAvailabilityChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "item availability changed" })

    @Test fun sa04WidgetProviderChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "widget provider changed" })

    @Test fun sa04WidgetAppWidgetIdChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "widget appWidgetId changed" })

    @Test fun sa04WidgetOptionsChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "widget options changed" })

    @Test fun sa04WidgetSourceChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "widget source changed" })

    @Test fun sa04TitleChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "title changed" })

    @Test fun sa04IntentChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "intent changed" })

    @Test fun sa04IconChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "icon changed" })

    @Test fun sa04ModifiedChangedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "modified changed" })

    @Test fun sa04ItemAddedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "item added" })

    @Test fun sa04ItemRemovedA5Rejects() = assertA5RaceRejectedDim(RevisionRaceDimensions.all.first { it.name == "item removed" })

    private fun assertA5RaceRejected(mutated: app.lawnchair.organizer.application.public.LayoutState) {
        val plan = mutatingPlan()
        writer.onApplyA5Reread = { writer.setCurrentState(mutated) }
        val result = protocol.apply(plan)
        assertTrue("Expected Rejected, got $result", result is ApplyResult.Rejected)
        val rejected = result as ApplyResult.Rejected
        assertTrue(
            rejected.reason == PreWriteRejection.STALE_REVISION ||
                rejected.reason == PreWriteRejection.EXACT_PRECONDITION_FAILED,
        )
        assertEquals(0, writer.appliedWriteSets)
    }

    private fun assertA5RaceRejectedDim(dim: RevisionRaceDimensions.Dimension) {
        writer.setCurrentState(dim.source)
        val plan = mutatingPlan(dim.source)
        writer.onApplyA5Reread = { writer.setCurrentState(dim.mutated) }
        val result = protocol.apply(plan)
        assertTrue("Expected Rejected for ${dim.name}, got $result", result is ApplyResult.Rejected)
        assertEquals(
            "Expected STALE_REVISION for ${dim.name}",
            PreWriteRejection.STALE_REVISION,
            (result as ApplyResult.Rejected).reason,
        )
        assertEquals("Zero committed writes for ${dim.name}", 0, writer.appliedWriteSets)
    }

    // --- Finding 5a: AC-14 lifecycle fault matrix ---

    @Test fun ac14CheckpointCreateFailsReturnsRejected() {
        store.checkpointCreateFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.CHECKPOINT_CREATE_FAILED, (result as ApplyResult.Rejected).reason)
    }

    @Test fun ac14CheckpointValidateFailsReturnsRejected() {
        store.checkpointValidateFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.CHECKPOINT_VALIDATE_FAILED, (result as ApplyResult.Rejected).reason)
    }

    @Test fun ac14MarkApplyingFailsReturnsRejected() {
        store.markApplyingFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(
            PreWriteRejection.RECOVERY_STORE_UNAVAILABLE,
            (result as ApplyResult.Rejected).reason,
        )
    }

    @Test fun ac14AdvanceCommittedUnverifiedFailsTriggersRecovery() {
        store.advanceFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.RecoveryFailed)
    }

    @Test fun ac14AdvanceVerifiedFailsTriggersRecovery() {
        store.advanceFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.RecoveryFailed)
    }

    @Test fun sa04A5StaleRejectionPrunesUnusedCheckpoint() {
        writer.onApplyA5Reread = {
            writer.setCurrentState(
                CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(9, 9)))),
            )
        }
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Rejected)
        assertEquals(PreWriteRejection.STALE_REVISION, (result as ApplyResult.Rejected).reason)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(RecoveryPointId("22222222222222222222222222222222")))
    }

    @Test fun sa04A5StaleAdvanceFailureSurfacesRecoveryStoreFailure() {
        writer.onApplyA5Reread = {
            writer.setCurrentState(
                CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(9, 9)))),
            )
        }
        store.advanceFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Unresolved)
        assertEquals(ApplyFailure.RECOVERY_STORE_FAILED, (result as ApplyResult.Unresolved).failure)
        assertEquals(
            LifecycleState.APPLYING,
            storedLifecycleOf(RecoveryPointId("22222222222222222222222222222222")),
        )
    }

    @Test fun sa04A5StalePruneFailureSurfacesRecoveryStoreFailure() {
        writer.onApplyA5Reread = {
            writer.setCurrentState(
                CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(9, 9)))),
            )
        }
        store.pruneUnusedFails = true
        val result = protocol.apply(mutatingPlan())
        assertTrue(result is ApplyResult.Unresolved)
        assertEquals(ApplyFailure.RECOVERY_STORE_FAILED, (result as ApplyResult.Unresolved).failure)
        assertEquals(
            LifecycleState.READY,
            storedLifecycleOf(RecoveryPointId("22222222222222222222222222222222")),
        )
    }

    private fun mutatingPlan(): ValidatedLayoutPlan {
        val sourceState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(0, 0))))
        val intendedState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = GridCell(1, 1))))
        val ref = app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem(
            app.lawnchair.organizer.planning.ItemId("app.a"),
        )
        val action = ApplyAction.Update(
            ref = ref,
            expected = CanonicalFixtures.appItem(cell = GridCell(0, 0)),
            intended = CanonicalFixtures.appItem(cell = GridCell(1, 1)),
        )
        return ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = listOf(action),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }

    private fun mutatingPlan(sourceState: app.lawnchair.organizer.application.public.LayoutState): ValidatedLayoutPlan {
        val sourceItem = sourceState.items.first()
        val intendedItem = sourceItem.copy(title = OptionalText.Present("Z"))
        val intendedState = sourceState.copy(items = listOf(intendedItem))
        val action = ApplyAction.Update(
            ref = sourceItem.ref,
            expected = sourceItem,
            intended = intendedItem,
        )
        return ValidatedLayoutPlan(
            sourceRevision = app.lawnchair.organizer.application.revision.RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = listOf(action),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }
}
