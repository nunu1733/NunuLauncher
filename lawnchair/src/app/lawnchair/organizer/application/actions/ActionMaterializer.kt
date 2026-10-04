package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement

/**
 * Validates planner-issued page/folder ordinals and materializes exactly one
 * [ApplyAction] per represented item. Does **not** allocate persistent IDs;
 * that happens only inside the Launcher transaction.
 *
 * Issue #14 Stage B step 1 (file shape); step 4 protocol invokes this.
 */
object ActionMaterializer {

    /**
     * Result of materializing a planned outcome against the source state.
     *
     * @property actions canonical actions, exactly one per represented item.
     * @property intendedState the canonical intended post-apply state derived
     *   from the source plus the planned placements.
     */
    data class Materialized(
        val actions: List<ApplyAction>,
        val intendedState: LayoutState,
    )

    /**
     * Validate the planner's [Planned] ordinals: every [NewPage] ordinal is
     * unique and canonical; every [NewFolder] ordinal is unique and references
     * only items present in the placements list.
     *
     * Returns an [InvalidOrdinals] on the first violation; never throws.
     */
    fun validateOrdinals(planned: Planned): OrdinalValidation {
        val pageOrdinals = planned.newPages.map { it.ordinal }
        if (pageOrdinals.distinct().size != pageOrdinals.size) {
            return OrdinalValidation.DuplicatePageOrdinal
        }
        if (pageOrdinals.sorted() != pageOrdinals) {
            return OrdinalValidation.OutOfOrderPageOrdinal
        }
        val folderOrdinals = planned.newFolders.map { it.ordinal }
        if (folderOrdinals.distinct().size != folderOrdinals.size) {
            return OrdinalValidation.DuplicateFolderOrdinal
        }
        if (folderOrdinals.sorted() != folderOrdinals) {
            return OrdinalValidation.OutOfOrderFolderOrdinal
        }
        val placementItems = planned.placements.map { it.item }.toSet()
        val orphanFolderMembers = planned.newFolders.firstOrNull { folder ->
            folder.members.any { it !in placementItems }
        }
        if (orphanFolderMembers != null) {
            return OrdinalValidation.FolderMemberWithoutPlacement(orphanFolderMembers.ordinal)
        }
        return OrdinalValidation.Ok
    }

    sealed interface OrdinalValidation {
        data object Ok : OrdinalValidation
        data object DuplicatePageOrdinal : OrdinalValidation
        data object OutOfOrderPageOrdinal : OrdinalValidation
        data object DuplicateFolderOrdinal : OrdinalValidation
        data object OutOfOrderFolderOrdinal : OrdinalValidation
        data class FolderMemberWithoutPlacement(val folderOrdinal: app.lawnchair.organizer.planning.NewFolderOrdinal) : OrdinalValidation
    }
}

/**
 * Marker type carrying an item that participates in [PlannedPlacement] but is
 * not part of the captured snapshot — i.e. a [CandidateItem] that has just
 * been planned. Used by the action materializer to construct an
 * [ApplyAction.Insert].
 */
data class PlannedCandidateItem(val ref: ApplicationItemRef.PlannedCandidate, val intended: CanonicalItemState)
