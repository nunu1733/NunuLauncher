package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentDecodeResult
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.SourceContextIdentity
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent

/**
 * Issue #205: the pure import pipeline (spec 205 data flow). Binds the
 * #205-owned envelope/framing stages in front of the #204 codec/validator
 * seams and the session-scoped reconstruction:
 *
 * envelope limit → #329 normalizer → framing extraction → #204 decode →
 * session lookup → expiry → structural digest equality → reconstruction →
 * #204 validation.
 *
 * Every failure is typed and zero-write. The structural digest is compared
 * *before* the reconstructed view reaches the validator's per-ref semantics
 * so any structural change after the export converges on `CONTEXT_STALE`
 * (spec 205 parity contract); the validator keeps its own staleness check as
 * the #204-internal guard.
 */
object ExchangeImportPipeline {

    /**
     * Stage 1 (review P2 ordering): bounds, frames, and decodes the untrusted
     * reply — every envelope/normalization/framing/decode failure fails closed
     * here, before any canonical capture/composition runs. The decoded intent
     * is the session lookup key (its echoed `exportId`).
     */
    fun prepare(importText: String): ExchangeImportResult {
        // Envelope limit first (#205 Decision 6, spec 329 D-5): the #205-owned
        // gate settles `InputOversize` with its unchanged typed identity
        // before the normalizer sees the reply, so oversized input never
        // reaches shape recognition.
        if (utf8ByteLengthExceeds(importText, ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES)) {
            return ExchangeImportResult.Failure(ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.InputOversize))
        }
        val framed = when (val normalization = ImportNormalizer.normalize(importText)) {
            is ImportNormalization.Failure ->
                return ExchangeImportResult.Failure(ExchangeImportFailure.Normalization(normalization.failure))

            // Marker form: the #205 parser keeps owning extraction and its
            // typed framing failures (spec 329 D-1 priority 1). The framing
            // failure still knows a full-line marker exists — the recognized
            // metadata records MARKER without decode facts (spec 332 D-6).
            is ImportNormalization.MarkedFraming -> when (val framing = IntentImportParser.parse(importText)) {
                is IntentFramingResult.Failure ->
                    return ExchangeImportResult.Failure(
                        ExchangeImportFailure.Envelope(framing.failure),
                        RecognizedImportInfo(framing = RecognizedImportFraming.MARKER, intentSchemaVersion = null, authoredEntryCount = null),
                    )

                is IntentFramingResult.Extracted -> FramedPayload(framing.payload, RecognizedImportFraming.MARKER)
            }

            // Fenced/standalone form: the normalizer payload feeds the codec
            // directly (spec 329 D-6: verbatim, never re-serialized).
            is ImportNormalization.Payload -> FramedPayload(normalization.payload, normalization.framing)
        }
        val intent = when (val decoded = IntentCodec.decode(framed.text.toByteArray(Charsets.UTF_8))) {
            is IntentDecodeResult.Failure ->
                return ExchangeImportResult.Failure(
                    ExchangeImportFailure.Contract(decoded.failure),
                    RecognizedImportInfo(framing = framed.framing, intentSchemaVersion = null, authoredEntryCount = null),
                )

            is IntentDecodeResult.Success -> decoded.intent
        }
        return Prepared(intent, framed.framing)
    }

    private data class FramedPayload(val text: String, val framing: RecognizedImportFraming)

    /**
     * Stage 1 success: the decoded intent plus the recognized framing (spec
     * 329 D-5), ready for session binding and parse-state display (#332).
     */
    data class Prepared(
        val intent: app.lawnchair.organizer.personalization.PersonalizedIntentV1,
        val framing: RecognizedImportFraming,
    ) : ExchangeImportResult

    /** Single-call composition of [prepare] and [validate]. */
    fun import(
        importText: String,
        session: ExportSession?,
        currentStructural: CanonicalStructuralInputs,
        nowEpochMs: Long,
    ): ExchangeImportResult {
        val prepared = when (val result = prepare(importText)) {
            is ExchangeImportResult.Failure -> return result
            is Prepared -> result
            is ExchangeImportResult.Validated -> error("unreachable")
        }
        return validate(prepared, session, currentStructural, nowEpochMs)
    }

    /** Stage 2: session binding + expiry + structural digest + reconstruction + validation. */
    fun validate(
        prepared: Prepared,
        session: ExportSession?,
        currentStructural: CanonicalStructuralInputs,
        nowEpochMs: Long,
    ): ExchangeImportResult {
        val intent = prepared.intent
        val activeSession = session
            ?: return ExchangeImportResult.Failure(
                ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch),
                prepared.recognizedInfo(),
            )
        if (activeSession.isExpired(nowEpochMs)) {
            return ExchangeImportResult.Failure(
                ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
                prepared.recognizedInfo(),
            )
        }
        val currentDigest = SourceContextIdentity.digest(currentStructural)
        if (currentDigest != activeSession.sourceContextDigest) {
            return ExchangeImportResult.Failure(
                ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
                prepared.recognizedInfo(),
            )
        }
        val exportView = when (val reconstructed = SessionExportReconstructor.rebuild(activeSession, currentStructural)) {
            is ReconstructionResult.Diverged ->
                return ExchangeImportResult.Failure(
                    ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
                    prepared.recognizedInfo(),
                )

            is ReconstructionResult.Rebuilt -> reconstructed.export
        }
        return when (
            val validation = IntentValidator.validate(
                intent = intent,
                export = exportView,
                session = activeSession,
                nowEpochMs = nowEpochMs,
                currentStructuralDigest = currentDigest,
            )
        ) {
            is IntentValidation.Validated -> ExchangeImportResult.Validated(validation.validated)

            is IntentValidation.Failure ->
                ExchangeImportResult.Failure(ExchangeImportFailure.Contract(validation.failure), prepared.recognizedInfo())
        }
    }
}

