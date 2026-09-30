/*
 * Issue #450: the undo record (single slot, generation-bound compare-and-consume)
 * and the evidence builder carried over from #448 (spec 448 AC-9, spec 450
 * AC-6/AC-11). Pure JVM.
 */
package app.lawnchair.homeedit

import com.android.launcher3.model.DirectEditContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEditUndoRecordTest {

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

    private fun payload(itemId: Int = 100): DirectEditContract.UndoRowPayload = DirectEditContract.UndoRowPayload(
        itemId, HomeEditItemTypes.APPLICATION, HomeEditContainers.DESKTOP, 0,
        1, 2, 1, 1, 0, 10L,
        "intent", "title", 0, 1,
        "com.example.app/.MainActivity", null, null,
    )

    private fun directEntry(itemId: Int = 100): HomeEditUndoEntry.DirectEdit = HomeEditUndoEntry.DirectEdit(
        HomeEditUndoEvidence(
            action = HomeEditActionKind.MOVE_TO_PAGE,
            itemId = itemId,
            oldContainer = HomeEditContainers.DESKTOP,
            oldScreenId = 0,
            oldCellX = 1,
            oldCellY = 2,
            oldSpanX = 1,
            oldSpanY = 1,
            oldRank = 0,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
            newRank = 0,
            createdFolderId = null,
            createdFolderScreenId = null,
            createdFolderCellX = null,
            createdFolderCellY = null,
        ),
        removedRow = null,
    )

    // --- evidence builder (#448, ported) ---

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

    // --- record lifetime and generation binding (AC-6 / AC-11) ---

    @Test
    fun `record replaces the previous entry and tokens are strictly increasing`() {
        val tokenA = HomeEditUndoRecord.record(directEntry(100))
        val tokenB = HomeEditUndoRecord.record(
            HomeEditUndoEntry.EditSession(
                TestHex.point(1),
                TestHex.revision(1),
            ),
        )
        assertTrue(tokenB.generation > tokenA.generation)
        // Stale snackbar tap (A) after replacement: zero-write no-op.
        assertNull(HomeEditUndoRecord.compareAndConsume(tokenA))
        // The current record survives the mismatched consume.
        val consumed = HomeEditUndoRecord.compareAndConsume(tokenB)
        assertTrue(consumed is HomeEditUndoEntry.EditSession)
        // Consumption empties the slot: redo is impossible (AC-6).
        assertNull(HomeEditUndoRecord.compareAndConsume(tokenB))
    }

    @Test
    fun `mismatched consume leaves the current record consumable`() {
        val tokenA = HomeEditUndoRecord.record(directEntry(100))
        val tokenB = HomeEditUndoRecord.record(directEntry(101))
        assertNull(HomeEditUndoRecord.compareAndConsume(tokenA))
        val consumed = HomeEditUndoRecord.compareAndConsume(tokenB)
        assertNotNull(consumed)
        val entry = consumed as HomeEditUndoEntry.DirectEdit
        assertEquals(101, entry.evidence.itemId)
    }

    @Test
    fun `direct entry carries the remove payload for the inverse INSERT`() {
        val token = HomeEditUndoRecord.record(
            HomeEditUndoEntry.DirectEdit(
                HomeEditUndoEvidence(
                    action = HomeEditActionKind.REMOVE,
                    itemId = 100,
                    oldContainer = HomeEditContainers.DESKTOP,
                    oldScreenId = 0,
                    oldCellX = 1,
                    oldCellY = 2,
                    oldSpanX = 1,
                    oldSpanY = 1,
                    oldRank = 0,
                    newContainer = HomeEditContainers.DESKTOP,
                    newScreenId = 0,
                    newCellX = 1,
                    newCellY = 2,
                    newRank = 0,
                    createdFolderId = null,
                    createdFolderScreenId = null,
                    createdFolderCellX = null,
                    createdFolderCellY = null,
                ),
                removedRow = payload(),
            ),
        )
        val consumed = HomeEditUndoRecord.compareAndConsume(token) as HomeEditUndoEntry.DirectEdit
        val removedRow = consumed.removedRow!!
        assertEquals(100, removedRow.itemId)
        assertEquals(HomeEditItemTypes.APPLICATION, removedRow.itemType)
        assertEquals(HomeEditContainers.DESKTOP, removedRow.container)
        assertEquals(0, removedRow.screenId)
        assertEquals(1, removedRow.cellX)
        assertEquals(2, removedRow.cellY)
        assertEquals(1, removedRow.organizerLockState)
        assertEquals("com.example.app/.MainActivity", removedRow.componentName)
    }
}

/** 32-hex fixtures for the organizer public value classes. */
object TestHex {
    private const val HEX = "0123456789abcdef"
    private const val DIGITS = "1234567890abcdef"

    fun point(n: Int): app.lawnchair.organizer.application.public.RecoveryPointId = app.lawnchair.organizer.application.public.RecoveryPointId(variant(n))

    fun revision(n: Int): app.lawnchair.organizer.planning.RevisionId = app.lawnchair.organizer.planning.RevisionId(variant(n + 7))

    private fun variant(n: Int): String {
        val chars = HEX.repeat(2).toCharArray()
        chars[n % 32] = DIGITS[n % 16]
        return String(chars)
    }
}
