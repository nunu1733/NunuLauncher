// Issue #14: Cross-flavor process-wide reentrant writer lease with owner kind/token and non-blocking organizer acquisition.
// Contains no reload registry. Spec §"Shared writer serialization"; plan step 5.
package com.android.launcher3.model;

import android.util.Log;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide reentrant writer lease that serializes every runtime Launcher
 * DB mutation and raw-file writer. Issue #14 Stage B step 5.
 *
 * <p>A reentrant lock records owning thread, recursion count, owner kind, and
 * monotonic logical lease token. Reentrancy requires both the owning thread
 * and the exact token; same-thread work without that capability cannot enter.
 *
 * <p>Organizer acquisition is non-blocking: if another lease is held,
 * {@link #tryAcquire} returns {@code null} so the caller can surface a typed
 * busy result. Baseline writers may use {@link #acquireBlocking} off the UI
 * thread.
 *
 * <p>Tokenless MODEL_EXECUTOR work is appended to a coordinator FIFO and
 * reposted on lease release; it never blocks the executor. The exact-token
 * holder (organizer reload) bypasses the queue.
 */
public final class LayoutWriteCoordinator {

    // Issue #14: process-wide singleton; initialized before organizer module accepts calls.
    private static volatile LayoutWriteCoordinator INSTANCE;

    @NonNull
    public static LayoutWriteCoordinator getInstance() {
        LayoutWriteCoordinator i = INSTANCE;
        if (i == null) {
            synchronized (LayoutWriteCoordinator.class) {
                i = INSTANCE;
                if (i == null) {
                    i = new LayoutWriteCoordinator();
                    INSTANCE = i;
                }
            }
        }
        return i;
    }

    private static final String TAG = "LayoutWriteCoordinator";

    public enum OwnerKind {
        ORGANIZER,
        MODEL_WRITER,
        GRID_MIGRATION,
        RESTORE,
        BACKUP_RESTORE,
    }

    public interface Lease extends AutoCloseable {
        @NonNull OwnerKind kind();
        long token();
        @Override void close();
    }

    private interface DeferredRunnable {
        void runWithOperationFuture();
    }

    private static final class Holder {
        final Thread thread;
        final OwnerKind kind;
        final long token;
        int recursionCount;

        Holder(Thread thread, OwnerKind kind, long token) {
            this.thread = thread;
            this.kind = kind;
            this.token = token;
            this.recursionCount = 1;
        }
    }

    private final Object lock = new Object();
    @GuardedBy("lock")
    private Holder current;
    private final AtomicLong nextToken = new AtomicLong(1L);
    private final ArrayDeque<DeferredRunnable> deferred = new ArrayDeque<>();
    // Issue #14: capability is scoped to the one correlated LoaderTask execution. It is
    // deliberately not a process-global bypass and is restored before the task returns.
    private final ThreadLocal<Long> activeOrganizerToken = new ThreadLocal<>();

    /**
     * Non-blocking acquisition. Returns a lease on success, {@code null} if any
     * other lease is currently held. Re-entry must use {@link #tryReenter}.
     */
    @Nullable
    public Lease tryAcquire(@NonNull OwnerKind kind) {
        long token = nextToken.getAndIncrement();
        synchronized (lock) {
            if (current == null) {
                current = new Holder(Thread.currentThread(), kind, token);
                return new LeaseImpl(kind, token);
            }
        }
        return null;
    }

    // Issue #58: RESTORE and BACKUP_RESTORE form one reentrancy family so a
    // thread holding any restore lease can enter RestoreDbTask.performRestore without
    // self-deadlocking. Exclusion against every other kind is unchanged.
    public static boolean isRestoreFamily(@NonNull OwnerKind kind) {
        return kind == OwnerKind.RESTORE
                || kind == OwnerKind.BACKUP_RESTORE;
    }

    /**
     * Reentrant acquisition for the restore family (Issue #58). Succeeds when the
     * current lease is any restore kind ({@code RESTORE} or {@code BACKUP_RESTORE})
     * held by the calling thread; the returned view
     * keeps the outer kind and token so only the outermost lease unlocks.
     */
    @Nullable
    public Lease tryReenterRestoreFamily() {
        synchronized (lock) {
            Holder h = current;
            if (h != null && h.thread == Thread.currentThread() && isRestoreFamily(h.kind)) {
                h.recursionCount += 1;
                return new LeaseImpl(h.kind, h.token);
            }
        }
        return null;
    }

    /**
     * Reentrant acquisition of the current lease by the owning thread with the
     * exact token. Used by the organizer protocol to re-enter its own lease
     * from the same thread during the A5/A6 transaction.
     */
    @Nullable
    public Lease tryReenter(@NonNull OwnerKind kind, long token) {
        synchronized (lock) {
            Holder h = current;
            if (h != null && h.thread == Thread.currentThread() && h.kind == kind
                    && h.token == token) {
                h.recursionCount += 1;
                return new LeaseImpl(kind, h.token);
            }
        }
        return null;
    }

    /**
     * Returns a lease view for the exact organizer capability currently installed on this
     * thread. The outer organizer lease remains the owner and is the only lease that unlocks.
     * This permits its correlated LoaderTask to perform cleanup on MODEL_EXECUTOR without
     * making token possession a general cross-thread reentrancy mechanism.
     */
    @Nullable
    public Lease tryAcquireOrganizerCapability(long token) {
        synchronized (lock) {
            Holder h = current;
            Long activeToken = activeOrganizerToken.get();
            if (h != null && h.kind == OwnerKind.ORGANIZER && h.token == token
                    && activeToken != null && activeToken == token) {
                return new CapabilityLease(token);
            }
        }
        return null;
    }

    /** Returns the capability installed for this thread, or zero when it has none. */
    long getActiveOrganizerToken() {
        Long token = activeOrganizerToken.get();
        return token == null ? 0L : token;
    }

    /**
     * Uses the normal same-thread reentrant lease for organizer writes, or the scoped
     * capability held by the exact correlated loader.
     */
    @Nullable
    public Lease tryAcquireOrganizerLease(long token) {
        Lease reentrant = tryReenter(OwnerKind.ORGANIZER, token);
        return reentrant != null ? reentrant : tryAcquireOrganizerCapability(token);
    }

    /**
     * Blocking acquisition for baseline writers. Must not be called on the UI
     * thread or from MODEL_EXECUTOR while an organizer lease is held.
     */
    @NonNull
    public Lease acquireBlocking(@NonNull OwnerKind kind) throws InterruptedException {
        long token = nextToken.getAndIncrement();
        synchronized (lock) {
            while (current != null) {
                lock.wait();
            }
            current = new Holder(Thread.currentThread(), kind, token);
            return new LeaseImpl(kind, token);
        }
    }

    // Issue #14: non-throwing variant for callers that cannot propagate InterruptedException.
    @NonNull
    public Lease acquireBlockingQuietly(@NonNull OwnerKind kind) {
        try {
            return acquireBlocking(kind);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring layout write lease", e);
        }
    }

    /**
     * Run [runnable] under the coordinator's MODEL_EXECUTOR gate. If an
     * organizer or restore-family lease is held and the runnable is tokenless,
     * it is appended to the FIFO and the executor returns immediately. Baseline
     * {@link OwnerKind#MODEL_WRITER} and {@link OwnerKind#GRID_MIGRATION}
     * leases do not defer tokenless work, preserving baseline executor
     * semantics. The exact-token holder bypasses the queue.
     */
    public void runOrDefer(
        @NonNull OwnerKind kind,
        long token,
        boolean exactOrganizerToken,
        @NonNull Runnable runnable
    ) {
        boolean installOrganizerCapability = false;
        synchronized (lock) {
            Holder h = current;
            if (h != null && !exactOrganizerToken && defersTokenlessWork(h)) {
                deferred.addLast(new DeferredRunnable() {
                    @Override
                    public void runWithOperationFuture() {
                        runnable.run();
                    }
                });
                Log.d(TAG, "Deferring tokenless runnable; queue size=" + deferred.size());
                return;
            }
            if (h != null && h.kind == OwnerKind.ORGANIZER && exactOrganizerToken && h.token == token) {
                installOrganizerCapability = true;
            }
        }
        if (installOrganizerCapability) {
            runWithOrganizerCapability(token, runnable);
        } else {
            runnable.run();
        }
    }

    private void runWithOrganizerCapability(long token, @NonNull Runnable runnable) {
        Long previous = activeOrganizerToken.get();
        activeOrganizerToken.set(token);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                activeOrganizerToken.remove();
            } else {
                activeOrganizerToken.set(previous);
            }
        }
    }

    // Issue #58 audit: only organizer and restore-family leases defer tokenless work.
    // GRID_MIGRATION and MODEL_WRITER leases must not change executor deferral semantics.
    private static boolean defersTokenlessWork(@NonNull Holder h) {
        return h.kind == OwnerKind.ORGANIZER || isRestoreFamily(h.kind);
    }

    /**
     * Variant of {@link #runOrDefer} for LauncherProvider's synchronous Binder
     * contract. The supplier runs immediately unless an organizer or
     * restore-family lease is held, in which case it runs after that lease's
     * release; the returned future completes with its result or the caught
     * exception. Binder threads may wait on this future; MODEL_EXECUTOR never
     * blocks.
     */
    @NonNull
    public <T> CompletableFuture<T> runOrDeferWithOperationFuture(
            @NonNull OwnerKind kind,
            long token,
            boolean exactOrganizerToken,
            @NonNull java.util.function.Supplier<T> supplier
    ) {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (lock) {
            Holder h = current;
            if (h != null && !exactOrganizerToken && defersTokenlessWork(h)) {
                deferred.addLast(() -> {
                    try {
                        future.complete(supplier.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                return future;
            }
        }
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    public int pendingDeferredCount() {
        synchronized (lock) {
            return deferred.size();
        }
    }

    private void release(@NonNull LeaseImpl lease) {
        ArrayDeque<DeferredRunnable> toRun = new ArrayDeque<>();
        synchronized (lock) {
            Holder h = current;
            if (h == null || h.thread != Thread.currentThread() || h.token != lease.token) {
                Log.wtf(TAG, "Release from non-owner; ignoring");
                return;
            }
            h.recursionCount -= 1;
            if (h.recursionCount > 0) {
                return;
            }
            current = null;
            // The queued callback owns its executor hand-off. Moving callbacks out of the
            // monitor avoids running arbitrary model/provider code while holding this lock.
            toRun.addAll(deferred);
            deferred.clear();
            lock.notifyAll();
        }
        // Issue #60: each deferred callback runs in isolation so a throwing entry
        // (e.g. a plain runOrDefer runnable) cannot prevent later entries from
        // receiving their exactly-once terminal signal. Futures from
        // runOrDeferWithOperationFuture are already completed exceptionally by
        // their own inner try/catch; the outer catch is a safety net for any
        // remaining path, including Errors.
        while (!toRun.isEmpty()) {
            DeferredRunnable r = toRun.removeFirst();
            try {
                r.runWithOperationFuture();
            } catch (Throwable t) {
                Log.e(TAG, "Deferred callback threw", t);
            }
        }
    }

    private final class LeaseImpl implements Lease {
        private final OwnerKind kind;
        private final long token;
        private volatile boolean closed;

        LeaseImpl(OwnerKind kind, long token) {
            this.kind = kind;
            this.token = token;
        }

        @Override
        public OwnerKind kind() {
            return kind;
        }

        @Override
        public long token() {
            return token;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            release(this);
        }
    }

    /** Non-owning close view used only inside {@link #runWithOrganizerCapability}. */
    private static final class CapabilityLease implements Lease {
        private final long token;

        CapabilityLease(long token) {
            this.token = token;
        }

        @NonNull
        @Override
        public OwnerKind kind() {
            return OwnerKind.ORGANIZER;
        }

        @Override
        public long token() {
            return token;
        }

        @Override
        public void close() {
            // The outer organizer holder controls unlock and deferred FIFO release.
        }
    }
}
