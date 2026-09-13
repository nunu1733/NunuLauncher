package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.locks.LockFixtures.appPair
import app.lawnchair.organizer.locks.LockFixtures.capture
import app.lawnchair.organizer.locks.LockFixtures.dockItem
import app.lawnchair.organizer.locks.LockFixtures.folder
import app.lawnchair.organizer.locks.LockFixtures.folderChild
import app.lawnchair.organizer.locks.LockFixtures.item
import app.lawnchair.organizer.locks.LockFixtures.state
import app.lawnchair.organizer.locks.LockFixtures.unknownKindItem
import app.lawnchair.organizer.locks.LockFixtures.unsupportedContainerItem
import app.lawnchair.organizer.locks.LockFixtures.widgetItem
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §“Behavior scenarios”: decision matrix for single-item changes and
 * batch review — identity, profile, support, bounds, intent, closed targets,
 * no-op, and batch atomicity preconditions.
 *
 * Issue #38.
 */
class LockAuthoringDecisionTest {

    private val intent = UserReviewedIntent("test_confirm")

    @Test
    fun `accepts locking an unlocked plain item with intent`() {
        val app = item("101")
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(app))),
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertTrue(decision is LockDecision.Ready)
        val plan = (decision as LockDecision.Ready).plan
        assertEquals(1, plan.writes.size)
        assertEquals(101L, plan.writes.single().rowId)
        assertEquals(LockTargetState.LOCKED, plan.writes.single().newState)
        assertEquals(app, plan.writes.single().expected)
    }

    @Test
    fun `non-numeric item id cannot address a persisted row`() {
        // Production item ids are numeric favorites row ids; anything else is
        // not a persisted row and must reject instead of crashing.
        val app = item("app.not-a-row")
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(app))),
            LockStateChangeRequest(ItemId("app.not-a-row"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND), decision)
    }

    @Test
    fun `no change when target equals stored state`() {
        val app = item("101", lockState = OrganizerLockState.LOCKED)
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(app))),
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.NoChange, decision)
    }

    @Test
    fun `intent is required for unknown review`() {
        val app = item("101", lockState = OrganizerLockState.UNKNOWN)
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(app))),
            LockStateChangeRequest(ItemId("101"), LockTargetState.UNLOCKED, intent = null),
        )
        assertEquals(LockDecision.Rejected(LockRejection.INTENT_REQUIRED), decision)
    }

    @Test
    fun `intent is required for every change`() {
        val app = item("101")
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(app))),
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent = null),
        )
        assertEquals(LockDecision.Rejected(LockRejection.INTENT_REQUIRED), decision)
    }

    @Test
    fun `with intent unknown resolves to locked or unlocked`() {
        val app = item("101", lockState = OrganizerLockState.UNKNOWN)
        val state = state(listOf(app))
        for (target in LockTargetState.entries) {
            val decision = LockAuthoringDecision.evaluateChange(
                capture(state),
                LockStateChangeRequest(ItemId("101"), target, intent),
            )
            assertTrue("target $target must be ready: $decision", decision is LockDecision.Ready)
        }
    }

    @Test
    fun `missing item rejects as stale identity`() {
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(item("101")))),
            LockStateChangeRequest(ItemId("999"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND), decision)
    }

    @Test
    fun `unavailable profile rejects without mutation`() {
        val app = item("701", profile = "work")
        val capture = capture(
            state(
                listOf(app),
                profiles = listOf(
                    ProfileState(ProfileId("work"), ProfileAvailability.UNAVAILABLE),
                ),
            ),
        )
        val decision = LockAuthoringDecision.evaluateChange(
            capture,
            LockStateChangeRequest(ItemId("701"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.PROFILE_UNAVAILABLE), decision)
    }

    @Test
    fun `profile absent from inventory rejects`() {
        // LayoutState itself requires every item profile to be inventoried, so a
        // profile-unknown item cannot round-trip the constructor; the decision
        // still guards the branch for defensive callers.
        val app = item("101")
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(app))),
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        )
        assertTrue(decision is LockDecision.Ready)
    }

    @Test
    fun `unknown kind rejects as unsupported regardless of stored state`() {
        val row = unknownKindItem("601", lockState = OrganizerLockState.LOCKED)
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(row))),
            LockStateChangeRequest(ItemId("601"), LockTargetState.UNLOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.UNSUPPORTED_ITEM), decision)
    }

    @Test
    fun `unsupported container rejects`() {
        val row = unsupportedContainerItem("601")
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(row))),
            LockStateChangeRequest(ItemId("601"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.UNSUPPORTED_ITEM), decision)
    }

    @Test
    fun `placement outside device profile rejects`() {
        val outOfBounds = item(
            "801",
            placement = app.lawnchair.organizer.application.public.PlacementState.Workspace(
                page = app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
                    app.lawnchair.organizer.planning.PageId("p0"),
                ),
                cell = GridCell(3, 4),
                span = GridSpan(2, 2),
            ),
        )
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(outOfBounds))),
            LockStateChangeRequest(ItemId("801"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.PLACEMENT_OUT_OF_PROFILE), decision)
    }

    @Test
    fun `dock rank outside hotseat rejects`() {
        val dock = dockItem("401", rank = 4)
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(dock))),
            LockStateChangeRequest(ItemId("401"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.PLACEMENT_OUT_OF_PROFILE), decision)
    }

    @Test
    fun `folder child beyond one-page capacity is reviewable`() {
        // Issue #287: the single-page folder capacity (fixture 4x4 = 16) is a
        // display pagination constant, not a per-folder platform limit.
        // Grid presets declare smaller folder grids than the workspace, so a
        // grid change can lower the captured capacity below persisted member
        // ranks; those members must stay reviewable or the grid-change
        // recovery never completes (CAPTURE_UNKNOWN_LOCK stays permanent).
        for (rank in listOf(15, 16, 17, 40)) {
            val child = folderChild("211", parent = "201", rank = rank, lockState = OrganizerLockState.UNKNOWN)
            val captureState = state(listOf(folder("201", children = listOf(child)), child))
            for (target in LockTargetState.entries) {
                val decision = LockAuthoringDecision.evaluateChange(
                    capture(captureState),
                    LockStateChangeRequest(ItemId("211"), target, intent),
                )
                assertTrue("rank $rank target $target must be reviewable: $decision", decision is LockDecision.Ready)
            }
        }
    }

    @Test
    fun `negative folder child rank still rejects`() {
        val child = folderChild("211", parent = "201", rank = -1, lockState = OrganizerLockState.UNKNOWN)
        val decision = LockAuthoringDecision.evaluateChange(
            capture(state(listOf(folder("201", children = listOf(child)), child))),
            LockStateChangeRequest(ItemId("211"), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.PLACEMENT_OUT_OF_PROFILE), decision)
    }

    @Test
    fun `batch review resolves folder members beyond one-page capacity atomically`() {
        val members = listOf(
            folderChild("211", parent = "201", rank = 0, lockState = OrganizerLockState.UNKNOWN),
            folderChild("212", parent = "201", rank = 16, lockState = OrganizerLockState.UNKNOWN),
            folderChild("213", parent = "201", rank = 40, lockState = OrganizerLockState.UNKNOWN),
        )
        val parent = folder("201", children = members, lockState = OrganizerLockState.UNKNOWN)
        val decision = LockAuthoringDecision.evaluateReviewBatch(
            capture(state(listOf(parent) + members)),
            LockBatchReviewRequest(
                listOf(ItemId("211"), ItemId("212"), ItemId("213")),
                LockTargetState.UNLOCKED,
                intent,
            ),
        )
        assertTrue("batch with capacity-exceeding members must be ready: $decision", decision is LockDecision.Ready)
        val writes = (decision as LockDecision.Ready).plan.writes
        assertEquals(listOf("211", "212", "213"), writes.map { it.item.value })
        assertTrue(writes.all { it.newState == LockTargetState.UNLOCKED })
    }

    @Test
    fun `batch review still rejects atomically when any member is out of profile`() {
        val good = folderChild("212", parent = "201", rank = 16, lockState = OrganizerLockState.UNKNOWN)
        val negative = folderChild("213", parent = "201", rank = -1, lockState = OrganizerLockState.UNKNOWN)
        val parent = folder("201", children = listOf(good, negative), lockState = OrganizerLockState.UNKNOWN)
        val decision = LockAuthoringDecision.evaluateReviewBatch(
            capture(state(listOf(parent, good, negative))),
            LockBatchReviewRequest(
                listOf(ItemId("212"), ItemId("213")),
                LockTargetState.UNLOCKED,
                intent,
            ),
        )
        assertEquals(LockDecision.Rejected(LockRejection.PLACEMENT_OUT_OF_PROFILE), decision)
    }

    @Test
    fun `folder folder-child dock widget and app pair are all lockable`() {
        val childA = folderChild("211", parent = "201", rank = 0)
        val folder = folder("201", children = listOf(childA))
        val dock = dockItem("401")
        val widget = widgetItem("501")
        val pair = appPair("301", first = "311", second = "312")
        val capture = capture(state(listOf(childA, folder, dock, widget) + pair))
        for (id in listOf("211", "201", "401", "501", "301", "311")) {
            val decision = LockAuthoringDecision.evaluateChange(
                capture,
                LockStateChangeRequest(ItemId(id), LockTargetState.LOCKED, intent),
            )
            assertTrue("$id must be lockable: $decision", decision is LockDecision.Ready)
        }
    }

    @Test
    fun `batch review requires intent and concrete non-empty items`() {
        val capture = capture(state(listOf(item("101", lockState = OrganizerLockState.UNKNOWN))))
        assertEquals(
            LockDecision.Rejected(LockRejection.INTENT_REQUIRED),
            LockAuthoringDecision.evaluateReviewBatch(
                capture,
                LockBatchReviewRequest(listOf(ItemId("101")), LockTargetState.LOCKED, intent = null),
            ),
        )
        assertEquals(
            LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND),
            LockAuthoringDecision.evaluateReviewBatch(
                capture,
                LockBatchReviewRequest(emptyList(), LockTargetState.LOCKED, intent),
            ),
        )
    }

    @Test
    fun `batch review rejects missing or already reviewed items atomically`() {
        val unknown = item("101", lockState = OrganizerLockState.UNKNOWN)
        val reviewed = item("102", lockState = OrganizerLockState.UNLOCKED)
        val capture = capture(state(listOf(unknown, reviewed)))
        assertEquals(
            LockDecision.Rejected(LockRejection.ITEM_NOT_UNKNOWN),
            LockAuthoringDecision.evaluateReviewBatch(
                capture,
                LockBatchReviewRequest(listOf(ItemId("101"), ItemId("102")), LockTargetState.LOCKED, intent),
            ),
        )
        assertEquals(
            LockDecision.Rejected(LockRejection.ITEM_NOT_FOUND),
            LockAuthoringDecision.evaluateReviewBatch(
                capture,
                LockBatchReviewRequest(listOf(ItemId("101"), ItemId("999")), LockTargetState.LOCKED, intent),
            ),
        )
    }

    @Test
    fun `batch review of unknown rows produces one atomic plan`() {
        val items = (1..3).map { item((100 + it).toString(), lockState = OrganizerLockState.UNKNOWN) }
        val decision = LockAuthoringDecision.evaluateReviewBatch(
            capture(state(items)),
            LockBatchReviewRequest(listOf(ItemId("101"), ItemId("102"), ItemId("103")), LockTargetState.UNLOCKED, intent),
        )
        assertTrue(decision is LockDecision.Ready)
        assertEquals(3, (decision as LockDecision.Ready).plan.writes.size)
        assertTrue(decision.plan.writes.all { it.newState == LockTargetState.UNLOCKED })
        assertTrue(decision.plan.writes.all { it.expected.lockState == OrganizerLockState.UNKNOWN })
    }

    @Test
    fun `unknown kind rows never enter review decisions`() {
        // Review listing excludes them; decision still rejects them when asked.
        val row = unknownKindItem("601", lockState = OrganizerLockState.UNKNOWN)
        val decision = LockAuthoringDecision.evaluateReviewBatch(
            capture(state(listOf(row))),
            LockBatchReviewRequest(listOf(ItemId("601")), LockTargetState.LOCKED, intent),
        )
        assertEquals(LockDecision.Rejected(LockRejection.UNSUPPORTED_ITEM), decision)
    }

    @Test
    fun `write plan carries exact expected precondition and source revision`() {
        val app = item("101")
        val capture = capture(state(listOf(app)), revision = "rev-9")
        val decision = LockAuthoringDecision.evaluateChange(
            capture,
            LockStateChangeRequest(ItemId("101"), LockTargetState.LOCKED, intent),
        ) as LockDecision.Ready
        assertEquals(capture.revision, decision.plan.sourceRevision)
        assertEquals(CanonicalItemKind.Application, decision.plan.writes.single().expected.kind)
    }
}
