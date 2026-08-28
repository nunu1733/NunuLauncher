package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #164 AC-164-01: the resolved write-set intended state must leave the
 * resolution/finalization seam in canonical `ItemId` UTF-8 byte order — the
 * order `RowManifestCodec.capture` produces — whenever a plan creates a new
 * folder whose allocated id byte-sorts mid-list (here: existing ids 1..9 and
 * folder id 10, which sorts between "1" and "2").
 *
 * Red against the pre-fix seam (new folder kept last, exactly what the
 * materializer emits), green once the canonical finalization lands. Pure JVM
 * through the production seam; no fixture reimplementation of the ordering.
 */
class IntendedStateCanonicalOrderTest {

    @Test
    fun resolvedIntendedStateIsInCanonicalItemIdByteOrder() {
        val resolved = IntendedStateResolution.resolveAndFinalize(plannedState(), plannedIds(), emptyMap())

        assertNotNull("Resolution must succeed", resolved)
        val ids = resolved!!.items.map {
            (it.ref as ApplicationItemRef.PersistentItem).itemId.value
        }
        assertEquals(
            "Intended items must be in canonical ItemId byte order (folder id 10 between 1 and 2)",
            listOf("1", "10", "2", "3", "4", "5", "6", "7", "8", "9"),
            ids,
        )
    }

    @Test
    fun resolutionIsDeterministicAcrossRepeatedPreparation() {
        val first = IntendedStateResolution.resolveAndFinalize(plannedState(), plannedIds(), emptyMap())
        val second = IntendedStateResolution.resolveAndFinalize(plannedState(), plannedIds(), emptyMap())

        assertEquals(first, second)
    }

    @Test
    fun finalizationFailsClosedOnUnresolvedReferenceInsteadOfInventingAnOrder() {
        val planned = plannedState()
        val unresolved = planned.items.any { it.ref !is ApplicationItemRef.PersistentItem }
        assertTrue("Fixture must contain a planned reference", unresolved)
        assertNull(
            "Unresolved references must fail closed, not produce a fallback order",
            IntendedStateResolution.finalizeCanonicalOrder(planned),
        )
    }

    @Test
    fun folderIdentityResolvesAcrossRefPlacementTargetKeyAndStructure() {
        val resolved = IntendedStateResolution.resolveAndFinalize(plannedState(), plannedIds(), emptyMap())!!

        val folder = resolved.items.single { it.kind is CanonicalItemKind.Folder }
        assertEquals(ApplicationItemRef.PersistentItem(ItemId("10")), folder.ref)
        assertEquals(TargetKey.FolderKey(FolderId("10")), folder.targetKey)
        assertEquals(
            StructureState.FolderMembers(
                listOf(
                    RankedMember(ApplicationItemRef.PersistentItem(ItemId("8")), 0),
                    RankedMember(ApplicationItemRef.PersistentItem(ItemId("9")), 1),
                ),
            ),
            folder.structure,
        )
        val childParentIds = resolved.items
            .map { it.placement }
            .filterIsInstance<PlacementState.FolderChild>()
            .map { (it.parent as ApplicationItemRef.PersistentItem).itemId.value }
        assertEquals(listOf("10", "10"), childParentIds)
    }

    private fun plannedIds(): Map<ApplicationItemRef, Long> = mapOf(plannedFolderRef() to 10L)

    private fun plannedFolderRef() = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0))

    /**
     * Existing items 1..9 in planner order (matching byte order for existing
     * items) with items 8 and 9 moved into the new folder and the folder
     * appended last, exactly as `OrganizationPlanMaterializer` emits it.
     */
    private fun plannedState(): LayoutState {
        val base = CanonicalFixtures.state()
        val items = (1..9).map { i ->
            CanonicalFixtures.appItem(
                itemId = i.toString(),
                cell = GridCell(0, i - 1),
                target = TargetKey.AppKey(
                    component = app.lawnchair.organizer.planning.ComponentKey("com.example.a$i/.Main"),
                    profile = ProfileId("personal"),
                ),
            )
        }.map { item ->
            when ((item.ref as ApplicationItemRef.PersistentItem).itemId.value) {
                "8" -> item.copy(placement = PlacementState.FolderChild(plannedFolderRef(), 0))
                "9" -> item.copy(placement = PlacementState.FolderChild(plannedFolderRef(), 1))
                else -> item
            }
        } + plannedFolderItem()
        return LayoutState(
            pages = base.pages,
            profiles = base.profiles,
            deviceCapabilities = base.deviceCapabilities,
            items = items,
        )
    }

    private fun plannedFolderItem(): CanonicalItemState = CanonicalItemState(
        ref = plannedFolderRef(),
        kind = CanonicalItemKind.Folder,
        targetKey = TargetKey.FolderKey(FolderId("planned-folder-0")),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = PlacementState.Workspace(
            page = ApplicationPageRef.PersistentPage(PageId("p0")),
            cell = GridCell(1, 0),
            span = GridSpan(1, 1),
        ),
        title = OptionalText.Present("Folder"),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(0),
        lockState = OrganizerLockState.UNLOCKED,
        structure = StructureState.FolderMembers(
            listOf(
                RankedMember(ApplicationItemRef.PersistentItem(ItemId("8")), 0),
                RankedMember(ApplicationItemRef.PersistentItem(ItemId("9")), 1),
            ),
        ),
    )
}
