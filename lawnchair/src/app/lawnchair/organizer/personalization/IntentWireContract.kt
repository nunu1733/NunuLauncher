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
 * [ConstraintClaim] carrying its own [Enforcement] classification:
 *
 * - [Enforcement.PRODUCTION_ENFORCED] — production rejects violations; the
 *   parity cases in `Issue348AiFacingContractSyncTest` are keyed one-to-one
 *   onto these claim ids and pin the typed failure, so editing a claim
 *   against production (or adding/removing/reclassifying one) surfaces as a
 *   test failure.
 * - [Enforcement.AUTHORING_POLICY] — production accepts broader input than
 *   the canonical authoring representation; each claim must be positively
 *   rendered in the instruction (set-matched in the test) and the canonical
 *   acceptance fixtures pin `canonical ⊆ accepted`.
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
         * it. The instruction states the positive rule; the canonical
         * acceptance fixtures pin that canonical fixtures are accepted.
         */
        AUTHORING_POLICY,
    }

    /** Canonical authoring JSON type of a field (the instruction renders these). */
    enum class WireType { STRING, BOOLEAN, INTEGER, STRING_ARRAY, OBJECT, OBJECT_ARRAY }

    /** Rendering-only field descriptor (names, types, enum spellings). */
    data class WireField(
        val name: String,
        val type: WireType,
        val optional: Boolean,
        val enumValues: List<String> = emptyList(),
    )

    /**
     * One constraint claim. Constraint granularity is deliberate: a single
     * field can carry claims of both enforcements (e.g. `desiredGroup` has a
     * production-enforced container-type claim and an authoring-policy
     * non-empty claim), so enforcement lives on the claim, not the field.
     */
    data class ConstraintClaim(
        /** Stable id; the parity/policy test oracles are keyed on this. */
        val id: String,
        /** The field the constraint is about (wire key). */
        val field: String,
        val enforcement: Enforcement,
        /** Human-readable statement (test diagnostics only). */
        val statement: String,
    )

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
        WireField(
            "importance",
            WireType.STRING,
            optional = true,
            enumValues = Importance.entries.map { it.name },
        ),
        WireField("desiredGroup", WireType.STRING_ARRAY, optional = true),
        WireField("groupSemantic", WireType.OBJECT, optional = true),
        WireField("pageAffinity", WireType.INTEGER, optional = true),
        WireField(
            "regionAffinity",
            WireType.STRING,
            optional = true,
            enumValues = ExportRegionKind.entries.map { it.name },
        ),
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

    /** Enum claims by field name, for rendering and parity lookups. */
    val enumClaims: Map<String, List<String>> =
        (topLevel + item + globalPreference + groupSemantic)
            .filter { it.enumValues.isNotEmpty() }
            .associate { it.name to it.enumValues }

    /**
     * The #204 model-level any-of rule for `groupSemantic`: at least one of
     * the two members must be present (production rejects a both-absent
     * object with `SCHEMA_MISMATCH`).
     */
    val groupSemanticAnyOf: Pair<String, String> = "category" to "freeText"

    /**
     * Every constraint the AI-facing contract states, classified. The sync
     * test asserts set equality between this list and its fixed oracle sets,
     * then executes one keyed case per claim — so a reclassification,
     * addition, or removal cannot pass unnoticed.
     */
    val claims: List<ConstraintClaim> = listOf(
        // Presence / identity (decode).
        ConstraintClaim("schemaVersion.presence", "schemaVersion", Enforcement.PRODUCTION_ENFORCED, "schemaVersion must be present"),
        ConstraintClaim("schemaVersion.exactValue", "schemaVersion", Enforcement.PRODUCTION_ENFORCED, "schemaVersion must equal the advertised version"),
        ConstraintClaim("exportId.presence", "exportId", Enforcement.PRODUCTION_ENFORCED, "exportId must be present"),
        ConstraintClaim("item.ref.presence", "ref", Enforcement.PRODUCTION_ENFORCED, "every itemIntents entry needs a ref"),
        // Container shapes (decode).
        ConstraintClaim("itemIntents.containerType", "itemIntents", Enforcement.PRODUCTION_ENFORCED, "itemIntents must be an array"),
        ConstraintClaim("unresolvedRefs.containerType", "unresolvedRefs", Enforcement.PRODUCTION_ENFORCED, "unresolvedRefs must be an array"),
        ConstraintClaim("globalPreference.containerType", "globalPreference", Enforcement.PRODUCTION_ENFORCED, "globalPreference must be an object"),
        ConstraintClaim("desiredGroup.containerType", "desiredGroup", Enforcement.PRODUCTION_ENFORCED, "desiredGroup must be an array"),
        ConstraintClaim("groupSemantic.containerType", "groupSemantic", Enforcement.PRODUCTION_ENFORCED, "groupSemantic must be an object"),
        // Enum spellings (decode).
        ConstraintClaim("importance.enum", "importance", Enforcement.PRODUCTION_ENFORCED, "importance must be one of the enum values"),
        ConstraintClaim("regionAffinity.enum", "regionAffinity", Enforcement.PRODUCTION_ENFORCED, "regionAffinity must be one of the enum values"),
        // Primitive types and value bounds (decode / validate).
        ConstraintClaim("confidence.valueType", "confidence", Enforcement.PRODUCTION_ENFORCED, "confidence must be an integer"),
        ConstraintClaim("confidence.valueBound", "confidence", Enforcement.PRODUCTION_ENFORCED, "confidence must be inside the advertised bounds"),
        ConstraintClaim("pageAffinity.valueType", "pageAffinity", Enforcement.PRODUCTION_ENFORCED, "pageAffinity must be an integer"),
        ConstraintClaim("pageAffinity.exportBound", "pageAffinity", Enforcement.PRODUCTION_ENFORCED, "pageAffinity must stay inside the exported page range"),
        ConstraintClaim("preserve.valueType", "preserve", Enforcement.PRODUCTION_ENFORCED, "preserve must be a boolean"),
        ConstraintClaim("minimizeMovement.valueType", "minimizeMovement", Enforcement.PRODUCTION_ENFORCED, "minimizeMovement must be a boolean"),
        ConstraintClaim("groupSemantic.anyOf", "groupSemantic", Enforcement.PRODUCTION_ENFORCED, "groupSemantic must set at least one of its members"),
        // Mobility rules (validate).
        ConstraintClaim("mobility.fixedSemanticForbidden", "mobility", Enforcement.PRODUCTION_ENFORCED, "FIXED items accept no semantic fields"),
        ConstraintClaim("mobility.conditionalGroupingForbidden", "mobility", Enforcement.PRODUCTION_ENFORCED, "CONDITIONAL items accept no grouping fields"),
        ConstraintClaim("mobility.candidatePreserveForbidden", "mobility", Enforcement.PRODUCTION_ENFORCED, "CANDIDATE items accept no preserve"),
        // Ref scope and partition (validate).
        ConstraintClaim("refScope.itemIntents", "ref", Enforcement.PRODUCTION_ENFORCED, "itemIntents refs must exist in the export"),
        ConstraintClaim("refScope.desiredGroup", "desiredGroup", Enforcement.PRODUCTION_ENFORCED, "desiredGroup refs must exist in the export"),
        ConstraintClaim("refScope.unresolvedRefs", "unresolvedRefs", Enforcement.PRODUCTION_ENFORCED, "unresolvedRefs entries must exist in the export"),
        ConstraintClaim("refPartition.duplicate", "ref", Enforcement.PRODUCTION_ENFORCED, "a ref may appear at most once"),
        ConstraintClaim("refPartition.disjoint", "ref", Enforcement.PRODUCTION_ENFORCED, "itemIntents and unresolvedRefs must stay disjoint"),
        // Resource limits (decode, OVERSIZE).
        ConstraintClaim("rationale.lengthLimit", "rationale", Enforcement.PRODUCTION_ENFORCED, "rationale has an upper character bound"),
        ConstraintClaim("groupSemantic.freeText.lengthLimit", "freeText", Enforcement.PRODUCTION_ENFORCED, "groupSemantic freeText has an upper character bound"),
        ConstraintClaim("itemIntents.entryLimit", "itemIntents", Enforcement.PRODUCTION_ENFORCED, "itemIntents has an upper entry bound"),
        ConstraintClaim("unresolvedRefs.entryLimit", "unresolvedRefs", Enforcement.PRODUCTION_ENFORCED, "unresolvedRefs has an upper entry bound"),
        // Authoring policies (production accepts broader input).
        ConstraintClaim("policy.stringFieldsAsJsonStrings", "string fields", Enforcement.AUTHORING_POLICY, "string values are authored as JSON strings"),
        ConstraintClaim("policy.uppercaseEnums", "enums", Enforcement.AUTHORING_POLICY, "enum values are authored UPPERCASE as listed"),
        ConstraintClaim("policy.stringListElements", "string arrays", Enforcement.AUTHORING_POLICY, "string array elements are authored as strings"),
        ConstraintClaim("policy.desiredGroupNonEmpty", "desiredGroup", Enforcement.AUTHORING_POLICY, "desiredGroup, when present, is non-empty"),
        ConstraintClaim("policy.fixedAuthoring", "mobility", Enforcement.AUTHORING_POLICY, "FIXED items are authored with preserve:true or left unjudged"),
    )

    val productionClaims: List<ConstraintClaim> =
        claims.filter { it.enforcement == Enforcement.PRODUCTION_ENFORCED }

    val authoringPolicyClaims: List<ConstraintClaim> =
        claims.filter { it.enforcement == Enforcement.AUTHORING_POLICY }

    // Static identities and limits rendered into the output contract.

    val intentSchemaVersion: String = ContextExportContract.INTENT_SCHEMA_VERSION
    val confidenceMin: Int = ContextExportContract.CONFIDENCE_MIN
    val confidenceMax: Int = ContextExportContract.CONFIDENCE_MAX
    val maxGroupSemanticFreeTextChars: Int = ContextExportContract.MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS
    val maxRationaleChars: Int = ContextExportContract.MAX_RATIONALE_CHARS
    val maxItemIntents: Int = ContextExportContract.MAX_INTENT_ENTRIES
    val maxUnresolvedRefs: Int = ContextExportContract.MAX_INTENT_UNRESOLVED
}
