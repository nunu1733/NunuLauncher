package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.rules.UserDefinedCategoryNameRules

/**
 * Issue #348: the wire descriptor of the #204 intent payload — the single
 * data table that the [IntentCodec] allow-lists, the AI-facing output
 * contract section of the exchange package
 * ([app.lawnchair.organizer.personalization.exchange.ExchangePackageComposer]),
 * and the contract-sync test oracles are all rendered from.
 *
 * Structure:
 * - [FieldSpec] states each wire field's canonical authoring type,
 *   requiredness, enum spellings, bounds, and length limits. There is no
 *   second definition of those facts anywhere.
 * - [claims] states every constraint as a typed [ConstraintClaim] whose
 *   [Semantic] payload is built from the same [FieldSpec] objects (or the
 *   contract constants). The composer renders claims; the sync test walks
 *   claims and derives its parity fixtures from the semantic payload, so an
 *   edit that contradicts production (type, bound, limit, enum spelling,
 *   requiredness, mobility rule) changes the fixture input and fails its
 *   case against production.
 *
 * Enforcement split (spec 348 Decision 1): `PRODUCTION_ENFORCED` claims have
 * a keyed parity case on the production pipeline; `AUTHORING_POLICY` claims
 * (production accepts broader input) carry the exact sentence the instruction
 * must render plus the canonical entry template whose acceptance pins
 * `canonical ⊆ accepted`.
 *
 * Per-export facts (`exportId`, refs, `gridContext.pageCount`) are never
 * stored: the composer does not interpret the export and the instruction
 * refers the agent to the CONTEXT data.
 */
internal object IntentWireContract {

    enum class Enforcement { PRODUCTION_ENFORCED, AUTHORING_POLICY }

    enum class WireType { STRING, BOOLEAN, INTEGER, STRING_ARRAY, OBJECT, OBJECT_ARRAY }

    enum class Location { TOP_LEVEL, ITEM_ENTRY, GLOBAL_PREFERENCE, GROUP_SEMANTIC }

    /** Canonical authoring facts of one wire field — the single source. */
    data class FieldSpec(
        val name: String,
        val type: WireType,
        val location: Location,
        val required: Boolean,
        val enumValues: List<String> = emptyList(),
        /** Inclusive value bounds for integer fields. */
        val min: Long? = null,
        val max: Long? = null,
        /** Upper character bound for string fields. */
        val maxLength: Int? = null,
        /** The exact advertised spelling (schemaVersion). */
        val exactValue: String? = null,
    )

    // The four field groups; the group keys are the codec allow-list source.

    val topLevel: List<FieldSpec> = listOf(
        FieldSpec(
            "schemaVersion",
            WireType.STRING,
            Location.TOP_LEVEL,
            required = true,
            exactValue = ContextExportContract.INTENT_SCHEMA_VERSION,
        ),
        FieldSpec("exportId", WireType.STRING, Location.TOP_LEVEL, required = true),
        FieldSpec("itemIntents", WireType.OBJECT_ARRAY, Location.TOP_LEVEL, required = false),
        FieldSpec("unresolvedRefs", WireType.STRING_ARRAY, Location.TOP_LEVEL, required = false),
        FieldSpec("globalPreference", WireType.OBJECT, Location.TOP_LEVEL, required = false),
        FieldSpec(
            "rationale",
            WireType.STRING,
            Location.TOP_LEVEL,
            required = false,
            maxLength = ContextExportContract.MAX_RATIONALE_CHARS,
        ),
        FieldSpec(
            "confidence",
            WireType.INTEGER,
            Location.TOP_LEVEL,
            required = false,
            min = ContextExportContract.CONFIDENCE_MIN.toLong(),
            max = ContextExportContract.CONFIDENCE_MAX.toLong(),
        ),
    )

    val item: List<FieldSpec> = listOf(
        FieldSpec("ref", WireType.STRING, Location.ITEM_ENTRY, required = true),
        FieldSpec(
            "importance",
            WireType.STRING,
            Location.ITEM_ENTRY,
            required = false,
            enumValues = Importance.entries.map { it.name },
        ),
        FieldSpec("desiredGroup", WireType.STRING_ARRAY, Location.ITEM_ENTRY, required = false),
        FieldSpec("groupSemantic", WireType.OBJECT, Location.ITEM_ENTRY, required = false),
        // The upper page bound is export-dependent (gridContext.pageCount - 1):
        // min is fixed, max is context-relative and set by the claim below.
        FieldSpec("pageAffinity", WireType.INTEGER, Location.ITEM_ENTRY, required = false, min = 0),
        FieldSpec(
            "regionAffinity",
            WireType.STRING,
            Location.ITEM_ENTRY,
            required = false,
            enumValues = ExportRegionKind.entries.map { it.name },
        ),
        FieldSpec("preserve", WireType.BOOLEAN, Location.ITEM_ENTRY, required = false),
    )

