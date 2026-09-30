/*
 * Issue #450: pure mapping of an organizer recovery result to the undo
 * failure text (spec 450 "Failure and rejection vocabulary": typed, resource
 * derived, never a raw exception or internal id). JVM-tested as the oracle;
 * `null` means success — the recovery path's correlated reload refreshes the
 * home, no toast is shown.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryResult
import com.android.launcher3.R

fun homeEditUndoRecoveryText(result: RecoveryResult): Int? = when (result) {
    is RecoveryResult.Restored -> null

    is RecoveryResult.NotRestorable -> when (result.reason) {
        RecoveryRejection.STALE_REVISION -> R.string.homeedit_undo_error_stale_revision

        RecoveryRejection.MISSING,
        RecoveryRejection.EXPIRED,
        RecoveryRejection.ALREADY_RESTORED,
        RecoveryRejection.CORRUPT,
        RecoveryRejection.INCOMPATIBLE_VERSION,
        -> R.string.homeedit_undo_error_not_restorable

        RecoveryRejection.LOCK_STATE_UNAVAILABLE -> R.string.homeedit_undo_error_restore_failed
    }

    // Uncertain outcomes stay fail-closed (spec 449 AC-16 vocabulary): the
    // text never asserts "home unchanged"; the recovery entry guides the user.
    is RecoveryResult.RestoreFailed -> R.string.homeedit_undo_error_restore_failed

    RecoveryResult.WriterBusy -> R.string.homeedit_undo_error_busy

    RecoveryResult.ConcurrentRun -> R.string.homeedit_undo_error_busy
}
