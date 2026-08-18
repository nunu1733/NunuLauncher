package app.lawnchair.organizer.diagnostics.model

import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.planning.Confidence
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AC-67-01: closed-schema validation.
 * Asserts that arbitrary or wrong-family strings are rejected at construction.
 */
class ModelValidationTest {

    // --- ErrorEntry: blank / UNMAPPED / basic ---

    @Test
    fun errorEntryRejectsBlankCode() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "")
        }
    }

    @Test
    fun errorEntryAllowsUnmapped() {
        ErrorEntry(ErrorFamily.APPLY_FAILURE, "UNMAPPED")
    }

    @Test
    fun errorEntryAllowsApplyFailureCode() {
        ErrorEntry(ErrorFamily.APPLY_FAILURE, "WRITE_FAILED")
    }

    @Test
    fun errorEntryRejectsBlankAdditionalCode() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "WRITE_FAILED", additionalCodes = listOf(""))
        }
    }

    @Test
    fun errorEntryRejectsMoreThan8AdditionalCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(
                ErrorFamily.APPLY_FAILURE,
                "WRITE_FAILED",
                additionalCodes = (1..9).map { "WRITE_FAILED" },
            )
        }
    }

    // --- ErrorEntry: per-family closed-code rejection ---

    @Test
    fun errorEntryRejectsWrongFamilyCode() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "STALE_REVISION")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForPlanningInvalid() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.PLANNING_INVALID, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForPlanningImpossible() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.PLANNING_IMPOSSIBLE, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForPreWriteRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.PRE_WRITE_REJECTED, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForApplyFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForRecoveryRejection() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.RECOVERY_REJECTION, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForRecoveryFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.RECOVERY_FAILURE, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForConcurrent() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.CONCURRENT, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringForWriterBusy() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.WRITER_BUSY, "FAKE_CODE_123")
        }
    }

    @Test
    fun errorEntryRejectsArbitraryStringInAdditionalCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            ErrorEntry(
                ErrorFamily.APPLY_FAILURE,
                "WRITE_FAILED",
                additionalCodes = listOf("FAKE_CODE_123"),
            )
        }
    }

    // --- ErrorEntry: every real enum value passes (per family) ---

    @Test
    fun errorEntryAllowsEveryRejectionCode() {
        for (code in RejectionCode.entries) {
            ErrorEntry(ErrorFamily.PLANNING_INVALID, code.name)
        }
    }

    @Test
    fun errorEntryAllowsEveryUnplacedReason() {
        for (code in UnplacedReason.entries) {
            ErrorEntry(ErrorFamily.PLANNING_IMPOSSIBLE, code.name)
        }
    }

    @Test
    fun errorEntryAllowsEveryPreWriteRejection() {
        for (code in PreWriteRejection.entries) {
            ErrorEntry(ErrorFamily.PRE_WRITE_REJECTED, code.name)
        }
    }

    @Test
    fun errorEntryAllowsEveryApplyFailure() {
        for (code in ApplyFailure.entries) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, code.name)
        }
    }

    @Test
    fun errorEntryAllowsEveryRecoveryRejection() {
        for (code in RecoveryRejection.entries) {
            ErrorEntry(ErrorFamily.RECOVERY_REJECTION, code.name)
        }
    }

    @Test
    fun errorEntryAllowsEveryRecoveryFailure() {
        for (code in RecoveryFailure.entries) {
            ErrorEntry(ErrorFamily.RECOVERY_FAILURE, code.name)
        }
    }

    @Test
    fun errorEntryAllowsConcurrentCode() {
        ErrorEntry(ErrorFamily.CONCURRENT, "CONCURRENT_RUN")
    }

    @Test
    fun errorEntryAllowsWriterBusyCode() {
        ErrorEntry(ErrorFamily.WRITER_BUSY, "WRITER_BUSY")
    }

    @Test
    fun errorEntryAllowsUnmappedForEveryFamily() {
        for (family in ErrorFamily.entries) {
            ErrorEntry(family, "UNMAPPED")
        }
    }

    // --- PlanSummary: non-member key rejection ---

    @Test
    fun planSummaryRejectsNonMemberPreservedByReasonKey() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanSummary(preservedByReason = mapOf("FAKE_REASON" to 5))
        }
    }

    @Test
    fun planSummaryRejectsNonMemberUnplacedByReasonKey() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanSummary(unplacedByReason = mapOf("FAKE_REASON" to 1))
        }
    }

    @Test
    fun planSummaryRejectsNonMemberWarningByCodeKey() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanSummary(warningByCode = mapOf("FAKE_CODE" to 1))
        }
    }

    @Test
    fun planSummaryRejectsNonMemberConfidenceCountsKey() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanSummary(confidenceCounts = mapOf("FAKE_CONFIDENCE" to 1))
        }
    }

    // --- PlanSummary: every real enum value passes ---

    @Test
    fun planSummaryAllowsEveryPreserveReason() {
        for (reason in PreserveReason.entries) {
            PlanSummary(preservedByReason = mapOf(reason.name to 1))
        }
    }

    @Test
    fun planSummaryAllowsEveryUnplacedReason() {
        for (reason in UnplacedReason.entries) {
            PlanSummary(unplacedByReason = mapOf(reason.name to 1))
        }
    }

    @Test
    fun planSummaryAllowsEveryWarningCode() {
        for (code in WarningCode.entries) {
            PlanSummary(warningByCode = mapOf(code.name to 1))
        }
    }

    @Test
    fun planSummaryAllowsEveryConfidence() {
        for (code in Confidence.entries) {
            PlanSummary(confidenceCounts = mapOf(code.name to 1))
        }
    }

    @Test
    fun planSummaryAllowsValidKeys() {
        PlanSummary(
            preservedByReason = mapOf("DOCK" to 5, "WIDGET" to 3),
            unplacedByReason = mapOf("EXCEEDS_GRID_DIMENSIONS" to 1),
            warningByCode = mapOf("FALLBACK_CATEGORY" to 2),
            confidenceCounts = mapOf("EXPLICIT" to 1, "RULE" to 5, "FALLBACK" to 2),
        )
    }
}
