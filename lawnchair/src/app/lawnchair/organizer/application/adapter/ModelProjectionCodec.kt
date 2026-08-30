package app.lawnchair.organizer.application.adapter

import android.content.Context
import android.util.Log
import app.lawnchair.organizer.application.protocol.ModelAppPairMember
import app.lawnchair.organizer.application.protocol.ModelItemProjection
import app.lawnchair.organizer.application.protocol.ModelPlacement
import app.lawnchair.organizer.application.protocol.ModelSnapshot
import app.lawnchair.organizer.application.protocol.ModelStructure
import app.lawnchair.organizer.application.protocol.ModelWidgetIdentity
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.WidgetRestoreState
import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ShortcutId
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.pm.UserCache

/**
 * Issue #152: capture-side model projection. Reduces the in-memory
 * [BgDataModel] state to the model-verifiable [ModelSnapshot] at the #150
 * terminal boundary of the correlated reload. The projection's required
 * fields are pinned by the accepted spec; anything the model does not
 * faithfully represent is excluded here and stays covered by the exact DB
 * comparison.
 *
 * Called from `LauncherModel`'s completion path; any capture failure returns
 * null so the reload is failed closed instead of reported complete.
 */
internal object ModelProjectionCodec {

    private const val TAG = "ModelProjectionCodec"

    /** Rank decode shared with `RowManifestCodec` (persisted app-pair format). */
    private const val APP_PAIR_STAGE_BITS = 16

    @JvmStatic
    fun captureModelSnapshot(bgDataModel: BgDataModel, context: Context): ModelSnapshot? = try {
        capture(bgDataModel, context)
    } catch (t: Throwable) {
        Log.e(TAG, "Model snapshot capture failed; reload fails closed", t)
        null
    }

    private fun capture(bgDataModel: BgDataModel, context: Context): ModelSnapshot {
        val userCache = UserCache.INSTANCE.get(context)
        val allInfos = ArrayList<ItemInfo>()
        synchronized(bgDataModel) {
            allInfos += bgDataModel.workspaceItems
            allInfos += bgDataModel.appWidgets
            for (index in 0 until bgDataModel.collections.size()) {
                allInfos += bgDataModel.collections.valueAt(index)
                allInfos += bgDataModel.collections.valueAt(index).getContents()
            }
        }
        val kindById = HashMap<Long, Int>()
        allInfos.forEach { kindById[it.id.toLong()] = it.itemType }
        val membersByParent = HashMap<Long, List<ItemInfo>>()
        for (index in 0 until bgDataModel.collections.size()) {
            val collection = bgDataModel.collections.valueAt(index)
            membersByParent[collection.id.toLong()] = collection.getContents().sortedBy { it.rank }
        }
        val seen = HashSet<Long>()
        val items = allInfos.mapNotNull { info ->
            if (!seen.add(info.id.toLong())) return@mapNotNull null
            projectItem(info, kindById, membersByParent, userCache)
        }.sortedBy { (it.ref as ApplicationItemRef.PersistentItem).itemId }
        return ModelSnapshot(items, bgDataModel.lastLoadId.toLong())
    }

