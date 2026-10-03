/*
 * Issue #507: JVM tests for the duplicate-group projection and the last-one
 * guard across the two confirmed-action boundaries (spec 507 AC-2/AC-4).
 * The dialog drives the same production selection state and the same
 * RemoveFromHome action as the diagram; the guard oracle lives on the pure
 * functions (EditSurfaceDuplicateGroups) that both boundaries call.
 */
package app.lawnchair.homeedit

import app.lawnchair.homeedit.ui.editSurfaceDuplicateRowDescription
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ShortcutId
import app.lawnchair.organizer.planning.TargetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceDuplicateGroupsTest {

    // --- kind mapping: synthetic rows (RESERVED) and unknown codes never map. ---

    @Test
    fun kindMapping_excludesReservedAndUnknownCodes() {
        assertNull(editSurfaceDuplicateItemKind(HomeEditItemTypes.RESERVED))
        assertNull(editSurfaceDuplicateItemKind(999))
    }

    // --- group computation: same launch target only (AC-2). ---

    @Test
    fun sameNameDifferentTargetIsNotADuplicate() {
        val groups = groupsOf(
            appItem(id = 20, title = "Photos", component = "com.example/.App-20"),
            appItem(id = 21, title = "Photos", component = "com.example/.App-21", page = 1),
        )
        assertEquals(0, groups.size)
    }

    @Test
    fun sameComponentDifferentProfileIsNotADuplicate() {
        val groups = groupsOf(
            appItem(id = 30, title = "Memo", component = "com.example/.memo"),
            appItem(id = 31, title = "Memo", component = "com.example/.memo", page = 1, profile = ProfileId("11")),
        )
        assertEquals(0, groups.size)
    }

    @Test
    fun sameTargetAcrossPagesFormsAGroupOrderedVisually() {
        val groups = groupsOf(
            appItem(id = 40, title = "Photos", component = "com.example/.shared"),
            appItem(id = 41, title = "Photos", component = "com.example/.shared", page = 1),
        )
        assertEquals(1, groups.size)
        // Page 0 member first, then page 1 (visual order, not list order).
        assertEquals(listOf(40, 41), groups.single().members.map { it.id })
        assertEquals(2, groups.single().selectableMembers.size)
    }

    @Test
    fun deepShortcutDuplicatesGroupByPackageShortcutIdProfile() {
        val groups = groupsOf(
            deepShortcut(id = 60, page = 0, shortcutId = "note"),
            deepShortcut(id = 61, page = 1, shortcutId = "note"),
            deepShortcut(id = 62, page = 0, shortcutId = "other"),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(60, 61), groups.single().members.map { it.id })
        assertEquals(2, groups.single().selectableMembers.size)
    }

    @Test
    fun nonParticipatingKindsNeverGroup() {
        val groups = groupsOf(
            canonicalSurfaceItem(
                70,
                CanonicalItemKind.Folder,
                onSurfaceWorkspace(0, 0, 0),
                structure = StructureState.Plain,
            ),
            appItem(id = 71, title = "W", component = "com.example/.w", page = 0, cellX = 2, cellY = 2).copy(
                kind = CanonicalItemKind.AppWidget,
                targetKey = TargetKey.WidgetKey(ComponentKey("com.example/.Widget"), AppWidgetId(7), PROFILE_A),
            ),
        )
        assertEquals(0, groups.size)
    }

    @Test
    fun sessionCreatedFolderRowsNeverGroup() {
        val folder = EditSurfaceItem(
            id = editSurfaceNewFolderKey(0),
            itemType = HomeEditItemTypes.FOLDER,
            label = null,
            iconBytes = null,
            targetKey = null,
            userSerial = SERIAL_A,
            lockState = OrganizerLockState.UNLOCKED,
            container = HomeEditContainers.DESKTOP,
            screenId = 0,
            cellX = 2,
            cellY = 2,
            spanX = 1,
            spanY = 1,
            rank = 0,
            isSessionCreated = true,
        )
        val another = folder.copy(id = editSurfaceNewFolderKey(1), cellX = 3)
        val diagram = EditSurfaceProjection.diagram(
            editSurfaceLayout(emptyList()),
            HomeEditSnapshot(editSurfaceCapabilities.columns, editSurfaceCapabilities.rows, listOf(0, 1, 2), emptyList()),
        ).copy(items = listOf(folder, another))
        assertEquals(0, EditSurfaceDuplicateGroups.groups(diagram).size)
    }

    // --- grouping delegated to the #451 authority (AC-2: no second rule). ---

    @Test
    fun planningGeneralizationMatchesLegacySurplusRule() {
        val items = listOf(
            capturedApp("3", page = 0),
            capturedApp("7", page = 1),
            capturedApp("4", page = 0, componentSuffix = "other"),
        )
        // The generalized delegate returns the same surplus ids as the #451
        // rule: the canonical-first ItemId is the representative ("3"), the
        // surplus is the non-representative member ("7").
        val surplus = app.lawnchair.organizer.planning.duplicateSurplusIds(items)
        assertEquals(setOf("7"), surplus.map { it.value }.toSet())
        val grouped = app.lawnchair.organizer.planning.duplicateGroups(
            items,
            kindOf = { it.kind },
            targetOf = { it.target },
        )
        assertEquals(1, grouped.size)
        assertEquals(2, grouped.values.single().size)
    }

    // --- last-one guard (AC-4): the shared function both boundaries call. ---

    @Test
    fun guardFiresOnlyWhenEveryMemberIsSelected() {
        val group = groupsOf(
            appItem(id = 81, title = "Photos", component = "com.example/.photo"),
            appItem(id = 82, title = "Photos", component = "com.example/.photo", page = 1, cellX = 1).copy(
                lockState = OrganizerLockState.LOCKED,
            ),
        ).single()
        // The locked member cannot be selected, so the group can never be
        // fully selected and the guard must not fire.
        assertNull(EditSurfaceDuplicateGroups.fullySelectedGroup(listOf(group), listOf(81)))
        assertEquals(1, group.selectableMembers.size)
        // Full selection of an all-selectable group fires.
        val pair = groupsOf(
            appItem(id = 90, title = "Photos", component = "com.example/.dup2"),
            appItem(id = 91, title = "Photos", component = "com.example/.dup2", page = 1),
        ).single()
        assertEquals(pair, EditSurfaceDuplicateGroups.fullySelectedGroup(listOf(pair), listOf(90, 91)))
        assertNull(EditSurfaceDuplicateGroups.fullySelectedGroup(listOf(pair), listOf(90)))
    }

    @Test
    fun guardIgnoresUnrelatedSelections() {
        val groups = groupsOf(
            appItem(id = 100, title = "Photos", component = "com.example/.d1"),
            appItem(id = 101, title = "Photos", component = "com.example/.d1", page = 1),
            appItem(id = 102, title = "Solo", component = "com.example/.solo", page = 1, cellX = 1),
        )
        // 1 dup member + unrelated selected: no group is fully selected.
        assertNull(EditSurfaceDuplicateGroups.fullySelectedGroup(groups, listOf(100, 102)))
        // Both dup members selected: the guard fires for their group only.
        val group = EditSurfaceDuplicateGroups.fullySelectedGroup(groups, listOf(100, 101, 102))
        assertEquals(setOf(100, 101), group?.members?.map { it.id }?.toSet())
    }

    @Test
    fun rowSelectableFollowsEligibilityAndTouchedGuard() {
        val diagram = groupsDiagram(
            appItem(id = 110, title = "P", component = "com.example/.p"),
            appItem(id = 111, title = "L", component = "com.example/.l", page = 1).copy(
                lockState = OrganizerLockState.LOCKED,
            ),
        )
        // Selectable on-workspace app not yet handled.
        assertEquals(true, EditSurfaceDuplicateGroups.rowSelectable(diagram.itemById.getValue(110), emptySet()))
        // Locked rows are never selectable, regardless of the touched set.
        assertEquals(false, EditSurfaceDuplicateGroups.rowSelectable(diagram.itemById.getValue(111), emptySet()))
        // Touched items are shown as handled (not selectable).
        assertEquals(false, EditSurfaceDuplicateGroups.rowSelectable(diagram.itemById.getValue(110), setOf(110)))
    }

    // --- session-applied working diagram: removed members drop out (AC-6). ---

    @Test
    fun removalDropsTheMemberFromTheGroup() {
        val layout = editSurfaceLayout(
            listOf(
                appItem(id = 120, title = "Photos", component = "com.example/.gone"),
                appItem(id = 121, title = "Photos", component = "com.example/.gone", page = 1),
            ),
        )
        val capture = EditSurfaceProjection.homeEditSnapshot(layout)
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(121)),
        )
        val working = EditSurfaceProjection.workingSnapshot(capture, session)
        val groups = EditSurfaceDuplicateGroups.groups(EditSurfaceProjection.diagram(layout, working))
        assertEquals(0, groups.size)
    }

    @Test
    fun threeMembersKeepOneAfterTwoRemovals() {
        val layout = editSurfaceLayout(
            listOf(
                appItem(id = 130, title = "N", component = "com.example/.n1"),
                appItem(id = 131, title = "N", component = "com.example/.n1", page = 1),
                appItem(id = 132, title = "N", component = "com.example/.n1", page = 2),
            ),
        )
        val capture = EditSurfaceProjection.homeEditSnapshot(layout)
        val session = EditSurfaceSession(
            newFolders = emptyList(),
            changes = listOf(SessionItemChange.Removal(131), SessionItemChange.Removal(132)),
        )
        val working = EditSurfaceProjection.workingSnapshot(capture, session)
        val groups = EditSurfaceDuplicateGroups.groups(EditSurfaceProjection.diagram(layout, working))
        assertEquals(0, groups.size)
    }

    // --- determinism: permutation invariance of the group computation. ---

    @Test
    fun groupOrderIsDeterministicAcrossPermutations() {
        val items = listOf(
            appItem(id = 140, title = "A", component = "com.example/.a"),
            appItem(id = 141, title = "A", component = "com.example/.a", page = 1),
            appItem(id = 142, title = "B", component = "com.example/.b", page = 1, cellX = 1),
            appItem(id = 143, title = "B", component = "com.example/.b", cellX = 1),
        )
        val base = EditSurfaceDuplicateGroups.groups(groupsDiagram(*items.toTypedArray()))
        val permuted = EditSurfaceDuplicateGroups.groups(
            groupsDiagram(*items.reversed().toTypedArray()),
        )
        assertEquals(
            base.map { it.members.map { it.id } },
            permuted.map { it.members.map { it.id } },
        )
        // Group ordering follows the first member's visual rank (page 0 pair first).
        assertEquals(listOf(140, 141), base[0].members.map { it.id })
        assertEquals(listOf(143, 142), base[1].members.map { it.id })
    }

    // --- rows: folder members subordinate to the parent's position, dock last. ---

    @Test
    fun folderMembersSortUnderTheirParentAndDockSortsLast() {
        val parent = canonicalSurfaceItem(
            150,
            CanonicalItemKind.Folder,
            onSurfaceWorkspace(0, 2, 2),
            structure = StructureState.Plain,
        )
        val childA = folderChild(151, folderId = 150, rank = 0, component = "com.example/.childdup")
        val childB = folderChild(152, folderId = 150, rank = 1, component = "com.example/.childdup")
        val dockA = dockItem(153, rank = 0, title = "D", component = "com.example/.dockdup")
        val dockB = dockItem(154, rank = 1, title = "D", component = "com.example/.dockdup")
        val groups = groupsOf(parent, childA, childB, dockA, dockB)
        assertEquals(2, groups.size)
        val folderGroup = groups.first { it.members.any { it.id == 151 } }
        val dockGroup = groups.first { it.members.any { it.id == 153 } }
        // Folder children follow the parent cell; dock rows sort after every
        // workspace/folder member (group order: folder group, then dock group).
        assertEquals(listOf(151, 152), folderGroup.members.map { it.id })
        assertEquals(listOf(153, 154), dockGroup.members.map { it.id })
        assertEquals(0, groups.indexOf(folderGroup))
        assertEquals(1, groups.indexOf(dockGroup))
    }

    @Test
    fun folderChildAndWorkspaceMembersShareThePageAxis() {
        // Folder child under a page-0 folder at (0,0) must come BEFORE a
        // workspace member on page 1 (review round 2: folder children were
        // sorted after every desktop item regardless of page).
        val parent = canonicalSurfaceItem(
            160,
            CanonicalItemKind.Folder,
            onSurfaceWorkspace(0, 0, 0),
            structure = StructureState.Plain,
        )
        val child = folderChild(161, folderId = 160, rank = 0, component = "com.example/.mixdup")
        val workspace = appItem(id = 162, title = "W", component = "com.example/.mixdup", page = 1)
        val groups = groupsOf(parent, child, workspace)
        assertEquals(1, groups.size)
        assertEquals(listOf(161, 162), groups.single().members.map { it.id })
    }

    @Test
    fun samePageWorkspacePositionBeatsFolderChildAtLaterParentCell() {
        // Workspace item at (0,0) comes before a folder child whose parent
        // sits at (0,1) on the same page.
        val parent = canonicalSurfaceItem(
            170,
            CanonicalItemKind.Folder,
            onSurfaceWorkspace(0, 0, 1),
            structure = StructureState.Plain,
        )
        val child = folderChild(171, folderId = 170, rank = 0, component = "com.example/.pagedup")
        val workspace = appItem(id = 172, title = "W", component = "com.example/.pagedup")
        val groups = groupsOf(parent, child, workspace)
        assertEquals(1, groups.size)
        assertEquals(listOf(172, 171), groups.single().members.map { it.id })
    }

    // --- AC-4 dispatch-boundary oracle: pre-selected full group. ---

    @Test
    fun dispatchGuardRejectsPreSelectedThreeMemberGroupAndAllowsAfterDeselect() {
        val layout = editSurfaceLayout(
            listOf(
                appItem(id = 180, title = "T", component = "com.example/.t1"),
                appItem(id = 181, title = "T", component = "com.example/.t1", page = 1),
                appItem(id = 182, title = "T", component = "com.example/.t1", page = 2),
            ),
        )
        val capture = EditSurfaceProjection.homeEditSnapshot(layout)
        val working = EditSurfaceProjection.workingSnapshot(capture, EditSurfaceSession.EMPTY)
        val diagram = EditSurfaceProjection.diagram(layout, working)
        val groups = EditSurfaceDuplicateGroups.groups(diagram)
        val lockStates = mapOf(
            180 to OrganizerLockState.UNLOCKED,
            181 to OrganizerLockState.UNLOCKED,
            182 to OrganizerLockState.UNLOCKED,
        )

        // Pre-selected full group (the round-1 bypass: select everything on
        // the diagram, then dispatch Remove from the dialog). The
        // dispatch-boundary guard fires, so the dialog keeps the zero-write
        // session unchanged.
        val fullSelection = listOf(180, 181, 182)
        val guarded = EditSurfaceDuplicateGroups.fullySelectedGroup(groups, fullSelection)
        assertEquals(setOf(180, 181, 182), guarded?.members?.map { it.id }?.toSet())
        assertEquals(0, EditSurfaceSession.EMPTY.changes.size)
        // Without the guard the planner WOULD plan all three removals — the
        // guard is the only thing standing between the dialog and the
        // last-one deletion (this pins why the dispatch check is required).
        val unguarded = EditSurfaceSessionPlanner.plan(
            capture,
            lockStates,
            EditSurfaceSession.EMPTY,
            fullSelection,
            PendingSessionAction.RemoveFromHome,
        )
        assertTrue(unguarded is SessionPlanResult.Applied)
        assertEquals(3, (unguarded as SessionPlanResult.Applied).session.changes.size)

        // Deselecting one member clears the guard: the same dialog dispatch
        // now plans exactly the two remaining removals through the existing
        // RemoveFromHome contract.
        val reduced = fullSelection - 181
        assertNull(EditSurfaceDuplicateGroups.fullySelectedGroup(groups, reduced))
        val planned = EditSurfaceSessionPlanner.plan(
            capture,
            lockStates,
            EditSurfaceSession.EMPTY,
            reduced,
            PendingSessionAction.RemoveFromHome,
        )
        assertTrue(planned is SessionPlanResult.Applied)
        assertEquals(setOf(180, 182), (planned as SessionPlanResult.Applied).session.changes.map { it.targetId }.toSet())
    }

    // --- row description oracle (AC-8). ---

    @Test
    fun duplicateRowDescriptionIncludesNamePositionProfileReason() {
        val description = editSurfaceDuplicateRowDescription(
            label = "Photos",
            position = "ページ1、行2",
            profile = "仕事用プロファイル",
            reason = "ロック済み",
            selected = false,
            selectedText = "選択済み",
        )
        assertEquals("Photos, ページ1、行2, 仕事用プロファイル, ロック済み", description)
    }

    @Test
    fun duplicateRowDescriptionOmitsBlankParts() {
        val description = editSurfaceDuplicateRowDescription(
            label = "Photos",
            position = "",
            profile = null,
            reason = null,
            selected = true,
            selectedText = "selected",
        )
        assertEquals("Photos, selected", description)
    }
}

