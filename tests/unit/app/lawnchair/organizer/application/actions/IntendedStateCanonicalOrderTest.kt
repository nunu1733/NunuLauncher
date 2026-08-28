package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolderOrdinal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #164 AC-164-01/03: plans produced by the real
 * `OrganizationPlanMaterializer` (via [NewFolderPlanFixtures]) must resolve
 * into a write-set intended state whose items are in canonical `ItemId`
 * UTF-8 byte order — the order `RowManifestCodec.capture` produces — whenever
 * a plan creates new folders whose allocated ids byte-sort mid-list.
 *
 * Red against the pre-fix seam (new folders kept last, exactly what the
 * materializer emits), green once the canonical finalization lands. Pure JVM
 * through the production seam; no fixture reimplementation of the ordering
 * and no hand-assembled materializer output.
 */
class IntendedStateCanonicalOrderTest {

    @Test
    fun singleFolderPlanFromRealMaterializerIsCanonicalAfterResolution() {
        val plan = NewFolderPlanFixtures.materializeReady(NewFolderPlanFixtures.singleFolder())

        val folderItems = plan.intendedState.items.filter { it.ref is ApplicationItemRef.PlannedFolder }
        assertTrue("Fixture must plan a new folder", folderItems.isNotEmpty())
        assertEquals(
            "The materializer appends the new folder last (the defect's writer-side shape)",
            listOf(0),
            folderItems.map { (it.ref as ApplicationItemRef.PlannedFolder).ordinal.value },
        )
        assertTrue(folderItems.single().ref == plan.intendedState.items.last().ref)

        val resolved = IntendedStateResolution.resolveAndFinalize(
            plan.intendedState,
            mapOf(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)) to 10L),
            emptyMap(),
        )

        assertNotNull("Resolution must succeed", resolved)
        assertEquals(
            "Intended items must be in canonical ItemId byte order (folder id 10 between 1 and 2)",
            listOf("1", "10", "2", "3", "4", "5", "6", "7", "8", "9"),
            persistentIds(resolved!!),
        )
    }

    @Test
    fun multiFolderPlanWithBoundaryIdsMixesIntoCanonicalOrder() {
        val plan = NewFolderPlanFixtures.materializeReady(NewFolderPlanFixtures.multiFolderWithBoundaryIds())

        val resolved = IntendedStateResolution.resolveAndFinalize(
            plan.intendedState,
            mapOf<ApplicationItemRef, Long>(
                ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)) to 100L,
                ApplicationItemRef.PlannedFolder(NewFolderOrdinal(1)) to 101L,
            ),
            emptyMap(),
        )

        assertNotNull(resolved)
        assertEquals(
            "Both new folders (99+1 → 100, +1 → 101) must byte-sort mid-list across the 9x/10x boundary",
            listOf("1", "100", "101", "19", "9", "91", "99"),
            persistentIds(resolved!!),
        )
    }

    @Test
    fun repeatedPreparationOfMultiFolderPlanIsDeterministic() {
        val plan = NewFolderPlanFixtures.materializeReady(NewFolderPlanFixtures.multiFolderWithBoundaryIds())
        val plannedIds = mapOf<ApplicationItemRef, Long>(
            ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)) to 100L,
            ApplicationItemRef.PlannedFolder(NewFolderOrdinal(1)) to 101L,
        )

        val first = IntendedStateResolution.resolveAndFinalize(plan.intendedState, plannedIds, emptyMap())
        val second = IntendedStateResolution.resolveAndFinalize(plan.intendedState, plannedIds, emptyMap())

        assertEquals(first, second)
    }

    @Test
    fun finalizationFailsClosedOnUnresolvedTopLevelReference() {
        val plan = NewFolderPlanFixtures.materializeReady(NewFolderPlanFixtures.singleFolder())
        val unresolved = plan.intendedState.items.any { it.ref !is ApplicationItemRef.PersistentItem }
        assertTrue("Fixture must contain a planned top-level reference", unresolved)
        assertNull(
            "Unresolved top-level references must fail closed, not produce a fallback order",
            IntendedStateResolution.finalizeCanonicalOrder(plan.intendedState),
        )
    }

    @Test
    fun finalizationFailsClosedOnUnresolvedNestedReference() {
        val resolved = IntendedStateResolution.resolveAndFinalize(
            NewFolderPlanFixtures.materializeReady(NewFolderPlanFixtures.singleFolder()).intendedState,
            mapOf(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)) to 10L),
            emptyMap(),
        )!!
        val drifted = resolved.copy(
            items = resolved.items.map { item ->
                val placement = item.placement as? PlacementState.FolderChild
                if (placement != null && (placement.parent as ApplicationItemRef.PersistentItem).itemId.value == "10") {
                    item.copy(placement = placement.copy(parent = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(7))))
                } else {
                    item
                }
            },
        )

        assertNull(
            "Unresolved nested placement references must fail closed too",
            IntendedStateResolution.finalizeCanonicalOrder(drifted),
        )
    }

    @Test
    fun folderIdentityResolvesAcrossRefPlacementTargetKeyAndStructure() {
        val resolved = IntendedStateResolution.resolveAndFinalize(
            NewFolderPlanFixtures.materializeReady(NewFolderPlanFixtures.singleFolder()).intendedState,
            mapOf(ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0)) to 10L),
            emptyMap(),
        )!!

        val folder = resolved.items.single { it.kind is CanonicalItemKind.Folder }
        assertEquals(ApplicationItemRef.PersistentItem(ItemId("10")), folder.ref)
        val childParentIds = resolved.items
            .map { it.placement }
            .filterIsInstance<PlacementState.FolderChild>()
            .map { (it.parent as ApplicationItemRef.PersistentItem).itemId.value }
        assertEquals(listOf("10", "10"), childParentIds)
        val memberIds = resolved.items
            .flatMap { item ->
                when (val structure = item.structure) {
                    is app.lawnchair.organizer.application.public.StructureState.FolderMembers ->
                        structure.members.map { (it.item as ApplicationItemRef.PersistentItem).itemId.value }

                    else -> emptyList()
                }
            }
        assertEquals(listOf("8", "9"), memberIds)
    }

    private fun persistentIds(state: app.lawnchair.organizer.application.public.LayoutState) = state.items.map {
        (it.ref as ApplicationItemRef.PersistentItem).itemId.value
    }
}
