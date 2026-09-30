/*
 * Issue #450 (review round 11 findings 2–3): the AC-1/AC-9 emulator
 * operation EVIDENCE TOOLING — the undo snackbar's Undo action operated once
 * through the REAL UI for the four per-item actions and the edit-surface
 * confirm, with asserted-success screenshots before/after each tap, plus the
 * real snackbar's accessibility record with TalkBack enabled (AC-9).
 *
 * Write side (production seams the UI calls, no test shortcut): the
 * per-item actions go through `HomeEditExecutor.confirm(itemId, intent)` —
 * the same entry the #448 popup dialogs invoke (stage-1 plan → admission →
 * ModelWriter direct edit → success callback → record + snackbar); the
 * edit-surface confirm goes through the #449 `confirm()` flow. The undo side
 * is the production Snackbar view's action — a UiDevice tap on it reaches
 * `HomeEditUndoExecutor.start(token)` exactly as a user tap does.
 *
 * Deliberately NOT part of any CI lane class list (same status as the #376
 * cold-process evidence): the committed artifacts under
 * docs/assessment/450-edit-undo-ac1-screenshots/ are regenerated from this
 * class. The device must hold this app as the home role (the snackbar and
 * every evidence surface live on the launcher's own window) and have
 * com.android.chrome provisioned (the seeded fixture rows point at it).
 * Run:
 * ```bash
 * adb shell cmd package set-home-activity app.lawnchair.debug/.LawnchairLauncher
 * adb shell am instrument -w \
 *   -e class app.lawnchair.homeedit.HomeEditUndoEvidenceToolingTest \
 *   app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
 * adb pull /sdcard/Android/data/app.lawnchair.debug/files/
 * ```
 */
