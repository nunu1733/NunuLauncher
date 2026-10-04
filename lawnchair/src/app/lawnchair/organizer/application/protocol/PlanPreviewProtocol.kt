package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.preview.PlanPreviewProjector
import app.lawnchair.organizer.application.public.PlanPreview
import app.lawnchair.organizer.application.public.PlanPreviewRejection
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.PlanPreviewUnavailable
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult

/**
 * Read-only, revision-bound plan preview (Issue #194).
 *
 * The protocol owns one short, non-blocking organizer lease only to capture
 * authoritative current state. It never creates a checkpoint, mutates a
 * recovery record, writes layout state, requests a model reload, transitions
 * lifecycle, or emits a diagnostic event. The returned preview carries the
 * materialized [PlanPreview.plan]; the coordinator applies that same object on
 * confirmation and the existing A2 exact preconditions stay the final gate.
 */
class PlanPreviewProtocol(
    private val writer: LayoutWriterPort,
    private val operationIds: OperationIdSource,
    private val faults: FaultInjector,
    private val mutex: RunMutexPort,
) {

    fun inspect(input: OrganizationInput, result: PlanningResult): PlanPreviewResult {
        val runId: RunId = operationIds.newRunId()
        if (!mutex.tryAcquire(runId)) return PlanPreviewResult.Concurrent
        return try {
            inspectWithRunMutex(input, result, runId)
        } finally {
            mutex.release(runId)
        }
    }

    private fun inspectWithRunMutex(
        input: OrganizationInput,
        result: PlanningResult,
        runId: RunId,
    ): PlanPreviewResult {
        if (faults.serializationContention()) return PlanPreviewResult.WriterBusy
        val lease = writer.tryAcquireLease(WriterKind.ORGANIZER, runId.value.hashCode().toLong())
            ?: return PlanPreviewResult.WriterBusy
        val capture = try {
            try {
                writer.captureCurrent(CaptureId("plan-preview"))
            } catch (_: RuntimeException) {
                return PlanPreviewResult.NotPlannable(PlanPreviewRejection.CAPTURE_FAILED)
            }
        } finally {
            lease.close()
        }
        if (capture.revision != input.snapshot.revision) return PlanPreviewResult.Stale

        val planned = result.outcome as? Planned
            ?: return PlanPreviewResult.NotPlannable(PlanPreviewRejection.OUTCOME_NOT_PLANNED)
        val materialized = OrganizationPlanMaterializer.materialize(input, result, capture.layoutState)
        val plan = (materialized as? OrganizationPlanMaterializer.Result.Ready)?.plan
            ?: return PlanPreviewResult.NotPlannable(PlanPreviewRejection.MATERIALIZATION_INVALID)
        val projection = PlanPreviewProjector.project(plan, planned)
        val details = (projection as? PlanPreviewProjector.Result.Ready)?.details
            ?: return PlanPreviewResult.NotPlannable(PlanPreviewRejection.MATERIALIZATION_INVALID)
        return PlanPreviewResult.Previewed(PlanPreview(plan = plan, details = details))
    }
}
