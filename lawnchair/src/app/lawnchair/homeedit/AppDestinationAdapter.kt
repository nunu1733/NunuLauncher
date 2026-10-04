/*
 * Issue #497: adapter between the platform destination contract and the pure
 * destination planner, plus the process-side bridge (resolver registration,
 * result handling). The stage-2 validator is the destination-policy half of
 * ADR-0013 contract 2 and runs inside MODEL_WRITER admission on the model
 * thread; the result callback records fallbacks and refreshes the folder UI
 * after a successful write. Package names never appear in any output
 * (organizer-diagnostics §7 Never classification, spec 497).
 */
package app.lawnchair.homeedit

import app.lawnchair.homeedit.getHomescreenIconByItemId
import android.content.Context
import app.lawnchair.homeedit.getHomescreenIconByItemId
import android.os.UserHandle
import app.lawnchair.homeedit.getHomescreenIconByItemId
import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.getHomescreenIconByItemId
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.Launcher
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.LauncherSettings.Favorites
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.folder.FolderIcon
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.logging.FileLog
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.model.DirectEditContract
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.model.data.FolderInfo
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.model.data.ItemInfo
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.pm.UserCache
import app.lawnchair.homeedit.getHomescreenIconByItemId
import com.android.launcher3.util.Executors.MAIN_EXECUTOR

/** Wire format変換（DirectEditContractのencodingと1:1。JVM testで往復を固定する）。 */
object AppDestinationSnapshotCodec {

    /** 欠損・破損のrawはnull（typedにはMISSING/INVALIDとして分類される）。 */
    fun fromWire(raw: String?): DestinationPolicySnapshot? {
        val parts = DirectEditContract.parseDestinationSnapshot(raw) ?: return null
        return DestinationPolicySnapshot(
            kind = if (parts[0] == DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM) {
                DestinationPolicySnapshot.Kind.UPSTREAM
            } else {
                DestinationPolicySnapshot.Kind.FOLDER
            },
            folderId = parts[1].toInt(),
            userSerial = parts[2].toLong(),
            packageName = parts[3],
        )
    }
}

fun appDestinationReasonKey(reason: AppDestinationFallbackReason): String = when (reason) {
    AppDestinationFallbackReason.FOLDER_MISSING -> DirectEditContract.DEST_FOLDER_MISSING
    AppDestinationFallbackReason.PROFILE_MISMATCH -> DirectEditContract.DEST_PROFILE_MISMATCH
    AppDestinationFallbackReason.DOCK_FOLDER -> DirectEditContract.DEST_DOCK_FOLDER
    AppDestinationFallbackReason.CONSTRAINT_VIOLATION -> DirectEditContract.DEST_CONSTRAINT_VIOLATION
    AppDestinationFallbackReason.SNAPSHOT_INVALID -> DirectEditContract.DEST_SNAPSHOT_INVALID
}

/**
 * Stage-2 validator（ADR-0013契約2の二段階目。ADR-0015 Decision 8/10）。
 * 永続化済みsnapshotを読むだけでcurrent policyを再読せず、同一の純粋計画
 * 関数を現状態へ再実行する。指定folderがstaleな場合は検証失敗ではなく
 * `UpstreamDefault(reason)`という有効planへ再計画する。snapshot部分の
 * identity不一致だけが無変更の`Reject(SNAPSHOT_INVALID)`になる（spec 497
 * Open questions 9）。
 */
class AppDestinationStage2Validator(
    private val rawSnapshot: String?,
    private val incoming: IncomingInstall,
) : DirectEditContract.DestinationValidator {

    override fun validate(current: DirectEditContract.Snapshot): DirectEditContract.DestinationDecision {
        val policy = AppDestinationSnapshotCodec.fromWire(rawSnapshot)
        val classification = AppDestinationClassifier.classify(
            policy = policy,
            snapshotPersisted = rawSnapshot != null,
            baseUserSerial = incoming.userSerial,
            basePackageName = incoming.packageName,
        )
        when (classification.validity) {
            DestinationSnapshotValidity.MISSING,
            DestinationSnapshotValidity.INVALID,
            -> return DirectEditContract.DestinationDecision.upstreamDefault(
                DirectEditContract.DEST_SNAPSHOT_INVALID,
            )

            DestinationSnapshotValidity.IDENTITY_MISMATCH ->
                return DirectEditContract
                    .DestinationDecision.reject(DirectEditContract.DEST_SNAPSHOT_INVALID)

            DestinationSnapshotValidity.VALID -> Unit
        }
        val snapshot = HomeEditSnapshotMapper.map(current)
        return when (val plan = AppDestinationPlanner.plan(snapshot, classification.policy!!, incoming)) {
            is AppDestinationPlan.FolderTarget -> DirectEditContract.DestinationDecision.folder(
                plan.folderId,
            )

            is AppDestinationPlan.UpstreamDefault ->
                DirectEditContract.DestinationDecision
                    .upstreamDefault(appDestinationReasonKey(plan.reason))

            is AppDestinationPlan.Reject -> DirectEditContract.DestinationDecision.reject(
                appDestinationReasonKey(plan.reason),
            )
        }
    }
}

/**
 * Process-side bridge. Registers the destination resolver at process start;
 * the capture side reads the current policy once per enqueue and the route
 * side reads only the persisted snapshot at flush time (ADR-0015 Decision
 * 10). Registered as one of the fork-owned hooks; with no registration the
 * platform side keeps the stock upstream behavior.
 */
object AppDestinationBridge {