// --- helpers ---

/** The edit-surface diagram of a #449 capture built from [items]. */
private fun groupsDiagram(vararg items: CanonicalItemState): EditSurfaceDiagram {
    val layout = editSurfaceLayout(items.toList())
    val capture = EditSurfaceProjection.homeEditSnapshot(layout)
    val working = EditSurfaceProjection.workingSnapshot(capture, EditSurfaceSession.EMPTY)
    return EditSurfaceProjection.diagram(layout, working)
}

private fun groupsOf(vararg items: CanonicalItemState): List<EditSurfaceDuplicateGroup> = EditSurfaceDuplicateGroups.groups(groupsDiagram(*items))

/** A workspace app row whose launch target is [component] (+ its profile). */
private fun appItem(
    id: Int,
    title: String,
    component: String,
    page: Int = 0,
    cellX: Int = 0,
    cellY: Int = 0,
    profile: ProfileId = PROFILE_A,
): CanonicalItemState {
    val base = canonicalSurfaceItem(
        id = id,
        placement = onSurfaceWorkspace(page, cellX, cellY),
        title = OptionalText.Present(title),
        profile = profile,
    )
    return base.copy(targetKey = TargetKey.AppKey(ComponentKey(component), profile))
}

/** A desktop deep-shortcut row sharing [shortcutId] in the fixture package. */
private fun deepShortcut(id: Int, page: Int, shortcutId: String): CanonicalItemState {
    val base = canonicalSurfaceItem(
        id = id,
        CanonicalItemKind.DeepShortcut,
        onSurfaceWorkspace(page, 0, 3),
        title = OptionalText.Present("S$id"),
    )
    return base.copy(
        targetKey = TargetKey.ShortcutKey(
            PackageName("com.example"),
            ShortcutId("s-$shortcutId"),
            PROFILE_A,
        ),
    )
}

