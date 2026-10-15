/*
 * Issue #449: fixture and boundary tests for the pure apply-plan builder,
 * through the public seam (EditSurfacePlanBuilder.build). The protected
 * contracts are the action correspondence (Preserve / Update / Insert,
 * deletion by absence), the conservation invariant (nothing invented, nothing
 * silently dropped — the apply path's delete pass materializes absence), the
 * NoChanges unreachability invariant (no empty diff), the untitled new-folder
 * declaration, and determinism.
 */
package app.lawnchair.homeedit

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val REVISION = RevisionId("a".repeat(32))
private val RULE_VERSION = RuleVersion("test-rules")
private val TAXONOMY_VERSION = TaxonomyVersion("test-taxonomy")

private fun build(capture: app.lawnchair.organizer.application.public.LayoutState, session: EditSurfaceSession): ValidatedLayoutPlan {
    val result = EditSurfacePlanBuilder.build(capture, REVISION, session, RULE_VERSION, TAXONOMY_VERSION)
    return (result as? EditSurfaceApplyPlan.Ready)?.plan ?: throw AssertionError("expected Ready: $result")
}

private fun pageMoveSession(targetId: Int, screenId: Int, cellX: Int, cellY: Int) = EditSurfaceSession(
    newFolders = emptyList(),
    changes = listOf(SessionItemChange.PageMove(targetId, screenId, GridCell(cellX, cellY))),
)

class EditSurfacePlanBuilderTest {

