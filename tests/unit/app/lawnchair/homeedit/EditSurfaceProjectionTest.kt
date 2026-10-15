/*
 * Issue #449: fixture and boundary tests for the pure edit-surface projection,
 * through the public seams (EditSurfaceProjection.homeEditSnapshot /
 * workingSnapshot / diagram / visualOrder).
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.SplitStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceProjectionTest {

    // --- homeEditSnapshot: organizer capture → #448 snapshot ---

    @Test
    fun `workspace placement maps to desktop row`() {
        val snapshot = editSurfaceCapture(listOf(canonicalSurfaceItem(1, placement = onSurfaceWorkspace(2, 1, 3))))
        assertEquals(listOf(0, 1, 2), snapshot.screenIds)
        val row = snapshot.itemById(1)!!
        assertEquals(HomeEditContainers.DESKTOP, row.container)
        assertEquals(2, row.screenId)
        assertEquals(1, row.cellX)
        assertEquals(3, row.cellY)
        assertEquals(SERIAL_A, row.userSerial)
    }

    @Test
    fun `dock placement maps to hotseat row with rank as slot`() {
        val snapshot = editSurfaceCapture(
            listOf(canonicalSurfaceItem(2, placement = PlacementState.Dock(rank = 3))),
        )
        val row = snapshot.itemById(2)!!
        assertEquals(HomeEditContainers.HOTSEAT, row.container)
        assertEquals(3, row.screenId)
        assertEquals(3, row.rank)
    }

    @Test
    fun `folder child placement maps to the parent container and rank`() {
        val snapshot = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(50, CanonicalItemKind.Folder, onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(
                    51,
                    placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("50")), 4),
                ),
            ),
        )
        val row = snapshot.itemById(51)!!
        assertEquals(50, row.container)
        assertEquals(4, row.rank)
    }

    @Test
    fun `reserved regions join the snapshot as reserved synthetic rows`() {
        val snapshot = editSurfaceCapture(
            items = emptyList(),
            reservations = listOf(editSurfaceReservation(page = 0, x = 0, y = 0, spanX = 4, spanY = 1)),
        )
        val reserved = snapshot.items.single { it.itemType == HomeEditItemTypes.RESERVED }
        assertEquals(editSurfaceReservationKey(0), reserved.id)
        assertEquals(HomeEditContainers.DESKTOP, reserved.container)
        assertEquals(0, reserved.screenId)
        assertEquals(4, reserved.spanX)
        // The shared planner must not place into the reserved cells: the
        // reservation occupies (0,0)..(3,0), so the first free cell is (0,1).
        val target = item(id = 100, screenId = 1, cellX = 0, cellY = 0)
        val withTarget = snapshot.copy(items = snapshot.items + target)
        val plan = HomeEditPlanner.plan(withTarget, HomeEditIntent.MoveToPage(target, 0))
        assertEquals(HomeEditPlan.Move(target, HomeEditContainers.DESKTOP, 0, 0, 1, 0), plan)
    }

    @Test
    fun `a non-persistent ref fails closed`() {
        val planned = canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0))
            .copy(ref = ApplicationItemRef.PlannedCandidate(app.lawnchair.organizer.planning.ItemId("1")))
        assertThrows(IllegalArgumentException::class.java) {
            EditSurfaceProjection.homeEditSnapshot(editSurfaceLayout(listOf(planned)))
        }
    }

    // --- workingSnapshot: session application onto the capture ---

    @Test
    fun `empty session returns the capture unchanged`() {
        val capture = editSurfaceCapture(listOf(canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0))))
        assertEquals(capture, EditSurfaceProjection.workingSnapshot(capture, EditSurfaceSession.EMPTY))
    }

    @Test
    fun `page move updates the geometry of the target only`() {
        val capture = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(2, placement = onSurfaceWorkspace(0, 1, 0)),
            ),
        )
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.PageMove(1, screenId = 2, cell = GridCell(3, 5))),
        )
        val working = EditSurfaceProjection.workingSnapshot(capture, session)
        assertEquals(2, working.itemById(1)!!.screenId)
        assertEquals(3, working.itemById(1)!!.cellX)
        assertEquals(5, working.itemById(1)!!.cellY)
        assertEquals(0, working.itemById(2)!!.screenId)
        // Idempotent: applying the same session again yields the same result.
        assertEquals(working, EditSurfaceProjection.workingSnapshot(capture, session))
    }

    @Test
    fun `new folder rows appear with the folder user serial`() {
        val capture = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(2, placement = onSurfaceWorkspace(1, 0, 0)),
            ),
        )
        val session = EditSurfaceSession(
            newFolders = listOf(
                SessionNewFolder(ordinal = 0, screenId = 0, cellX = 0, cellY = 0, memberIds = listOf(1, 2), userSerial = SERIAL_A),
            ),
            changes = listOf(
                SessionItemChange.FolderAdd(1, editSurfaceNewFolderKey(0), 0),
                SessionItemChange.FolderAdd(2, editSurfaceNewFolderKey(0), 1),
            ),
        )
        val working = EditSurfaceProjection.workingSnapshot(capture, session)
        val folderRow = working.itemById(editSurfaceNewFolderKey(0))!!
        assertEquals(HomeEditItemTypes.FOLDER, folderRow.itemType)
        assertEquals(HomeEditContainers.DESKTOP, folderRow.container)
        assertEquals(0, folderRow.screenId)
        assertEquals(SERIAL_A, folderRow.userSerial)
        assertEquals(editSurfaceNewFolderKey(0), working.itemById(1)!!.container)
        assertEquals(1, working.itemById(2)!!.rank)
        assertFalse(working.items.any { it.id == 1 && it.container == HomeEditContainers.DESKTOP })
    }

    @Test
    fun `removal drops the row from the working projection`() {
        val capture = editSurfaceCapture(listOf(canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0))))
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(1)),
        )
        assertTrue(EditSurfaceProjection.workingSnapshot(capture, session).items.isEmpty())
    }

    @Test
    fun `a change targeting a missing item fails closed`() {
        val capture = editSurfaceCapture(listOf(canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0))))
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(999)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            EditSurfaceProjection.workingSnapshot(capture, session)
        }
    }

    // --- diagram: display projection ---

    @Test
    fun `diagram excludes reserved rows and removed items and carries capture metadata`() {
        val layoutState = editSurfaceLayout(
            listOf(
                canonicalSurfaceItem(
                    1,
                    placement = onSurfaceWorkspace(0, 0, 0),
                    title = OptionalText.Present("Alpha"),
                    lockState = OrganizerLockState.LOCKED,
                ),
                canonicalSurfaceItem(2, placement = onSurfaceWorkspace(0, 1, 0)),
            ),
            reservations = listOf(editSurfaceReservation(page = 0, x = 0, y = 0, spanX = 4, spanY = 1)),
        )
        val capture = EditSurfaceProjection.homeEditSnapshot(layoutState)
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(2)),
        )
        val diagram = EditSurfaceProjection.diagram(
            layoutState,
            EditSurfaceProjection.workingSnapshot(capture, session),
        )
        val ids = diagram.items.map { it.id }
        assertTrue(1 in ids)
        assertFalse(2 in ids)
        assertFalse(ids.contains(editSurfaceReservationKey(0)))
        assertEquals(1, diagram.reservedRegions.size)
        val alpha = diagram.itemById.getValue(1)
        assertEquals("Alpha", alpha.label)
        assertNull(alpha.iconBytes)
        assertEquals(OrganizerLockState.LOCKED, alpha.lockState)
        assertEquals(SelectionEligibility.LOCKED, alpha.eligibility)
    }

    @Test
    fun `diagram marks session created folders and counts members`() {
        val layoutState = editSurfaceLayout(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(2, placement = onSurfaceWorkspace(1, 0, 0)),
                canonicalSurfaceItem(3, CanonicalItemKind.Folder, onSurfaceWorkspace(0, 2, 0)),
                canonicalSurfaceItem(4, placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("3")), 0)),
            ),
        )
        val capture = EditSurfaceProjection.homeEditSnapshot(layoutState)
        val session = EditSurfaceSession(
            newFolders = listOf(
                SessionNewFolder(ordinal = 0, screenId = 0, cellX = 0, cellY = 0, memberIds = listOf(1, 2), userSerial = SERIAL_A),
            ),
            changes = listOf(
                SessionItemChange.FolderAdd(1, editSurfaceNewFolderKey(0), 0),
                SessionItemChange.FolderAdd(2, editSurfaceNewFolderKey(0), 1),
            ),
        )
        val diagram = EditSurfaceProjection.diagram(
            layoutState,
            EditSurfaceProjection.workingSnapshot(capture, session),
        )
        val newFolder = diagram.itemById.getValue(editSurfaceNewFolderKey(0))
        assertTrue(newFolder.isSessionCreated)
        assertEquals(HomeEditItemTypes.FOLDER, newFolder.itemType)
        assertEquals(2, diagram.folderMemberCounts[editSurfaceNewFolderKey(0)])
        assertEquals(1, diagram.folderMemberCounts[3])
        // A session folder row itself is not selectable.
        assertEquals(SelectionEligibility.UNSUPPORTED, newFolder.eligibility)
    }

    // --- visual order ---

    @Test
    fun `visual order sorts by page row then column then id`() {
        val capture = editSurfaceCapture(
            listOf(
                canonicalSurfaceItem(30, placement = onSurfaceWorkspace(0, 1, 0)),
                canonicalSurfaceItem(20, placement = onSurfaceWorkspace(0, 0, 0)),
                canonicalSurfaceItem(40, placement = onSurfaceWorkspace(1, 0, 0)),
                canonicalSurfaceItem(10, placement = onSurfaceWorkspace(0, 0, 1)),
            ),
        )
        assertEquals(
            listOf(20, 30, 10, 40),
            EditSurfaceProjection.visualOrder(capture, setOf(40, 30, 20, 10)),
        )
    }

    @Test
    fun `eligibility rejects dock widgets folders app pairs and unknown locks`() {
        val layoutState = editSurfaceLayout(
            listOf(
                canonicalSurfaceItem(1, placement = PlacementState.Dock(0)),
                canonicalSurfaceItem(
                    2,
                    CanonicalItemKind.AppWidget,
                    onSurfaceWorkspace(0, 0, 0, spanX = 2, spanY = 2),
                ),
                canonicalSurfaceItem(3, CanonicalItemKind.Folder, onSurfaceWorkspace(0, 2, 0)),
                canonicalSurfaceItem(4, placement = onSurfaceWorkspace(0, 3, 0), lockState = OrganizerLockState.UNKNOWN),
                canonicalSurfaceItem(5, CanonicalItemKind.AppPair, onSurfaceWorkspace(1, 0, 0)),
                canonicalSurfaceItem(
                    6,
                    placement = PlacementState.AppPairChild(
                        ApplicationItemRef.PersistentItem(ItemId("5")),
                        SplitStage.BOTTOM_OR_RIGHT,
                    ),
                ),
                canonicalSurfaceItem(7, placement = onSurfaceWorkspace(0, 3, 1)),
            ),
        )
        val diagram = EditSurfaceProjection.diagram(
            layoutState,
            EditSurfaceProjection.homeEditSnapshot(layoutState),
        )
        val byId = diagram.itemById
        assertEquals(SelectionEligibility.UNSUPPORTED, byId.getValue(1).eligibility)
        assertEquals(SelectionEligibility.UNSUPPORTED, byId.getValue(2).eligibility)
        assertEquals(SelectionEligibility.UNSUPPORTED, byId.getValue(3).eligibility)
        assertEquals(SelectionEligibility.LOCK_UNKNOWN, byId.getValue(4).eligibility)
        assertEquals(SelectionEligibility.UNSUPPORTED, byId.getValue(5).eligibility)
        assertEquals(SelectionEligibility.UNSUPPORTED, byId.getValue(6).eligibility)
        assertEquals(SelectionEligibility.SELECTABLE, byId.getValue(7).eligibility)
    }
}
