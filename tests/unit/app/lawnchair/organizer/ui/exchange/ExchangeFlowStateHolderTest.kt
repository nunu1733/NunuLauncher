package app.lawnchair.organizer.ui.exchange

import android.net.Uri
import app.lawnchair.organizer.integration.exchange.ClipboardImportRead
import app.lawnchair.organizer.integration.exchange.ClipboardImportTransport
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.integration.exchange.ExchangeInputResult
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.integration.exchange.ExchangeTransportFailure
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeRead
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ContextExportContract
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
import app.lawnchair.organizer.personalization.exchange.ExchangeEnvelopeFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportFailure
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
import app.lawnchair.organizer.personalization.exchange.ImportNormalizationFailure
import app.lawnchair.organizer.personalization.exchange.RecognizedImportFraming
import app.lawnchair.organizer.personalization.exchange.RecognizedImportInfo
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
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #205 (ChatGPT PR review round 3 P1): holder-level regression test for
 * the delayed-file-write race. The holder's transport start path must land
 * the in-flight marker in the disclosure state (suspending the pre-send
 * cancel), a cancel racing an in-flight write must never invalidate the
 * session, and a cancel that was accepted must refuse later transports.
 *
 * The holder only calls `run.start` for validated imports, so the run double
 * here is a reference-holder whose `start` outcome is asserted indirectly via
 * the import result; `ManualOrganizationRun` itself is exercised by
 * `ManualOrganizationRunTest`.
 */
class ExchangeFlowStateHolderTest {

    private class FakeStore : ExportSessionStore {
        var session: ExportSession? = null

        /** Issue #332: receipt tests observe that the common import path ran. */
        var loadCalls = 0

        /** Issue #372 review: replacement-identity oracle counts real saves. */
        var saveCalls = 0

        /**
         * Issue #372 review: a controllable gate that parks the invalidate
         * BEFORE it clears the session, so the settle-pending boundary is
         * deterministic in the busy-close regression.
         */
        @Volatile
        var invalidateGate: CountDownLatch? = null

        @Volatile
        var invalidateEntered = false

        override fun save(session: ExportSession): Boolean {
            saveCalls++
            this.session = session
            return true
        }

        /** Issue #328 review: lets a test hold validation at the session load. */
        var loadGate: CountDownLatch? = null

        override fun load(exportId: String): ExportSession? {
            loadCalls++
            loadGate?.await(5, TimeUnit.SECONDS)
            return session?.takeIf { it.exportId == exportId }
        }

