package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CategoryRefKind
import app.lawnchair.organizer.personalization.CompletedPersonalIntent
import app.lawnchair.organizer.personalization.RefDecision

/**
 * Issue #328 (spec 328 "summaryの内容"): the privacy-safe import summary shown
 * by the import success state. Counts only — this type deliberately carries no
 * label, ref, folder title, rationale or confidence field, so none of them can
 * be rendered (the shape is itself the privacy guarantee; a contract test
 * pins it).
 *
 * The item-level counts are derived from the canonical representation
 * (`completed`, spec 330 D-4/D-5): the four breakdown counts come from
 * `RefDecision.Authored` decisions only (bare entries are canonical
 * unresolved and never counted as wishes), and the no-judgment count merges
 * explicit-unresolved, bare and omitted refs because the three share one
 * semantic identity (spec 330 D-5/D-6) — provenance is not user-visible.
 *
 * [minimizeMovement] is the planner-effective global preference
 * (`IntentPlannerAdapter` reads the same value), displayed as the global
 * orientation line; it is not an item count.
 */
data class ExchangeImportSummary(
    /** Number of export refs with authored wishes (`RefDecision.Authored`). */
    val recognizedCount: Int,
    /** `authoredUnresolvedCount + omittedCount` (provenance hidden). */
    val noJudgmentCount: Int,
    /** Items whose authored entry sets `importance`. */
    val priorityCount: Int,
    /** Items whose authored entry sets `desiredGroupRefs` or `groupSemantic`. */
    val groupCount: Int,
    /**
     * Issue #337 (spec 337 AC-10): the authored group semantics split by what
     * they actually refer to, so the success state can distinguish an existing
     * built-in category, an existing persisted user-defined category, and a
     * run-scoped proposal the app does not save. Counts only — the model keeps
     * its no-label / no-ref / no-free-text shape guarantee.
     */
    val builtInCategoryCount: Int,
    val userCategoryCount: Int,
    val proposedGroupCount: Int,
    /** Items whose authored entry sets `pageAffinity` or `regionAffinity`. */
    val placementCount: Int,
    /** Items whose authored entry sets `preserve` (`false` counts as set). */
    val keepCount: Int,
    /** Planner-effective `globalPreference.minimizeMovement == true`. */
    val minimizeMovement: Boolean,
    /**
     * Run-in entry only: the export scope's candidate count (the same value
     * the selection surface's guidance uses). 0 for the idle entry.
     */
    val scopeCandidateCount: Int,
)

/**
 * Issue #328: pure derivation of the import summary. Item counts read the
 * canonical representation only; each breakdown dimension counts items
 * independently (one item may carry several dimensions), and a `false`
 * `preserve` is a set field ("keep it unkept" is still a stated wish) — its
 * counterpart in `IntentCompletion.isBare` treats any non-null field the same
 * way.
 */
fun exchangeImportSummary(
    completed: CompletedPersonalIntent,
    scopeCandidateCount: Int,
    /**
     * Issue #337: the kind of every advertised category ref of the accepted
     * export (`ExportCategory.ref` -> `CategoryRefKind`). A ref outside this
     * map cannot happen for a validated intent; it is counted as neither kind
     * (the validator already rejected it).
     */
    categoryKindByRef: Map<String, CategoryRefKind> = emptyMap(),
): ExchangeImportSummary {
    val authored = completed.decisions.values
        .filterIsInstance<RefDecision.Authored>()
        .map { it.intent }
    return ExchangeImportSummary(
        recognizedCount = completed.authoredItemCount,
        noJudgmentCount = completed.authoredUnresolvedCount + completed.omittedCount,
        priorityCount = authored.count { it.importance != null },
        groupCount = authored.count { !it.desiredGroupRefs.isNullOrEmpty() || it.groupSemantic != null },
        builtInCategoryCount = authored.count {
            it.groupSemantic?.categoryRef?.let { ref -> categoryKindByRef[ref] } == CategoryRefKind.BUILT_IN
        },
        userCategoryCount = authored.count {
            it.groupSemantic?.categoryRef?.let { ref -> categoryKindByRef[ref] } == CategoryRefKind.USER_DEFINED
        },
        proposedGroupCount = authored.count { it.groupSemantic?.proposalLabel != null },
        placementCount = authored.count { it.pageAffinity != null || it.regionAffinity != null },
        keepCount = authored.count { it.preserve != null },
        minimizeMovement = completed.globalPreference?.minimizeMovement == true,
        scopeCandidateCount = scopeCandidateCount,
    )
}
