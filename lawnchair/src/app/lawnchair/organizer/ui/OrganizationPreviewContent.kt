package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.ItemWarningChange
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.NewFolderChange
import app.lawnchair.organizer.application.public.NewPageChange
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PreservedChange
import app.lawnchair.organizer.application.public.PreviewChange
import app.lawnchair.organizer.application.public.PreviewFolderRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.WarningCode
import java.util.Locale

/**
 * Resolved display wording for the concrete change list (Issue #195). The pure
 * row builder below has no string resources of its own: composables supply an
 * implementation backed by `stringResource`, and JVM tests supply literals.
 * Format templates use positional `%n$` placeholders like the resources they
 * mirror (values/ + values-ja/, Issue #123 contract).
 */
interface OrganizationPreviewWording {
    val groupMoved: String
    val groupNewFolders: String
    val groupNewPages: String
    val groupPreserved: String
    val groupWarnings: String
    val moveRow: String
    val sameBandMoveRow: String
    val rowOrdinalNote: String
    val itemRow: String
    val moveReasonSinglePlacement: String
    val moveReasonFolderMember: String
    val moveReasonFolderUnit: String
    val moveReasonUnspecified: String
    val preservedReasonLocked: String
    val preservedReasonReservedRegion: String
    val preservedReasonUnavailable: String
    val preservedReasonDock: String
    val preservedReasonWidget: String
    val preservedReasonAppPair: String
    val preservedReasonLegacyShortcut: String
    val preservedReasonNonTarget: String
    val preservedReasonStructural: String
    val preservedReasonAlreadyCanonical: String
    val warningLegacyShortcutReview: String
    val warningFallbackCategory: String
    val warningUnavailablePreserved: String
    val pagePosition: String
    val newPagePosition: String
    val workspacePosition: String
    val regionTopLeft: String
    val regionTopCenter: String
    val regionTopRight: String
    val regionMiddleLeft: String
    val regionMiddleCenter: String
    val regionMiddleRight: String
    val regionBottomLeft: String
    val regionBottomCenter: String
    val regionBottomRight: String
    val dockPosition: String
    val folderPositionExisting: String
    val folderPositionPlanned: String
    val appPairPosition: String
    val kindApplication: String
    val kindDeepShortcut: String
    val kindShortcutLegacy: String
    val kindFolder: String
    val kindAppWidget: String
    val kindCustomAppWidget: String
    val kindAppPair: String
    val kindUnknown: String
    val newFolderRow: String
    val newPageRow: String
}

/**
 * One grouped change list section: a heading with the [PreviewCounts]-derived
 * total (rows and header always agree, even while truncated) and the
 * deterministic row texts for that group.
 */
data class OrganizationPreviewSection(
    val heading: String,
    val totalCount: Int,
    val rows: List<String>,
)

/**
 * Pure builder of the confirmation change list (Issue #195). Consumes only the
 * spec 194 `PreviewChange` projection; never re-sorts rows (projection order is
 * authoritative), never leaks raw identifiers, and performs no I/O so identical
 * inputs render identically.
 */
object OrganizationPreviewContent {

