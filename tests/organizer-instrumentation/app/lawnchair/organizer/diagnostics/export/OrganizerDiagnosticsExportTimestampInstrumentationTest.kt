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
package app.lawnchair.organizer.diagnostics.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #288: the suggested export filename embeds the export timestamp and
 * the header's `exportedAtWallMillis` derives from the same captured instant;
 * the pending session survives picker-open recreation and is consumed at
 * result delivery (no stale session on any delivery path).
 *
 * SAF results are observed on a recording [ActivityResultRegistry] (the
 * production androidx contract consumed by
 * `rememberLauncherForActivityResult`), writing to a real file URI so the
 * exported header can be read back. Writer-seam isolation remains owned by
 * `ExportWriterTest`.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class OrganizerDiagnosticsExportTimestampInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Records SAF launch intents and delivers results through [dispatchResult]. */
    private class RecordingRegistry(private val context: Context) : ActivityResultRegistry() {
        val launchedIntents = mutableListOf<Intent>()
        var lastRequestCode = Int.MIN_VALUE

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequestCode = requestCode
            launchedIntents.add(contract.createIntent(context, input))
        }

        fun dispatch(resultCode: Int, data: Intent? = null): Boolean =
            dispatchResult(lastRequestCode, resultCode, data)
    }

    private class RecordingPort : DiagnosticsPort {
        var snapshotCalls = 0

        override fun emit(event: RunEvent) = Unit

        override fun snapshot(): List<RunEvent> {
            snapshotCalls++
            return emptyList()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val exportLabel = context.getString(R.string.organizer_diagnostics_export_label)

    private fun composeScreen(port: RecordingPort, registry: RecordingRegistry) {
        composeRule.setContent {
            LawnchairTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                        override val activityResultRegistry: ActivityResultRegistry get() = registry
                    },
                ) {
                    OrganizerDiagnosticsExportPreference(diagnosticsPort = port)
                }
            }
        }
    }

    private fun newExportFile(): File =
        File(context.cacheDir, "export_${System.nanoTime()}.jsonl").also { it.deleteOnExit() }

    private fun launchExport(registry: RecordingRegistry) {
        composeRule.onNodeWithText(exportLabel).performClick()
        composeRule.waitUntil(5_000) { registry.launchedIntents.isNotEmpty() }
    }

    private fun suggestedName(intent: Intent): String {
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertTrue("Suggested name must be present: $title", title != null)
        assertTrue("Name must contain only safe characters: $title", title!!.matches(Regex("[A-Za-z0-9_.]+")))
        assertTrue("Name must end in .jsonl: $title", title.endsWith(".jsonl"))
        return title
    }

    private fun headerExportedAtMillis(file: File): Long {
        val headerLine = file.readLines(Charsets.UTF_8).first { it.isNotBlank() }
        return Json.parseToJsonElement(headerLine)
            .jsonObject.getValue("header").jsonObject
            .getValue("exportedAtWallMillis").jsonPrimitive.content.toLong()
    }

    @Test
    fun suggestedFilenameTimestampMatchesHeaderInstantOnSuccessfulExport() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        composeScreen(port, registry)

        launchExport(registry)
        val suggestedName = suggestedName(registry.launchedIntents.single())
        val timestampSegment = suggestedName
            .removePrefix(DiagnosticsExportFilename.PREFIX + "_")
            .removeSuffix(DiagnosticsExportFilename.EXTENSION)
        val capturedMillis = LocalDateTime
            .parse(timestampSegment, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

        val destination = newExportFile()
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination))))
        composeRule.waitUntil(10_000) { destination.length() > 0 }

        assertEquals(
            "Header exportedAtWallMillis must equal the filename's captured instant",
            capturedMillis,
            headerExportedAtMillis(destination),
        )
        assertEquals("Writer must read the snapshot exactly once", 1, port.snapshotCalls)
    }

    @Test
    fun secondDeliveredResultWithoutNewSessionIsIgnored() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        composeScreen(port, registry)

        launchExport(registry)
        val first = newExportFile()
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(Uri.fromFile(first))))
        composeRule.waitUntil(10_000) { first.length() > 0 }

        // The session was consumed at delivery: a re-played OK result must
        // not write again, and no pending state may pair with it.
        val replayTarget = newExportFile()
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(Uri.fromFile(replayTarget))))
        composeRule.waitForIdle()

        assertFalse("Replayed result must not write", replayTarget.exists())
        assertEquals("Snapshot must not be read again", 1, port.snapshotCalls)
    }

    @Test
    fun cancelledSessionDoesNotPairWithLaterStaleOkResult() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        composeScreen(port, registry)

        launchExport(registry)
        assertTrue(registry.dispatch(Activity.RESULT_CANCELED))
        composeRule.waitForIdle()

        // Cancel consumed the session: a later stale OK result must be ignored.
        val staleTarget = newExportFile()
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(Uri.fromFile(staleTarget))))
        composeRule.waitForIdle()
        assertFalse("Stale OK after cancel must not write", staleTarget.exists())
        assertEquals(0, port.snapshotCalls)

        // A fresh activation opens a new session and relaunches SAF.
        launchExport(registry)
        assertEquals("Fresh export must relaunch the picker", 2, registry.launchedIntents.size)
    }

    @Test
    fun okResultWithNullUriLeavesNoSessionBehind() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        composeScreen(port, registry)

        launchExport(registry)
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(null)))
        composeRule.waitForIdle()

        // The OK-without-URI delivery consumed the session: a stale replay
        // must not write, and the journal stays untouched.
        val staleTarget = newExportFile()
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(Uri.fromFile(staleTarget))))
        composeRule.waitForIdle()
        assertFalse("Result pair after null-URI delivery must not write", staleTarget.exists())
        assertEquals(0, port.snapshotCalls)
    }

    @Test
    fun pendingSessionSurvivesPickerOpenStateRestorationAndWritesOriginalInstant() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            LawnchairTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                        override val activityResultRegistry: ActivityResultRegistry get() = registry
                    },
                ) {
                    OrganizerDiagnosticsExportPreference(diagnosticsPort = port)
                }
            }
        }

        launchExport(registry)
        val suggestedName = suggestedName(registry.launchedIntents.single())
        val timestampSegment = suggestedName
            .removePrefix(DiagnosticsExportFilename.PREFIX + "_")
            .removeSuffix(DiagnosticsExportFilename.EXTENSION)
        val capturedMillis = LocalDateTime
            .parse(timestampSegment, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

        // Simulate recreation while the picker is open: saveable state is
        // saved and restored; the registry re-delivers the pending result.
        tester.emulateSavedInstanceStateRestore()

        val destination = newExportFile()
        assertTrue(registry.dispatch(Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination))))
        composeRule.waitUntil(10_000) { destination.length() > 0 }

        assertEquals(
            "Restored session must write with the originally captured instant",
            capturedMillis,
            headerExportedAtMillis(destination),
        )
    }
}