    private const val LOG = "AppDestination"

    fun install(context: Context) {
        DirectEditContract.setDestinationResolver(
            Resolver(context.applicationContext),
        )
    }

    private class Resolver(private val appContext: Context) : DirectEditContract.DestinationResolver {

        /**
         * Enqueue時のcapture（自動追加overloadからのみ呼ばれる）。policy選択
         * と指定folder idをこの時点で固定する。選択がupstreamのときも必ず
         * snapshotを永続化する（snapshotの有無が旧format entryとの区別になる）。
         */
        override fun captureDestination(
            context: Context,
            packageName: String,
            user: UserHandle,
        ): String {
            val userSerial = UserCache.getInstance(context).getSerialNumberForUser(user)
            val folderId = AppDestinationPolicyPrefs.designatedFolderId(context)
            return if (folderId != null) {
                DirectEditContract.serializeDestinationSnapshot(
                    DirectEditContract.DEST_SNAPSHOT_KIND_FOLDER,
                    folderId,
                    userSerial,
                    packageName,
                )
            } else {
                DirectEditContract.serializeDestinationSnapshot(
                    DirectEditContract.DEST_SNAPSHOT_KIND_UPSTREAM,
                    0,
                    userSerial,
                    packageName,
                )
            }
        }

        /**
         * flush時のrouting。persisted snapshotを読むだけ（current policyは
         * 再読しない）。既定経路（stock）に戻すのは「snapshotが完全にdecode
         * でき、かつ基底entryのidentityと一致する有効なUPSTREAM選択」のとき
         * だけ（Phase 2 review round 1。prefix照合では破損・identity不一致を
         * 見逃す）。それ以外はpolicy routeへ流し、stage-2のclassifierと
         * validatorにtypedな判断を委ねる。
         */
        override fun route(
            raw: String?,
            userSerial: Long,
            packageName: String,
        ): DirectEditContract.DestinationRoute? {
            if (DirectEditContract.isValidUpstreamSnapshot(raw, userSerial, packageName)) {
                return null
            }
            return DirectEditContract.DestinationRoute(
                true,
                AppDestinationStage2Validator(raw, IncomingInstall(userSerial, packageName)),
                ResultCallback(appContext),
            )
        }
    }

    /**
     * Admitted結果の記録と通知。fallback（`UpstreamDefault(reason)`）は
     * FileLogへtypedに記録され（package名は出力しない）、設定行で一度だけ
     * 消費される通知stateへ積まれる。Rejectは無変更のtyped failureとして
     * 記録のみ。フォルダ配置の成功時はfolder iconの表示をUI threadで
     * 更新する（model thread側のcontents addは#448と同じく無音）。
     */
    private class ResultCallback(private val appContext: Context) : DirectEditContract.DestinationResultCallback {

        override fun onResult(
            success: Boolean,
            container: Int,
            screenId: Int,
            cellX: Int,
            cellY: Int,
            rank: Int,
            newScreenId: Int,
            reason: String?,
        ) {
            if (!success) {
                FileLog.e(LOG, "destination policy write rejected: $reason")
                return
            }
            if (reason != null) {
                FileLog.d(LOG, "destination policy fallback to upstream default: $reason")
                AppDestinationNotice.postPending(appContext, reason)
            }
            if (container != Favorites.CONTAINER_DESKTOP) {
                MAIN_EXECUTOR.execute { refreshFolderIcon(container, rank) }
            }
        }

        private fun refreshFolderIcon(folderId: Int, rank: Int) {
            val launcher = Launcher.ACTIVITY_TRACKER.getCreatedActivity()
                as? LawnchairLauncher ?: return
            val folderIcon = launcher.workspace?.getHomescreenIconByItemId(folderId) as? FolderIcon
                ?: return
            val folder = folderIcon.mInfo as? FolderInfo ?: return
            // The freshly appended child sits at the tail of the model list;
            // the FolderIcon preview refresh (view-only onAdd, #448 precedent)
            // is the UI-side counterpart of the silent model add.
            val child: ItemInfo = folder.getContents().lastOrNull() ?: return
            folderIcon.onAdd(child, rank)
        }
    }
}

/**
 * 配置先ポリシーの選択値の読み書き（既存の「ホームにアイコンを追加」と同じ
 * SharedPreferencesに載るtyped pref）。値は `upstream` または `folder:<id>`
 * （「追加しない」は既存 `pref_add_icon_to_home` のOFFそのものであり、本prefに
 * 値を持たない。ADR-0015 Decision 15、spec 497 Open questions 4）。
 */
object AppDestinationPolicyPrefs {

    const val VALUE_UPSTREAM = "upstream"
    const val VALUE_FOLDER_PREFIX = "folder:"

    fun designatedFolderId(context: Context): Int? = folderIdFromValue(PreferenceManager.getInstance(context).newAppDestination.get())

    /** `folder:<id>` 値からフォルダidを取り出す（upstream値はnull）。 */
    fun folderIdFromValue(value: String): Int? = value.takeIf { it.startsWith(VALUE_FOLDER_PREFIX) }
        ?.removePrefix(VALUE_FOLDER_PREFIX)?.toIntOrNull()

    fun setDesignatedFolder(context: Context, folderId: Int) {
        PreferenceManager.getInstance(context).newAppDestination.set("$VALUE_FOLDER_PREFIX$folderId")
    }

    fun setUpstream(context: Context) {
        PreferenceManager.getInstance(context).newAppDestination.set(VALUE_UPSTREAM)
    }
}