package app.lawnchair.homeedit

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.BgDataModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeEditUndoEvidenceToolingTest {

    private lateinit var context: Context
    private lateinit var appState: LauncherAppState
    private lateinit var device: UiDevice
    private var snapshotRows: List<ContentValues> = emptyList()
    private var modelCallback: BgDataModel.Callbacks? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        appState = LauncherAppState.getInstance(context)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertChromeFixtureAvailable()
        snapshotRows = snapshotFavorites()
        // Same passive-callback binding as EditSurfaceUndoInstrumentationTest:
        // without a bound callback the tokenless forceReload() never starts a
        // generation and the correlated waits time out (Issue #150/#152).
        val callback = object : BgDataModel.Callbacks {}
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            appState.model.addCallbacks(callback)
        }
        modelCallback = callback
        appState.model.forceReload()
        waitForModelSettled()
        bringLauncherToFront()
    }

    @After
    fun tearDown() {
        modelCallback?.let { cb ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                appState.model.removeCallbacks(cb)
            }
        }
        restoreFavorites(snapshotRows)
        appState.model.forceReload()
    }

    // --- AC-1: the four per-item actions, each undone by ONE real tap ---

    @Test
    fun moveUndoByTappingTheRealSnackbarAction() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val launcher = currentLauncher()
        val item = desktopItem(launcher, screenId = 0)

        // The REAL cross-page sequence: the source page is visible, the item
        // moves to page 1 (off-screen), the launcher snaps to the
        // destination, and the undo snackbar must SURVIVE the rebind (the
        // round 12 production fix removed the animated rebind's delayed
        // closeOpenViews that self-closed it ~500ms in). The >600ms
        // wait below is the survival oracle; the tap then reverts the move.
        confirmDirectEditAndAwaitUndoAction(launcher, item.id, HomeEditIntent.MoveToPage(item, 1))
        // >600ms survival past the old self-close, then the post-write
        // reload drain (keeps the undo task from being dropped).
        Thread.sleep(700)
        waitForModelSettled(2_500L)
        val survivingAction = device.findObject(By.text(undoActionText()))
            ?: error("the undo snackbar did not survive the cross-page move rebind")
        captureEvidence("direct-move-before")
        survivingAction.click()
        awaitSnackbarDismissed()
        awaitUndoState("the moved row back at its original page-0 cell") {
            val current = itemById(item.id)
            current != null && current.screenId == 0 &&
                current.cellX == item.cellX && current.cellY == item.cellY
        }
        captureEvidence("direct-move-after")
    }

    @Test
    fun addToFolderUndoByTappingTheRealSnackbarAction() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(0, 1, 2), Triple(1, 0, 0))
        val launcher = currentLauncher()
        val first = desktopItem(launcher, screenId = 0)
        val second = desktopItem(launcher, screenId = 0, excludeIds = listOf(first.id))
        val third = desktopItem(launcher, screenId = 0, excludeIds = listOf(first.id, second.id))

        // The target folder is SEEDED with two children so a stable
        // pre-existing folder exists for the evidence edit: seeded rows carry
        // no direct-edit OPTIONS bit, so a single-child seed folder is
        // flattened back to an icon by the launcher's bind-time cleanup
        // (~1-2s, a DB write: the child out, the folder row deleted). A
        // popup-created single-child folder is NOT flattened anymore — the
        // persisted OPTIONS_DIRECT_EDIT_CREATED_FOLDER bit keeps it alive
        // (see createFolderUndoByTappingTheRealSnackbarAction and
        // directEditCreatedFolderSurvivesReload) — so the seeding is not a
        // popup-path workaround. The seeded child shape mirrors what the
        // create task writes (cell -1/-1, rank ordered). The evidence edit
        // adds `third` through the real production path; its undo is the
        // evidence.
        seedFolderWithTwoChildren(folderId = 910, screen = 0, cellX = 0, cellY = 2)
        waitForModelSettled()
        Thread.sleep(2_000)
        waitForModelSettled()

        val item = third
        confirmTapUndoEvidence("direct-add-to-folder", launcher, item.id, HomeEditIntent.AddToFolder(item, 910))
        awaitSnackbarDismissed()
        awaitUndoState("the row back on the desktop out of the folder") {
            val current = itemById(item.id)
            current != null && current.container == HomeEditContainers.DESKTOP &&
                current.screenId == item.screenId &&
                current.cellX == item.cellX && current.cellY == item.cellY
        }
        captureEvidence("direct-add-to-folder-after")
    }

    /**
     * The popup create-folder undone by ONE real tap on the snackbar (the
     * round 12 production fix keeps the direct-edit-created single-child
     * folder alive through the undo window — the bind-time single-child
     * cleanup no longer flattens it). The undo is the inverse transaction:
     * the child back at its original placement AND the created folder row
     * deleted.
     */
    @Test
    fun createFolderUndoByTappingTheRealSnackbarAction() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val launcher = currentLauncher()
        val item = desktopItem(launcher, screenId = 0)

        confirmDirectEditAndAwaitUndoAction(launcher, item.id, HomeEditIntent.CreateFolderAndAdd(item, 0))
        captureEvidence("direct-create-folder-before")
        assertEquals("the created folder exists before the undo", 1, folderRowCount())
        val action = device.findObject(By.text(undoActionText()))
            ?: error("the undo snackbar action vanished before the tap")
        action.click()
        awaitSnackbarDismissed()
        awaitUndoState("the created folder row gone and the child back at its original cell") {
            val current = itemById(item.id)
            current != null && current.container == HomeEditContainers.DESKTOP &&
                current.screenId == 0 &&
                current.cellX == item.cellX && current.cellY == item.cellY &&
                folderRowCount() == 0
        }
        captureEvidence("direct-create-folder-after")
    }

    /**
     * Regression oracle for the Issue #450 suppression scope: the bind-time
     * single-child cleanup must KEEP working for folders the direct-edit
     * path did not create (the loading-artifact case the cleanup exists
     * for). A seeded single-child folder (no direct-edit flag) is flattened
     * by the launcher's own write.
     */
    @Test
    fun singleChildFolderCleanupStillFlattensNonDirectEditFolders() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        seedSingleChildFolder(920, screen = 0, cellX = 0, cellY = 2, childId = 921)
        waitForModelSettled()
        Thread.sleep(2_000)
        awaitUndoState("the non-direct-edit single-child folder flattened by the launcher") {
            folderRowCount() == 0
        }
    }

    /**
     * Review round 13 reload-lifecycle oracle: the direct-edit create-folder
     * write marks the row in the PERSISTED favorites OPTIONS column
     * (DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER), so the
     * bind-time single-child cleanup keeps respecting the folder after a
     * reload — the round 12 marker was transient and any reload re-armed the
     * flattening. The snackbar is left to expire untouched: the folder must
     * survive on its own persisted marker, not inside the undo window.
     *
     * Tooling status: this class is deliberately NOT part of any CI lane
     * class list (see the class KDoc) — on-demand emulator evidence.
     */
    @Test
    fun directEditCreatedFolderSurvivesReload() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val launcher = currentLauncher()
        val item = desktopItem(launcher, screenId = 0)

        confirmDirectEditAndAwaitUndoAction(launcher, item.id, HomeEditIntent.CreateFolderAndAdd(item, 0))
        assertEquals("the created folder exists before the reload", 1, folderRowCount())
        // No tap: let the undo snackbar expire so the reload lands outside
        // the undo window entirely.
        device.wait(Until.gone(By.text(undoActionText())), 15_000)
        // The reload rebuilds every FolderInfo from the favorites rows; the
        // persisted OPTIONS bit must ride along (loader:
        // collection.options = c.options) and keep the bind-time single-child
        // cleanup off this folder.
        appState.model.forceReload()
        waitForModelSettled()
        Thread.sleep(2_000)
        awaitUndoState("the single-child folder survived the reload's bind") {
            folderRowCount() == 1
        }
    }

    @Test
    fun removeUndoByTappingTheRealSnackbarAction() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val launcher = currentLauncher()
        val item = desktopItem(launcher, screenId = 0)

        confirmTapUndoEvidence("direct-remove", launcher, item.id, HomeEditIntent.Remove(item))
        awaitSnackbarDismissed()
        // The remove undo re-inserts the captured row with a FRESHLY
        // ALLOCATED id (ModelWriter restoreRemovedItemForDirectEdit), so the
        // predicate matches the recorded placement + launch target, not id.
        awaitUndoState("the removed row re-inserted at its original placement") {
            chromeRowAt(item.screenId, item.cellX, item.cellY)
        }
        captureEvidence("direct-remove-after")
    }

    // --- AC-1: the edit-surface confirm undone through the real snackbar ---

    /**
     * The #449 confirm-flow undo: the production confirm() records the entry
     * and hands the snackbar token to the launcher (the activity finishes
     * onto it); the undo is ONE real tap on that snackbar.
     */
    @Test
    fun editSessionConfirmUndoByTappingTheRealSnackbarAction() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        // Launcher first: the confirm's snackbar lands on it after finish().
        val launcher = currentLauncher()
        // The production module's readiness gate must be READY before the
        // activity's onCreate capture (inspectCapture is fail-closed).
        app.lawnchair.LawnchairApp.instance.layoutApplicationModule.reconcileAtStart()
        val scenario = androidx.test.core.app.ActivityScenario.launch(
            app.lawnchair.homeedit.ui.HomeEditSurfaceActivity::class.java,
        )
        var activityRef: app.lawnchair.homeedit.ui.HomeEditSurfaceActivity? = null
        scenario.onActivity { activityRef = it }
        val activity = activityRef ?: error("activity not launched")
        val itemId = awaitSelectableItem(activity)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.toggleSelectionForTest(itemId)
            activity.moveToPageForTest(1)
            activity.confirm()
        }
        val action = awaitUndoAction()
        captureEvidence("session-confirm-before")
        val freshAction = device.findObject(By.text(undoActionText()))
            ?: error("the undo snackbar action vanished before the tap")
        freshAction.click()
        awaitSnackbarDismissed()
        awaitUndoState("the confirmed move undone: the row back on page 0") {
            val current = itemById(itemId)
            current != null && current.screenId == 0
        }
        captureEvidence("session-confirm-after")
    }

    // --- AC-9: the real snackbar's accessibility record with TalkBack ---

    /**
     * AC-9: with TalkBack enabled (service bound), the REAL undo snackbar's
     * label and Undo action are present in the accessibility tree and the
     * action accepts an accessibility focus. Records the node details and the
     * window hierarchy XML as artifacts.
     */
    @Test
    fun undoSnackbarAccessibilityRecordWithTalkBack() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val launcher = currentLauncher()
        val previousServices = shell("settings get secure enabled_accessibility_services").trim()
        val previousEnabled = shell("settings get secure accessibility_enabled").trim()
        try {
            shell(
                "settings put secure enabled_accessibility_services " +
                    "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService",
            )
            shell("settings put secure accessibility_enabled 1")
            awaitTalkBackBound()
            device.pressHome()
            Thread.sleep(2_000)

            val item = desktopItem(launcher, screenId = 0)
            // Remove: the remove snackbar has no icon-rebind self-close (the
            // cross-page MOVE's snackbar closes itself within ~0.5s) and the
            // undo is not needed for this record.
            confirmDirectEditAndAwaitUndoAction(launcher, item.id, HomeEditIntent.Remove(item))

            val label = context.getString(com.android.launcher3.R.string.homeedit_undo_label)
            val undoAction = context.getString(com.android.launcher3.R.string.undo)
            val (labelNode, actionNode) = awaitSnackbarNodes(label, undoAction)
            captureEvidence("ac9-talkback-snackbar")
            assertTrue(
                "the Undo action must accept an accessibility focus",
                actionNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS),
            )
            recordAccessibilityEvidence(label, undoAction, labelNode, actionNode)
            device.dumpWindowHierarchy(evidenceFile("ac9-accessibility-dump", "xml"))

            assertTrue(
                "the snackbar label must be in the accessibility tree",
                labelNode.text?.contains(label) == true,
            )
            assertEquals("the Undo action text", undoAction, actionNode.text?.toString())
            assertTrue("the Undo action must be clickable", actionNode.isClickable)
            assertTrue("the Undo action must be focusable", actionNode.isFocusable)
        } finally {
            restoreSecureSetting("enabled_accessibility_services", previousServices)
            restoreSecureSetting("accessibility_enabled", previousEnabled)
        }
    }

    // --- write-side helpers (production seams) ---

    private fun currentLauncher(): LawnchairLauncher =
        app.lawnchair.LawnchairLauncher.instance
            ?: error("the launcher instance is unavailable (bringLauncherToFront failed)")

    /** Runs the production popup entry the #448 dialogs invoke. */
    private fun confirmDirectEdit(launcher: LawnchairLauncher, itemId: Int, intent: HomeEditIntent) {
        val executor = HomeEditExecutor(launcher)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            executor.confirm(itemId, intent)
        }
    }

    /**
     * Drains every coordinator-deferred MODEL_WRITER task before the evidence
     * write: a dummy direct-edit on a nonexistent item is queued behind them
     * (FIFO) and its typed ITEM_GONE callback fires only once the queue has
     * flushed. Without this, an earlier attempt's deferred write can execute
     * AFTER a later one and steal the item (the undo then STALE-rejects).
     */
    private fun awaitWriteBarrier(launcher: LawnchairLauncher) {
        val latch = java.util.concurrent.CountDownLatch(1)
        launcher.modelWriter.moveItemForDirectEdit(
            -1,
            Favorites.CONTAINER_DESKTOP,
            0,
            0,
            0,
            0,
            { com.android.launcher3.model.DirectEditContract.Decision.proceed() },
            { _, _, _, _, _, _, _, _, _, _, _, _, _ -> latch.countDown() },
        )
        assertTrue(
            "the direct-edit write barrier did not flush within 15s",
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    /**
     * Confirms the direct edit and waits for the REAL snackbar action. The
     * launcher's own startup loads can supersede the write's load id between
     * task creation and execution — the ModelWriter ModelTask guard then
     * drops the task SILENTLY (no callback, no snackbar, no write). Before
     * retrying, the placement is compared: an already-landed write without a
     * snackbar is a hard failure, while an unlanded one is retried with the
     * same intent (the placement is unchanged, so stage-1 still matches).
     */
    private fun confirmDirectEditAndAwaitUndoAction(
        launcher: LawnchairLauncher,
        itemId: Int,
        intent: HomeEditIntent,
    ): UiObject2 {
        repeat(4) {
            awaitWriteBarrier(launcher)
            val before = itemById(itemId)
            confirmDirectEdit(launcher, itemId, intent)
            val action = device.wait(Until.findObject(By.text(undoActionText())), 6_000)
            if (action != null) return action
            val after = itemById(itemId)
            val landed = after != null && (before == null ||
                after.container != before.container ||
                after.screenId != before.screenId ||
                after.cellX != before.cellX ||
                after.cellY != before.cellY)
            if (landed) {
                error("the direct-edit write landed but the undo snackbar never appeared")
            }
        }
        error("the undo snackbar did not appear after 4 confirm attempts")
    }

    /**
     * Confirms the direct edit, captures the before screenshot with the real
     * snackbar up, and taps the snackbar action ONCE through the UI. The node
     * is re-found immediately before the tap (the snackbar's accessibility
     * nodes can be re-created between the first find and the tap), with a
     * short capped settle so the undo — a ModelWriter task too — is not
     * dropped by a load landing inside its execution window.
     */
    private fun confirmTapUndoEvidence(
        label: String,
        launcher: LawnchairLauncher,
        itemId: Int,
        intent: HomeEditIntent,
    ) {
        confirmDirectEditAndAwaitUndoAction(launcher, itemId, intent)
        captureEvidence("$label-before")
        // Short capped settle before the tap: the confirm write schedules a
        // correlated reload, and a load landing inside the undo task's
        // execution window drops it silently (the ModelTask guard). The cap
        // keeps the tap inside the ~4s snackbar window.
        waitForModelSettled(3_000L)
        val action = device.findObject(By.text(undoActionText()))
            ?: error("the undo snackbar action vanished before the tap")
        action.click()
        // Catch the typed rejection Toast (if the undo was rejected) while it
        // is still on screen.
        Thread.sleep(1_000)
        runCatching { device.takeScreenshot(evidenceFile("$label-undo-t1")) }
    }

    private fun awaitSelectableItem(
        activity: app.lawnchair.homeedit.ui.HomeEditSurfaceActivity,
    ): Int {
        val deadline = System.currentTimeMillis() + 30_000
        var selectable: Int? = null
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                selectable = activity.firstSelectableItemIdForTest()
            }
            if (selectable != null) break
            Thread.sleep(300)
        }
        return selectable ?: error("no selectable item on the diagram")
    }

    // --- state, evidence and fixture helpers ---

    /**
     * Brings THIS app's launcher to the foreground as the device home. The
     * snackbar (and every evidence surface) lives on the launcher's own
     * window: with a different home app in the foreground (the emulator's
     * default system launcher) the launcher window is background — the write
     * path and the snackbar view work, but nothing is rendered or exposed to
     * the accessibility tree.
     */
    private fun bringLauncherToFront() {
        shell("cmd package set-home-activity app.lawnchair.debug/.LawnchairLauncher")
        device.pressHome()
        val deadline = System.currentTimeMillis() + 30_000
        var focused = false
        while (System.currentTimeMillis() < deadline) {
            val launcher = app.lawnchair.LawnchairLauncher.instance
            if (launcher != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    focused = launcher.hasWindowFocus()
                }
                if (focused) {
                    // The launcher's own startup loads must drain before the
                    // confirm — a ModelWriter direct-edit task created against
                    // a superseded load id is silently dropped. The deferred
                    // (inflated-item) binds ALSO matter: their flush closes
                    // every open floating view, i.e. a snackbar shown before
                    // the flush dies within ~1s (bindInflatedItems →
                    // closeOpenViews). Wait out a quiet window that covers
                    // both before any UI evidence runs.
                    waitForModelSettled()
                    Thread.sleep(3_000)
                    waitForModelSettled()
                    Thread.sleep(2_000)
                    waitForModelSettled()
                    return
                }
            }
            Thread.sleep(300)
        }
        error("the launcher did not come to the foreground with window focus")
    }

    private fun waitForModelSettled() {
        val model = appState.model
        val bgField = com.android.launcher3.LauncherModel::class.java.getDeclaredField("mBgDataModel")
        bgField.isAccessible = true
        val bgDataModel = bgField.get(model) as com.android.launcher3.model.BgDataModel
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            if (model.isModelLoaded && bgDataModel.lastLoadId == model.lastLoadId) {
                return
            }
            Thread.sleep(100)
        }
        error("the model did not settle (lastLoadId " + bgDataModel.lastLoadId +
            " != current " + model.lastLoadId + ")")
    }

    /** [cappedMillis]-bounded variant for in-window use (the snackbar lives ~4s). */
    private fun waitForModelSettled(cappedMillis: Long) {
        val model = appState.model
        val bgField = com.android.launcher3.LauncherModel::class.java.getDeclaredField("mBgDataModel")
        bgField.isAccessible = true
        val bgDataModel = bgField.get(model) as com.android.launcher3.model.BgDataModel
        val deadline = System.currentTimeMillis() + cappedMillis
        while (System.currentTimeMillis() < deadline) {
            if (model.isModelLoaded && bgDataModel.lastLoadId == model.lastLoadId) {
                return
            }
            Thread.sleep(100)
        }
    }

    private fun undoActionText(): String = context.getString(com.android.launcher3.R.string.undo)

    /** Waits for the production undo snackbar's action view and returns it. */
    private fun awaitUndoAction(): UiObject2 {
        val action = device.wait(Until.findObject(By.text(undoActionText())), 15_000)
        if (action == null) {
            // Diagnostic: what is actually on screen when the snackbar never
            // came (rejection Toast, missing write, wrong node text...).
            runCatching { device.dumpWindowHierarchy(evidenceFile("failure-window-dump", "xml")) }
            runCatching { device.takeScreenshot(evidenceFile("failure-screen")) }
        }
        assertNotNull("the undo snackbar action did not appear", action)
        return action!!
    }

    private fun awaitSnackbarDismissed() {
        device.wait(Until.gone(By.text(undoActionText())), 10_000)
    }

    private fun awaitUndoState(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(250)
        }
        // Failure evidence: what is on screen when the undo did not land —
        // a typed rejection Toast text is visible in the dump — plus the
        // exact favorites rows at failure time.
        runCatching { device.dumpWindowHierarchy(evidenceFile("undo-not-reached-dump", "xml")) }
        runCatching { device.takeScreenshot(evidenceFile("undo-not-reached-screen")) }
        runCatching {
            appState.model.modelDbController.db.rawQuery(
                "SELECT ${Favorites._ID}, ${Favorites.CONTAINER}, ${Favorites.SCREEN}, " +
                    "${Favorites.CELLX}, ${Favorites.CELLY}, ${Favorites.ITEM_TYPE}, ${Favorites.RANK} " +
                    "FROM ${Favorites.TABLE_NAME} ORDER BY ${Favorites._ID}",
                null,
            ).use { cursor ->
                val rows = StringBuilder()
                while (cursor.moveToNext()) {
                    rows.append("[id=").append(cursor.getLong(0))
                        .append(" c=").append(cursor.getLong(1))
                        .append(" s=").append(cursor.getInt(2))
                        .append(" x=").append(cursor.getInt(3))
                        .append(" y=").append(cursor.getInt(4))
                        .append(" t=").append(cursor.getInt(5))
                        .append(" r=").append(cursor.getInt(6))
                        .append("] ")
                }
                android.util.Log.d("UndoUiEvidence", "rows at undo-failure: $rows")
            }
        }
        error("undo state not reached: $what")
    }

    /** The id of the (single) real folder row, once it exists. */
    private fun awaitFolderRowId(launcher: LawnchairLauncher): Int {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val folder = buildHomeEditSnapshot(launcher).items.firstOrNull {
                it.itemType == HomeEditItemTypes.FOLDER && it.id > 0
            }
            if (folder != null) return folder.id
            Thread.sleep(250)
        }
        error("the target folder row was not found after the creation edit")
    }

    /** Reads a row through the shared production stage-1 projection. */
    private fun itemById(id: Int): HomeEditItem? = buildHomeEditSnapshot(context).itemById(id)

    /** The first seeded application row on [screenId] through the same projection. */
    private fun desktopItem(launcher: LawnchairLauncher, screenId: Int, excludeIds: List<Int> = emptyList()): HomeEditItem =
        buildHomeEditSnapshot(launcher).items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == screenId &&
                it.id > 0 && it.id !in excludeIds &&
                it.itemType == HomeEditItemTypes.APPLICATION
        }

    /** A Chrome application row currently at this desktop placement. */
    private fun chromeRowAt(screenId: Int, cellX: Int, cellY: Int): Boolean {
        appState.model.modelDbController.db.rawQuery(
            "SELECT COUNT(*) FROM ${Favorites.TABLE_NAME} " +
                "WHERE ${Favorites.ITEM_TYPE} = ? AND ${Favorites.INTENT} LIKE ? " +
                "AND ${Favorites.CONTAINER} = ? AND ${Favorites.SCREEN} = ? " +
                "AND ${Favorites.CELLX} = ? AND ${Favorites.CELLY} = ?",
            arrayOf(
                Favorites.ITEM_TYPE_APPLICATION.toString(),
                "%com.android.chrome/%",
                Favorites.CONTAINER_DESKTOP.toString(),
                screenId.toString(),
                cellX.toString(),
                cellY.toString(),
            ),
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0) > 0
        }
    }

    private fun folderRowCount(): Int =
        buildHomeEditSnapshot(context).items.count { it.itemType == HomeEditItemTypes.FOLDER }

    // --- AC-9 helpers ---

    /** The snackbar label/action nodes across all windows (TalkBack may add its own). */
    private fun awaitSnackbarNodes(
        labelText: String,
        actionText: String,
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val deadline = System.currentTimeMillis() + 15_000
        var label: AccessibilityNodeInfo? = null
        var action: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < deadline && (label == null || action == null)) {
            for (window in InstrumentationRegistry.getInstrumentation().uiAutomation.windows) {
                val root = try {
                    window.root
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (label == null) {
                    label = root.findAccessibilityNodeInfosByText(labelText)
                        .firstOrNull { it.viewIdResourceName?.endsWith(":id/label") == true }
                }
                if (action == null) {
                    action = root.findAccessibilityNodeInfosByText(actionText)
                        .firstOrNull { it.viewIdResourceName?.endsWith(":id/action") == true }
                }
            }
            Thread.sleep(250)
        }
        assertNotNull("the snackbar label node was not found in the accessibility tree", label)
        assertNotNull("the snackbar action node was not found in the accessibility tree", action)
        return label!! to action!!
    }

    private fun recordAccessibilityEvidence(
        labelText: String,
        actionText: String,
        labelNode: AccessibilityNodeInfo,
        actionNode: AccessibilityNodeInfo,
    ) {
        val record = buildString {
            appendLine("Issue #450 AC-9 evidence: real undo snackbar under TalkBack (emulator nunu_qpr2_api36_1)")
            appendLine("recorded: ${java.time.Instant.now()}")
            appendLine()
            appendLine("label node (expected contains \"$labelText\"):")
            appendNode(labelNode)
            appendLine()
            appendLine("action node (expected \"$actionText\"):")
            appendNode(actionNode)
        }
        evidenceFile("ac9-node-record", "txt").writeText(record)
    }

    private fun StringBuilder.appendNode(node: AccessibilityNodeInfo) {
        appendLine("  viewId: ${node.viewIdResourceName}")
        appendLine("  text: ${node.text}")
        appendLine("  class: ${node.className}")
        appendLine("  visibleToUser: ${node.isVisibleToUser}")
        appendLine("  clickable: ${node.isClickable}")
        appendLine("  focusable: ${node.isFocusable}")
        appendLine("  bounds: ${node.boundsInScreen}")
    }

    private fun awaitTalkBackBound() {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val dumpsys = shell("dumpsys accessibility")
            if (dumpsys.contains("com.google.android.marvin.talkback") &&
                dumpsys.contains("bound", ignoreCase = true)
            ) {
                return
            }
            Thread.sleep(500)
        }
        error("TalkBack did not bind within 30s (ac9 evidence requires the service enabled)")
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private fun restoreSecureSetting(key: String, previous: String) {
        if (previous.isEmpty() || previous == "null") {
            shell("settings delete secure $key")
        } else {
            shell("settings put secure $key $previous")
        }
    }

    // --- evidence capture (asserted success) ---

    private fun evidenceFile(label: String, ext: String = "png"): java.io.File {
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null) ?: error("external files dir unavailable")
        val out = java.io.File(dir, "450-edit-undo-$label.$ext")
        out.parentFile?.mkdirs()
        return out
    }

    /**
     * Evidence capture with ASSERTED success (round 11 finding 2): a failed
     * capture fails the oracle instead of degrading to a missing artifact.
     */
    private fun captureEvidence(label: String) {
        val out = evidenceFile(label)
        val captured = device.takeScreenshot(out)
        assertTrue("screenshot capture failed: $label", captured)
        assertTrue("screenshot file empty: $label", out.length() > 0)
    }

    // --- DB fixtures (same contract as EditSurfaceUndoInstrumentationTest) ---

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
        appState.model.forceReload()
        waitForModelSettled()
    }

    /**
     * Seeds a folder row with two children in the stable on-DB shape (cell
     * -1/-1, rank ordered — what the create task writes). Seeded rows carry
     * no direct-edit OPTIONS bit, so a single-child seed folder is flattened
     * back to an icon by the launcher's bind-time cleanup; two children keep
     * it a stable target. (A popup-created single-child folder is exempt via
     * the persisted OPTIONS bit — see directEditCreatedFolderSurvivesReload.)
     */
    private fun seedFolderWithTwoChildren(folderId: Int, screen: Int, cellX: Int, cellY: Int) {
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            val folder = ContentValues().apply {
                put(Favorites._ID, folderId.toLong())
                put(Favorites.TITLE, "Undo evidence folder $folderId")
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                put(Favorites.RANK, 0)
                put(Favorites.PROFILE_ID, currentProfileSerial())
            }
            db.insertOrThrow(Favorites.TABLE_NAME, null, folder)
            var rank = 0
            for (childId in listOf(911L, 912L)) {
                val child = ContentValues().apply {
                    put(Favorites._ID, childId)
                    put(Favorites.TITLE, "Undo evidence fixture $childId")
                    put(
                        Favorites.INTENT,
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main"))
                            .toUri(0),
                    )
                    put(Favorites.CONTAINER, folderId)
                    put(Favorites.SCREEN, 0)
                    put(Favorites.CELLX, -1)
                    put(Favorites.CELLY, -1)
                    put(Favorites.SPANX, 1)
                    put(Favorites.SPANY, 1)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                    put(Favorites.RANK, rank)
                    put(Favorites.PROFILE_ID, currentProfileSerial())
                }
                db.insertOrThrow(Favorites.TABLE_NAME, null, child)
                rank++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        appState.model.forceReload()
    }

    /** Folder + one child in the stable on-DB shape (cell -1/-1, rank 0). */
    private fun seedSingleChildFolder(folderId: Int, screen: Int, cellX: Int, cellY: Int, childId: Long) {
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            val folder = ContentValues().apply {
                put(Favorites._ID, folderId.toLong())
                put(Favorites.TITLE, "Undo evidence folder $folderId")
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                put(Favorites.RANK, 0)
                put(Favorites.PROFILE_ID, currentProfileSerial())
            }
            db.insertOrThrow(Favorites.TABLE_NAME, null, folder)
            val child = ContentValues().apply {
                put(Favorites._ID, childId)
                put(Favorites.TITLE, "Undo evidence fixture $childId")
                put(
                    Favorites.INTENT,
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main"))
                        .toUri(0),
                )
                put(Favorites.CONTAINER, folderId)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, -1)
                put(Favorites.CELLY, -1)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.RANK, 0)
                put(Favorites.PROFILE_ID, currentProfileSerial())
            }
            db.insertOrThrow(Favorites.TABLE_NAME, null, child)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        appState.model.forceReload()
    }

    private fun currentProfileSerial(): Long =
        com.android.launcher3.pm.UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(android.os.Process.myUserHandle())

    private fun desktopRowValues(id: Long, screen: Int, cellX: Int, cellY: Int): ContentValues = ContentValues().apply {
        put(Favorites._ID, id)
        put(Favorites.TITLE, "Undo evidence fixture $id")
        put(
            Favorites.INTENT,
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main"))
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
        put(Favorites.PROFILE_ID, currentProfileSerial())
    }

    /**
     * Fixture contract (review round 11 finding 1): the seeded rows point at
     * a foreign, provisioned component — the launcher-self component made the
     * post-apply workspace loading mutate the DB (folder-expansion write)
     * inside the undo windows. Assert the fixture's presence up front so a
     * missing provision fails with an actionable message instead of a layout
     * race.
     */
    private fun assertChromeFixtureAvailable() {
        val fixtureIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main"))
        val resolved = context.packageManager.resolveActivity(
            fixtureIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (resolved == null) {
            error(
                "undo fixture missing: com.android.chrome must be provisioned on the " +
                    "test device — the seeded rows require a stable foreign component " +
                    "(launcher-self seeds mutate the DB inside the undo windows)",
            )
        }
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
}
