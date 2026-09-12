package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.AddChange
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ColumnBand
import app.lawnchair.organizer.application.public.ItemWarningChange
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.NewFolderChange
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.PreviewFolderRef
import app.lawnchair.organizer.application.public.PreviewLabel
import app.lawnchair.organizer.application.public.PreviewPosition
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RowBand
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #228 spec AC-5: Add rows — one per selected candidate regardless of
 * destination (top-level or inside a generated folder), counted separately
 * from moves, with candidate-keyed fallback warnings excluded from the
 * captured-placement warning contract.
 */
class PlanPreviewProjectorAddTest {

    private val profile = ProfileId("personal")

    @Test
    fun everyCandidateInsertProjectsExactlyOneAddRow() {
        val top = candidate("c-top", "Top App", PlacementState.Workspace(page(0), GridCell(1, 1), GridSpan(1, 1)))
        val folderMember = candidate(
            "c-folder",
            "Folder App",
            PlacementState.FolderChild(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)), 0),
        )
        val source = captured("a", GridCell(0, 0))
        val folder = NewFolder(
            ordinal = NewFolderOrdinal(0),
            profile = profile,
            naming = FolderNaming.FromCategory(CategoryId("GAMES")),
            workspacePlacement = PlacementTarget.WorkspaceTarget(
                app.lawnchair.organizer.planning.PageRef(PageId("p0")),
                GridCell(2, 2),
                GridSpan(1, 1),
            ),
            members = listOf(ItemId("c-folder")),
        )
        val plan = plan(
            sourceItems = listOf(source),
            actions = listOf(
                ApplyAction.Preserve(source.ref, source),
                ApplyAction.Insert(top.ref, top),
                ApplyAction.Insert(folderMember.ref, folderMember),
                folderInsert(),
            ),
            newFolders = listOf(folder),
        )
        val planned = planned(
            placement("a", Disposition.Preserved(PreserveReason.NON_TARGET)),
            placement("c-top", Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)),
            placement("c-folder", Disposition.Moved(PlacementCode.FOLDER_MEMBER)),
        )

        val result = PlanPreviewProjector.project(plan, planned) as PlanPreviewProjector.Result.Ready

        val adds = result.details.changes.filterIsInstance<AddChange>()
        assertEquals(2, adds.size)
        val topRow = adds.single { it.item == ItemId("c-top") }
        val folderRow = adds.single { it.item == ItemId("c-folder") }
        assertEquals(PreviewLabel.Named("Top App"), topRow.label)
        assertEquals(CanonicalItemKind.Application, topRow.kind)
        assertEquals(
            PreviewPosition.Workspace(1, false, RowBand.TOP, ColumnBand.LEFT, 2, 2),
            topRow.destination,
        )
        assertEquals(
            PreviewPosition.InFolder(PreviewFolderRef.Planned(NewFolderOrdinal(0), PreviewLabel.Named("Games")), 1),
            folderRow.destination,
        )
        assertEquals(2, result.details.counts.addedCount)

        // The generated folder keeps its structural row; its member label
        // resolves through the candidate's own intended state.
        val folderChange = result.details.changes.filterIsInstance<NewFolderChange>().single()
        assertEquals(listOf(PreviewLabel.Named("Folder App")), folderChange.memberLabels)
    }

    @Test
    fun candidateFallbackWarningsStayOutOfCountsAndRows() {
        val top = candidate("c-top", "Top App", PlacementState.Workspace(page(0), GridCell(1, 1), GridSpan(1, 1)))
        val source = captured("a", GridCell(0, 0))
        val plan = plan(
            sourceItems = listOf(source),
            actions = listOf(
                ApplyAction.Preserve(source.ref, source),
                ApplyAction.Insert(top.ref, top),
            ),
        )
        val planned = planned(
            placement("a", Disposition.Preserved(PreserveReason.NON_TARGET)),
            placement("c-top", Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)),
            warnings = listOf(
                Warning(WarningCode.FALLBACK_CATEGORY, listOf(DiagnosticParam.ItemParam(ItemId("c-top")))),
                Warning(WarningCode.FALLBACK_CATEGORY, listOf(DiagnosticParam.ItemParam(ItemId("a")))),
            ),
        )

        val result = PlanPreviewProjector.project(plan, planned) as PlanPreviewProjector.Result.Ready

        // Only the captured item's warning survives in counts and rows.
        assertEquals(mapOf(WarningCode.FALLBACK_CATEGORY to 1), result.details.counts.warningCounts)
        assertEquals(1, result.details.changes.filterIsInstance<ItemWarningChange>().size)
        assertEquals(ItemId("a"), result.details.changes.filterIsInstance<ItemWarningChange>().single().item)
    }

    @Test
    fun plansWithoutCandidateInsertsProjectNoAddRows() {
        val source = captured("a", GridCell(0, 0))
        val plan = plan(
            sourceItems = listOf(source),
            actions = listOf(ApplyAction.Preserve(source.ref, source)),
        )

        val result = PlanPreviewProjector.project(plan, planned(placement("a", Disposition.Preserved(PreserveReason.NON_TARGET)))) as PlanPreviewProjector.Result.Ready

        assertTrue(result.details.changes.filterIsInstance<AddChange>().isEmpty())
        assertEquals(0, result.details.counts.addedCount)
    }

    private fun page(order: Int) = ApplicationPageRef.PersistentPage(PageId("p$order"))

    private fun captured(id: String, cell: GridCell): CanonicalItemState = CanonicalFixtures.appItem(
        itemId = id,
        profile = "personal",
        cell = cell,
        target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), profile),
    )

    private fun candidate(id: String, title: String, placement: PlacementState): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PlannedCandidate(ItemId(id)),
        kind = CanonicalItemKind.Application,
        targetKey = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), profile),
        profile = profile,
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = app.lawnchair.organizer.application.public.ItemAvailability.AVAILABLE,
        placement = placement,
        title = OptionalText.Present(title),
        intent = OptionalText.Present("#Intent;"),
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.Plain,
    )

    private fun folderInsert(): ApplyAction.Insert {
        val state = CanonicalItemState(
            ref = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)),
            kind = CanonicalItemKind.Folder,
            targetKey = TargetKey.FolderKey(app.lawnchair.organizer.planning.FolderId("planned-folder-0")),
            profile = profile,
            profileAvailability = ProfileAvailability.AVAILABLE,
            itemAvailability = app.lawnchair.organizer.application.public.ItemAvailability.AVAILABLE,
            placement = PlacementState.Workspace(page(0), GridCell(2, 2), GridSpan(1, 1)),
            title = OptionalText.Present("Games"),
            intent = OptionalText.Absent,
            icon = OptionalBytes.Absent,
            widget = WidgetState.NoWidget,
            modified = ModifiedAtMillis(0),
            lockState = OrganizerLockState.UNLOCKED,
            structure = StructureState.FolderMembers(emptyList()),
        )
        return ApplyAction.Insert(state.ref, state)
    }

    private fun placement(id: String, disposition: Disposition) = PlannedPlacement(
        ItemId(id),
        disposition,
        PlacementTarget.WorkspaceTarget(app.lawnchair.organizer.planning.PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
    )

    private fun planned(vararg placements: PlannedPlacement, warnings: List<Warning> = emptyList()) = Planned(
        placements = placements.toList(),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = warnings,
    )

    private fun plan(
        sourceItems: List<CanonicalItemState>,
        actions: List<ApplyAction>,
        newFolders: List<NewFolder> = emptyList(),
    ): ValidatedLayoutPlan {
        val sourceState = LayoutState(
            pages = listOf(PageState(page(0), PageOrder(0))),
            profiles = listOf(ProfileState(profile, ProfileAvailability.AVAILABLE)),
            deviceCapabilities = CanonicalFixtures.deviceCapabilities(columns = 4, rows = 4),
            items = sourceItems,
        )
        return ValidatedLayoutPlan(
            sourceRevision = RevisionId("revision-1"),
            sourceState = sourceState,
            intendedState = sourceState,
            actions = actions,
            newPages = emptyList(),
            newFolders = newFolders,
            ruleVersion = RuleVersion("v2"),
            taxonomyVersion = TaxonomyVersion("v1"),
        )
    }
}
