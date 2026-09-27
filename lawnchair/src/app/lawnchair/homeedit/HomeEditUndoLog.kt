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