        override fun active(nowEpochMs: Long): ExportSession? = session?.takeIf { !it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) {
                invalidateEntered = true
                invalidateGate?.await(5, TimeUnit.SECONDS)
                session = null
            }
        }
    }

    private fun app(id: String, x: Int = 0): CapturedItem = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun structural(): CanonicalStructuralInputs {
        val items = listOf(app("a"), app("b", x = 1))
        val snapshot = LayoutSnapshot(
            RevisionId("rev"),
            DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT),
            listOf(Page(PageId("p0"), PageOrder(0))),
            items,
        )
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return CanonicalStructuralInputs(snapshot, targets, emptyMap())
    }

    private fun exportInputs(nowEpochMs: Long): ExportInputs = ExportInputs(
        snapshot = structural().snapshot,
        targets = structural().targets,
        resolvedIdentities = emptyMap(),
        nowEpochMs = nowEpochMs,
    )

    private class ReplayAllocator(session: ExportSession) : RandomIdAllocator {
        private val ids = ArrayDeque(session.itemRefs.keys.toList() + listOf(session.exportId))

        override fun newId(): String = ids.removeFirst()
    }

    private fun replyFor(session: ExportSession): String {
        val built = ContextExportBuilder.build(
            exportInputs(session.createdAtEpochMs),
            session.tier,
            ReplayAllocator(session),
        )
        return markedReply(built)
    }

    /** The run-in (scope-composed) reply: rebuilt from the scoped inputs so
     * the export id and refs replay exactly as the scoped session stored. */
    private fun scopedReplyFor(session: ExportSession): String {
        val built = ContextExportBuilder.build(
            scopedExportInputs(session.createdAtEpochMs),
            session.tier,
            ReplayAllocator(session),
        )
        return markedReply(built)
    }

    private fun markedReply(built: app.lawnchair.organizer.personalization.BuiltExport): String {
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            // Mobility candidates must not assert a keep-position wish
            // (validator: mobility contradiction); they stay bare.
            itemIntents = built.export.items.map { item ->
                ItemIntent(
                    ref = item.ref,
                    preserve = if (item.mobility == app.lawnchair.organizer.personalization.Mobility.CANDIDATE) null else true,
                )
            },
        )
        return buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
    }

    private fun newHolder(store: FakeStore, now: Long): Pair<ExchangeFlowStateHolder, ExchangeFlowController> {
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(now)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural()) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { now },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = RecordingRun.get(),
            scope = CoroutineScope(Dispatchers.IO),
            settleDispatcher = Dispatchers.IO,
        )
        return holder to controller
    }

    /**
     * Issue #332: receipt tests assert the TERMINAL display states (the
     * parse-first outcome screen). The holder's display hop is injectable
     * (`uiDispatcher`), so the tests run it on IO and reach
     * `ExchangeScreen.ImportOutcomeScreen` deterministically; the tail hop of
     * a refused/busy import never occurs in these fixtures.
     */
    private fun newHolderWithRecordedScope(store: FakeStore, now: Long): Triple<ExchangeFlowStateHolder, ExchangeFlowController, MutableList<Throwable>> {
        val unhandled = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                synchronized(unhandled) { unhandled.add(throwable) }
            },
        )
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(now)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural()) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { now },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = RecordingRun.get(),
            scope = scope,
            settleDispatcher = Dispatchers.IO,
            uiDispatcher = Dispatchers.IO,
        )
        return Triple(holder, controller, unhandled)
    }

    /** A marker-framed, decodable reply whose export matches no live session. */
    private fun unmatchedMarkedReply(): String {
        val intent = PersonalizedIntentV1(exportId = "no-such-export", itemIntents = emptyList())
        return buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
    }

    private fun awaitStoreLoad(store: FakeStore, expected: Int) {
        var waited = 0
        while (store.loadCalls < expected && waited < 5_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals("the common import path must have started", expected, store.loadCalls)
    }

    private fun screenStateField(holder: ExchangeFlowStateHolder): androidx.compose.runtime.MutableState<ExchangeScreen> {
        val field = holder::class.java.getDeclaredField("screenState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(holder) as androidx.compose.runtime.MutableState<ExchangeScreen>
    }

    private fun setScreenToDisclosing(holder: ExchangeFlowStateHolder, state: ExchangeDisclosureState) {
        screenStateField(holder).value = ExchangeScreen.Disclosing(state)
    }

    private fun currentDisclosureOrNull(holder: ExchangeFlowStateHolder): ExchangeDisclosureState? = (screenStateField(holder).value as? ExchangeScreen.Disclosing)?.state

    private fun currentDisclosureState(holder: ExchangeFlowStateHolder): ExchangeDisclosureState = currentDisclosureOrNull(holder) ?: error("disclosure already closed")

    @Test
    fun delayedWriteCancelAttemptWriteSuccessThenReplyImports() {
        val store = FakeStore()
        val (holder, controller) = newHolder(store, now = 1_000_000L)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        // The (simulated) file write starts: the in-flight marker goes through
        // the holder's real start path and must land in the state.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeThread = Thread {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            holder.onTransportResult(ExchangeTransportResult.Success)
        }
        holder.startTransport { ExchangeTransportResult.InFlight }
        assertTrue("the InFlight marker must reach the disclosure state", currentDisclosureState(holder).transportInFlight)
        assertFalse("in-flight must suspend the pre-send cancel", currentDisclosureState(holder).cancelable)
        writeThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // A cancel racing the write: not cancelable → closeDisclosure only
        // closes the screen; the session survives.
        holder.closeDisclosure()
        assertNotNull("session must survive a cancel attempted during the write", store.session)
        // Issue #372 (implementation review): the busy unsent disclosure now
        // REFUSES to close — the face stays until the write settles, so the
        // holder's composition-owned scope cannot be disposed mid-settle.
        assertNotNull("the busy face refuses to close while the write is in flight", currentDisclosureOrNull(holder))

        // The write settles on the SAME disclosure: it becomes sent (the
        // package may already be outside the device) and the request survives.
        release.countDown()
        writeThread.join(5_000)
        assertTrue("the settled write marks the disclosure sent", currentDisclosureState(holder).sent)
        assertNotNull("the session survives the delivered write", store.session)

        // The reply imports against the surviving session.
        val outcome = controller.importReply(replyFor(generated.session))
        assertTrue((outcome as ExchangeImportOutcome.Pipeline).result is ExchangeImportResult.Validated)
    }

    @Test
    fun aSecondWriteFileWhileOneIsInFlightIsRefused() {
        // Review round 4 P1: two concurrent file writes must not be possible —
        // the second writeFile must be refused before any IO, so a first
        // write's failure cannot clear the busy flag while the second write is
        // still landing outside the device.
        val store = FakeStore()
        val (holder, controller) = newHolder(store, now = 1_000_000L)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        // Write A: a gated write that blocks in the transport.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeAThread = Thread {
            val transport = FileExchangeTransport(throwNoContextForTest())
            transport.writeOverride = { _, _ ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ExchangeTransportResult.Failure(
                    ExchangeTransportFailure.FILE_WRITE_FAILED,
                )
            }
            holder.writeFile(transport, generated.packageText, anyUri())
        }
        writeAThread.start()
        val enteredOk = entered.await(5, TimeUnit.SECONDS)
        if (!enteredOk) {
            println("DEBUG screen=" + screenStateField(holder).value + " state=" + (screenStateField(holder).value as? ExchangeScreen.Disclosing)?.state)
        }
        assertTrue("write A never entered transport: " + screenStateField(holder).value, enteredOk)
        assertTrue(currentDisclosureOrNull(holder)!!.transportInFlight)

        // Write B while A is in flight: refused — it never runs its transport.
        var bRan = false
        val transportB = FileExchangeTransport(throwNoContextForTest())
        transportB.writeOverride = { _, _ ->
            bRan = true
            ExchangeTransportResult.Success
        }
        holder.writeFile(transportB, generated.packageText, anyUri())
        Thread.sleep(200)
        assertFalse("the second writeFile must be refused while the first is in flight", bRan)
        assertTrue(currentDisclosureOrNull(holder)!!.transportInFlight)

        // A fails and settles: the failure must not be delivered after the
        // disclosure state moved on in a way that lets a late B run — B never
        // started, so the state simply returns to cancelable. (The settle hops
        // through the settle dispatcher; poll briefly for it to land.)
        release.countDown()
        writeAThread.join(5_000)
        var settled = currentDisclosureOrNull(holder)
        var waited = 0
        while (settled != null && settled.transportInFlight && waited < 5_000) {
            Thread.sleep(50)
            waited += 50
            settled = currentDisclosureOrNull(holder)
        }
        assertNotNull(settled)
        assertFalse(settled!!.transportInFlight)
        assertTrue(settled.cancelable)
        assertFalse(bRan)
        assertNotNull(store.session)
    }

    @Test
    fun writeFileThroughTheRealPathRacingCancelKeepsTheSession() {
        // Review round 4 P1 (Required): the delayed-write race test must go
        // through the real `ExchangeFlowStateHolder.writeFile()` start path.
        val store = FakeStore()
        val (holder, controller) = newHolder(store, now = 1_000_000L)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeThread = Thread {
            val transport = FileExchangeTransport(throwNoContextForTest())
            transport.writeOverride = { _, _ ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ExchangeTransportResult.Success
            }
            holder.writeFile(transport, generated.packageText, anyUri())
        }
        writeThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue("the real writeFile must mark the disclosure in flight", currentDisclosureOrNull(holder)!!.transportInFlight)

        // A cancel racing the write is refused (not cancelable): #372 (review)
        // goes further — the busy face refuses to close entirely, so the
        // settle happens on the same disclosure and the session survives.
        holder.closeDisclosure()
        assertNotNull("session must survive a cancel attempted during the write", store.session)
        assertNotNull(
            "the racing cancel cannot close the busy disclosure",
            currentDisclosureOrNull(holder),
        )

        // The write then succeeds on the SAME disclosure: sent (delivered
        // outside the device), no resurrection problem, session intact. The
        // settle hops through the holder scope, so poll for it.
        release.countDown()
        writeThread.join(5_000)
        awaitScreen(holder) { currentDisclosureOrNull(holder)?.sent == true }
        assertNotNull(store.session)
        val outcome = controller.importReply(replyFor(generated.session))
        assertTrue((outcome as ExchangeImportOutcome.Pipeline).result is ExchangeImportResult.Validated)
    }

    @Test
    fun aCancelAcceptedBeforeTheWriteStartsRefusesTransports() {
        val store = FakeStore()
        val (holder, controller) = newHolder(store, now = 1_000_000L)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        // Pre-send cancel: accepted, terminal (cancelling), invalidates the bound session.
        holder.closeDisclosure()
        val cancelling = currentDisclosureState(holder)
        assertTrue(cancelling.cancelling)
        assertFalse(cancelling.cancelable)
        assertFalse(cancelling.transportAllowed)

        // A transport racing after the accepted cancel is refused: no settle,
        // no sent, and the cancel still lands.
        holder.startTransport { ExchangeTransportResult.Success }
        assertFalse(currentDisclosureState(holder).sent)
        holder.onTransportResult(ExchangeTransportResult.Success)
        assertFalse(currentDisclosureState(holder).sent)
        // The accepted cancel invalidates asynchronously on IO; yield to it.
        Thread.sleep(200)
        Thread.yield()
        Thread.sleep(200)
        assertNull(store.session)
    }

    private fun anyUri(): Uri? = null

    // the write hook never dereferences it
    @Suppress("DEPRECATION")
    private fun throwNoContextForTest(): android.content.Context = android.content.ContextWrapper(null)

    @Test
    fun lateResultFromAnOldWriteCannotTouchANewerDisclosure() {
        // Review round 5 P1 (ABA): A delayed write -> racing close -> new
        // disclosure B -> B delayed write -> A's late settle. A's result must
        // be dropped: B stays in flight until its own result returns.
        val store = FakeStore()
        val now = 1_000_000L
        val (holder, controller) = newHolder(store, now = now)
        val disclosureA = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(disclosureA.session, disclosureA.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        // A's delayed write starts; its Failure is held back until B is live —
        // the ABA ordering (close -> B generated -> B in flight -> A settles)
        // must be deterministic, not scheduler-dependent.
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val writeAThread = Thread {
            val transport = FileExchangeTransport(throwNoContextForTest())
            transport.writeOverride = { _, _ ->
                aEntered.countDown()
                releaseA.await(5, TimeUnit.SECONDS)
                ExchangeTransportResult.Failure(ExchangeTransportFailure.FILE_WRITE_FAILED)
            }
            holder.writeFile(transport, disclosureA.packageText, anyUri())
        }
        writeAThread.start()
        assertTrue(aEntered.await(5, TimeUnit.SECONDS))
        assertTrue(currentDisclosureOrNull(holder)!!.transportInFlight)

        // The racing close is refused (issue #372 review): the busy unsent
        // face cannot be closed out of the write's way — A's session stays
        // intact and the face remains until the test re-anchors the display.
        holder.closeDisclosure()
        assertNotNull(currentDisclosureOrNull(holder))
        assertNotNull(store.session)

        // A new flow generates disclosure B (single-active-session replaces A)
        // while A's Failure is still held back.
        val b = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(b.session, b.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )
        assertEquals(b.session.exportId, store.active(now.toLong())?.exportId)

        // B's delayed write starts and is confirmed in flight — only now is
        // A's Failure released, so the A settle lands while B is live.
        val bEntered = CountDownLatch(1)
        val bSettled = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val writeBThread = Thread {
            val transport = FileExchangeTransport(throwNoContextForTest())
            transport.writeOverride = { _, _ ->
                bEntered.countDown()
                releaseB.await(5, TimeUnit.SECONDS)
                ExchangeTransportResult.Success
            }
            holder.writeFile(transport, b.packageText, anyUri())
        }
        writeBThread.start()
        assertTrue(bEntered.await(5, TimeUnit.SECONDS))
        val bLive = currentDisclosureOrNull(holder)
        assertNotNull(bLive)
        assertTrue("B is in flight", bLive!!.transportInFlight)
        assertFalse("B is not sent", bLive.sent)

        // NOW release A's Failure. A settle-observation latch waits for A's
        // settle attempt to be PROCESSED (not a fixed sleep): the holder's
        // onSettleObserved seam fires after the settle decision, applied=false
        // for the dropped A result.
        val aSettleObserved = CountDownLatch(1)
        val observed = arrayOf<Pair<String, Boolean>?>(null)
        holder.onSettleObserved = { disclosure, applied ->
            synchronized(observed) {
                if (observed[0] == null) {
                    observed[0] = disclosure.session.exportId to applied
                }
            }
            aSettleObserved.countDown()
        }
        releaseA.countDown()
        assertTrue("A's settle attempt must be processed", aSettleObserved.await(5, TimeUnit.SECONDS))
        val aObservation = synchronized(observed) { observed[0] }
        assertNotNull(aObservation)
        assertEquals("the processed settle is A's generation", disclosureA.session.exportId, aObservation!!.first)
        assertFalse("A's settle was applied=false (dropped)", aObservation.second)

        // With A's settle provably processed, B must remain in flight/unsent.
        val afterA = currentDisclosureOrNull(holder)
        assertNotNull(afterA)
        assertTrue("B must still be in flight after A's dropped settle", afterA!!.transportInFlight)
        assertFalse("A's late failure must not mark B sent", afterA.sent)

        // B's own success settles: sent, non-cancelable, B's session active.
        val bSettleObserved = CountDownLatch(1)
        var bApplied = false
        holder.onSettleObserved = { disclosure, applied ->
            if (disclosure.session.exportId == b.session.exportId) {
                bApplied = applied
                bSettleObserved.countDown()
            }
        }
        releaseB.countDown()
        assertTrue("B's settle attempt must be processed", bSettleObserved.await(5, TimeUnit.SECONDS))
        assertTrue("B's own success was applied", bApplied)
        writeBThread.join(5_000)
        val sentB = currentDisclosureOrNull(holder)
        assertNotNull(sentB)
        assertTrue(sentB!!.sent)
        assertFalse(sentB.cancelable)
        assertEquals(b.session.exportId, store.active(now.toLong())?.exportId)
    }

    @Test
    fun normalizationFailuresReachTheImportOutcomeScreen() {
        // Issue #329: the two new typed normalizer failures must surface
        // through the same controller outcome the failure screen consumes
        // (`ExchangeScreen.ImportOutcomeScreen` maps the 19-kind exhaustive
        // `when`). The holder's `import` failure branch routes any
        // non-validated pipeline outcome there; this asserts the typed
        // failures survive the controller seam, and `holder.import` itself
        // pins `Dispatchers.Main` (not injectable, so the display hop is
        // exercised by the compile-time exhaustive mapping instead).
        val store = FakeStore()
        val (_, controller) = newHolder(store, now = 1_000_000L)
        val ambiguous = controller.importReply("```json\n{}\n```\nprose\n```json\n{}\n```") as ExchangeImportOutcome.Pipeline
        assertEquals(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.AmbiguousBlocks),
            (ambiguous.result as ExchangeImportResult.Failure).failure,
        )
        val unrecognized = controller.importReply("no recognizable shape at all") as ExchangeImportOutcome.Pipeline
        assertEquals(
            ExchangeImportFailure.Normalization(ImportNormalizationFailure.UnrecognizedFormat),
            (unrecognized.result as ExchangeImportResult.Failure).failure,
        )
    }

    // ---- Issue #332: clipboard/file-first receipt (spec AC-1/AC-2/AC-5/AC-6) ----

    @Test
    fun clipboardEmptyReadKeepsTheScreenAndSetsTheTypedStatus() {
        val store = FakeStore()
        val (holder, _) = newHolder(store, now = 1_000_000L)
        holder.openImport()
        holder.importFromClipboard(
            ClipboardImportTransport(throwNoContextForTest()).apply {
                readOverride = { ClipboardImportRead.EmptyOrUnavailable }
            },
        )
        assertEquals(ExchangeStatus.Kind.CLIPBOARD_EMPTY, holder.status!!.kind)
        val importing = holder.screen as ExchangeScreen.Importing
        assertEquals("", importing.replyText)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun clipboardNonTextReadKeepsTheScreenAndSetsTheTypedStatus() {
        val store = FakeStore()
        val (holder, _) = newHolder(store, now = 1_000_000L)
        holder.openImport()
        holder.importFromClipboard(
            ClipboardImportTransport(throwNoContextForTest()).apply {
                readOverride = { ClipboardImportRead.NotText }
            },
        )
        assertEquals(ExchangeStatus.Kind.CLIPBOARD_NOT_TEXT, holder.status!!.kind)
        assertEquals("", (holder.screen as ExchangeScreen.Importing).replyText)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun oversizedClipboardTextIsNotAdoptedAndReportsInputOversize() {
        val store = FakeStore()
        val (holder, _) = newHolder(store, now = 1_000_000L)
        holder.openImport()
        val oversized = "x".repeat(ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES + 1)
        holder.importFromClipboard(
            ClipboardImportTransport(throwNoContextForTest()).apply {
                readOverride = { ClipboardImportRead.Text(oversized) }
            },
        )
        // The transport read succeeds (no bound owned there); the shared
        // receipt gate settles the failure and the editor state is untouched.
        assertEquals(ExchangeStatus.Kind.INPUT_OVERSIZE, holder.status!!.kind)
        assertEquals("", (holder.screen as ExchangeScreen.Importing).replyText)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun clipboardTextReceiptReplacesTheEditorAndRunsTheCommonImportPath() {
        val store = FakeStore()
        val (holder, _, unhandled) = newHolderWithRecordedScope(store, now = 1_000_000L)
        holder.openImport()
        val reply = unmatchedMarkedReply()
        holder.importFromClipboard(
            ClipboardImportTransport(throwNoContextForTest()).apply {
                readOverride = { ClipboardImportRead.Text(reply) }
            },
        )
        // One operation: the editor content is replaced and the common import
        // path starts (store.load runs after the pipeline decode) without any
        // further "import" press.
        val importing = holder.screen as ExchangeScreen.Importing
        assertEquals(reply, importing.replyText)
        awaitStoreLoad(store, expected = 1)
        assertEquals(1, store.loadCalls)
    }

    @Test
    fun fileTextReceiptRunsTheSameCommonImportPathInOneOperation() {
        val store = FakeStore()
        // The recorded scope swallows the import tail's Main hop (no Main on
        // the JVM); the pre-Main effects are what this test asserts.
        val (holder, _, _) = newHolderWithRecordedScope(store, now = 1_000_000L)
        holder.openImport()
        val reply = unmatchedMarkedReply()
        // The typed SAF-callback branch: the read result flows into the SAME
        // receipt helper as the clipboard — editor replaced and the common
        // import path started, no extra "import" press (AC-2/AC-5).
        holder.onFileRead(FileExchangeRead.Text(reply))
        val importing = holder.screen as ExchangeScreen.Importing
        assertEquals(reply, importing.replyText)
        awaitStoreLoad(store, expected = 1)
    }

    @Test
    fun fileReadFailureSetsTheTypedFileStatusAndKeepsTheEditor() {
        val store = FakeStore()
        val (holder, _) = newHolder(store, now = 1_000_000L)
        holder.openImport()
        holder.onFileRead(FileExchangeRead.Failure)
        assertEquals(ExchangeStatus.Kind.FILE_READ_FAILED, holder.status!!.kind)
        assertEquals("", (holder.screen as ExchangeScreen.Importing).replyText)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun clipboardReceiptReachesTheParseFirstOutcomeWithEphemeralRawDiscardedOnRetry() {
        // AC-1 + AC-7: one clipboard press lands on the parse-first outcome
        // surface carrying the reply as the collapsed raw detail; the reply
        // lives ONLY in that surface's field — retry (openImport) and close
        // replace the screen state and thereby discard it.
        val store = FakeStore()
        val (holder, _, _) = newHolderWithRecordedScope(store, now = 1_000_000L)
        holder.openImport()
        val reply = unmatchedMarkedReply()
        holder.importFromClipboard(
            ClipboardImportTransport(throwNoContextForTest()).apply {
                readOverride = { ClipboardImportRead.Text(reply) }
            },
        )
        awaitScreenOutcome(holder)
        val outcomeScreen = holder.screen as ExchangeScreen.ImportOutcomeScreen
        val result = (outcomeScreen.outcome as ExchangeImportOutcome.Pipeline).result as ExchangeImportResult.Failure
        assertEquals(ExchangeImportFailure.Contract(IntentValidationFailure.ExportMismatch), result.failure)
        assertEquals("the reply is retained only on the outcome surface", reply, outcomeScreen.rawText)

        // Retry: back to an empty editor; the raw text is gone with the
        // replaced surface state.
        holder.openImport()
        assertTrue(holder.screen is ExchangeScreen.Importing)
        assertEquals("", (holder.screen as ExchangeScreen.Importing).replyText)

        holder.close()
        assertTrue(holder.screen is ExchangeScreen.Closed)
    }

    @Test
    fun fileReceiptReachesTheParseFirstOutcomeInOneOperation() {
        // AC-2: the file pick lands on the parse-first outcome display with
        // no additional import press.
        val store = FakeStore()
        val (holder, _, _) = newHolderWithRecordedScope(store, now = 1_000_000L)
        holder.openImport()
        val reply = unmatchedMarkedReply()
        holder.onFileRead(FileExchangeRead.Text(reply))
        awaitScreenOutcome(holder)
        val outcomeScreen = holder.screen as ExchangeScreen.ImportOutcomeScreen
        assertEquals(reply, outcomeScreen.rawText)
    }

    @Test
    fun inputNotReadyOutcomeCarriesTheRecognizedMetadata() {
        // Required-fix (spec D-6): a structural NotReady settles AFTER the
        // decode, so the outcome keeps the framing/version/entry count.
        val store = FakeStore()
        val now = 1_000_000L
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(now)) },
            currentStructuralInputs = {
                ExchangeStructuralResult.NotReady(
                    app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending,
                )
            },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { now },
        )
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        val outcome = controller.importReply(replyFor(generated.session)) as ExchangeImportOutcome.InputNotReady
        val info = outcome.recognized!!
        assertEquals(RecognizedImportFraming.MARKER, info.framing)
        assertEquals(ContextExportContract.INTENT_SCHEMA_VERSION, info.intentSchemaVersion)
        assertEquals(2, info.authoredEntryCount) // the fixture export scope is a + b
    }

    private fun awaitScreenOutcome(holder: ExchangeFlowStateHolder) {
        var waited = 0
        while (holder.screen !is ExchangeScreen.ImportOutcomeScreen && waited < 5_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertTrue("the parse-first outcome surface must be reached", holder.screen is ExchangeScreen.ImportOutcomeScreen)
    }

    @Test
    fun oversizedFileTextIsRejectedByTheSharedEnvelopeGate() {
        val store = FakeStore()
        val (holder, _) = newHolder(store, now = 1_000_000L)
        holder.openImport()
        val oversized = "x".repeat(ExchangeContract.MAX_EXCHANGE_IMPORT_BYTES + 1)
        holder.onFileRead(FileExchangeRead.Text(oversized))
        assertEquals(ExchangeStatus.Kind.INPUT_OVERSIZE, holder.status!!.kind)
        assertEquals("", (holder.screen as ExchangeScreen.Importing).replyText)
        assertEquals(0, store.loadCalls)
    }

    @Test
    fun fileReadFailureResolvesToTheDedicatedImportGuidance() {
        // AC-6 regression guard (Phase2 review R1): the FILE_READ_FAILED
        // status must resolve to the dedicated import/read guidance — never
        // back to the export/send copy — and the sibling import-source
        // failures keep their own strings.
        assertEquals(
            com.android.launcher3.R.string.exchange_status_file_read_failed,
            exchangeStatusTextResource(ExchangeStatus.Kind.FILE_READ_FAILED),
        )
        assertTrue(
            exchangeStatusTextResource(ExchangeStatus.Kind.FILE_READ_FAILED) !=
                com.android.launcher3.R.string.exchange_transport_file_failed,
        )
        assertEquals(
            com.android.launcher3.R.string.exchange_status_clipboard_empty,
            exchangeStatusTextResource(ExchangeStatus.Kind.CLIPBOARD_EMPTY),
        )
        assertEquals(
            com.android.launcher3.R.string.exchange_status_clipboard_not_text,
            exchangeStatusTextResource(ExchangeStatus.Kind.CLIPBOARD_NOT_TEXT),
        )
    }

    @Test
    fun fileReadFailureGuidanceExistsInBothLocales() {
        // AC-6: the dedicated guidance is the ja正本 AND the en translation —
        // both resource files must declare it (locale resolution guard).
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            assertTrue(
                "exchange_status_file_read_failed must exist in $localeDir",
                xml.contains("name=\"exchange_status_file_read_failed\""),
            )
        }
    }

    private fun lawnchairStringsXml(localeDir: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = File(dir, "lawnchair/res/$localeDir/strings.xml")
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("lawnchair strings.xml not found for $localeDir from ${System.getProperty("user.dir")}")
    }

    @Test
    fun displayInfoProjectionMapsOnlyTheRecognizedMetadata() {
        // Issue #332 (spec D-5): the pure projection surfaces only the
        // seam-derived recognition metadata; failures without it display
        // nothing extra.
        assertEquals(ExchangeImportDisplayInfo(), exchangeImportDisplayInfo(result = null))
        val bare = ExchangeImportResult.Failure(ExchangeImportFailure.Envelope(ExchangeEnvelopeFailure.FramingMissing))
        assertEquals(ExchangeImportDisplayInfo(), exchangeImportDisplayInfo(bare))
        val full = ExchangeImportResult.Failure(
            ExchangeImportFailure.Contract(IntentValidationFailure.SessionExpired),
            RecognizedImportInfo(RecognizedImportFraming.MARKER, ContextExportContract.INTENT_SCHEMA_VERSION, 2),
        )
        assertEquals(
            ExchangeImportDisplayInfo(RecognizedImportFraming.MARKER, ContextExportContract.INTENT_SCHEMA_VERSION, 2),
            exchangeImportDisplayInfo(full),
        )
        // The recognized overload (InputNotReady path) maps the same facts.
        assertEquals(
            ExchangeImportDisplayInfo(RecognizedImportFraming.MARKER, ContextExportContract.INTENT_SCHEMA_VERSION, 2),
            exchangeImportDisplayInfo(RecognizedImportInfo(RecognizedImportFraming.MARKER, ContextExportContract.INTENT_SCHEMA_VERSION, 2)),
        )
    }

    // ------------------------------------------------------------------
    // Issue #328: the import success state, its attempt anchor and the
    // explicit continuation/discard lifecycle (spec 328 AC-1..AC-5).
    // ------------------------------------------------------------------

    /** Issue #328: a run double that reaches the selection surface. */
    private class ExchangeRunApplication(
        private val detectionReady: Boolean = true,
    ) : app.lawnchair.organizer.ui.ManualOrganizationApplication {
        var composeFullCalls = 0
        var detectionCalls = 0
        private var runIdCounter = 0

        override val diagnostics = object : app.lawnchair.organizer.diagnostics.DiagnosticsPort {
            override fun emit(event: app.lawnchair.organizer.diagnostics.model.RunEvent) = Unit
            override fun snapshot() = emptyList<app.lawnchair.organizer.diagnostics.model.RunEvent>()
        }

        override fun newRunId() = app.lawnchair.organizer.application.public.RunId(java.lang.String.format("%032x", ++runIdCounter))

        override fun detectMissingAppCandidates(): app.lawnchair.organizer.integration.CandidateDetectionResult {
            detectionCalls++
            return if (detectionReady) {
                app.lawnchair.organizer.integration.CandidateDetectionResult.Ready(
                    listOf(
                        app.lawnchair.organizer.integration.DetectedCandidate(
                            target = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
                                ComponentKey("com.example.c1"),
                                ProfileId("personal"),
                            ),
                            label = "c1",
                            availability = Availability.AVAILABLE,
                        ),
                    ),
                )
            } else {
                app.lawnchair.organizer.integration.CandidateDetectionResult.Unavailable(
                    app.lawnchair.organizer.integration.DetectionUnavailableReason.PROFILE_SERIAL_UNAVAILABLE,
                )
            }
        }

        override fun composeFullOrganization(): app.lawnchair.organizer.integration.OrganizationInputComposition {
            composeFullCalls++
            return notReady()
        }

        override fun composeScopeComposedOrganization(
            selection: List<app.lawnchair.organizer.planning.CandidateTarget.AppKey>,
        ): app.lawnchair.organizer.integration.OrganizationInputComposition = notReady()

        override fun inspectPlan(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = app.lawnchair.organizer.application.public.PlanPreviewResult.WriterBusy

        override fun materialize(
            input: app.lawnchair.organizer.planning.OrganizationInput,
            result: app.lawnchair.organizer.planning.PlanningResult,
        ) = error("not reached in exchange holder tests")

        override fun apply(
            plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
            runId: app.lawnchair.organizer.application.public.RunId,
        ) = error("not reached in exchange holder tests")

        override fun inspectRecovery(pointId: app.lawnchair.organizer.application.public.RecoveryPointId) = error("not reached in exchange holder tests")

        override fun confirmRecovery(
            pointId: app.lawnchair.organizer.application.public.RecoveryPointId,
            confirmation: app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation,
        ) = error("not reached in exchange holder tests")

        override fun readDurableOrganizerStatus() = error("not reached in exchange holder tests")

        override fun readRestorableRecoveryEntry(): app.lawnchair.organizer.application.public.RestorableRecoveryEntry? = error("not reached in exchange holder tests")

        override val readinessState: kotlinx.coroutines.flow.StateFlow<app.lawnchair.organizer.application.protocol.ReadinessGate.State> =
            kotlinx.coroutines.flow.MutableStateFlow(app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY)

        private fun notReady(): app.lawnchair.organizer.integration.OrganizationInputComposition = app.lawnchair.organizer.integration.OrganizationInputComposition.NotReady(
            app.lawnchair.organizer.integration.InputReadinessReason.InvalidCanonicalCapture(
                app.lawnchair.organizer.integration.CaptureFailureCategory.CAPTURE_UNAVAILABLE,
            ),
            app.lawnchair.organizer.integration.CompositionDiagnostic(
                app.lawnchair.organizer.integration.InputCompositionCode.CAPTURE_INVALID,
            ),
        )
    }

    private fun newExchangeRun(
        detectionReady: Boolean = true,
    ): Pair<app.lawnchair.organizer.ui.ManualOrganizationRun, ExchangeRunApplication> {
        val application = ExchangeRunApplication(detectionReady)
        val run = app.lawnchair.organizer.ui.ManualOrganizationRun(
            application,
            app.lawnchair.organizer.planning.OrganizationPlanner { error("planner must not run") },
        )
        return run to application
    }

    private inner class BlockingStructuralInputs {
        @Volatile
        var gate: CountDownLatch? = null

        /** Issue #328 review: settle kind coverage (InputNotReady). */
        @Volatile
        var notReady = false

        fun inputs(): ExchangeStructuralResult {
            gate?.await(5, TimeUnit.SECONDS)
            return if (notReady) {
                ExchangeStructuralResult.NotReady(
                    app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending,
                )
            } else {
                ExchangeStructuralResult.Ready(structural())
            }
        }
    }

    private class HolderFixture(
        val holder: ExchangeFlowStateHolder,
        val controller: ExchangeFlowController,
        val store: FakeStore,
        val run: app.lawnchair.organizer.ui.ManualOrganizationRun,
        val application: ExchangeRunApplication,
        val unhandled: MutableList<Throwable>,
        val scope: CoroutineScope,
    )

    private val scopedCandidate = app.lawnchair.organizer.planning.CandidateTarget.AppKey(
        ComponentKey("com.example.c1"),
        ProfileId("personal"),
    )

    private fun scopedTargets(): TargetSet = TargetSet(
        structural().targets.existing,
        listOf(
            app.lawnchair.organizer.planning.CandidateItem(
                id = ItemId("c1"),
                profile = ProfileId("personal"),
                kind = app.lawnchair.organizer.planning.CandidateKind.APPLICATION,
                target = scopedCandidate,
                availability = Availability.AVAILABLE,
                span = GridSpan(1, 1),
            ),
        ),
    )

    private fun scopedExportInputs(now: Long): ExportInputs = exportInputs(now).copy(targets = scopedTargets())

    /**
     * The scope-composed freshness digest covers the additions, so a run-in
     * validation must see the same scoped structural state the export was
     * composed from (mirrors the production module's frozen-scope seam).
     */
    private fun scopedStructural(): CanonicalStructuralInputs = CanonicalStructuralInputs(structural().snapshot, scopedTargets(), structural().resolvedIdentities)

    private fun newFixture(
        detectionReady: Boolean = true,
        blockingStructural: BlockingStructuralInputs? = null,
        scopedStructural: Boolean = false,
        now: Long = 1_000_000L,
    ): HolderFixture {
        val store = FakeStore()
        val (run, application) = newExchangeRun(detectionReady)
        val unhandled = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                synchronized(unhandled) { unhandled.add(throwable) }
            },
        )
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(now)) },
            currentStructuralInputs = {
                blockingStructural?.inputs() ?: ExchangeStructuralResult.Ready(
                    if (scopedStructural) scopedStructural() else structural(),
                )
            },
            composeScopedExportInputs = { _, _, _ -> ExchangeInputResult.ExportReady(scopedExportInputs(now)) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { now },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = run,
            scope = scope,
            settleDispatcher = Dispatchers.IO,
            uiDispatcher = Dispatchers.IO,
        )
        return HolderFixture(holder, controller, store, run, application, unhandled, scope)
    }

    private fun awaitImportSuccess(holder: ExchangeFlowStateHolder): ExchangeScreen.ImportSuccess {
        var waited = 0
        while (holder.screen !is ExchangeScreen.ImportSuccess && waited < 5_000) {
            Thread.sleep(20)
            waited += 20
        }
        assertTrue(
            "the import success state must be reached (screen=${holder.screen}, status=${holder.status?.kind})",
            holder.screen is ExchangeScreen.ImportSuccess,
        )
        return holder.screen as ExchangeScreen.ImportSuccess
    }

    private fun awaitClosed(holder: ExchangeFlowStateHolder) {
        var waited = 0
        while (holder.screen !is ExchangeScreen.Closed && waited < 5_000) {
            Thread.sleep(20)
            waited += 20
        }
        assertTrue("the success state must close after the continuation settles", holder.screen is ExchangeScreen.Closed)
    }

    private fun awaitNotContinuing(holder: ExchangeFlowStateHolder) {
        var waited = 0
        while (holder.importContinuationActive && waited < 5_000) {
            Thread.sleep(20)
            waited += 20
        }
        assertFalse("the continuation must settle", holder.importContinuationActive)
    }

    private fun awaitImporting(holder: ExchangeFlowStateHolder, text: String) {
        var waited = 0
        while ((holder.screen as? ExchangeScreen.Importing)?.replyText != text && waited < 5_000) {
            Thread.sleep(20)
            waited += 20
        }
        assertEquals(text, (holder.screen as ExchangeScreen.Importing).replyText)
    }

    private fun generatedReplyFixture(fixture: HolderFixture): String {
        val generated = fixture.controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        return replyFor(generated.session)
    }

    /** A reply whose items are all canonical unresolved (warning case). */
    private fun replyWithNoJudgment(session: ExportSession): String {
        val built = ContextExportBuilder.build(
            exportInputs(session.createdAtEpochMs),
            session.tier,
            ReplayAllocator(session),
        )
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = listOf(ItemIntent(ref = built.export.items[0].ref)),
            unresolvedRefs = listOf(built.export.items[1].ref),
        )
        return buildString {
            append(ExchangeContract.INTENT_BEGIN_MARKER)
            append('\n')
            append(IntentCodec.encode(intent).decodeToString())
            append('\n')
            append(ExchangeContract.INTENT_END_MARKER)
        }
    }

    @Test
    fun importSuccessShowsTheSummaryAndDoesNotConnectTheRun() {
        // AC-1/AC-2/AC-4: a validated import stops at the success state; no
        // run seam is called and the summary is derived from the canonical
        // representation (authored preserve wishes counted, no judgment).
        val fixture = newFixture()
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        val success = awaitImportSuccess(fixture.holder)
        assertEquals(ExchangeImportEntryKind.IDLE, success.entryKind)
        assertFalse(success.continuing)
        assertTrue("the attempt is alive while the success state shows", fixture.holder.importAttemptActive)
        assertEquals("the run must stay untouched before the CTA", "Idle", fixture.run.state::class.java.simpleName)
        assertEquals(0, fixture.application.detectionCalls)
        assertEquals(2, success.summary.recognizedCount) // the fixture scope is a + b
        assertEquals(0, success.summary.noJudgmentCount)
        assertEquals(2, success.summary.keepCount)
        assertEquals(0, success.summary.priorityCount)
        assertEquals(0, success.summary.scopeCandidateCount)
        assertFalse(success.summary.minimizeMovement)
    }

    @Test
    fun warningSummaryCountsCanonicalNoJudgmentItems() {
        // AC-4: bare entries and unmentioned refs share one canonical
        // no-judgment count; the success state carries it for the warning
        // heading (semantics asserted at the UI level).
        val fixture = newFixture()
        val generated = fixture.controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        fixture.holder.openImport()
        fixture.holder.import(replyWithNoJudgment(generated.session))
        val success = awaitImportSuccess(fixture.holder)
        assertEquals(0, success.summary.recognizedCount)
        assertEquals(2, success.summary.noJudgmentCount)
        assertEquals(0, success.summary.keepCount)
    }

    @Test
    fun continueCtaConnectsTheRunExactlyOnceAndClosesTheSuccessState() {
        // AC-3: the CTA flips continuing synchronously (single-flight — the
        // second press is refused before any seam call) and the run start
        // runs exactly once.
        val fixture = newFixture()
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        awaitImportSuccess(fixture.holder)

        fixture.holder.continueImport()
        assertTrue("the synchronous flip closes the re-entry window", fixture.holder.importContinuationActive)
        fixture.holder.continueImport() // second press: refused
        awaitClosed(fixture.holder)
        assertEquals("exactly one run start", 1, fixture.application.detectionCalls)
        assertFalse(fixture.holder.importAttemptActive)
    }

    @Test
    fun discardClosesTheSuccessStateAndKeepsTheSession() {
        // AC-5: the explicit discard is zero-write, keeps the export session
        // alive (re-import stays possible) and shows the re-import guidance.
        val fixture = newFixture()
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        awaitImportSuccess(fixture.holder)
        assertNotNull(fixture.store.session)

        fixture.holder.discardImport()
        assertTrue(fixture.holder.screen is ExchangeScreen.Closed)
        assertNotNull("the session survives a discard", fixture.store.session)
        assertEquals(0, fixture.application.detectionCalls)
        assertEquals(ExchangeStatus.Kind.IMPORT_DISCARDED, fixture.holder.status!!.kind)
        assertFalse(fixture.holder.importAttemptActive)
    }

    @Test
    fun cancelBeforeTheValidationSettleDropsTheLateValidated() {
        // AC-1: a late Validated after the explicit cancel must not (re)create
        // the success state.
        val blocking = BlockingStructuralInputs()
        val fixture = newFixture(blockingStructural = blocking)
        val reply = generatedReplyFixture(fixture)
        blocking.gate = CountDownLatch(1)
        fixture.holder.openImport()
        fixture.holder.import(reply)
        fixture.holder.close()
        blocking.gate!!.countDown()
        Thread.sleep(200)
        assertTrue("a cancelled attempt never surfaces a success state", fixture.holder.screen is ExchangeScreen.Closed)
        assertFalse(fixture.holder.importAttemptActive)
    }

    @Test
    fun inputEditInvalidatesTheAttemptSoALateValidatedCannotSurface() {
        // AC-1: an editor change (no re-import) invalidates the attempt — the
        // late Validated of the replaced text is dropped.
        val blocking = BlockingStructuralInputs()
        val fixture = newFixture(blockingStructural = blocking)
        val reply = generatedReplyFixture(fixture)
        blocking.gate = CountDownLatch(1)
        fixture.holder.openImport()
        fixture.holder.import(reply)
        fixture.holder.onImportTextChange("edited while validating")
        blocking.gate!!.countDown()
        awaitImporting(fixture.holder, "edited while validating")
        Thread.sleep(200)
        assertTrue(fixture.holder.screen is ExchangeScreen.Importing)
        assertFalse(fixture.holder.importAttemptActive)
    }

    @Test
    fun outOfOrderValidatedKeepsTheNewestAttemptState() {
        // AC-1: with two imports in flight, the newest attempt owns the
        // surface; the older attempt's late Validated is dropped.
        val fixture = newFixture()
        val generated = fixture.controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        val reply = replyFor(generated.session)

        val gateA = CountDownLatch(1)
        val gateB = CountDownLatch(1)
        val gates = ArrayDeque(listOf(gateA, gateB))
        val fixtureWithGates = newFixtureWithGates(fixture, gates)

        fixtureWithGates.holder.openImport()
        fixtureWithGates.holder.import(reply) // attempt A
        Thread.sleep(100)
        fixtureWithGates.holder.import(reply) // attempt B
        Thread.sleep(100)
        gateB.countDown() // B settles first
        val success = awaitImportSuccess(fixtureWithGates.holder)
        val tokenB = success.attemptToken
        gateA.countDown() // A settles late: dropped
        Thread.sleep(200)
        val still = fixtureWithGates.holder.screen as ExchangeScreen.ImportSuccess
        assertEquals("the newest attempt owns the success state", tokenB, still.attemptToken)
    }

    private fun newFixtureWithGates(base: HolderFixture, gates: ArrayDeque<CountDownLatch>): HolderFixture {
        // Rebuild the controller seam so each validation blocks on the next
        // gate; the store/session fixture is carried over.
        val unhandled = base.unhandled
        val scope = CoroutineScope(
            Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                synchronized(unhandled) { unhandled.add(throwable) }
            },
        )
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(1_000_000L)) },
            currentStructuralInputs = {
                gates.removeFirstOrNull()?.await(5, TimeUnit.SECONDS)
                ExchangeStructuralResult.Ready(structural())
            },
            store = base.store,
            allocator = SequentialIdAllocator(),
            clock = { 1_000_000L },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = base.run,
            scope = scope,
            settleDispatcher = Dispatchers.IO,
            uiDispatcher = Dispatchers.IO,
        )
        return HolderFixture(holder, controller, base.store, base.run, base.application, unhandled, scope)
    }

    @Test
    fun runInImportShowsTheScopedSummaryAndAttachesOnCta() {
        // AC-1/AC-3 (run-in entry): the owning run holds the selection
        // surface while the success state shows; the CTA attaches the intent
        // to that run and the selection surface's guidance follows.
        val fixture = newFixture(detectionReady = true, scopedStructural = true)
        fixture.run.start()
        val selectingBefore = fixture.run.state as app.lawnchair.organizer.ui.ManualOrganizationRun.State.Selecting
        val generated = fixture.controller.generateForSelection(
            PrivacyTier.EXTERNAL_REDACTED,
            listOf(scopedCandidate),
            mapOf(scopedCandidate to "c1"),
        ) as ExchangeGenerationResult.Generated
        fixture.holder.openImport()
        fixture.holder.import(scopedReplyFor(generated.session))
        val success = awaitImportSuccess(fixture.holder)
        assertEquals(ExchangeImportEntryKind.RUN_IN, success.entryKind)
        assertEquals(1, success.summary.scopeCandidateCount)
        val selectingDuring = fixture.run.state as app.lawnchair.organizer.ui.ManualOrganizationRun.State.Selecting
        assertEquals("the run state stays untouched while the success state shows", selectingBefore.runId, selectingDuring.runId)

        fixture.holder.continueImport()
        awaitClosed(fixture.holder)
        val selectingAfter = fixture.run.state as app.lawnchair.organizer.ui.ManualOrganizationRun.State.Selecting
        assertEquals("the validated intent binds to the owning run", 1, selectingAfter.intentScopeCount)
    }

    @Test
    fun runInLateSettleIsDroppedWhenTheOwningRunIsGone() {
        // AC-1: a run-in settle whose owning run no longer holds the
        // selection surface is dropped (defense-in-depth).
        val blocking = BlockingStructuralInputs()
        val fixture = newFixture(detectionReady = true, blockingStructural = blocking)
        fixture.run.start()
        val reply = generatedReplyFixture(fixture)
        blocking.gate = CountDownLatch(1)
        fixture.holder.openImport()
        fixture.holder.import(reply)
        fixture.run.cancel()
        blocking.gate!!.countDown()
        Thread.sleep(200)
        assertFalse(fixture.holder.screen is ExchangeScreen.ImportSuccess)
    }

    @Test
    fun runReplacementBeforeTheCtaIsATypedFailureNotAnAttach() {
        // AC-3(h): the pre-attach owning-runId re-check refuses to attach
        // into a replacement run and keeps the success state operable.
        val fixture = newFixture(detectionReady = true, scopedStructural = true)
        fixture.run.start()
        val generated = fixture.controller.generateForSelection(
            PrivacyTier.EXTERNAL_REDACTED,
            listOf(scopedCandidate),
            mapOf(scopedCandidate to "c1"),
        ) as ExchangeGenerationResult.Generated
        fixture.holder.openImport()
        fixture.holder.import(scopedReplyFor(generated.session))
        awaitImportSuccess(fixture.holder)

        fixture.run.cancel()
        fixture.run.start() // a fresh run now holds the selection surface

        fixture.holder.continueImport()
        var waited = 0
        while (fixture.holder.importContinuationActive && waited < 5_000) {
            Thread.sleep(20)
            waited += 20
        }
        assertFalse("the refusal releases the continuing flag", fixture.holder.importContinuationActive)
        assertTrue("the success state stays for retry/discard", fixture.holder.screen is ExchangeScreen.ImportSuccess)
        assertEquals(ExchangeStatus.Kind.CTA_START_FAILED, fixture.holder.status!!.kind)
    }

    @Test
    fun clearBeforeTheValidationSettleDropsTheLateValidated() {
        // AC-1 (review): the Clear affordance invalidates the active attempt,
        // so a late Validated never surfaces.
        val blocking = BlockingStructuralInputs()
        val fixture = newFixture(blockingStructural = blocking)
        val reply = generatedReplyFixture(fixture)
        blocking.gate = CountDownLatch(1)
        fixture.holder.openImport()
        fixture.holder.onImportTextChange(reply)
        fixture.holder.import(reply)
        fixture.holder.onImportTextChange("")
        blocking.gate!!.countDown()
        Thread.sleep(200)
        assertEquals("", (fixture.holder.screen as ExchangeScreen.Importing).replyText)
        assertFalse(fixture.holder.importAttemptActive)
    }

    @Test
    fun editBeforeTheValidationSettleDropsTheLateFailure() {
        // AC-1 (review): the table covers Failure settlements too — the
        // attempt is held at the session load, the editor changes, and the
        // late ExportMismatch must not paint the outcome surface.
        val fixture = newFixture()
        val loadGate = CountDownLatch(1)
        fixture.store.loadGate = loadGate
        fixture.holder.openImport()
        fixture.holder.import(unmatchedMarkedReply())
        fixture.holder.onImportTextChange("edited")
        loadGate.countDown()
        Thread.sleep(200)
        assertEquals("edited", (fixture.holder.screen as ExchangeScreen.Importing).replyText)
        assertFalse(fixture.holder.importAttemptActive)
        assertTrue(fixture.holder.screen !is ExchangeScreen.ImportOutcomeScreen)
    }

    @Test
    fun editBeforeTheValidationSettleDropsTheLateInputNotReady() {
        // AC-1 (review): InputNotReady settlements are covered by the same
        // attempt anchor.
        val blocking = BlockingStructuralInputs().apply { notReady = true }
        val fixture = newFixture(blockingStructural = blocking)
        val reply = generatedReplyFixture(fixture)
        blocking.gate = CountDownLatch(1)
        fixture.holder.openImport()
        fixture.holder.import(reply)
        fixture.holder.onImportTextChange("edited")
        blocking.gate!!.countDown()
        Thread.sleep(200)
        assertEquals("edited", (fixture.holder.screen as ExchangeScreen.Importing).replyText)
        assertFalse(fixture.holder.importAttemptActive)
    }

    @Test
    fun ctaSeamFailuresKeepTheSuccessStateOperable() {
        // AC-3 (review): (a) non-CE throw, (b) CancellationException from
        // inside the seam while the holder scope lives — both settle as an
        // operable typed failure; (c) the retry then succeeds.
        val fixture = newFixture()
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        awaitImportSuccess(fixture.holder)

        fixture.holder.connectRunOverride = { throw IllegalStateException("boom") }
        fixture.holder.continueImport()
        awaitNotContinuing(fixture.holder)
        assertEquals(ExchangeStatus.Kind.CTA_START_FAILED, fixture.holder.status!!.kind)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)

        fixture.holder.connectRunOverride = { throw kotlinx.coroutines.CancellationException("seam ce") }
        fixture.holder.continueImport()
        awaitNotContinuing(fixture.holder)
        assertEquals(ExchangeStatus.Kind.CTA_START_FAILED, fixture.holder.status!!.kind)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)

        fixture.holder.connectRunOverride = null
        fixture.holder.continueImport()
        awaitClosed(fixture.holder)
    }

    @Test
    fun holderScopeCancellationDoesNotRequireAUiSettle() {
        // AC-3 (review): cancelling the holder scope itself is not a failure
        // of the seam — no UI settle is required (and none may corrupt state).
        val fixture = newFixture()
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        awaitImportSuccess(fixture.holder)
        fixture.scope.cancel()
        fixture.holder.continueImport()
        Thread.sleep(100)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)
        assertTrue("no unhandled failure may escape", fixture.unhandled.isEmpty())
    }

    @Test
    fun aLateCtaSettleNeverAppliesToAReimportedIdenticalAttempt() {
        // AC-3 ABA (review): the success state of an attempt that was closed
        // and re-created from the SAME semantic reply must not be touched by
        // the old attempt's settle (attempt tokens, not content identity).
        val fixture = newFixture()
        val reply = generatedReplyFixture(fixture)
        fixture.holder.openImport()
        fixture.holder.import(reply)
        val successA = awaitImportSuccess(fixture.holder)

        val releaseA = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.holder.connectRunOverride = {
            releaseA.await()
            ExchangeFlowStateHolder.ContinueOutcome.Success(null)
        }
        fixture.holder.continueImport()
        assertTrue(fixture.holder.importContinuationActive)

        fixture.holder.close()
        fixture.holder.import(reply)
        val successB = awaitImportSuccess(fixture.holder)
        assertTrue("the re-import must number a fresh attempt", successB.attemptToken != successA.attemptToken)

        releaseA.complete(Unit)
        Thread.sleep(200)
        val still = fixture.holder.screen as ExchangeScreen.ImportSuccess
        assertEquals("the old attempt's settle must not touch the new attempt", successB.attemptToken, still.attemptToken)
        assertFalse(still.continuing)
    }

    @Test
    fun discardedReplyCanBeImportedAgainWhileTheSessionLives() {
        // AC-5 (review): the discard round trip — the same reply imports
        // successfully again because the session stays valid.
        val fixture = newFixture()
        val reply = generatedReplyFixture(fixture)
        fixture.holder.openImport()
        fixture.holder.import(reply)
        awaitImportSuccess(fixture.holder)
        fixture.holder.discardImport()
        assertNotNull(fixture.store.session)
        fixture.holder.import(reply)
        awaitImportSuccess(fixture.holder)
    }

    @Test
    fun ctaGateRefusalsKeepTheSuccessStateAndAllowRetry() {
        // AC-3 (audit D-2): Busy / NotAttachable settle with the typed
        // guidance, release the continuing flag and keep the success state;
        // the eventual success settles away the transient guidance.
        val fixture = newFixture(detectionReady = true)
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        awaitImportSuccess(fixture.holder)

        fixture.holder.connectRunOverride = { ExchangeFlowStateHolder.ContinueOutcome.Busy }
        fixture.holder.continueImport()
        awaitNotContinuing(fixture.holder)
        assertEquals(ExchangeStatus.Kind.RUN_BUSY, fixture.holder.status!!.kind)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)

        fixture.holder.connectRunOverride = { ExchangeFlowStateHolder.ContinueOutcome.NotAttachable }
        fixture.holder.continueImport()
        awaitNotContinuing(fixture.holder)
        assertEquals(ExchangeStatus.Kind.RUN_BUSY, fixture.holder.status!!.kind)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)

        fixture.holder.connectRunOverride = null
        fixture.holder.continueImport()
        awaitClosed(fixture.holder)
        assertNull("the settled CTA clears the transient guidance", fixture.holder.status)
    }

    @Test
    fun aStartedRunIdMismatchSettlesAsFailureNotSuccess() {
        // AC-3 (audit D-3): the success settle applies only while the started
        // run still matches the live selection surface.
        val fixture = newFixture(detectionReady = true)
        fixture.run.start()
        val generated = fixture.controller.generateForSelection(
            PrivacyTier.EXTERNAL_REDACTED,
            listOf(scopedCandidate),
            mapOf(scopedCandidate to "c1"),
        ) as ExchangeGenerationResult.Generated
        fixture.holder.openImport()
        fixture.holder.import(scopedReplyFor(generated.session))
        awaitImportSuccess(fixture.holder)

        fixture.holder.connectRunOverride = {
            ExchangeFlowStateHolder.ContinueOutcome.Success(
                app.lawnchair.organizer.application.public.RunId("ffffffffffffffffffffffffffffffff"),
            )
        }
        fixture.holder.continueImport()
        awaitNotContinuing(fixture.holder)
        assertEquals(ExchangeStatus.Kind.CTA_START_FAILED, fixture.holder.status!!.kind)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)
    }

    @Test
    fun discardIsRefusedWhileTheCtaIsContinuing() {
        // AC-5 (audit D-4): the discard guard itself, not only the disabled
        // button: a continuing discard is a no-op until the seam settles.
        val fixture = newFixture(detectionReady = true)
        fixture.holder.openImport()
        fixture.holder.import(generatedReplyFixture(fixture))
        awaitImportSuccess(fixture.holder)

        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.holder.connectRunOverride = {
            release.await()
            ExchangeFlowStateHolder.ContinueOutcome.Success(null)
        }
        fixture.holder.continueImport()
        assertTrue(fixture.holder.importContinuationActive)

        fixture.holder.discardImport()
        assertTrue("the continuing discard is refused", fixture.holder.importContinuationActive)
        assertTrue(fixture.holder.screen is ExchangeScreen.ImportSuccess)

        release.complete(Unit)
        awaitClosed(fixture.holder)
    }

    @Test
    fun importSuccessStateStringsExistInBothLocales() {
        // Audit D-7: the #328 surface copy is the ja正本 AND the en
        // translation — both resource files must declare every string and
        // plural.
        val names = listOf(
            "exchange_import_success_title",
            "exchange_import_success_warning_title",
            "exchange_import_not_applied",
            "exchange_import_cta_idle",
            "exchange_import_cta_run_in",
            "exchange_import_discard",
            "exchange_import_discard_confirm_title",
            "exchange_import_discard_confirm_body",
            "exchange_import_discard_confirm_confirm",
            "exchange_import_discarded_guidance",
            "exchange_import_summary_global_minimize",
            "exchange_import_cta_failed",
            "exchange_start_frozen_import",
        )
        val plurals = listOf(
            "exchange_import_summary_recognized",
            "exchange_import_summary_no_judgment",
            "exchange_import_summary_scope_candidates",
            "exchange_import_summary_priority",
            "exchange_import_summary_group",
            "exchange_import_summary_placement",
            "exchange_import_summary_keep",
        )
        for (localeDir in listOf("values", "values-ja")) {
            val xml = lawnchairStringsXml(localeDir).readText()
            for (name in names) {
                assertTrue("$name must exist in $localeDir", xml.contains("name=\"$name\""))
            }
            for (name in plurals) {
                assertTrue("plurals $name must exist in $localeDir", xml.contains("<plurals name=\"$name\">"))
            }
        }
    }

    /**
     * Minimal run stand-in provider. The holder's import path only consults
     * `run.start` for a validated intent, which these race tests never reach
     * (they stop at the transport/cancel lifecycle); any call would be a bug.
     * `ManualOrganizationRun` is built through its real (internal) constructor
     * from the same test source set used by `ManualOrganizationRunTest`.
     */
    private object RecordingRun {

        fun get(): app.lawnchair.organizer.ui.ManualOrganizationRun = app.lawnchair.organizer.ui.ManualOrganizationRunTestSupport.newRun()
    }

    // region Issue #372: T-15 pre-display, expiry re-read, and the Back contract

    /** A clock backed by a mutable cell, so a test can cross the TTL. */
    private class MutableClock(var nowMs: Long)

    private fun plainSession(clock: MutableClock, ttlMs: Long = 24 * 60L * 60L * 1000L): ExportSession = ExportSession(
        exportId = "t15-session",
        itemRefs = emptyMap(),
        tier = PrivacyTier.EXTERNAL_REDACTED,
        sourceContextDigest = "digest",
        signalProvenance = null,
        createdAtEpochMs = clock.nowMs,
        expiresAtEpochMs = clock.nowMs + ttlMs,
    )

    private fun newHolderWithMutableClock(store: FakeStore, clock: MutableClock): Pair<ExchangeFlowStateHolder, ExchangeFlowController> {
        val controller = ExchangeFlowController(
            composeExportInputs = { ExchangeInputResult.ExportReady(exportInputs(clock.nowMs)) },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural()) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { clock.nowMs },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = RecordingRun.get(),
            scope = CoroutineScope(Dispatchers.IO),
            settleDispatcher = Dispatchers.IO,
            uiDispatcher = Dispatchers.IO,
        )
        return holder to controller
    }

    private fun selectings(holder: ExchangeFlowStateHolder): ExchangeScreen.SelectingPrivacy? = holder.screen as? ExchangeScreen.SelectingPrivacy

    /** Polls the holder's display until the predicate holds (IO threads settle). */
    private fun awaitScreen(holder: ExchangeFlowStateHolder, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        var waited = 0
        while (!predicate() && waited < timeoutMs) {
            Thread.sleep(50)
            waited += 50
        }
        assertTrue("the display did not reach the expected state within ${timeoutMs}ms", predicate())
    }

    @Test
    fun openFlowCapturesTheActiveRequestPreDisplay() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, _) = newHolderWithMutableClock(store, clock)

        // No active request: the pre-display stays absent (no fake row).
        holder.openFlow()
        val empty = selectings(holder)
        assertFalse(empty!!.replacementConfirmationRequired)
        assertEquals(null, empty.activeRequestExpiresAtEpochMs)

        // An active request: the pre-display carries existence + expiry only.
        store.session = plainSession(clock)
        holder.openFlow()
        val populated = selectings(holder)
        assertTrue(populated!!.replacementConfirmationRequired)
        assertEquals(clock.nowMs + 24 * 60L * 60L * 1000L, populated.activeRequestExpiresAtEpochMs)
    }

    @Test
    fun refreshActiveRequestReReadsExistenceConfirmationAndExpiry() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, _) = newHolderWithMutableClock(store, clock)
        store.session = plainSession(clock)
        holder.openFlow()
        assertTrue(selectings(holder)!!.replacementConfirmationRequired)

        // The request expired: the next read (resume path) clears the whole
        // pre-display and drops the replacement confirmation — the same
        // `active()` seam the generation gate uses, so display and gate agree.
        val expired = plainSession(clock, ttlMs = 400)
        clock.nowMs = expired.expiresAtEpochMs + 1
        store.session = expired
        holder.refreshActiveRequest()
        val refreshed = selectings(holder)
        assertFalse(refreshed!!.replacementConfirmationRequired)
        assertEquals(null, refreshed.activeRequestExpiresAtEpochMs)
    }

    @Test
    fun t15PreDisplayClearsWhenTheScheduledReReadFiresAfterTtlCrossing() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, controller) = newHolderWithMutableClock(store, clock)
        // A short remaining lifetime so the expiry-scheduled re-read fires in
        // real time; NO lifecycle event is simulated — the clock crossing alone.
        store.session = plainSession(clock, ttlMs = 400)
        val expiresAt = store.session!!.expiresAtEpochMs
        holder.openFlow()
        assertEquals(expiresAt, selectings(holder)!!.activeRequestExpiresAtEpochMs)

        clock.nowMs = expiresAt + 1
        awaitScreen(holder) {
            val selecting = selectings(holder)
            selecting != null && !selecting.replacementConfirmationRequired && selecting.activeRequestExpiresAtEpochMs == null
        }
        // Display and gate read the same seam: generation needs no confirmation.
        assertEquals(
            app.lawnchair.organizer.personalization.exchange.ExchangeGenerationGateOutcome.Proceed,
            controller.generationGate(userConfirmation = null),
        )
    }

    @Test
    fun refreshActiveRequestLeavesNonSelectingFacesUntouched() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, controller, _) = newHolderWithRecordedScope(store, clock.nowMs)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(holder, ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED))

        holder.refreshActiveRequest()

        assertTrue(holder.screen is ExchangeScreen.Disclosing)
        store.session = null
    }

    @Test
    fun backOnGeneratingIsBlockedAndTheGenerationStillSettles() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = ExchangeFlowController(
            composeExportInputs = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                ExchangeInputResult.ExportReady(exportInputs(clock.nowMs))
            },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural()) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { clock.nowMs },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = RecordingRun.get(),
            scope = CoroutineScope(Dispatchers.IO),
            settleDispatcher = Dispatchers.IO,
            uiDispatcher = Dispatchers.IO,
        )
        holder.openFlow()
        holder.generate(PrivacyTier.EXTERNAL_REDACTED)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // Back while generating: CONSUMED (never delegated to navigation).
        assertEquals(ExchangeBackAction.BLOCKED, exchangeBackAction(holder.screen))

        release.countDown()
        awaitScreen(holder) { holder.screen is ExchangeScreen.Disclosing }
        assertNotNull("the generation settled and the session was saved", store.session)
    }

    @Test
    fun backOnInFlightTransportIsBlockedAndTheWriteStillSettles() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, controller, _) = newHolderWithRecordedScope(store, clock.nowMs)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        val release = CountDownLatch(1)
        // The transport runs on its own thread (startTransport is synchronous
        // on the caller), mirroring the delayed-write race fixture above.
        val transportThread = Thread {
            holder.startTransport {
                release.await(5, TimeUnit.SECONDS)
                ExchangeTransportResult.Success
            }
        }
        transportThread.start()
        awaitScreen(holder) { currentDisclosureOrNull(holder)?.transportInFlight == true }
        assertEquals(ExchangeBackAction.BLOCKED, exchangeBackAction(holder.screen))

        release.countDown()
        transportThread.join(5_000)
        awaitScreen(holder) { currentDisclosureOrNull(holder)?.sent == true }
        // After the settle, Back is the zero-write close and the request
        // survives it (importable again).
        assertEquals(ExchangeBackAction.CLOSE, exchangeBackAction(holder.screen))
        holder.close()
        assertTrue(holder.screen is ExchangeScreen.Closed)
        assertNotNull("the sent request survives the close", store.session)
    }

    @Test
    fun backOnUnsentDisclosureRequestsDiscardAndConfirmInvalidatesOnlyThatSession() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, controller, _) = newHolderWithRecordedScope(store, clock.nowMs)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        assertEquals(ExchangeBackAction.REQUEST_DISCARD, exchangeBackAction(holder.screen))
        // The confirmation's confirm action is the existing closeDisclosure
        // structural gate: exactly this unsent session is invalidated.
        holder.closeDisclosure()
        awaitScreen(holder) { holder.screen is ExchangeScreen.Closed }
        assertNull("the unsent request is invalidated by the confirmed discard", store.session)
    }

    @Test
    fun replacementConfirmationIsReDerivedFromTheStoreOnEveryStart() {
        // Issue #372 implementation review (AC-13 TOCTOU + failure settle):
        // the gate decision must come from a fresh activeSession() read, never
        // from the face snapshot.
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        store.session = plainSession(clock)
        val controller = ExchangeFlowController(
            composeExportInputs = {
                ExchangeInputResult.NotReady(app.lawnchair.organizer.integration.InputReadinessReason.ReconciliationPending)
            },
            currentStructuralInputs = { ExchangeStructuralResult.Ready(structural()) },
            store = store,
            allocator = SequentialIdAllocator(),
            clock = { clock.nowMs },
        )
        val holder = ExchangeFlowStateHolder(
            controllerFactory = { controller },
            run = RecordingRun.get(),
            scope = CoroutineScope(Dispatchers.IO),
            settleDispatcher = Dispatchers.IO,
            uiDispatcher = Dispatchers.IO,
        )
        holder.openFlow()
        assertTrue(selectings(holder)!!.replacementConfirmationRequired)

        // Start → confirmation → approve → composition NOT READY: E1 survives
        // (nothing was saved) and the settle must RE-READ the store truth.
        holder.requestGeneration(PrivacyTier.EXTERNAL_REDACTED)
        assertTrue(holder.screen is ExchangeScreen.ReplacementConfirm)
        holder.confirmReplacementAndGenerate(PrivacyTier.EXTERNAL_REDACTED)
        awaitScreen(holder) { holder.screen is ExchangeScreen.SelectingPrivacy }
        assertEquals(ExchangeStatus.Kind.GENERATION_INPUT_NOT_READY, holder.status?.kind)
        assertNotNull("E1 must survive the failed attempt", store.session)
        assertEquals(0, store.saveCalls)
        assertTrue(
            "the failure settle must restore the confirmation requirement (E1 still active)",
            selectings(holder)!!.replacementConfirmationRequired,
        )

        // The retry hits the confirmation again — no unconfirmed replacement.
        holder.requestGeneration(PrivacyTier.EXTERNAL_REDACTED)
        assertTrue(holder.screen is ExchangeScreen.ReplacementConfirm)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun sessionAppearingAfterTheFaceWasOpenedStillRequiresConfirmation() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, _) = newHolderWithMutableClock(store, clock)
        holder.openFlow()
        assertFalse(selectings(holder)!!.replacementConfirmationRequired)

        // An active session appears through another path while T-15 shows:
        // the next start re-reads the store and routes to the confirmation.
        store.session = plainSession(clock)
        holder.requestGeneration(PrivacyTier.EXTERNAL_REDACTED)
        assertTrue(holder.screen is ExchangeScreen.ReplacementConfirm)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun busyDiscloseRefusesToCloseUntilTheInvalidateSettles() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, controller, _) = newHolderWithRecordedScope(store, clock.nowMs)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )

        // A blocking store: the invalidate is provably unfinished while the
        // gate holds, making the settle-pending boundary deterministic.
        val gate = CountDownLatch(1)
        store.invalidateGate = gate
        holder.closeDisclosure()
        assertTrue(currentDisclosureState(holder).cancelling)
        awaitScreen(holder) { store.invalidateEntered }

        // A second close while the invalidate is in flight: refused — the
        // face is retained and nothing is left mid-settle.
        holder.closeDisclosure()
        assertTrue(
            "the cancelling face must not be closable before the settle",
            currentDisclosureOrNull(holder)?.cancelling == true,
        )

        // The gate releases: exactly then the flow closes and only the
        // confirmed unsent session is invalidated.
        gate.countDown()
        awaitScreen(holder) { holder.screen is ExchangeScreen.Closed }
        assertNull(store.session)
    }

    @Test
    fun sentRequestSurvivesCloseAndTheT15PreDisplayShowsItAgain() {
        val clock = MutableClock(1_000_000L)
        val store = FakeStore()
        val (holder, controller, _) = newHolderWithRecordedScope(store, clock.nowMs)
        val generated = controller.generate(PrivacyTier.EXTERNAL_REDACTED) as ExchangeGenerationResult.Generated
        setScreenToDisclosing(
            holder,
            ExchangeDisclosureState(generated.session, generated.packageText, PrivacyTier.EXTERNAL_REDACTED),
        )
        holder.onTransportResult(ExchangeTransportResult.Success)
        assertTrue(currentDisclosureState(holder).sent)
        holder.close()
        assertEquals(ExchangeScreen.Closed, holder.screen)

        // Reopening within the TTL shows the active request pre-display —
        // without claiming any send state (issue #372 review: the copy must
        // stay send-state-neutral; `sent` is process-local).
        holder.openFlow()
        val selecting = selectings(holder)
        assertTrue(selecting!!.replacementConfirmationRequired)
        assertEquals(generated.session.expiresAtEpochMs, selecting.activeRequestExpiresAtEpochMs)
    }

    // endregion
}
