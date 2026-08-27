package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId

/**
 * Authoritative Launcher DB/model classification at restart or after an
 * uncertain transaction close. The reconciler uses this to drive lifecycle
 * transitions per spec §§“Transaction outcome classification”, “Restart
 * reconciliation”.
 *
 * The classification is supplied by the protocol layer after re-reading the
 * Launcher state and comparing its canonical digest to the stored pre-state
 * and intended-post-state digests; the reconciler never touches SQLite or
 * platform state.
 *
 * Issue #14 Stage B step 2.
 */
sealed interface AuthoritativeDigestClass {
    data object PreState : AuthoritativeDigestClass
    data object IntendedPostState : AuthoritativeDigestClass
    data object RecoveryTarget : AuthoritativeDigestClass
    data object ReviewedCurrentState : AuthoritativeDigestClass
    data object Neither : AuthoritativeDigestClass
}

/**
 * Snapshot of a persisted recovery record plus its digests, supplied to the
 * reconciler. The reconciler never depends on the in-memory plan; everything
 * it needs lives on this record.
 */
data class ReconcilerRecord(
    val pointId: RecoveryPointId,
    val runId: RunId,
    val lifecycle: LifecycleState,
    val priorLifecycle: LifecycleState?,
    val hasStoredPostIntent: Boolean,
    val hasReviewedCurrentManifest: Boolean,
    val formatVersion: Int,
    val checksumValid: Boolean,
)

/**
 * Pure reconciliation result. The protocol layer is responsible for actually
 * writing the [nextLifecycle] to the recovery DB and for any Launcher reload
 * implied by [publicResult].
 */
data class ReconciliationOutcome(
    val nextLifecycle: LifecycleState,
    val publicResult: ReconciliationPublicResult,
    val launcherReloadRequired: Boolean,
)

/**
 * Public result surfaced from restart reconciliation, if any. Most
 * reconciliations simply advance the lifecycle silently; only interrupted
 * apply/recovery surface a typed public result.
 */
sealed interface ReconciliationPublicResult {
    data object SilentPrune : ReconciliationPublicResult
    data object SilentAdvance : ReconciliationPublicResult
    data class ResumeApply(val outcome: ApplyResult) : ReconciliationPublicResult
    data class ResumeRecovery(val outcome: RecoveryResult) : ReconciliationPublicResult
    data class Unresolved(val outcome: ApplyResult) : ReconciliationPublicResult
}

/**
 * The brain that makes restart reconciliation work without the in-memory plan.
 *
 * Each row corresponds to one cell of spec §§“Restart reconciliation”,
 * “Transaction outcome classification”. The protocol layer invokes it after
 * re-reading the authoritative Launcher state and classifying its digest
 * against the stored pre/post/current digests.
 *
 * Issue #14 Stage B step 2.
 */
object LifecycleReconciler {

    /**
     * Reconcile a record discovered at process start. Returns the next
     * lifecycle state and any public result that must be surfaced.
     *
     * Assumes the caller has already classified the checksum and format.
     */
    fun reconcile(record: ReconcilerRecord, digest: AuthoritativeDigestClass): ReconciliationOutcome {
        if (!record.checksumValid) {
            return corrupt(record)
        }
        if (record.formatVersion != SUPPORTED_FORMAT) {
            return incompatible(record)
        }
        return when (record.lifecycle) {
            LifecycleState.CREATING -> reconcileCreating(record, digest)

            LifecycleState.READY -> reconcileReady(record, digest)

            LifecycleState.APPLYING -> reconcileApplying(record, digest)

            LifecycleState.COMMITTED_UNVERIFIED -> reconcileCommittedUnverified(record, digest)

            LifecycleState.VERIFIED -> reconcileVerified(record, digest)

            LifecycleState.RESTORING -> reconcileRestoring(record, digest)

            else -> ReconciliationOutcome(
                nextLifecycle = record.lifecycle,
                publicResult = ReconciliationPublicResult.SilentAdvance,
                launcherReloadRequired = false,
            )
        }
    }

    /**
     * Classify the outcome of an in-flight apply transaction after a write
     * failure, close exception, or model reload/convergence failure.
     *
     * Spec §“Transaction outcome classification”.
     */
    fun classifyApplyOutcome(
        record: ReconcilerRecord,
        digest: AuthoritativeDigestClass,
    ): ApplyOutcomeClassification {
        return when (digest) {
            AuthoritativeDigestClass.PreState -> ApplyOutcomeClassification.RolledBack(
                nextLifecycle = LifecycleState.READY,
                pruneRecord = true,
            )

            AuthoritativeDigestClass.IntendedPostState -> ApplyOutcomeClassification.Committed(
                nextLifecycle = LifecycleState.COMMITTED_UNVERIFIED,
                continueReloadAndVerify = true,
            )

            else -> ApplyOutcomeClassification.NeedsRecovery(
                nextLifecycle = LifecycleState.RESTORING,
            )
        }
    }

