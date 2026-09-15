package app.lawnchair.organizer.integration.exchange

import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.personalization.BuiltExport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.exchange.ExchangeContract
import app.lawnchair.organizer.personalization.exchange.ExchangeGenerationGateOutcome
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.ExchangePackageComposer
import app.lawnchair.organizer.personalization.exchange.PackageStructureResult
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205: the exchange flow controller — generation ordering (build →
 * durable save → package), disclosure-cancel invalidation, the AC-13
 * representative scenario (E1 sent → replacement confirmed → E2 generated →
 * E2 cancelled → E1 import = EXPORT_MISMATCH; declining keeps E1 importable),
 * and the AC-11 process recreation simulation (a new controller instance over
 * the same durable store).
 */
class ExchangeFlowControllerTest {

    private val now = 1_000_000L

    private class FakeStore : ExportSessionStore {
        var session: ExportSession? = null
        var failSave = false

        override fun save(session: ExportSession): Boolean {
            if (failSave) return false
            this.session = session
            return true
        }

        override fun load(exportId: String): ExportSession? = session?.takeIf { it.exportId == exportId }

        override fun active(nowEpochMs: Long): ExportSession? = session?.takeUnless { it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
        }
    }

    private inner class Fixture {
        val store: FakeStore = FakeStore()
        val structural: CanonicalStructuralInputs = structuralOf(defaultItems)
        val allocator: SequentialIdAllocator = SequentialIdAllocator()
        var clock: Long = now

        fun newController(): ExchangeFlowController = ExchangeFlowController(
            composeExportInputs = { t -> ExchangeInputResult.ExportReady(exportInputsOf(structural, t)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural) },
            store = store,
            allocator = allocator,
            clock = { clock },
        )
    }

    private val defaultItems: List<CapturedItem> get() = listOf(app("a"), app("b", x = 1))

    private fun app(id: String, x: Int = 0): CapturedItem = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun structuralOf(items: List<CapturedItem>): CanonicalStructuralInputs {
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return CanonicalStructuralInputs(snapshot, targets, emptyMap<ItemId, String?>())
    }

    private fun exportInputsOf(structural: CanonicalStructuralInputs, nowEpochMs: Long): ExportInputs = ExportInputs(
        snapshot = structural.snapshot,
        targets = structural.targets,
        resolvedCategories = structural.resolvedCategories,
        nowEpochMs = nowEpochMs,
    )

    private fun generated(result: ExchangeGenerationResult): ExchangeGenerationResult.Generated = result as ExchangeGenerationResult.Generated

    /**
     * Rebuilds the export exactly as the controller did (same structural
     * inputs, same tier, and the session's own ref/exportId allocation
     * replayed in builder order: refs first, then the exportId).
     */
    private fun rebuild(fixture: Fixture, session: ExportSession): BuiltExport {
        val replay = ReplayAllocator(session)
        return ContextExportBuilder.build(exportInputsOf(fixture.structural, session.createdAtEpochMs), session.tier, replay)
    }

    private class ReplayAllocator(session: ExportSession) : RandomIdAllocator {
        // The builder allocates item refs first (capture order) and the
        // exportId last; the session map preserves the allocation order.
        private val ids = ArrayDeque(session.itemRefs.keys.toList() + listOf(session.exportId))

        override fun newId(): String = ids.removeFirst()
    }

