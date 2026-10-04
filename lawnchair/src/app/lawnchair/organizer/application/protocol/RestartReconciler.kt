package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.ReconciliationPublicResult
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryResult

/** Executes restart reconciliation; lifecycle classification alone is never treated as work. */
class RestartReconciler(
    private val writer: LayoutWriterPort,
    private val store: RecoveryStorePort,
    private val faults: FaultInjector,
) {
    sealed interface ReconciliationSummary {
        data object Clean : ReconciliationSummary
        data class Resolved(
            val publicResults: List<ReconciliationPublicResult>,
        ) : ReconciliationSummary
        data object Failed : ReconciliationSummary

        fun hasUnresolvedFailures(): Boolean = when (this) {
            Failed -> true

            Clean -> false

            is Resolved -> publicResults.any { result ->
                when (result) {
                    is ReconciliationPublicResult.Unresolved -> true

                    is ReconciliationPublicResult.ResumeRecovery ->
                        result.outcome is RecoveryResult.RestoreFailed

                    is ReconciliationPublicResult.ResumeApply ->
                        result.outcome is ApplyResult.Unresolved ||
                            result.outcome is ApplyResult.RecoveryFailed

                    else -> false
                }
            }
        }
    }

    fun reconcileAll(): ReconciliationSummary {
        val surfaced = buildList {
            store.listNonFinalRecords().forEach { record ->
                faults.restartBoundary(FaultInjector.RestartPhase.BEFORE_RECONCILE)
                reconcileOne(record)?.let(::add)
                faults.restartBoundary(FaultInjector.RestartPhase.AFTER_RECONCILE)
            }
        }
        if (store.runRetention(System.currentTimeMillis()) != RecoveryStorePort.RetentionOutcome.Applied) {
            return ReconciliationSummary.Failed
        }
        return if (surfaced.isEmpty()) ReconciliationSummary.Clean else ReconciliationSummary.Resolved(surfaced)
    }

    private fun reconcileOne(record: RecoveryStorePort.StoredRecord): ReconciliationPublicResult? {
        if (!record.checksumValid) {
            store.advance(record.pointId, LifecycleState.CORRUPT)
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        if (record.formatVersion != LifecycleReconciler.SUPPORTED_FORMAT) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        val lease = writer.tryAcquireLease(WriterKind.ORGANIZER, record.runId.value.hashCode().toLong())
            ?: return unresolved(record, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
        return try {
            reconcileWithLease(record, lease)
        } finally {
            lease.close()
        }
    }

    private fun reconcileWithLease(
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): ReconciliationPublicResult? {
        val authoritative = classify(record)
        return when (record.lifecycle) {
            LifecycleState.CREATING -> when (authoritative) {
                AuthoritativeClass.PRE_STATE -> {
                    if (!store.advance(record.pointId, LifecycleState.READY) ||
                        !store.pruneUnused(record.pointId)
                    ) {
                        unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                    } else {
                        null
                    }
                }

                else -> {
                    store.advance(record.pointId, LifecycleState.CORRUPT)
                    unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                }
            }

            LifecycleState.READY -> if (authoritative == AuthoritativeClass.PRE_STATE) {
                if (!store.pruneUnused(record.pointId)) {
                    unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                } else {
                    null
                }
            } else {
                unresolved(record, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.APPLYING -> when (authoritative) {
                AuthoritativeClass.PRE_STATE -> {
                    if (!store.advance(record.pointId, LifecycleState.READY) ||
                        !store.pruneUnused(record.pointId)
                    ) {
                        unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                    } else {
                        null
                    }
                }

                AuthoritativeClass.INTENDED_POST_STATE -> finishCommittedApply(record, lease)

                else -> recover(record, lease, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.COMMITTED_UNVERIFIED -> when (authoritative) {
                AuthoritativeClass.INTENDED_POST_STATE -> finishCommittedApply(record, lease)

                AuthoritativeClass.PRE_STATE -> {
                    if (!store.advance(record.pointId, LifecycleState.READY) ||
                        !store.pruneUnused(record.pointId)
                    ) {
                        unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                    } else {
                        ReconciliationPublicResult.ResumeApply(
                            ApplyResult.RolledBack(record.runId, ApplyFailure.COMMIT_OUTCOME_UNKNOWN),
                        )
                    }
                }

                else -> recover(record, lease, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.RESTORING -> when (authoritative) {
                AuthoritativeClass.PRE_STATE, AuthoritativeClass.RECOVERY_TARGET -> finishRestored(record, lease)
                else -> recover(record, lease, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.VERIFIED -> null

            else -> null
        }
    }

    private fun finishCommittedApply(
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): ReconciliationPublicResult {
        if (record.lifecycle == LifecycleState.APPLYING &&
            !store.advance(record.pointId, LifecycleState.COMMITTED_UNVERIFIED)
        ) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        if (writer.requestCorrelatedReload(lease) != ReloadResult.Completed) {
            return recover(record, lease, ApplyFailure.MODEL_RELOAD_FAILED)
        }
        if (writer.recaptureDb().manifest != record.intendedManifest) {
            return recover(record, lease, ApplyFailure.VERIFICATION_FAILED)
        }
        if (!store.advance(record.pointId, LifecycleState.VERIFIED)) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        return ReconciliationPublicResult.ResumeApply(ApplyResult.Applied(record.runId, record.pointId))
    }

    private fun finishRestored(
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): ReconciliationPublicResult {
        if (writer.requestCorrelatedReload(lease) != ReloadResult.Completed) {
            return ReconciliationPublicResult.ResumeRecovery(
                RecoveryResult.RestoreFailed(
                    record.pointId,
                    RecoveryFailure.MODEL_RELOAD_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                ),
            )
        }
        if (writer.recaptureDb().manifest != record.preManifest) {
            return ReconciliationPublicResult.ResumeRecovery(
                RecoveryResult.RestoreFailed(
                    record.pointId,
                    RecoveryFailure.VERIFICATION_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                ),
            )
        }
        if (!store.advance(record.pointId, LifecycleState.RESTORED)) {
            return ReconciliationPublicResult.ResumeRecovery(
                RecoveryResult.RestoreFailed(
                    record.pointId,
                    RecoveryFailure.RECOVERY_STORE_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_AND_MODEL,
                ),
            )
        }
        return ReconciliationPublicResult.ResumeRecovery(RecoveryResult.Restored(record.pointId))
    }

    private fun recover(
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
        failure: ApplyFailure,
    ): ReconciliationPublicResult {
        val reviewed = writer.recaptureDb()
        val writeSet = when (val prepared = writer.prepareRecoveryWriteSet(record.preManifest, reviewed)) {
            is WriteSetPreparation.Ready -> prepared.writeSet
            else -> return unresolved(record, failure)
        }
        faults.beforeRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.RESTORING, record.pointId)
        if (!store.markRestoring(
                record.pointId,
                reviewed.manifest,
                reviewed.digest,
                writeSet.actionSetDigest,
            )
        ) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        faults.afterRecoveryLifecycleCommit(FaultInjector.RecoveryLifecyclePhase.RESTORING, record.pointId)
        val outcome = writer.applyWriteSet(lease, writeSet, record.pointId, faults)
        val restored = classify(record) in setOf(AuthoritativeClass.PRE_STATE, AuthoritativeClass.RECOVERY_TARGET)
        if (!restored || outcome is ApplyTxOutcome.Failed) return unresolved(record, failure)
        return finishRestored(store.readRecord(record.pointId) ?: record, lease)
    }

    private fun classify(record: RecoveryStorePort.StoredRecord): AuthoritativeClass = writer.classifyAuthoritativeState(
        preDigest = record.preDigest,
        intendedPostDigest = record.intendedDigest,
        recoveryTargetDigest = record.preDigest,
        reviewedCurrentDigest = record.reviewedDigest,
    )

    private fun unresolved(
        record: RecoveryStorePort.StoredRecord,
        failure: ApplyFailure,
    ): ReconciliationPublicResult = ReconciliationPublicResult.Unresolved(
        ApplyResult.Unresolved(
            record.runId,
            record.pointId,
            failure,
            AuthoritativeState.UNKNOWN,
        ),
    )
}

fun ReconciliationPublicResult.asApplyResultOrNull(): ApplyResult? = (this as? ReconciliationPublicResult.ResumeApply)?.outcome
    ?: (this as? ReconciliationPublicResult.Unresolved)?.outcome

fun ReconciliationPublicResult.asRecoveryResultOrNull(): RecoveryResult? = (this as? ReconciliationPublicResult.ResumeRecovery)?.outcome
