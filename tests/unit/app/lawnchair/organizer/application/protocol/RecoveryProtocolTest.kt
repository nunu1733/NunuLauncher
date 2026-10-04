package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AC-8, AC-10, AC-15 plus SA-15..SA-22 recovery coverage. Pure JVM.
 *
 * Issue #14 Stage B step 4.
 */
class RecoveryProtocolTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var faults: RecordingFaultInjector
    private lateinit var protocol: RecoveryProtocol

    private fun storedLifecycleOf(id: RecoveryPointId): LifecycleState? = (
        store.readRecord(id) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.lifecycle

    private val pointId = RecoveryPointId("22222222222222222222222222222222")
    private val runId = RunId("11111111111111111111111111111111")

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        faults = RecordingFaultInjector()
        val mutex = RunMutex()
        protocol = RecoveryProtocol(writer, store, FakeClock, FixedOperationIdSource(), faults, mutex)
    }

    @Test
    fun sa16RecoveryWithMatchingReviewedRevisionRestoresPreState() {
        seedVerifiedPoint()
        val request = RecoveryRequest(
            pointId = pointId,
            expectedCurrentRevision = RevisionCalculator.revisionOf(writer.currentState()),
        )
        val result = protocol.recover(request)
        assertTrue("Expected Restored, got $result", result is RecoveryResult.Restored)
    }

    @Test
    fun sa17RecoveryWithStaleReviewedRevisionReturnsStaleRejection() {
        seedVerifiedPoint()
        val request = RecoveryRequest(
            pointId = pointId,
            expectedCurrentRevision = RevisionId("0".repeat(32)),
        )
        val result = protocol.recover(request)
        assertTrue(result is RecoveryResult.NotRestorable)
        assertEquals(RecoveryRejection.STALE_REVISION, (result as RecoveryResult.NotRestorable).reason)
    }

    @Test
    fun sa21RepeatRecoveryReturnsAlreadyRestored() {
        seedVerifiedPoint()
        val request = matchingRequest()
        val first = protocol.recover(request)
        assertTrue("First recovery must succeed: $first", first is RecoveryResult.Restored)
        val second = protocol.recover(request)
        assertTrue(second is RecoveryResult.NotRestorable)
        assertEquals(RecoveryRejection.ALREADY_RESTORED, (second as RecoveryResult.NotRestorable).reason)
    }

    @Test
    fun sa22MissingPointReturnsMissing() {
        val request = RecoveryRequest(
            pointId = RecoveryPointId("99999999999999999999999999999999"),
            expectedCurrentRevision = RevisionCalculator.revisionOf(writer.currentState()),
        )
        val result = protocol.recover(request)
        assertTrue(result is RecoveryResult.NotRestorable)
        assertEquals(RecoveryRejection.MISSING, (result as RecoveryResult.NotRestorable).reason)
    }

    @Test
    fun sa22TombstonesReturnTheirExactPublicReasons() {
        val cases = listOf(
            RecoveryStorePort.TombstoneReason.EXPIRED to RecoveryRejection.EXPIRED,
            RecoveryStorePort.TombstoneReason.CORRUPT to RecoveryRejection.CORRUPT,
            RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION to RecoveryRejection.INCOMPATIBLE_VERSION,
            RecoveryStorePort.TombstoneReason.ALREADY_RESTORED to RecoveryRejection.ALREADY_RESTORED,
            RecoveryStorePort.TombstoneReason.PRUNED_UNUSED to RecoveryRejection.MISSING,
        )
        cases.forEachIndexed { index, (storedReason, publicReason) ->
            val id = RecoveryPointId((index + 3).toString().repeat(32))
            store.seedTombstone(RecoveryStorePort.Tombstone(id, storedReason, Long.MAX_VALUE))

            val result = protocol.recover(
                RecoveryRequest(id, RevisionCalculator.revisionOf(writer.currentState())),
            )

            assertTrue("Expected NotRestorable for $storedReason, got $result", result is RecoveryResult.NotRestorable)
            assertEquals(publicReason, (result as RecoveryResult.NotRestorable).reason)
        }
    }

    @Test
    fun sa22UnavailableStoreReturnsTypedOutcomeBeforeReadingPoint() {
        store.storeAvailability = RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION
        val incompatible = protocol.recover(matchingRequest())
        assertTrue(incompatible is RecoveryResult.NotRestorable)
        assertEquals(
            RecoveryRejection.INCOMPATIBLE_VERSION,
            (incompatible as RecoveryResult.NotRestorable).reason,
        )

        store.storeAvailability = RecoveryStorePort.StoreAvailability.READ_FAILED
        val failed = protocol.recover(matchingRequest())
        assertTrue(failed is RecoveryResult.RestoreFailed)
        assertEquals(RecoveryFailure.RECOVERY_STORE_FAILED, (failed as RecoveryResult.RestoreFailed).failure)
    }

    @Test
    fun sa22CorruptPointReturnsCorrupt() {
        seedVerifiedPoint(checksumValid = false)
        val result = protocol.recover(matchingRequest())
        assertTrue(result is RecoveryResult.NotRestorable)
        assertEquals(RecoveryRejection.CORRUPT, (result as RecoveryResult.NotRestorable).reason)
    }

    @Test
    fun sa22IncompatibleFormatReturnsIncompatible() {
        seedVerifiedPoint(formatVersion = 999)
        val result = protocol.recover(matchingRequest())
        assertTrue(result is RecoveryResult.NotRestorable)
        assertEquals(
            RecoveryRejection.INCOMPATIBLE_VERSION,
            (result as RecoveryResult.NotRestorable).reason,
        )
    }

    @Test
    fun sa15LockStateUnavailableIsNotRestorableBeforeMutation() {
        seedVerifiedPoint()
        val lockedUnknown = CanonicalFixtures.appItem(lockState = OrganizerLockState.UNKNOWN)
        writer.setCurrentState(CanonicalFixtures.state(items = listOf(lockedUnknown)))
        val result = protocol.recover(matchingRequest())
        assertTrue(result is RecoveryResult.NotRestorable)
        assertEquals(
            RecoveryRejection.LOCK_STATE_UNAVAILABLE,
            (result as RecoveryResult.NotRestorable).reason,
        )
    }

    @Test
    fun sa23ConcurrentRecoveryIsRejected() {
        seedVerifiedPoint()
        val mutex = RunMutex()
        val ids = FixedOperationIdSource()
        val p = RecoveryProtocol(writer, store, FakeClock, ids, faults, mutex)
        assertTrue(mutex.tryAcquire(RunId("44444444444444444444444444444444")))
        val result = p.recover(matchingRequest())
        assertEquals(RecoveryResult.ConcurrentRun, result)
    }

    @Test
    fun sa24RecoveryWriterBusyReturnsWriterBusyWithoutMutation() {
        seedVerifiedPoint()
        writer.refuseLease = true
        val result = protocol.recover(matchingRequest())
        assertEquals(RecoveryResult.WriterBusy, result)
    }

    @Test
    fun recoveryFailedWhenReloadFailsAfterCommit() {
        seedVerifiedPoint()
        writer.reloadResult = ReloadResult.Failed
        val result = protocol.recover(matchingRequest())
        assertTrue(
            "Expected RestoreFailed on reload failure: $result",
            result is RecoveryResult.RestoreFailed,
        )
        assertEquals(
            RecoveryFailure.MODEL_RELOAD_FAILED,
            (result as RecoveryResult.RestoreFailed).failure,
        )
    }

    // Issue #152 (AC-152-03/04): explicit recovery must verify the model leg.
    // The DB recapture still matches the checkpoint pre-state (echo semantics);
    // a divergent model generation of the recovery reload must fail closed.

    @Test
    fun recoveryFailedWhenModelSnapshotDivergesFromDb() {
        seedVerifiedPoint()
        writer.modelSnapshotTransform = { snapshot -> snapshot.copy(items = snapshot.items.dropLast(1)) }
        val result = protocol.recover(matchingRequest())
        assertTrue(
            "Expected RestoreFailed on model divergence: $result",
            result is RecoveryResult.RestoreFailed,
        )
        assertEquals(
            RecoveryFailure.VERIFICATION_FAILED,
            (result as RecoveryResult.RestoreFailed).failure,
        )
        assertEquals(
            "A model-divergent recovery must never reach RESTORED",
            LifecycleState.RESTORING,
            storedLifecycleOf(pointId),
        )
    }

    @Test
    fun recoverySupersededReloadIsNotFalseSuccess() {
        seedVerifiedPoint()
        writer.reloadResult = ReloadResult.Superseded
        val result = protocol.recover(matchingRequest())
        assertTrue(
            "Expected RestoreFailed on supersession: $result",
            result is RecoveryResult.RestoreFailed,
        )
        assertEquals(
            RecoveryFailure.MODEL_RELOAD_FAILED,
            (result as RecoveryResult.RestoreFailed).failure,
        )
    }

    @Test
    fun recoveryFailedWhenVerificationFails() {
        seedVerifiedPoint()
        writer.nextTxOutcome = ApplyTxOutcome.Failed(RuntimeException("injected recovery failure"))
        val result = protocol.recover(matchingRequest())
        assertEquals(RecoveryResult.Restored(pointId), result)
    }

    // --- AC-1: per-dimension recovery stale revision matrix (shared matrix) ---

    @Test
    fun allPersistedDimensionsRejectAtRecoveryTransactionReread() {
        RevisionRaceDimensions.all.forEach(::assertRecoveryStaleRejection)
    }

    @Test fun recoveryStalePageAddedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "page added" })

    @Test fun recoveryStalePageOrderChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "page order changed" })

    @Test fun recoveryStalePageRemovedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "page removed" })

    @Test fun recoveryStaleSpanChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "span changed" })

    @Test fun recoveryStaleDockRankChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "dock rank changed" })

    @Test fun recoveryStaleContainerChangedToDockReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "container changed to dock" })

    @Test fun recoveryStaleContainerChangedToFolderChildReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "container changed to folder child" })

    @Test fun recoveryStaleTargetComponentChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "target component changed" })

    @Test fun recoveryStaleTargetProfileChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "target profile changed" })

    @Test fun recoveryStaleItemAvailabilityChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "item availability changed" })

    @Test fun recoveryStaleWidgetProviderChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "widget provider changed" })

    @Test fun recoveryStaleWidgetAppWidgetIdChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "widget appWidgetId changed" })

    @Test fun recoveryStaleWidgetOptionsChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "widget options changed" })

    @Test fun recoveryStaleWidgetSourceChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "widget source changed" })

    @Test fun recoveryStaleTitleChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "title changed" })

    @Test fun recoveryStaleIntentChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "intent changed" })

    @Test fun recoveryStaleIconChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "icon changed" })

    @Test fun recoveryStaleModifiedChangedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "modified changed" })

    @Test fun recoveryStaleItemAddedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "item added" })

    @Test fun recoveryStaleItemRemovedReturnsStaleRejection() = assertRecoveryStaleRejection(RevisionRaceDimensions.all.first { it.name == "item removed" })

    private fun assertRecoveryStaleRejection(dim: RevisionRaceDimensions.Dimension) {
        writer.setCurrentState(dim.source)
        seedVerifiedPoint()
        writer.onApplyA5Reread = { writer.setCurrentState(dim.mutated) }
        val request = RecoveryRequest(
            pointId = pointId,
            expectedCurrentRevision = RevisionCalculator.revisionOf(dim.source),
        )
        val result = protocol.recover(request)
        assertTrue("Expected RestoreFailed for ${dim.name}, got $result", result is RecoveryResult.RestoreFailed)
        assertEquals(
            "Expected WRITE_FAILED for ${dim.name}",
            RecoveryFailure.WRITE_FAILED,
            (result as RecoveryResult.RestoreFailed).failure,
        )
        assertEquals(AuthoritativeState.UNKNOWN, result.authoritativeState)
        assertEquals(LifecycleState.RESTORING, storedLifecycleOf(pointId))
        assertEquals("Zero committed writes for ${dim.name}", 0, writer.appliedWriteSets)
    }

    // --- Finding 5a: AC-14 lifecycle fault matrix ---

    @Test fun ac14RecoveryStoreReadFailedReturnsRestoreFailed() {
        store.storeAvailability = RecoveryStorePort.StoreAvailability.READ_FAILED
        val result = protocol.recover(matchingRequest())
        assertTrue(result is RecoveryResult.RestoreFailed)
        val r = result as RecoveryResult.RestoreFailed
        assertEquals(RecoveryFailure.RECOVERY_STORE_FAILED, r.failure)
    }

    @Test fun ac14MarkRestoringFailsReturnsRestoreFailed() {
        seedVerifiedPoint()
        store.markRestoringFails = true
        val result = protocol.recover(matchingRequest())
        assertTrue(result is RecoveryResult.RestoreFailed)
        val r = result as RecoveryResult.RestoreFailed
        assertEquals(RecoveryFailure.RECOVERY_STORE_FAILED, r.failure)
    }

    @Test fun ac14AdvanceRestoredFailsReturnsRestoreFailed() {
        seedVerifiedPoint()
        store.advanceFails = true
        val result = protocol.recover(matchingRequest())
        assertTrue(result is RecoveryResult.RestoreFailed)
    }

    private fun matchingRequest() = RecoveryRequest(
        pointId = pointId,
        expectedCurrentRevision = RevisionCalculator.revisionOf(writer.currentState()),
    )

    private fun seedVerifiedPoint(
        checksumValid: Boolean = true,
        formatVersion: Int = LifecycleReconciler.SUPPORTED_FORMAT,
    ) {
        val preState = writer.currentState()
        val preManifest = PersistenceManifest(
            formatVersion = 1,
            schemaVersion = 33,
            rowCount = preState.items.size,
            rows = preState.items.mapIndexed { index, item ->
                PersistentRow(
                    rowId = (index + 1).toLong(),
                    itemId = ItemId("item-$index"),
                    profileId = item.profile,
                    containerCode = ContainerCode(0),
                    screenId = null,
                    cellX = 0,
                    cellY = 0,
                    spanX = 1,
                    spanY = 1,
                    rank = 0,
                    itemType = KindCode(0),
                    appWidgetId = null,
                    appWidgetProvider = null,
                    iconBytes = null,
                    title = null,
                    intent = null,
                    restored = null,
                    options = null,
                    appWidgetSource = null,
                    modified = 0L,
                    organizerLockState = item.lockState,
                    rawCell = null,
                    rawSpan = null,
                )
            },
            resources = preState.pages.mapIndexed { index, _ ->
                PersistentResource(
                    kind = PersistentResourceKind.WORKSPACE_SCREEN,
                    profileId = ProfileId("personal"),
                    order = index.toLong(),
                    payload = byteArrayOf(index.toByte()),
                )
            },
            modifiedAtMillis = 0L,
        )
        val preRevision = RevisionCalculator.revisionOf(preState)
        val preDigest = RevisionCalculator.classificationDigestOf(preState)
        val stored = object : app.lawnchair.organizer.application.protocol.RecoveryStorePort.StoredRecord {
            override val pointId: RecoveryPointId get() = this@RecoveryProtocolTest.pointId
            override val runId: RunId get() = this@RecoveryProtocolTest.runId
            override val lifecycle: LifecycleState = LifecycleState.VERIFIED
            override val priorLifecycle: LifecycleState? = null
            override val createdAtMs: Long = 0L
            override val updatedAtMs: Long = 0L
            override val preManifest: PersistenceManifest = preManifest
            override val preRevision: RevisionId = preRevision
            override val preDigest: ByteArray = preDigest
            override val intendedManifest: PersistenceManifest = preManifest
            override val intendedDigest: ByteArray = preDigest
            override val applyActionDigest: ByteArray = preDigest
            override val reviewedManifest: PersistenceManifest? = null
            override val reviewedDigest: ByteArray? = null
            override val recoveryActionDigest: ByteArray? = null
            override val itemCount: Int = preState.items.size
            override val resourceCount: Int = preState.pages.size
            override val checksumValid: Boolean = checksumValid
            override val formatVersion: Int = formatVersion
        }
        store.seedRecord(stored)
    }

    @Test
    fun unreadablePreservedRecordIsNotRestorableCorruptNeverMissingOrStoreFailure() {
        seedVerifiedPoint()
        store.unreadablePointIds.add(pointId.value)

        val result = protocol.recover(matchingRequest())

        assertTrue("Expected NotRestorable, got $result", result is RecoveryResult.NotRestorable)
        assertEquals(RecoveryRejection.CORRUPT, (result as RecoveryResult.NotRestorable).reason)
    }

    @Test
    fun failedPointReadReturnsRestoreFailedStoreFailure() {
        seedVerifiedPoint()
        store.failedReadPointIds.add(pointId.value)

        val result = protocol.recover(matchingRequest())

        assertTrue("Expected RestoreFailed, got $result", result is RecoveryResult.RestoreFailed)
        assertEquals(
            RecoveryFailure.RECOVERY_STORE_FAILED,
            (result as RecoveryResult.RestoreFailed).failure,
        )
    }

    @Test
    fun quarantinedTombstoneReturnsCorruptRejection() {
        store.seedTombstone(
            RecoveryStorePort.Tombstone(
                pointId,
                RecoveryStorePort.TombstoneReason.QUARANTINED,
                FakeClock.nowMillis() + 1_000L,
            ),
        )

        val result = protocol.recover(matchingRequest())

        assertTrue("Expected NotRestorable, got $result", result is RecoveryResult.NotRestorable)
        assertEquals(RecoveryRejection.CORRUPT, (result as RecoveryResult.NotRestorable).reason)
    }
}
