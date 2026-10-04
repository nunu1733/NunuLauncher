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
import app.lawnchair.ui.preferences.navigation.HomeScreenManualOrganization
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
            composeRule.waitUntil(10_000) { runner.state is ManualOrganizationRun.State.Preview }
            composeRule.onNodeWithText(context.getString(R.string.manual_organization_confirm)).performClick()
            composeRule.waitUntil(10_000) { runner.state is ManualOrganizationRun.State.Applied }

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

        override fun composeFullOrganization(): OrganizationInputComposition = OrganizationInputComposition.Ready(
            input = input(),
            provenance = InputProvenance(
                revision = RevisionId(REVISION),
                rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
                targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
                policyBundle = PolicyBundleIdentity("v1", SHA_256),
            ),
        )

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
                RuleVersion("v1"),
                FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                DockPolicy.PRESERVE,
                OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                app.lawnchair.organizer.planning.OrderingPolicy.CANONICAL_V1,
            ),
            taxonomy = TaxonomyContract(
                TaxonomyVersion("v1"),
                listOf(app.lawnchair.organizer.planning.CategoryId("other")),
                app.lawnchair.organizer.planning.CategoryId("other"),
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
