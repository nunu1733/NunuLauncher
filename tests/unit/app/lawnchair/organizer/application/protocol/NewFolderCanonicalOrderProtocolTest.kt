package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
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
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.NewFolder
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #164 AC-164-02: a full-organization plan that creates a new folder
 * whose allocated id byte-sorts mid-list must reach `APPLY_VERIFIED` (A8).
 *
 * The fake runs the opt-in `productionEquivalentCapture` mode: the writer side
 * materializes through the real production resolution seam with fixture
 * identity (max row id + 1, so the folder becomes id 10 among ids 1..9), and
 * the recapture side independently rebuilds state from persisted rows in
 * capture-side canonical `ItemId` byte order — mirroring `RowManifestCodec`
 * without reusing the writer-side seam and without echoing the write set's
 * intended state. Pre-fix this reproduces the device asymmetry (manifest
 * equal, item order divergent) and fails A7 into automatic recovery; after
 * the writer-side canonical finalization the run verifies.
 */
class NewFolderCanonicalOrderProtocolTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var faults: RecordingFaultInjector
    private lateinit var protocol: ApplyProtocol
    private lateinit var sourceState: LayoutState

    @Before
    fun setUp() {
        sourceState = sourceFixture()
        writer = FakeLayoutWriter(sourceState).also { it.productionEquivalentCapture = true }
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        faults = RecordingFaultInjector()
        protocol = ApplyProtocol(writer, store, FakeClock, FixedOperationIdSource(), faults, RunMutex())
    }

    @Test
    fun newFolderPlanReachesAppliedAfterCanonicalFinalization() {
        val result = protocol.apply(newFolderPlan())

        assertTrue(
            "Expected Applied (A8), got $result — pre-fix this run recovers via A7 VERIFICATION_FAILED",
            result is ApplyResult.Applied,
        )
        assertEquals(1, writer.reloadCount)
    }

    @Test
    fun genuineDbDriftAfterCommitStillFailsClosedAndRecovers() {
        writer.onReloadRequest = { requestNumber ->
            if (requestNumber == 1) {
                // Simulate genuine post-write DB drift: the committed rows no
                // longer match the intended state. The manifest is unchanged
                // (row identity is untouched), exactly like the device case.
                writer.mutatePersistedRowsForTest { rows ->
                    rows[0] = rows[0].copy(
                        item = rows[0].item.copy(
                            placement = PlacementState.Workspace(
                                page = ApplicationPageRef.PersistentPage(PageId("p0")),
                                cell = GridCell(3, 3),
                                span = GridSpan(1, 1),
                            ),
                        ),
                    )
                }
            }
        }

        val plan = newFolderPlan()
        val result = protocol.apply(plan)

        assertTrue("Expected automatic recovery, got $result", result is ApplyResult.Recovered)
        assertEquals(
            "The typed failure must remain VERIFICATION_FAILED",
            ApplyFailure.VERIFICATION_FAILED,
            (result as ApplyResult.Recovered).failure,
        )
        assertEquals("Recovery must restore the pre-apply state", plan.sourceState, writer.currentState())
        assertEquals("Initial and recovery reloads must both complete", 2, writer.reloadCount)
    }

    /**
     * Mirrors `OrganizationPlanMaterializer` output shape: existing items in
     * planner order (matching byte order for ids 1..9) with items 8 and 9
     * moved into the new folder, and the planned folder appended last. Fixture
     * identity allocates folder id 10 (max row id 9 + 1), which byte-sorts
     * between "1" and "2".
     */
    private fun newFolderPlan(): ValidatedLayoutPlan {
        val intendedItems = sourceState.items
            .map { item ->
                when ((item.ref as ApplicationItemRef.PersistentItem).itemId.value) {
                    "8" -> item.copy(placement = PlacementState.FolderChild(plannedFolderRef(), 0))
                    "9" -> item.copy(placement = PlacementState.FolderChild(plannedFolderRef(), 1))
                    else -> item
                }
            } + plannedFolderItem()
        val intendedState = LayoutState(
            pages = sourceState.pages,
            profiles = sourceState.profiles,
            deviceCapabilities = sourceState.deviceCapabilities,
            items = intendedItems,
        )
        val intendedByRef = intendedState.items.associateBy { it.ref }
        val actions = sourceState.items.map { source ->
            val intended = intendedByRef[source.ref]!!
            if (intended == source) {
                ApplyAction.Preserve(source.ref, source)
            } else {
                ApplyAction.Update(source.ref, source, intended)
            }
        } + ApplyAction.Insert(plannedFolderRef(), intendedByRef[plannedFolderRef()]!!)
        return ValidatedLayoutPlan(
            sourceRevision = RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = actions,
            newPages = emptyList(),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("personal"),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = app.lawnchair.organizer.planning.PageRef(PageId("p0")),
                        cell = GridCell(1, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("8"), ItemId("9")),
                ),
            ),
            ruleVersion = RuleVersion("v1"),
            taxonomyVersion = TaxonomyVersion("tv1"),
        )
    }

    private fun plannedFolderRef() = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0))

    private fun sourceFixture(): LayoutState {
        val base = CanonicalFixtures.state()
        return LayoutState(
            pages = base.pages,
            profiles = base.profiles,
            deviceCapabilities = base.deviceCapabilities,
            items = (1..9).map { i ->
                CanonicalFixtures.appItem(
                    itemId = i.toString(),
                    cell = GridCell(0, i - 1),
                    target = TargetKey.AppKey(
                        component = app.lawnchair.organizer.planning.ComponentKey("com.example.a$i/.Main"),
                        profile = ProfileId("personal"),
                    ),
                )
            },
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
