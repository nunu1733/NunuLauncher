/*
 * Issue #449: pure session planner for the visual edit surface. Android-free.
 * Applies one edit action to the whole selection in the working projection's
 * visual order, calling #448's HomeEditPlanner.plan for every item (the shared
 * placement computation). All-or-nothing: a single rejection discards the whole
 * action and the current session is returned unchanged inside a typed result.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.planning.GridCell

object EditSurfaceSessionPlanner {

    /**
     * 現在の選択へアクションを適用した新しいセッションを返す（all-or-nothing）。
     * - 選択は作業投影の視覚順（ページ順 → 行 → 列 → id）に決定的に並べ替える。
     * - 各アイテムの配置決定は #448 の [HomeEditPlanner.plan] を共有する。
     * - 1個でも拒否されれば全体を適用しない（部分適用しない。spec決定済み）。
     * - セッション計画に固定済み（touched）のアイテムの再操作は拒否する
     *   （spec: アクションを実行したアイテムは選択から外れ固定される。取り消しは
     *   リセットのみ）。
     * 同一入力からは常に同一結果（決定性・冪等）。
     */
    fun plan(
        capture: HomeEditSnapshot,
        captureLockStates: Map<Int, OrganizerLockState>,
        current: EditSurfaceSession,
        selection: List<Int>,
        action: PendingSessionAction,
    ): SessionPlanResult {
        val working = EditSurfaceProjection.workingSnapshot(capture, current)
        // The touched guard runs on the raw selection before visual ordering:
        // a touched item is already absent from the working projection, so the
        // ordering step would silently drop it instead of rejecting (spec:
        // 実行済みアイテムの再操作は拒否。取り消しはリセットのみ).
        if (selection.any { it in current.touchedIds }) {
            return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
        }
        val ordered = EditSurfaceProjection.visualOrder(working, selection).distinct()
        if (ordered.isEmpty()) return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
        val mutable = working.items.associateBy { it.id }.toMutableMap()
        if (ordered.any { it !in mutable }) {
            return SessionPlanResult.Rejected(HomeEditRejection.ITEM_GONE)
        }
        // The selection contract (#448 parity + lock rules): only unlocked
        // desktop applications and deep shortcuts are actionable. LOCKED rows
        // are rejected before the planner runs so a lock can never move
        // (spec: ロック中は選択不可、tap時にその旨を示す).
        if (ordered.any { id ->
                val item = mutable.getValue(id)
                item.container != HomeEditContainers.DESKTOP ||
                    (item.itemType != HomeEditItemTypes.APPLICATION && item.itemType != HomeEditItemTypes.DEEP_SHORTCUT)
            }
        ) {
            return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
        }
        // The working projection does not carry lock state; the capture does.
        if (ordered.any { id ->
                captureLockStates[id]?.let { it == OrganizerLockState.LOCKED || it == OrganizerLockState.UNKNOWN } == true
            }
        ) {
            return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
        }
        return when (action) {
            is PendingSessionAction.MoveToPage -> planMoveToPage(working, current, ordered, mutable, action)
            is PendingSessionAction.AddToFolder -> planAddToFolder(working, current, ordered, mutable, action)
            is PendingSessionAction.CreateFolder -> planCreateFolder(working, current, ordered, mutable)
            is PendingSessionAction.RemoveFromHome -> planRemove(working, current, ordered, mutable)
        }
    }

    private fun planMoveToPage(
        working: HomeEditSnapshot,
        current: EditSurfaceSession,
        ordered: List<Int>,
        mutable: MutableMap<Int, HomeEditItem>,
        action: PendingSessionAction.MoveToPage,
    ): SessionPlanResult {
        val changes = mutableListOf<SessionItemChange>()
        for (id in ordered) {
            val item = mutable.getValue(id)
            when (val plan = HomeEditPlanner.plan(currentSnapshot(working, mutable), HomeEditIntent.MoveToPage(item, action.screenId))) {
                is HomeEditPlan.Move -> {
                    mutable[id] = item.copy(screenId = plan.screenId, cellX = plan.cellX, cellY = plan.cellY)
                    changes += SessionItemChange.PageMove(id, plan.screenId, GridCell(plan.cellX, plan.cellY))
                }

                is HomeEditPlan.Rejected -> return SessionPlanResult.Rejected(plan.reason)

                else -> return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
            }
        }
        return SessionPlanResult.Applied(current.append(changes))
    }

    private fun planAddToFolder(
        working: HomeEditSnapshot,
        current: EditSurfaceSession,
        ordered: List<Int>,
        mutable: MutableMap<Int, HomeEditItem>,
        action: PendingSessionAction.AddToFolder,
    ): SessionPlanResult {
        val changes = mutableListOf<SessionItemChange>()
        for (id in ordered) {
            val item = mutable.getValue(id)
            when (val plan = HomeEditPlanner.plan(currentSnapshot(working, mutable), HomeEditIntent.AddToFolder(item, action.folderId))) {
                is HomeEditPlan.Move -> {
                    if (plan.container != action.folderId) {
                        return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
                    }
                    mutable[id] = item.copy(container = plan.container, screenId = 0, cellX = -1, cellY = -1, rank = plan.rank)
                    changes += SessionItemChange.FolderAdd(id, plan.container, plan.rank)
                }

                is HomeEditPlan.Rejected -> return SessionPlanResult.Rejected(plan.reason)

                else -> return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
            }
        }
        return SessionPlanResult.Applied(current.append(changes))
    }

    /**
     * 選択から新しいフォルダを作る。置き先は視覚順で最初のアイテムの元セル
     * （spec決定済み）。#448の共有moduleに追加した指定セル付きintent variant
     * （[HomeEditIntent.CreateFolderAt]）で作り、残りはセッション内フォルダidへの
     * [HomeEditIntent.AddToFolder] として計画する（rankはplannerが既存メンバー数
     * から決定する）。
     */
    private fun planCreateFolder(
        working: HomeEditSnapshot,
        current: EditSurfaceSession,
        ordered: List<Int>,
        mutable: MutableMap<Int, HomeEditItem>,
    ): SessionPlanResult {
        val first = mutable.getValue(ordered.first())
        val ordinal = current.newFolders.size
        val folderId = editSurfaceNewFolderKey(ordinal)
        val changes = mutableListOf<SessionItemChange>()
        when (
            val plan = HomeEditPlanner.plan(
                currentSnapshot(working, mutable),
                HomeEditIntent.CreateFolderAt(first, first.screenId, first.cellX, first.cellY),
            )
        ) {
            is HomeEditPlan.CreateFolder -> {
                // The synthetic folder row is owned by the session record; the
                // member move is an ordinary FolderAdd change. The row joins
                // the in-progress snapshot so the shared planner can plan the
                // remaining members against it (FOLDER_GONE otherwise).
                mutable[folderId] = HomeEditItem(
                    id = folderId,
                    container = HomeEditContainers.DESKTOP,
                    screenId = plan.screenId,
                    cellX = plan.cellX,
                    cellY = plan.cellY,
                    spanX = 1,
                    spanY = 1,
                    itemType = HomeEditItemTypes.FOLDER,
                    rank = 0,
                    userSerial = first.userSerial,
                )
                mutable[first.id] = first.copy(container = folderId, screenId = 0, cellX = -1, cellY = -1, rank = 0)
                changes += SessionItemChange.FolderAdd(first.id, folderId, 0)
            }

            is HomeEditPlan.Rejected -> return SessionPlanResult.Rejected(plan.reason)

            else -> return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
        }
        for (id in ordered.drop(1)) {
            val item = mutable.getValue(id)
            when (val plan = HomeEditPlanner.plan(currentSnapshot(working, mutable), HomeEditIntent.AddToFolder(item, folderId))) {
                is HomeEditPlan.Move -> {
                    if (plan.container != folderId) {
                        return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
                    }
                    mutable[id] = item.copy(container = folderId, screenId = 0, cellX = -1, cellY = -1, rank = plan.rank)
                    changes += SessionItemChange.FolderAdd(id, folderId, plan.rank)
                }

                is HomeEditPlan.Rejected -> return SessionPlanResult.Rejected(plan.reason)

                else -> return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
            }
        }
        val session = EditSurfaceSession(
            newFolders = current.newFolders + SessionNewFolder(
                ordinal = ordinal,
                screenId = first.screenId,
                cellX = first.cellX,
                cellY = first.cellY,
                memberIds = ordered.toList(),
                userSerial = first.userSerial,
            ),
            changes = current.changes + changes,
        )
        return SessionPlanResult.Applied(session)
    }

    private fun planRemove(
        working: HomeEditSnapshot,
        current: EditSurfaceSession,
        ordered: List<Int>,
        mutable: MutableMap<Int, HomeEditItem>,
    ): SessionPlanResult {
        val changes = mutableListOf<SessionItemChange>()
        for (id in ordered) {
            val item = mutable.getValue(id)
            when (val plan = HomeEditPlanner.plan(currentSnapshot(working, mutable), HomeEditIntent.Remove(item))) {
                is HomeEditPlan.RemoveItem -> {
                    mutable.remove(id)
                    changes += SessionItemChange.Removal(id)
                }

                is HomeEditPlan.Rejected -> return SessionPlanResult.Rejected(plan.reason)

                else -> return SessionPlanResult.Rejected(HomeEditRejection.UNSUPPORTED)
            }
        }
        return SessionPlanResult.Applied(current.append(changes))
    }

    /** The in-progress snapshot the shared planner sees at each step. */
    private fun currentSnapshot(working: HomeEditSnapshot, mutable: Map<Int, HomeEditItem>): HomeEditSnapshot = HomeEditSnapshot(
        columnCount = working.columnCount,
        rowCount = working.rowCount,
        screenIds = working.screenIds,
        items = mutable.values.toList(),
    )

    private fun EditSurfaceSession.append(changes: List<SessionItemChange>): EditSurfaceSession = copy(changes = this.changes + changes)

    /**
     * 確定ゲート（AC-7）。確定は(1)セッション計画が空でない間のみ可能であり、
     * (2)セッション開始時のcapture内に `OrganizerLockState.UNKNOWN` 行が存在する
     * 間は無効化する（既存 `LOCK_STATE_UNAVAILABLE` 契約との一致。review round 1
     * 指摘4）。nullでない戻り値は零書込みの理由表示に対応する。
     */
    fun confirmGate(
        session: EditSurfaceSession,
        captureLockStates: Collection<OrganizerLockState>,
    ): ConfirmGate = when {
        session.isEmpty -> ConfirmGate.EmptySession
        captureLockStates.any { it == OrganizerLockState.UNKNOWN } -> ConfirmGate.LockStateUnknown
        else -> ConfirmGate.Open
    }

    /** 確定ゲートのtypedな結果。 */
    sealed interface ConfirmGate {
        /** 確定できる。 */
        data object Open : ConfirmGate

        /** セッション計画が空（変更0件）。 */
        data object EmptySession : ConfirmGate

        /** capture内にロック状態が確認できない行がある（正規化されるまで確定不可）。 */
        data object LockStateUnknown : ConfirmGate
    }
}
