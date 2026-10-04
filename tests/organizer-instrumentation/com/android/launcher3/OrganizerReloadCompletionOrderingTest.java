package com.android.launcher3;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.util.IntSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Regression test for the causal boundary of an organizer reload completion.
 *
 * <p>The package matches {@link LauncherModel} so this test can call the
 * package-private correlated reload seam without reflection. The callback
 * itself is the oracle: before the fix, the existing completion runnable fires
 * before the loader reaches the transaction barrier; after the fix, it cannot
 * fire until the barrier is released and the transaction can close.</p>
 */
@RunWith(AndroidJUnit4.class)
public class OrganizerReloadCompletionOrderingTest {

    private static final long RELOAD_TIMEOUT_SECONDS = 15L;
    private static final long HOLD_PROBE_SECONDS = 3L;

    private LauncherModel model;
    private final List<BgDataModel.Callbacks> addedCallbacks = new ArrayList<>();

    @Before
    public void setUp() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        model = LauncherAppState.getInstance(context).getModel();
    }

    @After
    public void tearDown() {
        for (var cb : new ArrayList<>(addedCallbacks)) {
            removeModelCallbackQuietly(cb);
        }
    }

    /**
     * Intentionally red against the old ordering: the completion runnable
     * must not fire while the loader transaction is held incomplete.
     *
     * <p>Causal chain of the hold (Issue #150 audit): the barrier blocks the UI
     * thread inside {@code onInitialBindComplete}, and {@code LoaderTask} parks
     * itself on main-looper idleness in {@code waitForIdle()} after
     * {@code bindWorkspace} and before {@code transaction.commit()}. The held
     * callback therefore pins the loader before the transaction boundary. The
     * probe below makes that hold an observed invariant rather than an
     * assumption: while the barrier is held, the completion must not fire even
     * given generous scheduling time, so the oracle fails whenever the hold is
     * broken, and never passes merely because the scheduler was slow.
     */
    @Test
    public void completionRunnableWaitsForLoaderTransactionBoundary() throws Exception {
        var barrier = new LoaderTransactionBarrier();
        addModelCallback(barrier);
        waitForModelIdle();
        barrier.arm();

        var lease = LayoutWriteCoordinator.getInstance()
                .tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("Must acquire organizer lease", lease);
        try {
            var completionFired = new AtomicBoolean(false);
            var completion = new CountDownLatch(1);
            model.forceReloadForOrganizer(
                    1L,
                    lease.token(),
                    () -> {
                        completionFired.set(true);
                        completion.countDown();
                    },
                    () -> { });

            barrier.awaitEntered();
            assertFalse(
                    "Loader transaction committed before the test barrier",
                    model.isModelLoaded());
            assertFalse(
                    "Organizer completion fired before the loader transaction boundary",
                    completionFired.get());

            // The hold must be causal, not a scheduling accident: give the loader
            // generous time to commit, close, and — if the hold were broken —
            // deliver its completion anyway. Any firing in this window means the
            // barrier does not own the transaction boundary.
            assertFalse(
                    "Completion fired while the loader transaction boundary was held",
                    completion.await(HOLD_PROBE_SECONDS, TimeUnit.SECONDS));
            assertFalse(
                    "Organizer completion state changed during the held boundary",
                    completionFired.get());

            barrier.release();
            assertTrue(
                    "Organizer completion did not fire after the loader transaction boundary",
                    completion.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            barrier.assertWaitDidNotFail();
        } finally {
            barrier.release();
            lease.close();
            waitForModelIdle();
        }
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

    private void waitForModelIdle() {
        if (model.isModelLoaded()) {
            return;
        }
        var latch = new CountDownLatch(1);
        var cb = new BgDataModel.Callbacks() {
            @Override
            public void finishBindingItems(IntSet pagesBoundFirst) {
                latch.countDown();
            }
        };
        addModelCallback(cb);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(model::forceReload);
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

    /** Holds the main-thread bind callback until the loader may commit. */
    private static final class LoaderTransactionBarrier implements BgDataModel.Callbacks {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(false);
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
            entered.countDown();
            try {
                if (!released.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    waitFailed.set(true);
                }
            } catch (InterruptedException e) {
                waitFailed.set(true);
                Thread.currentThread().interrupt();
            }
            pendingTasks.executeAllAndDestroy();
        }

        void arm() {
            assertTrue("Loader transaction barrier was already armed",
                    armed.compareAndSet(false, true));
        }

        void awaitEntered() throws InterruptedException {
            assertTrue("Loader did not reach the transaction barrier",
                    entered.await(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        void release() {
            released.countDown();
        }

        void assertWaitDidNotFail() {
            assertFalse("Loader transaction barrier timed out or was interrupted",
                    waitFailed.get());
        }
    }
}
