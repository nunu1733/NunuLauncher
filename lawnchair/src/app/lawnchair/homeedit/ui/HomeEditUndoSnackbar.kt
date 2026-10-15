/*
 * Issue #450: the undo snackbar — the only undo entry in the first version
 * (spec 450 Scope「UI」). Same mechanism as the upstream remove snackbar
 * (Snackbar.show, accessibility-aware timeout, single display): the action
 * closes over the record token observed at display time, so a snackbar that
 * was already replaced by a newer edit can never undo that newer edit.
 */
package app.lawnchair.homeedit.ui

import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.HomeEditUndoExecutor
import app.lawnchair.homeedit.HomeEditUndoToken
import com.android.launcher3.R
import com.android.launcher3.views.Snackbar

object HomeEditUndoSnackbar {

    /** Shows the undo snackbar for [token] on [launcher]. */
    fun show(launcher: LawnchairLauncher, token: HomeEditUndoToken) {
        val executor = HomeEditUndoExecutor(launcher)
        Snackbar.show(
            launcher,
            R.string.homeedit_undo_label,
            R.string.undo,
            null,
        ) { executor.start(token) }
    }
}
