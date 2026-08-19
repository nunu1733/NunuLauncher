package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewEffect
import app.lawnchair.organizer.application.public.RecoveryPreviewRejection
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewUnavailable
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.RevisionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #84 read-only recovery-preview contract.
 *
 * Every test asserts that inspection does not enter a recovery mutation path.
 */
class RecoveryPreviewProtocolTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var faults: RecordingFaultInjector
    private lateinit var mutex: RunMutex
    private lateinit var protocol: RecoveryPreviewProtocol
    private val pointId = RecoveryPointId("22222222222222222222222222222222")
    private val runId = RunId("11111111111111111111111111111111")

    @Before
    fun setUp() {
        FakeClock.set(1_000L)
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        faults = RecordingFaultInjector()
        mutex = RunMutex()
        protocol = RecoveryPreviewProtocol(writer, store, FakeClock, FixedOperationIdSource(), faults, mutex)
    }

    @Test
    fun verifiedUnexpiredPointReturnsSafeRestorablePreviewWithoutMutation() {
        seedRecord(LifecycleState.VERIFIED)

        val result = protocol.inspect(pointId)

        assertTrue("Expected Restorable, got $result", result is RecoveryPreviewResult.Restorable)
        val restorable = result as RecoveryPreviewResult.Restorable
        assertEquals(pointId, restorable.pointId)
        assertEquals(RecoveryPreviewEffect.RESTORE_SAVED_LAYOUT, restorable.summary.effect)
        assertTrue(restorable.summary.confirmationRequired)
        assertTrue(restorable.summary.conditionalOnCurrentRevision)
        assertEquals(1, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    @Test
    fun agedVerifiedPointReturnsExpiredWithoutCaptureOrRetentionWrite() {
        seedRecord(LifecycleState.VERIFIED, createdAtMs = 0L)
        FakeClock.set(RetentionPolicy.RETENTION_MILLIS)

        val result = protocol.inspect(pointId)

        assertEquals(
            RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.EXPIRED),
            result,
        )
        assertEquals(0, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    @Test
    fun expiredTombstoneReturnsMissingWithoutLazyPurge() {
        store.seedTombstone(
            RecoveryStorePort.Tombstone(
                pointId,
                RecoveryStorePort.TombstoneReason.EXPIRED,
                FakeClock.nowMillis(),
            ),
        )

        val result = protocol.inspect(pointId)

        assertEquals(
            RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.MISSING),
            result,
        )
        assertEquals(1, store.inspectionTombstoneReads)
        assertEquals(0, store.maintenanceTombstoneReads)
        assertEquals(0, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    @Test
    fun activeLifecycleReturnsUnresolvedWithoutCaptureOrMutation() {
        seedRecord(LifecycleState.APPLYING)

        val result = protocol.inspect(pointId)

        assertEquals(
            RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.UNRESOLVED),
            result,
        )
        assertEquals(0, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    @Test
    fun readFailureReturnsUnavailableWithoutStoreOrWriterMutation() {
        store.storeAvailability = RecoveryStorePort.StoreAvailability.READ_FAILED

        val result = protocol.inspect(pointId)

        assertEquals(
            RecoveryPreviewResult.Unavailable(pointId, RecoveryPreviewUnavailable.RECOVERY_STORE_UNAVAILABLE),
            result,
        )
        assertEquals(0, store.inspectionTombstoneReads)
        assertEquals(0, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    @Test
    fun writerContentionReturnsBusyWithoutCaptureOrMutation() {
        seedRecord(LifecycleState.VERIFIED)
        writer.refuseLease = true

        val result = protocol.inspect(pointId)

        assertEquals(RecoveryPreviewResult.WriterBusy, result)
        assertEquals(0, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    @Test
    fun heldRunMutexReturnsConcurrentWithoutAnyReadOrMutation() {
        seedRecord(LifecycleState.VERIFIED)
        assertTrue(mutex.tryAcquire(RunId("33333333333333333333333333333333")))

        val result = protocol.inspect(pointId)

        assertEquals(RecoveryPreviewResult.Concurrent, result)
        assertEquals(0, writer.capturedSnapshots)
        assertEquals(0, store.inspectionTombstoneReads)
        assertNoInspectionMutation()
    }

    @Test
    fun unknownLockStateReturnsTypedRejectionWithoutMutation() {
        seedRecord(LifecycleState.VERIFIED)
        writer.setCurrentState(
            CanonicalFixtures.state(
                items = listOf(CanonicalFixtures.appItem(lockState = OrganizerLockState.UNKNOWN)),
            ),
        )

        val result = protocol.inspect(pointId)

        assertEquals(
            RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.LOCK_STATE_UNAVAILABLE),
            result,
        )
        assertEquals(1, writer.capturedSnapshots)
        assertNoInspectionMutation()
    }

    private fun assertNoInspectionMutation() {
        assertEquals(0, store.checkpointPointIds.size)
        assertEquals(0, store.markRestoringCalls)
        assertEquals(0, store.advanceCalls)
        assertEquals(0, store.pruneUnusedCalls)
        assertEquals(0, store.retentionCalls)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
        assertEquals(0, writer.recaptureCount)
    }

    private fun seedRecord(
        lifecycle: LifecycleState,
        createdAtMs: Long = FakeClock.nowMillis(),
        updatedAtMs: Long = FakeClock.nowMillis(),
    ) {
        val state = writer.currentState()
        val manifest = PersistenceManifest(
            formatVersion = 1,
            schemaVersion = 33,
            rowCount = 0,
            rows = emptyList(),
            resources = emptyList(),
            modifiedAtMillis = 0L,
        )
        val revision = RevisionCalculator.revisionOf(state)
        val digest = RevisionCalculator.classificationDigestOf(state)
        store.seedRecord(
            object : RecoveryStorePort.StoredRecord {
                override val pointId: RecoveryPointId get() = this@RecoveryPreviewProtocolTest.pointId
                override val runId: RunId get() = this@RecoveryPreviewProtocolTest.runId
                override val lifecycle: LifecycleState = lifecycle
                override val priorLifecycle: LifecycleState? = null
                override val createdAtMs: Long = createdAtMs
                override val updatedAtMs: Long = updatedAtMs
                override val preManifest: PersistenceManifest = manifest
                override val preRevision: RevisionId = revision
                override val preDigest: ByteArray = digest
                override val intendedManifest: PersistenceManifest = manifest
                override val intendedDigest: ByteArray = digest
                override val applyActionDigest: ByteArray = digest
                override val reviewedManifest: PersistenceManifest? = null
                override val reviewedDigest: ByteArray? = null
                override val recoveryActionDigest: ByteArray? = null
                override val itemCount: Int = 0
                override val resourceCount: Int = 0
                override val checksumValid: Boolean = true
                override val formatVersion: Int = 1
            },
        )
    }
}
