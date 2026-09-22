package app.lawnchair.organizer.ui.exchange

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ImportNormalizationFailure
import com.android.launcher3.R

/**
 * Issue #373 (TO-BE D-11): the remedy category of the failure-remedy
 * projection. The primary face never lists the 20 typed vocabularies — each
 * typed failure maps onto exactly one remedy category whose copy and action
 * state what the user does next, and the typed cause is demoted to auxiliary
 * information (the collapsed detail expansion and the diagnostics face).
 */
enum class ImportFailureRemedy {
    /** Re-run the import with the same or a different source. */
    RETRY_IMPORT,

    /** The reply content itself is the cause: re-request and re-paste it. */
    REPASTE,

    /** The request side is the cause: recreate the request (T-15). */
    RECREATE_REQUEST,
}

/**
 * Issue #373 (spec "primary mapping表"): the display model one typed failure
 * projects onto. Table-driven and pure — the resource IDs are resolved by the
 * UI, so the mapping itself is testable on the JVM (spec 332 AC-5 "UI側
 * parseなし" is the same seam discipline). [detailTextRes] is the inherited
 * typed-cause explanation (the former primary copy) and [detailTypeName] is
 * the typed classification name; both are auxiliary information shown only
 * inside the collapsed detail expansion (TO-BE §10), and neither carries user
 * data.
 */
data class ExchangeImportFailureDisplay(
    val remedy: ImportFailureRemedy,
    val primaryTextRes: Int,
    val primaryActionLabelRes: Int,
    val detailTextRes: Int,
    val detailTypeName: String,
)

/**
 * Issue #373 (TO-BE D-11): project one typed import failure onto its
 * remedy-category primary copy and action. The exhaustive `when`s are the
 * compile-time guarantee that every typed class — envelope 4, normalization
 * 2, contract 14 — reaches the projection; a future classification addition
 * fails compilation here instead of silently falling out of the table.
 */
fun exchangeImportFailureDisplay(failure: ExchangeImportFailure): ExchangeImportFailureDisplay = when (failure) {
    is ExchangeImportFailure.Envelope -> when (failure.failure) {
        ExchangeEnvelopeFailure.InputOversize -> display(
            remedy = ImportFailureRemedy.RETRY_IMPORT,
            primaryTextRes = R.string.exchange_failure_primary_input_oversize,
            detailTextRes = R.string.exchange_failure_input_oversize,
            detailTypeName = "INPUT_OVERSIZE",
        )

        ExchangeEnvelopeFailure.FramingMissing -> display(
            remedy = ImportFailureRemedy.REPASTE,
            primaryTextRes = R.string.exchange_failure_primary_framing_missing,
            detailTextRes = R.string.exchange_failure_framing_missing,
            detailTypeName = "FRAMING_MISSING",
        )

        ExchangeEnvelopeFailure.FramingAmbiguous -> display(
            remedy = ImportFailureRemedy.REPASTE,
            primaryTextRes = R.string.exchange_failure_primary_framing_ambiguous,
            detailTextRes = R.string.exchange_failure_framing_ambiguous,
            detailTypeName = "FRAMING_AMBIGUOUS",
        )

        ExchangeEnvelopeFailure.FramingEmpty -> display(
            remedy = ImportFailureRemedy.REPASTE,
            primaryTextRes = R.string.exchange_failure_primary_framing_empty,
            detailTextRes = R.string.exchange_failure_framing_empty,
            detailTypeName = "FRAMING_EMPTY",
        )
    }

    // Spec 329: normalizer failures settle before the codec — the projection
    // still guides re-pasting, the typed cause explains the accepted formats.
    is ExchangeImportFailure.Normalization -> when (failure.failure) {
        ImportNormalizationFailure.AmbiguousBlocks -> display(
            remedy = ImportFailureRemedy.REPASTE,
            primaryTextRes = R.string.exchange_failure_primary_normalization_ambiguous,
            detailTextRes = R.string.exchange_failure_normalization_ambiguous,
            detailTypeName = "AMBIGUOUS_BLOCKS",
        )

        ImportNormalizationFailure.UnrecognizedFormat -> display(
            remedy = ImportFailureRemedy.REPASTE,
            primaryTextRes = R.string.exchange_failure_primary_normalization_unrecognized,
            detailTextRes = R.string.exchange_failure_normalization_unrecognized,
            detailTypeName = "UNRECOGNIZED_FORMAT",
        )
    }

    is ExchangeImportFailure.Contract -> contractDisplay(failure.failure)
}

/**
 * The 14-class #204 contract failure mapping (spec 204 + spec 331 D-5 + spec
 * 337 D-8), projected onto the remedy categories. `SCOPE_MISMATCH` never
 * reaches the T-18 failure face (the run-side scope gate owns its surfaces,
 * issues #369/#375); the row exists for exhaustiveness of the projection.
 */
