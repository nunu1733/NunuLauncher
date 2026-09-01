package app.lawnchair.organizer.application.adapter

import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import com.android.launcher3.LauncherSettings.Favorites
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #185 / ADR-0010: the A5 acceptance gate. An intended state containing a
 * desktop row inside an authoritative reservation is writable only under a
 * tolerant overlap policy; any other intended state is unaffected by the policy.
 */
class OverlapAcceptanceGateTest {

    private val reservation = ReservedWorkspaceRegion(PageRef(PageId("0")), GridCell(0, 0), GridSpan(5, 1))

    @Test
    fun intendedRowInsideReservationRequiresTolerance() {
        val rows = listOf(desktopRow(rowId = 115, cell = GridCell(2, 0)))

        assertFalse(overlapAcceptanceHolds(rows, listOf(reservation), overlapTolerated = false))
        assertTrue(overlapAcceptanceHolds(rows, listOf(reservation), overlapTolerated = true))
    }

    @Test
    fun intendedRowsOutsideReservationIgnorePolicy() {
        val rows = listOf(
            desktopRow(rowId = 2, cell = GridCell(0, 3)),
            desktopRow(rowId = 10, cell = GridCell(4, 4)),
        )

        assertTrue(overlapAcceptanceHolds(rows, listOf(reservation), overlapTolerated = false))
        assertTrue(overlapAcceptanceHolds(rows, listOf(reservation), overlapTolerated = true))
    }

    @Test
    fun emptyReservationsAndNonDesktopRowsIgnorePolicy() {
        val desktop = listOf(desktopRow(rowId = 115, cell = GridCell(2, 0)))
        val hotseat = listOf(desktopRow(rowId = 30, container = Favorites.CONTAINER_HOTSEAT, screen = "0", cell = null))
        val folderChild = listOf(desktopRow(rowId = 40, container = 40, screen = null, cell = null))

        assertTrue(overlapAcceptanceHolds(desktop, emptyList(), overlapTolerated = false))
        assertTrue(overlapAcceptanceHolds(hotseat, listOf(reservation), overlapTolerated = false))
        assertTrue(overlapAcceptanceHolds(folderChild, listOf(reservation), overlapTolerated = false))
    }

    private fun desktopRow(
        rowId: Long,
        cell: GridCell?,
        container: Int = Favorites.CONTAINER_DESKTOP,
        screen: String? = "0",
    ) = PersistentRow(
        rowId = rowId,
        itemId = ItemId(rowId.toString()),
        profileId = ProfileId("0"),
        containerCode = ContainerCode(container),
        screenId = screen?.let(::PageId),
        cellX = cell?.x,
        cellY = cell?.y,
        spanX = 1,
        spanY = 1,
        rank = 0,
        itemType = KindCode(Favorites.ITEM_TYPE_APPLICATION),
        appWidgetId = null,
        appWidgetProvider = null,
        iconBytes = null,
        title = null,
        intent = "#Intent;component=com.example/.Main;end",
        restored = 0,
        options = 0,
        appWidgetSource = -1,
        modified = 0L,
        organizerLockState = OrganizerLockState.UNLOCKED,
        rawCell = cell,
        rawSpan = GridSpan(1, 1),
    )
}
