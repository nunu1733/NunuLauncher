/*
 * Issue #449: pure data types for the visual edit surface session (ADR-0014
 * case B). Android-free: session state and its planners import nothing from
 * the launcher model, DB, or UI layers. organizer public types are
 * platform-free canonical data and are the shared vocabulary between the
 * capture, the session plan, and the apply-plan builder.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.ImmutableByteString
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.TargetKey

/**
 * Session-synthetic ids live in two disjoint ranges that never collide with
 * each other or with persisted favorites rowids (which are positive):
 * - session folders: [Int.MIN_VALUE + 1, Int.MIN_VALUE + 1 + SYNTHETIC_KEY_RANGE)
 * - reserved regions: [Int.MAX_VALUE - SYNTHETIC_KEY_RANGE + 1, Int.MAX_VALUE]
 * The container constants (DESKTOP/HOTSEAT, around -100) sit between the
 * ranges and only ever appear in [HomeEditItem.container], never as an item id.
 */
const val SYNTHETIC_KEY_RANGE: Int = 1 shl 20

/** Session-synthetic id for the [ordinal]-th (0-based) session folder. */
fun editSurfaceNewFolderKey(ordinal: Int): Int {
    require(ordinal in 0 until SYNTHETIC_KEY_RANGE) { "ordinal out of synthetic range: $ordinal" }
    return Int.MIN_VALUE + 1 + ordinal
}

/** True only inside the session-folder synthetic range. */
fun isEditSurfaceNewFolderKey(id: Int): Boolean = id in Int.MIN_VALUE + 1 until Int.MIN_VALUE + 1 + SYNTHETIC_KEY_RANGE

/**
 * Session-synthetic id for the [index]-th (0-based) platform-owned reserved
 * region (QSB etc.) projected into the edit-surface snapshot.
 */
fun editSurfaceReservationKey(index: Int): Int {
    require(index in 0 until SYNTHETIC_KEY_RANGE) { "index out of synthetic range: $index" }
    return Int.MAX_VALUE - index
}

/** True only inside the reserved-region synthetic range. */
fun isEditSurfaceReservationKey(id: Int): Boolean = id in Int.MAX_VALUE - SYNTHETIC_KEY_RANGE + 1..Int.MAX_VALUE

/**
 * 編集セッション内で確定済みの1アイテム分の変更（適用待ち）。実行順に保持され、
 * 図の表示と常に一致する（Domain language: セッション計画）。
 */
sealed interface SessionItemChange {
    /** 対象アイテム（favorites行idベースの識別。#448のHomeEditItem.idと同じ値域）。 */
    val targetId: Int

    /** 既存ページへの移動（移動先セルはセッション計画時に確定済み）。 */
    data class PageMove(
        override val targetId: Int,
        val screenId: Int,
        val cell: GridCell,
    ) : SessionItemChange

    /**
     * フォルダへの追加。[folderId] は既存フォルダのfavorites行idまたは
     * [editSurfaceNewFolderKey] の発行するセッション内id。
     */
    data class FolderAdd(
        override val targetId: Int,
        val folderId: Int,
        val rank: Int,
    ) : SessionItemChange

    /** ホームから外す（行削除）。 */
    data class Removal(
        override val targetId: Int,
    ) : SessionItemChange
}

/** 「新しいフォルダを作る」で作るセッション内フォルダ（1x1、無題）。 */
data class SessionNewFolder(
    val ordinal: Int,
    val screenId: Int,
    val cellX: Int,
    val cellY: Int,
    /** メンバーのfavorites行id（rank順=視覚順）。先頭メンバーの元セルが置き先。 */
    val memberIds: List<Int>,
    /** メンバーのprofile（フォルダのprofile。混在選択はplannerが拒否するため単一）。 */
    val userSerial: Long,
) {
    val profile: app.lawnchair.organizer.planning.ProfileId
        get() = app.lawnchair.organizer.planning.ProfileId(userSerial.toString())
}

/**
 * 編集セッション（Domain language: spec 449）。開いてから確定またはキャンセルで
 * 終わるまでの1回の複数選択編集の試行のうち、適用待ちの変更の集まり。
 * process内のみで存在し、永続化しない。空のセッション（変更0件）は確定できない。
 */
