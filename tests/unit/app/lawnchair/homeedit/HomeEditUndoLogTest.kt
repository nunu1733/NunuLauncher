/*
 * Issue #448: undo evidence recording. Only the information set is verified
 * here; the undo body, lifetime, and UI are owned by Issue #450 (spec 448
 * AC-9).
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeEditUndoLogTest {

    @Test
    fun `records the latest evidence`() {
        assertNull(HomeEditUndoLog.last())
        val evidence = HomeEditUndoEvidence(
            action = HomeEditActionKind.MOVE_TO_PAGE,
            itemId = 100,
            oldContainer = -100,
            oldScreenId = 0,
            oldCellX = 1,
            oldCellY = 2,
            oldSpanX = 1,
            oldSpanY = 1,
            oldRank = 0,
            newContainer = -100,
            newScreenId = 2,
            newCellX = 0,
            newCellY = 0,
            newRank = 0,
            createdFolderId = null,
        )
        HomeEditUndoLog.record(evidence)
        assertEquals(evidence, HomeEditUndoLog.last())

        val next = evidence.copy(action = HomeEditActionKind.REMOVE)
        HomeEditUndoLog.record(next)
        assertEquals(next, HomeEditUndoLog.last())
    }

    @Test
    fun `folder creation evidence keeps the created folder reference`() {
        val evidence = HomeEditUndoEvidence(
            action = HomeEditActionKind.CREATE_FOLDER_AND_ADD,
            itemId = 100,
            oldContainer = -101,
            oldScreenId = 0,
            oldCellX = 2,
            oldCellY = 0,
            oldSpanX = 1,
            oldSpanY = 1,
            oldRank = 3,
            newContainer = 55,
            newScreenId = 0,
            newCellX = -1,
            newCellY = -1,
            newRank = 0,
            createdFolderId = 55,
        )
        HomeEditUndoLog.record(evidence)
        assertEquals(55, HomeEditUndoLog.last()?.createdFolderId)
    }
}
