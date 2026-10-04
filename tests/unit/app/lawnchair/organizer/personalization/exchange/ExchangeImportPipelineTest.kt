package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205: the pure import pipeline — envelope/framing stages in front of
 * the #204 codec/validator, session-scoped reconstruction, digest-first
 * `CONTEXT_STALE` convergence, and typed passthrough of every #204 failure.
 */
class ExchangeImportPipelineTest {

    private val now = 1_000_000L

    private fun app(id: String, x: Int = 0): CapturedItem = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun docked(id: String): CapturedItem = app(id).copy(placement = CapturedPlacement.Dock(0))

    private fun buildState(items: List<CapturedItem>): Pair<BuiltExport, CanonicalStructuralInputs> {
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val structural = CanonicalStructuralInputs(snapshot, targets, emptyMap<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>())
        val inputs = ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = now)
        return ContextExportBuilder.build(inputs, PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator()) to structural
    }

    /** A full-coverage, mobility-safe intent: every ref preserved. */
    private fun fullCoverageIntent(built: BuiltExport): PersonalizedIntentV1 = PersonalizedIntentV1(
        exportId = built.export.exportId,
        itemIntents = built.export.items.map { ItemIntent(ref = it.ref, preserve = true) },
    )

    private fun reply(payloadJson: String, prose: Boolean = true): String = buildString {
        if (prose) append("Here is the proposed intent.\n")
        append(ExchangeContract.INTENT_BEGIN_MARKER)
        append('\n')
        append(payloadJson)
        append('\n')
        append(ExchangeContract.INTENT_END_MARKER)
        if (prose) append("\nContact me for refinements.")
    }

    /** Issue #329: a fenced `json` reply without any marker line. */
    private fun fencedReply(payloadJson: String, prose: Boolean = true): String = buildString {
        if (prose) append("Here is the proposed intent.\n")
        append("```json\n")
        append(payloadJson)
        append("\n```\n")
        if (prose) append("Contact me for refinements.")
    }

    private fun failureOf(
        text: String,
        session: app.lawnchair.organizer.personalization.ExportSession?,
        structural: CanonicalStructuralInputs,
        at: Long = now + 1,
    ): ExchangeImportFailure = (ExchangeImportPipeline.import(text, session, structural, at) as ExchangeImportResult.Failure).failure

    @Test
    fun happyPathValidatesAFullyCoveredMarkedReply() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1), docked("d")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val result = ExchangeImportPipeline.import(reply(intentJson), built.session, structural, now + 1)
        assertTrue(result is ExchangeImportResult.Validated)
        assertEquals(
            built.export.exportId,
            (result as ExchangeImportResult.Validated).validated.intent.exportId,
        )
    }

    @Test
    fun unknownSessionIsExportMismatch() {
        val (built, structural) = buildState(listOf(app("a")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch),
            failureOf(reply(intentJson), session = null, structural = structural),
        )
    }

    @Test
    fun expiredSessionIsSessionExpired() {
        val (built, structural) = buildState(listOf(app("a")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
            failureOf(reply(intentJson), session = built.session, structural = structural, at = built.session.expiresAtEpochMs),
        )
    }

    @Test
    fun structuralChangeAfterExportConvergesOnContextStale() {
        val (built, _) = buildState(listOf(app("a"), app("b", x = 1)))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val moved = buildState(listOf(app("a"), app("b", x = 3))).second
        val locked = buildState(listOf(app("a").copy(locked = true), app("b", x = 1))).second
        val added = buildState(listOf(app("a"), app("b", x = 1), app("c", x = 2))).second
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            failureOf(reply(intentJson), session = built.session, structural = moved),
        )
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            failureOf(reply(intentJson), session = built.session, structural = locked),
        )
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            failureOf(reply(intentJson), session = built.session, structural = added),
        )
        // A removed session item diverges reconstruction — still CONTEXT_STALE.
        val removed = buildState(listOf(app("a"))).second
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ContextStale),
            failureOf(reply(intentJson), session = built.session, structural = removed),
        )
    }

    @Test
    fun malformedPayloadIsContractSchemaMismatch() {
        val (built, structural) = buildState(listOf(app("a")))
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.SchemaMismatch),
            failureOf(reply("not json at all"), session = built.session, structural = structural),
        )
    }

    @Test
    fun oversizePayloadIsContractOversize() {
        val (built, structural) = buildState(listOf(app("a")))
        val hugeJson = "{\"rationale\":\"" + "r".repeat(200_000) + "\"}"
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.Oversize),
            failureOf(reply(hugeJson), session = built.session, structural = structural),
        )
    }

    @Test
    fun mobilityContradictionPassesThroughAsContractFailure() {
        val (built, structural) = buildState(listOf(app("a"), docked("d")))
        val dockRef = built.export.items.single { it.mobility == app.lawnchair.organizer.personalization.Mobility.FIXED }.ref
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = built.export.items.map {
                if (it.ref == dockRef) ItemIntent(ref = it.ref, importance = Importance.HIGH) else ItemIntent(ref = it.ref, preserve = true)
            },
        )
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.MobilityContradiction(dockRef)),
            failureOf(reply(IntentCodec.encode(intent).decodeToString()), session = built.session, structural = structural),
        )
    }

    @Test
    fun envelopeFailuresPassThroughWithoutSessionAccess() {
        val (built, structural) = buildState(listOf(app("a")))
        // Spec 329: marker-less plain prose is now the normalizer's typed
        // failure — it no longer masquerades as a marker framing failure.
        assertEquals(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.UnrecognizedFormat),
            failureOf("the agent forgot the markers", session = built.session, structural = structural),
        )
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        assertEquals(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.FramingEmpty),
            failureOf(reply(""), session = built.session, structural = structural),
        )
        // FRAMING_MISSING stays owned by the marker path: a BEGIN line is
        // present but its END is missing.
        assertEquals(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.FramingMissing),
            failureOf(
                "${ExchangeContract.INTENT_BEGIN_MARKER}\n$intentJson",
                session = built.session,
                structural = structural,
            ),
        )
    }

    // --- Issue #329: normalizer framings reach the same pipeline outcome ---

    @Test
    fun standaloneJsonPreparesWithStandaloneFramingAndValidates() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1), docked("d")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val prepared = ExchangeImportPipeline.prepare(intentJson) as ExchangeImportPipeline.Prepared
        assertEquals(RecognizedImportFraming.STANDALONE_JSON, prepared.framing)
        assertEquals(built.export.exportId, prepared.intent.exportId)
        val result = ExchangeImportPipeline.import(intentJson, built.session, structural, now + 1)
        assertTrue(result is ExchangeImportResult.Validated)
    }

    @Test
    fun fencedJsonPreparesWithFencedFramingAndValidates() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1), docked("d")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val prepared = ExchangeImportPipeline.prepare(fencedReply(intentJson)) as ExchangeImportPipeline.Prepared
        assertEquals(RecognizedImportFraming.FENCED_JSON, prepared.framing)
        val result = ExchangeImportPipeline.import(fencedReply(intentJson), built.session, structural, now + 1)
        assertTrue(result is ExchangeImportResult.Validated)
    }

    @Test
    fun markedReplyPreparesWithMarkerFraming() {
        val (built, _) = buildState(listOf(app("a")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val prepared = ExchangeImportPipeline.prepare(reply(intentJson)) as ExchangeImportPipeline.Prepared
        assertEquals(RecognizedImportFraming.MARKER, prepared.framing)
    }

    @Test
    fun ambiguousBlocksAreTypedNormalizerFailures() {
        val (built, structural) = buildState(listOf(app("a")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val twoBlocks = "${fencedReply(intentJson, prose = false)}\n${fencedReply(intentJson, prose = false)}"
        assertEquals(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.AmbiguousBlocks),
            failureOf(twoBlocks, session = built.session, structural = structural),
        )
    }

    @Test
    fun envelopeGateSettlesOversizeBeforeTheNormalizer() {
        val (built, structural) = buildState(listOf(app("a")))
        val limit = ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES
        // One byte over: the #205-owned gate rejects with the unchanged typed
        // identity, before shape recognition runs.
        assertEquals(
            ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.InputOversize),
            failureOf("y".repeat(limit + 1), session = built.session, structural = structural),
        )
        // The same junk at exactly the limit passes the gate and reaches the
        // normalizer — proving the gate ordering, not a double rejection.
        assertEquals(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.UnrecognizedFormat),
            failureOf("y".repeat(limit), session = built.session, structural = structural),
        )
    }

    // --- Issue #329 security corpus (spec: nested wrapper, adversarial) ---

    @Test
    fun fenceInsideMarkerRegionIsNeverUnwrapped() {
        val (built, structural) = buildState(listOf(app("a")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val marked = "${ExchangeContract.INTENT_BEGIN_MARKER}\n```json\n$intentJson\n```\n${ExchangeContract.INTENT_END_MARKER}"
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.SchemaMismatch),
            failureOf(marked, session = built.session, structural = structural),
        )
    }

    @Test
    fun bareFenceLinesAroundAMarkerPairStayProse() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1), docked("d")))
        val intentJson = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()
        val wrapped = "```\n${reply(intentJson, prose = false)}\n```"
        val result = ExchangeImportPipeline.import(wrapped, built.session, structural, now + 1)
        assertTrue(result is ExchangeImportResult.Validated)
    }

    @Test
    fun innerInfoFenceExtendsTheBlockAndConvergesOnSchemaMismatch() {
        val (built, structural) = buildState(listOf(app("a")))
        // The inner ` ```json ` line does not close the block; the payload
        // verbatim includes it and can never be valid JSON (spec 329 D-4).
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.SchemaMismatch),
            failureOf("```json\n{\n```json\n}\n```", session = built.session, structural = structural),
        )
    }

    @Test
    fun contractFailuresAreFramingIndependent() {
        val (built, structural) = buildState(listOf(app("a")))
        val encoded = IntentCodec.encode(fullCoverageIntent(built)).decodeToString()

        // Decode-level: an unknown top-level key → SCHEMA_MISMATCH, standalone.
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.SchemaMismatch),
            failureOf(encoded.replace("itemIntents", "itemIntentz"), session = built.session, structural = structural),
        )

        // Decode-level: an unknown enum name → INVALID_ENUM, fenced.
        val invalidEnum = encoded.replaceFirst("\"preserve\":true", "\"importance\":\"WHENEVER\"")
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.InvalidEnum),
            failureOf(fencedReply(invalidEnum, prose = false), session = built.session, structural = structural),
        )

        // Validator-level: an out-of-scope ref → UNKNOWN_REF, fenced — the
        // normalizer pass never relaxes per-ref validation.
        val ref = built.export.items.first().ref
        val unknownRef = encoded.replace("\"$ref\"", "\"zzz\"")
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.UnknownRef("zzz")),
            failureOf(fencedReply(unknownRef, prose = false), session = built.session, structural = structural),
        )
    }
}
