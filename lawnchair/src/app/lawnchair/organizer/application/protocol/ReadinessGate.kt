package app.lawnchair.organizer.application.protocol

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Issue #14 fixed-point review P0. Issue #271 review: [stateFlow] mirrors
 * every transition so a UI surface that read a fail-closed result during
 * reconciliation can re-read once the gate reaches a terminal state, without
 * depending on timing.
 */
class ReadinessGate {

    enum class State { IDLE, RECONCILING, READY, FAILED }

    private val stateRef: AtomicReference<State> = AtomicReference(State.IDLE)
    private val stateMirror = MutableStateFlow(State.IDLE)
    private val lock = ReentrantReadWriteLock()

    val state: State get() = stateRef.get()

    /** Observable mirror of [state]; emits on every transition, conflated. */
    val stateFlow: StateFlow<State> = stateMirror.asStateFlow()

    fun <T> reconcile(
        block: () -> T,
        succeeded: (T) -> Boolean,
        failed: (Throwable) -> T,
    ): T = lock.writeLocked {
        set(State.RECONCILING)
        try {
            block().also { result ->
                set(if (succeeded(result)) State.READY else State.FAILED)
            }
        } catch (error: Throwable) {
            set(State.FAILED)
            failed(error)
        }
    }

    fun <T> runWhenReady(unavailable: (State) -> T, block: () -> T): T = lock.readLocked {
        val current = stateRef.get()
        if (current == State.READY) block() else unavailable(current)
    }

    fun failBeforeReconciliation() = lock.writeLocked {
        set(State.FAILED)
    }

    /** Publish the reference and its observable mirror under the same lock. */
    private fun set(next: State) {
        stateRef.set(next)
        stateMirror.value = next
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
