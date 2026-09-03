package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.ItemWarningChange
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.NewFolderChange
import app.lawnchair.organizer.application.public.NewPageChange
import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PreservedChange
import app.lawnchair.organizer.application.public.PreviewCounts
import app.lawnchair.organizer.application.public.PreviewFolderRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure row-builder contract for the confirmation change list (Issue #195,
 * spec AC-1/AC-2): grouping order, `PreviewCounts` header truth, position
 * wording, same-band adjustment rows, row-ordinal notes, and label fallback.
 */
class OrganizationPreviewContentTest {

    @Test
    fun sectionsGroupChangesInProjectionOrderWithCountsTruth() {
        val details = PlanPreviewDetails(
            changes = listOf(
                move("game", source(1, RowBand.TOP, ColumnBand.CENTER, 2), destination(1, RowBand.BOTTOM, ColumnBand.RIGHT, 5)),
                move("maps", source(2, RowBand.TOP, ColumnBand.LEFT, 1), dock(1)),
                newFolder(0),
                newPage(2),
                preserved("clock", PreserveReason.LOCKED),
                itemWarning("notes", WarningCode.FALLBACK_CATEGORY),
            ),
            counts = PreviewCounts(
                movedCount = 2,
                preservedCount = 1,
                newFolderCount = 1,
                newPageCount = 1,
                warningCounts = mapOf(WarningCode.FALLBACK_CATEGORY to 2),
            ),
        )

        val sections = OrganizationPreviewContent.sections(details, TestWording)

        assertEquals(
            listOf("Move (2)", "New folders (1)", "New pages (1)", "Preserve (1)", "Warnings (2)"),
            sections.map { it.heading },
        )
        assertEquals(listOf(2, 1, 1, 1, 2), sections.map { it.totalCount })
        assertEquals(2, sections[0].rows.size)
    }

    @Test
    fun emptyGroupsAreOmitted() {
        val details = PlanPreviewDetails(
            changes = listOf(move("game", source(1, RowBand.TOP, ColumnBand.LEFT, 1), destination(1, RowBand.TOP, ColumnBand.RIGHT, 1))),
            counts = PreviewCounts(1, 0, 0, 0, emptyMap()),
        )

        val sections = OrganizationPreviewContent.sections(details, TestWording)

        assertEquals(listOf("Move (1)"), sections.map { it.heading })
    }

    @Test
    fun moveRowSpeaksPagesRegionsReasonsAndRowOrdinalNote() {
        val change = move(
            label = "game",
            source = source(1, RowBand.TOP, ColumnBand.CENTER, 2),
            destination = destination(1, RowBand.TOP, ColumnBand.LEFT, 1),
        )

        assertEquals(
            "“game”: top center, page 1 → top left, page 1 (from row 2 to row 1) (moves as a single placement)",
            OrganizationPreviewContent.moveRowText(change, TestWording),
        )
    }

    @Test
    fun bandChangeSpeaksForItselfWithoutOrdinalNote() {
        val change = labeledMove(
            label = PreviewLabel.Named("game"),
            source = source(1, RowBand.TOP, ColumnBand.CENTER, 2),
            destination = destination(1, RowBand.CENTER, ColumnBand.CENTER, 3),
            rationale = PlacementCode.FOLDER_MEMBER,
        )

        assertEquals(
            "“game”: top center, page 1 → middle center, page 1 (moves as a folder member)",
            OrganizationPreviewContent.moveRowText(change, TestWording),
        )
    }

    @Test
    fun crossPageMoveOmitsOrdinalNote() {
        val change = labeledMove(
            label = PreviewLabel.Named("game"),
            source = source(1, RowBand.TOP, ColumnBand.CENTER, 2),
            destination = destination(2, RowBand.TOP, ColumnBand.LEFT, 1),
            rationale = null,
        )

        assertEquals(
            "“game”: top center, page 1 → top left, page 2 (moves)",
            OrganizationPreviewContent.moveRowText(change, TestWording),
        )
    }

