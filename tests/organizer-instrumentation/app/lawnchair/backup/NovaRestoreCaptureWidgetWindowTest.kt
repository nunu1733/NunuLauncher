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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #299 I-1 widget window: the Nova path persists the widget row with
 * appWidgetId=-1 (no rebind exists in the restore itself), so capture through
 * the production source fails closed with the codec widget invariant
 * (`RowManifestCodec`: "Widget is missing its appWidgetId") BEFORE the reload
 * barrier, and the reload generation's widget repair
 * ([com.android.launcher3.model.WorkspaceItemProcessor.processWidget]) binds a
 * real id so capture is Ready after the barrier. This pins both the
 * CAPTURE_INVALID window and the repair that normally closes it. Run in its
 * own instrumentation process (see [NovaRestoreCaptureTestBase]).
 */
class NovaRestoreCaptureWidgetWindowTest : NovaRestoreCaptureTestBase() {

    @Test
    fun novaRestoreWithUnboundWidget_captureInvalidBeforeBarrier_readyAfterRepair() {
        val provider = firstWidgetProviderFlatten()
        val restored = restoreSyntheticBackup(includeWidget = true, widgetProvider = provider)

        // Pre-barrier: the row is still unbound -> composer fail-closed, and
        // the shipped diagnostics observer records the exception identity.
        val observed = mutableListOf<Class<out Throwable>>()
        val preBarrierReady = captureThroughProductionSource(observed)
        Log.i(TAG, "widget/immediateCapture (pre-barrier): ready=$preBarrierReady observed=${observed.map { it.simpleName }}")
        logMatrix("widget/postRestore", restored.info)
        assertEquals(
            "unbound widget row must be present in the DB before the barrier",
            1 to 0,
            widgetRowCount("widget/postRestore"),
        )
        assertTrue(
            "capture before the completion barrier must fail closed on the unbound widget row",
            !preBarrierReady,
        )
        assertEquals(
            "predicted codec invariant: widget row without a bound appWidgetId",
            listOf(IllegalArgumentException::class.java),
            observed,
        )

        // Post-barrier: the reload generation repaired the widget row.
        awaitRestoreReloadBarrier("widget cycle 1", restored)
        logMatrix("widget/afterBarrier", restored.info)
        assertEquals(
            "the reload generation's widget repair must bind the row",
            0 to 1,
            widgetRowCount("widget/afterBarrier"),
        )
        assertTrue(
            "after the completion barrier the repaired widget row must capture Ready",
            captureThroughProductionSource(),
        )
    }
}
