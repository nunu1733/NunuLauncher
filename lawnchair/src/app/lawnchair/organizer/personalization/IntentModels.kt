package app.lawnchair.organizer.personalization

/**
 * Issue #204: the versioned AI/agent-returned semantic layout intent
 * (spec 204 "Contract 2"). It is a semantic preference only — never a DB
 * mutation manifest or a coordinate decision. Schema-external authority
 * expressions (raw coordinates, widget spans, reservation occupation, scripts)
 * cannot be expressed in this schema; the validator rejects them as
 * `FORBIDDEN_CONTENT` when they appear in the payload.
 */
data class PersonalizedIntentV1(
    /** The export this intent answers. The only echoed identifier. */
    val exportId: String,
    val itemIntents: List<ItemIntent>,
    /** Explicit unresolved marker; partition-invariant checked with [itemIntents]. */
    val unresolvedRefs: List<String> = emptyList(),
    val globalPreference: GlobalPreference? = null,
    /** Display/diagnostic only; holds no authority. */
    val rationale: String? = null,
    /** 0–100. Display/diagnostic only. */
    val confidence: Int? = null,
) {
    init {
        require(exportId.isNotEmpty())
        require(itemIntents.size <= ContextExportContract.MAX_INTENT_ENTRIES)
        require(unresolvedRefs.size <= ContextExportContract.MAX_INTENT_UNRESOLVED)
        rationale?.let { require(it.length <= ContextExportContract.MAX_RATIONALE_CHARS) }
        confidence?.let { require(it in ContextExportContract.CONFIDENCE_MIN..ContextExportContract.CONFIDENCE_MAX) }
    }
}

enum class Importance {
    HIGH,
    NORMAL,
    LOW,
}

/**
 * Proposed group/folder semantic: an existing taxonomy category id or bounded
 * free text. Free text is data-as-data; it never becomes a planner rule.
 */
data class GroupSemantic(
    val category: String?,
    val freeText: String?,
) {
    init {
        require(category != null || freeText != null)
        require(category == null || category.isNotEmpty())
        require(freeText == null || freeText.length <= ContextExportContract.MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS)
    }
}

data class GlobalPreference(
    /** Prefer plans that minimize movement of the existing layout. */
    val minimizeMovement: Boolean? = null,
)

data class ItemIntent(
    val ref: String,
    val importance: Importance? = null,
    /** Desired group members within the same export (other `ref`s). */
    val desiredGroupRefs: List<String>? = null,
    /** Proposed group/folder semantic for the desired group. */
    val groupSemantic: GroupSemantic? = null,
    /** Page affinity at the export's abstraction level (page ordinal). */
    val pageAffinity: Int? = null,
    val regionAffinity: ExportRegionKind? = null,
    /** Keep this item's current placement. */
    val preserve: Boolean? = null,
) {
    init {
        require(ref.isNotEmpty())
        desiredGroupRefs?.let { require(it.isNotEmpty()) }
    }
}
