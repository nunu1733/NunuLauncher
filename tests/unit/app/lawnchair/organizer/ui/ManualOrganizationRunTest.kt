package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryPreviewSummary
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.Trigger
import app.lawnchair.organizer.integration.CompositionDiagnostic
import app.lawnchair.organizer.integration.InputProvenance
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposition
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
                    CompositionDiagnostic("capture-unavailable"),
                ),
            ),
            planner = OrganizationPlanner { error("planner must not run") },
            operationGate = OrganizationOperationLease,
        ).start()
        assertTrue(admitted is ManualOrganizationRun.StartOutcome.Started)
    }

    @Test
    fun exceptionDuringConfirmationReleasesTheOrganizationOperationLease() {
        val application = FakeApplication(readyInput()).apply {
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
                CompositionDiagnostic("capture-unavailable"),
            ),
        )
        val runner = ManualOrganizationRun(application, OrganizationPlanner { error("planner must not run") })

        runner.start()

        assertTrue(runner.state is ManualOrganizationRun.State.InputUnavailable)
        assertEquals(0, application.materializeCalls)
        assertEquals(0, application.applyCalls)
        assertEquals(1, application.events.size)
        assertEquals(app.lawnchair.organizer.diagnostics.model.PhaseCode.RUN_STARTED, application.events.single().phase)
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
        assertEquals(1, application.materializeCalls)
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
        application.materializeOverride = { _, _ -> OrganizationPlanMaterializer.Result.Invalid }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start(Trigger.ONBOARDING_PROPOSAL)
        runner.confirm()

        assertEquals(ManualOrganizationRun.State.Stale, runner.state)
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
    fun staleMaterializationIsVisibleAndNeverReachesApplicationWriter() {
        val application = FakeApplication(readyInput())
        application.materializeOverride = { _, _ -> OrganizationPlanMaterializer.Result.Invalid }
        val runner = ManualOrganizationRun(application, OrganizationPlanner { planningResult(movingPlan()) })

        runner.start()
        runner.confirm()

        assertEquals(ManualOrganizationRun.State.Stale, runner.state)
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

    private fun readyInput() = OrganizationInputComposition.Ready(
        input = input(),
        provenance = InputProvenance(
            revision = RevisionId("revision"),
            rules = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            taxonomy = policyIdentity(PolicySourceKind.ORGANIZER_POLICY_BUNDLE),
            signals = policyIdentity(PolicySourceKind.MATERIALIZED_CLASSIFICATION_SIGNALS),
            targets = policyIdentity(PolicySourceKind.MATERIALIZED_FULL_TARGET_SET),
            policyBundle = PolicyBundleIdentity("v1", SHA_256),
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
            RuleVersion("v1"),
            FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
            DockPolicy.PRESERVE,
            OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
            FallbackCategoryPolicy.KEEP_AS_SINGLETON,
            app.lawnchair.organizer.planning.OrderingPolicy.CANONICAL_V1,
        ),
        taxonomy = TaxonomyContract(TaxonomyVersion("v1"), listOf(app.lawnchair.organizer.planning.CategoryId("other")), app.lawnchair.organizer.planning.CategoryId("other")),
        signals = ClassificationSignals(emptyList()),
        targets = TargetSet(emptyList(), emptyList()),
        runMode = RunMode.FullOrganization,
    )

    private fun planningResult(outcome: app.lawnchair.organizer.planning.PlanningOutcome) = PlanningResult(
        revision = RevisionId("revision"),
        ruleVersion = RuleVersion("v1"),
        taxonomyVersion = TaxonomyVersion("v1"),
        outcome = outcome,
    )

    private fun movingPlan() = Planned(
        placements = listOf(placement("item", Disposition.Moved(PlacementCode.SINGLE_PLACEMENT))),
        newPages = emptyList(),
        newFolders = emptyList(),
        categories = emptyList(),
        warnings = emptyList(),
    )

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
        var applyResult: ApplyResult = ApplyResult.Applied(RunId(RUN_ID), RecoveryPointId(POINT_ID))
        var recoveryPreview: RecoveryPreviewResult = RecoveryPreviewResult.NotRestorable(
            RecoveryPointId(POINT_ID),
            app.lawnchair.organizer.application.public.RecoveryPreviewRejection.MISSING,
        )

        override fun newRunId() = RunId(nextRunIds.getOrElse(nextRunIdIndex++) { RUN_ID })
        override fun composeFullOrganization(): OrganizationInputComposition {
            composeStarted?.countDown()
            composeRelease?.await(5, TimeUnit.SECONDS)
            return composeOverride?.invoke() ?: composition
        }
        override fun materialize(input: OrganizationInput, result: PlanningResult): OrganizationPlanMaterializer.Result {
            materializeCalls++
            materializeStarted?.countDown()
            materializeRelease?.await(5, TimeUnit.SECONDS)
            return materializeOverride?.invoke(input, result) ?: OrganizationPlanMaterializer.Result.Ready(
                ValidatedLayoutPlan(
                    sourceRevision = input.snapshot.revision,
                    sourceState = LayoutState(emptyList(), emptyList(), DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT), emptyList()),
                    intendedState = LayoutState(emptyList(), emptyList(), DeviceCapabilities(4, 5, 5, 3, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT), emptyList()),
                    actions = emptyList(),
                    newPages = emptyList(),
                    newFolders = emptyList(),
                    ruleVersion = input.rules.version,
                    taxonomyVersion = input.taxonomy.version,
                ),
            )
        }

        override fun apply(plan: ValidatedLayoutPlan, runId: RunId): ApplyResult {
            applyCalls++
            appliedRunId = runId
            applyStarted?.countDown()
            applyRelease?.await(5, TimeUnit.SECONDS)
            return applyResult
        }

        override fun inspectRecovery(pointId: RecoveryPointId): RecoveryPreviewResult = recoveryPreview

        override fun confirmRecovery(pointId: RecoveryPointId, confirmation: RecoveryPreviewConfirmation): RecoveryResult = RecoveryResult.NotRestorable(pointId, app.lawnchair.organizer.application.public.RecoveryRejection.MISSING)
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
        override fun emit(event: RunEvent) {
            events += event
        }
        override fun snapshot(): List<RunEvent> = events.toList()
    }

    private companion object {
        const val RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val POINT_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SECOND_RUN_ID = "cccccccccccccccccccccccccccccccc"
        const val SHA_256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
