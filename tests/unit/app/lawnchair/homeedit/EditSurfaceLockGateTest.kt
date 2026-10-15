/*
 * Issue #449: JVM oracle for the AC-7 typed rejection contract. The
 * confirm gate and the apply-result reason mapping are the observable
 * behavior the review round 2 asked to pin: a session whose capture carries
 * an UNKNOWN lock row can never confirm (zero-write), and the
 * LOCK_STATE_UNAVAILABLE pre-write rejection maps to the dedicated
 * lock-unknown reason (not the generic error) with the session kept.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PreWriteRejection
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceLockGateTest {

    // --- confirm gate (session-start UNKNOWN) ---

    @Test
    fun `confirm gate blocks while the capture holds an unknown lock row`() {
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(1)),
        )
        assertEquals(
            EditSurfaceSessionPlanner.ConfirmGate.LockStateUnknown,
            EditSurfaceSessionPlanner.confirmGate(session, listOf(OrganizerLockState.UNKNOWN)),
        )
        // A single UNKNOWN anywhere in the capture is enough (existing
        // LOCK_STATE_UNAVAILABLE contract rejects the whole apply).
        assertEquals(
            EditSurfaceSessionPlanner.ConfirmGate.LockStateUnknown,
            EditSurfaceSessionPlanner.confirmGate(
                session,
                listOf(OrganizerLockState.UNLOCKED, OrganizerLockState.LOCKED, OrganizerLockState.UNKNOWN),
            ),
        )
        assertEquals(
            EditSurfaceSessionPlanner.ConfirmGate.Open,
            EditSurfaceSessionPlanner.confirmGate(session, listOf(OrganizerLockState.UNLOCKED, OrganizerLockState.LOCKED)),
        )
    }

    @Test
    fun `planner rejects an action on a lock-unknown row before planning`() {
        val capture = editSurfaceCapture(
            listOf(canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0))),
        )
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            mapOf(1 to OrganizerLockState.UNKNOWN),
            EditSurfaceSession.EMPTY,
            listOf(1),
            PendingSessionAction.RemoveFromHome,
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED), result)
    }

    // --- apply-result reason mapping (LOCK_STATE_UNAVAILABLE) ---

    @Test
    fun `lock state unavailable maps to the dedicated lock unknown reason`() {
        // The pure mapping cannot silently fall back to the generic error;
        // the session-keeping behavior lives in the result handler, which
        // never clears the session on a zero-write rejection.
        assertEquals(
            R.string.edit_surface_error_lock_unknown,
            app.lawnchair.homeedit.ui.editSurfaceRejectionText(PreWriteRejection.LOCK_STATE_UNAVAILABLE),
        )
        assertTrue(
            app.lawnchair.homeedit.ui.editSurfaceRejectionText(PreWriteRejection.LOCK_STATE_UNAVAILABLE) !=
                app.lawnchair.homeedit.ui.editSurfaceRejectionText(PreWriteRejection.INVALID_PLAN),
        )
    }
}
