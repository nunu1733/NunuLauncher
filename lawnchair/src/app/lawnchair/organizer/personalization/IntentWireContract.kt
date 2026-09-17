package app.lawnchair.organizer.personalization

/**
 * Issue #348: the wire descriptor of the #204 intent payload — the single
 * data table that the [IntentCodec] allow-lists and the AI-facing output
 * contract section of the exchange package ([app.lawnchair.organizer.personalization.exchange.ExchangePackageComposer])
 * are both rendered from, so the property-name sets, enum spellings, and
 * static limits cannot drift between what the validator accepts and what the
 * external agent is told to author.
 *
 * The descriptor is a *view*, not a second schema: production acceptance is
 * and stays owned by the codec/validator. Every constraint is stated as a
 * typed [ConstraintClaim] (kind + interpreted value), and the claim is the
 * unit both consumers read:
 *
 * - the composer renders the output-contract facts (exact values, enum
 *   spellings, bounds, limits, policy sentences) from the claim values;
 * - the sync test walks the claims themselves: one keyed parity case per
 *   `PRODUCTION_ENFORCED` claim whose fixture input is derived from the
 *   claim's field/kind/value, and one positive-render + canonical-acceptance
 *   case per `AUTHORING_POLICY` claim. Editing a claim against production
 *   fails its case; adding, removing, or duplicating one fails the set or
 *   uniqueness assertions.
 *
 * Per-export facts (`exportId`, refs, `gridContext.pageCount`) are never
 * stored here: the composer does not interpret the export and the instruction
 * refers the agent to the CONTEXT data.
 */
internal object IntentWireContract {

    /** How a constraint claim is kept in sync with production behavior. */
    enum class Enforcement {
        /** Production rejects violations; a keyed parity case pins the typed failure. */
        PRODUCTION_ENFORCED,

        /**
         * Production accepts the representation; canonical authoring restricts
         * it. The instruction states the positive rule (rendered from the
         * claim value) and a canonical acceptance case pins that a compliant
         * payload is accepted.
         */
        AUTHORING_POLICY,
    }

    /** What kind of constraint the claim states (drives fixture derivation). */
    enum class ClaimKind {
        /** The field must be present. */
        PRESENCE,

        /** The field must equal the exact advertised value ([value][ConstraintClaim.value][0]). */
        EXACT_VALUE,

        /** The field, when present, must be the advertised container. */
        CONTAINER_TYPE,

        /** The field, when present, must be the advertised primitive. */
        PRIMITIVE_TYPE,

        /** The field must stay inside the advertised [min, max] bounds. */
        VALUE_BOUND,

        /** The field must use exactly the advertised enum spellings. */
        ENUM,

        /** At least one of the two advertised member fields must be set. */
        ANY_OF,

        /** The field's length must stay inside the advertised maximum. */
        LENGTH_LIMIT,

        /** The array's size must stay inside the advertised maximum. */
        ENTRY_LIMIT,

        /** The field's refs must exist in the export scope. */
        REF_SCOPE,

        /** The field's refs must not duplicate or cross the authored partition. */
        REF_PARTITION,

        /** The named mobility forbids the fields the composer's prose states. */
        MOBILITY_FORBIDDEN,

        /**
         * Authoring policy only: production accepts broader input; the claim
         * value is the exact sentence the instruction must render.
         */
        POLICY_RENDER,
    }

    /**
     * One constraint claim. Constraint granularity is deliberate: a single
     * field can carry claims of both enforcements (e.g. `desiredGroup` has a
     * production-enforced container-type claim and an authoring-policy
     * non-empty claim), so enforcement lives on the claim, not the field.
     */
    data class ConstraintClaim(
        /** Stable unique id; the parity/policy oracles are keyed on this. */
        val id: String,
        /** The wire key the constraint is about. */
        val field: String,
        val kind: ClaimKind,
        val enforcement: Enforcement,
        /**
         * Typed constraint values, interpreted by [kind]: `EXACT_VALUE` → the
         * exact spelling; `ENUM` → the allowed spellings; `VALUE_BOUND` →
         * `[min, max]`; `LENGTH_LIMIT` / `ENTRY_LIMIT` → `[max]`; `ANY_OF` →
         * the two member field names; `MOBILITY_FORBIDDEN` → the mobility
         * name; `POLICY_RENDER` → the exact sentence to render.
         */
        val value: List<String> = emptyList(),
    )

    /** Rendering-only field descriptor (names and canonical authoring types). */
    data class WireField(
        val name: String,
        val type: WireType,
        val optional: Boolean,
    )

    /** Canonical authoring JSON type of a field (the instruction renders these). */
    enum class WireType { STRING, BOOLEAN, INTEGER, STRING_ARRAY, OBJECT, OBJECT_ARRAY }

