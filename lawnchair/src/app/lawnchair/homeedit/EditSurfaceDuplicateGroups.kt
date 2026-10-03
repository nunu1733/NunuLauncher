/*
 * Issue #507: pure duplicate-group projection for the visual edit surface
 * (spec 507). Android-free. The grouping rule is not re-implemented here: the
 * projection delegates to the #451 detection authority in the organizer
 * planning module (planning/DuplicateLaunchTargets.kt duplicateGroups) and
 * only maps the edit-surface item vocabulary onto the planning kind/target
 * vocabulary. The result depends on the input diagram only (deterministic;
 * the caller recomputes it from the session-applied working diagram).
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.duplicateGroups

/**
 * 図上アイテムの種別コード → planner重複判定語彙への逆写像
 * （[EditSurfaceProjection.itemTypeCode] の逆写像。参加判定そのものは
 * planning側の一般化関数が所有するため、ここでは対応表のみを持つ。
 * 合成行（RESERVED）と未知コードはnull＝不参加）。
 */
internal fun editSurfaceDuplicateItemKind(itemType: Int): ItemKind? = when (itemType) {
    HomeEditItemTypes.APPLICATION -> ItemKind.APPLICATION
    HomeEditItemTypes.SHORTCUT -> ItemKind.SHORTCUT_LEGACY
    HomeEditItemTypes.FOLDER -> ItemKind.FOLDER
    HomeEditItemTypes.APP_WIDGET -> ItemKind.APPWIDGET
    HomeEditItemTypes.CUSTOM_APP_WIDGET -> ItemKind.CUSTOM_APPWIDGET
    HomeEditItemTypes.DEEP_SHORTCUT -> ItemKind.DEEP_SHORTCUT
    HomeEditItemTypes.APP_PAIR -> ItemKind.APP_PAIR
    else -> null
}

/**
 * 重複グループ1組（同一起動先の配置アイテム集合。サイズ≥2）。
 * メンバーは視覚順（ページ → 行 → 列 → id。フォルダ内は親フォルダの位置、
 * Dockはrank順）に決定的に並ぶ。
 */
data class EditSurfaceDuplicateGroup(
    val target: TargetKey,
    val members: List<EditSurfaceItem>,
) {
    /** #449の選択対象になるメンバー（ロック等でないworkspace上のアプリ/ショートカット）。 */
    val selectableMembers: List<EditSurfaceItem>
        get() = members.filter { it.eligibility == SelectionEligibility.SELECTABLE }
}

object EditSurfaceDuplicateGroups {

    /**
     * 図（セッション計画を適用した作業投影。spec 507 Scope）から重複グループを
     * 決定的に計算する。セッション計画で除かれた（Removal済み）itemは図に
     * 存在しないためグループから消える。判定規則の単一の権威はplanning側の
     * 一般化関数であり、ここに重複判定の2実装を作らない。
     */
    fun groups(diagram: EditSurfaceDiagram): List<EditSurfaceDuplicateGroup> {
        val grouped = duplicateGroups(
            diagram.items,
            kindOf = { item -> editSurfaceDuplicateItemKind(item.itemType) },
            targetOf = { it.targetKey },
        )
        val byId = diagram.itemById
        val pages = diagram.pages
        return grouped.values
            .map { members -> members.sortedByListKey { memberKey(it, byId, pages) } }
            .map { members ->
                EditSurfaceDuplicateGroup(
                    target = requireNotNull(members.first().targetKey),
                    members = members,
                )
            }
            .sortedByListKey { group -> memberKey(group.members.first(), byId, pages) }
    }

    /**
     * 最後の1個のguard（spec 507。確認面のtoggle時とRemove dispatch直前の
     * 2境界で同じ関数を使う）。[selection] がいずれかのグループの全メンバーを
     * 含むとき、その最初（視覚順）のグループを返す。グループに選択できない
     * メンバー（ロック等）が1個でもある場合、このguardは発火しない — 選択され
     * ていない対象外メンバーが残る個体になるため。重複でない項目の選択は
     * 判定に影響しない。
     */
    fun fullySelectedGroup(
        groups: List<EditSurfaceDuplicateGroup>,
        selection: Collection<Int>,
    ): EditSurfaceDuplicateGroup? = groups.firstOrNull { group -> group.members.all { it.id in selection } }

    /**
     * メンバー行の選択可否（確認面の表示用）。#449のeligibilityに、セッションで
     * 処理済み（touched）の再操作禁止を加えたもの。touched itemは図に残る
     * （フォルダ追加・移動は行を残す）ため、確認面では理由つきで非選択として
     * 示す（Removal済みitemは図ごと消えるので行自体が現れない）。
     */
    fun rowSelectable(item: EditSurfaceItem, touchedIds: Set<Int>): Boolean = item.eligibility == SelectionEligibility.SELECTABLE && item.id !in touchedIds

    /** メンバーの表示順キー。フォルダ内は親フォルダの位置に従属させる。 */
    private fun memberKey(item: EditSurfaceItem, byId: Map<Int, EditSurfaceItem>, pages: List<Int>): List<Int> = when {
        item.container == HomeEditContainers.DESKTOP ->
            listOf(0, pageIndex(pages, item.screenId), item.cellY, item.cellX, 0, item.id)

        item.container > 0 || isEditSurfaceNewFolderKey(item.container) -> {
            val parent = byId[item.container]
            if (parent == null) {
                listOf(1, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, item.rank, item.id)
            } else {
                listOf(1, pageIndex(pages, parent.screenId), parent.cellY, parent.cellX, item.rank, item.id)
            }
        }

        item.container == HomeEditContainers.HOTSEAT ->
            listOf(2, item.rank, 0, 0, 0, item.id)

        else -> listOf(3, item.id, 0, 0, 0, 0)
    }

    /** [List<Int>]キー（辞書順）で並べ替える。`List` はComparableでないため明示比較。 */
    private fun <T> List<T>.sortedByListKey(key: (T) -> List<Int>): List<T> = map { it to key(it) }
        .sortedWith { left, right -> compareKeys(left.second, right.second) }
        .map { it.first }

    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    private fun pageIndex(pages: List<Int>, screenId: Int): Int = pages.indexOf(screenId).let { if (it < 0) Int.MAX_VALUE else it }
}
