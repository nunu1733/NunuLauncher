package app.lawnchair.organizer.ui.exchange

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.integration.CandidateDetectionResult
import app.lawnchair.organizer.integration.DetectedCandidate
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeInputResult
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.DiscardIfResult
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportInvalidationResult
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SequentialIdAllocator
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * AC-8 1:1 mapping (every letter traces to a connected test here/elsewhere
 * or to an explicit unit-oracle pointer):
 *
 * - (a)  connected: `ManualOrganizationPreferencesInstrumentationTest.emptyHomeSelectAllMethodFaceAiArmImportAttachReachesThePreview`
 * - (b)  connected: `ManualOrganizationPreferencesInstrumentationTest.emptyHomeSelectAllMethodFaceThenThePlainArmReachesThePreview`
 * - (c)  connected: `ManualOrganizationPreferencesInstrumentationTest.backWithAnActiveRequestDiscardsItAndReopensTheSelection` (`Committed`)
 *        + `ManualOrganizationPreferencesInstrumentationTest.backDiscardWriteFailedKeepsTheFaceAndShowsTheTypedFailure` (`WriteFailed`)
 * - (d)  connected: `ManualOrganizationPreferencesInstrumentationTest.backFromTheMethodFaceWithoutARequestReopensTheEditableSelection`
 *        (scope ownership survives Back); the legacy-IDLE admission/import-blocking half is unit-oracled by
 *        `ExchangeFlowStateHolderTest.methodFaceImportGateRefusesAForeignRequestAndSurfacesRecreationGuidance`
 * - (e)  connected: `MissingAppSelectionInstrumentationTest.zeroCandidatesContinuesWithoutShowingTheSelectionSurface` (Back = 中断)
 * - (f)  connected: `ManualOrganizationPreferencesInstrumentationTest.onboardingRunNeverShowsTheMethodFace`
 * - (g)  connected: [journeyGSameRunGenerateImportAttachSucceeds]
 * - (h)  connected: [journeyHOwnerlessRunInImportSavesPendingThenRebindCompletes]
 * - (i)  connected: [journeyISameScopeForeignOriginIsBlockedToRecreationGuidance]
 * - (j)  connected: [journeyJInterruptedRunRejectsTheLateCompletionZeroWrite]
 * - (k)  connected: [journeyKReplacementMovesTheAuthorityE1RefusedE2Attachable]
 * - (l)  connected: [journeyLSCrossingRebindAdmissionAndPendingSaveDoesNotDeadlock] (shared with (s))
 * - (m)  connected: [journeyMScopeDiscardDuringClaimedReplacementDropsE2AndReopens]
 * - (n)  unit oracle: `ManualOrganizationRunTest.claimingAGenerationEpochInvalidatesThePreviousClaimZeroWrite`
 *        + `ManualOrganizationRunTest.invalidatingTheGenerationEpochRejectsThePendingCommit` (a stale epoch completion = zero-write)
 * - (o)  connected: [journeyOAfterCrashTheOwnerlessWorldReconnectsOnlyThroughRebind] — the death injection itself is
 *        impractical in-process (the durable save inside the atomic commit is one AtomicFile write; the binding is
 *        process-local RAM), so the connected oracle rebuilds the POST-crash world (durable session, no binding) in a
 *        fresh run+holder pair; the ownerless recovery halves are unit-oracled by
 *        `ExchangeFlowStateHolderTest.ownerlessRunInImportSavesThePendingAndProjectsToImportReviewWithoutAttach`
 * - (p)  unit oracle: `ExchangeFlowStateHolderTest.encodeFailureCleansTheBoundExportThroughTheRunOwnedSeam`
 *        + `ExchangeFlowStateHolderTest.runInImportTheLiveOwnerNeverBoundFencesTheCommittedRecordAway` (no session/binding divergence)
 * - (q)  unit oracle: `ExchangeFlowStateHolderTest.entryKindDerivesFromTheSessionOriginAndStaysStableAcrossReImports`
 *        + `ExchangeFlowStateHolderTest.ownerlessRunInImportSavesThePendingAndProjectsToImportReviewWithoutAttach`
 *        (legacy decode rules; the #375 restore-initial-value halves are pinned by the `ManualOrganizationRunTest`
 *        selection-restore oracles)
 * - (r)  unit oracle: `ManualOrganizationRunTest.commitGeneratedSessionBindsOnlyOnPersistSuccess`
 *        + `ManualOrganizationRunTest.staleCleanupAfterAReplacementIsASupersededNoOpThatKeepsTheCurrentBinding`
 * - (s)  connected: [journeyLSCrossingRebindAdmissionAndPendingSaveDoesNotDeadlock] (shared with (l); every run-owned
 *        seam's bind/clear callback runs inside the gate hold on the lock-owning thread)
 * - (t)  unit oracle: `ManualOrganizationRunTest.staleCleanupAfterAReplacementIsASupersededNoOpThatKeepsTheCurrentBinding`
 * - (u)  connected: [journeyUCleanupWriteFailedKeepsRetryableStateThenReachesTerminal]; unit twin:
 *        `ExchangeFlowStateHolderTest.exportCleanupWriteFailedKeepsTheSessionAndBindingAndRetries`
 * - (v)  unit oracle: `ManualOrganizationRunTest.discardScopeBoundRequestCommittedClearsTheBindingAndInvalidatesTheEpochFirst`
 *        (the epoch invalidation precedes the capability's store call, asserted directly)
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
        var invalidateIfCalls = 0

        override fun save(session: ExportSession): Boolean {
            this.session = session
            return true
        }

        override fun load(exportId: String): ExportSession? = session?.takeIf { it.exportId == exportId }

        override fun active(nowEpochMs: Long): ExportSession? = session?.takeIf { !it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
        }

        override fun invalidateIf(expectedExportId: String): ExportInvalidationResult {
            invalidateIfCalls++
            if (failInvalidateIf) return ExportInvalidationResult.WriteFailed
            if (session?.exportId != expectedExportId) return ExportInvalidationResult.NoMatch
            session = null
            return ExportInvalidationResult.Committed
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
     * generation (entered ⇒ the generation epoch was already claimed).
     */
    private fun newFixture(
        driveToConfirmedScope: Boolean,
        prepareGate: Pair<CountDownLatch, CountDownLatch>? = null,
    ): HolderFixture {
        val gate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val run = ManualOrganizationRun(
            JourneyApplication(),
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run in the method-choice journeys") },
            exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(gate),
        )
        val store = FakeStore()
        val pendingStore = FakePendingStore()
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = false)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural(withCandidate = true)) },
            composeScopedExportInputs = { _, _, _ ->
                prepareGate?.first?.countDown()
                prepareGate?.second?.await(15, TimeUnit.SECONDS)
                ExchangeInputResult.ExportReady(exportInputs(1_000_000L, withCandidate = true))
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
    private fun scopedReplyFor(session: ExportSession): String {
        val built = ContextExportBuilder.build(
            exportInputs(session.createdAtEpochMs, withCandidate = true),
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

        composeRule.runOnUiThread { fixture.holder.continueImport() }
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.Closed }
        awaitRunState(fixture.run) { it is ManualOrganizationRun.State.InputUnavailable }
        assertEquals("継続成功は提案を消費しない", 0, fixture.pendingStore.deleteCalls)
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
        awaitScreenIs(fixture.holder) { it is ExchangeScreen.RecreateRequestGuidance }

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
    fun journeyOAfterCrashTheOwnerlessWorldReconnectsOnlyThroughRebind() {
        // AC-8(o): save後・束縛更新前のprocess death. The death injection is
        // impractical in-process (the durable save inside the atomic commit is
        // one AtomicFile write; the binding is process-local RAM), so the
        // oracle rebuilds the POST-crash world exactly: the durable session
        // survives in the store, and a FRESH run+holder pair (the new process)
        // holds no binding by construction. The reply is then imported from
        // the entry face: the RUN_IN pending is saved ownerless, the
        // ImportReview face (never the direct-attach success state) adopts,
        // and the rebind 1-path admits a fresh run — the direct attach
        // authority is never revived. Unit oracle for the ownerless recovery
        // halves:
        // `ExchangeFlowStateHolderTest.ownerlessRunInImportSavesThePendingAndProjectsToImportReviewWithoutAttach`.
        val gate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val store = FakeStore()
        val pendingStore = FakePendingStore()
        // "Process 1": generates the request; the session is durable.
        val firstRun = ManualOrganizationRun(
            JourneyApplication(),
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
            exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(gate),
        )
        val firstHolder = ExchangeFlowStateHolder(
            controllerFactory = { newJourneyController(store, pendingStore) },
            run = firstRun,
            scope = CoroutineScope(Dispatchers.Main),
            pendingImportStore = pendingStore,
            exchangeMutationGate = gate,
        )
        firstRun.start()
        firstRun.confirmSelection(setOf(scopedCandidate))
        composeRule.runOnUiThread {
            firstHolder.generateScoped(PrivacyTier.EXTERNAL_REDACTED, listOf(scopedCandidate), mapOf(scopedCandidate to "c1"))
        }
        awaitScreenIs(firstHolder) { it is ExchangeScreen.Disclosing }
        val session = store.session!!
        val reply = scopedReplyFor(session)

        // "Process death": a fresh run+holder pair over the SAME durable
        // stores. Binding absent is the correct post-crash state.
        val secondRun = ManualOrganizationRun(
            JourneyApplication(),
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
            exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(gate),
        )
        val secondHolder = ExchangeFlowStateHolder(
            controllerFactory = { newJourneyController(store, pendingStore) },
            run = secondRun,
            scope = CoroutineScope(Dispatchers.Main),
            pendingImportStore = pendingStore,
            exchangeMutationGate = gate,
        )
        assertEquals("Idle", secondRun.state::class.java.simpleName)

        composeRule.runOnUiThread {
            secondHolder.openImport() // the entry/hub hosting shape
            secondHolder.import(reply)
        }
        val review = awaitImportReview(secondHolder)
        assertEquals(ExchangeImportEntryKind.RUN_IN, review.entryKind)
        assertNotNull("the ownerless RUN_IN pending was saved", pendingStore.record)
        assertTrue(secondHolder.screen !is ExchangeScreen.ImportSuccess)

        // The rebind 1-path: a fresh run is admitted; no direct attach.
        composeRule.runOnUiThread { secondHolder.continuePendingImport() }
        awaitScreenIs(secondHolder) { it is ExchangeScreen.Closed }
        awaitRunState(secondRun) { it is ManualOrganizationRun.State.Selecting }
    }

    private fun newJourneyController(store: FakeStore, pendingStore: FakePendingStore): ExchangeFlowController = ExchangeFlowController(
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
    fun journeyLSCrossingRebindAdmissionAndPendingSaveDoesNotDeadlock() {
        // AC-8(l)+(s): the rebind admission holds the run lock and waits on
        // the REAL process-wide gate while a live-owner pending save (the
        // run-owned transaction) waits for the run lock — the crossing
        // completes for BOTH sides with the lock order run lock → gate, and
        // the save's gate-held callback ran after the admission's lock section
        // was over (no gate→run-lock acquisition exists).
        val gate = app.lawnchair.organizer.personalization.exchange.ExchangeMutationGate()
        val run = ManualOrganizationRun(
            JourneyApplication(),
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
            exchangeGateTransaction = ManualOrganizationRun.gateHeldExchangeGateTransaction(gate),
        )
        val cHolding = CountDownLatch(1)
        val cRelease = CountDownLatch(1)
        thread(name = "gate-holder") {
            gate.withGate {
                cHolding.countDown()
                cRelease.await(15, TimeUnit.SECONDS)
            }
        }
        assertTrue(cHolding.await(10, TimeUnit.SECONDS))

        // Side A — the rebind admission: inside the run's lock section (the
        // anchor contract), it waits on the barrier and then takes the gate
        // (the anchor's fresh-verification shape) while STILL holding the
        // run lock.
        val aAnchorEntered = CountDownLatch(1)
        val aCanProceed = CountDownLatch(1)
        val aGateEntered = CountDownLatch(1)
        var aOutcome: ManualOrganizationRun.StartOutcome? = null
        val threadA = thread(name = "rebind-admission") {
            aOutcome = run.start(
                app.lawnchair.organizer.diagnostics.model.Trigger.MANUAL_FULL,
                intent = null,
                admissionAnchor = { complete ->
                    aAnchorEntered.countDown() // A now holds the run lock
                    aCanProceed.await(15, TimeUnit.SECONDS)
                    gate.withGate {
                        aGateEntered.countDown()
                        complete()
                    }
                    true
                },
            )
        }
        assertTrue(aAnchorEntered.await(10, TimeUnit.SECONDS))

        // Side B — the live-owner pending save (the run-owned transaction):
        // waits for the run lock BEFORE it can reach the gate.
        val bDone = CountDownLatch(1)
        var bOwned: Boolean? = null
        var bSaveRan = false
        val threadB = thread(name = "pending-save") {
            run.savePendingImportForLiveOwner(
                RunId("ffffffffffffffffffffffffffffffff"),
                "export-x",
                save = {
                    bSaveRan = true
                    "saved"
                },
                onGateHeld = { _, owned ->
                    bOwned = owned
                    bDone.countDown()
                    "settled"
                },
            )
        }
        // Deterministic setup: B has actually blocked (on the run lock) before
        // the gate is released — a bounded thread-state poll, not
        // sleep-based synchronization.
        val blockedDeadline = System.currentTimeMillis() + 10_000
        while (threadB.state != Thread.State.BLOCKED && System.currentTimeMillis() < blockedDeadline) {
            Thread.sleep(10)
        }
        assertEquals("B must be blocked on the run lock (lock before gate)", Thread.State.BLOCKED, threadB.state)

        // Release the gate: A's anchor proceeds (B never queued on the gate),
        // the admission completes, the lock is released, and B finishes.
        cRelease.countDown()
        assertTrue("the pending save must complete (no deadlock)", bDone.await(15, TimeUnit.SECONDS))
        threadA.join(15_000)
        threadB.join(15_000)
        assertTrue("the admission started the run", aOutcome is ManualOrganizationRun.StartOutcome.Started)
        assertEquals("A's gate-held anchor section ran", 0L, aGateEntered.count)
        assertTrue("the save's durable mutation ran", bSaveRan)
        assertEquals("no live owner binds a foreign export", false, bOwned)
    }
}
