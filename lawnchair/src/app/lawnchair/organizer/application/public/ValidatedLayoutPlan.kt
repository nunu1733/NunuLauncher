package app.lawnchair.organizer.application.public

import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion

/**
 * Public apply input. Built from the exact [OrganizationInput] and the
 * planner's [PlanningResult.Planned]; carries the validated canonical
 * pre-state and intended post-state. Spec §“Apply input”.
 *
 * Issue #14 Stage B step 1.
 */
data class ValidatedLayoutPlan(
    val sourceRevision: RevisionId,
    val sourceState: LayoutState,
    val intendedState: LayoutState,
    val actions: List<ApplyAction>,
    val newPages: List<NewPage>,
    val newFolders: List<NewFolder>,
    val ruleVersion: RuleVersion,
    val taxonomyVersion: TaxonomyVersion,
) {
    init {
        require(actions.distinctBy { it.ref }.size == actions.size) {
            "ValidatedLayoutPlan.actions must be duplicate-free by ref"
        }
        require(newPages.distinctBy { it.ordinal }.size == newPages.size) {
            "ValidatedLayoutPlan.newPages must be duplicate-free by ordinal"
        }
        require(newFolders.distinctBy { it.ordinal }.size == newFolders.size) {
            "ValidatedLayoutPlan.newFolders must be duplicate-free by ordinal"
        }
        actions.forEach { action ->
            val ref = action.ref
            require(ref !is ApplicationItemRef.PlannedFolder || ref.ordinal in newFolders.map(NewFolder::ordinal)) {
                "Insert/Update references a planned folder not declared in newFolders: $action"
            }
        }
    }
}
