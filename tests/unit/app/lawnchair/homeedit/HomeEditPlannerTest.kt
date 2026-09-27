/*
 * Issue #448: fixture and boundary tests for the pure edit-action planner,
 * through the single public `plan` seam (AGENTS.md test rules). The fixtures
 * use a 4x6 grid unless stated; synthetic identities only.
 */
package app.lawnchair.homeedit

import org.junit.Assert.assertEquals
import org.junit.Test

private const val USER_A = 10L
private const val USER_B = 11L

internal fun item(
    id: Int,
    container: Int = HomeEditContainers.DESKTOP,
    screenId: Int = 0,
    cellX: Int = 0,
    cellY: Int = 0,
    spanX: Int = 1,
    spanY: Int = 1,
    itemType: Int = HomeEditItemTypes.APPLICATION,
    rank: Int = 0,
    userSerial: Long = USER_A,
) = HomeEditItem(id, container, screenId, cellX, cellY, spanX, spanY, itemType, rank, userSerial)

private fun snapshot(
    items: List<HomeEditItem>,
    columnCount: Int = 4,
    rowCount: Int = 6,
    screens: List<Int> = listOf(0, 1, 2),
) = HomeEditSnapshot(columnCount, rowCount, screens, items)

class HomeEditPlannerTest {

    // --- page move: deterministic empty-cell placement ---

    @Test
    fun `move picks first free cell in row-major order`() {
        val target = item(id = 100, screenId = 0, cellX = 0, cellY = 5)
        val snapshot = snapshot(listOf(target))
        val plan = HomeEditPlanner.plan(snapshot, HomeEditIntent.MoveToPage(100, 2))
        assertEquals(HomeEditPlan.Move(target, HomeEditContainers.DESKTOP, 2, 0, 0, 0), plan)
    }

