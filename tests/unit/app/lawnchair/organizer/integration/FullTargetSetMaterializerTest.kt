package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.rules.FullOrganizationTargetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullTargetSetMaterializerTest {
    @Test
    fun partitionPreservesLockedUnavailableAndDockWhileMovingEligibleWorkspaceApp() {
        val items = listOf(
            item("movable"),
            item("locked", locked = true),
            item("quiet", availability = Availability.QUIET),
            item("dock", placement = CapturedPlacement.Dock(0)),
        )

        val result = FullTargetSetMaterializer().materialize(items, FullOrganizationTargetPolicy("full-target-v1"))
        assertTrue(result is TargetMaterializationResult.Ready)
        val targets = (result as TargetMaterializationResult.Ready).value.targets
        assertTrue(targets.additions.isEmpty())
        assertEquals(items.map { it.id }.toSet(), targets.existing.map { it.item }.toSet())
        assertEquals(ExistingRole.Movable, targets.existing.single { it.item == ItemId("movable") }.role)
        assertEquals(ExistingRole.Preserved, targets.existing.single { it.item == ItemId("locked") }.role)
        assertEquals(ExistingRole.Preserved, targets.existing.single { it.item == ItemId("quiet") }.role)
        assertEquals(ExistingRole.Preserved, targets.existing.single { it.item == ItemId("dock") }.role)
    }

    private fun item(
        id: String,
        locked: Boolean = false,
        availability: Availability = Availability.AVAILABLE,
        placement: CapturedPlacement = CapturedPlacement.Workspace(PageRef(PageId("0")), GridCell(0, 0), GridSpan(1, 1)),
    ) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), ProfileId("0")),
        placement = placement,
        locked = locked,
        availability = availability,
    )
}
