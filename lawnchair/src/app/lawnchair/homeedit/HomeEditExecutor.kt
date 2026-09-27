/*
 * Issue #448: orchestration for the popup edit actions. Stage-1 snapshot and
 * planning run on the model executor (MODEL_EXECUTOR) per spec 448 — the
 * launcher DB is only read through ModelDbController there, never bulk-read on
 * the UI thread. The confirmed intent is submitted to ModelWriter's
 * direct-edit operations (ADR-0013 contract 4: validation → admission →
 * stage-2 re-validation → model/DB change).
 */
package app.lawnchair.homeedit

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.Workspace
import com.android.launcher3.model.DirectEditContract
import com.android.launcher3.util.Executors

class HomeEditExecutor(private val launcher: LawnchairLauncher) {

    /** One page entry for the destination picker. */
    data class PageOption(val screenId: Int, val hasFreeCell: Boolean)

    /** One folder entry for the folder picker. */
    data class FolderOption(val folderId: Int, val itemCount: Int, val userSerial: Long)

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Builds the stage-1 snapshot from the launcher DB. Model executor only.
     * The projection mirrors ModelWriter's stage-2 projection; the admitted
     * stage-2 re-validation is the binding check for any divergence.
     */
    private fun buildSnapshot(): HomeEditSnapshot {
        val controller = LauncherAppState.getInstance(launcher).model.modelDbController
        val db = controller.db
        val items = ArrayList<HomeEditItem>()
        val columns = "${Favorites._ID}, ${Favorites.CONTAINER}, ${Favorites.SCREEN}, " +
            "${Favorites.CELLX}, ${Favorites.CELLY}, ${Favorites.SPANX}, ${Favorites.SPANY}, " +
            "${Favorites.ITEM_TYPE}, ${Favorites.RANK}, ${Favorites.PROFILE_ID}"
        db.rawQuery("SELECT $columns FROM ${Favorites.TABLE_NAME}", null).use { c ->
            while (c.moveToNext()) {
                items.add(
                    HomeEditItem(
                        id = c.getInt(0),
                        container = c.getInt(1),
                        screenId = c.getInt(2),
                        cellX = c.getInt(3),
                        cellY = c.getInt(4),
                        spanX = c.getInt(5),
                        spanY = c.getInt(6),
                        itemType = c.getInt(7),
                        rank = c.getInt(8),
                        userSerial = c.getLong(9),
                    ),
                )
            }
        }
        val screens = ArrayList<Int>()
        db.rawQuery(
            "SELECT DISTINCT ${Favorites.SCREEN} FROM ${Favorites.TABLE_NAME} " +
                "WHERE ${Favorites.CONTAINER} = ? ORDER BY ${Favorites.SCREEN}",
            arrayOf(Favorites.CONTAINER_DESKTOP.toString()),
        ).use { c ->
            while (c.moveToNext()) screens.add(c.getInt(0))
        }
        // Mirror BgDataModel.collectWorkspaceScreens: the first screen leads
        // whenever the QSB reservation applies or no row carries a page yet.
        if ((
                com.android.launcher3.config.FeatureFlags.topQsbOnFirstScreenEnabled(launcher) ||
                    screens.isEmpty()
                ) && Workspace.FIRST_SCREEN_ID !in screens
        ) {
            screens.add(0, Workspace.FIRST_SCREEN_ID)
        }
        return HomeEditSnapshot(
            columnCount = LauncherAppState.getIDP(launcher).numColumns,
            rowCount = LauncherAppState.getIDP(launcher).numRows,
            screenIds = screens,
            items = items,
        )
    }

    fun fetchPageOptions(targetItemId: Int, callback: (List<PageOption>) -> Unit) {
        Executors.MODEL_EXECUTOR.execute {
            val snapshot = buildSnapshot()
            val options = snapshot.screenIds.map { screenId ->
                PageOption(screenId, HomeEditPlanner.hasFreeCell(snapshot, screenId, targetItemId))
            }
            mainHandler.post { callback(options) }
        }
    }

    fun fetchFolderOptions(targetUserSerial: Long, callback: (List<FolderOption>) -> Unit) {
        Executors.MODEL_EXECUTOR.execute {
            val snapshot = buildSnapshot()
            val options = snapshot.items
                .filter { it.itemType == HomeEditItemTypes.FOLDER && it.userSerial == targetUserSerial }
                .map { folder ->
                    FolderOption(
                        folderId = folder.id,
                        itemCount = snapshot.items.count { it.container == folder.id },
                        userSerial = folder.userSerial,
                    )
                }
            mainHandler.post { callback(options) }
        }
    }

    /**
     * Stage-1 planning at confirm time, then submission of the validated
     * plan. Rejects without any write, reporting a localized reason.
     */
    fun confirm(itemId: Int, intent: HomeEditIntent) {
        Executors.MODEL_EXECUTOR.execute {
            val snapshot = buildSnapshot()
            when (val plan = HomeEditPlanner.plan(snapshot, intent)) {
                is HomeEditPlan.Rejected -> mainHandler.post { reportRejection(plan.reason) }
                is HomeEditPlan.Success -> submit(itemId, intent, plan)
            }
        }
    }

