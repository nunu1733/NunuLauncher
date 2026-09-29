/*
 * Issue #449: full-screen activity hosting the visual edit surface (ADR-0014
 * case B). Composition root: it requests the read-only capture through
 * HomeEditSurfaceAccess, projects it into the pure homeedit types, resolves
 * display icons (custom icon bytes first, then TargetKey + profile through the
 * launcher icon path, placeholder when unresolvable), and drives the session
 * with the pure session planner. The confirm applies the session-built plan
 * through the existing organizer apply path (one session = one apply = one
 * recovery point).
 */
package app.lawnchair.homeedit.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.EditSurfaceApplyPlan
import app.lawnchair.homeedit.EditSurfaceDiagram
import app.lawnchair.homeedit.EditSurfacePlanBuilder
import app.lawnchair.homeedit.EditSurfaceProjection
import app.lawnchair.homeedit.EditSurfaceSession
import app.lawnchair.homeedit.EditSurfaceSessionPlanner
import app.lawnchair.homeedit.HomeEditApplyReceipt
import app.lawnchair.homeedit.HomeEditRejection
import app.lawnchair.homeedit.HomeEditSnapshot
import app.lawnchair.homeedit.HomeEditSurfaceAccess
import app.lawnchair.homeedit.HomeEditUndoEntry
import app.lawnchair.homeedit.HomeEditUndoRecord
import app.lawnchair.homeedit.PendingSessionAction
import app.lawnchair.homeedit.SelectionEligibility
import app.lawnchair.homeedit.SessionPlanResult
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.icons.IconCache
import com.android.launcher3.pm.UserCache

class HomeEditSurfaceActivity : ComponentActivity() {

    private val access by lazy { HomeEditSurfaceAccess.get(this) }

