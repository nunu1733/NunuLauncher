package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.adapter.ContextResourceCodec
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryWriteSetTest {
    @Test
    fun accountsForEveryReviewedAndTargetRow() {
        val unchanged = row(1, "one")
        val changedBefore = row(2, "two")
        val changedTarget = row(2, "two", rank = 7)
        val reviewedOnly = row(3, "three")
        val targetOnly = row(4, "four")
        val result = RecoveryWriteSetMaterializer.materialize(
            target = manifest(unchanged, changedTarget, targetOnly),
            reviewedCurrent = manifest(unchanged, changedBefore, reviewedOnly),
        )

        assertEquals(4, result.actions.filterNot { it is RecoveryAction.PreserveResource }.size)
        assertTrue(result.actions.any { it is RecoveryAction.PreserveRow && it.expected.rowId == 1L })
        assertTrue(result.actions.any { it is RecoveryAction.UpdateRow && it.expected.rowId == 2L })
        assertTrue(result.actions.any { it is RecoveryAction.DeleteRow && it.expected.rowId == 3L })
        assertTrue(result.actions.any { it is RecoveryAction.InsertRow && it.intended.rowId == 4L })
    }

    @Test
    fun digestIsDeterministicAndIncludesChangedIntent() {
        val reviewed = manifest(row(1, "one"))
        val first = RecoveryWriteSetMaterializer.materialize(manifest(row(1, "one", rank = 1)), reviewed)
        val same = RecoveryWriteSetMaterializer.materialize(manifest(row(1, "one", rank = 1)), reviewed)
        val changed = RecoveryWriteSetMaterializer.materialize(manifest(row(1, "one", rank = 2)), reviewed)

        assertTrue(first.digest().contentEquals(same.digest()))
        assertFalse(first.digest().contentEquals(changed.digest()))
    }

    @Test
    fun workspaceReservationRecoveryAllowsRowDerivedPageChangesButRejectsReservationChanges() {
        val reservation = ReservedWorkspaceRegion(PageRef(PageId("0")), GridCell(0, 0), GridSpan(4, 1))
        val target = workspaceManifest(
            pages = listOf(PageId("0"), PageId("1"), PageId("3")),
            reservations = listOf(reservation),
        )
        val reviewed = workspaceManifest(
            pages = listOf(PageId("0"), PageId("1")),
            reservations = listOf(reservation),
        )

        val result = RecoveryWriteSetMaterializer.materialize(target, reviewed)
        assertEquals(
            reviewed.resources,
            result.actions.filterIsInstance<RecoveryAction.PreserveResource>().map { it.expected },
        )

        val disabled = workspaceManifest(
            pages = listOf(PageId("0"), PageId("1")),
            reservations = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryWriteSetMaterializer.materialize(target, disabled)
        }
        val changedSpan = workspaceManifest(
            pages = listOf(PageId("0"), PageId("1")),
            reservations = listOf(reservation.copy(span = GridSpan(3, 1))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryWriteSetMaterializer.materialize(target, changedSpan)
        }
    }

    @Test
    fun contextualResourceMismatchIsRejectedInsteadOfMaterializedAsMutation() {
        val target = manifest(row(1, "one"))
        val changed = target.copy(
            resources = target.resources.map { resource ->
                resource.copy(payload = resource.payload.copyOf().also { it[0] = 0 })
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryWriteSetMaterializer.materialize(target, changed)
        }
    }

    private fun workspaceManifest(
        pages: List<PageId>,
        reservations: List<ReservedWorkspaceRegion>,
    ): PersistenceManifest = PersistenceManifest(
        formatVersion = 1,
        schemaVersion = 33,
        rowCount = 0,
        rows = emptyList(),
        resources = ContextResourceCodec.encode(
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            capabilities = DeviceCapabilities(4, 5, 4, 4, 4, DeviceOrientation.PORTRAIT),
            pages = pages,
            reservedWorkspaceRegions = reservations,
        ),
        modifiedAtMillis = 0,
    )

    private fun manifest(vararg rows: PersistentRow): PersistenceManifest = PersistenceManifest(
        formatVersion = 1,
        schemaVersion = 33,
        rowCount = rows.size,
        rows = rows.toList(),
        resources = listOf(
            PersistentResource(
                kind = PersistentResourceKind.PROFILE_INVENTORY,
                profileId = ProfileId("personal"),
                order = 0,
                payload = byteArrayOf(1),
            ),
        ),
        modifiedAtMillis = 0,
    )

    private fun row(id: Long, item: String, rank: Int = 0): PersistentRow = PersistentRow(
        rowId = id,
        itemId = ItemId(item),
        profileId = ProfileId("personal"),
        containerCode = ContainerCode(0),
        screenId = null,
        cellX = 0,
        cellY = 0,
        spanX = 1,
        spanY = 1,
        rank = rank,
        itemType = KindCode(0),
        appWidgetId = null,
        appWidgetProvider = null,
        iconBytes = null,
        title = item,
        intent = null,
        restored = null,
        options = null,
        appWidgetSource = null,
        modified = 0,
        organizerLockState = OrganizerLockState.UNLOCKED,
        rawCell = null,
        rawSpan = null,
    )
}