    /**
     * Classify the outcome of an in-flight recovery transaction after a write
     * failure, close exception, or model reload/verification failure.
     *
     * Spec §§“Recovery protocol” step 6, “Restart reconciliation”.
     */
    fun classifyRecoveryOutcome(
        record: ReconcilerRecord,
        digest: AuthoritativeDigestClass,
    ): RecoveryOutcomeClassification {
        return when (digest) {
            AuthoritativeDigestClass.RecoveryTarget -> RecoveryOutcomeClassification.Restored(
                nextLifecycle = LifecycleState.RESTORED,
            )

            AuthoritativeDigestClass.ReviewedCurrentState -> RecoveryOutcomeClassification.NotCommitted(
                // Restore prior lifecycle; protocol layer will resume or surface.
                nextLifecycle = record.priorLifecycle ?: LifecycleState.VERIFIED,
            )

            else -> RecoveryOutcomeClassification.NeedsRecovery(
                nextLifecycle = LifecycleState.RESTORING,
            )
        }
    }

    private fun reconcileCreating(record: ReconcilerRecord, digest: AuthoritativeDigestClass): ReconciliationOutcome {
        // Spec: a committed CREATING record whose Launcher state is still the stored
        // pre-state becomes unused READY and is pruned; an incomplete record becomes CORRUPT.
        // We can't observe "incomplete" directly; if the digest class is PreState we treat
        // the record as committed-but-unused and prune. Otherwise it is corrupt.
        return if (digest == AuthoritativeDigestClass.PreState) {
            ReconciliationOutcome(
                nextLifecycle = LifecycleState.READY,
                publicResult = ReconciliationPublicResult.SilentPrune,
                launcherReloadRequired = false,
            )
        } else {
            corrupt(record)
        }
    }

    private fun reconcileReady(record: ReconcilerRecord, digest: AuthoritativeDigestClass): ReconciliationOutcome {
        // READY means checkpoint committed; Launcher DB unchanged. Reconciliation prunes it.
        return if (digest == AuthoritativeDigestClass.PreState) {
            ReconciliationOutcome(
                nextLifecycle = LifecycleState.READY,
                publicResult = ReconciliationPublicResult.SilentPrune,
                launcherReloadRequired = false,
            )
        } else {
            // Launcher state advanced past the checkpoint without an APPLYING record:
            // unusual but not corrupt. Prune silently.
            ReconciliationOutcome(
                nextLifecycle = LifecycleState.READY,
                publicResult = ReconciliationPublicResult.SilentPrune,
                launcherReloadRequired = false,
            )
        }
    }

    private fun reconcileApplying(record: ReconcilerRecord, digest: AuthoritativeDigestClass): ReconciliationOutcome {
        // Spec: interrupted apply.
        return when (digest) {
            AuthoritativeDigestClass.PreState -> ReconciliationOutcome(
                // Apply did not commit. Prune unused READY (set READY then immediately prune).
                nextLifecycle = LifecycleState.READY,
                publicResult = ReconciliationPublicResult.SilentPrune,
                launcherReloadRequired = false,
            )

            AuthoritativeDigestClass.IntendedPostState -> ReconciliationOutcome(
                nextLifecycle = LifecycleState.COMMITTED_UNVERIFIED,
                publicResult = ReconciliationPublicResult.ResumeApply(
                    ApplyResult.Applied(runId = record.runId, pointId = record.pointId),
                ),
                launcherReloadRequired = true,
            )

            else -> unresolvedNeither(record)
        }
    }

    private fun reconcileCommittedUnverified(
        record: ReconcilerRecord,
        digest: AuthoritativeDigestClass,
    ): ReconciliationOutcome {
        // Spec: death after layout commit, before verification.
        return when (digest) {
            AuthoritativeDigestClass.IntendedPostState -> ReconciliationOutcome(
                nextLifecycle = LifecycleState.VERIFIED,
                publicResult = ReconciliationPublicResult.ResumeApply(
                    ApplyResult.Applied(runId = record.runId, pointId = record.pointId),
                ),
                launcherReloadRequired = true,
            )

            AuthoritativeDigestClass.PreState -> ReconciliationOutcome(
                // Rolled back after commit was believed to have happened — surface rolled-back.
                nextLifecycle = LifecycleState.READY,
                publicResult = ReconciliationPublicResult.ResumeApply(
                    ApplyResult.RolledBack(
                        runId = record.runId,
                        failure = ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                    ),
                ),
                launcherReloadRequired = false,
            )

            else -> unresolvedNeither(record)
        }
    }