    /**
     * 適用とcaptureの実行スレッド。MODEL_EXECUTOR（単一のlauncher-loader
     * Looperスレッド）は使わない: 相関reloadはLoaderTaskをMODEL_EXECUTORへ
     * postし、その完了を待つ呼び出し元と同じスレッドで待つと、LoaderTaskが
     * 永遠に実行されずタイムアウトする（MODEL_EXECUTOR上での自己待ち）。
     * organizer runのapply（Dispatchers.IO）と同じく、待ちの間に
     * MODEL_EXECUTORを塞がない専用スレッドで実行する。
     */
    private val surfaceExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "homeedit-surface") } }

    companion object {
        /** 編集画面を開く（workspace長押しメニューとOrganizer hubの共通入口）。 */
        fun start(context: Context) {
            context.startActivity(Intent(context, HomeEditSurfaceActivity::class.java))
        }
    }

    // capture時点のorganizer state（適用計画構築の入力。process内のみで永続化しない）。
    private var captureState: app.lawnchair.organizer.application.public.LayoutState? = null
    private var captureRevision: RevisionId? = null

    private var captureSnapshot: HomeEditSnapshot? = null
    private var session by mutableStateOf(EditSurfaceSession.EMPTY)
    private val selection = mutableStateListOf<Int>()
    private var diagram by mutableStateOf<EditSurfaceDiagram?>(null)
    private var icons by mutableStateOf<Map<Int, ImageBitmap?>>(emptyMap())
    private var reasonRes by mutableStateOf<Int?>(null)
    private var busy by mutableStateOf(false)
    private var applying = false

    /** セッション開始時captureにUNKNOWNロック行があるか（確定ゲート。AC-7）。 */
    private var captureHasUnknownLock by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Content() }
        reloadCapture()
    }

    @Composable
    private fun Content() {
        val currentDiagram = diagram
        val currentReasonRes = reasonRes
        if (currentDiagram == null) {
            LoadingContent()
            return
        }
        EditSurfaceScreen(
            diagram = currentDiagram,
            selection = selection.toList(),
            sessionChangeCount = session.changes.size,
            confirmGate = EditSurfaceSessionPlanner.confirmGate(
                session,
                if (captureHasUnknownLock) {
                    listOf(app.lawnchair.organizer.application.public.OrganizerLockState.UNKNOWN)
                } else {
                    emptyList()
                },
            ),
            icons = icons,
            reasonText = currentReasonRes?.let { stringResource(it) },
            busy = busy,
            onToggleSelection = ::toggleSelection,
            onCreateFolder = ::createFolder,
            onRemove = ::removeFromHome,
            onConfirm = ::confirm,
            onReset = ::resetSession,
            onCancel = { finish() },
            onPickPage = ::moveToPage,
            onPickFolder = ::addToFolder,
        )
    }

    private fun reloadCapture() {
        busy = true
        surfaceExecutor.execute {
            val captured = access.inspectCapture()
            if (captured == null) {
                runOnUiThread {
                    busy = false
                    reasonRes = R.string.edit_surface_error_capture_unavailable
                }
                return@execute
            }
            val layoutState = captured.layoutState
            val snapshot = EditSurfaceProjection.homeEditSnapshot(layoutState)
            val working = EditSurfaceProjection.workingSnapshot(snapshot, EditSurfaceSession.EMPTY)
            val newDiagram = EditSurfaceProjection.diagram(layoutState, working)
            val resolved = resolveIcons(newDiagram)
            runOnUiThread {
                captureState = layoutState
                captureRevision = captured.revision
                captureSnapshot = snapshot
                captureHasUnknownLock = layoutState.items.any {
                    it.lockState == app.lawnchair.organizer.application.public.OrganizerLockState.UNKNOWN
                }
                diagram = newDiagram
                icons = resolved
                session = EditSurfaceSession.EMPTY
                selection.clear()
                reasonRes = null
                busy = false
            }
        }
    }

    private fun toggleSelection(itemId: Int) {
        val currentDiagram = diagram ?: return
        val item = currentDiagram.itemById[itemId] ?: return
        when (item.eligibility) {
            SelectionEligibility.SELECTABLE -> {
                if (itemId !in session.touchedIds) {
                    if (itemId in selection) selection.remove(itemId) else selection.add(itemId)
                    reasonRes = null
                }
            }

            SelectionEligibility.LOCKED -> reasonRes = R.string.homeedit_lock_note_locked

            SelectionEligibility.LOCK_UNKNOWN -> reasonRes = R.string.edit_surface_error_lock_unknown

            SelectionEligibility.UNSUPPORTED -> Unit
        }
    }

    private fun runAction(action: PendingSessionAction) {
        val snapshot = captureSnapshot ?: return
        when (
            val result = EditSurfaceSessionPlanner.plan(
                snapshot,
                sessionLockStates(),
                session,
                selection.toList(),
                action,
            )
        ) {
            is SessionPlanResult.Applied -> {
                session = result.session
                // アクションを実行したアイテムは選択から外れる（spec決定済み）。
                selection.clear()
                reasonRes = null
            }

            is SessionPlanResult.Rejected -> reasonRes = rejectionText(result.reason)
        }
    }

    /** capture時点のlock状態（working投影にはlock列がないためcaptureから参照）。 */
    private fun sessionLockStates(): Map<Int, app.lawnchair.organizer.application.public.OrganizerLockState> {
        val state = captureState ?: return emptyMap()
        return state.items.mapNotNull { item ->
            val ref = item.ref as? app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem
                ?: return@mapNotNull null
            val id = ref.itemId.value.toIntOrNull() ?: return@mapNotNull null
            id to item.lockState
        }.toMap()
    }

    private fun moveToPage(screenId: Int) = runAction(PendingSessionAction.MoveToPage(screenId))

    // Issue #450: instrumentation hooks (same module, internal). They expose
    // the same production callbacks the UI wiring uses; no behavior change.
    internal fun firstSelectableItemIdForTest(): Int? = diagram?.items?.firstOrNull {
        it.eligibility == SelectionEligibility.SELECTABLE &&
            it.isOnWorkspace && it.screenId == 0
    }?.id

    internal fun toggleSelectionForTest(itemId: Int) = toggleSelection(itemId)

    /** Test-only: the second selectable item (different id, page 0). */
    internal fun secondSelectableItemIdForTest(excludeId: Int): Int? = diagram?.items?.firstOrNull {
        it.eligibility == SelectionEligibility.SELECTABLE &&
            it.isOnWorkspace && it.screenId == 0 && it.id != excludeId
    }?.id

    internal fun createFolderForTest() = createFolder()

    /** Test-only: the current typed reason (null when none). */
    internal fun reasonResForTest(): Int? = reasonRes

    /** Test-only: re-runs the capture (the same path reloadCapture uses). */
    internal fun recaptureForTest() = reloadCapture()

    /** Test-only: the last non-Applied apply result, for oracle diagnostics. */
    internal var lastApplyResultForTest: ApplyResult? = null

    /** Test-only: the last plan built by confirm(), for oracle diagnostics. */
    internal var lastPlanForTest: EditSurfaceApplyPlan? = null

    internal fun moveToPageForTest(screenId: Int) = moveToPage(screenId)

    private fun addToFolder(folderId: Int) = runAction(PendingSessionAction.AddToFolder(folderId))

    private fun createFolder() = runAction(PendingSessionAction.CreateFolder)

    private fun removeFromHome() = runAction(PendingSessionAction.RemoveFromHome)

    private fun resetSession() {
        session = EditSurfaceSession.EMPTY
        selection.clear()
        reasonRes = null
    }

    // Internal so the instrumentation oracle can drive the real #449 confirm
    // flow (capture → session → plan build → applyForUndo → handleApplyResult)
    // end to end; production callers are within this class only.
    internal fun confirm() {
        val layoutState = captureState ?: return
        val revision = captureRevision ?: return
        if (applying || session.isEmpty) return
        val versions = access.policyVersions()
        if (versions == null) {
            reasonRes = R.string.edit_surface_error_generic
            return
        }
        applying = true
        busy = true
        reasonRes = null
        surfaceExecutor.execute {
            val runId: RunId = access.newRunId()
            val built = EditSurfacePlanBuilder.build(
                layoutState,
                revision,
                session,
                versions.first,
                versions.second,
            )
            lastPlanForTest = built
            val receipt = when (built) {
                is EditSurfaceApplyPlan.Ready -> access.applyForUndo(built.plan, runId)

                is EditSurfaceApplyPlan.Empty -> null

                // 確定ゲートが空セッションを阻止済み（防御）
                is EditSurfaceApplyPlan.Inconsistent -> null
            }
            runOnUiThread { handleApplyResult(receipt, built) }
        }
    }

    // Internal so the instrumentation oracle can drive the #449 confirm-flow's
    // Applied branch directly (the flow the undo record + snackbar hook lives
    // in); production callers are unaffected.
    internal fun handleApplyResult(receipt: HomeEditApplyReceipt?, built: EditSurfaceApplyPlan) {
        val result = receipt?.result
        lastApplyResultForTest = if (result is ApplyResult.Applied) null else result
        applying = false
        busy = false
        when {
            result is ApplyResult.Applied -> {
                // 1回の適用と1個の復元点が完了。ホームは相関reloadで更新される。
                // Undo記録（spec 450）: pointId + 適用経路のverified post
                // revision（receipt正本。post-hoc captureではない）。snackbarは
                // 閉じたあとのlauncher画面へ出す（launcher不在時は出さない）。
                val revision = receipt?.verifiedPostRevision
                if (revision != null) {
                    val token = HomeEditUndoRecord.record(
                        HomeEditUndoEntry.EditSession(result.pointId, revision),
                    )
                    LawnchairLauncher.instance?.let { launcher ->
                        HomeEditUndoSnackbar.show(launcher, token)
                    }
                }
                finish()
                return
            }

            // stale（ずれ検出）: 零書込み。セッションを破棄し最新captureで開き直す。
            result is ApplyResult.Rejected && (
                result.reason == PreWriteRejection.STALE_REVISION ||
                    result.reason == PreWriteRejection.EXACT_PRECONDITION_FAILED
                ) -> {
                reasonRes = R.string.edit_surface_error_stale_reopen
                reloadCapture()
                return
            }

            // 防御到達（実装不具合）: 零書込み。変更未反映の旨を表示しセッション保持。
            built is EditSurfaceApplyPlan.Inconsistent || result == null ||
                result is ApplyResult.NoChanges ->
                reasonRes = R.string.edit_surface_error_no_changes

            result is ApplyResult.Rejected -> reasonRes = rejectionTextFor(result.reason)

            result is ApplyResult.ConcurrentRun -> reasonRes = R.string.edit_surface_error_busy

            result is ApplyResult.RolledBack || result is ApplyResult.Recovered ->
                reasonRes = R.string.edit_surface_error_unchanged

            result is ApplyResult.Unresolved || result is ApplyResult.RecoveryFailed ->
                reasonRes = R.string.edit_surface_error_unresolved
        }
    }

    private fun rejectionTextFor(reason: PreWriteRejection): Int = editSurfaceRejectionText(reason)

    private fun rejectionText(reason: HomeEditRejection): Int = when (reason) {
        HomeEditRejection.STALE -> R.string.homeedit_error_stale
        HomeEditRejection.ITEM_GONE -> R.string.homeedit_error_item_gone
        HomeEditRejection.NO_SPACE -> R.string.homeedit_error_no_space
        HomeEditRejection.REDUNDANT -> R.string.homeedit_error_redundant
        HomeEditRejection.FOLDER_GONE -> R.string.homeedit_error_folder_gone
        HomeEditRejection.PROFILE_MISMATCH -> R.string.homeedit_error_profile_mismatch
        HomeEditRejection.UNSUPPORTED -> R.string.homeedit_error_unsupported
    }

    /** 図表示用のicon解決（custom icon bytes優先。解決不能はplaceholder=null）。 */
    private fun resolveIcons(diagram: EditSurfaceDiagram): Map<Int, ImageBitmap?> {
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
        val userCache = UserCache.INSTANCE.get(this)
        val iconCache = LauncherAppState.getInstance(this).iconCache
        val size = LauncherAppState.getIDP(this).iconBitmapSize
        return diagram.items.associate { item ->
            item.id to runCatching {
                item.iconBytes?.let { bytes ->
                    bytes.asByteArray().let { array ->
                        android.graphics.BitmapFactory.decodeByteArray(array, 0, array.size)?.asImageBitmap()
                    }
                } ?: resolveIconFromTargetKey(item, launcherApps, userCache, iconCache, size)
            }.getOrNull()
        }
    }

    private fun resolveIconFromTargetKey(
        item: app.lawnchair.homeedit.EditSurfaceItem,
        launcherApps: android.content.pm.LauncherApps,
        userCache: UserCache,
        iconCache: IconCache,
        size: Int,
    ): ImageBitmap? = when (val key = item.targetKey) {
        is TargetKey.AppKey -> {
            val component = ComponentName.unflattenFromString(key.component.value) ?: return null
            val user = userCache.getUserForSerialNumber(item.userSerial) ?: Process.myUserHandle()
            val activity = launcherApps.getActivityList(component.packageName, user)
                .firstOrNull { it.componentName == component } ?: return null
            iconCache.getFullResIcon(activity).toImageBitmap(size)
        }

        is TargetKey.ShortcutKey -> {
            val user = userCache.getUserForSerialNumber(item.userSerial) ?: return null
            val query = android.content.pm.LauncherApps.ShortcutQuery().apply {
                setPackage(key.packageName.value)
                setShortcutIds(listOf(key.shortcutId.value))
                setQueryFlags(
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            }
            val shortcut = launcherApps.getShortcuts(query, user)?.firstOrNull() ?: return null
            launcherApps.getShortcutIcon(shortcut).toImageBitmap(size)
        }

        else -> null
    }

    private fun android.graphics.drawable.Icon.toImageBitmap(size: Int): ImageBitmap? {
        val drawable = loadDrawable(this@HomeEditSurfaceActivity) ?: return null
        return drawable.toImageBitmap(size)
    }

    private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}

/**
 * 確定前のtyped拒否reason → ユーザー向け理由リソース（AC-7。零書込み・セッション保持・
 * 理由表示の契約の表示側。apply時の再captureでUNKNOWNになった場合を含む）。
 * 純関数としてJVM testのoracleにする。
 */
internal fun editSurfaceRejectionText(reason: PreWriteRejection): Int = when (reason) {
    PreWriteRejection.RECOVERY_POINT_ADMISSION_BLOCKED -> R.string.edit_surface_error_blocked

    PreWriteRejection.WRITER_BUSY -> R.string.edit_surface_error_busy

    PreWriteRejection.STALE_REVISION, PreWriteRejection.EXACT_PRECONDITION_FAILED ->
        R.string.edit_surface_error_stale_reopen

    // Apply時の再captureでUNKNOWNになった場合（開始後にロック状態が変わった等）。
    PreWriteRejection.LOCK_STATE_UNAVAILABLE -> R.string.edit_surface_error_lock_unknown

    else -> R.string.edit_surface_error_generic
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
