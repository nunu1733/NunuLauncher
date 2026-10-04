package app.lawnchair.organizer.integration

import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.AppPairRef
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderRef
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.KindCode
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

        val result = ready(items)
        assertTrue(result.targets.additions.isEmpty())
        assertEquals(items.map { it.id }.toSet(), result.targets.existing.map { it.item }.toSet())
        assertRole(result, "movable", ExistingRole.Movable)
        assertRole(result, "locked", ExistingRole.Preserved)
        assertRole(result, "quiet", ExistingRole.Preserved)
        assertRole(result, "dock", ExistingRole.Preserved)
    }

    @Test
    fun structuralWidgetAppPairAndLegacyItemsArePreservedWhileWorkspaceFolderAndShortcutMove() {
        val items = listOf(
            item("folder", kind = ItemKind.FOLDER),
            item("shortcut", kind = ItemKind.DEEP_SHORTCUT),
            item("folder-child", placement = CapturedPlacement.FolderMember(FolderRef(FolderId("folder")), 0)),
            item("pair-child", placement = CapturedPlacement.AppPairMember(AppPairRef(AppPairId("pair")))),
            item("widget", kind = ItemKind.APPWIDGET),
            item("pair", kind = ItemKind.APP_PAIR),
            item("legacy", kind = ItemKind.SHORTCUT_LEGACY),
        )

        val result = ready(items)
        assertRole(result, "folder", ExistingRole.Movable)
        assertRole(result, "shortcut", ExistingRole.Movable)
        listOf("folder-child", "pair-child", "widget", "pair", "legacy").forEach {
            assertRole(result, it, ExistingRole.Preserved)
        }
    }

    @Test
    fun unknownKindAndUnsupportedContainerRejectInsteadOfDroppingItems() {
        val policy = FullOrganizationTargetPolicy("full-target-v1")
        val unknown = FullTargetSetMaterializer().materialize(
            listOf(item("unknown", kind = ItemKind.Unknown(KindCode(99)))),
            policy,
        )
        val unsupported = FullTargetSetMaterializer().materialize(
            listOf(item("unsupported", placement = CapturedPlacement.UnsupportedContainer(ContainerCode(42)))),
            policy,
        )
        assertEquals(TargetMaterializationResult.Invalid, unknown)
        assertEquals(TargetMaterializationResult.Invalid, unsupported)
    }

    private fun ready(items: List<CapturedItem>): MaterializedTargetSet {
        val result = FullTargetSetMaterializer().materialize(items, FullOrganizationTargetPolicy("full-target-v1"))
        assertTrue(result is TargetMaterializationResult.Ready)
        return (result as TargetMaterializationResult.Ready).value
    }

    private fun assertRole(result: MaterializedTargetSet, id: String, expected: ExistingRole) {
        assertEquals(expected, result.targets.existing.single { it.item == ItemId(id) }.role)
    }

    private fun item(
        id: String,
        locked: Boolean = false,
        availability: Availability = Availability.AVAILABLE,
        kind: ItemKind = ItemKind.APPLICATION,
        placement: CapturedPlacement = CapturedPlacement.Workspace(PageRef(PageId("0")), GridCell(0, 0), GridSpan(1, 1)),
    ) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("0"),
        kind = kind,
        target = TargetKey.AppKey(ComponentKey("com.example.$id/.Main"), ProfileId("0")),
        placement = placement,
        locked = locked,
        availability = availability,
    )
}