    @Test
    fun `move skips occupied cells including widget spans`() {
        val target = item(id = 100, screenId = 0, cellX = 0, cellY = 5)
        val blocker = item(id = 1, screenId = 2, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val snapshot = snapshot(listOf(target, blocker))
        val plan = HomeEditPlanner.plan(snapshot, HomeEditIntent.MoveToPage(100, 2))
        // (0,0)..(1,1) occupied by the widget; first free cell is (2,0).
        assertEquals(HomeEditPlan.Move(target, HomeEditContainers.DESKTOP, 2, 2, 0, 0), plan)
    }

    @Test
    fun `move counts the vacated target cell as a candidate`() {
        // Page 2 is full except nothing; the item itself sits on page 2 and its
        // own cell would be vacated — the scan may pick it, yielding REDUNDANT.
        val target = item(id = 100, screenId = 2, cellX = 0, cellY = 0)
        val blockers = (0 until 23).map { i ->
            item(id = i + 1, screenId = 2, cellX = (i + 1) % 4, cellY = (i + 1) / 4)
        }
        val snapshot = snapshot(listOf(target) + blockers)
        val plan = HomeEditPlanner.plan(snapshot, HomeEditIntent.MoveToPage(100, 2))
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.REDUNDANT), plan)
    }

    @Test
    fun `full page is rejected as no space`() {
        val target = item(id = 100, screenId = 0, cellX = 0, cellY = 0)
        val blockers = (0 until 24).map { i ->
            item(id = i + 1, screenId = 1, cellX = i % 4, cellY = i / 4)
        }
        val snapshot = snapshot(listOf(target) + blockers)
        val plan = HomeEditPlanner.plan(snapshot, HomeEditIntent.MoveToPage(100, 1))
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.NO_SPACE), plan)
    }

    @Test
    fun `missing page is rejected as stale`() {
        val target = item(id = 100)
        val plan = HomeEditPlanner.plan(snapshot(listOf(target)), HomeEditIntent.MoveToPage(100, 99))
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.STALE), plan)
    }

    @Test
    fun `gone target is rejected as item gone`() {
        val plan = HomeEditPlanner.plan(snapshot(emptyList()), HomeEditIntent.MoveToPage(42, 1))
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.ITEM_GONE), plan)
    }

    @Test
    fun `widget target is unsupported`() {
        val widget = item(id = 100, itemType = 5)
        val plan = HomeEditPlanner.plan(
            snapshot(listOf(widget)),
            HomeEditIntent.Remove(100),
        )
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.UNSUPPORTED), plan)
    }

    // --- add to folder ---

    @Test
    fun `add to folder appends at end rank`() {
        val target = item(id = 100, screenId = 0, cellX = 3, cellY = 5)
        val folder = item(id = 50, itemType = HomeEditItemTypes.FOLDER, screenId = 1, cellX = 0, cellY = 0)
        val child = item(id = 51, container = 50, screenId = 0, cellX = -1, cellY = -1, rank = 0)
        val snapshot = snapshot(listOf(target, folder, child))
        val plan = HomeEditPlanner.plan(snapshot, HomeEditIntent.AddToFolder(100, 50))
        assertEquals(HomeEditPlan.Move(target, 50, 0, -1, -1, 1), plan)
    }

    @Test
    fun `add to missing folder is rejected`() {
        val target = item(id = 100)
        val plan = HomeEditPlanner.plan(snapshot(listOf(target)), HomeEditIntent.AddToFolder(100, 999))
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.FOLDER_GONE), plan)
    }

    @Test
    fun `cross profile add is rejected`() {
        val target = item(id = 100, userSerial = USER_B)
        val folder = item(id = 50, itemType = HomeEditItemTypes.FOLDER, screenId = 1)
        val plan = HomeEditPlanner.plan(snapshot(listOf(target, folder)), HomeEditIntent.AddToFolder(100, 50))
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.PROFILE_MISMATCH), plan)
    }

    // --- create folder on chosen destination page ---

    @Test
    fun `create folder uses the chosen destination page`() {
        val target = item(id = 100, screenId = 0, cellX = 0, cellY = 0)
        val plan = HomeEditPlanner.plan(snapshot(listOf(target)), HomeEditIntent.CreateFolderAndAdd(100, 1))
        assertEquals(HomeEditPlan.CreateFolder(target, 1, 0, 0), plan)
    }

    @Test
    fun `create folder rejects full destination page`() {
        val target = item(id = 100, screenId = 0)
        val blockers = (0 until 24).map { i ->
            item(id = i + 1, screenId = 1, cellX = i % 4, cellY = i / 4)
        }
        val plan = HomeEditPlanner.plan(
            snapshot(listOf(target) + blockers),
            HomeEditIntent.CreateFolderAndAdd(100, 1),
        )
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.NO_SPACE), plan)
    }

    // --- remove ---

    @Test
    fun `remove returns the item placement as evidence`() {
        val target = item(id = 100, screenId = 1, cellX = 2, cellY = 3)
        val folder = item(id = 50, itemType = HomeEditItemTypes.FOLDER)
        val child = item(id = 51, container = 50)
        val plan = HomeEditPlanner.plan(snapshot(listOf(target, folder, child)), HomeEditIntent.Remove(100))
        assertEquals(HomeEditPlan.RemoveItem(target), plan)
    }

    // --- determinism: same input, same output ---

    @Test
    fun `planning is deterministic across repeated calls`() {
        val target = item(id = 100, screenId = 0, cellX = 2, cellY = 2)
        val blocker = item(id = 1, screenId = 1, cellX = 0, cellY = 0)
        val snapshot = snapshot(listOf(target, blocker))
        val intent = HomeEditIntent.MoveToPage(100, 1)
        assertEquals(HomeEditPlanner.plan(snapshot, intent), HomeEditPlanner.plan(snapshot, intent))
    }

    @Test
    fun `constants mirror the platform layout values`() {
        // Guard against drift between the pure module and LauncherSettings.
        assertEquals(-100, HomeEditContainers.DESKTOP)
        assertEquals(-101, HomeEditContainers.HOTSEAT)
        assertEquals(0, HomeEditItemTypes.APPLICATION)
        assertEquals(2, HomeEditItemTypes.FOLDER)
        assertEquals(6, HomeEditItemTypes.DEEP_SHORTCUT)
    }
}
