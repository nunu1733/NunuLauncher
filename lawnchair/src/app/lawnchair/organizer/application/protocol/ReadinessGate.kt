package app.lawnchair.organizer.application.protocol

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Startup/readiness gate owned by [LayoutApplicationModule]. Ensures that
 * `apply`/`recover` cannot proceed until restart reconciliation has completed
 * successfully for the current store generation.
 *
 * States: [IDLE] → [RECONCILING] → [READY] or [FAILED].
 *
 * - [READY]: apply/recover proceed normally.
 * - [FAILED]: apply/recover report the existing recovery-store failure result.
 * - [IDLE] / [RECONCILING]: apply/recover report existing writer contention.
 *
 * Thread-safe via [AtomicReference]. Reconciliation runs once per store
 * generation; a new [LayoutApplicationModule] instance gets a fresh gate.
 *
 * Spec §"Restart reconciliation": "Before accepting a new apply/recover
 * operation, the module reconciles every unresolved APPLYING,
 * COMMITTED_UNVERIFIED, or RESTORING record."
 *
 * Issue #14 fixed-point review P0.
 */
class ReadinessGate {

    enum class State { IDLE, RECONCILING, READY, FAILED }

    private val stateRef: AtomicReference<State> = AtomicReference(State.IDLE)
    private val lock = ReentrantReadWriteLock()

    val state: State get() = stateRef.get()

    fun <T> reconcile(
        block: () -> T,
        succeeded: (T) -> Boolean,
        failed: (Throwable) -> T,
    ): T = lock.writeLocked {
        stateRef.set(State.RECONCILING)
        try {
            block().also { result ->
                stateRef.set(if (succeeded(result)) State.READY else State.FAILED)
            }
        } catch (error: Throwable) {
            stateRef.set(State.FAILED)
            failed(error)
        }
    }

    fun <T> runWhenReady(unavailable: (State) -> T, block: () -> T): T = lock.readLocked {
        val current = stateRef.get()
        if (current == State.READY) block() else unavailable(current)
    }

    fun failBeforeReconciliation() = lock.writeLocked {
        stateRef.set(State.FAILED)
    }

    private inline fun <T> ReentrantReadWriteLock.writeLocked(block: () -> T): T {
        writeLock().lock()
        return try {
            block()
        } finally {
            writeLock().unlock()
        }
    }

    private inline fun <T> ReentrantReadWriteLock.readLocked(block: () -> T): T {
        readLock().lock()
        return try {
            block()
        } finally {
            readLock().unlock()
        }
    }
}
