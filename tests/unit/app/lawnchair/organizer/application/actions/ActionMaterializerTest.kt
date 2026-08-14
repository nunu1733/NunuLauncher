package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PreserveReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #14 Stage B step 1: ActionMaterializer.validateOrdinals covers every
 * malformed/duplicate/out-of-order/missing case the apply protocol must turn
 * into `Rejected(INVALID_PLAN)`.
 */
class ActionMaterializerTest {

    @Test
    fun validOrdinalsAreAccepted() {
        val planned = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = ItemId("a"),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(
                        page = app.lawnchair.organizer.planning.NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                ),
            ),
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(0))),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = app.lawnchair.organizer.planning.ProfileId("personal"),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = app.lawnchair.organizer.planning.NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(1, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("a")),
                ),
            ),
            categories = emptyList(),
            warnings = emptyList(),
        )
        val result = ActionMaterializer.validateOrdinals(planned)
        assertEquals(ActionMaterializer.OrdinalValidation.Ok, result)
    }

    @Test
    fun duplicatePageOrdinalIsRejected() {
        val planned = basePlanned().copy(
            newPages = listOf(
                NewPage(NewPageOrdinal(0), PageOrder(0)),
                NewPage(NewPageOrdinal(0), PageOrder(1)),
            ),
        )
        assertEquals(
            ActionMaterializer.OrdinalValidation.DuplicatePageOrdinal,
            ActionMaterializer.validateOrdinals(planned),
        )
    }

    @Test
    fun outOfOrderPageOrdinalIsRejected() {
        val planned = basePlanned().copy(
            newPages = listOf(
                NewPage(NewPageOrdinal(1), PageOrder(0)),
                NewPage(NewPageOrdinal(0), PageOrder(1)),
            ),
        )
        assertEquals(
            ActionMaterializer.OrdinalValidation.OutOfOrderPageOrdinal,
            ActionMaterializer.validateOrdinals(planned),
        )
    }

    @Test
    fun duplicateFolderOrdinalIsRejected() {
        val planned = basePlanned().copy(
            newFolders = listOf(
                folder(ordinal = NewFolderOrdinal(0)),
                folder(ordinal = NewFolderOrdinal(0)),
            ),
        )
        assertEquals(
            ActionMaterializer.OrdinalValidation.DuplicateFolderOrdinal,
            ActionMaterializer.validateOrdinals(planned),
        )
    }

    @Test
    fun outOfOrderFolderOrdinalIsRejected() {
        val planned = basePlanned().copy(
            newFolders = listOf(
                folder(ordinal = NewFolderOrdinal(1)),
                folder(ordinal = NewFolderOrdinal(0)),
            ),
        )
        assertEquals(
            ActionMaterializer.OrdinalValidation.OutOfOrderFolderOrdinal,
            ActionMaterializer.validateOrdinals(planned),
        )
    }

    @Test
    fun folderMemberWithoutPlacementIsRejected() {
        val planned = basePlanned().copy(
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = app.lawnchair.organizer.planning.ProfileId("personal"),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = app.lawnchair.organizer.planning.NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("orphan")),
                ),
            ),
        )
        val result = ActionMaterializer.validateOrdinals(planned)
        assertTrue(
            "expected FolderMemberWithoutPlacement, got $result",
            result is ActionMaterializer.OrdinalValidation.FolderMemberWithoutPlacement,
        )
    }

    private fun basePlanned(): Planned = Planned(
        placements = listOf(
            PlannedPlacement(
                item = ItemId("a"),
                disposition = Disposition.Preserved(PreserveReason.ALREADY_CANONICAL),
                target = PlacementTarget.WorkspaceTarget(
                    page = app.lawnchair.organizer.planning.PageRef(app.lawnchair.organizer.planning.PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
            ),
        ),
        newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(0))),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

    private fun folder(ordinal: NewFolderOrdinal): NewFolder = NewFolder(
        ordinal = ordinal,
        profile = app.lawnchair.organizer.planning.ProfileId("personal"),
        workspacePlacement = PlacementTarget.WorkspaceTarget(
            page = app.lawnchair.organizer.planning.NewPageRef(NewPageOrdinal(0)),
            cell = GridCell(0, 0),
            span = GridSpan(1, 1),
        ),
        members = listOf(ItemId("a")),
    )
}
