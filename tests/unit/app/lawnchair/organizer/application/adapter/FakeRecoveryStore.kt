package app.lawnchair.organizer.application.adapter

import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.LifecycleTransitions
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationIssuer
import app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationPort
import app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationSession
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [RecoveryStorePort] used by every contract test through the same
 * public seam (AC-13). Models the format-1 lifecycle transitions exactly as
 * the SQLite store does; no Android/SQLite types.
 *
 * Issue #14 Stage B step 4.
 */
class FakeRecoveryStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RecoveryStorePort,
    RecoveryStoreReconciliationPort {

    private val records: ConcurrentHashMap<String, MutableRecord> = ConcurrentHashMap()
    private val tombstones: ConcurrentHashMap<String, RecoveryStorePort.Tombstone> = ConcurrentHashMap()
    private var reconciliationMutex: RunMutex? = null

    var storeAvailability: RecoveryStorePort.StoreAvailability = RecoveryStorePort.StoreAvailability.READY
    var checkpointCreateFails: Boolean = false
    var checkpointCollisionsRemaining: Int = 0
    val checkpointPointIds: MutableList<RecoveryPointId> = mutableListOf()
    var checkpointValidateFails: Boolean = false
    var markApplyingFails: Boolean = false
    var markRestoringFails: Boolean = false
    var advanceFails: Boolean = false
    var pruneUnusedFails: Boolean = false
    var retentionOutcome: RecoveryStorePort.RetentionOutcome = RecoveryStorePort.RetentionOutcome.Applied
    var maintenanceTombstoneReads: Int = 0
        private set
    var inspectionProjectionReads: Int = 0
        private set
    var inspectionReadFails: Boolean = false
    var markRestoringCalls: Int = 0
        private set
    var advanceCalls: Int = 0
        private set
    var pruneUnusedCalls: Int = 0
        private set
    var retentionCalls: Int = 0
        private set

    override fun availability(): RecoveryStorePort.StoreAvailability = storeAvailability

    override fun readTombstone(pointId: RecoveryPointId): RecoveryStorePort.Tombstone? {
        maintenanceTombstoneReads += 1
        return tombstones[pointId.value]
    }

    var snapshotRebuildSucceeds: Boolean = true

    override fun readInspectionProjection(
        pointId: RecoveryPointId,
    ): RecoveryStorePort.InspectionProjectionRead {
        inspectionProjectionReads += 1
        when (storeAvailability) {
            RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION ->
                return RecoveryStorePort.InspectionProjectionRead.Incompatible

            RecoveryStorePort.StoreAvailability.READ_FAILED ->
                return RecoveryStorePort.InspectionProjectionRead.Unavailable

            RecoveryStorePort.StoreAvailability.READY -> Unit
        }
        if (inspectionReadFails) return RecoveryStorePort.InspectionProjectionRead.Unavailable
        records[pointId.value]?.let { record ->
            return RecoveryStorePort.InspectionProjectionRead.Value(
                RecoveryStorePort.InspectionProjection.Record(
                    pointId = record.pointId,
                    lifecycle = record.lifecycle,
                    createdAtMs = record.createdAtMs,
                    updatedAtMs = record.updatedAtMs,
                    checksumValid = record.checksumValid,
                    formatVersion = record.formatVersion,
                ),
            )
        }
        tombstones[pointId.value]?.let { tombstone ->
            return RecoveryStorePort.InspectionProjectionRead.Value(
                RecoveryStorePort.InspectionProjection.Tombstone(
                    pointId = tombstone.pointId,
                    reason = tombstone.reason,
                    expiresAtMs = tombstone.expiresAtMs,
                ),
            )
        }
        return RecoveryStorePort.InspectionProjectionRead.Value(RecoveryStorePort.InspectionProjection.Missing)
    }

    override fun bindReconciliationIssuer(
        mutex: RunMutex,
    ): RecoveryStoreReconciliationIssuer? {
        val bound = reconciliationMutex
        if (bound != null && bound !== mutex) return null
        reconciliationMutex = mutex
        return FakeReconciliationIssuer(mutex)
    }

    private inner class FakeReconciliationIssuer(
        private val mutex: RunMutex,
    ) : RecoveryStoreReconciliationIssuer {
        override fun openSession(
            lease: RunMutex.ReconciliationLease,
        ): RecoveryStoreReconciliationSession? =
            if (lease.isActiveFor(mutex)) FakeReconciliationSession(lease, mutex) else null
    }

    private inner class FakeReconciliationSession(
        private val lease: RunMutex.ReconciliationLease,
        private val mutex: RunMutex,
    ) : RecoveryStoreReconciliationSession {
        private var closed: Boolean = false

        override fun isActive(): Boolean = !closed && lease.isActiveFor(mutex)

        override fun availability(): RecoveryStorePort.StoreAvailability =
            if (isActive()) this@FakeRecoveryStore.availability() else RecoveryStorePort.StoreAvailability.READ_FAILED

        override fun listNonFinalRecords(): List<RecoveryStorePort.StoredRecord>? =
            if (isActive()) this@FakeRecoveryStore.listNonFinalRecords() else null

        override fun readRecord(pointId: RecoveryPointId): RecoveryStorePort.StoredRecord? =
            if (isActive()) this@FakeRecoveryStore.readRecord(pointId) else null

        override fun advance(pointId: RecoveryPointId, next: LifecycleState): Boolean =
            isActive() && this@FakeRecoveryStore.advance(pointId, next)

        override fun markRestoring(
            pointId: RecoveryPointId,
            reviewedManifest: PersistenceManifest,
            reviewedDigest: ByteArray,
            recoveryActionDigest: ByteArray,
        ): Boolean = isActive() && this@FakeRecoveryStore.markRestoring(
            pointId,
            reviewedManifest,
            reviewedDigest,
            recoveryActionDigest,
        )

        override fun pruneUnused(pointId: RecoveryPointId): Boolean =
            isActive() && this@FakeRecoveryStore.pruneUnused(pointId)

        override fun runRetention(nowMillis: Long): RecoveryStorePort.RetentionOutcome =
            if (isActive()) this@FakeRecoveryStore.runRetention(nowMillis) else RecoveryStorePort.RetentionOutcome.StoreUnavailable

        override fun rebuildInspectionSnapshot(): Boolean = isActive() && snapshotRebuildSucceeds

        override fun close() {
            closed = true
        }
    }

    override fun checkpoint(payload: RecoveryStorePort.CheckpointPayload): RecoveryStorePort.CheckpointResult {
        checkpointPointIds += payload.pointId
        if (checkpointCollisionsRemaining > 0) {
            checkpointCollisionsRemaining -= 1
            return RecoveryStorePort.CheckpointResult.PointIdCollision
        }
        if (checkpointCreateFails) {
            return RecoveryStorePort.CheckpointResult.CreateFailed
        }
        val now = clock()
        val existing = records.values.map { record ->
            RetentionPolicy.RetentionRecord(
                record.pointId,
                record.lifecycle,
                record.createdAtMs,
                record.updatedAtMs,
            )
        }
        when (val decision = RetentionPolicy.planCreate(existing, now)) {
            RetentionPolicy.CreateDecision.Unavailable ->
                return RecoveryStorePort.CheckpointResult.StoreUnavailable

            is RetentionPolicy.CreateDecision.Allowed -> decision.toEvict.forEach { records.remove(it.pointId.value) }
        }
        val record = MutableRecord(
            pointId = payload.pointId,
            runId = payload.runId,
            createdAtMs = now,
            updatedAtMs = now,
            lifecycle = LifecycleState.CREATING,
            priorLifecycle = null,
            preManifest = payload.preManifest,
            preRevision = payload.preRevision,
            preDigest = payload.preDigest,
            intendedManifest = payload.preManifest,
            intendedDigest = payload.preDigest,
            applyActionDigest = payload.applyActionDigest,
            reviewedManifest = null,
            reviewedDigest = null,
            recoveryActionDigest = null,
            itemCount = payload.itemCount,
            resourceCount = payload.resourceCount,
            checksumValid = !checkpointValidateFails,
            formatVersion = 1,
        )
        records[payload.pointId.value] = record
        // Advance CREATING -> READY in its own logical transaction.
        record.lifecycle = LifecycleState.READY
        record.updatedAtMs = clock()
        if (checkpointValidateFails) {
            record.checksumValid = false
            return RecoveryStorePort.CheckpointResult.ValidateFailed
        }
        return RecoveryStorePort.CheckpointResult.Ready(record.asStored())
    }

    override fun markApplying(
        pointId: RecoveryPointId,
        intendedManifest: PersistenceManifest,
        intendedDigest: ByteArray,
        applyActionDigest: ByteArray,
        itemCount: Int,
        resourceCount: Int,
    ): Boolean {
        if (markApplyingFails) return false
        val record = records[pointId.value] ?: return false
        if (record.lifecycle != LifecycleState.READY) return false
        record.intendedManifest = intendedManifest
        record.intendedDigest = intendedDigest
        record.applyActionDigest = applyActionDigest
        record.itemCount = itemCount
        record.resourceCount = resourceCount
        record.priorLifecycle = record.lifecycle
        record.lifecycle = LifecycleState.APPLYING
        record.updatedAtMs = clock()
        return true
    }

    override fun advance(pointId: RecoveryPointId, next: LifecycleState): Boolean {
        advanceCalls += 1
        if (advanceFails) return false
        val record = records[pointId.value] ?: return false
        if (!LifecycleTransitions.isLegal(record.lifecycle, next)) return false
        record.priorLifecycle = record.lifecycle
        record.lifecycle = next
        record.updatedAtMs = clock()
        return true
    }

    override fun markRestoring(
        pointId: RecoveryPointId,
        reviewedManifest: PersistenceManifest,
        reviewedDigest: ByteArray,
        recoveryActionDigest: ByteArray,
    ): Boolean {
        markRestoringCalls += 1
        if (markRestoringFails) return false
        val record = records[pointId.value] ?: return false
        if (record.lifecycle != LifecycleState.RESTORING &&
            !LifecycleTransitions.isLegal(record.lifecycle, LifecycleState.RESTORING)
        ) {
            return false
        }
        record.reviewedManifest = reviewedManifest
        record.reviewedDigest = reviewedDigest
        record.recoveryActionDigest = recoveryActionDigest
        if (record.lifecycle != LifecycleState.RESTORING) record.priorLifecycle = record.lifecycle
        record.lifecycle = LifecycleState.RESTORING
        record.updatedAtMs = clock()
        return true
    }

    override fun readRecord(pointId: RecoveryPointId): RecoveryStorePort.StoredRecord? = records[pointId.value]?.asStored()

    override fun listNonFinalRecords(): List<RecoveryStorePort.StoredRecord> = records.values
        .filter { !LifecycleTransitions.isFinal(it.lifecycle) }
        .sortedBy { it.createdAtMs }
        .map { record -> record.asStored() }

    override fun pruneUnused(pointId: RecoveryPointId): Boolean {
        pruneUnusedCalls += 1
        if (pruneUnusedFails) return false
        val record = records[pointId.value] ?: return false
        if (record.lifecycle != LifecycleState.READY) return false
        return records.remove(pointId.value, record)
    }

    override fun runRetention(nowMillis: Long): RecoveryStorePort.RetentionOutcome {
        retentionCalls += 1
        // Fake store does not perform retention by default; tests assert retention
        // behavior via RetentionPolicyTest. Returning Applied keeps the protocol happy.
        return retentionOutcome
    }

    fun seedRecord(record: RecoveryStorePort.StoredRecord) {
        records[record.pointId.value] = MutableRecord(
            pointId = record.pointId,
            runId = record.runId,
            createdAtMs = record.createdAtMs,
            updatedAtMs = record.updatedAtMs,
            lifecycle = record.lifecycle,
            priorLifecycle = record.priorLifecycle,
            preManifest = record.preManifest,
            preRevision = record.preRevision,
            preDigest = record.preDigest,
            intendedManifest = record.intendedManifest,
            intendedDigest = record.intendedDigest,
            applyActionDigest = record.applyActionDigest,
            reviewedManifest = record.reviewedManifest,
            reviewedDigest = record.reviewedDigest,
            recoveryActionDigest = record.recoveryActionDigest,
            itemCount = record.itemCount,
            resourceCount = record.resourceCount,
            checksumValid = record.checksumValid,
            formatVersion = record.formatVersion,
        )
    }

    fun seedTombstone(tombstone: RecoveryStorePort.Tombstone) {
        tombstones[tombstone.pointId.value] = tombstone
    }

    fun clear() {
        records.clear()
        tombstones.clear()
        storeAvailability = RecoveryStorePort.StoreAvailability.READY
        checkpointCreateFails = false
        checkpointCollisionsRemaining = 0
        checkpointPointIds.clear()
        checkpointValidateFails = false
        markApplyingFails = false
        markRestoringFails = false
        advanceFails = false
        pruneUnusedFails = false
        retentionOutcome = RecoveryStorePort.RetentionOutcome.Applied
        maintenanceTombstoneReads = 0
        inspectionProjectionReads = 0
        inspectionReadFails = false
        snapshotRebuildSucceeds = true
        markRestoringCalls = 0
        advanceCalls = 0
        pruneUnusedCalls = 0
        retentionCalls = 0
    }

    private class MutableRecord(
        val pointId: RecoveryPointId,
        val runId: RunId,
        val createdAtMs: Long,
        var updatedAtMs: Long,
        var lifecycle: LifecycleState,
        var priorLifecycle: LifecycleState?,
        var preManifest: PersistenceManifest,
        var preRevision: RevisionId,
        var preDigest: ByteArray,
        var intendedManifest: PersistenceManifest,
        var intendedDigest: ByteArray,
        var applyActionDigest: ByteArray,
        var reviewedManifest: PersistenceManifest?,
        var reviewedDigest: ByteArray?,
        var recoveryActionDigest: ByteArray?,
        var itemCount: Int,
        var resourceCount: Int,
        var checksumValid: Boolean,
        val formatVersion: Int,
    )

    private fun MutableRecord.asStored(): RecoveryStorePort.StoredRecord = StoredRecordImpl(this)

    private class StoredRecordImpl(private val backing: MutableRecord) : RecoveryStorePort.StoredRecord {
        override val pointId: RecoveryPointId get() = backing.pointId
        override val runId: RunId get() = backing.runId
        override val lifecycle: LifecycleState get() = backing.lifecycle
        override val priorLifecycle: LifecycleState? get() = backing.priorLifecycle
        override val createdAtMs: Long get() = backing.createdAtMs
        override val updatedAtMs: Long get() = backing.updatedAtMs
        override val preManifest: PersistenceManifest get() = backing.preManifest
        override val preRevision: RevisionId get() = backing.preRevision
        override val preDigest: ByteArray get() = backing.preDigest
        override val intendedManifest: PersistenceManifest get() = backing.intendedManifest
        override val intendedDigest: ByteArray get() = backing.intendedDigest
        override val applyActionDigest: ByteArray get() = backing.applyActionDigest
        override val reviewedManifest: PersistenceManifest? get() = backing.reviewedManifest
        override val reviewedDigest: ByteArray? get() = backing.reviewedDigest
        override val recoveryActionDigest: ByteArray? get() = backing.recoveryActionDigest
        override val itemCount: Int get() = backing.itemCount
        override val resourceCount: Int get() = backing.resourceCount
        override val checksumValid: Boolean get() = backing.checksumValid
        override val formatVersion: Int get() = backing.formatVersion
    }
}