    val globalPreference: List<FieldSpec> = listOf(
        FieldSpec("minimizeMovement", WireType.BOOLEAN, Location.GLOBAL_PREFERENCE, required = false),
    )

    val groupSemantic: List<FieldSpec> = listOf(
        FieldSpec("categoryRef", WireType.STRING, Location.GROUP_SEMANTIC, required = false),
        FieldSpec(
            "proposalLabel",
            WireType.STRING,
            Location.GROUP_SEMANTIC,
            required = false,
            maxLength = UserDefinedCategoryNameRules.MAX_CODE_POINTS,
        ),
    )

    val allFields: List<FieldSpec> = topLevel + item + globalPreference + groupSemantic

    fun field(name: String): FieldSpec = allFields.first { it.name == name }

    /** Enum spellings per field name, rendered into the output contract. */
    val enumClaims: Map<String, List<String>> =
        allFields.filter { it.enumValues.isNotEmpty() }.associate { it.name to it.enumValues }

    /**
     * Issue #337: the model-level exactly-one-of rule for `groupSemantic` — an
     * existing-category reference or a run-scoped proposal, never both.
     */
    val groupSemanticExactlyOneOf: Pair<String, String> = "categoryRef" to "proposalLabel"

    /** Typed constraint semantics — what the claim actually asserts. */
    sealed interface Semantic {
        /** The field [required] flag is production-enforced. */
        data class Presence(val required: Boolean) : Semantic

        /** The field, when present, must be this container/primitive type. */
        data class Type(val type: WireType) : Semantic

        /** The field must equal the exact advertised spelling. */
        data class ExactValue(val value: String) : Semantic

        /** The field must use exactly these spellings. */
        data class AllowedValues(val values: List<String>) : Semantic

        /**
         * Inclusive integer bounds. A `null` max is export-relative
         * (`gridContext.pageCount - 1`).
         */
        data class IntBounds(val min: Long, val max: Long?) : Semantic

        /** String length upper bound. */
        data class LengthLimit(val max: Int) : Semantic

        /** Exactly one of the member fields must be set. */
        data class ExactlyOneOf(val members: List<String>) : Semantic

        /** Array size upper bound. */
        data class EntryLimit(val max: Int) : Semantic

        /** The field's refs must exist in the export scope. */
        data class RefScope(val inKey: String) : Semantic

        /** Refs must not duplicate (true) or cross the partition (false). */
        data class RefPartition(val duplicate: Boolean) : Semantic

        /** The named mobility forbids authoring these fields. */
        data class MobilityForbidden(val mobility: String, val forbiddenFields: List<String>) : Semantic

        // ---- Authoring policies (closed semantics; the rendered prose and
        // the canonical acceptance fixture are both generated from these —
        // a free-form sentence could be inverted without any test
        // noticing). ----

        /** Every string value is authored as a proper JSON string. */
        object StringsAsJsonStrings : Semantic

        /** Enum values use the exact advertised spellings. */
        data class UppercaseSpelledEnums(val field: String) : Semantic

        /** String arrays contain only string elements. */
        data class StringArrayOfStrings(val field: String) : Semantic

        /** The array, when present, has at least [minElements] elements. */
        data class NonEmptyArray(val field: String, val minElements: Int) : Semantic

        /** A FIXED item is authored with preserve:true, or left unjudged. */
        object FixedAuthoredAsPreserve : Semantic

        /**
         * Issue #337: an existing-category reference is authored as a `ref`
         * advertised in the CONTEXT data's `categories` array — never a
         * category name, and never a made-up identifier.
         */
        object CategoryRefFromContextArray : Semantic
    }

    data class ConstraintClaim(
        /** Stable unique id; the parity/policy oracles are keyed on this. */
        val id: String,
        /** The wire key the constraint is about. */
        val field: String,
        val location: Location,
        val kind: ClaimKind,
        val enforcement: Enforcement,
        val semantic: Semantic,
    )

    enum class ClaimKind { PRESENCE, TYPE, EXACT_VALUE, ALLOWED_VALUES, VALUE_BOUND, LENGTH_LIMIT, EXACTLY_ONE, ENTRY_LIMIT, REF_SCOPE, REF_PARTITION, MOBILITY_FORBIDDEN, POLICY }

    private fun claim(
        id: String,
        spec: FieldSpec,
        kind: ClaimKind,
        enforcement: Enforcement,
        semantic: Semantic,
    ): ConstraintClaim = ConstraintClaim(id, spec.name, spec.location, kind, enforcement, semantic)

    private val schemaVersionSpec = field("schemaVersion")
    private val confidenceSpec = field("confidence")
    private val rationaleSpec = field("rationale")
    private val proposalLabelSpec = field("proposalLabel")
    private val pageAffinitySpec = field("pageAffinity")

