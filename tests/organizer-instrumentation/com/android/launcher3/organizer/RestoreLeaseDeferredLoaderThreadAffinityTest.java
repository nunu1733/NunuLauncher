// Issue #298: a tokenless LoaderTask deferred behind the restore-family lease is
// drained by LayoutWriteCoordinator inline on the lease-releasing thread. For a
// Nova restore that thread is the Looper-less NovaBackupRestore thread, so the
// load body (icon cache worker-thread affinity, Handler creation) must not run
// there: it has to hand itself back to MODEL_EXECUTOR and retry admission.
package com.android.launcher3.organizer;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.IntSet;
import com.android.launcher3.util.RunnableList;

import app.lawnchair.LawnchairLauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RestoreLeaseDeferredLoaderThreadAffinityTest {

    private static final long TIMEOUT_SECONDS = 30L;
    private static final String RELEASING_THREAD_NAME = "NovaBackupRestoreTestThread";

    private Context context;
    private LauncherModel model;
    private LayoutWriteCoordinator coordinator;
    private final List<BgDataModel.Callbacks> addedCallbacks = new ArrayList<>();
    private List<ContentValues> snapshotRows;

    @Before
    public void setUp() {
        context = getInstrumentation().getTargetContext();
        model = LauncherAppState.getInstance(context).getModel();
        coordinator = LayoutWriteCoordinator.getInstance();
        waitForModelIdle();
        snapshotRows = snapshotFavorites();
    }

    @After
    public void tearDown() {
        for (BgDataModel.Callbacks cb : new ArrayList<>(addedCallbacks)) {
            removeModelCallbackQuietly(cb);
        }
        restoreFavoritesQuietly();
    }

    /**
     * Deterministically reconstructs the T4 wrong-thread window: a Looper-less
     * thread holds the BACKUP_RESTORE lease while a loader starts, the loader
     * defers behind the lease, and the lease releases on that same thread. The
     * deferred load must complete on the model worker thread with the seeded
     * workspace item intact — never on the releasing thread, where the icon
     * cache's worker-thread assertion and Handler creation would fail.
     */
    @Test
    public void deferredTokenlessLoaderCompletesOnModelExecutorNotOnReleaseThread()
            throws Exception {
        long seededRowId = seedWorkspaceApplicationRow("Issue 298 seeded app");

        // Baseline load (not deferred): learns the current workspace item count
        // including the seeded row, so the deferred load can be compared against it.
        int baselineItemCount = reloadAndAwaitBindItemCount();
        assertTrue("Seeded item missing from baseline load", baselineItemCount >= 1);

        CountDownLatch leaseAcquired = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        AtomicReference<LayoutWriteCoordinator.Lease> leaseRef = new AtomicReference<>();
        AtomicReference<Throwable> releaseThreadFailure = new AtomicReference<>();
        Thread restoreLike = new Thread(() -> {
            try {
                leaseRef.set(coordinator.acquireBlockingQuietly(
                        LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE));
                leaseAcquired.countDown();
                if (!releaseNow.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    releaseThreadFailure.set(new AssertionError("Release signal timed out"));
                    return;
                }
                // The coordinator drains the deferred FIFO inline on THIS thread.
                leaseRef.get().close();
            } catch (Throwable t) {
                releaseThreadFailure.set(t);
                leaseAcquired.countDown();
            }
        }, RELEASING_THREAD_NAME);
        restoreLike.start();
        assertTrue("Restore-like thread could not acquire the lease",
                leaseAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        CountDownLatch bound = new CountDownLatch(1);
        AtomicInteger boundItemCount = new AtomicInteger(-1);
        BgDataModel.Callbacks callbacks = bindItemCountCallback(bound, boundItemCount);
        addModelCallback(callbacks);
        try {
            int baselineDeferred = coordinator.pendingDeferredCount();
            getInstrumentation().runOnMainSync(() -> model.forceReload());

            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1000;
            while (coordinator.pendingDeferredCount() <= baselineDeferred
                    && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25);
            }
            assertTrue("Loader was not deferred behind the restore-family lease",
                    coordinator.pendingDeferredCount() > baselineDeferred);

            releaseNow.countDown();
            restoreLike.join(TIMEOUT_SECONDS * 1000);
            assertFalse("Restore-like releasing thread is still alive", restoreLike.isAlive());

            assertTrue("Workspace load did not complete after the lease release",
                    bound.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("Loaded workspace must keep the seeded item", baselineItemCount,
                    boundItemCount.get());
            assertNull("Failure escaped on the releasing thread", releaseThreadFailure.get());

            long modelLoadedDeadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1000;
            while (!model.isModelLoaded() && SystemClock.elapsedRealtime() < modelLoadedDeadline) {
                SystemClock.sleep(25);
            }
            assertTrue("Model did not reach the loaded state after the deferred load",
                    model.isModelLoaded());
        } finally {
            removeModelCallbackQuietly(callbacks);
            deleteRowQuietly(seededRowId);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private BgDataModel.Callbacks bindItemCountCallback(
            CountDownLatch bound, AtomicInteger boundItemCount) {
        return new BgDataModel.Callbacks() {
            @Override
            public void onInitialBindComplete(
                    IntSet boundPages,
                    RunnableList pendingTasks,
                    RunnableList onCompleteSignal,
                    int workspaceItemCount,
                    boolean isBindSync) {
                boundItemCount.set(workspaceItemCount);
                bound.countDown();
            }
        };
    }

    /** Forces a reload while no lease is held and returns the bound item count. */
    private int reloadAndAwaitBindItemCount() throws InterruptedException {
        CountDownLatch bound = new CountDownLatch(1);
        AtomicInteger boundItemCount = new AtomicInteger(-1);
        BgDataModel.Callbacks callbacks = bindItemCountCallback(bound, boundItemCount);
        addModelCallback(callbacks);
        try {
            getInstrumentation().runOnMainSync(() -> model.forceReload());
            assertTrue("Baseline workspace load did not complete",
                    bound.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            return boundItemCount.get();
        } finally {
            removeModelCallbackQuietly(callbacks);
        }
    }

    private void waitForModelIdle() {
        if (model.isModelLoaded()) {
            return;
        }
        CountDownLatch bound = new CountDownLatch(1);
        BgDataModel.Callbacks cb = new BgDataModel.Callbacks() {
            @Override
            public void finishBindingItems(IntSet pagesBoundFirst) {
                bound.countDown();
            }
        };
        addModelCallback(cb);
        try {
            getInstrumentation().runOnMainSync(() -> model.forceReload());
            assertTrue("Model did not become idle within the timeout",
                    bound.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for model idle", e);
        } finally {
            removeModelCallbackQuietly(cb);
        }
    }

    private long seedWorkspaceApplicationRow(String title) {
        long profileId = UserCache.INSTANCE.get(context)
                .getSerialNumberForUser(Process.myUserHandle());
        int rowId = model.getModelDbController().generateNewItemId();
        String intent = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setComponent(new android.content.ComponentName(
                        context.getPackageName(), LawnchairLauncher.class.getName()))
                .toUri(0);
        model.getModelDbController().getDb().insertOrThrow(
                Favorites.TABLE_NAME,
                null,
                rowValues(rowId, title, intent, profileId, Favorites.CONTAINER_DESKTOP));
        return rowId;
    }

    private ContentValues rowValues(
            int id, String title, String intent, long profileId, int container) {
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.TITLE, title);
        values.put(Favorites.INTENT, intent);
        values.put(Favorites.CONTAINER, container);
        values.put(Favorites.SCREEN, 0L);
        values.put(Favorites.CELLX, 0);
        values.put(Favorites.CELLY, 0);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
        values.put(Favorites.APPWIDGET_ID, -1);
        values.put(Favorites.MODIFIED, 1_000L);
        values.put(Favorites.RESTORED, 0);
        values.put(Favorites.PROFILE_ID, profileId);
        values.put(Favorites.RANK, 0);
        values.put(Favorites.OPTIONS, 0);
        values.put(Favorites.APPWIDGET_SOURCE, -1);
        return values;
    }

    private List<ContentValues> snapshotFavorites() {
        List<ContentValues> rows = new ArrayList<>();
        try (Cursor cursor = model.getModelDbController().getDb().query(
                Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID)) {
            while (cursor.moveToNext()) {
                ContentValues row = new ContentValues();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    row.put(cursor.getColumnName(i), cursor.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void restoreFavoritesQuietly() {
        if (snapshotRows == null) {
            return;
        }
        try {
            model.getModelDbController().getDb().beginTransaction();
            try {
                model.getModelDbController().getDb().delete(Favorites.TABLE_NAME, null, null);
                for (ContentValues row : snapshotRows) {
                    model.getModelDbController().getDb()
                            .insertOrThrow(Favorites.TABLE_NAME, null, row);
                }
                model.getModelDbController().getDb().setTransactionSuccessful();
            } finally {
                model.getModelDbController().getDb().endTransaction();
            }
        } catch (Exception e) {
            // Fixture restore must not fail the suite; the next start re-derives state.
        }
    }

    private void deleteRowQuietly(long rowId) {
        try {
            model.getModelDbController().getDb().delete(
                    Favorites.TABLE_NAME, Favorites._ID + "=?",
                    new String[] {String.valueOf(rowId)});
        } catch (Exception e) {
            // Cleanup only.
        }
    }

    private void addModelCallback(BgDataModel.Callbacks cb) {
        getInstrumentation().runOnMainSync(() -> model.addCallbacks(cb));
        addedCallbacks.add(cb);
    }

    private void removeModelCallbackQuietly(BgDataModel.Callbacks cb) {
        try {
            getInstrumentation().runOnMainSync(() -> model.removeCallbacks(cb));
            addedCallbacks.remove(cb);
        } catch (Exception e) {
            // Swallow cleanup exceptions.
        }
    }
}
