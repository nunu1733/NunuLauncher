/*
 * Issue #448: adapter between the platform direct-edit contract and the pure
 * homeedit planner. The mapper is the only place platform snapshot data turns
 * into the fork snapshot type; the validator is the stage-2 half of
 * ADR-0013 contract 2 and runs inside MODEL_WRITER admission on the model
 * thread.
 */
package app.lawnchair.homeedit

import com.android.launcher3.model.DirectEditContract

object HomeEditSnapshotMapper {

    /** Pure projection: platform snapshot → homeedit snapshot. */
    fun map(snapshot: DirectEditContract.Snapshot): HomeEditSnapshot = HomeEditSnapshot(
        columnCount = snapshot.columnCount,
        rowCount = snapshot.rowCount,
        screenIds = snapshot.screenIds.toList(),
        items = snapshot.rows.map { row ->
            HomeEditItem(
                id = row.id,
                container = row.container,
                screenId = row.screenId,
                cellX = row.cellX,
                cellY = row.cellY,
                spanX = row.spanX,
                spanY = row.spanY,
                itemType = row.itemType,
                rank = row.rank,
                userSerial = row.userSerial,
            )
        },
    )

    fun rejectionKey(reason: HomeEditRejection): String = when (reason) {
        HomeEditRejection.STALE -> DirectEditContract.FAIL_STALE
        HomeEditRejection.ITEM_GONE -> DirectEditContract.FAIL_ITEM_GONE
        HomeEditRejection.NO_SPACE -> DirectEditContract.FAIL_NO_SPACE
        HomeEditRejection.REDUNDANT -> DirectEditContract.FAIL_REDUNDANT
        HomeEditRejection.FOLDER_GONE -> DirectEditContract.FAIL_FOLDER_GONE
        HomeEditRejection.PROFILE_MISMATCH -> DirectEditContract.FAIL_PROFILE_MISMATCH
        HomeEditRejection.UNSUPPORTED -> DirectEditContract.FAIL_UNSUPPORTED
    }
}

/**
 * Stage-2 validator (ADR-0013 contract 2, second stage). Re-runs the same
 * pure planner against the current state inside admission and only allows the
 * write when the result still equals the stage-1 plan — a different
 * destination or rejection is surfaced as a typed failure, never re-planned
 * silently. The target must also still sit at the placement it had at stage 1.
 */
class HomeEditStage2Validator(
    private val intent: HomeEditIntent,
    private val stage1: HomeEditPlan.Success,
) : DirectEditContract.Validator {

    override fun validate(current: DirectEditContract.Snapshot): DirectEditContract.Decision {
        val snapshot = HomeEditSnapshotMapper.map(current)
        val targetId = when (intent) {
            is HomeEditIntent.MoveToPage -> intent.targetItemId
            is HomeEditIntent.AddToFolder -> intent.targetItemId
            is HomeEditIntent.CreateFolderAndAdd -> intent.targetItemId
            is HomeEditIntent.Remove -> intent.targetItemId
        }
        val target = snapshot.itemById(targetId)
        if (target == null || target != stage1.targetItemPlacement) {
            return DirectEditContract.Decision.reject(
                if (target == null) DirectEditContract.FAIL_ITEM_GONE else DirectEditContract.FAIL_STALE,
            )
        }
        return when (val replan = HomeEditPlanner.plan(snapshot, intent)) {
            stage1 -> DirectEditContract.Decision.proceed()

            is HomeEditPlan.Rejected -> DirectEditContract.Decision.reject(
                HomeEditSnapshotMapper.rejectionKey(replan.reason),
            )

            else -> DirectEditContract.Decision.reject(DirectEditContract.FAIL_STALE)
        }
    }
}
