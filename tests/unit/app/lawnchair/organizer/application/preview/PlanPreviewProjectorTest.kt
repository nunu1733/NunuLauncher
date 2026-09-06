package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.ItemWarningChange
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.MoveChange
import app.lawnchair.organizer.application.public.NewFolderChange
import app.lawnchair.organizer.application.public.NewPageChange
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreservedChange
import app.lawnchair.organizer.application.public.PreviewFolderRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.PreviewPlacementIdentity
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #194 pure projection contract: actions / before-after from the
 * validated plan, rationale / reasons / warnings from the semantic plan,
 * deterministic join, fixed position normalization, synthetic identities only.
 */
class PlanPreviewProjectorTest {

    @Test
    fun moveRowsCarryActionsBeforeAfterAndPlannedRationale() {
        val plan = plan(
            sourceItems = listOf(item("a", cell = GridCell(0, 0)), item("b", cell = GridCell(2, 2))),
            actions = listOf(
                updateAction(
                    item("a", cell = GridCell(0, 0)),
                    item("a", cell = GridCell(4, 4), page = pageRef("p1")),
                ),
                updateAction(
                    item("b", cell = GridCell(2, 2)),
                    item("b", cell = GridCell(5, 5), page = pageRef("p1")),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("a"), moved("b"))) as PlanPreviewProjector.Result.Ready

        val moves = result.details.changes.filterIsInstance<MoveChange>()
        assertEquals(2, moves.size)
        assertEquals(
            PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 1),
            moves.first { it.item.value == "a" }.source,
        )
        assertEquals(
            PreviewPosition.Workspace(2, false, RowBand.BOTTOM, ColumnBand.RIGHT, 5),
            moves.first { it.item.value == "a" }.destination,
        )
        assertEquals(PlacementCode.SINGLE_PLACEMENT, moves.first { it.item.value == "a" }.rationale)
        assertEquals(
            PreviewPosition.Workspace(2, false, RowBand.BOTTOM, ColumnBand.RIGHT, 6),
            moves.first { it.item.value == "b" }.destination,
        )
        assertFalse(moves.any { it.sameBandAdjustment })
        assertEquals(2, result.details.counts.movedCount)
    }

