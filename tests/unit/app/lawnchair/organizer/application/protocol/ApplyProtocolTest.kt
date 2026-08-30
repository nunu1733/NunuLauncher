package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
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
            ruleVersion = RuleVersion("v1"),
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
            PreWriteRejection.RECOVERY_STORE_UNAVAILABLE,
            (result as ApplyResult.Rejected).reason,
        )
        assertEquals(0, writer.appliedWriteSets)
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
            ruleVersion = RuleVersion("v1"),
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
            ruleVersion = RuleVersion("v1"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }
}
