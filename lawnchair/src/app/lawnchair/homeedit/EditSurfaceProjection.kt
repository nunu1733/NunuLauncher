/*
 * Issue #449: pure projection from the organizer capture to the edit-surface
 * vocabulary. Android-free. The layoutState → HomeEditSnapshot mapping is a
 * separate entry from #448's HomeEditSnapshotMapper (different authority: the
 * #449 capture goes through the organizer application module, the #448
 * snapshot is read on the model thread). The working snapshot derived here is
 * the single geometry authority for both the session planner and the diagram.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion

/** 図の1面分の表示投影（captureメタデータ + セッション適用済みの幾何）。 */
data class EditSurfaceDiagram(
    val columnCount: Int,
    val rowCount: Int,
    /** 表示順のscreenId（captureのページ順。セッションで新設ページはしない）。 */
    val pages: List<Int>,
    val items: List<EditSurfaceItem>,
    /** QSB等の予約領域（表示のみで選択できない。platform所有）。 */
    val reservedRegions: List<ReservedRegion>,
    /** folderId（favorites行id。セッション内フォルダを含む）→ メンバー数。 */
    val folderMemberCounts: Map<Int, Int>,
) {
    /** 図上アイテムのid→表示投影。UIのtap処理とTalkBackラベルに使う。 */
    val itemById: Map<Int, EditSurfaceItem> get() = items.associateBy { it.id }

    data class ReservedRegion(
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
        val spanX: Int,
        val spanY: Int,
    )
}

object EditSurfaceProjection {

    /**
     * organizer capture → #448編集snapshotへの投影（organizer capture権威の入口）。
     * captureは恒久行のみを含む（planned参照はcapture不変条件違反）。
     * platform所有の予約領域（QSB等）はレイアウトitemではないため、[HomeEditItemTypes.RESERVED]
     * 型の合成行として占有一覧に加える（#448経路のQSB合成行と同じ規約。共有plannerの
     * 空きセル探索が予約セルを避ける）。図の描画では予約領域はreservedRegionsから描くため
     * [diagram] はこの合成行を除外する。
     */
    fun homeEditSnapshot(layoutState: LayoutState): HomeEditSnapshot = HomeEditSnapshot(
        columnCount = layoutState.deviceCapabilities.columns,
        rowCount = layoutState.deviceCapabilities.rows,
        screenIds = layoutState.pages.map { it.ref }.map { ref ->
            pageScreenId(ref)
        },
        items = layoutState.items.map { item ->
            val ref = item.ref as? ApplicationItemRef.PersistentItem
                ?: throw IllegalArgumentException("Capture contains a non-persistent item ref: ${item.ref}")
            val id = requireId(ref)
            val container: Int
            val screenId: Int
            val cellX: Int
            val cellY: Int
            val spanX: Int
            val spanY: Int
            val rank: Int
            when (val placement = item.placement) {
                is PlacementState.Workspace -> {
                    container = HomeEditContainers.DESKTOP
                    screenId = pageScreenId(placement.page)
                    cellX = placement.cell.x
                    cellY = placement.cell.y
                    spanX = placement.span.width
                    spanY = placement.span.height
                    rank = 0
                }

                is PlacementState.Dock -> {
                    container = HomeEditContainers.HOTSEAT
                    screenId = placement.rank
                    cellX = -1
                    cellY = -1
                    spanX = 1
                    spanY = 1
                    rank = placement.rank
                }

                is PlacementState.FolderChild -> {
                    container = folderContainerId(placement.parent)
                    screenId = 0
                    cellX = -1
                    cellY = -1
                    spanX = 1
                    spanY = 1
                    rank = placement.rank
                }

                is PlacementState.AppPairChild -> {
                    container = folderContainerId(placement.parent)
                    screenId = 0
                    cellX = -1
                    cellY = -1
                    spanX = 1
                    spanY = 1
                    rank = placement.stage.ordinal
                }

                is PlacementState.UnsupportedContainer -> {
                    container = placement.code.value
                    screenId = 0
                    cellX = 0
                    cellY = 0
                    spanX = 1
                    spanY = 1
                    rank = 0
                }
            }
            HomeEditItem(
                id = id,
                container = container,
                screenId = screenId,
                cellX = cellX,
                cellY = cellY,
                spanX = spanX,
                spanY = spanY,
                itemType = itemTypeCode(item.kind),
                rank = rank,
                userSerial = item.profile.value.toLong(),
            )
        } + layoutState.reservedWorkspaceRegions.mapIndexed { index, reservation ->
            HomeEditItem(
                id = editSurfaceReservationKey(index),
                container = HomeEditContainers.DESKTOP,
                screenId = reservation.page.pageId.value.toInt(),
                cellX = reservation.cell.x,
                cellY = reservation.cell.y,
                spanX = reservation.span.width,
                spanY = reservation.span.height,
                itemType = HomeEditItemTypes.RESERVED,
                rank = 0,
                userSerial = 0,
            )
        },
    )

