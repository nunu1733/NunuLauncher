package app.lawnchair.organizer.integration

import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.ClassificationPolicy
import app.lawnchair.organizer.rules.OrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #136 regression: an ordinary default launcher layout — Dock entries, one
 * workspace folder with members, and a second page — must compose through the
 * production mapping seam into planner-valid full-organization input. Before the
 * fix the composed input collapsed into a typed zero-placement rejection with
 * overlapping-placement, broken-reference, and item/target-mismatch counts.
 *
 * Dock ranks here are distinct because they model what canonical capture must
 * hand over; the capture-side slot authority itself is covered by the Issue #136
 * instrumentation evidence.
 */
class DefaultLayoutComposerPlannerRegressionTest {
    @Test
    fun defaultFolderDockAndTwoPageLayoutComposesIntoPlannerValidInput() {
        val composition = composer(defaultLayoutState()).composeFullOrganization()
        assertTrue("composition must be ready: $composition", composition is OrganizationInputComposition.Ready)
        val ready = composition as OrganizationInputComposition.Ready

        val partition = ready.input.targets.existing
        assertEquals(15, partition.size)
        assertEquals(partition.map { it.item }.distinct().size, 15)

        val items = ready.input.snapshot.items.associateBy { it.id }
        val folderId = ItemId("40")
        val memberIds = (41..47).map { ItemId(it.toString()) }
        val dockIds = (50..53).map { ItemId(it.toString()) }
        val pageOneIds = listOf(ItemId("60"), ItemId("61"), ItemId("62"))

        // full-target-v1 precedence: dock and structural members are preserved,
        // unlocked available top-level applications/folders are movable.
        memberIds.forEach { memberId ->
            assertEquals(ExistingRole.Preserved, partition.single { it.item == memberId }.role)
        }
        dockIds.forEach { dockId ->
            assertEquals(ExistingRole.Preserved, partition.single { it.item == dockId }.role)
        }
        (listOf(folderId) + pageOneIds).forEach { movableId ->
            assertEquals(ExistingRole.Movable, partition.single { it.item == movableId }.role)
        }

        val planned = DeterministicOrganizationPlanner().plan(ready.input)
        assertTrue(
            "planner must accept the ordinary default layout, got: ${planned.outcome}",
            planned.outcome is Planned,
        )

        // Only the FOLDER item carries its own container identity; members stay
        // linked exclusively through their placement and the parent member list.
        val folder = items.getValue(folderId)
        assertEquals(ItemKind.FOLDER, folder.kind)
        assertEquals(FolderId(folderId.value), folder.folderId)
        assertEquals(memberIds.sorted(), folder.members.sorted())
        assertNull((folder.placement as? CapturedPlacement.FolderMember))
        memberIds.forEach { memberId ->
            val member = items.getValue(memberId)
            assertEquals(ItemKind.APPLICATION, member.kind)
            assertNull(member.folderId)
            val placement = member.placement as CapturedPlacement.FolderMember
            assertEquals(FolderId(folderId.value), placement.folder.folderId)
        }

        // full-target-v1 precedence checks live above the planner assertion so a
        // regression surfaces the typed rejection itself first.

        dockIds.forEachIndexed { index, dockId ->
            val rank = (items.getValue(dockId).placement as CapturedPlacement.Dock).rank
            assertEquals(index, rank)
        }

        val placements = (planned.outcome as Planned).placements
        assertEquals(15, placements.size)
        dockIds.forEachIndexed { index, dockId ->
            val placement = placements.single { it.item == dockId }
            assertTrue(placement.disposition is Disposition.Preserved)
            val target = placement.target as PlacementTarget.Dock
            assertEquals(index, target.rank)
        }
    }

    /** 4 Dock apps, one 7-member workspace folder on page 0, three apps on page 1. */
    private fun defaultLayoutState(): LayoutState = CanonicalFixtures.state(
        pages = listOf(
            PageState(ApplicationPageRef.PersistentPage(PageId("0")), PageOrder(0)),
            PageState(ApplicationPageRef.PersistentPage(PageId("1")), PageOrder(1)),
        ),
        items = buildList {
            add(
                CanonicalFixtures.appItem(
                    itemId = "40",
                    page = ApplicationPageRef.PersistentPage(PageId("0")),
                    kind = CanonicalItemKind.Folder,
                    target = TargetKey.FolderKey(FolderId("40")),
                    cell = GridCell(0, 4),
                    structure = StructureState.FolderMembers(
                        (41..47).mapIndexed { rank, id ->
                            RankedMember(ApplicationItemRef.PersistentItem(ItemId(id.toString())), rank)
                        },
                    ),
                ),
            )
            (41..47).forEachIndexed { rank, id -> add(member(id.toString(), rank)) }
            (50..53).forEachIndexed { rank, id -> add(dockApp(id.toString(), rank)) }
            add(pageOneApp("60", GridCell(0, 4)))
            add(pageOneApp("61", GridCell(1, 4)))
            add(pageOneApp("62", GridCell(3, 4)))
        },
    )

    private fun member(
        id: String,
        rank: Int,
    ) = CanonicalFixtures.appItem(
        itemId = id,
        target = appTarget(),
    ).copy(placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("40")), rank))

    private fun dockApp(
        id: String,
        rank: Int,
    ) = CanonicalFixtures.appItem(
        itemId = id,
        target = appTarget(),
    ).copy(placement = PlacementState.Dock(rank))

    private fun pageOneApp(
        id: String,
        cell: GridCell,
    ) = CanonicalFixtures.appItem(
        itemId = id,
        page = ApplicationPageRef.PersistentPage(PageId("1")),
        cell = cell,
        target = appTarget(),
    )

    private fun appTarget() = TargetKey.AppKey(ComponentKey("com.example.default/.Main"), ProfileId("personal"))

    private fun composer(state: LayoutState) = DefaultOrganizationInputComposer(
        captureSource = CanonicalCaptureSource {
            CanonicalCaptureReadResult.Ready(FakeLayoutWriter(state).captureCurrent(CaptureId("issue136")))
        },
        bundleSource = object : OrganizerPolicyBundleSource {
            override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
        },
        overrides = object : CategoryOverrideSnapshotSource {
            override fun read(capturedProfiles: Set<ProfileId>) = OverrideSnapshotReadResult.Ready(emptyOverrideSnapshot())
        },
        platformEvidence = object : ClassificationSignalSnapshotSource {
            override fun read(
                requests: List<ClassificationEvidenceRequest>,
                policy: ClassificationPolicy,
            ) = PlatformEvidenceReadResult.Ready(emptyEvidence())
        },
    )

    private fun emptyOverrideSnapshot() = CategoryOverrideSnapshot(
        schemaVersion = 1,
        generation = 0L,
        assignments = emptyMap(),
        identity = PolicyInputIdentity(
            PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
            "schema-1-generation-0",
            "a".repeat(64),
        ),
    )

    private fun emptyEvidence() = PlatformClassificationEvidence(
        s2 = emptyMap(),
        s5 = emptyMap(),
        identity = PolicyInputIdentity(
            PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE,
            "platform-evidence-v1",
            "b".repeat(64),
        ),
    )
}
