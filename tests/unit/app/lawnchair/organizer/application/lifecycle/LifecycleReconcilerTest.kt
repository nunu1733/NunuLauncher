package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #14 Stage B step 2: LifecycleReconciler covers every (record state,
 * authoritative digest class) row from spec §§“Restart reconciliation”,
 * “Transaction outcome classification”.
 */
class LifecycleReconcilerTest {

    private val runId = RunId("11111111111111111111111111111111")
    private val pointId = RecoveryPointId("22222222222222222222222222222222")

    @Test
    fun creatingRecordWithExactPreStateIsClassifiedReadyAndPruned() {
        val record = record(lifecycle = LifecycleState.CREATING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.PreState)
        assertEquals(LifecycleState.READY, outcome.nextLifecycle)
        assertTrue(outcome.publicResult is ReconciliationPublicResult.SilentPrune)
    }

    @Test
    fun creatingRecordWithDifferentStateIsCorrupt() {
        val record = record(lifecycle = LifecycleState.CREATING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.Neither)
        assertEquals(LifecycleState.CORRUPT, outcome.nextLifecycle)
    }

    @Test
    fun readyRecordWithExactPreStateIsPruned() {
        val record = record(lifecycle = LifecycleState.READY)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.PreState)
        assertEquals(LifecycleState.READY, outcome.nextLifecycle)
        assertTrue(outcome.publicResult is ReconciliationPublicResult.SilentPrune)
    }

    @Test
    fun applyingRecordWithExactPreStateIsPrunedAsUnusedReady() {
        val record = record(lifecycle = LifecycleState.APPLYING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.PreState)
        assertEquals(LifecycleState.READY, outcome.nextLifecycle)
        assertTrue(outcome.publicResult is ReconciliationPublicResult.SilentPrune)
        assertFalse(outcome.launcherReloadRequired)
    }

    @Test
    fun applyingRecordWithExactIntendedPostStateResumesAsCommitted() {
        val record = record(lifecycle = LifecycleState.APPLYING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.IntendedPostState)
        assertEquals(LifecycleState.COMMITTED_UNVERIFIED, outcome.nextLifecycle)
        assertTrue(outcome.launcherReloadRequired)
        val resumed = outcome.publicResult as ReconciliationPublicResult.ResumeApply
        assertTrue(resumed.outcome is ApplyResult.Applied)
    }

    @Test
    fun applyingRecordWithNeitherStateIsUnresolved() {
        val record = record(lifecycle = LifecycleState.APPLYING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.Neither)
        assertEquals(LifecycleState.RESTORING, outcome.nextLifecycle)
        assertTrue(outcome.publicResult is ReconciliationPublicResult.Unresolved)
    }

    @Test
    fun committedUnverifiedWithExactIntendedPostStateResumesVerified() {
        val record = record(lifecycle = LifecycleState.COMMITTED_UNVERIFIED)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.IntendedPostState)
        assertEquals(LifecycleState.VERIFIED, outcome.nextLifecycle)
        assertTrue(outcome.launcherReloadRequired)
    }

    @Test
    fun committedUnverifiedWithExactPreStateSurfacesRolledBack() {
        val record = record(lifecycle = LifecycleState.COMMITTED_UNVERIFIED)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.PreState)
        assertEquals(LifecycleState.READY, outcome.nextLifecycle)
        val resumed = outcome.publicResult as ReconciliationPublicResult.ResumeApply
        assertTrue(resumed.outcome is ApplyResult.RolledBack)
    }

    @Test
    fun restoringWithExactRecoveryTargetIsRestored() {
        val record = record(lifecycle = LifecycleState.RESTORING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.RecoveryTarget)
        assertEquals(LifecycleState.RESTORED, outcome.nextLifecycle)
        val resumed = outcome.publicResult as ReconciliationPublicResult.ResumeRecovery
        assertTrue(resumed.outcome is RecoveryResult.Restored)
    }

    @Test
    fun restoringWithExactReviewedCurrentSurfacesNotCommittedAndRestoresPriorLifecycle() {
        val record = record(
            lifecycle = LifecycleState.RESTORING,
            priorLifecycle = LifecycleState.VERIFIED,
        )
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.ReviewedCurrentState)
        assertEquals(LifecycleState.VERIFIED, outcome.nextLifecycle)
        val resumed = outcome.publicResult as ReconciliationPublicResult.ResumeRecovery
        assertTrue(resumed.outcome is RecoveryResult.RestoreFailed)
    }

    @Test
    fun restoringWithNeitherStateIsUnresolved() {
        val record = record(lifecycle = LifecycleState.RESTORING)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.Neither)
        assertEquals(LifecycleState.RESTORING, outcome.nextLifecycle)
        assertTrue(outcome.publicResult is ReconciliationPublicResult.Unresolved)
    }

    @Test
    fun invalidChecksumTransitionsToCorrupt() {
        val record = record(lifecycle = LifecycleState.READY, checksumValid = false)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.PreState)
        assertEquals(LifecycleState.CORRUPT, outcome.nextLifecycle)
    }

    @Test
    fun unsupportedFormatTransitionsToIncompatible() {
        val record = record(lifecycle = LifecycleState.READY, formatVersion = 999)
        val outcome = LifecycleReconciler.reconcile(record, AuthoritativeDigestClass.PreState)
        assertEquals(LifecycleState.INCOMPATIBLE, outcome.nextLifecycle)
    }

    @Test
    fun applyOutcomePreStateIsRolledBack() {
        val record = record(lifecycle = LifecycleState.APPLYING)
        val outcome = LifecycleReconciler.classifyApplyOutcome(record, AuthoritativeDigestClass.PreState)
        assertTrue(outcome is LifecycleReconciler.ApplyOutcomeClassification.RolledBack)
    }

    @Test
    fun applyOutcomeIntendedPostStateIsCommitted() {
        val record = record(lifecycle = LifecycleState.APPLYING)
        val outcome = LifecycleReconciler.classifyApplyOutcome(record, AuthoritativeDigestClass.IntendedPostState)
        assertTrue(outcome is LifecycleReconciler.ApplyOutcomeClassification.Committed)
        val committed = outcome as LifecycleReconciler.ApplyOutcomeClassification.Committed
        assertTrue(committed.continueReloadAndVerify)
    }

    @Test
    fun applyOutcomeNeitherNeedsRecovery() {
        val record = record(lifecycle = LifecycleState.APPLYING)
        val outcome = LifecycleReconciler.classifyApplyOutcome(record, AuthoritativeDigestClass.Neither)
        assertTrue(outcome is LifecycleReconciler.ApplyOutcomeClassification.NeedsRecovery)
    }

    @Test
    fun recoveryOutcomeRecoveryTargetIsRestored() {
        val record = record(lifecycle = LifecycleState.RESTORING)
        val outcome = LifecycleReconciler.classifyRecoveryOutcome(record, AuthoritativeDigestClass.RecoveryTarget)
        assertTrue(outcome is LifecycleReconciler.RecoveryOutcomeClassification.Restored)
    }

    @Test
    fun recoveryOutcomeReviewedCurrentIsNotCommitted() {
        val record = record(lifecycle = LifecycleState.RESTORING, priorLifecycle = LifecycleState.VERIFIED)
        val outcome = LifecycleReconciler.classifyRecoveryOutcome(record, AuthoritativeDigestClass.ReviewedCurrentState)
        val notCommitted = outcome as LifecycleReconciler.RecoveryOutcomeClassification.NotCommitted
        assertEquals(LifecycleState.VERIFIED, notCommitted.nextLifecycle)
    }

    @Test
    fun lifecycleTransitionsTableEnforcesAllSpecEdges() {
        // Forward-only happy path
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.CREATING, LifecycleState.READY))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.READY, LifecycleState.APPLYING))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.APPLYING, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.COMMITTED_UNVERIFIED, LifecycleState.VERIFIED))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.VERIFIED, LifecycleState.RESTORING))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.RESTORING, LifecycleState.RESTORED))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.VERIFIED, LifecycleState.EXPIRED))

        // Recovery transitions
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.APPLYING, LifecycleState.READY))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.COMMITTED_UNVERIFIED, LifecycleState.RESTORING))

        // Corruption transitions
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.READY, LifecycleState.CORRUPT))
        assertTrue(LifecycleTransitions.isLegal(LifecycleState.APPLYING, LifecycleState.CORRUPT))

        // Illegal backwards transitions
        assertFalse(LifecycleTransitions.isLegal(LifecycleState.VERIFIED, LifecycleState.APPLYING))
        assertFalse(LifecycleTransitions.isLegal(LifecycleState.RESTORED, LifecycleState.RESTORING))

        // Terminal states
        assertTrue(LifecycleTransitions.isFinal(LifecycleState.RESTORED))
        assertTrue(LifecycleTransitions.isFinal(LifecycleState.EXPIRED))
        assertTrue(LifecycleTransitions.isFinal(LifecycleState.CORRUPT))
        assertTrue(LifecycleTransitions.isFinal(LifecycleState.INCOMPATIBLE))
        assertFalse(LifecycleTransitions.isFinal(LifecycleState.VERIFIED))

        // Active states
        assertTrue(LifecycleTransitions.isActive(LifecycleState.APPLYING))
        assertTrue(LifecycleTransitions.isActive(LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(LifecycleTransitions.isActive(LifecycleState.RESTORING))
        assertFalse(LifecycleTransitions.isActive(LifecycleState.VERIFIED))
    }

    private fun record(
        lifecycle: LifecycleState,
        priorLifecycle: LifecycleState? = null,
        checksumValid: Boolean = true,
        formatVersion: Int = LifecycleReconciler.SUPPORTED_FORMAT,
    ): ReconcilerRecord = ReconcilerRecord(
        pointId = pointId,
        runId = runId,
        lifecycle = lifecycle,
        priorLifecycle = priorLifecycle,
        hasStoredPostIntent = true,
        hasReviewedCurrentManifest = lifecycle == LifecycleState.RESTORING,
        formatVersion = formatVersion,
        checksumValid = checksumValid,
    )
}
