package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.locks.LockFixtures.capture
import app.lawnchair.organizer.locks.LockFixtures.item
import app.lawnchair.organizer.locks.LockFixtures.state
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.RevisionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §“Behavior scenarios”: module orchestration over fake ports — busy
 * lease, stale revision, precondition mismatch, commit success, single-column
 * write set, and no-op handling.
 *
 * Issue #38.
 */
class LockAuthoringModuleProtocolTest {

    private class RecordingWriter(
        var outcome: LockWriteOutcome = LockWriteOutcome.Committed(RevisionId("r1")),
    ) : LockStateWriterPort {
        val plans = mutableListOf<LockWritePlan>()

        override fun write(plan: LockWritePlan): LockWriteOutcome {
            plans += plan
            return outcome
        }
    }

    private class FakeCapture(var current: LockCapture) : LockCapturePort {
        override fun capture(): LockCapture = current
    }

    private val intent = UserReviewedIntent("test_confirm")

    private fun module(capture: FakeCapture, writer: RecordingWriter) = LockAuthoringModule(captures = capture, writer = writer)

    @Test
    fun `successful change returns committed result and writes single column plan`() {
        val app = item("101")
        val capture = FakeCapture(capture(state(listOf(app))))
        val writer = RecordingWriter()
        val result = module(capture, writer).setLock(
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertTrue(result is LockChangeResult.Changed)
        val plan = writer.plans.single()
        assertEquals(1, plan.writes.size)
        assertEquals(LockTargetState.LOCKED, plan.writes.single().newState)
        // Exact precondition: the expected state is the full captured row.
        assertEquals(app, plan.writes.single().expected)
        assertEquals(RevisionId("r0"), plan.sourceRevision)
    }

    @Test
    fun `no-op never reaches the writer`() {
        val app = item("101", lockState = OrganizerLockState.LOCKED)
        val capture = FakeCapture(capture(state(listOf(app))))
        val writer = RecordingWriter()
        val result = module(capture, writer).setLock(
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockChangeResult.NoChange, result)
        assertTrue(writer.plans.isEmpty())
    }

    @Test
    fun `missing intent never reaches the writer`() {
        val app = item("101")
        val capture = FakeCapture(capture(state(listOf(app))))
        val writer = RecordingWriter()
        val result = module(capture, writer).setLock(
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent = null),
        )
        assertEquals(LockChangeResult.Rejected(LockRejection.INTENT_REQUIRED), result)
        assertTrue(writer.plans.isEmpty())
    }

    @Test
    fun `writer busy maps to typed busy rejection`() {
        val app = item("101")
        val capture = FakeCapture(capture(state(listOf(app))))
        val writer = RecordingWriter(outcome = LockWriteOutcome.Rejected(LockWriteRejection.WRITER_BUSY))
        val result = module(capture, writer).setLock(
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockChangeResult.Rejected(LockRejection.WRITER_BUSY), result)
    }

    @Test
    fun `stale revision and precondition map to stale capture`() {
        val app = item("101")
        val capture = FakeCapture(capture(state(listOf(app))))
        for (reason in listOf(LockWriteRejection.STALE_REVISION, LockWriteRejection.PRECONDITION_FAILED)) {
            val writer = RecordingWriter(outcome = LockWriteOutcome.Rejected(reason))
            val result = module(capture, writer).setLock(
                LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
            )
            assertEquals(LockChangeResult.Rejected(LockRejection.STALE_CAPTURE), result)
        }
    }

    @Test
    fun `writer failure surfaces as failed without hiding the cause`() {
        val app = item("101")
        val capture = FakeCapture(capture(state(listOf(app))))
        val failure = IllegalStateException("db closed")
        val writer = RecordingWriter(outcome = LockWriteOutcome.Failed(failure))
        val result = module(capture, writer).setLock(
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertTrue(result is LockChangeResult.Failed)
        assertEquals(failure, (result as LockChangeResult.Failed).cause)
    }

    @Test
    fun `batch review executes one atomic plan with every listed row`() {
        val items = (1..3).map { item((100 + it).toString(), lockState = OrganizerLockState.UNKNOWN) }
        val capture = FakeCapture(capture(state(items)))
        val writer = RecordingWriter()
        val result = module(capture, writer).reviewBatch(
            LockBatchReviewRequest(
                listOf(ItemId("101"), ItemId("102"), ItemId("103")),
                LockTargetState.UNLOCKED,
                intent,
            ),
        )
        assertTrue(result is LockChangeResult.Changed)
        val plan = writer.plans.single()
        assertEquals(3, plan.writes.size)
        assertTrue(plan.writes.all { it.newState == LockTargetState.UNLOCKED })
    }

    @Test
    fun `explain previews state and notes without writing`() {
        val child = LockFixtures.folderChild("211", parent = "201", rank = 0)
        val parent = LockFixtures.folder(
            "201",
            children = listOf(child),
            lockState = OrganizerLockState.LOCKED,
        )
        val capture = FakeCapture(capture(state(listOf(child, parent))))
        val writer = RecordingWriter()
        val explanation = module(capture, writer).explain(ItemId("211"), LockTargetState.UNLOCKED)
        assertTrue(explanation is LockExplanation.Available)
        val available = explanation as LockExplanation.Available
        assertEquals(OrganizerLockState.UNLOCKED, available.entry.stored)
        assertTrue(available.entry.effectivelyProtected)
        assertTrue(LockEffectNote.FOLDER_CHILD_UNLOCK_INEFFECTIVE_UNDER_PARENT_LOCK in available.notes)
        assertTrue(writer.plans.isEmpty())
    }

    @Test
    fun `explain for missing item reports unavailable`() {
        val capture = FakeCapture(capture(state(listOf(item("101")))))
        val explanation = module(capture, RecordingWriter()).explain(ItemId("999"), LockTargetState.LOCKED)
        assertTrue(explanation is LockExplanation.Unavailable)
        assertEquals(LockRejection.ITEM_NOT_FOUND, (explanation as LockExplanation.Unavailable).reason)
    }

    @Test
    fun `committed result carries the writer revision when observed`() {
        val app = item("101")
        val capture = FakeCapture(capture(state(listOf(app))))
        val writer = RecordingWriter(outcome = LockWriteOutcome.Committed(RevisionId("r-after")))
        val result = module(capture, writer).setLock(
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        val changed = result as LockChangeResult.Changed
        assertNotNull(changed.newRevision)
        assertEquals(RevisionId("r-after"), changed.newRevision)
    }

    @Test
    fun `module exposes no plan or apply surface`() {
        // AC-6 guard: the lock seam offers only preview/review/setLock/reviewBatch;
        // organization apply stays on the Issue #14 module.
        val methods = LockAuthoringModule::class.java.methods.map { it.name }.toSet()
        assertTrue("apply" !in methods)
        assertTrue("recover" !in methods)
        assertTrue("plan" !in methods)
    }
}
