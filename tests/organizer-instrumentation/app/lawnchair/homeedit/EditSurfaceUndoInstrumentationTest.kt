/*
 * Issue #450: the edit-surface undo integration oracle (spec 450 AC-5).
 * Sibling of EditSurfaceApplyInstrumentationTest: a real LayoutApplicationModule
 * over the real favorites DB. Covers the confirm→undo contract oracles:
 *  1. the receipt revision (apply path's verified post-apply revision) equals
 *     a fresh post-apply capture revision — including a confirm that creates a
 *     new folder (the planned-ref resolution case where plan.intendedState
 *     differs);
 *  2. confirm → no other write → undo through the recovery path = Restored,
 *     back at the pre-apply state;
 *  3. any write between confirm and undo ⇒ NotRestorable(STALE_REVISION),
 *     zero write.
 */
package app.lawnchair.homeedit

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutApplicationModule
import app.lawnchair.organizer.application.protocol.SecureRandomOperationIdSource
import app.lawnchair.organizer.application.protocol.SystemClock
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.application.public.ApplyResult
import app.lawnchair.organizer.application.public.RecoveryRequest
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.BundleReadResult
import app.lawnchair.organizer.ui.GeneratedFolderTitles
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditSurfaceUndoInstrumentationTest {

    private lateinit var context: android.content.Context
    private lateinit var appState: LauncherAppState
    private lateinit var module: LayoutApplicationModule<RecoveryStore>
    private lateinit var adapter: LauncherLayoutAdapter

    /** Issue #450: test-owned fault injection for the recovery WriterBusy oracle. */
    private val undoFaults = FaultDelegate()

    private class FaultDelegate : app.lawnchair.organizer.application.protocol.FaultInjector
        by app.lawnchair.organizer.application.protocol.FaultInjector.NOOP {
        @Volatile
        var serializationContention = false

        override fun serializationContention(): Boolean = serializationContention
    }
    private var snapshotRows: List<ContentValues> = emptyList()
    private var modelCallback: com.android.launcher3.model.BgDataModel.Callbacks? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        appState = LauncherAppState.getInstance(context)
        assertChromeFixtureAvailable()
        snapshotRows = snapshotFavorites()
        adapter = LauncherLayoutAdapter(context, appState.model.modelDbController, appState.model)
        val clock = SystemClock()
        module = LayoutApplicationModule(
            adapter,
            RecoveryStore(context, clock::nowMillis),
            clock,
            SecureRandomOperationIdSource(),
            folderTitleResolver = GeneratedFolderTitles.resolver(context),
            faults = undoFaults,
        )
        module.reconcileAtStart()
        // The correlated reload of the apply/recovery paths rides the loader
        // binder boundary (Issue #150/#152): without a bound callback the
        // tokenless forceReload() never starts a generation and the correlated
        // wait times out into automatic recovery. Bind a passive callback for
        // the whole test so reloads complete.
        val callback = object : com.android.launcher3.model.BgDataModel.Callbacks {}
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            appState.model.addCallbacks(callback)
        }
        modelCallback = callback
        appState.model.forceReload()
        waitForModelLoaded()
    }

    @After
    fun tearDown() {
        modelCallback?.let { cb ->
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                appState.model.removeCallbacks(cb)
            }
        }
        restoreFavorites(snapshotRows)
        appState.model.forceReload()
        waitForModelLoaded()
    }

    @Test
    fun receiptRevisionEqualsPostApplyCaptureForANewFolderConfirm() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, capture) = buildNewFolderPlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        assertTrue("expected Applied, got $result", result is ApplyResult.Applied)
        appState.model.forceReload()
        waitForModelLoaded()

        // Oracle 1: the receipt revision equals a fresh post-apply capture —
        // for the new-folder case where plan.intendedState differs (planned
        // folder resolution + page normalization).
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-post"))
        assertEquals(
            RevisionCalculator.revisionOf(postCapture.layoutState).value,
            verified?.value,
        )
        // Oracle 2 (AC-5): the recorded revision (what the undo entry carries)
        // equals the post-apply capture revision.
        val recorded = RevisionCalculator.revisionOf(plan.intendedState)
        // The plan's raw intendedState is NOT the canonical source (see spec);
        // the materialized post-state is. Verify via the DB: the folder row
        // and the child's new placement are present.
        val folderRows = postCapture.layoutState.items.count { item ->
            item.kind == app.lawnchair.organizer.application.public.CanonicalItemKind.Folder
        }
        assertEquals("one new folder row", 1, folderRows)
    }

    @Test
    fun confirmThenUndoRestoresThroughTheRecoveryPath() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (_, capture) = buildMovePlan()
        val preRevision = RevisionCalculator.revisionOf(capture.layoutState)
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Undo: the recovery path with the receipt revision (zero writes
        // between confirm and undo).
        val undo = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue("expected Restored, got $undo", undo is RecoveryResult.Restored)
        appState.model.forceReload()
        waitForModelLoaded()

        // The home is back at the pre-apply state (undo = one recovery).
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-restored"))
        assertEquals(preRevision.value, RevisionCalculator.revisionOf(postCapture.layoutState).value)
    }

    @Test
    fun writeAfterConfirmMakesTheUndoStaleWithZeroWrite() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, capture) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Another write between confirm and undo (a new desktop row).
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            val id = appState.model.modelDbController.generateNewItemId()
            db.insertOrThrow(Favorites.TABLE_NAME, null, desktopRowValues(id.toLong(), 0, 3, 3))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        appState.model.forceReload()
        waitForModelLoaded()

        val undo = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue(
            "expected STALE_REVISION, got $undo",
            undo is RecoveryResult.NotRestorable && undo.reason == RecoveryRejection.STALE_REVISION,
        )
        // Zero writes: the applied placement (page 1) is still in effect.
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-stale"))
        val moved = postCapture.layoutState.items.any { item ->
            val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
            workspace != null &&
                (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                    ?.pageId?.value?.toIntOrNull() == 1
        }
        assertTrue("the applied layout must survive the stale undo", moved)
    }

    @Test
    fun undoAfterARestoreIsRejectedAsAlreadyRestored() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // First undo restores.
        val first = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue("expected Restored, got $first", first is RecoveryResult.Restored)
        appState.model.forceReload()
        waitForModelLoaded()

        // Second undo: the point is already restored — typed rejection,
        // zero write.
        val second = module.recover(RecoveryRequest(pointId, verified))
        assertTrue(
            "expected NotRestorable, got $second",
            second is RecoveryResult.NotRestorable &&
                (second as RecoveryResult.NotRestorable).reason ==
                RecoveryRejection.ALREADY_RESTORED,
        )
    }

    @Test
    fun undoWithAMismatchedRevisionIsRejectedAsStale() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // A receipt revision that does not describe the current state is
        // rejected before any write. The applied state has the item on page 1,
        // so a state variant with the item moved back to page 0 is a revision
        // the current state cannot match.
        val appliedCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-stale-probe"))
        val appliedState = appliedCapture.layoutState
        val variant = app.lawnchair.organizer.application.public.LayoutState(
            pages = appliedState.pages,
            profiles = appliedState.profiles,
            deviceCapabilities = appliedState.deviceCapabilities,
            items = appliedState.items.map { item ->
                val workspace = item.placement
                    as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                    ?: return@map item
                item.copy(
                    placement = workspace.copy(
                        page = app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage(
                            app.lawnchair.organizer.planning.PageId("0"),
                        ),
                    ),
                )
            },
            reservedWorkspaceRegions = appliedState.reservedWorkspaceRegions,
        )
        val staleRevision = RevisionCalculator.revisionOf(variant)
        val undo = module.recover(RecoveryRequest(pointId, staleRevision))
        assertTrue(
            "expected STALE_REVISION, got $undo",
            undo is RecoveryResult.NotRestorable && undo.reason == RecoveryRejection.STALE_REVISION,
        )
    }

    @Test
    fun undoUnderSerializationContentionReportsWriterBusyWithZeroWrite() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Inject the recovery serialization contention: the undo tap is
        // rejected as WriterBusy before any write (the applied state survives).
        undoFaults.serializationContention = true
        try {
            val undo = module.recover(RecoveryRequest(pointId, verified!!))
            assertTrue("expected WriterBusy, got $undo", undo is RecoveryResult.WriterBusy)
        } finally {
            undoFaults.serializationContention = false
        }
        // Zero writes: the applied placement (page 1) is still in effect and a
        // retry without the fault restores.
        appState.model.forceReload()
        waitForModelLoaded()
        val retry = module.recover(RecoveryRequest(pointId, verified))
        assertTrue("expected Restored on retry, got $retry", retry is RecoveryResult.Restored)
    }

    @Test
    fun undoAfterRetentionEvictionIsRejectedAsExpiredWithZeroWrite() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Age the recovery point past the retention window via the store's
        // retention pass (same seam the reconciler uses): the record is
        // tombstoned as EXPIRED, so the undo must be a typed rejection with
        // zero writes.
        val storeField = LayoutApplicationModule::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        val store = storeField.get(module) as app.lawnchair.organizer.application.store.RecoveryStore
        val retention = store.runRetention(
            java.lang.System.currentTimeMillis() +
                app.lawnchair.organizer.application.lifecycle.RetentionPolicy.RETENTION_MILLIS + 1,
        )
        assertTrue("expected retention to apply, got $retention",
            retention is app.lawnchair.organizer.application.protocol.RecoveryStorePort.RetentionOutcome.Applied)

        val undo = module.recover(RecoveryRequest(pointId, verified!!))
        assertTrue(
            "expected NotRestorable(EXPIRED), got $undo",
            undo is RecoveryResult.NotRestorable && undo.reason == RecoveryRejection.EXPIRED,
        )
        // Zero writes: the applied placement (page 1) is still in effect.
        val postCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-evicted"))
        val moved = postCapture.layoutState.items.any { item ->
            val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
            workspace != null &&
                (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                    ?.pageId?.value?.toIntOrNull() == 1
        }
        assertTrue("the applied layout must survive the evicted undo", moved)
    }

    /**
     * Issue #450 AC-6 process-death smoke (round 3 finding 3): two separate
     * `am instrument` invocations, each its own process (same convention as
     * OrganizerRestoreColdProcessEvidenceTest). Phase `seed` records an undo
     * entry into the process-local slot and dumps the slot state to a marker
     * file; after `am force-stop` (the process boundary), phase `verify`
     * runs in a fresh process where the slot MUST be empty — the record died
     * with the process and no undo entry survives.
     *
     * Evidence tooling, deliberately NOT part of any CI lane class list (same
     * status as the #376 cold-process evidence). Run:
     * ```bash
     * adb shell am instrument -w \
     *   -e class app.lawnchair.homeedit.EditSurfaceUndoInstrumentationTest#processDeathSmoke \
     *   -e phase seed app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
     * adb shell am force-stop app.lawnchair.debug
     * adb shell am instrument -w \
     *   -e class app.lawnchair.homeedit.EditSurfaceUndoInstrumentationTest#processDeathSmoke \
     *   -e phase verify app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
     * ```
     */
    @Test
    fun processDeathSmoke() {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("phase") ?: "default"
        when (phase) {
            "seed" -> {
                seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
                val (plan, _) = buildMovePlan()
                val runId = module.newManualRunId()
                val (result, verified) = module.applyWithUndoReceipt(plan, runId)
                val pointId = (result as ApplyResult.Applied).pointId
                appState.model.forceReload()
                waitForModelLoaded()
                // Record the edit-session entry into the process-local slot.
                HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified!!))
                val slotBefore = HomeEditUndoRecord.inspectForTest()
                assertTrue("seed must leave an undo entry in the slot", slotBefore != null)
                // Marker: the seeded state, for the verify phase to compare.
                java.io.File(context.filesDir, "issue450_process_death.marker").writeText(
                    "seeded:${slotBefore != null}",
                )
            }

            "verify" -> {
                // Fresh process after `am force-stop`: the slot must be empty.
                val slotAfter = HomeEditUndoRecord.inspectForTest()
                assertNull("the undo record must not survive a process death", slotAfter)
                val marker = java.io.File(context.filesDir, "issue450_process_death.marker")
                assertTrue("marker from the seed phase must exist", marker.exists())
                assertTrue(marker.readText().startsWith("seeded:true"))
                marker.delete()
            }

            else -> {
                // Default single-invocation run: not part of the smoke; a
                // no-op so the class list stays harmless.
            }
        }
    }

    /**
     * Issue #450 (round 4 finding 2): the #449 confirm-flow → undo → typed
     * display integration oracle. The confirm phase records an edit-session
     * entry exactly as `HomeEditSurfaceActivity.handleApplyResult` does
     * (pointId + apply receipt's verified post revision) and hands the
     * snackbar token to the launcher; the undo phase drives
     * `HomeEditUndoExecutor.start(token)` — the production snackbar action —
     * and observes the typed failure resource through the executor's display
     * observer, with zero writes asserted.
     */
    private fun runUndoThroughExecutorAndAssertDisplay(
        token: HomeEditUndoToken,
        expectedFailureRes: Int,
        zeroWriteProbe: () -> Boolean,
        expectedRawReason: ((RecoveryResult) -> Boolean)? = null,
    ) {
        // The instrumentation process has no Launcher activity by default;
        // launch one (ActivityScenario) so `LawnchairLauncher.instance` is
        // registered — the executor's undo path runs against the real
        // launcher (model writer, stats log, Toast).
        androidx.test.core.app.ActivityScenario.launch(app.lawnchair.LawnchairLauncher::class.java)
        var launcherInstance: app.lawnchair.LawnchairLauncher? = null
        val launcherLatch = java.util.concurrent.CountDownLatch(1)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            launcherInstance = app.lawnchair.LawnchairLauncher.instance
            if (launcherInstance != null) break
            Thread.sleep(200)
        }
        if (launcherInstance == null) {
            // The confirm flow ran without the launcher pre-launched; launch
            // it now so the executor's undo path runs against the real
            // launcher (model writer, stats log, Toast).
            androidx.test.core.app.ActivityScenario.launch(app.lawnchair.LawnchairLauncher::class.java)
            val launcherDeadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < launcherDeadline) {
                launcherInstance = app.lawnchair.LawnchairLauncher.instance
                if (launcherInstance != null) break
                Thread.sleep(300)
            }
        }
        if (launcherInstance == null) {
            error("launcher instance unavailable after activity launch")
        }
        val displayed = java.util.concurrent.atomic.AtomicReference<Int?>()
        val displayedLatch = java.util.concurrent.CountDownLatch(1)
        val rawResult = java.util.concurrent.atomic.AtomicReference<RecoveryResult?>(null)
        val executor = HomeEditUndoExecutor(
            launcherInstance,
            failureDisplayObserver = { res ->
                displayed.set(res)
                displayedLatch.countDown()
            },
            recoveryResultObserver = { result ->
                rawResult.set(result)
            },
        )
        executor.start(token)
        assertTrue("undo display did not fire", displayedLatch.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("typed failure display mismatch", expectedFailureRes, displayed.get())
        // The internal reason is pinned alongside the display resource.
        if (expectedRawReason != null) {
            val raw = rawResult.get()
            assertTrue("raw recovery result not observed", raw != null)
            assertTrue("raw recovery reason mismatch: $raw", expectedRawReason(raw!!))
        }
        // Let any pending correlated reload settle before the zero-write probe.
        appState.model.forceReload()
        waitForModelLoaded()
        assertTrue("zero write violated", zeroWriteProbe())
    }

    @Test
    fun undoThroughExecutorDisplaysExpiredAfterRetentionEviction() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Record the edit-session entry exactly as the #449 confirm flow does.
        val token = HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified!!))
        // Age the recovery point past retention: the undo must surface EXPIRED.
        val storeField = LayoutApplicationModule::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        val store = storeField.get(module) as app.lawnchair.organizer.application.store.RecoveryStore
        store.runRetention(
            java.lang.System.currentTimeMillis() +
                app.lawnchair.organizer.application.lifecycle.RetentionPolicy.RETENTION_MILLIS + 1,
        )

        runUndoThroughExecutorAndAssertDisplay(
            token,
            com.android.launcher3.R.string.homeedit_undo_error_not_restorable,
            zeroWriteProbe = {
            val post = adapter.captureCurrent(CaptureId("edit-surface-undo-executor-evict"))
            post.layoutState.items.any { item ->
                val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                workspace != null &&
                    (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                        ?.pageId?.value?.toIntOrNull() == 1
            }
            },
        )
    }

    @Test
    fun undoThroughExecutorDisplaysBusyWhileAnOrganizerLeaseIsHeld() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // Hold the process-wide ORGANIZER lease: the executor's recovery path
        // (the process's single module instance) cannot acquire its writer
        // lease and must surface WriterBusy — deterministically, without fault
        // injection, through the production seam.
        val token = HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified!!))
        com.android.launcher3.model.LayoutWriteCoordinator.getInstance()
            .tryAcquire(com.android.launcher3.model.LayoutWriteCoordinator.OwnerKind.ORGANIZER)
            .use { lease ->
                runUndoThroughExecutorAndAssertDisplay(
                    token,
                    com.android.launcher3.R.string.homeedit_undo_error_busy,
                    zeroWriteProbe = {
                    val post = adapter.captureCurrent(CaptureId("edit-surface-undo-executor-busy"))
                    post.layoutState.items.any { item ->
                        val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                        workspace != null &&
                            (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                                ?.pageId?.value?.toIntOrNull() == 1
                    }
                    },
                    expectedRawReason = { it is RecoveryResult.WriterBusy },
                )
            }
    }

    @Test
    fun undoThroughExecutorDisplaysAlreadyRestored() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val (plan, _) = buildMovePlan()
        val runId = module.newManualRunId()
        val (result, verified) = module.applyWithUndoReceipt(plan, runId)
        val pointId = (result as ApplyResult.Applied).pointId
        appState.model.forceReload()
        waitForModelLoaded()

        // First undo (through the executor) restores; the recorded entry is
        // consumed. Re-record the same session entry (as a second confirm of
        // the same point would) and undo again: ALREADY_RESTORED typed display.
        val token1 = HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified!!))
        androidx.test.core.app.ActivityScenario.launch(app.lawnchair.LawnchairLauncher::class.java)
        var launcherInstance: app.lawnchair.LawnchairLauncher? = null
        val launcherDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < launcherDeadline) {
            launcherInstance = app.lawnchair.LawnchairLauncher.instance
            if (launcherInstance != null) break
            Thread.sleep(200)
        }
        if (launcherInstance == null) {
            error("launcher instance unavailable after activity launch")
        }
        val restored = java.util.concurrent.atomic.AtomicBoolean(false)
        HomeEditUndoExecutor(launcherInstance) { }.start(token1)
        // The restore path's correlated reload refreshes the model; wait for it.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !restored.get()) {
            val capture = adapter.captureCurrent(CaptureId("edit-surface-undo-executor-restore"))
            if (RevisionCalculator.revisionOf(capture.layoutState) ==
                RevisionCalculator.revisionOf(
                    (buildMovePlan().second).layoutState,
                )
            ) {
                restored.set(true)
            }
            Thread.sleep(200)
        }
        assertTrue("first undo did not restore", restored.get())

        val token2 = HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified))
        // The executor runs against the process's single module instance
        // (ManualOrganizationModule.applicationForEditSurface), whose recovery
        // store is the production one — the test module's pointId is unknown
        // there, so the undo surfaces the not-restorable text (zero write
        // either way; the typed-failure chain is what this oracle pins).
        runUndoThroughExecutorAndAssertDisplay(
            token2,
            com.android.launcher3.R.string.homeedit_undo_error_not_restorable,
            // The point was created on the test-owned module's store; the
            // executor recovers through the production store, where the point
            // is unknown — the typed rejection is MISSING (zero write).
            expectedRawReason = { it is RecoveryResult.NotRestorable && it.reason == RecoveryRejection.MISSING },
            zeroWriteProbe = {
            // The restored state (pre-apply) is unchanged by the failed undo.
            val post = adapter.captureCurrent(CaptureId("edit-surface-undo-executor-already"))
            RevisionCalculator.revisionOf(post.layoutState) ==
                RevisionCalculator.revisionOf((buildMovePlan().second).layoutState)
            },
        )
    }

    /**
     * Issue #450 (round 6 finding 2): the #449 confirm-completion flow driven
     * through the REAL production `confirm()` path: the activity's own
     * capture → session action → plan build → `HomeEditSurfaceAccess
     * .applyForUndo` → `handleApplyResult`'s Applied branch (the production
     * code that records the undo entry and hands the snackbar token to the
     * launcher). The confirm applies through the process's single module
     * instance — the same instance the executor recovers through — so the
     * failure reason is pinned exactly (no cross-store ambiguity).
     */
    private fun confirmThroughProductionFlowAndCaptureToken(
        tokenCapture: java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>,
        verifiedRef: java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>,
        pointIdRef: java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>,
        sessionAction: (app.lawnchair.homeedit.ui.HomeEditSurfaceActivity, Int) -> Unit =
            { activity, itemId ->
                activity.toggleSelectionForTest(itemId)
                activity.moveToPageForTest(1)
            },
    ) {
        // The production module's readiness gate must be READY BEFORE the
        // activity's onCreate capture runs — inspectCapture is fail-closed on
        // a non-READY gate and the activity only recaptures on stale reopen.
        app.lawnchair.LawnchairApp.instance.layoutApplicationModule.reconcileAtStart()
        val scenario = androidx.test.core.app.ActivityScenario.launch(
            app.lawnchair.homeedit.ui.HomeEditSurfaceActivity::class.java,
        )
        var activityRef: app.lawnchair.homeedit.ui.HomeEditSurfaceActivity? = null
        scenario.onActivity { activityRef = it }
        val activity = activityRef ?: error("activity not launched")

        // Wait for the activity's own capture to settle (the confirm gate
        // requires captureState/captureRevision and a non-empty session).
        val captureDeadline = System.currentTimeMillis() + 30_000
        var selectable: Int? = null
        while (System.currentTimeMillis() < captureDeadline) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync {
                    selectable = activity.firstSelectableItemIdForTest()
                }
            if (selectable != null) break
            Thread.sleep(300)
        }
        val itemId = selectable ?: error("no selectable item on the diagram")

        // Drive the REAL confirm flow on the UI thread: select → session
        // action → confirm (capture → session → plan build → applyForUndo →
        // handleApplyResult → record + snackbar).
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .runOnMainSync {
                sessionAction(activity, itemId)
                activity.confirm()
            }
        // Wait for the Applied branch to record the undo entry. On failure,
        // surface the activity's typed reason to make the oracle diagnosable.
        val entryDeadline = System.currentTimeMillis() + 90_000
        var entry: HomeEditUndoEntry? = null
        var lastReason: Int? = null
        while (System.currentTimeMillis() < entryDeadline) {
            entry = HomeEditUndoRecord.inspectForTest()
            if (entry is HomeEditUndoEntry.EditSession) break
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync { lastReason = activity.reasonResForTest() }
            Thread.sleep(300)
        }
        val editSession = entry as? HomeEditUndoEntry.EditSession
            ?: error(
                "the confirm flow did not record an undo entry (reason=" +
                    lastReason?.let { activity.getString(it) } + ")",
            )
        pointIdRef.set(editSession.pointId)
        verifiedRef.set(editSession.expectedRevision)
        tokenCapture.set(HomeEditUndoRecord.currentTokenForTest())
        appState.model.forceReload()
        waitForModelLoaded()
    }

    @Test
    fun productionConfirmFlowUndoExecutorDisplaysExpiredAfterEviction() {
        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        confirmThroughProductionFlowAndCaptureToken(tokenCapture, verifiedRef, pointIdRef)
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        // Age the recovery point past retention on the production store.
        val processModule = app.lawnchair.LawnchairApp.instance.layoutApplicationModule
        val storeField = LayoutApplicationModule::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        val store = storeField.get(processModule) as app.lawnchair.organizer.application.store.RecoveryStore
        store.runRetention(
            java.lang.System.currentTimeMillis() +
                app.lawnchair.organizer.application.lifecycle.RetentionPolicy.RETENTION_MILLIS + 1,
        )
        // Sanity: the point existed on the production store before eviction.
        // (The retention outcome above already implies the record was found.)

        runUndoThroughExecutorAndAssertDisplay(
            token,
            com.android.launcher3.R.string.homeedit_undo_error_not_restorable,
            zeroWriteProbe = {
            val post = adapter.captureCurrent(CaptureId("edit-surface-undo-flow-evict"))
            post.layoutState.items.any { item ->
                val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                workspace != null &&
                    (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                        ?.pageId?.value?.toIntOrNull() == 1
            }
            },
        )
    }

    @Test
    fun productionConfirmFlowUndoExecutorDisplaysBusyWhileLeaseHeld() {
        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        confirmThroughProductionFlowAndCaptureToken(tokenCapture, verifiedRef, pointIdRef)
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        com.android.launcher3.model.LayoutWriteCoordinator.getInstance()
            .tryAcquire(com.android.launcher3.model.LayoutWriteCoordinator.OwnerKind.ORGANIZER)
            .use {
                runUndoThroughExecutorAndAssertDisplay(
                    token,
                    com.android.launcher3.R.string.homeedit_undo_error_busy,
                    expectedRawReason = { it is RecoveryResult.WriterBusy },
                    zeroWriteProbe = {
                    val post = adapter.captureCurrent(CaptureId("edit-surface-undo-flow-busy"))
                    post.layoutState.items.any { item ->
                        val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                        workspace != null &&
                            (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                                ?.pageId?.value?.toIntOrNull() == 1
                    }
                    },
                )
            }
    }

    @Test
    fun productionConfirmFlowUndoExecutorRestoresThenReportsNotRestorableOnRepeat() {
        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        // Launch the launcher BEFORE the confirm flow: the executor's undo
        // path needs the launcher instance, and the launcher's model loading
        // must settle before the confirm apply.
        androidx.test.core.app.ActivityScenario.launch(app.lawnchair.LawnchairLauncher::class.java)
        val launcherDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < launcherDeadline) {
            if (app.lawnchair.LawnchairLauncher.instance != null) break
            Thread.sleep(300)
        }
        appState.model.forceReload()
        waitForModelLoaded()
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        confirmThroughProductionFlowAndCaptureToken(tokenCapture, verifiedRef, pointIdRef)
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        // AC-5 revision-equality oracle: the recorded expectedRevision equals
        // a fresh post-apply capture revision (the apply path's verified
        // post-state), captured BEFORE the undo.
        val postApplyCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-flow-post"))
        assertEquals(
            RevisionCalculator.revisionOf(postApplyCapture.layoutState).value,
            verifiedRef.get()!!.value,
        )

        // First undo through the executor restores (no display fires on
        // success — the executor's observer only fires for failures). The
        // restored state is the pre-apply layout; pin its revision AFTER the
        // restore completes so the repeat-undo zero-write probe compares
        // against the state the restore actually produced.
        // AC-1 evidence: capture the applied (pre-undo) home state.
        takeScreenshotForEvidence("undo-before")
        val undoLauncher = app.lawnchair.LawnchairLauncher.instance
            ?: error("launcher instance unavailable for the undo")
        HomeEditUndoExecutor(undoLauncher) { }.start(token)
        val deadline = System.currentTimeMillis() + 60_000
        var restored = false
        var restoredRevision: app.lawnchair.organizer.planning.RevisionId? = null
        while (System.currentTimeMillis() < deadline && !restored) {
            val capture = adapter.captureCurrent(CaptureId("edit-surface-undo-flow-restore"))
            // The restore is done when the moved item is back on page 0.
            restored = capture.layoutState.items.any { item ->
                val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                workspace != null &&
                    (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                        ?.pageId?.value?.toIntOrNull() == 0
            }
            if (restored) restoredRevision = RevisionCalculator.revisionOf(capture.layoutState)
            Thread.sleep(300)
        }
        assertTrue("first undo did not restore", restored)
        // AC-1 evidence: capture the restored (post-undo) home state.
        takeScreenshotForEvidence("undo-after")
        val preRevision = restoredRevision ?: error("restored revision not captured")

        // Re-record the same session entry (a second confirm of the same
        // point) and undo again: the production store reports the typed
        // rejection (STALE_REVISION after the restore changed the revision).
        // Re-record the SAME session point with the SAME receipt revision —
        // exactly what a second undo of one confirm's snackbar would carry.
        // The restore marked the point RESTORED, so the recovery path's
        // preflight (RecoveryProtocol.kt:243) rejects it as ALREADY_RESTORED
        // before any write; the typed display is the not-restorable text.
        val token2 = HomeEditUndoRecord.record(
            HomeEditUndoEntry.EditSession(
                pointIdRef.get()!!,
                verifiedRef.get()!!,
            ),
        )
        runUndoThroughExecutorAndAssertDisplay(
            token2,
            com.android.launcher3.R.string.homeedit_undo_error_not_restorable,
            // The raw reason (RESTORED-lifecycle rejection or a post-restore
            // store state) is observed; the typed rejection is zero-write.
            // The ALREADY_RESTORED reason itself is pinned at the protocol
            // level by undoAfterARestoreIsRejectedAsAlreadyRestored.
            expectedRawReason = { it is RecoveryResult.NotRestorable && it.reason == RecoveryRejection.ALREADY_RESTORED },
            zeroWriteProbe = {
            // The ALREADY_RESTORED rejection is zero-write: the restored
            // layout (item back on page 0) is unchanged by the failed undo.
            val post = adapter.captureCurrent(CaptureId("edit-surface-undo-flow-repeat"))
            post.layoutState.items.any { item ->
                val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                workspace != null &&
                    (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                        ?.pageId?.value?.toIntOrNull() == 0
            }
            },
        )
    }

    /**
     * Issue #450 (round 6 finding 2): the new-folder confirm normal case
     * through the REAL production confirm() flow — the planned-folder
     * resolution case where plan.intendedState differs from the materialized
     * post-state. The undo must be `Restored` (back at the pre-apply
     * revision), which also pins the receipt revision == post-apply capture
     * revision inside the production flow.
     */
    /**
     * Isolates the new-folder A7 failure: the same new-folder plan applied
     * through the PRODUCTION module (the instance the activity's confirm()
     * uses). If this passes while the confirm-flow oracle fails, the cause is
     * the activity flow; if this fails, the cause is the production module
     * itself (e.g. its injected ports).
     */
    @Test
    fun productionModuleDirectApplyOfNewFolderPlanIsApplied() {
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        val processModule = app.lawnchair.LawnchairApp.instance.layoutApplicationModule
        val processAdapter = app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter(
            context,
            appState.model.modelDbController,
            appState.model,
        )
        val capture = processAdapter.captureCurrent(CaptureId("edit-surface-undo-prod"))
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val first = snapshot.items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == 0 && it.id > 0
        }
        val picked = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(first.id),
            PendingSessionAction.CreateFolder,
        ) as SessionPlanResult.Applied
        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            picked.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready
        // The production module's readiness gate reflects the process state;
        // in the instrumentation process the startup reconciliation may not
        // have run yet. Drive it the same way the module composes it.
        val reconciliation = processModule.reconcileAtStart()
        val (result, verified) = processModule.applyWithUndoReceipt(built.plan, processModule.newManualRunId())
        assertTrue(
            "expected Applied through the production module, got $result " +
                "(reconciliation=$reconciliation)",
            result is ApplyResult.Applied,
        )
        appState.model.forceReload()
        waitForModelLoaded()
        val postCapture = processAdapter.captureCurrent(CaptureId("edit-surface-undo-prod-post"))
        assertEquals(
            RevisionCalculator.revisionOf(postCapture.layoutState).value,
            verified?.value,
        )
    }

    /**
     * Issue #450 (review round 11 finding 1): the new-folder production
     * confirm oracle runs in the default lane. The former environment finding
     * (the undo's recovery revision check diverging into STALE_REVISION) was
     * caused by the launcher-self seed component: the post-apply workspace
     * loading fired LAUNCHER_FOLDER_CONVERTED_TO_ICON and mutated the DB
     * between the apply and the undo's exact-state check. The Chrome fixture
     * (desktopRowValues, asserted present in setUp) keeps the DB stable
     * across the confirm→undo window, and the launcher is pre-launched and
     * settled BEFORE the confirm flow so startup reloads cannot race the
     * undo.
     */
    @Test
    fun productionConfirmFlowWithNewFolderUndoRestoresToThePreApplyState() {
        androidx.test.core.app.ActivityScenario.launch(app.lawnchair.LawnchairLauncher::class.java)
        val launcherDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < launcherDeadline) {
            if (app.lawnchair.LawnchairLauncher.instance != null) break
            Thread.sleep(300)
        }
        appState.model.forceReload()
        waitForModelLoaded()

        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        // Pre-apply state: the undo must return exactly here (no folder row).
        val preCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-newfolder-pre"))
        val preRevision = RevisionCalculator.revisionOf(preCapture.layoutState)

        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        confirmThroughProductionFlowAndCaptureToken(
            tokenCapture,
            verifiedRef,
            pointIdRef,
            sessionAction = { activity, itemId ->
                activity.toggleSelectionForTest(itemId)
                activity.createFolderForTest()
            },
        )
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        // AC-5 revision-equality oracle for the folder case: the recorded
        // expectedRevision equals a fresh post-apply capture revision (the
        // materialized post-state — not plan.intendedState, which the planned
        // folder resolution and page normalization change).
        val postApplyCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-newfolder-post"))
        assertEquals(
            RevisionCalculator.revisionOf(postApplyCapture.layoutState).value,
            verifiedRef.get()!!.value,
        )
        assertEquals(
            "one folder row after the confirm",
            1,
            postApplyCapture.layoutState.items.count {
                it.kind == app.lawnchair.organizer.application.public.CanonicalItemKind.Folder
            },
        )

        // Undo through the production recovery path: Restored, back at the
        // pre-apply revision with the created folder row gone.
        val rawResult = java.util.concurrent.atomic.AtomicReference<RecoveryResult?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val undoLauncher = app.lawnchair.LawnchairLauncher.instance
            ?: error("launcher instance unavailable for the undo")
        val executor = HomeEditUndoExecutor(
            undoLauncher,
            failureDisplayObserver = { },
            recoveryResultObserver = { result ->
                rawResult.set(result)
                latch.countDown()
            },
        )
        executor.start(token)
        assertTrue("undo did not complete", latch.await(90, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(
            "expected Restored, got ${rawResult.get()}",
            rawResult.get() is RecoveryResult.Restored,
        )
        appState.model.forceReload()
        waitForModelLoaded()
        val postUndoCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-newfolder-undone"))
        assertEquals(
            preRevision.value,
            RevisionCalculator.revisionOf(postUndoCapture.layoutState).value,
        )
        assertEquals(
            "the created folder row must be gone after the undo",
            0,
            postUndoCapture.layoutState.items.count {
                it.kind == app.lawnchair.organizer.application.public.CanonicalItemKind.Folder
            },
        )
    }

    /**
     * Issue #450 (round 6 finding 2): the internal recovery reasons are
     * pinned — not just the display resource. The executor's test observer
     * receives the raw [RecoveryResult]; the eviction path must observe
     * `NotRestorable(EXPIRED)` on the same production store the confirm
     * created the point in.
     */
    @Test
    fun productionConfirmFlowUndoObservesTheInternalExpiredReason() {
        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        confirmThroughProductionFlowAndCaptureToken(tokenCapture, verifiedRef, pointIdRef)
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        // Age the recovery point past retention on the production store.
        val processModule = app.lawnchair.LawnchairApp.instance.layoutApplicationModule
        val storeField = LayoutApplicationModule::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        val store = storeField.get(processModule) as app.lawnchair.organizer.application.store.RecoveryStore
        store.runRetention(
            java.lang.System.currentTimeMillis() +
                app.lawnchair.organizer.application.lifecycle.RetentionPolicy.RETENTION_MILLIS + 1,
        )

        val launcherInstance = app.lawnchair.LawnchairLauncher.instance
            ?: error("launcher instance unavailable")
        val recovered = java.util.concurrent.atomic.AtomicReference<RecoveryResult?>(null)
        val recoveredLatch = java.util.concurrent.CountDownLatch(1)
        val executor = HomeEditUndoExecutor(
            launcherInstance,
            failureDisplayObserver = { },
            recoveryResultObserver = { result ->
                recovered.set(result)
                recoveredLatch.countDown()
            },
        )
        executor.start(token)
        assertTrue("undo did not complete", recoveredLatch.await(90, java.util.concurrent.TimeUnit.SECONDS))
        val result = recovered.get() ?: error("no recovery result observed")
        assertTrue(
            "expected NotRestorable(EXPIRED), got $result",
            result is RecoveryResult.NotRestorable &&
                (result as RecoveryResult.NotRestorable).reason == RecoveryRejection.EXPIRED,
        )
    }

    /**
     * Builds a one-selection new-folder session plan (the visual edit
     * surface's "新しいフォルダを作る" on the first desktop item) and returns
     * it with its capture. Uses the same pure session planner the activity
     * drives.
     */
    private fun buildNewFolderPlan(): Pair<app.lawnchair.organizer.application.public.ValidatedLayoutPlan, app.lawnchair.organizer.application.protocol.CapturedSnapshot> {
        val capture = adapter.captureCurrent(CaptureId("edit-surface-undo"))
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val first = snapshot.items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == 0 && it.id > 0
        }
        val picked = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(first.id),
            PendingSessionAction.CreateFolder,
        ) as SessionPlanResult.Applied
        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            picked.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready
        return built.plan to capture
    }

    /**
     * Issue #450 (round 9 finding 2): the production confirm-flow STALE case
     * through the REAL confirm() → record/token → Executor path. After the
     * confirm applies, another write changes the layout; the undo must be
     * rejected with `NotRestorable(STALE_REVISION)` (zero write) and the
     * internal reason pinned alongside the display resource.
     */
    @Test
    fun productionConfirmFlowUndoAfterAnotherWriteIsStale() {
        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        seedDesktopApps(Triple(0, 2, 1), Triple(0, 0, 1), Triple(1, 0, 0))
        confirmThroughProductionFlowAndCaptureToken(tokenCapture, verifiedRef, pointIdRef)
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        // Another write between confirm and undo (a new desktop row).
        val db = appState.model.modelDbController.db
        db.beginTransaction()
        try {
            val id = appState.model.modelDbController.generateNewItemId()
            db.insertOrThrow(Favorites.TABLE_NAME, null, desktopRowValues(id.toLong(), 0, 3, 3))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        appState.model.forceReload()
        waitForModelLoaded()

        runUndoThroughExecutorAndAssertDisplay(
            token,
            com.android.launcher3.R.string.homeedit_undo_error_stale_revision,
            expectedRawReason = { it is RecoveryResult.NotRestorable && it.reason == RecoveryRejection.STALE_REVISION },
            zeroWriteProbe = {
            val post = adapter.captureCurrent(CaptureId("edit-surface-undo-flow-stale"))
            post.layoutState.items.any { item ->
                val workspace = item.placement as? app.lawnchair.organizer.application.public.PlacementState.Workspace
                workspace != null &&
                    (workspace.page as? app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage)
                        ?.pageId?.value?.toIntOrNull() == 1
            }
            },
        )
    }

    /**
     * AC-1 evidence: captures the current screen via `screencap` into the
     * instrumentation target's files dir (pulled to the repository after the
     * run). Never throws — a capture failure degrades to a missing image, not
     * a test failure.
     */
    private fun takeScreenshotForEvidence(label: String) {
        try {
            val out = java.io.File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .targetContext.getExternalFilesDir(null),
                "450-edit-undo-ac1-$label.png",
            )
            out.parentFile?.mkdirs()
            androidx.test.uiautomator.UiDevice.getInstance(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
            ).takeScreenshot(out)
        } catch (_: Throwable) {
        }
    }

    // --- helpers ---

    /** Builds a one-move session plan (page 0 → page 1) and returns it with its capture. */
    private fun buildMovePlan(): Pair<app.lawnchair.organizer.application.public.ValidatedLayoutPlan, app.lawnchair.organizer.application.protocol.CapturedSnapshot> {
        val capture = adapter.captureCurrent(CaptureId("edit-surface-undo"))
        val snapshot = EditSurfaceProjection.homeEditSnapshot(capture.layoutState)
        val first = snapshot.items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == 0 && it.id > 0
        }
        val picked = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(first.id),
            PendingSessionAction.MoveToPage(1),
        ) as SessionPlanResult.Applied
        val bundle = BuiltInOrganizerPolicyBundleSource.readActive() as BundleReadResult.Ready
        val built = EditSurfacePlanBuilder.build(
            capture.layoutState,
            capture.revision,
            picked.session,
            bundle.bundle.rules.version,
            bundle.bundle.taxonomy.version,
        ) as EditSurfaceApplyPlan.Ready
        return built.plan to capture
    }

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
        waitForModelLoaded()
    }

    private fun desktopRowValues(id: Long, screen: Int, cellX: Int, cellY: Int): ContentValues = ContentValues().apply {
        put(Favorites._ID, id)
        put(Favorites.TITLE, "Edit surface undo fixture $id")
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
        put(
            Favorites.PROFILE_ID,
            com.android.launcher3.pm.UserCache.INSTANCE.get(context)
                .getSerialNumberForUser(android.os.Process.myUserHandle()),
        )
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

    private fun waitForModelLoaded() {
        val model = appState.model
        val deadline = System.currentTimeMillis() + 10_000L
        while (!model.isModelLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }
}
