/*
 * Issue #450: pure undo verification oracle (spec 450 AC-2/AC-7/AC-11). The
 * planner is a pure function; every typed rejection, the occupancy/grid
 * boundaries, the availability fail-closed rule, determinism, and the
 * stage-2 validator identity are exercised through the interface only.
 */
package app.lawnchair.homeedit

import com.android.launcher3.model.DirectEditContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEditUndoPlannerTest {

    private fun item(
        id: Int,
        container: Int = HomeEditContainers.DESKTOP,
        screenId: Int = 0,
        cellX: Int,
        cellY: Int,
        spanX: Int = 1,
        spanY: Int = 1,
        itemType: Int = HomeEditItemTypes.APPLICATION,
        rank: Int = 0,
        userSerial: Long = 10L,
    ) = HomeEditItem(id, container, screenId, cellX, cellY, spanX, spanY, itemType, rank, userSerial)

    private fun evidence(
        action: HomeEditActionKind,
        itemId: Int = 100,
        oldContainer: Int = HomeEditContainers.DESKTOP,
        oldScreenId: Int = 0,
        oldCellX: Int = 1,
        oldCellY: Int = 2,
        newContainer: Int,
        newScreenId: Int,
        newCellX: Int,
        newCellY: Int,
        newRank: Int = 0,
        createdFolderId: Int? = null,
        folderScreenId: Int? = null,
        folderCellX: Int? = null,
        folderCellY: Int? = null,
    ) = HomeEditUndoEvidence(
        action = action,
        itemId = itemId,
        oldContainer = oldContainer,
        oldScreenId = oldScreenId,
        oldCellX = oldCellX,
        oldCellY = oldCellY,
        oldSpanX = 1,
        oldSpanY = 1,
        oldRank = 0,
        newContainer = newContainer,
        newScreenId = newScreenId,
        newCellX = newCellX,
        newCellY = newCellY,
        newRank = newRank,
        createdFolderId = createdFolderId,
        createdFolderScreenId = folderScreenId,
        createdFolderCellX = folderCellX,
        createdFolderCellY = folderCellY,
    )

    private fun payload(
        container: Int = HomeEditContainers.DESKTOP,
        screenId: Int = 0,
        cellX: Int = 1,
        cellY: Int = 2,
        itemId: Int = 100,
    ) = DirectEditContract.UndoRowPayload(
        itemId, HomeEditItemTypes.APPLICATION, container, screenId,
        cellX, cellY, 1, 1, 0, 10L,
        "intent", "title", 0, 1,
        "com.example.app/.MainActivity", null, null,
    )

    private fun entry(
        evidence: HomeEditUndoEvidence,
        removedRow: DirectEditContract.UndoRowPayload? = null,
    ) = HomeEditUndoEntry.DirectEdit(evidence, removedRow)

    // --- move undo ---

    @Test
    fun `move undo restores when the item sits at the recorded result and the old cell is free`() {
        val moved = item(100, screenId = 1, cellX = 0, cellY = 0)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0, 1), listOf(moved))
        val e = evidence(
            HomeEditActionKind.MOVE_TO_PAGE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
        )
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(e), null)
        assertEquals(
            HomeEditUndoPlan.RestorePlacement(100, HomeEditContainers.DESKTOP, 0, 1, 2, 1, 1, 0),
            plan,
        )
    }

    @Test
    fun `move undo rejects stale when the item moved again`() {
        val movedAgain = item(100, screenId = 1, cellX = 2, cellY = 3)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0, 1), listOf(movedAgain))
        val e = evidence(
            HomeEditActionKind.MOVE_TO_PAGE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
        )
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(e), null)
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE), plan)
    }

    @Test
    fun `move undo rejects when the old cell is occupied`() {
        val moved = item(100, screenId = 1, cellX = 0, cellY = 0)
        val occupant = item(200, screenId = 0, cellX = 1, cellY = 2)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0, 1), listOf(moved, occupant))
        val e = evidence(
            HomeEditActionKind.MOVE_TO_PAGE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
        )
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(e), null)
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE), plan)
    }

    @Test
    fun `move undo rejects when the grid no longer contains the old cell`() {
        val moved = item(100, screenId = 1, cellX = 0, cellY = 0)
        val snapshot = HomeEditSnapshot(2, 2, listOf(0, 1), listOf(moved))
        val e = evidence(
            HomeEditActionKind.MOVE_TO_PAGE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
        )
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(e), null)
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE), plan)
    }

    // --- add-to-folder undo ---

    @Test
    fun `add to folder undo restores the old placement and keeps the folder`() {
        val folder = item(55, cellX = 2, cellY = 2, itemType = HomeEditItemTypes.FOLDER)
        val child = item(100, container = 55, screenId = 0, cellX = -1, cellY = -1, rank = 1)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), listOf(folder, child))
        val e = evidence(
            HomeEditActionKind.ADD_TO_FOLDER,
            oldContainer = HomeEditContainers.DESKTOP,
            newContainer = 55,
            newScreenId = 0,
            newCellX = -1,
            newCellY = -1,
            newRank = 1,
        )
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(e), null)
        assertEquals(
            HomeEditUndoPlan.RestorePlacement(100, HomeEditContainers.DESKTOP, 0, 1, 2, 1, 1, 0),
            plan,
        )
    }

    // --- create-folder undo ---

    private fun createFolderEntry() = evidence(
        HomeEditActionKind.CREATE_FOLDER_AND_ADD,
        oldContainer = HomeEditContainers.HOTSEAT,
        oldScreenId = 0,
        oldCellX = 3,
        oldCellY = 0,
        newContainer = 55,
        newScreenId = 0,
        newCellX = -1,
        newCellY = -1,
        newRank = 0,
        createdFolderId = 55,
        folderScreenId = 0,
        folderCellX = 1,
        folderCellY = 1,
    )

    @Test
    fun `create folder undo removes the folder when it is untouched`() {
        val folder = item(55, cellX = 1, cellY = 1, itemType = HomeEditItemTypes.FOLDER)
        val child = item(100, container = 55, screenId = 0, cellX = -1, cellY = -1, rank = 0)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), listOf(folder, child))
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(createFolderEntry()), null)
        assertEquals(
            HomeEditUndoPlan.UndoCreateFolder(100, 55, HomeEditContainers.HOTSEAT, 0, 3, 0, 1, 1, 0),
            plan,
        )
    }

    @Test
    fun `create folder undo rejects when another child joined the folder`() {
        val folder = item(55, cellX = 1, cellY = 1, itemType = HomeEditItemTypes.FOLDER)
        val child = item(100, container = 55, screenId = 0, cellX = -1, cellY = -1, rank = 0)
        val extraChild = item(200, container = 55, screenId = 0, cellX = -1, cellY = -1, rank = 1)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), listOf(folder, child, extraChild))
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(createFolderEntry()), null)
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.FOLDER_CHANGED), plan)
    }

    @Test
    fun `create folder undo rejects when the folder row moved or vanished`() {
        val movedFolder = item(55, cellX = 2, cellY = 3, itemType = HomeEditItemTypes.FOLDER)
        val child = item(100, container = 55, screenId = 0, cellX = -1, cellY = -1, rank = 0)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), listOf(movedFolder, child))
        val plan = HomeEditUndoPlanner.verify(snapshot, entry(createFolderEntry()), null)
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.FOLDER_CHANGED), plan)
    }

    // --- remove undo ---

    private fun removeEntry(removedRow: DirectEditContract.UndoRowPayload? = payload()) = entry(
        evidence(
            HomeEditActionKind.REMOVE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 0,
            newCellX = 1,
            newCellY = 2,
        ),
        removedRow = removedRow,
    )

    @Test
    fun `remove undo restores when available and the old cell is free`() {
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), emptyList())
        val plan = HomeEditUndoPlanner.verify(snapshot, removeEntry(), HomeEditUndoAvailability.AVAILABLE)
        val restored = plan as HomeEditUndoPlan.RestoreRemoved
        assertPayloadEquals(payload(), restored.payload)
    }

    private fun assertPayloadEquals(expected: DirectEditContract.UndoRowPayload, actual: DirectEditContract.UndoRowPayload) {
        assertEquals(expected.itemId, actual.itemId)
        assertEquals(expected.itemType, actual.itemType)
        assertEquals(expected.container, actual.container)
        assertEquals(expected.screenId, actual.screenId)
        assertEquals(expected.cellX, actual.cellX)
        assertEquals(expected.cellY, actual.cellY)
        assertEquals(expected.spanX, actual.spanX)
        assertEquals(expected.spanY, actual.spanY)
        assertEquals(expected.rank, actual.rank)
        assertEquals(expected.userSerial, actual.userSerial)
        assertEquals(expected.intent, actual.intent)
        assertEquals(expected.title, actual.title)
        assertEquals(expected.options, actual.options)
        assertEquals(expected.organizerLockState, actual.organizerLockState)
        assertEquals(expected.componentName, actual.componentName)
        assertEquals(expected.packageName, actual.packageName)
        assertEquals(expected.shortcutId, actual.shortcutId)
    }

    @Test
    fun `remove undo rejects unavailable and unknown availability fail-closed`() {
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), emptyList())
        for (availability in listOf(
            HomeEditUndoAvailability.UNAVAILABLE,
            HomeEditUndoAvailability.UNKNOWN,
        )) {
            val plan = HomeEditUndoPlanner.verify(snapshot, removeEntry(), availability)
            assertEquals(
                "availability=$availability",
                HomeEditUndoPlan.Rejected(HomeEditUndoRejection.ITEM_UNAVAILABLE),
                plan,
            )
        }
    }

    @Test
    fun `remove undo rejects when the old cell got occupied`() {
        val occupant = item(200, screenId = 0, cellX = 1, cellY = 2)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), listOf(occupant))
        val plan = HomeEditUndoPlanner.verify(
            snapshot,
            removeEntry(),
            HomeEditUndoAvailability.AVAILABLE,
        )
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.NO_SPACE), plan)
    }

    @Test
    fun `remove undo rejects stale when the item id was re-created`() {
        val recreated = item(100, screenId = 0, cellX = 3, cellY = 3)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0), listOf(recreated))
        val plan = HomeEditUndoPlanner.verify(
            snapshot,
            removeEntry(),
            HomeEditUndoAvailability.AVAILABLE,
        )
        assertEquals(HomeEditUndoPlan.Rejected(HomeEditUndoRejection.STALE), plan)
    }

    // --- determinism and typed key mapping ---

    @Test
    fun `verification is deterministic for identical inputs`() {
        val moved = item(100, screenId = 1, cellX = 0, cellY = 0)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0, 1), listOf(moved))
        val e = evidence(
            HomeEditActionKind.MOVE_TO_PAGE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
        )
        assertEquals(
            HomeEditUndoPlanner.verify(snapshot, entry(e), null),
            HomeEditUndoPlanner.verify(snapshot, entry(e), null),
        )
    }

    @Test
    fun `rejection keys map to the typed undo vocabulary`() {
        assertEquals(
            DirectEditContract.FAIL_UNDO_STALE,
            undoRejectionKey(HomeEditUndoRejection.STALE),
        )
        assertEquals(
            DirectEditContract.FAIL_UNDO_NO_SPACE,
            undoRejectionKey(HomeEditUndoRejection.NO_SPACE),
        )
        assertEquals(
            DirectEditContract.FAIL_UNDO_FOLDER_CHANGED,
            undoRejectionKey(HomeEditUndoRejection.FOLDER_CHANGED),
        )
        assertEquals(
            DirectEditContract.FAIL_UNDO_ITEM_UNAVAILABLE,
            undoRejectionKey(HomeEditUndoRejection.ITEM_UNAVAILABLE),
        )
    }

    // --- stage 2 validator (same function, admission input) ---

    private fun platformSnapshot(items: List<HomeEditItem>): DirectEditContract.Snapshot = DirectEditContract.Snapshot(
        4,
        6,
        intArrayOf(0, 1),
        items.map {
            DirectEditContract.Row(
                it.id, it.container, it.screenId, it.cellX, it.cellY,
                it.spanX, it.spanY, it.itemType, it.rank, it.userSerial,
            )
        }.toTypedArray(),
    )

    @Test
    fun `stage 2 proceeds only on the stage 1 result`() {
        val moved = item(100, screenId = 1, cellX = 0, cellY = 0)
        val snapshot = HomeEditSnapshot(4, 6, listOf(0, 1), listOf(moved))
        val e = evidence(
            HomeEditActionKind.MOVE_TO_PAGE,
            newContainer = HomeEditContainers.DESKTOP,
            newScreenId = 1,
            newCellX = 0,
            newCellY = 0,
        )
        val stage1 = HomeEditUndoPlanner.verify(snapshot, entry(e), null)
        assertTrue(stage1 is HomeEditUndoPlan.Success)
        val validator = HomeEditUndoStage2Validator(entry(e), stage1 as HomeEditUndoPlan.Success, null)
        val proceed = validator.validate(platformSnapshot(listOf(moved)))
        assertEquals(true, proceed.proceed)
        assertEquals(null, proceed.failureReason)
        // The item moved again inside admission: typed rejection, no write.
        val movedAgain = item(100, screenId = 1, cellX = 2, cellY = 3)
        val decision = validator.validate(platformSnapshot(listOf(movedAgain)))
        assertEquals(false, decision.proceed)
        assertEquals(DirectEditContract.FAIL_UNDO_STALE, decision.failureReason)
    }
}
