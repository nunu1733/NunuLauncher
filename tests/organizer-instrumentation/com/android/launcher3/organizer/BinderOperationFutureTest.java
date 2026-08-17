// Issue #60 AC-05: Binder operation-future self-wait.
// Spec 60 Section "AC-05 Binder future".
// Models the LauncherProvider.executeControllerTask pattern (lines 156-191)
// at the coordinator seam.
package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.util.Executors;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-05: The Binder operation-future sequence is proven not to self-wait on
 * {@link com.android.launcher3.util.Executors#MODEL_EXECUTOR}, including
 * release from the deferred-callback thread.
 *
 * <p>The audit hazard (LauncherProvider.java lines 156-191): a Binder thread
 * calls {@link LayoutWriteCoordinator#runOrDeferWithOperationFuture} whose
 * supplier runs {@code MODEL_EXECUTOR.submit(...).get()}. If lease release
 * happened on {@code MODEL_EXECUTOR}, the deferred callback would run on
 * {@code MODEL_EXECUTOR} and the inner {@code submit().get()} would need to
 * execute on the same single-thread executor -- a self-wait.
 *
 * <p>In the current design, {@link LayoutWriteCoordinator#release} runs
 * deferred callbacks inline on whichever thread closes the lease (the
 * releasing thread), NOT on {@code MODEL_EXECUTOR}. Additionally,
 * {@link com.android.launcher3.util.LooperExecutor#execute} has an inline
 * optimization: if the current thread is the executor's looper thread, it
 * runs the task directly rather than posting to the handler, so even in the
 * hazardous case the inner task would run inline and the {@code .get()}
 * would return immediately. The tests below verify the design intent (the
 * callback runs on the releasing thread) and that {@code MODEL_EXECUTOR} is
 * never blocked.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BinderOperationFutureTest {

    private final LayoutWriteCoordinator mCoordinator =
            LayoutWriteCoordinator.getInstance();

    @Test
    public void deferredCallbackRunsOnReleasingThreadNotModelExecutor()
            throws Exception {
        // AC-05: the deferred callback from runOrDeferWithOperationFuture
        // runs on the thread that releases the lease, not on MODEL_EXECUTOR.
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<CompletableFuture<Integer>> operationFuture =
                new AtomicReference<>();
        AtomicBoolean modelExecutorTaskRan = new AtomicBoolean(false);
        CountDownLatch futureReady = new CountDownLatch(1);

        // Step 1: Hold an organizer lease on the test thread.
        LayoutWriteCoordinator.Lease lease =
                mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("organizer lease acquired", lease);

        try {
            // Step 2: Dispatch from a simulated Binder thread (non-executor).
            // The supplier mirrors LauncherProvider.executeControllerTask
            // (line 168): MODEL_EXECUTOR.submit(...).get().
            Thread binderThread = new Thread(() -> {
                try {
                    CompletableFuture<Integer> future = mCoordinator
                            .runOrDeferWithOperationFuture(
                                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                                    /* token= */ 0L,
                                    /* exactOrganizerToken= */ false,
                                    () -> {
                                        callbackThread.set(Thread.currentThread());
                                        try {
                                            return Executors.MODEL_EXECUTOR.submit(
                                                    () -> {
                                                        modelExecutorTaskRan.set(true);
                                                        return 42;
                                                    }).get();
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                    operationFuture.set(future);
                } finally {
                    futureReady.countDown();
                }
            });
            binderThread.start();

            // Wait for the future to be returned (the operation was deferred
            // because the organizer lease is held).
            assertTrue("future ready within timeout",
                    futureReady.await(5, TimeUnit.SECONDS));
            assertNotNull("operation future is set", operationFuture.get());
            assertFalse("operation is deferred, not yet done",
                    operationFuture.get().isDone());

            // Step 3: Prove MODEL_EXECUTOR is not blocked during the
            // deferred window (before lease release).
            AtomicInteger modelHealth = new AtomicInteger(-1);
            Executors.MODEL_EXECUTOR.submit(() -> modelHealth.set(99)).get();
            assertEquals("MODEL_EXECUTOR is healthy during deferred window",
                    99, modelHealth.get());

            // Step 4: Release the lease from the test thread.
            // This runs the deferred callback inline on the test thread.
            lease.close();

            // Step 5: Assert the callback ran on the test thread (the
            // releasing thread), not on MODEL_EXECUTOR.
            assertNotNull("callback thread captured", callbackThread.get());
            assertEquals(
                    "deferred callback must run on releasing thread, "
                            + "not MODEL_EXECUTOR",
                    Thread.currentThread(), callbackThread.get());

            // Step 6: Assert the MODEL_EXECUTOR task ran.
            assertTrue("MODEL_EXECUTOR task ran inside supplier",
                    modelExecutorTaskRan.get());

            // Step 7: Assert the future completed with the correct value.
            assertTrue("operation future is done after lease release",
                    operationFuture.get().isDone());
            assertEquals(42, operationFuture.get().get().intValue());

            binderThread.join(5_000);
        } finally {
            // Idempotent close: LeaseImpl.close() checks the closed flag.
            lease.close();
        }
    }

    @Test
    public void modelExecutorNotBlockedByOrganizerLease() throws Exception {
        // AC-05: while an organizer lease is held, tokenless operation
        // futures are deferred but MODEL_EXECUTOR is never blocked.
        // A task submitted to MODEL_EXECUTOR during the deferred window
        // must execute immediately.
        LayoutWriteCoordinator.Lease lease =
                mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER);
        assertNotNull("organizer lease acquired", lease);

        try {
            // Defer a tokenless operation.
            CompletableFuture<Integer> future = mCoordinator
                    .runOrDeferWithOperationFuture(
                            LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                            /* token= */ 0L,
                            /* exactOrganizerToken= */ false,
                            () -> {
                                // This supplier would run MODEL_EXECUTOR
                                // work, but it's deferred.
                                return 1;
                            });
            assertFalse("operation is deferred", future.isDone());

            // MODEL_EXECUTOR is free to execute unrelated tasks.
            AtomicInteger health = new AtomicInteger(-1);
            Executors.MODEL_EXECUTOR.submit(() -> health.set(77)).get(
                    5, TimeUnit.SECONDS);
            assertEquals("MODEL_EXECUTOR runs during deferred window",
                    77, health.get());

            // Release the lease so the deferred operation runs.
            lease.close();

            assertEquals(1, future.get().intValue());
        } finally {
            lease.close();
        }
    }

    @Test
    public void looperExecutorInlineOptimizationPreventsSelfDeadlock() {
        // AC-05 (supplementary): LooperExecutor.execute() runs a task inline
        // when the current thread is the executor's looper thread.  This
        // means even if a deferred callback hypothetically ran on
        // MODEL_EXECUTOR, the inner submit().get() would not deadlock
        // because the task would execute inline.  This test documents the
        // optimization that makes the self-wait hazard impossible at the
        // LooperExecutor level.
        //
        // This is a documented property of LooperExecutor.execute() (line 43):
        //   if (getHandler().getLooper() == Looper.myLooper()) {
        //       runnable.run();
        //   } else {
        //       getHandler().post(runnable);
        //   }
        AtomicInteger result = new AtomicInteger(-1);
        try {
            int value = Executors.MODEL_EXECUTOR.submit(() -> {
                // We are now on MODEL_EXECUTOR's thread.
                // Submit another task to MODEL_EXECUTOR and wait for it.
                // Due to the inline optimization, this inner task runs
                // inline rather than being posted to the handler.
                return Executors.MODEL_EXECUTOR.submit(() -> {
                    result.set(42);
                    return 42;
                }).get();
            }).get();
            assertEquals(42, value);
            assertEquals(42, result.get());
        } catch (Exception e) {
            throw new RuntimeException("Inline optimization should prevent "
                    + "self-deadlock", e);
        }
    }
}