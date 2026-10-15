/*
 * Issue #449: fixture and boundary tests for the pure session planner, through
 * the public seams (EditSurfaceSessionPlanner.plan / confirmGate). All-or-
 * nothing, visual-order determinism, the touched guard, and the #448 shared
 * planner's placement results are the protected contracts (AGENTS.md test
 * rules).
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceSessionPlannerTest {

    private val capture = editSurfaceCapture(
        listOf(
            canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
            canonicalSurfaceItem(2, placement = onSurfaceWorkspace(0, 1, 0)),
            canonicalSurfaceItem(3, placement = onSurfaceWorkspace(0, 0, 1)),
            canonicalSurfaceItem(4, placement = onSurfaceWorkspace(1, 0, 0)),
            canonicalSurfaceItem(5, CanonicalItemKind.Folder, onSurfaceWorkspace(0, 3, 0)),
            canonicalSurfaceItem(6, placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("5")), 0)),
        ),
    )

    // --- move to page ---

    @Test
    fun `move to page plans every item deterministically in visual order`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(4, 1, 2),
            PendingSessionAction.MoveToPage(2),
        )
        val session = (result as SessionPlanResult.Applied).session
        // Visual order on page 0: (0,0)=1, (1,0)=2 — then 4 from page 1.
        assertEquals(
            listOf(
                SessionItemChange.PageMove(1, 2, GridCell(0, 0)),
                SessionItemChange.PageMove(2, 2, GridCell(1, 0)),
                SessionItemChange.PageMove(4, 2, GridCell(2, 0)),
            ),
            session.changes,
        )
        // Deterministic: the selection order does not matter.
        assertEquals(
            result,
            EditSurfaceSessionPlanner.plan(capture, emptyMap(), EditSurfaceSession.EMPTY, listOf(1, 2, 4), PendingSessionAction.MoveToPage(2)),
        )
    }

    @Test
    fun `move to page is all or nothing on missing space`() {
        val fullPage = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(2, placement = onSurfaceWorkspace(0, 1, 0)),
            ) + (0 until 24).map { i ->
                canonicalSurfaceItem(i + 10, placement = onSurfaceWorkspace(2, x = i % 4, y = i / 4))
            },
        )
        val result = EditSurfaceSessionPlanner.plan(
            fullPage,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(1, 2),
            PendingSessionAction.MoveToPage(2),
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.NO_SPACE), result)
    }

    // --- add to existing folder ---

    @Test
    fun `add to folder appends in visual order after existing members`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(2, 1),
            PendingSessionAction.AddToFolder(5),
        )
        val session = (result as SessionPlanResult.Applied).session
        // Visual order of {1 (0,0), 2 (1,0)} is 1 then 2 regardless of the tap
        // order; the folder ranks follow it after the existing member.
        assertEquals(
            listOf(
                SessionItemChange.FolderAdd(1, 5, 1),
                SessionItemChange.FolderAdd(2, 5, 2),
            ),
            session.changes,
        )
    }

    @Test
    fun `missing folder is rejected`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(1),
            PendingSessionAction.AddToFolder(999),
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.FOLDER_GONE), result)
    }

    // --- create folder ---

    @Test
    fun `new folder sits at the first selected item cell with ranks in visual order`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(3, 2, 1),
            PendingSessionAction.CreateFolder,
        )
        val session = (result as SessionPlanResult.Applied).session
        // Visual order of {1,2,3} is 1, 2, 3; the folder goes to item 1's cell.
        assertEquals(1, session.newFolders.size)
        val folder = session.newFolders.single()
        assertEquals(0, folder.ordinal)
        assertEquals(0, folder.screenId)
        assertEquals(0, folder.cellX)
        assertEquals(0, folder.cellY)
        assertEquals(listOf(1, 2, 3), folder.memberIds)
        assertEquals(
            listOf(
                SessionItemChange.FolderAdd(1, editSurfaceNewFolderKey(0), 0),
                SessionItemChange.FolderAdd(2, editSurfaceNewFolderKey(0), 1),
                SessionItemChange.FolderAdd(3, editSurfaceNewFolderKey(0), 2),
            ),
            session.changes,
        )
        // The working projection shows the folder row and its members inside.
        val working = EditSurfaceProjection.workingSnapshot(capture, session)
        assertEquals(HomeEditItemTypes.FOLDER, working.itemById(editSurfaceNewFolderKey(0))!!.itemType)
        assertEquals(editSurfaceNewFolderKey(0), working.itemById(1)!!.container)
    }

    @Test
    fun `a second new folder gets the next ordinal and its own cell`() {
        val first = (
            EditSurfaceSessionPlanner.plan(
                capture,
                emptyMap(),
                EditSurfaceSession.EMPTY,
                listOf(1, 2),
                PendingSessionAction.CreateFolder,
            ) as SessionPlanResult.Applied
            ).session
        val second = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            first,
            listOf(3, 4),
            PendingSessionAction.CreateFolder,
        )
        val session = (second as SessionPlanResult.Applied).session
        assertEquals(2, session.newFolders.size)
        assertEquals(1, session.newFolders[1].ordinal)
        // Item 3 lives on page 0, so the second folder's cell is item 3's cell.
        assertEquals(0, session.newFolders[1].screenId)
        assertEquals(0, session.newFolders[1].cellX)
        assertEquals(1, session.newFolders[1].cellY)
        assertEquals(listOf(3, 4), session.newFolders[1].memberIds)
    }

    // --- remove ---

    @Test
    fun `remove plans a removal change for every selected item`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(2, 1),
            PendingSessionAction.RemoveFromHome,
        )
        val session = (result as SessionPlanResult.Applied).session
        assertEquals(
            listOf(SessionItemChange.Removal(1), SessionItemChange.Removal(2)),
            session.changes,
        )
    }

    // --- guards ---

    @Test
    fun `touched items cannot be acted on again`() {
        val first = (
            EditSurfaceSessionPlanner.plan(
                capture,
                emptyMap(),
                EditSurfaceSession.EMPTY,
                listOf(1, 2),
                PendingSessionAction.RemoveFromHome,
            ) as SessionPlanResult.Applied
            ).session
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            first,
            listOf(1, 3),
            PendingSessionAction.RemoveFromHome,
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED), result)
    }

    @Test
    fun `empty selection is rejected`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            emptyList(),
            PendingSessionAction.RemoveFromHome,
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED), result)
    }

    @Test
    fun `a folder row itself is not a selection target`() {
        val result = EditSurfaceSessionPlanner.plan(
            capture,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(5),
            PendingSessionAction.RemoveFromHome,
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED), result)
    }

    @Test
    fun `locked items cannot join an action`() {
        val locked = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0), lockState = OrganizerLockState.LOCKED),
            ),
        )
        val result = EditSurfaceSessionPlanner.plan(
            locked,
            mapOf(1 to OrganizerLockState.LOCKED),
            EditSurfaceSession.EMPTY,
            listOf(1),
            PendingSessionAction.RemoveFromHome,
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED), result)
    }

    @Test
    fun `profile mixing rejects the folder action as a whole`() {
        val mixed = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(
                    2,
                    placement = onSurfaceWorkspace(0, 1, 0),
                    profile = ProfileId("11"),
                ),
                canonicalSurfaceItem(5, CanonicalItemKind.Folder, onSurfaceWorkspace(0, 3, 0)),
            ),
        )
        val result = EditSurfaceSessionPlanner.plan(
            mixed,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(1, 2),
            PendingSessionAction.AddToFolder(5),
        )
        assertEquals(SessionPlanResult.Rejected(HomeEditRejection.PROFILE_MISMATCH), result)
    }

    // --- confirm gate ---

    @Test
    fun `confirm gate blocks an empty session`() {
        assertEquals(
            EditSurfaceSessionPlanner.ConfirmGate.EmptySession,
            EditSurfaceSessionPlanner.confirmGate(EditSurfaceSession.EMPTY, emptyList()),
        )
    }

    @Test
    fun `confirm gate blocks while an unknown lock row exists`() {
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(1)),
        )
        assertEquals(
            EditSurfaceSessionPlanner.ConfirmGate.LockStateUnknown,
            EditSurfaceSessionPlanner.confirmGate(session, listOf(OrganizerLockState.UNKNOWN)),
        )
        assertEquals(
            EditSurfaceSessionPlanner.ConfirmGate.Open,
            EditSurfaceSessionPlanner.confirmGate(session, listOf(OrganizerLockState.UNLOCKED, OrganizerLockState.LOCKED)),
        )
    }

    // --- composed session ---

    @Test
    fun `actions compose in execution order and the working projection reflects them`() {
        val first = (
            EditSurfaceSessionPlanner.plan(
                capture,
                emptyMap(),
                EditSurfaceSession.EMPTY,
                listOf(1, 2),
                PendingSessionAction.MoveToPage(2),
            ) as SessionPlanResult.Applied
            ).session
        val second = (
            EditSurfaceSessionPlanner.plan(
                capture,
                emptyMap(),
                first,
                listOf(3),
                PendingSessionAction.RemoveFromHome,
            ) as SessionPlanResult.Applied
            ).session
        // The second action appends; the first changes are retained in order.
        assertEquals(first.changes, second.changes.dropLast(1))
        assertTrue(second.touchedIds.containsAll(setOf(1, 2, 3)))
        val working = EditSurfaceProjection.workingSnapshot(capture, second)
        assertEquals(2, working.itemById(1)!!.screenId)
        assertTrue(working.items.none { it.id == 3 })
    }
}
