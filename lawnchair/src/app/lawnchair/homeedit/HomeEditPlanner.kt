/*
 * Issue #448: pure planning function for per-item edit actions. No I/O, no
 * Android types: the same function validates at stage 1 (submit time) and at
 * stage 2 (inside MODEL_WRITER admission) — the validator re-runs this planner
 * against the current snapshot and requires the same closed result, never
 * re-planning silently (ADR-0013 contract 2).
 */
package app.lawnchair.homeedit

object HomeEditPlanner {

    /**
     * Computes the closed result for one edit intent against [snapshot].
     * Deterministic: the destination scan is row-major (top→bottom,
     * left→right) and the vacated cell of the target item is a candidate.
     * A page move that resolves to the item's current position is rejected as
     * [HomeEditRejection.REDUNDANT] instead of writing a no-op.
     */
    fun plan(snapshot: HomeEditSnapshot, intent: HomeEditIntent): HomeEditPlan {
        return when (intent) {
            is HomeEditIntent.MoveToPage -> planMoveToPage(snapshot, intent)
            is HomeEditIntent.AddToFolder -> planAddToFolder(snapshot, intent)
            is HomeEditIntent.CreateFolderAndAdd -> planCreateFolder(snapshot, intent)
            is HomeEditIntent.Remove -> planRemove(snapshot, intent)
        }
    }

    private fun planMoveToPage(
        snapshot: HomeEditSnapshot,
        intent: HomeEditIntent.MoveToPage,
    ): HomeEditPlan {
        val target = editableTarget(snapshot, intent.targetItemId) ?: return rejectTarget(snapshot, intent.targetItemId)
        if (intent.targetScreenId !in snapshot.screenIds) {
            return HomeEditPlan.Rejected(HomeEditRejection.STALE)
        }
        val cell = firstFreeCell(snapshot, intent.targetScreenId, target)
            ?: return HomeEditPlan.Rejected(HomeEditRejection.NO_SPACE)
        if (cell.x == target.cellX && cell.y == target.cellY) {
            return HomeEditPlan.Rejected(HomeEditRejection.REDUNDANT)
        }
        return HomeEditPlan.Move(
            targetItemPlacement = target,
            container = HomeEditContainers.DESKTOP,
            screenId = intent.targetScreenId,
            cellX = cell.x,
            cellY = cell.y,
            rank = target.rank,
        )
    }

    private fun planAddToFolder(
        snapshot: HomeEditSnapshot,
        intent: HomeEditIntent.AddToFolder,
    ): HomeEditPlan {
        val target = editableTarget(snapshot, intent.targetItemId) ?: return rejectTarget(snapshot, intent.targetItemId)
        val folder = snapshot.items.firstOrNull {
            it.id == intent.folderId && it.itemType == HomeEditItemTypes.FOLDER
        } ?: return HomeEditPlan.Rejected(HomeEditRejection.FOLDER_GONE)
        if (folder.userSerial != target.userSerial) {
            return HomeEditPlan.Rejected(HomeEditRejection.PROFILE_MISMATCH)
        }
        val rank = snapshot.items.count { it.container == intent.folderId }
        return HomeEditPlan.Move(
            targetItemPlacement = target,
            container = intent.folderId,
            screenId = 0,
            cellX = -1,
            cellY = -1,
            rank = rank,
        )
    }

    private fun planCreateFolder(
        snapshot: HomeEditSnapshot,
        intent: HomeEditIntent.CreateFolderAndAdd,
    ): HomeEditPlan {
        val target = editableTarget(snapshot, intent.targetItemId) ?: return rejectTarget(snapshot, intent.targetItemId)
        if (intent.destinationScreenId !in snapshot.screenIds) {
            return HomeEditPlan.Rejected(HomeEditRejection.STALE)
        }
        val cell = firstFreeCell(snapshot, intent.destinationScreenId, target)
            ?: return HomeEditPlan.Rejected(HomeEditRejection.NO_SPACE)
        return HomeEditPlan.CreateFolder(
            targetItemPlacement = target,
            screenId = intent.destinationScreenId,
            cellX = cell.x,
            cellY = cell.y,
        )
    }

    private fun planRemove(
        snapshot: HomeEditSnapshot,
        intent: HomeEditIntent.Remove,
    ): HomeEditPlan {
        val target = editableTarget(snapshot, intent.targetItemId) ?: return rejectTarget(snapshot, intent.targetItemId)
        return HomeEditPlan.RemoveItem(target)
    }

    /**
     * True when [screenId] exists on [snapshot] and has at least one free 1x1
     * cell (the vacated target cell counts). Used by the destination picker.
     */
    fun hasFreeCell(snapshot: HomeEditSnapshot, screenId: Int, targetId: Int): Boolean {
        val target = snapshot.itemById(targetId)
        if (target == null) {
            // Without the target there is no vacated cell; a plain 1x1 probe.
            val probe = HomeEditItem(
                id = -1, container = HomeEditContainers.DESKTOP, screenId = screenId,
                cellX = -1, cellY = -1, spanX = 1, spanY = 1,
                itemType = HomeEditItemTypes.APPLICATION, rank = 0, userSerial = 0,
            )
            return firstFreeCell(snapshot, screenId, probe) != null
        }
        return firstFreeCell(snapshot, screenId, target) != null
    }

    private fun editableTarget(snapshot: HomeEditSnapshot, targetId: Int): HomeEditItem? {
        val target = snapshot.itemById(targetId) ?: return null
        val supported = target.itemType == HomeEditItemTypes.APPLICATION ||
            target.itemType == HomeEditItemTypes.DEEP_SHORTCUT
        return if (supported) target else null
    }

    private fun rejectTarget(snapshot: HomeEditSnapshot, targetId: Int): HomeEditPlan = HomeEditPlan.Rejected(
        if (snapshot.itemById(targetId) == null) {
            HomeEditRejection.ITEM_GONE
        } else {
            HomeEditRejection.UNSUPPORTED
        },
    )

    /**
     * Row-major scan of the first free 1x1 cell on [screenId]. The target's
     * own cell counts as free (it is vacated by the move); choosing it yields
     * a REDUNDANT rejection at the call site, per spec.
     */
    private fun firstFreeCell(
        snapshot: HomeEditSnapshot,
        screenId: Int,
        target: HomeEditItem,
    ): Cell? {
        val occupied = HashSet<Long>()
        for (item in snapshot.items) {
            if (item.id == target.id) continue
            if (item.container != HomeEditContainers.DESKTOP) continue
            if (item.screenId != screenId) continue
            for (dy in 0 until item.spanY) {
                for (dx in 0 until item.spanX) {
                    occupied.add(pack(item.cellX + dx, item.cellY + dy))
                }
            }
        }
        for (y in 0 until snapshot.rowCount) {
            for (x in 0 until snapshot.columnCount) {
                if (pack(x, y) !in occupied) return Cell(x, y)
            }
        }
        return null
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    private data class Cell(val x: Int, val y: Int)
}