    private val layout = editSurfaceLayout(
        listOf(
            canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0)),
            canonicalSurfaceItem(2, placement = onSurfaceWorkspace(0, 1, 0)),
            canonicalSurfaceItem(3, placement = onSurfaceWorkspace(1, 0, 0)),
            canonicalSurfaceItem(50, CanonicalItemKind.Folder, onSurfaceWorkspace(0, 3, 0)),
        ),
    )

    // --- correspondence ---

    @Test
    fun `a page move is one update and everything else is preserved`() {
        val built = build(layout, pageMoveSession(1, screenId = 1, cellX = 2, cellY = 2))
        assertEquals(REVISION, built.sourceRevision)
        assertEquals(layout, built.sourceState)
        val updates = built.actions.filterIsInstance<ApplyAction.Update>()
        val preserves = built.actions.filterIsInstance<ApplyAction.Preserve>()
        assertEquals(1, updates.size)
        assertEquals(3, preserves.size)
        val moved = updates.single()
        val movedPlacement = moved.intended.placement as PlacementState.Workspace
        assertEquals(1, (movedPlacement.page as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId.value.toInt())
        assertEquals(GridCell(2, 2), movedPlacement.cell)
        assertEquals(
            moved.expected.copy(
                placement = (moved.expected.placement as PlacementState.Workspace).copy(
                    page = app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
                        app.lawnchair.organizer.planning.PageId("1"),
                    ),
                    cell = GridCell(2, 2),
                ),
            ),
            moved.intended,
        )
        assertTrue(built.newFolders.isEmpty())
        assertTrue(built.newPages.isEmpty())
    }

    @Test
    fun `a removal is expressed as absence without an action row`() {
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(2)),
        )
        val built = build(layout, session)
        assertTrue(
            built.intendedState.items.none { item ->
                (item.ref as? ApplicationItemRef.PersistentItem)?.itemId?.value == "2"
            },
        )
        assertTrue(built.actions.none { it.ref == ApplicationItemRef.PersistentItem(ItemId("2")) })
        // Conservation: exactly one persistent item less than the capture.
        assertEquals(
            layout.items.size - 1,
            built.intendedState.items.size,
        )
    }

    // --- new folder ---

    @Test
    fun `a session folder is an insert with an untitled declaration and member updates`() {
        val session = EditSurfaceSession(
            newFolders = listOf(
                SessionNewFolder(ordinal = 0, screenId = 0, cellX = 0, cellY = 0, memberIds = listOf(1, 2), userSerial = SERIAL_A),
            ),
            changes = listOf(
                SessionItemChange.FolderAdd(1, editSurfaceNewFolderKey(0), 0),
                SessionItemChange.FolderAdd(2, editSurfaceNewFolderKey(0), 1),
            ),
        )
        val built = build(layout, session)
        assertEquals(1, built.newFolders.size)
        val declaration = built.newFolders.single()
        assertEquals(FolderNaming.FromUserCreation, declaration.naming)
        assertEquals(listOf(ItemId("1"), ItemId("2")), declaration.members)
        assertEquals(0, declaration.ordinal.value)
        val inserts = built.actions.filterIsInstance<ApplyAction.Insert>()
        assertEquals(1, inserts.size)
        val folderItem = inserts.single().intended
        assertTrue(folderItem.ref is ApplicationItemRef.PlannedFolder)
        assertEquals(OptionalText.Absent, folderItem.title)
        assertEquals(CanonicalItemKind.Folder, folderItem.kind)
        assertEquals(OrganizerLockState.UNLOCKED, folderItem.lockState)
        // The children point at the planned folder and their rows update.
        val childUpdates = built.actions.filterIsInstance<ApplyAction.Update>()
            .filter { (it.intended.placement as? PlacementState.FolderChild)?.parent is ApplicationItemRef.PlannedFolder }
        assertEquals(2, childUpdates.size)
        // The folder structure carries both members in rank order.
        val structure = folderItem.structure as app.lawnchair.organizer.application.public.StructureState.FolderMembers
        assertEquals(listOf(0, 1), structure.members.map { it.rank })
        assertEquals(listOf("1", "2"), structure.members.map { (it.item as ApplicationItemRef.PersistentItem).itemId.value })
    }

    @Test
    fun `adding to an existing folder updates the folder structure too`() {
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.FolderAdd(1, 50, 0)),
        )
        val built = build(layout, session)
        val folderUpdates = built.actions.filterIsInstance<ApplyAction.Update>()
            .filter { it.ref == ApplicationItemRef.PersistentItem(ItemId("50")) }
        assertEquals(1, folderUpdates.size)
        val structure = folderUpdates.single().intended.structure
            as app.lawnchair.organizer.application.public.StructureState.FolderMembers
        assertEquals(listOf("1"), structure.members.map { (it.item as ApplicationItemRef.PersistentItem).itemId.value })
    }

    // --- guards ---

    @Test
    fun `an empty session is rejected as empty`() {
        assertEquals(
            EditSurfaceApplyPlan.Empty,
            EditSurfacePlanBuilder.build(layout, REVISION, EditSurfaceSession.EMPTY, RULE_VERSION, TAXONOMY_VERSION),
        )
    }

    @Test
    fun `a no-op session never produces an empty diff`() {
        // A page move to the item's current cell would be a no-op; the builder
        // must refuse it (NoChanges unreachability invariant, AC-16).
        val session = pageMoveSession(1, screenId = 0, cellX = 0, cellY = 0)
        assertEquals(
            EditSurfaceApplyPlan.Inconsistent("session produced an empty diff"),
            EditSurfacePlanBuilder.build(layout, REVISION, session, RULE_VERSION, TAXONOMY_VERSION),
        )
    }

    @Test
    fun `a change targeting a missing item is inconsistent`() {
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(999)),
        )
        assertTrue(
            EditSurfacePlanBuilder.build(layout, REVISION, session, RULE_VERSION, TAXONOMY_VERSION)
                is EditSurfaceApplyPlan.Inconsistent,
        )
    }

    @Test
    fun `a session folder without members is inconsistent`() {
        val session = EditSurfaceSession(
            newFolders = listOf(
                SessionNewFolder(ordinal = 0, screenId = 0, cellX = 0, cellY = 0, memberIds = emptyList(), userSerial = SERIAL_A),
            ),
            changes = emptyList(),
        )
        assertTrue(
            EditSurfacePlanBuilder.build(layout, REVISION, session, RULE_VERSION, TAXONOMY_VERSION)
                is EditSurfaceApplyPlan.Inconsistent,
        )
    }

    // --- determinism ---

    @Test
    fun `building is deterministic for the same inputs`() {
        val session = EditSurfaceSession(
            newFolders = listOf(
                SessionNewFolder(ordinal = 0, screenId = 0, cellX = 0, cellY = 0, memberIds = listOf(1, 2), userSerial = SERIAL_A),
            ),
            changes = listOf(
                SessionItemChange.FolderAdd(1, editSurfaceNewFolderKey(0), 0),
                SessionItemChange.FolderAdd(2, editSurfaceNewFolderKey(0), 1),
            ),
        )
        assertEquals(
            build(layout, session),
            build(layout, session),
        )
    }

    @Test
    fun `two session folders get distinct planned ordinals`() {
        val first = (
            EditSurfaceSessionPlanner.plan(
                editSurfaceCapture(layout.items),
                emptyMap(),
                EditSurfaceSession.EMPTY,
                listOf(1, 2),
                PendingSessionAction.CreateFolder,
            ) as SessionPlanResult.Applied
            ).session
        val second = (
            EditSurfaceSessionPlanner.plan(
                editSurfaceCapture(layout.items),
                emptyMap(),
                first,
                listOf(3),
                PendingSessionAction.CreateFolder,
            ) as SessionPlanResult.Applied
            ).session
        val built = build(layout, second)
        assertEquals(listOf(0, 1), built.newFolders.map { it.ordinal.value })
        assertEquals(2, built.actions.count { it is ApplyAction.Insert })
    }

    @Test
    fun `title metadata is untouched by a move`() {
        val titled = editSurfaceLayout(
            listOf(
                canonicalSurfaceItem(1, placement = onSurfaceWorkspace(0, 0, 0), title = OptionalText.Present("Alpha")),
            ),
        )
        val built = build(titled, pageMoveSession(1, screenId = 1, cellX = 0, cellY = 0))
        val update = built.actions.filterIsInstance<ApplyAction.Update>().single()
        // The moved row keeps its capture title; only the placement differs.
        assertEquals("Alpha", (update.intended.title as OptionalText.Present).value)
        assertEquals(update.expected.title, update.intended.title)
    }
}
