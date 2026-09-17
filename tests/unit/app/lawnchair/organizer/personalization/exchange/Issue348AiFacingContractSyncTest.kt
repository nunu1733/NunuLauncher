package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentDecodeResult
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.IntentWireContract
import app.lawnchair.organizer.personalization.IntentWireContract.ClaimKind
import app.lawnchair.organizer.personalization.IntentWireContract.ConstraintClaim
import app.lawnchair.organizer.personalization.IntentWireContract.Semantic
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
 * Oracle structure (accepted AC-2/AC-3): [IntentWireContract.claims] are the
 * sync surface, and this test consumes the claims themselves — no third copy
 * of the expectation set exists. For `PRODUCTION_ENFORCED` claims the parity
 * fixture input is derived from the claim's field/kind/value (bounds, limits,
 * enum spellings, exact values), so editing a claim against production fails
 * its case; keys equality and id uniqueness make any claim addition, removal,
 * or duplication surface as a failure. For `AUTHORING_POLICY` claims the
 * claim value is the exact sentence the instruction must render, and each
 * claim owns a canonical acceptance case reaching `Validated`
 * (`canonical ⊆ accepted`).
 *
 * Lanes: parity/acceptance/golden/regression fixtures run through
 * `ExchangeImportPipeline.import` (the production seam); descriptor and
 * instruction renderings are asserted directly.
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
    ): ExchangeImportFailure = when (val result = importOf(text, built, structural)) {
        is ExchangeImportResult.Failure -> result.failure
        is ExchangeImportResult.Validated -> error("unexpectedly validated: ${result.validated.intent.exportId}")
        else -> error("unknown import result")
    }

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

    // ============ Descriptor ↔ codec key sets (allow-list evidence) ============

    @Test
    fun everyTopLevelDescriptorNameIsAcceptedByTheCodec() {
        for (field in IntentWireContract.topLevel) {
            val body = if (!field.required) ",\"${field.name}\":null" else ""
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

    // ============ Descriptor-derived instruction rendering ============

    @Test
    fun composedOutputContractRendersTheDescriptorEnumAndLimitClaims() {
        val pkg = ExchangePackageComposer.compose("{}")
        for ((name, values) in IntentWireContract.enumClaims) {
            for (value in values) {
                assertTrue("enum $name=$value missing", pkg.contains(value))
            }
        }
        val confidence = IntentWireContract.field("confidence")
        assertTrue(pkg.contains("from ${confidence.min} to ${confidence.max}"))
        fun entryLimit(name: String): Int = (IntentWireContract.claim("$name.entryLimit").semantic as Semantic.EntryLimit).max
        assertTrue(pkg.contains("At most ${entryLimit("itemIntents")} \"itemIntents\" entries"))
        assertTrue(pkg.contains("at most ${entryLimit("unresolvedRefs")} \"unresolvedRefs\" entries"))
        assertTrue(pkg.contains("at most ${IntentWireContract.field("rationale").maxLength} characters"))
        assertTrue(pkg.contains("at most ${IntentWireContract.field("freeText").maxLength} characters"))
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
        val bounds = IntentWireContract.claim("confidence.valueBound").semantic as Semantic.IntBounds
        assertTrue(pkg.contains("is an integer ${bounds.min}-${bounds.max}"))
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

    // ==== Production-enforced parity matrix, keyed by the claims themselves ====

    private fun parityState(): Pair<BuiltExport, CanonicalStructuralInputs> = buildState(
        items = listOf(app("a"), app("b", x = 1), docked("d"), widget("w")),
        additions = listOf(candidate("com.example.c")),
    )

    private data class ParityCase(
        val claim: ConstraintClaim,
        val payload: String,
        val expected: ExchangeImportFailure,
    )

    /**
     * Derives the parity case for one production claim from the claim's
     * typed semantic payload against the built export. This is the
     * counterfactual link: edit the claim's semantic (type, bound, limit,
     * enum spelling, requiredness, mobility rule, ref location) and the
     * fixture input follows it — and stops matching production, so the case
     * fails. The expected typed failure is likewise derived from the
     * semantic, not hand-paired.
     */
    private fun parityCase(claim: ConstraintClaim, built: BuiltExport, pageCount: Int): ParityCase {
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref
        val conditional = built.export.items.first { it.mobility == Mobility.CONDITIONAL }.ref
        val candidateRef = built.export.items.first { it.mobility == Mobility.CANDIDATE }.ref

        fun payload(itemEntries: String, top: String = "", unresolved: String = "") = doc(built, itemEntries, top = top, unresolved = unresolved)

        val field = claim.field

        // Places a violating value at the claim's own JSON location, so the
        // fixture always exercises the field the claim is about.
        fun violation(value: String): String = when (claim.location) {
            IntentWireContract.Location.TOP_LEVEL -> payload(entry(movable, ",\"preserve\":true"), top = ",\"$field\":$value")

            IntentWireContract.Location.ITEM_ENTRY -> payload(entry(movable, ",\"$field\":$value"))

            IntentWireContract.Location.GLOBAL_PREFERENCE -> payload(
                entry(movable, ",\"preserve\":true"),
                top = ",\"globalPreference\":{\"$field\":$value}",
            )

            IntentWireContract.Location.GROUP_SEMANTIC -> payload(entry(movable, ",\"groupSemantic\":{\"$field\":$value}"))
        }

        val (payload, expected) = when (val s = claim.semantic) {
            is Semantic.Presence -> when (claim.location) {
                IntentWireContract.Location.TOP_LEVEL -> when (field) {
                    "schemaVersion" -> ("{\"exportId\":\"${built.export.exportId}\"}" to contract(IntentValidationFailure.SchemaMismatch))
                    "exportId" -> ("{\"schemaVersion\":\"$schemaVersion\"}" to contract(IntentValidationFailure.SchemaMismatch))
                    else -> error("unexpected presence claim $field")
                }

                IntentWireContract.Location.ITEM_ENTRY -> (payload("{}") to contract(IntentValidationFailure.SchemaMismatch))

                else -> error("unexpected presence location ${claim.location}")
            }

            is Semantic.ExactValue -> (
                "{\"schemaVersion\":\"${s.value}-stale\",\"exportId\":\"${built.export.exportId}\"}" to
                    contract(IntentValidationFailure.SchemaMismatch)
                )

            is Semantic.Type -> if (claim.location == IntentWireContract.Location.TOP_LEVEL) {
                // The base doc already carries the array/object key; the
                // violation replaces it with a scalar so the key appears once.
                (
                    "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${built.export.exportId}\",\"$field\":\"oops\"}" to
                        when (s.type) {
                            IntentWireContract.WireType.INTEGER -> contract(IntentValidationFailure.InvalidEnum)
                            else -> contract(IntentValidationFailure.SchemaMismatch)
                        }
                    )
            } else {
                when (s.type) {
                    IntentWireContract.WireType.INTEGER -> (violation("\"oops\"") to contract(IntentValidationFailure.InvalidEnum))
                    else -> (violation("\"oops\"") to contract(IntentValidationFailure.SchemaMismatch))
                }
            }

            is Semantic.AllowedValues -> (violation("\"${s.values.first()}-NOPE\"") to contract(IntentValidationFailure.InvalidEnum))

            is Semantic.IntBounds -> when {
                s.max != null -> (violation("${s.max + 1}") to contract(IntentValidationFailure.InvalidEnum))

                // Export-relative bound (pageAffinity): ordinal pageCount is
                // one past the last page of the exported grid.
                else -> (violation("$pageCount") to contract(IntentValidationFailure.InvalidEnum))
            }

            is Semantic.LengthLimit -> (violation("\"${"x".repeat(s.max + 1)}\"") to contract(IntentValidationFailure.Oversize))

            is Semantic.AnyOf -> (violation("{}") to contract(IntentValidationFailure.SchemaMismatch))

            is Semantic.EntryLimit -> (
                payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"$field\":[${(0..s.max).joinToString(",") { "\"e$it\"" }}]") to
                    contract(IntentValidationFailure.Oversize)
                )

            is Semantic.RefScope -> when (s.inKey) {
                "itemIntents" -> (payload(entry("zzz", ",\"preserve\":true")) to contract(IntentValidationFailure.UnknownRef("zzz")))
                "desiredGroup" -> (payload(entry(movable, ",\"desiredGroup\":[\"zzz\"]")) to contract(IntentValidationFailure.UnknownRef("zzz")))
                "unresolvedRefs" -> (payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"zzz\"]") to contract(IntentValidationFailure.UnknownRef("zzz")))
                else -> error("unexpected ref scope ${s.inKey}")
            }

            is Semantic.RefPartition -> if (s.duplicate) {
                (payload("${entry(movable, ",\"preserve\":true")},${entry(movable)}") to contract(IntentValidationFailure.DuplicateRef))
            } else {
                (payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"$movable\"]") to contract(IntentValidationFailure.IncompleteCoverage))
            }

            is Semantic.MobilityForbidden -> {
                val target = built.export.items.first { it.mobility.name == s.mobility }.ref
                val violation = when (s.forbiddenFields.first()) {
                    "importance" -> ",\"importance\":\"$importanceCanonical\""
                    "pageAffinity" -> ",\"pageAffinity\":0"
                    "regionAffinity" -> ",\"regionAffinity\":\"${IntentWireContract.enumClaims.getValue("regionAffinity").first()}\""
                    "desiredGroup" -> ",\"desiredGroup\":[\"$movable\"]"
                    "groupSemantic" -> ",\"groupSemantic\":{\"freeText\":\"Tools\"}"
                    "preserve" -> ",\"preserve\":true"
                    else -> error("unexpected forbidden field ${s.forbiddenFields.first()}")
                }
                (payload(entry(target, violation)) to contract(IntentValidationFailure.MobilityContradiction(target)))
            }

            is Semantic.PolicyRule -> error("policy claims are not production-enforced: ${claim.id}")
        }
        return ParityCase(claim, payload, expected)
    }

    @Test
    fun everyProductionClaimHasAKeyedParityCaseOnTheProductionPath() {
        val (built, structural) = parityState()
        val pageCount = built.export.grid.pageCount
        val productionClaims = IntentWireContract.productionClaims
        // Claim ids are unique, and the parity cases are keyed by the claims
        // themselves — no third copy of the id set exists.
        assertEquals(productionClaims.map { it.id }.size, productionClaims.map { it.id }.toSet().size)
        val cases = productionClaims.associateWith { parityCase(it, built, pageCount) }
        for ((claim, case) in cases) {
            // Field linkage: the fixture must target the claim's own field
            // (present in the payload unless the claim is about its absence),
            // and mobility claims are export-side facts the payload cannot
            // spell out.
            if (claim.kind != ClaimKind.PRESENCE && claim.kind != ClaimKind.MOBILITY_FORBIDDEN) {
                assertTrue("case for ${claim.id} does not touch its field", case.payload.contains("\"${claim.field}\""))
            }
            val outcome = importOf(fencedReply(case.payload), built, structural)
            assertTrue(
                "parity case ${claim.id} expected ${case.expected} but succeeded",
                outcome is ExchangeImportResult.Failure,
            )
            assertEquals("parity case ${claim.id}", case.expected, (outcome as ExchangeImportResult.Failure).failure)
        }
    }

    @Test
    fun legalBoundaryValuesValidate() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val inBoundConfidence = doc(
            built,
            entry(movable, ",\"preserve\":true"),
            top = ",\"confidence\":${IntentWireContract.field("confidence").max}",
        )
        assertTrue(importOf(fencedReply(inBoundConfidence), built, structural) is ExchangeImportResult.Validated)
    }

    // ==== Authoring policy matrix: positive render + per-claim acceptance ====

    @Test
    fun everyAuthoringPolicyClaimIsPositivelyRendered() {
        val pkg = ExchangePackageComposer.compose("{}")
        val policyClaims = IntentWireContract.authoringPolicyClaims
        assertEquals(policyClaims.map { it.id }.size, policyClaims.map { it.id }.toSet().size)
        for (claim in policyClaims) {
            // The claim's policy sentence is the exact fragment the
            // instruction renders — edit either side and this assertion fails.
            val sentence = (claim.semantic as Semantic.PolicyRule).sentence
            assertTrue("policy ${claim.id} not rendered", pkg.contains(sentence))
        }
    }

    @Test
    fun everyAuthoringPolicyClaimHasACanonicalAcceptanceCase() {
        val (built, structural) = parityState()

        fun refFor(mobility: String): String = built.export.items.first { it.mobility.name == mobility }.ref

        // The canonical entry is built from the claim's own semantic data
        // (entry field, value template, target mobility) — a policy whose
        // semantic changes produces a different fixture.
        val acceptance: Map<ConstraintClaim, String> = IntentWireContract.authoringPolicyClaims.associateWith { claim ->
            val rule = claim.semantic as Semantic.PolicyRule
            val ref = refFor(rule.targetMobility ?: "MOVABLE")
            val value = rule.entryValueTemplate.replace("REF", ref)
            doc(built, entry(ref, ",\"${rule.entryField}\":$value"))
        }
        for ((claim, canonical) in acceptance) {
            val rule = claim.semantic as Semantic.PolicyRule
            rule.minArrayElements?.let { min ->
                val elements = rule.entryValueTemplate.split("REF").size - 1
                assertTrue("policy ${claim.id} template authors fewer than $min elements", elements >= min)
            }
            val result = importOf(fencedReply(canonical), built, structural)
            assertTrue("policy ${claim.id} canonical case was not accepted", result is ExchangeImportResult.Validated)
        }
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

    // ========= #345 contract-invalid regression fixtures =========

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