    fun sections(details: PlanPreviewDetails, wording: OrganizationPreviewWording): List<OrganizationPreviewSection> {
        val changes = details.changes
        val counts = details.counts
        val sections = mutableListOf<OrganizationPreviewSection>()
        val moves = changes.filterIsInstance<MoveChange>().map { moveRowText(it, wording) }
        if (moves.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupMoved, counts.movedCount), counts.movedCount, moves)
        val folders = changes.filterIsInstance<NewFolderChange>().map { newFolderRowText(it, wording) }
        if (folders.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupNewFolders, counts.newFolderCount), counts.newFolderCount, folders)
        val pages = changes.filterIsInstance<NewPageChange>().map { newPageRowText(it, wording) }
        if (pages.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupNewPages, counts.newPageCount), counts.newPageCount, pages)
        val preserved = changes.filterIsInstance<PreservedChange>().map { preservedRowText(it, wording) }
        if (preserved.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupPreserved, counts.preservedCount), counts.preservedCount, preserved)
        val warnings = changes.filterIsInstance<ItemWarningChange>().map { warningRowText(it, wording) }
        if (warnings.isNotEmpty()) {
            // Spec §D2 exception: this group speaks for its concrete rows only.
            // Global / multi-item warnings stay header-count-only (PreviewCounts
            // carries all warnings), so the heading count matches the row count.
            sections += OrganizationPreviewSection(
                format(wording.groupWarnings, warnings.size),
                warnings.size,
                warnings,
            )
        }
        return sections
    }

    fun moveRowText(change: MoveChange, wording: OrganizationPreviewWording): String {
        val label = labelText(change.label, wording)
        return if (change.sameBandAdjustment) {
            // sameBandAdjustment guarantees both ends are same-page workspaces.
            val source = change.source as PreviewPosition.Workspace
            val note = rowOrdinalNote(change, wording)
            format(wording.sameBandMoveRow, label, regionText(source, wording), note)
        } else {
            val source = positionText(change.source, wording)
            val destination = positionText(change.destination, wording) + rowOrdinalNote(change, wording)
            format(wording.moveRow, label, source, destination, moveReasonText(change.rationale, wording))
        }
    }

    fun positionText(position: PreviewPosition, wording: OrganizationPreviewWording): String = when (position) {
        is PreviewPosition.Workspace -> format(
            wording.workspacePosition,
            workspacePageText(position, wording),
            regionText(position, wording),
        )

        is PreviewPosition.DockRank -> format(wording.dockPosition, position.rank + 1)

        is PreviewPosition.InFolder -> when (val folder = position.folder) {
            is PreviewFolderRef.Existing -> format(wording.folderPositionExisting, labelText(folder.label, wording))

            // Issue #201: planned-folder destinations show the resolved name,
            // never the planner ordinal.
            is PreviewFolderRef.Planned -> format(wording.folderPositionPlanned, labelText(folder.name, wording))
        }

        is PreviewPosition.InAppPair -> format(wording.appPairPosition, labelText(position.pair, wording))
    }

    fun labelText(label: PreviewLabel, wording: OrganizationPreviewWording): String = when (label) {
        is PreviewLabel.Named -> label.value
        is PreviewLabel.KindFallback -> kindText(label.kind, wording)
    }

    private fun newFolderRowText(change: NewFolderChange, wording: OrganizationPreviewWording): String {
        // Issue #201: the row leads with the resolved folder name instead of the
        // planner ordinal — an internal identifier has no place in user copy.
        val name = labelText(change.name, wording)
        return format(
            wording.newFolderRow,
            name,
            positionText(change.placement, wording),
            change.memberLabels.joinToString(SEPARATOR) { labelText(it, wording) },
        )
    }

    private fun newPageRowText(change: NewPageChange, wording: OrganizationPreviewWording): String = format(
        wording.newPageRow,
        change.displayPosition,
    )

    private fun preservedRowText(change: PreservedChange, wording: OrganizationPreviewWording): String = format(
        wording.itemRow,
        labelText(change.label, wording),
        preservedReasonText(change.reason, wording),
    )

    private fun warningRowText(change: ItemWarningChange, wording: OrganizationPreviewWording): String = format(
        wording.itemRow,
        labelText(change.label, wording),
        warningText(change.code, wording),
    )

    /**
     * Spec §D5: a row-ordinal note restores row-direction changes the 3×3 band
     * wording cannot express — same page, unchanged row band, changed row
     * ordinal (e.g. "top center → top left (from row 2 to row 1)"). Band changes
     * already speak for themselves; other containers carry no rows.
     */
    private fun rowOrdinalNote(change: MoveChange, wording: OrganizationPreviewWording): String {
        val source = change.source as? PreviewPosition.Workspace ?: return ""
        val destination = change.destination as? PreviewPosition.Workspace ?: return ""
        if (source.pageDisplayOrdinal != destination.pageDisplayOrdinal) return ""
        if (source.rowBand != destination.rowBand) return ""
        if (source.rowOrdinal == destination.rowOrdinal) return ""
        return format(wording.rowOrdinalNote, source.rowOrdinal, destination.rowOrdinal)
    }

    private fun moveReasonText(rationale: PlacementCode?, wording: OrganizationPreviewWording): String = when (rationale) {
        PlacementCode.SINGLE_PLACEMENT -> wording.moveReasonSinglePlacement
        PlacementCode.FOLDER_MEMBER -> wording.moveReasonFolderMember
        PlacementCode.FOLDER_UNIT -> wording.moveReasonFolderUnit
        null -> wording.moveReasonUnspecified
    }

    private fun preservedReasonText(reason: PreserveReason, wording: OrganizationPreviewWording): String = when (reason) {
        PreserveReason.LOCKED -> wording.preservedReasonLocked
        PreserveReason.RESERVED_REGION -> wording.preservedReasonReservedRegion
        PreserveReason.UNAVAILABLE_TARGET -> wording.preservedReasonUnavailable
        PreserveReason.DOCK -> wording.preservedReasonDock
        PreserveReason.WIDGET -> wording.preservedReasonWidget
        PreserveReason.APP_PAIR -> wording.preservedReasonAppPair
        PreserveReason.LEGACY_SHORTCUT -> wording.preservedReasonLegacyShortcut
        PreserveReason.NON_TARGET -> wording.preservedReasonNonTarget
        PreserveReason.STRUCTURAL -> wording.preservedReasonStructural
        PreserveReason.ALREADY_CANONICAL -> wording.preservedReasonAlreadyCanonical
    }

    private fun warningText(code: WarningCode, wording: OrganizationPreviewWording): String = when (code) {
        WarningCode.LEGACY_SHORTCUT_REVIEW -> wording.warningLegacyShortcutReview
        WarningCode.FALLBACK_CATEGORY -> wording.warningFallbackCategory
        WarningCode.UNAVAILABLE_PRESERVED -> wording.warningUnavailablePreserved
    }

    private fun kindText(kind: CanonicalItemKind, wording: OrganizationPreviewWording): String = when (kind) {
        CanonicalItemKind.Application -> wording.kindApplication
        CanonicalItemKind.DeepShortcut -> wording.kindDeepShortcut
        CanonicalItemKind.ShortcutLegacy -> wording.kindShortcutLegacy
        CanonicalItemKind.Folder -> wording.kindFolder
        CanonicalItemKind.AppWidget -> wording.kindAppWidget
        CanonicalItemKind.CustomAppWidget -> wording.kindCustomAppWidget
        CanonicalItemKind.AppPair -> wording.kindAppPair
        is CanonicalItemKind.Unknown -> wording.kindUnknown
    }

    private fun workspacePageText(position: PreviewPosition.Workspace, wording: OrganizationPreviewWording): String = if (position.isNewPage) {
        format(wording.newPagePosition, position.pageDisplayOrdinal)
    } else {
        format(wording.pagePosition, position.pageDisplayOrdinal)
    }

    private fun regionText(position: PreviewPosition.Workspace, wording: OrganizationPreviewWording): String = when (position.rowBand) {
        RowBand.TOP -> when (position.columnBand) {
            ColumnBand.LEFT -> wording.regionTopLeft
            ColumnBand.CENTER -> wording.regionTopCenter
            ColumnBand.RIGHT -> wording.regionTopRight
        }

        RowBand.CENTER -> when (position.columnBand) {
            ColumnBand.LEFT -> wording.regionMiddleLeft
            ColumnBand.CENTER -> wording.regionMiddleCenter
            ColumnBand.RIGHT -> wording.regionMiddleRight
        }

        RowBand.BOTTOM -> when (position.columnBand) {
            ColumnBand.LEFT -> wording.regionBottomLeft
            ColumnBand.CENTER -> wording.regionBottomCenter
            ColumnBand.RIGHT -> wording.regionBottomRight
        }
    }

    private fun format(template: String, vararg args: Any?): String = String.format(Locale.getDefault(), template, *args)

    private const val SEPARATOR = ", "
}
