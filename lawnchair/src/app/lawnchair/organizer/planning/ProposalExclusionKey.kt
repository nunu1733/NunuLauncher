package app.lawnchair.organizer.planning

/**
 * Issue #508: opaque key of one item a single proposal may exclude. The key
 * identifies a target-set member — an existing top-level app/deep-shortcut or
 * a selected missing-app candidate — by its planning [ItemId]; it is never a
 * persistent launcher identity beyond that and never rendered.
 *
 * Owned by the planning module (next to [TargetSet]) so the application
 * projection, the derivation below, and the run coordinator/UI all consume the
 * same neutral closed type without a layering inversion.
 */
sealed interface ProposalExclusionKey {
    /** An existing captured item this proposal would otherwise re-place. */
    data class Existing(val item: ItemId) : ProposalExclusionKey

    /** A selected missing-app candidate this proposal would otherwise add. */
    data class Candidate(val item: ItemId) : ProposalExclusionKey
}
