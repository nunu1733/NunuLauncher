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
package app.lawnchair.ui.preferences

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.ui.preferences.destinations.OrganizerDiagnosticsPreferences
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.PreferenceNavigation
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #138: proves the supported release Settings route for organizer
 * diagnostics export. Launch and cancellation are observed on a recording
 * [ActivityResultRegistry] (the production androidx contract consumed by
 * `rememberLauncherForActivityResult`), so no production test hook is needed.
 * Writer-seam isolation itself remains owned by #67 `ExportWriterTest`.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class OrganizerDiagnosticsRouteInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Records SAF launch intents and delivers results through
     * [dispatchResult] instead of starting the real system picker activity.
     */
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

        fun dispatch(resultCode: Int): Boolean = dispatchResult(lastRequestCode, resultCode, null)
    }

    /** Counts snapshot reads; #67's export writer reads the snapshot exactly once per run. */
    private class RecordingPort : DiagnosticsPort {
        var snapshotCalls = 0

        override fun emit(event: RunEvent) = Unit

        override fun snapshot(): List<RunEvent> {
            snapshotCalls++
            return emptyList()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun composeScreen(port: RecordingPort, registry: RecordingRegistry) {
        composeRule.setContent {
            LawnchairTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                        override val activityResultRegistry: ActivityResultRegistry get() = registry
                    },
                ) {
                    OrganizerDiagnosticsPreferences(port = port)
                }
            }
        }
    }

    private fun journalBytes(): ByteArray = File(context.filesDir, "organizer_diagnostics")
        .walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.path }
        .flatMap { it.readBytes().asSequence() }
        .toList()
        .toByteArray()

    @Test
    fun displayAloneNeverLaunchesAndExplicitActivationLaunchesCreateDocument() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        composeScreen(port, registry)

        val label = context.getString(R.string.organizer_diagnostics_export_label)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.organizer_diagnostics_description)).assertIsDisplayed()

        assertEquals("Display must not start the SAF surface", 0, registry.launchedIntents.size)
        assertEquals(0, port.snapshotCalls)

        composeRule.onNodeWithText(label).performClick()
        composeRule.waitUntil(5_000) { registry.launchedIntents.size == 1 }

        val intent = registry.launchedIntents.single()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue("SAF intent must carry CATEGORY_OPENABLE", intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("application/jsonl", intent.type)
        assertEquals("Writer must stay idle until a result arrives", 0, port.snapshotCalls)
    }

    @Test
    fun cancellationKeepsWriterIdleAndJournalUntouchedWithoutRelaunch() {
        val port = RecordingPort()
        val registry = RecordingRegistry(context)
        composeScreen(port, registry)

        val label = context.getString(R.string.organizer_diagnostics_export_label)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        val journalBefore = journalBytes()

        composeRule.onNodeWithText(label).performClick()
        composeRule.waitUntil(5_000) { registry.launchedIntents.size == 1 }

        assertTrue(registry.dispatch(Activity.RESULT_CANCELED))
        composeRule.waitForIdle()

        assertEquals("Cancel must not relaunch the SAF surface", 1, registry.launchedIntents.size)
        assertEquals("Writer must not read the journal after cancel", 0, port.snapshotCalls)
        assertTrue(
            "Journal bytes must be untouched by cancel",
            journalBefore.contentEquals(journalBytes()),
        )
    }

    @Test
    fun homeScreenEntryNavigatesToDiagnosticsRouteShowingExportSurface() {
        // Production Settings runs inside the launcher process, where
        // LauncherAppState creation has initialized layoutApplicationModule.
        // Mirror that environment before composing the production route graph.
        LauncherAppState.getInstance(context)
        composeRule.setContent {
            LawnchairTheme {
                val navController = rememberNavController()
                // Production Preferences.kt provides these three locals around
                // PreferenceNavigation; mirror them for a single-pane host.
                CompositionLocalProvider(
                    LocalNavController provides navController,
                    LocalPreferenceInteractor provides PreferenceViewModel(context.applicationContext as android.app.Application),
                    LocalIsExpandedScreen provides false,
                ) {
                    PreferenceNavigation(navController = navController, startDestination = HomeScreen)
                }
            }
        }

        val entryLabel = context.getString(R.string.organizer_diagnostics_title)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(entryLabel))
        composeRule.onNodeWithText(entryLabel).performClick()

        val exportLabel = context.getString(R.string.organizer_diagnostics_export_label)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(exportLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(exportLabel).assertIsDisplayed()
    }
}
