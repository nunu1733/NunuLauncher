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
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #299 I-1 #298-correlation experiment: the widget repair
 * ([com.android.launcher3.model.WorkspaceItemProcessor.processWidget]) runs
 * inside the restore's reload generation. Interrupting that generation the
 * way the #298 wrong-thread failures interrupt loads must leave the widget
 * row unbound and capture fail-closed, and the test records whether a later
 * completed generation repairs it (the persistence question behind the
 * observed unrecovered session). Run in its own instrumentation process
 * (see [NovaRestoreCaptureTestBase]).
 */
class NovaRestoreCaptureInterruptedReloadTest : NovaRestoreCaptureTestBase() {

    @Test
    fun interruptedReloadLeavesUnboundWidgetRow_untilALaterCompletedGenerationRepairs() {
        val provider = firstWidgetProviderFlatten()
        val restored = restoreSyntheticBackup(includeWidget = true, widgetProvider = provider)

        val loadedAtReturn = isModelLoaded()
        Log.i(
            TAG,
            "interrupt/isModelLoaded at convertAndRestore return (restore API return != completion): $loadedAtReturn",
        )

        // Interrupt the in-flight generation immediately (the loader takes
        // hundreds of ms; the quiesce lands ~ms after the restore returns),
        // the way a wrong-thread failure / stopLoader aborts a load
        // mid-repair. The latch state after the quiesce annotates whether the
        // generation had already bound+signaled before the interrupt.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            launcher.model.quiesceForRestore()
        }
        val completedBeforeInterrupt = restored.barrier.count == 0L
        Log.i(TAG, "interrupt/generationCompletedBeforeInterrupt=$completedBeforeInterrupt")

        val observed = mutableListOf<Class<out Throwable>>()
        val interruptedCapture = captureThroughProductionSource(observed)
        val postInterrupt = widgetRowCount("interrupt/postInterrupt")
        logMatrix("interrupt/postInterrupt", restored.info)
        Log.i(
            TAG,
            "interrupt/captureWhileInterrupted: ready=$interruptedCapture observed=${observed.map { it.simpleName }}",
        )
        if (!completedBeforeInterrupt) {
            assertTrue(
                "an interrupted generation must leave the workspace un-capturable until a repair completes",
                !interruptedCapture,
            )
            assertEquals(
                "interrupted repair must leave the codec widget invariant violated",
                listOf(IllegalArgumentException::class.java),
                observed,
            )
            assertEquals(
                "interrupted repair must leave the widget row unbound in the DB",
                1 to 0,
                postInterrupt,
            )
        }

        // A later completed generation: records whether the repair still runs
        // (persistence question behind the observed unrecovered session).
        forceReloadAndAwaitBarrier("post-interruption generation")
        val afterLaterGeneration = widgetRowCount("interrupt/afterLaterGeneration")
        val laterCapture = captureThroughProductionSource()
        Log.i(
            TAG,
            "interrupt/afterLaterGeneration: widgetIdNegative=${afterLaterGeneration.first} " +
                "widgetIdValid=${afterLaterGeneration.second} ready=$laterCapture",
        )
        assertTrue("a later completed generation must repair and capture Ready", laterCapture)
    }
}
