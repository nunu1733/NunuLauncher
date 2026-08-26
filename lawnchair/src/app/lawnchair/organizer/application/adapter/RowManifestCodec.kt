package app.lawnchair.organizer.application.adapter

import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.ImmutableByteString
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalSnapPosition
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetOptions
import app.lawnchair.organizer.application.public.WidgetRestoreState
import app.lawnchair.organizer.application.public.WidgetSource
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ShortcutId
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey
import com.android.launcher3.LauncherSettings.Favorites

/** Issue #14: the sole lossless schema-33 row/public-state conversion boundary. */
internal object RowManifestCodec {
    data class Capture(val state: LayoutState, val manifest: PersistenceManifest)

    fun capture(
        db: SQLiteDatabase,
        capabilities: DeviceCapabilities,
        orderedPages: List<PageId>,
        profileInventory: List<ProfileState>,
    ): Capture {
        val rows = mutableListOf<PersistentRow>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, "${Favorites._ID} ASC").use { c ->
            while (c.moveToNext()) rows += c.toPersistentRow()
        }
        val referencedPages = rows.asSequence()
            .filter { it.containerCode.value == Favorites.CONTAINER_DESKTOP }
            .mapNotNull { it.screenId }.toSet()
        require(orderedPages.toSet().containsAll(referencedPages)) {
            "Model page inventory omits a page referenced by favorites"
        }
        val persistentPages = orderedPages.filter { it in referencedPages }
        val pages = persistentPages.mapIndexed { index, id ->
            PageState(ApplicationPageRef.PersistentPage(id), PageOrder(index))
        }
        val profileById = profileInventory.associateBy { it.id }
        require(rows.all { it.profileId in profileById }) {
            "Favorites contains a profile absent from the authoritative user inventory"
        }
        val childrenByParent = rows.filter { it.containerCode.value >= 0 }
            .groupBy { it.containerCode.value.toLong() }
        val kindById = rows.associate { it.rowId to it.itemType.value }
        val state = LayoutState(
            pages,
            profileInventory,
            capabilities,
            rows.map { toCanonical(it, profileById.getValue(it.profileId), childrenByParent, kindById) },
        )
        return Capture(
            state,
            PersistenceManifest(
                1,
                33,
                rows.size,
                rows,
                ContextResourceCodec.encode(profileInventory, capabilities),
                rows.maxOfOrNull { it.modified } ?: 0L,
            ),
        )
    }

    fun values(row: PersistentRow): ContentValues = ContentValues().apply {
        put(Favorites._ID, row.rowId)
        put(Favorites.TITLE, row.title)
        put(Favorites.INTENT, row.intent)
        put(Favorites.CONTAINER, row.containerCode.value)
        // Faithful column write: a captured NULL SCREEN stays NULL instead of
        // being normalized to 0 behind the platform's back.
        if (row.screenId == null) {
            putNull(Favorites.SCREEN)
        } else {
            put(
                Favorites.SCREEN,
                requireNotNull(row.screenId.value.toLongOrNull()) { "Screen id must be numeric" },
            )
        }
        put(Favorites.CELLX, row.cellX)
        put(Favorites.CELLY, row.cellY)
        put(Favorites.SPANX, row.spanX)
        put(Favorites.SPANY, row.spanY)
        put(Favorites.ITEM_TYPE, row.itemType.value)
        put(Favorites.APPWIDGET_ID, row.appWidgetId?.value ?: -1)
        put(Favorites.ICON, row.iconBytes)
        put(Favorites.APPWIDGET_PROVIDER, row.appWidgetProvider?.value)
        put(Favorites.MODIFIED, row.modified)
        put(Favorites.RESTORED, row.restored ?: 0)
        put(Favorites.PROFILE_ID, row.profileId.value.toLong())
        put(Favorites.RANK, row.rank)
        put(Favorites.OPTIONS, row.options ?: 0)
        put(Favorites.APPWIDGET_SOURCE, row.appWidgetSource ?: -1)
        put(Favorites.ORGANIZER_LOCK_STATE, row.organizerLockState.ordinal)
    }

    private fun Cursor.toPersistentRow(): PersistentRow {
        fun int(name: String) = getInt(getColumnIndexOrThrow(name))
        fun long(name: String) = getLong(getColumnIndexOrThrow(name))
        fun text(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun blob(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getBlob(it) }
        val container = int(Favorites.CONTAINER)
        fun nullableInt(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
        fun nullableLong(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        val screen = nullableLong(Favorites.SCREEN)
        val cx = nullableInt(Favorites.CELLX)
        val cy = nullableInt(Favorites.CELLY)
        val sx = nullableInt(Favorites.SPANX)
        val sy = nullableInt(Favorites.SPANY)
        // SCREEN is schema-nullable; the manifest keeps the raw value for every
        // row so apply and recovery never rewrite NULL into 0. Only the desktop
        // placement requires a page, and only its derivation may fail closed.
        val screenId = when {
            container == Favorites.CONTAINER_DESKTOP -> PageId(requireNotNull(screen).toString())
            else -> screen?.let { PageId(it.toString()) }
        }
        return PersistentRow(
            long(Favorites._ID), ItemId(long(Favorites._ID).toString()),
            ProfileId(long(Favorites.PROFILE_ID).toString()), ContainerCode(container),
            screenId,
            cx, cy, sx, sy, int(Favorites.RANK), KindCode(int(Favorites.ITEM_TYPE)),
            int(Favorites.APPWIDGET_ID).takeIf { it >= 0 }?.let(::AppWidgetId),
            text(Favorites.APPWIDGET_PROVIDER)?.let(::ComponentKey), blob(Favorites.ICON),
            text(Favorites.TITLE), text(Favorites.INTENT), int(Favorites.RESTORED),
            int(Favorites.OPTIONS), int(Favorites.APPWIDGET_SOURCE), long(Favorites.MODIFIED),
            OrganizerLockState.entries.getOrNull(int(Favorites.ORGANIZER_LOCK_STATE))
                ?: OrganizerLockState.UNKNOWN,
            if (cx != null && cy != null) GridCell(cx, cy) else null,
            if (sx != null && sy != null) GridSpan(sx, sy) else null,
        )
    }

    private fun toCanonical(
        row: PersistentRow,
        profile: ProfileState,
        childrenByParent: Map<Long, List<PersistentRow>>,
        kindById: Map<Long, Int>,
    ): CanonicalItemState {
        val placement = when (row.containerCode.value) {
            Favorites.CONTAINER_DESKTOP -> PlacementState.Workspace(
                ApplicationPageRef.PersistentPage(requireNotNull(row.screenId)),
                requireNotNull(row.rawCell),
                requireNotNull(row.rawSpan),
            )

            Favorites.CONTAINER_HOTSEAT -> PlacementState.Dock(
                // Hotseat slot authority is SCREEN (LoaderCursor.checkItemPlacement,
                // GridSizeMigrationUtil.loadHotseatEntries); default-layout rows
                // leave RANK at its schema default of 0 for every entry. The raw
                // column stays untouched in the manifest; NULL is read as slot 0
                // exactly like the platform loader's getInt.
                row.hotseatSlot(),
            )

            else -> if (row.containerCode.value >= 0) {
                val parent = ApplicationItemRef.PersistentItem(ItemId(row.containerCode.value.toString()))
                if (kindById[row.containerCode.value.toLong()] == Favorites.ITEM_TYPE_APP_PAIR) {
                    PlacementState.AppPairChild(parent, row.rank.appPairStage())
                } else {
                    PlacementState.FolderChild(parent, row.rank)
                }
            } else {
                PlacementState.UnsupportedContainer(row.containerCode)
            }
        }
        val kind = when (row.itemType.value) {
            Favorites.ITEM_TYPE_APPLICATION -> CanonicalItemKind.Application
            Favorites.ITEM_TYPE_SHORTCUT -> CanonicalItemKind.ShortcutLegacy
            Favorites.ITEM_TYPE_FOLDER -> CanonicalItemKind.Folder
            Favorites.ITEM_TYPE_APPWIDGET -> CanonicalItemKind.AppWidget
            Favorites.ITEM_TYPE_CUSTOM_APPWIDGET -> CanonicalItemKind.CustomAppWidget
            Favorites.ITEM_TYPE_DEEP_SHORTCUT -> CanonicalItemKind.DeepShortcut
            Favorites.ITEM_TYPE_APP_PAIR -> CanonicalItemKind.AppPair
            else -> CanonicalItemKind.Unknown(row.itemType)
        }
        val widget = when (kind) {
            CanonicalItemKind.AppWidget, CanonicalItemKind.CustomAppWidget -> {
                val provider = requireNotNull(row.appWidgetProvider) { "Widget is missing its provider" }
                val widgetId = requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }
                WidgetState.Widget(
                    provider,
                    widgetId,
                    WidgetRestoreState(row.restored ?: 0),
                    WidgetOptions(row.options ?: 0),
                    WidgetSource(row.appWidgetSource ?: -1),
                )
            }

            else -> WidgetState.NoWidget
        }
        val children = childrenByParent[row.rowId].orEmpty().sortedBy { it.rank }
        val structure = when (kind) {
            CanonicalItemKind.Folder -> StructureState.FolderMembers(
                children.map { RankedMember(ApplicationItemRef.PersistentItem(it.itemId), it.rank) },
            )

            CanonicalItemKind.AppPair -> {
                require(children.size == 2) {
                    "App pair must have exactly two member rows"
                }
                // Issue #141: the authoritative snap position is the persisted
                // member rank itself. Both member rows must decode to one
                // persistent snap with complementary stages; anything else stays
                // Absent so the planner keeps rejecting the pair as
                // MALFORMED_APP_PAIR instead of capture inventing a value.
                val firstRank = children[0].rank
                val secondRank = children[1].rank
                val firstStage = firstRank.decodedAppPairStage()
                val secondStage = secondRank.decodedAppPairStage()
                val snapPosition = firstRank.decodedSnapPosition()
                    ?.takeIf { it == secondRank.decodedSnapPosition() }
                    ?.takeIf { firstStage != null && secondStage != null && firstStage != secondStage }
                    ?.let { OptionalSnapPosition.Present(SnapPositionToken(it.toString())) }
                    ?: OptionalSnapPosition.Absent
                StructureState.AppPairMembers(
                    ApplicationItemRef.PersistentItem(children[0].itemId),
                    ApplicationItemRef.PersistentItem(children[1].itemId),
                    firstStage ?: SplitStage.TOP_OR_LEFT,
                    secondStage ?: SplitStage.BOTTOM_OR_RIGHT,
                    snapPosition,
                )
            }

            else -> StructureState.Plain
        }
        return CanonicalItemState(
            ApplicationItemRef.PersistentItem(row.itemId), kind,
            targetKey(row, kind), row.profileId,
            profile.availability,
            if (profile.availability == ProfileAvailability.AVAILABLE) {
                ItemAvailability.AVAILABLE
            } else {
                ItemAvailability.UNAVAILABLE
            },
            placement,
            row.title?.let { OptionalText.Present(it) } ?: OptionalText.Absent,
            row.intent?.let { OptionalText.Present(it) } ?: OptionalText.Absent,
            row.iconBytes?.let { OptionalBytes.Present(ImmutableByteString.copyFrom(it)) }
                ?: OptionalBytes.Absent,
            widget, ModifiedAtMillis(row.modified), row.organizerLockState, structure,
        )
    }

    private fun targetKey(row: PersistentRow, kind: CanonicalItemKind): TargetKey = when (kind) {
        CanonicalItemKind.Application -> {
            val intent = parseIntent(row)
            TargetKey.AppKey(
                ComponentKey(requireNotNull(intent.component) { "Application intent has no component" }.flattenToString()),
                row.profileId,
            )
        }

        CanonicalItemKind.DeepShortcut -> TargetKey.ShortcutKey(
            PackageName(requireNotNull(parseIntent(row).`package`) { "Shortcut intent has no package" }),
            ShortcutId(
                requireNotNull(parseIntent(row).getStringExtra("shortcut_id")) {
                    "Shortcut intent has no shortcut_id"
                },
            ),
            row.profileId,
        )

        CanonicalItemKind.ShortcutLegacy, is CanonicalItemKind.Unknown -> TargetKey.LegacyShortcutKey

        CanonicalItemKind.Folder -> TargetKey.FolderKey(FolderId(row.itemId.value))

        CanonicalItemKind.AppPair -> TargetKey.AppPairKey(AppPairId(row.itemId.value))

        CanonicalItemKind.AppWidget, CanonicalItemKind.CustomAppWidget -> TargetKey.WidgetKey(
            requireNotNull(row.appWidgetProvider) { "Widget has no provider" },
            requireNotNull(row.appWidgetId) { "Widget has no id" },
            row.profileId,
        )
    }

    private fun parseIntent(row: PersistentRow): Intent = try {
        Intent.parseUri(requireNotNull(row.intent) { "Item has no intent" }, 0)
    } catch (error: Exception) {
        throw IllegalArgumentException("Malformed intent for row ${row.rowId}", error)
    }

    private fun PersistentRow.hotseatSlot(): Int = screenId?.value?.toIntOrNull() ?: 0

    /**
     * Issue #141: app-pair member ranks are a persisted platform format —
     * `AppPairsController.encodeRank` packs `(splitPosition shl 16) + snapPosition`
     * into `favorites.rank`, and SplitScreenConstants pins those values ("must not
     * be changed -- they are persisted to user-defined app pairs"). The mirrored
     * constants below decode that column; they are data-format facts rather than
     * behavior imports so every flavor compiles the same projection.
     */
    private const val APP_PAIR_STAGE_BITS = 16
    private const val SNAP_TO_30_70 = 0
    private const val SNAP_TO_50_50 = 1
    private const val SNAP_TO_70_30 = 2
    private val PERSISTENT_SNAP_POSITIONS = setOf(SNAP_TO_30_70, SNAP_TO_50_50, SNAP_TO_70_30)

    /** Stage half of an encoded member rank, or null when outside the persisted domain. */
    private fun Int.decodedAppPairStage(): SplitStage? = when (this ushr APP_PAIR_STAGE_BITS) {
        0 -> SplitStage.TOP_OR_LEFT
        1 -> SplitStage.BOTTOM_OR_RIGHT
        else -> null
    }

    /** Snap half of an encoded member rank, or null when not a persistent snap position. */
    private fun Int.decodedSnapPosition(): Int? = (this and ((1 shl APP_PAIR_STAGE_BITS) - 1)).takeIf { it in PERSISTENT_SNAP_POSITIONS }

    /**
     * Projection stage for an app-pair child row. Out-of-domain ranks fall back to
     * the legacy rank==0 heuristic; such rows carry no decodable snap position and
     * are rejected as MALFORMED_APP_PAIR downstream.
     */
    private fun Int.appPairStage(): SplitStage = decodedAppPairStage()
        ?: if (this == 0) SplitStage.TOP_OR_LEFT else SplitStage.BOTTOM_OR_RIGHT
}
