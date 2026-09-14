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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #299 I-1 control: a Nova restore whose fixture has no widget row
 * must capture Ready at the settle point (the investigation substitute
 * for the spec's completion barrier; see the I-1 assessment). Run in its own
 * instrumentation process (see [NovaRestoreCaptureTestBase]).
 */
class NovaRestoreCaptureControlTest : NovaRestoreCaptureTestBase() {

    @Test
    fun novaRestoreWithoutWidget_capturesReadyAfterBarrier() {
        val restored = restoreSyntheticBackup(includeWidget = false)
        logMatrix("control/postRestore", restored.info)

        awaitSettlePoint("control", restored)
        val ready = captureThroughProductionSource()
        assertTrue(
            "control Nova restore must capture Ready at the settle point",
            ready,
        )
        // 10 fixture rows enter the restore; the loader's restore sanitize
        // removes the deep-shortcut row whose package is not installed
        // (WorkspaceItemProcessor "removing app that is not restored and not
        // installing"), so the post-barrier canonical capture carries 9.
        assertEquals(9, captureItemCount("issue299-control"))
    }
}
