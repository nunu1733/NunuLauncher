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

    // ============ Descriptor ↔ codec key sets (allow-list evidence) ============

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

    // ============ Descriptor-derived instruction rendering ============

    @Test
    fun composedOutputContractRendersTheDescriptorEnumAndLimitClaims() {
        val pkg = ExchangePackageComposer.compose("{}")
        for ((name, values) in IntentWireContract.enumClaims) {
            for (value in values) {
                assertTrue("enum $name=$value missing", pkg.contains(value))
            }
        }
        val confidenceBounds = IntentWireContract.claim("confidence.valueBound").value
        assertTrue(pkg.contains("from ${confidenceBounds[0]} to ${confidenceBounds[1]}"))
        assertTrue(
            pkg.contains("At most ${IntentWireContract.claim("itemIntents.entryLimit").value.single()} \"itemIntents\" entries"),
        )
        assertTrue(
            pkg.contains("at most ${IntentWireContract.claim("unresolvedRefs.entryLimit").value.single()} \"unresolvedRefs\" entries"),
        )
        assertTrue(pkg.contains("at most ${IntentWireContract.claim("rationale.lengthLimit").value.single()} characters"))
        assertTrue(
            pkg.contains("at most ${IntentWireContract.claim("groupSemantic.freeText.lengthLimit").value.single()} characters"),
        )
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
        val bounds = IntentWireContract.claim("confidence.valueBound").value
        assertTrue(pkg.contains("is an integer ${bounds[0]}-${bounds[1]}"))
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
     * Derives the parity case for one production claim from the claim's own
     * field/kind/value against the built export. This is the counterfactual
     * link: change the claim's value or field and the payload follows it —
     * and stops matching production, so the case fails.
     */
    private fun parityCase(claim: ConstraintClaim, built: BuiltExport): ParityCase {
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref
        val conditional = built.export.items.first { it.mobility == Mobility.CONDITIONAL }.ref
        val candidateRef = built.export.items.first { it.mobility == Mobility.CANDIDATE }.ref

        fun payload(itemEntries: String, top: String = "", unresolved: String = "") = doc(built, itemEntries, top = top, unresolved = unresolved)

        val (payload, expected) = when (claim.id) {
            "schemaVersion.presence" ->
                ("{\"exportId\":\"${built.export.exportId}\"}" to contract(IntentValidationFailure.SchemaMismatch))

            "schemaVersion.exactValue" ->
                (
                    "{\"schemaVersion\":\"${claim.value.single()}-stale\",\"exportId\":\"${built.export.exportId}\"}" to
                        contract(IntentValidationFailure.SchemaMismatch)
                    )

            "exportId.presence" ->
                ("{\"schemaVersion\":\"$schemaVersion\"}" to contract(IntentValidationFailure.SchemaMismatch))

            "item.ref.presence" ->
                (payload("{}") to contract(IntentValidationFailure.SchemaMismatch))

            "itemIntents.containerType" ->
                (
                    "{\"schemaVersion\":\"$schemaVersion\",\"exportId\":\"${built.export.exportId}\",\"itemIntents\":\"oops\"}" to
                        contract(IntentValidationFailure.SchemaMismatch)
                    )

            "unresolvedRefs.containerType" ->
                (payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":\"oops\"") to contract(IntentValidationFailure.SchemaMismatch))

            "globalPreference.containerType" ->
                (payload(entry(movable, ",\"preserve\":true"), top = ",\"globalPreference\":\"oops\"") to contract(IntentValidationFailure.SchemaMismatch))

            "desiredGroup.containerType" ->
                (payload(entry(movable, ",\"desiredGroup\":\"oops\"")) to contract(IntentValidationFailure.SchemaMismatch))

            "groupSemantic.containerType" ->
                (payload(entry(movable, ",\"groupSemantic\":\"oops\"")) to contract(IntentValidationFailure.SchemaMismatch))

            "importance.enum" ->
                (payload(entry(movable, ",\"importance\":\"${claim.value.first()}-NOPE\"")) to contract(IntentValidationFailure.InvalidEnum))

            "regionAffinity.enum" ->
                (payload(entry(movable, ",\"regionAffinity\":\"${claim.value.first()}-NOPE\"")) to contract(IntentValidationFailure.InvalidEnum))

            "confidence.valueType" ->
                (payload(entry(movable, ",\"preserve\":true"), top = ",\"confidence\":\"high\"") to contract(IntentValidationFailure.InvalidEnum))

            "confidence.valueBound" ->
                (
                    payload(entry(movable, ",\"preserve\":true"), top = ",\"confidence\":${claim.value.last().toInt() + 1}") to
                        contract(IntentValidationFailure.InvalidEnum)
                    )

            "pageAffinity.valueType" ->
                (payload(entry(movable, ",\"pageAffinity\":\"top\"")) to contract(IntentValidationFailure.InvalidEnum))

            "pageAffinity.exportBound" ->
                // The parity state exports a single page, so ordinal 1 is out of range.
                (payload(entry(movable, ",\"pageAffinity\":1")) to contract(IntentValidationFailure.InvalidEnum))

            "preserve.valueType" ->
                (payload(entry(movable, ",\"preserve\":\"yes\"")) to contract(IntentValidationFailure.SchemaMismatch))

            "minimizeMovement.valueType" ->
                (
                    payload(entry(movable, ",\"preserve\":true"), top = ",\"globalPreference\":{\"minimizeMovement\":\"yes\"}") to
                        contract(IntentValidationFailure.SchemaMismatch)
                    )

            "groupSemantic.anyOf" ->
                (payload(entry(movable, ",\"groupSemantic\":{}")) to contract(IntentValidationFailure.SchemaMismatch))

            "mobility.fixedSemanticForbidden" ->
                (payload(entry(fixed, ",\"importance\":\"$importanceCanonical\"")) to contract(IntentValidationFailure.MobilityContradiction(fixed)))

            "mobility.conditionalGroupingForbidden" ->
                (payload(entry(conditional, ",\"desiredGroup\":[\"$movable\"]")) to contract(IntentValidationFailure.MobilityContradiction(conditional)))

            "mobility.candidatePreserveForbidden" ->
                (payload(entry(candidateRef, ",\"preserve\":true")) to contract(IntentValidationFailure.MobilityContradiction(candidateRef)))

            "refScope.itemIntents" ->
                (payload(entry("zzz", ",\"preserve\":true")) to contract(IntentValidationFailure.UnknownRef("zzz")))

            "refScope.desiredGroup" ->
                (payload(entry(movable, ",\"desiredGroup\":[\"zzz\"]")) to contract(IntentValidationFailure.UnknownRef("zzz")))

            "refScope.unresolvedRefs" ->
                (payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"zzz\"]") to contract(IntentValidationFailure.UnknownRef("zzz")))

            "refPartition.duplicate" ->
                (payload("${entry(movable, ",\"preserve\":true")},${entry(movable)}") to contract(IntentValidationFailure.DuplicateRef))

            "refPartition.disjoint" ->
                (
                    payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[\"$movable\"]") to
                        contract(IntentValidationFailure.IncompleteCoverage)
                    )

            "rationale.lengthLimit" ->
                (
                    payload(entry(movable, ",\"preserve\":true"), top = ",\"rationale\":\"${"x".repeat(claim.value.single().toInt() + 1)}\"") to
                        contract(IntentValidationFailure.Oversize)
                    )

            "groupSemantic.freeText.lengthLimit" ->
                (
                    payload(entry(movable, ",\"groupSemantic\":{\"freeText\":\"${"x".repeat(claim.value.single().toInt() + 1)}\"}")) to
                        contract(IntentValidationFailure.Oversize)
                    )

            "itemIntents.entryLimit" ->
                (
                    payload((0..claim.value.single().toInt()).joinToString(",") { "{\"ref\":\"e$it\"}" }) to
                        contract(IntentValidationFailure.Oversize)
                    )

            "unresolvedRefs.entryLimit" ->
                (
                    payload(entry(movable, ",\"preserve\":true"), unresolved = ",\"unresolvedRefs\":[${(0..claim.value.single().toInt()).joinToString(",") { "\"e$it\"" }}]") to
                        contract(IntentValidationFailure.Oversize)
                    )

            else -> error("unmapped production claim ${claim.id}")
        }
        return ParityCase(claim, payload, expected)
    }

    @Test
    fun everyProductionClaimHasAKeyedParityCaseOnTheProductionPath() {
        val (built, structural) = parityState()
        val productionClaims = IntentWireContract.productionClaims
        // Claim ids are unique, and the parity cases are keyed by the claims
        // themselves — no third copy of the id set exists.
        assertEquals(productionClaims.map { it.id }.size, productionClaims.map { it.id }.toSet().size)
        val cases = productionClaims.associateWith { parityCase(it, built) }
        for ((claim, case) in cases) {
            // Field linkage: the fixture must target the claim's own field
            // (present in the payload unless the claim is about its absence),
            // and mobility claims are export-side facts the payload cannot
            // spell out.
            if (claim.kind != ClaimKind.PRESENCE && claim.kind != ClaimKind.MOBILITY_FORBIDDEN) {
                assertTrue("case for ${claim.id} does not touch its field", case.payload.contains("\"${claim.field}\""))
            }
            assertEquals("parity case ${claim.id}", case.expected, failureOf(fencedReply(case.payload), built, structural))
        }
    }

    @Test
    fun legalBoundaryValuesValidate() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val bound = IntentWireContract.claim("confidence.valueBound").value
        val inBoundConfidence = doc(
            built,
            entry(movable, ",\"preserve\":true"),
            top = ",\"confidence\":${bound[1]}",
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
            // The claim value is the exact sentence fragment the instruction
            // renders — edit either side and this assertion fails.
            assertTrue("policy ${claim.id} not rendered", pkg.contains(claim.value.single()))
        }
    }

    @Test
    fun everyAuthoringPolicyClaimHasACanonicalAcceptanceCase() {
        val (built, structural) = parityState()
        val movable = built.export.items.first { it.mobility == Mobility.MOVABLE }.ref
        val other = built.export.items.first { it.ref != movable && it.mobility == Mobility.MOVABLE }.ref
        val fixed = built.export.items.first { it.mobility == Mobility.FIXED }.ref

        val acceptance: Map<ConstraintClaim, String> = IntentWireContract.authoringPolicyClaims.associateWith { claim ->
            when (claim.id) {
                "policy.stringFieldsAsJsonStrings" ->
                    doc(built, entry(movable, ",\"groupSemantic\":{\"freeText\":\"Tools\"},\"preserve\":true"))

                "policy.uppercaseEnums" ->
                    doc(built, entry(movable, ",\"importance\":\"$importanceCanonical\""))

                "policy.stringListElements" ->
                    doc(built, entry(movable, ",\"desiredGroup\":[\"$other\"]"))

                "policy.desiredGroupNonEmpty" ->
                    doc(built, entry(movable, ",\"desiredGroup\":[\"$other\"]"))

                "policy.fixedAuthoring" ->
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
