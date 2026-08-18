package app.lawnchair.organizer.diagnostics.projection

import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AC-67-03, D-08: Recovery result projection tests.
 */
class RecoveryProjectionTest {

    private val pointId = RecoveryPointId("9c2e1234567890abcdef1234567890ab")

    @Test
    fun restoredProjection() {
        val result = RecoveryResult.Restored(pointId)
        val event = RecoveryProjection.project(result, 1L, pointId = pointId.value)
        assertEquals(PhaseCode.RECOVERY_RESTORED, event.phase)
        assertNotNull(event.recovery)
        assertEquals(pointId.value, event.recovery?.pointId)
    }

    @Test
    fun notRestorableProjection() {
        val result = RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING)
        val event = RecoveryProjection.project(result, 2L, pointId = pointId.value)
        assertEquals(PhaseCode.RECOVERY_REJECTED, event.phase)
        assertEquals(ErrorFamily.RECOVERY_REJECTION, event.error?.family)
        assertEquals("MISSING", event.error?.code)
    }

    @Test
    fun restoreFailedProjection() {
        val result = RecoveryResult.RestoreFailed(
            pointId,
            RecoveryFailure.WRITE_FAILED,
            AuthoritativeState.UNKNOWN,
        )
        val event = RecoveryProjection.project(result, 3L, pointId = pointId.value)
        assertEquals(PhaseCode.RECOVERY_FAILED, event.phase)
        assertEquals(ErrorFamily.RECOVERY_FAILURE, event.error?.family)
        assertEquals("WRITE_FAILED", event.error?.code)
    }

    @Test
    fun writerBusyProjection() {
        val result = RecoveryResult.WriterBusy
        val event = RecoveryProjection.project(result, 4L)
        assertEquals(PhaseCode.RECOVERY_WRITER_BUSY, event.phase)
        assertEquals(ErrorFamily.WRITER_BUSY, event.error?.family)
    }

    @Test
    fun concurrentRunProjection() {
        val result = RecoveryResult.ConcurrentRun
        val event = RecoveryProjection.project(result, 5L)
        assertEquals(PhaseCode.RECOVERY_CONCURRENT, event.phase)
        assertEquals(ErrorFamily.CONCURRENT, event.error?.family)
    }

    @Test
    fun requestedProjection() {
        val event = RecoveryProjection.projectRequested(
            pointId = pointId.value,
            pointOriginRunId = "5f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c",
            journalSequence = 6L,
        )
        assertEquals(PhaseCode.RECOVERY_REQUESTED, event.phase)
        assertEquals(pointId.value, event.pointId)
        assertNotNull(event.recovery)
        assertEquals(pointId.value, event.recovery?.pointId)
        assertEquals("5f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c", event.recovery?.pointOriginRunId)
    }
}
