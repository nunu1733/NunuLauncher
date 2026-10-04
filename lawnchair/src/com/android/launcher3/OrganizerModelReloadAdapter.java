// Issue #14: Same-package internal adapter that carries an organizer reload token
// through one exact LoaderTask into its bind-complete signal.
// Plan step 5; spec §"Correlated reload".
package com.android.launcher3;

import android.os.Handler;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Internal Lawnchair adapter deliberately placed in the same Java package as
 * {@link LauncherModel}. Creates an organizer request token and calls the
 * package-private model method, which passes it through one exact loader task
 * to bind completion. A replaced/cancelled task fails the request; unrelated
 * reloads cannot complete it.
 *
 * <p>Issue #14 Stage B step 5.
 */
public final class OrganizerModelReloadAdapter {

    private static final String TAG = "OrganizerReloadAdapter";
    private static final long TIMEOUT_MILLIS = 10_000L;

    public enum Outcome { COMPLETED, FAILED, SUPERSEDED, TIMEOUT }

    public interface Completion {
        void on(@NonNull Outcome outcome);
    }

    private final LauncherModel model;
    private final Handler mainHandler;
    private final AtomicLong nextRequestId = new AtomicLong(1L);

    public OrganizerModelReloadAdapter(@NonNull LauncherModel model, @NonNull Handler mainHandler) {
        this.model = model;
        this.mainHandler = mainHandler;
    }

    /**
     * Request a correlated reload and wait for the matching generation. The
     * returned outcome is {@link Outcome#COMPLETED} only if the exact loader
     * task signalled completion; otherwise {@link Outcome#FAILED},
     * {@link Outcome#SUPERSEDED}, or {@link Outcome#TIMEOUT}.
     *
     * <p>Blocking wait occurs off MODEL_EXECUTOR.
     */
    @NonNull
    public Outcome requestAndWait(long organizerLeaseToken) {
        long requestId = nextRequestId.getAndIncrement();
        Object lock = new Object();
        boolean[] completed = new boolean[1];
        Outcome[] outcome = new Outcome[1];

        Consumer<Outcome> signal = (result) -> {
            synchronized (lock) {
                if (!completed[0]) {
                    completed[0] = true;
                    outcome[0] = result;
                    lock.notifyAll();
                }
            }
        };

        try {
            model.forceReloadForOrganizer(
                    requestId,
                    organizerLeaseToken,
                    () -> signal.accept(Outcome.COMPLETED),
                    () -> signal.accept(Outcome.SUPERSEDED));
        } catch (Throwable t) {
            Log.e(TAG, "forceReloadForOrganizer failed", t);
            return Outcome.FAILED;
        }

        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        synchronized (lock) {
            while (!completed[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return Outcome.TIMEOUT;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Outcome.FAILED;
                }
            }
            return outcome[0] != null ? outcome[0] : Outcome.FAILED;
        }
    }

}
