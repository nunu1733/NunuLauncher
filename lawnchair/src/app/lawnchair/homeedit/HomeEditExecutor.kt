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
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.DirectEditContract
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors
import java.util.Collections

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
        val qsbEnabled = com.android.launcher3.config.FeatureFlags.topQsbOnFirstScreenEnabled(launcher)
        if ((qsbEnabled || screens.isEmpty()) && Workspace.FIRST_SCREEN_ID !in screens) {
            screens.add(0, Workspace.FIRST_SCREEN_ID)
        }
        // The QSB reservation occupies the head of the first screen; the
        // stage-2 projection (ModelWriter) applies the identical rule.
        if (qsbEnabled) {
            items.add(
                HomeEditItem(
                    id = -1,
                    container = HomeEditContainers.DESKTOP,
                    screenId = Workspace.FIRST_SCREEN_ID,
                    cellX = 0,
                    cellY = 0,
                    spanX = LauncherAppState.getIDP(launcher).numSearchContainerColumns,
                    spanY = 1,
                    itemType = HomeEditItemTypes.APPLICATION,
                    rank = 0,
                    userSerial = 0,
                ),
            )
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
                    createdFolder,
                ->
                if (!success) {
                    mainHandler.post { reportFailureKey(reason) }
                    return@ResultCallback
                }
                recordUndoEvidence(
                    intent, plan, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, createdFolderId,
                )
                when (plan) {
                    is HomeEditPlan.Move -> mainHandler.post { refreshAfterMove(id, plan) }

                    is HomeEditPlan.RemoveItem -> mainHandler.post { refreshAfterRemove(id) }

                    is HomeEditPlan.CreateFolder -> mainHandler.post {
                        // The new folder icon does not exist yet on the owning
                        // launcher; bind it from the model folder row.
                        createdFolder?.let { launcher.bindItems(Collections.singletonList(it), false) }
                    }
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

    /**
     * UI refresh after an admitted move (accessibility-path precedent:
     * LauncherAccessibilityDelegate binds the item after the model write).
     * `bindItemsModified` carries no view update, so the stale view is
     * removed; desktop destinations are re-bound and snapped to, and folder
     * destinations get a FolderIcon preview refresh (view-only onAdd) on the
     * UI thread — the model-thread contents add is silent by design.
     */
    private fun refreshAfterMove(itemId: Int, plan: HomeEditPlan.Move) {
        val view = launcher.workspace?.getHomescreenIconByItemId(itemId)
        val info = view?.tag as? ItemInfo
        // The workspace matcher can observe unbound views whose tag is not
        // bound yet; guard before reading the id.
        launcher.bindWorkspaceComponentsRemoved { candidate ->
            candidate != null && candidate.id == itemId
        }
        when {
            plan.container == Favorites.CONTAINER_DESKTOP && info != null -> {
                launcher.bindItems(Collections.singletonList(info), true)
                showDestinationPage(plan.screenId)
            }

            info != null -> {
                val folderIcon = launcher.workspace
                    ?.getHomescreenIconByItemId(plan.container) as? FolderIcon
                folderIcon?.onAdd(info, plan.rank)
            }
        }
    }

    /**
     * UI refresh after an admitted remove. ModelWriter's
     * `notifyOtherCallbacks` intentionally skips the owning launcher (the
     * upstream delete flow removes the view in the drag layer), so the popup
     * path removes the view here.
     */
    private fun refreshAfterRemove(itemId: Int) {
        launcher.bindWorkspaceComponentsRemoved { candidate ->
            candidate != null && candidate.id == itemId
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
        createdFolderId: Int,
    ) {
        HomeEditUndoLog.record(
            buildUndoEvidence(
                intent,
                plan,
                oldContainer,
                oldScreenId,
                oldCellX,
                oldCellY,
                oldSpanX,
                oldSpanY,
                oldRank,
                createdFolderId,
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
