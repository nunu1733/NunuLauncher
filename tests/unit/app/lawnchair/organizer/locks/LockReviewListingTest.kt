package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockFixtures.dockItem
import app.lawnchair.organizer.locks.LockFixtures.folder
import app.lawnchair.organizer.locks.LockFixtures.folderChild
import app.lawnchair.organizer.locks.LockFixtures.item
import app.lawnchair.organizer.locks.LockFixtures.state
import app.lawnchair.organizer.locks.LockFixtures.unknownKindItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §“Behavior scenarios”: deterministic `UNKNOWN` review listing and the
 * management listing — only unknown actionable rows are reviewable, ordering
 * is stable, and corrupt/unknown-kind rows are surfaced through the state
 * listing path, never silently coerced.
 *
 * Issue #38.
 */
class LockReviewListingTest {

    @Test
    fun `lists only unknown rows`() {
        val items = listOf(
            item("111", lockState = OrganizerLockState.LOCKED),
            item("112", lockState = OrganizerLockState.UNKNOWN),
            item("113", lockState = OrganizerLockState.UNLOCKED),
        )
        val listing = LockReviewListing.of(state(items))
        assertEquals(listOf("112"), listing.entries.map { it.item.value })
    }

    @Test
    fun `excludes unknown-kind rows even when state is unknown`() {
        val row = unknownKindItem("601", lockState = OrganizerLockState.UNKNOWN)
        val listing = LockReviewListing.of(state(listOf(row)))
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `listing is deterministically ordered across shuffles`() {
        val child = folderChild("211", parent = "201", rank = 0, lockState = OrganizerLockState.UNKNOWN)
        val parent = folder("201", children = listOf(child), lockState = OrganizerLockState.UNKNOWN)
        val dock = dockItem("401", lockState = OrganizerLockState.UNKNOWN)
        val desktop = item("102", lockState = OrganizerLockState.UNKNOWN)
        val base = listOf(child, parent, dock, desktop)
        val reference = LockReviewListing.of(state(base)).entries.map { it.item.value }
        repeat(5) { seed ->
            val shuffled = base.shuffled(java.util.Random(seed.toLong()))
            assertEquals(reference, LockReviewListing.of(state(shuffled)).entries.map { it.item.value })
        }
    }

    @Test
    fun `listing carries placement profile and kind context`() {
        val dock = dockItem("401", lockState = OrganizerLockState.UNKNOWN)
        val entry = LockReviewListing.of(state(listOf(dock))).entries.single()
        assertEquals(LockPlacementSummary.DockSlot(0), entry.placement)
        assertEquals("personal", entry.profile.value)
        assertTrue(entry.title is OptionalText.Present)
    }

    @Test
    fun `state listing covers every actionable kind with availability`() {
        val child = folderChild("211", parent = "201", rank = 0)
        val parent = folder("201", children = listOf(child))
        val dock = dockItem("401")
        val widget = LockFixtures.widgetItem("501")
        val pair = LockFixtures.appPair("301", first = "311", second = "312")
        val listing = lockStateListing(state(listOf(child, parent, dock, widget) + pair))
        // Deterministic order: desktop cells first, then folder children, Dock,
        // app-pair members, tie-broken by item id inside each group.
        assertEquals(
            listOf("201", "301", "501", "211", "401", "311", "312"),
            listing.map { it.item.value },
        )
        assertTrue(listing.all { it.profileAvailable })
    }
}