    /** Top-level properties of the intent object (codec allow-list source). */
    val topLevel: List<WireField> = listOf(
        WireField("schemaVersion", WireType.STRING, optional = false),
        WireField("exportId", WireType.STRING, optional = false),
        WireField("itemIntents", WireType.OBJECT_ARRAY, optional = true),
        WireField("unresolvedRefs", WireType.STRING_ARRAY, optional = true),
        WireField("globalPreference", WireType.OBJECT, optional = true),
        WireField("rationale", WireType.STRING, optional = true),
        WireField("confidence", WireType.INTEGER, optional = true),
    )

    /** Properties of one `itemIntents` entry. */
    val item: List<WireField> = listOf(
        WireField("ref", WireType.STRING, optional = false),
        WireField("importance", WireType.STRING, optional = true),
        WireField("desiredGroup", WireType.STRING_ARRAY, optional = true),
        WireField("groupSemantic", WireType.OBJECT, optional = true),
        WireField("pageAffinity", WireType.INTEGER, optional = true),
        WireField("regionAffinity", WireType.STRING, optional = true),
        WireField("preserve", WireType.BOOLEAN, optional = true),
    )

    /** Properties of the optional `globalPreference` object. */
    val globalPreference: List<WireField> = listOf(
        WireField("minimizeMovement", WireType.BOOLEAN, optional = true),
    )

    /** Properties of the optional `groupSemantic` object. */
    val groupSemantic: List<WireField> = listOf(
        WireField("category", WireType.STRING, optional = true),
        WireField("freeText", WireType.STRING, optional = true),
    )

    /** Enum spellings per field name, rendered into the output contract. */
    val enumClaims: Map<String, List<String>> = mapOf(
        "importance" to Importance.entries.map { it.name },
        "regionAffinity" to ExportRegionKind.entries.map { it.name },
    )

    /** The #204 model-level any-of rule for `groupSemantic`. */
    val groupSemanticAnyOf: Pair<String, String> = "category" to "freeText"

    private fun claim(
        id: String,
        field: String,
        kind: ClaimKind,
        enforcement: Enforcement,
        vararg value: String,
    ): ConstraintClaim = ConstraintClaim(id, field, kind, enforcement, value.toList())

