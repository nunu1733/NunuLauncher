package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.public.PlanPreviewRejection
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities as PlannerDeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrderingPolicy
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.Rejected
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #194 read-only plan-preview contract.
 *
 * Every test asserts that inspection does not write, checkpoint, reload,
 * transition lifecycle, or emit diagnostics.
 */
class PlanPreviewProtocolTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var faults: RecordingFaultInjector
    private lateinit var mutex: RunMutex
    private lateinit var protocol: PlanPreviewProtocol

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        faults = RecordingFaultInjector()
        mutex = RunMutex()
        protocol = PlanPreviewProtocol(
            writer,
            FixedOperationIdSource(),
            faults,
            mutex,
            app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
        )
    }

    @Test
    fun revisionMatchReturnsPreviewedWithoutAnyMutation() {
        val fixture = consistentFixture()

        val result = protocol.inspect(fixture.input, fixture.result)

        assertTrue("Expected Previewed, got $result", result is PlanPreviewResult.Previewed)
        assertEquals(1, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
        assertEquals(0, writer.recaptureCount)
    }

    @Test
    fun identicalInputsProduceIdenticalPreviews() {
        val fixture = consistentFixture()

        val first = protocol.inspect(fixture.input, fixture.result)
        val second = protocol.inspect(fixture.input, fixture.result)

        assertEquals(first, second)
    }

    @Test
    fun revisionMismatchReturnsStaleWithoutMutation() {
        val fixture = consistentFixture()
        writer.setCurrentState(
            CanonicalFixtures.state(
                items = listOf(CanonicalFixtures.appItem(cell = GridCell(1, 1))),
            ),
        )

        val result = protocol.inspect(fixture.input, fixture.result)

        assertEquals(PlanPreviewResult.Stale, result)
    }

    @Test
    fun rejectedOutcomeReturnsNotPlannableWithoutMutation() {
        val fixture = consistentFixture()
        val rejected = fixture.result.copy(
            outcome = Rejected.Invalid(emptyList(), emptyList()),
        )

        val result = protocol.inspect(fixture.input, rejected)

        assertEquals(
            PlanPreviewResult.NotPlannable(PlanPreviewRejection.OUTCOME_NOT_PLANNED),
            result,
        )
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
    }

    @Test
    fun captureFailureReturnsTypedRejectionWithoutMutation() {
        val fixture = consistentFixture()
        val failing = PlanPreviewProtocol(
            ThrowingCaptureWriter(writer),
            FixedOperationIdSource(),
            faults,
            mutex,
            app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
        )

        val result = failing.inspect(fixture.input, fixture.result)

        assertEquals(
            PlanPreviewResult.NotPlannable(PlanPreviewRejection.CAPTURE_FAILED),
            result,
        )
        assertEquals(0, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
    }

    @Test
    fun materializerRejectionReturnsTypedRejectionWithoutMutation() {
        val fixture = consistentFixture()
        val incompatibleResult = fixture.result.copy(ruleVersion = RuleVersion("v9"))

        val result = protocol.inspect(fixture.input, incompatibleResult)

        assertEquals(
            PlanPreviewResult.NotPlannable(PlanPreviewRejection.MATERIALIZATION_INVALID),
            result,
        )
        assertEquals(1, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
    }

    @Test
    fun serializationContentionReturnsWriterBusyBeforeCapture() {
        faults.serializationContention = true
        val fixture = consistentFixture()

        val result = protocol.inspect(fixture.input, fixture.result)

        assertEquals(PlanPreviewResult.WriterBusy, result)
        assertEquals(0, writer.capturedSnapshots)
    }

    @Test
    fun refusedLeaseReturnsWriterBusyWithoutCapture() {
        writer.refuseLease = true
        val fixture = consistentFixture()

        val result = protocol.inspect(fixture.input, fixture.result)

        assertEquals(PlanPreviewResult.WriterBusy, result)
        assertEquals(0, writer.capturedSnapshots)
    }

    @Test
    fun heldMutexReturnsConcurrentWithoutCapture() {
        val fixture = consistentFixture()
        val blockingRunId = RunId("33333333333333333333333333333333")
        assertTrue(mutex.tryAcquire(blockingRunId))

        val result = protocol.inspect(fixture.input, fixture.result)

        assertEquals(PlanPreviewResult.Concurrent, result)
        assertEquals(0, writer.capturedSnapshots)
        mutex.release(blockingRunId)
    }

    /**
     * Builds a snapshot whose revision equals the fake writer's current
     * canonical state and whose single captured item matches the planner's
     * preserve-in-place decision, so the materializer produces joined
     * Preserve-only actions and the protocol reaches `Previewed`.
     */
    private fun consistentFixture(): Fixture {
        val pageId = PageId("p0")
        val cell = GridCell(0, 0)
        val span = GridSpan(1, 1)
        val itemId = ItemId("app.a")
        val revision = RevisionCalculator.revisionOf(
            CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())),
        )
        val snapshot = LayoutSnapshot(
            revision = revision,
            device = PlannerDeviceCapabilities(4, 5, 5, 3, 4, Orientation.PORTRAIT),
            pages = listOf(Page(pageId, PageOrder(0))),
            items = listOf(
                CapturedItem(
                    id = itemId,
                    profile = ProfileId("personal"),
                    kind = ItemKind.APPLICATION,
                    target = TargetKey.AppKey(ComponentKey("com.example.a/.Main"), ProfileId("personal")),
                    placement = CapturedPlacement.Workspace(PageRef(pageId), cell, span),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
            ),
        )
        val input = OrganizationInput(
            snapshot = snapshot,
            rules = RuleSemantics(
                RuleVersion("v1"),
                FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                DockPolicy.PRESERVE,
                OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                OrderingPolicy.CANONICAL_V1,
            ),
            taxonomy = TaxonomyContract(
                TaxonomyVersion("v1"),
                listOf(app.lawnchair.organizer.planning.CategoryId("other")),
                app.lawnchair.organizer.planning.CategoryId("other"),
            ),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(
                existing = listOf(ExistingTargetMembership(itemId, ExistingRole.Movable)),
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        val result = PlanningResult(
            revision = revision,
            ruleVersion = RuleVersion("v1"),
            taxonomyVersion = TaxonomyVersion("v1"),
            outcome = Planned(
                placements = listOf(
                    PlannedPlacement(
                        item = itemId,
                        disposition = Disposition.Preserved(PreserveReason.ALREADY_CANONICAL),
                        target = PlacementTarget.WorkspaceTarget(PageRef(pageId), cell, span),
                    ),
                ),
                newPages = emptyList(),
                newFolders = emptyList(),
                categories = emptyList(),
                warnings = emptyList(),
            ),
        )
        return Fixture(input, result)
    }

    /** Delegating writer whose authoritative capture always fails. */
    private class ThrowingCaptureWriter(
        private val delegate: LayoutWriterPort,
    ) : LayoutWriterPort by delegate {
        override fun captureCurrent(captureId: CaptureId): CapturedSnapshot = throw IllegalStateException("capture failure")
    }

    private class Fixture(
        val input: OrganizationInput,
        val result: PlanningResult,
    )
}