/** A folder child row under the persistent folder row [folderId]. */
private fun folderChild(id: Int, folderId: Int, rank: Int, component: String): CanonicalItemState {
    val base = appItem(id = id, title = "C$id", component = component)
    return base.copy(
        placement = PlacementState.FolderChild(
            ApplicationItemRef.PersistentItem(ItemId(folderId.toString())),
            rank,
        ),
    )
}

/** A dock row whose launch target is [component]. */
private fun dockItem(id: Int, rank: Int, title: String, component: String): CanonicalItemState {
    val base = canonicalSurfaceItem(
        id = id,
        placement = PlacementState.Dock(rank),
        title = OptionalText.Present(title),
    )
    return base.copy(targetKey = TargetKey.AppKey(ComponentKey(component), PROFILE_A))
}

// --- planning-side fixtures (for the #451 delegate regression test) ---

/** A captured application item for the planning delegate test. */
private fun capturedApp(
    id: String,
    page: Int,
    componentSuffix: String = "",
): CapturedItem = CapturedItem(
    id = ItemId(id),
    profile = PROFILE_A,
    kind = ItemKind.APPLICATION,
    target = TargetKey.AppKey(
        ComponentKey(if (componentSuffix.isEmpty()) "com.example/.shared" else "com.example/.$componentSuffix"),
        PROFILE_A,
    ),
    placement = app.lawnchair.organizer.planning.CapturedPlacement.Workspace(
        app.lawnchair.organizer.planning.PageRef(app.lawnchair.organizer.planning.PageId(page.toString())),
        app.lawnchair.organizer.planning.GridCell(0, 0),
        app.lawnchair.organizer.planning.GridSpan(1, 1),
    ),
    locked = false,
    availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
)