    private fun projectItem(
        info: ItemInfo,
        kindById: Map<Long, Int>,
        membersByParent: Map<Long, List<ItemInfo>>,
        userCache: UserCache,
    ): ModelItemProjection? {
        val profile = canonicalProfileId(userCache, info.user)
            ?: error("Model item ${info.id} has no stable profile serial")
        val kind = kindOf(info.itemType)
        val placement = when {
            info.container == Favorites.CONTAINER_DESKTOP -> ModelPlacement.Workspace(
                PageId(info.screenId.toString()),
                GridCell(info.cellX, info.cellY),
                GridSpan(info.spanX, info.spanY),
            )

            info.container == Favorites.CONTAINER_HOTSEAT -> ModelPlacement.Dock(info.screenId)

            info.container >= 0 -> when (kindById[info.container.toLong()]) {
                Favorites.ITEM_TYPE_APP_PAIR -> ModelPlacement.AppPairChild(
                    ItemId(info.container.toString()),
                    info.rank.appPairStage(),
                )

                else -> ModelPlacement.FolderChild(ItemId(info.container.toString()))
            }

            // Issue #152 (re-review P1): represented explicitly, never dropped —
            // a row the model loses must not compare equal by symmetric omission.
            else -> ModelPlacement.UnsupportedContainer(ContainerCode(info.container))
        }
        val members = membersByParent[info.id.toLong()].orEmpty()
        val structure = when (kind) {
            CanonicalItemKind.Folder -> ModelStructure.FolderMembers(members.map { ItemId(it.id.toString()) })

            CanonicalItemKind.AppPair -> ModelStructure.AppPairMembers(
                members.map { ModelAppPairMember(ItemId(it.id.toString()), it.rank.appPairStage()) },
            )

            else -> ModelStructure.Plain
        }
        // Issue #152 (re-review P1): the faithful launch identity of legacy
        // shortcut kinds, canonically re-serialized from the in-memory intent —
        // the same form the DB-side port derives from the persisted text.
        val legacyLaunchIdentity = when (kind) {
            CanonicalItemKind.ShortcutLegacy, is CanonicalItemKind.Unknown ->
                (info as? WorkspaceItemInfo)?.intent?.toUri(0)

            else -> null
        }
        val widget = when (kind) {
            CanonicalItemKind.AppWidget, CanonicalItemKind.CustomAppWidget -> {
                val widgetInfo = info as LauncherAppWidgetInfo
                ModelWidgetIdentity(
                    ComponentKey(
                        requireNotNull(widgetInfo.providerName) { "Widget has no provider" }.flattenToString(),
                    ),
                    AppWidgetId(widgetInfo.appWidgetId),
                    WidgetRestoreState(widgetInfo.restoreStatus),
                )
            }

            else -> null
        }
        return ModelItemProjection(
            ApplicationItemRef.PersistentItem(ItemId(info.id.toString())),
            kind,
            targetKey(info, kind, profile),
            profile,
            placement,
            structure,
            widget,
            legacyLaunchIdentity,
        )
    }

    private fun targetKey(info: ItemInfo, kind: CanonicalItemKind, profile: ProfileId): TargetKey = when (kind) {
        CanonicalItemKind.Application -> {
            val intent = requireNotNull((info as WorkspaceItemInfo).intent) { "Application has no intent" }
            TargetKey.AppKey(
                ComponentKey(
                    requireNotNull(intent.component) { "Application intent has no component" }.flattenToString(),
                ),
                profile,
            )
        }

        CanonicalItemKind.DeepShortcut -> {
            val intent = requireNotNull((info as WorkspaceItemInfo).intent) { "Shortcut has no intent" }
            TargetKey.ShortcutKey(
                PackageName(requireNotNull(intent.`package`) { "Shortcut intent has no package" }),
                ShortcutId(
                    requireNotNull(intent.getStringExtra("shortcut_id")) { "Shortcut intent has no shortcut_id" },
                ),
                profile,
            )
        }

        CanonicalItemKind.ShortcutLegacy, is CanonicalItemKind.Unknown -> TargetKey.LegacyShortcutKey

        CanonicalItemKind.Folder -> TargetKey.FolderKey(FolderId(info.id.toString()))

        CanonicalItemKind.AppPair -> TargetKey.AppPairKey(AppPairId(info.id.toString()))

        CanonicalItemKind.AppWidget, CanonicalItemKind.CustomAppWidget -> {
            val widgetInfo = info as LauncherAppWidgetInfo
            TargetKey.WidgetKey(
                ComponentKey(
                    requireNotNull(widgetInfo.providerName) { "Widget has no provider" }.flattenToString(),
                ),
                AppWidgetId(widgetInfo.appWidgetId),
                profile,
            )
        }
    }

    private fun kindOf(itemType: Int): CanonicalItemKind = when (itemType) {
        Favorites.ITEM_TYPE_APPLICATION -> CanonicalItemKind.Application
        Favorites.ITEM_TYPE_SHORTCUT -> CanonicalItemKind.ShortcutLegacy
        Favorites.ITEM_TYPE_FOLDER -> CanonicalItemKind.Folder
        Favorites.ITEM_TYPE_APPWIDGET -> CanonicalItemKind.AppWidget
        Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> CanonicalItemKind.CustomAppWidget
        Favorites.ITEM_TYPE_DEEP_SHORTCUT -> CanonicalItemKind.DeepShortcut
        Favorites.ITEM_TYPE_APP_PAIR -> CanonicalItemKind.AppPair
        else -> CanonicalItemKind.Unknown(app.lawnchair.organizer.planning.KindCode(itemType))
    }

    private fun Int.appPairStage(): SplitStage = when (this ushr APP_PAIR_STAGE_BITS) {
        0 -> SplitStage.TOP_OR_LEFT
        1 -> SplitStage.BOTTOM_OR_RIGHT
        else -> if (this == 0) SplitStage.TOP_OR_LEFT else SplitStage.BOTTOM_OR_RIGHT
    }
}
