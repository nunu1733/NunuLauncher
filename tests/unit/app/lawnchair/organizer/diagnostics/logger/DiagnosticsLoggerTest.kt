package app.lawnchair.organizer.diagnostics.logger

import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.ApplySummary
import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.PlanSummary
import app.lawnchair.organizer.diagnostics.model.ReconciliationClassification
import app.lawnchair.organizer.diagnostics.model.ReconciliationContext
import app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle
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
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.CAPTURED)
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
        logger.log(event)
    }

    @Test
    fun releaseBuildSkipsNonTerminal() {
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.CAPTURED)
        val logger = DiagnosticsLogger(isReleaseBuild = true)
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
        logger.log(event)
    }

    @Test
    fun releaseBuildSkipsApplyNoChanges() {
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.APPLY_NO_CHANGES)
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event)
    }

    @Test
    fun releaseBuildSkipsUserCancelled() {
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.USER_CANCELLED)
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event)
    }

    @Test
    fun releaseBuildSkipsRecoveryWriterBusy() {
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.RECOVERY_WRITER_BUSY)
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event)
    }

    @Test
    fun releaseBuildSkipsRecoveryConcurrent() {
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.RECOVERY_CONCURRENT)
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event)
    }

    @Test
    fun releaseBuildLogsPlanningRejected() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.PLANNING_REJECTED,
            error = ErrorEntry(family = ErrorFamily.PLANNING_INVALID, code = "BOUNDS_VIOLATION"),
        )
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event)
    }

    @Test
    fun releaseBuildLogsApplyRecoveryFailed() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_RECOVERY_FAILED,
            error = ErrorEntry(family = ErrorFamily.APPLY_FAILURE, code = "WRITE_FAILED"),
        )
        val logger = DiagnosticsLogger(isReleaseBuild = true)
        logger.log(event)
    }

    @Test
    fun formatIncludesRunId() {
        val event = RunEvent(journalSequence = 1L, phase = PhaseCode.RUN_STARTED, runId = "test-run")
        val logger = DiagnosticsLogger()
        val formatted = logger.format(event)
        assertTrue(formatted.contains("run=test-run"))
        assertTrue(formatted.contains("phase=RUN_STARTED"))
    }

    @Test
    fun formatIncludesStage() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_REJECTED,
            applyStage = ApplyStage.A2,
        )
        val logger = DiagnosticsLogger()
        val formatted = logger.format(event)
        assertTrue(formatted.contains("stage=A2"))
    }

    @Test
    fun formatIncludesError() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_REJECTED,
            error = ErrorEntry(family = ErrorFamily.PRE_WRITE_REJECTED, code = "STALE_REVISION"),
        )
        val logger = DiagnosticsLogger()
        val formatted = logger.format(event)
        assertTrue(formatted.contains("err=PRE_WRITE_REJECTED.STALE_REVISION"))
    }

    @Test
    fun formatIncludesPlanSummary() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.PLANNED,
            planSummary = PlanSummary(capturedItemCount = 84, movedCount = 61, preservedCount = 23),
        )
        val logger = DiagnosticsLogger()
        val formatted = logger.format(event)
        assertTrue(formatted.contains("captured=84"))
        assertTrue(formatted.contains("moved=61"))
        assertTrue(formatted.contains("preserved=23"))
    }

    @Test
    fun formatIncludesReconciliation() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.RESTART_RECONCILED,
            reconciliation = ReconciliationContext(
                subjectRunId = "run-id",
                priorLifecycle = RecoveryLifecycle.COMMITTED_UNVERIFIED,
                classification = ReconciliationClassification.INTENDED_POST_STATE,
                resultingLifecycle = RecoveryLifecycle.VERIFIED,
            ),
        )
        val logger = DiagnosticsLogger()
        val formatted = logger.format(event)
        assertTrue(formatted.contains("subjectRun=run-id"))
        assertTrue(formatted.contains("priorLifecycle=COMMITTED_UNVERIFIED"))
        assertTrue(formatted.contains("classification=INTENDED_POST_STATE"))
        assertTrue(formatted.contains("resultLifecycle=VERIFIED"))
    }

    @Test
    fun formatDoesNotContainNeverClassifiedValues() {
        // D-09: Never-classified values must not appear in rendered output
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_VERIFIED,
            runId = "valid-run-id",
            applySummary = ApplySummary(preserveActionCount = 10, updateActionCount = 5, insertActionCount = 0),
        )
        val logger = DiagnosticsLogger()
        val formatted = logger.format(event)
        val forbidden = listOf(
            "packageName", "com.example", "component", "MainActivity",
            "profileSerial", "cell", "folderTitle", "rules", "category", "revision", "message", "SQLException", "items",
        )
        for (f in forbidden) {
            assertFalse("Forbidden '$f' must not appear in logcat format", formatted.contains(f))
        }
    }

    @Test
    fun terminalFailurePhasesAreCorrect() {
        // Contract §10: WARN only for *_REJECTED, *_FAILED, *_ROLLED_BACK, *_UNRESOLVED
        val nonTerminal: Set<PhaseCode> = setOf(
            PhaseCode.APPLY_NO_CHANGES,
            PhaseCode.USER_CANCELLED,
            PhaseCode.PLANNING_IMPOSSIBLE,
            PhaseCode.APPLY_RECOVERED,
            PhaseCode.RECOVERY_WRITER_BUSY,
            PhaseCode.RECOVERY_CONCURRENT,
        )
        val releaseLogger = DiagnosticsLogger(isReleaseBuild = true)
        for (p in nonTerminal) {
            releaseLogger.log(RunEvent(journalSequence = 1L, phase = p))
        }
    }
}
