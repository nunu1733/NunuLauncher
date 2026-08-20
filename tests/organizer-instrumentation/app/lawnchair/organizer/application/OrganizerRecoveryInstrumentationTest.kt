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
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.OrganizerLockState
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
        val capture = writer.captureCurrent(CaptureId("smoke-source"))
        val expected = capture.layoutState.items.firstOrNull()
            ?: error("Smoke requires at least one launcher row")
        val intended = expected.copy(
            lockState = if (expected.lockState == OrganizerLockState.LOCKED) {
                OrganizerLockState.UNLOCKED
            } else {
                OrganizerLockState.LOCKED
            },
        )
        val plan = ValidatedLayoutPlan(
            capture.revision,
            capture.layoutState,
            capture.layoutState.copy(
                items = capture.layoutState.items.map { if (it.ref == expected.ref) intended else it },
            ),
            listOf(ApplyAction.Update(expected.ref, expected, intended)),
            emptyList(),
            emptyList(),
            RuleVersion("smoke"),
            TaxonomyVersion("smoke"),
        )
        val clock = SystemClock()
        val module = LayoutApplicationModule(
            writer,
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
            SmokeFaultInjector(context, phase),
        )
        module.reconcileAtStart()
        module.apply(plan)
        error("Fault phase $phase did not pause")
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

    private fun verifyAfterRestart(context: Context, phase: String) {
        val scenario = ActivityScenario.launch(LawnchairLauncher::class.java)
        val launcher = LauncherAppState.getInstance(context)
        var modelAttempts = 0
        while (!launcher.model.isModelLoaded && modelAttempts < 240) {
            Thread.sleep(250)
            modelAttempts++
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not load for restart verification" }
        (context.applicationContext as LawnchairApp).layoutApplicationModule.reconcileAtStart()
        val manualRun = ManualOrganizationRun(
            ProductionManualOrganizationApplication(
                context,
                LayoutApplicationModule.production(context, launcher),
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
        var record = store.readRecord(RecoveryPointId(rawId))
        var attempts = 0
        while (attempts < 240) {
            val complete = when (phase) {
                "READY", "AROUND_COMMIT" -> record == null
                "COMMITTED_UNVERIFIED" -> record?.lifecycle == LifecycleState.VERIFIED
                "RESTORING" -> record?.lifecycle == LifecycleState.RESTORED
                else -> error("Unknown phase $phase")
            }
            if (complete) break
            Thread.sleep(250)
            record = store.readRecord(RecoveryPointId(rawId))
            attempts++
        }
        when (phase) {
            "READY", "AROUND_COMMIT" -> assertNull(record)
            "COMMITTED_UNVERIFIED" -> assertEquals(LifecycleState.VERIFIED, record?.lifecycle)
            "RESTORING" -> assertEquals(LifecycleState.RESTORED, record?.lifecycle)
            else -> error("Unknown phase $phase")
        }
        Log.i(
            TAG,
            "VERIFIED phase=$phase lifecycle=${record?.lifecycle ?: "PRUNED"} " +
                "manualState=${manualRun.state} typed=true",
        )
        reportToHost(
            "VERIFIED phase=$phase lifecycle=${record?.lifecycle ?: "PRUNED"} " +
                "manualState=${manualRun.state} typed=true",
        )
        assertTrue(record == null || record.checksumValid)
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

        fun reportToHost(marker: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("stream", "$TAG $marker\n") },
            )
        }
    }
}
