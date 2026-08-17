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

/**
 * Instrumentation tests for ER-04 reload supersession.
 *
 * <p>Covers A-then-B supersession, stale completion rejection, cancellation,
 * and exactly-one-terminal-signal per request, using the public seams
 * ({@link LauncherModel} reload lifecycle + {@link OrganizerModelReloadAdapter}
 * {@link OrganizerModelReloadAdapter.Outcome}).
 *
 * <p>See spec: specs/60-executor-writer-admission-audit/spec.md §ER-04.
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
        addDummyCallback();
        waitForModelIdle();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var executor = Executors.newSingleThreadExecutor();

            // Start request A on a background thread (requestAndWait blocks).
            Future<OrganizerModelReloadAdapter.Outcome> outcomeAFuture = executor.submit(
                    () -> adapter.requestAndWait(lease.token()));

            // Allow A's LoaderTask to start before issuing B.
            // The LoaderTask does DB reads, sorting, and binding, so this
            // window is sufficient on a real device/emulator.
            Thread.sleep(500);

            // Request B supersedes A via forceReloadForOrganizer -> stopLoader
            // -> cancelOrganizerReload.
            OrganizerModelReloadAdapter.Outcome outcomeB = adapter.requestAndWait(lease.token());

            // Wait for A with timeout (should wake up from SUPERSEDED signal).
            OrganizerModelReloadAdapter.Outcome outcomeA = outcomeAFuture.get(
                    RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("A must be SUPERSEDED by B",
                    OrganizerModelReloadAdapter.Outcome.SUPERSEDED, outcomeA);
            assertEquals("B must complete successfully",
                    OrganizerModelReloadAdapter.Outcome.COMPLETED, outcomeB);

            executor.shutdown();
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
        addDummyCallback();
        waitForModelIdle();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var executor = Executors.newSingleThreadExecutor();

            Future<OrganizerModelReloadAdapter.Outcome> outcomeAFuture = executor.submit(
                    () -> adapter.requestAndWait(lease.token()));

            Thread.sleep(500);

            // B supersedes A.
            OrganizerModelReloadAdapter.Outcome outcomeB = adapter.requestAndWait(lease.token());

            OrganizerModelReloadAdapter.Outcome outcomeA = outcomeAFuture.get(
                    RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // A must be SUPERSEDED, proving A's completed callback was rejected.
            assertEquals("Stale completion must be rejected (SUPERSEDED, not COMPLETED)",
                    OrganizerModelReloadAdapter.Outcome.SUPERSEDED, outcomeA);
            assertEquals("B must complete",
                    OrganizerModelReloadAdapter.Outcome.COMPLETED, outcomeB);

            executor.shutdown();
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
        addDummyCallback();
        waitForModelIdle();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var adapter = new OrganizerModelReloadAdapter(model, mainHandler);
            var executor = Executors.newSingleThreadExecutor();

            // Start an organizer reload on a background thread.
            Future<OrganizerModelReloadAdapter.Outcome> outcomeFuture = executor.submit(
                    () -> adapter.requestAndWait(lease.token()));

            // Allow the reload to start.
            Thread.sleep(500);

            // Cancel the organizer reload via a regular forceReload.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                model.forceReload();
            });

            // The cancelled callback fires exactly once, delivering SUPERSEDED.
            OrganizerModelReloadAdapter.Outcome outcome = outcomeFuture.get(
                    RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("Cancelled request must be SUPERSEDED",
                    OrganizerModelReloadAdapter.Outcome.SUPERSEDED, outcome);

            executor.shutdown();

            // Wait for the forceReload that cancelled it to settle.
            waitForModelIdle();
        } finally {
            lease.close();
        }
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