    @Test
    fun samePageSameBandMoveIsFlaggedAsBandAdjustment() {
        val plan = plan(
            sourceItems = listOf(item("a", cell = GridCell(0, 0))),
            actions = listOf(
                updateAction(item("a", cell = GridCell(0, 0)), item("a", cell = GridCell(1, 1))),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("a"))) as PlanPreviewProjector.Result.Ready

        val move = result.details.changes.single() as MoveChange
        assertTrue(move.sameBandAdjustment)
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 1), move.source)
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 2), move.destination)
    }

    @Test
    fun strategyConsequenceCountsSplitCrossPageMovesAndStrategyPreservedRows() {
        // Spec 182 child 8 data path: header counts derive from the same rows
        // the change list renders — a move across pages (page p0 → p1), a
        // same-page move, and a STRATEGY_PRESERVED row.
        val plan = plan(
            sourceItems = listOf(
                item("cross", cell = GridCell(0, 0)),
                item("same", cell = GridCell(2, 2)),
                item("kept", cell = GridCell(4, 4)),
            ),
            actions = listOf(
                updateAction(
                    item("cross", cell = GridCell(0, 0)),
                    item("cross", cell = GridCell(4, 4), page = pageRef("p1")),
                ),
                updateAction(item("same", cell = GridCell(2, 2)), item("same", cell = GridCell(3, 3))),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("kept")), item("kept", cell = GridCell(4, 4))),
            ),
        )
        val result = PlanPreviewProjector.project(
            plan,
            planned(
                moved("cross"),
                moved("same"),
                preserved("kept", PreserveReason.STRATEGY_PRESERVED),
            ),
        ) as PlanPreviewProjector.Result.Ready

        assertEquals(2, result.details.counts.movedCount)
        assertEquals(1, result.details.counts.crossPageMovedCount)
        assertEquals(1, result.details.counts.preservedByStrategyCount)
    }

    @Test
    fun preserveRowsCarryPlannedReasonIdentityKindAndDerivedCurrent() {
        val widget = CanonicalFixtures.widgetItem(itemId = "widget.1")
        val plan = plan(
            sourceItems = listOf(widget),
            actions = listOf(ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("widget.1")), widget)),
        )

        val result = PlanPreviewProjector.project(plan, planned(preserved("widget.1", PreserveReason.WIDGET))) as PlanPreviewProjector.Result.Ready

        val row = result.details.changes.single() as PreservedChange
        assertEquals(ItemId("widget.1"), row.item)
        assertEquals(PreviewLabel.KindFallback(CanonicalItemKind.AppWidget), row.label)
        assertEquals(PreserveReason.WIDGET, row.reason)
        // Issue #208: identity anchors at the exact source cell; the current
        // position is the identity's presentation, and the kind is restored.
        assertEquals(PreviewPlacementIdentity.Workspace(1, false, 0, 0), row.identity)
        assertEquals(CanonicalItemKind.AppWidget, row.kind)
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 1), row.current)
        assertEquals(1, result.details.counts.preservedCount)
    }

    @Test
    fun updatesWithUnchangedPlacementProduceNoRow() {
        val plan = plan(
            sourceItems = listOf(item("a", cell = GridCell(0, 0))),
            actions = listOf(
                updateAction(item("a", cell = GridCell(0, 0)), item("a", cell = GridCell(0, 0))),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("a"))) as PlanPreviewProjector.Result.Ready

        assertTrue(result.details.changes.isEmpty())
        assertEquals(0, result.details.counts.movedCount)
    }

    @Test
    fun newFolderRowsCarryPlacementAndMemberLabels() {
        val folderState = plannedFolderState(
            ordinal = 0,
            placement = PlacementState.Workspace(ApplicationPageRef.PersistentPage(PageId("p0")), GridCell(2, 0), GridSpan(1, 1)),
        )
        val plan = plan(
            sourceItems = listOf(item("a"), item("b")),
            actions = listOf(ApplyAction.Insert(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folderState)),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    naming = FolderNaming.FromCategory(CategoryId("COMMUNICATION")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        PageRef(PageId("p0")),
                        GridCell(2, 0),
                        GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a"), ItemId("b")),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned()) as PlanPreviewProjector.Result.Ready

        val row = result.details.changes.single() as NewFolderChange
        assertEquals(NewFolderOrdinal(0), row.ordinal)
        assertEquals(PreviewLabel.Named("Folder"), row.name)
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.CENTER, 1), row.placement)
        assertEquals(listOf(PreviewLabel.Named("Ta"), PreviewLabel.Named("Tb")), row.memberLabels)
        assertEquals(1, result.details.counts.newFolderCount)
    }

    @Test
    fun newFolderRowCarriesTheSameResolvedTitleApplyPersists() {
        // Issue #201 (FN-AC-06): the row name is the insert's intended title —
        // the exact value the apply writer persists — never re-derived.
        val folderState = plannedFolderState(
            ordinal = 0,
            placement = PlacementState.Workspace(ApplicationPageRef.PersistentPage(PageId("p0")), GridCell(2, 0), GridSpan(1, 1)),
            title = OptionalText.Present("通信"),
        )
        val plan = plan(
            sourceItems = listOf(item("a"), item("b")),
            actions = listOf(ApplyAction.Insert(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folderState)),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    naming = FolderNaming.FromCategory(CategoryId("COMMUNICATION")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        PageRef(PageId("p0")),
                        GridCell(2, 0),
                        GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a"), ItemId("b")),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned()) as PlanPreviewProjector.Result.Ready

        assertEquals(PreviewLabel.Named("通信"), (result.details.changes.single() as NewFolderChange).name)
    }

    @Test
    fun newFolderInsertWithoutResolvedTitleFailsClosed() {
        val folderState = plannedFolderState(
            ordinal = 0,
            placement = PlacementState.Workspace(ApplicationPageRef.PersistentPage(PageId("p0")), GridCell(2, 0), GridSpan(1, 1)),
            title = OptionalText.Absent,
        )
        val plan = plan(
            sourceItems = listOf(item("a"), item("b")),
            actions = listOf(ApplyAction.Insert(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folderState)),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    naming = FolderNaming.FromCategory(CategoryId("COMMUNICATION")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        PageRef(PageId("p0")),
                        GridCell(2, 0),
                        GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a"), ItemId("b")),
                ),
            ),
        )

        assertEquals(PlanPreviewProjector.Result.Invalid, PlanPreviewProjector.project(plan, planned()))
    }

    @Test
    fun newFolderInsertWithBlankResolvedTitleFailsClosed() {
        val folderState = plannedFolderState(
            ordinal = 0,
            placement = PlacementState.Workspace(ApplicationPageRef.PersistentPage(PageId("p0")), GridCell(2, 0), GridSpan(1, 1)),
            title = OptionalText.Present("   "),
        )
        val plan = plan(
            sourceItems = listOf(item("a"), item("b")),
            actions = listOf(ApplyAction.Insert(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folderState)),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    naming = FolderNaming.FromCategory(CategoryId("COMMUNICATION")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        PageRef(PageId("p0")),
                        GridCell(2, 0),
                        GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a"), ItemId("b")),
                ),
            ),
        )

        assertEquals(PlanPreviewProjector.Result.Invalid, PlanPreviewProjector.project(plan, planned()))
    }

    @Test
    fun newPageRowsFollowCombinedPageOrderAndPlannedPageTargetsResolve() {
        val plan = plan(
            sourceItems = listOf(item("a", cell = GridCell(0, 0))),
            actions = listOf(
                updateAction(
                    item("a", cell = GridCell(0, 0)),
                    item("a", cell = GridCell(0, 0), page = ApplicationPageRef.PlannedPage(NewPageOrdinal(0))),
                ),
            ),
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(2))),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("a"))) as PlanPreviewProjector.Result.Ready

        val pageRow = result.details.changes.filterIsInstance<NewPageChange>().single()
        assertEquals(NewPageOrdinal(0), pageRow.ordinal)
        assertEquals(3, pageRow.displayPosition)
        val move = result.details.changes.filterIsInstance<MoveChange>().single()
        assertEquals(PreviewPosition.Workspace(3, true, RowBand.TOP, ColumnBand.LEFT, 1), move.destination)
        assertEquals(1, result.details.counts.newPageCount)
    }

    @Test
    fun plannedPageBetweenPersistentPagesGetsItsInBetweenDisplayPosition() {
        // persistent orders 0 and 10; planned order 5 sits between them.
        val plan = plan(
            sourceItems = listOf(item("a", cell = GridCell(0, 0))),
            actions = listOf(
                updateAction(
                    item("a", cell = GridCell(0, 0)),
                    item("a", cell = GridCell(0, 0), page = ApplicationPageRef.PlannedPage(NewPageOrdinal(0))),
                ),
                updateAction(
                    item("b", cell = GridCell(1, 0)),
                    item("b", cell = GridCell(1, 0), page = pageRef("p1")),
                ),
            ),
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(5))),
            pageOrders = listOf(0, 10),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("a"), moved("b"))) as PlanPreviewProjector.Result.Ready

        val move = result.details.changes.filterIsInstance<MoveChange>().first { it.item.value == "a" }
        assertEquals(PreviewPosition.Workspace(2, true, RowBand.TOP, ColumnBand.LEFT, 1), move.destination)
        val persistedMove = result.details.changes.filterIsInstance<MoveChange>().first { it.item.value == "b" }
        assertEquals(PreviewPosition.Workspace(3, false, RowBand.TOP, ColumnBand.LEFT, 1), persistedMove.destination)
        val pageRow = result.details.changes.filterIsInstance<NewPageChange>().single()
        assertEquals(2, pageRow.displayPosition)
    }

    @Test
    fun singleItemWarningsBecomeRowsAndOtherWarningsStayHeaderOnly() {
        val plan = plan(
            sourceItems = listOf(item("a"), item("b")),
            actions = listOf(
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("a")), item("a")),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("b")), item("b")),
            ),
        )
        val planned = Planned(
            placements = listOf(preserved("a"), preserved("b")),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = listOf(
                Warning(WarningCode.LEGACY_SHORTCUT_REVIEW, listOf(DiagnosticParam.ItemParam(ItemId("a")))),
                Warning(WarningCode.FALLBACK_CATEGORY, emptyList()),
                Warning(
                    WarningCode.UNAVAILABLE_PRESERVED,
                    listOf(DiagnosticParam.ItemParam(ItemId("a")), DiagnosticParam.ItemParam(ItemId("b"))),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned) as PlanPreviewProjector.Result.Ready

        val warningRows = result.details.changes.filterIsInstance<ItemWarningChange>()
        assertEquals(1, warningRows.size)
        assertEquals(ItemId("a"), warningRows.single().item)
        assertEquals(WarningCode.LEGACY_SHORTCUT_REVIEW, warningRows.single().code)
        // Issue #208: warning rows carry the same identity / kind / current
        // presentation contract as preserve rows.
        assertEquals(PreviewPlacementIdentity.Workspace(1, false, 0, 0), warningRows.single().identity)
        assertEquals(CanonicalItemKind.Application, warningRows.single().kind)
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 1), warningRows.single().current)
        assertEquals(
            mapOf(
                WarningCode.LEGACY_SHORTCUT_REVIEW to 1,
                WarningCode.FALLBACK_CATEGORY to 1,
                WarningCode.UNAVAILABLE_PRESERVED to 1,
            ),
            result.details.counts.warningCounts,
        )
    }

    @Test
    fun moveIntoPlannedFolderRendersTheResolvedFolderName() {
        // Issue #201 (review Major 1): a move row whose destination is a
        // planned folder must carry the same resolved title as the folder's
        // own change row — the planner ordinal never reaches the UI.
        val folderState = plannedFolderState(
            ordinal = 0,
            placement = PlacementState.Workspace(ApplicationPageRef.PersistentPage(PageId("p0")), GridCell(2, 0), GridSpan(1, 1)),
            title = OptionalText.Present("ソーシャル"),
        )
        val intendedMember = item("a").copy(
            placement = PlacementState.FolderChild(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), 0),
        )
        val plan = plan(
            sourceItems = listOf(item("a")),
            actions = listOf(
                updateAction(item("a", cell = GridCell(0, 0)), intendedMember),
                ApplyAction.Insert(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), folderState),
            ),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    naming = FolderNaming.FromCategory(CategoryId("SOCIAL")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        PageRef(PageId("p0")),
                        GridCell(2, 0),
                        GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a")),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(
            plan,
            planned(
                PlannedPlacement(
                    item = ItemId("a"),
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = app.lawnchair.organizer.planning.PlacementTarget.FolderMember(
                        app.lawnchair.organizer.planning.NewFolderRef(NewFolderOrdinal(0)),
                        0,
                    ),
                ),
            ),
        ) as PlanPreviewProjector.Result.Ready

        val move = result.details.changes.filterIsInstance<MoveChange>().single()
        assertEquals(
            PreviewPosition.InFolder(
                PreviewFolderRef.Planned(NewFolderOrdinal(0), PreviewLabel.Named("ソーシャル")),
                1,
            ),
            move.destination,
        )
        val folderRow = result.details.changes.filterIsInstance<NewFolderChange>().single()
        assertEquals(PreviewLabel.Named("ソーシャル"), folderRow.name)
    }

    @Test
    fun dockSourceAndFolderDestinationsResolveByReference() {
        val dockItem = dockItem("dock.1", rank = 2)
        val folder = folderItem("folder.1", "Work", cell = GridCell(3, 0))
        val plan = plan(
            sourceItems = listOf(dockItem, item("a"), folder),
            actions = listOf(
                updateAction(
                    item("a", cell = GridCell(0, 0)),
                    item("a").copy(
                        placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("folder.1")), 0),
                    ),
                ),
                updateAction(
                    dockItem,
                    item("dock.1", cell = GridCell(0, 0)),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("a"), moved("dock.1"))) as PlanPreviewProjector.Result.Ready

        val moves = result.details.changes.filterIsInstance<MoveChange>()
        assertEquals(2, moves.size)
        assertEquals(
            PreviewPosition.InFolder(PreviewFolderRef.Existing(PreviewLabel.Named("Work")), 1),
            moves.first { it.item.value == "a" }.destination,
        )
        assertEquals(
            PreviewPosition.DockRank(2),
            moves.first { it.item.value == "dock.1" }.source,
        )
    }

    @Test
    fun projectedCountsMatchPlanningDispositionCountsForV1Fixtures() {
        val plan = plan(
            sourceItems = listOf(item("a"), item("b"), item("c")),
            actions = listOf(
                updateAction(item("a", cell = GridCell(0, 0)), item("a", cell = GridCell(0, 0), page = pageRef("p1"))),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("b")), item("b")),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("c")), item("c")),
            ),
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(2))),
        )
        val planned = planned(
            moved("a"),
            preserved("b"),
            preserved("c", PreserveReason.LOCKED),
        )

        val result = PlanPreviewProjector.project(plan, planned) as PlanPreviewProjector.Result.Ready

        assertEquals(
            planned.placements.count { it.disposition is Disposition.Moved },
            result.details.counts.movedCount,
        )
        assertEquals(
            planned.placements.count { it.disposition is Disposition.Preserved },
            result.details.counts.preservedCount,
        )
        assertEquals(0, result.details.counts.newFolderCount)
        assertEquals(1, result.details.counts.newPageCount)
    }

    @Test
    fun identicalInputsProduceIdenticalDetails() {
        val plan = plan(
            sourceItems = listOf(item("a"), item("b")),
            actions = listOf(
                updateAction(item("a", cell = GridCell(0, 0)), item("a", cell = GridCell(0, 0), page = pageRef("p1"))),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("b")), item("b")),
            ),
        )
        val planned = planned(moved("a"), preserved("b"))

        val first = PlanPreviewProjector.project(plan, planned)
        val second = PlanPreviewProjector.project(plan, planned)

        assertEquals(first, second)
    }

    @Test
    fun joinAndDispositionViolationsFailClosed() {
        val orphanPlan = plan(
            sourceItems = listOf(item("a")),
            actions = listOf(
                updateAction(item("a", cell = GridCell(0, 0)), item("a", cell = GridCell(0, 0), page = pageRef("p1"))),
            ),
        )
        assertTrue(PlanPreviewProjector.project(orphanPlan, planned()) is PlanPreviewProjector.Result.Invalid)

        val preserveWithMoved = plan(
            sourceItems = listOf(item("a")),
            actions = listOf(ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("a")), item("a"))),
        )
        assertTrue(
            PlanPreviewProjector.project(preserveWithMoved, planned(moved("a"))) is PlanPreviewProjector.Result.Invalid,
        )

        val missingFolder = plan(
            sourceItems = listOf(item("a")),
            actions = listOf(
                updateAction(
                    item("a", cell = GridCell(0, 0)),
                    item("a").copy(
                        placement = PlacementState.FolderChild(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(7)), 0),
                    ),
                ),
            ),
        )
        assertTrue(PlanPreviewProjector.project(missingFolder, planned(moved("a"))) is PlanPreviewProjector.Result.Invalid)
    }

    @Test
    fun bandNormalizationUsesStartCellAndClampsToThreeBands() {
        val plan = plan(
            sourceItems = listOf(item("span", cell = GridCell(1, 2), span = GridSpan(3, 2))),
            actions = listOf(
                updateAction(
                    item("span", cell = GridCell(1, 2), span = GridSpan(3, 2)),
                    item("span", cell = GridCell(4, 4), span = GridSpan(3, 2), page = pageRef("p1")),
                ),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned(moved("span"))) as PlanPreviewProjector.Result.Ready

        val move = result.details.changes.single() as MoveChange
        assertEquals(PreviewPosition.Workspace(1, false, RowBand.CENTER, ColumnBand.LEFT, 3), move.source)
        assertEquals(PreviewPosition.Workspace(2, false, RowBand.BOTTOM, ColumnBand.RIGHT, 5), move.destination)
    }

    // Issue #208 identity-invariant fixtures (F1-F5, F9, F10).

    @Test
    fun sameNamedItemsOnDifferentPagesGetDistinctIdentities() {
        // F1: same name, same kind, different pages, both preserved.
        val plan = plan(
            sourceItems = listOf(
                item("gmail.a", title = "Gmail", cell = GridCell(0, 0)),
                item("gmail.b", title = "Gmail", cell = GridCell(0, 0), page = pageRef("p1")),
            ),
            actions = listOf(
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("gmail.a")), item("gmail.a", title = "Gmail")),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("gmail.b")), item("gmail.b", title = "Gmail", page = pageRef("p1"))),
            ),
        )

        val result = PlanPreviewProjector.project(
            plan,
            planned(preserved("gmail.a", PreserveReason.NON_TARGET), preserved("gmail.b", PreserveReason.NON_TARGET)),
        ) as PlanPreviewProjector.Result.Ready

        val identities = result.details.changes.filterIsInstance<PreservedChange>().map { it.identity }
        assertEquals(
            listOf(
                PreviewPlacementIdentity.Workspace(1, false, 0, 0),
                PreviewPlacementIdentity.Workspace(2, false, 0, 0),
            ),
            identities,
        )
        assertEquals(2, identities.toSet().size)
    }

    @Test
    fun sameNamedIconsInsideOneBandGetDistinctCellAnchors() {
        // F2: same name, same kind, same page, same 3x3 band, different cells.
        val plan = plan(
            sourceItems = listOf(
                item("gmail.a", title = "Gmail", cell = GridCell(0, 0)),
                item("gmail.b", title = "Gmail", cell = GridCell(1, 0)),
            ),
            actions = listOf(
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("gmail.a")), item("gmail.a", title = "Gmail", cell = GridCell(0, 0))),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("gmail.b")), item("gmail.b", title = "Gmail", cell = GridCell(1, 0))),
            ),
        )

        val result = PlanPreviewProjector.project(
            plan,
            planned(preserved("gmail.a", PreserveReason.NON_TARGET), preserved("gmail.b", PreserveReason.NON_TARGET)),
        ) as PlanPreviewProjector.Result.Ready

        val rows = result.details.changes.filterIsInstance<PreservedChange>()
        // Distinct anchors, and both band to the same coarse region — the
        // descriptor supplement (row/column) is what keeps the rows apart.
        assertEquals(
            listOf(
                PreviewPlacementIdentity.Workspace(1, false, 0, 0),
                PreviewPlacementIdentity.Workspace(1, false, 1, 0),
            ),
            rows.map { it.identity },
        )
        assertEquals(rows[0].current, rows[1].current)
    }

    @Test
    fun sameNamedFoldersWithSameNamedChildrenResolveDistinctParentIdentities() {
        // F3/F4: two "Google" folders on different cells, each with a
        // same-named child at the same rank.
        val childA = item("gmail.a", title = "Gmail").copy(
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("folder.a")), 0),
        )
        val childB = item("gmail.b", title = "Gmail").copy(
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("folder.b")), 0),
        )
        val plan = plan(
            sourceItems = listOf(
                folderItem("folder.a", "Google", cell = GridCell(0, 0)),
                folderItem("folder.b", "Google", cell = GridCell(0, 3)),
                childA,
                childB,
            ),
            actions = listOf(
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("gmail.a")), childA),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("gmail.b")), childB),
            ),
        )

        val result = PlanPreviewProjector.project(
            plan,
            planned(preserved("gmail.a", PreserveReason.NON_TARGET), preserved("gmail.b", PreserveReason.NON_TARGET)),
        ) as PlanPreviewProjector.Result.Ready

        val rows = result.details.changes.filterIsInstance<PreservedChange>()
        assertEquals(
            listOf(
                PreviewPlacementIdentity.FolderChild(PreviewPlacementIdentity.Workspace(1, false, 0, 0), 0),
                PreviewPlacementIdentity.FolderChild(PreviewPlacementIdentity.Workspace(1, false, 0, 3), 0),
            ),
            rows.map { it.identity },
        )
        // Same folder title and rank — the parent cell is what distinguishes.
        assertEquals(rows[0].current, rows[1].current)
    }

    @Test
    fun moveAndPreserveIdentitiesStayDisjointForSameNamedItems() {
        // F5: same-named home icon moves while the folder copy is preserved;
        // no identity may appear in both buckets.
        val movedIcon = item("photos", title = "Photos", cell = GridCell(0, 0))
        val folderChild = item("photos.b", title = "Photos").copy(
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("folder.a")), 0),
        )
        val plan = plan(
            sourceItems = listOf(
                movedIcon,
                folderItem("folder.a", "Utilities", cell = GridCell(3, 3)),
                folderChild,
            ),
            actions = listOf(
                updateAction(movedIcon, movedIcon.copy(placement = withCell(movedIcon.placement, GridCell(4, 4)))),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("photos.b")), folderChild),
            ),
        )

        val result = PlanPreviewProjector.project(
            plan,
            planned(moved("photos"), preserved("photos.b", PreserveReason.NON_TARGET)),
        ) as PlanPreviewProjector.Result.Ready

        val moveIdentities = result.details.changes.filterIsInstance<MoveChange>().map { it.identity }
        val preservedIdentities = result.details.changes.filterIsInstance<PreservedChange>().map { it.identity }
        assertTrue(moveIdentities.toSet().intersect(preservedIdentities.toSet()).isEmpty())
    }

    /** Workspace placement with only the anchor cell changed. */
    private fun withCell(placement: PlacementState, cell: GridCell): PlacementState {
        val workspace = placement as PlacementState.Workspace
        return PlacementState.Workspace(workspace.page, cell, workspace.span)
    }

    @Test
    fun sameUnsupportedSourceItemSharesOneIdentityAcrossPreserveAndWarningRows() {
        // F9: the discriminator is keyed by source item, so the same item's
        // preserve row and warning row carry the identical identity.
        val unsupported = item("u.1").copy(placement = PlacementState.UnsupportedContainer(ContainerCode(7)))
        val plan = plan(
            sourceItems = listOf(unsupported),
            actions = listOf(ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("u.1")), unsupported)),
        )
        val plannedWithWarning = Planned(
            placements = listOf(preserved("u.1", PreserveReason.UNAVAILABLE_TARGET)),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = listOf(
                Warning(WarningCode.UNAVAILABLE_PRESERVED, listOf(DiagnosticParam.ItemParam(ItemId("u.1")))),
            ),
        )

        val result = PlanPreviewProjector.project(plan, plannedWithWarning) as PlanPreviewProjector.Result.Ready

        val preservedRow = result.details.changes.filterIsInstance<PreservedChange>().single()
        val warningRow = result.details.changes.filterIsInstance<ItemWarningChange>().single()
        assertEquals(PreviewPlacementIdentity.Unidentified(ContainerCode(7), 1), preservedRow.identity)
        assertEquals(preservedRow.identity, warningRow.identity)
        assertEquals(PreviewPosition.Unidentified(1), preservedRow.current)
        assertEquals(PreviewPosition.Unidentified(1), warningRow.current)
    }

    @Test
    fun sameCodeUnsupportedItemsGetDistinctProposalLocalDiscriminators() {
        // F10: two items inside identically-coded unsupported containers stay
        // distinct identities with deterministic discriminator ordinals.
        val first = item("u.1").copy(placement = PlacementState.UnsupportedContainer(ContainerCode(7)))
        val second = item("u.2").copy(placement = PlacementState.UnsupportedContainer(ContainerCode(7)))
        val plan = plan(
            sourceItems = listOf(first, second),
            actions = listOf(
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("u.1")), first),
                ApplyAction.Preserve(ApplicationItemRef.PersistentItem(ItemId("u.2")), second),
            ),
        )

        val result = PlanPreviewProjector.project(
            plan,
            planned(preserved("u.1", PreserveReason.UNAVAILABLE_TARGET), preserved("u.2", PreserveReason.UNAVAILABLE_TARGET)),
        ) as PlanPreviewProjector.Result.Ready

        assertEquals(
            listOf(
                PreviewPlacementIdentity.Unidentified(ContainerCode(7), 1),
                PreviewPlacementIdentity.Unidentified(ContainerCode(7), 2),
            ),
            result.details.changes.filterIsInstance<PreservedChange>().map { it.identity },
        )
    }

    // Fixture helpers (synthetic identities only).

    private fun pageRef(id: String) = ApplicationPageRef.PersistentPage(PageId(id))

    private fun item(
        id: String,
        title: String = "T$id",
        cell: GridCell = GridCell(0, 0),
        span: GridSpan = GridSpan(1, 1),
        page: ApplicationPageRef = ApplicationPageRef.PersistentPage(PageId("p0")),
    ): CanonicalItemState = CanonicalFixtures.appItem(
        itemId = id,
        title = OptionalText.Present(title),
        page = page,
        cell = cell,
        span = span,
    )

    private fun folderItem(
        id: String,
        title: String,
        cell: GridCell,
    ): CanonicalItemState = CanonicalFixtures.appItem(
        itemId = id,
        title = OptionalText.Present(title),
        cell = cell,
        kind = CanonicalItemKind.Folder,
        structure = StructureState.FolderMembers(emptyList()),
        target = TargetKey.FolderKey(FolderId(id)),
    )

    private fun dockItem(id: String, rank: Int): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId(id)),
        kind = CanonicalItemKind.Application,
        targetKey = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), ProfileId("personal")),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Dock(rank),
        title = OptionalText.Present("T$id"),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(1_000L),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.Plain,
    )

    private fun plannedFolderState(
        ordinal: Int,
        placement: PlacementState,
        title: OptionalText = OptionalText.Present("Folder"),
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(ordinal)),
        kind = CanonicalItemKind.Folder,
        targetKey = TargetKey.FolderKey(FolderId("planned-folder-$ordinal")),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = placement,
        title = title,
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.FolderMembers(emptyList()),
    )

    private fun updateAction(expected: CanonicalItemState, intended: CanonicalItemState): ApplyAction {
        val itemId = (expected.ref as ApplicationItemRef.PersistentItem).itemId
        return ApplyAction.Update(ApplicationItemRef.PersistentItem(itemId), expected, intended)
    }

    private fun moved(id: String): PlannedPlacement = PlannedPlacement(
        item = ItemId(id),
        disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
        target = PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
    )

    private fun preserved(
        id: String,
        reason: PreserveReason = PreserveReason.ALREADY_CANONICAL,
    ): PlannedPlacement = PlannedPlacement(
        item = ItemId(id),
        disposition = Disposition.Preserved(reason),
        target = PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
    )

    private fun planned(vararg placements: PlannedPlacement) = Planned(
        placements = placements.toList(),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

    private fun plan(
        sourceItems: List<CanonicalItemState>,
        actions: List<ApplyAction>,
        newPages: List<NewPage> = emptyList(),
        newFolders: List<NewFolder> = emptyList(),
        pageOrders: List<Int> = listOf(0, 1),
    ): ValidatedLayoutPlan {
        val sourceState = LayoutState(
            pages = pageOrders.mapIndexed { index, order ->
                PageState(ApplicationPageRef.PersistentPage(PageId("p$index")), PageOrder(order))
            },
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 6, rows = 6),
            items = sourceItems,
        )
        return ValidatedLayoutPlan(
            sourceRevision = RevisionId("revision-1"),
            sourceState = sourceState,
            intendedState = sourceState,
            actions = actions,
            newPages = newPages,
            newFolders = newFolders,
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("v1"),
        )
    }
}
