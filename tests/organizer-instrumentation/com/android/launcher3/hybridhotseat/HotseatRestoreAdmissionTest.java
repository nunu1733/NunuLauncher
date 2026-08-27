package com.android.launcher3.hybridhotseat;

import static com.android.launcher3.LauncherSettings.Favorites.HYBRID_HOTSEAT_BACKUP_TABLE;
import static com.android.launcher3.provider.LauncherDbUtils.tableExists;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.OrganizerModelReloadAdapter;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Issue #156: deterministic MODEL_EXECUTOR admission coverage for Hotseat restore work.
 *
 * <p>The queue represents the single MODEL_EXECUTOR and is injected through the actual
 * HotseatRestoreHelper package-private scheduler seam. Tests deliberately schedule tokenless
 * Hotseat work while the coordinator is empty, acquire ORGANIZER before executing it, and then
 * run the exact correlated loader behind it. This fixes the ordering that produced the production
 * starvation without using a sleep or adapter timeout as the correctness oracle.
 */
@RunWith(AndroidJUnit4.class)
public class HotseatRestoreAdmissionTest {

    private Context context;
    private ModelDbController dbController;
    private LauncherModel model;
    private Handler mainHandler;
    private boolean ownsBackupTable;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        model = LauncherAppState.getInstance(context).getModel();
        dbController = model.getModelDbController();
        mainHandler = new Handler(Looper.getMainLooper());
        Assume.assumeFalse("fixture must not overwrite an existing Hotseat backup table",
                backupTableExists());
        ownsBackupTable = true;
    }

    @After
    public void tearDown() throws Exception {
        if (ownsBackupTable) {
            MODEL_EXECUTOR.submit(() -> {
                try (SQLiteTransaction transaction = dbController.newTransaction()) {
                    transaction.getDb().execSQL(
                            "DROP TABLE IF EXISTS " + HYBRID_HOTSEAT_BACKUP_TABLE);
                    transaction.commit();
                    dbController.refreshHotseatRestoreTable();
                }
            }).get();
        }
    }

    @Test
    public void createBackupUsesAtomicAdmissionAndCreatesBackupTable() throws Exception {
        HotseatRestoreHelper.createBackup(context);
        awaitModelExecutor();

        assertTrue("uncontended createBackup must preserve its table-creation semantics",
                backupTableExists());
    }

    @Test
    public void restoreWithoutBackupTableReturnsWithoutCreatingOne() throws Exception {
        assertFalse(backupTableExists());

        HotseatRestoreHelper.restoreBackup(context);
        awaitModelExecutor();

        assertFalse("restore without a backup table must preserve its early-return behavior",
                backupTableExists());
    }

    @Test
    public void restoreExistingBackupExecutesAndDropsBackupTable() throws Exception {
        HotseatRestoreHelper.createBackup(context);
        awaitModelExecutor();
        assertTrue(backupTableExists());

        HotseatRestoreHelper.restoreBackup(context);
        awaitModelExecutor();

        assertFalse("restore must execute its existing dropAfterUse behavior", backupTableExists());
    }

    @Test
    public void deferredRestoreLetsExactCorrelatedReloadCompleteBeforeOrganizerRelease()
            throws Exception {
        CountDownLatch executorReached = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        AtomicBoolean executorWaitFailed = new AtomicBoolean();
        MODEL_EXECUTOR.execute(() -> {
            executorReached.countDown();
            try {
                if (!releaseExecutor.await(15, TimeUnit.SECONDS)) {
                    executorWaitFailed.set(true);
                }
            } catch (InterruptedException e) {
                executorWaitFailed.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertTrue("MODEL_EXECUTOR did not reach the deterministic admission gate",
                executorReached.await(15, TimeUnit.SECONDS));

        BgDataModel.Callbacks callback = new BgDataModel.Callbacks() { };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> model.addCallbacks(callback));
        LayoutWriteCoordinator.Lease acquiredOrganizer = null;
        ExecutorService reloadExecutor = null;
        try {
            // Queue tokenless restore while MODEL_EXECUTOR is stopped. The coordinator is empty
            // now; ORGANIZER becomes the holder only after scheduling and before admission.
            HotseatRestoreHelper.restoreBackup(context);
            acquiredOrganizer = LayoutWriteCoordinator.getInstance().tryAcquire(
                    LayoutWriteCoordinator.OwnerKind.ORGANIZER);
            assertNotNull(acquiredOrganizer);
            final LayoutWriteCoordinator.Lease organizer = acquiredOrganizer;
            reloadExecutor = Executors.newSingleThreadExecutor();
            Future<OrganizerModelReloadAdapter.Outcome> outcome = reloadExecutor.submit(() ->
                    new OrganizerModelReloadAdapter(model, mainHandler).requestAndWait(organizer.token()));
            releaseExecutor.countDown();

            assertEquals("correlated reload must complete before the outer organizer release",
                    OrganizerModelReloadAdapter.Outcome.COMPLETED,
                    outcome.get(15, TimeUnit.SECONDS));
            assertFalse("deterministic executor gate timed out", executorWaitFailed.get());
        } finally {
            releaseExecutor.countDown();
            if (acquiredOrganizer != null) {
                acquiredOrganizer.close();
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> model.removeCallbacks(callback));
            if (reloadExecutor != null) {
                reloadExecutor.shutdownNow();
                assertTrue("reload worker did not terminate",
                        reloadExecutor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        awaitModelExecutor();
    }

    @Test
    public void atomicAdmissionDefersWhenOrganizerAppearsAfterTaskIsScheduled() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        DeterministicExecutor modelExecutor = new DeterministicExecutor();
        AtomicInteger hotseatDbBodyRuns = new AtomicInteger();
        AtomicBoolean correlatedLoaderCompleted = new AtomicBoolean();

        // The helper has scheduled its MODEL_EXECUTOR work while no writer is held.
        scheduleAtomicHotseatWork(modelExecutor, hotseatDbBodyRuns::incrementAndGet);
        assertEquals(1, modelExecutor.pendingTaskCount());
        assertEquals(0, coordinator.pendingDeferredCount());

        LayoutWriteCoordinator.Lease organizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(organizer);
        try {
            // This is the review regression: organizer appears after schedule but before admission.
            modelExecutor.runNext();
            assertEquals("busy admission must not execute the Hotseat DB body", 0,
                    hotseatDbBodyRuns.get());
            assertEquals("busy admission must register exactly one retry", 1,
                    coordinator.pendingDeferredCount());
            assertEquals("MODEL_EXECUTOR must return instead of waiting on the organizer", 0,
                    modelExecutor.pendingTaskCount());

            // The correlated loader was queued behind the formerly-blocking Hotseat task. It must
            // still run before the explicit organizer release.
            modelExecutor.execute(() -> coordinator.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    organizer.token(),
                    true,
                    () -> correlatedLoaderCompleted.set(true)));
            modelExecutor.runNext();
            assertTrue("exact-token correlated loader must make progress", correlatedLoaderCompleted.get());
            assertEquals("Hotseat DB body remains deferred until organizer release", 0,
                    hotseatDbBodyRuns.get());
        } finally {
            organizer.close();
        }

        assertEquals("FIFO release must resubmit one MODEL_EXECUTOR retry", 1,
                modelExecutor.pendingTaskCount());
        modelExecutor.runNext();
        assertEquals("the admitted Hotseat body runs exactly once", 1, hotseatDbBodyRuns.get());
        assertEquals(0, coordinator.pendingDeferredCount());
    }

    @Test
    public void retriedAdmissionDefersAgainWhenAnotherHolderArrivesBeforeRetry() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        DeterministicExecutor modelExecutor = new DeterministicExecutor();
        AtomicInteger hotseatDbBodyRuns = new AtomicInteger();

        LayoutWriteCoordinator.Lease firstOrganizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(firstOrganizer);
        try {
            scheduleAtomicHotseatWork(modelExecutor, hotseatDbBodyRuns::incrementAndGet);
            modelExecutor.runNext();
            assertEquals(1, coordinator.pendingDeferredCount());
        } finally {
            firstOrganizer.close();
        }

        assertEquals(1, modelExecutor.pendingTaskCount());
        LayoutWriteCoordinator.Lease secondOrganizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(secondOrganizer);
        try {
            modelExecutor.runNext();
            assertEquals("a retry cannot run DB work under a new external holder", 0,
                    hotseatDbBodyRuns.get());
            assertEquals("the retry must return to the FIFO", 1, coordinator.pendingDeferredCount());
        } finally {
            secondOrganizer.close();
        }

        assertEquals(1, modelExecutor.pendingTaskCount());
        modelExecutor.runNext();
        assertEquals(1, hotseatDbBodyRuns.get());
        assertEquals(0, coordinator.pendingDeferredCount());
    }

    @Test
    public void admittedBodyExceptionReleasesOuterLeaseAndDoesNotBlockLaterWork() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        AtomicInteger laterBodyRuns = new AtomicInteger();

        try {
            coordinator.runModelWriterOrDefer(
                    () -> {
                        throw new IllegalStateException("expected issue-156 test failure");
                    },
                    () -> fail("uncontended body must not be deferred"));
            fail("exception from admitted body must remain observable");
        } catch (IllegalStateException expected) {
            assertEquals("expected issue-156 test failure", expected.getMessage());
        }

        coordinator.runModelWriterOrDefer(
                laterBodyRuns::incrementAndGet,
                () -> fail("released coordinator must admit later work immediately"));
        assertEquals("finally must release the outer MODEL_WRITER lease", 1, laterBodyRuns.get());
        assertEquals(0, coordinator.pendingDeferredCount());
    }

    @Test
    public void sameThreadModelWriterReentryRemainsSupported() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        AtomicInteger bodyRuns = new AtomicInteger();
        LayoutWriteCoordinator.Lease outer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
        assertNotNull(outer);
        try {
            coordinator.runModelWriterOrDefer(
                    bodyRuns::incrementAndGet,
                    () -> fail("same-thread MODEL_WRITER must reenter, not defer"));
            assertEquals(1, bodyRuns.get());
            assertEquals(0, coordinator.pendingDeferredCount());
        } finally {
            outer.close();
        }
    }

    private boolean backupTableExists() throws Exception {
        AtomicBoolean exists = new AtomicBoolean();
        MODEL_EXECUTOR.submit(() -> exists.set(
                tableExists(dbController.getDb(), HYBRID_HOTSEAT_BACKUP_TABLE))).get();
        return exists.get();
    }

    private static void awaitModelExecutor() throws Exception {
        MODEL_EXECUTOR.submit(() -> { }).get();
    }

    private static void scheduleAtomicHotseatWork(
            DeterministicExecutor modelExecutor, Runnable dbBody) {
        HotseatRestoreHelper.executeWithAtomicModelWriterAdmission(modelExecutor, dbBody);
    }

    /** A one-thread executor model with explicit, event-ordered task execution. */
    private static final class DeterministicExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.addLast(task);
        }

        void runNext() {
            assertFalse("expected a queued MODEL_EXECUTOR task", tasks.isEmpty());
            tasks.removeFirst().run();
        }

        int pendingTaskCount() {
            return tasks.size();
        }
    }
}
