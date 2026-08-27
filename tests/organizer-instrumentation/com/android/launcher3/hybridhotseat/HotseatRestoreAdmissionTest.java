package com.android.launcher3.hybridhotseat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.launcher3.model.LayoutWriteCoordinator;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
