package app.lawnchair.ui.popup

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockAuthoringModule
import app.lawnchair.organizer.locks.LockChangeResult
import app.lawnchair.organizer.locks.LockExplanation
import app.lawnchair.organizer.locks.LockStateChangeRequest
import app.lawnchair.organizer.locks.LockTargetState
import app.lawnchair.organizer.locks.OrganizerLocks
import app.lawnchair.organizer.locks.UserReviewedIntent
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.ui.LockMessages
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.util.Executors

/**
 * Issue #38: "Placement lock" entry in the item long-press popup for
 * shortcut-capable rows (application and deep shortcut items on the workspace
 * and the non-taskbar hotseat). The entry opens a state-aware confirmation
 * dialog; only its buttons supply [UserReviewedIntent]. Folder, widget,
 * Dock-under-taskbar, and app-pair rows are authored from the preferences
 * management screen.
 */
class OrganizerLockShortcut {

    companion object {
        val PLACEMENT_LOCK =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
                if (itemInfo.itemType != ITEM_TYPE_APPLICATION &&
                    itemInfo.itemType != ITEM_TYPE_DEEP_SHORTCUT
                ) {
                    null
                } else if (itemInfo.id == ItemInfo.NO_ID) {
                    null
                } else {
                    PlacementLock(activity, itemInfo, originalView)
                }
            }
    }

    class PlacementLock(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(
        R.drawable.ic_lock,
        R.string.organizer_lock_menu_title,
        target,
        itemInfo,
        originalView,
    ) {

        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onClick(view: View) {
            AbstractFloatingView.closeAllOpenViews(mTarget)
            val itemId = ItemId(mItemInfo.id.toString())
            val module = OrganizerLocks.get(mTarget)
            Executors.THREAD_POOL_EXECUTOR.execute {
                val explanation = module.explain(itemId, LockTargetState.LOCKED)
                mainHandler.post { showDialog(module, explanation) }
            }
        }

        private fun showDialog(module: LockAuthoringModule, explanation: LockExplanation) {
            val context = mTarget
            when (explanation) {
                is LockExplanation.Unavailable -> Toast.makeText(
                    context,
                    LockMessages.rejection(explanation.reason),
                    Toast.LENGTH_LONG,
                ).show()

                is LockExplanation.Available -> {
                    val entry = explanation.entry
                    val stateText = context.getString(LockMessages.stateLabel(entry.stored))
                    val scopeText = context.getString(LockMessages.scopeDescription(entry.scope))
                    val effectLines = explanation.notes.joinToString("\n") {
                        context.getString(LockMessages.effectNote(it))
                    }
                    val message = buildString {
                        append(context.getString(R.string.organizer_lock_dialog_current_state, stateText))
                        append("\n\n")
                        append(scopeText)
                        if (effectLines.isNotEmpty()) {
                            append("\n\n")
                            append(effectLines)
                        }
                    }
                    when (entry.stored) {
                        OrganizerLockState.LOCKED -> {
                            confirmDialog(
                                module,
                                LockTargetState.UNLOCKED,
                                title = R.string.organizer_lock_dialog_title_unlock,
                                message = message,
                                confirmLabel = R.string.organizer_lock_action_unlock,
                            )
                        }

                        OrganizerLockState.UNLOCKED -> confirmDialog(
                            module,
                            LockTargetState.LOCKED,
                            title = R.string.organizer_lock_dialog_title_lock,
                            message = message,
                            confirmLabel = R.string.organizer_lock_action_lock,
                        )

                        OrganizerLockState.UNKNOWN ->
                            reviewDialog(module, message)
                    }
                }
            }
        }

        private fun confirmDialog(
            module: LockAuthoringModule,
            target: LockTargetState,
            title: Int,
            message: String,
            confirmLabel: Int,
        ) {
            val itemId = ItemId(mItemInfo.id.toString())
            AlertDialog.Builder(mTarget)
                .setIcon(R.drawable.ic_lock)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(confirmLabel) { _, _ ->
                    applyChange(module, itemId, target, UserReviewedIntent("popup_confirm"))
                }
                .show()
        }

        private fun reviewDialog(module: LockAuthoringModule, message: String) {
            val itemId = ItemId(mItemInfo.id.toString())
            val reviewIntro = mTarget.getString(R.string.organizer_lock_dialog_review_intro)
            AlertDialog.Builder(mTarget)
                .setIcon(R.drawable.ic_lock)
                .setTitle(R.string.organizer_lock_dialog_title_review)
                .setMessage("$reviewIntro\n\n$message")
                .setPositiveButton(R.string.organizer_lock_action_keep_locked) { _, _ ->
                    applyChange(module, itemId, LockTargetState.LOCKED, UserReviewedIntent("popup_review_keep_locked"))
                }
                .setNegativeButton(R.string.organizer_lock_action_mark_unlocked) { _, _ ->
                    applyChange(module, itemId, LockTargetState.UNLOCKED, UserReviewedIntent("popup_review_mark_unlocked"))
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }

        private fun applyChange(
            module: LockAuthoringModule,
            itemId: ItemId,
            target: LockTargetState,
            intent: UserReviewedIntent,
        ) {
            Executors.THREAD_POOL_EXECUTOR.execute {
                val result = module.setLock(LockStateChangeRequest(itemId, target, intent))
                mainHandler.post { reportResult(result, target) }
            }
        }

        private fun reportResult(result: LockChangeResult, target: LockTargetState) {
            val context = mTarget
            val message = when (result) {
                is LockChangeResult.Changed -> context.getString(
                    if (target == LockTargetState.LOCKED) {
                        R.string.organizer_lock_result_locked
                    } else {
                        R.string.organizer_lock_result_unlocked
                    },
                )

                LockChangeResult.NoChange -> context.getString(R.string.organizer_lock_result_no_change)

                is LockChangeResult.Rejected -> context.getString(LockMessages.rejection(result.reason))

                is LockChangeResult.Failed -> context.getString(R.string.organizer_lock_error_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
