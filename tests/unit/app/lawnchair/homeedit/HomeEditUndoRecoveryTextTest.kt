/*
 * Issue #450: recovery-result → undo failure text mapper oracle (AC-5).
 * Restored maps to no toast; every typed rejection maps to its resource;
 * uncertain outcomes stay fail-closed. Pure JVM.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryResult
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeEditUndoRecoveryTextTest {

    private fun pointId() = RecoveryPointId(TestHex.point(3).value)

    @Test
    fun `restored shows nothing`() {
        assertEquals(null, homeEditUndoRecoveryText(RecoveryResult.Restored(pointId())))
    }

    @Test
    fun `stale revision maps to its own text`() {
        val text = homeEditUndoRecoveryText(
            RecoveryResult.NotRestorable(pointId(), RecoveryRejection.STALE_REVISION),
        )
        assertEquals(R.string.homeedit_undo_error_stale_revision, text)
    }

    @Test
    fun `missing expired and already restored map to not restorable`() {
        for (reason in listOf(
            RecoveryRejection.MISSING,
            RecoveryRejection.EXPIRED,
            RecoveryRejection.ALREADY_RESTORED,
            RecoveryRejection.CORRUPT,
            RecoveryRejection.INCOMPATIBLE_VERSION,
        )) {
            assertEquals(
                "reason=$reason",
                R.string.homeedit_undo_error_not_restorable,
                homeEditUndoRecoveryText(RecoveryResult.NotRestorable(pointId(), reason)),
            )
        }
    }

    @Test
    fun `busy and concurrent run map to busy`() {
        assertEquals(
            R.string.homeedit_undo_error_busy,
            homeEditUndoRecoveryText(RecoveryResult.WriterBusy),
        )
        assertEquals(
            R.string.homeedit_undo_error_busy,
            homeEditUndoRecoveryText(RecoveryResult.ConcurrentRun),
        )
    }

    @Test
    fun `restore failed stays fail-closed without asserting the layout state`() {
        assertEquals(
            R.string.homeedit_undo_error_restore_failed,
            homeEditUndoRecoveryText(
                RecoveryResult.RestoreFailed(
                    pointId(),
                    RecoveryFailure.WRITE_FAILED,
                    AuthoritativeState.UNKNOWN,
                ),
            ),
        )
    }
}