private fun contractDisplay(failure: IntentValidationFailure): ExchangeImportFailureDisplay = when (failure) {
    IntentValidationFailure.SchemaMismatch -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_schema_mismatch,
        detailTextRes = R.string.exchange_failure_schema_mismatch,
        detailTypeName = "SCHEMA_MISMATCH",
    )

    IntentValidationFailure.ExportMismatch -> display(
        remedy = ImportFailureRemedy.RECREATE_REQUEST,
        primaryTextRes = R.string.exchange_failure_primary_export_mismatch,
        detailTextRes = R.string.exchange_failure_export_mismatch,
        detailTypeName = "EXPORT_MISMATCH",
    )

    IntentValidationFailure.SessionExpired -> display(
        remedy = ImportFailureRemedy.RECREATE_REQUEST,
        primaryTextRes = R.string.exchange_failure_primary_session_expired,
        detailTextRes = R.string.exchange_failure_session_expired,
        detailTypeName = "SESSION_EXPIRED",
    )

    // TO-BE §8.3 stale remedy table's import row (D-12): the primary copy is
    // 「依頼の内容が古くなりました」 and the remedy is always re-creating the
    // request — no partial apply, no rebase.
    IntentValidationFailure.ContextStale -> display(
        remedy = ImportFailureRemedy.RECREATE_REQUEST,
        primaryTextRes = R.string.exchange_failure_primary_context_stale,
        detailTextRes = R.string.exchange_failure_context_stale,
        detailTypeName = "CONTEXT_STALE",
    )

    IntentValidationFailure.Oversize -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_oversize,
        detailTextRes = R.string.exchange_failure_oversize,
        detailTypeName = "OVERSIZE",
    )

    is IntentValidationFailure.UnknownRef -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_unknown_ref,
        detailTextRes = R.string.exchange_failure_unknown_ref,
        detailTypeName = "UNKNOWN_REF",
    )

    IntentValidationFailure.DuplicateRef -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_duplicate_ref,
        detailTextRes = R.string.exchange_failure_duplicate_ref,
        detailTypeName = "DUPLICATE_REF",
    )

    IntentValidationFailure.IncompleteCoverage -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_incomplete_coverage,
        detailTextRes = R.string.exchange_failure_incomplete_coverage,
        detailTypeName = "INCOMPLETE_COVERAGE",
    )

    IntentValidationFailure.InvalidEnum -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_invalid_enum,
        detailTextRes = R.string.exchange_failure_invalid_enum,
        detailTypeName = "INVALID_ENUM",
    )

    IntentValidationFailure.ForbiddenContent -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_forbidden_content,
        detailTextRes = R.string.exchange_failure_forbidden_content,
        detailTypeName = "FORBIDDEN_CONTENT",
    )

    is IntentValidationFailure.MobilityContradiction -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_mobility_contradiction,
        detailTextRes = R.string.exchange_failure_mobility_contradiction,
        detailTypeName = "MOBILITY_CONTRADICTION",
    )

    // V1-unreachable reserved class: the projection row exists so a future
    // capability gate lands on a remedy, not on a crash or a silent failure.
    IntentValidationFailure.CapabilityUnsupported -> display(
        remedy = ImportFailureRemedy.REPASTE,
        primaryTextRes = R.string.exchange_failure_primary_capability_unsupported,
        detailTextRes = R.string.exchange_failure_capability_unsupported,
        detailTypeName = "CAPABILITY_UNSUPPORTED",
    )

    is IntentValidationFailure.ScopeMismatch -> display(
        remedy = ImportFailureRemedy.RECREATE_REQUEST,
        primaryTextRes = R.string.exchange_failure_primary_scope_mismatch,
        detailTextRes = R.string.exchange_failure_scope_mismatch,
        detailTypeName = "SCOPE_MISMATCH",
    )

    is IntentValidationFailure.UnknownCategoryRef -> display(
        remedy = ImportFailureRemedy.RECREATE_REQUEST,
        primaryTextRes = R.string.exchange_failure_primary_unknown_category_ref,
        detailTextRes = R.string.exchange_failure_unknown_category_ref,
        detailTypeName = "UNKNOWN_CATEGORY_REF",
    )
}

/** The action label resource of one remedy category (the 3 class-level means). */
private fun display(
    remedy: ImportFailureRemedy,
    primaryTextRes: Int,
    detailTextRes: Int,
    detailTypeName: String,
): ExchangeImportFailureDisplay {
    val actionLabelRes = when (remedy) {
        ImportFailureRemedy.RETRY_IMPORT -> R.string.exchange_import_failure_action_reimport
        ImportFailureRemedy.REPASTE -> R.string.exchange_import_failure_action_repost
        ImportFailureRemedy.RECREATE_REQUEST -> R.string.exchange_import_failure_action_recreate
    }
    return ExchangeImportFailureDisplay(
        remedy = remedy,
        primaryTextRes = primaryTextRes,
        primaryActionLabelRes = actionLabelRes,
        detailTextRes = detailTextRes,
        detailTypeName = detailTypeName,
    )
}

/** One recorded 「直近の取り込み失敗」 row of the diagnostics face. */
data class RecentImportFailure(
    val typeName: String,
    val explanation: String,
)

/**
 * Issue #373 (spec "診断を開く"): process-scoped transient holder feeding the
 * diagnostics face's 「直近の取り込み失敗」 auxiliary row. Written only by the
 * failure face's 診断を開く action (the next 診断を開く overwrites it) and read
 * by the diagnostics face; the typed classification name and the contract
 * copy are the only content — never user data (raw text, refs, labels).
 *
 * Deliberately NOT serializable and never stored in navigation saved state or
 * `SavedStateHandle`: a system-initiated process death loses it (the spec's
 * non-persistence contract), while an Activity recreation — same process —
 * keeps it. The diagnostics route itself stays argument-less for the same
 * reason. The recording is Compose snapshot state so the diagnostics face
 * recomposes when the failure face records.
 */
object ExchangeImportFailureDiagnostics {

    var recent: RecentImportFailure? by mutableStateOf<RecentImportFailure?>(null)
        private set

    fun record(failure: RecentImportFailure) {
        recent = failure
    }

    /**
     * The current attempt has no typed classification (e.g. the post-decode
     * `InputNotReady` environmental failure): empty the recording so a
     * PREVIOUS attempt's typed cause is never presented as the current one.
     * The write happens at the 診断を開く operation only, same as [record].
     */
    fun clear() {
        recent = null
    }

    @VisibleForTesting
    fun resetForTests() {
        recent = null
    }
}
