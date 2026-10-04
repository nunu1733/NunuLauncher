package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.SourceContextIdentity
import app.lawnchair.organizer.planning.Availability
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
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205 AC-11 (reconstruction parity): build → session → rebuild from
 * the same structural state yields identical authority fields; label/signal
 * changes (outside the structural digest) never change the view or the
 * digest; structural changes diverge or change the digest.
 */
class SessionExportReconstructorTest {

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun app(
        id: String,
        x: Int = 0,
        y: Int = 0,
        locked: Boolean = false,
    ): CapturedItem = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun widget(id: String): CapturedItem = app(id).copy(kind = ItemKind.APPWIDGET)

    private fun docked(id: String): CapturedItem = app(id).copy(placement = CapturedPlacement.Dock(0))

    private fun buildState(
        items: List<CapturedItem>,
        labels: Map<ItemId, String> = emptyMap(),
    ): Pair<app.lawnchair.organizer.personalization.BuiltExport, CanonicalStructuralInputs> {
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            device(),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val structural = CanonicalStructuralInputs(snapshot, targets, emptyMap<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>())
        val inputs = ExportInputs(
            snapshot = snapshot,
            targets = targets,
            userLabels = labels,
            nowEpochMs = 1_000L,
        )
        return ContextExportBuilder.build(inputs, PrivacyTier.EXTERNAL_WITH_LABELS, SequentialIdAllocator()) to structural
    }

    @Test
    fun rebuildFromTheSameStructuralStateMatchesAllAuthorityFields() {
        val (built, structural) = buildState(listOf(app("a"), app("b", x = 1), widget("w"), docked("d")))
        val rebuilt = SessionExportReconstructor.rebuild(built.session, structural)
        assertTrue(rebuilt is ReconstructionResult.Rebuilt)
        val view = (rebuilt as ReconstructionResult.Rebuilt).export
        assertEquals(built.export.exportId, view.exportId)
        assertEquals(built.export.tier, view.tier)
        assertEquals(built.export.grid, view.grid)
        assertEquals(built.export.items.size, view.items.size)
        val originalByRef = built.export.items.associateBy { it.ref }
        for (item in view.items) {
            val original = originalByRef.getValue(item.ref)
            assertEquals(original.role, item.role)
            assertEquals(original.mobility, item.mobility)
            assertEquals(original.fixReason, item.fixReason)
            assertEquals(original.pageAffinity, item.pageAffinity)
            assertEquals(original.regionAffinity, item.regionAffinity)
            assertEquals(original.category, item.category)
        }
        // Labels/usage live only in the original document.
        assertTrue(view.items.all { it.label == null && it.usage == null })
    }

    @Test
    fun labelOrSignalChangesLeaveTheStructuralDigestAndViewUnchanged() {
        val (withLabels, structural) = buildState(listOf(app("a")), labels = mapOf(ItemId("a") to "Some App"))
        val (withoutLabels, _) = buildState(listOf(app("a")), labels = emptyMap())
        assertEquals(
            SourceContextIdentity.digest(structural),
            withLabels.session.sourceContextDigest,
        )
        assertEquals(withLabels.session.sourceContextDigest, withoutLabels.session.sourceContextDigest)
        val rebuilt = SessionExportReconstructor.rebuild(withLabels.session, structural)
        assertTrue(rebuilt is ReconstructionResult.Rebuilt)
    }

    @Test
    fun movingLockingOrAddingItemsChangesTheStructuralDigest() {
        val (base, _) = buildState(listOf(app("a"), app("b", x = 1)))
        val (moved, _) = buildState(listOf(app("a"), app("b", x = 3)))
        val (locked, _) = buildState(listOf(app("a", locked = true), app("b", x = 1)))
        val (added, _) = buildState(listOf(app("a"), app("b", x = 1), app("c", x = 2)))
        val baseDigest = base.session.sourceContextDigest
        assertNotEquals(baseDigest, moved.session.sourceContextDigest)
        assertNotEquals(baseDigest, locked.session.sourceContextDigest)
        assertNotEquals(baseDigest, added.session.sourceContextDigest)
    }

    @Test
    fun removingASessionItemDivergesTheReconstruction() {
        val (built, _) = buildState(listOf(app("a"), app("b", x = 1)))
        val afterRemoval = buildState(listOf(app("a"))).second
        assertEquals(ReconstructionResult.Diverged, SessionExportReconstructor.rebuild(built.session, afterRemoval))
    }

    @Test
    fun addedItemsStayOutsideTheSessionRefUniverseButChangeTheDigest() {
        val (built, base) = buildState(listOf(app("a")))
        val (grown, grownStructural) = buildState(listOf(app("a"), app("b", x = 1)))
        val rebuilt = SessionExportReconstructor.rebuild(built.session, grownStructural)
        assertTrue(rebuilt is ReconstructionResult.Rebuilt)
        assertEquals(built.session.itemRefs.keys, (rebuilt as ReconstructionResult.Rebuilt).export.items.map { it.ref }.toSet())
        assertNotEquals(built.session.sourceContextDigest, grown.session.sourceContextDigest)
    }
}
