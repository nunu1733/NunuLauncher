package app.lawnchair.organizer.application.contract

import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewEffect
import app.lawnchair.organizer.application.public.RecoveryPreviewRejection
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewSummary
import app.lawnchair.organizer.application.public.RecoveryPreviewUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public value contract for Issue #84's read-only recovery preview. */
class RecoveryPreviewContractTest {

    private val pointId = RecoveryPointId("22222222222222222222222222222222")

    @Test
    fun summaryContainsOnlyTheClosedSafeEffect() {
        val summary = RecoveryPreviewSummary()

        assertEquals(RecoveryPreviewEffect.RESTORE_SAVED_LAYOUT, summary.effect)
        assertTrue(summary.confirmationRequired)
        assertTrue(summary.conditionalOnCurrentRevision)
        val fieldNames = RecoveryPreviewSummary::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertTrue(fieldNames.containsAll(setOf("effect", "confirmationRequired", "conditionalOnCurrentRevision")))
        assertFalse(
            fieldNames.any { name ->
                name.contains("manifest", ignoreCase = true) ||
                    name.contains("payload", ignoreCase = true) ||
                    name.contains("digest", ignoreCase = true)
            },
        )
    }

    @Test
    fun previewRejectionsRemainClosedAndDoNotReuseMutationRejections() {
        assertEquals(
            setOf(
                RecoveryPreviewRejection.MISSING,
                RecoveryPreviewRejection.EXPIRED,
                RecoveryPreviewRejection.CORRUPT,
                RecoveryPreviewRejection.INCOMPATIBLE_VERSION,
                RecoveryPreviewRejection.ALREADY_RESTORED,
                RecoveryPreviewRejection.UNRESOLVED,
                RecoveryPreviewRejection.LOCK_STATE_UNAVAILABLE,
            ),
            RecoveryPreviewRejection.entries.toSet(),
        )
        assertEquals(
            setOf(
                RecoveryPreviewUnavailable.RECONCILIATION_PENDING,
                RecoveryPreviewUnavailable.RECOVERY_STORE_UNAVAILABLE,
            ),
            RecoveryPreviewUnavailable.entries.toSet(),
        )
    }

    @Test
    fun nonRestorableAndUnavailableValuesCarryOnlyPointAndClosedReason() {
        val rejected = RecoveryPreviewResult.NotRestorable(pointId, RecoveryPreviewRejection.EXPIRED)
        val unavailable = RecoveryPreviewResult.Unavailable(
            pointId,
            RecoveryPreviewUnavailable.RECOVERY_STORE_UNAVAILABLE,
        )

        assertEquals(pointId, rejected.pointId)
        assertEquals(RecoveryPreviewRejection.EXPIRED, rejected.reason)
        assertEquals(pointId, unavailable.pointId)
        assertEquals(RecoveryPreviewUnavailable.RECOVERY_STORE_UNAVAILABLE, unavailable.reason)
        assertFalse(rejected.toString().contains("RevisionId"))
        assertFalse(unavailable.toString().contains("RevisionId"))
    }
}
