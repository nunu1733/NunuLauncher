package app.lawnchair.organizer.application.contract

import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-13: every apply/recovery result variant is constructible exactly as
 * spec.md §“Results” fixes it; no leaked platform types; no undocumented
 * variant.
 *
 * Issue #14 Stage B step 1.
 */
class ApplyResultContractTest {

    @Test
    fun runIdRejectsNonHexOrWrongLength() {
        assertThrows(IllegalArgumentException::class.java) { RunId("") }
        assertThrows(IllegalArgumentException::class.java) { RunId("ABCDEF0123456789abcdef0123456789") }
        assertThrows(IllegalArgumentException::class.java) { RunId("deadbeef") }
        assertThrows(IllegalArgumentException::class.java) { RunId("z".repeat(32)) }
    }

    @Test
    fun runIdAcceptsCanonicalLowercaseHex() {
        val v = RunId("0123456789abcdef0123456789abcdef")
        assertEquals("0123456789abcdef0123456789abcdef", v.value)
    }

    @Test
    fun recoveryPointIdRejectsNonHexOrWrongLength() {
        assertThrows(IllegalArgumentException::class.java) { RecoveryPointId("short") }
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryPointId("0123456789ABCDEF0123456789ABCDEF")
        }
    }

    @Test
    fun recoveryPointIdAcceptsCanonicalLowercaseHex() {
        val v = RecoveryPointId("fedcba98765432100123456789abcdef")
        assertEquals("fedcba98765432100123456789abcdef", v.value)
    }

    @Test
    fun everyPreWriteRejectionVariantExists() {
        val expected = setOf(
            "INVALID_PLAN",
            "STALE_REVISION",
            "EXACT_PRECONDITION_FAILED",
            // Issue #185 / ADR-0010; added to the spec 13 closed set.
            "OVERLAP_POLICY_REJECTED",
            "LOCK_STATE_UNAVAILABLE",
            "IDENTITY_EXHAUSTED",
            "CHECKPOINT_CREATE_FAILED",
            "CHECKPOINT_VALIDATE_FAILED",
            "RECOVERY_POINT_ADMISSION_BLOCKED",
            "RECOVERY_STORE_UNAVAILABLE",
            "WRITER_BUSY",
        )
        val actual = PreWriteRejection.entries.map { it.name }.toSet()
        assertEquals("PreWriteRejection must match spec exactly", expected, actual)
    }

    @Test
    fun everyApplyFailureVariantExists() {
        val expected = setOf(
            "WRITE_FAILED",
            "COMMIT_OUTCOME_UNKNOWN",
            "MODEL_RELOAD_FAILED",
            "VERIFICATION_FAILED",
            "RECOVERY_STORE_FAILED",
        )
        val actual = ApplyFailure.entries.map { it.name }.toSet()
        assertEquals("ApplyFailure must match spec exactly", expected, actual)
    }

    @Test
    fun everyRecoveryRejectionVariantExists() {
        val expected = setOf(
            "MISSING",
            "EXPIRED",
            "CORRUPT",
            "INCOMPATIBLE_VERSION",
            "STALE_REVISION",
            "LOCK_STATE_UNAVAILABLE",
            "ALREADY_RESTORED",
        )
        val actual = RecoveryRejection.entries.map { it.name }.toSet()
        assertEquals("RecoveryRejection must match spec exactly", expected, actual)
    }

    @Test
    fun everyRecoveryFailureVariantExists() {
        val expected = setOf(
            "WRITE_FAILED",
            "COMMIT_OUTCOME_UNKNOWN",
            "MODEL_RELOAD_FAILED",
            "VERIFICATION_FAILED",
            "RECOVERY_STORE_FAILED",
        )
        val actual = RecoveryFailure.entries.map { it.name }.toSet()
        assertEquals("RecoveryFailure must match spec exactly", expected, actual)
    }

    @Test
    fun everyAuthoritativeStateVariantExists() {
        val expected = setOf(
            "PRE_APPLY_DB_AND_MODEL",
            "PRE_APPLY_DB_MODEL_UNVERIFIED",
            "POST_APPLY_DB_AND_MODEL",
            "POST_APPLY_DB_MODEL_UNVERIFIED",
            "REVIEWED_CURRENT_DB_AND_MODEL",
            "REVIEWED_CURRENT_DB_MODEL_UNVERIFIED",
            "UNKNOWN",
        )
        val actual = AuthoritativeState.entries.map { it.name }.toSet()
        assertEquals("AuthoritativeState must match spec exactly", expected, actual)
    }

    @Test
    fun everyApplyResultVariantIsConstructible() {
        val runId = RunId("11111111111111111111111111111111")
        val pointId = RecoveryPointId("22222222222222222222222222222222")

        assertTrue(ApplyResult.NoChanges(runId) is ApplyResult)
        assertTrue(ApplyResult.Applied(runId, pointId) is ApplyResult)
        assertTrue(ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION) is ApplyResult)
        assertTrue(ApplyResult.RolledBack(runId, ApplyFailure.WRITE_FAILED) is ApplyResult)
        assertTrue(
            ApplyResult.Recovered(runId, pointId, ApplyFailure.VERIFICATION_FAILED) is ApplyResult,
        )
        assertTrue(
            ApplyResult.Unresolved(
                runId,
                pointId,
                ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                AuthoritativeState.UNKNOWN,
            ) is ApplyResult,
        )
        assertTrue(
            ApplyResult.RecoveryFailed(
                runId,
                pointId,
                ApplyFailure.VERIFICATION_FAILED,
                RecoveryFailure.RECOVERY_STORE_FAILED,
                AuthoritativeState.POST_APPLY_DB_MODEL_UNVERIFIED,
            ) is ApplyResult,
        )
        assertEquals(ApplyResult.ConcurrentRun, ApplyResult.ConcurrentRun)
    }

    @Test
    fun everyRecoveryResultVariantIsConstructible() {
        val pointId = RecoveryPointId("33333333333333333333333333333333")
        assertTrue(RecoveryResult.Restored(pointId) is RecoveryResult)
        assertTrue(
            RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING) is RecoveryResult,
        )
        assertTrue(
            RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.WRITE_FAILED,
                AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
            ) is RecoveryResult,
        )
        assertEquals(RecoveryResult.WriterBusy, RecoveryResult.WriterBusy)
        assertEquals(RecoveryResult.ConcurrentRun, RecoveryResult.ConcurrentRun)
    }

    @Test
    fun resultValueEqualityHolds() {
        val runId = RunId("44444444444444444444444444444444")
        val pointId = RecoveryPointId("55555555555555555555555555555555")
        assertEquals(ApplyResult.NoChanges(runId), ApplyResult.NoChanges(runId))
        assertEquals(
            ApplyResult.Rejected(runId, PreWriteRejection.INVALID_PLAN),
            ApplyResult.Rejected(runId, PreWriteRejection.INVALID_PLAN),
        )
        assertNotEquals(
            ApplyResult.Rejected(runId, PreWriteRejection.INVALID_PLAN),
            ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY),
        )
        assertEquals(RecoveryResult.Restored(pointId), RecoveryResult.Restored(pointId))
        assertEquals(
            RecoveryResult.NotRestorable(pointId, RecoveryRejection.EXPIRED),
            RecoveryResult.NotRestorable(pointId, RecoveryRejection.EXPIRED),
        )
    }
}
