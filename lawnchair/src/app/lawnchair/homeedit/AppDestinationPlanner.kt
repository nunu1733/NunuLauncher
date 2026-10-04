/*
 * Issue #497: pure planning for the new-app destination policy
 * (ADR-0015 Decisions 3, 4, 6, 8, 10). Android-free: the classifier and the
 * planner import nothing from the launcher model, DB, or UI layers; they
 * consume the shared HomeEditSnapshot projection used by the #448 planners.
 * The closed result deliberately carries no coordinates: an upstream-default
 * placement is computed inside MODEL_WRITER admission with the upstream
 * WorkspaceItemSpaceFinder semantics (spec 497 Open questions 10).
 */
package app.lawnchair.homeedit

/**
 * ポリシースナップショット（Domain language: spec 497）。自動追加1件の
 * queue投入時にcaptureされ、queueとともに永続化される配置先決定の不変入力
 * （ADR-0015 Decision 10）。flush時は読むだけであり、current policyから
 * 再生成しない。wire formatとの変換はplatform側アダプタが行う。
 */
data class DestinationPolicySnapshot(
    val kind: Kind,
    /** [Kind.FOLDER] のときだけ意味を持つ指定フォルダのfavorites行id。 */
    val folderId: Int,
    val userSerial: Long,
    val packageName: String,
) {
    enum class Kind { UPSTREAM, FOLDER }
}

/** 自動追加されるアプリのidentity（対象アイテムの同定。ADR-0015 Decision 10）。 */
data class IncomingInstall(
    val userSerial: Long,
    val packageName: String,
)

/**
 * ポリシースナップショット部分と基底queue entryのidentityの照合結果。
 * IDENTITY_MISMATCHは「snapshot部分はdecodeできるが対象と一致しない
 * （ペアリング破損）」であり、無変更でtypedに拒否される（spec 497 Open
 * questions 9）。欠損（raw無し。旧format）と破損（decode不能）はいずれも
 * SNAPSHOT_INVALIDとして上流既定へ明示fallbackする。
 */
enum class DestinationSnapshotValidity { VALID, MISSING, INVALID, IDENTITY_MISMATCH }

data class DestinationSnapshotClassification(
    val validity: DestinationSnapshotValidity,
    val policy: DestinationPolicySnapshot?,
)

/** Typedなフォールバック理由（ADR-0015 Decision 4/8/10）。設定行の通知とFileLogに出る。 */
enum class AppDestinationFallbackReason {
    FOLDER_MISSING,
    PROFILE_MISMATCH,
    DOCK_FOLDER,
    CONSTRAINT_VIOLATION,
    SNAPSHOT_INVALID,
}

/**
 * 配置先決定のclosed result（ADR-0015 Decision 8）。同一の純粋計画関数を
 * stage-2（admission内）で現状態へ再実行した結果がそのまま採用される。
 */
sealed interface AppDestinationPlan {
    /**
     * 指定フォルダへの末尾rank追加。既存子のcaptured rankを変えない
     * （Decision 6。lock状態の読み取りは検証入力に要求しない。spec 497
     * Open questions 6）。
     */
    data class FolderTarget(val folderId: Int, val rank: Int) : AppDestinationPlan

    /**
     * 上流の既定（空きセル）へ。reasonはフォールバック理由であり、
     * 記録と設定行での一度だけの通知の対象になる。
     */
    data class UpstreamDefault(val reason: AppDestinationFallbackReason) : AppDestinationPlan

    /** 書かない。書込み経路のinvariant failure（無変更・typed failure）。 */
    data class Reject(val reason: AppDestinationFallbackReason) : AppDestinationPlan
}

/**
 * 永続化されたsnapshotの妥当性分類（純粋関数。JVM testのcanonical対象）。
 * current policyは一切読まない（ADR-0015 Decision 10）。
 */
object AppDestinationClassifier {

    fun classify(
        policy: DestinationPolicySnapshot?,
        snapshotPersisted: Boolean,
        baseUserSerial: Long,
        basePackageName: String,
    ): DestinationSnapshotClassification {
        if (!snapshotPersisted) {
            return DestinationSnapshotClassification(DestinationSnapshotValidity.MISSING, null)
        }
        if (policy == null) {
            return DestinationSnapshotClassification(DestinationSnapshotValidity.INVALID, null)
        }
        if (policy.userSerial != baseUserSerial || policy.packageName != basePackageName) {
            return DestinationSnapshotClassification(
                DestinationSnapshotValidity.IDENTITY_MISMATCH,
                policy,
            )
        }
        return DestinationSnapshotClassification(DestinationSnapshotValidity.VALID, policy)
    }
}

/**
 * 配置先ポリシーの純粋計画関数（ADR-0015 Decision 3/4）。入力は現状態投影
 * （HomeEditSnapshot）・typedなpolicy snapshot・対象identity。出力はclosed
 * result。指定フォルダの存在・profile分離・Dock・配置制約と末尾rankを検証する。
 * 「満杯」はfallback条件としない（Decision 4。上流folderにハードな上限はなく
 * ページングで拡張するため、末尾rank追加は常に成立する）。
 */
object AppDestinationPlanner {

    fun plan(
        snapshot: HomeEditSnapshot,
        policy: DestinationPolicySnapshot,
        incoming: IncomingInstall,
    ): AppDestinationPlan {
        if (policy.kind != DestinationPolicySnapshot.Kind.FOLDER) {
            // 定義上、upstream選択はroutingで既定経路へ流れるため本関数には
            // 到達しない。到達した場合も決定論的に既定へ落とす（typedに記録）。
            return AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.SNAPSHOT_INVALID)
        }

        val folder = snapshot.items.firstOrNull {
            it.id == policy.folderId && it.itemType == HomeEditItemTypes.FOLDER
        } ?: return AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.FOLDER_MISSING)

        // Profile分離は書込み検証の必須条件（Decision 4。work profileのアプリを
        // 個人側フォルダへ入れない）。
        if (folder.userSerial != incoming.userSerial) {
            return AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.PROFILE_MISMATCH)
        }

        // Dockにあるフォルダは指定できない（Decision 4。第1段では新種の書込みを
        // 作らない）。指定後に移された場合も既定へ戻す。
        if (folder.container != HomeEditContainers.DESKTOP) {
            return AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.DOCK_FOLDER)
        }

        // 配置制約: フォルダ行がdevice profile内の有効なscreen/cellに置かれている
        // こと（書込み前の計画関数がtypedに検出できる観測可能な制約違反のみを
        // fallback条件とする。Decision 4）。
        if (folder.screenId !in snapshot.screenIds ||
            folder.cellX < 0 ||
            folder.cellX >= snapshot.columnCount ||
            folder.cellY < 0 ||
            folder.cellY >= snapshot.rowCount
        ) {
            return AppDestinationPlan.UpstreamDefault(AppDestinationFallbackReason.CONSTRAINT_VIOLATION)
        }

        // 末尾rank（既存子のrankを変えない。Decision 6。#448のフォルダ追加と
        // 同じ規約）。
        val rank = snapshot.items.count { it.container == policy.folderId }
        return AppDestinationPlan.FolderTarget(policy.folderId, rank)
    }
}