    /**
     * capture編集snapshotにセッション計画を適用した作業投影。図の幾何と
     * セッション計画の双方がこれを使う（単一の権威。決定的・冪等）。
     */
    fun workingSnapshot(capture: HomeEditSnapshot, session: EditSurfaceSession): HomeEditSnapshot {
        if (session.isEmpty) return capture
        val mutable = capture.items.associateBy { it.id }.toMutableMap()
        for (folder in session.newFolders) {
            mutable[editSurfaceNewFolderKey(folder.ordinal)] = HomeEditItem(
                id = editSurfaceNewFolderKey(folder.ordinal),
                container = HomeEditContainers.DESKTOP,
                screenId = folder.screenId,
                cellX = folder.cellX,
                cellY = folder.cellY,
                spanX = 1,
                spanY = 1,
                itemType = HomeEditItemTypes.FOLDER,
                rank = 0,
                userSerial = folder.userSerial,
            )
        }
        for (change in session.changes) {
            val item = mutable[change.targetId] ?: throw IllegalArgumentException(
                "Session change targets an item missing from the working projection: $change",
            )
            when (change) {
                is SessionItemChange.PageMove -> mutable[change.targetId] = item.copy(
                    screenId = change.screenId,
                    cellX = change.cell.x,
                    cellY = change.cell.y,
                )

                is SessionItemChange.FolderAdd -> mutable[change.targetId] = item.copy(
                    container = change.folderId,
                    screenId = 0,
                    cellX = -1,
                    cellY = -1,
                    rank = change.rank,
                )

                is SessionItemChange.Removal -> mutable.remove(change.targetId)
            }
        }
        return HomeEditSnapshot(
            columnCount = capture.columnCount,
            rowCount = capture.rowCount,
            screenIds = capture.screenIds,
            items = mutable.values.toList(),
        )
    }

    /**
     * 図表示への投影。幾何は[working]、メタデータ（ラベル・iconバイト・lock）は
     * capture時点の値。セッションで除かれたアイテムは図に現れない。
     * セッション内作成のフォルダ行はcaptureにメタデータがないため合成値を写す。
     */
    fun diagram(layoutState: LayoutState, working: HomeEditSnapshot): EditSurfaceDiagram {
        val metadata = layoutState.items.mapNotNull { item ->
            val ref = item.ref as? ApplicationItemRef.PersistentItem ?: return@mapNotNull null
            requireId(ref) to item
        }.toMap()
        val items = working.items
            // Reserved cells are drawn from reservedRegions, not as items.
            .filter { it.itemType != HomeEditItemTypes.RESERVED }
            .map { row ->
                val captured = metadata[row.id]
                EditSurfaceItem(
                    id = row.id,
                    itemType = row.itemType,
                    label = captured?.title?.valueOrNull(),
                    iconBytes = captured?.icon?.valueOrNull(),
                    targetKey = captured?.targetKey,
                    userSerial = row.userSerial,
                    lockState = captured?.lockState ?: OrganizerLockState.UNLOCKED,
                    container = row.container,
                    screenId = row.screenId,
                    cellX = row.cellX,
                    cellY = row.cellY,
                    spanX = row.spanX,
                    spanY = row.spanY,
                    rank = row.rank,
                    isSessionCreated = captured == null,
                )
            }
        return EditSurfaceDiagram(
            columnCount = working.columnCount,
            rowCount = working.rowCount,
            pages = working.screenIds,
            items = items,
            reservedRegions = layoutState.reservedWorkspaceRegions.map(::reservedRegion),
            folderMemberCounts = working.items
                // Positive containers are persistent parent folder rowids;
                // session folder keys are negative synthetic ids. DESKTOP/
                // HOTSEAT constants live below the session key range.
                .filter { it.container > 0 || isEditSurfaceNewFolderKey(it.container) }
                .groupBy { it.container }
                .mapValues { (_, members) -> members.size },
        )
    }

