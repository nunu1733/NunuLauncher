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
 * fixtures (satisfying AND boundary-violating) are derived from the claim's
 * typed semantic, so editing a semantic against production breaks one side
 * or the other; keys equality and id uniqueness surface any claim addition,
 * removal, or duplication. For `AUTHORING_POLICY` claims the rendered
 * sentence and the canonical acceptance fixture are both generated from the
 * closed policy semantic (`canonical ⊆ accepted`).
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

    private fun contract(failure: IntentValidationFailure): ExchangeImportFailure = ExchangeImportFailure.Contract(failure)

    private fun failureOf(
        text: String,
        built: BuiltExport,
        structural: CanonicalStructuralInputs,
    ): ExchangeImportFailure = when (val result = importOf(text, built, structural)) {
        is ExchangeImportResult.Failure -> result.failure
        is ExchangeImportResult.Validated -> error("unexpectedly validated: ${result.validated.intent.exportId}")
        else -> error("unknown import result")
    }

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
        val confidence = IntentWireContract.field("confidence")
        assertTrue(pkg.contains("is an integer ${confidence.min}-${confidence.max}"))
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

    /** A fixture payload bound to the export state it must run against. */
    private data class Fixture(
        val built: BuiltExport,
        val structural: CanonicalStructuralInputs,
        val payload: String,
    )

    private data class ParityCase(
        val claim: ConstraintClaim,
        /** Fixtures that must reach `Validated` (the claim holds). */
        val accepts: List<Fixture>,
        /** Fixtures that must fail closed with exactly the recorded failure. */
        val rejects: List<Pair<Fixture, ExchangeImportFailure>>,
    )

    /**
     * Derives BOTH the satisfying and the boundary-violating fixtures for one
     * production claim from the claim's typed semantic payload. Editing a
     * semantic against production therefore breaks one side or the other: a
     * claim loosened beyond production fails a reject case, a claim tightened
     * below production fails an accept case. The expected typed failure is
     * likewise derived from the semantic, not hand-paired.
     */
    private fun parityCase(claim: ConstraintClaim, built: BuiltExport, structural: CanonicalStructuralInputs, pageCount: Int): ParityCase {
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref
        val conditional = built.export.items.first { it.mobility == Mobility.CONDITIONAL }.ref
        val candidateRef = built.export.items.first { it.mobility == Mobility.CANDIDATE }.ref
        val field = claim.field

        fun payload(itemEntries: String, top: String = "", unresolved: String = "") = doc(built, itemEntries, top = top, unresolved = unresolved)

        fun placed(value: String): String = when (claim.location) {
            IntentWireContract.Location.TOP_LEVEL -> payload(entry(movable, ",\"preserve\":true"), top = ",\"$field\":$value")

            IntentWireContract.Location.ITEM_ENTRY -> payload(entry(movable, ",\"$field\":$value"))

            IntentWireContract.Location.GLOBAL_PREFERENCE -> payload(
                entry(movable, ",\"preserve\":true"),
                top = ",\"globalPreference\":{\"$field\":$value}",
            )

            IntentWireContract.Location.GROUP_SEMANTIC -> payload(entry(movable, ",\"groupSemantic\":{\"$field\":$value}"))
        }

        // A canonical JSON value production accepts for this claim's field —
        // the satisfying fixture of Type claims.
        fun satisfyingValue(): String = when (field) {
            "itemIntents" -> canonicalEntries(built)
            "unresolvedRefs" -> "[\"$movable\"]"
            "globalPreference" -> "{\"minimizeMovement\":false}"
            "groupSemantic" -> "{\"freeText\":\"Tools\"}"
            "confidence" -> "${IntentWireContract.field("confidence").max}"
            "preserve" -> "true"
            "pageAffinity" -> "0"
            "desiredGroup" -> "[\"$movable\"]"
            "minimizeMovement" -> "false"
            else -> error("no canonical value for $field")
        }

        val accepts = mutableListOf<String>()
        val rejects = mutableListOf<Pair<String, ExchangeImportFailure>>()
        // Accept fixtures bound to a dedicated export state (entry limit).
        val extraAccepts = mutableListOf<Fixture>()

        when (val s = claim.semantic) {
            is Semantic.Presence -> when (claim.location) {
                IntentWireContract.Location.TOP_LEVEL -> when (field) {
                    "schemaVersion" -> rejects += "{\"exportId\":\"${built.export.exportId}\"}" to contract(IntentValidationFailure.SchemaMismatch)
                    "exportId" -> rejects += "{\"schemaVersion\":\"$schemaVersion\"}" to contract(IntentValidationFailure.SchemaMismatch)
                    else -> error("unexpected presence claim $field")
                }

                IntentWireContract.Location.ITEM_ENTRY -> rejects += payload("{}") to contract(IntentValidationFailure.SchemaMismatch)

                else -> error("unexpected presence location ${claim.location}")
            }

            is Semantic.ExactValue ->
                rejects += "{\"schemaVersion\":\"${s.value}-stale\",\"exportId\":\"${built.export.exportId}\"}" to
                    contract(IntentValidationFailure.SchemaMismatch)

            is Semantic.Type -> if (claim.location == IntentWireContract.Location.TOP_LEVEL) {
                // The base doc already carries the array/object key; the
                // violating scalar replaces it so the key appears once.
                val expected = if (s.type == IntentWireContract.WireType.INTEGER) {
                    contract(IntentValidationFailure.InvalidEnum)
                } else {
                    contract(IntentValidationFailure.SchemaMismatch)
                }
                rejects += "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${built.export.exportId}\",\"$field\":\"oops\"}" to expected
                accepts += payload(entry(movable, ",\"preserve\":true"))
            } else {
                val expected = if (s.type == IntentWireContract.WireType.INTEGER) {
                    contract(IntentValidationFailure.InvalidEnum)
                } else {
                    contract(IntentValidationFailure.SchemaMismatch)
                }
                rejects += placed("\"oops\"") to expected
                accepts += placed(satisfyingValue())
            }

            is Semantic.AllowedValues -> {
                for (value in s.values) {
                    accepts += placed("\"$value\"")
                }
                rejects += placed("\"${s.values.first()}-NOPE\"") to contract(IntentValidationFailure.InvalidEnum)
            }

            is Semantic.IntBounds -> {
                accepts += placed("${s.min}")
                if (s.max != null) {
                    accepts += placed("${s.max}")
                    rejects += placed("${s.max + 1}") to contract(IntentValidationFailure.InvalidEnum)
                } else {
                    // Export-relative bound: the last page of the grid is the
                    // largest legal ordinal.
                    accepts += placed("${pageCount - 1}")
                    rejects += placed("$pageCount") to contract(IntentValidationFailure.InvalidEnum)
                }
                rejects += placed("${s.min - 1}") to contract(IntentValidationFailure.InvalidEnum)
            }

            is Semantic.LengthLimit -> {
                accepts += placed("\"${"x".repeat(s.max)}\"")
                rejects += placed("\"${"x".repeat(s.max + 1)}\"") to contract(IntentValidationFailure.Oversize)
            }

            is Semantic.AnyOf -> {
                for (member in s.members) {
                    accepts += payload(entry(movable, ",\"groupSemantic\":{\"$member\":\"Tools\"}"))
                }
                rejects += payload(entry(movable, ",\"groupSemantic\":{}")) to contract(IntentValidationFailure.SchemaMismatch)
            }

            is Semantic.EntryLimit -> {
                // The accept fixture fills exactly the advertised entry budget
                // with valid entries on a matching export; the reject fixture
                // oversteps the count by one, so entry count is the only
                // variable between the two sides.
                val (big, bigStructural) = buildState(items = (0 until s.max).map { app("a$it", x = it % 4) })
                val bigRefs = big.export.items.map { it.ref }
                val acceptDoc = if (field == "itemIntents") {
                    val entries = bigRefs.joinToString(",") { entry(it, ",\"preserve\":true") }
                    "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${big.export.exportId}\",\"itemIntents\":[$entries]}"
                } else {
                    val refs = bigRefs.joinToString(",") { "\"$it\"" }
                    "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${big.export.exportId}\",\"unresolvedRefs\":[$refs]}"
                }
                extraAccepts += Fixture(big, bigStructural, acceptDoc)
                val overEntries = (0..s.max).joinToString(",") { "\"e$it\"" }
                rejects += if (field == "itemIntents") {
                    payload(overEntries) to contract(IntentValidationFailure.Oversize)
                } else {
                    payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"$field\":[$overEntries]") to
                        contract(IntentValidationFailure.Oversize)
                }
            }

            is Semantic.RefScope -> when (s.inKey) {
                "itemIntents" -> rejects += payload(entry("zzz", ",\"preserve\":true")) to contract(IntentValidationFailure.UnknownRef("zzz"))
                "desiredGroup" -> rejects += payload(entry(movable, ",\"desiredGroup\":[\"zzz\"]")) to contract(IntentValidationFailure.UnknownRef("zzz"))
                "unresolvedRefs" -> rejects += payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"zzz\"]") to contract(IntentValidationFailure.UnknownRef("zzz"))
                else -> error("unexpected ref scope ${s.inKey}")
            }

            is Semantic.RefPartition -> if (s.duplicate) {
                rejects += payload("${entry(movable, ",\"preserve\":true")},${entry(movable)}") to contract(IntentValidationFailure.DuplicateRef)
            } else {
                rejects += payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"$movable\"]") to
                    contract(IntentValidationFailure.IncompleteCoverage)
            }

            is Semantic.MobilityForbidden -> {
                val target = built.export.items.first { it.mobility.name == s.mobility }.ref
                val canonicalValues = mapOf(
                    "importance" to "\"$importanceCanonical\"",
                    "pageAffinity" to "0",
                    "regionAffinity" to "\"${IntentWireContract.enumClaims.getValue("regionAffinity").first()}\"",
                    "desiredGroup" to "[\"$movable\"]",
                    "groupSemantic" to "{\"freeText\":\"Tools\"}",
                    "preserve" to "true",
                )
                // Every forbidden field is individually rejected...
                for (forbidden in s.forbiddenFields) {
                    rejects += payload(entry(target, ",\"$forbidden\":${canonicalValues.getValue(forbidden)}")) to
                        contract(IntentValidationFailure.MobilityContradiction(target))
                }
                // ...and a field outside the forbidden list stays accepted.
                val universe = canonicalValues.keys
                val allowed = universe.first { it !in s.forbiddenFields }
                accepts += payload(entry(target, ",\"$allowed\":${canonicalValues.getValue(allowed)}"))
            }

            else -> error("policy claims are not production-enforced: ${claim.id}")
        }
        fun fix(payload: String): Fixture = Fixture(built, structural, payload)
        return ParityCase(claim, accepts.map(::fix) + extraAccepts, rejects.map { (p, f) -> fix(p) to f })
    }

    @Test
    fun everyProductionClaimHasAKeyedParityCaseOnTheProductionPath() {
        val (built, structural) = parityState()
        val pageCount = built.export.grid.pageCount
        val productionClaims = IntentWireContract.productionClaims
        // Claim ids are unique, and the parity cases are keyed by the claims
        // themselves — no third copy of the id set exists.
        assertEquals(productionClaims.map { it.id }.size, productionClaims.map { it.id }.toSet().size)
        val cases = productionClaims.associateWith { parityCase(it, built, structural, pageCount) }
        for ((claim, case) in cases) {
            // Field linkage: the fixture must target the claim's own field
            // (present in the payload unless the claim is about its absence),
            // and mobility claims are export-side facts the payload cannot
            // spell out.
            if (claim.kind != ClaimKind.PRESENCE && claim.kind != ClaimKind.MOBILITY_FORBIDDEN) {
                assertTrue("case for ${claim.id} does not touch its field", case.rejects.first().first.payload.contains("\"${claim.field}\""))
            }
            for ((fixture, expected) in case.rejects) {
                assertEquals(
                    "reject case ${claim.id}",
                    expected,
                    failureOf(fencedReply(fixture.payload), fixture.built, fixture.structural),
                )
            }
            for (fixture in case.accepts) {
                assertTrue(
                    "accept case ${claim.id} was not validated",
                    importOf(fencedReply(fixture.payload), fixture.built, fixture.structural) is ExchangeImportResult.Validated,
                )
            }
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
            // The rendered sentence is generated from the closed policy
            // semantic (IntentWireContract.policySentence) — no separate
            // prose literal can drift.
            val sentence = IntentWireContract.policySentence(claim.id)
            assertTrue("policy ${claim.id} not rendered", pkg.contains(sentence))
        }
    }

    @Test
    fun everyAuthoringPolicyClaimHasACanonicalAcceptanceCase() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val other = built.export.items.first { it.ref != movable && it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref

        // The canonical entry is generated from the closed policy semantic,
        // so a semantic change produces a different fixture.
        val acceptance: Map<ConstraintClaim, String> = IntentWireContract.authoringPolicyClaims.associateWith { claim ->
            when (val s = claim.semantic) {
                is Semantic.StringsAsJsonStrings ->
                    doc(built, entry(movable, ",\"groupSemantic\":{\"freeText\":\"Tools\"},\"preserve\":true"))

                is Semantic.UppercaseSpelledEnums ->
                    doc(built, entry(movable, ",\"importance\":\"${IntentWireContract.enumClaims.getValue(s.field).first()}\""))

                is Semantic.StringArrayOfStrings ->
                    doc(built, entry(movable, ",\"${s.field}\":[\"$other\"]"))

                is Semantic.NonEmptyArray ->
                    doc(built, entry(movable, ",\"${s.field}\":[\"$other\"]"))

                is Semantic.FixedAuthoredAsPreserve ->
                    doc(built, entry(fixed, ",\"preserve\":true"))

                else -> error("unmapped authoring policy claim ${claim.id}")
            }
        }
        for ((claim, canonical) in acceptance) {
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
