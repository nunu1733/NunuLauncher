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
package app.lawnchair.organizer.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.CaptureFailureCategory
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.UsageAccess
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolder
import app.lawnchair.organizer.ui.exchange.ExchangeScreen
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #371 (spec 371 Test oracle): the real run surface shows the
 * just-in-time Usage Access request **before** the composition of an
 * ungranted process runs; the decline path and Back both continue ungranted
 * without cancelling the run; a granted process never sees the request; the
 * settings return resumes through the **production predicate** (grant and
 * deny fallback); a second origin waits with exactly one dialog on screen and
 * proceeds only via the observation seam after resolution; the dialog stays
 * open when the settings cannot be resolved; and it stays usable at a 200%
 * font scale. The injected gates keep the non-app-op tests deterministic —
 * the production predicate's app-op following is evidenced by
 * [UsageAccessTransitionProbeTest] and exercised end-to-end in the
 * settings-return tests through [UsageAccessJitGateProvider].
 */
@RunWith(AndroidJUnit4::class)
class UsageAccessJitInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun setUsageAccessOp(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val process = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context().packageName} GET_USAGE_STATS $mode",
        )
        process.close()
        // The app-op change is asynchronous from the app's point of view.
        Thread.sleep(1000)
    }

    private fun pressBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val process = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        process.close()
        Thread.sleep(1500)
    }

    @Test
    fun jitRequestAppearsBeforeCompositionAndDeclineContinuesUngranted() {
        val application = NotReadyApplication(context())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()

        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        // The request is on screen; the composition has not started (the run
        // is parked before the journal opens).
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertIsDisplayed()
        assertTrue(application.events.isEmpty())
        assertEquals(null, application.usageGrantedAtCompose)

        // Decline: the paused composition continues ungranted.
        composeRule.onNodeWithTag("usage_access_jit_continue").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
        assertEquals(false, application.usageGrantedAtCompose)

        // The request never re-shows in the same process.
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertDoesNotExist()
        runner.start()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
        assertFalse(runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit)
    }

    @Test
    fun grantedTriggerNeverShowsTheRequest() {
        val application = NotReadyApplication(context())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { true }),
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()

        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertDoesNotExist()
        assertTrue(application.events.isNotEmpty())
    }

    @Test
    fun unsupportedSettingsKeepsTheDialogOpenAndAllowsContinue() {
        val application = NotReadyApplication(context())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(
                    run = runner,
                    usageAccessSettingsOpener = { false },
                )
            }
        }

        runner.start()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        composeRule.onNodeWithTag("usage_access_jit_open_settings").performClick()

        // The single normative failure path: the dialog stays, the failure is
        // stated in text, and continue remains available.
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_access_jit_settings_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_access_jit_continue").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
    }

    @Test
    fun grantObservedByTheProductionPredicateWhenTheCompositionResumes() {
        val context = context()
        val application = NotReadyApplication(context)
        // The real production gate wiring (UsageAccessJitGateProvider) reads
        // the app-op; the shell grants while the dialog is up, and the
        // composed-phase resume must observe the grant through the production
        // predicate (recorded inside the composition seam).
        // Reset BEFORE building the runner: the gate instance is captured at
        // construction, so each test starts from an unconsumed opportunity.
        UsageAccessJitGateProvider.resetForTests()
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGateProvider.get(context),
        )
        try {
            setUsageAccessOp("deny")
            composeRule.setContent {
                LawnchairTheme {
                    ManualOrganizationPreferences(run = runner)
                }
            }

            runner.start()
            composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
            setUsageAccessOp("allow")
            composeRule.onNodeWithTag("usage_access_jit_continue").performClick()

            composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
            assertEquals(true, application.usageGrantedAtCompose)
            assertTrue(application.events.any { it.phase == PhaseCode.RUN_STARTED })
        } finally {
            setUsageAccessOp("deny")
        }
    }

    @Test
    fun declineKeepsTheCompositionUngrantedThroughTheProductionPredicate() {
        val context = context()
        val application = NotReadyApplication(context)
        // Reset BEFORE building the runner: the gate instance is captured at
        // construction, so each test starts from an unconsumed opportunity.
        UsageAccessJitGateProvider.resetForTests()
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGateProvider.get(context),
        )
        try {
            setUsageAccessOp("deny")
            composeRule.setContent {
                LawnchairTheme {
                    ManualOrganizationPreferences(run = runner)
                }
            }

            runner.start()
            composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
            composeRule.onNodeWithTag("usage_access_jit_continue").performClick()

            composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
            assertEquals(false, application.usageGrantedAtCompose)
            assertTrue(application.events.any { it.phase == PhaseCode.RUN_STARTED })
        } finally {
            setUsageAccessOp("deny")
        }
    }

    @Test
    fun jitDialogBackDeclinesAndContinuesWithoutCancellingTheRun() {
        val application = NotReadyApplication(context())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        // The dialog window must be up (and focused) before the BACK arrives,
        // otherwise the key falls through to the screen's own back handling.
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertIsDisplayed()
        Thread.sleep(500)

        pressBack()

        // Back dismisses the dialog = decline-and-continue, never a run cancel.
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
        assertFalse(runner.state is ManualOrganizationRun.State.Cancelled)
    }

    @Test
    fun crossOriginExchangePresentationPausesTheRunUntilResolution() {
        val context = context()
        val application = NotReadyApplication(context)
        val generationAttempts = AtomicInteger(0)
        val exchangeScope = CoroutineScope(
            Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
        )
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )
        // The exchange origin acquires the process's request first; its
        // background generation is out of scope (the screen transition is the
        // observable), so attempts are counted through the factory.
        val exchangeHolder = ExchangeFlowStateHolder(
            controllerFactory = {
                generationAttempts.incrementAndGet()
                error("background generation is out of scope for this oracle")
            },
            run = runner,
            scope = exchangeScope,
            usageAccessGate = runner.usageAccessGate,
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner, exchangeHolderOverride = exchangeHolder)
            }
        }

        // The exchange origin acquires first: its dialog presents on the Idle
        // face (the exchange section hosts it).
        exchangeHolder.requestGeneration(
            replacementConfirmationRequired = false,
            tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED,
        )
        composeRule.waitUntil(timeoutMillis = 30_000) {
            exchangeHolder.screen is ExchangeScreen.AwaitingUsageAccessJit &&
                composeRule.onAllNodesWithTag("usage_access_jit_dialog").fetchSemanticsNodes().size == 1
        }

        // The run origin evaluates second: it must pause as a waiter.
        runner.start()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        assertFalse((runner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit).isOwner)

        // The run's pause leaves the Idle face, so the exchange section (and
        // its dialog host) unmounts. The unmount applies the owner-destruction
        // rules — the presented request resolves as an abandon resolution —
        // which unblocks the run waiter through its observation seam (the test
        // never calls the waiter's continue), while the abandoned attempt's
        // generation is invalidated and never starts.
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
        assertEquals(ExchangeScreen.Closed, exchangeHolder.screen)
        assertEquals(0, generationAttempts.get())
    }

    @Test
    fun jitDialogStaysUsableAt200PercentFontScale() {
        val application = NotReadyApplication(context())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            // organization-run-ux §6: at a 200% font scale the request's text
            // and both actions must stay displayed and reachable.
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        composeRule.onNodeWithTag("usage_access_jit_title").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_access_jit_body").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_access_jit_open_settings").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("usage_access_jit_continue").assertIsDisplayed().assertHasClickAction()

        composeRule.onNodeWithTag("usage_access_jit_continue").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) { runner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertDoesNotExist()
    }
}

