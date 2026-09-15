package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_BEGIN_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_END_MARKER
import app.lawnchair.organizer.personalization.exchange.ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205 AC-4: the #205-owned exchange framing extraction — unique
 * extraction contract, free-text tolerance, typed rejects, newline/BOM
 * normalization, determinism, and the import envelope limit.
 */
class IntentImportParserTest {

    private fun framed(payload: String, prefix: String = "", suffix: String = "") = buildString {
        append(prefix)
        append(INTENT_BEGIN_MARKER)
        append('\n')
        append(payload)
        append('\n')
        append(INTENT_END_MARKER)
        append(suffix)
    }

    private fun failureOf(text: String): ExchangeEnvelopeFailure = (IntentImportParser.parse(text) as IntentFramingResult.Failure).failure

    @Test
    fun extractsTheVerbatimPayloadBetweenTheUniqueMarkerPair() {
        val result = IntentImportParser.parse(framed("""{"schemaVersion":"personalized-intent-v1"}"""))
        assertEquals(
            IntentFramingResult.Extracted("""{"schemaVersion":"personalized-intent-v1"}"""),
            result,
        )
    }

    @Test
    fun freeProseBeforeAndAfterTheMarkersIsTolerated() {
        val text = "Here is my plan as requested.\n" +
            framed("""{"a":1}""") +
            "\nLet me know if you want changes."
        assertEquals(IntentFramingResult.Extracted("""{"a":1}"""), IntentImportParser.parse(text))
    }

    @Test
    fun markerLinesMayCarrySurroundingAsciiWhitespace() {
        val text = "  $INTENT_BEGIN_MARKER \t\n{\"a\":1}\n\t $INTENT_END_MARKER  "
        assertEquals(IntentFramingResult.Extracted("""{"a":1}"""), IntentImportParser.parse(text))
    }

    @Test
    fun multiLinePayloadsArePreservedVerbatim() {
        val payload = "{\n  \"schemaVersion\": \"x\",\n  \"b\": 2\n}"
        assertEquals(IntentFramingResult.Extracted(payload), IntentImportParser.parse(framed(payload)))
    }

    @Test
    fun agentAddedBlankLinesAroundThePayloadAreTrimmedOnlyAtTheRegionEdges() {
        val text = framed("\n\n{\"a\":1}\n\n\n")
        assertEquals(IntentFramingResult.Extracted("""{"a":1}"""), IntentImportParser.parse(text))
    }

    @Test
    fun markerLookingSubstringsInsidePayloadLinesDoNotBreakExtraction() {
        // A marker string inside a JSON value is never a full-line match.
        val payload = """{"rationale":"use $INTENT_END_MARKER carefully"}"""
        assertEquals(IntentFramingResult.Extracted(payload), IntentImportParser.parse(framed(payload)))
    }

    @Test
    fun missingBeginOrEndOrEndBeforeBeginIsFramingMissing() {
        assertEquals(ExchangeEnvelopeFailure.FramingMissing, failureOf("no markers at all"))
        assertEquals(
            ExchangeEnvelopeFailure.FramingMissing,
            failureOf("{\"a\":1}\n$INTENT_END_MARKER"),
        )
        assertEquals(
            ExchangeEnvelopeFailure.FramingMissing,
            failureOf("$INTENT_BEGIN_MARKER\n{\"a\":1}"),
        )
        assertEquals(
            ExchangeEnvelopeFailure.FramingMissing,
            failureOf("$INTENT_END_MARKER\n{\"a\":1}\n$INTENT_BEGIN_MARKER"),
        )
    }

    @Test
    fun multipleOrNestedMarkerLinesAreFramingAmbiguous() {
        assertEquals(
            ExchangeEnvelopeFailure.FramingAmbiguous,
            failureOf(framed("{\"a\":1}") + "\n" + framed("{\"b\":2}")),
        )
        assertEquals(
            ExchangeEnvelopeFailure.FramingAmbiguous,
            failureOf(framed("{\"a\":1}\n$INTENT_BEGIN_MARKER")),
        )
        // A marker-equal line inside the region is a nested occurrence.
        assertEquals(
            ExchangeEnvelopeFailure.FramingAmbiguous,
            failureOf(framed("{\"a\":1}\n$INTENT_END_MARKER\n{\"b\":2}")),
        )
    }

