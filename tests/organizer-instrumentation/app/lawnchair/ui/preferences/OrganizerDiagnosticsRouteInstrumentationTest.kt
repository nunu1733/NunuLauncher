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
import android.app.Instrumentation
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.UsageAccess
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.DeviceCapabilities as PlannerDeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.ui.ManualOrganizationModule
import app.lawnchair.organizer.ui.ManualOrganizationRun
import app.lawnchair.organizer.ui.ManualOrganizationApplication
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.ui.preferences.destinations.OrganizerDiagnosticsPreferences
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.HomeScreenCategoryOverrides
import app.lawnchair.ui.preferences.navigation.HomeScreenCustomCategories
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
import app.lawnchair.ui.preferences.navigation.HomeScreenOrganizerStrategy
import app.lawnchair.ui.preferences.navigation.HomeScreenPlacementLocks
import app.lawnchair.ui.preferences.navigation.PreferenceNavigation
import app.lawnchair.ui.preferences.navigation.PreferenceRoute
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

    /**
     * Issue #367 MAT-AC-01: from the settings Home screen, the hub materials
     * group routes to each existing authoring destination — category
     * overrides (spec #99), user-defined categories (spec #336), placement
     * locks (spec #38) — and each destination renders its own surface.
     */
    @Test
    fun homeScreenHubMaterialsRoutesToEachAuthoringDestination() {
        val fixture = ManualOrganizationRun(FakeManualOrganizationApplication(), OrganizationPlanner { planningResult() })
        installProcessLocalRunner(fixture)
        try {
            val navController = composeProductionGraph(startDestination = HomeScreen)

            composeRule.onNodeWithText(context.getString(R.string.organizer_hub_title)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_hub_materials_heading),
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // T-02: category overrides destination. The override editor's app
            // list can be empty in the instrumentation environment, so
            // arrival is asserted on the navigation back stack.
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_category_overrides_title),
            ).performClick()
            assertCurrentDestination(navController, HomeScreenCategoryOverrides)
            composeRule.runOnIdle { navController.popBackStack() }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_hub_materials_heading),
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // T-03: user-defined categories destination.
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_custom_category_title),
            ).performClick()
            assertCurrentDestination(navController, HomeScreenCustomCategories)
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_custom_category_create),
            ).assertIsDisplayed()
            composeRule.runOnIdle { navController.popBackStack() }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_hub_materials_heading),
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // T-04: placement locks destination.
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_lock_screen_title),
            ).performClick()
            assertCurrentDestination(navController, HomeScreenPlacementLocks)
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_lock_screen_unknown_banner_none),
            ).assertIsDisplayed()
            composeRule.runOnIdle { navController.popBackStack() }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_hub_materials_heading),
                ).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            installProcessLocalRunner(null)
        }
    }

    /**
     * Issue #372 (EX-AC-02 + EX-AC-01, rendered-UI oracle over the PRODUCTION
     * navigation graph and the REAL user route): hub → start CTA → T-07 →
     * 「AIに相談」→ T-15 shows the ACTIVE REQUEST pre-display; Back returns
     * through T-07 to the hub; the hub materials row 「Organization strategy」
     * opens T-05 where a real strategy radio write commits through the
     * AUTHORING-token arbiter WITHOUT a lease rejection; returning start CTA →
     * T-07 → 「AIに相談」 restores the T-15 pre-display for the SAME durable
     * session. Every transition is a real UI row click / system Back.
     */
    @Test
    fun issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute() {
        val fixture = ManualOrganizationRun(FakeManualOrganizationApplication(), OrganizationPlanner { planningResult() })
        installProcessLocalRunner(fixture)
        // The consultation session is seeded through the REAL durable store
        // (#204 contract): the production controller's generation is covered
        // by its own oracle suite; this test owns the persistence-and-
        // materials-route interaction, so the session is written directly.
        val sessionStore = app.lawnchair.organizer.integration.exchange.ExchangeSessionStoreModule
            .store(context)
        val now = System.currentTimeMillis()
        sessionStore.save(
            app.lawnchair.organizer.personalization.ExportSession(
                exportId = "issue372-materials-route",
                itemRefs = emptyMap(),
                tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED,
                sourceContextDigest = "digest",
                signalProvenance = null,
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 24L * 60L * 60L * 1000L,
            ),
        )
        try {
            val navController = composeProductionGraph(startDestination = HomeScreen)

            fun pressBack() {
                composeRule.runOnUiThread {
                    val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<androidx.activity.ComponentActivity>()
                        .firstOrNull()
                    checkNotNull(resumed).onBackPressedDispatcher.onBackPressed()
                }
                composeRule.waitForIdle()
            }

            // Hub → start CTA → T-07 → 「AIに相談」 → T-15 pre-display.
            composeRule.onNodeWithText(context.getString(R.string.organizer_hub_title)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.manual_organization_start),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.manual_organization_start)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.exchange_method_consult),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.exchange_method_consult)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("exchange-request-title").fetchSemanticsNodes().isNotEmpty()
            }

            // Back: T-15 closes zero-write to T-07, then T-07 returns to the hub.
            pressBack()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("exchange-request-title").fetchSemanticsNodes().isEmpty()
            }
            pressBack()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_strategy_title),
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // The hub materials 「Organization strategy」 row opens T-05; one
            // real strategy write commits (AUTHORING token, no rejection).
            composeRule.onNodeWithText(context.getString(R.string.organizer_strategy_title)).performClick()
            assertCurrentDestination(navController, HomeScreenOrganizerStrategy)
            val tidy = context.getString(R.string.organization_strategy_tidy_name)
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(tidy))
            composeRule.onNodeWithText(tidy).assertIsNotSelected().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(tidy).assertIsSelected()

            // Back to the hub, then the start CTA → T-07 → 「AIに相談」 again:
            // the same durable request resurfaces through the T-15 pre-display.
            pressBack()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_strategy_title),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.manual_organization_start)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.exchange_method_consult),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.exchange_method_consult)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("exchange-request-active").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(ManualOrganizationRun.State.Idle, fixture.state)
        } finally {
            sessionStore.invalidate("issue372-materials-route")
            installProcessLocalRunner(null)
        }
    }

    /**
     * Issue #367 MAT-AC-06: the hub's T-06 rows are the only personalization
     * material surface. The recording toggle writes the existing preference,
     * and the usage-access row shows the app-op state that is re-read on
     * ON_RESUME (spec #203 U-2) — exercised by granting and revoking the
     * app-op through the shell and dispatching a resume cycle on the
     * composition's lifecycle owner.
     */
    @Test
    fun homeScreenHubTogglesRecordingPreferenceAndRereadsUsageAccessOnResume() {
        val owner = ResumableTestOwner()
        val fixture = ManualOrganizationRun(FakeManualOrganizationApplication(), OrganizationPlanner { planningResult() })
        installProcessLocalRunner(fixture)
        try {
            // The registry must be driven from the main thread.
            composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            val navController = composeProductionGraph(startDestination = HomeScreen, lifecycleOwner = owner)

            composeRule.onNodeWithText(context.getString(R.string.organizer_hub_title)).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_hub_materials_heading),
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // The recording toggle is the one existing preference switch on
            // the production hub: flipping it flips the shared adapter state
            // (the same preference the settings-side rows used to write).
            val recordingLabel = context.getString(R.string.organizer_personalization_recording_label)
            val before = hubRecordingSwitchState()
            composeRule.onNodeWithText(recordingLabel).performClick()
            composeRule.waitUntil(5_000) { hubRecordingSwitchState() != before }
            composeRule.onNodeWithText(recordingLabel).performClick()
            composeRule.waitUntil(5_000) { hubRecordingSwitchState() == before }

            // The usage-access row shows the app-op state, re-read on resume.
            val usageLabel = context.getString(R.string.organizer_personalization_usage_access_label)
            val grantedText = context.getString(R.string.organizer_personalization_usage_access_granted)
            val notGrantedText = context.getString(R.string.organizer_personalization_usage_access_not_granted)
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(usageLabel))

            // Clicking the row must send the user to the system usage-access
            // settings (spec #203 U-2). The instrumentation monitor blocks
            // the real launch and records the interception.
            val monitor = instrumentation.addMonitor(
                IntentFilter(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS),
                ActivityResult(Activity.RESULT_OK, null),
                /* block = */ true,
            )
            composeRule.onNodeWithText(usageLabel).performClick()
            assertEquals(1, monitor.hits)
            instrumentation.removeMonitor(monitor)

            val initiallyGranted = UsageAccess.isGranted(context)
            composeRule.onNodeWithText(
                if (initiallyGranted) grantedText else notGrantedText,
            ).assertIsDisplayed()

            // Grant, then a resume cycle must refresh the row.
            shell("appops set ${context.packageName} GET_USAGE_STATS allow")
            composeRule.runOnIdle { owner.dispatchResumeCycle() }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(grantedText).fetchSemanticsNodes().isNotEmpty()
            }

            // Revoke, then a resume cycle must refresh the row again.
            shell("appops set ${context.packageName} GET_USAGE_STATS deny")
            composeRule.runOnIdle { owner.dispatchResumeCycle() }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(notGrantedText).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            installProcessLocalRunner(null)
            shell("appops set ${context.packageName} GET_USAGE_STATS default")
        }
    }

    /** Runs a shell command through the instrumentation's UiAutomation. */
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String) {
        val process = instrumentation.uiAutomation.executeShellCommand(command)
        java.io.FileInputStream(process.fileDescriptor).readBytes()
        process.close()
    }

    /** Reads the production hub's recording toggle state (the only switch on the surface). */
    private fun hubRecordingSwitchState(): Boolean {
        val node = composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        ).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    }

    /** Asserts the production graph's current destination is [route]. */
    private fun assertCurrentDestination(navController: NavHostController, route: PreferenceRoute) {
        composeRule.waitForIdle()
        var arrived = false
        composeRule.runOnIdle {
            arrived = navController.currentBackStackEntry?.destination?.hasRoute(route::class) == true
        }
        assertTrue("expected navigation to $route", arrived)
    }

    /** A lifecycle owner whose state the test drives, to dispatch ON_RESUME. */
    private class ResumableTestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        /** ON_PAUSE/ON_STOP followed by ON_START/ON_RESUME. */
        fun dispatchResumeCycle() {
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    /**
     * Composes the production preferences graph, mirroring the locals the
     * production Preferences.kt host provides. The lifecycle owner override
     * (when given) is what the personalization row observes for ON_RESUME.
     * Returns the graph's nav controller for back-stack assertions.
     */
    private fun composeProductionGraph(
        startDestination: PreferenceRoute,
        lifecycleOwner: LifecycleOwner? = null,
    ): NavHostController {
        LauncherAppState.getInstance(context)
        var controller: NavHostController? = null
        composeRule.setContent {
            LawnchairTheme {
                val navController = rememberNavController()
                SideEffect { controller = navController }
                val effectiveOwner = lifecycleOwner ?: LocalLifecycleOwner.current
                CompositionLocalProvider(
                    LocalLifecycleOwner provides effectiveOwner,
                    LocalNavController provides navController,
                    LocalPreferenceInteractor provides PreferenceViewModel(
                        context.applicationContext as android.app.Application,
                    ),
                    LocalIsExpandedScreen provides false,
                ) {
                    PreferenceNavigation(navController = navController, startDestination = startDestination)
                }
            }
        }
        composeRule.waitForIdle()
        return checkNotNull(controller)
    }

    /**
     * Issue #367 (obsoletes the #138 settings-entry oracle): the Layout-group
     * diagnostics row is gone from the settings Home screen (TO-BE §5.2 —
     * organizer material rows aggregate under the hub; obsolete reason:
     * D-01 material relocation), so the supported settings route now runs
     * through the hub's standing diagnostics entry.
     * Issue #370 (obsoletes the #367 staged-coexistence oracle): the manual
     * run row is removed as well — the hub entry is now the ONLY organizer
     * row in the General group (D-01 entry-row-only end state; obsolete
     * reason recorded in the implementation PR). The lazy list omits
     * un-composed rows from the semantics tree, so material-row absence is
     * only observable across a full step-wise traversal before the
     * click-through.
     */
    @Test
    fun homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub() {
        // The hub composes the process-local runner; install the lightweight
        // fixture (idle, never-organized) instead of letting the hub create
        // the production module with a real reconciliation, so this test
        // stays independent from the model-loading environment.
        val fixture = ManualOrganizationRun(FakeManualOrganizationApplication(), OrganizationPlanner { planningResult() })
        installProcessLocalRunner(fixture)
        try {
            // Production Settings runs inside the launcher process, where
            // LauncherAppState creation has initialized layoutApplicationModule
            // (the diagnostics destination reads it directly). Mirror that
            // environment before composing the production route graph.
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

            // Issue #370: the manual run row is gone; the hub entry is the only
            // organizer row left in the General group.
            composeRule.onAllNodesWithText(
                context.getString(R.string.manual_organization_title),
            ).assertCountEquals(0)
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_hub_title),
            ).assertIsDisplayed()

            assertMaterialRowsAbsentAcrossFullScroll(
                listOf(
                    R.string.organizer_lock_screen_title,
                    R.string.organizer_diagnostics_title,
                    R.string.organizer_category_overrides_title,
                    R.string.organizer_custom_category_title,
                    R.string.organizer_personalization_recording_label,
                    R.string.organizer_personalization_usage_access_label,
                ),
            )

            composeRule.onNodeWithText(
                context.getString(R.string.organizer_hub_title),
            ).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(
                    context.getString(R.string.organizer_hub_materials_heading),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            // The personalization material (T-06) exists exactly once, in the hub.
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_personalization_recording_label),
            ).assertCountEquals(1)

            val exportLabel = context.getString(R.string.organizer_diagnostics_export_label)
            composeRule.onNodeWithText(
                context.getString(R.string.organizer_diagnostics_title),
            ).performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(exportLabel).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(exportLabel).assertIsDisplayed()
        } finally {
            installProcessLocalRunner(null)
        }
    }

    /**
     * Issue #367: asserts the material rows are absent from the settings Home
     * screen. A lazy list only exposes composed rows, so each step of a
     * full traversal re-asserts absence while its region is composed; the
     * traversal ends at the final widgets row.
     */
    private fun assertMaterialRowsAbsentAcrossFullScroll(labelResources: List<Int>) {
        val texts = labelResources.map { context.getString(it) }
        val sentinel = context.getString(R.string.force_widget_resize_label)
        val scroller = composeRule.onNode(hasScrollAction())
        var steps = 0
        while (steps++ < 25) {
            texts.forEach { text -> composeRule.onNodeWithText(text).assertDoesNotExist() }
            if (composeRule.onAllNodesWithText(sentinel).fetchSemanticsNodes().isNotEmpty()) return
            scroller.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        error("settings Home screen traversal never reached its final section")
    }

    /**
     * Issue #138 AC-5 regression oracle: inside the production navigation
     * graph, the safe-terminal `Open organizer diagnostics` action must land
     * on the supported route showing the export surface. A revert of the
     * wiring to `navigate(DebugMenu)` fails here because the export row stays
     * hidden behind the disabled debug switch.
     */
    @Test
    fun safeTerminalOpenDiagnosticsRoutesThroughProductionGraphToExportSurface() {
        LauncherAppState.getInstance(context)

        val application = FakeManualOrganizationApplication().apply {
            applyResult = ApplyResult.Unresolved(
                RunId(RUN_ID),
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
            )
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult() })
        installProcessLocalRunner(runner)
        try {
            composeRule.setContent {
                LawnchairTheme {
                    val navController = rememberNavController()
                    CompositionLocalProvider(
                        LocalNavController provides navController,
                        LocalPreferenceInteractor provides PreferenceViewModel(
                            context.applicationContext as android.app.Application,
                        ),
                        LocalIsExpandedScreen provides false,
                    ) {
                        PreferenceNavigation(
                            navController = navController,
                            startDestination = HomeScreenManualOrganization(),
                        )
                    }
                }
            }

            composeRule.onNodeWithText(context.getString(R.string.manual_organization_start)).performClick()
            // The decision pair sits below the fold on shorter viewports and a
            // lazy list only exposes composed rows, so scroll it into view
            // before clicking (#366's viewport precedent; the state flip and
            // the row composition are not atomic).
            composeRule.waitUntil(10_000) { runner.state is ManualOrganizationRun.State.Preview }
            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasText(context.getString(R.string.manual_organization_confirm)),
            )
            composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).performClick()
            composeRule.waitUntil(10_000) { runner.state is ManualOrganizationRun.State.Applied }
            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasText(context.getString(R.string.manual_organization_open_diagnostics)),
            )
            composeRule.onNodeWithText(context.getString(R.string.manual_organization_open_diagnostics)).performClick()

            val exportLabel = context.getString(R.string.organizer_diagnostics_export_label)
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText(exportLabel).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(exportLabel).assertIsDisplayed()
        } finally {
            installProcessLocalRunner(null)
        }
    }

    /** Installs a fixture runner into the process-local holder resolved by production wiring. */
    private fun installProcessLocalRunner(runner: ManualOrganizationRun?) {
        val field = ManualOrganizationModule.javaClass.getDeclaredField("instance")
        field.isAccessible = true
        field.set(ManualOrganizationModule, runner)
    }

    private class FakeManualOrganizationApplication : ManualOrganizationApplication {
        override val diagnostics = RecordingPort()
        var applyResult: ApplyResult = ApplyResult.Applied(RunId(RUN_ID), RecoveryPointId(POINT_ID))

        override fun newRunId() = RunId(RUN_ID)

        // Issue #228: detection unavailable keeps the legacy full flow.
        override fun detectMissingAppCandidates() = app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
            app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
        )

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): OrganizationInputComposition = composeFullOrganization()

        override fun composeFullOrganization(): OrganizationInputComposition = OrganizationInputComposition.Ready(
            input = input(),
            provenance = InputProvenance(
                revision = RevisionId(REVISION),
                rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
                targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
                policyBundle = PolicyBundleIdentity("v1", SHA_256),
                layoutStrategySelection = policyIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION),
            ),
        )

        /**
         * Issue #194: this fixture keeps the legacy count-only flow, so the
         * preview seam reports busy and confirm materializes as before.
         */
        override fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult =
            PlanPreviewResult.WriterBusy

        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result =
            OrganizationPlanMaterializer.Result.Ready(
                ValidatedLayoutPlan(
                    sourceRevision = input.snapshot.revision,
                    sourceState = emptyLayoutState(),
                    intendedState = emptyLayoutState(),
                    actions = emptyList(),
                    newPages = emptyList(),
                    newFolders = emptyList(),
                    ruleVersion = input.rules.version,
                    taxonomyVersion = input.taxonomy.version,
                ),
            )

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult = applyResult

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            pointId,
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult =
            RecoveryResult.NotRestorable(
                pointId,
                app.lawnchair.organizer.application.public.RecoveryRejection.MISSING,
            )

        override fun readDurableOrganizerStatus(): app.lawnchair.organizer.application.public.OrganizerDurableStatus =
            app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)
    }

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val REVISION = "revision"
        const val SHA_256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun planningResult() = PlanningResult(
            revision = RevisionId(REVISION),
            ruleVersion = RuleVersion("v1"),
            taxonomyVersion = TaxonomyVersion("v1"),
            organizationStrategy = app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            outcome = Planned(
                placements = listOf(
                    PlannedPlacement(
                        item = ItemId("item"),
                        disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                        target = PlacementTarget.WorkspaceTarget(
                            page = PageRef(PageId("page")),
                            cell = GridCell(0, 0),
                            span = GridSpan(1, 1),
                        ),
                    ),
                ),
                newPages = emptyList(),
                newFolders = emptyList(),
                categories = emptyList(),
                warnings = listOf(Warning(WarningCode.FALLBACK_CATEGORY, emptyList())),
            ),
        )

        fun input() = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId(REVISION),
                device = PlannerDeviceCapabilities(4, 5, 5, 3, 4, Orientation.PORTRAIT),
                pages = listOf(Page(PageId("page"), PageOrder(0))),
                items = emptyList(),
            ),
            rules = RuleSemantics(
                RuleVersion("v2"),
                FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                DockPolicy.PRESERVE,
                OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),           taxonomy = TaxonomyContract(
                TaxonomyVersion("v1"),
                listOf(app.lawnchair.organizer.planning.CategoryId("other")),
                app.lawnchair.organizer.planning.CategoryId("other"),
            ),
            catalog = app.lawnchair.organizer.planning.ActiveCategoryCatalog(
                TaxonomyContract(
                TaxonomyVersion("v1"),
                listOf(app.lawnchair.organizer.planning.CategoryId("other")),
                app.lawnchair.organizer.planning.CategoryId("other"),
            ),
                emptyList(),
            ),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(emptyList(), emptyList()),
            runMode = RunMode.FullOrganization,
        )

        fun emptyLayoutState() = LayoutState(
            pages = emptyList(),
            profiles = emptyList(),
            deviceCapabilities = DeviceCapabilities(
                4,
                5,
                5,
                3,
                4,
                app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT,
            ),
            items = emptyList(),
        )

        fun policyIdentity(source: PolicySourceKind) = PolicyInputIdentity(source, "v1", SHA_256)
    }
}
