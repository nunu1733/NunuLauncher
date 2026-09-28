/*
 * Issue #448: boundary test for the action-start placement freeze. The
 * snapshot must capture at construction; a regression back to lazy access
 * would re-read the live item after it moved and silently adopt the moved
 * placement as the precondition (Phase 2 review round 2/3).
 */
package app.lawnchair.homeedit

import app.lawnchair.homeedit.ui.SourcePlacementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePlacementSnapshotTest {

    @Test
    fun `snapshot freezes at construction and ignores later live-item moves`() {
        var liveItem = HomeEditItem(
            id = 100,
            container = HomeEditContainers.DESKTOP,
            screenId = 0,
            cellX = 0,
            cellY = 4,
            spanX = 1,
            spanY = 1,
            itemType = HomeEditItemTypes.APPLICATION,
            rank = 0,
            userSerial = 10L,
        )
        val snapshot = SourcePlacementSnapshot { liveItem }

        // Another writer moves the live item while the dialog is open.
        liveItem = liveItem.copy(container = HomeEditContainers.HOTSEAT, screenId = 0, cellX = 3)

        // The confirm-time intent must still carry the construction-time
        // placement so the planner rejects it as STALE.
        assertEquals(
            HomeEditItem(
                id = 100,
                container = HomeEditContainers.DESKTOP,
                screenId = 0,
                cellX = 0,
                cellY = 4,
                spanX = 1,
                spanY = 1,
                itemType = HomeEditItemTypes.APPLICATION,
                rank = 0,
                userSerial = 10L,
            ),
            snapshot.placement,
        )
    }

    @Test
    fun `stale precondition flows through the planner`() {
        val source = HomeEditItem(
            id = 100,
            container = HomeEditContainers.DESKTOP,
            screenId = 0,
            cellX = 0,
            cellY = 4,
            spanX = 1,
            spanY = 1,
            itemType = HomeEditItemTypes.APPLICATION,
            rank = 0,
            userSerial = 10L,
        )
        val moved = source.copy(cellX = 3, cellY = 0)
        val snapshot = SourcePlacementSnapshot { source }
        val snapshot2 = SourcePlacementSnapshot { moved }
        // The frozen placement is the planner's precondition.
        val plan = HomeEditPlanner.plan(
            HomeEditSnapshot(4, 6, listOf(0, 1), listOf(moved)),
            HomeEditIntent.MoveToPage(snapshot.placement, 1),
        )
        assertEquals(HomeEditPlan.Rejected(HomeEditRejection.STALE), plan)
        // A fresh snapshot taken after the move (the lazy regression) would
        // instead produce a plan for the moved item — the exact regression
        // this seam exists to prevent.
        val lazyRegression = HomeEditPlanner.plan(
            HomeEditSnapshot(4, 6, listOf(0, 1), listOf(moved)),
            HomeEditIntent.MoveToPage(snapshot2.placement, 1),
        )
        assertEquals(
            HomeEditPlan.Move(moved, HomeEditContainers.DESKTOP, 1, 0, 0, 0),
            lazyRegression,
        )
    }
}