    /**
     * `mobility` is an export-item fact the intent payload never spells out —
     * the mobility claims point at this synthetic spec so their field/
     * location stay typed without joining the wire allow-lists.
     */
    private val mobilitySpec = FieldSpec("mobility", WireType.STRING, Location.ITEM_ENTRY, required = false)

    /**
     * Every constraint the AI-facing contract states. Production-enforced
     * semantics are built from the [FieldSpec] objects above, so editing a
     * spec changes the claim, which changes the rendered instruction and the
     * derived parity fixture input at the same time.
     */
    val claims: List<ConstraintClaim> = listOf(
        // Presence / identity (decode).
        claim("schemaVersion.presence", schemaVersionSpec, ClaimKind.PRESENCE, Enforcement.PRODUCTION_ENFORCED, Semantic.Presence(schemaVersionSpec.required)),
        claim(
            "schemaVersion.exactValue",
            schemaVersionSpec,
            ClaimKind.EXACT_VALUE,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.ExactValue(schemaVersionSpec.exactValue!!),
        ),
        claim("exportId.presence", field("exportId"), ClaimKind.PRESENCE, Enforcement.PRODUCTION_ENFORCED, Semantic.Presence(field("exportId").required)),
        claim("item.ref.presence", field("ref"), ClaimKind.PRESENCE, Enforcement.PRODUCTION_ENFORCED, Semantic.Presence(field("ref").required)),
        // Container shapes (decode).
        claim("itemIntents.containerType", field("itemIntents"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("itemIntents").type)),
        claim("unresolvedRefs.containerType", field("unresolvedRefs"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("unresolvedRefs").type)),
        claim("globalPreference.containerType", field("globalPreference"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("globalPreference").type)),
        claim("desiredGroup.containerType", field("desiredGroup"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("desiredGroup").type)),
        claim("groupSemantic.containerType", field("groupSemantic"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("groupSemantic").type)),
        // Primitive types (decode).
        claim("confidence.valueType", confidenceSpec, ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(confidenceSpec.type)),
        claim("pageAffinity.valueType", pageAffinitySpec, ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(pageAffinitySpec.type)),
        claim("preserve.valueType", field("preserve"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("preserve").type)),
        claim("minimizeMovement.valueType", field("minimizeMovement"), ClaimKind.TYPE, Enforcement.PRODUCTION_ENFORCED, Semantic.Type(field("minimizeMovement").type)),
        // Enum spellings (decode).
        claim("importance.enum", field("importance"), ClaimKind.ALLOWED_VALUES, Enforcement.PRODUCTION_ENFORCED, Semantic.AllowedValues(field("importance").enumValues)),
        claim("regionAffinity.enum", field("regionAffinity"), ClaimKind.ALLOWED_VALUES, Enforcement.PRODUCTION_ENFORCED, Semantic.AllowedValues(field("regionAffinity").enumValues)),
        // Value bounds (decode / validate).
        claim(
            "confidence.valueBound",
            confidenceSpec,
            ClaimKind.VALUE_BOUND,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.IntBounds(confidenceSpec.min!!, confidenceSpec.max!!),
        ),
        claim(
            "pageAffinity.exportBound",
            pageAffinitySpec,
            ClaimKind.VALUE_BOUND,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.IntBounds(pageAffinitySpec.min!!, max = null),
        ),
        // Exactly-one-of (model rule).
        claim(
            "groupSemantic.exactlyOneOf",
            field("groupSemantic"),
            ClaimKind.EXACTLY_ONE,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.ExactlyOneOf(listOf(groupSemanticExactlyOneOf.first, groupSemanticExactlyOneOf.second)),
        ),
        // Mobility rules (validate).
        claim(
            "mobility.fixedSemanticForbidden",
            mobilitySpec,
            ClaimKind.MOBILITY_FORBIDDEN,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.MobilityForbidden(
                "FIXED",
                listOf("importance", "pageAffinity", "regionAffinity", "desiredGroup", "groupSemantic"),
            ),
        ),
        claim(
            "mobility.conditionalGroupingForbidden",
            mobilitySpec,
            ClaimKind.MOBILITY_FORBIDDEN,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.MobilityForbidden("CONDITIONAL", listOf("desiredGroup", "groupSemantic")),
        ),
        claim(
            "mobility.candidatePreserveForbidden",
            mobilitySpec,
            ClaimKind.MOBILITY_FORBIDDEN,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.MobilityForbidden("CANDIDATE", listOf("preserve")),
        ),
        // Ref scope and partition (validate).
        claim("refScope.itemIntents", field("ref"), ClaimKind.REF_SCOPE, Enforcement.PRODUCTION_ENFORCED, Semantic.RefScope("itemIntents")),
        claim("refScope.desiredGroup", field("desiredGroup"), ClaimKind.REF_SCOPE, Enforcement.PRODUCTION_ENFORCED, Semantic.RefScope("desiredGroup")),
        claim(
            "refScope.groupSemanticCategoryRef",
            field("categoryRef"),
            ClaimKind.REF_SCOPE,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.RefScope("categoryRef"),
        ),
        claim("refScope.unresolvedRefs", field("unresolvedRefs"), ClaimKind.REF_SCOPE, Enforcement.PRODUCTION_ENFORCED, Semantic.RefScope("unresolvedRefs")),
        claim("refPartition.duplicate", field("ref"), ClaimKind.REF_PARTITION, Enforcement.PRODUCTION_ENFORCED, Semantic.RefPartition(duplicate = true)),
        claim("refPartition.disjoint", field("ref"), ClaimKind.REF_PARTITION, Enforcement.PRODUCTION_ENFORCED, Semantic.RefPartition(duplicate = false)),
        // Resource limits (decode, OVERSIZE).
        claim(
            "rationale.lengthLimit",
            rationaleSpec,
            ClaimKind.LENGTH_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.LengthLimit(rationaleSpec.maxLength!!),
        ),
        claim(
            "groupSemantic.proposalLabel.lengthLimit",
            proposalLabelSpec,
            ClaimKind.LENGTH_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.LengthLimit(proposalLabelSpec.maxLength!!),
        ),
        claim(
            "itemIntents.entryLimit",
            field("itemIntents"),
            ClaimKind.ENTRY_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.EntryLimit(ContextExportContract.MAX_INTENT_ENTRIES),
        ),
        claim(
            "unresolvedRefs.entryLimit",
            field("unresolvedRefs"),
            ClaimKind.ENTRY_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            Semantic.EntryLimit(ContextExportContract.MAX_INTENT_UNRESOLVED),
        ),
        // Authoring policies (production accepts broader input). The sentence
        // is the exact fragment the instruction renders; the canonical entry
        // template is what the acceptance case authors.
        claim(
            "policy.stringFieldsAsJsonStrings",
            field("groupSemantic"),
            ClaimKind.POLICY,
            Enforcement.AUTHORING_POLICY,
            Semantic.StringsAsJsonStrings,
        ),
        claim(
            "policy.uppercaseEnums",
            field("importance"),
            ClaimKind.POLICY,
            Enforcement.AUTHORING_POLICY,
            Semantic.UppercaseSpelledEnums("importance"),
        ),
        claim(
            "policy.stringListElements",
            field("desiredGroup"),
            ClaimKind.POLICY,
            Enforcement.AUTHORING_POLICY,
            Semantic.StringArrayOfStrings("desiredGroup"),
        ),
        claim(
            "policy.desiredGroupNonEmpty",
            field("desiredGroup"),
            ClaimKind.POLICY,
            Enforcement.AUTHORING_POLICY,
            Semantic.NonEmptyArray("desiredGroup", minElements = 1),
        ),
        claim(
            "policy.fixedAuthoring",
            field("preserve"),
            ClaimKind.POLICY,
            Enforcement.AUTHORING_POLICY,
            Semantic.FixedAuthoredAsPreserve,
        ),
        claim(
            "policy.categoryRefFromContext",
            field("categoryRef"),
            ClaimKind.POLICY,
            Enforcement.AUTHORING_POLICY,
            Semantic.CategoryRefFromContextArray,
        ),
    )

    val productionClaims: List<ConstraintClaim> =
        claims.filter { it.enforcement == Enforcement.PRODUCTION_ENFORCED }

    val authoringPolicyClaims: List<ConstraintClaim> =
        claims.filter { it.enforcement == Enforcement.AUTHORING_POLICY }

    fun claim(id: String): ConstraintClaim = claims.first { it.id == id }

    /** The exact sentence a policy claim contributes to the instruction. */
    fun policySentence(id: String): String = when (val s = claim(id).semantic) {
        is Semantic.StringsAsJsonStrings -> "Write string values as JSON strings"
        is Semantic.UppercaseSpelledEnums -> "UPPERCASE exactly as listed"
        is Semantic.StringArrayOfStrings -> "array of strings"
        is Semantic.NonEmptyArray -> "if present, at least ${s.minElements} element(s)"
        is Semantic.FixedAuthoredAsPreserve -> "author only \"preserve\": true"
        is Semantic.CategoryRefFromContextArray ->
            "set \"categoryRef\" only to a \"ref\" from the CONTEXT data \"categories\" array"
        else -> error("$id is not a policy claim")
    }

    // Static identities rendered into the output contract.

    val intentSchemaVersion: String = ContextExportContract.INTENT_SCHEMA_VERSION
}
