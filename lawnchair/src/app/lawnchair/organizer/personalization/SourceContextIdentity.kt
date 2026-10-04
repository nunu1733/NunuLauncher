package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.TargetSet
import java.security.MessageDigest

/**
 * Issue #204: the session-local structural source-context identity
 * (spec 204 "source context identity (session-local) — structural identityと
 * signal provenanceの分離").
 *
 * The digest input is the internal canonical STRUCTURAL projection only:
 * captured snapshot (items with kind/lock/availability/placement), target-set
 * roles, and resolved categories keyed by internal `ItemId`. The #203 signal
 * snapshot is deliberately EXCLUDED — it is a dynamic per-composition input and
 * signal changes must never produce `CONTEXT_STALE`.
 *
 * The projection is independent of the export envelope, `exportId`, `ref`
 * allocation, privacy tier, capability set, and content limits, so the digest
 * cannot become self-referential and never leaves the session.
 */
object SourceContextIdentity {

    /** Stable digest of the canonical structural projection. */
    fun digest(inputs: CanonicalStructuralInputs): String = sha256Hex(canonicalRepresentation(inputs))

    /**
     * Canonical rows: grid header, one row per page (capture order), one row
     * per reserved region (page order then cell), and one sorted row per
     * captured item. Deterministic and stable across processes.
     */
    fun canonicalRepresentation(inputs: CanonicalStructuralInputs): String {
        val snapshot = inputs.snapshot
        val pageOrdinal = snapshot.pages.withIndex().associate { (index, page) -> page.id to index }
        val rows = mutableListOf<String>()
        rows += "grid|${snapshot.device.columns}|${snapshot.device.rows}|${snapshot.device.hotseatSlots}" +
            "|${snapshot.device.folderMaxColumns}|${snapshot.device.folderMaxRows}|${snapshot.device.orientation.name}"
        for (page in snapshot.pages) {
            rows += "page|${pageOrdinal[page.id]}|${page.id.value}"
        }
        for (region in snapshot.reservedWorkspaceRegions.sortedWith(reservedRegionOrder(pageOrdinal))) {
            rows += region.canonicalRow { pageOrdinal[it] ?: -1 }
        }
        val rolesById = inputs.targets.existing.associate { it.item to it.role }
        for (item in snapshot.items.sortedBy { it.id }) {
            rows += item.canonicalRow(
                role = rolesById[item.id]?.name ?: "-",
                category = inputs.resolvedCategories[item.id] ?: "-",
            )
        }
        return rows.joinToString("\n")
    }
}

/** Structural-only canonical inputs of one export attempt. */
data class CanonicalStructuralInputs(
    val snapshot: LayoutSnapshot,
    val targets: TargetSet,
    /** Resolved classification (override included) keyed by internal `ItemId`. */
    val resolvedCategories: Map<ItemId, String?>,
)

private fun reservedRegionOrder(
    pageOrdinal: Map<app.lawnchair.organizer.planning.PageId, Int>,
): Comparator<ReservedWorkspaceRegion> = compareBy(
    { region -> pageOrdinal[region.page.pageId] ?: -1 },
    { it.cell.x },
    { it.cell.y },
)

private fun ReservedWorkspaceRegion.canonicalRow(pageOrdinalOf: (app.lawnchair.organizer.planning.PageId) -> Int): String = "reservation|${pageOrdinalOf(page.pageId)}|${cell.x}|${cell.y}|${span.width}|${span.height}"

private fun CapturedItem.canonicalRow(role: String, category: String): String = "item|${id.value}|${kindCanonical()}|$locked|${availability.name}|${placement.canonical()}|$role|$category"

private fun CapturedItem.kindCanonical(): String = when (val k = kind) {
    is app.lawnchair.organizer.planning.ItemKind.APPLICATION -> "APPLICATION"
    is app.lawnchair.organizer.planning.ItemKind.DEEP_SHORTCUT -> "DEEP_SHORTCUT"
    is app.lawnchair.organizer.planning.ItemKind.SHORTCUT_LEGACY -> "SHORTCUT_LEGACY"
    is app.lawnchair.organizer.planning.ItemKind.FOLDER -> "FOLDER"
    is app.lawnchair.organizer.planning.ItemKind.APPWIDGET -> "APPWIDGET"
    is app.lawnchair.organizer.planning.ItemKind.CUSTOM_APPWIDGET -> "CUSTOM_APPWIDGET"
    is app.lawnchair.organizer.planning.ItemKind.APP_PAIR -> "APP_PAIR"
    is app.lawnchair.organizer.planning.ItemKind.Unknown -> "Unknown|${k.code.value}"
}

private fun CapturedPlacement.canonical(): String = when (this) {
    is CapturedPlacement.Workspace ->
        "workspace|${page.pageId.value}|${cell.x}|${cell.y}|${span.width}|${span.height}"

    is CapturedPlacement.Dock -> "dock|$rank"

    is CapturedPlacement.FolderMember -> "folder|${folder.folderId.value}|$rank"

    is CapturedPlacement.AppPairMember -> "pair|${pair.appPairId.value}"

    is CapturedPlacement.UnsupportedContainer -> "unsupported|${code.value}"
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
