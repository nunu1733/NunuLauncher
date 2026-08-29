package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.actions.NewFolderPlanFixtures
import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.PageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #164 AC-164-02: a full-organization plan produced by the real
 * `OrganizationPlanMaterializer` (via [NewFolderPlanFixtures]) that creates a
 * new folder whose allocated id byte-sorts mid-list must reach
 * `APPLY_VERIFIED` (A8).
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
    private lateinit var plan: ValidatedLayoutPlan

    @Before
    fun setUp() {
        val fixture = NewFolderPlanFixtures.singleFolder()
        plan = NewFolderPlanFixtures.materializeReady(fixture)
        writer = FakeLayoutWriter(fixture.sourceState).also { it.productionEquivalentCapture = true }
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        faults = RecordingFaultInjector()
        protocol = ApplyProtocol(writer, store, FakeClock, FixedOperationIdSource(), faults, RunMutex())
    }

    @Test
    fun newFolderPlanReachesAppliedAfterCanonicalFinalization() {
        val result = protocol.apply(plan)

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
}
