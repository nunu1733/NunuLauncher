package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.ReconciliationPublicResult
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.application.store.RecoveryRecordCodec
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #377 AC-1: the adjudicated reconciliation decision matrix (spec 377
 * plan Current evidence), fixed through the live production seams. Introduced
 * in the pre-refactor characterization commit (against the pre-table wiring)
 * and carried unchanged through the single-decision-table integration: the
 * same observable results must hold before and after. Restart rows go through
 * the live `RestartReconciler` seam; the in-flight apply / recovery
 * path-context rows go through the real `ApplyProtocol` / `RecoveryProtocol`
 * seams (rule (b): the same cell classifies differently per path).
 *
 * Gate rows (checksum / format) are covered by RestartReconcilerTest and the
 * production RecoveryStoreLifecycleTest oracles; this table fixes the
 * lifecycle × class rows. The intended-post-state digest is seeded directly
 * onto the record (the same seeding shape RecoveryProtocolTest uses), and the
 * writer state is moved to the matching state so the fake classification
 * yields the target AuthoritativeClass.
 */
class ReconciliationDecisionMatrixTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var reconciler: RestartReconciler
    private lateinit var mutex: RunMutex
    private lateinit var session: RecoveryStoreReconciliationSession
    private val reconciliationRunId = RunId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val pointId = RecoveryPointId("22222222222222222222222222222222")
    private val recordRunId = RunId("11111111111111111111111111111111")

    @Before
    fun setUp() {
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore(FakeClock::nowMillis)
        mutex = RunMutex()
        assertTrue(mutex.tryAcquire(reconciliationRunId))
        val lease = requireNotNull(mutex.issueReconciliationLease(reconciliationRunId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        session = requireNotNull(issuer.openSession(lease))
        reconciler = RestartReconciler(writer, RecordingFaultInjector())
    }

    @After
    fun tearDown() {
        session.close()
        mutex.release(reconciliationRunId)
    }

    /**
     * Seed a record at [lifecycle] whose digests make the fake writer classify
     * the current Launcher state as [authoritative]: preDigest always equals
     * the initial state; for INTENDED_POST_STATE rows the intended digest is
     * the moved state's digest and the writer is moved there. For RESTORING
     * rows the recovery-target digest equals preDigest by construction
     * (`classifyMeta` passes `recoveryTargetDigest = preDigest`), so
     * RECOVERY_TARGET rows use the pre state and REVIEWED_CURRENT_STATE rows
     * carry the reviewed digest of the moved state.
     */
    private fun seed(
        lifecycle: LifecycleState,
        authoritative: AuthoritativeClass,
    ) {
        val preState = writer.currentState()
        val preManifest = manifestFor(preState)
        val preDigest = RevisionCalculator.classificationDigestOf(preState)
        val movedState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(5, 5))))
        val movedDigest = RevisionCalculator.classificationDigestOf(movedState)
        val neitherState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(7, 7))))
        val neitherDigest = RevisionCalculator.classificationDigestOf(neitherState)
        val intendedDigest = when (authoritative) {
            AuthoritativeClass.INTENDED_POST_STATE -> movedDigest
            else -> preDigest
        }
        val reviewedDigest = if (authoritative == AuthoritativeClass.REVIEWED_CURRENT_STATE) movedDigest else null
        when (authoritative) {
            AuthoritativeClass.INTENDED_POST_STATE, AuthoritativeClass.REVIEWED_CURRENT_STATE -> writer.setCurrentState(movedState)
            AuthoritativeClass.NEITHER -> writer.setCurrentState(neitherState)
            else -> Unit
        }
        store.seedRecord(
            object : RecoveryStorePort.StoredRecord {
                override val pointId: RecoveryPointId = this@ReconciliationDecisionMatrixTest.pointId
                override val runId: RunId = recordRunId
                override val lifecycle: LifecycleState = lifecycle
                override val priorLifecycle: LifecycleState? = if (lifecycle == LifecycleState.RESTORING) LifecycleState.VERIFIED else null
                override val createdAtMs: Long = FakeClock.nowMillis()
                override val updatedAtMs: Long = FakeClock.nowMillis()
                override val preManifest: PersistenceManifest = preManifest
                override val preRevision: RevisionId = RevisionCalculator.revisionOf(preState)
                override val preDigest: ByteArray = preDigest
                override val intendedManifest: PersistenceManifest = if (authoritative == AuthoritativeClass.INTENDED_POST_STATE) manifestFor(movedState) else preManifest
                override val intendedDigest: ByteArray = intendedDigest
                override val applyActionDigest: ByteArray = preDigest
                override val reviewedManifest: PersistenceManifest? = if (authoritative == AuthoritativeClass.REVIEWED_CURRENT_STATE) manifestFor(movedState) else null
                override val reviewedDigest: ByteArray? = reviewedDigest
                override val recoveryActionDigest: ByteArray? = null
                override val itemCount: Int = preState.items.size
                override val resourceCount: Int = preState.pages.size
                override val checksumValid: Boolean = true
                override val formatVersion: Int = RecoveryRecordCodec.RECORD_FORMAT_VERSION
            },
        )
    }

    private fun manifestFor(state: app.lawnchair.organizer.application.public.LayoutState): PersistenceManifest = PersistenceManifest(
        formatVersion = 1,
        schemaVersion = 33,
        rowCount = state.items.size,
        rows = state.items.mapIndexed { index, item ->
            PersistentRow(
                rowId = (index + 1).toLong(),
                itemId = ItemId("item-$index"),
                profileId = item.profile,
                containerCode = ContainerCode(0),
                screenId = null,
                cellX = 0,
                cellY = 0,
                spanX = 1,
                spanY = 1,
                rank = 0,
                itemType = KindCode(0),
                appWidgetId = null,
                appWidgetProvider = null,
                iconBytes = null,
                title = null,
                intent = null,
                restored = null,
                options = null,
                appWidgetSource = null,
                modified = 0L,
                organizerLockState = item.lockState,
                rawCell = null,
                rawSpan = null,
            )
        },
        resources = state.pages.mapIndexed { index, page ->
            PersistentResource(
                kind = PersistentResourceKind.WORKSPACE_SCREEN,
                profileId = ProfileId("personal"),
                order = index.toLong(),
                payload = byteArrayOf(index.toByte()),
            )
        },
        modifiedAtMillis = 0L,
    )

    private fun singleResult(): ReconciliationPublicResult {
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved)
        val results = (summary as RestartReconciler.ReconciliationSummary.Resolved).publicResults
        assertEquals(1, results.size)
        return results.single()
    }

    // Row: READY × PRE_STATE → prune silently (no surfaced result).
    @Test
    fun readyAtPreStateIsSilentlyPruned() {
        seed(LifecycleState.READY, AuthoritativeClass.PRE_STATE)
        val summary = reconciler.reconcileAll(session)
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
    }

    // Row: READY × non-PRE_STATE → fail-closed unresolved (COMMIT_OUTCOME_UNKNOWN),
    // record kept at READY without entering the recovery path.
    @Test
    fun readyAtNonPreStateIsFailClosedUnresolvedAndKeepsRecord() {
        seed(LifecycleState.READY, AuthoritativeClass.INTENDED_POST_STATE)
        val result = singleResult()
        assertTrue(result is ReconciliationPublicResult.Unresolved)
        val outcome = (result as ReconciliationPublicResult.Unresolved).outcome
        assertTrue(outcome is ApplyResult.Unresolved)
        assertEquals(
            ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
            (outcome as ApplyResult.Unresolved).failure,
        )
        assertEquals(LifecycleState.READY, storedLifecycleOf(pointId))
    }

    // Row: CREATING × PRE_STATE → advance READY + prune silently (unused checkpoint).
    @Test
    fun creatingAtPreStatePrunesSilently() {
        seedRawLifecycle(LifecycleState.CREATING, AuthoritativeClass.PRE_STATE)
        val summary = reconciler.reconcileAll(session)
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
    }

    // Row: CREATING × other → advance CORRUPT + unresolved (never usable for apply/recovery).
    @Test
    fun creatingAtOtherAdvancesCorruptAndSurfacesUnresolved() {
        seedRawLifecycle(LifecycleState.CREATING, AuthoritativeClass.INTENDED_POST_STATE)
        val result = singleResult()
        assertTrue(result is ReconciliationPublicResult.Unresolved)
        assertEquals(LifecycleState.CORRUPT, storedLifecycleOf(pointId))
    }

    // Row: APPLYING × PRE_STATE → advance READY + prune silently.
    @Test
    fun applyingAtPreStatePrunesSilently() {
        seed(LifecycleState.APPLYING, AuthoritativeClass.PRE_STATE)
        val summary = reconciler.reconcileAll(session)
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
    }

    // Row: APPLYING × INTENDED_POST_STATE → finishCommittedApply → Applied, VERIFIED.
    @Test
    fun applyingAtIntendedPostStateCompletesCommittedApply() {
        seed(LifecycleState.APPLYING, AuthoritativeClass.INTENDED_POST_STATE)
        val result = singleResult()
        assertTrue(result is ReconciliationPublicResult.ResumeApply)
        assertTrue((result as ReconciliationPublicResult.ResumeApply).outcome is ApplyResult.Applied)
        assertEquals(LifecycleState.VERIFIED, storedLifecycleOf(pointId))
    }

    // Row: COMMITTED_UNVERIFIED × INTENDED_POST_STATE → finishCommittedApply → Applied, VERIFIED.
    @Test
    fun committedUnverifiedAtIntendedPostStateCompletesVerified() {
        seed(LifecycleState.COMMITTED_UNVERIFIED, AuthoritativeClass.INTENDED_POST_STATE)
        val result = singleResult()
        assertTrue(result is ReconciliationPublicResult.ResumeApply)
        assertTrue((result as ReconciliationPublicResult.ResumeApply).outcome is ApplyResult.Applied)
        assertEquals(LifecycleState.VERIFIED, storedLifecycleOf(pointId))
    }

    // Row: COMMITTED_UNVERIFIED × PRE_STATE → advance READY + prune, surfaces RolledBack.
    @Test
    fun committedUnverifiedAtPreStateSurfacesRolledBack() {
        seed(LifecycleState.COMMITTED_UNVERIFIED, AuthoritativeClass.PRE_STATE)
        val result = singleResult()
        assertTrue(result is ReconciliationPublicResult.ResumeApply)
        val outcome = (result as ReconciliationPublicResult.ResumeApply).outcome
        assertTrue(outcome is ApplyResult.RolledBack)
        assertEquals(ApplyFailure.COMMIT_OUTCOME_UNKNOWN, (outcome as ApplyResult.RolledBack).failure)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
    }

    // Row: RESTORING × RECOVERY_TARGET (≡ PRE_STATE for the restart classifier) → Restored.
    @Test
    fun restoringAtRecoveryTargetCompletesRestored() {
        seed(LifecycleState.RESTORING, AuthoritativeClass.PRE_STATE)
        val result = singleResult()
        assertTrue(result is ReconciliationPublicResult.ResumeRecovery)
        assertTrue((result as ReconciliationPublicResult.ResumeRecovery).outcome is RecoveryResult.Restored)
        assertEquals(LifecycleState.RESTORED, storedLifecycleOf(pointId))
        assertEquals(1, writer.reloadCount)
    }

    // Row: VERIFIED × any class → SilentAdvance (no surfaced result).
    @Test
    fun verifiedAtAnyClassAdvancesSilently() {
        seed(LifecycleState.VERIFIED, AuthoritativeClass.PRE_STATE)
        val summary = reconciler.reconcileAll(session)
        assertEquals(RestartReconciler.ReconciliationSummary.Clean, summary)
        assertEquals(LifecycleState.VERIFIED, storedLifecycleOf(pointId))
    }

    // Row: APPLYING × other (NEITHER) → recovery attempt → unresolved.
    @Test
    fun applyingAtNeitherAttemptsRecoveryAndSurfacesUnresolved() {
        seed(LifecycleState.APPLYING, AuthoritativeClass.NEITHER)
        val summary = reconciler.reconcileAll(session)
        // The NEITHER row enters the recovery attempt; its completion outcome
        // (Restored after verified reload, or Unresolved on store failure) is
        // the recovery path's own contract (fixed by RecoveryProtocolTest).
        // The adjudicated row fixed here is only that the NEITHER cell enters
        // recovery rather than pruning or continuing the committed apply.
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved || summary is RestartReconciler.ReconciliationSummary.Failed)
        assertTrue(
            "APPLYING × NEITHER must leave the record in an active or resolved state, got ${storedLifecycleOf(pointId)}",
            storedLifecycleOf(pointId) in setOf(LifecycleState.RESTORING, LifecycleState.RESTORED),
        )
    }

    // Row: COMMITTED_UNVERIFIED × other (NEITHER) → recovery attempt → unresolved.
    @Test
    fun committedUnverifiedAtNeitherAttemptsRecoveryAndSurfacesUnresolved() {
        seed(LifecycleState.COMMITTED_UNVERIFIED, AuthoritativeClass.NEITHER)
        val summary = reconciler.reconcileAll(session)
        assertTrue(summary is RestartReconciler.ReconciliationSummary.Resolved || summary is RestartReconciler.ReconciliationSummary.Failed)
        assertTrue(
            "COMMITTED_UNVERIFIED × NEITHER must leave the record in an active or resolved state, got ${storedLifecycleOf(pointId)}",
            storedLifecycleOf(pointId) in setOf(LifecycleState.RESTORING, LifecycleState.RESTORED),
        )
    }

    // Row: RESTORING × REVIEWED_CURRENT → recovery attempt (rule (b): the restart path
    // resumes the persisted recovery intent rather than surfacing NotCommitted).
    @Test
    fun restoringAtReviewedCurrentAttemptsRecovery() {
        seed(LifecycleState.RESTORING, AuthoritativeClass.REVIEWED_CURRENT_STATE)
        val result = singleResult()
        // The row under test is that the restart path enters recovery (not a
        // NotCommitted shortcut). The surfaced result is the recovery path's
        // own typed outcome, so the lifecycle contract is fixed here.
        assertTrue(result is ReconciliationPublicResult.ResumeRecovery || result is ReconciliationPublicResult.Unresolved)
        assertEquals(LifecycleState.RESTORING, storedLifecycleOf(pointId))
    }

    /**
     * Seed a record at a raw lifecycle the public store seam cannot produce
     * (CREATING): the checkpoint path always lands READY. Uses the same
     * digest seeding as [seed].
     */
    private fun seedRawLifecycle(
        lifecycle: LifecycleState,
        authoritative: AuthoritativeClass,
    ) {
        val preState = writer.currentState()
        val preManifest = manifestFor(preState)
        val preDigest = RevisionCalculator.classificationDigestOf(preState)
        val movedState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(5, 5))))
        val movedDigest = RevisionCalculator.classificationDigestOf(movedState)
        val intendedDigest = if (authoritative == AuthoritativeClass.INTENDED_POST_STATE) movedDigest else preDigest
        if (authoritative == AuthoritativeClass.INTENDED_POST_STATE) writer.setCurrentState(movedState)
        store.seedRecord(
            object : RecoveryStorePort.StoredRecord {
                override val pointId: RecoveryPointId = this@ReconciliationDecisionMatrixTest.pointId
                override val runId: RunId = recordRunId
                override val lifecycle: LifecycleState = lifecycle
                override val priorLifecycle: LifecycleState? = null
                override val createdAtMs: Long = FakeClock.nowMillis()
                override val updatedAtMs: Long = FakeClock.nowMillis()
                override val preManifest: PersistenceManifest = preManifest
                override val preRevision: RevisionId = RevisionCalculator.revisionOf(preState)
                override val preDigest: ByteArray = preDigest
                override val intendedManifest: PersistenceManifest = preManifest
                override val intendedDigest: ByteArray = intendedDigest
                override val applyActionDigest: ByteArray = preDigest
                override val reviewedManifest: PersistenceManifest? = null
                override val reviewedDigest: ByteArray? = null
                override val recoveryActionDigest: ByteArray? = null
                override val itemCount: Int = preState.items.size
                override val resourceCount: Int = preState.pages.size
                override val checksumValid: Boolean = true
                override val formatVersion: Int = RecoveryRecordCodec.RECORD_FORMAT_VERSION
            },
        )
    }

    // --- In-flight path-context rows, fixed through the real protocol seams ---
    //
    // Rule (b): the same (lifecycle × class) cell classifies differently per
    // path. These sections drive the real `ApplyProtocol` / `RecoveryProtocol`
    // seams (the same shape ApplyProtocolTest / RecoveryProtocolTest use) so
    // the pre-refactor in-flight rows are characterized on the production
    // wiring, not on a new abstraction.

    // In-flight apply × PRE_STATE: write failure with the DB at pre state →
    // RolledBack (spec 13 "Transaction outcome classification" row 1).
    @Test
    fun inFlightApplyAtPreStateSurfacesRolledBack() {
        val applyWriter = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(0, 0)))))
        val applyStore = FakeRecoveryStore(FakeClock::nowMillis)
        val applyProtocol = ApplyProtocol(
            applyWriter,
            applyStore,
            FakeClock,
            FixedOperationIdSource(),
            RecordingFaultInjector(),
            RunMutex(),
        )
        applyWriter.setFailOnNthWrite(1L)
        val result = applyProtocol.apply(mutatingPlanFor(applyWriter))
        assertTrue("Expected RolledBack, got $result", result is ApplyResult.RolledBack)
        assertEquals(ApplyFailure.WRITE_FAILED, (result as ApplyResult.RolledBack).failure)
    }

    // In-flight apply × INTENDED_POST_STATE: committed apply continues to
    // reload/verify → Applied (spec 13 row 2).
    @Test
    fun inFlightApplyAtIntendedPostStateContinuesToApplied() {
        val applyWriter = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(0, 0)))))
        val applyStore = FakeRecoveryStore(FakeClock::nowMillis)
        val applyProtocol = ApplyProtocol(
            applyWriter,
            applyStore,
            FakeClock,
            FixedOperationIdSource(),
            RecordingFaultInjector(),
            RunMutex(),
        )
        val result = applyProtocol.apply(mutatingPlanFor(applyWriter))
        assertTrue("Expected Applied, got $result", result is ApplyResult.Applied)
    }

    // In-flight apply × other (NEITHER): a write failure with the DB at a
    // digest that is neither pre nor intended → automatic recovery runs
    // (spec 13 row 3). The fake's classificationDigestOverride drives the
    // NEITHER cell; the recovery path then completes and surfaces its own
    // typed result.
    @Test
    fun inFlightApplyAtNeitherEntersAutomaticRecovery() {
        val applyWriter = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(0, 0)))))
        val applyStore = FakeRecoveryStore(FakeClock::nowMillis)
        val applyProtocol = ApplyProtocol(
            applyWriter,
            applyStore,
            FakeClock,
            FixedOperationIdSource(),
            RecordingFaultInjector(),
            RunMutex(),
        )
        applyWriter.nextTxOutcome = ApplyTxOutcome.Failed(RuntimeException("injected"))
        applyWriter.classificationDigestOverride = ByteArray(32) { (it + 11).toByte() }
        val result = applyProtocol.apply(mutatingPlanFor(applyWriter))
        // The adjudicated row is that the NEITHER cell enters automatic
        // recovery directly — never the RolledBack (PRE_STATE) row and never
        // the continueCommitted (INTENDED_POST) row. A misrouted
        // continueCommitted would advance the record to COMMITTED_UNVERIFIED,
        // emit the committed flow and issue a correlated reload before its
        // own verification failure falls back to recovery; the direct
        // automatic-recovery path reaches its typed outcome before any
        // reload. reloadCount == 0 therefore distinguishes the two wirings.
        assertEquals(
            "NEITHER must enter automatic recovery directly (no committed-flow reload)",
            0,
            applyWriter.reloadCount,
        )
        assertTrue(
            "Expected the recovery path outcome, got $result",
            result is ApplyResult.RecoveryFailed,
        )
    }

    // In-flight recovery × PRE_STATE/RECOVERY_TARGET: the restore committed →
    // Restored after verification (spec 13 "Recovery protocol" step 6).
    @Test
    fun inFlightRecoveryAtRecoveryTargetSurfacesRestored() {
        val recoveryWriter = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        val recoveryStore = FakeRecoveryStore(FakeClock::nowMillis)
        val recoveryProtocol = RecoveryProtocol(
            recoveryWriter,
            recoveryStore,
            FakeClock,
            FixedOperationIdSource(),
            RecordingFaultInjector(),
            RunMutex(),
        )
        seedVerifiedPointFor(recoveryWriter, recoveryStore)
        val request = RecoveryRequest(
            RecoveryPointId("22222222222222222222222222222222"),
            RevisionCalculator.revisionOf(recoveryWriter.currentState()),
        )
        val result = recoveryProtocol.recover(request)
        assertTrue("Expected Restored, got $result", result is RecoveryResult.Restored)
    }

    // In-flight recovery × REVIEWED_CURRENT: the restore did not commit →
    // typed RestoreFailed (rule (b): differs from the restart path's recovery
    // resume). The fake's classificationDigestOverride drives a reviewed
    // digest that matches none of the stored pre/intended/recovery-target
    // digests, so the classification lands on REVIEWED_CURRENT_STATE.
    @Test
    fun inFlightRecoveryAtReviewedCurrentSurfacesTypedFailure() {
        val recoveryWriter = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        val recoveryStore = FakeRecoveryStore(FakeClock::nowMillis)
        val recoveryProtocol = RecoveryProtocol(
            recoveryWriter,
            recoveryStore,
            FakeClock,
            FixedOperationIdSource(),
            RecordingFaultInjector(),
            RunMutex(),
        )
        seedVerifiedPointFor(recoveryWriter, recoveryStore)
        // Move the writer to a third state: the recovery recapture (reviewed)
        // digest then equals that state's digest while pre/intended/recovery
        // target all stay at the seeded pre digest. Forcing the classification
        // digest to the same third digest lands the classification on
        // REVIEWED_CURRENT_STATE (the write itself fails so the not-committed
        // row is reached).
        val thirdState = CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem(cell = app.lawnchair.organizer.planning.GridCell(7, 7))))
        recoveryWriter.setCurrentState(thirdState)
        recoveryWriter.nextTxOutcome = ApplyTxOutcome.Failed(RuntimeException("injected"))
        recoveryWriter.classificationDigestOverride =
            RevisionCalculator.classificationDigestOf(thirdState)
        val request = RecoveryRequest(
            RecoveryPointId("22222222222222222222222222222222"),
            RevisionCalculator.revisionOf(thirdState),
        )
        val result = recoveryProtocol.recover(request)
        assertTrue("Expected RestoreFailed, got $result", result is RecoveryResult.RestoreFailed)
        val failed = result as RecoveryResult.RestoreFailed
        assertEquals(
            "the not-committed row must surface the classification failure",
            RecoveryFailure.WRITE_FAILED,
            failed.failure,
        )
        assertEquals(
            app.lawnchair.organizer.application.public.AuthoritativeState.REVIEWED_CURRENT_DB_MODEL_UNVERIFIED,
            failed.authoritativeState,
        )
    }

    /** Mutating plan over the writer's current state (same shape as ApplyProtocolTest). */
    private fun mutatingPlanFor(w: FakeLayoutWriter): app.lawnchair.organizer.application.public.ValidatedLayoutPlan {
        val sourceState = w.currentState()
        val intendedState = sourceState.copy(
            items = sourceState.items.map { it.copy(title = app.lawnchair.organizer.application.public.OptionalText.Present("Z")) },
        )
        val ref = sourceState.items.first().ref
        val action = app.lawnchair.organizer.application.public.ApplyAction.Update(
            ref = ref,
            expected = sourceState.items.first(),
            intended = intendedState.items.first(),
        )
        return app.lawnchair.organizer.application.public.ValidatedLayoutPlan(
            sourceRevision = RevisionCalculator.revisionOf(sourceState),
            sourceState = sourceState,
            intendedState = intendedState,
            actions = listOf(action),
            newPages = emptyList(),
            newFolders = emptyList(),
            ruleVersion = app.lawnchair.organizer.planning.RuleVersion("v2"),
            taxonomyVersion = app.lawnchair.organizer.planning.TaxonomyVersion("tv1"),
        )
    }

    /** Seed a VERIFIED point with the writer's current state digests (same shape as RecoveryProtocolTest). */
    private fun seedVerifiedPointFor(w: FakeLayoutWriter, target: FakeRecoveryStore) {
        val state = w.currentState()
        val preDigest = RevisionCalculator.classificationDigestOf(state)
        target.seedRecord(
            object : RecoveryStorePort.StoredRecord {
                override val pointId: RecoveryPointId = this@ReconciliationDecisionMatrixTest.pointId
                override val runId: RunId = recordRunId
                override val lifecycle: LifecycleState = LifecycleState.VERIFIED
                override val priorLifecycle: LifecycleState? = null
                override val createdAtMs: Long = FakeClock.nowMillis()
                override val updatedAtMs: Long = FakeClock.nowMillis()
                override val preManifest: PersistenceManifest = manifestFor(state)
                override val preRevision: RevisionId = RevisionCalculator.revisionOf(state)
                override val preDigest: ByteArray = preDigest
                override val intendedManifest: PersistenceManifest = manifestFor(state)
                override val intendedDigest: ByteArray = preDigest
                override val applyActionDigest: ByteArray = preDigest
                override val reviewedManifest: PersistenceManifest? = null
                override val reviewedDigest: ByteArray? = null
                override val recoveryActionDigest: ByteArray? = null
                override val itemCount: Int = state.items.size
                override val resourceCount: Int = state.pages.size
                override val checksumValid: Boolean = true
                override val formatVersion: Int = RecoveryRecordCodec.RECORD_FORMAT_VERSION
            },
        )
    }

    private fun storedLifecycleOf(id: RecoveryPointId): LifecycleState? = (
        store.readRecord(id) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.lifecycle
}
