package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.lifecycle.LifecycleState

/**
 * Issue #377: the single restart/in-flight reconciliation decision table.
 *
 * The classification result (`AuthoritativeClass`) is supplied by the shared
 * `classifyAuthoritativeState` primitive; this table owns the remaining
 * per-context decision — which lifecycle transition the classification maps
 * to and which kind of public result the caller must observe. Path context is
 * an explicit input: the same (lifecycle × class) cell intentionally
 * classifies differently between the restart path (no caller to surface to)
 * and the in-flight paths (a caller owns the outcome), exactly as spec 13
 * keeps "Transaction outcome classification" and "Restart reconciliation" as
 * two tables over one shared row space.
 *
 * Pure: no store writes, no lease, no reload, no result construction — the
 * protocol layers keep the side effects (advance/prune/quarantine, reload,
 * verification, recovery resume) and assemble the typed results with their
 * own run/point identities.
 */
enum class ReconciliationPathContext { RESTART, IN_FLIGHT_APPLY, IN_FLIGHT_RECOVERY }

/**
 * The decision for one (path context × lifecycle × AuthoritativeClass) cell.
 *
 * @param nextLifecycle the lifecycle the record must be advanced to by the
 *   protocol layer (an unchanged lifecycle is expressed as the same value).
 * @param pruneRecord true when the protocol layer must prune the unused
 *   record after the transition.
 * @param surface the kind of public result the caller must observe; [Surface]
 *   is the protocol-side assembly contract, not a constructed result.
 */
data class ReconciliationDecision(
    val nextLifecycle: LifecycleState,
    val pruneRecord: Boolean,
    val surface: Surface,
) {
    enum class Surface {
        /** No public result: the reconciliation completes silently. */
        SILENT,

        /** Restart path: surface an `Unresolved` result with the given failure. */
        UNRESOLVED_RESTART,

        /** Restart path: surface `ResumeApply(RolledBack(COMMIT_OUTCOME_UNKNOWN))`. */
        ROLLED_BACK_RESTART,

        /** In-flight apply: the caller observes `RolledBack`. */
        ROLLED_BACK_APPLY,

        /** In-flight apply / restart: continue the committed apply flow. */
        CONTINUE_COMMITTED_APPLY,

        /** In-flight apply: attempt automatic recovery. */
        ATTEMPT_RECOVERY,

        /** In-flight / restart recovery: complete the restore. */
        COMPLETE_RESTORE,

        /** In-flight recovery: the restore did not commit; surface the typed failure. */
        RESTORE_NOT_COMMITTED,

        /** Restart path: keep the lifecycle and surface the unresolved directly. */
        UNRESOLVED_KEEP,
    }
}

/**
 * The single decision table. Each `decide*` function is one path context's
 * view over the shared row space; the cells encode the adjudicated matrix
 * from spec 377 plan Current evidence (rules (a)/(b) applied) and spec 13.
 */
object ReconciliationDecisionTable {

    /**
     * Restart reconciliation (no caller to surface to; classification of a
     * stored record at process start). Checksum-invalid and unsupported-format
     * records never reach this table — their gates advance CORRUPT and
     * INCOMPATIBLE directly before the classification.
     */
    fun decideRestart(lifecycle: LifecycleState, authoritative: AuthoritativeClass): ReconciliationDecision = when (lifecycle) {
        LifecycleState.CREATING -> when (authoritative) {
            AuthoritativeClass.PRE_STATE -> silentPrune()
            else -> corruptRestart()
        }

        LifecycleState.READY -> when (authoritative) {
            AuthoritativeClass.PRE_STATE -> silentPrune()

            // Adjudicated row: a READY record past its checkpoint keeps its
            // lifecycle and surfaces the fail-closed unresolved directly —
            // READY -> RESTORING is illegal, so this row never enters the
            // recovery path.
            else -> ReconciliationDecision(
                nextLifecycle = LifecycleState.READY,
                pruneRecord = false,
                ReconciliationDecision.Surface.UNRESOLVED_KEEP,
            )
        }

        LifecycleState.APPLYING -> when (authoritative) {
            AuthoritativeClass.PRE_STATE -> silentPrune()
            AuthoritativeClass.INTENDED_POST_STATE -> committed()
            else -> unresolvedRestart()
        }

        LifecycleState.COMMITTED_UNVERIFIED -> when (authoritative) {
            AuthoritativeClass.INTENDED_POST_STATE -> committed()
            AuthoritativeClass.PRE_STATE -> rolledBackRestart()
            else -> unresolvedRestart()
        }

        LifecycleState.RESTORING -> when (authoritative) {
            AuthoritativeClass.PRE_STATE, AuthoritativeClass.RECOVERY_TARGET -> restored()
            else -> unresolvedRestart()
        }

        LifecycleState.VERIFIED -> silentAdvance()

        else -> silentAdvance()
    }

    /**
     * In-flight apply outcome classification (spec 13 "Transaction outcome
     * classification"): the caller observes RolledBack / continues the
     * committed flow / attempts recovery itself.
     */
    fun decideInFlightApply(authoritative: AuthoritativeClass): ReconciliationDecision = when (authoritative) {
        AuthoritativeClass.PRE_STATE -> rolledBackInFlight()
        AuthoritativeClass.INTENDED_POST_STATE -> committed()
        else -> attemptRecovery()
    }

    /**
     * In-flight recovery outcome classification (spec 13 "Recovery protocol"
     * step 6): PRE_STATE and RECOVERY_TARGET mean the restore committed;
     * anything else did not and the caller surfaces the typed failure.
     */
    fun decideInFlightRecovery(authoritative: AuthoritativeClass): ReconciliationDecision = when (authoritative) {
        AuthoritativeClass.PRE_STATE, AuthoritativeClass.RECOVERY_TARGET -> restored()
        else -> restoreNotCommitted()
    }

    private fun silentPrune() = ReconciliationDecision(LifecycleState.READY, pruneRecord = true, ReconciliationDecision.Surface.SILENT)
    private fun silentAdvance() = ReconciliationDecision(LifecycleState.VERIFIED, pruneRecord = false, ReconciliationDecision.Surface.SILENT)
    private fun corruptRestart() = ReconciliationDecision(LifecycleState.CORRUPT, pruneRecord = false, ReconciliationDecision.Surface.UNRESOLVED_RESTART)
    private fun unresolvedRestart() = ReconciliationDecision(LifecycleState.RESTORING, pruneRecord = false, ReconciliationDecision.Surface.UNRESOLVED_RESTART)
    private fun rolledBackRestart() = ReconciliationDecision(LifecycleState.READY, pruneRecord = true, ReconciliationDecision.Surface.ROLLED_BACK_RESTART)
    private fun rolledBackInFlight() = ReconciliationDecision(LifecycleState.READY, pruneRecord = true, ReconciliationDecision.Surface.ROLLED_BACK_APPLY)
    private fun committed() = ReconciliationDecision(LifecycleState.COMMITTED_UNVERIFIED, pruneRecord = false, ReconciliationDecision.Surface.CONTINUE_COMMITTED_APPLY)
    private fun attemptRecovery() = ReconciliationDecision(LifecycleState.RESTORING, pruneRecord = false, ReconciliationDecision.Surface.ATTEMPT_RECOVERY)
    private fun restored() = ReconciliationDecision(LifecycleState.RESTORED, pruneRecord = false, ReconciliationDecision.Surface.COMPLETE_RESTORE)
    private fun restoreNotCommitted() = ReconciliationDecision(LifecycleState.RESTORING, pruneRecord = false, ReconciliationDecision.Surface.RESTORE_NOT_COMMITTED)
}
