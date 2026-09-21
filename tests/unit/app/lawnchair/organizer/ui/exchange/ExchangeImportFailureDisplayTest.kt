package app.lawnchair.organizer.ui.exchange

import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.ScopeMismatchCause
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ImportNormalizationFailure
import com.android.launcher3.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #373 (spec "primary mapping表" scenario, IM-AC-01): the failure-remedy
 * projection oracle. Every one of the 20 typed import failures is pinned to
 * its remedy category, primary copy resource, action label resource and
 * detail-expansion content — the accepted mapping table, table-driven here so
 * a review-visible table change and the implementation cannot drift. The
 * exhaustive `when`s in the projection make an unlisted future classification
 * a compile error; this test additionally pins the 20 present rows.
 */
class ExchangeImportFailureDisplayTest {

    private data class ExpectedRow(
        val failure: ExchangeImportFailure,
        val remedy: ImportFailureRemedy,
        val primaryTextRes: Int,
        val detailTextRes: Int,
        val detailTypeName: String,
    )

    private val expectedRows: List<ExpectedRow> = listOf(
        // Envelope 4 (spec 205).
        ExpectedRow(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.InputOversize),
            ImportFailureRemedy.RETRY_IMPORT,
            R.string.exchange_failure_primary_input_oversize,
            R.string.exchange_failure_input_oversize,
            "INPUT_OVERSIZE",
        ),
        ExpectedRow(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.FramingMissing),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_framing_missing,
            R.string.exchange_failure_framing_missing,
            "FRAMING_MISSING",
        ),
        ExpectedRow(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.FramingAmbiguous),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_framing_ambiguous,
            R.string.exchange_failure_framing_ambiguous,
            "FRAMING_AMBIGUOUS",
        ),
        ExpectedRow(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.FramingEmpty),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_framing_empty,
            R.string.exchange_failure_framing_empty,
            "FRAMING_EMPTY",
        ),
        // Normalization 2 (spec 329).
        ExpectedRow(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.AmbiguousBlocks),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_normalization_ambiguous,
            R.string.exchange_failure_normalization_ambiguous,
            "AMBIGUOUS_BLOCKS",
        ),
        ExpectedRow(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.UnrecognizedFormat),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_normalization_unrecognized,
            R.string.exchange_failure_normalization_unrecognized,
            "UNRECOGNIZED_FORMAT",
        ),
        // Contract 14 (spec 204 + 331 D-5 + 337 D-8).
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.SchemaMismatch),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_schema_mismatch,
            R.string.exchange_failure_schema_mismatch,
            "SCHEMA_MISMATCH",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch),
            ImportFailureRemedy.RECREATE_REQUEST,
            R.string.exchange_failure_primary_export_mismatch,
            R.string.exchange_failure_export_mismatch,
            "EXPORT_MISMATCH",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
            ImportFailureRemedy.RECREATE_REQUEST,
            R.string.exchange_failure_primary_session_expired,
            R.string.exchange_failure_session_expired,
            "SESSION_EXPIRED",
        ),
        // D-12 stale row: the remedy is always re-creating the request.
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            ImportFailureRemedy.RECREATE_REQUEST,
            R.string.exchange_failure_primary_context_stale,
            R.string.exchange_failure_context_stale,
            "CONTEXT_STALE",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.Oversize),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_oversize,
            R.string.exchange_failure_oversize,
            "OVERSIZE",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.UnknownRef("r1")),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_unknown_ref,
            R.string.exchange_failure_unknown_ref,
            "UNKNOWN_REF",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.DuplicateRef),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_duplicate_ref,
            R.string.exchange_failure_duplicate_ref,
            "DUPLICATE_REF",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.IncompleteCoverage),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_incomplete_coverage,
            R.string.exchange_failure_incomplete_coverage,
            "INCOMPLETE_COVERAGE",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.InvalidEnum),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_invalid_enum,
            R.string.exchange_failure_invalid_enum,
            "INVALID_ENUM",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.ForbiddenContent),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_forbidden_content,
            R.string.exchange_failure_forbidden_content,
            "FORBIDDEN_CONTENT",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.MobilityContradiction("r1")),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_mobility_contradiction,
            R.string.exchange_failure_mobility_contradiction,
            "MOBILITY_CONTRADICTION",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.CapabilityUnsupported),
            ImportFailureRemedy.REPASTE,
            R.string.exchange_failure_primary_capability_unsupported,
            R.string.exchange_failure_capability_unsupported,
            "CAPABILITY_UNSUPPORTED",
        ),
        // Exhaustiveness row: never settles on the T-18 failure face (the
        // run-side scope gate owns it), the projection still names a remedy.
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.ScopeMismatch(ScopeMismatchCause.SET_MISMATCH)),
            ImportFailureRemedy.RECREATE_REQUEST,
            R.string.exchange_failure_primary_scope_mismatch,
            R.string.exchange_failure_scope_mismatch,
            "SCOPE_MISMATCH",
        ),
        ExpectedRow(
            ExchangeImportFailure.Contract(IntentValidationFailure.UnknownCategoryRef("c1")),
            ImportFailureRemedy.RECREATE_REQUEST,
            R.string.exchange_failure_primary_unknown_category_ref,
            R.string.exchange_failure_unknown_category_ref,
            "UNKNOWN_CATEGORY_REF",
        ),
    )

    @Test
    fun everyTypedFailureProjectsOntoTheAcceptedMappingTable() {
        for (row in expectedRows) {
            val display = exchangeImportFailureDisplay(row.failure)
            assertEquals("remedy of ${row.detailTypeName}", row.remedy, display.remedy)
            assertEquals(
                "primary copy of ${row.detailTypeName}",
                row.primaryTextRes,
                display.primaryTextRes,
            )
            assertEquals(
                "detail copy of ${row.detailTypeName}",
                row.detailTextRes,
                display.detailTextRes,
            )
            assertEquals(
                "typed classification name of ${row.detailTypeName}",
                row.detailTypeName,
                display.detailTypeName,
            )
        }
    }

    @Test
    fun actionLabelIsSinglePerRemedyCategory() {
        for (row in expectedRows) {
            val display = exchangeImportFailureDisplay(row.failure)
            val expected = when (row.remedy) {
                ImportFailureRemedy.RETRY_IMPORT -> R.string.exchange_import_failure_action_reimport
                ImportFailureRemedy.REPASTE -> R.string.exchange_import_failure_action_repost
                ImportFailureRemedy.RECREATE_REQUEST -> R.string.exchange_import_failure_action_recreate
            }
            assertEquals("action label of ${row.detailTypeName}", expected, display.primaryActionLabelRes)
        }
    }

    @Test
    fun allTwentyTypedClassificationsAreCovered() {
        val names = expectedRows.map { it.detailTypeName }
        assertEquals(20, names.size)
        assertEquals(20, names.distinct().size)
    }

    @Test
    fun projectionCopyAndLabelsExistInBothLocales() {
        // spec 123 contract: ja正本 + en declare the same name set.
        val names = resourceNames(
            expectedRows.flatMap {
                listOf(
                    it.primaryTextRes,
                    it.detailTextRes,
                )
            }.distinct() + listOf(
                R.string.exchange_import_failure_action_reimport,
                R.string.exchange_import_failure_action_repost,
                R.string.exchange_import_failure_action_recreate,
                R.string.exchange_import_interrupt,
                R.string.exchange_import_open_diagnostics,
                R.string.exchange_import_failure_detail_show,
                R.string.exchange_import_failure_detail_hide,
                R.string.exchange_import_failure_detail_type_format,
            ),
        )
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            for (name in names) {
                assertTrue("$name must exist in $localeDir", xml.contains("name=\"$name\""))
            }
        }
    }

    @Test
    fun diagnosticsHolderKeepsOnlyTheLatestRecording() {
        // The process-scoped transient holder: the 診断を開く action records the
        // current attempt's typed cause; the next recording overwrites it, and
        // nothing else writes it.
        ExchangeImportFailureDiagnostics.resetForTests()
        assertEquals(null, ExchangeImportFailureDiagnostics.recent)
        ExchangeImportFailureDiagnostics.record(RecentImportFailure("CONTEXT_STALE", "explanation"))
        assertEquals(RecentImportFailure("CONTEXT_STALE", "explanation"), ExchangeImportFailureDiagnostics.recent)
        ExchangeImportFailureDiagnostics.record(RecentImportFailure("SESSION_EXPIRED", "next"))
        assertEquals(RecentImportFailure("SESSION_EXPIRED", "next"), ExchangeImportFailureDiagnostics.recent)
        ExchangeImportFailureDiagnostics.resetForTests()
        assertEquals(null, ExchangeImportFailureDiagnostics.recent)
    }

    @Test
    fun diagnosticsHolderClearEmptiesTheRecording() {
        // Issue #373 implementation review: an attempt WITHOUT a typed
        // classification (InputNotReady etc.) empties the recording at the
        // 診断を開く operation — a previous attempt's cause must never be
        // presented as the current failure.
        ExchangeImportFailureDiagnostics.record(RecentImportFailure("CONTEXT_STALE", "previous attempt"))
        ExchangeImportFailureDiagnostics.clear()
        assertEquals(null, ExchangeImportFailureDiagnostics.recent)
    }

    @Test
    fun diagnosticsRecordingIsNotSerializable() {
        // IM-AC-04 lifecycle oracle (process-death side, structural): the
        // recording is process memory only — nothing serializable that a
        // system-initiated process death could restore through saved state.
        val row = RecentImportFailure("CONTEXT_STALE", "explanation")
        assertTrue(row !is java.io.Serializable)
        assertTrue(ExchangeImportFailureDiagnostics !is java.io.Serializable)
    }

    private fun resourceNames(ids: List<Int>): List<String> {
        val fields = R.string::class.java.declaredFields
        val byId = fields.associate { it.name to it.getInt(null) }
        return ids.map { id -> byId.entries.first { it.value == id }.key }
    }

    private fun lawnchairStringsXml(localeDir: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("lawnchair strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }
}