    private fun submit(itemId: Int, intent: HomeEditIntent, plan: HomeEditPlan.Success) {
        val validator = HomeEditStage2Validator(intent, plan)
        val callback =
            DirectEditContract.ResultCallback {
                    id,
                    success,
                    reason,
                    oldContainer,
                    oldScreenId,
                    oldCellX,
                    oldCellY,
                    oldSpanX,
                    oldSpanY,
                    oldRank,
                    createdFolderId,
                ->
                if (!success) {
                    mainHandler.post { reportFailureKey(reason) }
                    return@ResultCallback
                }
                recordUndoEvidence(
                    intent, plan, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, createdFolderId,
                )
                if (plan is HomeEditPlan.Move && plan.container == Favorites.CONTAINER_DESKTOP) {
                    mainHandler.post { showDestinationPage(plan.screenId) }
                }
            }
        val writer = launcher.modelWriter
        when (plan) {
            is HomeEditPlan.Move -> writer.moveItemForDirectEdit(
                itemId,
                plan.container,
                plan.screenId,
                plan.cellX,
                plan.cellY,
                plan.rank,
                validator,
                callback,
            )

            is HomeEditPlan.CreateFolder -> writer.createFolderAndMoveForDirectEdit(
                itemId,
                plan.screenId,
                plan.cellX,
                plan.cellY,
                validator,
                callback,
            )

            is HomeEditPlan.RemoveItem -> writer.removeItemForDirectEdit(itemId, validator, callback)
        }
    }

    private fun recordUndoEvidence(
        intent: HomeEditIntent,
        plan: HomeEditPlan.Success,
        oldContainer: Int,
        oldScreenId: Int,
        oldCellX: Int,
        oldCellY: Int,
        oldSpanX: Int,
        oldSpanY: Int,
        oldRank: Int,
        createdFolderId: Int?,
    ) {
        val action = when (intent) {
            is HomeEditIntent.MoveToPage -> HomeEditActionKind.MOVE_TO_PAGE
            is HomeEditIntent.AddToFolder -> HomeEditActionKind.ADD_TO_FOLDER
            is HomeEditIntent.CreateFolderAndAdd -> HomeEditActionKind.CREATE_FOLDER_AND_ADD
            is HomeEditIntent.Remove -> HomeEditActionKind.REMOVE
        }
        val target = plan.targetItemPlacement
        val (newContainer, newScreenId, newCellX, newCellY, newRank) = when (plan) {
            is HomeEditPlan.Move -> listOf(plan.container, plan.screenId, plan.cellX, plan.cellY, plan.rank)
            is HomeEditPlan.CreateFolder -> listOf(target.container, target.screenId, target.cellX, target.cellY, 0)
            is HomeEditPlan.RemoveItem -> listOf(oldContainer, oldScreenId, oldCellX, oldCellY, oldRank)
        }
        HomeEditUndoLog.record(
            HomeEditUndoEvidence(
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
                createdFolderId = createdFolderId.takeIf { it != 0 },
            ),
        )
    }

    private fun showDestinationPage(screenId: Int) {
        val workspace = launcher.workspace ?: return
        val index = workspace.getPageIndexForScreenId(screenId)
        if (index >= 0) workspace.snapToPage(index)
    }

    private fun reportRejection(reason: HomeEditRejection) {
        val res = when (reason) {
            HomeEditRejection.STALE -> R.string.homeedit_error_stale
            HomeEditRejection.ITEM_GONE -> R.string.homeedit_error_item_gone
            HomeEditRejection.NO_SPACE -> R.string.homeedit_error_no_space
            HomeEditRejection.REDUNDANT -> R.string.homeedit_error_redundant
            HomeEditRejection.FOLDER_GONE -> R.string.homeedit_error_folder_gone
            HomeEditRejection.PROFILE_MISMATCH -> R.string.homeedit_error_profile_mismatch
            HomeEditRejection.UNSUPPORTED -> R.string.homeedit_error_unsupported
        }
        Toast.makeText(launcher, res, Toast.LENGTH_LONG).show()
    }

    private fun reportFailureKey(reason: String?) {
        val res = when (reason) {
            DirectEditContract.FAIL_STALE -> R.string.homeedit_error_stale
            DirectEditContract.FAIL_ITEM_GONE -> R.string.homeedit_error_item_gone
            DirectEditContract.FAIL_NO_SPACE -> R.string.homeedit_error_no_space
            DirectEditContract.FAIL_REDUNDANT -> R.string.homeedit_error_redundant
            DirectEditContract.FAIL_FOLDER_GONE -> R.string.homeedit_error_folder_gone
            DirectEditContract.FAIL_PROFILE_MISMATCH -> R.string.homeedit_error_profile_mismatch
            DirectEditContract.FAIL_UNSUPPORTED -> R.string.homeedit_error_unsupported
            else -> R.string.homeedit_error_write_failed
        }
        Toast.makeText(launcher, res, Toast.LENGTH_LONG).show()
    }
}
