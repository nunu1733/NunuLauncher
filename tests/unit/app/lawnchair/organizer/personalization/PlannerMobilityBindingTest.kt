package app.lawnchair.organizer.personalization

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
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.determinePreservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Issue #204 AC-13 (plan.md P2-1 audit finding): the export mobility judgment
 * is bound to the planner. For every projection case, the export-side
 * FIXED/MOVABLE/CONDITIONAL judgment must agree with the planner's
 * `determinePreservation` feasibility on the same inputs (run-time role input
 * excluded, which is a documented projection boundary). A future planner
 * change that alters mobility semantics makes this test fail.
 */
class PlannerMobilityBindingTest {

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun snapshotOf(items: List<CapturedItem>): LayoutSnapshot = LayoutSnapshot(
        app.lawnchair.organizer.planning.RevisionId("rev"),
        device(),
        listOf(Page(PageId("p0"), PageOrder(0))),
        items,
    )

    private fun workspaceItem(
        id: String,
        kind: ItemKind = ItemKind.APPLICATION,
        locked: Boolean = false,
        availability: Availability = Availability.AVAILABLE,
        x: Int = 0,
        y: Int = 0,
    ) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = kind,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = availability,
    )

    private val cases: List<CapturedItem> = listOf(
        workspaceItem("plain"),
        workspaceItem("locked", locked = true),
        workspaceItem("unavailable", availability = Availability.DISABLED),
        workspaceItem("widget", kind = ItemKind.APPWIDGET),
        workspaceItem("customWidget", kind = ItemKind.CUSTOM_APPWIDGET),
        workspaceItem("appPair", kind = ItemKind.APP_PAIR),
        workspaceItem("legacy", kind = ItemKind.SHORTCUT_LEGACY),
        CapturedItem(
            id = ItemId("docked"),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.docked"), ProfileId("p0")),
            placement = CapturedPlacement.Dock(0),
            locked = false,
            availability = Availability.AVAILABLE,
        ),
        CapturedItem(
            id = ItemId("folderMember"),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.folderMember"), ProfileId("p0")),
            placement = CapturedPlacement.FolderMember(
                app.lawnchair.organizer.planning.FolderRef(app.lawnchair.organizer.planning.FolderId("f1")),
                0,
            ),
            locked = false,
            availability = Availability.AVAILABLE,
        ),
        CapturedItem(
            id = ItemId("pairMember"),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.pairMember"), ProfileId("p0")),
            placement = CapturedPlacement.AppPairMember(app.lawnchair.organizer.planning.AppPairRef(app.lawnchair.organizer.planning.AppPairId("pair0"))),
            locked = false,
            availability = Availability.AVAILABLE,
        ),
        workspaceItem("reservedOverlap", x = 1, y = 0),
    )

    @Test
    fun exportMobilityJudgmentAgreesWithPlannerFeasibilityForEveryCase() {
        // The reservation covers row 0 only: cases placed at y >= 1 are free
        // of it, so the MOVABLE and CONDITIONAL branches are actually reached.
        // The reservedOverlap fixture deliberately stays in row 0 so the
        // RESERVED_REGION cause is also bound against the planner.
        val reserved = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1))
        val snapshot = snapshotOf(
            cases.map { item ->
                if (item.id == ItemId("reservedOverlap")) return@map item
                val ws = item.placement as? CapturedPlacement.Workspace
                if (ws != null && ws.cell.y == 0) item.copy(placement = ws.copy(cell = GridCell(ws.cell.x, 2))) else item
            },
        ).copy(reservedWorkspaceRegions = listOf(reserved))
        val roles = snapshot.items.associate { it.id to ExistingRole.Movable }

        for (item in snapshot.items) {
            // Non-addressable kinds never become export items, so the mobility
            // predicate is only bound for the addressable role matrix.
            val addressable = item.kind is ItemKind.APPLICATION || item.kind is ItemKind.DEEP_SHORTCUT ||
                item.kind is ItemKind.FOLDER || item.kind is ItemKind.APPWIDGET || item.kind is ItemKind.CUSTOM_APPWIDGET
            if (!addressable) continue
            val (mobility, _) = projectMobility(item, snapshot)
            val plannerReason = determinePreservation(item, roles[item.id], snapshot.reservedWorkspaceRegions)
            when (mobility) {
                Mobility.FIXED -> assertNotEquals(
                    "export FIXED must correspond to a planner preserve reason: ${item.id}",
                    null,
                    plannerReason,
                )

                Mobility.MOVABLE -> assertEquals(
                    "export MOVABLE must be planner-feasible: ${item.id}",
                    null,
                    plannerReason,
                )

                Mobility.CONDITIONAL -> {
                    // Widgets: planner is null only under relocateWidgets; the
                    // export keeps this conditional on the strategy.
                    assertEquals(
                        "CONDITIONAL must be a widget kind: ${item.id}",
                        true,
                        item.kind is ItemKind.APPWIDGET || item.kind is ItemKind.CUSTOM_APPWIDGET,
                    )
                    assertNotEquals(
                        null,
                        determinePreservation(item, roles[item.id], snapshot.reservedWorkspaceRegions),
                    )
                    assertEquals(
                        null,
                        determinePreservation(item, roles[item.id], snapshot.reservedWorkspaceRegions, relocateWidgets = true),
                    )
                }

                Mobility.CANDIDATE -> throw AssertionError(
                    "projectMobility never yields CANDIDATE for captured items: ${item.id}",
                )
            }
        }
    }

    @Test
    fun reservedRegionCauseBindsToThePlannerReasonExplicitly() {
        val region = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1))
        val overlapping = workspaceItem("reservedOverlap", x = 1, y = 0)
        val snapshot = snapshotOf(listOf(overlapping)).copy(reservedWorkspaceRegions = listOf(region))
        val (mobility, fixReason) = projectMobility(overlapping, snapshot)
        assertEquals(Mobility.FIXED, mobility)
        assertEquals(FixReason.RESERVED_REGION, fixReason)
        val plannerReason = determinePreservation(overlapping, ExistingRole.Movable, snapshot.reservedWorkspaceRegions)
        assertEquals(app.lawnchair.organizer.planning.PreserveReason.RESERVED_REGION, plannerReason)
    }

    @Test
    fun rolePreservedItemsStayFixedInFullTargetComposition() {
        // Full-target composition: role Preserved → run-time NON_TARGET, still
        // FIXED at export level (cause projection stays FOLDER_MEMBER etc.).
        val member = workspaceItem("folderMember").copy(
            placement = CapturedPlacement.FolderMember(
                app.lawnchair.organizer.planning.FolderRef(app.lawnchair.organizer.planning.FolderId("f1")),
                0,
            ),
        )
        val snapshot = snapshotOf(listOf(member))
        val plannerReason = determinePreservation(
            member,
            ExistingRole.Preserved,
            snapshot.reservedWorkspaceRegions,
        )
        // Run-time reason precedes the structural one in a full-target run...
        assertEquals(app.lawnchair.organizer.planning.PreserveReason.NON_TARGET, plannerReason)
        // ...while the export underlying cause remains FOLDER_MEMBER.
        val (mobility, fixReason) = projectMobility(member, snapshot)
        assertEquals(Mobility.FIXED, mobility)
        assertEquals(FixReason.FOLDER_MEMBER, fixReason)
    }

    @Test
    fun exportBuilderMobilityMatchesTheExportPredicate() {
        val items = listOf(
            workspaceItem("plain"),
            workspaceItem("locked", locked = true),
            workspaceItem("widget", kind = ItemKind.APPWIDGET),
            CapturedItem(
                id = ItemId("docked"),
                profile = ProfileId("p0"),
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.docked"), ProfileId("p0")),
                placement = CapturedPlacement.Dock(0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val snapshot = snapshotOf(items)
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        val built = ContextExportBuilder.build(
            ExportInputs(snapshot = snapshot, targets = targets, nowEpochMs = 1L),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val byRef = built.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val byItemRef = built.export.items.associateBy { it.ref }
        for (item in snapshot.items) {
            val exported = byItemRef.getValue(byRef.getValue(item.id.value))
            val expected = projectMobility(item, snapshot)
            assertEquals(expected.first, exported.mobility)
            assertEquals(expected.second, exported.fixReason)
        }
    }
}