/**
 * Minimal application double for the JIT oracles: detection unavailable
 * (plain full compose), composition typed-NotReady, planner never reached.
 * Records whether the production usage predicate was granted at the moment
 * the composition actually read it.
 */
private class NotReadyApplication(private val context: Context) : ManualOrganizationApplication {
    val events = mutableListOf<RunEvent>()
    var usageGrantedAtCompose: Boolean? = null
    private val recordingDiagnostics = object : DiagnosticsPort {
        override fun emit(event: RunEvent) {
            events += event
        }

        override fun snapshot(): List<RunEvent> = events.toList()
    }

    override val diagnostics: DiagnosticsPort get() = recordingDiagnostics

    override fun newRunId() = app.lawnchair.organizer.application.public.RunId("371aaaa371aaaa371aaaa371aaaa371a")

    override fun detectMissingAppCandidates() = CandidateDetectionResult.Unavailable(
        DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
    )

    override fun composeFullOrganization(): OrganizationInputComposition {
        usageGrantedAtCompose = UsageAccess.isGranted(context)
        return OrganizationInputComposition.NotReady(
            InputReadinessReason.SourceUnavailable(app.lawnchair.organizer.rules.PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            CompositionDiagnostic(InputCompositionCode.BUNDLE_MISSING),
        )
    }

    override fun composeScopeComposedOrganization(
        selection: List<CandidateTarget.AppKey>,
    ): OrganizationInputComposition = composeFullOrganization()

    override fun inspectPlan(input: OrganizationInput, result: PlanningResult) = error("not reached")

    override fun materialize(
        input: OrganizationInput,
        result: PlanningResult,
    ) = error("not reached")

    override fun apply(
        plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
        runId: app.lawnchair.organizer.application.public.RunId,
    ) = error("not reached")

    override fun inspectRecovery(pointId: app.lawnchair.organizer.application.public.RecoveryPointId) = error("not reached")

    override fun confirmRecovery(
        pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
        confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
    ) = error("not reached")

    override fun readDurableOrganizerStatus() = app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

    override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
        kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)
}
