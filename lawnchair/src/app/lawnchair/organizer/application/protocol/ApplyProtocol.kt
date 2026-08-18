package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.ApplySummary
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.projection.ApplyProjection

/** Implements the accepted A0-A8 apply protocol while holding one outer writer lease. */
class ApplyProtocol(
    private val writer: LayoutWriterPort,
    private val store: RecoveryStorePort,
    private val clock: Clock,
    private val operationIds: OperationIdSource,
    private val faults: FaultInjector,
    private val mutex: RunMutex,
    private val diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP,
) {
    fun apply(plan: ValidatedLayoutPlan, runId: RunId? = null): ApplyResult {
        val actualRunId = runId ?: operationIds.newRunId()
        if (!mutex.tryAcquire(actualRunId)) {
            emitSafely(
                RunEvent(
                    journalSequence = 0L,
                    phase = PhaseCode.CONCURRENT_RUN_REJECTED,
                    runId = actualRunId.value,
                ),
            )
            return ApplyResult.ConcurrentRun
        }
        return try {
            val result = applyWithRunMutex(actualRunId, plan)
            // Emit terminal event for the apply result
            emitTerminalApplyEvent(result, plan)
            result
        } finally {
            mutex.release(actualRunId)
        }
    }

    private fun applyWithRunMutex(runId: RunId, plan: ValidatedLayoutPlan): ApplyResult {
        validatePlan(plan)?.let { return ApplyResult.Rejected(runId, it) }
        if (store.availability() != RecoveryStorePort.StoreAvailability.READY) {
            return ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE)
        }
        if (faults.serializationContention()) {
            return ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY)
        }
        val lease = writer.tryAcquireLease(WriterKind.ORGANIZER, leaseToken(runId))
            ?: return ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY)
        return try {
            applyWithOuterLease(runId, plan, lease)
        } finally {
            // This is the only close of the outer A2-final lease. The adapter's
            // transaction may reenter it with the same token.
            lease.close()
        }
    }

    private fun applyWithOuterLease(
        runId: RunId,
        plan: ValidatedLayoutPlan,
        lease: LeaseHandle,
    ): ApplyResult {
        val capture = writer.captureCurrent(CaptureId(runId.value))
        when {
            lockStateUnavailable(capture) ->
                return ApplyResult.Rejected(runId, PreWriteRejection.LOCK_STATE_UNAVAILABLE)

            capture.revision != plan.sourceRevision ->
                return ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION)

            capture.layoutState != plan.sourceState ->
                return ApplyResult.Rejected(runId, PreWriteRejection.EXACT_PRECONDITION_FAILED)

            isNoChange(plan) -> return ApplyResult.NoChanges(runId)
        }

        val writeSet = when (val prepared = writer.prepareApplyWriteSet(capture, plan)) {
            is WriteSetPreparation.Ready -> prepared.writeSet

            WriteSetPreparation.InvalidPlan ->
                return ApplyResult.Rejected(runId, PreWriteRejection.INVALID_PLAN)

            WriteSetPreparation.IdentityExhausted ->
                return ApplyResult.Rejected(runId, PreWriteRejection.IDENTITY_EXHAUSTED)

            WriteSetPreparation.ContextMismatch ->
                return ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION)
        }
        if (writeSet.intendedState != plan.intendedState) {
            return ApplyResult.Rejected(runId, PreWriteRejection.INVALID_PLAN)
        }

        if (faults.recoveryStoreWriteFailure()) {
            return ApplyResult.Rejected(runId, PreWriteRejection.CHECKPOINT_CREATE_FAILED)
        }
        val (pointId, checkpoint) = createCheckpoint(runId, capture, writeSet)
            ?: return ApplyResult.Rejected(runId, PreWriteRejection.CHECKPOINT_CREATE_FAILED)
        when (checkpoint) {
            RecoveryStorePort.CheckpointResult.PointIdCollision,
            RecoveryStorePort.CheckpointResult.CreateFailed,
            ->
                return ApplyResult.Rejected(runId, PreWriteRejection.CHECKPOINT_CREATE_FAILED)

            RecoveryStorePort.CheckpointResult.ValidateFailed ->
                return ApplyResult.Rejected(runId, PreWriteRejection.CHECKPOINT_VALIDATE_FAILED)

            RecoveryStorePort.CheckpointResult.StoreUnavailable ->
                return ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE)

            is RecoveryStorePort.CheckpointResult.Ready -> {
                // Emit CHECKPOINTED right after the checkpoint Ready result (A4)
                emitSafely(
                    ApplyProjection.projectCheckpointed(
                        runId = runId.value,
                        pointId = pointId.value,
                        journalSequence = 0L,
                    ),
                )
            }
        }

        faults.beforeRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.APPLYING, pointId)
        val marked = store.markApplying(
            pointId = pointId,
            intendedManifest = writeSet.intendedManifest,
            intendedDigest = digestOfIntended(writeSet),
            applyActionDigest = writeSet.actionSetDigest,
            itemCount = writeSet.intendedManifest.rowCount,
            resourceCount = writeSet.intendedManifest.resources.size,
        )
        if (!marked) return ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE)
        faults.afterRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.APPLYING, pointId)

        val outcome = try {
            writer.applyWriteSet(lease, writeSet, pointId, faults)
        } catch (error: Throwable) {
            ApplyTxOutcome.Failed(error)
        }
        return classifyApplyOutcome(runId, pointId, capture, writeSet, outcome, lease)
    }

    private fun createCheckpoint(
        runId: RunId,
        capture: CapturedSnapshot,
        writeSet: MaterializedWriteSet,
    ): Pair<RecoveryPointId, RecoveryStorePort.CheckpointResult>? {
        repeat(MAX_POINT_ID_ATTEMPTS) {
            val pointId = operationIds.newPointId()
            val result = store.checkpoint(
                RecoveryStorePort.CheckpointPayload(
                    pointId = pointId,
                    runId = runId,
                    preManifest = capture.manifest,
                    preRevision = capture.revision,
                    preDigest = capture.digest,
                    applyActionDigest = writeSet.actionSetDigest,
                    itemCount = capture.manifest.rowCount,
                    resourceCount = capture.manifest.resources.size,
                ),
            )
            if (result != RecoveryStorePort.CheckpointResult.PointIdCollision) return pointId to result
        }
        return null
    }

    private fun classifyApplyOutcome(
        runId: RunId,
        pointId: RecoveryPointId,
        pre: CapturedSnapshot,
        writeSet: MaterializedWriteSet,
        outcome: ApplyTxOutcome,
        lease: LeaseHandle,
    ): ApplyResult {
        if (outcome is ApplyTxOutcome.PreconditionFailed) {
            // A5 reread caught a stale revision or precondition change before any
            // mutation committed. The Launcher DB is unchanged. Per spec §A5, the
            // unused checkpoint is pruned; if cleanup is interrupted, it is left
            // READY for restart reconciliation to complete later.
            if (!store.advance(pointId, LifecycleState.READY) || !store.pruneUnused(pointId)) {
                return ApplyResult.Unresolved(
                    runId,
                    pointId,
                    ApplyFailure.RECOVERY_STORE_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                )
            }
            return ApplyResult.Rejected(runId, outcome.rejection)
        }
        val failure = when (outcome) {
            is ApplyTxOutcome.Failed -> ApplyFailure.WRITE_FAILED
            ApplyTxOutcome.OutcomeUnknown -> ApplyFailure.COMMIT_OUTCOME_UNKNOWN
            ApplyTxOutcome.Committed -> ApplyFailure.COMMIT_OUTCOME_UNKNOWN
        }
        val intendedDigest = digestOfIntended(writeSet)
        return when (writer.classifyAuthoritativeState(pre.digest, intendedDigest, null, null)) {
            AuthoritativeClass.PRE_STATE -> {
                if (!store.advance(pointId, LifecycleState.READY) || !store.pruneUnused(pointId)) {
                    ApplyResult.Unresolved(
                        runId,
                        pointId,
                        ApplyFailure.RECOVERY_STORE_FAILED,
                        AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                    )
                } else {
                    ApplyResult.RolledBack(runId, failure)
                }
            }

            AuthoritativeClass.INTENDED_POST_STATE -> continueCommitted(
                runId,
                pointId,
                pre,
                writeSet,
                lease,
            )

            else -> automaticRecovery(runId, pointId, failure, lease)
        }
    }

    private fun continueCommitted(
        runId: RunId,
        pointId: RecoveryPointId,
        pre: CapturedSnapshot,
        writeSet: MaterializedWriteSet,
        lease: LeaseHandle,
    ): ApplyResult {
        if (!store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED)) {
            return automaticRecovery(runId, pointId, ApplyFailure.RECOVERY_STORE_FAILED, lease)
        }
        // Emit APPLY_COMMITTED after COMMITTED_UNVERIFIED mark succeeds (A6)
        emitSafely(
            ApplyProjection.projectCommitted(
                runId = runId.value,
                journalSequence = 0L,
            ),
        )
        faults.beforeModelReloadRequest()
        val requested = writer.requestCorrelatedReload(lease)
        val reload = when (faults.afterCorrelatedGenerationWait()) {
            FaultInjector.ReloadDirective.PROCEED -> requested
            FaultInjector.ReloadDirective.FAIL -> ReloadResult.Failed
            FaultInjector.ReloadDirective.SUPERSEDE -> ReloadResult.Superseded
        }
        if (reload != ReloadResult.Completed) {
            return automaticRecovery(runId, pointId, ApplyFailure.MODEL_RELOAD_FAILED, lease)
        }
        faults.beforeIndependentDbRecapture()
        val db = writer.recaptureDb()
        faults.afterVerification()
        val exactDb = db.layoutState == writeSet.intendedState &&
            db.manifest == writeSet.intendedManifest
        if (!exactDb) return automaticRecovery(runId, pointId, ApplyFailure.VERIFICATION_FAILED, lease)
        if (!store.advance(pointId, LifecycleState.VERIFIED)) {
            return automaticRecovery(runId, pointId, ApplyFailure.RECOVERY_STORE_FAILED, lease)
        }
        return ApplyResult.Applied(runId, pointId)
    }

    private fun automaticRecovery(
        runId: RunId,
        pointId: RecoveryPointId,
        applyFailure: ApplyFailure,
        lease: LeaseHandle,
    ): ApplyResult {
        val stored = store.readRecord(pointId)
            ?: return ApplyResult.Unresolved(runId, pointId, applyFailure, AuthoritativeState.UNKNOWN)
        val reviewed = writer.recaptureDb()
        val recoverySet = when (val prepared = writer.prepareRecoveryWriteSet(stored.preManifest, reviewed)) {
            is WriteSetPreparation.Ready -> prepared.writeSet

            WriteSetPreparation.ContextMismatch -> return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                RecoveryFailure.WRITE_FAILED,
                AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
            )

            else -> return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                RecoveryFailure.WRITE_FAILED,
                AuthoritativeState.UNKNOWN,
            )
        }
        faults.beforeRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.RESTORING, pointId)
        if (!store.markRestoring(
                pointId,
                reviewed.manifest,
                reviewed.digest,
                recoverySet.actionSetDigest,
            )
        ) {
            return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                RecoveryFailure.RECOVERY_STORE_FAILED,
                AuthoritativeState.POST_APPLY_DB_MODEL_UNVERIFIED,
            )
        }
        faults.afterRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.RESTORING, pointId)
        val outcome = try {
            writer.applyWriteSet(lease, recoverySet, pointId, faults)
        } catch (error: Throwable) {
            ApplyTxOutcome.Failed(error)
        }
        val classification = writer.classifyAuthoritativeState(
            preDigest = stored.preDigest,
            intendedPostDigest = stored.intendedDigest,
            recoveryTargetDigest = stored.preDigest,
            reviewedCurrentDigest = reviewed.digest,
        )
        if (classification != AuthoritativeClass.PRE_STATE &&
            classification != AuthoritativeClass.RECOVERY_TARGET
        ) {
            val recoveryFailure = if (outcome is ApplyTxOutcome.Failed) {
                RecoveryFailure.WRITE_FAILED
            } else {
                RecoveryFailure.COMMIT_OUTCOME_UNKNOWN
            }
            return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                recoveryFailure,
                authoritativeState(classification, false),
            )
        }
        if (writer.requestCorrelatedReload(lease) != ReloadResult.Completed) {
            return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                RecoveryFailure.MODEL_RELOAD_FAILED,
                AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
            )
        }
        val verified = writer.recaptureDb().manifest == stored.preManifest
        if (!verified) {
            return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                RecoveryFailure.VERIFICATION_FAILED,
                AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
            )
        }
        if (!store.advance(pointId, LifecycleState.RESTORED)) {
            return ApplyResult.RecoveryFailed(
                runId,
                pointId,
                applyFailure,
                RecoveryFailure.RECOVERY_STORE_FAILED,
                AuthoritativeState.PRE_APPLY_DB_AND_MODEL,
            )
        }
        return ApplyResult.Recovered(runId, pointId, applyFailure)
    }

    private fun validatePlan(plan: ValidatedLayoutPlan): PreWriteRejection? {
        if (plan.actions.distinctBy { it.ref }.size != plan.actions.size) return PreWriteRejection.INVALID_PLAN
        if (plan.actions.any { action ->
                when (action) {
                    is ApplyAction.Preserve -> action.ref != action.expected.ref
                    is ApplyAction.Update -> action.ref != action.expected.ref || action.ref != action.intended.ref
                    is ApplyAction.Insert -> action.ref != action.intended.ref
                }
            }
        ) {
            return PreWriteRejection.INVALID_PLAN
        }
        if (plan.actions.isEmpty() && plan.sourceState != plan.intendedState) return PreWriteRejection.INVALID_PLAN
        return null
    }

    private fun lockStateUnavailable(capture: CapturedSnapshot): Boolean = faults.lockStateColumnReadFailure() ||
        capture.layoutState.items.any { it.lockState == OrganizerLockState.UNKNOWN }

    private fun isNoChange(plan: ValidatedLayoutPlan): Boolean = plan.actions.all { it is ApplyAction.Preserve } && plan.sourceState == plan.intendedState

    private fun digestOfIntended(writeSet: MaterializedWriteSet): ByteArray = app.lawnchair.organizer.application.revision.RevisionCalculator
        .classificationDigestOf(writeSet.intendedState)

    private fun leaseToken(runId: RunId): Long = runId.value.hashCode().toLong()

    private fun authoritativeState(kind: AuthoritativeClass, modelVerified: Boolean): AuthoritativeState = when (kind) {
        AuthoritativeClass.PRE_STATE, AuthoritativeClass.RECOVERY_TARGET ->
            if (modelVerified) AuthoritativeState.PRE_APPLY_DB_AND_MODEL else AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED

        AuthoritativeClass.INTENDED_POST_STATE ->
            if (modelVerified) AuthoritativeState.POST_APPLY_DB_AND_MODEL else AuthoritativeState.POST_APPLY_DB_MODEL_UNVERIFIED

        AuthoritativeClass.REVIEWED_CURRENT_STATE ->
            if (modelVerified) AuthoritativeState.REVIEWED_CURRENT_DB_AND_MODEL else AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED

        AuthoritativeClass.NEITHER -> AuthoritativeState.UNKNOWN
    }

    private fun emitTerminalApplyEvent(result: ApplyResult, plan: ValidatedLayoutPlan) {
        val preserveCount = plan.actions.count { it is ApplyAction.Preserve }
        val updateCount = plan.actions.count { it is ApplyAction.Update }
        val insertCount = plan.actions.count { it is ApplyAction.Insert }
        val summary = ApplySummary(
            preserveActionCount = preserveCount,
            updateActionCount = updateCount,
            insertActionCount = insertCount,
        )
        val applyStage = applyStageForResult(result)
        val event = ApplyProjection.project(result, journalSequence = 0L, applyStage = applyStage, applySummary = summary)
        emitSafely(event)
    }

    private fun applyStageForResult(result: ApplyResult): ApplyStage? = when (result) {
        is ApplyResult.NoChanges -> ApplyStage.A2

        is ApplyResult.Applied -> ApplyStage.A8

        is ApplyResult.Rejected -> when (result.reason) {
            PreWriteRejection.INVALID_PLAN,
            PreWriteRejection.STALE_REVISION,
            PreWriteRejection.EXACT_PRECONDITION_FAILED,
            PreWriteRejection.LOCK_STATE_UNAVAILABLE,
            PreWriteRejection.IDENTITY_EXHAUSTED,
            PreWriteRejection.RECOVERY_STORE_UNAVAILABLE,
            -> ApplyStage.A2

            PreWriteRejection.CHECKPOINT_CREATE_FAILED,
            PreWriteRejection.CHECKPOINT_VALIDATE_FAILED,
            -> ApplyStage.A4

            PreWriteRejection.WRITER_BUSY -> ApplyStage.A0
        }

        is ApplyResult.RolledBack -> ApplyStage.A6

        is ApplyResult.Recovered -> ApplyStage.A7

        is ApplyResult.Unresolved -> ApplyStage.A6

        is ApplyResult.RecoveryFailed -> ApplyStage.A7

        is ApplyResult.ConcurrentRun -> ApplyStage.A0
    }

    private fun emitSafely(event: RunEvent) {
        try {
            diagnosticsPort.emit(event)
        } catch (_: Exception) {
            // Fail-open: diagnostics failure does not affect the organizer operation
        }
    }

    private companion object {
        const val MAX_POINT_ID_ATTEMPTS: Int = 4
    }
}
