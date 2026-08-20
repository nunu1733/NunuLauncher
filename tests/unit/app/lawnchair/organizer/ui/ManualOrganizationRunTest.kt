package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.DeviceCapabilities as PlannerDeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
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
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualOrganizationRunTest {
    @Test
    fun unavailableInputStopsBeforePlannerOrApplicationWrite() {
        val application = FakeApplication(
            composition = OrganizationInputComposition.NotReady(
                InputReadinessReason.InvalidCanonicalCapture(
                    app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                ),
                CompositionDiagnostic("capture-unavailable"),
            ),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
        assertEquals(1, application.events.size)
        assertEquals(app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED, application.events.single().phase)
    }

    @Test
    fun emptyPlanDoesNotMaterializeOrApply() {
        val application = FakeApplication(readyInput())
        val planner = OrganizationPlanner { planningResult(Planned(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) }
        val runner = ManualOrganizationRun(application, planner)

        runner.start()

        assertEquals(ManualOrganizationRun.State.NoChanges, runner.state)
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun confirmedPreviewCarriesOneRunIdIntoApplyAndManualDiagnostics() {
        val application = FakeApplication(readyInput())
        val planned = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = app.lawnchair.organizer.planning.ItemId("item"),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(PageRef(PageId("page")), app.lawnchair.organizer.planning.GridCell(0, 0), app.lawnchair.organizer.planning.GridSpan(1, 1)),
                ),
            ),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = emptyList(),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(planned) })

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        runner.confirm()

        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
        assertEquals(1, application.materializeCalls)
        assertEquals(1, application.applyCalls)
        assertEquals(RUN_ID, application.appliedRunId?.value)
        assertEquals(
            setOf(RUN_ID),
            application.events.mapNotNull { it.runId }.toSet(),
        )
        assertEquals(
            listOf(
                app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.CAPTURED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.PLANNED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.PREVIEWED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.USER_CONFIRMED,
            ),
            application.events.map { it.phase },
        )
    }

    private fun readyInput() = OrganizationInputComposition.Ready(
        input = input(),
        provenance = InputProvenance(
            revision = RevisionId("revision"),
            rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
            targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
            policyBundle = PolicyBundleIdentity("v1", SHA_256),
        ),
    )

    private fun policyIdentity(source: PolicySourceKind) = PolicyInputIdentity(source, "v1", SHA_256)

    private fun input() = OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("revision"),
            device = PlannerDeviceCapabilities(4, 5, 5, 3, 4, Orientation.PORTRAIT),
            pages = listOf(Page(PageId("page"), PageOrder(0))),
            items = emptyList(),
        ),
        rules = RuleSemantics(
            RuleVersion("v1"),
            FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
            DockPolicy.PRESERVE,
            OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            app.lawnchair.organizer.planning.OrderingPolicy.CANONICAL_V1,
        ),
        taxonomy = TaxonomyContract(TaxonomyVersion("v1"), listOf(app.lawnchair.organizer.planning.CategoryId("other")), app.lawnchair.organizer.planning.CategoryId("other")),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(emptyList(), emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun planningResult(outcome: app.lawnchair.organizer.planning.PlanningOutcome) = PlanningResult(
        revision = RevisionId("revision"),
        ruleVersion = RuleVersion("v1"),
        taxonomyVersion = TaxonomyVersion("v1"),
        outcome = outcome,
    )

    private class FakeApplication(
        var composition: OrganizationInputComposition,
    ) : ManualOrganizationApplication {
        override val diagnostics = RecordingDiagnostics()
        val events: List<RunEvent>
            get() = diagnostics.events
        var materializeCalls = 0
        var applyCalls = 0
        var appliedRunId: RunId? = null

        override fun newRunId() = RunId(RUN_ID)
        override fun composeFullOrganization() = composition
        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result {
            materializeCalls++
            return OrganizationPlanMaterializer.Result.Ready(
                ValidatedLayoutPlan(
                    sourceRevision = input.snapshot.revision,
                    sourceState = LayoutState(emptyList(), emptyList(), DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT), emptyList()),
                    intendedState = LayoutState(emptyList(), emptyList(), DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT), emptyList()),
                    actions = emptyList(),
                    newPages = emptyList(),
                    newFolders = emptyList(),
                    ruleVersion = input.rules.version,
                    taxonomyVersion = input.taxonomy.version,
                ),
            )
        }

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls++
            appliedRunId = runId
            return ApplyResult.Applied(runId, RecoveryPointId(POINT_ID))
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            pointId,
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult = RecoveryResult.NotRestorable(pointId, app.lawnchair.organizer.application.public.RecoveryRejection.MISSING)
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
        const val SHA_256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
