/*
 * Issue #450: pure verification of an undo attempt (ADR-0013 contract 5,
 * spec 450 Scope「現在状態との照合とfail-closed」). The same pure function runs
 * at stage 1 (submit time, MODEL_EXECUTOR snapshot) and stage 2 (inside
 * MODEL_WRITER admission, re-validated by HomeEditUndoStage2Validator); a
 * rejection — or any divergence from the stage-1 result — writes nothing and
 * surfaces a typed reason. Android-free: inputs are the homeedit snapshot
 * projection, the record entry, and the availability value.
 */
package app.lawnchair.homeedit

import com.android.launcher3.model.DirectEditContract

/** Typed undo rejections (spec 450 "Failure and rejection vocabulary"). */
enum class HomeEditUndoRejection {
    STALE,
    NO_SPACE,
    FOLDER_CHANGED,
    ITEM_UNAVAILABLE,
}

/** Closed undo plan. A success is only ever submitted with its stage-1 value. */
sealed interface HomeEditUndoPlan {
    sealed interface Success : HomeEditUndoPlan {
        val itemId: Int
    }

    /** Undo of 移動/フォルダ追加: one UPDATE back to the recorded placement. */
    data class RestorePlacement(
        override val itemId: Int,
        val container: Int,
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
        val rank: Int,
    ) : Success

    /** Undo of ホームから外す: one INSERT of the captured row. */
    data class RestoreRemoved(
        val payload: DirectEditContract.UndoRowPayload,
    ) : Success {
        override val itemId: Int get() = payload.itemId
    }

    /** Undo of 新しいフォルダ: child UPDATE + folder DELETE in one transaction. */
    data class UndoCreateFolder(
        override val itemId: Int,
        val createdFolderId: Int,
        val container: Int,
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
        val rank: Int,
    ) : Success

    data class Rejected(val reason: HomeEditUndoRejection) : HomeEditUndoPlan
}

object HomeEditUndoPlanner {

