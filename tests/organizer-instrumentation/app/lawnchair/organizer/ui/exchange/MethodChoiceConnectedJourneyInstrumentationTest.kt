package app.lawnchair.organizer.ui.exchange

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.AndroidExportSessionStore
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectedCandidate
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationPreparation
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.integration.exchange.ExchangeInputResult
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.integration.exchange.SessionPersistOutcome
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportCodec
import app.lawnchair.organizer.personalization.ContextExportResult
import app.lawnchair.organizer.personalization.DiscardIfResult
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.ExportEncodeProblem
import app.lawnchair.organizer.personalization.ExportEntryOrigin
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportInvalidationResult
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.ui.ManualOrganizationApplication
import app.lawnchair.organizer.ui.ManualOrganizationRun
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #417 (spec 417 AC-8): the connected journeys of the method-choice
 * exchange flow, driven against the REAL [ManualOrganizationRun] state
 * machine, the REAL [ExchangeFlowStateHolder]/[ExchangeFlowController] seams
 * and the REAL process-wide
 * [app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate] —
 * at the transport level (the harness convention of the exchange
 * instrumentation tests: transports are faked, the holder is driven
 * directly).
 *
 * AC-8 1:1 mapping — every letter (a)〜(v) names its connected test method
 * (unit oracles, where they exist, are ADDITIONAL early-warning evidence and
 * never a replacement for the connected one; plan revision 11: AC-8 is fixed
 * entirely in the connected lane):
 *
 * - (a)  connected: `ManualOrganizationPreferencesInstrumentationTest.emptyHomeSelectAllMethodFaceAiArmImportAttachReachesThePreview`
 * - (b)  connected: `ManualOrganizationPreferencesInstrumentationTest.emptyHomeSelectAllMethodFaceThenThePlainArmReachesThePreview`
 * - (c)  connected: `ManualOrganizationPreferencesInstrumentationTest.backWithAnActiveRequestDiscardsItAndReopensTheSelection` (`Committed`)
 *        + `ManualOrganizationPreferencesInstrumentationTest.backDiscardWriteFailedKeepsTheFaceAndShowsTheTypedFailure` (`WriteFailed`)
 * - (d)  connected: `ManualOrganizationPreferencesInstrumentationTest.backFromTheMethodFaceWithoutARequestReopensTheEditableSelection`
 *        (scope ownership survives Back) + [journeyDLegacyIdleRequestAdmitsTheRunAndBlocksTheMethodFaceImport]
 *        (the legacy-IDLE half: a durable legacy IDLE request never blocks the manual run admission, and the
 *        method-choice face's import of it is blocked with the 作り直し guidance, nothing saved)
 * - (e)  connected: `MissingAppSelectionInstrumentationTest.zeroCandidatesContinuesWithoutShowingTheSelectionSurface` (Back = 中断)
 * - (f)  connected: `ManualOrganizationPreferencesInstrumentationTest.onboardingRunNeverShowsTheMethodFace`
 * - (g)  connected: [journeyGSameRunGenerateImportAttachSucceeds]
 * - (h)  connected: [journeyHOwnerlessRunInImportSavesPendingThenRebindCompletes]
 * - (i)  connected: [journeyISameScopeForeignOriginIsBlockedToRecreationGuidance]
 * - (j)  connected: [journeyJInterruptedRunRejectsTheLateCompletionZeroWrite]
 * - (k)  connected: [journeyKReplacementMovesTheAuthorityE1RefusedE2Attachable]
 * - (l)  connected: [journeyLCrossingRebindAdmissionAndGenerationCommitDoesNotDeadlock]
 *        (rebind admission holding the run lock × a GENERATION COMMIT of the SAME run — ONE
 *        [ManualOrganizationRun], so with the correct order the commit blocks on the SAME run lock BEFORE
 *        reaching the gate; latch-sequenced; the live-owner pending-save crossing is pinned on the same
 *        single run lock by [rebindAdmissionAndLiveOwnerPendingSaveAlsoCrossesWithoutDeadlock]) (shared with (s))
 * - (m)  connected: [journeyMScopeDiscardDuringClaimedReplacementDropsE2AndReopens]
 * - (n)  connected: [journeyNOutOfOrderCompletionsCommitOnlyTheCurrentEpoch]; unit twin (additional):
 *        `ManualOrganizationRunTest.claimingAGenerationEpochInvalidatesThePreviousClaimZeroWrite`
 *        + `ManualOrganizationRunTest.invalidatingTheGenerationEpochRejectsThePendingCommit`
 * - (o)  connected: [journeyODeathBetweenDurableSaveAndBindingUpdateReconnectsOnlyThroughRebind] — the DURABLE
 *        fault point (the session save landed in the REAL `AndroidExportSessionStore` file inside the gate
 *        hold / the commit never returned to its caller / the run-provided bind callback never ran) is
 *        exercised in-process by a gate transaction capability that throws after the durable mutation; a fresh
 *        store/controller/run/holder is rebuilt from the SAME durable file. Process death itself remains
 *        device/crash-test territory. Unit twin (additional):
 *        `ExchangeFlowStateHolderTest.ownerlessRunInImportSavesThePendingAndProjectsToImportReviewWithoutAttach`
 * - (p)  connected: [journeyPActiveSessionAndBindingNeverDivergeAcrossFailurePaths] (store-save failure / encode
 *        failure / pre-send discard); unit twins (additional):
 *        `ExchangeFlowStateHolderTest.encodeFailureCleansTheBoundExportThroughTheRunOwnedSeam`
 *        + `ExchangeFlowStateHolderTest.runInImportTheLiveOwnerNeverBoundFencesTheCommittedRecordAway`
 * - (q)  connected: [journeyQLegacyIdleFixtureRestoresUncheckedWithoutAttachAuthority] (legacy IDLE),
 *        [journeyQLegacyRunInFixtureOffersTheSelectionRestoreInitialValues] (legacy RUN_IN + non-empty scope),
 *        [journeyQLegacyRunInEmptyScopeFixtureDecodesIdleFailSafe] (legacy RUN_IN + empty scope) — the three
 *        upgrade fixtures seeded as pre-#417 records (no `entryOrigin` key) through the REAL
 *        `AndroidExportSessionStore`; unit twin (additional):
 *        `ExchangeFlowStateHolderTest.entryKindDerivesFromTheSessionOriginAndStaysStableAcrossReImports`
 * - (r)  connected: [journeyRReplacementCommitKeepsE2BoundAndInvalidatesOnlyTheOldPending]; unit twin (additional):
 *        `ManualOrganizationRunTest.commitGeneratedSessionBindsOnlyOnPersistSuccess`
 * - (s)  connected: [journeySBindAndClearCallbacksRunInsideTheGateHold] (bind on the commit path, clear on the
 *        cleanup and discard paths, each observed under the REAL process-wide gate hold) — shared with (l)'s
 *        crossing test
 * - (t)  connected: [journeyTStaleCleanupAfterAReplacementKeepsE2ThroughTheHolderCleanupPath]; unit twin
 *        (additional): `ManualOrganizationRunTest.staleCleanupAfterAReplacementIsASupersededNoOpThatKeepsTheCurrentBinding`
 * - (u)  connected: [journeyUCleanupWriteFailedKeepsRetryableStateThenReachesTerminal]; unit twin (additional):
 *        `ExchangeFlowStateHolderTest.exportCleanupWriteFailedKeepsTheSessionAndBindingAndRetries`
 * - (v)  connected: [journeyVDiscardInvalidatesTheEpochBeforeTheCapabilityCall] (the recording gate capability
 *        observes the epoch already invalidated at gate entry, before the `invalidateIf` call); unit twin
 *        (additional): `ManualOrganizationRunTest.discardScopeBoundRequestCommittedClearsTheBindingAndInvalidatesTheEpochFirst`
 */
@RunWith(AndroidJUnit4::class)
class MethodChoiceConnectedJourneyInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private companion object {
        /** The stable detection candidate shared by the run and export fixtures. */
        val scopedCandidate = CandidateTarget.AppKey(
            ComponentKey("com.example.c1/.Main"),
            ProfileId("0"),
        )
    }

    // ------------------------------------------------------------------
    // Fixtures (the exchange instrumentation harness shape)
    // ------------------------------------------------------------------

    private class FakeStore : ExportSessionStore {
        var session: ExportSession? = null

        @Volatile
        var failInvalidateIf = false

        @Volatile
        var failSave = false

        @Volatile
        var invalidateIfCalls = 0

        /** The exportIds of SUCCESSFUL saves, in order — the zero-write proof of stale completions. */
        val saveOrder = Collections.synchronizedList(mutableListOf<String>())

        /** One entry per `invalidateIf` call: (expectedExportId, session held at call time, outcome simpleName). */
        val invalidateIfLog = Collections.synchronizedList(mutableListOf<Triple<String, String?, String>>())

        override fun save(session: ExportSession): Boolean {
            if (failSave) return false
            this.session = session
            saveOrder += session.exportId
            return true
        }

        override fun load(exportId: String): ExportSession? = session?.takeIf { it.exportId == exportId }

        override fun active(nowEpochMs: Long): ExportSession? = session?.takeIf { !it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
        }

        override fun invalidateIf(expectedExportId: String): ExportInvalidationResult {
            invalidateIfCalls++
            val heldAtCall = session?.exportId
            val result = when {
                failInvalidateIf -> ExportInvalidationResult.WriteFailed
                session?.exportId != expectedExportId -> ExportInvalidationResult.NoMatch
                else -> {
                    session = null
                    ExportInvalidationResult.Committed
                }
            }
            invalidateIfLog += Triple(expectedExportId, heldAtCall, result::class.java.simpleName)
            return result
        }
    }

    private class FakePendingStore : PendingImportedIntentStore {
        var record: DurablePendingIntent? = null
        var deleteCalls = 0

        override fun save(proposal: DurablePendingIntent): Boolean {
            record = proposal
            return true
        }

        override fun load(): DurablePendingIntent? = record

        override fun discard(): Boolean {
            if (record == null) return true
            record = null
            return true
        }

        override fun delete() {
            deleteCalls++
            record = null
        }

        override fun discardIf(expected: DurablePendingIntent): DiscardIfResult {
            if (record != expected) return DiscardIfResult.NoMatch
            record = expected.copy(discarded = true)
            return DiscardIfResult.Committed
        }

        override fun deleteIf(proposal: DurablePendingIntent): Boolean {
            if (record == proposal) {
                record = null
                return true
            }
            return false
        }
    }

    /**
     * The run double: detection is READY (the stable candidate), the scoped
     * composition stays NotReady (the planner must never run — a run reaching
     * the composed phase surfaces as the typed InputUnavailable state), so
     * every run transition in these journeys is observable without layout
     * writes.
     */
    private class JourneyApplication : ManualOrganizationApplication {
        override val diagnostics = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot(): List<RunEvent> = emptyList()
        }

        override fun newRunId() = RunId("0123456789abcdef0123456789abcdef")

        override fun detectMissingAppCandidates(): CandidateDetectionResult = CandidateDetectionResult.Ready(
            listOf(
                DetectedCandidate(
                    target = scopedCandidate,
                    label = "c1",
                    availability = Availability.AVAILABLE,
                ),
            ),
        )

        override fun composeScopeComposedOrganization(
            selection: List<CandidateTarget.AppKey>,
        ): OrganizationInputComposition = notReady()

        override fun composeFullOrganization(): OrganizationInputComposition = notReady()

        override fun inspectPlan(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = app.lawnchair.organizer.application.public.PlanPreviewResult.WriterBusy

        override fun materialize(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = error("not reached in the method-choice journeys")

        override fun apply(
            plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
            runId: RunId,
        ) = error("not reached in the method-choice journeys")

        override fun inspectRecovery(pointId: app.lawnchair.organizer.application.public.RecoveryPointId) = error("not reached")

        override fun confirmRecovery(
            pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
            confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
        ) = error("not reached")

        override fun readDurableOrganizerStatus() = app.lawnchair.organizer.application.public.OrganizerDurableStatus.NEVER_ORGANIZED

        override fun readRestorableRecoveryEntry(): app.lawnchair.organizer.application.public.RestorableRecoveryEntry? = null

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)

        private fun notReady(): OrganizationInputComposition = OrganizationInputComposition.NotReady(
            app.lawnchair.organizer.integration.InputReadinessReason.InvalidCanonicalCapture(
                app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
            ),
            app.lawnchair.organizer.integration.CompositionDiagnostic(
                app.lawnchair.organizer.integration.InputCompositionCode.CAPTURE_INVALID,
            ),
        )
    }

    private class HolderFixture(
        val holder: ExchangeFlowStateHolder,
        val controller: ExchangeFlowController,
        val store: FakeStore,
        val pendingStore: FakePendingStore,
        val run: ManualOrganizationRun,
        val gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate,
    )

    /** The structural truth: the snapshot plus (scoped) the CANDIDATE projection. */
    private fun structural(withCandidate: Boolean): CanonicalStructuralInputs {
        fun app(id: String, x: Int = 0) = CapturedItem(
            id = ItemId(id),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val items = listOf(app("a"), app("b", x = 1))
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            app.lawnchair.organizer.planning.DeviceCapabilities(4, 6, 5, 3, 5, app.lawnchair.organizer.planning.Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = if (withCandidate) {
            TargetSet(
                items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                listOf(
                    CandidateItem(
                        id = ItemId("c1"),
                        profile = ProfileId("0"),
                        kind = CandidateKind.APPLICATION,
                        target = scopedCandidate,
                        availability = Availability.AVAILABLE,
                        span = GridSpan(1, 1),
                    ),
                ),
            )
        } else {
            TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        }
        return CanonicalStructuralInputs(snapshot, targets, emptyMap())
    }

    private fun exportInputs(now: Long, withCandidate: Boolean): ExportInputs {
        val s = structural(withCandidate)
        return ExportInputs(snapshot = s.snapshot, targets = s.targets, nowEpochMs = now)
    }

    /**
     * The journey fixture: run + controller + holder sharing ONE process-wide
     * exchange mutation gate (the production wiring shape), the scoped
     * prepare ready so the atomic commit and the encode both succeed, and an
     * optional latch pair that parks the LOCK-FREE prepare half of a scoped
     * generation (entered ⇒ the generation epoch was already claimed). Only
     * the prepare invocation with the 0-based index [prepareParkIndex] parks
     * (0 = the first generation — the default); later ones run straight
     * through so a second generation can complete while the first is parked,
     * or the first can complete while the replacement is parked.
     */
    private fun newFixture(
        driveToConfirmedScope: Boolean,
        prepareGate: Pair<CountDownLatch, CountDownLatch>? = null,
        prepareParkIndex: Int = 0,
        gateTransaction: ManualOrganizationRun.ExchangeGateTransaction? = null,
        encodeFailureSwitch: AtomicBoolean? = null,
        gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate(),
    ): HolderFixture {
        val run = ManualOrganizationRun(
            JourneyApplication(),
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run in the method-choice journeys") },
            exchangeGateTransaction = gateTransaction ?: ManualOrganizationRun.gateHeldExchangeGateTransaction(gate),
        )
        val store = FakeStore()
        val pendingStore = FakePendingStore()
        val parkCounter = AtomicInteger()
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = false)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural(withCandidate = true)) },
            composeScopedExportInputs = { _, _, _ ->
                if (prepareGate != null && parkCounter.getAndIncrement() == prepareParkIndex) {
                    prepareGate.first.countDown()
                    prepareGate.second.await(15, TimeUnit.SECONDS)
                }
                ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = true))
            },
            encodeExport = { export ->
                if (encodeFailureSwitch?.getAndSet(false) == true) {
                    ContextExportResult.Failure(ExportEncodeProblem.Oversize)
                } else {
                    ContextExportCodec.encode(export)
                }
            },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { 1_000_000L },
            pendingImportStore = pendingStore,
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = run,
            scope = CoroutineScope(Dispatchers.Main),
            pendingImportStore = pendingStore,
            exchangeMutationGate = gate,
        )
        if (driveToConfirmedScope) {
            run.start()
            run.confirmSelection(setOf(scopedCandidate))
        }
        return HolderFixture(holder, controller, store, pendingStore, run, gate)
    }

    /** Builds the marked reply of [session] by replaying the SCOPED export. */
    private fun scopedReplyFor(session: ExportSession): String = markedReplyFor(session, withCandidate = true)

    /**
     * Builds the marked reply of [session] by replaying the export built over
     * the SAME deterministic inputs ([withCandidate] selects the scoped vs
     * plain shape, matching the shape the session was originally built from).
     */
    private fun markedReplyFor(session: ExportSession, withCandidate: Boolean): String {
        val built = ContextExportBuilder.build(
            exportInputs(session.createdAtEpochMs, withCandidate = withCandidate),
            session.tier,
            ReplayAllocator(session),
        )
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = built.export.items.map { item ->
                ItemIntent(
                    ref = item.ref,
                    preserve = if (item.mobility == app.lawnchair.organizer.personalization.Mobility.CANDIDATE) null else true,
                )
            },
        )
        return buildString {
            append(app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(app.lawnchair.organizer.personalization.exchange.ExchangeContract.INTENT_END_MARKER)
        }
    }

    private class ReplayAllocator(session: ExportSession) : RandomIdAllocator {
        private val ids = ArrayDeque(session.itemRefs.keys.toList() + listOf(session.exportId))

        override fun newId(): String = ids.removeFirst()
    }

    /** Polls the holder's screen until the predicate holds (settles hop IO → Main). */
    private fun awaitScreenIs(holder: ExchangeFlowStateHolder, timeoutMs: Long = 10_000, predicate: (ExchangeScreen) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(holder.screen) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(
            "the holder screen must reach the expected state (was ${holder.screen::class.java.simpleName}, status=${holder.status?.kind})",
            predicate(holder.screen),
        )
    }

    private fun awaitCondition(description: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(description, predicate())
    }

    private fun awaitRunState(run: ManualOrganizationRun, timeoutMs: Long = 10_000, predicate: (ManualOrganizationRun.State) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(run.state) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("the run state must reach the expected state (was ${run.state})", predicate(run.state))
    }

    private fun awaitImportSuccess(holder: ExchangeFlowStateHolder): ExchangeScreen.ImportSuccess {
        awaitScreenIs(holder) { it is ExchangeScreen.ImportSuccess }
        return holder.screen as ExchangeScreen.ImportSuccess
    }

    private fun awaitImportReview(holder: ExchangeFlowStateHolder): ExchangeScreen.ImportReview {
        awaitScreenIs(holder) { it is ExchangeScreen.ImportReview }
        return holder.screen as ExchangeScreen.ImportReview
    }

    private fun generateScopedOnUi(fixture: HolderFixture) {
        composeRule.runOnUiThread {
            fixture.holder.generateScoped(PrivacyTier.EXTERNAL_REDACTED, listOf(scopedCandidate), mapOf(scopedCandidate to "c1"))
        }
    }

    // ------------------------------------------------------------------
    // Gate transaction capabilities for the connected oracles
    // ------------------------------------------------------------------

    /**
     * Oracle (v): records the sequence [gate entry → durable mutation →
     * run-provided callback] of every run-owned transaction through the REAL
     * process-wide gate, evaluating [epochProbe] at GATE ENTRY — the probe
     * reads `isCurrentEpoch` re-entrantly under the run lock the caller
     * holds, so the record shows whether the epoch was still current when the
     * capability (the gate) was entered.
     */
    private class RecordingTransaction(
        private val gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate,
    ) : ManualOrganizationRun.ExchangeGateTransaction {
        val events = Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var epochProbe: (() -> Boolean)? = null

        override fun <R> withinGate(
            durableMutation: () -> R,
            onGateHeld: (R) -> Unit,
        ): R = gate.withGate {
            events += "gate-enter(epochCurrent=${epochProbe?.invoke() ?: false})"
            val result = durableMutation()
            events += "durable-mutation"
            onGateHeld(result)
            events += "run-callback(gateHeld=${gate.heldCount > 0})"
            result
        }
    }

    /**
     * Oracle (s): observes the REAL process-wide gate's hold state at the
     * durable mutation AND around the run-provided bind/clear callback — the
     * callback is bracketed by heldCount readings taken inside the gate's own
     * critical section, so a `held=true` record proves the callback executed
     * while the gate was held.
     */
    private class GateObservationTransaction(
        private val gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate,
    ) : ManualOrganizationRun.ExchangeGateTransaction {
        class Observation(
            val phase: String,
            val gateHeldAtMutation: Boolean,
            val gateHeldAroundRunCallback: Boolean,
            val gateDepthAtCallback: Int,
        )

        val observations = Collections.synchronizedList(mutableListOf<Observation>())

        @Volatile
        var phase: String = ""

        override fun <R> withinGate(
            durableMutation: () -> R,
            onGateHeld: (R) -> Unit,
        ): R = gate.withGate {
            val result = durableMutation()
            val heldAtMutation = gate.heldCount > 0
            val depthAtCallbackEntry = gate.heldCount
            onGateHeld(result)
            val depthAtCallbackExit = gate.heldCount
            observations += Observation(
                phase = phase,
                gateHeldAtMutation = heldAtMutation,
                gateHeldAroundRunCallback = depthAtCallbackEntry > 0 && depthAtCallbackExit > 0,
                gateDepthAtCallback = minOf(depthAtCallbackEntry, depthAtCallbackExit),
            )
            result
        }
    }

    /**
     * Oracle (o): the deterministic DURABLE death injection at the atomic
     * commit's fault point, over the REAL store file — the gate-held durable
     * mutation (the session save) RUNS and lands in the durable file, and the
     * fault then THROWS: the commit never returns to its caller and the
     * run-provided BIND callback (the binding update) never runs. This is
     * exactly the world a process death between the durable save and the
     * binding update leaves behind (process death itself remains
     * device/crash-test territory).
     */
    private class DurableDeathTransaction(
        private val gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate,
    ) : ManualOrganizationRun.ExchangeGateTransaction {
        /** The injected death: the commit's caller never observes a return. */
        class ProcessDeath : RuntimeException("the process died between the durable save and the binding update")

        override fun <R> withinGate(
            durableMutation: () -> R,
            onGateHeld: (R) -> Unit,
        ): R = gate.withGate {
            // The durable mutation (the session save) lands in the REAL store
            // file inside the gate hold…
            durableMutation()
            // …and the process "dies": no run-provided callback, no return to
            // the caller — the save-landed/binding-never-updated fault point.
            throw ProcessDeath()
        }
    }

    /**
     * Oracle (l): the pre-gate boundary observer of the run-owned transaction.
     * [ManualOrganizationRun] invokes [withinGate] only from inside its run
     * lock critical section, so [entrySignal] firing at the boundary is
     * latch-level evidence that the commit thread has ACQUIRED the run lock
     * and is about to acquire the gate — observed via a latch, never via
     * Thread.State. When [parkOnEntry] is set, the thread additionally parks
     * AT the boundary — holding the run lock, before the gate acquisition —
     * until released, or until it is interrupted (the interrupt unwinds the
     * commit before any gate involvement, freeing the run lock with zero
     * durable writes).
     */
    private class PreGateBoundaryTransaction(
        private val gate: app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate,
    ) : ManualOrganizationRun.ExchangeGateTransaction {
        @Volatile
        var entrySignal: CountDownLatch? = null

        @Volatile
        var parkOnEntry: CountDownLatch? = null

        private val delegate = ManualOrganizationRun.gateHeldExchangeGateTransaction(gate)

        override fun <R> withinGate(
            durableMutation: () -> R,
            onGateHeld: (R) -> Unit,
        ): R {
            entrySignal?.countDown()
            parkOnEntry?.await()
            return delegate.withinGate(durableMutation, onGateHeld)
        }
    }

    // ------------------------------------------------------------------
    // The legacy (pre-#417 record) fixture — the REAL durable store
    // ------------------------------------------------------------------

    /** The fixture bundle of a journey driven over the REAL [AndroidExportSessionStore]. */
    private class LegacyFixture(
        val holder: ExchangeFlowStateHolder,
        val controller: ExchangeFlowController,
        val run: ManualOrganizationRun,
        val store: AndroidExportSessionStore,
        val pendingStore: FakePendingStore,
        val sessionFile: File,
        val seededSession: ExportSession,
    ) {
        fun deleteStoreFiles() {
            sessionFile.delete()
            File(sessionFile.path + ".new").delete()
            File(sessionFile.path + ".bak").delete()
        }
    }

    /**
     * Seeds the REAL app-private export session store with a PRE-#417 record —
     * an untagged [ExportSession] whose save omits the `entryOrigin` key
     * entirely (the same seeding `AndroidExportSessionStoreTest` uses) — and
     * wires a fresh run + holder over it. [withCandidate] shapes the record:
     * `true` = legacy RUN_IN (non-empty scope), `false` = legacy IDLE (empty
     * scope). The structural supply matches the seeded shape so a reply import
     * validates against the session's own digest.
     */
    private fun newLegacyFixture(withCandidate: Boolean): LegacyFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionFile = File(context.cacheDir, "issue417-legacy-${System.nanoTime()}.json")
        val store = AndroidExportSessionStore(sessionFile)
        val seeded = ContextExportBuilder.build(
            exportInputs(1_000_000L, withCandidate = withCandidate),
            PrivacyTier.EXTERNAL_REDACTED,
            SequentialIdAllocator(),
        ).session
        check(seeded.entryOrigin == null) { "the seeded record must be pre-#417 (no origin)" }
        check(store.save(seeded))
        // The durable bytes are exactly a pre-#417 record: the origin key is absent.
        assertFalse("the seeded record must omit the entryOrigin key", sessionFile.readText().contains("entryOrigin"))

        val pendingStore = FakePendingStore()
        val gate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val run = ManualOrganizationRun(
            JourneyApplication(),
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run in the method-choice journeys") },
            exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(gate),
        )
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = false)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural(withCandidate = withCandidate)) },
            composeScopedExportInputs = { _, _, _ -> ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = true)) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { 1_000_000L },
            pendingImportStore = pendingStore,
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = run,
            scope = CoroutineScope(Dispatchers.Main),
            pendingImportStore = pendingStore,
            exchangeMutationGate = gate,
        )
        return LegacyFixture(holder, controller, run, store, pendingStore, sessionFile, seeded)
    }

    /**
     * The (p) non-divergence assertion: the store's active session and the
     * run-side binding state agree exactly — the active session is
     * [expectedExportId] (null = none), the run reports a bound scope request
     * exactly when a session is expected, and that exact exportId verifies as
     * the live scope owner. Any split state (session without binding, binding
     * without session) fails here.
     */
    private fun assertActiveSessionAndBindingAgree(
        fixture: HolderFixture,
        runId: RunId,
        expectedExportId: String?,
    ) {
        assertEquals(expectedExportId, fixture.controller.activeSession()?.exportId)
        assertEquals(expectedExportId != null, fixture.run.hasBoundScopeRequest())
        if (expectedExportId != null) {
            assertTrue("the expected export must verify as the live scope owner", fixture.run.isLiveScopeOwner(runId, expectedExportId))
        }
    }

    // ------------------------------------------------------------------
    // The journeys
    // ------------------------------------------------------------------

    @Test
    fun journeyGSameRunGenerateImportAttachSucceeds() {
        // AC-8(g): the same run generates (the atomic commit binds the
        // session), imports the reply on the method-choice face, and the CTA
        // attaches the validated intent at the frozen scope — the composed
        // phase consumes the confirmed scope (the fixture's composition is
        // NotReady → the typed InputUnavailable state; zero layout writes).
        val fixture = newFixture(driveToConfirmedScope = true)
        val runId = (fixture.run.state as ManualOrganizationRun.State.ScopeConfirmed).runId
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val session = fixture.store.session!!
        assertTrue("the commit bound the session to this run", fixture.run.isLiveScopeOwner(runId, session.exportId))

        composeRule.runOnUiThread {
            fixture.holder.openImport()
            fixture.holder.import(scopedReplyFor(session))
        }
        val success = awaitImportSuccess(fixture.holder)
        assertEquals(ExchangeImportEntryKind.RUN_IN, success.entryKind)
        // The commit mutation's unconditional replacement-invalidation delete
        // (the #374 write order, a no-op here) already ran once for E1's own
        // generation; the baseline for the continuation is NOW.
        val deleteCallsAtImport = fixture.pendingStore.deleteCalls

        composeRule.runOnUiThread { fixture.holder.continueImport() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.InputUnavailable }
        assertEquals(
            "継続成功は提案を消費しない",
            deleteCallsAtImport,
            fixture.pendingStore.deleteCalls,
        )
    }

    @Test
    fun journeyHOwnerlessRunInImportSavesPendingThenRebindCompletes() {
        // AC-8(h): a RUN_IN-origin import with NO live owner (the
        // process-death/hub recovery shape — the run is Idle) saves the
        // durable RUN_IN pending, adopts the ImportReview face (never the
        // direct-attach success state), and the rebind CTA admits a FRESH run
        // through the #375 anchor on the real shared gate.
        val fixture = newFixture(driveToConfirmedScope = false)
        // The legacy-compat scoped session decodes as RUN_IN; no run holds it.
        val generated = composeRule.runOnUiThread {
            fixture.controller.generateForSelection(PrivacyTier.EXTERNAL_REDACTED, listOf(scopedCandidate), mapOf(scopedCandidate to "c1"))
        } as ExchangeGenerationResult.Generated
        composeRule.runOnUiThread {
            fixture.holder.openImport()
            fixture.holder.import(scopedReplyFor(generated.session))
        }
        val review = awaitImportReview(fixture.holder)
        assertEquals(ExchangeImportEntryKind.RUN_IN, review.entryKind)
        assertNotNull("the durable RUN_IN pending was saved", fixture.pendingStore.record)
        assertEquals("no run admission happened at the import", "Idle", fixture.run.state::class.java.simpleName)

        composeRule.runOnUiThread { fixture.holder.continuePendingImport() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        // The rebind's selection restore re-opens the selection surface with
        // the record's scope as the initial values (the #375 contract) —
        // never a direct attach into a confirmed scope.
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Selecting }
    }

    @Test
    fun journeyISameScopeForeignOriginIsBlockedToRecreationGuidance() {
        // AC-8(i): the SAME scope generated by another (dead) run is not
        // attachable — the method-choice face's gate refuses the import with
        // the 作り直し guidance and nothing is saved.
        val fixture = newFixture(driveToConfirmedScope = true)
        // The run's own E1 binds the frozen scope…
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        fixture.holder.close()
        // …while the foreign reply belongs to a same-scope session generated
        // outside the run-owned commit (the other-run origin shape).
        val foreign = composeRule.runOnUiThread {
            fixture.controller.generateForSelection(PrivacyTier.EXTERNAL_REDACTED, listOf(scopedCandidate), mapOf(scopedCandidate to "c1"))
        } as ExchangeGenerationResult.Generated

        composeRule.runOnUiThread {
            fixture.holder.openMethodChoiceFlow()
            fixture.holder.import(scopedReplyFor(foreign.session))
        }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.RecreateRequestGuidance }
        assertNull("nothing was durably saved for a foreign request", fixture.pendingStore.record)
        assertTrue(fixture.run.state is ManualOrganizationRun.State.ScopeConfirmed)
    }

    @Test
    fun journeyKReplacementMovesTheAuthorityE1RefusedE2Attachable() {
        // AC-8(k): after the E1→E2 replacement generation, the binding moved
        // to E2 — E1's reply is refused with the 作り直し guidance, E2's reply
        // attaches.
        val fixture = newFixture(driveToConfirmedScope = true)
        val runId = (fixture.run.state as ManualOrganizationRun.State.ScopeConfirmed).runId
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val e1 = fixture.store.session!!
        // The replacement: the atomic commit advances the epoch and moves the
        // binding to E2 (the disclosed face becomes E2's).
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) {
            it is ExchangeScreen.Disclosing && it.state.session.exportId != e1.exportId
        }
        val e2 = fixture.store.session!!
        assertTrue(fixture.run.isLiveScopeOwner(runId, e2.exportId))
        assertFalse("E1's authority was replaced", fixture.run.isLiveScopeOwner(runId, e1.exportId))

        composeRule.runOnUiThread {
            fixture.holder.openImport()
            fixture.holder.import(scopedReplyFor(e1))
        }
        // E1's reply can no longer attach: the single-active store was replaced
        // by E2's commit, so the reply resolves to the typed EXPORT_MISMATCH
        // failure (the 作り直し guidance is reserved for a session that is still
        // durably loadable but foreign — journeyI's shape). Either way E1 is
        // unattachable, and nothing about E2 changed.
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.ImportOutcomeScreen }
        val e1Refusal = fixture.holder.screen as ExchangeScreen.ImportOutcomeScreen
        val mismatch = (e1Refusal.outcome as ExchangeImportOutcome.Pipeline).result as ExchangeImportResult.Failure
        assertEquals(
            IntentValidationFailure.ExportMismatch,
            (mismatch.failure as ExchangeImportFailure.Contract).failure,
        )

        composeRule.runOnUiThread {
            fixture.holder.openImport()
            fixture.holder.import(scopedReplyFor(e2))
        }
        val success = awaitImportSuccess(fixture.holder)
        assertEquals(ExchangeImportEntryKind.RUN_IN, success.entryKind)
        composeRule.runOnUiThread { fixture.holder.continueImport() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.InputUnavailable }
    }

    @Test
    fun journeyJInterruptedRunRejectsTheLateCompletionZeroWrite() {
        // AC-8(j): the generation epoch is claimed, the lock-free prepare is
        // parked on a barrier, the run is interrupted; the late completion's
        // commit is rejected (zero-write — no session save, no binding) and
        // the durable world a process recreation reads stays empty.
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val fixture = newFixture(
            driveToConfirmedScope = true,
            prepareGate = prepareEntered to releasePrepare,
        )
        generateScopedOnUi(fixture)
        assertTrue("the epoch was claimed and prepare began", prepareEntered.await(15, TimeUnit.SECONDS))

        // 中断 with the claimed epoch: the epoch dies with the operation.
        composeRule.runOnUiThread { fixture.run.cancel() }
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Cancelled }

        releasePrepare.countDown() // the delayed completion arrives
        awaitCondition("the late completion settled typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.GENERATION_NOT_CONFIRMED
        }
        assertNull("zero-write: no session was saved", fixture.store.session)
        assertNull("no pending record exists either", fixture.pendingStore.record)
        // The durable truth a process recreation reads: nothing resurfaces.
        assertEquals(null, fixture.store.active(1_000_000L))
    }

    @Test
    fun journeyMScopeDiscardDuringClaimedReplacementDropsE2AndReopens() {
        // AC-8(m): E1 is bound; the replacement's epoch is claimed (prepare
        // parked on a barrier); the scope-bound discard runs the run lock
        // first, invalidates the CURRENT epoch BEFORE the capability, and the
        // store invalidation retires E1. The late E2 completion is rejected
        // zero-write and never resurfaces; the selection face reopens
        // editable.
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val fixture = newFixture(
            driveToConfirmedScope = true,
            prepareGate = prepareEntered to releasePrepare,
            prepareParkIndex = 1,
        )
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        fixture.holder.close()
        assertTrue(fixture.run.hasBoundScopeRequest())

        generateScopedOnUi(fixture) // the E2 replacement: claim → parked prepare
        assertTrue("the replacement epoch was claimed", prepareEntered.await(15, TimeUnit.SECONDS))

        var outcome: ManualOrganizationRun.ScopeDiscardOutcome? = null
        composeRule.runOnUiThread {
            fixture.holder.discardScopeBoundRequest { outcome = it }
        }
        awaitCondition("the scope-bound discard settled") { outcome != null }
        assertEquals(ManualOrganizationRun.ScopeDiscardOutcome.Discarded, outcome)
        assertNull("E1 was invalidated by the discard", fixture.store.session)
        assertFalse(fixture.run.hasBoundScopeRequest())
        // The hosting surface's post-success reopen (the same seam the
        // method-choice face calls).
        composeRule.runOnUiThread { check(fixture.run.reopenSelection()) }

        releasePrepare.countDown() // E2's late completion arrives
        awaitCondition("the late E2 completion settled typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.GENERATION_NOT_CONFIRMED
        }
        assertNull("zero-write: E2 was never saved", fixture.store.session)
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Selecting }
    }

    @Test
    fun journeyODeathBetweenDurableSaveAndBindingUpdateReconnectsOnlyThroughRebind() {
        // AC-8(o): save後・束縛更新前のprocess death — the DURABLE fault point,
        // exercised against the REAL store file. "Process 1" drives a real
        // scoped generation THROUGH the run-owned atomic commit over the REAL
        // [AndroidExportSessionStore] on a temp file: inside the gate hold the
        // durable mutation runs (the session save lands in the durable file)
        // and the injected fault then THROWS, so the commit never returns to
        // its caller and the run-provided bind callback never runs — exactly
        // the world a process death between the durable save and the binding
        // update leaves behind. The first world (holder face + run + claimed
        // epoch + store) is abandoned exactly there: the durable file holds
        // the RUN_IN session with NO bound run. (Process death itself remains
        // device/crash-test territory; this connected oracle pins the durable
        // fault point's on-disk state and the recovery from it.)
        //
        // The "reboot" rebuilds a FRESH store/controller/run/holder from the
        // SAME durable file (no in-memory carryover): the reply is imported
        // through the hub/import-only hosting, the ownerless RUN_IN pending is
        // saved, the ImportReview face (never the direct-attach success state)
        // adopts, and the rebind 1-path admits a fresh run with the #375
        // restore — the direct attach authority never resurrects (the binding
        // stays absent in the fresh world until the rebind's own admission).
        // Unit twin for the ownerless recovery halves (additional):
        // `ExchangeFlowStateHolderTest.ownerlessRunInImportSavesThePendingAndProjectsToImportReviewWithoutAttach`.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionFile = File(context.cacheDir, "issue417-o-death-${System.nanoTime()}.json")
        try {
            // ---- "Process 1": the scoped generation dies mid-commit ----
            val firstGate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
            val firstStore = AndroidExportSessionStore(sessionFile)
            val firstPendingStore = FakePendingStore()
            val firstRun = ManualOrganizationRun(
                JourneyApplication(),
                app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
                exchangeGateTransaction = DurableDeathTransaction(firstGate),
            )
            val firstController = newJourneyController(firstStore, firstPendingStore)
            val firstHolder = ExchangeFlowStateHolder(
                controllerFactory = { firstController },
                run = firstRun,
                scope = CoroutineScope(Dispatchers.Main),
                pendingImportStore = firstPendingStore,
                exchangeMutationGate = firstGate,
            )
            firstRun.start()
            firstRun.confirmSelection(setOf(scopedCandidate))
            val runId1 = confirmedScopeRunId(firstRun)
            composeRule.runOnUiThread { firstHolder.openMethodChoiceFlow() }
            val epoch = firstRun.claimGenerationEpoch(listOf(scopedCandidate))!!
            val prepared = firstController.prepareScopedGeneration(
                PrivacyTier.EXTERNAL_REDACTED,
                listOf(scopedCandidate),
                mapOf(scopedCandidate to "c1"),
            ) as ExchangeGenerationPreparation.Prepared
            assertEquals("the scoped generation carries the RUN_IN origin", ExportEntryOrigin.RUN_IN, prepared.session.entryOrigin)

            var death: Throwable? = null
            val commitThread = thread(name = "process-1-generation-commit") {
                try {
                    firstRun.commitGeneratedSession(epoch, prepared.session.exportId) {
                        when (firstController.commitPreparedSession(prepared)) {
                            SessionPersistOutcome.Committed -> ManualOrganizationRun.PersistOutcome.Committed
                            SessionPersistOutcome.WriteFailed -> ManualOrganizationRun.PersistOutcome.WriteFailed
                        }
                    }
                } catch (failure: Throwable) {
                    death = failure
                }
            }
            commitThread.join(15_000)
            assertTrue("the injected process death aborted the commit", death is DurableDeathTransaction.ProcessDeath)

            // The fault point, asserted on the DURABLE truth: the save landed
            // in the real file (read through a FRESH store instance — the
            // bytes are on disk), the binding update never ran, and the first
            // world is abandoned exactly there.
            val durableAfterDeath = AndroidExportSessionStore(sessionFile)
            val saved = durableAfterDeath.load(prepared.session.exportId)
            assertNotNull("the durable file holds the saved session", saved)
            assertEquals(ExportEntryOrigin.RUN_IN, saved!!.resolvedEntryOrigin)
            assertFalse("the abandoned run never bound the saved session", firstRun.isLiveScopeOwner(runId1, prepared.session.exportId))
            assertFalse("the abandoned run holds no binding", firstRun.hasBoundScopeRequest())
            assertNull("the first world saved no pending either", firstPendingStore.record)
            val reply = scopedReplyFor(prepared.session)

            // ---- "Reboot": a fresh world rebuilt from the SAME durable file ----
            val secondGate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
            val secondStore = AndroidExportSessionStore(sessionFile)
            val secondPendingStore = FakePendingStore()
            val secondRun = ManualOrganizationRun(
                JourneyApplication(),
                app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
                exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(secondGate),
            )
            val secondHolder = ExchangeFlowStateHolder(
                controllerFactory = { newJourneyController(secondStore, secondPendingStore) },
                run = secondRun,
                scope = CoroutineScope(Dispatchers.Main),
                pendingImportStore = secondPendingStore,
                exchangeMutationGate = secondGate,
            )
            assertEquals("the fresh world starts with no run and no binding", "Idle", secondRun.state::class.java.simpleName)
            assertFalse(secondRun.hasBoundScopeRequest())

            composeRule.runOnUiThread {
                secondHolder.openImport() // the hub/import-only hosting shape
                secondHolder.import(reply)
            }
            val review = awaitImportReview(secondHolder)
            assertEquals(ExchangeImportEntryKind.RUN_IN, review.entryKind)
            assertNotNull("the ownerless RUN_IN pending was saved", secondPendingStore.record)
            assertEquals(PendingImportEntryKind.RUN_IN, secondPendingStore.record!!.entryKind)
            assertTrue(secondHolder.screen !is ExchangeScreen.ImportSuccess)

            // The rebind 1-path only: a fresh run is admitted; no direct attach.
            composeRule.runOnUiThread { secondHolder.continuePendingImport() }
            awaitScreenIs(secondHolder) { it is ExchangeScreen.Closed }
            awaitRunState(secondRun) { it is ManualOrganizationRun.State.Selecting }
            val selecting = secondRun.state as ManualOrganizationRun.State.Selecting
            assertEquals("the rebind restores the export scope as the initial values", setOf(scopedCandidate), selecting.restoredSelection)
            assertFalse("the direct attach authority was never revived", secondRun.isLiveScopeOwner(selecting.runId, prepared.session.exportId))
        } finally {
            sessionFile.delete()
            File(sessionFile.path + ".new").delete()
            File(sessionFile.path + ".bak").delete()
        }
    }

    /** The confirmed scope's runId of a run parked at the frozen scope. */
    private fun confirmedScopeRunId(run: ManualOrganizationRun): RunId =
        (run.state as ManualOrganizationRun.State.ScopeConfirmed).runId

    private fun newJourneyController(
        store: ExportSessionStore,
        pendingStore: PendingImportedIntentStore,
    ): ExchangeFlowController = ExchangeFlowController(
        composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = false)) },
        currentStructuralInputs = { ExchangeStructuralResult.Ready(structural(withCandidate = true)) },
        composeScopedExportInputs = { _, _, _ -> ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = true)) },
        store = store,
        allocator = SequentialIdAllocator(),
        clock = { 1_000_000L },
        pendingImportStore = pendingStore,
    )

    @Test
    fun journeyUCleanupWriteFailedKeepsRetryableStateThenReachesTerminal() {
        // AC-8(u): a cleanup whose fallback store write fails does NOT settle
        // — the session, the cancelling face and the retryable handle stay
        // consistent; the retry reaches the terminal. (Owner-loss shape: the
        // run-owned seam is a `Superseded` no-op, so the failure-aware exact
        // invalidation is the fallback.)
        val fixture = newFixture(driveToConfirmedScope = true)
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        fixture.store.failInvalidateIf = true
        composeRule.runOnUiThread { fixture.run.cancel() } // owner loss → Superseded cleanup
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Cancelled }

        composeRule.runOnUiThread { fixture.holder.closeDisclosure() }
        awaitCondition("the fallback failure surfaced typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.EXPORT_CLEANUP_FAILED
        }
        assertNotNull("the failed fallback keeps the session", fixture.store.session)
        assertTrue("the cancelling face is kept (no settle)", (fixture.holder.screen as? ExchangeScreen.Disclosing)?.state?.cancelling == true)
        assertEquals("the fallback used the failure-aware invalidation", 1, fixture.store.invalidateIfCalls)

        fixture.store.failInvalidateIf = false
        composeRule.runOnUiThread { fixture.holder.retryExportCleanup() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        assertNull("the retry's terminal invalidation cleared the session", fixture.store.session)
    }

    @Test
    fun journeyLCrossingRebindAdmissionAndGenerationCommitDoesNotDeadlock() {
        // AC-8(l): the crossing is built on ONE [ManualOrganizationRun] — the
        // rebind admission HOLDS that run's lock and waits on the REAL
        // process-wide gate (the #375 anchor shape) while a GENERATION COMMIT
        // of the SAME run is released against it. With the correct (run lock →
        // gate) order the commit must BLOCK on the SAME run lock BEFORE
        // reaching the gate; a gate-first regression (a gate→run-lock
        // acquisition anywhere) would let the commit into the gate queue while
        // the admission holds the lock and forms the ABBA cycle — caught by
        // the not-entered latch assertions and, ultimately, by the bounded
        // joins as the deadlock failure detector. No wall-clock sleeps for
        // synchronization, no Thread.State discrimination anywhere.
        val sharedGate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val crossing = PreGateBoundaryTransaction(sharedGate)
        val fixture = newFixture(driveToConfirmedScope = true, gateTransaction = crossing, gate = sharedGate)
        val run = fixture.run

        // The old operation's generation: the epoch is claimed and the
        // lock-free prepare is done; the commit (the gate acquisition) is what
        // gets parked/crossed below.
        val epoch = run.claimGenerationEpoch(listOf(scopedCandidate))!!
        val prepared = fixture.controller.prepareScopedGeneration(
            PrivacyTier.EXTERNAL_REDACTED,
            listOf(scopedCandidate),
            mapOf(scopedCandidate to "c1"),
        ) as ExchangeGenerationPreparation.Prepared

        // Phase 1 — the commit thread parks at its pre-gate boundary: it has
        // ACQUIRED the run lock (the run invokes the transaction only from
        // inside its lock section) and is about to acquire the gate. Positive
        // latch observation — never thread state.
        val commitPreGate = CountDownLatch(1)
        val commitMayProceed = CountDownLatch(1)
        crossing.entrySignal = commitPreGate
        crossing.parkOnEntry = commitMayProceed
        var parkedCommitDeath: Throwable? = null
        val parkedCommit = thread(name = "parked-generation-commit-pre-gate") {
            try {
                run.commitGeneratedSession(epoch, prepared.session.exportId) {
                    when (fixture.controller.commitPreparedSession(prepared)) {
                        SessionPersistOutcome.Committed -> ManualOrganizationRun.PersistOutcome.Committed
                        SessionPersistOutcome.WriteFailed -> ManualOrganizationRun.PersistOutcome.WriteFailed
                    }
                }
            } catch (failure: Throwable) {
                parkedCommitDeath = failure
            }
        }
        assertTrue(
            "the commit thread acquired the run lock and reached its pre-gate boundary",
            commitPreGate.await(10, TimeUnit.SECONDS),
        )
        // Interrupt/finish that commit operation: it unwinds BEFORE any gate
        // involvement (zero durable writes) and frees the run lock.
        parkedCommit.interrupt()
        parkedCommit.join(15_000)
        assertTrue("the parked commit unwound through the interruption", parkedCommitDeath is InterruptedException)
        crossing.parkOnEntry = null

        // The old operation ends (the harness interrupt path): the epoch dies
        // with it and the run lock is free — while a generation completion of
        // the old operation stays parked pre-gate (it holds NO lock while
        // parked).
        run.cancel()
        awaitRunState(run) { it is ManualOrganizationRun.State.Cancelled }

        val commitMayStart = CountDownLatch(1)
        val commitAttemptingRunLock = CountDownLatch(1)
        val commitEnteredGate = CountDownLatch(1)
        crossing.entrySignal = commitEnteredGate
        var lateCommitOutcome: ManualOrganizationRun.GenerationCommitOutcome? = null
        val lateCommit = thread(name = "parked-generation-commit") {
            commitMayStart.await(15, TimeUnit.SECONDS)
            commitAttemptingRunLock.countDown() // B's pre-gate observation: it is now attempting the commit
            lateCommitOutcome = run.commitGeneratedSession(epoch, prepared.session.exportId) {
                when (fixture.controller.commitPreparedSession(prepared)) {
                    SessionPersistOutcome.Committed -> ManualOrganizationRun.PersistOutcome.Committed
                    SessionPersistOutcome.WriteFailed -> ManualOrganizationRun.PersistOutcome.WriteFailed
                }
            }
        }

        // The third party holds the shared gate, so the rebind admission
        // genuinely waits inside the gate queue.
        val holderInGate = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        thread(name = "gate-holder") {
            sharedGate.withGate {
                holderInGate.countDown()
                releaseGate.await(15, TimeUnit.SECONDS)
            }
        }
        assertTrue(holderInGate.await(10, TimeUnit.SECONDS))

        // The rebind admission on the SAME run: inside the run's lock section
        // (the anchor contract), it then attempts the gate while STILL holding
        // THE run lock the parked commit needs.
        val aHoldsLock = CountDownLatch(1)
        val aMayProceed = CountDownLatch(1)
        val aAttemptingGate = CountDownLatch(1)
        val aEnteredGate = CountDownLatch(1)
        var aOutcome: ManualOrganizationRun.StartOutcome? = null
        val rebindAdmission = thread(name = "rebind-admission") {
            aOutcome = run.start(
                app.lawnchair.organizer.diagnostics.model.Trigger.MANUAL_FULL,
                intent = null,
                admissionAnchor = { complete ->
                    aHoldsLock.countDown() // A now holds the run's (only) lock
                    aMayProceed.await(15, TimeUnit.SECONDS)
                    aAttemptingGate.countDown() // A is about to queue on the gate
                    sharedGate.withGate {
                        aEnteredGate.countDown()
                        complete()
                    }
                    true
                },
            )
        }
        assertTrue("A must be holding the run lock", aHoldsLock.await(10, TimeUnit.SECONDS))

        // Release the parked commit: with the correct (run lock → gate) order
        // it must BLOCK on the run lock BEFORE reaching the gate.
        commitMayStart.countDown()
        assertTrue("B must be attempting its commit", commitAttemptingRunLock.await(10, TimeUnit.SECONDS))
        assertFalse(
            "B must not have entered the gate while A held the run lock",
            commitEnteredGate.await(3, TimeUnit.SECONDS),
        )

        // The fully formed crossing: A queues on the gate under the run lock;
        // B is still blocked on the same run lock, in front of the gate.
        aMayProceed.countDown()
        assertTrue("A must reach its gate attempt under the run lock", aAttemptingGate.await(10, TimeUnit.SECONDS))
        assertFalse(
            "B still has not entered the gate while the admission holds the lock and waits",
            commitEnteredGate.await(3, TimeUnit.SECONDS),
        )
        assertEquals("only the third party holds the gate while both sides wait", 1, sharedGate.heldCount)

        // Both sides complete regardless of the monitor's wakeup order — the
        // bounded joins are the deadlock failure detector. The admission
        // finishes first (it holds the lock); the released commit then
        // acquires the freed lock, fails the commit re-verification (the
        // operation it committed against is gone) and settles as a typed
        // zero-write reject BEFORE the gate.
        releaseGate.countDown()
        assertTrue(aEnteredGate.await(15, TimeUnit.SECONDS))
        rebindAdmission.join(15_000)
        lateCommit.join(15_000)
        assertTrue("the rebind admission started a fresh operation on the same run", aOutcome is ManualOrganizationRun.StartOutcome.Started)
        awaitRunState(run) { it is ManualOrganizationRun.State.Selecting }
        val rejection = lateCommitOutcome as? ManualOrganizationRun.GenerationCommitOutcome.Rejected
        assertEquals(
            "the late commit settled as a typed reject",
            ManualOrganizationRun.GenerationCommitRejection.NOT_CONFIRMED,
            rejection?.reason,
        )
        assertEquals("the rejected commit never even entered the gate", 1L, commitEnteredGate.count)
        // Zero-write: the store was never touched by either commit attempt.
        assertNull("zero-write: no session was saved", fixture.store.session)
        assertTrue("zero-write: the save order is empty", fixture.store.saveOrder.isEmpty())
        assertEquals("the gate was released with no leak", 0, sharedGate.heldCount)
    }

    @Test
    fun rebindAdmissionAndLiveOwnerPendingSaveAlsoCrossesWithoutDeadlock() {
        // Additional regression for the round-1 lock-inversion fix (not a
        // letter's primary oracle): the SAME single-run-lock crossing as
        // [journeyLCrossingRebindAdmissionAndGenerationCommitDoesNotDeadlock]
        // with the LIVE-OWNER PENDING SAVE as side B — the run-owned
        // transaction takes THE run's lock first and then the shared gate, so
        // while the rebind admission holds that lock and waits at the gate,
        // the save must block on the run lock BEFORE reaching the gate, and it
        // may only enter the gate after the admission released the lock.
        // Latch-sequenced; no Thread.State discrimination.
        val sharedGate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val saveEnteredGate = CountDownLatch(1)
        val saveTransaction = object : ManualOrganizationRun.ExchangeGateTransaction {
            private val delegate = ManualOrganizationRun.gateHeldExchangeGateTransaction(sharedGate)

            override fun <R> withinGate(durableMutation: () -> R, onGateHeld: (R) -> Unit): R {
                saveEnteredGate.countDown()
                return delegate.withinGate(durableMutation, onGateHeld)
            }
        }
        val fixture = newFixture(driveToConfirmedScope = true, gateTransaction = saveTransaction, gate = sharedGate)
        val run = fixture.run
        val runId = confirmedScopeRunId(run)

        // The old operation ends (the harness interrupt path) so the rebind
        // admission can run on the SAME run — the pending save's crossing is
        // then against that same run lock.
        run.cancel()
        awaitRunState(run) { it is ManualOrganizationRun.State.Cancelled }

        val holderInGate = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        thread(name = "gate-holder") {
            sharedGate.withGate {
                holderInGate.countDown()
                releaseGate.await(15, TimeUnit.SECONDS)
            }
        }
        assertTrue(holderInGate.await(10, TimeUnit.SECONDS))

        val aHoldsLock = CountDownLatch(1)
        val aMayProceed = CountDownLatch(1)
        val aAttemptingGate = CountDownLatch(1)
        var aOutcome: ManualOrganizationRun.StartOutcome? = null
        val rebindAdmission = thread(name = "rebind-admission") {
            aOutcome = run.start(
                app.lawnchair.organizer.diagnostics.model.Trigger.MANUAL_FULL,
                intent = null,
                admissionAnchor = { complete ->
                    aHoldsLock.countDown()
                    aMayProceed.await(15, TimeUnit.SECONDS)
                    aAttemptingGate.countDown()
                    sharedGate.withGate { complete() }
                    true
                },
            )
        }
        assertTrue(aHoldsLock.await(10, TimeUnit.SECONDS))

        // Side B — the live-owner pending save on the SAME run: parked pre-gate
        // (it holds NO lock while parked), then released against the run lock
        // the admission is holding.
        val saveMayStart = CountDownLatch(1)
        val saveAttemptingRunLock = CountDownLatch(1)
        var saveOwned: Boolean? = null
        var saveRan = false
        val saveDone = CountDownLatch(1)
        val pendingSave = thread(name = "pending-save") {
            saveMayStart.await(15, TimeUnit.SECONDS)
            saveAttemptingRunLock.countDown() // B's pre-gate observation: it is now attempting the save
            run.savePendingImportForLiveOwner(
                runId,
                "export-x",
                save = {
                    saveRan = true
                    "saved"
                },
                onGateHeld = { _, owned ->
                    saveOwned = owned
                    saveDone.countDown()
                    "settled"
                },
            )
        }
        saveMayStart.countDown()
        assertTrue("B must be attempting its save", saveAttemptingRunLock.await(10, TimeUnit.SECONDS))
        aMayProceed.countDown()
        assertTrue("A must reach its gate attempt under the run lock", aAttemptingGate.await(10, TimeUnit.SECONDS))
        assertFalse(
            "B must not have entered the gate while A held the run lock",
            saveEnteredGate.await(3, TimeUnit.SECONDS),
        )
        assertEquals("only the third party holds the gate while both sides wait", 1, sharedGate.heldCount)

        releaseGate.countDown()
        rebindAdmission.join(15_000)
        pendingSave.join(15_000)
        assertTrue("the admission started the run", aOutcome is ManualOrganizationRun.StartOutcome.Started)
        assertTrue("the pending save completed (no deadlock)", saveDone.await(15, TimeUnit.SECONDS))
        assertTrue("the save's durable mutation ran", saveRan)
        assertEquals("no live owner binds a foreign export", false, saveOwned)
        assertTrue(
            "the save entered the gate only after the run lock was freed",
            saveEnteredGate.await(15, TimeUnit.SECONDS),
        )
        assertEquals(0, sharedGate.heldCount)
    }

    @Test
    fun journeySBindAndClearCallbacksRunInsideTheGateHold() {
        // AC-8(s): the run-provided bind/clear callbacks execute while the
        // REAL process-wide gate is held — observed directly by the observing
        // capability, which brackets every callback with gate-hold readings
        // taken inside the gate's own critical section: the BIND on the
        // generation commit path, and the CLEAR on both the pre-send discard
        // (the run-owned cleanup) and the scope-bound discard paths. Every
        // hold is released afterwards (no leaked hold).
        val gate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val observing = GateObservationTransaction(gate)
        val fixture = newFixture(driveToConfirmedScope = true, gateTransaction = observing, gate = gate)
        val runId = confirmedScopeRunId(fixture.run)

        // The bind on the commit path.
        observing.phase = "commit-bind"
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val e1 = fixture.store.session!!
        assertTrue("the callback that bound the session ran", fixture.run.isLiveScopeOwner(runId, e1.exportId))

        // The clear on the cleanup path (pre-send discard retires the bound session).
        observing.phase = "cleanup-clear"
        composeRule.runOnUiThread { fixture.holder.closeDisclosure() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        assertFalse(fixture.run.hasBoundScopeRequest())

        // The clear on the scope-bound discard path. (The replacement's
        // generation commit records its own bind observation under
        // "commit-bind-2" — a second bind under the gate hold.)
        observing.phase = "commit-bind-2"
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        observing.phase = "discard-clear"
        var outcome: ManualOrganizationRun.ScopeDiscardOutcome? = null
        composeRule.runOnUiThread { fixture.holder.discardScopeBoundRequest { outcome = it } }
        awaitCondition("the scope-bound discard settled") { outcome != null }
        assertEquals(ManualOrganizationRun.ScopeDiscardOutcome.Discarded, outcome)
        assertFalse(fixture.run.hasBoundScopeRequest())

        // Every run-provided callback ran while the gate was held.
        val recorded = observing.observations.toList()
        assertEquals(
            listOf("commit-bind", "cleanup-clear", "commit-bind-2", "discard-clear"),
            recorded.map { it.phase },
        )
        for (observation in recorded) {
            assertTrue("[$observation] the durable mutation ran under the gate", observation.gateHeldAtMutation)
            assertTrue("[$observation] the run-provided callback ran under the gate hold", observation.gateHeldAroundRunCallback)
            assertTrue("[$observation] gate hold depth at the callback", observation.gateDepthAtCallback >= 1)
        }
        assertEquals("every hold was released", 0, gate.heldCount)
    }

    @Test
    fun journeyDLegacyIdleRequestAdmitsTheRunAndBlocksTheMethodFaceImport() {
        // AC-8(d), second half: with a durable legacy IDLE request active (a
        // pre-#417 record — no origin key, empty scope — seeded into the REAL
        // store), the manual run admission SUCCEEDS and reaches the confirmed
        // scope (a durable request holds no RUN lease and never freezes the
        // scope), and the method-choice face's import of that foreign request
        // is blocked with the 作り直し guidance: nothing is saved, the legacy
        // record stays untouched.
        val fixture = newLegacyFixture(withCandidate = false)
        try {
            val legacy = fixture.seededSession
            assertEquals(ExportEntryOrigin.IDLE, legacy.resolvedEntryOrigin)

            // The admission succeeds with the legacy request durable-active.
            fixture.run.start()
            awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Selecting }
            fixture.run.confirmSelection(setOf(scopedCandidate))
            awaitRunState(fixture.run) { it is ManualOrganizationRun.State.ScopeConfirmed }
            assertFalse("a legacy IDLE request never binds the confirmed scope", fixture.run.hasBoundScopeRequest())

            composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
            awaitScreenIs(fixture.holder) {
                it is ExchangeScreen.SelectingPrivacy && it.replacementConfirmationRequired
            }
            composeRule.runOnUiThread {
                fixture.holder.openImport()
                fixture.holder.import(markedReplyFor(legacy, withCandidate = false))
            }
            awaitScreenIs(fixture.holder) { it is ExchangeScreen.RecreateRequestGuidance }
            assertNull("nothing was saved for the blocked import", fixture.pendingStore.record)
            assertNotNull("the legacy record stays active", fixture.store.active(1_000_000L))
            assertFalse(fixture.run.hasBoundScopeRequest())
        } finally {
            fixture.deleteStoreFiles()
        }
    }

    @Test
    fun journeyNOutOfOrderCompletionsCommitOnlyTheCurrentEpoch() {
        // AC-8(n): two scoped generations whose completions arrive OUT OF
        // ORDER — E1's prepare is parked after its epoch claim; E2 claims
        // (advancing the epoch), commits, binds and settles while E1 is still
        // parked; the released E1 completion then fails the epoch
        // re-verification and is discarded ZERO-WRITE: no session save (the
        // save order proves only E2 ever landed), no binding change.
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val fixture = newFixture(
            driveToConfirmedScope = true,
            prepareGate = prepareEntered to releasePrepare,
            prepareParkIndex = 0, // park E1's prepare only; E2 completes first
        )
        val runId = confirmedScopeRunId(fixture.run)

        generateScopedOnUi(fixture) // E1: claim → parked prepare
        assertTrue("the E1 epoch was claimed and prepare began", prepareEntered.await(15, TimeUnit.SECONDS))

        generateScopedOnUi(fixture) // E2: claims the NEXT epoch and completes first
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val e2 = fixture.store.session!!
        assertTrue(fixture.run.isLiveScopeOwner(runId, e2.exportId))

        releasePrepare.countDown() // E1's LATE completion arrives
        awaitCondition("the stale E1 completion settled typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.GENERATION_NOT_CONFIRMED
        }
        assertEquals("zero-write: only E2 was ever saved", listOf(e2.exportId), fixture.store.saveOrder.toList())
        assertTrue("the binding stayed on E2", fixture.run.isLiveScopeOwner(runId, e2.exportId))
        assertNull(fixture.pendingStore.record)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.SelectingPrivacy }
    }

    @Test
    fun journeyPActiveSessionAndBindingNeverDivergeAcrossFailurePaths() {
        // AC-8(p): after the store-save failure, the encode failure and the
        // pre-send discard paths, the store's active session and the run-side
        // binding NEVER diverge — asserted after every path via store reads
        // (controller.activeSession) plus run-side state
        // (hasBoundScopeRequest / isLiveScopeOwner).
        val encodeFailureSwitch = AtomicBoolean(false)
        val fixture = newFixture(driveToConfirmedScope = true, encodeFailureSwitch = encodeFailureSwitch)
        val runId = confirmedScopeRunId(fixture.run)

        // Path 1 — store-save failure: the commit's durable mutation fails;
        // nothing is saved and nothing binds.
        fixture.store.failSave = true
        generateScopedOnUi(fixture)
        awaitCondition("the store failure surfaced typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.GENERATION_STORE_FAILURE
        }
        assertActiveSessionAndBindingAgree(fixture, runId, expectedExportId = null)

        // Path 2 — encode failure: the commit SUCCEEDS (session saved and
        // bound), then the encode fails and the run-owned cleanup retires the
        // committed session — session and binding retire TOGETHER.
        fixture.store.failSave = false
        encodeFailureSwitch.set(true)
        generateScopedOnUi(fixture)
        awaitCondition("the encode failure surfaced typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.GENERATION_OVERSIZE
        }
        assertActiveSessionAndBindingAgree(fixture, runId, expectedExportId = null)

        // Path 3 — pre-send discard: a bound session retires through the
        // run-owned cleanup; the invalidation and the binding clear happen in
        // one gate-held section.
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val e3 = fixture.store.session!!
        assertActiveSessionAndBindingAgree(fixture, runId, expectedExportId = e3.exportId)
        composeRule.runOnUiThread { fixture.holder.closeDisclosure() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        assertActiveSessionAndBindingAgree(fixture, runId, expectedExportId = null)
    }

    @Test
    fun journeyQLegacyIdleFixtureRestoresUncheckedWithoutAttachAuthority() {
        // AC-8(q), fixture (a): a pre-#417 legacy IDLE record (no origin key,
        // empty scope) imported through the hub — the decode rule reads IDLE,
        // the record restores UNCHECKED (no #375 initial values), and the
        // direct attach authority never resurrects (the continuation is a
        // fresh run admission, never an attach into a confirmed scope).
        val fixture = newLegacyFixture(withCandidate = false)
        try {
            val legacy = fixture.seededSession
            assertEquals(ExportEntryOrigin.IDLE, legacy.resolvedEntryOrigin)

            composeRule.runOnUiThread { fixture.holder.openFlow() }
            composeRule.runOnUiThread {
                fixture.holder.openImport()
                fixture.holder.import(markedReplyFor(legacy, withCandidate = false))
            }
            val success = awaitImportSuccess(fixture.holder)
            assertEquals(ExchangeImportEntryKind.IDLE, success.entryKind)
            val record = fixture.pendingStore.record!!
            assertEquals(PendingImportEntryKind.IDLE, record.entryKind)

            composeRule.runOnUiThread { fixture.holder.continueImport() }
            awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
            awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Selecting }
            val selecting = fixture.run.state as ManualOrganizationRun.State.Selecting
            assertTrue("unchecked restore: no #375 initial values", selecting.restoredSelection.isEmpty())
            assertEquals("no exported scope on a legacy IDLE record", 0, selecting.intentScopeCount)
            assertFalse(
                "the direct attach authority never resurrects",
                fixture.run.isLiveScopeOwner(selecting.runId, legacy.exportId),
            )
        } finally {
            fixture.deleteStoreFiles()
        }
    }

    @Test
    fun journeyQLegacyRunInFixtureOffersTheSelectionRestoreInitialValues() {
        // AC-8(q), fixture (b): a pre-#417 legacy RUN_IN record (no origin
        // key, non-empty scope) imported through the hub ownerless — the
        // decode rule reads RUN_IN, the RUN_IN pending is saved, the
        // ImportReview face adopts (never the direct-attach success state),
        // and the rebind offers the #375 selection restore initial values
        // (the resolvable export scope) on the reopened selection surface.
        val fixture = newLegacyFixture(withCandidate = true)
        try {
            val legacy = fixture.seededSession
            assertEquals(ExportEntryOrigin.RUN_IN, legacy.resolvedEntryOrigin)
            assertEquals("the import is ownerless (hub recovery shape)", "Idle", fixture.run.state::class.java.simpleName)

            composeRule.runOnUiThread { fixture.holder.openFlow() }
            composeRule.runOnUiThread {
                fixture.holder.openImport()
                fixture.holder.import(markedReplyFor(legacy, withCandidate = true))
            }
            val review = awaitImportReview(fixture.holder)
            assertEquals(ExchangeImportEntryKind.RUN_IN, review.entryKind)
            assertEquals(PendingImportEntryKind.RUN_IN, fixture.pendingStore.record!!.entryKind)
            assertTrue(fixture.holder.screen !is ExchangeScreen.ImportSuccess)

            composeRule.runOnUiThread { fixture.holder.continuePendingImport() }
            awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
            awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Selecting }
            val selecting = fixture.run.state as ManualOrganizationRun.State.Selecting
            assertEquals("the #375 restore initial values are the export scope", setOf(scopedCandidate), selecting.restoredSelection)
            assertEquals(1, selecting.intentScopeCount)
        } finally {
            fixture.deleteStoreFiles()
        }
    }

    @Test
    fun journeyQLegacyRunInEmptyScopeFixtureDecodesIdleFailSafe() {
        // AC-8(q), fixture (c): a record authored by a PRE-#417 run-in
        // generation with an EMPTY selection (no origin key, empty scope) —
        // indistinguishable from a legacy IDLE record, so the decode rule
        // FAILS SAFE to IDLE: the restore stays unchecked and the direct
        // attach authority never resurrects, whatever the record's author.
        val fixture = newLegacyFixture(withCandidate = false)
        try {
            val legacy = fixture.seededSession
            assertNull("the record carries no origin key", legacy.entryOrigin)
            assertTrue(legacy.scopeCandidates.isEmpty())
            assertEquals("the fail-safe arm: empty scope decodes IDLE", ExportEntryOrigin.IDLE, legacy.resolvedEntryOrigin)

            composeRule.runOnUiThread { fixture.holder.openFlow() }
            composeRule.runOnUiThread {
                fixture.holder.openImport()
                fixture.holder.import(markedReplyFor(legacy, withCandidate = false))
            }
            val success = awaitImportSuccess(fixture.holder)
            assertEquals(ExchangeImportEntryKind.IDLE, success.entryKind)

            composeRule.runOnUiThread { fixture.holder.continueImport() }
            awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
            awaitRunState(fixture.run) { it is ManualOrganizationRun.State.Selecting }
            val selecting = fixture.run.state as ManualOrganizationRun.State.Selecting
            assertTrue("unchecked restore: nothing run-in survives the decode", selecting.restoredSelection.isEmpty())
            assertEquals(0, selecting.intentScopeCount)
            assertFalse(
                "the direct attach authority never resurrects",
                fixture.run.isLiveScopeOwner(selecting.runId, legacy.exportId),
            )
        } finally {
            fixture.deleteStoreFiles()
        }
    }

    @Test
    fun journeyRReplacementCommitKeepsE2BoundAndInvalidatesOnlyTheOldPending() {
        // AC-8(r): the E1→E2 replacement commit keeps active session = E2 and
        // the binding = E2; the ONLY invalidated thing is E1's dependent
        // pending record (the #374 replacement invalidation inside the commit
        // mutation); E2 is never rolled back — asserted directly on the store
        // and the run state, including after E1's reply is refused.
        val fixture = newFixture(driveToConfirmedScope = true)
        val runId = confirmedScopeRunId(fixture.run)
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val e1 = fixture.store.session!!

        // E1's reply imported under the live binding: the RUN_IN pending exists.
        composeRule.runOnUiThread {
            fixture.holder.openImport()
            fixture.holder.import(scopedReplyFor(e1))
        }
        awaitImportSuccess(fixture.holder)
        val oldRecord = fixture.pendingStore.record!!
        assertEquals(e1.exportId, oldRecord.exportId)
        assertEquals(PendingImportEntryKind.RUN_IN, oldRecord.entryKind)
        // Baseline: E1's own generation commit already ran the unconditional
        // replacement-invalidation delete (a no-op at that point).
        val deleteCallsBeforeReplacement = fixture.pendingStore.deleteCalls

        // The replacement: the atomic commit saves E2 and invalidates the old
        // pending inside the same durable mutation.
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) {
            it is ExchangeScreen.Disclosing && it.state.session.exportId != e1.exportId
        }
        val e2 = fixture.store.session!!
        assertEquals("active session = E2 after the replacement commit", e2.exportId, fixture.controller.activeSession()?.exportId)
        assertTrue("binding = E2", fixture.run.isLiveScopeOwner(runId, e2.exportId))
        assertFalse(fixture.run.isLiveScopeOwner(runId, e1.exportId))
        assertEquals(
            "only E1's dependent pending was invalidated",
            deleteCallsBeforeReplacement + 1,
            fixture.pendingStore.deleteCalls,
        )
        assertNull(fixture.pendingStore.record)
        assertEquals(
            "E2 was never rolled back (exactly two saves, the second one durable)",
            listOf(e1.exportId, e2.exportId),
            fixture.store.saveOrder.toList(),
        )

        // E1's reply is refused (its record was replaced); the refusal leaves
        // E2's session and binding intact.
        composeRule.runOnUiThread {
            fixture.holder.openImport()
            fixture.holder.import(scopedReplyFor(e1))
        }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.ImportOutcomeScreen }
        assertEquals("E2 still active after the refusal", e2.exportId, fixture.controller.activeSession()?.exportId)
        assertTrue("binding still E2", fixture.run.isLiveScopeOwner(runId, e2.exportId))
    }

    @Test
    fun journeyTStaleCleanupAfterAReplacementKeepsE2ThroughTheHolderCleanupPath() {
        // AC-8(t): E1's cleanup lands only AFTER E2's replacement commit —
        // driven through the holder's OWN cleanup path (the pre-send discard
        // leaves a WriteFailed retry anchor; the replacement then moves the
        // binding to E2; the retry reaches the run-owned seam). The stale
        // cleanup is a `Superseded` typed no-op that never touches the store,
        // and its gate-held fallback `invalidateIf(E1)` is a NoMatch against
        // E2: active session = E2 and binding = E2 are kept throughout.
        val fixture = newFixture(driveToConfirmedScope = true)
        val runId = confirmedScopeRunId(fixture.run)
        composeRule.runOnUiThread { fixture.holder.openMethodChoiceFlow() }
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Disclosing }
        val e1 = fixture.store.session!!

        // The pre-send discard of E1 fails durably: the typed retryable state
        // keeps the session, the binding and the retry anchor.
        fixture.store.failInvalidateIf = true
        composeRule.runOnUiThread { fixture.holder.closeDisclosure() }
        awaitCondition("the cleanup failure surfaced typed") {
            fixture.holder.status?.kind == ExchangeStatus.Kind.EXPORT_CLEANUP_FAILED
        }
        assertEquals(1, fixture.store.invalidateIfCalls)

        // The replacement commits while the E1 cleanup is still retryable.
        fixture.store.failInvalidateIf = false
        generateScopedOnUi(fixture)
        awaitScreenIs(fixture.holder) {
            it is ExchangeScreen.Disclosing && it.state.session.exportId != e1.exportId
        }
        val e2 = fixture.store.session!!
        assertTrue(fixture.run.isLiveScopeOwner(runId, e2.exportId))

        // The retry: the run-owned seam sees binding = E2 ≠ E1 → the stale
        // cleanup is a typed no-op that never reaches the store…
        composeRule.runOnUiThread { fixture.holder.retryExportCleanup() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }

        // …and the fallback's exact invalidation of E1 is a NoMatch against E2.
        assertEquals(2, fixture.store.invalidateIfCalls)
        assertEquals(
            listOf(
                Triple(e1.exportId, e1.exportId as String?, "WriteFailed"),
                Triple(e1.exportId, e2.exportId as String?, "NoMatch"),
            ),
            fixture.store.invalidateIfLog.toList(),
        )
        assertEquals("active session = E2 kept", e2.exportId, fixture.controller.activeSession()?.exportId)
        assertTrue("binding = E2 kept", fixture.run.isLiveScopeOwner(runId, e2.exportId))
    }

    @Test
    fun journeyVDiscardInvalidatesTheEpochBeforeTheCapabilityCall() {
        // AC-8(v): the scope-bound discard invalidates the current generation
        // epoch under the run lock BEFORE the capability call / gate entry —
        // asserted directly: the recording capability evaluates
        // `isCurrentEpoch` at GATE ENTRY (re-entrant under the run lock the
        // caller holds), and the store records the `invalidateIf` call, so the
        // committed sequence reads [gate entry with the epoch already
        // invalidated] → [invalidateIf] — for a discard driven through the
        // holder's connected path.
        val gate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val recording = RecordingTransaction(gate)
        val fixture = newFixture(driveToConfirmedScope = true, gateTransaction = recording, gate = gate)
        val runId = confirmedScopeRunId(fixture.run)

        // The test owns the epoch: claimed and committed through the
        // production commit shape (prepare → commitPreparedSession).
        val epoch = fixture.run.claimGenerationEpoch(listOf(scopedCandidate))!!
        recording.epochProbe = { fixture.run.isCurrentEpoch(epoch) }
        val prepared = fixture.controller.prepareScopedGeneration(
            PrivacyTier.EXTERNAL_REDACTED,
            listOf(scopedCandidate),
            mapOf(scopedCandidate to "c1"),
        ) as ExchangeGenerationPreparation.Prepared
        assertEquals(
            ManualOrganizationRun.GenerationCommitOutcome.Committed,
            fixture.run.commitGeneratedSession(epoch, prepared.session.exportId) {
                when (fixture.controller.commitPreparedSession(prepared)) {
                    SessionPersistOutcome.Committed -> ManualOrganizationRun.PersistOutcome.Committed
                    SessionPersistOutcome.WriteFailed -> ManualOrganizationRun.PersistOutcome.WriteFailed
                }
            },
        )
        val exportId = prepared.session.exportId
        assertTrue(fixture.run.isLiveScopeOwner(runId, exportId))
        assertEquals(
            listOf("gate-enter(epochCurrent=true)", "durable-mutation", "run-callback(gateHeld=true)"),
            recording.events.toList(),
        )

        // The discard through the holder's connected path.
        var outcome: ManualOrganizationRun.ScopeDiscardOutcome? = null
        composeRule.runOnUiThread { fixture.holder.discardScopeBoundRequest { outcome = it } }
        awaitCondition("the scope-bound discard settled") { outcome != null }
        assertEquals(ManualOrganizationRun.ScopeDiscardOutcome.Discarded, outcome)

        // The committed sequence: the discard's gate entry observed the epoch
        // ALREADY invalidated (the run lock section invalidated it before the
        // capability call), and only then did the store's invalidateIf run.
        assertEquals(
            listOf(
                "gate-enter(epochCurrent=true)",
                "durable-mutation",
                "run-callback(gateHeld=true)",
                "gate-enter(epochCurrent=false)",
                "durable-mutation",
                "run-callback(gateHeld=true)",
            ),
            recording.events.toList(),
        )
        assertEquals(
            listOf(Triple(exportId, exportId as String?, "Committed")),
            fixture.store.invalidateIfLog.toList(),
        )
        assertFalse(fixture.run.isCurrentEpoch(epoch))
        assertNull(fixture.store.session)
        assertFalse(fixture.run.hasBoundScopeRequest())
    }
}
