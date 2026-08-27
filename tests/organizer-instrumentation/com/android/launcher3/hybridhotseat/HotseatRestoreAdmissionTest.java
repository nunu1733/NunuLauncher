package com.android.launcher3.hybridhotseat;

import static com.android.launcher3.LauncherSettings.Favorites.HYBRID_HOTSEAT_BACKUP_TABLE;
import static com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME;
import static com.android.launcher3.provider.LauncherDbUtils.itemIdMatch;
import static com.android.launcher3.provider.LauncherDbUtils.tableExists;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.celllayout.CellPosMapper;
import com.android.launcher3.OrganizerModelReloadAdapter;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.ModelWriter;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;

import app.lawnchair.LawnchairLauncher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
    private ItemInfo seededHotseatItem;
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
        if (seededHotseatItem != null) {
            model.getWriter(false, CellPosMapper.DEFAULT, null).deleteItemFromDatabase(
                    seededHotseatItem, "Issue156 test fixture cleanup");
            awaitModelExecutor();
        }
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
    public void createBackupReservationPrecedesActualModelWriterMigration() throws Exception {
        ItemInfo migrationItem = addTestHotseatItem();
        int backupRank = migrationItem.rank;
        int migratedRank = backupRank + 1;

        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease organizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(organizer);
        try {
            HotseatRestoreHelper.createBackup(context);
            migrationItem.rank = migratedRank;
            ModelWriter modelWriter = model.getWriter(
                    false, CellPosMapper.DEFAULT, null);
            modelWriter.moveItemInDatabase(
                    migrationItem,
                    migrationItem.container,
                    migrationItem.screenId,
                    migrationItem.cellX,
                    migrationItem.cellY);
            assertEquals("backup reservation must enter FIFO before ModelWriter migration", 2,
                    coordinator.pendingDeferredCount());
        } finally {
            organizer.close();
        }
        awaitModelExecutor();

        assertEquals("backup must retain the rank from before ModelWriter mutation", backupRank,
                rankInTable(HYBRID_HOTSEAT_BACKUP_TABLE, migrationItem.id));
        assertEquals("migration must update the primary Favorites row after backup", migratedRank,
                rankInTable(TABLE_NAME, migrationItem.id));
    }

    @Test
    public void createBackupReservationBlocksMigrationAcrossHolderReacquisition()
            throws Exception {
        ItemInfo migrationItem = addTestHotseatItem();
        int backupRank = migrationItem.rank;
        int migratedRank = backupRank + 1;
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
        assertTrue("MODEL_EXECUTOR did not reach the deterministic reacquisition gate",
                executorReached.await(15, TimeUnit.SECONDS));

        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease firstOrganizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(firstOrganizer);
        LayoutWriteCoordinator.Lease secondOrganizer = null;
        try {
            HotseatRestoreHelper.createBackup(context);
            migrationItem.rank = migratedRank;
            model.getWriter(false, CellPosMapper.DEFAULT, null).moveItemInDatabase(
                    migrationItem,
                    migrationItem.container,
                    migrationItem.screenId,
                    migrationItem.cellX,
                    migrationItem.cellY);
            assertEquals("backup and following ModelWriter must be queued in call order", 2,
                    coordinator.pendingDeferredCount());

            firstOrganizer.close();
            firstOrganizer = null;
            secondOrganizer = coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
            assertNotNull("second holder must acquire before reposted backup admission", secondOrganizer);
            releaseExecutor.countDown();
            awaitModelExecutor();
            assertFalse("deterministic executor gate timed out", executorWaitFailed.get());

            // The backup reservation remains the FIFO head under the second holder. The later
            // ModelWriter must not be reposted to MODEL_EXECUTOR, where it would otherwise block
            // on the second holder and could mutate Favorites before the backup snapshot.
            assertEquals("re-deferred backup and later ModelWriter remain ordered", 2,
                    coordinator.pendingDeferredCount());
            assertEquals("migration cannot execute while the backup reservation owns FIFO head",
                    backupRank, rankInTable(TABLE_NAME, migrationItem.id));
        } finally {
            releaseExecutor.countDown();
            if (secondOrganizer != null) {
                secondOrganizer.close();
            }
            if (firstOrganizer != null) {
                firstOrganizer.close();
            }
        }
        awaitModelExecutor();

        assertEquals("backup must retain the rank from before ModelWriter migration", backupRank,
                rankInTable(HYBRID_HOTSEAT_BACKUP_TABLE, migrationItem.id));
        assertEquals("migration must update Favorites only after backup completes", migratedRank,
                rankInTable(TABLE_NAME, migrationItem.id));
    }

    @Test
    public void createBackupReservationSurvivesHolderAppearingBeforeAdmission()
            throws Exception {
        ItemInfo migrationItem = addTestHotseatItem();
        int backupRank = migrationItem.rank;
        int migratedRank = backupRank + 1;
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
        assertTrue("MODEL_EXECUTOR did not reach the deterministic schedule gate",
                executorReached.await(15, TimeUnit.SECONDS));

        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease organizer = null;
        try {
            // createBackup reserves first while the coordinator is empty, but its executor
            // admission is still stopped. The following organizer lease and real ModelWriter
            // submission reproduce the review-reported interleaving.
            HotseatRestoreHelper.createBackup(context);
            organizer = coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
            assertNotNull(organizer);
            migrationItem.rank = migratedRank;
            model.getWriter(false, CellPosMapper.DEFAULT, null).moveItemInDatabase(
                    migrationItem,
                    migrationItem.container,
                    migrationItem.screenId,
                    migrationItem.cellX,
                    migrationItem.cellY);
            assertEquals("call-time backup reservation must precede later ModelWriter work", 2,
                    coordinator.pendingDeferredCount());

            releaseExecutor.countDown();
            awaitModelExecutor();
            assertFalse("deterministic executor gate timed out", executorWaitFailed.get());
            assertEquals("busy executor admission must retain its original reservation", 2,
                    coordinator.pendingDeferredCount());
        } finally {
            releaseExecutor.countDown();
            if (organizer != null) {
                organizer.close();
            }
        }
        awaitModelExecutor();

        assertEquals("backup must retain the rank from before the later ModelWriter mutation",
                backupRank, rankInTable(HYBRID_HOTSEAT_BACKUP_TABLE, migrationItem.id));
        assertEquals("migration must update Favorites after the backup snapshot", migratedRank,
                rankInTable(TABLE_NAME, migrationItem.id));
    }

    @Test
    public void createBackupReservationPrecedesFollowingMigrationSubmission() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        DeterministicExecutor modelExecutor = new DeterministicExecutor();
        List<String> executionOrder = new ArrayList<>();

        LayoutWriteCoordinator.Lease organizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(organizer);
        try {
            // This is the concrete helper scheduling seam used by createBackup. Its DB body is
            // deliberately represented as an ordered event so the test can assert release-thread
            // affinity separately from MODEL_EXECUTOR order.
            scheduleAtomicHotseatWork(modelExecutor, () -> executionOrder.add("backup"));
            coordinator.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    0L,
                    false,
                    () -> modelExecutor.execute(() -> executionOrder.add("migration")));

            assertEquals("backup reservation must enter FIFO before following migration", 2,
                    coordinator.pendingDeferredCount());
            assertEquals("neither body may run while the external holder is active", 0,
                    executionOrder.size());
        } finally {
            organizer.close();
        }

        assertEquals("release callbacks may post but must not run DB bodies", 0,
                executionOrder.size());
        assertEquals("FIFO head drain must post only the backup admission first", 1,
                modelExecutor.pendingTaskCount());
        modelExecutor.runNext();
        assertEquals("backup DB body must run first on MODEL_EXECUTOR", "backup",
                executionOrder.get(0));
        assertEquals("backup completion may hand off the following migration", 1,
                modelExecutor.pendingTaskCount());
        modelExecutor.runNext();
        assertEquals(2, executionOrder.size());
        assertEquals("following migration must run after backup snapshot", "migration",
                executionOrder.get(1));
        assertEquals(0, coordinator.pendingDeferredCount());
    }

    @Test
    public void atomicAdmissionDefersWhenOrganizerAppearsAfterTaskIsScheduled() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        DeterministicExecutor modelExecutor = new DeterministicExecutor();
        AtomicInteger hotseatDbBodyRuns = new AtomicInteger();
        AtomicBoolean correlatedLoaderCompleted = new AtomicBoolean();

        // The helper has reserved its logical FIFO position while no writer is held, then
        // scheduled its MODEL_EXECUTOR admission attempt.
        scheduleAtomicHotseatWork(modelExecutor, hotseatDbBodyRuns::incrementAndGet);
        assertEquals(1, modelExecutor.pendingTaskCount());
        assertEquals(1, coordinator.pendingDeferredCount());

        LayoutWriteCoordinator.Lease organizer = coordinator.tryAcquire(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(organizer);
        try {
            // This is the review regression: organizer appears after schedule but before admission.
            modelExecutor.runNext();
            assertEquals("busy admission must not execute the Hotseat DB body", 0,
                    hotseatDbBodyRuns.get());
            assertEquals("busy admission must retain exactly one reservation", 1,
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
            assertEquals("call-time reservation must defer before MODEL_EXECUTOR starts", 1,
                    coordinator.pendingDeferredCount());
            assertEquals(0, modelExecutor.pendingTaskCount());
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

    private ItemInfo addTestHotseatItem() throws Exception {
        var item = new com.android.launcher3.model.data.WorkspaceItemInfo();
        item.title = "Issue156 Hotseat fixture";
        item.user = Process.myUserHandle();
        item.intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(context, LawnchairLauncher.class));
        model.getWriter(false, CellPosMapper.DEFAULT, null).addItemToDatabase(
                item, Favorites.CONTAINER_HOTSEAT, 0, 0, 0);
        awaitModelExecutor();
        seededHotseatItem = item;
        return item;
    }

    private int rankInTable(String tableName, int itemId) throws Exception {
        AtomicInteger rank = new AtomicInteger(Integer.MIN_VALUE);
        MODEL_EXECUTOR.submit(() -> {
            try (Cursor cursor = dbController.getDb().query(
                    tableName,
                    new String[] {Favorites.RANK},
                    itemIdMatch(itemId),
                    null,
                    null,
                    null,
                    null)) {
                assertTrue("expected fixture item in " + tableName, cursor.moveToFirst());
                rank.set(cursor.getInt(0));
            }
        }).get();
        return rank.get();
    }

    private boolean backupTableExists() throws Exception {
        AtomicBoolean exists = new AtomicBoolean();
        MODEL_EXECUTOR.submit(() -> exists.set(
                tableExists(dbController.getDb(), HYBRID_HOTSEAT_BACKUP_TABLE))).get();
        return exists.get();
    }

    private static void awaitModelExecutor() throws Exception {
        MODEL_EXECUTOR.submit(() -> { }).get(15, TimeUnit.SECONDS);
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
