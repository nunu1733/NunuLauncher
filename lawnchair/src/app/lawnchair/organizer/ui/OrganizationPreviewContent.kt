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
import app.lawnchair.organizer.application.public.PreviewPlacementIdentity
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.SplitStage
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

    /** Issue #208: %1 = source descriptor (name, kind, current position),
     *  %2 = destination, %3 = reason. Quoting lives in the descriptor. */
    val moveRow: String

    /** Issue #208: %1 = full source descriptor (same path as the plain move
     *  row), %2 = region, %3 = ordinal note. */
    val sameBandMoveRow: String
    val rowOrdinalNote: String

    /** %1 = descriptor (name, kind, current position), %2 = fate wording. */
    val itemRow: String

    /** Issue #208: %1 = name, %2 = kind, %3 = current position. */
    val itemDescriptor: String

    /** Issue #208: kindless variant for kind-fallback labels. */
    val itemDescriptorWithoutKind: String
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
    val preservedReasonStrategyPreserved: String
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

    /** Issue #208: %1 = folder title, %2 = 1-based position inside the folder. */
    val folderPositionExisting: String
    val folderPositionPlanned: String
    val appPairPosition: String

    /** Issue #208: %1 = position text, %2 = identity-derived supplement. */
    val positionWithSupplement: String

    /** Issue #208: %1 = 1-based row ordinal, %2 = 1-based column ordinal,
     *  %3 = page wording — the full parent locator for nested collisions. */
    val supplementCell: String

    /** Issue #208: %1 = parent locator, %2 = split stage word. */
    val supplementParentWithStage: String

    /** Issue #208: split-pair stage words for same-named pair children. */
    val supplementStageTop: String
    val supplementStageBottom: String

    /** Issue #208: %1 = proposal-local ordinal (never a raw identifier). */
    val unidentifiedPosition: String
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
 *
 * Issue #208: every row's leading source descriptor ("name (kind) — position")
 * is built by [descriptorText] alone, so two same-named placements can never
 * collapse into one row prefix; the fate part (destination / reason / warning)
 * is appended by the row formatters.
 */
object OrganizationPreviewContent {

