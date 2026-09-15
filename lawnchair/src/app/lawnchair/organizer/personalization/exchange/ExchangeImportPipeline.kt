package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
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
 * envelope limit → framing extraction → #204 decode → session lookup →
 * expiry → structural digest equality → reconstruction → #204 validation.
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
     * reply — every envelope/framing/decode failure fails closed here, before
     * any canonical capture/composition runs. The decoded intent is the
     * session lookup key (its echoed `exportId`).
     */
    fun prepare(importText: String): ExchangeImportResult {
        val payload = when (val framing = IntentImportParser.parse(importText)) {
            is IntentFramingResult.Failure ->
                return ExchangeImportResult.Failure(ExchangeImportFailure.Envelope(framing.failure))

            is IntentFramingResult.Extracted -> framing.payload
        }
        val intent = when (val decoded = IntentCodec.decode(payload.toByteArray(Charsets.UTF_8))) {
            is IntentDecodeResult.Failure ->
                return ExchangeImportResult.Failure(ExchangeImportFailure.Contract(decoded.failure))

            is IntentDecodeResult.Success -> decoded.intent
        }
        return Prepared(intent)
    }

    /** Stage 1 success: the decoded intent, ready for session binding. */
    data class Prepared(
        val intent: app.lawnchair.organizer.personalization.PersonalizedIntentV1,
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
            )
        if (activeSession.isExpired(nowEpochMs)) {
            return ExchangeImportResult.Failure(
                ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
            )
        }
        val currentDigest = SourceContextIdentity.digest(currentStructural)
        if (currentDigest != activeSession.sourceContextDigest) {
            return ExchangeImportResult.Failure(
                ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            )
        }
        val exportView = when (val reconstructed = SessionExportReconstructor.rebuild(activeSession, currentStructural)) {
            is ReconstructionResult.Diverged ->
                return ExchangeImportResult.Failure(
                    ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
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
                ExchangeImportResult.Failure(ExchangeImportFailure.Contract(validation.failure))
        }
    }
}

sealed interface ExchangeImportResult {
    data class Validated(val validated: ValidatedPersonalizedIntent) : ExchangeImportResult

    data class Failure(val failure: ExchangeImportFailure) : ExchangeImportResult
}

/**
 * The unified failure surface of the import path (spec 205 AC-5): the four
 * #205-side envelope/framing failures plus the twelve #204 contract classes
 * wrapped in [Contract]. UI failure displays map one-to-one onto these
 * (16 kinds total).
 */
sealed interface ExchangeImportFailure {
    data class Envelope(val failure: ExchangeEnvelopeFailure) : ExchangeImportFailure

    data class Contract(val failure: IntentValidationFailure) : ExchangeImportFailure
}
