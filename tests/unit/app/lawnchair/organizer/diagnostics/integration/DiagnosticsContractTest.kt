package app.lawnchair.organizer.diagnostics.integration

import app.lawnchair.organizer.diagnostics.journal.RunEventSerializer
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-05, D-09: Negative fixture — prohibited strings are absent from
 * journal bytes, export bytes, and logcat renderer output.
 */
class DiagnosticsContractTest {

    private val d09ForbiddenStrings = listOf(
        "packageName",
        "com.example",
        "component",
        "MainActivity",
        "profileSerial",
        "cell",
        "folderTitle",
        "rules",
        "category",
        "revision",
        "message",
        "SQLException",
        "items",
    )

    @Test
    fun d09NonContainmentJournalBytes() {
        // Create events that cover all phase types
        val events = listOf(
            RunEvent(journalSequence = 1L, phase = PhaseCode.RUN_STARTED, runId = "abcd1234abcd1234abcd1234abcd1234"),
            RunEvent(journalSequence = 2L, phase = PhaseCode.CAPTURED),
            RunEvent(journalSequence = 3L, phase = PhaseCode.PLANNED, planSummary = app.lawnchair.organizer.diagnostics.model.PlanSummary(capturedItemCount = 10)),
            RunEvent(journalSequence = 4L, phase = PhaseCode.APPLY_REJECTED, applyStage = app.lawnchair.organizer.diagnostics.model.ApplyStage.A2, error = app.lawnchair.organizer.diagnostics.model.ErrorEntry(family = app.lawnchair.organizer.diagnostics.model.ErrorFamily.PRE_WRITE_REJECTED, code = "STALE_REVISION")),
            RunEvent(journalSequence = 5L, phase = PhaseCode.RECOVERY_REQUESTED, recovery = app.lawnchair.organizer.diagnostics.model.RecoveryContext(pointId = "9c2e1234567890abcdef1234567890ab")),
            RunEvent(journalSequence = 6L, phase = PhaseCode.RESTART_RECONCILED, reconciliation = app.lawnchair.organizer.diagnostics.model.ReconciliationContext(subjectRunId = "run-id", priorLifecycle = app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle.COMMITTED_UNVERIFIED, classification = app.lawnchair.organizer.diagnostics.model.ReconciliationClassification.INTENDED_POST_STATE, resultingLifecycle = app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle.VERIFIED)),
        )

        for (event in events) {
            val json = RunEventSerializer.encodeToString(event)
            for (f in d09ForbiddenStrings) {
                assertFalse("Forbidden string '$f' must not appear in serialized event: ${event.phase}", json.contains(f))
            }
        }
    }

    @Test
    fun d09NonContainmentExportBytes() {
        // Export bytes should be the same as journal bytes (same serializer)
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.RUN_STARTED,
            runId = "abcd1234abcd1234abcd1234abcd1234",
        )
        val bytes = RunEventSerializer.encode(event)
        val text = bytes.toString(Charsets.UTF_8)
        for (f in d09ForbiddenStrings) {
            assertFalse("Forbidden string '$f' must not appear in export bytes", text.contains(f))
        }
    }

    @Test
    fun d09ForbiddenStringsInReconciliation() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.RESTART_RECONCILED,
            reconciliation = app.lawnchair.organizer.diagnostics.model.ReconciliationContext(
                subjectRunId = "abcd1234abcd1234abcd1234abcd1234",
                priorLifecycle = app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle.COMMITTED_UNVERIFIED,
                classification = app.lawnchair.organizer.diagnostics.model.ReconciliationClassification.INTENDED_POST_STATE,
                resultingLifecycle = app.lawnchair.organizer.diagnostics.model.RecoveryLifecycle.VERIFIED,
            ),
        )
        val json = RunEventSerializer.encodeToString(event)
        for (f in d09ForbiddenStrings) {
            assertFalse("Forbidden string '$f' must not appear in reconciliation event", json.contains(f))
        }
    }

    @Test
    fun noNeverClassifiedValuesInLogger() {
        // The logger should never render Never-classified values.
        // This test verifies that the format method doesn't include forbidden patterns.
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.APPLY_VERIFIED,
            applySummary = app.lawnchair.organizer.diagnostics.model.ApplySummary(
                preserveActionCount = 10,
                updateActionCount = 5,
                insertActionCount = 0,
            ),
        )
        val logger = app.lawnchair.organizer.diagnostics.logger.DiagnosticsLogger()
        val formatted = logger.format(event)
        // Verify the rendered output contains only approved fields
        assertTrue(formatted.contains("phase=APPLY_VERIFIED"))
        assertTrue(formatted.contains("preserveActions=10"))
        assertTrue(formatted.contains("updateActions=5"))
        assertTrue(formatted.contains("insertActions=0"))
        // Verify D-09 forbidden strings are absent
        val d09Forbidden = listOf(
            "packageName", "com.example", "component", "MainActivity",
            "profileSerial", "cell", "folderTitle", "rules", "category", "revision", "message", "SQLException", "items",
        )
        for (f in d09Forbidden) {
            assertFalse("Forbidden string '$f' must not appear in logcat format", formatted.contains(f))
        }
    }
}
