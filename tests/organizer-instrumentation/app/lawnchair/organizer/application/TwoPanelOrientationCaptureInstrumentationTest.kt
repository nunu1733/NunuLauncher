package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.ProductionOrganizationInputComposer
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec #130: canonical capture must derive orientation from the same
 * constructed DeviceProfile authority as the launcher UI, preserve it through
 * the production composer, and treat orientation-value changes as revision
 * changes (stale rejection without any DB write).
 *
 * Spec #435: the stale-rejection oracle of
 * [orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite] establishes
 * explicit preconditions and classifies every apply attempt through the
 * stage-aware retry contract in [decideStaleOracleOutcome].
 */

/** The stale-rejection oracle's classification of one apply attempt (spec #435). */
internal enum class StaleOracleDecision {

    /** `STALE_REVISION` was observed — the stale-rejection contract held. */
    SUCCESS,

    /**
     * Retryable pre-revision rejection or gate-level rejection: re-establish
     * the preconditions and re-apply under the shared bounded budget.
     */
    RETRY_PRECONDITIONS,

    /**
     * Contract violation, an unexplainable observation, or budget exhaustion:
     * fail immediately with the recorded observations.
     */
    HARD_FAIL,
}

private fun retryableWhenPreRevision(
    expectedStage: ApplyStage,
    terminalStage: ApplyStage?,
    gateState: ReadinessGate.State?,
): StaleOracleDecision = when {
    // Protocol-level rejection before the revision comparison (A0 lease, A2
    // availability probe): pre-write and legitimately contended under load.
    terminalStage == expectedStage -> StaleOracleDecision.RETRY_PRECONDITIONS
    // The same reason at any other stage means the revision check already
    // passed (A4 checkpoint store unavailable, A5 markApplying failure) or the
    // observation does not match the contract — retrying could let a later
    // STALE_REVISION mask it.
    terminalStage != null -> StaleOracleDecision.HARD_FAIL
    // Gate-level rejection: the protocol was never reached, so there is no
    // terminal event; classify by the gate state read after the rejection.
    gateState == ReadinessGate.State.FAILED ||
        gateState == ReadinessGate.State.IDLE ||
        gateState == ReadinessGate.State.RECONCILING -> StaleOracleDecision.RETRY_PRECONDITIONS
    // No terminal event and the gate claims READY: the observation cannot be
    // explained, so it is never retried on the reason string alone.
    else -> StaleOracleDecision.HARD_FAIL
}

/**
 * The A2 stale rejection: the capture/revision comparison rejected the plan
 * before any write-path state was created — the exact contract point of the
 * orientation stale scenario (spec #130, #435).
 */
private fun staleAtCaptureComparison(terminalStage: ApplyStage?): Boolean =
    terminalStage == ApplyStage.A2

/**
 * Stage-aware retry decision for the stale-rejection oracle (spec #435
 * TOR-AC-03/04/07). Only `WRITER_BUSY` at stage A0 and
 * `RECOVERY_STORE_UNAVAILABLE` at stage A2 — the rejections evaluated before
 * the revision comparison — plus gate-level rejections (no terminal
 * diagnostics event) are retryable; everything else is a hard failure.
 * `STALE_REVISION` is only a success at A2: the same reason also returns from
 * the A5 in-transaction reread (`classifyApplyOutcome`), which runs after the
 * checkpoint was created and would let an A2 regression hide behind a later
 * stale hit. The budget only bounds retries.
 */
