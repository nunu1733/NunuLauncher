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

    private class NotReadyApplication : ManualOrganizationApplication {
        override val diagnostics = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot() = emptyList<RunEvent>()
        }

        override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

        // Issue #228: detection unavailable keeps the legacy full flow.
        override fun detectMissingAppCandidates() = CandidateDetectionResult.Unavailable(
            DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

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

        override val readinessState: StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)

        private fun notReady() = OrganizationInputComposition.NotReady(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.CAPTURE_UNAVAILABLE),
            CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
        )

        private fun notReadyPreview(): app.lawnchair.organizer.application.public.PlanPreviewResult = app.lawnchair.organizer.application.public.PlanPreviewResult.WriterBusy
    }
}
