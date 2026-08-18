package app.lawnchair.organizer.diagnostics.model

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AC-67-01: Validation tests for ErrorEntry.code and PlanSummary map keys.
 * Asserts that arbitrary strings are rejected at construction.
 */
class ModelValidationTest {

    @Test
    fun errorEntryRejectsBlankCode() {
        assertThrows("ErrorEntry with blank code must throw", IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "")
        }
    }

    @Test
    fun errorEntryRejectsLowercaseCode() {
        assertThrows("ErrorEntry with lowercase code must throw", IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "write_failed")
        }
    }

    @Test
    fun errorEntryRejectsCodeWithSpaces() {
        assertThrows("ErrorEntry with space in code must throw", IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "WRITE FAILED")
        }
    }

    @Test
    fun errorEntryAllowsUnmapped() {
        // "UNMAPPED" is the special allowed fallback value
        ErrorEntry(ErrorFamily.APPLY_FAILURE, "UNMAPPED")
    }

    @Test
    fun errorEntryAllowsValidEnumName() {
        ErrorEntry(ErrorFamily.APPLY_FAILURE, "WRITE_FAILED")
    }

    @Test
    fun errorEntryAllowsCodeWithDigits() {
        ErrorEntry(ErrorFamily.PLANNING_INVALID, "V_01")
    }

    @Test
    fun errorEntryRejectsBlankAdditionalCode() {
        assertThrows("ErrorEntry.additionalCodes with blank entry must throw", IllegalArgumentException::class.java) {
            ErrorEntry(ErrorFamily.APPLY_FAILURE, "WRITE_FAILED", additionalCodes = listOf(""))
        }
    }

    @Test
    fun planSummaryRejectsLowercasePreservedByReasonKey() {
        assertThrows("PlanSummary.preservedByReason with lowercase key must throw", IllegalArgumentException::class.java) {
            PlanSummary(preservedByReason = mapOf("dock" to 5))
        }
    }

    @Test
    fun planSummaryRejectsUnplacedByReasonWithSpaces() {
        assertThrows("PlanSummary.unplacedByReason with space in key must throw", IllegalArgumentException::class.java) {
            PlanSummary(unplacedByReason = mapOf("EXCEEDS GRID" to 1))
        }
    }

    @Test
    fun planSummaryRejectsWarningByCodeWithSpecialChars() {
        assertThrows("PlanSummary.warningByCode with special chars must throw", IllegalArgumentException::class.java) {
            PlanSummary(warningByCode = mapOf("FALLBACK@CATEGORY" to 1))
        }
    }

    @Test
    fun planSummaryRejectsConfidenceCountsWithLowercase() {
        assertThrows("PlanSummary.confidenceCounts with lowercase must throw", IllegalArgumentException::class.java) {
            PlanSummary(confidenceCounts = mapOf("explicit" to 1))
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
