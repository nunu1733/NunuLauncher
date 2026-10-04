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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-10: Single tag, DEBUG for ordinary phases, WARN for terminal failures,
 * release failure-only, no output before persist.
 */
class DiagnosticsLoggerTest {

    private fun event(
        seq: Long = 1L,
        phase: PhaseCode = PhaseCode.CAPTURED,
        runId: String? = null,
        applyStage: ApplyStage? = null,
        error: ErrorEntry? = null,
        planSummary: PlanSummary? = null,
        reconciliation: ReconciliationContext? = null,
        applySummary: ApplySummary? = null,
    ) = RunEvent(
        journalSequence = seq,
        phase = phase,
        runId = runId,
        applyStage = applyStage,
        error = error,
        planSummary = planSummary,
        reconciliation = reconciliation,
        applySummary = applySummary,
    )

    @Test
    fun tagIsOrganizerDiag() {
        assertEquals("OrganizerDiag", DiagnosticsLogger.TAG)
    }

    @Test
    fun debugForOrdinaryPhaseTransition() {
        val logger = DiagnosticsLogger()
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.CAPTURED)))
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.PLANNED)))
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.APPLY_NO_CHANGES)))
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.USER_CANCELLED)))
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.APPLY_RECOVERED)))
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.RECOVERY_WRITER_BUSY)))
        assertEquals("DEBUG", logger.levelFor(event(phase = PhaseCode.RECOVERY_CONCURRENT)))
    }

    @Test
    fun warnForTerminalFailure() {
        val logger = DiagnosticsLogger()
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.PLANNING_REJECTED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.INPUT_NOT_READY)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.CHECKPOINT_REJECTED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.APPLY_REJECTED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.CONCURRENT_RUN_REJECTED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.APPLY_ROLLED_BACK)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.APPLY_UNRESOLVED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.APPLY_RECOVERY_FAILED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.RECOVERY_REJECTED)))
        assertEquals("WARN", logger.levelFor(event(phase = PhaseCode.RECOVERY_FAILED)))
    }

    @Test
    fun releaseModeSuppressesOrdinary() {
        val releaseLogger = DiagnosticsLogger(isReleaseBuild = true)
        releaseLogger.log(event(phase = PhaseCode.CAPTURED))
        releaseLogger.log(event(phase = PhaseCode.APPLY_ROLLED_BACK))
    }

    @Test
    fun formatIncludesRunId() {
        val formatted = DiagnosticsLogger().format(event(runId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(formatted.contains("run=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(formatted.contains("phase=CAPTURED"))
    }

    @Test
    fun formatIncludesStage() {
        val formatted = DiagnosticsLogger().format(event(phase = PhaseCode.APPLY_REJECTED, applyStage = ApplyStage.A2))
        assertTrue(formatted.contains("stage=A2"))
    }

    @Test
    fun formatIncludesError() {
        val formatted = DiagnosticsLogger().format(event(phase = PhaseCode.APPLY_REJECTED, error = ErrorEntry(ErrorFamily.PRE_WRITE_REJECTED, "STALE_REVISION")))
        assertTrue(formatted.contains("err=PRE_WRITE_REJECTED.STALE_REVISION"))
    }

    @Test
    fun formatIncludesPlanSummary() {
        val formatted = DiagnosticsLogger().format(event(phase = PhaseCode.PLANNED, planSummary = PlanSummary(84, 0, 61, 23)))
        assertTrue(formatted.contains("captured=84"))
        assertTrue(formatted.contains("moved=61"))
        assertTrue(formatted.contains("preserved=23"))
    }

    @Test
    fun formatIncludesReconciliation() {
        val formatted = DiagnosticsLogger().format(
            event(
                phase = PhaseCode.RESTART_RECONCILED,
                reconciliation = ReconciliationContext(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    RecoveryLifecycle.COMMITTED_UNVERIFIED,
                    ReconciliationClassification.INTENDED_POST_STATE,
                    RecoveryLifecycle.VERIFIED,
                ),
            ),
        )
        assertTrue(formatted.contains("subjectRun=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(formatted.contains("priorLifecycle=COMMITTED_UNVERIFIED"))
        assertTrue(formatted.contains("classification=INTENDED_POST_STATE"))
        assertTrue(formatted.contains("resultLifecycle=VERIFIED"))
    }

    @Test
    fun formatDoesNotContainNeverClassifiedValues() {
        val formatted = DiagnosticsLogger().format(event(runId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", applySummary = ApplySummary(10, 5, 0)))
        val forbidden = listOf(
            "packageName", "com.example", "component", "MainActivity",
            "profileSerial", "cell", "folderTitle", "rules", "category", "revision", "message", "SQLException", "items",
        )
        for (f in forbidden) {
            assertFalse("Forbidden '$f' must not appear in logcat format", formatted.contains(f))
        }
    }

    @Test
    fun inputNotReadyCarriesOnlyReadinessFamilyAndCode() {
        val formatted = DiagnosticsLogger().format(
            event(
                runId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                phase = PhaseCode.INPUT_NOT_READY,
                error = ErrorEntry(ErrorFamily.INPUT_READINESS, app.lawnchair.organizer.integration.InputCompositionCode.BUNDLE_CORRUPT.name),
            ),
        )
        assertTrue(formatted.contains("phase=INPUT_NOT_READY"))
        assertTrue(formatted.contains("err=INPUT_READINESS.BUNDLE_CORRUPT"))
        val forbidden = listOf(
            "packageName",
            "com.example",
            "cell",
            "revision",
            "message",
            "digest",
            "bundle-corrupt",
            "kebab",
        )
        for (f in forbidden) {
            assertFalse("Forbidden '$f' must not appear in logcat format", formatted.contains(f))
        }
    }

    @Test
    fun captureFailureFormatContainsOnlyExceptionClassName() {
        val formatted = DiagnosticsLogger().formatCaptureFailure(java.lang.IllegalStateException::class.java)
        assertEquals("phase=CAPTURE exceptionClass=IllegalStateException", formatted)
    }

    @Test
    fun captureFailureFormatNormalizesToSimpleNameWithoutMessage() {
        val formatted = DiagnosticsLogger().formatCaptureFailure(
            android.database.sqlite.SQLiteBlobTooBigException::class.java,
        )
        assertEquals("phase=CAPTURE exceptionClass=SQLiteBlobTooBigException", formatted)
        assertFalse(formatted.contains("Row too big"))
        assertFalse(formatted.contains("CursorWindow"))
        assertFalse(formatted.contains("message"))
    }

    @Test
    fun releaseBuildSuppressesCaptureFailureLine() {
        // Early return must happen before any logcat call; reaching Log.d in a
        // JVM unit test throws "not mocked", so a passing call proves suppression.
        DiagnosticsLogger(isReleaseBuild = true).logCaptureFailure(java.lang.IllegalStateException::class.java)
    }
}
