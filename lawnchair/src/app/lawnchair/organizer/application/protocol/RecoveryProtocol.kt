package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId

/** Implements explicit, revision-bound recovery while holding one outer writer lease. */
class RecoveryProtocol(
    private val writer: LayoutWriterPort,
    private val store: RecoveryStorePort,
    private val clock: Clock,
    private val operationIds: OperationIdSource,
    private val faults: FaultInjector,
    private val mutex: RunMutexPort,
) {
    fun recover(request: RecoveryRequest): RecoveryResult {
        val runId = operationIds.newRunId()
        if (!mutex.tryAcquire(runId)) return RecoveryResult.ConcurrentRun
        return try {
            recoverWithRunMutex(runId, request)
        } finally {
            mutex.release(runId)
        }
    }

    private fun recoverWithRunMutex(runId: RunId, request: RecoveryRequest): RecoveryResult {
        when (store.availability()) {
            RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION ->
                return RecoveryResult.NotRestorable(request.pointId, RecoveryRejection.INCOMPATIBLE_VERSION)

            RecoveryStorePort.StoreAvailability.READ_FAILED ->
                return RecoveryResult.RestoreFailed(
                    request.pointId,
                    RecoveryFailure.RECOVERY_STORE_FAILED,
                    AuthoritativeState.UNKNOWN,
                )

            RecoveryStorePort.StoreAvailability.READY -> Unit
        }
        // Closed point read (Issue #174): the store itself distinguishes an
        // unreadable preserved record from a missing one and from store I/O
        // failure. Unreadable means the record exists but its manifest cannot
        // be reconstructed — deterministically corrupt, never missing and
        // never a transient store failure.
        val stored: RecoveryStorePort.StoredRecord = when (
            val read = try {
                store.readRecord(request.pointId)
            } catch (_: RuntimeException) {
                RecoveryStorePort.RecordRead.Failed
            }
        ) {
            RecoveryStorePort.RecordRead.Missing -> return tombstoneResult(request.pointId)

            RecoveryStorePort.RecordRead.Failed -> return recoveryStoreFailure(request.pointId)

            is RecoveryStorePort.RecordRead.Unreadable -> return RecoveryResult.NotRestorable(
                request.pointId,
                RecoveryRejection.CORRUPT,
            )

            is RecoveryStorePort.RecordRead.Readable -> read.record
        }
        preflight(stored)?.let { return RecoveryResult.NotRestorable(request.pointId, it) }
        if (faults.serializationContention()) return RecoveryResult.WriterBusy
        val lease = writer.tryAcquireLease(WriterKind.ORGANIZER, runId.value.hashCode().toLong())
            ?: return RecoveryResult.WriterBusy
        return try {
            recoverWithOuterLease(request, stored, lease)
        } finally {
            lease.close()
        }
    }

    private fun tombstoneResult(pointId: app.lawnchair.organizer.application.public.RecoveryPointId): RecoveryResult {
        val tombstone = try {
            store.readTombstone(pointId)
        } catch (_: RuntimeException) {
            return recoveryStoreFailure(pointId)
        } ?: return RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING)
        if (tombstone.expiresAtMs <= clock.nowMillis()) {
            return RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING)
        }
        val rejection = when (tombstone.reason) {
            RecoveryStorePort.TombstoneReason.EXPIRED -> RecoveryRejection.EXPIRED
            RecoveryStorePort.TombstoneReason.CORRUPT -> RecoveryRejection.CORRUPT
            RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION -> RecoveryRejection.INCOMPATIBLE_VERSION
            RecoveryStorePort.TombstoneReason.ALREADY_RESTORED -> RecoveryRejection.ALREADY_RESTORED
            RecoveryStorePort.TombstoneReason.PRUNED_UNUSED -> RecoveryRejection.MISSING
            RecoveryStorePort.TombstoneReason.QUARANTINED -> RecoveryRejection.CORRUPT
        }
        return RecoveryResult.NotRestorable(pointId, rejection)
    }

    private fun recoveryStoreFailure(pointId: app.lawnchair.organizer.application.public.RecoveryPointId): RecoveryResult = RecoveryResult.RestoreFailed(
        pointId,
        RecoveryFailure.RECOVERY_STORE_FAILED,
        AuthoritativeState.UNKNOWN,
    )

    private fun recoverWithOuterLease(
        request: RecoveryRequest,
        stored: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): RecoveryResult {
        val pointId = request.pointId
        val reviewed = writer.captureCurrent(CaptureId(pointId.value))
        if (reviewed.revision != request.expectedCurrentRevision) {
            return RecoveryResult.NotRestorable(pointId, RecoveryRejection.STALE_REVISION)
        }
        if (faults.lockStateColumnReadFailure() ||
            reviewed.layoutState.items.any { it.lockState == OrganizerLockState.UNKNOWN }
        ) {
            return RecoveryResult.NotRestorable(pointId, RecoveryRejection.LOCK_STATE_UNAVAILABLE)
        }
        val recoverySet = when (
            val prepared =
                writer.prepareRecoveryWriteSet(stored.preManifest, reviewed)
        ) {
            is WriteSetPreparation.Ready -> prepared.writeSet

            WriteSetPreparation.ContextMismatch ->
                return RecoveryResult.NotRestorable(pointId, RecoveryRejection.STALE_REVISION)

            WriteSetPreparation.InvalidPlan, WriteSetPreparation.IdentityExhausted ->
                return RecoveryResult.RestoreFailed(
                    pointId,
                    RecoveryFailure.WRITE_FAILED,
                    AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
                )
        }
        faults.beforeRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.RESTORING, pointId)
        if (!store.markRestoring(
                pointId = pointId,
                reviewedManifest = reviewed.manifest,
                reviewedDigest = reviewed.digest,
                recoveryActionDigest = recoverySet.actionSetDigest,
            )
        ) {
            return RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.RECOVERY_STORE_FAILED,
                AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
            )
        }
        faults.afterRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.RESTORING, pointId)
        val outcome = try {
            writer.applyWriteSet(lease, recoverySet, pointId, faults)
        } catch (error: Throwable) {
            ApplyTxOutcome.Failed(error)
        }
        if (outcome is ApplyTxOutcome.PreconditionFailed) {
            return RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.WRITE_FAILED,
                AuthoritativeState.UNKNOWN,
            )
        }
        val authoritative = writer.classifyAuthoritativeState(
            preDigest = stored.preDigest,
            intendedPostDigest = stored.intendedDigest,
            recoveryTargetDigest = stored.preDigest,
            reviewedCurrentDigest = reviewed.digest,
        )
        if (authoritative != AuthoritativeClass.PRE_STATE &&
            authoritative != AuthoritativeClass.RECOVERY_TARGET
        ) {
            val failure = if (outcome is ApplyTxOutcome.Failed) {
                RecoveryFailure.WRITE_FAILED
            } else {
                RecoveryFailure.COMMIT_OUTCOME_UNKNOWN
            }
            val state = when (authoritative) {
                AuthoritativeClass.REVIEWED_CURRENT_STATE ->
                    AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED

                AuthoritativeClass.INTENDED_POST_STATE ->
                    AuthoritativeState.POST_APPLY_DB_MODEL_UNVERIFIED

                else -> AuthoritativeState.UNKNOWN
            }
            return RecoveryResult.RestoreFailed(pointId, failure, state)
        }
        // Issue #152: the reload outcome carries the model snapshot; staleness
        // is excluded inside the adapter, never here.
        val completed = writer.requestCorrelatedReload(lease) as? ReloadResult.Completed
        if (completed == null) {
            return RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.MODEL_RELOAD_FAILED,
                AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
            )
        }
        val db = writer.recaptureDb()
        val verified = db.manifest == stored.preManifest
        if (!verified) {
            return RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.VERIFICATION_FAILED,
                AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
            )
        }
        // Issue #152: DB/model convergence on the model-verifiable projection
        // before Restored is returned.
        val modelVerified = db.layoutState.projectedToModelVerifiable(writer::legacyLaunchIdentityOf).items ==
            completed.modelSnapshot.items
        if (!modelVerified) {
            return RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.VERIFICATION_FAILED,
                AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
            )
        }
        if (!store.advance(pointId, LifecycleState.RESTORED)) {
            return RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.RECOVERY_STORE_FAILED,
                AuthoritativeState.PRE_APPLY_DB_AND_MODEL,
            )
        }
        return RecoveryResult.Restored(pointId)
    }

    private fun preflight(stored: RecoveryStorePort.StoredRecord): RecoveryRejection? {
        if (!stored.checksumValid) return RecoveryRejection.CORRUPT
        if (stored.formatVersion != LifecycleReconciler.SUPPORTED_FORMAT) {
            return RecoveryRejection.INCOMPATIBLE_VERSION
        }
        return when (stored.lifecycle) {
            LifecycleState.VERIFIED -> when (RetentionPolicy.actionFor(stored.retentionRecord(), clock.nowMillis())) {
                is RetentionPolicy.RetentionAction.Expire -> RecoveryRejection.EXPIRED
                else -> null
            }

            LifecycleState.RESTORED -> finalRejection(stored, RecoveryRejection.ALREADY_RESTORED)

            LifecycleState.EXPIRED -> finalRejection(stored, RecoveryRejection.EXPIRED)

            LifecycleState.CORRUPT -> finalRejection(stored, RecoveryRejection.CORRUPT)

            LifecycleState.INCOMPATIBLE -> finalRejection(stored, RecoveryRejection.INCOMPATIBLE_VERSION)

            else -> RecoveryRejection.MISSING
        }
    }

    private fun finalRejection(
        stored: RecoveryStorePort.StoredRecord,
        retainedReason: RecoveryRejection,
    ): RecoveryRejection = when (RetentionPolicy.actionFor(stored.retentionRecord(), clock.nowMillis())) {
        is RetentionPolicy.RetentionAction.Tombstone -> RecoveryRejection.MISSING
        else -> retainedReason
    }

    private fun RecoveryStorePort.StoredRecord.retentionRecord(): RetentionPolicy.RetentionRecord = RetentionPolicy.RetentionRecord(pointId, lifecycle, createdAtMs, updatedAtMs)
}
