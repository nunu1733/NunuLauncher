/*
 * Issue #448: stage-2 validator semantics (ADR-0013 contract 2 second stage).
 * The validator re-runs the same pure planner against the current snapshot
 * inside admission and only proceeds on the identical closed result.
 */
package app.lawnchair.homeedit

import com.android.launcher3.model.DirectEditContract
import org.junit.Assert.assertEquals
import org.junit.Test

private fun contractRow(
    id: Int,
    container: Int,
    screenId: Int,
    cellX: Int,
    cellY: Int,
    itemType: Int = HomeEditItemTypes.APPLICATION,
    spanX: Int = 1,
    spanY: Int = 1,
    rank: Int = 0,
    userSerial: Long = 10L,
) = DirectEditContract.Row(id, container, screenId, cellX, cellY, spanX, spanY, itemType, rank, userSerial)

private fun contractSnapshot(rows: List<DirectEditContract.Row>) = DirectEditContract.Snapshot(4, 6, intArrayOf(0, 1, 2), rows.toTypedArray())

class HomeEditStage2ValidatorTest {

    private val target = item(id = 100, screenId = 0, cellX = 0, cellY = 5)
    private val intent = HomeEditIntent.MoveToPage(100, 1)

    private fun snapshotFor(vararg rows: DirectEditContract.Row) = contractSnapshot(rows.toList())

    private fun stage1For(rows: List<DirectEditContract.Row>): HomeEditPlan.Success {
        val snapshot = HomeEditSnapshotMapper.map(contractSnapshot(rows))
        return HomeEditPlanner.plan(snapshot, intent) as HomeEditPlan.Success
    }

    @Test
    fun `unchanged state proceeds`() {
        val rows = listOf(contractRow(100, -100, 0, 0, 5))
        val stage1 = stage1For(rows)
        val validator = HomeEditStage2Validator(intent, stage1)
        val decision = validator.validate(snapshotFor(*rows.toTypedArray()))
        assertEquals(true, decision.proceed)
        assertEquals(null, decision.failureReason)
    }

    @Test
    fun `target moved by another writer is rejected as stale`() {
        val rows = listOf(contractRow(100, -100, 0, 0, 5))
        val stage1 = stage1For(rows)
        val validator = HomeEditStage2Validator(intent, stage1)
        // The organizer moved the target meanwhile.
        val moved = contractRow(100, -100, 0, 3, 3)
        val decision = validator.validate(snapshotFor(moved))
        assertEquals(false, decision.proceed)
        assertEquals(DirectEditContract.FAIL_STALE, decision.failureReason)
    }

    @Test
    fun `target gone is rejected as item gone`() {
        val rows = listOf(contractRow(100, -100, 0, 0, 5))
        val stage1 = stage1For(rows)
        val validator = HomeEditStage2Validator(intent, stage1)
        val decision = validator.validate(snapshotFor())
        assertEquals(false, decision.proceed)
        assertEquals(DirectEditContract.FAIL_ITEM_GONE, decision.failureReason)
    }

    @Test
    fun `destination now occupied re-plans to a different cell and is stale`() {
        val rows = listOf(contractRow(100, -100, 0, 0, 5))
        val stage1 = stage1For(rows)
        val validator = HomeEditStage2Validator(intent, stage1)
        // Stage 1 planned (0,0) on page 1; an organizer row now sits there.
        val decision = validator.validate(
            snapshotFor(rows[0], contractRow(7, -100, 1, 0, 0)),
        )
        assertEquals(false, decision.proceed)
        assertEquals(DirectEditContract.FAIL_STALE, decision.failureReason)
    }

    @Test
    fun `planner rejection inside admission maps to the typed key`() {
        val folderAdd = HomeEditIntent.AddToFolder(100, 50)
        val rows = listOf(
            contractRow(100, -100, 0, 0, 5),
            contractRow(50, -100, 1, 0, 0, itemType = HomeEditItemTypes.FOLDER),
        )
        val snapshot = HomeEditSnapshotMapper.map(contractSnapshot(rows))
        val stage1 = HomeEditPlanner.plan(snapshot, folderAdd) as HomeEditPlan.Success
        val validator = HomeEditStage2Validator(folderAdd, stage1)
        // The folder is deleted before admission resolves.
        val decision = validator.validate(snapshotFor(rows[0]))
        assertEquals(false, decision.proceed)
        assertEquals(DirectEditContract.FAIL_FOLDER_GONE, decision.failureReason)
    }

    @Test
    fun `mapper projects platform rows faithfully`() {
        val platform = contractSnapshot(
            listOf(
                contractRow(1, -101, 0, 3, 0, rank = 2),
                contractRow(2, 50, 0, -1, -1, itemType = HomeEditItemTypes.DEEP_SHORTCUT, userSerial = 11L),
            ),
        )
        val mapped = HomeEditSnapshotMapper.map(platform)
        assertEquals(4, mapped.columnCount)
        assertEquals(listOf(0, 1, 2), mapped.screenIds)
        assertEquals(
            listOf(
                HomeEditItem(1, -101, 0, 3, 0, 1, 1, HomeEditItemTypes.APPLICATION, 2, 10L),
                HomeEditItem(2, 50, 0, -1, -1, 1, 1, HomeEditItemTypes.DEEP_SHORTCUT, 0, 11L),
            ),
            mapped.items,
        )
    }
}
