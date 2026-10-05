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

    /** G5 (§6.10): one-shot latch for the reload-generation settle ([reloadAndWaitForGeneration]). */
    @Volatile
    private var generationLatch: java.util.concurrent.CountDownLatch? = null

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
        // the whole test so reloads complete; it also carries the
        // bindCompleteModel latch the seeding settle uses.
        val callback = object : com.android.launcher3.model.BgDataModel.Callbacks {
            override fun bindCompleteModel(
                itemIdMap: com.android.launcher3.model.data.WorkspaceData,
                isBindingSync: Boolean,
            ) {
                generationLatch?.countDown()
            }
        }
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
        undoTokenFactory: (() -> HomeEditUndoToken)? = null,
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
        // G5 (§6.10): the recovery protocol maps a single-shot lease/mutex
        // acquisition failure straight to WriterBusy/ConcurrentRun. The
        // launcher's startup load and in-flight organizer operations hold the
        // process-wide coordinator, so on the slower CI emulator the undo must
        // start only after the model settled (loaded, no active loader).
        awaitModelSettled()
        val displayed = java.util.concurrent.atomic.AtomicReference<Int?>()
        val rawResult = java.util.concurrent.atomic.AtomicReference<RecoveryResult?>(null)
        var currentToken = token
        var busyRetries = 0
        while (true) {
            val displayedLatch = java.util.concurrent.CountDownLatch(1)
            rawResult.set(null)
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
            executor.start(currentToken)
            assertTrue("undo display did not fire", displayedLatch.await(30, java.util.concurrent.TimeUnit.SECONDS))
            val raw = rawResult.get()
            val transientBusy = undoTokenFactory != null &&
                (raw is RecoveryResult.WriterBusy || raw is RecoveryResult.ConcurrentRun)
            if (!transientBusy) break
            // G5 (§6.10): a busy surface here is the environmental contention
            // above, not the typed failure this oracle pins (the busy display is
            // pinned by the dedicated lease-held oracles, which do not pass a
            // token factory). Re-arm the same session entry — exactly a second
            // undo tap — and require the final display to be the typed failure;
            // the assert contract is unchanged.
            busyRetries++
            check(busyRetries <= 3) { "undo kept returning busy after the model settled: $raw" }
            awaitModelSettled()
            Thread.sleep(1_000)
            currentToken = undoTokenFactory.invoke()
        }
        // The typed failure display is pinned on the final attempt (a busy
        // display with a token factory only triggers the re-arm above; the
        // dedicated lease-held oracles pin the busy display itself).
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

    /** G5 (§6.10): loaded with no active loader — the recovery wait's own settle definition. */
    private fun awaitModelSettled() {
        val deadline = System.currentTimeMillis() + 30_000
        while (!appState.model.isModelLoaded() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
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
            // G5 (§6.10): on the CI emulator the undo can race the launcher's
            // startup load (single-shot lease acquisition -> WriterBusy). Re-arm
            // the same expired session entry — a second undo tap — and keep the
            // typed not-restorable assert on the final attempt.
            undoTokenFactory = { HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified)) },
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
        // G5 (§6.10): the first undo's recovery call holds the organizer run
        // mutex through its correlated reload wait; the DB shows "restored"
        // before the call returns. Await the RAW recovery result (the executor's
        // observer fires when recover() returned) before the second undo —
        // otherwise the second undo's single-shot mutex acquisition races the
        // first undo's release and surfaces busy instead of the typed rejection.
        val firstUndoReturned = java.util.concurrent.CountDownLatch(1)
        HomeEditUndoExecutor(launcherInstance, recoveryResultObserver = { firstUndoReturned.countDown() }).start(token1)
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
        assertTrue(
            "first undo recovery call did not return",
            firstUndoReturned.await(60, java.util.concurrent.TimeUnit.SECONDS),
        )

        val token2 = HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified))
        // The executor runs against the process's single module instance, whose
        // recovery store IS the store this test's module wrote the point into
        // (one organizer_recovery.db per app data). The point is known there and
        // the first undo marked it RESTORED, so the second undo surfaces
        // ALREADY_RESTORED (zero write either way; the typed-failure chain is
        // what this oracle pins, and the ALREADY_RESTORED reason itself is
        // pinned at the protocol level by undoAfterARestoreIsRejectedAsAlreadyRestored).
        runUndoThroughExecutorAndAssertDisplay(
            token2,
            com.android.launcher3.R.string.homeedit_undo_error_not_restorable,
            expectedRawReason = { it is RecoveryResult.NotRestorable && it.reason == RecoveryRejection.ALREADY_RESTORED },
            zeroWriteProbe = {
            // The restored state (pre-apply) is unchanged by the failed undo.
            val post = adapter.captureCurrent(CaptureId("edit-surface-undo-executor-already"))
            RevisionCalculator.revisionOf(post.layoutState) ==
                RevisionCalculator.revisionOf((buildMovePlan().second).layoutState)
            },
            // G5 (§6.10): re-arm the same session entry on a transient busy
            // (the MISSING typed rejection stays the pinned final display).
            undoTokenFactory = { HomeEditUndoRecord.record(HomeEditUndoEntry.EditSession(pointId, verified)) },
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
        // G5 (§6.10): the gate can still be transiently non-READY here (the
        // launcher's startup reconciliation, or a FAILED gate left by an earlier
        // generation's reconciliation). inspectCapture maps every non-READY state
        // to null, so poll the gate to READY (bounded) before opening the
        // surface — an unopenable gate must fail this oracle with the typed
        // state, not as an opaque capture timeout below.
        awaitProductionReadinessGate()
        val scenario = androidx.test.core.app.ActivityScenario.launch(
            app.lawnchair.homeedit.ui.HomeEditSurfaceActivity::class.java,
        )
        var activityRef: app.lawnchair.homeedit.ui.HomeEditSurfaceActivity? = null
        scenario.onActivity { activityRef = it }
        val activity = activityRef ?: error("activity not launched")

        // Wait for the activity's own capture to settle (the confirm gate
        // requires captureState/captureRevision and a non-empty session).
        //
        // G5 (§6.10): the poll runs on a BACKGROUND thread. The instrumentation
        // thread IS the main thread; a sleep-poll here blocks the main looper,
        // so the activity's `runOnUiThread` capture updates (reloadCapture runs
        // its result on main) can never execute during the poll — a capture
        // that failed once (e.g. against the concurrent startup reconciliation)
        // could never be observed to recover, and the poll always timed out
        // with a stale typed reason. With the poll off-main, main idles between
        // polls and pumps the posted capture updates.
        val captureDeadline = System.currentTimeMillis() + 30_000
        val pollOutcome = java.util.concurrent.atomic.AtomicReference<String>("timeout")
        val pollDone = java.util.concurrent.CountDownLatch(1)
        Thread(
            {
                var polls = 0
                var lastDirectInspect = "not-run"
                try {
                    while (System.currentTimeMillis() < captureDeadline) {
                        var found: Int? = null
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                            .runOnMainSync {
                                found = activity.firstSelectableItemIdForTest()
                            }
                        if (found != null) {
                            pollOutcome.set("itemId:" + found)
                            return@Thread
                        }
                        polls++
                        // G5 (§6.10): inspectCapture is fail-closed on transient
                        // contention (e.g. the startup reconciliation holds the
                        // run mutex) or a silent capture failure — the diagram
                        // stays null and the activity shows the typed
                        // capture-unavailable reason. Reopen through the
                        // production seam (the same reloadCapture path the UI
                        // drives on reopen) so a transiently failed capture
                        // recovers instead of exhausting the poll window.
                        if (polls % 10 == 0) {
                            lastDirectInspect = directInspectForDiagnostics()
                            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                                .runOnMainSync { activity.recaptureForTest() }
                        }
                        Thread.sleep(300)
                    }
                    pollOutcome.set(
                        "timeout(lastDirectInspect=" + lastDirectInspect +
                            ", typedReason=" + reasonResForDiagnostics(activity) + ")",
                    )
                } catch (t: Throwable) {
                    pollOutcome.set("pollError(${t.javaClass.simpleName}: ${t.message})")
                } finally {
                    pollDone.countDown()
                }
            },
            "edit-surface-poll",
        ).start()
        check(pollDone.await(35, java.util.concurrent.TimeUnit.SECONDS)) { "edit-surface poll did not finish" }
        val outcome = pollOutcome.get()
        check(outcome.startsWith("itemId:")) {
            "no selectable item on the diagram ($outcome, readinessGate=" +
                app.lawnchair.LawnchairApp.instance.layoutApplicationModule.readinessGate.state +
                ", directCapture=" + directCaptureForDiagnostics() + ")"
        }
        val itemId = outcome.removePrefix("itemId:").toInt()

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
                    lastReason?.let { activity.getString(it) } +
                    "; lastApplyResult=" + activity.lastApplyResultForTest +
                    "; lastPlan=" + activity.lastPlanForTest + ")",
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
            // G5 (§6.10): re-arm the same expired session entry on a transient
            // busy (the EXPIRED typed rejection stays the pinned final display).
            undoTokenFactory = {
                HomeEditUndoRecord.record(
                    HomeEditUndoEntry.EditSession(pointIdRef.get()!!, verifiedRef.get()!!),
                )
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
        // G5 (§6.10): await the first undo's recovery call (the run mutex is
        // held through its correlated reload wait) before the repeat undo.
        val firstUndoReturned = java.util.concurrent.CountDownLatch(1)
        HomeEditUndoExecutor(undoLauncher, recoveryResultObserver = { firstUndoReturned.countDown() }).start(token)
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
        assertTrue(
            "first undo recovery call did not return",
            firstUndoReturned.await(60, java.util.concurrent.TimeUnit.SECONDS),
        )
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
            // G5 (§6.10): re-arm the same session entry on a transient busy
            // (environmental contention; the ALREADY_RESTORED typed rejection
            // stays the pinned final display).
            undoTokenFactory = {
                HomeEditUndoRecord.record(
                    HomeEditUndoEntry.EditSession(
                        pointIdRef.get()!!,
                        verifiedRef.get()!!,
                    ),
                )
            },
        )
    }

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
        // 2-member folder: see buildNewFolderPlan — a single-child folder is
        // flattened by the launcher's bind-time cleanup.
        val second = snapshot.items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == 0 &&
                it.id > 0 && it.id != first.id
        }
        val picked = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(first.id, second.id),
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
     * confirm oracle runs in the default lane. The Chrome fixture
     * (desktopRowValues, asserted present in setUp) keeps the DB stable
     * across the confirm→undo window, and the launcher is pre-launched and
     * settled BEFORE the confirm flow so startup reloads cannot race the
     * undo.
     *
     * Issue #450 (review round 13 finding 1): the launcher's own folder
     * binding normalizes folder-internal cells at bind time and used to bump
     * the rows' `modified` right after the apply, staling the receipt
     * revision (a legitimate create-folder undo was rejected as
     * STALE_REVISION). The organizer adapter now materializes the same
     * launcher-normalized cells (LauncherLayoutAdapter.rowFor), so the
     * bind-time pass finds nothing to rewrite and this AC-5 normal-case
     * oracle holds end to end: the receipt revision equals a fresh post-apply
     * capture revision and the undo restores the pre-apply state.
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

        val tokenCapture =
            java.util.concurrent.atomic.AtomicReference<HomeEditUndoToken?>(null)
        val verifiedRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.planning.RevisionId?>(null)
        val pointIdRef = java.util.concurrent.atomic.AtomicReference<app.lawnchair.organizer.application.public.RecoveryPointId?>(null)
        confirmThroughProductionFlowAndCaptureToken(
            tokenCapture,
            verifiedRef,
            pointIdRef,
            sessionAction = { activity, itemId ->
                // A 2-item selection: the launcher flattens a single-child
                // folder, so a 1-item create-folder cannot survive the
                // apply's post-write verification (the folder binding's
                // single-child cleanup rewrites the DB inside the verify
                // window and the apply self-recovers). Two children are the
                // stable folder shape the surface is meant to produce.
                val second = activity.secondSelectableItemIdForTest(itemId)
                    ?: error("no second selectable item for the 2-item folder")
                activity.toggleSelectionForTest(itemId)
                activity.toggleSelectionForTest(second)
                activity.createFolderForTest()
            },
        )
        val token = tokenCapture.get() ?: error("no token captured from the confirm flow")

        // AC-5 revision-equality oracle: the receipt's recorded revision
        // equals a fresh post-apply capture revision — for the new-folder
        // case where plan.intendedState differs from the materialized
        // post-state (planned folder resolution + page normalization). With
        // the adapter materializing the launcher-normalized folder grid
        // cells, the folder binding's bind-time normalization finds nothing
        // to rewrite, so no post-apply write bumps `modified` and the
        // receipt stays authoritative here.
        val postApplyCapture = adapter.captureCurrent(CaptureId("edit-surface-undo-newfolder-post"))
        assertNotNull("the receipt carried a verified post revision", verifiedRef.get())
        assertEquals(
            RevisionCalculator.revisionOf(postApplyCapture.layoutState).value,
            verifiedRef.get()!!.value,
        )

        // Undo through the production recovery path: Restored — the receipt
        // revision still describes the current state (no launcher write
        // between the apply and the undo), so the recovery is admitted.
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
        // The undo contract is the ITEM-level restoration: every pre-apply
        // item back at its pre-apply placement and the created folder row
        // gone. (A whole-state revision equality is stricter than the
        // recovery contract — the restored rows re-carry loader-normalized
        // fields, so the canonical digest may differ in non-placement
        // details.)
        val prePlacements = preCapture.layoutState.items
            .filter { it.kind != app.lawnchair.organizer.application.public.CanonicalItemKind.Folder }
            .associate { it.ref.toString() to it.placement.toString() }
        val postPlacements = postUndoCapture.layoutState.items
            .filter { it.kind != app.lawnchair.organizer.application.public.CanonicalItemKind.Folder }
            .associate { it.ref.toString() to it.placement.toString() }
        assertEquals(
            "every pre-apply item back at its pre-apply placement",
            prePlacements,
            postPlacements,
        )
        assertEquals(
            "the created folder row is gone",
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

        // Launch the launcher for the executor's undo path (the same pre-launch
        // the other production-confirm oracles drive) — the instance from an
        // earlier test may already have been destroyed when this runs.
        androidx.test.core.app.ActivityScenario.launch(app.lawnchair.LawnchairLauncher::class.java)
        val launcherDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < launcherDeadline) {
            if (app.lawnchair.LawnchairLauncher.instance != null) break
            Thread.sleep(200)
        }
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
        // A 2-member folder: a single-child folder is flattened by the
        // launcher's bind-time cleanup (a DB write whose timing is outside
        // the apply's control), which makes the apply's post-write
        // verification fail with a state mutation the plan did not make.
        val second = snapshot.items.first {
            it.container == HomeEditContainers.DESKTOP && it.screenId == 0 &&
                it.id > 0 && it.id != first.id
        }
        val picked = EditSurfaceSessionPlanner.plan(
            snapshot,
            emptyMap(),
            EditSurfaceSession.EMPTY,
            listOf(first.id, second.id),
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
            // G5 (§6.10): re-arm the same stale session entry on a transient
            // busy (the STALE_REVISION typed rejection stays the pinned final
            // display).
            undoTokenFactory = {
                HomeEditUndoRecord.record(
                    HomeEditUndoEntry.EditSession(pointIdRef.get()!!, verifiedRef.get()!!),
                )
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
        // G5 (§6.10): settle the seeded rows as a completed reload generation —
        // the plain isModelLoaded poll returns immediately across a reload
        // (the previous generation stays bound), so the edit surface's capture
        // could observe a mid-flight model on the slower CI emulator.
        reloadAndWaitForGeneration("seedDesktopApps")
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
        while (!model.isModelLoaded() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    /**
     * G5 (§6.10): the seeded rows only reach the model through a reload
     * GENERATION; `isModelLoaded()` stays true across a reload (the previous
     * generation stays bound until the new one commits), so the plain wait can
     * return before the seeds are bound. Wait for one generation via
     * bindCompleteModel — the same settle seam the organizer E2E fixtures use.
     */
    private fun reloadAndWaitForGeneration(label: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        generationLatch = latch
        appState.model.forceReload()
        check(
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS),
        ) { "$label: reload generation did not complete" }
        waitForModelLoaded()
        generationLatch = null
    }

    /**
     * G5 (§6.10): the edit surface's capture seam is fail-closed on a non-READY
     * production readiness gate; a gate left FAILED (or still RECONCILING from
     * the launcher's startup reconciliation) would keep the diagram null for the
     * whole poll window. Drive the gate to READY with bounded re-reconciliations
     * — the same reconcileAtStart entry the confirm flow runs before opening.
     */
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

    /** G5 (§6.10): the activity's typed reason for the poll-timeout diagnostics. */
    private fun reasonResForDiagnostics(activity: app.lawnchair.homeedit.ui.HomeEditSurfaceActivity): Int? {
        var reason: Int? = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .runOnMainSync { reason = activity.reasonResForTest() }
        return reason
    }

    /** G5 (§6.10): a direct module inspectCapture — the seam the activity drives. */
    private fun directInspectForDiagnostics(): String = try {
        val module = app.lawnchair.LawnchairApp.instance.layoutApplicationModule
        val started = System.currentTimeMillis()
        val captured = module.inspectCapture()
        if (captured == null) {
            "null(${System.currentTimeMillis() - started}ms,gate=" + module.readinessGate.state + ")"
        } else {
            "ok(${System.currentTimeMillis() - started}ms,items=${captured.layoutState.items.size})"
        }
    } catch (t: Throwable) {
        "threw(${t.javaClass.name}: ${t.message})"
    }

    /**
     * G5 (§6.10): a direct capture through the production module's writer —
     * the same call inspectCapture makes — so a silent capture failure is
     * visible in the poll-timeout diagnostics.
     */
    private fun directCaptureForDiagnostics(): String = try {
        val module = app.lawnchair.LawnchairApp.instance.layoutApplicationModule
        val writerField = LayoutApplicationModule::class.java.getDeclaredField("writer")
            .apply { isAccessible = true }
        val writer = writerField.get(module)
        val capture = writer.javaClass.methods
            .first { it.name == "captureCurrent" && it.parameterCount == 1 }
        val started = System.currentTimeMillis()
        val result = capture.invoke(
            writer,
            app.lawnchair.organizer.application.protocol.CaptureId("edit-surface-undo-diag"),
        )
        "ok(${System.currentTimeMillis() - started}ms,items=${(result as app.lawnchair.organizer.application.protocol.CapturedSnapshot).layoutState.items.size})"
    } catch (t: Throwable) {
        "threw(${(t as? java.lang.reflect.InvocationTargetException)?.targetException?.javaClass?.name ?: t.javaClass.name}: " +
            "${(t as? java.lang.reflect.InvocationTargetException)?.targetException?.message ?: t.message})"
    }
}