    @Test
    fun emptyOrWhitespaceOnlyRegionIsFramingEmpty() {
        assertEquals(ExchangeEnvelopeFailure.FramingEmpty, failureOf(framed("")))
        assertEquals(ExchangeEnvelopeFailure.FramingEmpty, failureOf(framed("   \n\t ")))
    }

    @Test
    fun crlfCrAndBomInputsNormalizeToTheSameResult() {
        val payload = """{"a":1}"""
        val lf = framed(payload)
        val crlf = lf.replace("\n", "\r\n")
        val cr = lf.replace("\n", "\r")
        val bom = "\uFEFF" + lf
        val expected = IntentFramingResult.Extracted(payload)
        assertEquals(expected, IntentImportParser.parse(crlf))
        assertEquals(expected, IntentImportParser.parse(cr))
        assertEquals(expected, IntentImportParser.parse(bom))
    }

    @Test
    fun parsingIsDeterministicAndIdempotentForTheSameInput() {
        val text = "comment\n" + framed("""{"a":"日本語テキスト"}""") + "\ntrailing"
        val first = IntentImportParser.parse(text)
        repeat(5) { assertEquals(first, IntentImportParser.parse(text)) }
    }

    @Test
    fun hugeProseAroundASmallValidPayloadIsInputOversize() {
        val hugePrefix = "x".repeat(MAX_EXCHANGE_IMPORT_BYTES)
        val text = hugePrefix + framed("""{"a":1}""")
        assertEquals(ExchangeEnvelopeFailure.InputOversize, failureOf(text))
    }

    @Test
    fun markerLessHugeInputIsInputOversize() {
        assertEquals(
            ExchangeEnvelopeFailure.InputOversize,
            failureOf("y".repeat(MAX_EXCHANGE_IMPORT_BYTES + 1)),
        )
    }

    @Test
    fun envelopeBoundaryExactLimitPassesAndOneByteOverFails() {
        // ASCII-only fixtures make the UTF-8 byte length equal the char count.
        val payload = """{"a":1}"""
        val bare = framed(payload)
        val overhead = bare.length - payload.length
        val filler = MAX_EXCHANGE_IMPORT_BYTES - overhead - 1
        val atLimit = framed("p".repeat(filler) + "\n")
        assertEquals(MAX_EXCHANGE_IMPORT_BYTES, atLimit.length)
        assertTrue(IntentImportParser.parse(atLimit) is IntentFramingResult.Extracted)
        val overLimit = atLimit + " "
        assertEquals(MAX_EXCHANGE_IMPORT_BYTES + 1, overLimit.length)
        assertEquals(ExchangeEnvelopeFailure.InputOversize, failureOf(overLimit))
    }

    @Test
    fun receiptEnvelopeCheckMatchesTheParserBoundary() {
        // UI receipt (paste/clipboard) uses the same allocation-bounded check.
        assertTrue(acceptsExchangeImportEnvelope("plain text"))
        assertTrue(acceptsExchangeImportEnvelope("日".repeat(100)))
        assertFalse(acceptsExchangeImportEnvelope("x".repeat(MAX_EXCHANGE_IMPORT_BYTES + 1)))
        assertFalse(acceptsExchangeImportEnvelope("日".repeat(MAX_EXCHANGE_IMPORT_BYTES / 3 + 2)))
    }

    @Test
    fun multibyteContentCountsAsBytesNotChars() {
        val filler = "日".repeat(MAX_EXCHANGE_IMPORT_BYTES / 3 + 2)
        val text = framed(filler)
        assertTrue(text.length <= MAX_EXCHANGE_IMPORT_BYTES)
        assertEquals(ExchangeEnvelopeFailure.InputOversize, failureOf(text))
    }
}