sealed interface ExchangeImportResult {
    data class Validated(val validated: ValidatedPersonalizedIntent) : ExchangeImportResult

    /**
     * Issue #332 (spec D-6): the recognized metadata travels additively on
     * the failure value for the parse-first outcome display; the 19-kind
     * failure enumeration itself is unchanged.
     */
    data class Failure(
        val failure: ExchangeImportFailure,
        val recognized: RecognizedImportInfo? = null,
    ) : ExchangeImportResult
}

/**
 * Issue #332 (spec D-5/D-6): parse-stage recognition metadata. All values are
 * seam-derived — [framing] is the recognized framing (null when the failure
 * settled before recognition), [intentSchemaVersion] is the codec-accepted
 * schema version (null until decode succeeds), and [authoredEntryCount] is
 * the authored document entry count (`itemIntents.size`: a bare
 * `{ "ref": ... }` entry counts, a ref absent from the document — canonical
 * `UnresolvedByOmission` — does not). No user content (labels, refs,
 * rationale, confidence) is exposed.
 */
data class RecognizedImportInfo(
    val framing: RecognizedImportFraming?,
    val intentSchemaVersion: String?,
    val authoredEntryCount: Int?,
)

/** The recognition facts of a decoded, framed reply (spec 332 D-6). */
fun ExchangeImportPipeline.Prepared.recognizedInfo(): RecognizedImportInfo = RecognizedImportInfo(
    framing = framing,
    intentSchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
    authoredEntryCount = intent.itemIntents.size,
)

/**
 * The unified failure surface of the import path (spec 205 AC-5, spec 331
 * D-5, spec 329 D-5): the four #205-side envelope/framing failures, the two
 * #329 normalizer failures wrapped in [Normalization], and the thirteen #204
 * contract classes wrapped in [Contract]. UI failure displays map one-to-one
 * onto these (19 kinds total). The #204 `ScopeMismatch` class is raised by
 * the run-side scope binding gate (`ScopeBindingGate`), not by this pipeline —
 * only the run knows the confirmed selection and the composition-time
 * candidate projection.
 */
sealed interface ExchangeImportFailure {
    data class Envelope(val failure: ExchangeEnvelopeFailure) : ExchangeImportFailure

    data class Normalization(val failure: ImportNormalizationFailure) : ExchangeImportFailure

    data class Contract(val failure: IntentValidationFailure) : ExchangeImportFailure
}
