package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PlanPreviewRejection
import app.lawnchair.organizer.application.public.PlanPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewSummary
import app.lawnchair.organizer.application.public.RecoveryPreviewUnavailable
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.InputCompositionCode
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.DeviceCapabilities as PlannerDeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderPolicy
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
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.RejectionReason
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UnplacedItem
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.rules.PolicyBundleIdentity
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManualOrganizationRunTest {
    @Test
    fun exceptionDuringCompositionReleasesTheOrganizationOperationLease() {
        val application = FakeApplication(readyInput()).apply {
            composeOverride = { error("composition failure") }
        }
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run") },
            operationGate = OrganizationOperationLease,
        )

        assertThrowsIllegalState { runner.start() }
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)

        val admitted = ManualOrganizationRun(
            FakeApplication(
                OrganizationInputComposition.NotReady(
                    InputReadinessReason.InvalidCanonicalCapture(
                        app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                    ),
                    CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
                ),
            ),
            planner = OrganizationPlanner { error("planner must not run") },
            operationGate = OrganizationOperationLease,
        ).start()
        assertTrue(admitted is ManualOrganizationRun.StartOutcome.Started)
    }

    @Test
    fun startWithValidatedIntentInjectsTheProjectionIntoTheComposedInput() {
        // Issue #205 (spec 205 run connection): a run started from an imported
        // intent composes through the same seam and hands the planner the pure
        // preference projection; preview/confirmation are unchanged.
        val validated = app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent(
            intent = app.lawnchair.organizer.personalization.PersonalizedIntentV1(
                exportId = "export-1",
                itemIntents = emptyList(),
            ),
            export = app.lawnchair.organizer.personalization.PersonalizationContextExportV1(
                exportId = "export-1",
                tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED,
                grid = app.lawnchair.organizer.personalization.ExportGridContext(4, 5, 1),
                items = emptyList(),
                preservedConstraints = app.lawnchair.organizer.personalization.PreservedConstraints(
                    reservedRegions = emptyList(),
                    preservedCounts = emptyMap(),
                ),
                categories = emptyList(),
                capabilities = app.lawnchair.organizer.personalization.ExportCapabilities(
                    intentSchemaVersion = app.lawnchair.organizer.personalization.ContextExportContract.INTENT_SCHEMA_VERSION,
                    functions = app.lawnchair.organizer.personalization.ContextExportContract.FIXED_CAPABILITIES,
                ),
                usageSignals = null,
            ),
            session = app.lawnchair.organizer.personalization.ExportSession(
                exportId = "export-1",
                itemRefs = emptyMap(),
                tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED,
                sourceContextDigest = "digest",
                signalProvenance = null,
                createdAtEpochMs = 0L,
                expiresAtEpochMs = 1L,
            ),
            identity = app.lawnchair.organizer.personalization.IntentIdentityCalculator.identity(
                app.lawnchair.organizer.personalization.IntentCompletion.complete(
                    app.lawnchair.organizer.personalization.PersonalizedIntentV1(
                        exportId = "export-1",
                        itemIntents = emptyList(),
                    ),
                    emptySet(),
                ),
            ),
        )
        var plannedInput: OrganizationInput? = null
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { input ->
                plannedInput = input
                planningResult(movingPlan())
            },
            operationGate = OrganizationOperationLease,
        )
        assertTrue(runner.start(intent = validated) is ManualOrganizationRun.StartOutcome.Started)
        assertEquals(validated.identity, plannedInput?.intentPreferences?.identity)
        runner.cancel()
    }

    @Test
    fun exceptionDuringConfirmationReleasesTheOrganizationOperationLease() {
        val application = FakeApplication(readyInput()).apply {
            inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
            materializeOverride = { _, _ -> error("materialization failure") }
        }
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            operationGate = OrganizationOperationLease,
        )

        runner.start()
        assertThrowsIllegalState { runner.confirm() }
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)

        val admittedRunner = ManualOrganizationRun(
            FakeApplication(readyInput()),
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            operationGate = OrganizationOperationLease,
        )
        assertTrue(admittedRunner.start() is ManualOrganizationRun.StartOutcome.Started)
        admittedRunner.cancel()
    }

    @Test
    fun unavailableInputStopsBeforePlannerOrApplicationWrite() {
        val application = FakeApplication(
            composition = OrganizationInputComposition.NotReady(
                InputReadinessReason.InvalidCanonicalCapture(
                    app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                ),
                CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
            ),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
        assertEquals(
            listOf(
                app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.INPUT_NOT_READY,
            ),
            application.events.map { it.phase },
        )
    }

    @Test
    fun inputUnavailableRunEmitsTerminalInputNotReadyWithReadinessCode() {
        val application = FakeApplication(
            composition = OrganizationInputComposition.NotReady(
                InputReadinessReason.InvalidCanonicalCapture(
                    app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                ),
                CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
            ),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
        assertEquals(
            listOf(
                app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.INPUT_NOT_READY,
            ),
            application.events.map { it.phase },
        )
        val terminal = application.events.last()
        assertEquals(RUN_ID, terminal.runId)
        assertEquals(Trigger.MANUAL_FULL, terminal.trigger)
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.ErrorEntry(
                app.lawnchair.organizer.diagnostics.model.ErrorFamily.INPUT_READINESS,
                InputCompositionCode.CAPTURE_INVALID.name,
            ),
            terminal.error,
        )
    }

    @Test
    fun reconciliationPendingRunEmitsTerminalInputNotReadyWithPendingCode() {
        val application = FakeApplication(
            composition = OrganizationInputComposition.NotReady(
                InputReadinessReason.ReconciliationPending,
                CompositionDiagnostic(InputCompositionCode.RECONCILIATION_PENDING),
            ),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
        val terminal = application.events.last()
        assertEquals(app.lawnchair.organizer.diagnostics.model.PhaseCode.INPUT_NOT_READY, terminal.phase)
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.ErrorFamily.INPUT_READINESS,
            terminal.error?.family,
        )
        assertEquals(InputCompositionCode.RECONCILIATION_PENDING.name, terminal.error?.code)
    }

    @Test
    fun diagnosticsEmitFailureDoesNotBlockInputUnavailableTerminalState() {
        val application = FakeApplication(
            composition = OrganizationInputComposition.NotReady(
                InputReadinessReason.SourceUnavailable(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
                CompositionDiagnostic(InputCompositionCode.BUNDLE_MISSING),
            ),
        )
        application.diagnostics.emitOverride = { error("journal failure") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
    }

    @Test
    fun emptyPlanDoesNotMaterializeOrApply() {
        val application = FakeApplication(readyInput())
        val planner = OrganizationPlanner { planningResult(Planned(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) }
        val runner = ManualOrganizationRun(application, planner)

        runner.start()

        assertEquals(ManualOrganizationRun.State.NoChanges, runner.state)
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun confirmedPreviewCarriesOneRunIdIntoApplyAndManualDiagnostics() {
        val application = FakeApplication(readyInput())
        val planned = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = app.lawnchair.organizer.planning.ItemId("item"),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(PageRef(PageId("page")), app.lawnchair.organizer.planning.GridCell(0, 0), app.lawnchair.organizer.planning.GridSpan(1, 1)),
                ),
            ),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = emptyList(),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(planned) })

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        runner.confirm()

        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
        assertEquals(0, application.materializeCalls)
        assertEquals(1, application.applyCalls)
        assertEquals(RUN_ID, application.appliedRunId?.value)
        assertEquals(
            setOf(RUN_ID),
            application.events.mapNotNull { it.runId }.toSet(),
        )
        assertEquals(
            listOf(
                app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.CAPTURED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.PLANNED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.PREVIEWED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.USER_CONFIRMED,
            ),
            application.events.map { it.phase },
        )
    }

    @Test
    fun onboardingTriggerIsRetainedThroughPreviewConfirmationAndStaleRejection() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        application.materializeOverride = { _, _ -> OrganizationPlanMaterializer.Result.Invalid }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start(Trigger.ONBOARDING_PROPOSAL)
        runner.confirm()

        // Issue #210: a blocked apply attempt carries the apply-blocked origin.
        assertEquals(
            ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED),
            runner.state,
        )
        assertEquals(
            setOf(Trigger.ONBOARDING_PROPOSAL),
            application.events.mapNotNull { it.trigger }.toSet(),
        )
        assertEquals(
            listOf(
                app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.CAPTURED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.PLANNED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.PREVIEWED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.USER_CONFIRMED,
                app.lawnchair.organizer.diagnostics.model.PhaseCode.APPLY_REJECTED,
            ),
            application.events.map { it.phase },
        )
    }

    @Test
    fun defaultStartRetainsManualTrigger() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)), runner.start())

        assertEquals(
            setOf(Trigger.MANUAL_FULL),
            application.events.mapNotNull { it.trigger }.toSet(),
        )
    }

    @Test
    fun busyOnboardingStartDoesNotAttachToAnActiveManualRun() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        val manualStart = runner.start()
        val onboardingStart = runner.start(Trigger.ONBOARDING_PROPOSAL)

        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)), manualStart)
        assertEquals(ManualOrganizationRun.StartOutcome.Busy, onboardingStart)
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(
            listOf(RUN_ID),
            application.events.filter { it.phase == app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED }.map { it.runId },
        )
        assertEquals(setOf(Trigger.MANUAL_FULL), application.events.mapNotNull { it.trigger }.toSet())
    }

    @Test
    fun busyManualStartDoesNotAttachToAnActiveOnboardingRun() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        val onboardingStart = runner.start(Trigger.ONBOARDING_PROPOSAL)
        val manualStart = runner.start()

        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)), onboardingStart)
        assertEquals(ManualOrganizationRun.StartOutcome.Busy, manualStart)
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(setOf(Trigger.ONBOARDING_PROPOSAL), application.events.mapNotNull { it.trigger }.toSet())
    }

    @Test
    fun retryStartsAFreshRunAndRetainsOnboardingTrigger() {
        val application = FakeApplication(readyInput())
        application.nextRunIds = listOf(RUN_ID, SECOND_RUN_ID)
        var attempts = 0
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner {
                attempts++
                if (attempts == 1) {
                    planningResult(
                        app.lawnchair.organizer.planning.Rejected.Invalid(
                            reasons = emptyList(),
                            warnings = emptyList(),
                        ),
                    )
                } else {
                    planningResult(movingPlan())
                }
            },
        )

        val firstStart = runner.start(Trigger.ONBOARDING_PROPOSAL)
        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(RUN_ID)), firstStart)
        assertTrue(runner.state is ManualOrganizationRun.State.PlanningRejected)
        val retryStart = runner.start(Trigger.ONBOARDING_PROPOSAL)

        assertEquals(ManualOrganizationRun.StartOutcome.Started(RunId(SECOND_RUN_ID)), retryStart)
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(
            listOf(RUN_ID, SECOND_RUN_ID),
            application.events.filter { it.phase == app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED }.map { it.runId },
        )
        assertEquals(
            setOf(Trigger.ONBOARDING_PROPOSAL),
            application.events.mapNotNull { it.trigger }.toSet(),
        )
    }

    @Test
    fun previewAndResultRetainTypedReasonConstraintAndScopeProjection() {
        val application = FakeApplication(readyInput())
        val planned = Planned(
            placements = listOf(
                placement("moved", Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)),
                placement("locked", Disposition.Preserved(PreserveReason.LOCKED)),
                placement("widget", Disposition.Preserved(PreserveReason.WIDGET)),
                placement("canonical", Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)),
            ),
            newPages = emptyList(),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = listOf(Warning(WarningCode.FALLBACK_CATEGORY, emptyList())),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(planned) })

        runner.start()

        val summary = (runner.state as ManualOrganizationRun.State.Preview).summary
        assertEquals(mapOf(PlacementCode.SINGLE_PLACEMENT to 1), summary.movedByReason)
        assertEquals(
            mapOf(
                PreserveReason.LOCKED to 1,
                PreserveReason.WIDGET to 1,
                PreserveReason.ALREADY_CANONICAL to 1,
            ),
            summary.preservedByReason,
        )
        assertEquals(mapOf(WarningCode.FALLBACK_CATEGORY to 1), summary.warningCounts)
        assertEquals(0, summary.unplacedByReason.size)
        assertEquals(0, summary.scope.targetCount)
        assertEquals(4, summary.scope.columns)
        assertEquals(0, summary.constraints.lockedCount)
    }

    @Test
    fun impossiblePlanningRetainsUnplacedReasonProjection() {
        val application = FakeApplication(readyInput())
        val impossible = app.lawnchair.organizer.planning.Rejected.Impossible(
            unplaced = listOf(
                UnplacedItem(
                    item = app.lawnchair.organizer.planning.ItemId("unplaced"),
                    requiredSpan = app.lawnchair.organizer.planning.GridSpan(5, 5),
                    reason = UnplacedReason.EXCEEDS_GRID_DIMENSIONS,
                ),
            ),
            warnings = emptyList(),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(impossible) })

        runner.start()

        val state = runner.state as ManualOrganizationRun.State.PlanningRejected
        assertEquals(ManualOrganizationRun.PlanningFailureKind.IMPOSSIBLE, state.kind)
        assertEquals(mapOf(UnplacedReason.EXCEEDS_GRID_DIMENSIONS to 1), state.summary.unplacedByReason)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun invalidPlanningRetainsMajorRejectionReasonProjection() {
        val application = FakeApplication(readyInput())
        val invalid = app.lawnchair.organizer.planning.Rejected.Invalid(
            reasons = listOf(RejectionReason(RejectionCode.TARGET_PROFILE_MISMATCH, emptyList())),
            warnings = listOf(Warning(WarningCode.UNAVAILABLE_PRESERVED, emptyList())),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(invalid) })

        runner.start()

        val state = runner.state as ManualOrganizationRun.State.PlanningRejected
        assertEquals(mapOf(RejectionCode.TARGET_PROFILE_MISMATCH to 1), state.summary.rejectedByReason)
        assertEquals(mapOf(WarningCode.UNAVAILABLE_PRESERVED to 1), state.summary.warningCounts)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun previewedPlanIsAppliedDirectlyWithoutConfirmTimeMaterialization() {
        val application = FakeApplication(readyInput())
        val previewedPlan = minimalPlan(readyInput().input)
        val details = app.lawnchair.organizer.application.public.PlanPreviewDetails(
            changes = emptyList(),
            counts = app.lawnchair.organizer.application.public.PreviewCounts(1, 0, 0, 0, emptyMap()),
        )
        application.inspectPlanOverride = { _, _ ->
            PlanPreviewResult.Previewed(
                app.lawnchair.organizer.application.public.PlanPreview(
                    plan = previewedPlan,
                    details = details,
                ),
            )
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        val state = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(details, state.details)
        assertEquals(1, application.inspectPlanCalls)

        runner.confirm()

        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
        assertEquals(0, application.materializeCalls)
        assertEquals(1, application.applyCalls)
        assertTrue(application.lastAppliedPlan === previewedPlan)
    }

    @Test
    fun previewTimeStaleEndsRunWithA2RejectionAndNeverMaterializesOrApplies() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.Stale }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()

        // Issue #210: staleness detected before the proposal was ever shown.
        assertEquals(
            ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.DETECTED_BEFORE_REVIEW),
            runner.state,
        )
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.PhaseCode.APPLY_REJECTED,
            application.events.last().phase,
        )
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.ApplyStage.A2,
            application.events.last().applyStage,
        )
    }

    @Test
    fun previewWriterBusyFallsBackToCountOnlyPreviewAndConfirmMaterializes() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        val state = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(null, state.details)
        assertEquals(1, state.summary.movedCount)
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.PhaseCode.PREVIEWED,
            application.events.last().phase,
        )

        runner.confirm()

        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
        assertEquals(1, application.materializeCalls)
        assertEquals(1, application.applyCalls)
    }

    @Test
    fun previewIntegrityViolationsFailClosedWithoutMaterializeOrApply() {
        listOf(
            PlanPreviewRejection.MATERIALIZATION_INVALID,
            PlanPreviewRejection.OUTCOME_NOT_PLANNED,
        ).forEach { rejection ->
            val application = FakeApplication(readyInput())
            application.inspectPlanOverride = { _, _ -> PlanPreviewResult.NotPlannable(rejection) }
            val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

            runner.start()

            val state = runner.state as ManualOrganizationRun.State.PlanningRejected
            assertEquals(rejection.name, ManualOrganizationRun.PlanningFailureKind.IMPOSSIBLE, state.kind)
            assertEquals(rejection.name, 0, application.materializeCalls)
            assertEquals(rejection.name, 0, application.applyCalls)
            assertEquals(
                rejection.name,
                0,
                application.events.count {
                    it.phase == app.lawnchair.organizer.diagnostics.model.PhaseCode.PREVIEWED
                },
            )
        }
    }

    @Test
    fun previewCaptureFailureFallsBackToCountOnlyPreview() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ ->
            PlanPreviewResult.NotPlannable(PlanPreviewRejection.CAPTURE_FAILED)
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()

        val state = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(null, state.details)
        runner.confirm()
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
        assertEquals(1, application.materializeCalls)
    }

    @Test
    fun staleMaterializationIsVisibleAndNeverReachesApplicationWriter() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        application.materializeOverride = { _, _ -> OrganizationPlanMaterializer.Result.Invalid }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirm()

        assertEquals(
            ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED),
            runner.state,
        )
        assertEquals(0, application.applyCalls)
        assertEquals(app.lawnchair.organizer.diagnostics.model.PhaseCode.APPLY_REJECTED, application.events.last().phase)
    }

    @Test
    fun everyApplyResultFamilyIsRetainedWithTheVerifiedPlanSummary() {
        val results = listOf<ApplyResult>(
            ApplyResult.NoChanges(RunId(RUN_ID)),
            ApplyResult.Applied(RunId(RUN_ID), RecoveryPointId(POINT_ID)),
            ApplyResult.Rejected(RunId(RUN_ID), app.lawnchair.organizer.application.public.PreWriteRejection.INVALID_PLAN),
            ApplyResult.RolledBack(RunId(RUN_ID), app.lawnchair.organizer.application.public.ApplyFailure.WRITE_FAILED),
            ApplyResult.Recovered(RunId(RUN_ID), RecoveryPointId(POINT_ID), app.lawnchair.organizer.application.public.ApplyFailure.VERIFICATION_FAILED),
            ApplyResult.Unresolved(
                RunId(RUN_ID),
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.ApplyFailure.COMMIT_OUTCOME_UNKNOWN,
                app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
            ),
            ApplyResult.RecoveryFailed(
                RunId(RUN_ID),
                RecoveryPointId(POINT_ID),
                app.lawnchair.organizer.application.public.ApplyFailure.RECOVERY_STORE_FAILED,
                app.lawnchair.organizer.application.public.RecoveryFailure.RECOVERY_STORE_FAILED,
                app.lawnchair.organizer.application.public.AuthoritativeState.UNKNOWN,
            ),
            ApplyResult.ConcurrentRun,
        )

        results.forEach { expected ->
            val application = FakeApplication(readyInput())
            application.applyResult = expected
            val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

            runner.start()
            runner.confirm()

            if (expected is ApplyResult.NoChanges) {
                assertEquals(ManualOrganizationRun.State.NoChanges, runner.state)
            } else {
                val state = runner.state as ManualOrganizationRun.State.Applied
                assertEquals(expected, state.result)
                assertEquals(1, state.summary.movedCount)
            }
        }
    }

    @Test
    fun cancellationDuringCaptureIsObservableAndCannotApplyAfterCaptureReturns() {
        val application = FakeApplication(readyInput())
        val captureStarted = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        application.composeStarted = captureStarted
        application.composeRelease = releaseCapture
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        val worker = thread(start = true) { runner.start() }
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(ManualOrganizationRun.State.Capturing, runner.state)

        runner.cancel()
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        releaseCapture.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun cancellationBeforeApplicationAdmissionPreventsApply() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        val planned = movingPlan()
        val materializeStarted = CountDownLatch(1)
        val releaseMaterialize = CountDownLatch(1)
        application.materializeStarted = materializeStarted
        application.materializeRelease = releaseMaterialize
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(planned) })
        runner.start()

        val worker = thread(start = true) { runner.confirm() }
        assertTrue(materializeStarted.await(5, TimeUnit.SECONDS))
        assertEquals(ManualOrganizationRun.State.Applying, runner.state)

        runner.cancel()
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        releaseMaterialize.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun dismissBeforeApplicationAdmissionCancelsAndEmitsUserCancellation() {
        val application = FakeApplication(readyInput())
        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        val materializeStarted = CountDownLatch(1)
        val releaseMaterialize = CountDownLatch(1)
        application.materializeStarted = materializeStarted
        application.materializeRelease = releaseMaterialize
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()

        val worker = thread(start = true) { runner.confirm() }
        assertTrue(materializeStarted.await(5, TimeUnit.SECONDS))

        runner.dismiss()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertEquals(1, application.events.count { it.phase == app.lawnchair.organizer.diagnostics.model.PhaseCode.USER_CANCELLED })
        releaseMaterialize.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun dismissDoesNotClearVerifiedTerminalState() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirm()
        val applied = runner.state

        assertEquals(ManualOrganizationRun.DismissalOutcome.NoActiveOperation, runner.dismiss())
        assertEquals(applied, runner.state)
    }

    @Test
    fun dismissAfterApplicationAdmissionSuppressesNavigationAndPreservesApplyingState() {
        val application = FakeApplication(readyInput())
        val applyStarted = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        application.applyStarted = applyStarted
        application.applyRelease = releaseApply
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()

        val worker = thread(start = true) { runner.confirm() }
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS))

        assertEquals(ManualOrganizationRun.DismissalOutcome.ApplicationInProgress, runner.dismiss())
        assertEquals(ManualOrganizationRun.State.Applying, runner.state)
        releaseApply.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
    }

    @Test
    fun cancellationAfterApplicationAdmissionDoesNotInterruptAtomicApply() {
        val application = FakeApplication(readyInput())
        val applyStarted = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        application.applyStarted = applyStarted
        application.applyRelease = releaseApply
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()

        val worker = thread(start = true) { runner.confirm() }
        assertTrue(applyStarted.await(5, TimeUnit.SECONDS))
        runner.cancel()
        assertEquals(ManualOrganizationRun.State.Applying, runner.state)
        releaseApply.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
        assertEquals(1, application.applyCalls)
    }

    @Test
    fun cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface() {
        val application = FakeApplication(readyInput())
        application.recoveryPreview = RecoveryPreviewResult.Restorable(
            pointId = RecoveryPointId(POINT_ID),
            summary = RecoveryPreviewSummary(),
            confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        val applied = runner.state as ManualOrganizationRun.State.Applied

        runner.beginRecoveryPreview()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryPreview)
        runner.cancelRecoveryPreview()

        assertEquals(applied, runner.state)
        assertEquals(applied.summary, (runner.state as ManualOrganizationRun.State.Applied).summary)
    }

    @Test
    fun recoveryPreviewWithCaptureFailureSurfacesTypedUnavailableWithoutThrowing() {
        // Issue #270: the capture-failure Unavailable result must reach the
        // run state machine as a rendered preview (no exception, no confirm),
        // with cancel returning to the retained verified apply.
        val application = FakeApplication(readyInput())
        application.recoveryPreview = RecoveryPreviewResult.Unavailable(
            RecoveryPointId(POINT_ID),
            RecoveryPreviewUnavailable.CURRENT_LAYOUT_CAPTURE_UNAVAILABLE,
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        val applied = runner.state as ManualOrganizationRun.State.Applied

        runner.beginRecoveryPreview()

        val preview = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertEquals(
            RecoveryPreviewResult.Unavailable(RecoveryPointId(POINT_ID), RecoveryPreviewUnavailable.CURRENT_LAYOUT_CAPTURE_UNAVAILABLE),
            preview.result,
        )

        runner.cancelRecoveryPreview()
        assertEquals(applied, runner.state)
    }

    @Test
    fun recoveryPreviewCarriesTheCorrelatedApplyHistory() {
        val application = FakeApplication(readyInput())
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        val applied = runner.state as ManualOrganizationRun.State.Applied

        runner.beginRecoveryPreview()

        val preview = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertTrue(preview.result is RecoveryPreviewResult.Restorable)
        assertEquals(applied.summary, preview.appliedSummary)
        assertEquals(1, preview.appliedSummary?.movedCount)
    }

    @Test
    fun recoveryPreviewWithMismatchedPointIdOmitsApplyHistoryButKeepsConfirmationUsable() {
        val application = FakeApplication(readyInput())
        application.recoveryPreview = restorablePreview(OTHER_POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()

        runner.beginRecoveryPreview()

        val preview = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertTrue(preview.result is RecoveryPreviewResult.Restorable)
        assertEquals(null, preview.appliedSummary)

        runner.cancelRecoveryPreview()
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)
    }

    @Test
    fun recoveryPreviewFollowsTheLatestVerifiedApply() {
        val application = FakeApplication(readyInput())
        application.nextRunIds = listOf(RUN_ID, SECOND_RUN_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        // Issue #230 recommended test (d): apply B supersedes apply A, and the
        // confirmation carries B's history only.
        application.applyResult = ApplyResult.Applied(RunId(SECOND_RUN_ID), RecoveryPointId(OTHER_POINT_ID))
        runner.start()
        runner.confirm()
        val appliedB = runner.state as ManualOrganizationRun.State.Applied

        application.recoveryPreview = restorablePreview(OTHER_POINT_ID)
        runner.beginRecoveryPreview()
        val previewB = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertEquals(appliedB.summary, previewB.appliedSummary)

        runner.cancelRecoveryPreview()
        application.recoveryPreview = restorablePreview(POINT_ID)
        runner.beginRecoveryPreview()
        val previewA = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertEquals(null, previewA.appliedSummary)
    }

    @Test
    fun freshRunInstanceDoesNotReachRecoveryPreview() {
        val application = FakeApplication(readyInput())
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)

        // Issue #230 recommended test (e): a process restart constructs a
        // fresh run; the process-local apply context is empty, so the
        // confirmation surface is unreachable.
        val restarted = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        restarted.beginRecoveryPreview()

        assertEquals(ManualOrganizationRun.State.Idle, restarted.state)
    }

    @Test
    fun nonRestorablePreviewCarriesNoApplyHistory() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()

        runner.beginRecoveryPreview()

        val preview = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertTrue(preview.result is RecoveryPreviewResult.NotRestorable)
        assertEquals(null, preview.appliedSummary)
    }

    @Test
    fun durableEntryOpensPreviewFromIdleAndExplicitHubReturnRestoresPreEntryState() {
        // Issue #376 (RS-AC-01/04): the hub entry needs no apply context, the
        // preview carries no apply history, and the explicit hub return
        // restores the pre-entry state so the hub re-derives the durable row.
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)

        assertTrue(runner.beginRecoveryPreviewFromDurableEntry())

        val preview = runner.state as ManualOrganizationRun.State.RecoveryPreview
        assertTrue(preview.result is RecoveryPreviewResult.Restorable)
        assertEquals(null, preview.appliedSummary)

        runner.confirmRecovery()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)

        assertTrue(runner.leaveRecoveryResultToHub())
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
        // Idempotent: a second call is a no-op.
        assertFalse(runner.leaveRecoveryResultToHub())
    }

    @Test
    fun durableEntryFromCancelledStateRestoresCancelledOnPreviewCancel() {
        // Issue #376 (spec D5): the cancel return target is the pre-entry
        // display state — never a stale Applied face via lastVerifiedApply.
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.cancel()
        assertTrue(runner.state is ManualOrganizationRun.State.Cancelled)

        assertTrue(runner.beginRecoveryPreviewFromDurableEntry())
        runner.cancelRecoveryPreview()

        assertTrue(runner.state is ManualOrganizationRun.State.Cancelled)
    }

    @Test
    fun durableEntryFromIdleRestoresIdleOnPreviewCancel() {
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        assertTrue(runner.beginRecoveryPreviewFromDurableEntry())
        runner.cancelRecoveryPreview()

        assertEquals(ManualOrganizationRun.State.Idle, runner.state)
    }

    @Test
    fun durableEntryIsSilentlyRejectedOutsideIdleAndCancelledWithoutLeaseLeak() {
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)

        // The durable row is not visible on an Applied face, so the entry
        // rejects without touching the state — and the recovery lease must be
        // released for the legacy entry to still work.
        assertFalse(runner.beginRecoveryPreviewFromDurableEntry())
        assertTrue(runner.state is ManualOrganizationRun.State.Applied)

        runner.beginRecoveryPreview()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryPreview)
    }

    @Test
    fun durableEntryIsRejectedWhenTheSelectionReadFailsClosed() {
        val application = FakeApplication(readyInput())
        application.restorableEntry = null
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        assertFalse(runner.beginRecoveryPreviewFromDurableEntry())
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)

        // The lease is released; a later read recovery admits the entry.
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        assertTrue(runner.beginRecoveryPreviewFromDurableEntry())
    }

    @Test
    fun durableEntryLaunchHandoffIsConsumedExactlyOnceAndLostOnProcessDeath() {
        // Issue #376 (RS-AC-03): the hub CTA arms a process-local handoff; a
        // fresh coordinator (process death) has nothing armed, so a restored
        // durable-recovery route pops back to the hub instead of re-running
        // the flow.
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        assertFalse(runner.consumeDurableEntryLaunchArm())

        runner.armDurableEntryLaunch()
        assertTrue(runner.consumeDurableEntryLaunchArm())
        assertFalse(runner.consumeDurableEntryLaunchArm())

        // A fresh coordinator instance models the process death boundary.
        val restarted = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        assertFalse(restarted.consumeDurableEntryLaunchArm())
    }

    @Test
    fun hubOriginRecoveryResultStateSurvivesTheGenericDismissal() {
        // Issue #376 (RS-AC-04 / spec D5): the explicit hub return owns the
        // result-face exit; a generic dismissal (host dispose, diagnostics
        // push) must keep the terminal state so the result surface survives.
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.beginRecoveryPreviewFromDurableEntry()
        runner.confirmRecovery()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)

        runner.dismiss()

        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)
    }

    @Test
    fun durableEntryRepresentsTheNextRemainingPointAfterHubReturn() {
        // Issue #376 (RS-AC-02): with two retained points, restoring the
        // latest and returning to the hub re-presents the surviving point as
        // the next restore target (state-machine proof, not manual evidence).
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        assertTrue(runner.beginRecoveryPreviewFromDurableEntry())
        runner.confirmRecovery()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)
        assertTrue(runner.leaveRecoveryResultToHub())
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)

        // The hub's re-read now selects the surviving older point.
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(OTHER_POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(2),
        )
        application.recoveryPreview = restorablePreview(OTHER_POINT_ID)

        assertTrue(runner.beginRecoveryPreviewFromDurableEntry())
        val nextPreview = runner.state as ManualOrganizationRun.State.RecoveryPreview
        val restorable = nextPreview.result as RecoveryPreviewResult.Restorable
        assertEquals(RecoveryPointId(OTHER_POINT_ID), restorable.pointId)
    }

    @Test
    fun legacyRecoveryResultStateIsUnchangedByTheExplicitHubReturn() {
        val application = FakeApplication(readyInput())
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.start()
        runner.confirm()
        runner.beginRecoveryPreview()
        runner.confirmRecovery()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)

        // Applied-surface origin keeps the current behavior: the terminal
        // state is not dissolved by the hub-return path.
        assertFalse(runner.leaveRecoveryResultToHub())
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)
    }

    @Test
    fun newRunAdmissionDissolvesTheHubRecoveryEntryOrigin() {
        val application = FakeApplication(readyInput())
        application.restorableEntry = app.lawnchair.organizer.application.public.RestorableRecoveryEntry(
            pointId = RecoveryPointId(POINT_ID),
            remainingWindow = app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining(5),
        )
        application.recoveryPreview = restorablePreview(POINT_ID)
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        runner.beginRecoveryPreviewFromDurableEntry()
        runner.confirmRecovery()
        assertTrue(runner.state is ManualOrganizationRun.State.RecoveryResultState)

        runner.start()
        val runState = runner.state

        // The origin was dissolved by the new admission: the hub return does
        // nothing and the live run is untouched.
        assertFalse(runner.leaveRecoveryResultToHub())
        assertEquals(runState, runner.state)
    }

    private fun restorablePreview(pointId: String) = RecoveryPreviewResult.Restorable(
        pointId = RecoveryPointId(pointId),
        summary = RecoveryPreviewSummary(),
        confirmation = RecoveryPreviewConfirmation.issue(byteArrayOf(1)),
    )

    private fun readyInput() = OrganizationInputComposition.Ready(
        input = input(),
        provenance = InputProvenance(
            revision = RevisionId("revision"),
            rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
            targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
            policyBundle = PolicyBundleIdentity("v1", SHA_256),
            layoutStrategySelection = policyIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION),
        ),
    )

    private fun policyIdentity(source: PolicySourceKind) = PolicyInputIdentity(source, "v1", SHA_256)

    private fun input() = OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("revision"),
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
        ),
        taxonomy = TaxonomyContract(TaxonomyVersion("v1"), listOf(app.lawnchair.organizer.planning.CategoryId("other")), app.lawnchair.organizer.planning.CategoryId("other")),
        catalog = app.lawnchair.organizer.planning.ActiveCategoryCatalog(
            TaxonomyContract(TaxonomyVersion("v1"), listOf(app.lawnchair.organizer.planning.CategoryId("other")), app.lawnchair.organizer.planning.CategoryId("other")),
            emptyList(),
        ),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(emptyList(), emptyList()),
        runMode = RunMode.FullOrganization,
    )

    @Test
    fun summaryEchoesTheEffectiveStrategyIdentity() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(
            app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            preview.summary.organizationStrategy,
        )
    }

    // --- Issue #368: strategy write admission (spec AC-9) ---

    @Test
    fun aPausedStrategyWriteBlocksRunAdmissionUntilItsTerminal() {
        // AC-9(a): the write holds the AUTHORING token; a run start during
        // the write is Busy (the RUN token cannot take the shared domain).
        // After the write's terminal the domain is free and the run starts.
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult(movingPlan()) },
            operationGate = OrganizationOperationLease,
        )
        val writeGate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val arbiter = StrategyWriteArbiter(
            scope = scope,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            writeStrategy = { writeGate.await() },
            operationGate = OrganizationOperationLease,
            runOrRecoveryActive = { runner.operationActive.value },
        )

        assertEquals(StrategyWriteArbiter.StartOutcome.Started, arbiter.onStrategySelected(StrategyId("CANONICAL_PAGE_COMPACT_V1")))
        assertFalse(runner.operationActive.value)
        assertEquals(ManualOrganizationRun.StartOutcome.Busy, runner.start())
        assertTrue("the run must not have started during the write", runner.state is ManualOrganizationRun.State.Idle)

        writeGate.complete(true)
        assertEquals(StrategyWriteArbiter.State.IDLE, arbiter.state)
        assertTrue(runner.start() is ManualOrganizationRun.StartOutcome.Started)
        assertTrue(runner.operationActive.value)

        runner.cancel()
        scope.cancel()
    }

    @Test
    fun anActiveRunRefusesTheStrategyWriteWithoutAStoreCall() {
        // AC-9(b): while a run holds the RUN token, a selection attempt is a
        // typed non-write (RefusedRunOrRecoveryActive) — no store call, the
        // arbiter never enters Writing.
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult(movingPlan()) },
            operationGate = OrganizationOperationLease,
        )
        assertTrue(runner.start() is ManualOrganizationRun.StartOutcome.Started)
        assertTrue(runner.operationActive.value)

        val writes = java.util.concurrent.atomic.AtomicInteger()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val arbiter = StrategyWriteArbiter(
            scope = scope,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            writeStrategy = {
                writes.incrementAndGet()
                true
            },
            operationGate = OrganizationOperationLease,
            runOrRecoveryActive = { runner.operationActive.value },
        )

        assertEquals(
            StrategyWriteArbiter.StartOutcome.RefusedRunOrRecoveryActive,
            arbiter.onStrategySelected(StrategyId("CANONICAL_PAGE_COMPACT_V1")),
        )
        assertEquals(0, writes.get())
        assertEquals(StrategyWriteArbiter.State.IDLE, arbiter.state)

        runner.cancel()
        scope.cancel()
    }

    @Test
    fun operationActiveResetsAfterEveryTerminalState() {
        // AC-9(d): terminal display states (InputUnavailable, NoChanges,
        // Applied, Stale, Cancelled) stay visible after the operation ended.
        // The operation-lifetime projection — not the display State — must
        // read false so the strategy surface stays writable.

        // InputUnavailable (typed failure).
        val unavailable = ManualOrganizationRun(
            FakeApplication(
                OrganizationInputComposition.NotReady(
                    InputReadinessReason.InvalidCanonicalCapture(
                        app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                    ),
                    CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
                ),
            ),
            OrganizationPlanner { error("planner must not run") },
        )
        unavailable.start()
        assertTrue(unavailable.state is ManualOrganizationRun.State.InputUnavailable)
        assertFalse(unavailable.operationActive.value)

        // NoChanges (empty plan).
        val noChanges = ManualOrganizationRun(
            FakeApplication(readyInput()),
            OrganizationPlanner { planningResult(Planned(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) },
        )
        noChanges.start()
        assertEquals(ManualOrganizationRun.State.NoChanges, noChanges.state)
        assertFalse(noChanges.operationActive.value)

        // Applied (confirmed preview).
        val applied = ManualOrganizationRun(
            FakeApplication(readyInput()),
            OrganizationPlanner { planningResult(movingPlan()) },
        )
        applied.start()
        assertTrue(applied.operationActive.value)
        applied.confirm()
        assertTrue(applied.state is ManualOrganizationRun.State.Applied)
        assertFalse(applied.operationActive.value)

        // Stale (apply-blocked materialize rejection).
        val staleApplication = FakeApplication(readyInput())
        staleApplication.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        staleApplication.materializeOverride = { _, _ -> OrganizationPlanMaterializer.Result.Invalid }
        val stale = ManualOrganizationRun(staleApplication, OrganizationPlanner { planningResult(movingPlan()) })
        stale.start()
        assertTrue(stale.operationActive.value)
        stale.confirm()
        assertEquals(ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED), stale.state)
        assertFalse(stale.operationActive.value)

        // Cancelled (user cancel).
        val cancelled = ManualOrganizationRun(
            FakeApplication(readyInput()),
            OrganizationPlanner { planningResult(movingPlan()) },
        )
        cancelled.start()
        assertTrue(cancelled.operationActive.value)
        cancelled.cancel()
        assertEquals(ManualOrganizationRun.State.Cancelled, cancelled.state)
        assertFalse(cancelled.operationActive.value)
    }

    @Test
    fun aStrategyWriteIsPossibleAfterEveryTerminalState() {
        // AC-9(d): after EVERY terminal state the operation is over and the
        // real admission domain is free — a strategy write acquires the
        // AUTHORING token and completes (Started, back to Idle) on its own.
        val terminals = listOf(
            "InputUnavailable" to {
                val unavailable = ManualOrganizationRun(
                    FakeApplication(
                        OrganizationInputComposition.NotReady(
                            InputReadinessReason.InvalidCanonicalCapture(
                                app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
                            ),
                            CompositionDiagnostic(InputCompositionCode.CAPTURE_INVALID),
                        ),
                    ),
                    OrganizationPlanner { error("planner must not run") },
                )
                unavailable.start()
                unavailable to { assertTrue(unavailable.state is ManualOrganizationRun.State.InputUnavailable) }
            },

            "NoChanges" to {
                val noChanges = ManualOrganizationRun(
                    FakeApplication(readyInput()),
                    OrganizationPlanner { planningResult(Planned(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) },
                )
                noChanges.start()
                noChanges to { assertEquals(ManualOrganizationRun.State.NoChanges, noChanges.state) }
            },

            "Applied" to {
                val applied = ManualOrganizationRun(
                    FakeApplication(readyInput()),
                    OrganizationPlanner { planningResult(movingPlan()) },
                )
                applied.start()
                applied.confirm()
                applied to { assertTrue(applied.state is ManualOrganizationRun.State.Applied) }
            },

            "Stale" to {
                val staleApplication = FakeApplication(readyInput())
                staleApplication.inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
                staleApplication.materializeOverride = { _, _ -> OrganizationPlanMaterializer.Result.Invalid }
                val stale = ManualOrganizationRun(staleApplication, OrganizationPlanner { planningResult(movingPlan()) })
                stale.start()
                stale.confirm()
                stale to {
                    assertEquals(ManualOrganizationRun.State.Stale(ManualOrganizationRun.StaleOrigin.APPLY_BLOCKED), stale.state)
                }
            },

            "Cancelled" to {
                val cancelled = ManualOrganizationRun(
                    FakeApplication(readyInput()),
                    OrganizationPlanner { planningResult(movingPlan()) },
                )
                cancelled.start()
                cancelled.cancel()
                cancelled to { assertEquals(ManualOrganizationRun.State.Cancelled, cancelled.state) }
            },
        )
        for ((name, drive) in terminals) {
            val (runner, assertTerminal) = drive()
            assertTerminal()

            assertFalse("$name: operation lifetime must be over", runner.operationActive.value)

            // The real admission domain admits a fresh strategy write and the
            // write completes back to Idle on its own.
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            val arbiter = StrategyWriteArbiter(
                scope = scope,
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                writeStrategy = { true },
                operationGate = OrganizationOperationLease,
                runOrRecoveryActive = { runner.operationActive.value },
            )
            assertEquals(
                "$name: a strategy write must be possible after the terminal",
                StrategyWriteArbiter.StartOutcome.Started,
                arbiter.onStrategySelected(StrategyId("CANONICAL_PAGE_COMPACT_V1")),
            )
            assertEquals("$name: the write completed back to Idle", StrategyWriteArbiter.State.IDLE, arbiter.state)
            scope.cancel()
        }
    }

    private fun c1Target() = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
        app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
        app.lawnchair.organizer.planning.ProfileId("personal"),
    )

    private fun validatedIntentFor(
        target: app.lawnchair.organizer.planning.CandidateTarget.AppKey,
        category: String?,
    ): app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent {
        val addition = app.lawnchair.organizer.planning.CandidateItem(
            id = app.lawnchair.organizer.planning.CandidatePlanningIds.planningId(target),
            profile = target.profile,
            kind = app.lawnchair.organizer.planning.CandidateKind.APPLICATION,
            target = target,
            availability = Availability.AVAILABLE,
            span = app.lawnchair.organizer.planning.GridSpan(1, 1),
        )
        val built = app.lawnchair.organizer.personalization.ContextExportBuilder.build(
            app.lawnchair.organizer.personalization.ExportInputs(
                snapshot = app.lawnchair.organizer.planning.LayoutSnapshot(
                    revision = app.lawnchair.organizer.planning.RevisionId("rev"),
                    device = app.lawnchair.organizer.planning.DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
                    pages = listOf(app.lawnchair.organizer.planning.Page(app.lawnchair.organizer.planning.PageId("page"), app.lawnchair.organizer.planning.PageOrder(0))),
                    items = emptyList(),
                ),
                targets = TargetSet(emptyList(), listOf(addition)),
                resolvedIdentities = mapOf(addition.id to category?.let { app.lawnchair.organizer.planning.CategoryIdentity.BuiltIn(app.lawnchair.organizer.planning.CategoryId(it)) }),
                nowEpochMs = 1_000L,
            ),
            app.lawnchair.organizer.personalization.PrivacyTier.LOCAL_FULL,
            app.lawnchair.organizer.personalization.SequentialIdAllocator(),
        )
        val intent = app.lawnchair.organizer.personalization.PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = emptyList(),
            unresolvedRefs = built.export.items.map { it.ref },
        )
        val validation = app.lawnchair.organizer.personalization.IntentValidator.validate(
            intent,
            built.export,
            built.session,
            1_000L,
            built.session.sourceContextDigest,
        )
        return (validation as app.lawnchair.organizer.personalization.IntentValidation.Validated).validated
    }

    @Test
    fun attachIntentBindsToTheSelectionSurfaceOnce() {
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Selecting)

        val attached = runner.attachIntent(validatedIntentFor(c1Target(), category = null))
        assertEquals(ManualOrganizationRun.AttachIntentOutcome.Attached, attached)
        assertEquals(1, (runner.state as ManualOrganizationRun.State.Selecting).intentScopeCount)
        assertEquals(
            ManualOrganizationRun.AttachIntentOutcome.NotAttachable,
            runner.attachIntent(validatedIntentFor(c1Target(), category = null)),
        )
    }

    @Test
    fun scopeBindingRejectsASelectionMissingTheExportedCandidate() {
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
        runner.start()
        runner.attachIntent(validatedIntentFor(c1Target(), category = null))

        // The export scope holds the candidate; confirming an empty selection
        // diverges → SCOPE_MISMATCH, zero-write, surface re-opens with guidance.
        runner.confirmSelection(emptySet())

        val state = runner.state as ManualOrganizationRun.State.Selecting
        val rejection = state.scopeRejection
        assertEquals(
            app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(
                app.lawnchair.organizer.personalization.ScopeMismatchCause.SET_MISMATCH,
            ),
            rejection,
        )
        assertEquals(1, state.intentScopeCount)
        assertEquals(0, application.applyCalls)
        assertEquals(0, application.composeScopeComposedCalls)
    }

    @Test
    fun scopeBindingProjectionDriftReturnsToSelectionWithZeroWrites() {
        // The export saw the candidate as NEWS; the composition (no signals)
        // resolves no category → the projection digest diverges (D-4) even
        // though the selection matches exactly. The run RETURNS to the
        // selection surface (stale intent discarded so the user can re-export
        // and re-attach); nothing is written.
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
        runner.start()
        runner.attachIntent(validatedIntentFor(c1Target(), category = "NEWS"))

        runner.confirmSelection(setOf(c1Target()))

        val state = runner.state as ManualOrganizationRun.State.Selecting
        val rejection = state.scopeRejection
        assertEquals(
            app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(
                app.lawnchair.organizer.personalization.ScopeMismatchCause.PROJECTION_MISMATCH,
            ),
            rejection,
        )
        assertEquals(0, state.intentScopeCount)
        assertEquals(0, application.applyCalls)

        // The stale intent is discarded: a fresh exchange can attach again.
        assertEquals(
            ManualOrganizationRun.AttachIntentOutcome.Attached,
            runner.attachIntent(validatedIntentFor(c1Target(), category = null)),
        )
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun scopeBindingWithoutASelectionSurfaceFailsTypedZeroWrite() {
        // Detection unavailable → no selection surface exists; the gate's
        // SET_MISMATCH surfaces as the typed terminal failure (zero-write).
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
        val intent = validatedIntentFor(c1Target(), category = null)

        runner.start(intent = intent)

        val state = runner.state as ManualOrganizationRun.State.ScopeMismatchFailed
        assertEquals(
            app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(
                app.lawnchair.organizer.personalization.ScopeMismatchCause.CANDIDATE_UNRESOLVED,
            ),
            state.failure,
        )
        assertEquals(0, application.applyCalls)
    }

    private fun candidate(id: String) = app.lawnchair.organizer.planning.CandidateItem(
        id = app.lawnchair.organizer.planning.ItemId(id),
        profile = app.lawnchair.organizer.planning.ProfileId("personal"),
        kind = app.lawnchair.organizer.planning.CandidateKind.APPLICATION,
        target = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
            app.lawnchair.organizer.planning.ComponentKey("com.example.$id"),
            app.lawnchair.organizer.planning.ProfileId("personal"),
        ),
        availability = Availability.AVAILABLE,
        span = app.lawnchair.organizer.planning.GridSpan(1, 1),
    )

    private fun scopeReadyInput() = readyInput().let { composition ->
        composition.copy(
            input = composition.input.copy(
                runMode = RunMode.ScopeComposedOrganization,
                targets = TargetSet(emptyList(), listOf(candidate("c1"))),
            ),
        )
    }

    private fun detected(vararg components: String) = app.lawnchair.organizer.integration.CandidateDetectionResult.Ready(
        components.map { component ->
            app.lawnchair.organizer.integration.DetectedCandidate(
                target = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                    app.lawnchair.organizer.planning.ComponentKey(component),
                    app.lawnchair.organizer.planning.ProfileId("personal"),
                ),
                label = component,
                availability = Availability.AVAILABLE,
            )
        },
    )

    @Test
    fun detectionReadyOpensTheSelectionSurfaceWithoutComposingOrWriting() {
        val application = FakeApplication(readyInput()).apply {
            detection = detected("com.example.a/.Main", "com.example.b/.Main")
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run before selection") })

        runner.start()

        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        assertEquals(2, selecting.candidates.size)
        assertEquals(0, application.composeScopeComposedCalls)
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun detectionFailureFallsThroughToThePlainFullFlow() {
        // The fake's default detection is Unavailable; the run must continue
        // as the plain full organize without opening the selection surface.
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(0, application.composeScopeComposedCalls)
        // Review P2 #3: the run's diagnostics mode is constant — the
        // detection-only run never mixes FULL_ORGANIZATION with
        // SCOPE_COMPOSED_ORGANIZATION on one runId.
        val runModes = application.events.map { it.runMode }.toSet()
        assertEquals(
            setOf(app.lawnchair.organizer.diagnostics.model.RunMode.FULL_ORGANIZATION),
            runModes,
        )
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.RunMode.FULL_ORGANIZATION,
            application.events.last { it.phase == app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED }.runMode,
        )
    }

    @Test
    fun scopeComposedRunKeepsOneDiagnosticsModeAcrossAllEvents() {
        // Review P2 #3: RUN_STARTED is emitted only after the selection
        // resolves the mode, so every event of the run carries the same
        // SCOPE_COMPOSED_ORGANIZATION identity.
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.c1")
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirmSelection(
            setOf(
                app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                    app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
                    app.lawnchair.organizer.planning.ProfileId("personal"),
                ),
            ),
        )
        // Issue #417: the manual confirm freezes the scope; the composed
        // phase starts from the "このまま整理" arm.
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
        runner.planWithConfirmedScope()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)

        val runModes = application.events.map { it.runMode }.toSet()
        assertEquals(
            setOf(app.lawnchair.organizer.diagnostics.model.RunMode.SCOPE_COMPOSED_ORGANIZATION),
            runModes,
        )
        // No FULL_ORGANIZATION-tagged event exists for this runId at all.
        assertTrue(
            application.events.none { it.runMode == app.lawnchair.organizer.diagnostics.model.RunMode.FULL_ORGANIZATION },
        )
    }

    @Test
    fun confirmingAnEmptySelectionConfirmsAnExplicitZeroScopeAndThePlainArmComposesFull() {
        // Issue #417 (AC-4): confirming with nothing selected is an explicit
        // zero-selection scope — never an undecided surface, and nothing
        // composes yet. Only the method choice's "このまま整理" arm runs the
        // plain full organization.
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.a/.Main") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirmSelection(emptySet())

        val confirmed = runner.state as ManualOrganizationRun.State.ScopeConfirmed
        assertTrue(confirmed.selection.isEmpty())
        assertTrue(confirmed.candidates.isNotEmpty())
        assertEquals(0, application.composeScopeComposedCalls)

        runner.planWithConfirmedScope()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(0, application.composeScopeComposedCalls)
    }

    @Test
    fun confirmingASelectionRunsTheScopeComposedComposeWithDeterministicOrder() {
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.b/.Main", "com.example.a/.Main", "com.example.c/.Main")
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        val identities = selecting.candidates.map { it.target }.toSet()
        runner.confirmSelection(identities)
        // Issue #417: the confirm freezes the scope; the compose happens on
        // the method choice's "このまま整理" arm with the frozen selection.
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
        runner.planWithConfirmedScope()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(1, application.composeScopeComposedCalls)
        assertEquals(
            identities.sortedWith(compareBy({ it.component.value }, { it.profile.value })),
            application.composeSelection,
        )
    }

    @Test
    fun cancellingFromTheSelectionSurfaceWritesNothing() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.a/.Main") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()
        runner.cancel()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertEquals(0, application.composeScopeComposedCalls)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun addRunWithoutConcretePreviewStopsAtPreviewUnavailableAndCannotConfirm() {
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.c1")
            inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirmSelection(
            setOf(
                app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                    app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
                    app.lawnchair.organizer.planning.ProfileId("personal"),
                ),
            ),
        )
        runner.planWithConfirmedScope()

        // Spec AC-14: no count-only fallback for an Add run.
        assertTrue(runner.state is ManualOrganizationRun.State.PreviewUnavailable)

        // Confirm is impossible from this state — apply never happens.
        runner.confirm()
        assertEquals(0, application.applyCalls)

        // Re-preview succeeds and restores the confirmable preview.
        application.inspectPlanOverride = null
        runner.retryPlanPreview()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
    }

    @Test
    fun cancellingFromPreviewUnavailableEndsTheRunAndReleasesTheOperation() {
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.c1")
            inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult(movingPlan()) },
            operationGate = OrganizationOperationLease,
        )

        runner.start()
        runner.confirmSelection(
            setOf(
                app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                    app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
                    app.lawnchair.organizer.planning.ProfileId("personal"),
                ),
            ),
        )
        runner.planWithConfirmedScope()
        assertTrue(runner.state is ManualOrganizationRun.State.PreviewUnavailable)

        runner.cancel()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertEquals(0, application.applyCalls)
        // The released lease admits a fresh run on the shared gate immediately
        // (the fresh runner is dismissed again so the gate is free for later
        // tests on the shared singleton).
        val freshRunner = ManualOrganizationRun(
            FakeApplication(readyInput()),
            OrganizationPlanner { planningResult(movingPlan()) },
            operationGate = OrganizationOperationLease,
        )
        assertTrue(freshRunner.start() is ManualOrganizationRun.StartOutcome.Started)
        freshRunner.cancel()
        // The cancelled scope-composed run reports its diagnostics mode
        // consistently on the terminal USER_CANCELLED event.
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.RunMode.SCOPE_COMPOSED_ORGANIZATION,
            application.events.last { it.phase == app.lawnchair.organizer.diagnostics.model.PhaseCode.USER_CANCELLED }.runMode,
        )
    }

    @Test
    fun addRunPreviewUnavailableRetrySurfacesStalenessThroughTheInspectSeam() {
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.c1")
            inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirmSelection(
            setOf(
                app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                    app.lawnchair.organizer.planning.ComponentKey("com.example.c1"),
                    app.lawnchair.organizer.planning.ProfileId("personal"),
                ),
            ),
        )
        runner.planWithConfirmedScope()
        assertTrue(runner.state is ManualOrganizationRun.State.PreviewUnavailable)

        application.inspectPlanOverride = { _, _ -> PlanPreviewResult.Stale }
        runner.retryPlanPreview()

        assertTrue(runner.state is ManualOrganizationRun.State.Stale)
        assertEquals(ManualOrganizationRun.StaleOrigin.DETECTED_BEFORE_REVIEW, (runner.state as ManualOrganizationRun.State.Stale).origin)
    }

    @Test
    fun nonAddRunKeepsTheCountOnlyFallbackForEnvironmentalPreviewFailures() {
        // Regression for spec AC-14's second half: an Add-less run still
        // falls back to the count-only preview (existing behavior).
        val application = FakeApplication(readyInput()).apply {
            inspectPlanOverride = { _, _ -> PlanPreviewResult.WriterBusy }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()

        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(null, preview.details)
    }

    private fun planningResult(outcome: app.lawnchair.organizer.planning.PlanningOutcome) = PlanningResult(
        revision = RevisionId("revision"),
        ruleVersion = RuleVersion("v2"),
        taxonomyVersion = TaxonomyVersion("v1"),
        organizationStrategy = app.lawnchair.organizer.planning.StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        outcome = outcome,
    )

    private fun movingPlan() = Planned(
        placements = listOf(placement("item", Disposition.Moved(PlacementCode.SINGLE_PLACEMENT))),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

    // --- Issue #228 review follow-ups: partial placement, typed preview
    // resolution failure, and pre-start journal silence ---

    // --- Issue #369: D-06 zero-candidate continuation, cancel gate (RD-6),
    // preparation-phase projection (RD-7) ---

    /**
     * Issue #369 (RD-7): an Unconfined collector observes every intermediate
     * publish synchronously inside the emitting lock section, recording the
     * visible phase at the moment each state was observed.
     */
    private fun collectStateWithPhase(
        runner: ManualOrganizationRun,
    ): Pair<MutableList<Pair<ManualOrganizationRun.State, ManualOrganizationRun.PreparationPhase>>, Job> {
        val seen = mutableListOf<Pair<ManualOrganizationRun.State, ManualOrganizationRun.PreparationPhase>>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            runner.stateFlow.collect { state -> seen += state to runner.preparationPhase.value }
        }
        return seen to job
    }

    @Test
    fun manualZeroCandidatesStopAtTheConfirmedScopeAndThePlainArmComposesFull() {
        // Issue #417 (AC-3, replacing the #369 D-06 pass-through for manual
        // runs): an empty cut never enters Selecting at the state level — the
        // machine publishes ScopeConfirmed(empty) and waits for the method
        // choice. The Unconfined collector proves the pass-through is gone.
        val application = FakeApplication(readyInput()).apply {
            detection = detected()
            composeOverride = { error("the composed phase must not start before the method choice") }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        val (observations, collector) = collectStateWithPhase(runner)

        runner.start()

        val confirmed = runner.state as ManualOrganizationRun.State.ScopeConfirmed
        assertTrue(confirmed.candidates.isEmpty())
        assertTrue(confirmed.selection.isEmpty())
        assertTrue(
            "no internal Selecting(empty) may be published for a manual zero cut",
            observations.none { it.first is ManualOrganizationRun.State.Selecting },
        )
        assertEquals(0, application.composeScopeComposedCalls)
        assertTrue("nothing may be composed or journaled while parked", application.events.isEmpty())

        // The "このまま整理" arm: the plain full organization (empty scope).
        application.composeOverride = null
        runner.planWithConfirmedScope()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(0, application.composeScopeComposedCalls)
        collector.cancel()
    }

    @Test
    fun zeroCutWithExportScopeCandidatesStillOpensTheSelectionSurface() {
        // RD-3 guard: an intent-bound run whose export scope holds candidates
        // opens T-08 on a zero detection cut so the spec 331 mismatch display
        // contract survives D-06.
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected() }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start(intent = validatedIntentWithScopeCandidates(listOf(appKey("com.example.c1"))))

        assertTrue("expected Selecting, got ${runner.state}", runner.state is ManualOrganizationRun.State.Selecting)
        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        assertTrue(selecting.candidates.isEmpty())
        assertEquals(1, selecting.intentScopeCount)
        assertEquals(ManualOrganizationFace.SELECTION, manualOrganizationFace(runner.state))
    }

    @Test
    fun preparationPhaseStartsAtDetectionAndNeverReannouncesItAfterTheSelectionSurface() {
        // RD-7: the first visible phase after admission is detection (the
        // legacy admission Capturing projects as 検出), the selection surface
        // opens for a non-empty cut, and the Capturing publish that returns to
        // T-09 after T-08 always commits with CAPTURE — never a re-shown
        // detection. The Unconfined collector reads the projection inside the
        // emitting lock section, so no conflation timing can hide a stale
        // pairing.
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.a/.Main") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        val (observations, collector) = collectStateWithPhase(runner)

        runner.start()
        assertEquals(ManualOrganizationRun.PreparationPhase.DETECTION, runner.preparationPhase.value)
        assertEquals(ManualOrganizationFace.SELECTION, manualOrganizationFace(runner.state))

        runner.confirmSelection(
            setOf(
                app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                    app.lawnchair.organizer.planning.ComponentKey("com.example.a/.Main"),
                    app.lawnchair.organizer.planning.ProfileId("personal"),
                ),
            ),
        )
        // Issue #417: the confirm freezes the scope; the composed phase starts
        // from the method choice's "このまま整理" arm.
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
        runner.planWithConfirmedScope()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        collector.cancel()

        // Admission legacy Capturing carries the detection phase…
        assertTrue(
            "admission Capturing must project as detection",
            observations.any {
                it.first is ManualOrganizationRun.State.Capturing && it.second == ManualOrganizationRun.PreparationPhase.DETECTION
            },
        )
        // …and every Capturing published after the confirmed scope leaves the
        // method-choice face carries CAPTURE — the visible column never goes
        // back to 検出.
        val postSelectingCaptures = observations.windowed(2).mapNotNull { (previous, current) ->
            if (previous.first is ManualOrganizationRun.State.ScopeConfirmed &&
                current.first is ManualOrganizationRun.State.Capturing
            ) {
                current
            } else {
                null
            }
        }
        assertTrue(
            "expected a T-08 return capture transition, got $observations",
            postSelectingCaptures.isNotEmpty(),
        )
        assertTrue(
            "T-08 return Capturing must commit with CAPTURE",
            postSelectingCaptures.all { it.second == ManualOrganizationRun.PreparationPhase.CAPTURE },
        )
    }

    @Test
    fun preparationPhaseResetsToDetectionForTheNextRun() {
        // RD-7: every admission restarts the visible progression at detection —
        // a fresh run never inherits the previous run's captured phase.
        val application = FakeApplication(readyInput()).apply {
            detectStarted = CountDownLatch(1)
            detectRelease = CountDownLatch(1)
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        val first = thread { runner.start() }
        assertTrue(application.detectStarted?.await(5, TimeUnit.SECONDS) == true)
        runner.cancel()
        application.detectRelease?.countDown()
        first.join(5000)

        application.detectStarted = CountDownLatch(1)
        application.detectRelease = CountDownLatch(1)
        val second = thread { runner.start() }
        assertTrue(application.detectStarted?.await(5, TimeUnit.SECONDS) == true)
        assertEquals(ManualOrganizationRun.PreparationPhase.DETECTION, runner.preparationPhase.value)
        assertEquals(ManualOrganizationRun.State.CandidateDetection, runner.state)
        runner.cancel()
        application.detectRelease?.countDown()
        second.join(5000)
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
    }

    @Test
    fun detectionUnavailableContinuationAdvancesTheVisiblePhaseWithoutASecondDetection() {
        // RD-7: the detection-unavailable continuation enters the composed
        // phase through the cancel gate — the visible column moves from
        // detection to capture and never re-shows 検出 afterwards.
        val application = FakeApplication(readyInput()) // default detection = Unavailable
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        val (observations, collector) = collectStateWithPhase(runner)

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(ManualOrganizationRun.PreparationPhase.PLAN, runner.preparationPhase.value)
        collector.cancel()

        val lastDetection = observations.indexOfLast { it.second == ManualOrganizationRun.PreparationPhase.DETECTION }
        assertTrue(
            "expected the projection to reach capture, got $observations",
            observations.any { it.second == ManualOrganizationRun.PreparationPhase.CAPTURE },
        )
        assertTrue(
            "no detection re-shown after the composed phase began",
            lastDetection < observations.indexOfLast { it.second == ManualOrganizationRun.PreparationPhase.CAPTURE },
        )
    }

    @Test
    fun onboardingZeroCutContinuationAdvancesThePreparationPhaseToCapture() {
        // RD-7, onboarding-only since #417: the internal zero-candidate
        // continuation (D-16 fixed path) commits CAPTURE with (before) its
        // Capturing publish, and the confirmation face follows.
        val application = FakeApplication(readyInput()).apply { detection = detected() }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        val (observations, collector) = collectStateWithPhase(runner)

        runner.start(Trigger.ONBOARDING_PROPOSAL)

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(ManualOrganizationRun.PreparationPhase.PLAN, runner.preparationPhase.value)
        assertEquals(ManualOrganizationFace.CONFIRMATION, manualOrganizationFace(runner.state))
        collector.cancel()
        // The pass-through published Selecting(empty) and the continuation
        // immediately committed CAPTURE — both are in the observed column.
        assertTrue(
            "expected the internal Selecting(empty) pass-through",
            observations.any { (state, _) -> state is ManualOrganizationRun.State.Selecting && state.candidates.isEmpty() },
        )
        assertTrue(
            "expected CAPTURE to be committed by the continuation",
            observations.any { it.second == ManualOrganizationRun.PreparationPhase.CAPTURE },
        )
    }

    @Test
    fun cancelDuringDetectionThenDetectorReturnsKeepsTheRunCancelledAndTheJournalEmpty() {
        // RD-6 / RUN-AC-10: T-09 makes cancel during detection user-reachable.
        // A cancel that lands before the composed-phase gate must leave the
        // journal empty for this runId and the lease released exactly once —
        // no RUN_STARTED, no composition, no planning, regardless of the
        // detector result. The blocking detector holds the run in
        // CandidateDetection while the cancel lands, so the race is
        // deterministic.
        val outcomes = listOf(
            "empty" to app.lawnchair.organizer.integration.CandidateDetectionResult.Ready(emptyList()),
            "unavailable" to app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
                app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
            ),
            "candidates" to detected("com.example.a/.Main"),
        )
        for ((name, detectorOutcome) in outcomes) {
            val application = FakeApplication(readyInput()).apply {
                detection = detectorOutcome
                detectStarted = CountDownLatch(1)
                detectRelease = CountDownLatch(1)
            }
            val gate = CountingGate()
            val plannerRan = booleanArrayOf(false)
            val runner = ManualOrganizationRun(
                application,
                OrganizationPlanner {
                    plannerRan[0] = true
                    planningResult(movingPlan())
                },
                operationGate = gate,
            )

            val startThread = thread { runner.start() }
            assertTrue("[$name] detector reached", application.detectStarted?.await(5, TimeUnit.SECONDS) == true)
            runner.cancel()
            application.detectRelease?.countDown()
            startThread.join(5000)
            assertFalse("[$name] start() settled", startThread.isAlive)

            assertEquals("[$name]", ManualOrganizationRun.State.Cancelled, runner.state)
            assertFalse("[$name] planner must not run after a cancel", plannerRan[0])
            assertTrue(
                "[$name] no journal events for a cancelled pre-composed run",
                application.events.isEmpty(),
            )
            assertEquals("[$name] lease released exactly once", 1, gate.closeCount)
            assertEquals(
                "[$name] projection stays at detection",
                ManualOrganizationRun.PreparationPhase.DETECTION,
                runner.preparationPhase.value,
            )
        }
    }

    @Test
    fun cancelAfterTheComposedGateFollowsRunStarted() {
        // RD-6 contrast case: a cancel that lands after the gate committed sees
        // RUN_STARTED first and records USER_CANCELLED after it.
        val application = FakeApplication(readyInput()) // detection = Unavailable → straight through the gate
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner { planningResult(movingPlan()) },
        )

        runner.start()
        runner.cancel()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        val phases = application.events.map { it.phase }
        assertTrue(
            "expected RUN_STARTED before USER_CANCELLED, got $phases",
            phases.indexOf(app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED) <
                phases.indexOf(app.lawnchair.organizer.diagnostics.model.PhaseCode.USER_CANCELLED),
        )
    }

    private fun appKey(component: String) = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
        app.lawnchair.organizer.planning.ComponentKey(component),
        app.lawnchair.organizer.planning.ProfileId("personal"),
    )

    /** Issue #369 (RD-6): a gate whose lease counts `.close()` calls. */

    // region Issue #371: JIT Usage Access request pause (spec 371)

    @Test
    fun detectionUnavailablePausesForTheJitRequestBeforeTheJournalOpens() {
        val application = FakeApplication(readyInput())
        val gate = UsageAccessJitGate(isGranted = { false })
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { error("planner must not run while paused") },
            operationGate = OrganizationOperationLease,
            usageAccessGate = gate,
        )

        val outcome = runner.start()

        assertTrue(outcome is ManualOrganizationRun.StartOutcome.Started)
        val awaiting = runner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        assertTrue(awaiting.isOwner)
        assertEquals(null, awaiting.selection)
        assertTrue(application.events.isEmpty())
        // The RUN lease stays held: a second start is Busy.
        assertTrue(runner.start() is ManualOrganizationRun.StartOutcome.Busy)
        // The host has not presented yet: the reservation is un-presented.
        assertEquals(UsageAccessJitGate.Phase.Reserved, gate.ownedPhase(awaiting.runId))

        // Test hygiene: release the process-wide RUN lease.
        runner.cancel()
        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
    }

    @Test
    fun jitResolutionResumesTheCompositionExactlyOnce() {
        val application = FakeApplication(readyInput())
        val gate = UsageAccessJitGate(isGranted = { false })
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )

        runner.start()
        val awaiting = runner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        gate.markPresented(awaiting.runId)

        runner.continueAfterUsageAccessGate()
        runner.continueAfterUsageAccessGate()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(1, application.events.count { it.phase == PhaseCode.RUN_STARTED })
    }

    @Test
    fun cancellingDuringTheJitPauseKeepsTheJournalEmptyAndReleasesTheOpportunity() {
        val application = FakeApplication(readyInput())
        val gate = UsageAccessJitGate(isGranted = { false })
        val runner = ManualOrganizationRun(
            application = application,
            operationGate = OrganizationOperationLease,
            usageAccessGate = gate,
        )

        runner.start()
        runner.cancel()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertTrue(application.events.isEmpty())
        assertEquals(UsageAccessJitGate.Phase.Available, gate.snapshot.value.phase)
        // The next trigger requests again — the opportunity was not consumed.
        assertTrue(runner.start() is ManualOrganizationRun.StartOutcome.Started)
        assertTrue(runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit)

        // Test hygiene: release the process-wide RUN lease.
        runner.cancel()
    }

    @Test
    fun cancellingAfterPresentationResolvesTheRequestSoWaitersProceed() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val ownerApp = FakeApplication(readyInput())
        val owner = ManualOrganizationRun(
            application = ownerApp,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )
        owner.start()
        val ownerAwaiting = owner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        gate.markPresented(ownerAwaiting.runId)

        owner.cancel()

        // Abandon resolution: waiters unblock, the destroyed owner composes nothing.
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)
        assertTrue(ownerApp.events.isEmpty())

        val waiterApp = FakeApplication(readyInput())
        val waiter = ManualOrganizationRun(
            application = waiterApp,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )
        waiter.start()

        assertFalse(waiter.state is ManualOrganizationRun.State.AwaitingUsageAccessJit)
        assertTrue(waiter.state is ManualOrganizationRun.State.Preview)
    }

    @Test
    fun continueAfterThePauseWasCancelledDoesNotResurrectTheRun() {
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(
            application = application,
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )

        runner.start()
        runner.cancel()
        runner.continueAfterUsageAccessGate()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertTrue(application.events.isEmpty())
    }

    @Test
    fun selectionConfirmationPausesWithTheSelectionAndResumesScopeComposed() {
        val application = FakeApplication(readyInput()).apply {
            detection = detected("app.missing.one")
        }
        val gate = UsageAccessJitGate(isGranted = { false })
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Selecting)

        val target = (runner.state as ManualOrganizationRun.State.Selecting).candidates.single().target
        runner.confirmSelection(setOf(target))
        // Issue #417: the manual confirm freezes the scope; the composed phase
        // (and with it the JIT pause) starts from the "このまま整理" arm.
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)

        runner.planWithConfirmedScope()

        val awaiting = runner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        assertEquals(listOf(target), awaiting.selection)
        assertTrue(application.events.isEmpty())

        gate.markPresented(awaiting.runId)
        runner.continueAfterUsageAccessGate()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        val started = application.events.filter { it.phase == PhaseCode.RUN_STARTED }
        assertEquals(1, started.size)
        assertEquals(
            app.lawnchair.organizer.diagnostics.model.RunMode.SCOPE_COMPOSED_ORGANIZATION,
            started.single().runMode,
        )
    }

    @Test
    fun waiterPausesWithoutPresentingAndProceedsWhenTheOwnerResolves() {
        val gate = UsageAccessJitGate(isGranted = { false })
        val ownerApp = FakeApplication(readyInput())
        val owner = ManualOrganizationRun(
            application = ownerApp,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )
        owner.start()
        val ownerAwaiting = owner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        gate.markPresented(ownerAwaiting.runId)

        val waiterApp = FakeApplication(readyInput())
        val waiter = ManualOrganizationRun(
            application = waiterApp,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )
        waiter.start()

        val waiterAwaiting = waiter.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        assertFalse(waiterAwaiting.isOwner)
        assertTrue(waiterApp.events.isEmpty())

        // The owner's user resolves; the waiter's host observes and continues.
        gate.resolve(ownerAwaiting.runId)
        waiter.continueAfterUsageAccessGate()

        assertTrue(waiter.state is ManualOrganizationRun.State.Preview)
        assertEquals(1, waiterApp.events.count { it.phase == PhaseCode.RUN_STARTED })
    }

    @Test
    fun manualZeroCutPausesAtTheJitChokePointOnlyWhenTheComposedPhaseStarts() {
        // Issue #417: the manual empty cut stops at ScopeConfirmed BEFORE the
        // composed phase — the JIT pause is reached only when the method
        // choice ("このまま整理") starts it. The pause carries the NULL
        // selection: the empty confirmed scope keeps the plain full
        // organization (the null-selection legacy composition path), so the
        // resumed composition after the gate resolves must be identical to the
        // never-paused zero-cut flow (an empty LIST here would resume as the
        // scope-composed path with an empty scope instead).
        val application = FakeApplication(readyInput()).apply {
            detection = app.lawnchair.organizer.integration.CandidateDetectionResult.Ready(emptyList())
        }
        val runner = ManualOrganizationRun(
            application = application,
            usageAccessGate = UsageAccessJitGate(isGranted = { false }),
        )

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
        assertTrue(application.events.isEmpty())

        runner.planWithConfirmedScope()

        val awaiting = runner.state as ManualOrganizationRun.State.AwaitingUsageAccessJit
        assertTrue(awaiting.selection == null)
        assertTrue(application.events.isEmpty())

        // Test hygiene: release the process-wide RUN lease.
        runner.cancel()
    }

    @Test
    fun grantedFirstTriggerNeverPausesAndConsumesTheOpportunityForTheProcess() {
        var granted = true
        val gate = UsageAccessJitGate(isGranted = { granted })
        val application = FakeApplication(readyInput())
        val runner = ManualOrganizationRun(
            application = application,
            planner = OrganizationPlanner { planningResult(movingPlan()) },
            usageAccessGate = gate,
        )

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(UsageAccessJitGate.Phase.Resolved, gate.snapshot.value.phase)

        // Revoke mid-process: the moment has passed, no request appears.
        runner.cancel()
        granted = false
        runner.start()

        assertFalse(runner.state is ManualOrganizationRun.State.AwaitingUsageAccessJit)
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
    }

    // endregion

    private class CountingGate : OrganizationOperationGate {
        var closeCount = 0

        override fun tryAcquire(kind: OrganizationOperationLease.Kind): AutoCloseable = AutoCloseable { closeCount++ }
    }

    private fun validatedIntentWithScopeCandidates(
        candidates: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
    ): app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent {
        val itemRefs = candidates.associate { candidate ->
            CandidatePlanningIds.planningId(candidate).let { id -> "ref-${id.value}" to id }
        }
        return app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent(
            intent = app.lawnchair.organizer.personalization.PersonalizedIntentV1(
                exportId = "export-scope-1",
                itemIntents = emptyList(),
            ),
            export = app.lawnchair.organizer.personalization.PersonalizationContextExportV1(
                exportId = "export-scope-1",
                tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED,
                grid = app.lawnchair.organizer.personalization.ExportGridContext(4, 5, 1),
                items = emptyList(),
                preservedConstraints = app.lawnchair.organizer.personalization.PreservedConstraints(
                    reservedRegions = emptyList(),
                    preservedCounts = emptyMap(),
                ),
                categories = emptyList(),
                capabilities = app.lawnchair.organizer.personalization.ExportCapabilities(
                    intentSchemaVersion = app.lawnchair.organizer.personalization.ContextExportContract.INTENT_SCHEMA_VERSION,
                    functions = app.lawnchair.organizer.personalization.ContextExportContract.FIXED_CAPABILITIES,
                ),
                usageSignals = null,
            ),
            session = app.lawnchair.organizer.personalization.ExportSession(
                exportId = "export-scope-1",
                itemRefs = itemRefs,
                tier = app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED,
                sourceContextDigest = "digest-scope",
                signalProvenance = null,
                createdAtEpochMs = 0L,
                expiresAtEpochMs = 1L,
                scopeCandidates = candidates,
            ),
            identity = app.lawnchair.organizer.personalization.IntentIdentityCalculator.identity(
                app.lawnchair.organizer.personalization.IntentCompletion.complete(
                    app.lawnchair.organizer.personalization.PersonalizedIntentV1(
                        exportId = "export-scope-1",
                        itemIntents = emptyList(),
                    ),
                    emptySet(),
                ),
            ),
        )
    }

    private fun candidatePlacementPlan(placedIds: List<String>, unplacedIds: List<String>) = Planned(
        placements = placedIds.map { id ->
            PlannedPlacement(
                item = app.lawnchair.organizer.planning.ItemId(id),
                disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                target = PlacementTarget.WorkspaceTarget(
                    PageRef(PageId("page")),
                    app.lawnchair.organizer.planning.GridCell(0, 0),
                    app.lawnchair.organizer.planning.GridSpan(1, 1),
                ),
            )
        },
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
        unplaced = unplacedIds.map { id ->
            app.lawnchair.organizer.planning.UnplacedItem(
                app.lawnchair.organizer.planning.ItemId(id),
                app.lawnchair.organizer.planning.GridSpan(1, 1),
                app.lawnchair.organizer.planning.UnplacedReason.STRATEGY_SCOPE_FULL,
            )
        },
    )

    private fun selectionOf(vararg components: String) = components.map {
        app.lawnchair.organizer.planning.CandidateTarget.AppKey(
            app.lawnchair.organizer.planning.ComponentKey(it),
            app.lawnchair.organizer.planning.ProfileId("personal"),
        )
    }.toSet()

    @Test
    fun partiallyPlacedCandidatesPreviewWithUnplacedCountsInSummary() {
        // Review P1 follow-up: one candidate placed, one scope-unplaced.
        // The placed one previews as an Add; the unplaced one surfaces as a
        // STRATEGY_SCOPE_FULL count in the summary (spec overflow contract),
        // and the run does NOT collapse into NoChanges.
        val composition = scopeReadyInput().let { ready ->
            ready.copy(
                input = ready.input.copy(
                    targets = TargetSet(emptyList(), listOf(candidate("c1"), candidate("c2"))),
                ),
            )
        }
        val application = FakeApplication(composition).apply {
            detection = detected("com.example.c1", "com.example.c2")
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner {
                planningResult(candidatePlacementPlan(placedIds = listOf("c1"), unplacedIds = listOf("c2")))
            },
        )

        runner.start()
        runner.confirmSelection(selectionOf("com.example.c1", "com.example.c2"))
        runner.planWithConfirmedScope()

        val preview = runner.state as ManualOrganizationRun.State.Preview
        assertEquals(1, preview.summary.addedCount)
        assertEquals(
            1,
            preview.summary.unplacedByReason[app.lawnchair.organizer.planning.UnplacedReason.STRATEGY_SCOPE_FULL],
        )
    }

    @Test
    fun allCandidatesUnplacedWithNoOtherChangesEndsInTheImpossibleSurface() {
        // Review P1 follow-up: nothing fits and nothing else changes — the
        // run must not report "no changes"; it reports the scope overflow
        // through the existing Impossible surface.
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.c1")
        }
        val runner = ManualOrganizationRun(
            application,
            OrganizationPlanner {
                planningResult(candidatePlacementPlan(placedIds = emptyList(), unplacedIds = listOf("c1")))
            },
        )

        runner.start()
        runner.confirmSelection(selectionOf("com.example.c1"))
        runner.planWithConfirmedScope()

        val rejected = runner.state as ManualOrganizationRun.State.PlanningRejected
        assertEquals(ManualOrganizationRun.PlanningFailureKind.IMPOSSIBLE, rejected.kind)
        assertEquals(
            1,
            rejected.summary.unplacedByReason[app.lawnchair.organizer.planning.UnplacedReason.STRATEGY_SCOPE_FULL],
        )
    }

    @Test
    fun resolutionFailureOnTheFirstPreviewReachesTheTypedReDetectState() {
        // Review P2: the typed candidate-resolution failure must survive the
        // preview seam (not collapse into MATERIALIZATION_INVALID) so the
        // first preview routes to the re-detect outcome.
        val application = FakeApplication(scopeReadyInput()).apply {
            detection = detected("com.example.c1")
            inspectPlanOverride = { _, _ ->
                PlanPreviewResult.CandidateResolutionFailed(
                    app.lawnchair.organizer.application.public.CandidateResolutionFailure.COMPONENT_NOT_FOUND,
                )
            }
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirmSelection(selectionOf("com.example.c1"))
        runner.planWithConfirmedScope()

        val failed = runner.state as ManualOrganizationRun.State.CandidateResolutionFailed
        assertEquals(
            app.lawnchair.organizer.application.public.CandidateResolutionFailure.COMPONENT_NOT_FOUND,
            failed.failure,
        )
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun cancellingDuringSelectionLeavesTheJournalSilentForThatRunId() {
        // Review P2 (runMode correlation): RUN_STARTED anchors the journal;
        // a cancel during the pre-select window emits no events at all for
        // the runId — never a USER_CANCELLED without its RUN_STARTED.
        val application = FakeApplication(readyInput()).apply {
            detection = detected("com.example.a/.Main")
        }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Selecting)
        runner.cancel()

        assertEquals(ManualOrganizationRun.State.Cancelled, runner.state)
        assertEquals(emptyList<RunEvent>(), application.events)
    }

    // --- Issue #417 (spec 417): frozen-scope (ScopeConfirmed) state machine ---

    /**
     * Issue #417: drives a manual run to
     * [ManualOrganizationRun.State.ScopeConfirmed] by confirming the whole
     * detection cut. The planner only runs after the method choice, so the
     * default errors out if the machine composes early.
     */
    private fun runToConfirmedScope(
        application: FakeApplication,
        planner: OrganizationPlanner = OrganizationPlanner { error("planner must not run before the method choice") },
    ): ManualOrganizationRun {
        val runner = ManualOrganizationRun(application, planner)
        runner.start()
        val selecting = runner.state as? ManualOrganizationRun.State.Selecting
        checkNotNull(selecting) { "expected Selecting, got ${runner.state}" }
        runner.confirmSelection(selecting.candidates.map { it.target }.toSet())
        checkNotNull(runner.state as? ManualOrganizationRun.State.ScopeConfirmed) {
            "expected ScopeConfirmed, got ${runner.state}"
        }
        return runner
    }

    @Test
    fun manualConfirmWithoutIntentPublishesTheConfirmedScopeWithoutComposing() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run on confirm") })

        runner.start()
        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        runner.confirmSelection(selecting.candidates.map { it.target }.toSet())

        val confirmed = runner.state as ManualOrganizationRun.State.ScopeConfirmed
        assertEquals(RunId(RUN_ID), confirmed.runId)
        assertEquals(selecting.candidates, confirmed.candidates)
        assertEquals(selecting.candidates.map { it.target }, confirmed.selection)
        assertEquals(selecting.candidates.associate { it.target to it.label }, confirmed.candidateLabels)
        assertEquals(0, application.composeScopeComposedCalls)
        assertEquals(0, application.applyCalls)
        assertTrue("no journal event before the composed phase", application.events.isEmpty())
    }

    @Test
    fun manualConfirmWithABoundIntentComposesDirectlyWithoutTheConfirmedScope() {
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        val (observations, collector) = collectStateWithPhase(runner)

        runner.start()
        runner.attachIntent(validatedIntentFor(c1Target(), category = null))
        runner.confirmSelection(setOf(c1Target()))

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(1, application.composeScopeComposedCalls)
        assertTrue(
            "the intent-bound confirm must compose directly (rebind continuation)",
            observations.none { it.first is ManualOrganizationRun.State.ScopeConfirmed },
        )
        collector.cancel()
    }

    @Test
    fun onboardingConfirmComposesDirectlyAndNeverReachesTheConfirmedScope() {
        // D-16 regression: the onboarding fixed path never shows the
        // method-choice state, with or without an intent.
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })
        val (observations, collector) = collectStateWithPhase(runner)

        runner.start(Trigger.ONBOARDING_PROPOSAL)
        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        runner.confirmSelection(selecting.candidates.map { it.target }.toSet())

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertTrue(
            observations.none { it.first is ManualOrganizationRun.State.ScopeConfirmed },
        )
        collector.cancel()
    }

    @Test
    fun planWithConfirmedScopeRunsTheComposedPhaseWithTheFrozenSelection() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.planWithConfirmedScope()

        assertTrue(runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(1, application.composeScopeComposedCalls)
        assertEquals(listOf(appKey("com.example.c1")), application.composeSelection)
    }

    @Test
    fun planWithConfirmedScopeOutsideTheConfirmedScopeIsANoOp() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.planWithConfirmedScope()
        assertEquals(ManualOrganizationRun.State.Idle, runner.state)

        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.Selecting)
        runner.planWithConfirmedScope()
        assertTrue(runner.state is ManualOrganizationRun.State.Selecting)
        assertEquals(0, application.composeScopeComposedCalls)
        assertEquals(0, application.applyCalls)
        runner.cancel()
    }

    @Test
    fun attachIntentAtTheConfirmedScopeBindsAndComposesOnMatch() {
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { planningResult(movingPlan()) })

        val outcome = runner.attachIntent(validatedIntentFor(c1Target(), category = null))

        assertEquals(ManualOrganizationRun.AttachIntentOutcome.Attached, outcome)
        assertTrue("a matching attach IS the consent point: compose directly", runner.state is ManualOrganizationRun.State.Preview)
        assertEquals(1, application.composeScopeComposedCalls)
    }

    @Test
    fun attachIntentAtTheConfirmedScopeRefusesAMismatchTypedZeroWrite() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
        runner.start()
        // Explicit zero-selection scope; the export scope holds c1.
        runner.confirmSelection(emptySet())
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)

        val outcome = runner.attachIntent(validatedIntentFor(c1Target(), category = null))

        val rejected = outcome as ManualOrganizationRun.AttachIntentOutcome.Rejected
        assertEquals(
            app.lawnchair.organizer.personalization.IntentValidationFailure.ScopeMismatch(
                app.lawnchair.organizer.personalization.ScopeMismatchCause.SET_MISMATCH,
            ),
            rejected.failure,
        )
        val confirmed = runner.state as ManualOrganizationRun.State.ScopeConfirmed
        assertEquals(rejected.failure, confirmed.scopeRejection)
        assertEquals(0, application.composeScopeComposedCalls)
        assertEquals(0, application.applyCalls)
    }

    @Test
    fun attachIntentAtTheConfirmedScopeIsSingleShot() {
        val application = FakeApplication(scopeReadyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { planningResult(movingPlan()) })

        assertEquals(
            ManualOrganizationRun.AttachIntentOutcome.Attached,
            runner.attachIntent(validatedIntentFor(c1Target(), category = null)),
        )
        assertTrue(runner.state is ManualOrganizationRun.State.Preview)

        assertEquals(
            ManualOrganizationRun.AttachIntentOutcome.NotAttachable,
            runner.attachIntent(validatedIntentFor(c1Target(), category = null)),
        )
        assertEquals(1, application.composeScopeComposedCalls)
    }

    @Test
    fun reopenSelectionFromTheConfirmedScopeRepublishesSelectingWithTheSameCut() {
        val application = FakeApplication(readyInput())
            .apply { detection = detected("com.example.a/.Main", "com.example.b/.Main") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val cut = (runner.state as ManualOrganizationRun.State.ScopeConfirmed).candidates

        assertTrue(runner.reopenSelection())

        val selecting = runner.state as ManualOrganizationRun.State.Selecting
        assertEquals(cut, selecting.candidates)
        assertEquals(0, application.composeScopeComposedCalls)
        assertEquals(0, application.applyCalls)

        // The re-opened surface can confirm again into a fresh frozen scope.
        runner.confirmSelection(selecting.candidates.map { it.target }.toSet())
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
    }

    @Test
    fun reopenSelectionWithAnEmptyCutIsRefused() {
        val application = FakeApplication(readyInput()).apply { detection = detected() }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })
        runner.start()
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)

        assertFalse(runner.reopenSelection())
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
    }

    @Test
    fun reopenSelectionOutsideTheConfirmedScopeIsRefused() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        assertFalse(runner.reopenSelection())

        runner.start()
        assertFalse(runner.reopenSelection())
        assertTrue(runner.state is ManualOrganizationRun.State.Selecting)
        runner.cancel()
    }

    @Test
    fun claimingAGenerationEpochInvalidatesThePreviousClaimZeroWrite() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val scope = listOf(appKey("com.example.c1"))
        val first = runner.claimGenerationEpoch(scope)!!
        val replacement = runner.claimGenerationEpoch(scope)!!
        var committed = false
        val commit: ManualOrganizationRun.ExchangeGateTransaction.() -> ManualOrganizationRun.PersistOutcome = {
            committed = true
            ManualOrganizationRun.PersistOutcome.Committed
        }

        assertTrue(replacement.epoch > first.epoch)
        assertEquals(
            ManualOrganizationRun.GenerationCommitOutcome.Rejected(ManualOrganizationRun.GenerationCommitRejection.STALE_EPOCH),
            runner.commitGeneratedSession(epoch = first, exportId = "export-1", commit = commit),
        )
        assertFalse("a stale epoch commit is a zero-write rejection", committed)
        assertFalse(runner.isCurrentEpoch(first))

        assertEquals(
            ManualOrganizationRun.GenerationCommitOutcome.Committed,
            runner.commitGeneratedSession(epoch = replacement, exportId = "export-1", commit = commit),
        )
        assertTrue(committed)
    }

    @Test
    fun claimingAnEpochForADifferentScopeIsRefused() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })

        assertEquals(null, runner.claimGenerationEpoch(listOf(appKey("com.example.other"))))
    }

    @Test
    fun invalidatingTheGenerationEpochRejectsThePendingCommit() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!
        var invoked = false
        val commit: ManualOrganizationRun.ExchangeGateTransaction.() -> ManualOrganizationRun.PersistOutcome = {
            invoked = true
            ManualOrganizationRun.PersistOutcome.Committed
        }

        runner.invalidateGenerationEpoch()

        assertFalse(runner.isCurrentEpoch(epoch))
        assertEquals(
            ManualOrganizationRun.GenerationCommitOutcome.Rejected(ManualOrganizationRun.GenerationCommitRejection.STALE_EPOCH),
            runner.commitGeneratedSession(epoch = epoch, exportId = "export-1", commit = commit),
        )
        assertFalse("zero-write: the durable mutation must not run", invoked)
    }

    @Test
    fun commitGeneratedSessionBindsOnlyOnPersistSuccess() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val scope = listOf(appKey("com.example.c1"))
        val epoch = runner.claimGenerationEpoch(scope)!!

        // Store save failure: nothing binds — a cleanup for that export is a
        // Superseded no-op that does not even reach the store.
        assertEquals(
            ManualOrganizationRun.GenerationCommitOutcome.WriteFailed,
            runner.commitGeneratedSession(epoch = epoch, exportId = "export-e1") {
                ManualOrganizationRun.PersistOutcome.WriteFailed
            },
        )
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Superseded,
            runner.cleanupBoundExport("export-e1") { ManualOrganizationRun.StoreInvalidationOutcome.Committed },
        )

        // The still-current epoch retries: success binds the export.
        assertEquals(
            ManualOrganizationRun.GenerationCommitOutcome.Committed,
            runner.commitGeneratedSession(epoch = epoch, exportId = "export-e1") {
                ManualOrganizationRun.PersistOutcome.Committed
            },
        )
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Cleared,
            runner.cleanupBoundExport("export-e1") { ManualOrganizationRun.StoreInvalidationOutcome.Committed },
        )
    }

    @Test
    fun cleanupBoundExportClearsOnlyTheMatchingCurrentBinding() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        var storeCalls = 0
        runner.commitGeneratedSession(
            epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!,
            exportId = "export-1",
        ) {
            storeCalls++
            ManualOrganizationRun.PersistOutcome.Committed
        }

        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Cleared,
            runner.cleanupBoundExport("export-1") {
                storeCalls++
                ManualOrganizationRun.StoreInvalidationOutcome.Committed
            },
        )
        // The binding is gone: a second cleanup for the same export is a
        // Superseded no-op — the store is not touched.
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Superseded,
            runner.cleanupBoundExport("export-1") {
                storeCalls++
                ManualOrganizationRun.StoreInvalidationOutcome.Committed
            },
        )
        assertEquals(2, storeCalls)
    }

    @Test
    fun staleCleanupAfterAReplacementIsASupersededNoOpThatKeepsTheCurrentBinding() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val scope = listOf(appKey("com.example.c1"))
        runner.commitGeneratedSession(epoch = runner.claimGenerationEpoch(scope)!!, exportId = "export-e1") {
            ManualOrganizationRun.PersistOutcome.Committed
        }
        runner.commitGeneratedSession(epoch = runner.claimGenerationEpoch(scope)!!, exportId = "export-e2") {
            ManualOrganizationRun.PersistOutcome.Committed
        }

        // E1's delayed cleanup must not touch the store nor the E2 binding.
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Superseded,
            runner.cleanupBoundExport("export-e1") {
                error("the store must not be touched for a stale cleanup")
            },
        )
        // E2's own cleanup: a NoMatch against the current binding still clears.
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Cleared,
            runner.cleanupBoundExport("export-e2") { ManualOrganizationRun.StoreInvalidationOutcome.NoMatch },
        )
    }

    @Test
    fun cleanupBoundExportWriteFailedKeepsTheBindingAndRetries() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        runner.commitGeneratedSession(
            epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!,
            exportId = "export-1",
        ) { ManualOrganizationRun.PersistOutcome.Committed }

        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.WriteFailed,
            runner.cleanupBoundExport("export-1") { ManualOrganizationRun.StoreInvalidationOutcome.WriteFailed },
        )

        // The binding survived the failure: the retry with the same
        // expectedExportId reaches the terminal outcome.
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Cleared,
            runner.cleanupBoundExport("export-1") { ManualOrganizationRun.StoreInvalidationOutcome.Committed },
        )
    }

    @Test
    fun discardScopeBoundRequestCommittedClearsTheBindingAndInvalidatesTheEpochFirst() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!
        runner.commitGeneratedSession(epoch = epoch, exportId = "export-1") {
            ManualOrganizationRun.PersistOutcome.Committed
        }

        var epochCurrentAtStoreCall: Boolean? = null
        var invalidatedId: String? = null
        assertEquals(
            ManualOrganizationRun.ScopeDiscardOutcome.Discarded,
            runner.discardScopeBoundRequest { boundExportId ->
                invalidatedId = boundExportId
                epochCurrentAtStoreCall = runner.isCurrentEpoch(epoch)
                ManualOrganizationRun.StoreInvalidationOutcome.Committed
            },
        )
        assertEquals("export-1", invalidatedId)
        // Oracle (v): the epoch was invalidated BEFORE the capability's store call.
        assertEquals(false, epochCurrentAtStoreCall)
        assertFalse(runner.isCurrentEpoch(epoch))
        // The binding is cleared: a later cleanup for the same export is a no-op.
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Superseded,
            runner.cleanupBoundExport("export-1") { ManualOrganizationRun.StoreInvalidationOutcome.Committed },
        )
    }

    @Test
    fun discardScopeBoundRequestNoMatchOnTheCurrentBindingAlsoClears() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        runner.commitGeneratedSession(
            epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!,
            exportId = "export-1",
        ) { ManualOrganizationRun.PersistOutcome.Committed }

        assertEquals(
            ManualOrganizationRun.ScopeDiscardOutcome.Discarded,
            runner.discardScopeBoundRequest { boundExportId ->
                assertEquals("export-1", boundExportId)
                ManualOrganizationRun.StoreInvalidationOutcome.NoMatch
            },
        )
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Superseded,
            runner.cleanupBoundExport("export-1") { ManualOrganizationRun.StoreInvalidationOutcome.Committed },
        )
    }

    @Test
    fun discardScopeBoundRequestWriteFailedKeepsTheBindingAndTheConfirmedScope() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        runner.commitGeneratedSession(
            epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!,
            exportId = "export-1",
        ) { ManualOrganizationRun.PersistOutcome.Committed }

        assertEquals(
            ManualOrganizationRun.ScopeDiscardOutcome.WriteFailed,
            runner.discardScopeBoundRequest { ManualOrganizationRun.StoreInvalidationOutcome.WriteFailed },
        )

        // The frozen scope is kept (typed retryable failure)…
        assertTrue(runner.state is ManualOrganizationRun.State.ScopeConfirmed)
        // …and the binding survived the failure: a cleanup for the same
        // export still matches and clears.
        assertEquals(
            ManualOrganizationRun.ExportCleanupOutcome.Cleared,
            runner.cleanupBoundExport("export-1") { ManualOrganizationRun.StoreInvalidationOutcome.Committed },
        )

        // The retry: nothing is bound anymore — the discard completes without
        // touching the store again.
        assertEquals(
            ManualOrganizationRun.ScopeDiscardOutcome.Discarded,
            runner.discardScopeBoundRequest { error("nothing left to invalidate") },
        )
    }

    @Test
    fun discardWithoutABoundSessionStillInvalidatesTheClaimedEpoch() {
        val application = FakeApplication(readyInput()).apply { detection = detected("com.example.c1") }
        val runner = runToConfirmedScope(application, OrganizationPlanner { error("planner must not run") })
        val epoch = runner.claimGenerationEpoch(listOf(appKey("com.example.c1")))!!

        assertEquals(
            ManualOrganizationRun.ScopeDiscardOutcome.Discarded,
            runner.discardScopeBoundRequest { error("no store call without a binding") },
        )
        assertFalse(runner.isCurrentEpoch(epoch))
    }

    @Test
    fun discardOutsideTheConfirmedScopeIsATypedNoOp() {
        val runner = ManualOrganizationRun(
            FakeApplication(readyInput()),
            OrganizationPlanner { error("planner must not run") },
        )

        assertEquals(
            ManualOrganizationRun.ScopeDiscardOutcome.NotDiscardable,
            runner.discardScopeBoundRequest { error("the store must not be touched") },
        )
    }

    private fun placement(
        item: String,
        disposition: Disposition,
    ) = PlannedPlacement(
        item = app.lawnchair.organizer.planning.ItemId(item),
        disposition = disposition,
        target = PlacementTarget.WorkspaceTarget(
            PageRef(PageId("page")),
            app.lawnchair.organizer.planning.GridCell(0, 0),
            app.lawnchair.organizer.planning.GridSpan(1, 1),
        ),
    )

    private class FakeApplication(
        var composition: OrganizationInputComposition,
    ) : ManualOrganizationApplication {
        override val diagnostics = RecordingDiagnostics()
        val events: List<RunEvent>
            get() = diagnostics.events
        var nextRunIds = listOf(RUN_ID)
        private var nextRunIdIndex = 0
        var materializeCalls = 0
        var applyCalls = 0
        var appliedRunId: RunId? = null
        var composeStarted: CountDownLatch? = null
        var composeRelease: CountDownLatch? = null
        var materializeStarted: CountDownLatch? = null
        var materializeRelease: CountDownLatch? = null
        var applyStarted: CountDownLatch? = null
        var applyRelease: CountDownLatch? = null
        var materializeOverride: ((OrganizationInput, PlanningResult) -> OrganizationPlanMaterializer.Result)? = null
        var composeOverride: (() -> OrganizationInputComposition)? = null
        var inspectPlanOverride: ((OrganizationInput, PlanningResult) -> PlanPreviewResult)? = null
        var inspectPlanCalls = 0
        var lastAppliedPlan: ValidatedLayoutPlan? = null
        var applyResult: ApplyResult = ApplyResult.Applied(RunId(RUN_ID), RecoveryPointId(POINT_ID))
        var recoveryPreview: RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            RecoveryPointId(POINT_ID),
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )
        var durableStatus: app.lawnchair.organizer.application.public.OrganizerDurableStatus =
            app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

        // Issue #376: the D-15 restore-entry hint; null = fail-closed (no CTA).
        var restorableEntry: app.lawnchair.organizer.application.public.RestorableRecoveryEntry? = null
        var restorableEntryReads = 0

        // Issue #228: default keeps the legacy behavior — detection is
        // unavailable, so start() falls straight through to the plain full
        // compose (spec §7). Tests of the selection flow override this.
        var detection: app.lawnchair.organizer.integration.CandidateDetectionResult =
            app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
                app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
            )
        var detectStarted: CountDownLatch? = null
        var detectRelease: CountDownLatch? = null
        var composeSelection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>? = null
        var composeScopeComposedCalls = 0
        var composeScopeComposedOverride: ((List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>) -> OrganizationInputComposition)? = null

        override fun detectMissingAppCandidates(): app.lawnchair.organizer.integration.CandidateDetectionResult {
            detectStarted?.countDown()
            detectRelease?.await(5, TimeUnit.SECONDS)
            return detection
        }

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): OrganizationInputComposition {
            composeScopeComposedCalls++
            composeSelection = selection
            return composeScopeComposedOverride?.invoke(selection) ?: composition
        }

        override fun newRunId() = RunId(nextRunIds.getOrElse(nextRunIdIndex++) { RUN_ID })
        override fun composeFullOrganization(): OrganizationInputComposition {
            composeStarted?.countDown()
            composeRelease?.await(5, TimeUnit.SECONDS)
            return composeOverride?.invoke() ?: composition
        }

        override fun inspectPlan(input: OrganizationInput, result: PlanningResult): PlanPreviewResult {
            inspectPlanCalls++
            return inspectPlanOverride?.invoke(input, result)
                ?: PlanPreviewResult.Previewed(
                    app.lawnchair.organizer.application.public.PlanPreview(
                        plan = minimalPlan(input),
                        details = app.lawnchair.organizer.application.public.PlanPreviewDetails(
                            changes = emptyList(),
                            counts = app.lawnchair.organizer.application.public.PreviewCounts(0, 0, 0, 0, emptyMap()),
                        ),
                    ),
                )
        }

        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result {
            materializeCalls++
            materializeStarted?.countDown()
            materializeRelease?.await(5, TimeUnit.SECONDS)
            return materializeOverride?.invoke(input, result)
                ?: OrganizationPlanMaterializer.Result.Ready(minimalPlan(input))
        }

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls++
            appliedRunId = runId
            lastAppliedPlan = plan
            applyStarted?.countDown()
            applyRelease?.await(5, TimeUnit.SECONDS)
            return applyResult
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = recoveryPreview

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult = RecoveryResult.NotRestorable(pointId, app.lawnchair.organizer.application.public.RecoveryRejection.MISSING)

        override fun readDurableOrganizerStatus(): app.lawnchair.organizer.application.public.OrganizerDurableStatus = durableStatus

        override fun readRestorableRecoveryEntry(): app.lawnchair.organizer.application.public.RestorableRecoveryEntry? {
            restorableEntryReads++
            return restorableEntry
        }

        val readiness = kotlinx.coroutines.flow.MutableStateFlow(
            app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY,
        )
        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> = readiness
    }

    private fun assertThrowsIllegalState(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private class RecordingDiagnostics : DiagnosticsPort {
        val events = mutableListOf<RunEvent>()
        var emitOverride: ((RunEvent) -> Unit)? = null
        override fun emit(event: RunEvent) {
            emitOverride?.invoke(event)
            events += event
        }
        override fun snapshot(): List<RunEvent> = events.toList()
    }

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SECOND_RUN_ID = "cccccccccccccccccccccccccccccccc"
        const val OTHER_POINT_ID = "dddddddddddddddddddddddddddddddd"
        const val SHA_256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

private fun minimalPlan(input: OrganizationInput): ValidatedLayoutPlan = ValidatedLayoutPlan(
    sourceRevision = input.snapshot.revision,
    sourceState = LayoutState(emptyList(), emptyList(), DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT), emptyList()),
    intendedState = LayoutState(emptyList(), emptyList(), DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT), emptyList()),
    actions = emptyList(),
    newPages = emptyList(),
    newFolders = emptyList(),
    ruleVersion = input.rules.version,
    taxonomyVersion = input.taxonomy.version,
)
