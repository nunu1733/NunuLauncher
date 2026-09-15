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
import static org.junit.Assert.fail;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

import app.lawnchair.LawnchairLauncher;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RestoreLeaseDeferredLoaderThreadAffinityTest {

    private static final long TIMEOUT_SECONDS = 30L;
    private static final int DEFERRED_WINDOW_CYCLES = 3;
    private static final String RELEASING_THREAD_NAME = "NovaBackupRestoreTestThread";

    /**
     * The three wrong-thread failure signatures recorded by the #287 T4 device
     * session. The post-fix oracle requires their absence in this process's
     * logcat (TA-AC-03's logcat-absence requirement, applied to the
     * deterministic deferral window).
     */
    private static final String[] FORBIDDEN_SIGNATURES = {
        "Cache accessed on wrong thread",
        "Can't create handler inside Thread[NovaBackupRestore]",
        "Desktop items loading interrupted",
        "Deferred callback threw",
    };

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
        // Every cleanup step records failures instead of swallowing them, and the
        // final reload rebinds the in-memory model to the restored DB so no test
        // state leaks into later tests of this process-wide lane.
        List<Throwable> cleanupFailures = new ArrayList<>();
        for (BgDataModel.Callbacks cb : new ArrayList<>(addedCallbacks)) {
            removeModelCallbackQuietly(cb, cleanupFailures);
        }
        restoreFavoritesQuietly(cleanupFailures);
        forceReloadAndAwaitBindQuietly(cleanupFailures);
        if (!cleanupFailures.isEmpty()) {
            fail("Cleanup failed (" + cleanupFailures.size() + " error(s)); the first was: "
                    + cleanupFailures.get(0));
        }
    }

    /**
     * Deterministically reconstructs the T4 wrong-thread window: a Looper-less
     * thread holds the BACKUP_RESTORE lease while a loader starts, the loader
     * defers behind the lease, and the lease releases on that same thread. The
     * deferred load must complete on the model worker thread with the seeded
     * workspace item intact — never on the releasing thread, where the icon
     * cache's worker-thread assertion fires before anything else can run.
     *
     * <p>Runs repeated defer/release cycles (the spec's repeated restore/reload
     * verification, applied to the deterministic window) and closes with a
     * logcat sweep — scoped to entries emitted after this test's marker line —
     * asserting the recorded wrong-thread signatures are absent.
     */
    @Test
    public void deferredTokenlessLoaderCompletesOnModelExecutorNotOnReleaseThread()
            throws Exception {
        long seededRowId = seedWorkspaceApplicationRow("Issue 298 seeded app");
        List<Throwable> cleanupFailures = new ArrayList<>();

        // Baseline load (not deferred): learns the current workspace item count
        // including the seeded row, so the deferred loads can be compared against it.
        int baselineItemCount = reloadAndAwaitBindItemCount(cleanupFailures);
        assertTrue("Seeded item missing from baseline load", baselineItemCount >= 1);

        // Unique window marker: the logcat oracle only inspects entries logged
        // after this line, so unrelated earlier output in this process cannot
        // fail the sweep.
        String windowMarker = "WINDOW-" + java.util.UUID.randomUUID();
        android.util.Log.i("Issue298Oracle", windowMarker);

        try {
            for (int cycle = 1; cycle <= DEFERRED_WINDOW_CYCLES; cycle++) {
                runDeferredWindowCycle(cycle, baselineItemCount, cleanupFailures);
            }
        } finally {
            deleteRowQuietly(seededRowId, cleanupFailures);
        }

        // TA-AC-03 logcat oracle: none of the recorded wrong-thread signatures
        // may appear in this process's logcat after the marker. The pre-fix code
        // produced all of these in the very window reconstructed above
        // (implementer-reported red run).
        assertSignaturesAbsentFromLogcat(windowMarker, cleanupFailures);

        if (!cleanupFailures.isEmpty()) {
            fail("Cleanup failed (" + cleanupFailures.size() + " error(s)); the first was: "
                    + cleanupFailures.get(0));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * One deterministic deferral window: a fresh Looper-less thread acquires the
     * restore-family lease, a reload dispatched from main parks in the
     * coordinator FIFO, and the lease releases on that same thread.
     */
    private void runDeferredWindowCycle(
            int cycle, int baselineItemCount, List<Throwable> cleanupFailures)
            throws InterruptedException {
        CountDownLatch leaseAcquired = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        AtomicReference<LayoutWriteCoordinator.Lease> leaseRef = new AtomicReference<>();
        AtomicReference<Throwable> releaseThreadFailure = new AtomicReference<>();
        Thread restoreLike = new Thread(() -> {
            LayoutWriteCoordinator.Lease lease = null;
            try {
                lease = coordinator.acquireBlockingQuietly(
                        LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE);
                leaseRef.set(lease);
                leaseAcquired.countDown();
                if (!releaseNow.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    releaseThreadFailure.set(new AssertionError("Release signal timed out"));
                }
            } catch (Throwable t) {
                releaseThreadFailure.set(t);
                leaseAcquired.countDown();
            } finally {
                // The Looper-less holder must never exit with the lease held: the
                // coordinator is a process-wide singleton and a leaked lease would
                // defer (or deadlock) every later writer and loader in this lane.
                if (lease != null) {
                    lease.close();
                }
            }
        }, RELEASING_THREAD_NAME);
        restoreLike.start();

        CountDownLatch bound = new CountDownLatch(1);
        AtomicInteger boundItemCount = new AtomicInteger(-1);
        BgDataModel.Callbacks callbacks = bindItemCountCallback(bound, boundItemCount);
        try {
            assertTrue("Cycle " + cycle + ": restore-like thread could not acquire the lease",
                    leaseAcquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            addModelCallback(callbacks);

            int baselineDeferred = coordinator.pendingDeferredCount();
            getInstrumentation().runOnMainSync(() -> model.forceReload());

            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1000;
            while (coordinator.pendingDeferredCount() <= baselineDeferred
                    && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25);
            }
            assertTrue("Cycle " + cycle + ": loader was not deferred behind the lease",
                    coordinator.pendingDeferredCount() > baselineDeferred);

            releaseNow.countDown();
            restoreLike.join(TIMEOUT_SECONDS * 1000);

            assertTrue("Cycle " + cycle + ": workspace load did not complete after release",
                    bound.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("Cycle " + cycle + ": loaded workspace must keep the seeded item",
                    baselineItemCount, boundItemCount.get());

            long modelLoadedDeadline = SystemClock.elapsedRealtime() + TIMEOUT_SECONDS * 1000;
            while (!model.isModelLoaded() && SystemClock.elapsedRealtime() < modelLoadedDeadline) {
                SystemClock.sleep(25);
            }
            assertTrue("Cycle " + cycle + ": model did not reach the loaded state",
                    model.isModelLoaded());
        } finally {
            // Idempotent: the normal path already counted down. Every exit path —
            // including an assertion failure while the lease is still held — must
            // let the Looper-less holder terminate (its own finally closes the
            // lease) before this cycle ends.
            releaseNow.countDown();
            restoreLike.join(TIMEOUT_SECONDS * 1000);
            if (restoreLike.isAlive()) {
                restoreLike.interrupt();
                restoreLike.join(TIMEOUT_SECONDS * 1000);
            }
            if (restoreLike.isAlive()) {
                cleanupFailures.add(new AssertionError(
                        RELEASING_THREAD_NAME + " did not terminate; lease may still be held"));
            }
            removeModelCallbackQuietly(callbacks, cleanupFailures);
        }

        assertNull("Cycle " + cycle + ": failure escaped on the releasing thread",
                releaseThreadFailure.get());
    }

    /**
     * Dumps this process's logcat and fails if any recorded wrong-thread
     * signature appears at or after the window marker. The deferred window is
     * deterministic, so a regression would reproduce the signatures here
     * exactly as the implementer-reported pre-fix red run did.
     */
    private void assertSignaturesAbsentFromLogcat(
            String windowMarker, List<Throwable> cleanupFailures) {
        String dump;
        try {
            java.lang.Process process = new ProcessBuilder(
                    "logcat", "-d", "--pid=" + Process.myPid())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                cleanupFailures.add(new AssertionError(
                        "logcat dump exited with " + exitCode + "; the signature "
                                + "oracle cannot be evaluated"));
                return;
            }
            dump = builder.toString();
        } catch (Throwable t) {
            cleanupFailures.add(new AssertionError("Could not read logcat for the "
                    + "wrong-thread signature oracle", t));
            return;
        }
        int markerIndex = dump.lastIndexOf(windowMarker);
        if (markerIndex < 0) {
            cleanupFailures.add(new AssertionError("Logcat window marker was not "
                    + "found; the signature oracle cannot be evaluated"));
            return;
        }
        String window = dump.substring(markerIndex);
        for (String signature : FORBIDDEN_SIGNATURES) {
            assertFalse("Forbidden wrong-thread signature present in the test window's "
                    + "logcat: " + signature, window.contains(signature));
        }
    }

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
    private int reloadAndAwaitBindItemCount(List<Throwable> failures)
            throws InterruptedException {
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
            removeModelCallbackQuietly(callbacks, failures);
        }
    }

    private void forceReloadAndAwaitBindQuietly(List<Throwable> failures) {
        try {
            CountDownLatch bound = new CountDownLatch(1);
            BgDataModel.Callbacks callbacks = new BgDataModel.Callbacks() {
                @Override
                public void finishBindingItems(IntSet pagesBoundFirst) {
                    bound.countDown();
                }
            };
            addModelCallback(callbacks);
            try {
                getInstrumentation().runOnMainSync(() -> model.forceReload());
                if (!bound.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    failures.add(new AssertionError(
                            "Post-cleanup model reload did not complete"));
                }
            } finally {
                removeModelCallbackQuietly(callbacks, failures);
            }
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private void waitForModelIdle() {
        if (model.isModelLoaded()) {
            return;
        }
        List<Throwable> failures = new ArrayList<>();
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
            removeModelCallbackQuietly(cb, failures);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Model-idle callback cleanup failed", failures.get(0));
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
                    String name = cursor.getColumnName(i);
                    // Favorites may carry BLOB columns (e.g. icons) written by
                    // earlier classes in this lane; read them as blobs instead
                    // of tripping CursorWindow's BLOB-to-string conversion.
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_BLOB:
                            row.put(name, cursor.getBlob(i));
                            break;
                        case Cursor.FIELD_TYPE_NULL:
                            row.putNull(name);
                            break;
                        default:
                            row.put(name, cursor.getString(i));
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void restoreFavoritesQuietly(List<Throwable> failures) {
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
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private void deleteRowQuietly(long rowId, List<Throwable> failures) {
        try {
            model.getModelDbController().getDb().delete(
                    Favorites.TABLE_NAME, Favorites._ID + "=?",
                    new String[] {String.valueOf(rowId)});
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private void addModelCallback(BgDataModel.Callbacks cb) {
        getInstrumentation().runOnMainSync(() -> model.addCallbacks(cb));
        addedCallbacks.add(cb);
    }

    private void removeModelCallbackQuietly(BgDataModel.Callbacks cb,
            List<Throwable> failures) {
        try {
            getInstrumentation().runOnMainSync(() -> model.removeCallbacks(cb));
            addedCallbacks.remove(cb);
        } catch (Throwable t) {
            failures.add(t);
        }
    }
}
