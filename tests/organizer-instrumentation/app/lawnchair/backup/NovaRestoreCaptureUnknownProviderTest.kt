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
 * Issue #299 I-2 matrix extension: a widget row whose provider is not
 * installed cannot be bound. The loader's restore sanitize resolves pending
 * widgets either by binding (installed provider) or by deleting the row
 * ([com.android.launcher3.model.WorkspaceItemProcessor.processWidget]
 * markDeleted branch). Until the settle point the row is capture-invalid;
 * the deletion repair must close the window. Run in its own instrumentation
 * process (see [NovaRestoreCaptureTestBase]).
 */
class NovaRestoreCaptureUnknownProviderTest : NovaRestoreCaptureTestBase() {

    @Test
    fun novaRestoreWithUnknownProviderWidget_unboundUntilSettlePointThenDeleted() {
        val absentProvider = "com.example.issue299.absent/.FakeWidgetProvider"
        val restored = restoreSyntheticBackup(includeWidget = true, widgetProvider = absentProvider)

        val observed = mutableListOf<Class<out Throwable>>()
        val preSettleReady = captureThroughProductionSource(observed)
        logMatrix("unknownProvider/postRestore", restored.info)
        Log.i(
            TAG,
            "unknownProvider/preSettleCapture: ready=$preSettleReady observed=${observed.map { it.simpleName }}",
        )
        assertEquals(
            "unbound widget row must be present in the DB before the settle point",
            1 to 0,
            widgetRowCount("unknownProvider/postRestore"),
        )
        assertTrue(
            "capture before the settle point must fail closed on the unbound widget row",
            !preSettleReady,
        )
        assertEquals(
            "same codec widget invariant as the installed-provider path",
            listOf(IllegalArgumentException::class.java),
            observed,
        )

        awaitSettlePoint("unknown provider", restored)
        val postSettle = widgetRowCount("unknownProvider/postSettle")
        logMatrix("unknownProvider/postSettle", restored.info)
        val postSettleReady = captureThroughProductionSource()
        Log.i(
            TAG,
            "unknownProvider/postSettle: widgetRows=${postSettle.first + postSettle.second} ready=$postSettleReady",
        )
        assertEquals(
            "the deletion repair must remove the un-installable pending widget row",
            0 to 0,
            postSettle,
        )
        assertTrue(
            "after the deletion repair the restored workspace must capture Ready",
            postSettleReady,
        )
    }
}
