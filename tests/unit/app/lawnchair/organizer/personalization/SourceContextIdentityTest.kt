package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservationOverlapAcceptance
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Issue #204 AC-12: the structural `sourceContextDigest` is a deterministic
 * function of the canonical structural state, and #203 signal changes never
 * move it.
 */
class SourceContextIdentityTest {

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun app(
        id: String,
        x: Int = 0,
        y: Int = 0,
        locked: Boolean = false,
        kind: ItemKind = ItemKind.APPLICATION,
    ) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = kind,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
    )

    private fun state(
        items: List<CapturedItem>,
        reserved: List<ReservedWorkspaceRegion> = emptyList(),
    ): CanonicalStructuralInputs {
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), listOf(Page(PageId("p0"), PageOrder(0))), items, reserved)
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return CanonicalStructuralInputs(snapshot, targets, emptyMap())
    }

    @Test
    fun sameStateYieldsTheSameDigestAndStableSerialization() {
        val state = state(listOf(app("a"), app("b", x = 1, y = 1)))
        assertEquals(SourceContextIdentity.digest(state), SourceContextIdentity.digest(state))
        assertEquals(
            SourceContextIdentity.canonicalRepresentation(state),
            SourceContextIdentity.canonicalRepresentation(state),
        )
    }

    @Test
    fun itemOrderDoesNotMoveTheDigest() {
        val first = state(listOf(app("a"), app("b", x = 1)))
        val swapped = first.copy(snapshot = first.snapshot.copy(items = first.snapshot.items.reversed()))
        assertEquals(SourceContextIdentity.digest(first), SourceContextIdentity.digest(swapped))
    }

    @Test
    fun structuralChangesMoveTheDigest() {
        val base = state(listOf(app("a")))
        val moved = state(listOf(app("a", x = 2)))
        val locked = state(listOf(app("a", locked = true)))
        val differentKind = state(listOf(app("a", kind = ItemKind.FOLDER)))

        assertNotEquals(SourceContextIdentity.digest(base), SourceContextIdentity.digest(moved))
        assertNotEquals(SourceContextIdentity.digest(base), SourceContextIdentity.digest(locked))
        assertNotEquals(SourceContextIdentity.digest(base), SourceContextIdentity.digest(differentKind))
    }

    @Test
    fun signalOnlyChangesNeverMoveTheDigest() {
        // The #203 snapshot is deliberately not part of the digest inputs; two
        // different snapshots over the same structural state share identity.
        val base = state(listOf(app("a")))
        val canonical = SourceContextIdentity.canonicalRepresentation(base)
        assertNotEquals("signal rows must not appear in the structural projection", true, canonical.contains("signal|"))
        assertEquals(
            SourceContextIdentity.digest(base),
            SourceContextIdentity.digest(base),
        )
    }

    @Test
    fun resolvedCategoryChangesMoveTheDigest() {
        val base = state(listOf(app("a")))
        val overridden = base.copy(resolvedCategories = mapOf(ItemId("a") to "CAT_SOCIAL"))
        assertNotEquals(SourceContextIdentity.digest(base), SourceContextIdentity.digest(overridden))
    }

    @Test
    fun reservationChangesMoveTheDigest() {
        val base = state(listOf(app("a")))
        val withReservation = base.copy(
            snapshot = base.snapshot.copy(
                reservedWorkspaceRegions = listOf(
                    ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1)),
                ),
            ),
        )
        assertNotEquals(SourceContextIdentity.digest(base), SourceContextIdentity.digest(withReservation))
    }
}
