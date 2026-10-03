package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import app.lawnchair.organizer.application.public.PlanPreview
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RestorableRecoveryEntry
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
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
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ProposalExclusionKey
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #508: the coordinator's exclusion replan — base-derived replanning
 * (an exclusion plus its reversal returns to the base proposal), generation
 * gating for superseded async results, the sticky count-only prohibition,
 * Replanning confirm-blocking, cancel, empty diffs, and previewed-plan
 * confirmation. Gated replans run on worker threads, exactly like the
 * production `execute { }` dispatch.
 */
class ProposalExclusionCoordinatorTest {

    private val excludeApp = setOf(ProposalExclusionKey.Existing(ItemId("app")))

    private val initialPlan = Planned(
        placements = listOf(movedPlacement("app"), movedPlacement("other")),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

    /** The replanned proposal: the excluded member keeps its captured
     *  placement as `Preserved(NON_TARGET)`, the other member still moves —
     *  a real diff, never an empty one. */
    private val replannedPlan = Planned(
        placements = listOf(preservedPlacement("app"), movedPlacement("other")),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

    /** Two movable top-level apps: excluding one still leaves a real diff. */
    private fun baseInput() = OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("revision"),
            device = DeviceCapabilities(4, 5, 5, 3, 4, Orientation.PORTRAIT),
            pages = listOf(Page(PageId("page"), PageOrder(0))),
            items = listOf(appItem("app", GridCell(0, 0)), appItem("other", GridCell(1, 0))),
        ),
        rules = RuleSemantics(
            RuleVersion("v2"),
            FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
            app.lawnchair.organizer.planning.DockPolicy.PRESERVE,
            OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        ),
        taxonomy = TaxonomyContract(TaxonomyVersion("v1"), listOf(app.lawnchair.organizer.planning.CategoryId("other")), app.lawnchair.organizer.planning.CategoryId("other")),
        catalog = app.lawnchair.organizer.planning.ActiveCategoryCatalog(
            TaxonomyContract(TaxonomyVersion("v1"), listOf(app.lawnchair.organizer.planning.CategoryId("other")), app.lawnchair.organizer.planning.CategoryId("other")),
            emptyList(),
        ),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(
            listOf(
                ExistingTargetMembership(ItemId("app"), ExistingRole.Movable),
                ExistingTargetMembership(ItemId("other"), ExistingRole.Movable),
            ),
            emptyList(),
        ),
        runMode = RunMode.FullOrganization,
    )

