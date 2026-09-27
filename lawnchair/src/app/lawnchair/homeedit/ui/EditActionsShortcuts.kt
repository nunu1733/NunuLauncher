/*
 * Issue #448: the three per-item edit actions in the long-press popup.
 * Same extension point as OrganizerLockShortcut (fork-side SystemShortcut
 * factories; no upstream file changes). The write goes through
 * HomeEditExecutor → ModelWriter direct-edit operations (ADR-0013).
 */
package app.lawnchair.homeedit.ui

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.HomeEditExecutor
import app.lawnchair.homeedit.HomeEditIntent
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockExplanation
import app.lawnchair.organizer.locks.LockTargetState
import app.lawnchair.organizer.locks.OrganizerLocks
import app.lawnchair.organizer.planning.ItemId
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.R
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.util.Executors

/** See spec 448 Scope: saved app/deep-shortcut rows only. */
private fun editableTarget(itemInfo: ItemInfo): ItemInfo? {
    if (itemInfo.itemType != ITEM_TYPE_APPLICATION && itemInfo.itemType != ITEM_TYPE_DEEP_SHORTCUT) {
        return null
    }
    if (itemInfo.id == ItemInfo.NO_ID) return null
    return itemInfo
}

class EditActionsShortcuts {

    companion object {
        val MOVE_TO_PAGE =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
                editableTarget(itemInfo)?.let { MoveToPage(activity, it, originalView) }
            }

