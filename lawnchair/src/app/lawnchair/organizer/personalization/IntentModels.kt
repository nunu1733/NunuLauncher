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
 * Issue #337 (v4, spec 337 D-4): the group semantic of one item — either an
 * existing-category reference or a run-scoped proposal label, **exactly one**
 * of the two.
 *
 * - [categoryRef] — an export-scoped ref advertised in the envelope
 *   `categories` projection. It names an existing (built-in or user-defined)
 *   category and is resolved by identity through the export session, never by
 *   a display name.
 * - [proposalLabel] — a run-scoped proposal: a normalized label (the #336
 *   category-name rule) that becomes the item's formation key for this run
 *   only. It is never interpreted as a category identity and nothing is
 *   persisted.
 *
 * The v3 any-of allowed both fields at once, which left the grouping authority
 * undecided; v4 accepts exactly one.
 */
data class GroupSemantic(
    val categoryRef: String?,
    val proposalLabel: String?,
) {
    init {
        require((categoryRef == null) != (proposalLabel == null)) {
            "groupSemantic must set exactly one of categoryRef / proposalLabel"
        }
        require(categoryRef == null || categoryRef.isNotEmpty())
        require(proposalLabel == null || proposalLabel.isNotEmpty())
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
    /** Proposed group/folder semantic for the desired group (see [GroupSemantic]). */
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