    @Test
    fun sameBandAdjustmentUsesDedicatedRowAndOrdinalNoteWhenRowsDiffer() {
        val sameRow = move(
            label = "game",
            source = source(1, RowBand.TOP, ColumnBand.CENTER, 1),
            destination = destination(1, RowBand.TOP, ColumnBand.CENTER, 1),
        )
        val differentRow = move(
            label = "game",
            source = source(1, RowBand.TOP, ColumnBand.CENTER, 2),
            destination = destination(1, RowBand.TOP, ColumnBand.CENTER, 1),
        )

        assertEquals(
            "“game”: position adjusted within top center",
            OrganizationPreviewContent.moveRowText(sameRow, TestWording),
        )
        assertEquals(
            "“game”: position adjusted within top center (from row 2 to row 1)",
            OrganizationPreviewContent.moveRowText(differentRow, TestWording),
        )
    }

    @Test
    fun dockAndPlannedFolderPositionsAreOneBased() {
        val dock = OrganizationPreviewContent.positionText(dock(1), TestWording)
        val plannedFolder = OrganizationPreviewContent.positionText(
            PreviewPosition.InFolder(PreviewFolderRef.Planned(NewFolderOrdinal(0))),
            TestWording,
        )

        assertEquals("Dock slot 2", dock)
        assertEquals("new folder 1", plannedFolder)
    }

    @Test
    fun newFolderRowListsPlacementAndMembers() {
        val change = NewFolderChange(
            ordinal = NewFolderOrdinal(0),
            placement = source(2, RowBand.CENTER, ColumnBand.LEFT, 3),
            memberLabels = listOf(PreviewLabel.Named("game"), PreviewLabel.Named("maps")),
        )

        assertEquals(
            "Create new folder 1 at middle left, page 2 (members: game, maps)",
            OrganizationPreviewContent.sections(
                PlanPreviewDetails(listOf(change), PreviewCounts(0, 0, 1, 0, emptyMap())),
                TestWording,
            ).single().rows.single(),
        )
    }

    @Test
    fun preservedAndWarningRowsSpeakPerItemReasons() {
        val details = PlanPreviewDetails(
            changes = listOf(
                preserved("clock", PreserveReason.LOCKED),
                itemWarning("notes", WarningCode.LEGACY_SHORTCUT_REVIEW),
            ),
            counts = PreviewCounts(0, 1, 0, 0, mapOf(WarningCode.LEGACY_SHORTCUT_REVIEW to 1)),
        )

        val sections = OrganizationPreviewContent.sections(details, TestWording)

        assertEquals("“clock”: kept because it is locked", sections[0].rows.single())
        assertEquals("“notes”: legacy shortcut needs review", sections[1].rows.single())
    }

    @Test
    fun absentTitlesFallBackToKindWording() {
        val change = labeledMove(
            label = PreviewLabel.KindFallback(app.lawnchair.organizer.application.public.CanonicalItemKind.AppWidget),
            source = source(1, RowBand.TOP, ColumnBand.LEFT, 1),
            destination = destination(1, RowBand.TOP, ColumnBand.RIGHT, 1),
            rationale = PlacementCode.SINGLE_PLACEMENT,
        )

        assertEquals(
            "“Widget”: top left, page 1 → top right, page 1 (moves as a single placement)",
            OrganizationPreviewContent.moveRowText(change, TestWording),
        )
    }

    @Test
    fun sectionsAreDeterministicForIdenticalDetails() {
        val details = PlanPreviewDetails(
            changes = listOf(
                move("game", source(1, RowBand.TOP, ColumnBand.CENTER, 2), destination(1, RowBand.TOP, ColumnBand.LEFT, 1)),
                preserved("clock", PreserveReason.ALREADY_CANONICAL),
            ),
            counts = PreviewCounts(1, 1, 0, 0, emptyMap()),
        )

        assertEquals(
            OrganizationPreviewContent.sections(details, TestWording),
            OrganizationPreviewContent.sections(details, TestWording),
        )
    }

    private fun move(
        label: String,
        source: PreviewPosition,
        destination: PreviewPosition,
    ) = labeledMove(PreviewLabel.Named(label), source, destination, PlacementCode.SINGLE_PLACEMENT)

    private fun labeledMove(
        label: PreviewLabel,
        source: PreviewPosition,
        destination: PreviewPosition,
        rationale: PlacementCode?,
    ) = MoveChange(
        item = ItemId(label.toString()),
        label = label,
        source = source,
        destination = destination,
        rationale = rationale,
    )

    private fun source(page: Int, rowBand: RowBand, columnBand: ColumnBand, rowOrdinal: Int) = workspace(page, rowBand, columnBand, rowOrdinal)