    /**
     * [ids]を作業投影の視覚順（ページ順 → 行 → 列 → id）に並べ替える。
     * セッション計画の決定性の根拠（spec: 視覚順に決定的に計画する）。
     * 図に存在しないidは無視する。
     */
    fun visualOrder(working: HomeEditSnapshot, ids: Collection<Int>): List<Int> {
        val pageIndexOf = working.screenIds.withIndex().associate { (index, screenId) -> screenId to index }
        val byId = working.items.associateBy { it.id }
        return ids.mapNotNull { id -> byId[id] }
            .sortedWith(
                compareBy(
                    { pageIndexOf[it.screenId] ?: Int.MAX_VALUE },
                    { it.cellY },
                    { it.cellX },
                    { it.id },
                ),
            )
            .map { it.id }
    }

    private fun reservedRegion(reservation: ReservedWorkspaceRegion): EditSurfaceDiagram.ReservedRegion {
        val page = reservation.page as? app.lawnchair.organizer.planning.PageRef
            ?: throw IllegalArgumentException("Reserved region on a non-persistent page: $reservation")
        return EditSurfaceDiagram.ReservedRegion(
            screenId = page.pageId.value.toInt(),
            cellX = reservation.cell.x,
            cellY = reservation.cell.y,
            spanX = reservation.span.width,
            spanY = reservation.span.height,
        )
    }

    private fun pageScreenId(ref: ApplicationPageRef): Int = when (ref) {
        is ApplicationPageRef.PersistentPage -> ref.pageId.value.toInt()

        is ApplicationPageRef.PlannedPage ->
            throw IllegalArgumentException("Capture contains a planned page: ${ref.ordinal}")
    }

    private fun folderContainerId(parent: ApplicationItemRef): Int = when (parent) {
        is ApplicationItemRef.PersistentItem -> requireId(parent)

        is ApplicationItemRef.PlannedFolder ->
            throw IllegalArgumentException("Capture contains a folder child of a planned folder: $parent")

        else -> throw IllegalArgumentException("Capture contains a child of a non-persistent parent: $parent")
    }

    private fun itemTypeCode(kind: CanonicalItemKind): Int = when (kind) {
        CanonicalItemKind.Application -> HomeEditItemTypes.APPLICATION
        CanonicalItemKind.DeepShortcut -> HomeEditItemTypes.DEEP_SHORTCUT
        CanonicalItemKind.ShortcutLegacy -> HomeEditItemTypes.SHORTCUT
        CanonicalItemKind.Folder -> HomeEditItemTypes.FOLDER
        CanonicalItemKind.AppWidget -> HomeEditItemTypes.APP_WIDGET
        CanonicalItemKind.CustomAppWidget -> HomeEditItemTypes.CUSTOM_APP_WIDGET
        CanonicalItemKind.AppPair -> HomeEditItemTypes.APP_PAIR
        is CanonicalItemKind.Unknown -> kind.code.value
    }

    private fun requireId(ref: ApplicationItemRef.PersistentItem): Int {
        val id = ref.itemId.value.toLongOrNull() ?: throw IllegalArgumentException(
            "Capture contains a non-numeric item id: ${ref.itemId.value}",
        )
        return id.toInt()
    }

    private fun app.lawnchair.organizer.application.public.OptionalText.valueOrNull(): String? = (this as? app.lawnchair.organizer.application.public.OptionalText.Present)?.value

    private fun app.lawnchair.organizer.application.public.OptionalBytes.valueOrNull(): app.lawnchair.organizer.application.public.ImmutableByteString? = (this as? app.lawnchair.organizer.application.public.OptionalBytes.Present)?.value
}
