// Issue #14: Same-package internal adapter that carries an organizer reload token
// through one exact LoaderTask into its completion signal.
// Plan step 5; spec §"Correlated reload".
// Issue #152: the completion now carries the model snapshot captured by
// LauncherModel at the #150 terminal boundary; a completed request without a
// snapshot fails closed.
package com.android.launcher3;

import android.os.Handler;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.lawnchair.organizer.application.protocol.ModelSnapshot;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Internal Lawnchair adapter deliberately placed in the same Java package as
 * {@link LauncherModel}. Creates an organizer request token and calls the
 * package-private model method, which passes it through one exact loader task
 * to completion. A replaced/cancelled task fails the request; unrelated
 * reloads cannot complete it.
 */
public final class OrganizerModelReloadAdapter {

    private static final String TAG = "OrganizerReloadAdapter";
    private static final long TIMEOUT_MILLIS = 10_000L;

    public enum Outcome { COMPLETED, FAILED, SUPERSEDED, TIMEOUT }

    public interface Completion {
        void on(@NonNull Outcome outcome);
    }

    /**
     * Issue #152: terminal result of one correlated reload request. A
     * {@code COMPLETED} outcome always carries the snapshot captured from the
     * exact token-bound generation; every other outcome carries none, and no
     * stale, unrelated, cancelled, or superseded generation can be delivered
     * as completed for the request.
     */
    public static final class RequestResult {
        public final Outcome outcome;
        @Nullable
        public final ModelSnapshot snapshot;

        RequestResult(@NonNull Outcome outcome, @Nullable ModelSnapshot snapshot) {
            this.outcome = outcome;
            this.snapshot = snapshot;
        }
    }

    private final LauncherModel model;
    private final Handler mainHandler;
    private final AtomicLong nextRequestId = new AtomicLong(1L);

    public OrganizerModelReloadAdapter(@NonNull LauncherModel model, @NonNull Handler mainHandler) {
        this.model = model;
        this.mainHandler = mainHandler;
    }

    /**
     * Request a correlated reload and wait for the matching generation.
     * Legacy single-value view of {@link #requestAndWaitWithSnapshot(long)}.
     *
     * <p>Blocking wait occurs off MODEL_EXECUTOR.
     */
    @NonNull
    public Outcome requestAndWait(long organizerLeaseToken) {
        return requestAndWaitWithSnapshot(organizerLeaseToken).outcome;
    }

    /**
     * Request a correlated reload and wait for the matching generation. The
     * returned outcome is {@link Outcome#COMPLETED} only if the exact loader
     * task signalled completion with a capturable model snapshot; otherwise
     * {@link Outcome#FAILED}, {@link Outcome#SUPERSEDED}, or
     * {@link Outcome#TIMEOUT}.
     *
     * <p>Blocking wait occurs off MODEL_EXECUTOR.
     */
    @NonNull
    public RequestResult requestAndWaitWithSnapshot(long organizerLeaseToken) {
        long requestId = nextRequestId.getAndIncrement();
        Object lock = new Object();
        boolean[] completed = new boolean[1];
        RequestResult[] result = new RequestResult[1];

        BiConsumer<Outcome, ModelSnapshot> signal = (outcome, snapshot) -> {
            synchronized (lock) {
                if (!completed[0]) {
                    completed[0] = true;
                    result[0] = new RequestResult(outcome, snapshot);
                    lock.notifyAll();
                }
            }
        };

        try {
            model.forceReloadForOrganizer(
                    requestId,
                    organizerLeaseToken,
                    (snapshot) -> signal.accept(
                            snapshot != null ? Outcome.COMPLETED : Outcome.FAILED, snapshot),
                    () -> signal.accept(Outcome.SUPERSEDED, null));
        } catch (Throwable t) {
            Log.e(TAG, "forceReloadForOrganizer failed", t);
            return new RequestResult(Outcome.FAILED, null);
        }

        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        synchronized (lock) {
            while (!completed[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return new RequestResult(Outcome.TIMEOUT, null);
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new RequestResult(Outcome.FAILED, null);
                }
            }
            RequestResult delivered = result[0];
            return delivered != null ? delivered : new RequestResult(Outcome.FAILED, null);
        }
    }

}