    private fun reconcileVerified(record: ReconcilerRecord, digest: AuthoritativeDigestClass): ReconciliationOutcome {
        // VERIFIED is a restorable, terminal-ish state. Reconciliation does not advance it
        // except for retention or explicit recovery.
        return ReconciliationOutcome(
            nextLifecycle = LifecycleState.VERIFIED,
            publicResult = ReconciliationPublicResult.SilentAdvance,
            launcherReloadRequired = false,
        )
    }

    private fun reconcileRestoring(record: ReconcilerRecord, digest: AuthoritativeDigestClass): ReconciliationOutcome {
        // Spec: death during RESTORING. Use the stored reviewed-current manifest to
        // disambiguate.
        return when (digest) {
            AuthoritativeDigestClass.RecoveryTarget -> ReconciliationOutcome(
                nextLifecycle = LifecycleState.RESTORED,
                publicResult = ReconciliationPublicResult.ResumeRecovery(
                    RecoveryResult.Restored(pointId = record.pointId),
                ),
                launcherReloadRequired = true,
            )

            AuthoritativeDigestClass.ReviewedCurrentState -> ReconciliationOutcome(
                // Recovery did not commit. Restore prior lifecycle and resume.
                nextLifecycle = record.priorLifecycle ?: LifecycleState.VERIFIED,
                publicResult = ReconciliationPublicResult.ResumeRecovery(
                    RecoveryResult.RestoreFailed(
                        pointId = record.pointId,
                        failure = RecoveryFailure.COMMIT_OUTCOME_UNKNOWN,
                        authoritativeState = AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
                    ),
                ),
                launcherReloadRequired = false,
            )

            else -> unresolvedNeither(record)
        }
    }

    private fun corrupt(record: ReconcilerRecord): ReconciliationOutcome = ReconciliationOutcome(
        nextLifecycle = LifecycleState.CORRUPT,
        publicResult = ReconciliationPublicResult.Unresolved(
            ApplyResult.Unresolved(
                runId = record.runId,
                pointId = record.pointId,
                failure = ApplyFailure.RECOVERY_STORE_FAILED,
                authoritativeState = AuthoritativeState.UNKNOWN,
            ),
        ),
        launcherReloadRequired = false,
    )

    private fun incompatible(record: ReconcilerRecord): ReconciliationOutcome = ReconciliationOutcome(
        nextLifecycle = LifecycleState.INCOMPATIBLE,
        publicResult = ReconciliationPublicResult.Unresolved(
            ApplyResult.Unresolved(
                runId = record.runId,
                pointId = record.pointId,
                failure = ApplyFailure.RECOVERY_STORE_FAILED,
                authoritativeState = AuthoritativeState.UNKNOWN,
            ),
        ),
        launcherReloadRequired = false,
    )

    private fun unresolvedNeither(record: ReconcilerRecord): ReconciliationOutcome = ReconciliationOutcome(
        nextLifecycle = LifecycleState.RESTORING,
        publicResult = ReconciliationPublicResult.Unresolved(
            ApplyResult.Unresolved(
                runId = record.runId,
                pointId = record.pointId,
                failure = ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                authoritativeState = AuthoritativeState.UNKNOWN,
            ),
        ),
        launcherReloadRequired = true,
    )

    /** Outcome of classifying an apply transaction's authoritative state. */
    sealed interface ApplyOutcomeClassification {
        data class RolledBack(val nextLifecycle: LifecycleState, val pruneRecord: Boolean) : ApplyOutcomeClassification
        data class Committed(
            val nextLifecycle: LifecycleState,
            val continueReloadAndVerify: Boolean,
        ) : ApplyOutcomeClassification
        data class NeedsRecovery(val nextLifecycle: LifecycleState) : ApplyOutcomeClassification
    }

    /** Outcome of classifying a recovery transaction's authoritative state. */
    sealed interface RecoveryOutcomeClassification {
        data class Restored(val nextLifecycle: LifecycleState) : RecoveryOutcomeClassification
        data class NotCommitted(val nextLifecycle: LifecycleState) : RecoveryOutcomeClassification
        data class NeedsRecovery(val nextLifecycle: LifecycleState) : RecoveryOutcomeClassification
    }

    /** Issue #155: v2 records carry the reservation-aware capture context. */
    const val SUPPORTED_FORMAT: Int = 2
}
