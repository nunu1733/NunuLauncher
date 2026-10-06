/*
 * Issue #526: instrumentation oracles for the targetSdk 37 fork UI contracts
 * of the edit surface (spec 526, tier M). Sibling of
 * EditSurfaceUndoInstrumentationTest (same ActivityScenario + internal-hook
 * harness, same module so `internal` production members are reachable; no
 * new production test hook is added). Owned contracts:
 *
 *  1. recreate() × {selection-only, planあり, 未操作}: the discard notice is
 *     shown for user work and absent without it (the 未確定セッション is
 *     discarded, never silently kept — zero-write).
 *  2. System back during an in-flight apply (gate InFlight) does not finish
 *     the surface; back while the gate is Idle still finishes it (zero-write
 *     cancel stays available during zero-write waits — the back gate is
 *     scoped to InFlight only).
 *  3. A recreated surface cannot confirm while the single authority is
 *     InFlight or Correlating: the real confirm() is refused with the busy
 *     reason and builds no plan, and Confirm is only re-admitted after a
 *     correlated capture that STARTED AFTER the terminal completes (the
 *     correlation-generation ticket). The post-terminal release is driven by
 *     the production claim wiring alone (review round 2): the LIVE surface's
 *     Correlating observation claims the pending generation and starts its own
 *     correlated reload — no manual recapture injection. A capture that
 *     started BEFORE the terminal (the recreated instance's initial capture
 *     racing an in-flight apply) can never release the gate — pinned end to
 *     end across recreation
 *     (preTerminalCaptureTicketCannotReleaseTheGateAcrossRecreation). No
 *     apply runs in these oracles, so no recovery point / Undo path is
 *     exercised or added (the no-dummy-recovery rule of spec 526 is untouched
 *     by construction — the contract adds no write path at all).
 *  4. The discard notice carries the liveRegion=Polite semantics in the
 *     accessibility tree when work existed, and there is no such node
 *     otherwise; it clears on the next zero-write user operation — a real-UI
 *     tap on the Reset CTA (review round 2: the oracle uses existing UI/a11y
 *     seams only and does not widen production test hooks), and it does not
 *     reappear on subsequent recomposition-forcing actions. The repo's
 *     instrumentation setup has no ComposeTestRule
 *     seam for this activity (existing edit-surface oracles drive the REAL
 *     activity through ActivityScenario + internal hooks), so the semantics
 *     are asserted through the platform accessibility node attribute that
 *     Compose maps `liveRegion` onto (AccessibilityNodeInfo.liveRegion) —
 *     no new test infrastructure is built. The once-only TalkBack
 *     announcement itself is the Compose liveRegion behavior pinned by this
 *     attribute assertion; repeated-announcement on recomposition is not
 *     observable through the a11y node alone and stays an emulator-matrix
 *     (TalkBack ON) verification per the spec.
 *
 * Process-wide singleton note: the gate is process state shared by every
 * surface instance in this process. Every test restores it to Idle in a
 * finally/@After so later edit-surface tests in the same process observe the
 * production release path only.
 */
