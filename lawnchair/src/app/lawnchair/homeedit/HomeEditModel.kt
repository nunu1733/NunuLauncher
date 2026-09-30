/*
 * Issue #448: pure data types for per-item edit actions (ADR-0013 target (a)).
 * Android-free: the planner and its tests import nothing from the launcher
 * model, DB, or UI layers. Layout constants mirror
 * LauncherSettings.Favorites values; a unit test pins the equality.
 */
package app.lawnchair.homeedit

/** Container ids mirrored from LauncherSettings.Favorites (values pinned by test). */
object HomeEditContainers {
    const val DESKTOP = -100
    const val HOTSEAT = -101
}

/** Item types mirrored from LauncherSettings.Favorites (values pinned by test). */
object HomeEditItemTypes {
    const val APPLICATION = 0
    const val SHORTCUT = 1
    const val FOLDER = 2
    const val APP_WIDGET = 4
    const val CUSTOM_APP_WIDGET = 5
    const val DEEP_SHORTCUT = 6
    const val APP_PAIR = 10

    /**
     * Issue #449: synthetic item type for platform-owned reserved cells (QSB
     * etc.) projected into the edit-surface snapshot. Never a real favorites
     * row type: the shared planner only counts DESKTOP occupancy, so a row
     * with this type reserves its cells without ever being selectable.
     */
    const val RESERVED = Int.MIN_VALUE
}

/** One favorites-row projection. Plain data. */
data class HomeEditItem(
    val id: Int,
    val container: Int,
    val screenId: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val itemType: Int,
    val rank: Int,
    val userSerial: Long,
)

/**
 * 編集snapshot（Domain language: spec 448）。1回の編集意図の計画と検証のために
 * 現在のホームレイアウトを投影した読み取り専用の入力。
 */
data class HomeEditSnapshot(
    val columnCount: Int,
    val rowCount: Int,
    val screenIds: List<Int>,
    val items: List<HomeEditItem>,
    /**
     * Issue #450: current hotseat capacity (numHotseatIcons). Hotseat rows
     * use `screen` as the slot index, so an undo back to the hotseat must
     * verify the recorded slot against the CURRENT device profile — a grid
     * change can shrink it below the recorded slot (spec scenario: 端末の格子
     * が変わっていれば書かずに失敗する).
     */
    val hotseatCount: Int = 0,
) {
    fun itemById(id: Int): HomeEditItem? = items.firstOrNull { it.id == id }
}

/**
 * 編集意図（Domain language: spec 448）。対象itemIdと移動先の組に加え、
 * action開始時（popup表示時点）の対象配置をpreconditionとして持つ。
 * 計画関数は現在の配置がこのpreconditionと一致しない場合STALEで拒否する
 * （dialog表示後〜確定前に対象が移動した場合のstage-1拒否）。
 */
sealed interface HomeEditIntent {
    /** action開始時の対象の配置。全intentで共通のprecondition。 */
    val sourcePlacement: HomeEditItem

    /** 既存ページへの移動。移動先セルは計画関数が決定する。 */
    data class MoveToPage(
        override val sourcePlacement: HomeEditItem,
        val targetScreenId: Int,
    ) : HomeEditIntent

    /** 既存フォルダへの追加（rank末尾＝選択時の件数）。 */
    data class AddToFolder(
        override val sourcePlacement: HomeEditItem,
        val folderId: Int,
    ) : HomeEditIntent

    /** 新規フォルダの作成。選択済みの置き先ページを必ず含む（spec Scope）。 */
    data class CreateFolderAndAdd(
        override val sourcePlacement: HomeEditItem,
        val destinationScreenId: Int,
    ) : HomeEditIntent

    /**
     * Issue #449: 新規フォルダの作成（置き先セル指定）。視覚的編集画面の
     * 「新しいフォルダを作る」は置き先を「選択の先頭アイテムの元セル」に固定する
     * ため、既存 CreateFolderAndAdd（row-majorの最初の空きセル）では保証できない。
     * 指定セルが範囲外・占有の場合はNO_SPACEで拒否する（additive拡張であり、
     * popup経路が使う既存4 intentの振る舞いは不変）。
     */
    data class CreateFolderAt(
        override val sourcePlacement: HomeEditItem,
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
    ) : HomeEditIntent

    /** ホームから外す（1行削除。アンインストールではない）。 */
    data class Remove(override val sourcePlacement: HomeEditItem) : HomeEditIntent
}

/** Typedな拒否理由。UI側でlocalized文字列へ対応させる。 */
enum class HomeEditRejection {
    STALE,
    ITEM_GONE,
    NO_SPACE,
    REDUNDANT,
    FOLDER_GONE,
    PROFILE_MISMATCH,
    UNSUPPORTED,
}

/** 純粋計画関数のclosed result。 */
sealed interface HomeEditPlan {
    /** 成功plan。同一intent・同一snapshotからは常に同一値（決定性）。 */
    sealed interface Success : HomeEditPlan {
        /** 計画時点の対象アイテムの配置。stage 2の対象一致検証に使う。 */
        val targetItemPlacement: HomeEditItem
    }

    /** 行の移動。containerはDESKTOP（ページ移動）またはfolderId（フォルダ追加）。 */
    data class Move(
        override val targetItemPlacement: HomeEditItem,
        val container: Int,
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
        val rank: Int,
    ) : Success

    /** 新規フォルダの作成（1x1）と対象アイテムのrank 0追加。 */
    data class CreateFolder(
        override val targetItemPlacement: HomeEditItem,
        val screenId: Int,
        val cellX: Int,
        val cellY: Int,
    ) : Success

    /** 対象行の削除。 */
    data class RemoveItem(override val targetItemPlacement: HomeEditItem) : Success

    data class Rejected(val reason: HomeEditRejection) : HomeEditPlan
}
