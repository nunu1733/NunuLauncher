package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.CanonicalItemKind
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
import app.lawnchair.organizer.application.public.PreviewPlacementIdentity
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure row-builder contract for the confirmation change list (Issue #195,
 * spec AC-1/AC-2; Issue #208 AC-3/AC-4/AC-6): grouping order, `PreviewCounts`
 * header truth, position wording, same-band adjustment rows, row-ordinal
 * notes, label fallback, and the source-descriptor identity contract that
 * keeps same-named placements distinguishable.
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
                preserved("clock", "clock", PreserveReason.LOCKED),
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
            listOf("Move (2)", "New folders (1)", "New pages (1)", "Preserve (1)", "Warnings (1)"),
            sections.map { it.heading },
        )
        assertEquals(listOf(2, 1, 1, 1, 1), sections.map { it.totalCount })
        assertEquals(2, sections[0].rows.size)
    }

    @Test
    fun warningGroupCountsConcreteRowsWhileHeaderKeepsAllWarnings() {
        val details = PlanPreviewDetails(
            changes = listOf(itemWarning("notes", WarningCode.FALLBACK_CATEGORY)),
            // Spec §D2 exception: warningCounts carries all warnings (1 item + 2
            // global here), while the group speaks only for its concrete rows.
            counts = PreviewCounts(0, 0, 0, 0, warningCounts = mapOf(WarningCode.FALLBACK_CATEGORY to 3)),
        )

        val warnings = OrganizationPreviewContent.sections(details, TestWording).single()

        assertEquals("Warnings (1)", warnings.heading)
        assertEquals(1, warnings.totalCount)
        assertEquals(1, warnings.rows.size)
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
    fun moveRowSpeaksNameKindSourceDestinationReasonAndOrdinalNote() {
        val change = move(
            label = "game",
            source = source(1, RowBand.TOP, ColumnBand.CENTER, 2),
            destination = destination(1, RowBand.TOP, ColumnBand.LEFT, 1),
        )

        assertEquals(
            "“game” (App) — top center, page 1 → top left, page 1 (from row 2 to row 1) (moves as a single placement)",
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
            "“game” (App) — top center, page 1 → middle center, page 1 (moves as a folder member)",
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
            "“game” (App) — top center, page 1 → top left, page 2 (moves)",
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
            "“game” (App): position adjusted within top center",
            OrganizationPreviewContent.moveRowText(sameRow, TestWording),
        )
        assertEquals(
            "“game” (App): position adjusted within top center (from row 2 to row 1)",
            OrganizationPreviewContent.moveRowText(differentRow, TestWording),
        )
    }

    @Test
    fun dockPositionsAreOneBasedAndFoldersRenderNameAndInsidePosition() {
        val dock = OrganizationPreviewContent.positionText(dock(1), TestWording)
        val existingFolder = OrganizationPreviewContent.positionText(
            PreviewPosition.InFolder(PreviewFolderRef.Existing(PreviewLabel.Named("Work")), 2),
            TestWording,
        )
        // Issue #201: planned-folder destinations render the resolved name,
        // never the planner ordinal. Issue #208: the in-folder position rides
        // along so same-named folder children stay distinguishable.
        val plannedFolder = OrganizationPreviewContent.positionText(
            PreviewPosition.InFolder(
                PreviewFolderRef.Planned(NewFolderOrdinal(0), PreviewLabel.Named("Communication")),
                1,
            ),
            TestWording,
        )

        assertEquals("Dock slot 2", dock)
        assertEquals("folder “Work”, position 2", existingFolder)
        assertEquals("new folder “Communication”, position 1", plannedFolder)
    }

    @Test
    fun newFolderRowListsPlacementAndMembers() {
        val change = NewFolderChange(
            ordinal = NewFolderOrdinal(0),
            name = PreviewLabel.Named("Communication"),
            placement = source(2, RowBand.CENTER, ColumnBand.LEFT, 3),
            memberLabels = listOf(PreviewLabel.Named("game"), PreviewLabel.Named("maps")),
        )

        assertEquals(
            "Create new folder \u201cCommunication\u201d at middle left, page 2 (members: game, maps)",
            OrganizationPreviewContent.sections(
                PlanPreviewDetails(listOf(change), PreviewCounts(0, 0, 1, 0, emptyMap())),
                TestWording,
            ).single().rows.single(),
        )
    }

    @Test
    fun preservedAndWarningRowsSpeakNameKindPositionThenFate() {
        val details = PlanPreviewDetails(
            changes = listOf(
                preserved("clock", "clock", PreserveReason.LOCKED),
                itemWarning("notes", WarningCode.LEGACY_SHORTCUT_REVIEW),
            ),
            counts = PreviewCounts(0, 1, 0, 0, mapOf(WarningCode.LEGACY_SHORTCUT_REVIEW to 1)),
        )

        val sections = OrganizationPreviewContent.sections(details, TestWording)

        assertEquals("“clock” (App) — top center, page 1: kept because it is locked", sections[0].rows.single())
        assertEquals("“notes” (App) — Dock slot 3: legacy shortcut needs review", sections[1].rows.single())
    }

    @Test
    fun unsupportedCurrentPositionRendersGenericOrdinalWithoutRawCodes() {
        val row = OrganizationPreviewContent.descriptorText(
            label = PreviewLabel.Named("mystery"),
            kind = CanonicalItemKind.Application,
            current = PreviewPosition.Unidentified(2),
            wording = TestWording,
        )

        assertEquals("“mystery” (App) — unsupported placement 2", row)
    }

    /**
     * Issue #208 F2 (AC-3 matrix): same name, same kind, same page, same 3x3
     * band, different anchor cells. The coarse position text alone collides,
     * so the identity-derived row/column supplement keeps the descriptors —
     * and therefore the rows — distinct.
     */
    @Test
    fun sameNamedSameBandAnchorsGetDistinctDescriptorsViaCellSupplement() {
        val details = PlanPreviewDetails(
            changes = listOf(
                preserved("gmail.a", "Gmail", PreserveReason.NON_TARGET, cell = Grid(0, 0)),
                preserved("gmail.b", "Gmail", PreserveReason.NON_TARGET, cell = Grid(1, 0)),
            ),
            counts = PreviewCounts(0, 2, 0, 0, emptyMap()),
        )

        val rows = OrganizationPreviewContent.sections(details, TestWording).single().rows

        assertEquals(
            listOf(
                "“Gmail” (App) — top left, page 1 (row 1, column 1): kept because it is out of scope",
                "“Gmail” (App) — top left, page 1 (row 1, column 2): kept because it is out of scope",
            ),
            rows,
        )
        assertEquals(2, rows.toSet().size)
    }

    /**
     * Issue #208 F4 (AC-3 matrix): two same-named folders each holding a
     * same-named child at the same rank. The parent folder's cell supplement
     * is the only thing that can tell the two descriptors apart.
     */
    @Test
    fun sameNamedFolderParentsGetDistinctChildDescriptorsViaParentCellSupplement() {
        val details = PlanPreviewDetails(
            changes = listOf(
                preservedInFolder("gmail.a", "Gmail", folderTitle = "Google", folderCell = Grid(0, 0), rank = 1),
                preservedInFolder("gmail.b", "Gmail", folderTitle = "Google", folderCell = Grid(0, 3), rank = 1),
            ),
            counts = PreviewCounts(0, 2, 0, 0, emptyMap()),
        )

        val rows = OrganizationPreviewContent.sections(details, TestWording).single().rows

        assertEquals(
            listOf(
                "“Gmail” (App) — folder “Google”, position 2 (row 1, column 1): kept because it is out of scope",
                "“Gmail” (App) — folder “Google”, position 2 (row 4, column 1): kept because it is out of scope",
            ),
            rows,
        )
        assertEquals(2, rows.toSet().size)
    }

    /**
     * Issue #208 F5 (AC-3): same-named home icon (moved) and folder child
     * (preserved) in one proposal. The descriptors differ by position — no
     * supplement needed — and each row explains which placement it speaks
     * about.
     */
    @Test
    fun sameNamedMoveAndPreserveDescriptorsDifferWithoutSupplement() {
        val details = PlanPreviewDetails(
            changes = listOf(
                move("Photos", source(2, RowBand.BOTTOM, ColumnBand.LEFT, 5), destination(2, RowBand.TOP, ColumnBand.LEFT, 1)),
                preservedInFolder("photos.b", "Photos", folderTitle = "Utilities", folderCell = Grid(3, 3), rank = 0),
            ),
            counts = PreviewCounts(1, 1, 0, 0, emptyMap()),
        )

        val sections = OrganizationPreviewContent.sections(details, TestWording)

        assertEquals(
            "“Photos” (App) — bottom left, page 2 → top left, page 2 (moves as a single placement)",
            sections[0].rows.single(),
        )
        assertEquals(
            "“Photos” (App) — folder “Utilities”, position 1: kept because it is out of scope",
            sections[1].rows.single(),
        )
    }

    /** Issue #208 F6 (AC-3 matrix): dock ranks and in-folder ranks already
     *  differentiate their rows; colliding split-pair children get stage words. */
    @Test
    fun sameNamedSplitPairChildrenGetStageSupplement() {
        val details = PlanPreviewDetails(
            changes = listOf(
                preservedInAppPair("maps.a", "Maps", pairTitle = "Pair", stage = SplitStage.TOP_OR_LEFT),
                preservedInAppPair("maps.b", "Maps", pairTitle = "Pair", stage = SplitStage.BOTTOM_OR_RIGHT),
            ),
            counts = PreviewCounts(0, 2, 0, 0, emptyMap()),
        )

        val rows = OrganizationPreviewContent.sections(details, TestWording).single().rows

        assertEquals(
            listOf(
                "“Maps” (App) — app pair “Pair” (upper half): kept because it is out of scope",
                "“Maps” (App) — app pair “Pair” (lower half): kept because it is out of scope",
            ),
            rows,
        )
    }

    /** Issue #208 F7: kind-fallback labels already carry the kind word, so the
     *  descriptor must not duplicate it. */
    @Test
    fun kindFallbackRowsDoNotDuplicateTheKindWord() {
        val details = PlanPreviewDetails(
            changes = listOf(preservedFallback(CanonicalItemKind.AppWidget, PreserveReason.WIDGET)),
            counts = PreviewCounts(0, 1, 0, 0, emptyMap()),
        )

        val row = OrganizationPreviewContent.sections(details, TestWording).single().rows.single()

        assertEquals("“Widget” — Dock slot 3: kept because it is a widget", row)
    }

    @Test
    fun sectionsAreDeterministicForIdenticalDetails() {
        val details = PlanPreviewDetails(
            changes = listOf(
                move("game", source(1, RowBand.TOP, ColumnBand.CENTER, 2), destination(1, RowBand.TOP, ColumnBand.LEFT, 1)),
                preserved("clock", "clock", PreserveReason.ALREADY_CANONICAL),
            ),
            counts = PreviewCounts(1, 1, 0, 0, emptyMap()),
        )

        assertEquals(
            OrganizationPreviewContent.sections(details, TestWording),
            OrganizationPreviewContent.sections(details, TestWording),
        )
    }

    // Fixture helpers (synthetic identities only).

    private data class Grid(val x: Int, val y: Int)

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
        identity = PreviewPlacementIdentity.Workspace(1, false, 0, 0),
        kind = CanonicalItemKind.Application,
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
        name = PreviewLabel.Named("Communication"),
        placement = source(1, RowBand.TOP, ColumnBand.LEFT, 1),
        memberLabels = listOf(PreviewLabel.Named("game")),
    )

    private fun newPage(displayPosition: Int) = NewPageChange(ordinal = NewPageOrdinal(0), displayPosition = displayPosition)

    private fun preserved(
        itemId: String,
        labelText: String,
        reason: PreserveReason,
        cell: Grid = Grid(2, 1),
    ) = PreservedChange(
        item = ItemId(itemId),
        label = PreviewLabel.Named(labelText),
        identity = PreviewPlacementIdentity.Workspace(1, false, cell.x, cell.y),
        kind = CanonicalItemKind.Application,
        current = workspaceFromCell(cell),
        reason = reason,
    )

    private fun preservedInFolder(
        itemId: String,
        labelText: String,
        folderTitle: String,
        folderCell: Grid,
        rank: Int,
    ) = PreservedChange(
        item = ItemId(itemId),
        label = PreviewLabel.Named(labelText),
        identity = PreviewPlacementIdentity.FolderChild(
            PreviewPlacementIdentity.Workspace(1, false, folderCell.x, folderCell.y),
            rank,
        ),
        kind = CanonicalItemKind.Application,
        current = PreviewPosition.InFolder(PreviewFolderRef.Existing(PreviewLabel.Named(folderTitle)), rank + 1),
        reason = PreserveReason.NON_TARGET,
    )

    private fun preservedInAppPair(
        itemId: String,
        labelText: String,
        pairTitle: String,
        stage: SplitStage,
    ) = PreservedChange(
        item = ItemId(itemId),
        label = PreviewLabel.Named(labelText),
        identity = PreviewPlacementIdentity.AppPairChild(
            PreviewPlacementIdentity.Workspace(1, false, 2, 2),
            stage,
        ),
        kind = CanonicalItemKind.Application,
        current = PreviewPosition.InAppPair(PreviewLabel.Named(pairTitle)),
        reason = PreserveReason.NON_TARGET,
    )

    private fun preservedFallback(kind: CanonicalItemKind, reason: PreserveReason) = PreservedChange(
        item = ItemId("widget.1"),
        label = PreviewLabel.KindFallback(kind),
        identity = PreviewPlacementIdentity.Dock(2),
        kind = kind,
        current = dock(2),
        reason = reason,
    )

    private fun itemWarning(label: String, code: WarningCode) = ItemWarningChange(
        item = ItemId(label),
        label = PreviewLabel.Named(label),
        identity = PreviewPlacementIdentity.Dock(2),
        kind = CanonicalItemKind.Application,
        current = dock(2),
        code = code,
    )

    /** Bands the fixture cell into the 3x3 presentation the projector derives. */
    private fun workspaceFromCell(cell: Grid): PreviewPosition.Workspace = PreviewPosition.Workspace(
        pageDisplayOrdinal = 1,
        isNewPage = false,
        rowBand = when {
            cell.y < 2 -> RowBand.TOP
            cell.y < 4 -> RowBand.CENTER
            else -> RowBand.BOTTOM
        },
        columnBand = when {
            cell.x < 2 -> ColumnBand.LEFT
            cell.x < 4 -> ColumnBand.CENTER
            else -> ColumnBand.RIGHT
        },
        rowOrdinal = cell.y + 1,
    )

    private object TestWording : OrganizationPreviewWording {
        override val groupMoved = "Move (%1\$d)"
        override val groupNewFolders = "New folders (%1\$d)"
        override val groupNewPages = "New pages (%1\$d)"
        override val groupPreserved = "Preserve (%1\$d)"
        override val groupWarnings = "Warnings (%1\$d)"
        override val moveRow = "%1\$s → %2\$s (%3\$s)"
        override val sameBandMoveRow = "%1\$s: position adjusted within %2\$s%3\$s"
        override val rowOrdinalNote = " (from row %1\$d to row %2\$d)"
        override val itemRow = "%1\$s: %2\$s"
        override val itemDescriptor = "“%1\$s” (%2\$s) — %3\$s"
        override val itemDescriptorWithoutKind = "“%1\$s” — %2\$s"
        override val itemNameWithKind = "“%1\$s” (%2\$s)"
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
        override val preservedReasonStrategyPreserved = "kept by the selected strategy"
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
        override val folderPositionExisting = "folder “%1\$s”, position %2\$d"
        override val folderPositionPlanned = "new folder “%1\$s”, position %2\$d"
        override val appPairPosition = "app pair “%1\$s”"
        override val unidentifiedPosition = "unsupported placement %1\$d"
        override val positionWithSupplement = "%1\$s (%2\$s)"
        override val supplementCell = "row %1\$d, column %2\$d"
        override val supplementStageTop = "upper half"
        override val supplementStageBottom = "lower half"
        override val kindApplication = "App"
        override val kindDeepShortcut = "Shortcut"
        override val kindShortcutLegacy = "Legacy shortcut"
        override val kindFolder = "Folder"
        override val kindAppWidget = "Widget"
        override val kindCustomAppWidget = "Custom widget"
        override val kindAppPair = "App pair"
        override val kindUnknown = "Item"
        override val newFolderRow = "Create new folder \u201c%1\$s\u201d at %2\$s (members: %3\$s)"
        override val newPageRow = "Add a new page at position %1\$d"
    }
}