internal fun decideStaleOracleOutcome(
    result: ApplyResult,
    terminalStage: ApplyStage?,
    gateState: ReadinessGate.State?,
    budgetLeft: Boolean,
): StaleOracleDecision {
    val classified = when (result) {
        is ApplyResult.Rejected -> when (result.reason) {
            PreWriteRejection.STALE_REVISION ->
                if (staleAtCaptureComparison(terminalStage)) {
                    StaleOracleDecision.SUCCESS
                } else {
                    StaleOracleDecision.HARD_FAIL
                }
            PreWriteRejection.WRITER_BUSY ->
                retryableWhenPreRevision(ApplyStage.A0, terminalStage, gateState)
            PreWriteRejection.RECOVERY_STORE_UNAVAILABLE ->
                retryableWhenPreRevision(ApplyStage.A2, terminalStage, gateState)
            PreWriteRejection.INVALID_PLAN,
            PreWriteRejection.EXACT_PRECONDITION_FAILED,
            PreWriteRejection.CANDIDATE_UNAVAILABLE,
            PreWriteRejection.OVERLAP_POLICY_REJECTED,
            PreWriteRejection.LOCK_STATE_UNAVAILABLE,
            PreWriteRejection.IDENTITY_EXHAUSTED,
            PreWriteRejection.CHECKPOINT_CREATE_FAILED,
            PreWriteRejection.CHECKPOINT_VALIDATE_FAILED,
            PreWriteRejection.RECOVERY_POINT_ADMISSION_BLOCKED,
            -> StaleOracleDecision.HARD_FAIL
        }
        ApplyResult.ConcurrentRun -> StaleOracleDecision.HARD_FAIL
        is ApplyResult.NoChanges -> StaleOracleDecision.HARD_FAIL
        is ApplyResult.Applied -> StaleOracleDecision.HARD_FAIL
        is ApplyResult.RolledBack -> StaleOracleDecision.HARD_FAIL
        is ApplyResult.Recovered -> StaleOracleDecision.HARD_FAIL
        is ApplyResult.Unresolved -> StaleOracleDecision.HARD_FAIL
        is ApplyResult.RecoveryFailed -> StaleOracleDecision.HARD_FAIL
    }
    return if (classified == StaleOracleDecision.RETRY_PRECONDITIONS && !budgetLeft) {
        StaleOracleDecision.HARD_FAIL
    } else {
        classified
    }
}

/** Retry budget for the stale-rejection oracle, aligned with the existing 20s helper timeouts. */
private const val ORACLE_BUDGET_MS = 20_000L

/** Backoff between oracle attempts and precondition polls. */
private const val RETRY_BACKOFF_MS = 100L

/**
 * Captures diagnostics events so a rejection can be classified by its terminal
 * apply stage instead of its reason string alone (spec #435 TOR-AC-05).
 */
private class RecordingDiagnosticsPort : DiagnosticsPort {
    private val events = CopyOnWriteArrayList<RunEvent>()

    override fun emit(event: RunEvent) {
        events.add(event)
    }

    override fun snapshot(): List<RunEvent> = events.toList()

    /**
     * The [ApplyStage] of the terminal apply event for [runId], or null when
     * the rejection happened before the protocol (gate-level). Matching is by
     * `runId` + terminal phase, never by "last event": a run also emits
     * intermediate events (CHECKPOINTED, APPLY_COMMITTED, ...) that carry
     * non-terminal stages.
     */
    fun terminalApplyStage(runId: String): ApplyStage? {
        val terminal = snapshot().filter {
            it.runId == runId && it.phase in TERMINAL_APPLY_PHASES
        }
        check(terminal.size <= 1) {
            "Expected at most one terminal apply event for run $runId, got ${terminal.size}"
        }
        return terminal.singleOrNull()?.applyStage
    }

    companion object {

        /** Terminal phases of the apply/recover projections (see PhaseCode contract). */
        private val TERMINAL_APPLY_PHASES = setOf(
            PhaseCode.APPLY_VERIFIED,
            PhaseCode.APPLY_NO_CHANGES,
            PhaseCode.APPLY_REJECTED,
            PhaseCode.CHECKPOINT_REJECTED,
            PhaseCode.CONCURRENT_RUN_REJECTED,
            PhaseCode.APPLY_ROLLED_BACK,
            PhaseCode.APPLY_RECOVERED,
            PhaseCode.APPLY_UNRESOLVED,
            PhaseCode.APPLY_RECOVERY_FAILED,
        )
    }
}

private fun ApplyResult.runIdForDiagnostics(): String? = when (this) {
    is ApplyResult.NoChanges -> runId.value
    is ApplyResult.Applied -> runId.value
    is ApplyResult.Rejected -> runId.value
    is ApplyResult.RolledBack -> runId.value
    is ApplyResult.Recovered -> runId.value
    is ApplyResult.Unresolved -> runId.value
    is ApplyResult.RecoveryFailed -> runId.value
    ApplyResult.ConcurrentRun -> null
}

private fun ApplyResult.reasonOf(): PreWriteRejection? = (this as? ApplyResult.Rejected)?.reason

