package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentDecodeResult
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.IntentWireContract
import app.lawnchair.organizer.personalization.Mobility
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
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
 * Issue #348: the AI-facing contract of the exchange package stays in sync
 * with production truth (accepted spec 348).
 *
 * Two test lanes (plan "Modules and interfaces"):
 * - production acceptance/failure fixtures (parity matrix, policy matrix
 *   canonical-acceptance half, golden, #345 regressions) run through
 *   `ExchangeImportPipeline.import` — the production seam;
 * - descriptor/instruction direct checks (allow-list equality evidence,
 *   descriptor-derived rendering, self-check / ask-before-final pinning,
 *   repair-loop absence) are direct unit oracles.
 */
class Issue348AiFacingContractSyncTest {

    private val now = 1_000_000L

    // ---- builders (same harness pattern as ExchangeImportPipelineTest) ----

    private fun app(id: String, x: Int = 0) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun docked(id: String): CapturedItem = app(id).copy(placement = CapturedPlacement.Dock(0))

    /** Widgets project mobility CONDITIONAL (builder mobility rule). */
    private fun widget(id: String): CapturedItem = app(id).copy(kind = ItemKind.APPWIDGET)

    private fun candidate(component: String) = CandidateItem(
        id = CandidatePlanningIds.planningId(
            CandidateTarget.AppKey(ComponentKey(component), ProfileId("p0")),
        ),
        profile = ProfileId("p0"),
        kind = CandidateKind.APPLICATION,
        target = CandidateTarget.AppKey(ComponentKey(component), ProfileId("p0")),
        availability = Availability.AVAILABLE,
        span = GridSpan(1, 1),
    )

    private fun buildState(
        items: List<CapturedItem>,
        additions: List<CandidateItem> = emptyList(),
        pageCount: Int = 1,
    ): Pair<BuiltExport, CanonicalStructuralInputs> {
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            (0 until pageCount).map { Page(PageId("p$it"), PageOrder(it)) },
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, additions)
        val structural = CanonicalStructuralInputs(snapshot, targets, emptyMap<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>())
        val inputs = ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = now)
        return ContextExportBuilder.build(inputs, PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator()) to structural
    }

    private fun fencedReply(payloadJson: String): String = "```json\n$payloadJson\n```"

    private fun importOf(
        text: String,
        built: BuiltExport,
        structural: CanonicalStructuralInputs,
    ): ExchangeImportResult = ExchangeImportPipeline.import(text, built.session, structural, now + 1)

    private fun failureOf(
        text: String,
        built: BuiltExport,
        structural: CanonicalStructuralInputs,
    ): ExchangeImportFailure = (importOf(text, built, structural) as ExchangeImportResult.Failure).failure

    private fun contract(failure: IntentValidationFailure): ExchangeImportFailure = ExchangeImportFailure.Contract(failure)

    // ---- descriptor identity facts rendered as canonical JSON ----

    private val schemaVersion: String get() = IntentWireContract.intentSchemaVersion

    private val importanceCanonical: String get() = IntentWireContract.enumClaims.getValue("importance").first()

    private fun entry(ref: String, fields: String = ""): String = "{\"ref\":\"$ref\"$fields}"

    private fun doc(built: BuiltExport, itemEntries: String, top: String = "", unresolved: String = ""): String = "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${built.export.exportId}\"$top,\"itemIntents\":[$itemEntries]$unresolved}"

    /**
     * The canonical authoring payload for a built export, constructed from
     * the descriptor claims only: enum spellings come from
     * [IntentWireContract.enumClaims], and every value is the canonical JSON
     * type (proper strings, uppercase enums, integer confidence).
     */
    private fun canonicalEntries(built: BuiltExport): String = built.export.items.joinToString(",") { item ->
        when (item.mobility) {
            Mobility.CANDIDATE -> entry(item.ref, ",\"importance\":\"$importanceCanonical\"")
            else -> entry(item.ref, ",\"preserve\":true")
        }
    }

    private fun canonicalPayload(built: BuiltExport): String = doc(built, canonicalEntries(built))

    // ================= Lane 1: descriptor ↔ codec key sets =================

    @Test
    fun everyTopLevelDescriptorNameIsAcceptedByTheCodec() {
        for (field in IntentWireContract.topLevel) {
            val body = if (field.optional) ",\"${field.name}\":null" else ""
            val payload = "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\"$body}"
            val result = IntentCodec.decode(payload.encodeToByteArray())
            assertTrue("top-level ${field.name} rejected", result is IntentDecodeResult.Success)
        }
    }

    @Test
    fun everyItemDescriptorNameIsAcceptedByTheCodec() {
        for (field in IntentWireContract.item) {
            val itemJson = if (field.name == "ref") {
                "{\"ref\":\"r\"}"
            } else {
                "{\"ref\":\"r\",\"${field.name}\":null}"
            }
            val payload = "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"itemIntents\":[$itemJson]}"
            val result = IntentCodec.decode(payload.encodeToByteArray())
            assertTrue("item ${field.name} rejected", result is IntentDecodeResult.Success)
        }
    }

    @Test
    fun everyGlobalAndSemanticDescriptorNameIsAcceptedByTheCodec() {
        val global = "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"globalPreference\":{\"minimizeMovement\":null}}"
        assertTrue(IntentCodec.decode(global.encodeToByteArray()) is IntentDecodeResult.Success)
        val semantic = "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"itemIntents\":[{\"ref\":\"r\",\"groupSemantic\":{\"category\":\"tools\"}}]}"
        assertTrue(IntentCodec.decode(semantic.encodeToByteArray()) is IntentDecodeResult.Success)
    }

    @Test
    fun keysOutsideTheDescriptorAreRejectedByTheCodec() {
        fun failureOf(payload: String): IntentValidationFailure = (IntentCodec.decode(payload.encodeToByteArray()) as IntentDecodeResult.Failure).failure

        assertEquals(
            IntentValidationFailure.SchemaMismatch,
            failureOf("{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"zorp\":1}"),
        )
        assertEquals(
            IntentValidationFailure.SchemaMismatch,
            failureOf("{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"itemIntents\":[{\"ref\":\"r\",\"zorp\":1}]}"),
        )
        assertEquals(
            IntentValidationFailure.SchemaMismatch,
            failureOf("{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"globalPreference\":{\"zorp\":1}}"),
        )
        assertEquals(
            IntentValidationFailure.SchemaMismatch,
            failureOf("{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"e\",\"itemIntents\":[{\"ref\":\"r\",\"groupSemantic\":{\"zorp\":1}}]}"),
        )
    }

    // ============ Lane 2: descriptor-derived instruction rendering ============

    @Test
    fun composedOutputContractRendersTheDescriptorEnumAndLimitClaims() {
        val pkg = ExchangePackageComposer.compose("{}")
        for ((name, values) in IntentWireContract.enumClaims) {
            for (value in values) {
                assertTrue("enum $name=$value missing", pkg.contains(value))
            }
        }
        assertTrue(pkg.contains("\"$schemaVersion\""))
        assertTrue(pkg.contains("from ${IntentWireContract.confidenceMin} to ${IntentWireContract.confidenceMax}"))
        assertTrue(pkg.contains("At most ${IntentWireContract.maxItemIntents} \"itemIntents\" entries"))
        assertTrue(pkg.contains("at most ${IntentWireContract.maxUnresolvedRefs} \"unresolvedRefs\" entries"))
        assertTrue(pkg.contains("at most ${IntentWireContract.maxRationaleChars} characters"))
        assertTrue(pkg.contains("at most ${IntentWireContract.maxGroupSemanticFreeTextChars} characters"))
        // The context-dependent bound refers the agent to the CONTEXT data.
        assertTrue(pkg.contains("gridContext"))
        assertTrue(pkg.contains("pageCount"))
    }

    @Test
    fun finalizationSelfCheckIsRendered() {
        val pkg = ExchangePackageComposer.compose("{}")
        assertTrue(pkg.contains("Before sending your final answer, verify:"))
        assertTrue(pkg.contains("\"schemaVersion\" is exactly \"$schemaVersion\""))
        assertTrue(pkg.contains("there is no extra field"))
        assertTrue(pkg.contains("Enum values are UPPERCASE"))
        assertTrue(pkg.contains("is an integer ${IntentWireContract.confidenceMin}-${IntentWireContract.confidenceMax}"))
        assertTrue(pkg.contains("mentioned at most once"))
        assertTrue(pkg.contains("The FIXED, CONDITIONAL, and CANDIDATE rules are respected"))
        assertTrue(pkg.contains("exactly one importable JSON artifact"))
    }

    @Test
    fun askBeforeFinalIsRenderedInsteadOfInventingContract() {
        val pkg = ExchangePackageComposer.compose("{}")
        assertTrue(pkg.contains("ask the user before you finalize"))
        assertTrue(pkg.contains("do not fill the gap by inventing properties or values"))
        assertTrue(pkg.contains("do not invent a property for an idea the contract cannot express"))
    }

    @Test
    fun instructionDoesNotInstructAnAiRepairLoop() {
        // Spec 348 AC-1: the package may never tell the user to feed failure
        // diagnostics back to the AI; recovery phrasing lives only in the
        // typed-failure display strings and invites re-copying the final JSON.
        val pkg = ExchangePackageComposer.compose("{}")
        for (forbidden in listOf("send the error", "paste the failure", "diagnostics", "validation error")) {
            assertTrue("found '$forbidden'", !pkg.contains(forbidden))
        }
    }

    // ========= Lane 3: production-enforced parity matrix (pipeline) =========

    private fun parityState(): Pair<BuiltExport, CanonicalStructuralInputs> = buildState(
        items = listOf(app("a"), app("b", x = 1), docked("d"), widget("w")),
        additions = listOf(candidate("com.example.c")),
    )

    @Test
    fun productionEnforcedParityMatrix() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val other = built.export.items.first { it.ref != movable && it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref
        val conditional = built.export.items.first { it.mobility == Mobility.CONDITIONAL }.ref
        val candidateRef = built.export.items.first { it.mobility == Mobility.CANDIDATE }.ref

        val rows: List<Triple<String, String, ExchangeImportFailure>> = listOf(
            // Presence / container shape (decode).
            Triple(
                "schemaVersion missing",
                "{\"exportId\":\"${built.export.exportId}\"}",
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "exportId missing",
                "{\"schemaVersion\":\"$schemaVersion\"}",
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "item ref missing",
                doc(built, "{}"),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "itemIntents not an array",
                "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${built.export.exportId}\",\"itemIntents\":\"oops\"}",
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "unresolvedRefs not an array",
                doc(built, entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":\"oops\""),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            // Canonical JSON types (decode).
            Triple(
                "confidence non-integer",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"confidence\":\"high\""),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "preserve non-boolean",
                doc(built, entry(movable, ",\"preserve\":\"yes\"")),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "pageAffinity non-integer",
                doc(built, entry(movable, ",\"pageAffinity\":\"top\"")),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "importance lowercase (spec 345)",
                doc(built, entry(movable, ",\"importance\":\"high\"")),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "regionAffinity outside the enum",
                doc(built, entry(movable, ",\"regionAffinity\":\"top\"")),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "importance outside the enum",
                doc(built, entry(movable, ",\"importance\":\"WHENEVER\"")),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "confidence decimal (spec 345)",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"confidence\":0.82"),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "groupSemantic both members absent",
                doc(built, entry(movable, ",\"groupSemantic\":{}")),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            // Validator-owned bounds and ref rules.
            Triple(
                "confidence below the bound",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"confidence\":${IntentWireContract.confidenceMin - 1}"),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "confidence above the bound",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"confidence\":${IntentWireContract.confidenceMax + 1}"),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "unknown ref in itemIntents",
                doc(built, entry("zzz", ",\"preserve\":true")),
                contract(IntentValidationFailure.UnknownRef("zzz")),
            ),
            Triple(
                "unknown ref in desiredGroup",
                doc(built, entry(movable, ",\"desiredGroup\":[\"zzz\"]")),
                contract(IntentValidationFailure.UnknownRef("zzz")),
            ),
            Triple(
                "unknown ref in unresolvedRefs",
                doc(built, entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"zzz\"]"),
                contract(IntentValidationFailure.UnknownRef("zzz")),
            ),
            Triple(
                "duplicate ref in itemIntents",
                doc(built, "${entry(movable, ",\"preserve\":true")},${entry(movable)}"),
                contract(IntentValidationFailure.DuplicateRef),
            ),
            Triple(
                "ref in both partitions",
                "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${built.export.exportId}\"," +
                    "\"itemIntents\":[{\"ref\":\"$movable\",\"preserve\":true}],\"unresolvedRefs\":[\"$movable\"]}",
                contract(IntentValidationFailure.IncompleteCoverage),
            ),
            Triple(
                "duplicate ref inside unresolvedRefs",
                doc(built, entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"$other\",\"$other\"]"),
                contract(IntentValidationFailure.IncompleteCoverage),
            ),
            // Mobility rules (production-enforced half).
            Triple(
                "FIXED gains a semantic field",
                doc(built, entry(fixed, ",\"importance\":\"$importanceCanonical\"")),
                contract(IntentValidationFailure.MobilityContradiction(fixed)),
            ),
            Triple(
                "CONDITIONAL gains grouping",
                doc(built, entry(conditional, ",\"desiredGroup\":[\"$movable\"]")),
                contract(IntentValidationFailure.MobilityContradiction(conditional)),
            ),
            Triple(
                "CANDIDATE claims preserve",
                doc(built, entry(candidateRef, ",\"preserve\":true")),
                contract(IntentValidationFailure.MobilityContradiction(candidateRef)),
            ),
        )
        for ((name, payload, expected) in rows) {
            assertEquals(name, expected, failureOf(fencedReply(payload), built, structural))
        }
    }

    @Test
    fun pageAffinityStaysInsideTheExportedPageRange() {
        val (built, structural) = buildState(items = listOf(app("a")), pageCount = 2)
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val lastPage = doc(built, entry(movable, ",\"pageAffinity\":1"))
        assertTrue(importOf(fencedReply(lastPage), built, structural) is ExchangeImportResult.Validated)
        val pastLastPage = lastPage.replace("\"pageAffinity\":1", "\"pageAffinity\":2")
        assertEquals(
            contract(IntentValidationFailure.InvalidEnum),
            failureOf(fencedReply(pastLastPage), built, structural),
        )
    }

    @Test
    fun legalMobilityCombinationsValidate() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val other = built.export.items.first { it.ref != movable && it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref
        val conditional = built.export.items.first { it.mobility == Mobility.CONDITIONAL }.ref
        val candidateRef = built.export.items.first { it.mobility == Mobility.CANDIDATE }.ref
        val payload = doc(
            built,
            listOf(
                entry(
                    movable,
                    ",\"importance\":\"$importanceCanonical\",\"desiredGroup\":[\"$other\"]," +
                        "\"groupSemantic\":{\"freeText\":\"Tools\"},\"pageAffinity\":0,\"regionAffinity\":\"TOP\",\"preserve\":true",
                ),
                entry(other),
                entry(fixed, ",\"preserve\":true"),
                entry(conditional, ",\"preserve\":true"),
                entry(candidateRef, ",\"importance\":\"$importanceCanonical\""),
            ).joinToString(","),
        )
        val result = importOf(fencedReply(payload), built, structural) as ExchangeImportResult.Validated
        // Spec 330 completion: the bare entry and authored decisions only —
        // nothing was omitted in this document.
        assertEquals(0, result.validated.completed.omittedCount)
    }

    // ========= Lane 4: authoring policy matrix (canonical ⊆ accepted) =========

    @Test
    fun authoringPoliciesArePositivelyRendered() {
        val pkg = ExchangePackageComposer.compose("{}")
        assertTrue(pkg.contains("Write string values as JSON strings"))
        assertTrue(pkg.contains("if present, non-empty"))
        assertTrue(pkg.contains("author only \"preserve\": true"))
        assertTrue(pkg.contains("UPPERCASE exactly as listed"))
    }

    @Test
    fun goldenCanonicalFencedReplyValidatesThroughTheProductionPipeline() {
        // AC-4: a reply authored exactly per the instruction (canonical
        // authoring representation) in the canonical authoring form (one
        // fenced `json` block) reaches validation and completion.
        val (built, structural) = parityState()
        val result = importOf(fencedReply(canonicalPayload(built)), built, structural)
        assertTrue(result is ExchangeImportResult.Validated)
        val validated = (result as ExchangeImportResult.Validated).validated
        assertEquals(built.export.exportId, validated.intent.exportId)
        assertEquals(0, validated.completed.omittedCount)
    }

    @Test
    fun partialCanonicalAuthoringCompletesOmissionsWithoutGuessing() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val payload = doc(built, entry(movable, ",\"preserve\":true"))
        val result = importOf(fencedReply(payload), built, structural) as ExchangeImportResult.Validated
        // Everything the AI did not judge is canonical unresolved, never guessed.
        assertEquals(built.export.items.size - 1, result.validated.completed.omittedCount)
    }

    // ========= Lane 5: #345 contract-invalid regression fixtures =========

    @Test
    fun issue345ContractInvalidOutputsFailClosed() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref

        val rows: List<Triple<String, String, ExchangeImportFailure>> = listOf(
            Triple(
                "unknown globalPreference.organization (spec 345)",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"globalPreference\":{\"minimizeMovement\":true,\"organization\":\"flat\"}"),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "unknown top-level grouping (spec 345)",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"grouping\":{\"apps\":\"by color\"}"),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "unknown item-level grouping (spec 345)",
                doc(built, entry(movable, ",\"grouping\":\"by color\"")),
                contract(IntentValidationFailure.SchemaMismatch),
            ),
            Triple(
                "lowercase importance enum (spec 345)",
                doc(built, entry(movable, ",\"importance\":\"high\"")),
                contract(IntentValidationFailure.InvalidEnum),
            ),
            Triple(
                "decimal confidence (spec 345)",
                doc(built, entry(movable, ",\"preserve\":true"), top = ",\"confidence\":0.82"),
                contract(IntentValidationFailure.InvalidEnum),
            ),
        )
        for ((name, payload, expected) in rows) {
            assertEquals(name, expected, failureOf(fencedReply(payload), built, structural))
        }
        // The canonical payload from the same export still succeeds — the
        // spec 345 failures are the AI's contract misses, not pipeline damage.
        assertTrue(importOf(fencedReply(canonicalPayload(built)), built, structural) is ExchangeImportResult.Validated)
    }
}
