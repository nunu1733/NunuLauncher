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
package app.lawnchair.backup

import android.util.Log
import java.lang.IllegalArgumentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #299 I-4 residual: cross-process persistence. Two stages run in two
 * separate instrumentation processes (run each class once, A then B, each in
 * its own `am instrument` invocation):
 *
 * - Stage A (`NovaRestoreCaptureCrossProcessStageATest`): restore a widget
 *   fixture and end the process right after the restore returns (the
 *   instrumentation runner force-stops the app), before any reload
 *   generation settles.
 * - Stage B (`NovaRestoreCaptureCrossProcessStageBTest`, fresh process):
 *   keeps the persisted workspace (base-setUp reset skipped) and inspects
 *   what actually survived the process death, pins the capture behavior
 *   against it, and drives one settle generation (cross-process recovery).
 *
 * I-4 observed outcome (2026-09-13): the death-window does NOT preserve the
 * unbound widget row. The interrupted generation's sanitize transaction
 * commits the deletion repair (markDeleted of the not-installed deep-shortcut
 * row) and the loader's restore sanitize runs inside the kill window, so the
 * persisted favorites can be empty when the next process starts. Process
 * death in this window collapses to either "deletion repair committed" or
 * "empty workspace read on restart" — not to a persistent unbound row. The
 * persistent-invalid variant remains exclusive to a *continuously
 * interrupted* repair generation (see
 * `NovaRestoreCaptureInterruptedReloadTest`), which the stage-B assertions
 * below treat as the not-observed case.
 */
class NovaRestoreCaptureCrossProcessStageATest : NovaRestoreCaptureTestBase() {

    @Test
    fun stageA_restoreAndEndBeforeAnyRepairGenerationSettles() {
        val provider = firstWidgetProviderFlatten()
        writeStageMarker("A")

        // Restore. convertAndRestore dispatches its reload generations; the
        // instrumentation runner force-stops the app right after the test
        // ends, so no completed repair generation runs in this process
        // (matching the observed session where the app died / restarted
        // around the restore).
        val restored = restoreSyntheticBackup(includeWidget = true, widgetProvider = provider)
        val rowState = widgetRowCount("crossProcess/A/postRestore")
        logMatrix("crossProcess/A/postRestore", restored.info)
        Log.i(TAG, "crossProcess/A: rowState=$rowState (process ends without settle; row must be persisted)")
        assertEquals("stage A must leave the unbound widget row in the DB", 1 to 0, rowState)
    }
}

class NovaRestoreCaptureCrossProcessStageBTest : NovaRestoreCaptureTestBase() {

    @Test
    fun stageB_persistedStateAfterDeathIsRecorded_settlesToReady() {
        // Tell the base setUp to keep the persisted workspace (the state
        // under test) instead of resetting it.
        writeStageMarker("B_keep_workspace")

        // Stage B runs in a fresh process. Its first loader generation (in
        // setUp) has already read the persisted DB; record what actually
        // survived the process death.
        val rowState = widgetRowCount("crossProcess/B/preRestoreCheck")
        val totalRows = favoritesRowCount()
        Log.i(
            TAG,
            "crossProcess/B: persisted state after process death rows=$totalRows " +
                "widgetState=$rowState (I-4: death window collapses to deletion-repair " +
                "or empty-workspace read, not a persistent unbound row)",
        )

        // Pin the capture behavior against whatever persisted: capture must
        // be deterministic w.r.t. the workspace — invalid only if an unbound
        // widget row is present.
        val observed = mutableListOf<Class<out Throwable>>()
        val ready = captureThroughProductionSource(observed)
        Log.i(
            TAG,
            "crossProcess/B: capture after process death ready=$ready observed=${observed.map { it.simpleName }}",
        )
        if (rowState.first > 0) {
            assertTrue("unbound persisted row must fail closed", !ready)
            assertEquals(
                "same codec widget invariant across the process boundary",
                listOf(IllegalArgumentException::class.java),
                observed,
            )
        } else {
            assertTrue(
                "workspace without unbound widget rows must capture Ready (empty workspace included)",
                ready,
            )
        }

        // A later completed generation in this process settles the persisted
        // state (repair or keep): cross-process recovery.
        forceReloadAndAwaitBarrier("crossProcess/B/settle")
        val afterSettle = widgetRowCount("crossProcess/B/afterSettle")
        val afterReady = captureThroughProductionSource()
        Log.i(
            TAG,
            "crossProcess/B: afterSettle rowState=$afterSettle ready=$afterReady",
        )
        assertEquals("no unbound widget row may survive the settle generation", 0, afterSettle.first)
        assertTrue("cross-process recovery must settle to Ready", afterReady)
    }

    private fun favoritesRowCount(): Int =
        launcher.model.modelDbController.db.query(
            com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME,
            null, null, null, null, null, null,
        ).use { it.count }
}
