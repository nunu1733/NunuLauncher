package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.CaptureFailureCategory
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlanningResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-source support for constructing a `ManualOrganizationRun` double from
 * other unit tests (issue #205 holder race tests). The application double is
 * permanently NotReady and the planner must never run, so the run object is
 * inert: only the transport/cancel lifecycle under test touches it — and a
 * bug would surface as the planner's error, not a silent pass.
 */
object ManualOrganizationRunTestSupport {

    fun newRun(): ManualOrganizationRun = ManualOrganizationRun(
        application = NotReadyApplication(),
        planner = OrganizationPlanner { error("planner must not run") },
    )

    /**
     * Issue #417: a run double whose detection is READY (one stable
     * candidate), so a test can drive it to the frozen scope
     * (`State.ScopeConfirmed`) via `start()` + `confirmSelection(...)` — the
     * state the scoped exchange generation is claimed from. The composition
     * stays permanently NotReady (the planner must never run), so any
     * composed-phase continuation surfaces as the typed InputUnavailable
     * state instead of a silent pass.
     */
    fun newReadyDetectionRun(): ManualOrganizationRun = ManualOrganizationRun(
        application = ReadyDetectionApplication(),
        planner = OrganizationPlanner { error("planner must not run") },
    )

    /** The stable candidate of [ReadyDetectionApplication]'s detection cut. */
    val readyDetectionCandidate: CandidateTarget.AppKey = CandidateTarget.AppKey(
        app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
        app.lawnchair.organizer.planning.ProfileId("personal"),
    )

    private open class BaseTestApplication : ManualOrganizationApplication {
        override val diagnostics = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot() = emptyList<RunEvent>()
        }

        override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

        override fun composeFullOrganization(): OrganizationInputComposition = notReady()

        override fun composeScopeComposedOrganization(selection: List<CandidateTarget.AppKey>): OrganizationInputComposition = notReady()

        override fun inspectPlan(input: OrganizationInput, result: PlanningResult) = notReadyPreview()

        override fun materialize(
            input: OrganizationInput,
            result: PlanningResult,
        ) = error("not reached in exchange holder tests")

        override fun apply(
            plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
            runId: RunId,
        ) = error("not reached in exchange holder tests")

        override fun inspectRecovery(pointId: app.lawnchair.organizer.application.public.RecoveryPointId) = error("not reached in exchange holder tests")

        override fun confirmRecovery(
            pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
            confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
        ) = error("not reached in exchange holder tests")

        override fun readDurableOrganizerStatus() = error("not reached in exchange holder tests")

        override fun readRestorableRecoveryEntry(): app.lawnchair.organizer.application.public.RestorableRecoveryEntry? = error("not reached in exchange holder tests")

        // Issue #417 (spec 417): the detection cut became an application port
        // member; the base default stays unavailable so only tests that
        // opt into a cut (NotReadyApplication/ReadyDetectionApplication)
        // observe one.
        override fun detectMissingAppCandidates(): CandidateDetectionResult = CandidateDetectionResult.Unavailable(
            DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

        override val readinessState: StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)

        protected fun notReady() = OrganizationInputComposition.NotReady(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
            CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
        )

        private fun notReadyPreview(): app.lawnchair.organizer.application.public.PlanPreviewResult = app.lawnchair.organizer.application.public.PlanPreviewResult.WriterBusy
    }

    private class NotReadyApplication : BaseTestApplication() {
        // Issue #228: detection unavailable keeps the legacy full flow.
        override fun detectMissingAppCandidates() = CandidateDetectionResult.Unavailable(
            DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )
    }

    private class ReadyDetectionApplication : BaseTestApplication() {
        override fun detectMissingAppCandidates() = CandidateDetectionResult.Ready(
            listOf(
                app.lawnchair.organizer.integration.DetectedCandidate(
                    target = readyDetectionCandidate,
                    label = "c1",
                    availability = app.lawnchair.organizer.planning.Availability.AVAILABLE,
                ),
            ),
        )
    }
}
