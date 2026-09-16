package app.lawnchair.organizer.personalization.exchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #329: the bounded Import Normalizer (spec 329). Fixes the accepted
 * framing corpus (D-1), the ambiguity judgment (D-3), the simplified fence
 * grammar (D-4), the typed outcomes (D-5), the verbatim-substring boundary
 * (D-6), and the fail-closed security behavior (D-8).
 */
class ImportNormalizerTest {

    private val json = """{"exportId":"e1","itemIntents":[]}"""

    private fun fenced(info: String, interior: String, prefix: String = "", suffix: String = "") = "$prefix```$info\n$interior\n```$suffix"

    private fun normalize(text: String): ImportNormalization = ImportNormalizer.normalize(text)

    private fun payloadOf(text: String): ImportNormalization.Payload = normalize(text) as ImportNormalization.Payload

    private fun failureOf(text: String): ImportNormalizationFailure = (normalize(text) as ImportNormalization.Failure).failure

    // --- D-1 priority 1: marker form delegates to the #205 parser rules ---

    @Test
    fun markerFormDelegatesTheOriginalTextVerbatim() {
        val reply = "Here it is.\n${ExchangeContract.INTENT_BEGIN_MARKER}\n$json\n${ExchangeContract.INTENT_END_MARKER}\nDone."
        assertEquals(ImportNormalization.MarkedFraming(reply), normalize(reply))
    }

    @Test
    fun markerPriorityWinsOverFencesAnywhere() {
        // A fence inside the marker region and a fence around the marker pair
        // both stay marker-first (spec 329 nested wrapper scenario).
        val fenceInside = "${ExchangeContract.INTENT_BEGIN_MARKER}\n```json\n$json\n```\n${ExchangeContract.INTENT_END_MARKER}"
        assertEquals(ImportNormalization.MarkedFraming(fenceInside), normalize(fenceInside))
        val markerInsideFence = fenced("json", "${ExchangeContract.INTENT_BEGIN_MARKER}\n$json\n${ExchangeContract.INTENT_END_MARKER}")
        assertEquals(ImportNormalization.MarkedFraming(markerInsideFence), normalize(markerInsideFence))
    }

    @Test
    fun markerLinesWithSurroundingAsciiWhitespaceStillDelegate() {
        val reply = "   ${ExchangeContract.INTENT_BEGIN_MARKER}  \n$json\n\t${ExchangeContract.INTENT_END_MARKER}"
        assertEquals(ImportNormalization.MarkedFraming(reply), normalize(reply))
    }

    @Test
    fun contextMarkersAreNotIntentMarkers() {
        val reply = "${ExchangeContract.CONTEXT_BEGIN_MARKER}\n{}\n${ExchangeContract.CONTEXT_END_MARKER}"
        assertEquals(ImportNormalizationFailure.UnrecognizedFormat, failureOf(reply))
    }

    // --- D-1 priority 2: single fenced json block (D-4 grammar) ---

    @Test
    fun singleFencedJsonBlockWithProseIsPayloadInterior() {
        val interior = "{\n  \"exportId\": \"e1\"\n}"
        val reply = "Here is the intent:\n${fenced("json", interior)}\nLet me know."
        val payload = payloadOf(reply)
        assertEquals(RecognizedImportFraming.FENCED_JSON, payload.framing)
        assertEquals(interior, payload.payload)
    }

    @Test
    fun fenceInfoStringIsCaseInsensitiveButExact() {
        assertEquals(RecognizedImportFraming.FENCED_JSON, payloadOf(fenced("JSON", json)).framing)
        assertEquals(RecognizedImportFraming.FENCED_JSON, payloadOf(fenced("Json", json)).framing)
        // `jsonc` is not `json` (spec 329 D-4): the non-json block falls
        // through, and the whole text (fence lines included) is not a
        // standalone JSON object → unrecognized, not guessed.
        assertEquals(ImportNormalizationFailure.UnrecognizedFormat, failureOf(fenced("jsonc", json)))
    }

    @Test
    fun multiLineFencedInteriorIsVerbatimExceptOuterTrim() {
        val interior = "  {\n    \"a\": 1\n  }  "
        val payload = payloadOf(fenced("json", interior))
        assertEquals("{\n    \"a\": 1\n  }", payload.payload)
    }

    // --- D-3: ambiguity is total block count, fail-closed ---

    @Test
    fun twoClosedBlocksAreAmbiguousRegardlessOfTags() {
        assertEquals(
            ImportNormalizationFailure.AmbiguousBlocks,
            failureOf("${fenced("json", json)}\n${fenced("json", json)}"),
        )
        assertEquals(
            ImportNormalizationFailure.AmbiguousBlocks,
            failureOf("${fenced("json", json)}\n${fenced("text", "notes")}"),
        )
    }

    @Test
    fun unclosedFenceDoesNotCountAsABlock() {
        // No closing line → not a block (D-1 note); the residual ``` lines
        // also break the standalone parse → unrecognized.
        assertEquals(
            ImportNormalizationFailure.UnrecognizedFormat,
            failureOf("```json\n$json"),
        )
    }

