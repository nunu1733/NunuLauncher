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
 * Issue #299 CI-AC-02 at the restore API boundary: the restore's completion
 * barrier (NovaBackupConverter's settle wait) holds `convertAndRestore` until
 * the repair-carrying reload generation settles, so a Nova-restored widget row
 * is already bound when the restore returns and the first authoritative
 * capture succeeds. Run in its own instrumentation process (see
 * [NovaRestoreCaptureTestBase]).
 */
class NovaRestoreCaptureWidgetWindowTest : NovaRestoreCaptureTestBase() {

    @Test
    fun novaRestoreWithWidget_returnsAfterSettleBarrierWithBoundRowAndReadyCapture() {
        val provider = firstWidgetProviderFlatten()
        val restored = restoreSyntheticBackup(includeWidget = true, widgetProvider = provider)

        // The completion barrier must have held the restore until the repair
        // generation settled: the row is bound at return time (CI-AC-02).
        logMatrix("widget/postRestore", restored.info)
        assertEquals(
            "the restore completion barrier must settle the widget repair before returning",
            0 to 1,
            widgetRowCount("widget/postRestore"),
        )
        assertTrue(
            "after the settle barrier the repaired widget row must capture Ready",
            captureThroughProductionSource(),
        )
    }
}
