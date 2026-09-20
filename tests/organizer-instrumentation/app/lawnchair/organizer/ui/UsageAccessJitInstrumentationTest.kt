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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.ui.exchange.ExchangeFlowStateHolder
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.CaptureFailureCategory
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.DetectionUnavailableReason
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.ui.preferences.destinations.ManualOrganizationPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #371 (spec 371 Test oracle JIT-AC-01/04): the real run surface shows
 * the just-in-time Usage Access request **before** the composition of an
 * ungranted process runs, the decline path continues ungranted, and a granted
 * process never sees the request. The gate is injected for determinism; the
 * production predicate's app-op following is evidenced by
 * [UsageAccessTransitionProbeTest].
 */
@RunWith(AndroidJUnit4::class)
class UsageAccessJitInstrumentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun jitRequestAppearsBeforeCompositionAndDeclineContinuesUngranted() {
        val application = NotReadyApplication()
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

        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        // The request is on screen; the composition has not started (the run
        // is parked before the journal opens).
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertIsDisplayed()
        assertTrue(application.events.isEmpty())

        // Decline: the paused composition continues ungranted.
        composeRule.onNodeWithTag("usage_access_jit_continue").performClick()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }

        // The request never re-shows in the same process.
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertDoesNotExist()
        runner.start()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        assertFalse(runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit)
    }

    @Test
    fun grantedTriggerNeverShowsTheRequest() {
        val application = NotReadyApplication()
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

        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertDoesNotExist()
        assertTrue(application.events.isNotEmpty())
    }

    @Test
    fun unsupportedSettingsKeepsTheDialogOpenAndAllowsContinue() {
        val application = NotReadyApplication()
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
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        composeRule.onNodeWithTag("usage_access_jit_open_settings").performClick()

        // The single normative failure path: the dialog stays, the failure is
        // stated in text, and continue remains available.
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_access_jit_settings_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_access_jit_continue").performClick()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
    }

    @Test
    fun settingsReturnAfterGrantResumesTheCompositionThroughTheProductionPredicate() {
        val context = context()
        val application = NotReadyApplication()
        // The real production gate (UsageAccessJitGateProvider wiring) reads
        // the app-op; the shell drives a real grant while the app is
        // backgrounded in the system settings.
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGateProvider.get(context),
        )
        setUsageAccessOp("deny")
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }
        composeRule.onNodeWithTag("usage_access_jit_open_settings").performClick()
        pressBack()
        // ON_RESUME -> the host's bounded re-read observes the (now allowed)
        // production predicate and only then resumes the composition.
        setUsageAccessOp("allow")
        pressBack()

        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        composeRule.onNodeWithTag("usage_access_jit_dialog").assertDoesNotExist()
        // The composition opened its journal (RUN_STARTED) — the resume ran
        // the composition after the grant was observable.
        assertTrue(application.events.any { it.phase == PhaseCode.RUN_STARTED })
    }

    @Test
    fun jitDialogBackDeclinesAndContinuesWithoutCancellingTheRun() {
        val application = NotReadyApplication()
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
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }

        pressBack()

        // Back dismisses the dialog = decline-and-continue, never a run cancel.
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        assertFalse(runner.state is ManualOrganizationRun.State.Cancelled)
    }

    @Test
    fun crossOriginTriggerWhileTheRunDialogIsUpWaitsAndShowsExactlyOneDialog() {
        val context = context()
        val application = NotReadyApplication()
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run for a NotReady composition") },
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )
        val exchangeHolder = ExchangeFlowStateHolder(
            controllerFactory = { error("generation starts only after resolution") },
            run = runner,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
            usageAccessGate = runner.usageAccessGate,
        )
        composeRule.setContent {
            LawnchairTheme {
                ManualOrganizationPreferences(run = runner)
            }
        }

        runner.start()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit }

        // A second (exchange) origin evaluates while the run owns the request.
        exchangeHolder.requestGeneration(replacementConfirmationRequired = false, tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED)
        val awaiting = exchangeHolder.screen as app.lawnchair.organizer.ui.exchange.ExchangeScreen.AwaitingUsageAccessJit
        assertFalse(awaiting.isPresenter)
        // Exactly one dialog on screen (the run's), the waiter is dialog-free.
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("usage_access_jit_dialog").fetchSemanticsNodes().size == 1
        }

        // Resolving the run's request unblocks the exchange origin.
        composeRule.onNodeWithTag("usage_access_jit_continue").performClick()
        composeRule.waitUntil { runner.state is ManualOrganizationRun.State.InputUnavailable }
        exchangeHolder.continueUsageAccessJit(awaiting.attemptToken)
        assertEquals(
            app.lawnchair.organizer.ui.exchange.ExchangeScreen.Generating,
            exchangeHolder.screen,
        )
    }

    private fun pressBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val process = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        process.close()
        Thread.sleep(1500)
    }

    private fun setUsageAccessOp(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val process = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context().packageName} GET_USAGE_STATS $mode",
        )
        process.close()
        // The app-op change is asynchronous from the app's point of view.
        Thread.sleep(1000)
    }

    /**
     * Minimal application double: detection unavailable (plain full compose),
     * composition typed-NotReady, planner never reached.
     */
    private class NotReadyApplication : ManualOrganizationApplication {
        val events = mutableListOf<RunEvent>()
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

        override fun composeFullOrganization(): OrganizationInputComposition = OrganizationInputComposition.NotReady(
            InputReadinessReason.SourceUnavailable(app.lawnchair.organizer.rules.PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            CompositionDiagnostic(InputCompositionCode.BUNDLE_MISSING),
        )

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
}
