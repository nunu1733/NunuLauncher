/*
 * Issue #497: pure mappers for the destination-policy settings row — the
 * three-choice → summary-state transition (AC-1: always consistent with the
 * existing pref_add_icon_to_home toggle) and the summary/notice resource
 * mappings (AC-11: resource-derived, never empty, never an internal id).
 * JVM-tested as the oracle (HomeEditUndoRecoveryText precedent). "Don't
 * add" is the toggle's off state itself; this layer never owns a stored
 * "don't add" policy value (ADR-0015 Decision 15).
 */
package app.lawnchair.homeedit

import com.android.launcher3.R
import com.android.launcher3.model.DirectEditContract

/** 設定行のsummary状態。FOLDER_*は指定フォルダの実在・titleで区別する。 */
enum class DestinationSummaryKind {
    DONT_ADD,
    UPSTREAM,
    FOLDER_NAMED,
    FOLDER_UNTITLED,
    FOLDER_MISSING,
}

object AppDestinationSummaryState {

    /**
     * 3択と既存スイッチの整合（spec AC-1）: スイッチOFFはどのpolicy値でも
     * DONT_ADDを表示し、フォルダ指定はスイッチONのときだけ表示する。
     * 指定idが実在しない場合はFOLDER_MISSING（有効な指定がない状態を示す。
     * 同名フォルダ再作成でも復活しない。spec AC-3）。
     */
    fun resolve(
        addIconOn: Boolean,
        designatedFolderId: Int?,
        folderExists: Boolean,
        folderTitle: String?,
    ): DestinationSummaryKind = when {
        !addIconOn -> DestinationSummaryKind.DONT_ADD
        designatedFolderId == null -> DestinationSummaryKind.UPSTREAM
        !folderExists -> DestinationSummaryKind.FOLDER_MISSING
        !folderTitle.isNullOrEmpty() -> DestinationSummaryKind.FOLDER_NAMED
        else -> DestinationSummaryKind.FOLDER_UNTITLED
    }
}

/** summary状態 → 文字列リソース（resource-derived。空にならない）。
 *  FOLDER_NAMED / FOLDER_UNTITLEDは書式付きresourceにtitle（または既定label）を
 *  引数として渡す（既定labelのフォルダは「フォルダ」表示で同じresourceに載る）。 */
fun destinationSummaryText(kind: DestinationSummaryKind): Int = when (kind) {
    DestinationSummaryKind.DONT_ADD -> R.string.destination_policy_summary_dont_add
    DestinationSummaryKind.UPSTREAM -> R.string.destination_policy_summary_upstream
    DestinationSummaryKind.FOLDER_MISSING -> R.string.destination_policy_summary_folder_missing
    DestinationSummaryKind.FOLDER_NAMED -> R.string.destination_policy_summary_folder
    DestinationSummaryKind.FOLDER_UNTITLED -> R.string.destination_policy_summary_folder
}

/** fallback理由キー → one-shot通知の文字列リソース（package名は出力しない）。 */
fun destinationNoticeText(reasonKey: String): Int = when (reasonKey) {
    DirectEditContract.DEST_FOLDER_MISSING -> R.string.destination_policy_notice_missing
    DirectEditContract.DEST_PROFILE_MISMATCH -> R.string.destination_policy_notice_profile
    DirectEditContract.DEST_DOCK_FOLDER -> R.string.destination_policy_notice_dock
    DirectEditContract.DEST_CONSTRAINT_VIOLATION -> R.string.destination_policy_notice_constraint
    else -> R.string.destination_policy_notice_snapshot
}