    /**
     * Every constraint the AI-facing contract states, classified. The sync
     * test walks this list: set/keys equality, id uniqueness, one derived
     * parity case per production claim, and one render + acceptance case per
     * policy claim — so a reclassification, addition, removal, duplication,
     * or value edit cannot pass unnoticed.
     */
    val claims: List<ConstraintClaim> = listOf(
        // Presence / identity (decode).
        claim("schemaVersion.presence", "schemaVersion", ClaimKind.PRESENCE, Enforcement.PRODUCTION_ENFORCED),
        claim(
            "schemaVersion.exactValue",
            "schemaVersion",
            ClaimKind.EXACT_VALUE,
            Enforcement.PRODUCTION_ENFORCED,
            ContextExportContract.INTENT_SCHEMA_VERSION,
        ),
        claim("exportId.presence", "exportId", ClaimKind.PRESENCE, Enforcement.PRODUCTION_ENFORCED),
        claim("item.ref.presence", "ref", ClaimKind.PRESENCE, Enforcement.PRODUCTION_ENFORCED),
        // Container shapes (decode).
        claim("itemIntents.containerType", "itemIntents", ClaimKind.CONTAINER_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim("unresolvedRefs.containerType", "unresolvedRefs", ClaimKind.CONTAINER_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim("globalPreference.containerType", "globalPreference", ClaimKind.CONTAINER_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim("desiredGroup.containerType", "desiredGroup", ClaimKind.CONTAINER_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim("groupSemantic.containerType", "groupSemantic", ClaimKind.CONTAINER_TYPE, Enforcement.PRODUCTION_ENFORCED),
        // Enum spellings (decode).
        claim("importance.enum", "importance", ClaimKind.ENUM, Enforcement.PRODUCTION_ENFORCED, *enumClaims.getValue("importance").toTypedArray()),
        claim("regionAffinity.enum", "regionAffinity", ClaimKind.ENUM, Enforcement.PRODUCTION_ENFORCED, *enumClaims.getValue("regionAffinity").toTypedArray()),
        // Primitive types and value bounds (decode / validate).
        claim("confidence.valueType", "confidence", ClaimKind.PRIMITIVE_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim(
            "confidence.valueBound",
            "confidence",
            ClaimKind.VALUE_BOUND,
            Enforcement.PRODUCTION_ENFORCED,
            ContextExportContract.CONFIDENCE_MIN.toString(),
            ContextExportContract.CONFIDENCE_MAX.toString(),
        ),
        claim("pageAffinity.valueType", "pageAffinity", ClaimKind.PRIMITIVE_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim("pageAffinity.exportBound", "pageAffinity", ClaimKind.VALUE_BOUND, Enforcement.PRODUCTION_ENFORCED, "0"),
        claim("preserve.valueType", "preserve", ClaimKind.PRIMITIVE_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim("minimizeMovement.valueType", "minimizeMovement", ClaimKind.PRIMITIVE_TYPE, Enforcement.PRODUCTION_ENFORCED),
        claim(
            "groupSemantic.anyOf",
            "groupSemantic",
            ClaimKind.ANY_OF,
            Enforcement.PRODUCTION_ENFORCED,
            groupSemanticAnyOf.first,
            groupSemanticAnyOf.second,
        ),
        // Mobility rules (validate).
        claim("mobility.fixedSemanticForbidden", "mobility", ClaimKind.MOBILITY_FORBIDDEN, Enforcement.PRODUCTION_ENFORCED, "FIXED"),
        claim("mobility.conditionalGroupingForbidden", "mobility", ClaimKind.MOBILITY_FORBIDDEN, Enforcement.PRODUCTION_ENFORCED, "CONDITIONAL"),
        claim("mobility.candidatePreserveForbidden", "mobility", ClaimKind.MOBILITY_FORBIDDEN, Enforcement.PRODUCTION_ENFORCED, "CANDIDATE"),
        // Ref scope and partition (validate).
        claim("refScope.itemIntents", "ref", ClaimKind.REF_SCOPE, Enforcement.PRODUCTION_ENFORCED),
        claim("refScope.desiredGroup", "desiredGroup", ClaimKind.REF_SCOPE, Enforcement.PRODUCTION_ENFORCED),
        claim("refScope.unresolvedRefs", "unresolvedRefs", ClaimKind.REF_SCOPE, Enforcement.PRODUCTION_ENFORCED),
        claim("refPartition.duplicate", "ref", ClaimKind.REF_PARTITION, Enforcement.PRODUCTION_ENFORCED),
        claim("refPartition.disjoint", "ref", ClaimKind.REF_PARTITION, Enforcement.PRODUCTION_ENFORCED),
        // Resource limits (decode, OVERSIZE).
        claim(
            "rationale.lengthLimit",
            "rationale",
            ClaimKind.LENGTH_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            ContextExportContract.MAX_RATIONALE_CHARS.toString(),
        ),
        claim(
            "groupSemantic.freeText.lengthLimit",
            "freeText",
            ClaimKind.LENGTH_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            ContextExportContract.MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS.toString(),
        ),
        claim(
            "itemIntents.entryLimit",
            "itemIntents",
            ClaimKind.ENTRY_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            ContextExportContract.MAX_INTENT_ENTRIES.toString(),
        ),
        claim(
            "unresolvedRefs.entryLimit",
            "unresolvedRefs",
            ClaimKind.ENTRY_LIMIT,
            Enforcement.PRODUCTION_ENFORCED,
            ContextExportContract.MAX_INTENT_UNRESOLVED.toString(),
        ),
        // Authoring policies (production accepts broader input). The value is
        // the exact sentence fragment the instruction must render.
        claim(
            "policy.stringFieldsAsJsonStrings",
            "string fields",
            ClaimKind.POLICY_RENDER,
            Enforcement.AUTHORING_POLICY,
            "Write string values as JSON strings",
        ),
        claim(
            "policy.uppercaseEnums",
            "enums",
            ClaimKind.POLICY_RENDER,
            Enforcement.AUTHORING_POLICY,
            "UPPERCASE exactly as listed",
        ),
        claim(
            "policy.stringListElements",
            "string arrays",
            ClaimKind.POLICY_RENDER,
            Enforcement.AUTHORING_POLICY,
            "array of strings",
        ),
        claim(
            "policy.desiredGroupNonEmpty",
            "desiredGroup",
            ClaimKind.POLICY_RENDER,
            Enforcement.AUTHORING_POLICY,
            "if present, non-empty",
        ),
        claim(
            "policy.fixedAuthoring",
            "mobility",
            ClaimKind.POLICY_RENDER,
            Enforcement.AUTHORING_POLICY,
            "author only \"preserve\": true",
        ),
    )

    val productionClaims: List<ConstraintClaim> =
        claims.filter { it.enforcement == Enforcement.PRODUCTION_ENFORCED }

    val authoringPolicyClaims: List<ConstraintClaim> =
        claims.filter { it.enforcement == Enforcement.AUTHORING_POLICY }

    fun claim(id: String): ConstraintClaim = claims.first { it.id == id }

    // Static identities rendered into the output contract.

    val intentSchemaVersion: String = ContextExportContract.INTENT_SCHEMA_VERSION
    val confidenceMin: Int = ContextExportContract.CONFIDENCE_MIN
    val confidenceMax: Int = ContextExportContract.CONFIDENCE_MAX
    val maxGroupSemanticFreeTextChars: Int = ContextExportContract.MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS
    val maxRationaleChars: Int = ContextExportContract.MAX_RATIONALE_CHARS
    val maxItemIntents: Int = ContextExportContract.MAX_INTENT_ENTRIES
    val maxUnresolvedRefs: Int = ContextExportContract.MAX_INTENT_UNRESOLVED
}
