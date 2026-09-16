package app.lawnchair.organizer.ui.exchange

import android.net.Uri
import app.lawnchair.organizer.integration.exchange.ExchangeFlowController
import app.lawnchair.organizer.integration.exchange.ExchangeGenerationResult
import app.lawnchair.organizer.integration.exchange.ExchangeImportOutcome
import app.lawnchair.organizer.integration.exchange.ExchangeInputResult
import app.lawnchair.organizer.integration.exchange.ExchangeStructuralResult
import app.lawnchair.organizer.integration.exchange.ExchangeTransportFailure
import app.lawnchair.organizer.integration.exchange.ExchangeTransportResult
import app.lawnchair.organizer.integration.exchange.FileExchangeTransport
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportBuilder
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.IntentCodec
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SequentialIdAllocator
import app.lawnchair.organizer.personalization.exchange.ExchangeContract
import app.lawnchair.organizer.personalization.exchange.ExchangeImportResult
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
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        override fun save(session: ExportSession): Boolean {
            this.session = session
            return true
        }

        override fun load(exportId: String): ExportSession? = session?.takeIf { it.exportId == exportId }

        override fun active(nowEpochMs: Long): ExportSession? = session

        override fun invalidate(exportId: String) {
            if (session?.exportId == exportId) session = null
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
        resolvedCategories = emptyMap(),
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
        val intent = PersonalizedIntentV1(
            exportId = built.export.exportId,
            itemIntents = built.export.items.map { ItemIntent(ref = it.ref, preserve = true) },
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
        assertNull("the racing cancel closes the screen without invalidating", currentDisclosureOrNull(holder))

        // The write then settles on a closed disclosure: the late result must
        // not resurrect or invalidate anything.
        release.countDown()
        writeThread.join(5_000)
        assertNull("no disclosure remains after the racing cancel closed it", currentDisclosureOrNull(holder))
        assertNotNull("the session survives the write that landed after close", store.session)

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

        // A cancel racing the write is refused (not cancelable): closeDisclosure
        // only closes the screen — the session survives.
        holder.closeDisclosure()
        assertNotNull("session must survive a cancel attempted during the write", store.session)
        assertNull(
            "the racing cancel closed the disclosure",
            currentDisclosureOrNull(holder),
        )

        // The write then succeeds on the closed disclosure: the late result
        // must not reopen anything, and the session stays intact.
        release.countDown()
        writeThread.join(5_000)
        assertNull("no disclosure is resurrected by the late settle", currentDisclosureOrNull(holder))
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
}