    fun sections(details: PlanPreviewDetails, wording: OrganizationPreviewWording): List<OrganizationPreviewSection> {
        val changes = details.changes
        val counts = details.counts
        val supplements = descriptorSupplements(changes, wording)
        val sections = mutableListOf<OrganizationPreviewSection>()
        val moves = changes.filterIsInstance<MoveChange>().map { moveRowText(it, wording, supplements[it]) }
        if (moves.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupMoved, counts.movedCount), counts.movedCount, moves)
        val folders = changes.filterIsInstance<NewFolderChange>().map { newFolderRowText(it, wording) }
        if (folders.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupNewFolders, counts.newFolderCount), counts.newFolderCount, folders)
        val pages = changes.filterIsInstance<NewPageChange>().map { newPageRowText(it, wording) }
        if (pages.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupNewPages, counts.newPageCount), counts.newPageCount, pages)
        val preserved = changes.filterIsInstance<PreservedChange>().map { preservedRowText(it, wording, supplements[it]) }
        if (preserved.isNotEmpty()) sections += OrganizationPreviewSection(format(wording.groupPreserved, counts.preservedCount), counts.preservedCount, preserved)
        val warnings = changes.filterIsInstance<ItemWarningChange>().map { warningRowText(it, wording, supplements[it]) }
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

    /**
     * The source-placement part of a row: name (with kind), position. Rows
     * distinguish same-named placements through this prefix alone, which is
     * the direct assertion target of the AC-3 descriptor-uniqueness tests.
     */
    fun descriptorText(
        label: PreviewLabel,
        kind: CanonicalItemKind,
        current: PreviewPosition,
        wording: OrganizationPreviewWording,
        supplement: String? = null,
    ): String {
        val name = labelText(label, wording)
        val position = if (supplement == null) {
            positionText(current, wording)
        } else {
            format(wording.positionWithSupplement, positionText(current, wording), supplement)
        }
        return if (label is PreviewLabel.KindFallback) {
            // The fallback label already IS the kind word — no duplication.
            format(wording.itemDescriptorWithoutKind, name, position)
        } else {
            format(wording.itemDescriptor, name, kindText(kind, wording), position)
        }
    }

    /**
     * Issue #208 (AC-3): rows whose (label, kind, position) triple collides
     * with another row receive an identity-derived supplement so their
     * rendered descriptors stay unique. Deterministic and computed once per
     * proposal; unique rows are never touched. Nested identities supplement
     * with the full parent locator (page + row/column, dock slot) — not just
     * the cell — so same-named parents on different pages stay distinct too.
     */
    private fun descriptorSupplements(
        changes: List<PreviewChange>,
        wording: OrganizationPreviewWording,
    ): Map<PreviewChange, String> {
        data class DescriptorKey(val label: String, val kind: String, val position: String)

        val keys = changes.mapNotNull { change ->
            val key = when (change) {
                is MoveChange -> DescriptorKey(labelText(change.label, wording), kindText(change.kind, wording), positionText(change.source, wording))
                is PreservedChange -> DescriptorKey(labelText(change.label, wording), kindText(change.kind, wording), positionText(change.current, wording))
                is ItemWarningChange -> DescriptorKey(labelText(change.label, wording), kindText(change.kind, wording), positionText(change.current, wording))
                else -> null
            }
            key?.let { change to it }
        }.toMap()
        val colliding = keys.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return keys.mapNotNull { (change, key) ->
            if (key in colliding) {
                identitySupplement(identityOf(change), wording)?.let { change to it }
            } else {
                null
            }
        }.toMap()
    }

    private fun identityOf(change: PreviewChange): PreviewPlacementIdentity? = when (change) {
        is MoveChange -> change.identity
        is PreservedChange -> change.identity
        is ItemWarningChange -> change.identity
        else -> null
    }

    private fun identitySupplement(identity: PreviewPlacementIdentity?, wording: OrganizationPreviewWording): String? = when (identity) {
        is PreviewPlacementIdentity.Workspace -> workspaceLocator(identity, wording)

        is PreviewPlacementIdentity.Dock -> format(wording.dockPosition, identity.rank + 1)

        is PreviewPlacementIdentity.FolderChild -> parentLocator(identity.parent, wording)

        is PreviewPlacementIdentity.AppPairChild -> {
            val stage = when (identity.stage) {
                SplitStage.TOP_OR_LEFT -> wording.supplementStageTop
                SplitStage.BOTTOM_OR_RIGHT -> wording.supplementStageBottom
            }
            parentLocator(identity.parent, wording)?.let { parent ->
                format(wording.supplementParentWithStage, parent, stage)
            }
        }

        else -> null
    }

    private fun parentLocator(parent: PreviewPlacementIdentity, wording: OrganizationPreviewWording): String? = when (parent) {
        is PreviewPlacementIdentity.Workspace -> workspaceLocator(parent, wording)
        is PreviewPlacementIdentity.Dock -> format(wording.dockPosition, parent.rank + 1)
        else -> null
    }

    private fun workspaceLocator(identity: PreviewPlacementIdentity.Workspace, wording: OrganizationPreviewWording): String {
        val page = if (identity.isNewPage) {
            format(wording.newPagePosition, identity.pageDisplayOrdinal)
        } else {
            format(wording.pagePosition, identity.pageDisplayOrdinal)
        }
        return format(
            wording.supplementCell,
            identity.cellY + 1,
            identity.cellX + 1,
            page,
        )
    }

    fun moveRowText(change: MoveChange, wording: OrganizationPreviewWording, supplement: String? = null): String {
        // Issue #208: every move row speaks its source placement through the
        // shared descriptor path — the same-band branch only replaces the
        // destination part, so same-named sources can never collapse.
        val source = descriptorText(change.label, change.kind, change.source, wording, supplement)
        if (change.sameBandAdjustment) {
            // sameBandAdjustment guarantees both ends are same-page workspaces.
            val destination = change.destination as PreviewPosition.Workspace
            val note = rowOrdinalNote(change, wording)
            return format(wording.sameBandMoveRow, source, regionText(destination, wording), note)
        }
        val destination = positionText(change.destination, wording) + rowOrdinalNote(change, wording)
        return format(wording.moveRow, source, destination, moveReasonText(change.rationale, wording))
    }

    fun positionText(position: PreviewPosition, wording: OrganizationPreviewWording): String = when (position) {
        is PreviewPosition.Workspace -> format(
            wording.workspacePosition,
            workspacePageText(position, wording),
            regionText(position, wording),
        )

        is PreviewPosition.DockRank -> format(wording.dockPosition, position.rank + 1)

        is PreviewPosition.InFolder -> when (val folder = position.folder) {
            is PreviewFolderRef.Existing -> format(wording.folderPositionExisting, labelText(folder.label, wording), position.rank)

            // Issue #201: planned-folder destinations show the resolved name,
            // never the planner ordinal.
            is PreviewFolderRef.Planned -> format(wording.folderPositionPlanned, labelText(folder.name, wording), position.rank)
        }

        is PreviewPosition.InAppPair -> format(wording.appPairPosition, labelText(position.pair, wording))

        // Issue #208: raw codes and discriminators never render; the generic
        // ordinal keeps same-named unsupported rows distinguishable.
        is PreviewPosition.Unidentified -> format(wording.unidentifiedPosition, position.proposalLocalOrdinal)
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

    private fun preservedRowText(change: PreservedChange, wording: OrganizationPreviewWording, supplement: String? = null): String = format(
        wording.itemRow,
        descriptorText(change.label, change.kind, change.current, wording, supplement),
        preservedReasonText(change.reason, wording),
    )

    private fun warningRowText(change: ItemWarningChange, wording: OrganizationPreviewWording, supplement: String? = null): String = format(
        wording.itemRow,
        descriptorText(change.label, change.kind, change.current, wording, supplement),
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
        PreserveReason.STRATEGY_PRESERVED -> wording.preservedReasonStrategyPreserved
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
