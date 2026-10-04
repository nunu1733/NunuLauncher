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
    ): String = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"$exportId","itemIntents":$items$extra}"""

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
                    groupSemantic = GroupSemantic(categoryRef = "ref-cat", proposalLabel = null),
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
        val bytes = """{"schemaVersion":"personalized-intent-v0","exportId":"e","itemIntents":[]}""".encodeToByteArray()
        assertEquals(IntentValidationFailure.SchemaMismatch, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun savedV1ToV3DocumentsAreRejectedFailClosed() {
        // Issue #330 (spec 330 D-3) / Issue #337 (spec 337 D-8): no
        // dual-version runtime — the current constant alone gates decode, so
        // every older document is a typed mismatch.
        for (oldVersion in listOf("personalized-intent-v1", "personalized-intent-v2", "personalized-intent-v3")) {
            val bytes = """{"schemaVersion":"$oldVersion","exportId":"e","itemIntents":[]}""".encodeToByteArray()
            assertEquals(
                "old version: $oldVersion",
                IntentValidationFailure.SchemaMismatch,
                (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure,
            )
        }
    }

    @Test
    fun encodeWritesTheCurrentSchemaVersion() {
        // Issue #330 (AC-6): the producer side advertises v3.
        val encoded = IntentCodec.encode(
            PersonalizedIntentV1(exportId = "export-1", itemIntents = emptyList()),
        ).decodeToString()
        assertTrue(encoded.contains("\"schemaVersion\":\"${ContextExportContract.INTENT_SCHEMA_VERSION}\""))
    }

    @Test
    fun malformedJsonIsRejectedWithoutPartialApply() {
        val bytes = "{not json".encodeToByteArray()
        assertEquals(IntentValidationFailure.SchemaMismatch, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun unknownFieldsAreSchemaMismatches() {
        val bytes = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[],"unexpected":1}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.SchemaMismatch, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun schemaExternalAuthorityExpressionsAreForbiddenContent() {
        val bytes = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[],"x":0,"y":3}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.ForbiddenContent, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)

        val script = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[],"script":"rm -rf"}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.ForbiddenContent, (IntentCodec.decode(script) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun oversizePayloadsAreRejected() {
        val big = ByteArray(ContextExportContract.MAX_INTENT_BYTES + 1)
        assertEquals(IntentValidationFailure.Oversize, (IntentCodec.decode(big) as IntentDecodeResult.Failure).failure)

        val tooManyEntries = buildString {
            append("""{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[""")
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
        val bytes = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[{"ref":"r","importance":"URGENT"}]}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.InvalidEnum, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun outOfRangeConfidenceIsRejected() {
        val bytes = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[],"confidence":150}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.InvalidEnum, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    @Test
    fun oversizedProposalLabelIsRejected() {
        val longText = "a".repeat(app.lawnchair.organizer.rules.UserDefinedCategoryNameRules.MAX_CODE_POINTS + 1)
        val bytes = """{"schemaVersion":"${ContextExportContract.INTENT_SCHEMA_VERSION}","exportId":"e","itemIntents":[{"ref":"r","groupSemantic":{"proposalLabel":"$longText"}}]}"""
            .encodeToByteArray()
        assertEquals(IntentValidationFailure.Oversize, (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure)
    }

    /**
     * Issue #337 (spec 337 D-4, AC-8): `groupSemantic` sets exactly one of the
     * two fields — the pre-v4 any-of left the grouping authority undecided, so
     * both-set is a shape violation next to the pre-existing neither-set case.
     */
    @Test
    fun groupSemanticSetsExactlyOneOfCategoryRefAndProposalLabel() {
        for (semantic in listOf("{}", """{"categoryRef":"ref-cat","proposalLabel":"Tools"}""")) {
            val bytes = validIntentJson(items = """[{"ref":"ref-a","groupSemantic":$semantic}]""").encodeToByteArray()
            assertEquals(
                "groupSemantic: $semantic",
                IntentValidationFailure.SchemaMismatch,
                (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure,
            )
        }
    }

    /**
     * Issue #337 (spec 337 D-4, AC-5): the proposal label adopts the #336
     * category-name domain, so a value the promotion path could never accept is
     * rejected here. Length overshoot is OVERSIZE (asserted above); the
     * remaining domain violations are shape mismatches.
     */
    @Test
    fun proposalLabelsOutsideTheCategoryNameDomainAreRejected() {
        for (label in listOf("   ", "a|b", "line\nbreak")) {
            val bytes = validIntentJson(
                items = """[{"ref":"ref-a","groupSemantic":{"proposalLabel":"$label"}}]""",
            ).encodeToByteArray()
            assertEquals(
                "label: $label",
                IntentValidationFailure.SchemaMismatch,
                (IntentCodec.decode(bytes) as IntentDecodeResult.Failure).failure,
            )
        }
    }

    /** Issue #337: the decoded label is the normalized (trim + NFC) form. */
    @Test
    fun proposalLabelsAreNormalizedAtDecode() {
        val bytes = validIntentJson(
            items = """[{"ref":"ref-a","groupSemantic":{"proposalLabel":"  Morning  "}}]""",
        ).encodeToByteArray()
        val decoded = (IntentCodec.decode(bytes) as IntentDecodeResult.Success).intent
        assertEquals("Morning", decoded.itemIntents.single().groupSemantic?.proposalLabel)
    }
}
