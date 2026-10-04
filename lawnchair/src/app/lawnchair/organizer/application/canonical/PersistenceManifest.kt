package app.lawnchair.organizer.application.canonical

import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId

/**
 * Internal lossless persistence manifest. Captures every schema-33 row/resource
 * field required to materialize the canonical apply state and to recover the
 * exact pre-apply layout, including columns and raw unsupported-container
 * placement that never cross the public seam.
 *
 * Spec §“Apply input”: raw column names, row encodings, and recovery bytes
 * never cross the public contract; the public [LayoutState] is derived from
 * this manifest.
 *
 * Issue #14 Stage B step 1 (shape); step 3 wires serialization to the recovery
 * DB blob.
 */
data class PersistenceManifest(
    val formatVersion: Int,
    val schemaVersion: Int,
    val rowCount: Int,
    val rows: List<PersistentRow>,
    val resources: List<PersistentResource>,
    val modifiedAtMillis: Long,
) {
    init {
        require(formatVersion > 0) { "formatVersion must be positive" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(rowCount >= 0) { "rowCount must be non-negative" }
        require(rows.size == rowCount) { "rowCount must equal rows.size" }
        require(rows.distinctBy { it.rowId }.size == rows.size) {
            "PersistentRow rowId must be duplicate-free"
        }
        require(modifiedAtMillis >= 0L) { "modifiedAtMillis must be non-negative" }
    }
}

/**
 * One schema-33 favorites row, captured losslessly. The exact nullable columns
 * are explicit so the apply/recovery write-set never needs to invent a value.
 */
data class PersistentRow(
    val rowId: Long,
    val itemId: ItemId,
    val profileId: ProfileId,
    val containerCode: ContainerCode,
    val screenId: PageId?,
    val cellX: Int?,
    val cellY: Int?,
    val spanX: Int?,
    val spanY: Int?,
    val rank: Int,
    val itemType: KindCode,
    val appWidgetId: AppWidgetId?,
    val appWidgetProvider: ComponentKey?,
    val iconBytes: ByteArray?,
    val title: String?,
    val intent: String?,
    val restored: Int?,
    val options: Int?,
    val appWidgetSource: Int?,
    val modified: Long,
    val organizerLockState: OrganizerLockState,
    val rawCell: GridCell?,
    val rawSpan: GridSpan?,
) {
    init {
        require(rowId >= 0L) { "rowId must be non-negative" }
        require(rank >= 0) { "rank must be non-negative" }
        require(modified >= 0L) { "modified must be non-negative" }
        if (cellX != null) require(cellX >= 0) { "cellX must be non-negative when present" }
        if (cellY != null) require(cellY >= 0) { "cellY must be non-negative when present" }
        if (spanX != null) require(spanX > 0) { "spanX must be positive when present" }
        if (spanY != null) require(spanY > 0) { "spanY must be positive when present" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistentRow) return false
        return rowId == other.rowId &&
            itemId == other.itemId &&
            profileId == other.profileId &&
            containerCode == other.containerCode &&
            screenId == other.screenId &&
            cellX == other.cellX &&
            cellY == other.cellY &&
            spanX == other.spanX &&
            spanY == other.spanY &&
            rank == other.rank &&
            itemType == other.itemType &&
            appWidgetId == other.appWidgetId &&
            appWidgetProvider == other.appWidgetProvider &&
            iconBytes.contentEquals(other.iconBytes) &&
            title == other.title &&
            intent == other.intent &&
            restored == other.restored &&
            options == other.options &&
            appWidgetSource == other.appWidgetSource &&
            modified == other.modified &&
            organizerLockState == other.organizerLockState &&
            rawCell == other.rawCell &&
            rawSpan == other.rawSpan
    }

    override fun hashCode(): Int {
        var result = rowId.hashCode()
        result = 31 * result + itemId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + containerCode.hashCode()
        result = 31 * result + (screenId?.hashCode() ?: 0)
        result = 31 * result + (cellX ?: 0)
        result = 31 * result + (cellY ?: 0)
        result = 31 * result + (spanX ?: 0)
        result = 31 * result + (spanY ?: 0)
        result = 31 * result + rank
        result = 31 * result + itemType.hashCode()
        result = 31 * result + (appWidgetId?.hashCode() ?: 0)
        result = 31 * result + (appWidgetProvider?.hashCode() ?: 0)
        result = 31 * result + (iconBytes?.contentHashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (intent?.hashCode() ?: 0)
        result = 31 * result + (restored ?: 0)
        result = 31 * result + (options ?: 0)
        result = 31 * result + (appWidgetSource ?: 0)
        result = 31 * result + modified.hashCode()
        result = 31 * result + organizerLockState.hashCode()
        result = 31 * result + (rawCell?.hashCode() ?: 0)
        result = 31 * result + (rawSpan?.hashCode() ?: 0)
        return result
    }
}

/**
 * Non-row persistent resource (e.g. workspace screen order, profile inventory).
 * Stored alongside [PersistentRow] so the manifest is a complete recovery
 * unit; the format tag distinguishes each kind.
 */
data class PersistentResource(
    val kind: PersistentResourceKind,
    val profileId: ProfileId?,
    val order: Long,
    val payload: ByteArray,
) {
    init {
        require(order >= 0L) { "order must be non-negative" }
        require(payload.isNotEmpty()) { "payload must be non-empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistentResource) return false
        return kind == other.kind &&
            profileId == other.profileId &&
            order == other.order &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + (profileId?.hashCode() ?: 0)
        result = 31 * result + order.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

enum class PersistentResourceKind {
    WORKSPACE_SCREEN,
    DEVICE_PROFILE,
    PROFILE_INVENTORY,

    /** Non-item workspace occupancy context captured from Launcher platform state (Issue #155). */
    WORKSPACE_RESERVATION,
}
