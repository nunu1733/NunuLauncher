/*
 * Issue #448: undo evidence recording. The builder is the same mapping the
 * executor's success callback uses; the undo body, lifetime, and UI are owned
 * by Issue #450 (spec 448 AC-9).
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeEditUndoLogTest {

    private val source = HomeEditItem(
        id = 100,
        container = HomeEditContainers.DESKTOP,
        screenId = 0,
        cellX = 1,
        cellY = 2,
        spanX = 1,
        spanY = 1,
        itemType = HomeEditItemTypes.APPLICATION,
        rank = 0,
        userSerial = 10L,
    )

    @Test
    fun `move evidence records old and new placement`() {
        val plan = HomeEditPlanner.plan(
            HomeEditSnapshot(4, 6, listOf(0, 1, 2), listOf(source)),
            HomeEditIntent.MoveToPage(source, 1),
        ) as HomeEditPlan.Success
        val evidence = buildUndoEvidence(
            HomeEditIntent.MoveToPage(source, 1),
            plan,
            oldContainer = HomeEditContainers.DESKTOP,
            oldScreenId = 0,
            oldCellX = 1,
            oldCellY = 2,
            oldSpanX = 1,
            oldSpanY = 1,
            oldRank = 0,
            createdFolderId = 0,
        )
        assertEquals(HomeEditActionKind.MOVE_TO_PAGE, evidence.action)
        assertEquals(100, evidence.itemId)
        assertEquals(HomeEditContainers.DESKTOP, evidence.oldContainer)
        assertEquals(0, evidence.oldScreenId)
        assertEquals(1, evidence.oldCellX)
        assertEquals(2, evidence.oldCellY)
        assertEquals(HomeEditContainers.DESKTOP, evidence.newContainer)
        assertEquals(1, evidence.newScreenId)
        assertEquals(0, evidence.newCellX)
        assertEquals(0, evidence.newCellY)
        assertNull(evidence.createdFolderId)
        assertNull(evidence.createdFolderScreenId)
        HomeEditUndoLog.record(evidence)
        assertEquals(evidence, HomeEditUndoLog.last())
    }

    @Test
    fun `create folder evidence records the child post state and the folder placement`() {
        val plan = HomeEditPlanner.plan(
            HomeEditSnapshot(4, 6, listOf(0, 1), listOf(source)),
            HomeEditIntent.CreateFolderAndAdd(source, 1),
        ) as HomeEditPlan.Success
        val create = plan as HomeEditPlan.CreateFolder
        val evidence = buildUndoEvidence(
            HomeEditIntent.CreateFolderAndAdd(source, 1),
            plan,
            oldContainer = HomeEditContainers.HOTSEAT,
            oldScreenId = 0,
            oldCellX = 3,
            oldCellY = 0,
            oldSpanX = 1,
            oldSpanY = 1,
            oldRank = 2,
            createdFolderId = 55,
        )
        assertEquals(HomeEditActionKind.CREATE_FOLDER_AND_ADD, evidence.action)
        // Child post state: inside the created folder at rank 0.
        assertEquals(55, evidence.newContainer)
        assertEquals(0, evidence.newScreenId)
        assertEquals(-1, evidence.newCellX)
        assertEquals(-1, evidence.newCellY)
        assertEquals(0, evidence.newRank)
        // Created folder reference and its own placement (AC-9).
        assertEquals(55, evidence.createdFolderId)
        assertEquals(create.screenId, evidence.createdFolderScreenId)
        assertEquals(create.cellX, evidence.createdFolderCellX)
        assertEquals(create.cellY, evidence.createdFolderCellY)
        assertEquals(HomeEditContainers.HOTSEAT, evidence.oldContainer)
        assertEquals(3, evidence.oldCellX)
    }

    @Test
    fun `remove evidence keeps the pre-delete placement`() {
        val plan = HomeEditPlanner.plan(
            HomeEditSnapshot(4, 6, listOf(0), listOf(source)),
            HomeEditIntent.Remove(source),
        ) as HomeEditPlan.Success
        val evidence = buildUndoEvidence(
            HomeEditIntent.Remove(source),
            plan,
            oldContainer = HomeEditContainers.DESKTOP,
            oldScreenId = 0,
            oldCellX = 1,
            oldCellY = 2,
            oldSpanX = 1,
            oldSpanY = 1,
            oldRank = 0,
            createdFolderId = 0,
        )
        assertEquals(HomeEditActionKind.REMOVE, evidence.action)
        assertEquals(HomeEditContainers.DESKTOP, evidence.oldContainer)
        assertEquals(0, evidence.oldScreenId)
        assertNull(evidence.createdFolderId)
    }
}
