package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryResult

/**
 * Public result surfaced from restart reconciliation, if any. Most
 * reconciliations simply advance the lifecycle silently; only interrupted
 * apply/recovery surface a typed public result.
 *
 * Issue #377: moved from the lifecycle package together with the retirement
 * of the unreachable pure `LifecycleReconciler` duplicate — the live decision
 * table lives in the protocol layer (`RestartReconciler`, `ApplyProtocol`,
 * `RecoveryProtocol`), so the shared result vocabulary lives there too.
 */
sealed interface ReconciliationPublicResult {
    data object SilentPrune : ReconciliationPublicResult
    data object SilentAdvance : ReconciliationPublicResult
    data class ResumeApply(val outcome: ApplyResult) : ReconciliationPublicResult
    data class ResumeRecovery(val outcome: RecoveryResult) : ReconciliationPublicResult
    data class Unresolved(val outcome: ApplyResult) : ReconciliationPublicResult
}

fun ReconciliationPublicResult.asApplyResultOrNull(): ApplyResult? = (this as? ReconciliationPublicResult.ResumeApply)?.outcome
    ?: (this as? ReconciliationPublicResult.Unresolved)?.outcome

fun ReconciliationPublicResult.asRecoveryResultOrNull(): RecoveryResult? = (this as? ReconciliationPublicResult.ResumeRecovery)?.outcome
