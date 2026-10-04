/*
 * Issue #450: orchestration for the undo (spec 450). The snackbar action
 * starts here with the generation token observed at display time; a mismatched
 * consume is a silent zero-write no-op. Item-level entries re-run the pure
 * undo planner at stage 1 and submit the inverse operation to ModelWriter
 * (ADR-0013 contract 5: validation → admission → stage-2 re-validation →
 * model/DB change). Edit-session entries restore through the organizer
 * recovery path on a dedicated thread (the correlated reload wait must not
 * occupy MODEL_EXECUTOR — same rationale as HomeEditSurfaceActivity).
 */
package app.lawnchair.homeedit

import app.lawnchair.homeedit.getHomescreenIconByItemId
import android.os.Handler
import app.lawnchair.homeedit.getHomescreenIconByItemId
import android.os.Looper
import app.lawnchair.homeedit.getHomescreenIconByItemId
import android.widget.Toast
import app.lawnchair.homeedit.getHomescreenIconByItemId
import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.getHomescreenIconByItemId
import app.lawnchair.homeedit.ui.HomeEditUndoSnackbar
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.LauncherAppState
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.LauncherSettings.Favorites
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.R
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.logging.StatsLogManager.LauncherEvent
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.model.DirectEditContract
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.model.data.ItemInfo
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.util.Executors
import app.lawnchair.homeedit.getHomescreenIconByItemId
import java.util.Collections

