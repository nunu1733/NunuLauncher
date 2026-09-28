/*
 * Copyright 2026, NunuLauncher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.lawnchair.organizer.application

import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import android.os.Process
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Issue #265 writer-busy observation: an immediate confirm after apply — the
 * one-off "Restore did nothing" surface — records the observed recovery
 * result without asserting it.
 *
 * Spec 265-post-apply-recovery-reconciliation dispositioned this observation
 * as report-only (will-not-investigate), so unlike
 * [Issue265GateFailedRouteInstrumentationTest] it carries no pass/fail oracle
 * and is deliberately NOT part of any CI lane class list (Issue #458 Phase 2
 * review: an assertion-free diagnostic must not ride a permanent conditional
 * gate). Run it locally via `am instrument` when reproducing #265-adjacent
 * recovery surfaces; the fixture copies the routed class's seeding to keep
 * the observation conditions identical.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Issue265WriterBusyObservationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private lateinit var launcherScenario: ActivityScenario<LawnchairLauncher>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Issue #371: the JIT Usage Access pause gates an ungranted process's
        // first composition at the composed-phase entry. This observation
        // drives production runs to Applied, so grant the app-op — the
        // granted fast path.
        instrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} GET_USAGE_STATS allow",
        ).close()
        Thread.sleep(1000)
        launcher = LauncherAppState.getInstance(context)
        launcherScenario = ActivityScenario.launch(LawnchairLauncher::class.java)
        awaitModelLoaded()
    }

    @After
    fun tearDown() {
        try {
            ManualOrganizationModule.get(context).cancel()
            ManualOrganizationModule.get(context).dismiss()
        } finally {
            if (::launcherScenario.isInitialized) launcherScenario.close()
        }
    }

    @Test
    fun writerBusy_immediateConfirmAfterApply() {
        val runner = ManualOrganizationModule.get(context)
        seedLayoutWithFolder()
        runner.start()
        if (runner.state is ManualOrganizationRun.State.Selecting) {
            runner.confirmSelection(emptySet())
        }
        check(runner.state is ManualOrganizationRun.State.ScopeConfirmed) {
            "Run did not reach the method-choice face: ${runner.state}"
        }
        runner.planWithConfirmedScope()
        if (runner.state is ManualOrganizationRun.State.Preview) {
            runner.confirm()
        }
        val applied = runner.state as? ManualOrganizationRun.State.Applied
            ?: error("organize did not reach Applied: ${runner.state}")
        val pointId = (applied.result as ApplyResult.Applied).pointId

        // No settling wait: confirm immediately, as the original report did.
        runner.beginRecoveryPreview()
        val previewState = runner.state as? ManualOrganizationRun.State.RecoveryPreview
        if (previewState == null) {
            report("WRITERBUSY_PREVIEW_STATE=${runner.state}")
        } else {
            runner.confirmRecovery()
            val result = runner.state
            report("WRITERBUSY_CONFIRM_RESULT=$result")
            if (result is ManualOrganizationRun.State.RecoveryResultState &&
                result.result is app.lawnchair.organizer.application.public.RecoveryResult.WriterBusy
            ) {
                report("WRITERBUSY_OBSERVED=true point=$pointId")
            }
        }
        runner.dismiss()
    }

    private fun instrumentation(): Instrumentation =
        InstrumentationRegistry.getInstrumentation()

    private fun awaitModelLoaded() {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!launcher.model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        check(launcher.model.isModelLoaded) { "Launcher model did not reload" }
    }

    private fun seedLayoutWithFolder() {
        launcher.model.modelDbController.db.delete(Favorites.TABLE_NAME, null, null)
        val folderId = launcher.model.modelDbController.generateNewItemId()
        insertFolderRow(folderId, cellX = 0, cellY = 2)
        insertLauncherRow("Issue265 C1", folderId, rank = 0)
        insertLauncherRow("Issue265 C2", folderId, rank = 1)
        insertLauncherRow("Issue265 C3", folderId, rank = 2)
        insertLauncherRow("Issue265 S1", Favorites.CONTAINER_DESKTOP, cellX = 2, cellY = 2)
        insertLauncherRow("Issue265 S2", Favorites.CONTAINER_DESKTOP, cellX = 3, cellY = 2)
        insertLauncherRow("Issue265 S3", Favorites.CONTAINER_DESKTOP, cellX = 4, cellY = 2)
        launcher.model.forceReload()
        awaitModelLoaded()
    }

    private fun insertFolderRow(id: Int, cellX: Int, cellY: Int) {
        val values = ContentValues().apply {
            put(Favorites._ID, id)
            put(Favorites.TITLE, "Issue265 folder")
            put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            put(Favorites.SCREEN, 0)
            put(Favorites.CELLX, cellX)
            put(Favorites.CELLY, cellY)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
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
            put(
                Favorites.ORGANIZER_LOCK_STATE,
                app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED.ordinal,
            )
        }
        check(
            launcher.model.modelDbController.db.insertOrThrow(Favorites.TABLE_NAME, null, values) == id.toLong(),
        ) { "Unable to seed folder row" }
    }

    private fun insertLauncherRow(
        title: String,
        container: Int,
        rank: Int = 0,
        cellX: Int = 0,
        cellY: Int = 0,
    ) {
        val id = launcher.model.modelDbController.generateNewItemId()
        val launcherComponent = ComponentName(context.packageName, LawnchairLauncher::class.java.name)
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(launcherComponent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val values = ContentValues().apply {
            put(Favorites._ID, id)
            put(Favorites.TITLE, title)
            put(Favorites.INTENT, homeIntent.toUri(0))
            put(Favorites.CONTAINER, container)
            if (container == Favorites.CONTAINER_DESKTOP) {
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
            }
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.APPWIDGET_ID, -1)
            put(Favorites.MODIFIED, System.currentTimeMillis())
            put(Favorites.RESTORED, 0)
            put(
                Favorites.PROFILE_ID,
                UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
            )
            put(Favorites.RANK, rank)
            put(Favorites.OPTIONS, 0)
            put(Favorites.APPWIDGET_SOURCE, -1)
            put(
                Favorites.ORGANIZER_LOCK_STATE,
                app.lawnchair.organizer.application.public.OrganizerLockState.UNLOCKED.ordinal,
            )
        }
        check(
            launcher.model.modelDbController.db.insertOrThrow(Favorites.TABLE_NAME, null, values) == id.toLong(),
        ) { "Unable to seed row $title" }
    }

    private fun report(line: String) {
        android.util.Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "Issue265WriterBusy"
    }
}