        val ADD_TO_FOLDER =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
                editableTarget(itemInfo)?.let { AddToFolder(activity, it, originalView) }
            }

        val REMOVE_FROM_HOME =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
                editableTarget(itemInfo)?.let { RemoveFromHome(activity, it, originalView) }
            }
    }

    /**
     * Base for the popup actions: closes the popup, resolves the lock note
     * (placement-lock rule of ADR-0013) plus action-specific options, then
     * shows the dialog once both async sources are ready.
     */
    abstract class EditAction(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
        iconRes: Int,
        titleRes: Int,
    ) : SystemShortcut<LawnchairLauncher>(
        iconRes,
        titleRes,
        target,
        itemInfo,
        originalView,
    ) {

        protected val executor by lazy { HomeEditExecutor(mTarget) }
        protected val itemId: Int get() = mItemInfo.id

        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingSources = 2
        private var locked = false

        /** Whether the dialog needs the placement-lock note. */
        protected open val showsLockNote: Boolean get() = true

        final override fun onClick(view: View) {
            AbstractFloatingView.closeAllOpenViews(mTarget)
            pendingSources = if (showsLockNote) 2 else 1
            loadOptions()
            if (showsLockNote) {
                Executors.THREAD_POOL_EXECUTOR.execute {
                    val isLocked = readLockState()
                    mainHandler.post {
                        locked = isLocked
                        onSourceReady()
                    }
                }
            }
        }

        /** Starts the action-specific async option load; call [onSourceReady] when done. */
        protected abstract fun loadOptions()

        /** Invoked on the main thread once lock state and options are ready. */
        protected abstract fun showDialog()

        protected fun lockNoteText(): String? = if (locked) mTarget.getString(R.string.homeedit_lock_note_locked) else null

        protected fun onSourceReady() {
            pendingSources--
            if (pendingSources == 0) showDialog()
        }

        private fun readLockState(): Boolean = try {
            val explanation = OrganizerLocks.get(mTarget).explain(
                ItemId(itemId.toString()),
                LockTargetState.LOCKED,
            )
            explanation is LockExplanation.Available && explanation.entry.stored == OrganizerLockState.LOCKED
        } catch (e: Exception) {
            false
        }
    }

    class MoveToPage(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : EditAction(
        target,
        itemInfo,
        originalView,
        com.android.launcher3.R.drawable.ic_apps,
        R.string.homeedit_menu_move_to_page,
    ) {
        private var options: List<HomeEditExecutor.PageOption> = emptyList()

        override fun loadOptions() {
            executor.fetchPageOptions(itemId) { pageOptions ->
                options = pageOptions
                onSourceReady()
            }
        }

        override fun showDialog() {
            val labels = options.mapIndexed { index, _ ->
                mTarget.getString(R.string.homeedit_page_label, index + 1)
            }
            AlertDialog.Builder(mTarget)
                .setTitle(R.string.homeedit_dialog_title_move_to_page)
                .setMessage(lockNoteText())
                .setNegativeButton(android.R.string.cancel, null)
                .setItems(labels.toTypedArray()) { _, which ->
                    executor.confirm(itemId, HomeEditIntent.MoveToPage(itemId, options[which].screenId))
                }
                .show()
        }
    }

    class AddToFolder(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : EditAction(
        target,
        itemInfo,
        originalView,
        R.drawable.ic_folder,
        R.string.homeedit_menu_add_to_folder,
    ) {
        private var options: List<HomeEditExecutor.FolderOption> = emptyList()

        override fun loadOptions() {
            val userSerial = UserCache.INSTANCE.get(mTarget)
                .getSerialNumberForUser(mItemInfo.user)
            executor.fetchFolderOptions(userSerial) { folderOptions ->
                options = folderOptions
                onSourceReady()
            }
        }

        override fun showDialog() {
            val labels = ArrayList<String>()
            val actions = ArrayList<Runnable>()
            for (option in options) {
                labels.add(
                    mTarget.getString(
                        R.string.homeedit_folder_entry,
                        folderTitle(option.folderId),
                        option.itemCount,
                    ),
                )
                actions.add {
                    executor.confirm(itemId, HomeEditIntent.AddToFolder(itemId, option.folderId))
                }
            }
            labels.add(mTarget.getString(R.string.homeedit_list_new_folder))
            actions.add { pickNewFolderPage() }
            AlertDialog.Builder(mTarget)
                .setTitle(R.string.homeedit_dialog_title_add_to_folder)
                .setMessage(lockNoteText())
                .setNegativeButton(android.R.string.cancel, null)
                .setItems(labels.toTypedArray()) { _, which -> actions[which].run() }
                .show()
        }

        /** Spec 448: a new folder is always created on a chosen workspace page. */
        private fun pickNewFolderPage() {
            executor.fetchPageOptions(itemId) { pageOptions ->
                val labels = pageOptions.mapIndexed { index, _ ->
                    mTarget.getString(R.string.homeedit_page_label, index + 1)
                }
                AlertDialog.Builder(mTarget)
                    .setTitle(R.string.homeedit_dialog_title_new_folder_page)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setItems(labels.toTypedArray()) { _, which ->
                        executor.confirm(
                            itemId,
                            HomeEditIntent.CreateFolderAndAdd(itemId, pageOptions[which].screenId),
                        )
                    }
                    .show()
            }
        }

        private fun folderTitle(folderId: Int): CharSequence {
            val icon = mTarget.workspace?.getHomescreenIconByItemId(folderId)
            val info = (icon as? FolderIcon)?.mInfo
            return info?.title?.takeIf { it.isNotEmpty() }
                ?: mTarget.getString(R.string.homeedit_folder_default_label)
        }
    }

    class RemoveFromHome(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : EditAction(
        target,
        itemInfo,
        originalView,
        com.android.launcher3.R.drawable.ic_remove_no_shadow,
        R.string.homeedit_menu_remove_from_home,
    ) {

        override val showsLockNote: Boolean get() = false

        override fun loadOptions() {
            // No options: the remove runs immediately (no confirmation dialog;
            // undo UI and window are owned by Issue #450). A locked item is
            // announced with the remove-specific lock note before deletion.
            Executors.THREAD_POOL_EXECUTOR.execute {
                val isLocked = isTargetLocked()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (isLocked) {
                        android.widget.Toast.makeText(
                            mTarget,
                            R.string.homeedit_lock_note_locked_remove,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    onSourceReady()
                }
            }
        }

        override fun showDialog() {
            executor.confirm(itemId, HomeEditIntent.Remove(itemId))
        }

        private fun isTargetLocked(): Boolean = try {
            val explanation = OrganizerLocks.get(mTarget).explain(
                ItemId(itemId.toString()),
                LockTargetState.LOCKED,
            )
            explanation is LockExplanation.Available && explanation.entry.stored == OrganizerLockState.LOCKED
        } catch (e: Exception) {
            false
        }
    }
}