class HomeEditUndoExecutor(
    private val launcher: LawnchairLauncher,
    /**
     * Test-only observer of the typed failure display: invoked with the
     * string resource the executor shows (Toast in production). Production
     * callers never pass it; the instrumentation oracle uses it to pin the
     * #449-flow → undo → typed-display chain without asserting on Toast
     * internals.
     */
    private val failureDisplayObserver: ((Int) -> Unit)? = null,
    /**
     * Test-only observer of the raw recovery result (round 6 finding 2): lets
     * the instrumentation oracle pin the INTERNAL reason (e.g.
     * `NotRestorable(EXPIRED)`) on the production seam, not just the display
     * resource. Production callers never pass it.
     */
    private val recoveryResultObserver: ((app.lawnchair.organizer.application.public.RecoveryResult) -> Unit)? = null,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val availabilitySource by lazy { ProductionHomeEditUndoAvailabilitySource(launcher) }

    /**
     * 適用後の復元（recover）専用スレッド。相関reloadはLoaderTaskを
     * MODEL_EXECUTORへpostし完了を待つため、MODEL_EXECUTOR上で待つと
     * デッドロックする（HomeEditSurfaceActivityのsurfaceExecutorと同理由）。
     */
    private val recoverExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "homeedit-undo-recover")
        }
    }

    /**
     * Snackbar action entry. The record is consumed atomically; a stale token
     * (the record was already replaced by a newer edit) is a zero-write no-op
     * that leaves the current record untouched (spec 450 AC-11).
     */
    fun start(token: HomeEditUndoToken) {
        val entry = HomeEditUndoRecord.compareAndConsume(token) ?: return
        launcher.statsLogManager.logger().log(LauncherEvent.LAUNCHER_UNDO)
        when (entry) {
            is HomeEditUndoEntry.DirectEdit -> Executors.MODEL_EXECUTOR.execute {
                runDirectEditUndo(entry)
            }

            is HomeEditUndoEntry.EditSession -> recoverExecutor.execute {
                runEditSessionUndo(entry)
            }
        }
    }

    private fun runDirectEditUndo(entry: HomeEditUndoEntry.DirectEdit) {
        val snapshot = buildHomeEditSnapshot(launcher)
        val availability = entry.removedRow?.let { availabilitySource.availabilityOf(it) }
        when (val plan = HomeEditUndoPlanner.verify(snapshot, entry, availability)) {
            is HomeEditUndoPlan.Rejected -> mainHandler.post { reportUndoRejection(plan.reason) }
            is HomeEditUndoPlan.Success -> submit(entry, plan)
        }
    }

    private fun submit(entry: HomeEditUndoEntry.DirectEdit, plan: HomeEditUndoPlan.Success) {
        val validator = HomeEditUndoStage2Validator(entry, plan, availabilitySource)
        val callback =
            DirectEditContract.ResultCallback { id, success, reason, _, _, _, _, _, _, _, _, _, _ ->
                if (!success) {
                    mainHandler.post { reportFailureKey(reason) }
                    return@ResultCallback
                }
                mainHandler.post { refreshAfterUndo(entry.evidence, plan, id) }
            }
        val writer = launcher.modelWriter
        when (plan) {
            is HomeEditUndoPlan.RestorePlacement -> writer.restorePlacementForDirectEdit(
                plan.itemId,
                plan.container,
                plan.screenId,
                plan.cellX,
                plan.cellY,
                plan.spanX,
                plan.spanY,
                plan.rank,
                validator,
                callback,
            )

            is HomeEditUndoPlan.RestoreRemoved -> writer.restoreRemovedItemForDirectEdit(
                plan.payload,
                validator,
                callback,
            )

            is HomeEditUndoPlan.UndoCreateFolder -> writer.undoCreateFolderForDirectEdit(
                plan.itemId,
                plan.createdFolderId,
                plan.container,
                plan.screenId,
                plan.cellX,
                plan.cellY,
                plan.spanX,
                plan.spanY,
                plan.rank,
                validator,
                callback,
            )
        }
    }

    private fun runEditSessionUndo(entry: HomeEditUndoEntry.EditSession) {
        val result = HomeEditSurfaceAccess.get(launcher)
            .recover(
                app.lawnchair.organizer.application.public.RecoveryRequest(
                    entry.pointId,
                    entry.expectedRevision,
                ),
            )
        recoveryResultObserver?.invoke(result)
        mainHandler.post {
            homeEditUndoRecoveryText(result)?.let { res ->
                failureDisplayObserver?.invoke(res)
                Toast.makeText(launcher, res, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * UI refresh after an admitted undo. Light path (view rebind + snap) for
     * pure placement restores; a model reload is the refresh for membership
     * changes and row restores — a one-shot operation, and the reload is the
     * single authoritative projection of the restored row.
     */
    private fun refreshAfterUndo(evidence: HomeEditUndoEvidence, plan: HomeEditUndoPlan.Success, id: Int) {
        when (plan) {
            is HomeEditUndoPlan.RestorePlacement -> {
                if (evidence.action == HomeEditActionKind.ADD_TO_FOLDER) {
                    // Undo leaves the folder: refresh the folder icon preview
                    // (model-thread contents removal is silent by design).
                    val folderIcon = launcher.workspace
                        ?.getHomescreenIconByItemId(evidence.newContainer)
                        as? com.android.launcher3.folder.FolderIcon
                    val info = launcher.workspace?.getHomescreenIconByItemId(id)?.tag as? ItemInfo
                    if (folderIcon != null && info != null) {
                        folderIcon.onRemove(Collections.singletonList(info))
                    }
                }
                refreshAfterRestorePlacement(id, plan)
            }

            is HomeEditUndoPlan.RestoreRemoved -> launcher.model.forceReload()

            is HomeEditUndoPlan.UndoCreateFolder -> {
                launcher.bindWorkspaceComponentsRemoved { candidate ->
                    candidate != null && candidate.id == plan.createdFolderId
                }
                launcher.model.forceReload()
            }
        }
    }

    /**
     * UI refresh after an admitted placement restore (accessibility-path
     * precedent, mirrors HomeEditExecutor.refreshAfterMove): the stale view is
     * removed, the desktop destination is re-bound and snapped to, and a
     * folder destination gets a FolderIcon preview refresh (view-only onAdd).
     */
    private fun refreshAfterRestorePlacement(itemId: Int, plan: HomeEditUndoPlan.RestorePlacement) {
        val view = launcher.workspace?.getHomescreenIconByItemId(itemId)
        val info = view?.tag as? ItemInfo
        launcher.bindWorkspaceComponentsRemoved { candidate ->
            candidate != null && candidate.id == itemId
        }
        when {
            plan.container == Favorites.CONTAINER_DESKTOP && info != null -> {
                launcher.bindItemsAdapted(Collections.singletonList(info), true)
                showDestinationPage(plan.screenId)
            }

            info != null -> {
                val folderIcon = launcher.workspace
                    ?.getHomescreenIconByItemId(plan.container) as? com.android.launcher3.folder.FolderIcon
                folderIcon?.onAdd(info, plan.rank)
            }
        }
    }

    private fun showDestinationPage(screenId: Int) {
        val workspace = launcher.workspace ?: return
        val index = workspace.getPageIndexForScreenId(screenId)
        if (index >= 0) workspace.snapToPage(index)
    }

    private fun reportUndoRejection(reason: HomeEditUndoRejection) {
        val res = when (reason) {
            HomeEditUndoRejection.STALE -> R.string.homeedit_undo_error_stale
            HomeEditUndoRejection.NO_SPACE -> R.string.homeedit_undo_error_no_space
            HomeEditUndoRejection.FOLDER_CHANGED -> R.string.homeedit_undo_error_folder_changed
            HomeEditUndoRejection.ITEM_UNAVAILABLE -> R.string.homeedit_undo_error_item_unavailable
        }
        failureDisplayObserver?.invoke(res)
        Toast.makeText(launcher, res, Toast.LENGTH_LONG).show()
    }

    private fun reportFailureKey(reason: String?) {
        val res = when (reason) {
            DirectEditContract.FAIL_UNDO_STALE -> R.string.homeedit_undo_error_stale
            DirectEditContract.FAIL_UNDO_NO_SPACE -> R.string.homeedit_undo_error_no_space
            DirectEditContract.FAIL_UNDO_FOLDER_CHANGED -> R.string.homeedit_undo_error_folder_changed
            DirectEditContract.FAIL_UNDO_ITEM_UNAVAILABLE -> R.string.homeedit_undo_error_item_unavailable
            else -> R.string.homeedit_undo_error_write_failed
        }
        failureDisplayObserver?.invoke(res)
        Toast.makeText(launcher, res, Toast.LENGTH_LONG).show()
    }
}
