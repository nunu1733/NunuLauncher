/*
 * Issue #448: undo evidence recorded at execution time. Only the information
 * set is owned here; the undo body, lifetime, and UI are owned by Issue #450
 * (ADR-0013 contract 5, spec 448 AC-9). Process-local, best-effort single
 * holder until #450 replaces it.
 */
package app.lawnchair.homeedit

enum class HomeEditActionKind {
    MOVE_TO_PAGE,
    ADD_TO_FOLDER,
    CREATE_FOLDER_AND_ADD,
    REMOVE,
}

data class HomeEditUndoEvidence(
    val action: HomeEditActionKind,
    val itemId: Int,
    val oldContainer: Int,
    val oldScreenId: Int,
    val oldCellX: Int,
    val oldCellY: Int,
    val oldSpanX: Int,
    val oldSpanY: Int,
    val oldRank: Int,
    val newContainer: Int,
    val newScreenId: Int,
    val newCellX: Int,
    val newCellY: Int,
    val newRank: Int,
    /** Reference to the folder created by this action, if any. */
    val createdFolderId: Int?,
    /** Placement of the folder this action created, if any (AC-9). */
    val createdFolderScreenId: Int?,
    val createdFolderCellX: Int?,
    val createdFolderCellY: Int?,
)

object HomeEditUndoLog {

    @Volatile
    private var last: HomeEditUndoEvidence? = null

    fun record(evidence: HomeEditUndoEvidence) {
        last = evidence
    }

    /** Latest recorded evidence, or null when no direct edit has succeeded. */
    fun last(): HomeEditUndoEvidence? = last
}

/**
 * Builds the undo evidence from the validated plan and the pre-write
 * placement reported by the admitted task. Pure; unit-tested through this
 * mapping (AC-9).
 */
fun buildUndoEvidence(
    intent: HomeEditIntent,
    plan: HomeEditPlan.Success,
    oldContainer: Int,
    oldScreenId: Int,
    oldCellX: Int,
    oldCellY: Int,
    oldSpanX: Int,
    oldSpanY: Int,
    oldRank: Int,
    createdFolderId: Int,
): HomeEditUndoEvidence {
    val target = plan.targetItemPlacement
    val action = when (intent) {
        is HomeEditIntent.MoveToPage -> HomeEditActionKind.MOVE_TO_PAGE
        is HomeEditIntent.AddToFolder -> HomeEditActionKind.ADD_TO_FOLDER
        is HomeEditIntent.CreateFolderAndAdd -> HomeEditActionKind.CREATE_FOLDER_AND_ADD
        is HomeEditIntent.Remove -> HomeEditActionKind.REMOVE
    }
    val folderId = createdFolderId.takeIf { it != 0 }
    val (newContainer, newScreenId, newCellX, newCellY, newRank) = when (plan) {
        is HomeEditPlan.Move -> listOf(plan.container, plan.screenId, plan.cellX, plan.cellY, plan.rank)
        is HomeEditPlan.CreateFolder -> listOf(createdFolderId, 0, -1, -1, 0)
        is HomeEditPlan.RemoveItem -> listOf(oldContainer, oldScreenId, oldCellX, oldCellY, oldRank)
    }
    val folderPlacement = plan as? HomeEditPlan.CreateFolder
    return HomeEditUndoEvidence(
        action = action,
        itemId = target.id,
        oldContainer = oldContainer,
        oldScreenId = oldScreenId,
        oldCellX = oldCellX,
        oldCellY = oldCellY,
        oldSpanX = oldSpanX,
        oldSpanY = oldSpanY,
        oldRank = oldRank,
        newContainer = newContainer,
        newScreenId = newScreenId,
        newCellX = newCellX,
        newCellY = newCellY,
        newRank = newRank,
        createdFolderId = folderId,
        createdFolderScreenId = folderPlacement?.screenId,
        createdFolderCellX = folderPlacement?.cellX,
        createdFolderCellY = folderPlacement?.cellY,
    )
}
