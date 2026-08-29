package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.launcher3.model.LayoutWriteCoordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LayoutWriteCoordinatorTest {
    @Test public void organizerLeaseIsExclusiveAndReentrantOnlyForOwner() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease outer = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(outer);
        assertNull(c.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
        LayoutWriteCoordinator.Lease inner = c.tryReenter(
                LayoutWriteCoordinator.OwnerKind.ORGANIZER, outer.token());
        assertNotNull(inner); inner.close(); outer.close();
    }

    // --- Issue #113: bounded same-thread MODEL_WRITER re-entry ---

    @Test public void modelWriterLeaseIsSameThreadReentrantWithSameToken() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease outer = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
        assertNotNull(outer);
        try {
            LayoutWriteCoordinator.Lease inner = c.tryReenterModelWriter();
            assertNotNull("owning thread re-enters its MODEL_WRITER lease", inner);
            assertEquals(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, inner.kind());
            assertEquals("re-entry view keeps the logical outer token",
                    outer.token(), inner.token());
            // Nested close decrements recursion only; the lease stays held.
            inner.close();
            assertNull("lease still held after nested close",
                    c.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
        } finally {
            outer.close();
        }
        // The outermost close unlocked exactly once.
        try (LayoutWriteCoordinator.Lease post =
                     c.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull(post);
        }
    }

    @Test public void modelWriterReentryIsDeniedToOtherKindsAndThreads() throws Exception {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();

        // A different owner kind must not obtain MODEL_WRITER re-entry.
        LayoutWriteCoordinator.Lease restore = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.RESTORE);
        assertNotNull(restore);
        assertNull(c.tryReenterModelWriter());
        restore.close();

        // A different thread must not re-enter a MODEL_WRITER holder's lease.
        LayoutWriteCoordinator.Lease outer = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
        assertNotNull(outer);
        AtomicBoolean foreignEntered = new AtomicBoolean(false);
        Thread foreign = new Thread(() -> {
            LayoutWriteCoordinator.Lease stolen = c.tryReenterModelWriter();
            foreignEntered.set(stolen != null);
            if (stolen != null) stolen.close();
        });
        foreign.start();
        foreign.join(5_000);
        assertFalse("cross-thread re-entry must stay denied", foreignEntered.get());
        outer.close();
    }

    @Test public void exactCorrelatedLoaderGetsScopedCapabilityAndTokenlessWorkDefers()
            throws Exception {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease outer = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(outer);
        AtomicBoolean exactCapability = new AtomicBoolean(false);
        AtomicBoolean tokenlessRan = new AtomicBoolean(false);
        try {
            Thread exactLoader = new Thread(() -> c.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    outer.token(),
                    true,
                    () -> {
                        LayoutWriteCoordinator.Lease capability =
                                c.tryAcquireOrganizerCapability(outer.token());
                        exactCapability.set(capability != null);
                        if (capability != null) capability.close();
                    }));
            exactLoader.start();
            exactLoader.join();
            assertTrue(exactCapability.get());

            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> tokenlessRan.set(true));
            assertTrue(!tokenlessRan.get());
        } finally {
            outer.close();
        }
        assertTrue(tokenlessRan.get());
    }

    // --- AC-03 FIFO exactly-once tests (Issue #60) ---

    @Test
    public void deferredFifoOrderAcrossMultipleLeases() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        List<Integer> order = new ArrayList<>();

        // First lease: defer 2 entries, release, verify FIFO order.
        LayoutWriteCoordinator.Lease lease1 = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(lease1);
        try {
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> order.add(1));
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> order.add(2));
            assertEquals(0, order.size());
        } finally {
            lease1.close();
        }
        assertEquals(2, order.size());
        assertEquals(1, (int) order.get(0));
        assertEquals(2, (int) order.get(1));
        assertEquals(0, c.pendingDeferredCount());

        // Second lease: defer 2 more entries, release, verify FIFO again.
        LayoutWriteCoordinator.Lease lease2 = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(lease2);
        try {
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> order.add(3));
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> order.add(4));
        } finally {
            lease2.close();
        }
        assertEquals(4, order.size());
        assertEquals(3, (int) order.get(2));
        assertEquals(4, (int) order.get(3));
        assertEquals(0, c.pendingDeferredCount());
    }

    @Test
    public void throwingDeferredCallbackDoesNotPreventLaterEntries() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease lease = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(lease);
        AtomicInteger runCount = new AtomicInteger(0);
        try {
            // First callback throws; later ones must still run.
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> { throw new RuntimeException("expected first"); });
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    runCount::incrementAndGet);
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    runCount::incrementAndGet);
        } finally {
            lease.close();
        }
        assertEquals(2, runCount.get());
        assertEquals(0, c.pendingDeferredCount());
    }

    @Test
    public void deferredOperationFutureCompletesExceptionallyAndLaterEntriesStillRun()
            throws Exception {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease lease = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(lease);
        AtomicInteger runCount = new AtomicInteger(0);
        CompletableFuture<Integer> throwingFuture;
        CompletableFuture<Integer> okFuture;
        try {
            throwingFuture = c.runOrDeferWithOperationFuture(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> { throw new RuntimeException("expected"); });
            okFuture = c.runOrDeferWithOperationFuture(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> 42);
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    runCount::incrementAndGet);
            assertFalse(throwingFuture.isDone());
            assertFalse(okFuture.isDone());
        } finally {
            lease.close();
        }
        // Throwing future completed exceptionally.
        assertTrue(throwingFuture.isDone());
        assertTrue(throwingFuture.isCompletedExceptionally());
        // Later entries still completed.
        assertTrue(okFuture.isDone());
        assertEquals(42, okFuture.get().intValue());
        assertEquals(1, runCount.get());
        assertEquals(0, c.pendingDeferredCount());
    }

    @Test
    public void reentrantReleaseDoesNotDoubleRunDeferredEntries() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        LayoutWriteCoordinator.Lease lease = c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull(lease);
        AtomicInteger runCount = new AtomicInteger(0);
        try {
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    runCount::incrementAndGet);
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    () -> {
                        runCount.incrementAndGet();
                        // Reentrant close while drain loop is executing.
                        // LeaseImpl.close() sets closed=true and calls release(),
                        // which sees current==null and returns early.
                        lease.close();
                    });
            c.runOrDefer(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false,
                    runCount::incrementAndGet);
        } finally {
            lease.close();
        }
        // All three entries ran exactly once.
        assertEquals(3, runCount.get());
        assertEquals(0, c.pendingDeferredCount());
    }

    // --- Issue #168: restore-family lease visibility for restore-safe DB cleanup ---

    @Test
    public void restoreFamilyLeaseIsReportedAsActive() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        assertFalse(c.hasActiveRestoreFamilyLease());
        try (LayoutWriteCoordinator.Lease lease =
                     c.tryAcquire(LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE)) {
            assertNotNull(lease);
            assertTrue(c.hasActiveRestoreFamilyLease());
        }
        assertFalse(c.hasActiveRestoreFamilyLease());
    }

    @Test
    public void plainRestoreLeaseIsReportedAsActive() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        try (LayoutWriteCoordinator.Lease lease =
                     c.tryAcquire(LayoutWriteCoordinator.OwnerKind.RESTORE)) {
            assertNotNull(lease);
            assertTrue(c.hasActiveRestoreFamilyLease());
        }
        assertFalse(c.hasActiveRestoreFamilyLease());
    }

    @Test
    public void nonRestoreLeaseIsNotReportedAsRestoreFamily() {
        LayoutWriteCoordinator c = LayoutWriteCoordinator.getInstance();
        try (LayoutWriteCoordinator.Lease lease =
                     c.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER)) {
            assertNotNull(lease);
            assertFalse(c.hasActiveRestoreFamilyLease());
        }
        assertFalse(c.hasActiveRestoreFamilyLease());
    }
}
