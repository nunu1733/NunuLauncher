package app.lawnchair.organizer.application

import android.content.ComponentName
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.ReconciliationPublicResult
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.ReadinessGate
import app.lawnchair.organizer.application.protocol.RestartReconciler
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.public.ApplyFailure
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.ProductionManualOrganizationApplication
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.TaxonomyVersion
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Process-death driver through the real public apply seam and durable production store. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OrganizerRecoveryInstrumentationTest {
    @Test
    fun faultPauseOrVerifyTypedLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("organizerFaultPhase", "READY")
        when (args.getString("organizerMode", "VERIFY_ONLY")) {
            "FAULT_INJECTION" -> runFault(context, phase)
            "VERIFY_ONLY" -> verifyAfterRestart(context, phase)
            else -> error("Unknown organizerMode")
        }
    }

    private fun runFault(context: Context, phase: String) {
        val launcher = LauncherAppState.getInstance(context)
        val controller = launcher.model.modelDbController
        ensureSmokeRow(context, controller)
        controller.db.execSQL(
            "UPDATE ${Favorites.TABLE_NAME} SET ${Favorites.ORGANIZER_LOCK_STATE}=1 " +
                "WHERE ${Favorites.ORGANIZER_LOCK_STATE}=0",
        )
        val writer = LauncherLayoutAdapter(context, controller, launcher.model)
        val clock = SystemClock()
        val module = LayoutApplicationModule(
            writer,
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
            SmokeFaultInjector(context, phase),
        )
        module.reconcileAtStart()
        // Issue #177: a paused apply never returns, so any return here means the
        // fault point was not reached. The apply result used to be discarded,
        // which turned a transient rejection (e.g. WRITER_BUSY from a baseline
        // writer holding the process-wide lease right after process start) into
        // a silent "did not pause" failure. Only pre-mutation, startup-race-
        // shaped rejections are retried with a fresh capture/plan; every other
        // result — including genuine post-mutation outcomes (RolledBack,
        // Unresolved, RecoveryFailed) that retrying could absorb — fails the
        // phase immediately with the typed result.
        var attempts = 0
        while (true) {
            val freshCapture = writer.captureCurrent(CaptureId("smoke-source"))
            val expected = freshCapture.layoutState.items.firstOrNull()
                ?: error("Smoke requires at least one launcher row")
            val intended = expected.copy(
                lockState = if (expected.lockState == OrganizerLockState.LOCKED) {
                    OrganizerLockState.UNLOCKED
                } else {
                    OrganizerLockState.LOCKED
                },
            )
            val plan = ValidatedLayoutPlan(
                freshCapture.revision,
                freshCapture.layoutState,
                freshCapture.layoutState.copy(
                    items = freshCapture.layoutState.items.map { if (it.ref == expected.ref) intended else it },
                ),
                listOf(ApplyAction.Update(expected.ref, expected, intended)),
                emptyList(),
                emptyList(),
                RuleVersion("smoke"),
                TaxonomyVersion("smoke"),
            )
            val result = module.apply(plan)
            Log.i(TAG, "FAULT_APPLY_NOT_PAUSED phase=$phase attempt=$attempts result=$result")
            if (!(result is ApplyResult.Rejected && result.reason in TRANSIENT_APPLY_REJECTIONS)) {
                error("Fault phase $phase did not pause (apply returned $result)")
            }
            check(attempts < 5) {
                "Fault phase $phase did not pause after $attempts transient rejections (last=$result)"
            }
            attempts++
            Thread.sleep(500)
        }
    }

    private fun ensureSmokeRow(
        context: Context,
        controller: com.android.launcher3.model.ModelDbController,
    ) {
        controller.db.delete(Favorites.TABLE_NAME, null, null)
        val id = controller.generateNewItemId()
        val launcherComponent = ComponentName(context.packageName, LawnchairLauncher::class.java.name)
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(launcherComponent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val values = ContentValues().apply {
            put(Favorites._ID, id)
            put(Favorites.TITLE, "Organizer recovery smoke")
            put(Favorites.INTENT, homeIntent.toUri(0))
            put(Favorites.CONTAINER, Favorites.CONTAINER_HOTSEAT)
            put(Favorites.SCREEN, 0)
            put(Favorites.CELLX, 0)
            put(Favorites.CELLY, 0)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.APPWIDGET_ID, -1)
            put(Favorites.MODIFIED, System.currentTimeMillis())
            put(Favorites.RESTORED, 0)
            put(
                Favorites.PROFILE_ID,
                UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
            )
            put(Favorites.RANK, 0)
            put(Favorites.OPTIONS, 0)
            put(Favorites.APPWIDGET_SOURCE, -1)
            put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
        }
        check(controller.db.insertOrThrow(Favorites.TABLE_NAME, null, values) == id.toLong()) {
            "Unable to seed organizer recovery smoke row"
        }
    }

    private fun recordLifecycleOf(store: RecoveryStore, rawId: String): LifecycleState? = (
        store.readRecord(RecoveryPointId(rawId)) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.lifecycle

    private fun recordChecksumValidOf(store: RecoveryStore, rawId: String): Boolean = (
        store.readRecord(RecoveryPointId(rawId)) as? RecoveryStorePort.RecordRead.Readable
        )?.record?.checksumValid ?: false

    private fun verifyAfterRestart(context: Context, phase: String) {
        val scenario = ActivityScenario.launch(LawnchairLauncher::class.java)
        val launcher = LauncherAppState.getInstance(context)
        var modelAttempts = 0
        while (!launcher.model.isModelLoaded && modelAttempts < 240) {
            Thread.sleep(250)
            modelAttempts++
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not load for restart verification" }
        // Issue #177: the app-owned startup reconciliation runs asynchronously on
        // its own thread and holds the ORGANIZER writer lease (through
        // requestCorrelatedReload + verification) while it advances the fault-run
        // record. Wait until its readiness gate reaches READY: the gate leaves
        // IDLE only once the pass actually runs, and reaches READY only after the
        // whole pass — including the writer-lease hold — has finished, so the
        // verify reconcile below cannot contend with the startup pass itself.
        // FAILED is a production startup failure and must fail the smoke, not be
        // absorbed by the fresh test module.
        val appModule = (context.applicationContext as LawnchairApp).layoutApplicationModule
        var gateAttempts = 0
        while (appModule.readinessGate.state != ReadinessGate.State.READY && gateAttempts < 240) {
            Thread.sleep(250)
            gateAttempts++
        }
        check(appModule.readinessGate.state == ReadinessGate.State.READY) {
            "Startup reconciliation did not become READY before restart verification " +
                "(state=${appModule.readinessGate.state})"
        }
        // Issue #177: even with the gate READY, the process-wide
        // LayoutWriteCoordinator also serializes baseline MODEL_WRITER mutations
        // (e.g. model work spawned by the startup pass's correlated reload), so
        // the verify reconcile can still transiently lose tryAcquireLease and
        // surface Unresolved(COMMIT_OUTCOME_UNKNOWN). Re-run only while the
        // summary reports that contention-capable shape, until it is quiescent;
        // genuine ambiguous states are deterministic and keep failing every
        // retry, so the exact Clean assert below still fails for them.
        val manualModule = LayoutApplicationModule.production(context, launcher)
        var summary = manualModule.reconcileAtStart()
        var settleAttempts = 0
        while (
            summary is RestartReconciler.ReconciliationSummary.Resolved &&
            summary.publicResults.any { isContentionCapable(it) } &&
            settleAttempts < 20
        ) {
            Log.i(TAG, "SETTLING phase=$phase attempt=$settleAttempts summary=$summary")
            Thread.sleep(500)
            summary = manualModule.reconcileAtStart()
            settleAttempts++
        }
        assertEquals(
            RestartReconciler.ReconciliationSummary.Clean,
            summary,
        )
        val manualRun = ManualOrganizationRun(
            ProductionManualOrganizationApplication(
                context,
                manualModule,
            ),
        )
        manualRun.start()
        check(manualRun.state !is ManualOrganizationRun.State.InputUnavailable) {
            "Manual operation remained blocked after restart reconciliation: ${manualRun.state}"
        }
        val rawId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POINT_ID, null)
        requireNotNull(rawId) { "Fault run did not persist a recovery point id" }
        val store = RecoveryStore(context) { System.currentTimeMillis() }
        var lifecycle = recordLifecycleOf(store, rawId)
        var attempts = 0
        while (attempts < 240) {
            val complete = when (phase) {
                "READY", "AROUND_COMMIT" -> lifecycle == null
                "COMMITTED_UNVERIFIED" -> lifecycle == LifecycleState.VERIFIED
                "RESTORING" -> lifecycle == LifecycleState.RESTORED
                else -> error("Unknown phase $phase")
            }
            if (complete) break
            Thread.sleep(250)
            lifecycle = recordLifecycleOf(store, rawId)
            attempts++
        }
        when (phase) {
            "READY", "AROUND_COMMIT" -> assertNull(lifecycle)
            "COMMITTED_UNVERIFIED" -> assertEquals(LifecycleState.VERIFIED, lifecycle)
            "RESTORING" -> assertEquals(LifecycleState.RESTORED, lifecycle)
            else -> error("Unknown phase $phase")
        }
        Log.i(
            TAG,
            "VERIFIED phase=$phase lifecycle=${lifecycle ?: "PRUNED"} " +
                "manualState=${manualRun.state} typed=true",
        )
        reportToHost(
            "VERIFIED phase=$phase lifecycle=${lifecycle ?: "PRUNED"} " +
                "manualState=${manualRun.state} typed=true",
        )
        assertTrue(lifecycle == null || recordChecksumValidOf(store, rawId))
        scenario.close()
    }

    private class SmokeFaultInjector(
        private val context: Context,
        private val requested: String,
    ) : FaultInjector by FaultInjector.NOOP {
        override fun beforeRecoveryLifecycleCommit(
            phase: FaultInjector.RecoveryLifecyclePhase,
            pointId: RecoveryPointId,
        ) {
            if (requested == "READY" && phase == FaultInjector.RecoveryLifecyclePhase.APPLYING) {
                pause(pointId, "READY")
            }
        }

        override fun atTransactionClose(pointId: RecoveryPointId?): FaultInjector.TransactionCloseDirective {
            if (requested == "AROUND_COMMIT") pause(requireNotNull(pointId), "AROUND_COMMIT")
            return FaultInjector.TransactionCloseDirective.PROCEED
        }

        override fun afterRecoveryLifecycleCommit(
            phase: FaultInjector.RecoveryLifecyclePhase,
            pointId: RecoveryPointId,
        ) {
            remember(pointId)
            if (requested == "RESTORING" && phase == FaultInjector.RecoveryLifecyclePhase.RESTORING) {
                pause(pointId, "RESTORING")
            }
        }

        override fun beforeModelReloadRequest() {
            if (requested == "COMMITTED_UNVERIFIED") pause(savedPointId(), "COMMITTED_UNVERIFIED")
        }

        override fun afterCorrelatedGenerationWait(): FaultInjector.ReloadDirective =
            if (requested == "RESTORING") FaultInjector.ReloadDirective.FAIL else FaultInjector.ReloadDirective.PROCEED

        private fun pause(pointId: RecoveryPointId, lifecycle: String): Nothing {
            remember(pointId)
            val marker = "PAUSED phase=$requested pointId=${pointId.value} lifecycle=$lifecycle typed=true"
            Log.i(TAG, marker)
            reportToHost(marker)
            CountDownLatch(1).await()
            error("unreachable")
        }

        private fun remember(pointId: RecoveryPointId) {
            check(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_POINT_ID, pointId.value).commit(),
            ) { "Unable to persist smoke recovery point id" }
        }

        private fun savedPointId(): RecoveryPointId {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_POINT_ID, null)
            return requireNotNull(id).let(::RecoveryPointId)
        }
    }

    private companion object {
        const val TAG = "OrganizerRecoverySmoke"
        const val PREFS = "organizer_recovery_smoke"
        const val KEY_POINT_ID = "point_id"

        /**
         * Issue #177 review: the only pre-mutation rejections a concurrent
         * baseline writer racing the fresh fault-run process can produce. All
         * `Rejected` variants perform no layout mutation, but only these two
         * reflect a transient state desync that a fresh capture/plan provably
         * resolves; every other result (persistent rejections, or post-mutation
         * RolledBack/Unresolved/RecoveryFailed/Applied/NoChanges that retrying
         * could absorb) fails the fault phase immediately.
         */
        private val TRANSIENT_APPLY_REJECTIONS = setOf(
            PreWriteRejection.WRITER_BUSY,
            PreWriteRejection.STALE_REVISION,
            PreWriteRejection.EXACT_PRECONDITION_FAILED,
        )

        /**
         * Issue #177: the only reconciliation failure shape that losing the
         * process-wide writer lease can produce. `COMMIT_OUTCOME_UNKNOWN` is
         * also produced by genuinely ambiguous durable states, so this shape is
         * a necessary — not sufficient — contention signal; it only makes the
         * reconcile pass retry-eligible. A genuine ambiguity is deterministic
         * and recurs on every retry, so the bounded loop still ends in the
         * exact-Clean assert failing with the real summary.
         */
        fun isContentionCapable(result: ReconciliationPublicResult): Boolean =
            result is ReconciliationPublicResult.Unresolved &&
                result.outcome is ApplyResult.Unresolved &&
                result.outcome.failure == ApplyFailure.COMMIT_OUTCOME_UNKNOWN

        fun reportToHost(marker: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("stream", "$TAG $marker\n") },
            )
        }
    }
}
