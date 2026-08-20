package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewRejection
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewSummary
import app.lawnchair.organizer.application.public.RecoveryPreviewUnavailable
import app.lawnchair.organizer.planning.RevisionId

/**
 * Read-only, revision-bound recovery inspection.
 *
 * The protocol owns one short, non-blocking organizer lease only to capture
 * authoritative current state. It never creates a checkpoint, mutates a
 * recovery record, writes layout state, requests a model reload, or emits a
 * diagnostic event.
 *
 * Issue #84.
 */
class RecoveryPreviewProtocol(
    private val writer: LayoutWriterPort,
    private val store: RecoveryStorePort,
    private val clock: Clock,
    private val operationIds: OperationIdSource,
    private val faults: FaultInjector,
    private val mutex: RunMutexPort,
    private val confirmationIssuer: (RecoveryPointId, RevisionId) -> RecoveryPreviewConfirmation,
) {

    fun inspect(pointId: RecoveryPointId): RecoveryPreviewResult {
        val runId = operationIds.newRunId()
        if (!mutex.tryAcquire(runId)) return RecoveryPreviewResult.Concurrent
        return try {
            inspectWithRunMutex(pointId, runId)
        } finally {
            mutex.release(runId)
        }
    }

    private fun inspectWithRunMutex(
        pointId: RecoveryPointId,
        runId: app.lawnchair.organizer.application.public.RunId,
    ): RecoveryPreviewResult {
        when (val read = store.readInspectionProjection(pointId)) {
            is RecoveryStorePort.InspectionProjectionRead.Value -> when (val projection = read.projection) {
                is RecoveryStorePort.InspectionProjection.Record -> {
                    preflight(projection)?.let { return RecoveryPreviewResult.NotRestorable(pointId, it) }
                }

                is RecoveryStorePort.InspectionProjection.Tombstone -> {
                    return tombstoneResult(pointId, projection)
                }

                RecoveryStorePort.InspectionProjection.Missing -> {
                    return RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.MISSING)
                }
            }

            RecoveryStorePort.InspectionProjectionRead.Incompatible -> {
                return RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.INCOMPATIBLE_VERSION)
            }

            RecoveryStorePort.InspectionProjectionRead.Unavailable -> {
                return RecoveryPreviewResult.Unavailable(
                    pointId,
                    RecoveryPreviewUnavailable.RECOVERY_STORE_UNAVAILABLE,
                )
            }
        }

        if (faults.serializationContention()) return RecoveryPreviewResult.WriterBusy
        val lease = writer.tryAcquireLease(WriterKind.ORGANIZER, runId.value.hashCode().toLong())
            ?: return RecoveryPreviewResult.WriterBusy
        return try {
            val current = writer.captureCurrent(CaptureId("recovery-preview:${pointId.value}"))
            if (faults.lockStateColumnReadFailure() ||
                current.layoutState.items.any { it.lockState == OrganizerLockState.UNKNOWN }
            ) {
                RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.LOCK_STATE_UNAVAILABLE)
            } else {
                RecoveryPreviewResult.Restorable(
                    pointId = pointId,
                    summary = RecoveryPreviewSummary(),
                    confirmation = confirmationIssuer(pointId, current.revision),
                )
            }
        } finally {
            lease.close()
        }
    }

    private fun tombstoneResult(
        pointId: RecoveryPointId,
        tombstone: RecoveryStorePort.InspectionProjection.Tombstone,
    ): RecoveryPreviewResult {
        if (tombstone.expiresAtMs <= clock.nowMillis()) {
            return RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.MISSING)
        }
        val reason = when (tombstone.reason) {
            RecoveryStorePort.TombstoneReason.EXPIRED -> RecoveryPreviewRejection.EXPIRED
            RecoveryStorePort.TombstoneReason.CORRUPT -> RecoveryPreviewRejection.CORRUPT
            RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION -> RecoveryPreviewRejection.INCOMPATIBLE_VERSION
            RecoveryStorePort.TombstoneReason.ALREADY_RESTORED -> RecoveryPreviewRejection.ALREADY_RESTORED
            RecoveryStorePort.TombstoneReason.PRUNED_UNUSED -> RecoveryPreviewRejection.MISSING
        }
        return RecoveryPreviewResult.NotRestorable(pointId, reason)
    }

    private fun preflight(stored: RecoveryStorePort.InspectionProjection.Record): RecoveryPreviewRejection? {
        if (!stored.checksumValid) return RecoveryPreviewRejection.CORRUPT
        if (stored.formatVersion != LifecycleReconciler.SUPPORTED_FORMAT) {
            return RecoveryPreviewRejection.INCOMPATIBLE_VERSION
        }
        return when (stored.lifecycle) {
            LifecycleState.VERIFIED -> when (RetentionPolicy.actionFor(stored.retentionRecord(), clock.nowMillis())) {
                is RetentionPolicy.RetentionAction.Expire -> RecoveryPreviewRejection.EXPIRED
                else -> null
            }

            LifecycleState.RESTORED -> finalRejection(stored, RecoveryPreviewRejection.ALREADY_RESTORED)

            LifecycleState.EXPIRED -> finalRejection(stored, RecoveryPreviewRejection.EXPIRED)

            LifecycleState.CORRUPT -> finalRejection(stored, RecoveryPreviewRejection.CORRUPT)

            LifecycleState.INCOMPATIBLE -> finalRejection(stored, RecoveryPreviewRejection.INCOMPATIBLE_VERSION)

            LifecycleState.CREATING,
            LifecycleState.READY,
            LifecycleState.APPLYING,
            LifecycleState.COMMITTED_UNVERIFIED,
            LifecycleState.RESTORING,
            -> RecoveryPreviewRejection.UNRESOLVED
        }
    }

    private fun finalRejection(
        stored: RecoveryStorePort.InspectionProjection.Record,
        retainedReason: RecoveryPreviewRejection,
    ): RecoveryPreviewRejection = when (RetentionPolicy.actionFor(stored.retentionRecord(), clock.nowMillis())) {
        is RetentionPolicy.RetentionAction.Tombstone -> RecoveryPreviewRejection.MISSING
        else -> retainedReason
    }

    private fun RecoveryStorePort.InspectionProjection.Record.retentionRecord(): RetentionPolicy.RetentionRecord = RetentionPolicy.RetentionRecord(pointId, lifecycle, createdAtMs, updatedAtMs)
}
