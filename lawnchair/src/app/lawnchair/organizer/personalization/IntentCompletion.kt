package app.lawnchair.organizer.personalization

/**
 * Issue #330 (spec 330 D-4): how one export ref was decided in an accepted
 * intent. The two unresolved states are semantically identical ("no judgment",
 * spec 330 D-5/D-6) and differ only as provenance for diagnostics; identity
 * and the planner projection treat them alike.
 */
sealed interface RefDecision {
    /** The agent authored at least one semantic field for the ref (spec 330 D-6). */
    data class Authored(val intent: ItemIntent) : RefDecision

    /** Explicitly unresolved, or a bare entry (all semantic fields null) normalized at completion. */
    data object UnresolvedAuthored : RefDecision

    /** A ref the agent never mentioned, canonicalized to "no judgment" (spec 330 D-1/D-2). */
    data object UnresolvedByOmission : RefDecision
}

/**
 * Issue #330 (spec 330 D-4): the complete canonical representation of an
 * accepted intent — every export ref appears exactly once. This is the only
 * planner-facing form; the authored (possibly partial) document is kept for
 * diagnostics only and never flows to the planner/preview/apply path.
 */
data class CompletedPersonalIntent(
    val exportId: String,
    val decisions: Map<String, RefDecision>,
    val globalPreference: GlobalPreference?,
    val rationale: String?,
    val confidence: Int?,
) {
    /** Refs with at least one authored semantic field. */
    val authoredItemCount: Int get() = decisions.values.count { it is RefDecision.Authored }

    /** Explicit unresolved markers plus bare entries normalized at completion. */
    val authoredUnresolvedCount: Int get() = decisions.values.count { it == RefDecision.UnresolvedAuthored }

    /** Refs the agent never mentioned. */
    val omittedCount: Int get() = decisions.values.count { it == RefDecision.UnresolvedByOmission }
}

/**
 * Issue #330 (spec 330 D-1/D-4/D-6): the pure completer behind the validator
 * seam. Pure, total and deterministic over its domain (a validator-passed
 * intent whose refs are known and disjoint, plus the export's full ref set):
 * it only attaches a state to every ref and never generates or rewrites
 * `ItemIntent` field values, so no semantic inference can enter here.
 */
object IntentCompletion {

    fun complete(authored: PersonalizedIntentV1, exportRefs: Set<String>): CompletedPersonalIntent {
        val decisions = HashMap<String, RefDecision>(exportRefs.size)
        for (ref in exportRefs) {
            decisions[ref] = RefDecision.UnresolvedByOmission
        }
        for (ref in authored.unresolvedRefs) {
            decisions[ref] = RefDecision.UnresolvedAuthored
        }
        for (item in authored.itemIntents) {
            decisions[item.ref] = if (isBare(item)) RefDecision.UnresolvedAuthored else RefDecision.Authored(item)
        }
        check(decisions.keys == exportRefs) {
            "completion requires a validator-passed intent: authored refs must be known and disjoint"
        }
        return CompletedPersonalIntent(
            exportId = authored.exportId,
            decisions = decisions,
            globalPreference = authored.globalPreference,
            rationale = authored.rationale,
            confidence = authored.confidence,
        )
    }

    /**
     * Spec 330 D-6: a bare entry (every semantic field null) carries no
     * judgment and normalizes to unresolved; any non-null field — including
     * `preserve = false` — keeps the entry authored.
     */
    private fun isBare(item: ItemIntent): Boolean = item.importance == null &&
        item.desiredGroupRefs == null &&
        item.groupSemantic == null &&
        item.pageAffinity == null &&
        item.regionAffinity == null &&
        item.preserve == null
}
