package app.lawnchair.organizer.personalization.exchange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Issue #329: the bounded Import Normalizer in front of the #205 marker
 * framing (spec 329 D-1..D-4). It recognizes only the explicitly enumerated
 * accepted framings — the exact marker form, a single fenced `json` code
 * block, or a standalone JSON object — and canonicalizes the framing /
 * transport layer only. Fuzzy extraction is prohibited (spec 329 D-2):
 * ambiguous or unrecognized input resolves to a typed failure and nothing is
 * applied.
 *
 * The envelope limit is not owned here: `ExchangeImportPipeline.prepare`
 * runs the #205-owned gate first, so [normalize] requires envelope-checked
 * input and its result types carry only the two normalizer failures (spec
 * 329 D-5). The result never rewrites field values, ref sets, or the schema
 * version — every payload is a verbatim substring of the transport-normalized
 * input (spec 329 D-6), enforced by the [ImportNormalization.Payload]
 * construction paths below.
 */
object ImportNormalizer {

    fun normalize(importText: String): ImportNormalization {
        // Transport normalization first (spec 329 D-1): strip a leading BOM
        // and unify CRLF/CR to LF — the same idempotent rules as the parser.
        var text = importText
        if (text.startsWith(BOM)) text = text.substring(1)
        text = text.replace(CRLF, LF).replace(CR, LF)

        // Priority 1: any full-line INTENT marker line delegates to the
        // #205-owned parser rules, which own their typed failures. The
        // original text is forwarded; the parser's normalization is
        // idempotent, so double normalization converges on the same result.
        val lines = text.split(LF)
        val hasIntentMarker = lines.any { line ->
            IntentImportParser.isMarkerLine(line, ExchangeContract.INTENT_BEGIN_MARKER) ||
                IntentImportParser.isMarkerLine(line, ExchangeContract.INTENT_END_MARKER)
        }
        if (hasIntentMarker) return ImportNormalization.MarkedFraming(importText)

        // Priority 2: fenced code blocks under the simplified deterministic
        // grammar (spec 329 D-4). The ambiguity judgment counts every
        // independently closed block — tagged or bare — (D-3): two or more
        // blocks reject without choosing.
        val blocks = fencedBlocks(lines)
        if (blocks.size > 1) return ImportNormalization.Failure(ImportNormalizationFailure.AmbiguousBlocks)
        val single = blocks.singleOrNull()
        if (single != null && isJsonInfoString(single.infoString)) {
            return ImportNormalization.Payload(single.interior, RecognizedImportFraming.FENCED_JSON)
        }

        // Priority 3: the whole text (outer whitespace trimmed) parses as a
        // single strict JSON object. The parse result is used for shape
        // recognition only — the trimmed source text is forwarded verbatim,
        // never re-serialized (spec 329 D-6).
        val candidate = text.trim()
        if (isBoundedJsonObject(candidate)) {
            return ImportNormalization.Payload(candidate, RecognizedImportFraming.STANDALONE_JSON)
        }

        return ImportNormalization.Failure(ImportNormalizationFailure.UnrecognizedFormat)
    }

    private data class FencedBlock(val infoString: String, val interior: String)

    /**
     * Single stateful left-to-right line scan (spec 329 D-8: no
     * backtracking). Any line starting with three backticks is a fence line:
     * outside a block it opens a candidate block (bare ` ``` ` included, so
     * the D-3 total count covers untagged blocks too); inside a block only a
     * whitespace-only rest closes it (so an inner ` ```json ` line never
     * closes). A candidate left open at the end of input does not count as a
     * block (spec 329 D-1 note: an unclosed fence pair is not a block).
     */
    private fun fencedBlocks(lines: List<String>): List<FencedBlock> {
        val blocks = mutableListOf<FencedBlock>()
        var infoString: String? = null
        var openIndex = -1
        for ((index, line) in lines.withIndex()) {
            if (!line.startsWith(BACKTICKS)) continue
            if (infoString == null) {
                infoString = line.substring(BACKTICKS.length).trim()
                openIndex = index
            } else if (line.substring(BACKTICKS.length).trim().isEmpty()) {
                blocks.add(FencedBlock(infoString, lines.subList(openIndex + 1, index).joinToString(LF).trim()))
                infoString = null
            }
        }
        return blocks
    }

    /**
     * ASCII case-insensitive `json` (spec 329 D-4). Deliberately not
     * `String.equals(ignoreCase = true)`, whose Unicode case folding would
     * accept look-alikes such as `jſon` (U+017F) outside the closed accepted
     * framing set.
     */
    private fun isJsonInfoString(info: String): Boolean {
        if (info.length != JSON_INFO_STRING.length) return false
        for (index in info.indices) {
            if (asciiLower(info[index]) != JSON_INFO_STRING[index]) return false
        }
        return true
    }

    private fun asciiLower(char: Char): Char = if (char in 'A'..'Z') char + ('a' - 'A') else char

    /**
     * Depth-bounded strict JSON object check for the standalone framing. The
     * cheap single-pass bracket scan fails closed on nesting beyond
     * [MAX_JSON_DEPTH] *before* the recursive parser runs, so an adversarial
     * pathological input cannot turn shape recognition into unbounded
     * recursion (spec 329 D-8: bounded single-pass processing only). Anything
     * at or under the depth cap is decided by the strict parser (default
     * `Json`, no leniency).
     */
    private fun isBoundedJsonObject(text: String): Boolean {
        if (maxBracketDepth(text) > MAX_JSON_DEPTH) return false
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() is JsonObject
    }

    private fun maxBracketDepth(text: String): Int {
        var depth = 0
        var maxDepth = 0
        var inString = false
        var escaped = false
        text.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '{', '[' -> {
                        depth++
                        if (depth > maxDepth) maxDepth = depth
                    }

                    '}', ']' -> depth--

                    '"' -> inString = true
                }
            }
        }
        return maxDepth
    }

    private const val BACKTICKS = "```"
    private const val JSON_INFO_STRING = "json"

    /**
     * Nesting accepted by shape recognition. The intent schema is shallow;
     * anything deeper is not a recognizable payload and fails closed.
     */
    private const val MAX_JSON_DEPTH = 64

    private const val BOM = "\uFEFF"
    private const val CRLF = "\r\n"
    private const val CR = "\r"
    private const val LF = "\n"
}

/** The framing shapes the normalizer can recognize (spec 329 D-5 closed enum). */
enum class RecognizedImportFraming { MARKER, FENCED_JSON, STANDALONE_JSON }

/** The recognized normalization outcome (spec 329 D-1). */
sealed interface ImportNormalization {
    /** A full-line INTENT marker exists: the #205 parser owns extraction. */
    data class MarkedFraming(val importText: String) : ImportNormalization

    /**
     * A payload region recognized without markers, verbatim from the
     * transport-normalized input (spec 329 D-6 substring constraint).
     */
    data class Payload(val payload: String, val framing: RecognizedImportFraming) : ImportNormalization

    data class Failure(val failure: ImportNormalizationFailure) : ImportNormalization
}

/**
 * The typed normalizer failures (spec 329 D-5). The envelope limit is
 * intentionally absent — it is settled by the #205-owned gate in
 * `ExchangeImportPipeline.prepare` before the normalizer runs.
 */
sealed interface ImportNormalizationFailure {
    /** Two or more fenced blocks: choosing between candidates is prohibited. */
    data object AmbiguousBlocks : ImportNormalizationFailure

    /** None of the accepted framings matches: no shape is guessed. */
    data object UnrecognizedFormat : ImportNormalizationFailure
}
