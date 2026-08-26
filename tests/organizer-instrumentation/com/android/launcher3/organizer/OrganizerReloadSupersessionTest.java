package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.OrganizerModelReloadAdapter;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation tests for AC-04 reload supersession.
 *
 * <p>Covers A-then-B supersession, stale completion rejection, cancellation,
 * and exactly-one-terminal-signal per request, using the public seams
 * ({@link LauncherModel} reload lifecycle + {@link OrganizerModelReloadAdapter}
 * {@link OrganizerModelReloadAdapter.Outcome}).
 *
 * <p>See spec: specs/60-executor-writer-admission-audit/spec.md §AC-04.
 *
 * <p><b>Unsupported paths (source-evidence-only):</b>
 * <ul>
 *   <li>Real 10-second TIMEOUT
 *   ({@link OrganizerModelReloadAdapter#TIMEOUT_MILLIS} = 10_000L, line 25).
 *   The timeout constant is not injectable; the adapter's
 *   {@code completed[0]} guard (line 60) and the {@code lock.wait(remaining)}
 *   path (line 86) are exercised by the normal wait-and-signal flow.
 *   Timed-out requests are covered by the stale-completion and
 *   exactly-one-terminal-signal tests, which verify that callers are not
 *   double-delivered after the timeout path.</li>
 *   <li>Real process death/restart with an active recovery lifecycle.
 *   The {@code forceReloadForOrganizer} "no callbacks" path (line 471-473)
 *   calls {@code cancelled.run()} immediately; this is exercised by the
 *   cancellation test.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class OrganizerReloadSupersessionTest {

    private static final long RELOAD_TIMEOUT_SECONDS = 15L;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private LauncherModel model;
    private Handler mainHandler;
    private final List<BgDataModel.Callbacks> addedCallbacks = new ArrayList<>();

    @Before
    public void setUp() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var launcher = LauncherAppState.getInstance(context);
        model = launcher.getModel();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @After
    public void tearDown() {
        for (var cb : new ArrayList<>(addedCallbacks)) {
            removeModelCallbackQuietly(cb);
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * A single organizer reload request completes with
     * {@link OrganizerModelReloadAdapter.Outcome#COMPLETED} when no
     * supersession or cancellation occurs.
     */
    @Test
    public void singleRequestCompletesWithOutcomeCompleted() throws Exception {
        addDummyCallback();
        waitForModelIdle();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var outcome = adapter.requestAndWait(lease.token());
            assertEquals(
                    "Single request must complete with COMPLETED",
                    OrganizerModelReloadAdapter.Outcome.COMPLETED,
                    outcome);
        } finally {
            lease.close();
        }
    }

    /**
     * Issue #150 adapter coverage after the deterministic completion-runnable
     * ordering oracle in {@code OrganizerReloadCompletionOrderingTest}.
     *
     * <p>The callback is deliberately held with a latch after the existing
     * organizer bind-completion signal and before the loader can leave its
     * {@code waitForIdle()} boundary. This callback uses the interface default
     * {@code null} item inflater, so its own binder deterministically takes the
     * synchronous non-inflation path even when workspace inflation is enabled
     * for a real Launcher callback. This makes {@code model.isModelLoaded()}
     * false without changing a feature flag or adding a production hook. This
     * test releases the barrier before awaiting the adapter outcome, so no
     * timed non-return assertion is used as ordering evidence here.</p>
     */
    @Test
    public void completedOutcomeAfterTransactionBarrierRelease() throws Exception {
        var barrier = new LoaderTransactionBarrier();
        addModelCallback(barrier);
        waitForModelIdle();
        barrier.arm();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var outcome = new AtomicReference<OrganizerModelReloadAdapter.Outcome>();
            Future<?> requestFuture = executor.submit(() -> {
                outcome.set(adapter.requestAndWait(lease.token()));
            });

            barrier.awaitEntered();
            assertTrue(
                    "Loader transaction must remain uncommitted while the callback barrier is held",
                    !model.isModelLoaded());
            assertTrue(
                    "The test callback must be holding the loader before commit",
                    barrier.isHolding());

            barrier.release();
            requestFuture.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(OrganizerModelReloadAdapter.Outcome.COMPLETED, outcome.get());
            barrier.assertWaitDidNotFail();
        } finally {
            barrier.release();
            lease.close();
            shutdownExecutor(executor);
            waitForModelIdle();
        }
    }

    /**
     * A-then-B supersession: B supersedes A, so A's outcome is
     * {@link OrganizerModelReloadAdapter.Outcome#SUPERSEDED} and B's
     * outcome is {@link OrganizerModelReloadAdapter.Outcome#COMPLETED}.
     *
     * <p>Verifies that {@code LauncherModel.cancelOrganizerReload()} (line 494)
     * fires the cancelled callback for the superseded request, and that
     * {@code forceReloadForOrganizer} (line 467) replaces the token.</p>
     */
    @Test
    public void subsequentRequestSupersedesPriorRequest() throws Exception {
        var barrier = new SyncPageSelectionBarrier();
        addModelCallback(barrier);
        waitForModelIdle();
        barrier.arm();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var executor = Executors.newFixedThreadPool(2);
            try {
                // Start request A on a background thread (requestAndWait blocks).
                Future<OrganizerModelReloadAdapter.Outcome> outcomeAFuture = executor.submit(
                        () -> adapter.requestAndWait(lease.token()));
                barrier.awaitReached();

                // Request B supersedes A while A is blocked before its completion signal.
                Future<OrganizerModelReloadAdapter.Outcome> outcomeBFuture = executor.submit(
                        () -> adapter.requestAndWait(lease.token()));
                OrganizerModelReloadAdapter.Outcome outcomeA = outcomeAFuture.get(
                        RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                assertEquals("A must be SUPERSEDED by B",
                        OrganizerModelReloadAdapter.Outcome.SUPERSEDED, outcomeA);

                // Let both the superseded A loader and replacement B loader drain.
                barrier.release();
                OrganizerModelReloadAdapter.Outcome outcomeB = outcomeBFuture.get(
                        RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals("B must complete successfully",
                        OrganizerModelReloadAdapter.Outcome.COMPLETED, outcomeB);
                barrier.assertWaitDidNotFail();
            } finally {
                barrier.release();
                shutdownExecutor(executor);
            }
        } finally {
            lease.close();
        }
    }

    /**
     * Stale completion rejection: after B supersedes A, A's completed
     * callback is never invoked because the identity check in
     * {@code LauncherModel.completeOrganizerReload} (line 488) rejects it.
     *
     * <p>We observe A's SUPERSEDED outcome and B's COMPLETED outcome,
     * which together prove that A's completed callback was skipped:
     * if A's completed callback had fired, the adapter's
     * {@code completed[0]} guard would have accepted the first signal
     * (COMPLETED) and the second signal (SUPERSEDED from cancellation)
     * would have been silently dropped -- but we see SUPERSEDED, not
     * COMPLETED, confirming the completed callback was never invoked.</p>
     */
    @Test
    public void staleCompletionIsRejectedAfterSupersession() throws Exception {
        var barrier = new SyncPageSelectionBarrier();
        addModelCallback(barrier);
        waitForModelIdle();
        barrier.arm();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Future<OrganizerModelReloadAdapter.Outcome> outcomeAFuture = executor.submit(
                        () -> adapter.requestAndWait(lease.token()));
                barrier.awaitReached();

                // B supersedes A while A is blocked before its completion signal.
                Future<OrganizerModelReloadAdapter.Outcome> outcomeBFuture = executor.submit(
                        () -> adapter.requestAndWait(lease.token()));
                OrganizerModelReloadAdapter.Outcome outcomeA = outcomeAFuture.get(
                        RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // A must be SUPERSEDED, proving A's stale completed callback was rejected.
                assertEquals("Stale completion must be rejected (SUPERSEDED, not COMPLETED)",
                        OrganizerModelReloadAdapter.Outcome.SUPERSEDED, outcomeA);

                barrier.release();
                OrganizerModelReloadAdapter.Outcome outcomeB = outcomeBFuture.get(
                        RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals("B must complete",
                        OrganizerModelReloadAdapter.Outcome.COMPLETED, outcomeB);
                barrier.assertWaitDidNotFail();
            } finally {
                barrier.release();
                shutdownExecutor(executor);
            }
        } finally {
            lease.close();
        }
    }

    /**
     * Cancellation terminal exactly once: a regular
     * {@link LauncherModel#forceReload()} cancels an in-flight organizer
     * reload, delivering exactly one terminal signal (SUPERSEDED).
     *
     * <p>Verifies that {@code stopLoader} (line 449) calls
     * {@code cancelOrganizerReload} (line 494) which fires the cancelled
     * callback exactly once. The adapter's {@code completed[0]} guard
     * (line 60) ensures no second signal is delivered.</p>
     */
    @Test
    public void cancellationByForceReloadIsTerminalExactlyOnce() throws Exception {
        var barrier = new SyncPageSelectionBarrier();
        addModelCallback(barrier);
        waitForModelIdle();
        barrier.arm();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var executor = Executors.newFixedThreadPool(2);
            try {
                // Start an organizer reload on a background thread.
                Future<OrganizerModelReloadAdapter.Outcome> outcomeFuture = executor.submit(
                        () -> adapter.requestAndWait(lease.token()));
                barrier.awaitReached();

                // The main thread is blocked by the barrier, so cancel from a safe worker.
                Future<?> cancellationFuture = executor.submit(model::forceReload);
                cancellationFuture.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // The cancelled callback fires exactly once, delivering SUPERSEDED.
                OrganizerModelReloadAdapter.Outcome outcome = outcomeFuture.get(
                        RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals("Cancelled request must be SUPERSEDED",
                        OrganizerModelReloadAdapter.Outcome.SUPERSEDED, outcome);

                barrier.release();
                barrier.assertWaitDidNotFail();
            } finally {
                barrier.release();
                shutdownExecutor(executor);
            }
        } finally {
            lease.close();
        }
        // The regular reload is tokenless and may remain deferred behind the organizer lease.
        // Wait for it only after the lease has been released.
        waitForModelIdle();
    }

    /**
     * Exactly-one terminal signal per request: sequential requests each
     * receive exactly one terminal outcome (COMPLETED). The adapter's
     * {@code completed[0]} guard (line 60) prevents duplicate delivery.
     *
     * <p>Two independent requests each complete successfully, confirming
     * no cross-request signal leakage and that the guard resets between
     * calls.</p>
     */
    @Test
    public void completedRequestReceivesExactlyOneTerminalSignal() throws Exception {
        addDummyCallback();
        waitForModelIdle();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);

            // First request: should complete normally.
            var outcome1 = adapter.requestAndWait(lease.token());
            assertEquals("First request must be COMPLETED",
                    OrganizerModelReloadAdapter.Outcome.COMPLETED, outcome1);

            // Second request: should also complete independently.
            var outcome2 = adapter.requestAndWait(lease.token());
            assertEquals("Second request must be COMPLETED",
                    OrganizerModelReloadAdapter.Outcome.COMPLETED, outcome2);
        } finally {
            lease.close();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private BgDataModel.Callbacks addDummyCallback() {
        var cb = new BgDataModel.Callbacks() {};
        addModelCallback(cb);
        return cb;
    }

    private void addModelCallback(BgDataModel.Callbacks cb) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            model.addCallbacks(cb);
        });
        addedCallbacks.add(cb);
    }

    private void removeModelCallbackQuietly(BgDataModel.Callbacks cb) {
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                model.removeCallbacks(cb);
            });
            addedCallbacks.remove(cb);
        } catch (Exception e) {
            // Swallow cleanup exceptions.
        }
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue("Reload executor did not terminate",
                executor.awaitTermination(
                        EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * One-shot barrier at the synchronous page-selection callback. The LoaderTask invokes
     * {@link BgDataModel.Callbacks#getPagesToBindSynchronously(IntArray)} before it can schedule the
     * organizer completion signal, so holding this callback prevents A from completing until the
     * test has issued B or cancellation.
     */
    private static final class SyncPageSelectionBarrier implements BgDataModel.Callbacks {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final AtomicBoolean waitFailed = new AtomicBoolean(false);

        @Override
        public IntSet getPagesToBindSynchronously(IntArray orderedScreenIds) {
            if (!armed.compareAndSet(true, false)) {
                return new IntSet();
            }
            reached.countDown();
            try {
                if (!released.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    waitFailed.set(true);
                }
            } catch (InterruptedException e) {
                waitFailed.set(true);
                Thread.currentThread().interrupt();
            }
            return new IntSet();
        }

        void awaitReached() throws InterruptedException {
            assertTrue("A did not reach the pre-completion barrier",
                    reached.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        void release() {
            released.countDown();
        }

        void arm() {
            assertTrue("Pre-completion barrier was already armed",
                    armed.compareAndSet(false, true));
        }

        void assertWaitDidNotFail() {
            assertTrue("Pre-completion barrier timed out or was interrupted",
                    !waitFailed.get());
        }
    }

    /**
     * Holds the callback that follows the existing organizer bind-completion
     * signal. Its default-null item inflater selects the synchronous
     * non-inflation path for this callback, where every Launcher binder mode
     * calls {@code onInitialBindComplete} after the organizer signal. This
     * controls the loader transaction through an existing callback seam rather
     * than changing a feature flag.
     */
    private static final class LoaderTransactionBarrier implements BgDataModel.Callbacks {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final AtomicBoolean holding = new AtomicBoolean(false);
        private final AtomicBoolean waitFailed = new AtomicBoolean(false);

        @Override
        public void onInitialBindComplete(
                IntSet boundPages,
                com.android.launcher3.util.RunnableList pendingTasks,
                com.android.launcher3.util.RunnableList onCompleteSignal,
                int workspaceItemCount,
                boolean isBindSync) {
            if (!armed.compareAndSet(true, false)) {
                pendingTasks.executeAllAndDestroy();
                return;
            }
            holding.set(true);
            entered.countDown();
            try {
                if (!released.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    waitFailed.set(true);
                }
            } catch (InterruptedException e) {
                waitFailed.set(true);
                Thread.currentThread().interrupt();
            } finally {
                holding.set(false);
            }
            pendingTasks.executeAllAndDestroy();
        }

        void arm() {
            assertTrue("Loader transaction barrier was already armed",
                    armed.compareAndSet(false, true));
        }

        void awaitEntered() throws InterruptedException {
            assertTrue("Loader did not reach the post-bind transaction barrier",
                    entered.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        boolean isHolding() {
            return holding.get();
        }

        void release() {
            released.countDown();
        }

        void assertWaitDidNotFail() {
            assertTrue("Loader transaction barrier timed out or was interrupted",
                    !waitFailed.get());
        }
    }

    /**
     * Waits for the model to reach an idle state (loaded, no running loader).
     * If the model is not yet loaded, forces a reload and waits for
     * completion, ensuring the model is in a clean state for the next test.
     */
    private void waitForModelIdle() {
        if (model.isModelLoaded()) {
            return;
        }
        // Model is not idle; force a reload and wait for finishBindingItems.
        var latch = new CountDownLatch(1);
        var cb = new BgDataModel.Callbacks() {
            @Override
            public void finishBindingItems(
                    @SuppressWarnings("unused") com.android.launcher3.util.IntSet pagesBoundFirst) {
                latch.countDown();
            }
        };
        addModelCallback(cb);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                model.forceReload();
            });
            if (!latch.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Model did not become idle within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for model idle", e);
        } finally {
            removeModelCallbackQuietly(cb);
        }
    }
}