@RunWith(AndroidJUnit4::class)
class TwoPanelOrientationCaptureInstrumentationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var snapshotRows: List<ContentValues> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        cleanRecoveryArtifacts()
        snapshotRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        try {
            restoreFavorites(snapshotRows)
            launcher.model.forceReload()
            val model = launcher.model
            val deadline = System.currentTimeMillis() + 5_000L
            while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        } finally {
            cleanRecoveryArtifacts()
        }
    }

    /**
     * Removes the recovery database and its companion inspection inventory.
     * Deleting only the database leaves the startup classifier in
     * SuspiciousAbsence, which fail-closes reconciliation for the next test.
     */
    private fun cleanRecoveryArtifacts() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).deleteRecursively()
    }

    @Test
    fun capturedOrientationMatchesConstructedDeviceProfileAuthority() {
        val writer = realWriter()
        val capture = writer.captureCurrent(CaptureId("orientation-authority"))
        val activeProfile = InvariantDeviceProfile.INSTANCE.get(context).getDeviceProfile(context)
        val captured = capture.layoutState.deviceCapabilities.orientation

        assertEquals(activeProfile.isTwoPanels, captured.isTwoPanel())
        if (!activeProfile.isTwoPanels) {
            val expected = if (
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                DeviceOrientation.LANDSCAPE
            } else {
                DeviceOrientation.PORTRAIT
            }
            assertEquals(expected, captured)
        }
    }

    @Test
    fun productionComposerPreservesCapturedOrientationIntoPlannerInput() {
        ensureLauncherRow(context, launcher)
        val writer = realWriter()
        val capture = writer.captureCurrent(CaptureId("orientation-compose"))
        val composition = ProductionOrganizationInputComposer(context, writer).composeFullOrganization()
        assertTrue(composition is OrganizationInputComposition.Ready)
        val ready = composition as OrganizationInputComposition.Ready
        assertEquals(
            capture.layoutState.deviceCapabilities.orientation.name,
            ready.input.snapshot.device.orientation.name,
        )
    }

    @Test
    fun orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite() {
        val writer = realWriter()
        val originalAccelerometer = systemSetting(Settings.System.ACCELEROMETER_ROTATION)
        val originalUserRotation = systemSetting(Settings.System.USER_ROTATION)
        try {
            bringLauncherToForeground()
            // The launcher locks portrait on phones; the upstream test hook
            // (TestProtocol REQUEST_ENABLE_ROTATION) lifts that for testing.
            enableLauncherTestRotation()
            lockRotationTo(android.content.res.Configuration.ORIENTATION_PORTRAIT)
            // Issue #292: pin the plan row only after the launcher's first model
            // load. Until that load ran, the pending EMPTY_DATABASE_CREATED flag
            // lets the next loader task delete every favorites row and re-insert
            // the default layout with fresh ids, so a row planned before it could
            // be wiped or its id could collide with a default-layout folder child
            // that the rotation's folder relayout legitimately rewrites.
            awaitLauncherModelLoaded()
            val plannedRowId = ensureLauncherRow(context, launcher)

            val capture = writer.captureCurrent(CaptureId("orientation-stale"))
            assertTrue(capture.layoutState.items.isNotEmpty())
            val plannedId = ItemId(plannedRowId.toString())
            val sourceItem = capture.layoutState.items.single {
                (it.ref as? ApplicationItemRef.PersistentItem)?.itemId == plannedId
            }
            val intendedItem = sourceItem.copy(title = OptionalText.Present("orientation-stale"))
            val plan = ValidatedLayoutPlan(
                capture.revision,
                capture.layoutState,
                capture.layoutState.copy(
                    items = capture.layoutState.items.map { if (it.ref == sourceItem.ref) intendedItem else it },
                ),
                listOf(ApplyAction.Update(sourceItem.ref, sourceItem, intendedItem)),
                emptyList(),
                emptyList(),
                RuleVersion("instrumentation"),
                TaxonomyVersion("instrumentation"),
            )
            val rowsBefore = snapshotFavorites()

            lockRotationTo(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            assertTrue(
                "Host configuration did not report landscape within timeout",
                awaitOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE),
            )

            val clock = SystemClock()
            val diagnostics = RecordingDiagnosticsPort()
            val store = RecoveryStore(context, clock::nowMillis)
            val module = LayoutApplicationModule(
                writer,
                store,
                clock,
                SecureRandomOperationIdSource(),
                folderTitleResolver = GeneratedFolderTitles.resolver(context),
                diagnosticsPort = diagnostics,
            )
            // Spec #435: the oracle must not depend on whether a contention
            // rejection or the stale rejection wins under load. Every attempt
            // re-establishes the preconditions (model loaded, readiness gate
            // READY, recovery store available), and only the stage-tagged
            // pre-revision rejections are retried (decideStaleOracleOutcome).
            val observations = mutableListOf<String>()
            val budgetDeadline = System.currentTimeMillis() + ORACLE_BUDGET_MS
            var staleRejectionObserved = false
            var attempt = 0
            while (System.currentTimeMillis() < budgetDeadline && !staleRejectionObserved) {
                attempt++
                val budgetLeft = System.currentTimeMillis() + RETRY_BACKOFF_MS < budgetDeadline
                val preconditionFailure = establishStaleOraclePreconditions(module, store)
                if (preconditionFailure != null) {
                    observations += "attempt $attempt: precondition not established: $preconditionFailure"
                    Thread.sleep(RETRY_BACKOFF_MS)
                    continue
                }
                val result = try {
                    module.apply(plan)
                } catch (error: Throwable) {
                    throw AssertionError(
                        "attempt $attempt: apply threw an exception; observations:\n" +
                            observations.joinToString("\n"),
                        error,
                    )
                }
                val terminalStage = result.runIdForDiagnostics()?.let { diagnostics.terminalApplyStage(it) }
                val gateState = module.readinessGate.state
                when (decideStaleOracleOutcome(result, terminalStage, gateState, budgetLeft)) {
                    StaleOracleDecision.SUCCESS -> {
                        assertEquals(
                            PreWriteRejection.STALE_REVISION,
                            (result as ApplyResult.Rejected).reason,
                        )
                        assertStaleOracleNoWrite(plannedRowId, rowsBefore, "stale rejection at attempt $attempt")
                        staleRejectionObserved = true
                    }
                    StaleOracleDecision.RETRY_PRECONDITIONS -> {
                        // The rejection is legitimate (pre-revision, pre-write),
                        // but it must not have landed: verify the no-write
                        // invariant before retrying.
                        assertStaleOracleNoWrite(
                            plannedRowId,
                            rowsBefore,
                            "attempt $attempt retryable rejection",
                        )
                        observations += "attempt $attempt: retryable rejection " +
                            "(reason=${result.reasonOf()}, terminalStage=$terminalStage, gateState=$gateState)"
                        Thread.sleep(RETRY_BACKOFF_MS)
                    }
                    StaleOracleDecision.HARD_FAIL -> fail(
                        "attempt $attempt: hard failure result=$result " +
                            "(terminalStage=$terminalStage, gateState=$gateState); observations:\n" +
                            observations.joinToString("\n"),
                    )
                }
            }
            assertTrue(
                "Stale rejection was not observed within the retry budget; attempts:\n" +
                    observations.joinToString("\n"),
                staleRejectionObserved,
            )
            assertStaleOracleNoWrite(plannedRowId, rowsBefore, "final no-write verification")
        } finally {
            restoreRotation(originalAccelerometer, originalUserRotation)
        }
    }

    /**
     * Spec #435 TOR-AC-07: the retry decision table is itself a contract and is
     * verified deterministically, in this class on the same production-input
     * surface (no separate lane, no duplicated scenario).
     */
    @Test
    fun staleOracleRetryDecisionTableIsContractual() {
        val runId = RunId("0123456789abcdef0123456789abcdef")
        val pointId = RecoveryPointId("fedcba9876543210fedcba9876543210")

        // (a) WRITER_BUSY at the A0 lease acquisition: pre-revision, retryable.
        assertEquals(
            StaleOracleDecision.RETRY_PRECONDITIONS,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY),
                ApplyStage.A0,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // (b) RECOVERY_STORE_UNAVAILABLE at the A2 availability probe: likewise.
        assertEquals(
            StaleOracleDecision.RETRY_PRECONDITIONS,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE),
                ApplyStage.A2,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // Gate-level rejections never reach the protocol (no terminal event)
        // and are classified by the readiness gate state.
        assertEquals(
            StaleOracleDecision.RETRY_PRECONDITIONS,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE),
                null,
                ReadinessGate.State.FAILED,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.RETRY_PRECONDITIONS,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY),
                null,
                ReadinessGate.State.RECONCILING,
                budgetLeft = true,
            ),
        )
        // (c) The same reason after the revision check passed (A4 checkpoint
        // store unavailable, A5 markApplying failure) must not be swallowed by
        // a retry — a later STALE_REVISION would mask the contract violation.
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE),
                ApplyStage.A4,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE),
                ApplyStage.A5,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // (d) The expected stale rejection at the A2 capture/revision compare.
        assertEquals(
            StaleOracleDecision.SUCCESS,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION),
                ApplyStage.A2,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // The same reason from the A5 in-transaction reread (or without a
        // terminal event) does not prove the A2 contract and must not succeed.
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION),
                ApplyStage.A5,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION),
                null,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // (e) Every other result is direct evidence of a contract violation.
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Applied(runId, pointId),
                ApplyStage.A8,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.NoChanges(runId),
                ApplyStage.A2,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.EXACT_PRECONDITION_FAILED),
                ApplyStage.A2,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.ConcurrentRun,
                null,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // Contradictory reason/stage/gate combinations are never retried on the
        // reason string alone.
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.RECOVERY_STORE_UNAVAILABLE),
                null,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY),
                ApplyStage.A2,
                ReadinessGate.State.READY,
                budgetLeft = true,
            ),
        )
        // (f) The budget bounds retries only: exhaustion turns a retryable
        // rejection into an explicit failure, while an observed stale rejection
        // stays a success.
        assertEquals(
            StaleOracleDecision.HARD_FAIL,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.WRITER_BUSY),
                ApplyStage.A0,
                ReadinessGate.State.READY,
                budgetLeft = false,
            ),
        )
        assertEquals(
            StaleOracleDecision.SUCCESS,
            decideStaleOracleOutcome(
                ApplyResult.Rejected(runId, PreWriteRejection.STALE_REVISION),
                ApplyStage.A2,
                ReadinessGate.State.READY,
                budgetLeft = false,
            ),
        )
    }

    private fun realWriter() = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)

    /**
     * Spec #435 preconditions (b)-(d) for one oracle attempt. Returns null
     * when all hold, or a human-readable description of the first unmet one
     * that the caller records and retries under the shared bounded budget.
     */
    private fun establishStaleOraclePreconditions(
        module: LayoutApplicationModule<RecoveryStore>,
        store: RecoveryStore,
    ): String? {
        if (!launcher.model.isModelLoaded) {
            return "launcher model not loaded (isModelLoaded=false)"
        }
        if (module.readinessGate.state != ReadinessGate.State.READY) {
            val summary = module.reconcileAtStart()
            if (module.readinessGate.state != ReadinessGate.State.READY || summary.hasUnresolvedFailures()) {
                return "readiness gate not READY (state=${module.readinessGate.state}, " +
                    "summary=${summary.javaClass.simpleName}, " +
                    "unresolvedFailures=${summary.hasUnresolvedFailures()})"
            }
        }
        val availability = store.availability()
        if (availability != RecoveryStorePort.StoreAvailability.READY) {
            return "recovery store not READY (availability=$availability)"
        }
        return null
    }

    /**
     * The no-write invariant of the stale-rejection oracle. Unrelated rows may
     * legitimately change during rotation relayout (the launcher fills
     * placement/modified on folder children), so no-write is asserted on the
     * plan's own row and marker title (Issue #292 discipline).
     */
    private fun assertStaleOracleNoWrite(
        plannedRowId: Long,
        rowsBefore: List<ContentValues>,
        context: String,
    ) {
        val rowsAfter = snapshotFavorites()
        assertTrue(
            "$context: marker title row was written",
            rowsAfter.none { it.getAsString(Favorites.TITLE) == "orientation-stale" },
        )
        assertEquals(
            "$context: plan row changed",
            rowsBefore.single { it.getAsString(Favorites._ID) == plannedRowId.toString() },
            rowsAfter.single { it.getAsString(Favorites._ID) == plannedRowId.toString() },
        )
    }

    /**
     * Issue #292: waits until the launcher model has completed a loader task.
     * [isModelLoaded][com.android.launcher3.LauncherModel.isModelLoaded] flips
     * only after a loader task commits, and every loader task consumes the
     * pending default-workspace load, so returning means no further load can
     * delete or renumber favorites rows. Callers may pin row identities after
     * this returns.
     */
    private fun awaitLauncherModelLoaded() {
        val deadline = System.currentTimeMillis() + 20_000L
        while (!launcher.model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(
            "Launcher model did not complete its first load within timeout; " +
                "row identities cannot be pinned safely against the pending " +
                "default-workspace load",
            launcher.model.isModelLoaded,
        )
    }

    /** Enables launcher rotation for testing on the live activity, as upstream TestProtocol does. */
    private fun enableLauncherTestRotation() {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var enabled = false
            Handler(Looper.getMainLooper()).post {
                try {
                    val activity = Launcher.ACTIVITY_TRACKER.getCreatedActivity<Launcher>()
                    if (activity != null) {
                        activity.getRotationHelper().forceAllowRotationForTesting(true)
                        enabled = true
                    }
                } finally {
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            if (enabled) return
            Thread.sleep(200)
        }
        error("Launcher activity was not created; cannot enable test rotation")
    }

    private fun DeviceOrientation.isTwoPanel() = this == DeviceOrientation.TWO_PANEL_PORTRAIT ||
        this == DeviceOrientation.TWO_PANEL_LANDSCAPE

    private fun lockRotationTo(orientation: Int) {
        shell("settings put system accelerometer_rotation 0")
        val userRotation = when (orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 1

            else -> 0
        }
        shell("settings put system user_rotation $userRotation")
        assertTrue(
            "Host configuration did not report orientation $orientation within timeout",
            awaitOrientation(orientation),
        )
    }

    private fun awaitOrientation(orientation: Int): Boolean {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            if (context.resources.configuration.orientation == orientation) return true
            Thread.sleep(100)
        }
        return context.resources.configuration.orientation == orientation
    }

    private fun restoreRotation(originalAccelerometer: Int?, originalUserRotation: Int?) {
        if (originalUserRotation != null) {
            shell("settings put system user_rotation $originalUserRotation")
        } else {
            shell("settings delete system user_rotation")
        }
        if (originalAccelerometer != null) {
            shell("settings put system accelerometer_rotation $originalAccelerometer")
        } else {
            shell("settings delete system accelerometer_rotation")
        }
    }

    private fun shell(command: String) = shellOutput(command) { true }

    /** Brings the debug launcher home activity to the foreground so host rotation reaches its configuration. */
    private fun bringLauncherToForeground() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(
                ComponentName(context.packageName, LawnchairLauncher::class.java.name),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val focus = shellOutput("dumpsys window") { line -> line.startsWith("  mCurrentFocus") }
            if (focus.contains(context.packageName)) return
            Thread.sleep(250)
        }
        // Fall through: awaitOrientation below reports a precise failure if the
        // launcher never reached the foreground.
    }

    private fun shellOutput(command: String, filter: (String) -> Boolean): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            return stream.readBytes().decodeToString().lineSequence().filter(filter).joinToString("\n")
        }
    }

    private fun systemSetting(name: String): Int? = try {
        Settings.System.getInt(context.contentResolver, name)
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    /**
     * Returns the _ID of an existing or freshly inserted stable launcher row.
     * Callers that later compare this row by id (no-write assertions) must call
     * this only after the launcher model has loaded ([awaitLauncherModelLoaded]):
     * the pending default-workspace load deletes and renumbers favorites rows,
     * so a row planned before it cannot be compared by id (Issue #292).
     */
    private fun ensureLauncherRow(
        context: android.content.Context,
        launcher: LauncherAppState,
    ): Long {
        val db = launcher.model.modelDbController.db
        // Reuse an existing row only when it is a workspace/hotseat item: the
        // no-write assertion below compares this row before and after a
        // rotation, and the launcher's relayout legitimately rewrites
        // placement/modified on folder children (container = a folder id, with
        // null screen/cells). Lane ordering decides whether rows from earlier
        // instrumented classes are present, so without this filter the reused
        // row — and therefore the test's outcome — depends on which row the
        // unordered query happens to return first.
        db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID, Favorites.CONTAINER),
            null,
            null,
            null,
            null,
            Favorites._ID,
        ).use {
            while (it.moveToNext()) {
                val container = it.getLong(it.getColumnIndexOrThrow(Favorites.CONTAINER))
                if (container == Favorites.CONTAINER_DESKTOP.toLong() || container == Favorites.CONTAINER_HOTSEAT.toLong()) {
                    return it.getLong(it.getColumnIndexOrThrow(Favorites._ID))
                }
            }
        }
        val id = launcher.model.modelDbController.generateNewItemId()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, "Orientation capture")
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_HOTSEAT)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()))
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, 1)
            },
        )
        return id.toLong()
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val db = launcher.model.modelDbController.db
        val rows = mutableListOf<ContentValues>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows.add(readRow(cursor, columns))
        }
        return rows
    }

    private fun readRow(cursor: Cursor, columns: Array<String>): ContentValues {
        val values = ContentValues()
        for (index in columns.indices) {
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(columns[index])
                Cursor.FIELD_TYPE_INTEGER -> values.put(columns[index], cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(columns[index], cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(columns[index], cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(columns[index], cursor.getBlob(index))
            }
        }
        return values
    }

    private fun restoreFavorites(snapshot: List<ContentValues>) {
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (row in snapshot) {
                db.insertOrThrow(Favorites.TABLE_NAME, null, row)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
