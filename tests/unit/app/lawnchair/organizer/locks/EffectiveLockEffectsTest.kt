package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockFixtures.appPair
import app.lawnchair.organizer.locks.LockFixtures.dockItem
import app.lawnchair.organizer.locks.LockFixtures.folder
import app.lawnchair.organizer.locks.LockFixtures.folderChild
import app.lawnchair.organizer.locks.LockFixtures.item
import app.lawnchair.organizer.locks.LockFixtures.state
import app.lawnchair.organizer.locks.LockFixtures.widgetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §“Behavior scenarios” + ADR-0004 “Identity and effective-lock rules”:
 * effective protection and typed explanation notes for every scope and
 * precedence case, before any mutation.
 *
 * Issue #38.
 */
class EffectiveLockEffectsTest {

    @Test
    fun `locking a folder parent explains child coverage`() {
        val child = folderChild("211", parent = "201", rank = 0)
        val parent = folder("201", children = listOf(child))
        val state = state(listOf(child, parent))
        val notes = explainEffect(state, parent, LockTargetState.LOCKED)
        assertEquals(listOf(LockEffectNote.FOLDER_PARENT_COVERS_CHILDREN), notes)
    }

    @Test
    fun `unlocking a folder with locked children explains remaining protection`() {
        val child = folderChild("211", parent = "201", rank = 0, lockState = OrganizerLockState.LOCKED)
        val parent = folder("201", children = listOf(child), lockState = OrganizerLockState.LOCKED)
        val state = state(listOf(child, parent))
        val notes = explainEffect(state, parent, LockTargetState.UNLOCKED)
        assertEquals(listOf(LockEffectNote.FOLDER_CHILDREN_OWN_LOCK_REMAINS), notes)
    }

    @Test
    fun `unlocking a folder without locked children has no extra note`() {
        val child = folderChild("211", parent = "201", rank = 0)
        val parent = folder("201", children = listOf(child), lockState = OrganizerLockState.LOCKED)
        val state = state(listOf(child, parent))
        assertTrue(explainEffect(state, parent, LockTargetState.UNLOCKED).isEmpty())
    }

    @Test
    fun `locking a folder child binds its rank despite unlocked parent`() {
        val child = folderChild("211", parent = "201", rank = 0)
        val parent = folder("201", children = listOf(child))
        val state = state(listOf(child, parent))
        assertEquals(
            listOf(LockEffectNote.FOLDER_CHILD_OWN_LOCK_BINDS),
            explainEffect(state, child, LockTargetState.LOCKED),
        )
    }

    @Test
    fun `unlocking a folder child under locked parent is explained as ineffective`() {
        val child = folderChild("211", parent = "201", rank = 0)
        val parent = folder("201", children = listOf(child), lockState = OrganizerLockState.LOCKED)
        val state = state(listOf(child, parent))
        assertEquals(
            listOf(LockEffectNote.FOLDER_CHILD_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK),
            explainEffect(state, child, LockTargetState.UNLOCKED),
        )
        // And the child stays effectively protected while the parent is locked.
        val view = effectiveLockViewOf(state, child)
        assertTrue(view.effectivelyProtected)
        assertEquals(OrganizerLockState.UNLOCKED, view.stored)
    }

    @Test
    fun `locking an app pair parent explains both members`() {
        val pair = appPair("301", first = "311", second = "312")
        val state = state(pair)
        val parent = state.items.byItem("301")
        assertEquals(CanonicalItemKind.AppPair, parent.kind)
        assertEquals(
            listOf(LockEffectNote.APP_PAIR_PARENT_COVERS_BOTH_MEMBERS),
            explainEffect(state, parent, LockTargetState.LOCKED),
        )
    }

    @Test
    fun `locking an app pair member binds despite unlocked pair`() {
        val pair = appPair("301", first = "311", second = "312")
        val state = state(pair)
        assertEquals(
            listOf(LockEffectNote.APP_PAIR_MEMBER_OWN_LOCK_BINDS),
            explainEffect(state, state.items.byItem("311"), LockTargetState.LOCKED),
        )
    }

    @Test
    fun `unlocking an app pair member under locked pair is explained as ineffective`() {
        val pair = appPair(
            "301",
            first = "311",
            second = "312",
            lockState = OrganizerLockState.LOCKED,
        )
        val state = state(pair)
        val member = state.items.byItem("311")
        assertEquals(OrganizerLockState.LOCKED, effectiveLockViewOf(state, state.items.byItem("301")).stored)
        assertEquals(
            listOf(LockEffectNote.APP_PAIR_MEMBER_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK),
            explainEffect(state, member, LockTargetState.UNLOCKED),
        )
        assertTrue(effectiveLockViewOf(state, member).effectivelyProtected)
    }

    @Test
    fun `dock widget and plain rows map to their scopes`() {
        val plain = item("101")
        val dock = dockItem("401")
        val widget = widgetItem("501")
        val state = state(listOf(plain, dock, widget))
        assertEquals(LockProtectionScope.OWN_PLACEMENT, effectiveLockViewOf(state, plain).scope)
        assertEquals(LockProtectionScope.DOCK_SLOT, effectiveLockViewOf(state, dock).scope)
        assertEquals(LockProtectionScope.WIDGET_REGION, effectiveLockViewOf(state, widget).scope)
    }

    @Test
    fun `locked stored state always protects effectively`() {
        val plain = item("101", lockState = OrganizerLockState.LOCKED)
        val view = effectiveLockViewOf(state(listOf(plain)), plain)
        assertTrue(view.effectivelyProtected)
        assertFalse(view.stored == OrganizerLockState.UNKNOWN)
    }

    private fun List<CanonicalItemState>.byItem(
        id: String,
    ) = first { itemIdOf(it).value == id }
}