    // --- D-1 priority 3: standalone JSON object ---

    @Test
    fun standaloneObjectIsTrimmedOnlyNeverReserialized() {
        val compact = """{"exportId":"e1","itemIntents":[],"notes":  [ 1 ,  2 ]}"""
        val payload = payloadOf("  \n\t$compact\n ")
        assertEquals(RecognizedImportFraming.STANDALONE_JSON, payload.framing)
        assertEquals(compact, payload.payload)
    }

    @Test
    fun onlyAJsonObjectRootIsStandalone() {
        assertEquals(ImportNormalizationFailure.UnrecognizedFormat, failureOf("[1, 2, 3]"))
        assertEquals(ImportNormalizationFailure.UnrecognizedFormat, failureOf("\"just a string\""))
        assertEquals(ImportNormalizationFailure.UnrecognizedFormat, failureOf("42"))
    }

    @Test
    fun inlineBracesInProseAreNeverExtracted() {
        assertEquals(
            ImportNormalizationFailure.UnrecognizedFormat,
            failureOf("The answer is $json and nothing else."),
        )
    }

    @Test
    fun nonJsonSingleFenceFallsThroughToUnrecognized() {
        assertEquals(
            ImportNormalizationFailure.UnrecognizedFormat,
            failureOf("Here you go:\n${fenced("text", json)}"),
        )
    }

    // --- Transport normalization (BOM / CRLF / CR), determinism, D-6 ---

    @Test
    fun bomAndCrlfNormalizeBeforeRecognition() {
        val reply = "```json\n{\n  \"a\": 1\n}\n```"
        val withBomAndCrlf = "\uFEFF```json\r\n{\r\n  \"a\": 1\r\n}\r\n```"
        val payload = payloadOf(withBomAndCrlf)
        assertEquals(RecognizedImportFraming.FENCED_JSON, payload.framing)
        assertEquals("{\n  \"a\": 1\n}", payload.payload)
        assertEquals(payloadOf(reply).payload, payload.payload)
    }

    @Test
    fun loneCrNormalizesToLf() {
        val payload = payloadOf("\uFEFF${fenced("json", "{\r\"a\": 1\r}")}")
        assertEquals("{\n\"a\": 1\n}", payload.payload)
    }

    @Test
    fun recognitionIsDeterministicForTheSameInput() {
        val reply = "${fenced("json", json)}\ntrailing prose"
        assertEquals(normalize(reply), normalize(reply))
        val standalone = "\uFEFF$json\r\n"
        assertEquals(normalize(standalone), normalize(standalone))
    }

    @Test
    fun normalizedPayloadIsIdempotentUnderRenormalization() {
        // Re-normalizing a recognized payload keeps the payload text stable
        // (the framing enum may legitimately move to STANDALONE_JSON).
        val fencedPayload = payloadOf(fenced("json", json)).payload
        assertEquals(fencedPayload, payloadOf(fencedPayload).payload)
        val standalonePayload = payloadOf(json).payload
        assertEquals(standalonePayload, payloadOf(standalonePayload).payload)
    }

    @Test
    fun everyPayloadIsASubstringOfTheTransportNormalizedInput() {
        // D-6: the only permitted edits are framing-line removal and outer
        // trim, so the payload must be a literal substring of the
        // BOM-stripped, LF-normalized input.
        val corpus = listOf(
            fenced("json", json, prefix = "prose\n", suffix = "\nepilogue"),
            fenced("JSON", "{\n  \"deep\": {\"x\": [1, 2]}\n}"),
            json,
            "  $json  ",
        )
        for (text in corpus) {
            val payload = payloadOf(text)
            val normalizedInput = text.replace("\r\n", "\n").replace("\r", "\n").trim()
            assertTrue(
                "payload must be a substring of the normalized input",
                normalizedInput.contains(payload.payload),
            )
        }
    }

    // --- D-8: adversarial inputs stay bounded and fail closed ---

    @Test
    fun deeplyNestedStandaloneInputFailsClosedWithoutInterpreting() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertEquals(ImportNormalizationFailure.UnrecognizedFormat, failureOf(deep))
    }

    @Test
    fun nestedWrapperOutcomesFollowTheFenceGrammar() {
        // An inner info-carrying fence line does not close the block; the
        // block extends to the first closing line and the fence line stays in
        // the verbatim payload (downstream codec → SCHEMA_MISMATCH).
        val payload = payloadOf(fenced("json", "{\n```json\n}"))
        assertEquals("{\n```json\n}", payload.payload)
        // Two independently closed blocks → the ambiguity rejection, exactly
        // like any other multi-block input.
        assertEquals(
            ImportNormalizationFailure.AmbiguousBlocks,
            failureOf("```json\n{}\n```\ntext\n```json\n{}\n```"),
        )
    }

    @Test
    fun emptyFencedBlockIsRecognizedAndLeftToTheCodec() {
        val payload = payloadOf("```json\n```")
        assertEquals(RecognizedImportFraming.FENCED_JSON, payload.framing)
        assertEquals("", payload.payload)
    }
}
