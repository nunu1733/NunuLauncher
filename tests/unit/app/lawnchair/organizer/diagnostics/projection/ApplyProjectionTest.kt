package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.ApplySummary
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AC-67-03, D-04–D-06: Apply result projection tests.
 */
class ApplyProjectionTest {

    private val runId = RunId("ab311234567890abcdef1234567890ab")
    private val pointId = RecoveryPointId("9c2e1234567890abcdef1234567890ab")

    @Test
    fun noChangesProjection() {
        val result = ApplyResult.NoChanges(runId)
        val event = ApplyProjection.project(result, 1L)
        assertEquals(PhaseCode.APPLY_NO_CHANGES, event.phase)
        assertNull(event.error)
        assertNull(event.applySummary)
    }

    @Test
    fun appliedProjection() {
        val result = ApplyResult.Applied(runId, pointId)
        val summary = ApplySummary(preserveActionCount = 23, updateActionCount = 61, insertActionCount = 0)
        val event = ApplyProjection.project(result, 2L, applyStage = ApplyStage.A8, applySummary = summary)
        assertEquals(PhaseCode.APPLY_VERIFIED, event.phase)
        assertEquals(ApplyStage.A8, event.applyStage)
        assertNotNull(event.applySummary)
        assertEquals(23, event.applySummary?.preserveActionCount)
    }

    @Test
    fun rejectedProjection() {
        val result = ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION)
        val event = ApplyProjection.project(result, 3L, applyStage = ApplyStage.A2)
        assertEquals(PhaseCode.APPLY_REJECTED, event.phase)
        assertEquals(ApplyStage.A2, event.applyStage)
        assertNotNull(event.error)
        assertEquals(ErrorFamily.PRE_WRITE_REJECTED, event.error?.family)
        assertEquals("STALE_REVISION", event.error?.code)
    }

    @Test
    fun checkpointRejectedProjection() {
        val result = ApplyResult.Rejected(runId, PreWriteRejection.CHECKPOINT_VALIDATE_FAILED)
        val event = ApplyProjection.project(result, 4L, applyStage = ApplyStage.A4)
        assertEquals(PhaseCode.CHECKPOINT_REJECTED, event.phase)
        assertEquals(ApplyStage.A4, event.applyStage)
        assertEquals(ErrorFamily.PRE_WRITE_REJECTED, event.error?.family)
        assertEquals("CHECKPOINT_VALIDATE_FAILED", event.error?.code)
    }

    @Test
    fun admissionBlockedProjection() {
        val result = ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_POINT_ADMISSION_BLOCKED)
        val event = ApplyProjection.project(result, 5L, applyStage = ApplyStage.A4)
        assertEquals(PhaseCode.CHECKPOINT_REJECTED, event.phase)
        assertEquals(ApplyStage.A4, event.applyStage)
        assertEquals(ErrorFamily.PRE_WRITE_REJECTED, event.error?.family)
        assertEquals("RECOVERY_POINT_ADMISSION_BLOCKED", event.error?.code)
    }

    @Test
    fun rolledBackProjection() {
        val result = ApplyResult.RolledBack(runId, ApplyFailure.WRITE_FAILED)
        val event = ApplyProjection.project(result, 5L, applyStage = ApplyStage.A6)
        assertEquals(PhaseCode.APPLY_ROLLED_BACK, event.phase)
        assertEquals(ApplyStage.A6, event.applyStage)
        assertEquals(ErrorFamily.APPLY_FAILURE, event.error?.family)
        assertEquals("WRITE_FAILED", event.error?.code)
    }

    @Test
    fun concurrentRunProjection() {
        val result = ApplyResult.ConcurrentRun
        val event = ApplyProjection.project(result, 6L)
        assertEquals(PhaseCode.CONCURRENT_RUN_REJECTED, event.phase)
        assertEquals(ErrorFamily.CONCURRENT, event.error?.family)
        assertEquals("CONCURRENT_RUN", event.error?.code)
    }

    @Test
    fun checkpointedProjection() {
        val event = ApplyProjection.projectCheckpointed(
            runId = runId.value,
            pointId = pointId.value,
            journalSequence = 7L,
        )
        assertEquals(PhaseCode.CHECKPOINTED, event.phase)
        assertEquals(ApplyStage.A4, event.applyStage)
        assertEquals(runId.value, event.runId)
        assertEquals(pointId.value, event.pointId)
    }

    @Test
    fun committedProjection() {
        val event = ApplyProjection.projectCommitted(
            runId = runId.value,
            pointId = pointId.value,
            journalSequence = 8L,
        )
        assertEquals(PhaseCode.APPLY_COMMITTED, event.phase)
        assertEquals(ApplyStage.A6, event.applyStage)
        assertEquals(pointId.value, event.pointId)
        assertEquals(runId.value, event.runId)
    }
}
