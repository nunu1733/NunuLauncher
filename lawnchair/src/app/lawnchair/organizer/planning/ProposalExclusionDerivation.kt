package app.lawnchair.organizer.planning

/**
 * Issue #508: pure derivation of a replanning input from the run's immutable
 * base input and the user's exclusion set. The derivation ONLY narrows the
 * target set — the snapshot revision, rules, taxonomy, catalog, signals (minus
 * excluded candidates), personalization snapshot, and intent preferences are
 * shared with the base input, never recomputed — so the derived input stays
 * revision-bound to the same capture and the replanned preview passes the
 * existing `inspectPlan` revision check.
 *
 * Every call derives directly from the base input (never from a previously
 * derived one), so an exclusion followed by its reversal reproduces the base
 * proposal exactly (determinism, AC-4).
 */
object ProposalExclusionDerivation {

    sealed interface Result {
        data class Ready(val input: OrganizationInput) : Result

        /** A key outside the base input's excludable surface — a coordinator
         *  contract violation; the replan fails closed instead of guessing. */
        data object Invalid : Result
    }

    /**
     * The keys of [input] this mechanism may exclude: existing members whose
     * role is already `Movable` and whose captured placement is a top-level
     * workspace app/deep-shortcut (spec D-3 — folders themselves, widgets,
     * dock, and everything with a stronger preservation predicate stay out),
     * plus every selected candidate addition.
     */
    fun excludableKeys(input: OrganizationInput): Set<ProposalExclusionKey> {
        val itemById = input.snapshot.items.associateBy { it.id }
        val existing = input.targets.existing.asSequence()
            .filter { it.role == ExistingRole.Movable }
            .mapNotNull { membership ->
                val item = itemById[membership.item] ?: return@mapNotNull null
                val excludable = item.placement is CapturedPlacement.Workspace &&
                    (item.kind == ItemKind.APPLICATION || item.kind == ItemKind.DEEP_SHORTCUT)
                if (excludable) ProposalExclusionKey.Existing(item.id) else null
            }
            .toSet()
        val candidates = input.targets.additions.mapTo(mutableSetOf()) { ProposalExclusionKey.Candidate(it.id) }
        return existing + candidates
    }

    /**
     * Derives the replanning input: excluded existing members flip to
     * `Preserved` (the planner keeps them at their captured placement as
     * `NON_TARGET` and their cells stay occupied), excluded candidates leave
     * `additions` together with their classification signal entries (planner
     * validation rejects signals pointing outside the target surface). The
     * run mode is unchanged — an emptied additions list under
     * `ScopeComposedOrganization` is the same shape as the explicit
     * zero-selection scope.
     */
    fun derive(base: OrganizationInput, exclusions: Set<ProposalExclusionKey>): Result {
        if (exclusions.isEmpty()) return Result.Ready(base)
        val excludable = excludableKeys(base)
        if (!excludable.containsAll(exclusions)) return Result.Invalid
        val excludedExisting = exclusions.filterIsInstance<ProposalExclusionKey.Existing>().mapTo(mutableSetOf()) { it.item }
        val excludedCandidateIds = exclusions.filterIsInstance<ProposalExclusionKey.Candidate>().mapTo(mutableSetOf()) { it.item }
        val existing = if (excludedExisting.isEmpty()) {
            base.targets.existing
        } else {
            base.targets.existing.map { membership ->
                if (membership.item in excludedExisting && membership.role == ExistingRole.Movable) {
                    ExistingTargetMembership(membership.item, ExistingRole.Preserved)
                } else {
                    membership
                }
            }
        }
        val additions = if (excludedCandidateIds.isEmpty()) {
            base.targets.additions
        } else {
            base.targets.additions.filterNot { it.id in excludedCandidateIds }
        }
        val signals = if (excludedCandidateIds.isEmpty()) {
            base.signals
        } else {
            ClassificationSignals(base.signals.entries.filterNot { it.item in excludedCandidateIds })
        }
        return Result.Ready(
            base.copy(
                targets = TargetSet(existing, additions),
                signals = signals,
            ),
        )
    }
}
