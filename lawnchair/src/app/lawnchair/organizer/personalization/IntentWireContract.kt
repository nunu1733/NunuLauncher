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
 * and stays owned by the codec/validator. Each constraint claim carries an
 * [Enforcement] classification stating how it is kept in sync:
 *
 * - [Enforcement.PRODUCTION_ENFORCED] — production rejects violations; parity
 *   fixtures over `ExchangeImportPipeline.import` pin the typed failure, and
 *   editing the claim against production fails those fixtures.
 * - [Enforcement.AUTHORING_POLICY] — production accepts broader input than the
 *   canonical authoring representation; the instruction renders the positive
 *   rule and the policy matrix pins `canonical ⊆ accepted`.
 *
 * Per-export facts (`exportId`, refs, `gridContext.pageCount`) are never
 * stored here: the composer does not interpret the export and the instruction
 * refers the agent to the CONTEXT data.
 */
internal object IntentWireContract {

    /**
     * How a constraint claim is kept in sync with production behavior (spec
     * 348 Decision 1).
     */
    enum class Enforcement {
        /** Production rejects violations; a parity fixture pins the typed failure. */
        PRODUCTION_ENFORCED,

        /**
         * Production accepts the representation; canonical authoring restricts
         * it. The instruction states the positive rule; the policy matrix pins
         * that canonical fixtures are accepted.
         */
        AUTHORING_POLICY,
    }

    /** Canonical authoring JSON type of a field (the instruction renders these). */
    enum class WireType { STRING, BOOLEAN, INTEGER, STRING_ARRAY, OBJECT, OBJECT_ARRAY }

    data class WireField(
        val name: String,
        val type: WireType,
        /** The field may be omitted from its object. */
        val optional: Boolean,
        val enforcement: Enforcement,
        /** Exact JSON enum spellings; empty when not enum-typed. */
        val enumValues: List<String> = emptyList(),
    )

    /** Top-level properties of the intent object (codec allow-list source). */
    val topLevel: List<WireField> = listOf(
        WireField(
            "schemaVersion",
            WireType.STRING,
            optional = false,
            enforcement = Enforcement.PRODUCTION_ENFORCED,
        ),
        WireField("exportId", WireType.STRING, optional = false, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField("itemIntents", WireType.OBJECT_ARRAY, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField("unresolvedRefs", WireType.STRING_ARRAY, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField("globalPreference", WireType.OBJECT, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
        // A non-string primitive is tolerated by the decoder; the string
        // requirement is canonical authoring only (the length limit is enforced
        // as OVERSIZE separately).
        WireField("rationale", WireType.STRING, optional = true, enforcement = Enforcement.AUTHORING_POLICY),
        WireField("confidence", WireType.INTEGER, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
    )

    /** Properties of one `itemIntents` entry. */
    val item: List<WireField> = listOf(
        WireField("ref", WireType.STRING, optional = false, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField(
            "importance",
            WireType.STRING,
            optional = true,
            enforcement = Enforcement.PRODUCTION_ENFORCED,
            enumValues = Importance.entries.map { it.name },
        ),
        WireField("desiredGroup", WireType.STRING_ARRAY, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField("groupSemantic", WireType.OBJECT, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField("pageAffinity", WireType.INTEGER, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
        WireField(
            "regionAffinity",
            WireType.STRING,
            optional = true,
            enforcement = Enforcement.PRODUCTION_ENFORCED,
            enumValues = ExportRegionKind.entries.map { it.name },
        ),
        WireField("preserve", WireType.BOOLEAN, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
    )

    /** Properties of the optional `globalPreference` object. */
    val globalPreference: List<WireField> = listOf(
        WireField("minimizeMovement", WireType.BOOLEAN, optional = true, enforcement = Enforcement.PRODUCTION_ENFORCED),
    )

    /** Properties of the optional `groupSemantic` object. */
    val groupSemantic: List<WireField> = listOf(
        WireField("category", WireType.STRING, optional = true, enforcement = Enforcement.AUTHORING_POLICY),
        WireField("freeText", WireType.STRING, optional = true, enforcement = Enforcement.AUTHORING_POLICY),
    )

    /** Enum claims by field name, for rendering and parity lookups. */
    val enumClaims: Map<String, List<String>> =
        (topLevel + item + globalPreference + groupSemantic)
            .filter { it.enumValues.isNotEmpty() }
            .associate { it.name to it.enumValues }

    // Static limits and identities rendered into the output contract.

    val intentSchemaVersion: String = ContextExportContract.INTENT_SCHEMA_VERSION
    val confidenceMin: Int = ContextExportContract.CONFIDENCE_MIN
    val confidenceMax: Int = ContextExportContract.CONFIDENCE_MAX
    val maxGroupSemanticFreeTextChars: Int = ContextExportContract.MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS
    val maxRationaleChars: Int = ContextExportContract.MAX_RATIONALE_CHARS
    val maxItemIntents: Int = ContextExportContract.MAX_INTENT_ENTRIES
    val maxUnresolvedRefs: Int = ContextExportContract.MAX_INTENT_UNRESOLVED

    /**
     * The #204 model-level any-of rule for `groupSemantic`: at least one of
     * `category` / `freeText` must be present. Production rejects a
     * both-absent object (`SCHEMA_MISMATCH`), so this is enforced.
     */
    val groupSemanticAnyOf: Pair<String, String> = "category" to "freeText"
}
