package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.ReconciliationPublicResult
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.projection.ReconciliationProjection

/**
 * Executes restart reconciliation; lifecycle classification alone is never treated as work.
 *
 * Issue #174: candidates are enumerated as bounded metadata and loaded one at
 * a time through the shared closed [RecoveryStorePort.RecordRead] result, so
 * one unreadable record degrades that record only. Containment is
 * lifecycle-sensitive (ADR-0009):
 *
 * - unreadable `CREATING`/`READY` records are transactionally quarantined —
 *   their durable lifecycle proves no Launcher mutation can have begun;
 * - unreadable `APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING` records keep
 *   their row, chunks, and lifecycle and surface an unresolved result so
 *   later organizer mutation stays fail-closed;
 * - unreadable `VERIFIED` points stay stored (non-restorable until normal
 *   retention) and reconcile silently;
 * - malformed metadata is preserved and fails the aggregate pass — it never
 *   authorizes deletion.
 */
internal class RestartReconciler(
    private val writer: LayoutWriterPort,
    private val faults: FaultInjector,
    private val diagnosticsPort: DiagnosticsPort = DiagnosticsPort.NOOP,
) {
    sealed interface ReconciliationSummary {
        data object Clean : ReconciliationSummary
        data class Resolved(
            val publicResults: List<ReconciliationPublicResult>,
        ) : ReconciliationSummary
        data object Incompatible : ReconciliationSummary
        data object Failed : ReconciliationSummary

        fun hasUnresolvedFailures(): Boolean = when (this) {
            Failed -> true

            Clean,
            Incompatible,
            -> false

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

    fun reconcileAll(session: RecoveryStoreReconciliationSession): ReconciliationSummary {
        if (!session.isActive()) return ReconciliationSummary.Failed
        when (session.availability()) {
            RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION -> {
                session.rebuildInspectionSnapshot()
                return ReconciliationSummary.Incompatible
            }

            RecoveryStorePort.StoreAvailability.READ_FAILED -> return ReconciliationSummary.Failed

            RecoveryStorePort.StoreAvailability.READY -> Unit
        }
        val candidates = session.listReconciliationCandidates() ?: return ReconciliationSummary.Failed
        var containmentFailure = false
        val surfaced = buildList {
            candidates.forEach { candidate ->
                faults.restartBoundary(FaultInjector.RestartPhase.BEFORE_RECONCILE)
                when (candidate) {
                    is ReconciliationCandidate.Malformed -> {
                        // Preserved, never speculatively deleted; fails the pass.
                        containmentFailure = true
                    }

                    is ReconciliationCandidate.Valid -> {
                        val meta = candidate.metadata
                        when (val read = session.loadRecord(meta)) {
                            null,
                            RecoveryStorePort.RecordRead.Failed,
                            -> containmentFailure = true

                            RecoveryStorePort.RecordRead.Missing -> Unit

                            is RecoveryStorePort.RecordRead.Unreadable -> {
                                val containment = containUnreadable(session, meta)
                                if (containment.quarantineRefused) containmentFailure = true
                                emitUnreadableEvent(meta, containment)
                                if (containment.surfaced) add(containment.result)
                            }

                            is RecoveryStorePort.RecordRead.Readable -> {
                                val record = read.record
                                // reconcileOne never returns null — every record gets a result
                                val result = reconcileOne(session, record)
                                emitReconciledEvent(session, record, result)
                                // Only add non-silent results to the public surfaced list.
                                // SilentPrune/SilentAdvance are internal outcomes that don't
                                // need to be surfaced to the public seam.
                                if (result !is ReconciliationPublicResult.SilentPrune &&
                                    result !is ReconciliationPublicResult.SilentAdvance
                                ) {
                                    add(result)
                                }
                            }
                        }
                    }
                }
                faults.restartBoundary(FaultInjector.RestartPhase.AFTER_RECONCILE)
            }
        }
        if (containmentFailure) return ReconciliationSummary.Failed
        if (session.runRetention(System.currentTimeMillis()) != RecoveryStorePort.RetentionOutcome.Applied) {
            return ReconciliationSummary.Failed
        }
        if (!session.rebuildInspectionSnapshot()) {
            return ReconciliationSummary.Failed
        }
        return if (surfaced.isEmpty()) ReconciliationSummary.Clean else ReconciliationSummary.Resolved(surfaced)
    }

    /** Lifecycle-sensitive containment decision for one unreadable record. */
    private data class Containment(
        val result: ReconciliationPublicResult,
        val surfaced: Boolean,
        val quarantined: Boolean = false,
        val quarantineRefused: Boolean = false,
    )

    private fun containUnreadable(
        session: RecoveryStoreReconciliationSession,
        meta: RecoveryStorePort.RecordMetadata,
    ): Containment = when (meta.lifecycle) {
        LifecycleState.CREATING, LifecycleState.READY -> {
            val quarantined = session.quarantineUnmutated(meta.pointId, meta.lifecycle)
            val result = unresolvedFor(meta)
            if (quarantined) {
                // The unreadable pre-mutation record is gone; the store is
                // clean. Surface nothing, but project the tombstoned lifecycle.
                Containment(result, surfaced = false, quarantined = true)
            } else {
                Containment(result, surfaced = true, quarantined = false, quarantineRefused = true)
            }
        }

        LifecycleState.APPLYING,
        LifecycleState.COMMITTED_UNVERIFIED,
        LifecycleState.RESTORING,
        -> {
            // Active states may need their manifest to recover a layout that
            // may already have been mutated. Preserve everything and keep the
            // aggregate unresolved.
            Containment(unresolvedFor(meta), surfaced = true)
        }

        LifecycleState.VERIFIED -> {
            // The recovery point itself: remains stored, projects
            // checksum-invalid, and is non-restorable until normal retention.
            Containment(ReconciliationPublicResult.SilentAdvance, surfaced = false)
        }

        else -> Containment(ReconciliationPublicResult.SilentAdvance, surfaced = false)
    }

    private fun unresolvedFor(meta: RecoveryStorePort.RecordMetadata): ReconciliationPublicResult = ReconciliationPublicResult.Unresolved(
        ApplyResult.Unresolved(
            meta.runId,
            meta.pointId,
            ApplyFailure.RECOVERY_STORE_FAILED,
            AuthoritativeState.UNKNOWN,
        ),
    )

    /** Quarantined records project `CORRUPT`; preserved records keep their lifecycle. */
    private fun emitUnreadableEvent(
        meta: RecoveryStorePort.RecordMetadata,
        containment: Containment,
    ) {
        try {
            val resultingLifecycle = when {
                containment.quarantined -> LifecycleState.CORRUPT
                else -> meta.lifecycle
            }
            val classification = classifyMeta(meta)
            val event = ReconciliationProjection.project(
                subjectRunId = meta.runId,
                priorLifecycle = meta.lifecycle,
                classification = classification,
                resultingLifecycle = resultingLifecycle,
                journalSequence = 0L,
                pointId = meta.pointId.value,
            )
            diagnosticsPort.emit(event)
        } catch (_: Exception) {
            // Fail-open
        }
    }

    private fun emitReconciledEvent(
        session: RecoveryStoreReconciliationSession,
        record: RecoveryStorePort.StoredRecord,
        result: ReconciliationPublicResult,
    ) {
        try {
            val classification = classify(record)
            // Use the result directly to determine the actual resulting lifecycle
            // (no destructive store re-read that could return Missing after prune).
            val resultingLifecycle = resultingLifecycleFor(session, result, record)
            val event = ReconciliationProjection.project(
                subjectRunId = record.runId,
                priorLifecycle = record.lifecycle,
                classification = classification,
                resultingLifecycle = resultingLifecycle,
                journalSequence = 0L,
                pointId = record.pointId.value,
            )
            diagnosticsPort.emit(event)
        } catch (_: Exception) {
            // Fail-open
        }
    }

    /**
     * Determines the actual post-reconciliation lifecycle from the result.
     * The store is the authoritative source for the current lifecycle state
     * after reconciliation; when the record is still present, its lifecycle
     * reflects exactly what advance/transition operations persisted. For
     * SilentPrune the record is gone, so READY is the correct fallback.
     */
    private fun resultingLifecycleFor(
        session: RecoveryStoreReconciliationSession,
        result: ReconciliationPublicResult,
        record: RecoveryStorePort.StoredRecord,
    ): LifecycleState {
        val actual = when (val read = session.loadRecord(metaOf(record))) {
            is RecoveryStorePort.RecordRead.Readable -> read.record.lifecycle
            else -> null
        }
        return actual ?: when (result) {
            is ReconciliationPublicResult.SilentPrune -> LifecycleState.READY

            is ReconciliationPublicResult.SilentAdvance -> record.lifecycle

            is ReconciliationPublicResult.ResumeApply -> when (result.outcome) {
                is ApplyResult.Applied -> LifecycleState.VERIFIED
                is ApplyResult.RolledBack -> LifecycleState.READY
                else -> LifecycleState.CORRUPT
            }

            is ReconciliationPublicResult.ResumeRecovery -> when (result.outcome) {
                is RecoveryResult.Restored -> LifecycleState.RESTORED
                else -> record.lifecycle
            }

            is ReconciliationPublicResult.Unresolved -> LifecycleState.CORRUPT
        }
    }

    private fun reconcileOne(
        session: RecoveryStoreReconciliationSession,
        record: RecoveryStorePort.StoredRecord,
    ): ReconciliationPublicResult {
        if (!record.checksumValid) {
            session.advance(record.pointId, LifecycleState.CORRUPT)
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        if (record.formatVersion != LifecycleReconciler.SUPPORTED_FORMAT) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        val lease = writer.tryAcquireLease(WriterKind.ORGANIZER, record.runId.value.hashCode().toLong())
            ?: return unresolved(record, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
        return try {
            reconcileWithLease(session, record, lease)
        } finally {
            lease.close()
        }
    }

    private fun reconcileWithLease(
        session: RecoveryStoreReconciliationSession,
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): ReconciliationPublicResult {
        val authoritative = classify(record)
        return when (record.lifecycle) {
            LifecycleState.CREATING -> when (authoritative) {
                AuthoritativeClass.PRE_STATE -> {
                    if (!session.advance(record.pointId, LifecycleState.READY) ||
                        !session.pruneUnused(record.pointId)
                    ) {
                        unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                    } else {
                        ReconciliationPublicResult.SilentPrune
                    }
                }

                else -> {
                    session.advance(record.pointId, LifecycleState.CORRUPT)
                    unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                }
            }

            LifecycleState.READY -> if (authoritative == AuthoritativeClass.PRE_STATE) {
                if (!session.pruneUnused(record.pointId)) {
                    unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                } else {
                    ReconciliationPublicResult.SilentPrune
                }
            } else {
                unresolved(record, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.APPLYING -> when (authoritative) {
                AuthoritativeClass.PRE_STATE -> {
                    if (!session.advance(record.pointId, LifecycleState.READY) ||
                        !session.pruneUnused(record.pointId)
                    ) {
                        unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                    } else {
                        ReconciliationPublicResult.SilentPrune
                    }
                }

                AuthoritativeClass.INTENDED_POST_STATE -> finishCommittedApply(session, record, lease)

                else -> recover(session, record, lease, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.COMMITTED_UNVERIFIED -> when (authoritative) {
                AuthoritativeClass.INTENDED_POST_STATE -> finishCommittedApply(session, record, lease)

                AuthoritativeClass.PRE_STATE -> {
                    if (!session.advance(record.pointId, LifecycleState.READY) ||
                        !session.pruneUnused(record.pointId)
                    ) {
                        unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
                    } else {
                        ReconciliationPublicResult.ResumeApply(
                            ApplyResult.RolledBack(record.runId, ApplyFailure.COMMIT_OUTCOME_UNKNOWN),
                        )
                    }
                }

                else -> recover(session, record, lease, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.RESTORING -> when (authoritative) {
                AuthoritativeClass.PRE_STATE, AuthoritativeClass.RECOVERY_TARGET -> finishRestored(session, record, lease)
                else -> recover(session, record, lease, ApplyFailure.COMMIT_OUTCOME_UNKNOWN)
            }

            LifecycleState.VERIFIED -> ReconciliationPublicResult.SilentAdvance

            else -> ReconciliationPublicResult.SilentAdvance
        }
    }

    private fun finishCommittedApply(
        session: RecoveryStoreReconciliationSession,
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): ReconciliationPublicResult {
        if (record.lifecycle == LifecycleState.APPLYING &&
            !session.advance(record.pointId, LifecycleState.COMMITTED_UNVERIFIED)
        ) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        // Issue #152: the completed reload carries the model snapshot; staleness
        // is excluded inside the adapter, never here.
        val completed = writer.requestCorrelatedReload(lease) as? ReloadResult.Completed
        if (completed == null) {
            return recover(session, record, lease, ApplyFailure.MODEL_RELOAD_FAILED)
        }
        val db = writer.recaptureDb()
        if (db.manifest != record.intendedManifest) {
            return recover(session, record, lease, ApplyFailure.VERIFICATION_FAILED)
        }
        // Issue #152: DB/model convergence on the model-verifiable projection
        // before the resumed Applied is returned.
        if (db.layoutState.projectedToModelVerifiable(writer::legacyLaunchIdentityOf).items != completed.modelSnapshot.items) {
            return recover(session, record, lease, ApplyFailure.VERIFICATION_FAILED)
        }
        if (!session.advance(record.pointId, LifecycleState.VERIFIED)) {
            return unresolved(record, ApplyFailure.RECOVERY_STORE_FAILED)
        }
        return ReconciliationPublicResult.ResumeApply(ApplyResult.Applied(record.runId, record.pointId))
    }

    private fun finishRestored(
        session: RecoveryStoreReconciliationSession,
        record: RecoveryStorePort.StoredRecord,
        lease: LeaseHandle,
    ): ReconciliationPublicResult {
        // Issue #152: the completed reload carries the model snapshot; staleness
        // is excluded inside the adapter, never here.
        val completed = writer.requestCorrelatedReload(lease) as? ReloadResult.Completed
        if (completed == null) {
            return ReconciliationPublicResult.ResumeRecovery(
                RecoveryResult.RestoreFailed(
                    record.pointId,
                    RecoveryFailure.MODEL_RELOAD_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                ),
            )
        }
        val db = writer.recaptureDb()
        if (db.manifest != record.preManifest) {
            return ReconciliationPublicResult.ResumeRecovery(
                RecoveryResult.RestoreFailed(
                    record.pointId,
                    RecoveryFailure.VERIFICATION_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                ),
            )
        }
        // Issue #152: DB/model convergence on the model-verifiable projection
        // before the resumed Restored is returned.
        if (db.layoutState.projectedToModelVerifiable(writer::legacyLaunchIdentityOf).items != completed.modelSnapshot.items) {
            return ReconciliationPublicResult.ResumeRecovery(
                RecoveryResult.RestoreFailed(
                    record.pointId,
                    RecoveryFailure.VERIFICATION_FAILED,
                    AuthoritativeState.PRE_APPLY_DB_MODEL_UNVERIFIED,
                ),
            )
        }
        if (!session.advance(record.pointId, LifecycleState.RESTORED)) {
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
        session: RecoveryStoreReconciliationSession,
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
        if (!session.markRestoring(
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
        val reloaded = when (val read = session.loadRecord(metaOf(record))) {
            is RecoveryStorePort.RecordRead.Readable -> read.record
            else -> record
        }
        return finishRestored(session, reloaded, lease)
    }

    private fun classify(record: RecoveryStorePort.StoredRecord): AuthoritativeClass = classifyMeta(metaOf(record))

    private fun classifyMeta(meta: RecoveryStorePort.RecordMetadata): AuthoritativeClass = writer.classifyAuthoritativeState(
        preDigest = meta.preDigest,
        intendedPostDigest = meta.intendedDigest,
        recoveryTargetDigest = meta.preDigest,
        reviewedCurrentDigest = meta.reviewedDigest,
    )

    private fun metaOf(record: RecoveryStorePort.StoredRecord): RecoveryStorePort.RecordMetadata = RecoveryStorePort.RecordMetadata(
        pointId = record.pointId,
        runId = record.runId,
        lifecycle = record.lifecycle,
        priorLifecycle = record.priorLifecycle,
        createdAtMs = record.createdAtMs,
        updatedAtMs = record.updatedAtMs,
        formatVersion = record.formatVersion,
        preDigest = record.preDigest,
        intendedDigest = record.intendedDigest,
        reviewedDigest = record.reviewedDigest,
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
