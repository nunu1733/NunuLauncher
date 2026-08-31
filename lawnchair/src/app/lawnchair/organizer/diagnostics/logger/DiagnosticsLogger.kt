package app.lawnchair.organizer.diagnostics.logger

import android.util.Log
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Single-tag redacted logcat sink for organizer diagnostics.
 *
 * Contract §10 rules:
 * - Single tag: `OrganizerDiag` (constant).
 * - Render only after successful journal append (enforced by the caller).
 * - DEBUG for ordinary phase transitions, WARN for terminal failures.
 * - Release build: failure-only (terminal failures only).
 * - No Never-classified values are ever rendered.
 *
 * The renderer produces a single line per event with the format:
 *   `run=<runId> phase=<phase> stage=<A0-A8> err=<family>.<code> counts...`
 *
 * "Terminal failure" phases: `*_REJECTED`, `*_FAILED`, `*_ROLLED_BACK`, `*_UNRESOLVED`.
 */
class DiagnosticsLogger(
    private val isReleaseBuild: Boolean = false,
) {
    companion object {
        const val TAG: String = "OrganizerDiag"
    }

    /**
     * Terminal failure phases: contract §10 restricts WARN to
     * `*_REJECTED`, `*_FAILED`, `*_ROLLED_BACK`, `*_UNRESOLVED`.
     * Phases like APPLY_NO_CHANGES, USER_CANCELLED, PLANNING_IMPOSSIBLE,
     * APPLY_RECOVERED, RECOVERY_WRITER_BUSY, RECOVERY_CONCURRENT are
     * NOT failures and are not included.
     * `INPUT_NOT_READY` (issue #172) is terminal and journaled with a
     * readiness code, so it joins the WARN set.
     */
    private val terminalFailurePhases: Set<PhaseCode> = setOf(
        PhaseCode.PLANNING_REJECTED,
        PhaseCode.INPUT_NOT_READY,
        PhaseCode.CHECKPOINT_REJECTED,
        PhaseCode.APPLY_REJECTED,
        PhaseCode.CONCURRENT_RUN_REJECTED,
        PhaseCode.RECOVERY_REJECTED,
        PhaseCode.RECOVERY_FAILED,
        PhaseCode.APPLY_RECOVERY_FAILED,
        PhaseCode.APPLY_ROLLED_BACK,
        PhaseCode.APPLY_UNRESOLVED,
    )

    /**
     * Returns the logcat level that would be used for the given event:
     * "WARN" for terminal failures, "DEBUG" otherwise.
     */
    fun levelFor(event: RunEvent): String = if (event.phase in terminalFailurePhases) "WARN" else "DEBUG"

    /**
     * Log an event to logcat.
     *
     * Must be called only after the event has been successfully
     * persisted to the journal.
     */
    fun log(event: RunEvent) {
        val isTerminal = event.phase in terminalFailurePhases

        // Release build: skip non-terminal events
        if (isReleaseBuild && !isTerminal) return

        val message = format(event)
        try {
            if (isTerminal) {
                Log.w(TAG, message)
            } else {
                Log.d(TAG, message)
            }
        } catch (_: RuntimeException) {
            // Fail-open: logcat may not be available in all environments
        }
    }

    /**
     * Issue #172: capture-side failure detail, debug builds only.
     *
     * Contract §7 bounded exception: only the exception class simple name may
     * appear. The API takes no String parameter, so no caller can feed
     * message/layout-derived text through this seam; raw `Throwable.message`
     * and stack traces stay Never on every surface. The class simple name
     * (e.g. `SQLiteBlobTooBigException`) is the normalized failure identity —
     * the platform exposes no typed numeric error-code accessor, so none is
     * carried.
     */
    fun logCaptureFailure(exceptionClass: Class<out Throwable>) {
        if (isReleaseBuild) return
        try {
            Log.d(TAG, formatCaptureFailure(exceptionClass))
        } catch (_: RuntimeException) {
            // Fail-open: logcat may not be available in all environments
        }
    }

    /**
     * Single-line rendering of [logCaptureFailure]. Pure function so the
     * contract (class simple name only, no message/stack trace) is assertable
     * without touching logcat.
     */
    fun formatCaptureFailure(exceptionClass: Class<out Throwable>): String = "phase=CAPTURE exceptionClass=${exceptionClass.simpleName}"

    fun format(event: RunEvent): String {
        val parts = mutableListOf<String>()

        // runId (if present)
        event.runId?.let { parts.add("run=$it") }

        // phase
        parts.add("phase=${event.phase.name}")

        // applyStage (if present)
        event.applyStage?.let { parts.add("stage=${it.name}") }

        // error (if present)
        event.error?.let { err ->
            parts.add("err=${err.family.name}.${err.code}")
            err.reasonTotal?.let { parts.add("reasons=$it") }
        }

        // planSummary counts
        event.planSummary?.let { ps ->
            parts.add("captured=${ps.capturedItemCount}")
            parts.add("moved=${ps.movedCount}")
            parts.add("preserved=${ps.preservedCount}")
            ps.unplacedCount.takeIf { it > 0 }?.let { parts.add("unplaced=$it") }
        }

        // applySummary counts
        event.applySummary?.let { asm ->
            parts.add("preserveActions=${asm.preserveActionCount}")
            parts.add("updateActions=${asm.updateActionCount}")
            parts.add("insertActions=${asm.insertActionCount}")
        }

        // reconciliation
        event.reconciliation?.let { rc ->
            parts.add("subjectRun=${rc.subjectRunId}")
            parts.add("priorLifecycle=${rc.priorLifecycle.name}")
            parts.add("classification=${rc.classification.name}")
            parts.add("resultLifecycle=${rc.resultingLifecycle.name}")
        }

        return parts.joinToString(" ")
    }
}
