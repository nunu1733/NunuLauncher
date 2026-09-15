package app.lawnchair.organizer.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204: codec contract tests — closed schema, allow-list fields,
 * typed failure classification, and content limits (AC-2/AC-4/AC-5).
 */
class IntentCodecTest {

    private fun validIntentJson(
        exportId: String = "export-1",
        items: String = """[{"ref":"ref-a","importance":"HIGH"}]""",
        extra: String = "",
    ): String = """{"schemaVersion":"personalized-intent-v1","exportId":"$exportId","itemIntents":$items$extra}"""

    @Test
    fun roundTripPreservesTheTypedIntent() {
        val intent = PersonalizedIntentV1(
            exportId = "export-1",
            itemIntents = listOf(
                ItemIntent(
                    ref = "ref-a",
                    importance = Importance.HIGH,
                    pageAffinity = 1,
                    regionAffinity = ExportRegionKind.TOP,
                    preserve = true,
                ),
                ItemIntent(
                    ref = "ref-b",
                    desiredGroupRefs = listOf("ref-a"),
                    groupSemantic = GroupSemantic(category = "CAT_SOCIAL", freeText = null),
                ),
            ),
            unresolvedRefs = listOf("ref-c"),
            globalPreference = GlobalPreference(minimizeMovement = true),
            rationale = "ai rationale",
            confidence = 80,
        )
        val decoded = IntentCodec.decode(IntentCodec.encode(intent))
        assertTrue(decoded is IntentDecodeResult.Success)
        assertEquals(intent, (decoded as IntentDecodeResult.Success).intent)
    }

    @Test
    fun unknownSchemaVersionIsRejectedFailClosed() {
        val bytes = """{"schemaVersion":"personalized-intent-v2","exportId":"e","itemIntents":[]}""".encodeToByteArray()
        assertEquals(IntentValidationFailure.SchemaMismatch, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun malformedJsonIsRejectedWithoutPartialApply() {
        val bytes = "{not json".encodeToByteArray()
        assertEquals(IntentValidationFailure.SchemaMismatch, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun unknownFieldsAreSchemaMismatches() {
        val bytes = """{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[],"unexpected":1}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.SchemaMismatch, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun schemaExternalAuthorityExpressionsAreForbiddenContent() {
        val bytes = """{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[],"x":0,"y":3}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.ForbiddenContent, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)

        val script = """{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[],"script":"rm -rf"}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.ForbiddenContent, (IntentCodec.decode(script) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun oversizePayloadsAreRejected() {
        val big = ByteArray(ContextExportContract.MAX_INTENT_BYTES + 1)
        assertEquals(IntentValidationFailure.Oversize, (IntentCodec.decode(big) as IntentDecodeResult.Failure).failure)

        val tooManyEntries = buildString {
            append("""{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[""")
            repeat(ContextExportContract.MAX_INTENT_ENTRIES + 1) { index ->
                if (index > 0) append(",")
                append("""{"ref":"r$index"}""")
            }
            append("]}")
        }
        assertEquals(
            IntentValidationFailure.Oversize,
            (IntentCodec.decode(tooManyEntries.encodeToByteArray()) as IntentDecodeResult.Failure).failure,
        )
    }

    @Test
    fun invalidEnumValuesAreTypedFailures() {
        val bytes = """{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[{"ref":"r","importance":"URGENT"}]}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.InvalidEnum, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun outOfRangeConfidenceIsRejected() {
        val bytes = """{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[],"confidence":150}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.InvalidEnum, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun oversizedFreeTextIsRejected() {
        val longText = "a".repeat(ContextExportContract.MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS + 1)
        val bytes = """{"schemaVersion":"personalized-intent-v1","exportId":"e","itemIntents":[{"ref":"r","groupSemantic":{"freeText":"$longText"}}]}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.Oversize, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }
}
