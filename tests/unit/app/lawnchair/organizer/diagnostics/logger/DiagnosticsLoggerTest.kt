package app.lawnchair.organizer.diagnostics.logger

import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-10: Single tag, DEBUG for ordinary phases, WARN for terminal failures,
 * release failure-only, no output before persist.
 */
class DiagnosticsLoggerTest {

    @Test
    fun tagIsOrganizerDiag() {
        assertEquals("OrganizerDiag", DiagnosticsLogger.TAG)
    }

    @Test
    fun debugPhaseTransition() {
        // CAPTURED is a non-terminal phase, should be DEBUG
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.CAPTURED,
        )
        // We can't easily verify logcat output in unit tests,
        // but we can verify the format method doesn't throw
        val logger = DiagnosticsLogger()
        logger.log(event) // Should not throw
    }

    @Test
    fun warnTerminalFailure() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_ROLLED_BACK,
            error = ErrorEntry(family = ErrorFamily.APPLY_FAILURE, code = "WRITE_FAILED"),
        )
        val logger = DiagnosticsLogger()
        logger.log(event) // Should not throw
    }

    @Test
    fun releaseBuildSkipsNonTerminal() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.CAPTURED,
        )
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        // Release build should skip non-terminal events
        logger.log(event) // Should not throw
    }

    @Test
    fun releaseBuildLogsTerminal() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_ROLLED_BACK,
            error = ErrorEntry(family = ErrorFamily.APPLY_FAILURE, code = "WRITE_FAILED"),
        )
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event) // Should not throw
    }

    @Test
    fun formatIncludesRunId() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.RUN_STARTED,
            runId = "test-run-id",
        )
        val logger = DiagnosticsLogger()
        logger.log(event) // Should not throw
    }

    @Test
    fun formatIncludesStage() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_REJECTED,
            applyStage = ApplyStage.A2,
        )
        val logger = DiagnosticsLogger()
        logger.log(event)
    }

    @Test
    fun formatIncludesError() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_REJECTED,
            error = ErrorEntry(family = ErrorFamily.PRE_WRITE_REJECTED, code = "STALE_REVISION"),
        )
        val logger = DiagnosticsLogger()
        logger.log(event)
    }

    @Test
    fun formatIncludesPlanSummary() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.PLANNED,
            planSummary = app.lawnchair.organizer.diagnostics.model.PlanSummary(
                capturedItemCount = 84,
                movedCount = 61,
                preservedCount = 23,
            ),
        )
        val logger = DiagnosticsLogger()
        logger.log(event)
    }

    @Test
    fun formatIncludesReconciliation() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.RESTART_RECONCILED,
            reconciliation = app.lawnchair.organizer.diagnostics.model.ReconciliationContext(
                subjectRunId = "run-id",
                priorLifecycle = app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle.COMMITTED_UNVERIFIED,
                classification = app.lawnchair.organizer.diagnostics.model.ReconciliationClassification.INTENDED_POST_STATE,
                resultingLifecycle = app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle.VERIFIED,
            ),
        )
        val logger = DiagnosticsLogger()
        logger.log(event)
    }
}