    private fun destination(page: Int, rowBand: RowBand, columnBand: ColumnBand, rowOrdinal: Int) = workspace(page, rowBand, columnBand, rowOrdinal)

    private fun workspace(page: Int, rowBand: RowBand, columnBand: ColumnBand, rowOrdinal: Int) = PreviewPosition.Workspace(
        pageDisplayOrdinal = page,
        isNewPage = false,
        rowBand = rowBand,
        columnBand = columnBand,
        rowOrdinal = rowOrdinal,
    )

    private fun dock(rank: Int) = PreviewPosition.DockRank(rank)

    private fun newFolder(ordinal: Int) = NewFolderChange(
        ordinal = NewFolderOrdinal(ordinal),
        placement = source(1, RowBand.TOP, ColumnBand.LEFT, 1),
        memberLabels = listOf(PreviewLabel.Named("game")),
    )

    private fun newPage(displayPosition: Int) = NewPageChange(ordinal = NewPageOrdinal(0), displayPosition = displayPosition)

    private fun preserved(label: String, reason: PreserveReason) = PreservedChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        reason = reason,
    )

    private fun itemWarning(label: String, code: WarningCode) = ItemWarningChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        code = code,
    )

    private object TestWording : OrganizationPreviewWording {
        override val groupMoved = "Move (%1\$d)"
        override val groupNewFolders = "New folders (%1\$d)"
        override val groupNewPages = "New pages (%1\$d)"
        override val groupPreserved = "Preserve (%1\$d)"
        override val groupWarnings = "Warnings (%1\$d)"
        override val moveRow = "“%1\$s”: %2\$s → %3\$s (%4\$s)"
        override val sameBandMoveRow = "“%1\$s”: position adjusted within %2\$s%3\$s"
        override val rowOrdinalNote = " (from row %1\$d to row %2\$d)"
        override val itemRow = "“%1\$s”: %2\$s"
        override val moveReasonSinglePlacement = "moves as a single placement"
        override val moveReasonFolderMember = "moves as a folder member"
        override val moveReasonFolderUnit = "moves as a folder unit"
        override val moveReasonUnspecified = "moves"
        override val preservedReasonLocked = "kept because it is locked"
        override val preservedReasonReservedRegion = "kept for the reserved search area"
        override val preservedReasonUnavailable = "kept because it is unavailable"
        override val preservedReasonDock = "kept in the Dock"
        override val preservedReasonWidget = "kept because it is a widget"
        override val preservedReasonAppPair = "kept because it is an app pair"
        override val preservedReasonLegacyShortcut = "kept because it is a legacy shortcut"
        override val preservedReasonNonTarget = "kept because it is out of scope"
        override val preservedReasonStructural = "kept as a structural member"
        override val preservedReasonAlreadyCanonical = "already in its canonical place"
        override val warningLegacyShortcutReview = "legacy shortcut needs review"
        override val warningFallbackCategory = "fallback category was used"
        override val warningUnavailablePreserved = "kept as it is currently unavailable"
        override val pagePosition = "page %1\$d"
        override val newPagePosition = "new page (position %1\$d)"
        override val workspacePosition = "%2\$s, %1\$s"
        override val regionTopLeft = "top left"
        override val regionTopCenter = "top center"
        override val regionTopRight = "top right"
        override val regionMiddleLeft = "middle left"
        override val regionMiddleCenter = "middle center"
        override val regionMiddleRight = "middle right"
        override val regionBottomLeft = "bottom left"
        override val regionBottomCenter = "bottom center"
        override val regionBottomRight = "bottom right"
        override val dockPosition = "Dock slot %1\$d"
        override val folderPositionExisting = "folder “%1\$s”"
        override val folderPositionPlanned = "new folder %1\$d"
        override val appPairPosition = "app pair “%1\$s”"
        override val kindApplication = "App"
        override val kindDeepShortcut = "Shortcut"
        override val kindShortcutLegacy = "Legacy shortcut"
        override val kindFolder = "Folder"
        override val kindAppWidget = "Widget"
        override val kindCustomAppWidget = "Custom widget"
        override val kindAppPair = "App pair"
        override val kindUnknown = "Item"
        override val newFolderRow = "Create new folder %1\$d at %2\$s (members: %3\$s)"
        override val newPageRow = "Add a new page at position %1\$d"
    }
}