    /** The agent reply for a generated exchange: full coverage, preserve-only. */
    private fun replyFor(fixture: Fixture, session: ExportSession): String {
        val built = rebuild(fixture, session)
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = built.export.items.map { ItemIntent(ref = it.ref, preserve = true) },
        )
        return buildString {
            append("Here is the proposal.\n")
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
            append("\nEnd of proposal.")
        }
    }

    @Test
    fun generationSavesTheSessionAndComposesAParseablePackage() {
        val fixture = Fixture()
        val result = generated(fixture.newController().generate(PrivacyTier.EXTERNAL_REDACTED))
        assertNotNull(fixture.store.session)
        assertTrue(
            ExchangePackageComposer.parsePackageStructure(result.packageText) is PackageStructureResult.Valid,
        )
    }

    @Test
    fun storeSaveFailureSuppressesGenerationFailClosed() {
        val fixture = Fixture()
        fixture.store.failSave = true
        assertEquals(
            ExchangeGenerationResult.SessionStoreFailure,
            fixture.newController().generate(PrivacyTier.EXTERNAL_REDACTED),
        )
        assertNull(fixture.store.session)
    }

    @Test
    fun ac13SentE1ThenConfirmedE2ThenCancelledE2ThenE1ImportIsExportMismatch() {
        val fixture = Fixture()
        val controller = fixture.newController()
        val e1 = generated(controller.generate(PrivacyTier.EXTERNAL_REDACTED))
        val e1Reply = replyFor(fixture, e1.session)

        // The replacement is confirmed: E2 is generated, E1 is invalidated.
        val e2 = generated(controller.generate(PrivacyTier.EXTERNAL_REDACTED))
        assertEquals(e2.session.exportId, fixture.store.active(fixture.clock)?.exportId)

        // Disclosure of the never-sent E2 is cancelled: only E2 is invalidated.
        controller.cancelDisclosure(e2.session)
        assertNull(fixture.store.active(fixture.clock))

        // The late E1 answer is unimportable — typed EXPORT_MISMATCH, zero-write.
        val outcome = controller.importReply(e1Reply) as ExchangeImportOutcome.Pipeline
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch),
            (outcome.result as ExchangeImportResult.Failure).failure,
        )
    }

    @Test
    fun ac13DecliningTheReplacementKeepsE1Importable() {
        val fixture = Fixture()
        val controller = fixture.newController()
        val e1 = generated(controller.generate(PrivacyTier.EXTERNAL_REDACTED))

        // A live session forces the explicit replacement confirmation...
        assertEquals(
            ExchangeGenerationGateOutcome.RequiresConfirmation,
            controller.generationGate(userConfirmation = null),
        )
        // ...and declining means no E2: E1 stays active and importable.
        assertEquals(
            ExchangeGenerationGateOutcome.Aborted,
            controller.generationGate(userConfirmation = false),
        )
        assertEquals(e1.session.exportId, fixture.store.active(fixture.clock)?.exportId)
        val outcome = controller.importReply(replyFor(fixture, e1.session)) as ExchangeImportOutcome.Pipeline
        assertTrue(outcome.result is ExchangeImportResult.Validated)
    }

    @Test
    fun ac11ProcessRecreationResolvesTheSessionThroughTheDurableStore() {
        val fixture = Fixture()
        val e1 = generated(fixture.newController().generate(PrivacyTier.EXTERNAL_REDACTED))
        // Simulated process death: the controller (parser/pipeline state) is
        // discarded; only the durable session store survives.
        val recreated = fixture.newController()
        val outcome = recreated.importReply(replyFor(fixture, e1.session)) as ExchangeImportOutcome.Pipeline
        assertTrue(outcome.result is ExchangeImportResult.Validated)
    }

    @Test
    fun prReviewTransportSuccessThenCloseKeepsTheSessionImportable() {
        val fixture = Fixture()
        val controller = fixture.newController()
        val e1 = generated(controller.generate(PrivacyTier.EXTERNAL_REDACTED))

        // The disclosure binds its own generating session (review P1).
        var disclosure = app.lawnchair.organizer.ui.exchange.ExchangeDisclosureState(
            session = e1.session,
            packageText = e1.packageText,
            tier = PrivacyTier.EXTERNAL_REDACTED,
        )
        assertTrue(disclosure.cancelable)

        // First transport success: the disclosure is sent; closing it must not
        // invalidate the session (the holder's close rule — no invalidate).
        disclosure = disclosure.onTransportResult(ExchangeTransportResult.Success)
        assertTrue(!disclosure.cancelable)
        if (disclosure.cancelable) controller.cancelDisclosure(disclosure.session)

        assertEquals(e1.session.exportId, fixture.store.active(fixture.clock)?.exportId)
        val outcome = controller.importReply(replyFor(fixture, e1.session)) as ExchangeImportOutcome.Pipeline
        assertTrue(outcome.result is ExchangeImportResult.Validated)
    }

    @Test
    fun prReviewPreSendCancelInvalidatesOnlyTheBoundSession() {
        val fixture = Fixture()
        val controller = fixture.newController()
        val e1 = generated(controller.generate(PrivacyTier.EXTERNAL_REDACTED))
        controller.cancelDisclosure(e1.session)
        assertNull(fixture.store.active(fixture.clock))
        // The late reply is unimportable — typed, zero-write.
        val outcome = controller.importReply(replyFor(fixture, e1.session)) as ExchangeImportOutcome.Pipeline
        assertEquals(
            ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch),
            (outcome.result as ExchangeImportResult.Failure).failure,
        )
    }

    @Test
    fun prReviewEnvelopeFailureTakesPriorityOverStructuralNotReady() {
        val fixture = Fixture()
        val controller = ExchangeFlowController(
            composeExportInputs = { t -> ExchangeInputResult.ExportReady(exportInputsOf(fixture.structural, t)) },
            currentStructuralInputs = { ExchangeStructuralResult.NotReady(InputReadinessReason.StaleCandidateSelection) },
            store = fixture.store,
            allocator = SequentialIdAllocator(),
            clock = { fixture.clock },
        )
        val oversizedReply = "x".repeat(1024 * 1024 + 1)
        val outcome = controller.importReply(oversizedReply) as ExchangeImportOutcome.Pipeline
        assertEquals(
            ExchangeImportFailure.Envelope(
                app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure.InputOversize,
            ),
            (outcome.result as ExchangeImportResult.Failure).failure,
        )
        // A valid reply still reports the composition problem (no regression).
        val e1 = generated(
            ExchangeFlowController(
                composeExportInputs = { t -> ExchangeInputResult.ExportReady(exportInputsOf(fixture.structural, t)) },
                currentStructuralInputs = { ExchangeStructuralResult.Ready(fixture.structural) },
                store = fixture.store,
                allocator = SequentialIdAllocator(),
                clock = { fixture.clock },
            ).generate(PrivacyTier.EXTERNAL_REDACTED),
        )
        val notReadyController = ExchangeFlowController(
            composeExportInputs = { t -> ExchangeInputResult.ExportReady(exportInputsOf(fixture.structural, t)) },
            currentStructuralInputs = { ExchangeStructuralResult.NotReady(InputReadinessReason.StaleCandidateSelection) },
            store = fixture.store,
            allocator = SequentialIdAllocator(),
            clock = { fixture.clock },
        )
        val validOutcome = notReadyController.importReply(replyFor(fixture, e1.session))
        assertTrue(validOutcome is ExchangeImportOutcome.InputNotReady)
    }

    @Test
    fun prReviewEncodeFailureCleansUpTheSession() {
        val fixture = Fixture()
        val controller = ExchangeFlowController(
            composeExportInputs = { t -> ExchangeInputResult.ExportReady(exportInputsOf(fixture.structural, t)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(fixture.structural) },
            store = fixture.store,
            allocator = SequentialIdAllocator(),
            clock = { fixture.clock },
            encodeExport = { app.lawnchair.organizer.personalization.ContextExportResult.Failure(app.lawnchair.organizer.personalization.ExportEncodeProblem.Oversize) },
        )
        val result = controller.generate(PrivacyTier.EXTERNAL_REDACTED)
        assertTrue(result is ExchangeGenerationResult.EncodeFailure)
        // No ghost active session: package and durable session stay 1:1.
        assertNull(fixture.store.active(fixture.clock))
    }

    @Test
    fun inputNotReadyIsTypedAndZeroWrite() {
        val fixture = Fixture()
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.NotReady(InputReadinessReason.StaleCandidateSelection) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(fixture.structural) },
            store = fixture.store,
            allocator = SequentialIdAllocator(),
            clock = { fixture.clock },
        )
        assertEquals(
            ExchangeGenerationResult.InputNotReady(InputReadinessReason.StaleCandidateSelection),
            controller.generate(PrivacyTier.EXTERNAL_REDACTED),
        )
        assertNull(fixture.store.session)
    }
}