data class EditSurfaceSession(
    val newFolders: List<SessionNewFolder>,
    /** 実行順（視覚順）に保持された確定済み変更。空の間は確定不可。 */
    val changes: List<SessionItemChange>,
) {
    val isEmpty: Boolean get() = changes.isEmpty() && newFolders.isEmpty()

    /** 変更済み（再操作対象外）のアイテムid。 */
    val touchedIds: Set<Int> get() = changes.map { it.targetId }.toSet()

    companion object {
        val EMPTY = EditSurfaceSession(newFolders = emptyList(), changes = emptyList())
    }
}

/** 図上アイテムの選択可否（spec: 対象絞り込みとロック扱い）。 */
enum class SelectionEligibility {
    /** 選択できる（workspace配置のアプリ/ショートカット、idあり、ロック解除済み）。 */
    SELECTABLE,

    /** ロック中のため選択できない（tap時にその旨を示す）。 */
    LOCKED,

    /** ロック状態が確認できないため選択できない（ADR-0004移行由来の行など）。 */
    LOCK_UNKNOWN,

    /** 対象外（widget、フォルダ自身、app pair、dock、予約領域など）。 */
    UNSUPPORTED,
}

/**
 * 図の1アイテム分の表示投影。幾何（配置）はcaptureにセッション計画を適用した
 * 作業投影（[EditSurfaceProjection.workingSnapshot]）が単一の権威であり、
 * ラベル・icon・lock等のメタデータはcapture時点の値を写す。
 * icon解決自体はUI層が行う（[iconBytes] はfavorites ICON列の転写で、
 * 通常アプリはabsent。absent時は [targetKey] 経由のIconCache解決→placeholder）。
 */
data class EditSurfaceItem(
    /** favorites行id。セッション内の新規フォルダ行は [editSurfaceNewFolderKey]。 */
    val id: Int,
    val itemType: Int,
    val label: String?,
    val iconBytes: ImmutableByteString?,
    /** icon解決の入力（TargetKey + profile。セッション内作成行はnull）。 */
    val targetKey: TargetKey?,
    val userSerial: Long,
    val lockState: OrganizerLockState,
    val container: Int,
    val screenId: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val rank: Int,
    /** captureに存在しないセッション内作成の行（新規フォルダ）。 */
    val isSessionCreated: Boolean,
) {
    val isOnWorkspace: Boolean get() = container == HomeEditContainers.DESKTOP

    /** 選択可否の判定（specどおり。派生値であり毎回導出する）。 */
    val eligibility: SelectionEligibility
        get() = when {
            !isOnWorkspace -> SelectionEligibility.UNSUPPORTED

            itemType != HomeEditItemTypes.APPLICATION && itemType != HomeEditItemTypes.DEEP_SHORTCUT ->
                SelectionEligibility.UNSUPPORTED

            lockState == OrganizerLockState.LOCKED -> SelectionEligibility.LOCKED

            lockState == OrganizerLockState.UNKNOWN -> SelectionEligibility.LOCK_UNKNOWN

            else -> SelectionEligibility.SELECTABLE
        }
}

/** アクションの種別と、確定時に使うパラメータ（セッション計画への入力）。 */
sealed interface PendingSessionAction {
    /** 既存ページへ移動する。 */
    data class MoveToPage(val screenId: Int) : PendingSessionAction

    /** 既存フォルダへ入れる（favorites行id）。 */
    data class AddToFolder(val folderId: Int) : PendingSessionAction

    /** 選択から新しいフォルダを作る（置き先=視覚順先頭の元セル）。 */
    data object CreateFolder : PendingSessionAction

    /** ホームから外す。 */
    data object RemoveFromHome : PendingSessionAction
}

/** セッション計画の構築結果（all-or-nothing。1個でも拒否されれば全体が適用されない）。 */
sealed interface SessionPlanResult {
    data class Applied(val session: EditSurfaceSession) : SessionPlanResult

    data class Rejected(val reason: HomeEditRejection) : SessionPlanResult
}