    /**
     * Verifies [entry] against the current [snapshot]. [availability] is only
     * consulted for the remove-undo; an UNAVAILABLE or UNKNOWN (verification
     * failure, fail-closed) launch target rejects without any write.
     */
    fun verify(
        snapshot: HomeEditSnapshot,
        entry: HomeEditUndoEntry.DirectEdit,
        availability: HomeEditUndoAvailability?,
    ): HomeEditUndoPlan {
        val evidence = entry.evidence
        return when (evidence.action) {
            HomeEditActionKind.MOVE_TO_PAGE, HomeEditActionKind.ADD_TO_FOLDER -> {
                val item = snapshot.itemById(evidence.itemId) ?: return HomeEditUndoPlan.Rejected(
                    HomeEditUndoRejection.STALE,
                )
                val atResult = item.container == evidence.newContainer &&
                    item.screenId == evidence.newScreenId &&
                    item.cellX == evidence.newCellX &&
                    item.cellY == evidence.newCellY &&
                    item.rank == evidence.newRank
                if (!atResult) return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE)
                checkRestoreTarget(
                    snapshot,
                    container = evidence.oldContainer,
                    screenId = evidence.oldScreenId,
                    cellX = evidence.oldCellX,
                    cellY = evidence.oldCellY,
                    spanX = evidence.oldSpanX,
                    spanY = evidence.oldSpanY,
                    rank = evidence.oldRank,
                    excludeItemId = item.id,
                )?.let { return it }
                HomeEditUndoPlan.RestorePlacement(
                    itemId = evidence.itemId,
                    container = evidence.oldContainer,
                    screenId = evidence.oldScreenId,
                    cellX = evidence.oldCellX,
                    cellY = evidence.oldCellY,
                    spanX = evidence.oldSpanX,
                    spanY = evidence.oldSpanY,
                    rank = evidence.oldRank,
                )
            }

            HomeEditActionKind.CREATE_FOLDER_AND_ADD -> {
                val item = snapshot.itemById(evidence.itemId) ?: return HomeEditUndoPlan.Rejected(
                    HomeEditUndoRejection.STALE,
                )
                val folderId = evidence.createdFolderId
                    ?: return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE)
                // Membership + rank is the undo contract. The folder-internal
                // cell is NOT: the folder view normalizes its children onto
                // the folder grid at bind time (Issue #450 — a create wrote
                // cell -1/-1 and the folder binding rewrote it to 0,0), so
                // pinning the recorded projection would STALE every real
                // undo. The stage-2 validator re-runs this same check inside
                // admission, so stage-1 and stage-2 stay consistent.
                val inFolder = item.container == folderId &&
                    item.rank == evidence.newRank
                if (!inFolder) return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE)
                val folder = snapshot.itemById(folderId)
                val folderIntact = folder != null &&
                    folder.itemType == HomeEditItemTypes.FOLDER &&
                    folder.container == HomeEditContainers.DESKTOP &&
                    folder.screenId == evidence.createdFolderScreenId &&
                    folder.cellX == evidence.createdFolderCellX &&
                    folder.cellY == evidence.createdFolderCellY &&
                    folder.spanX == 1 && folder.spanY == 1
                if (!folderIntact) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.FOLDER_CHANGED)
                }
                val childCount = snapshot.items.count { it.container == folderId }
                if (childCount != 1) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.FOLDER_CHANGED)
                }
                checkRestoreTarget(
                    snapshot,
                    container = evidence.oldContainer,
                    screenId = evidence.oldScreenId,
                    cellX = evidence.oldCellX,
                    cellY = evidence.oldCellY,
                    spanX = evidence.oldSpanX,
                    spanY = evidence.oldSpanY,
                    rank = evidence.oldRank,
                    excludeItemId = item.id,
                )?.let { return it }
                HomeEditUndoPlan.UndoCreateFolder(
                    itemId = evidence.itemId,
                    createdFolderId = folderId,
                    container = evidence.oldContainer,
                    screenId = evidence.oldScreenId,
                    cellX = evidence.oldCellX,
                    cellY = evidence.oldCellY,
                    spanX = evidence.oldSpanX,
                    spanY = evidence.oldSpanY,
                    rank = evidence.oldRank,
                )
            }

            HomeEditActionKind.REMOVE -> {
                val payload = entry.removedRow
                    ?: return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE)
                if (availability != HomeEditUndoAvailability.AVAILABLE) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.ITEM_UNAVAILABLE)
                }
                // The deleted row must still be gone: a re-created item means
                // the recorded precondition no longer describes the state.
                if (snapshot.itemById(payload.itemId) != null) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE)
                }
                checkRestoreTarget(
                    snapshot,
                    container = payload.container,
                    screenId = payload.screenId,
                    cellX = payload.cellX,
                    cellY = payload.cellY,
                    spanX = payload.spanX,
                    spanY = payload.spanY,
                    rank = payload.rank,
                    excludeItemId = payload.itemId,
                )?.let { return it }
                HomeEditUndoPlan.RestoreRemoved(payload)
            }
        }
    }

    /**
     * The recorded old placement must still be restorable: present container,
     * inside the current grid, and unoccupied. Returns the typed rejection or
     * null when the target is restorable. Deterministic; no side effects.
     */
    private fun checkRestoreTarget(
        snapshot: HomeEditSnapshot,
        container: Int,
        screenId: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int,
        rank: Int,
        excludeItemId: Int,
    ): HomeEditUndoPlan.Rejected? {
        return when (container) {
            HomeEditContainers.DESKTOP -> {
                if (screenId !in snapshot.screenIds) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE)
                }
                val withinGrid = cellX >= 0 && cellY >= 0 &&
                    cellX + spanX <= snapshot.columnCount &&
                    cellY + spanY <= snapshot.rowCount
                if (!withinGrid) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE)
                }
                if (!isDesktopRectFree(snapshot, screenId, cellX, cellY, spanX, spanY, excludeItemId)) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE)
                }
                null
            }

            HomeEditContainers.HOTSEAT -> {
                // Hotseat rows use screen as the slot index; the recorded slot
                // must still exist in the CURRENT device profile (a grid
                // change can shrink the hotseat below the recorded slot).
                if (screenId < 0 || screenId >= snapshot.hotseatCount) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE)
                }
                val occupied = snapshot.items.any {
                    it.id != excludeItemId &&
                        it.container == HomeEditContainers.HOTSEAT &&
                        it.screenId == screenId
                }
                if (occupied) HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE) else null
            }

            else -> {
                // A folder container: the folder row must still exist and the
                // recorded rank must still be free.
                val folder = snapshot.itemById(container)
                if (folder == null || folder.itemType != HomeEditItemTypes.FOLDER) {
                    return HomeEditUndoPlan.Rejected(HomeEditUndoRejection.FOLDER_CHANGED)
                }
                val rankTaken = snapshot.items.any {
                    it.id != excludeItemId && it.container == container && it.rank == rank
                }
                if (rankTaken) HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE) else null
            }
        }
    }

    private fun isDesktopRectFree(
        snapshot: HomeEditSnapshot,
        screenId: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int,
        excludeItemId: Int,
    ): Boolean {
        for (item in snapshot.items) {
            if (item.id == excludeItemId) continue
            if (item.container != HomeEditContainers.DESKTOP) continue
            if (item.screenId != screenId) continue
            val overlaps = cellX < item.cellX + item.spanX &&
                item.cellX < cellX + spanX &&
                cellY < item.cellY + item.spanY &&
                item.cellY < cellY + spanY
            if (overlaps) return false
        }
        return true
    }
}

/** Undo rejection → machine-readable key (typed failure vocabulary). */
fun undoRejectionKey(reason: HomeEditUndoRejection): String = when (reason) {
    HomeEditUndoRejection.STALE -> DirectEditContract.FAIL_UNDO_STALE
    HomeEditUndoRejection.NO_SPACE -> DirectEditContract.FAIL_UNDO_NO_SPACE
    HomeEditUndoRejection.FOLDER_CHANGED -> DirectEditContract.FAIL_UNDO_FOLDER_CHANGED
    HomeEditUndoRejection.ITEM_UNAVAILABLE -> DirectEditContract.FAIL_UNDO_ITEM_UNAVAILABLE
}

/**
 * Stage-2 validator for an undo attempt (ADR-0013 contract 2, second stage).
 * Re-runs the same pure planner inside admission against the current state
 * with a fresh availability determination from the same production source and
 * only allows the write when the result still equals the stage-1 plan.
 */
class HomeEditUndoStage2Validator(
    private val entry: HomeEditUndoEntry.DirectEdit,
    private val stage1: HomeEditUndoPlan.Success,
    private val availabilitySource: HomeEditUndoAvailabilitySource?,
) : DirectEditContract.Validator {

    override fun validate(current: DirectEditContract.Snapshot): DirectEditContract.Decision {
        val snapshot = HomeEditSnapshotMapper.map(current)
        val availability = entry.removedRow?.let { availabilitySource?.availabilityOf(it) }
        return when (val replan = HomeEditUndoPlanner.verify(snapshot, entry, availability)) {
            stage1 -> DirectEditContract.Decision.proceed()

            is HomeEditUndoPlan.Rejected -> DirectEditContract.Decision.reject(
                undoRejectionKey(replan.reason),
            )

            else -> DirectEditContract.Decision.reject(DirectEditContract.FAIL_UNDO_STALE)
        }
    }
}
