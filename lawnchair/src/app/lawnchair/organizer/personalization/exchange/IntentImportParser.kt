package app.lawnchair.organizer.personalization.exchange

/**
 * Issue #205: the #205-owned exchange framing extraction (spec 205
 * "Scenario: importの厳格な抽出"). Pure and total: any input resolves to the
 * verbatim payload region or a typed framing failure. The payload is never
 * interpreted here — schema validation belongs to the #204 codec/validator.
 */
object IntentImportParser {

    fun parse(importText: String): IntentFramingResult {
        // Envelope limit first (spec 205 Decision 6): bound the whole reply —
        // prose included — before any normalization or scan allocates more work.
        if (utf8ByteLengthExceeds(importText, ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES)) {
            return IntentFramingResult.Failure(ExchangeEnvelopeFailure.InputOversize)
        }
        var text = importText
        if (text.startsWith(BOM)) text = text.substring(1)
        text = text.replace(CRLF, LF).replace(CR, LF)

        val lines = text.split(LF)
        val beginIndices = markerLineIndices(lines, ExchangeContract.INTENT_BEGIN_MARKER)
        val endIndices = markerLineIndices(lines, ExchangeContract.INTENT_END_MARKER)
        return when {
            beginIndices.isEmpty() || endIndices.isEmpty() ->
                IntentFramingResult.Failure(ExchangeEnvelopeFailure.FramingMissing)

            beginIndices.size > 1 || endIndices.size > 1 ->
                IntentFramingResult.Failure(ExchangeEnvelopeFailure.FramingAmbiguous)

            beginIndices[0] > endIndices[0] ->
                IntentFramingResult.Failure(ExchangeEnvelopeFailure.FramingMissing)

            else -> {
                // Verbatim interior: only the outer whitespace of the whole
                // region is trimmed; agent-added blank lines are tolerated.
                val payload = lines.subList(beginIndices[0] + 1, endIndices[0])
                    .joinToString(LF)
                    .trim()
                if (payload.isEmpty()) {
                    IntentFramingResult.Failure(ExchangeEnvelopeFailure.FramingEmpty)
                } else {
                    IntentFramingResult.Extracted(payload)
                }
            }
        }
    }

    /**
     * A marker line matches iff the line, after stripping leading/trailing
     * ASCII spaces and tabs, equals the marker exactly (case-sensitive). A
     * marker-looking *substring* inside a JSON string value never matches, so
     * valid payloads need no escaping (spec 205 Decision 1).
     */
    internal fun isMarkerLine(line: String, marker: String): Boolean = line.trim(' ', '\t') == marker

    private fun markerLineIndices(lines: List<String>, marker: String): List<Int> = lines.withIndex().filter { isMarkerLine(it.value, marker) }.map { it.index }

    private const val BOM = "\uFEFF"
    private const val CRLF = "\r\n"
    private const val CR = "\r"
    private const val LF = "\n"
}

sealed interface IntentFramingResult {
    /** The verbatim payload region between the unique marker pair. */
    data class Extracted(val payload: String) : IntentFramingResult

    data class Failure(val failure: ExchangeEnvelopeFailure) : IntentFramingResult
}

/**
 * Typed #205-side envelope/framing failures (spec 205). Distinct from the
 * #204 validation failure classes: they are detected before any payload
 * interpretation, and their user guidance differs (reduce the surrounding
 * prose / re-request the marked reply vs. fix the intent itself).
 */
sealed interface ExchangeEnvelopeFailure {
    /** The whole import text exceeds the envelope limit (zero-write). */
    data object InputOversize : ExchangeEnvelopeFailure

    /** No complete marker pair: BEGIN or END absent, or END before BEGIN. */
    data object FramingMissing : ExchangeEnvelopeFailure

    /** Multiple BEGIN/END marker lines (nested marker lines included). */
    data object FramingAmbiguous : ExchangeEnvelopeFailure

    /** A valid marker pair whose payload region is empty/whitespace-only. */
    data object FramingEmpty : ExchangeEnvelopeFailure
}

/**
 * Receipt-time envelope check for UI input paths (spec 205 Decision 6 / plan:
 * paste/clipboard input validates the same limit on acceptance, so oversized
 * text never enters Compose state).
 */
fun acceptsExchangeImportEnvelope(text: String): Boolean = !utf8ByteLengthExceeds(text, ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES)

/**
 * Allocation-bounded UTF-8 length check (approving review note): the fast
 * path rejects on the char count (every char is at least one UTF-8 byte) and
 * the exact count early-exits at `limit + 1`, so the check itself never
 * materializes a same-size byte array for oversized inputs.
 */
internal fun utf8ByteLengthExceeds(text: String, limit: Int): Boolean {
    if (text.length > limit) return true
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val c = text[index]
        bytes += when {
            c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                index++
                4
            }

            c.code < 0x80 -> 1

            else -> 3
        }
        if (bytes > limit) return true
        index++
    }
    return false
}