package app.lawnchair.homeedit

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.lawnchair.LawnchairLauncher
import app.lawnchair.homeedit.ui.HomeEditSurfaceActivity
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditSurfaceTarget37ContractInstrumentationTest {

    private companion object {
        /**
         * G5-style wall-clock bound (same shape as
         * EditSurfaceUndoInstrumentationTest): every visible wait loop is
         * deadline-bounded; this rule additionally bounds the waits outside
         * test code (ActivityScenario launch/recreate) so a CI hang fails
         * with a stuck-thread stack dump instead of a silent lane timeout.
         */
        val METHOD_TIMEOUT = Timeout.builder()
            .withTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
            .withLookingForStuckThread(true)
            .build()
    }

    @get:Rule
    val methodTimeout: Timeout = METHOD_TIMEOUT

    private lateinit var context: android.content.Context
    private lateinit var appState: LauncherAppState
    private lateinit var device: UiDevice
    private var snapshotRows: List<ContentValues> = emptyList()
    private var modelCallback: com.android.launcher3.model.BgDataModel.Callbacks? = null
    private var openScenario: ActivityScenario<HomeEditSurfaceActivity>? = null

    @Volatile
    private var generationLatch: CountDownLatch? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        appState = LauncherAppState.getInstance(context)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        snapshotRows = snapshotFavorites()
        // Same passive-callback binding as EditSurfaceUndoInstrumentationTest:
        // without a bound callback the tokenless forceReload() never starts a
        // generation and the correlated waits time out (Issue #150/#152).
        val callback = object : com.android.launcher3.model.BgDataModel.Callbacks {
            override fun bindCompleteModel(
                itemIdMap: com.android.launcher3.model.data.WorkspaceData,
                isBindingSync: Boolean,
            ) {
                generationLatch?.countDown()
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            appState.model.addCallbacks(callback)
        }
        modelCallback = callback
        appState.model.forceReload()
        waitForModelLoaded()
    }

    @After
    fun tearDown() {
        openScenario?.let { scenario ->
            runCatching { scenario.close() }
        }
        openScenario = null
        forceGateIdleForNextTest()
        modelCallback?.let { cb ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                appState.model.removeCallbacks(cb)
            }
        }
        restoreFavorites(snapshotRows)
        appState.model.forceReload()
        waitForModelLoaded()
    }

    // --- oracle 1 + 4: the discard notice on recreation ---

    @Test
    fun recreateAfterSelectionOnlyShowsTheDiscardNoticeWithPoliteLiveRegion() {
        launchSettledSurface().let { (scenario, activity) ->
            val itemId = awaitSelectableItem(activity, "selection-only recreate")
            // Control: no notice while the work is only pending (the notice
            // belongs to the RECREATED surface).
            awaitNoticeText(present = false)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.toggleSelectionForTest(itemId)
            }
            recreateAndWait(scenario)
            val newActivity = currentActivity(scenario)
            val recreatedItemId = awaitSelectableItem(newActivity, "selection-only recreated capture")
            awaitNoticeText(present = true)

            // Issue #526 review round 1/2: 案内は「次のユーザー操作」で消える —
            // 選択toggle以外の実UIの零書込み操作（上段のReset CTAのtap）でも
            // 消えること、その後の再compositionを強制する操作（選択on/off）で
            // 再発火しないことを同じケースで固定する。tapは実UI経路のみ
            // （a11y treeでReset CTAを探してUiDeviceで押す。既存seamのみで
            // productionのtest専用hookは広げない — review round 2）。
            tapResetCta()
            awaitNoticeText(present = false)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                newActivity.toggleSelectionForTest(recreatedItemId)
                newActivity.toggleSelectionForTest(recreatedItemId)
            }
            awaitNoticeText(present = false)
            openScenario = null
        }
    }

    @Test
    fun recreateAfterAPlannedSessionShowsTheDiscardNoticeWithPoliteLiveRegion() {
        launchSettledSurface().let { (scenario, activity) ->
            val itemId = awaitSelectableItem(activity, "plan recreate")
            // Drive a session plan (the same successful session action the
            // undo oracle uses; page 1 is seeded so the move is admitted).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.toggleSelectionForTest(itemId)
                activity.moveToPageForTest(1)
            }
            recreateAndWait(scenario)
            awaitSelectableItem(currentActivity(scenario), "plan recreated capture")
        }
        awaitNoticeText(present = true)
    }

    @Test
    fun recreateWithoutUserWorkShowsNoDiscardNotice() {
        launchSettledSurface().let { (scenario, _) ->
            awaitSelectableItem(currentActivity(scenario), "no-work recreate")
            recreateAndWait(scenario)
            awaitSelectableItem(currentActivity(scenario), "no-work recreated capture")
        }
        awaitNoticeText(present = false)
    }

    // --- oracle 2: the in-flight back gate ---

    @Test
    fun backDuringAnInFlightApplyDoesNotFinishTheSurface() {
        try {
            launchSettledSurface().let { (scenario, _) ->
                awaitSelectableItem(currentActivity(scenario), "in-flight back")
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertEquals(
                        "the gate must start Idle on a fresh surface",
                        EditSurfaceApplyGate.State.Idle,
                        editSurfaceApplyGate.state,
                    )
                    assertTrue(editSurfaceApplyGate.beginApply())
                }
                // Give the recomposition a frame: the BackHandler's enabled
                // state reads the gate's observable state.
                Thread.sleep(500)
                device.pressBack()
                // The back must be swallowed while the apply is in flight.
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    if (scenario.state != Lifecycle.State.RESUMED) break
                    Thread.sleep(200)
                }
                assertEquals(
                    "the surface must stay RESUMED while an apply is in flight",
                    Lifecycle.State.RESUMED,
                    scenario.state,
                )
            }
        } finally {
            forceGateIdleForNextTest()
        }
    }

    @Test
    fun backWhileTheGateIsIdleStillFinishesTheSurfaceAsZeroWriteCancel() {
        launchSettledSurface().let { (scenario, _) ->
            awaitSelectableItem(currentActivity(scenario), "idle back")
            device.pressBack()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (scenario.state == Lifecycle.State.DESTROYED) break
                Thread.sleep(200)
            }
            assertEquals(
                "back during zero-write waits must stay the existing cancel",
                Lifecycle.State.DESTROYED,
                scenario.state,
            )
            openScenario = null
        }
    }

    // --- oracle 3: no double apply across recreation ---

    @Test
    fun recreatedSurfaceCannotConfirmWhileTheGateIsInFlightOrCorrelating() {
        launchSettledSurface().let { (scenario, _) ->
            val oldActivity = currentActivity(scenario)
            awaitSelectableItem(oldActivity, "double-apply recreate")
            // Deterministic in-flight state: force the single authority to
            // InFlight through its own API (no real apply runs in this oracle,
            // so no recovery point / Undo path is exercised — the
            // no-dummy-recovery rule stays untouched by construction).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(editSurfaceApplyGate.beginApply())
            }
            recreateAndWait(scenario)
            val newActivity = currentActivity(scenario)
            val itemId = awaitSelectableItem(newActivity, "double-apply new surface")

            // The recreated instance starts clean: no plan was ever built on it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertNull(newActivity.lastPlanForTest)
            }
            // Drive a session on the NEW surface, then confirm: the gate is
            // InFlight, so the real confirm() must refuse with the busy reason
            // and must not build a second plan.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                newActivity.toggleSelectionForTest(itemId)
                newActivity.moveToPageForTest(1)
                newActivity.confirm()
                assertEquals(
                    "confirm during InFlight must show the busy reason",
                    com.android.launcher3.R.string.edit_surface_error_busy,
                    newActivity.reasonResForTest(),
                )
                assertNull("no plan may be built while the gate refuses", newActivity.lastPlanForTest)
                assertNull(
                    "no apply result may exist while the gate refuses",
                    newActivity.lastApplyResultForTest,
                )
            }

            // Terminal of the (old) apply: the gate moves to Correlating and
            // Confirm must STILL be refused — the new surface's capture
            // completed BEFORE the terminal, so the next release needs the
            // correlated capture, not the pre-terminal one.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editSurfaceApplyGate.onApplyTerminal()
                newActivity.confirm()
                assertEquals(
                    "confirm must stay refused during Correlating",
                    com.android.launcher3.R.string.edit_surface_error_busy,
                    newActivity.reasonResForTest(),
                )
                assertNull("still no plan during Correlating", newActivity.lastPlanForTest)
            }

            // The correlated capture completes: Confirm is re-admitted. The
            // release is driven by the production wiring ALONE (review round 2):
            // the live recreated surface observes Correlating, claims the
            // pending correlation generation and starts its own correlated
            // reload — the manual recapture injection is gone. (Driving the
            // real apply here would be a write the oracle does not need.)
            var correlatedRelease = false
            val releaseDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < releaseDeadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    correlatedRelease = editSurfaceApplyGate.state == EditSurfaceApplyGate.State.Idle
                }
                if (correlatedRelease) break
                Thread.sleep(200)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(
                    "the post-terminal correlated capture must release the gate to Idle",
                    correlatedRelease,
                )
                assertTrue("the next apply must be admitted after the correlated capture", editSurfaceApplyGate.beginApply())
                // Leave the authority Idle for the next test.
                editSurfaceApplyGate.onApplyTerminal()
                editSurfaceApplyGate.onCaptureReady(editSurfaceApplyGate.newCaptureTicket())
                assertEquals(EditSurfaceApplyGate.State.Idle, editSurfaceApplyGate.state)
            }
        }
    }

    // --- oracle 3 (race): a pre-terminal-started capture can never release ---

    @Test
    fun preTerminalCaptureTicketCannotReleaseTheGateAcrossRecreation() {
        // Spec 526 review round 1/2 race oracle: the recreated instance's initial
        // capture STARTS while the old apply is in flight and settles BEFORE
        // the terminal. Its completion must never release the gate — the
        // correlation-generation ticket pins this (asserted on the settled
        // capture while the gate is still InFlight, and by the JVM oracle for
        // the post-terminal window). After the terminal the ONLY release path
        // is the production claim wiring (review round 2): the LIVE recreated
        // surface observes Correlating, claims the pending correlation
        // generation and runs its own correlated reload — no manual
        // recapture injection. The settled pre-terminal capture has no
        // completion left in flight (its runOnUiThread update already ran
        // before the terminal), so any release after the terminal can only
        // come from that production-wired post-terminal capture.
        try {
            launchSettledSurface().let { (scenario, _) ->
                awaitSelectableItem(currentActivity(scenario), "pre-terminal ticket race")
                // Deterministic in-flight state, then recreate: the new
                // instance's onCreate capture begins pre-terminal.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertTrue(editSurfaceApplyGate.beginApply())
                }
                recreateAndWait(scenario)
                val newActivity = currentActivity(scenario)
                // The recreated instance's initial capture settles before the
                // terminal (its completion carries a pre-terminal ticket).
                awaitSelectableItem(newActivity, "pre-terminal recreated capture")
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertEquals(
                        "the pre-terminal capture must not release the in-flight gate",
                        EditSurfaceApplyGate.State.InFlight,
                        editSurfaceApplyGate.state,
                    )
                    // Terminal of the old apply: Correlating, with the settled
                    // pre-terminal capture unable to release it.
                    editSurfaceApplyGate.onApplyTerminal()
                    assertEquals(EditSurfaceApplyGate.State.Correlating, editSurfaceApplyGate.state)
                }
                // The live surface's production claim wiring (Correlating
                // observation → claim → its own correlated reload) is what
                // releases — bounded poll, no manual injection.
                var correlatedRelease = false
                val releaseDeadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < releaseDeadline) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        correlatedRelease = editSurfaceApplyGate.state == EditSurfaceApplyGate.State.Idle
                    }
                    if (correlatedRelease) break
                    Thread.sleep(200)
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertTrue(
                        "the live surface's production claim wiring must release the gate to Idle",
                        correlatedRelease,
                    )
                }
            }
        } finally {
            forceGateIdleForNextTest()
        }
    }

    // --- oracle 3b: zero-write release composes with the existing stale gate ---

    @Test
    fun zeroWriteTerminalReleaseThenWorldMoveFailsClosedThroughTheStaleGate() {
        // Spec 526 revision 2 oracle (3b): a zero-write, no-local-recovery
        // terminal releases the gate to Idle immediately (no correlated
        // recapture). The safety of that release does NOT depend on the world
        // being unchanged — if the shared world moves afterwards, the very
        // next real confirm must be refused by the EXISTING pre-write stale
        // admission with zero DB write and no undo record. Full captures are
        // not reintroduced here; the cross-check is external revision movement
        // + the existing stale admission.
        launchSettledSurface().let { (scenario, activity) ->
            awaitSelectableItem(activity, "3b release")
            // Deterministic zero-write terminal through the gate's own API
            // (the ApplyResult → gate-call mapping itself is pinned by the
            // JVM classification oracle in EditSurfaceApplyGateTest).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(editSurfaceApplyGate.beginApply())
                editSurfaceApplyGate.onApplyTerminal()
                editSurfaceApplyGate.onTerminalWithoutLocalRecovery()
                assertEquals(EditSurfaceApplyGate.State.Idle, editSurfaceApplyGate.state)
            }

            // The shared world moves EXTERNALLY after the release (a fresh
            // fixture generation through the harness's own settle seam).
            seedDesktopApps(Triple(0, 3, 2), Triple(0, 1, 3), Triple(1, 2, 2))

            // Full-row snapshot AFTER the world move and BEFORE re-confirm
            // (review round 1): a row COUNT cannot catch a forbidden UPDATE
            // (container / screen / cell move), so the stale zero-write oracle
            // compares every row. Also pin the single-slot undo record token:
            // the stale branch must not replace it (Undo追加 0).
            val rowsBefore = favoritesRowSnapshot()
            val undoTokenBefore = HomeEditUndoRecord.currentTokenForTest()

            // Drive a session against the pre-move capture and confirm for
            // real: the plan builds against the stale capture revision, and
            // the apply-time pre-write admission must reject it as stale with
            // zero write.
            val itemId = awaitSelectableItem(activity, "3b stale confirm")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.toggleSelectionForTest(itemId)
                activity.moveToPageForTest(1)
                activity.confirm()
            }
            // Sample the terminal atomically on the main thread: the stale
            // reason text is visible from handleApplyResult until the reopen
            // capture's completion clears it, so the FIRST observation with a
            // result must show either the stale reason or the already-completed
            // reopen (gate Idle). Anything else means the contract broke.
            var sawResult: app.lawnchair.organizer.application.public.ApplyResult? = null
            var sawStaleReason = false
            var sawGateIdle = false
            val sampleDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < sampleDeadline && sawResult == null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val r = activity.lastApplyResultForTest
                    if (r != null) {
                        sawResult = r
                        sawStaleReason =
                            activity.reasonResForTest() ==
                            com.android.launcher3.R.string.edit_surface_error_stale_reopen
                        sawGateIdle = editSurfaceApplyGate.state == EditSurfaceApplyGate.State.Idle
                    }
                }
                if (sawResult == null) Thread.sleep(200)
            }
            // The stale reopen's correlated capture (ticket taken after the
            // terminal) must release the gate: wait bounded before pinning.
            var reopenIdle = false
            val reopenDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < reopenDeadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    reopenIdle = editSurfaceApplyGate.state == EditSurfaceApplyGate.State.Idle
                }
                if (reopenIdle) break
                Thread.sleep(200)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(
                    "the stale world move must surface as a Rejected apply",
                    sawResult is app.lawnchair.organizer.application.public.ApplyResult.Rejected &&
                        (sawResult as app.lawnchair.organizer.application.public.ApplyResult.Rejected)
                            .reason ==
                        app.lawnchair.organizer.application.public.PreWriteRejection.STALE_REVISION,
                )
                assertTrue(
                    "the stale rejection must reopen with the latest capture (reason shown or reopen done)",
                    sawStaleReason || sawGateIdle,
                )
                assertTrue(
                    "the stale reopen capture must release the gate to Idle",
                    reopenIdle,
                )
                assertEquals(
                    "a rejected pre-write admission must not change the workspace (full-row zero-write)",
                    rowsBefore,
                    favoritesRowSnapshot(),
                )
                assertEquals(
                    "the stale zero-write branch must not add an undo record (Undo追加 0)",
                    undoTokenBefore,
                    HomeEditUndoRecord.currentTokenForTest(),
                )
            }
            openScenario = null
        }
    }

    // --- oracle 3c (review round 3): Correlating BEFORE the surface starts ---

    @Test
    fun aSurfaceStartedWhileCorrelatingClaimsThePendingCorrelationInsteadOfBypassingIt() {
        // Review round 3 ordering: the terminal reaches Correlating BEFORE the
        // recreated/launched surface's onCreate runs. The unconditional
        // onCreate capture must NOT bypass the claim authority and start a
        // second post-terminal full capture: onCreate itself claims the pending
        // correlation and its initial capture IS the single correlated reload
        // (claimed ticket), and no second capture may start from the
        // Content() observer. Pinned by: gate reaches Correlating → launch a
        // NEW surface → its capture settles → gate must be Idle (released by
        // the onCreate-claimed capture alone) and the surface must be usable.
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(editSurfaceApplyGate.beginApply())
                editSurfaceApplyGate.onApplyTerminal()
                assertEquals(EditSurfaceApplyGate.State.Correlating, editSurfaceApplyGate.state)
            }
            launchSettledSurface().let { (scenario, activity) ->
                awaitSelectableItem(activity, "correlating-start surface")
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertEquals(
                        "the onCreate-claimed initial capture must release the gate (no bypass, no second capture)",
                        EditSurfaceApplyGate.State.Idle,
                        editSurfaceApplyGate.state,
                    )
                }
                openScenario = null
            }
        } finally {
            forceGateIdleForNextTest()
        }
    }

    // --- oracle 3d (review round 4): a claimed reload's owner dying mid-capture ---

    @Test
    fun aClaimedCorrelatedReloadWhoseOwnerIsDestroyedBeforeCompletionHandsOffToTheSuccessor() {
        // Review round 4 ordering: a live surface claims the pending
        // correlation and STARTS its correlated reload, then is destroyed
        // BEFORE that capture completes. The claim must not orphan: the
        // destroyed owner's completion releases the claim
        // (releaseClaimedCorrelation) and the live successor (recreated
        // surface) can claim the same generation and run the single correlated
        // reload, reaching Idle — no Loading/Correlating deadlock.
        try {
            launchSettledSurface().let { (scenario, activity) ->
                awaitSelectableItem(activity, "3d handoff surface")
                // Drive the owner into Correlating with a claimed reload: the
                // same entry the production stale branch uses (claim → reload).
                // The gate API path is used directly for determinism (no real
                // apply; the ApplyResult→gate mapping is pinned by the JVM
                // classification oracle).
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertTrue(editSurfaceApplyGate.beginApply())
                    editSurfaceApplyGate.onApplyTerminal()
                    val claimed = editSurfaceApplyGate.claimCorrelatedCapture()
                    assertNotNull("the live surface must claim the pending correlation", claimed)
                }
                // Simulate the claimed reload's capture being in flight while
                // the owner is destroyed: recreate off the main thread (the
                // claim was taken with this surface as owner).
                scenario.recreate()
                val successor = currentActivity(scenario)
                awaitSelectableItem(successor, "3d successor surface")
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    assertEquals(
                        "the successor must reach Idle through the handed-off correlation",
                        EditSurfaceApplyGate.State.Idle,
                        editSurfaceApplyGate.state,
                    )
                    assertTrue(
                        "the successor must be able to confirm again after the handoff",
                        editSurfaceApplyGate.beginApply(),
                    )
                }
            }
        } finally {
            forceGateIdleForNextTest()
        }
    }

    /**
     * Favorites full-row snapshot straight from the launcher DB (zero-write
     * oracle 3b, review round 1). Projects the stable placement columns
     * [desktopRowValues] writes, ordered by _ID — a forbidden UPDATE
     * (container / screen / cell move) changes rows without changing the
     * count, so the stale oracle compares rows, not counts.
     */
    private fun favoritesRowSnapshot(): List<List<Long?>> {
        val projection = arrayOf(
            Favorites._ID,
            Favorites.CONTAINER,
            Favorites.SCREEN,
            Favorites.CELLX,
            Favorites.CELLY,
            Favorites.SPANX,
            Favorites.SPANY,
            Favorites.RANK,
            Favorites.ITEM_TYPE,
            Favorites.PROFILE_ID,
        )
        val rows = mutableListOf<List<Long?>>()
        appState.model.modelDbController.db
            .query(Favorites.TABLE_NAME, projection, null, null, null, null, Favorites._ID)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        (0 until projection.size).map { index ->
                            if (cursor.isNull(index)) null else cursor.getLong(index)
                        },
                    )
                }
            }
        return rows
    }

    // --- harness (same shape as EditSurfaceUndoInstrumentationTest) ---

    private fun launchSettledSurface(): Pair<ActivityScenario<HomeEditSurfaceActivity>, HomeEditSurfaceActivity> {
        // The production module's readiness gate must be READY BEFORE the
        // activity's onCreate capture runs (inspectCapture is fail-closed on a
        // non-READY gate).
        app.lawnchair.LawnchairApp.instance.layoutApplicationModule.reconcileAtStart()
        awaitProductionReadinessGate()
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val scenario = ActivityScenario.launch(HomeEditSurfaceActivity::class.java)
        openScenario = scenario
        val activity = currentActivity(scenario)
        return scenario to activity
    }

    private fun currentActivity(scenario: ActivityScenario<HomeEditSurfaceActivity>): HomeEditSurfaceActivity {
        var ref: HomeEditSurfaceActivity? = null
        scenario.onActivity { ref = it }
        return ref ?: error("activity not launched")
    }

    private fun recreateAndWait(scenario: ActivityScenario<HomeEditSurfaceActivity>) {
        scenario.recreate()
        openScenario = scenario
        // The recreated instance must be RESUMED with its own capture poll
        // targetable; the caller polls the new instance's capture separately.
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    /**
     * Waits for the activity's own capture to settle (the diagram non-null).
     * The poll runs on a BACKGROUND thread (the same G5 §6.10 shape as the
     * undo oracle): a sleep-poll on the instrumentation thread blocks the
     * main looper and the activity's runOnUiThread capture updates would
     * never execute.
     */
    private fun awaitSelectableItem(activity: HomeEditSurfaceActivity, label: String): Int {
        val captureDeadline = System.currentTimeMillis() + 30_000
        var lastRecaptureDrive = 0L
        val pollOutcome = AtomicReference<String>("timeout")
        val pollDone = CountDownLatch(1)
        Thread(
            {
                try {
                    while (System.currentTimeMillis() < captureDeadline) {
                        var found: Int? = null
                        var typedReason: Int? = null
                        InstrumentationRegistry.getInstrumentation()
                            .runOnMainSync {
                                found = activity.firstSelectableItemIdForTest()
                                typedReason = activity.reasonResForTest()
                            }
                        if (found != null) {
                            pollOutcome.set("itemId:$found")
                            return@Thread
                        }
                        // Issue #526 harness: the onCreate capture is fail-closed
                        // and never retries by contract. When the typed reason is
                        // the capture-unavailable path (a settle race between the
                        // seeded model and the surface's first capture), re-drive
                        // the production "Try again" path (the same reloadCapture
                        // entry) at a bounded cadence instead of waiting out the
                        // deadline on a surface that will never self-heal.
                        if (typedReason ==
                            com.android.launcher3.R.string.edit_surface_error_capture_unavailable &&
                            System.currentTimeMillis() - lastRecaptureDrive >= 2_000
                        ) {
                            lastRecaptureDrive = System.currentTimeMillis()
                            InstrumentationRegistry.getInstrumentation()
                                .runOnMainSync { activity.recaptureForTest() }
                        }
                        Thread.sleep(300)
                    }
                    var lastReason: Int? = null
                    InstrumentationRegistry.getInstrumentation()
                        .runOnMainSync { lastReason = activity.reasonResForTest() }
                    pollOutcome.set(
                        "timeout(typedReason=" + lastReason?.let { activity.getString(it) } + ")",
                    )
                } catch (t: Throwable) {
                    pollOutcome.set("pollError(${t.javaClass.simpleName}: ${t.message})")
                } finally {
                    pollDone.countDown()
                }
            },
            "edit-surface-target37-poll",
        ).start()
        check(pollDone.await(35, TimeUnit.SECONDS)) { "$label: edit-surface poll did not finish" }
        val outcome = pollOutcome.get()
        check(outcome.startsWith("itemId:")) { "$label: no selectable item on the diagram ($outcome)" }
        return outcome.removePrefix("itemId:").toInt()
    }

    /**
     * Oracle 4: the notice node in the accessibility tree. Presence polls the
     * platform a11y tree (Compose maps `liveRegion = Polite` onto
     * AccessibilityNodeInfo.liveRegion); absence is checked against the same
     * tree after the capture has settled.
     */
    private fun awaitNoticeText(present: Boolean) {
        val notice = context.getString(com.android.launcher3.R.string.edit_surface_discard_notice)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val node = findA11yNodeWithText(notice)
            if (present && node != null) {
                assertEquals(
                    "the discard notice must carry liveRegion=Polite",
                    View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    node.liveRegion,
                )
                return
            }
            if (!present && node == null) return
            Thread.sleep(300)
        }
        if (present) {
            error("the discard notice did not appear in the accessibility tree")
        } else {
            error("the discard notice must not appear without prior user work")
        }
    }

    private fun findA11yNodeWithText(text: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots = buildList {
            runCatching { automation.windows }.getOrNull()?.forEach { window ->
                window.root?.let { add(it) }
            }
            automation.rootInActiveWindow?.let { add(it) }
        }
        roots.forEach { root ->
            findNodeRecursive(root, text)?.let { return it }
        }
        return null
    }

    private fun findNodeRecursive(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == text) return root
        for (index in 0 until root.childCount) {
            findNodeRecursive(root.getChild(index), text)?.let { return it }
        }
        return null
    }

    /**
     * Oracle 4 (review round 2): the discard-notice dismissal through a REAL
     * zero-write UI action — a tap on the top-bar Reset CTA
     * (edit_surface_reset; resetSession clears the notice via
     * onUserInteractionStarted). The button is located through the same a11y
     * seam as the notice node, lifted to its clickable ancestor (the Compose
     * TextButton) and clicked via UiDevice on its visible bounds. No internal
     * production hook is driven for the dismissal and no new production test
     * hook exists (the accepted spec's test-seam contract).
     */
    private fun tapResetCta() {
        val resetText = context.getString(com.android.launcher3.R.string.edit_surface_reset)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val node = findA11yNodeWithText(resetText)
            val clickable = node?.let { clickableAncestor(it) }
            if (clickable != null) {
                val bounds = Rect()
                clickable.getBoundsInScreen(bounds)
                device.click(bounds.centerX(), bounds.centerY())
                return
            }
            Thread.sleep(300)
        }
        error("the Reset CTA was not found in the accessibility tree")
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    /**
     * Restores the process-wide single authority to Idle (test hygiene: the
     * gate is process state and must not leak InFlight/Correlating into later
     * edit-surface tests in the same process). A ticket minted NOW matches
     * the pending correlation generation, so this deterministically releases
     * a Correlating authority (test-only forced release — production code
     * never bypasses the correlated capture).
     */
    private fun forceGateIdleForNextTest() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (editSurfaceApplyGate.state == EditSurfaceApplyGate.State.InFlight) {
                editSurfaceApplyGate.onApplyTerminal()
            }
            editSurfaceApplyGate.onCaptureReady(editSurfaceApplyGate.newCaptureTicket())
        }
    }

    /** G5 §6.10: the production readiness gate must be READY before the surface opens. */
    private fun awaitProductionReadinessGate() {
        val module = app.lawnchair.LawnchairApp.instance.layoutApplicationModule
        val deadline = System.currentTimeMillis() + 30_000
        var reReconciles = 0
        while (System.currentTimeMillis() < deadline) {
            when (module.readinessGate.state) {
                app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY -> return

                app.lawnchair.organizer.application.protocol.ReadinessGate.State.FAILED ->
                    if (reReconciles++ < 3) module.reconcileAtStart()

                else -> Unit
            }
            Thread.sleep(200)
        }
        check(
            module.readinessGate.state ==
                app.lawnchair.organizer.application.protocol.ReadinessGate.State.READY,
        ) {
            "production readiness gate not READY before the edit surface: " +
                module.readinessGate.state
        }
    }

    // --- fixture helpers (same shape as EditSurfaceUndoInstrumentationTest) ---

    private fun seedDesktopApps(vararg placements: Triple<Int, Int, Int>) {
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for ((screen, cellX, cellY) in placements) {
                val id = appState.model.modelDbController.generateNewItemId()
                db.insertOrThrow(Favorites.TABLE_NAME, null, desktopRowValues(id.toLong(), screen, cellX, cellY))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // G5 (§6.10): settle the seeded rows as a completed reload generation —
        // the plain isModelLoaded poll returns immediately across a reload.
        reloadAndWaitForGeneration("seedDesktopApps")
    }

    private fun desktopRowValues(id: Long, screen: Int, cellX: Int, cellY: Int): ContentValues = ContentValues().apply {
        put(Favorites._ID, id)
        put(Favorites.TITLE, "Edit surface target37 fixture $id")
        put(
            Favorites.INTENT,
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
                .toUri(0),
        )
        put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
        put(Favorites.SCREEN, screen)
        put(Favorites.CELLX, cellX)
        put(Favorites.CELLY, cellY)
        put(Favorites.SPANX, 1)
        put(Favorites.SPANY, 1)
        put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
        put(Favorites.RANK, 0)
        put(
            Favorites.PROFILE_ID,
            com.android.launcher3.pm.UserCache.INSTANCE.get(context)
                .getSerialNumberForUser(android.os.Process.myUserHandle()),
        )
    }

    private fun reloadAndWaitForGeneration(label: String) {
        val latch = CountDownLatch(1)
        generationLatch = latch
        appState.model.forceReload()
        check(
            latch.await(30, TimeUnit.SECONDS),
        ) { "$label: reload generation did not complete" }
        waitForModelLoaded()
        generationLatch = null
    }

    private fun snapshotFavorites(): List<ContentValues> {
        val rows = mutableListOf<ContentValues>()
        appState.model.modelDbController.db
            .query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID)
            .use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    val values = ContentValues()
                    for (index in columns.indices) {
                        when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(columns[index])
                            android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(columns[index], cursor.getLong(index))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(columns[index], cursor.getDouble(index))
                            android.database.Cursor.FIELD_TYPE_STRING -> values.put(columns[index], cursor.getString(index))
                            android.database.Cursor.FIELD_TYPE_BLOB -> values.put(columns[index], cursor.getBlob(index))
                        }
                    }
                    rows.add(values)
                }
            }
        return rows
    }

    private fun restoreFavorites(rows: List<ContentValues>) {
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            for (row in rows) db.insertOrThrow(Favorites.TABLE_NAME, null, row)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun waitForModelLoaded() {
        val model = appState.model
        val deadline = System.currentTimeMillis() + 10_000L
        while (!model.isModelLoaded() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }
}
