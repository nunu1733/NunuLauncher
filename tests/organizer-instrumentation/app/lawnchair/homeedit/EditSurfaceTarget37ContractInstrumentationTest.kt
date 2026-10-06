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
 *     reason and builds no plan, and Confirm is only re-admitted after the
 *     correlated capture completes (onCaptureReady). No apply runs in this
 *     oracle, so no recovery point / Undo path is exercised or added (the
 *     no-dummy-recovery rule of spec 526 is untouched by construction — the
 *     contract adds no write path at all).
 *  4. The discard notice carries the liveRegion=Polite semantics in the
 *     accessibility tree when work existed, and there is no such node
 *     otherwise. The repo's instrumentation setup has no ComposeTestRule
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
            awaitSelectableItem(currentActivity(scenario), "selection-only recreated capture")
        }
        awaitNoticeText(present = true)
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

            // The correlated capture completes: Confirm is re-admitted. Assert
            // the admission at the gate (driving the real apply here would be a
            // write the oracle does not need).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                editSurfaceApplyGate.onCaptureReady()
                assertEquals(EditSurfaceApplyGate.State.Idle, editSurfaceApplyGate.state)
                assertTrue("the next apply must be admitted after the correlated capture", editSurfaceApplyGate.beginApply())
                // Leave the authority Idle for the next test.
                editSurfaceApplyGate.onApplyTerminal()
                editSurfaceApplyGate.onCaptureReady()
                assertEquals(EditSurfaceApplyGate.State.Idle, editSurfaceApplyGate.state)
            }
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
                editSurfaceApplyGate.onTerminalWithoutWorldChange()
                assertEquals(EditSurfaceApplyGate.State.Idle, editSurfaceApplyGate.state)
            }

            // The shared world moves EXTERNALLY after the release (a fresh
            // fixture generation through the harness's own settle seam).
            val rowsBefore = favoritesRowCount()
            seedDesktopApps(Triple(0, 3, 2), Triple(0, 1, 3), Triple(1, 2, 2))

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
                assertEquals(
                    "a rejected pre-write admission must not change the workspace",
                    rowsBefore,
                    favoritesRowCount(),
                )
                assertEquals(
                    "the gate must be back to Idle after the stale reopen",
                    EditSurfaceApplyGate.State.Idle,
                    editSurfaceApplyGate.state,
                )
            }
            openScenario = null
        }
    }

    /** Favorites row count straight from the launcher DB (zero-write oracle). */
    private fun favoritesRowCount(): Int =
        appState.model.modelDbController.db
            .query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID)
            .use { it.count }

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
     * Restores the process-wide single authority to Idle (test hygiene: the
     * gate is process state and must not leak InFlight/Correlating into later
     * edit-surface tests in the same process).
     */
    private fun forceGateIdleForNextTest() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (editSurfaceApplyGate.state == EditSurfaceApplyGate.State.InFlight) {
                editSurfaceApplyGate.onApplyTerminal()
            }
            editSurfaceApplyGate.onCaptureReady()
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