    private fun appItem(id: String, cell: GridCell) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("$id/.Main"), ProfileId("0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("page")), cell, GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun composition(input: OrganizationInput) = OrganizationInputComposition.Ready(
        input = input,
        provenance = InputProvenance(
            revision = RevisionId("revision"),
            rules = PolicyInputIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, "v1", SHA),
            taxonomy = PolicyInputIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE, "v1", SHA),
            signals = PolicyInputIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS, "v1", SHA),
            targets = PolicyInputIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET, "v1", SHA),
            policyBundle = PolicyBundleIdentity("v1", SHA),
            layoutStrategySelection = PolicyInputIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION, "v1", SHA),
        ),
    )

    private fun planningResult(planned: Planned) = PlanningResult(
        revision = RevisionId("revision"),
        ruleVersion = RuleVersion("v2"),
        taxonomyVersion = TaxonomyVersion("v1"),
        organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        outcome = planned,
    )

    private fun movedPlacement(itemId: String) = PlannedPlacement(
        item = ItemId(itemId),
        disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
        target = app.lawnchair.organizer.planning.PlacementTarget.WorkspaceTarget(
            PageRef(PageId("page")),
            GridCell(0, 0),
            GridSpan(1, 1),
        ),
    )

    private fun preservedPlacement(itemId: String) = PlannedPlacement(
        item = ItemId(itemId),
        disposition = Disposition.Preserved(PreserveReason.NON_TARGET),
        target = app.lawnchair.organizer.planning.PlacementTarget.WorkspaceTarget(
            PageRef(PageId("page")),
            GridCell(0, 0),
            GridSpan(1, 1),
        ),
    )

    /** Initial call returns the base proposal; every replan call returns the
     *  exclusion-aware proposal, optionally gated on a latch. */
    private fun twoItemPlanner(
        replanStarted: CountDownLatch? = null,
        replanRelease: CountDownLatch? = null,
    ): OrganizationPlanner {
        val callCount = AtomicInteger()
        return OrganizationPlanner { input ->
            if (callCount.incrementAndGet() == 1) {
                planningResult(initialPlan)
            } else {
                replanStarted?.countDown()
                replanRelease?.await(5, TimeUnit.SECONDS)
                planningResult(replannedPlan)
            }
        }
    }

    @Test
    fun excludingAnExistingMemberDerivesFromTheBaseAndRepublishesThePreview() {
        val base = baseInput()
        val application = FakeApplication(composition(base))
        val plannerInputs = mutableListOf<OrganizationInput>()
        val planner = twoItemPlanner()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { input ->
                plannerInputs += input
                planner.plan(input)
            },
        )

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        runner.applyProposalExclusions(excludeApp)

        // The replan input is derived directly from the base: same revision
        // and snapshot, only the excluded member's role changed.
        assertEquals(2, plannerInputs.size)
        assertEquals(RevisionId("revision"), plannerInputs[1].snapshot.revision)
        assertEquals(base.snapshot, plannerInputs[1].snapshot)
        assertEquals(
            ExistingTargetMembership(ItemId("app"), ExistingRole.Preserved),
            plannerInputs[1].targets.existing.first { it.item.value == "app" },
        )
        assertEquals(
            ExistingTargetMembership(ItemId("other"), ExistingRole.Movable),
            plannerInputs[1].targets.existing.first { it.item.value == "other" },
        )
        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(excludeApp, preview.exclusions)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun restoringEveryExclusionReturnsToTheBaseProposal() {
        val base = baseInput()
        val application = FakeApplication(composition(base))
        val plannerInputs = mutableListOf<OrganizationInput>()
        val planner = twoItemPlanner()
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { input ->
                plannerInputs += input
                planner.plan(input)
            },
        )

        runner.start()
        runner.applyProposalExclusions(excludeApp)
        val excluded = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(excludeApp, excluded.exclusions)
        runner.applyProposalExclusions(emptySet())

        val restored = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(emptySet<ProposalExclusionKey>(), restored.exclusions)
        // The restore derives from the base again — identical target set.
        assertEquals(base.targets, plannerInputs.last().targets)
    }

    @Test
    fun aReplanningStateBlocksConfirmUntilTheReplanCompletes() {
        val application = FakeApplication(composition(baseInput()))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = ManualOrganizationRun(application, twoItemPlanner(started, release))

        runner.start()
        val worker = thread { runner.applyProposalExclusions(excludeApp) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(runner.state is ManualOrganizationRun.State.Replanning)

        // Confirm is structurally impossible while replanning.
        runner.confirm()
        assertEquals(0, application.applyCalls)
        assertTrue(runner.state is ManualOrganizationRun.State.Replanning)

        release.countDown()
        worker.join(5000)
        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(excludeApp, preview.exclusions)
    }

    @Test
    fun aSupersededReplanResultIsDiscardedZeroWrite() {
        val application = FakeApplication(composition(baseInput()))
        val started1 = CountDownLatch(1)
        val started2 = CountDownLatch(1)
        val release1 = CountDownLatch(1)
        val release2 = CountDownLatch(1)
        val callCount = AtomicInteger()
        val planner = OrganizationPlanner { _ ->
            when (callCount.incrementAndGet()) {
                1 -> planningResult(initialPlan)

                2 -> {
                    started1.countDown()
                    release1.await(5, TimeUnit.SECONDS)
                    planningResult(replannedPlan)
                }

                else -> {
                    started2.countDown()
                    release2.await(5, TimeUnit.SECONDS)
                    planningResult(replannedPlan)
                }
            }
        }
        val runner = ManualOrganizationRun(application, planner)

        runner.start()
        val worker1 = thread { runner.applyProposalExclusions(excludeApp) }
        assertTrue(started1.await(5, TimeUnit.SECONDS))
        // A newer request while the first replan is still computing. Wait for
        // ITS planner entry too — otherwise release1 could fire before the
        // generation-2 claim, and worker1's result would legitimately surface
        // (a test-thread scheduling race, not a coordinator behavior).
        val worker2 = thread { runner.applyProposalExclusions(emptySet()) }
        assertTrue(started2.await(5, TimeUnit.SECONDS))
        release1.countDown()
        worker1.join(5000)
        // The superseded generation-1 result must never surface.
        assertTrue(runner.state is ManualOrganizationRun.State.Replanning)

        release2.countDown()
        worker2.join(5000)
        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(emptySet<ProposalExclusionKey>(), preview.exclusions)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun environmentalFailureAfterAnExclusionStopsAtPreviewUnavailable() {
        val application = FakeApplication(composition(baseInput()))
        val runner = ManualOrganizationRun(application, twoItemPlanner())

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }

        runner.applyProposalExclusions(excludeApp)
        // Sticky prohibition: never a count-only confirmable preview.
        assertEquals(ManualOrganizationRun.State.PreviewUnavailable::class, runner.state::class)

        // Retry keeps the prohibition and the same derived input.
        runner.retryPlanPreview()
        assertEquals(ManualOrganizationRun.State.PreviewUnavailable::class, runner.state::class)

        application.inspectPlanOverride = null
        runner.retryPlanPreview()
        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(excludeApp, preview.exclusions)
    }

    @Test
    fun environmentalFailureAfterAFullRestoreStillStopsAtPreviewUnavailable() {
        val application = FakeApplication(composition(baseInput()))
        val runner = ManualOrganizationRun(application, twoItemPlanner())

        runner.start()
        runner.applyProposalExclusions(excludeApp)
        val excluded = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(excludeApp, excluded.exclusions)
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }

        // The exclusion set returns to empty, but the run changed it once —
        // the count-only fallback must stay unreachable (spec D-7).
        runner.applyProposalExclusions(emptySet())
        assertTrue(runner.state is ManualOrganizationRun.State.PreviewUnavailable)
        runner.confirm()
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun initialPreviewWithoutExclusionsKeepsTheCountOnlyFallback() {
        val application = FakeApplication(composition(baseInput()))
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(initialPlan) })

        runner.start()

        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertNull(preview.details)
        assertEquals(emptySet<ProposalExclusionKey>(), preview.exclusions)
    }

    @Test
    fun anEmptyDiffAfterExclusionEndsAsNoChanges() {
        val application = FakeApplication(composition(baseInput()))
        val callCount = AtomicInteger()
        val planner = OrganizationPlanner { _ ->
            if (callCount.incrementAndGet() == 1) {
                planningResult(initialPlan)
            } else {
                planningResult(Planned(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
            }
        }
        val runner = ManualOrganizationRun(application, planner)

        runner.start()
        runner.applyProposalExclusions(excludeApp)

        assertEquals(ManualOrganizationRun.State.NoChanges, runner.state)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun cancellingAReplanningRunIsZeroWriteAndSilencesTheWorker() {
        val application = FakeApplication(composition(baseInput()))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = ManualOrganizationRun(application, twoItemPlanner(started, release))

        runner.start()
        val worker = thread { runner.applyProposalExclusions(excludeApp) }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        runner.cancel()
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertEquals(0, application.applyCalls)

        // The worker's late completion must not resurrect the cancelled run.
        release.countDown()
        worker.join(5000)
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
    }

    @Test
    fun confirmingAReplannedPreviewAppliesThePreviewedPlanInstance() {
        val application = FakeApplication(composition(baseInput()))
        val runner = ManualOrganizationRun(application, twoItemPlanner())

        runner.start()
        runner.applyProposalExclusions(excludeApp)
        runner.confirm()

        assertEquals(1, application.applyCalls)
        assertEquals(0, application.materializeCalls)
        // The applied plan is the exact object the replan previewed.
        assertTrue(application.lastAppliedPlan === application.lastInspectedPlan)
    }

    private class FakeApplication(
        var composition: OrganizationInputComposition,
    ) : ManualOrganizationApplication {
        override val diagnostics = RecordingDiagnostics()
        var inspectPlanOverride: ((OrganizationInput, PlanningResult) -> PlanPreviewResult)? = null
        var applyCalls = 0
        var lastAppliedPlan: ValidatedLayoutPlan? = null
        var lastInspectedPlan: ValidatedLayoutPlan? = null
        var materializeCalls = 0

        override fun newRunId() = RunId(RUN_ID)

        override fun composeFullOrganization(): OrganizationInputComposition = composition

        override fun detectMissingAppCandidates(): CandidateDetectionResult = CandidateDetectionResult.Unavailable(
            DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

        override fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult {
            val plan = minimalPlan(input)
            lastInspectedPlan = plan
            return inspectPlanOverride?.invoke(input, result)
                ?: PlanPreviewResult.Previewed(
                    PlanPreview(
                        plan = plan,
                        details = planPreviewDetails(
                            changes = emptyList(),
                            counts = app.lawnchair.organizer.application.public.PreviewCounts(0, 0, 0, 0, emptyMap()),
                        ),
                    ),
                )
        }

        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result {
            materializeCalls++
            return OrganizationPlanMaterializer.Result.Ready(minimalPlan(input))
        }

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): OrganizationInputComposition = composition

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls++
            lastAppliedPlan = plan
            return ApplyResult.Applied(runId, RecoveryPointId(POINT_ID))
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            pointId,
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult = RecoveryResult.NotRestorable(pointId, app.lawnchair.organizer.application.public.RecoveryRejection.MISSING)

        override fun readDurableOrganizerStatus(): OrganizerDurableStatus = OrganizerDurableStatus.NEVER_ORGANIZED

        override fun readRestorableRecoveryEntry(): RestorableRecoveryEntry? = null

        override val readinessState: StateFlow<ReadinessGate.State> = kotlinx.coroutines.flow.MutableStateFlow(
            ReadinessGate.State.READY,
        )

        override fun inspectCapture(): CapturedSnapshot? = null

        override fun applyWithUndoReceipt(plan: ValidatedLayoutPlan, runId: RunId): Pair<ApplyResult, RevisionId?> = error("not reached in exclusion tests")

        override fun recover(request: RecoveryRequest): RecoveryResult = error("not reached in exclusion tests")
    }

    private class RecordingDiagnostics : DiagnosticsPort {
        val events = mutableListOf<RunEvent>()

        override fun emit(event: RunEvent) {
            events += event
        }

        override fun snapshot(): List<RunEvent> = events.toList()
    }

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

private fun minimalPlan(input: OrganizationInput): ValidatedLayoutPlan = ValidatedLayoutPlan(
    sourceRevision = input.snapshot.revision,
    sourceState = app.lawnchair.organizer.application.public.LayoutState(
        emptyList(),
        emptyList(),
        app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
        emptyList(),
    ),
    intendedState = app.lawnchair.organizer.application.public.LayoutState(
        emptyList(),
        emptyList(),
        app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
        emptyList(),
    ),
    actions = emptyList(),
    newPages = emptyList(),
    newFolders = emptyList(),
    ruleVersion = input.rules.version,
    taxonomyVersion = input.taxonomy.version,
)
