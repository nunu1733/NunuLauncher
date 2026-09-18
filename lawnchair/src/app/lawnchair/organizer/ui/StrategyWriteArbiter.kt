package app.lawnchair.organizer.ui

import androidx.compose.runtime.mutableStateOf
import app.lawnchair.organizer.planning.StrategyId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #328 (spec 328 "strategy書込との相互排他"): the single strategy-write
 * arbiter state machine shared by the exchange flow (import/CTA gates) and
 * the hosting settings screen (strategy picker gates).
 *
 * States and transitions (every transition is executed on [mainDispatcher],
 * the Main-confined serialization point):
 *
 * ```text
 * Idle -> Writing -> RestartReserved -> Restarting -> Idle
 *              \-> Idle   (non-committed write, or committed with no restart)
 * ```
 *
 * While non-idle the arbiter refuses new strategy writes (write-vs-write
 * single flight), new imports and CTA starts — see [busy]. The write itself
 * ([writeStrategy]) and the run restart ([restartRun]) are injected seams so
 * tests can block either step deterministically; production wires them to the
 * validated strategy-selection command and the coordinator.
 *
 * All reads of [state]/[busy] and the calls that start a transition
 * ([onStrategySelected]) happen on the Main thread in production.
 */
class StrategyWriteArbiter(
    private val scope: CoroutineScope,
    /** Where the strategy write and the run restart execute (heavy IO). */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Main-confined serialization point for every state transition. */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * The strategy write seam. Returns true when the write committed; any
     * other result (and a thrown failure) leaves the selection unchanged.
     */
    private val writeStrategy: suspend (StrategyId) -> Boolean,
    /**
     * The run restart: dismiss + fresh start (synchronously heavy — this
     * arbiter always invokes it on [ioDispatcher], never on Main).
     */
    private val restartRun: () -> Unit,
    /**
     * Entry-specific write-start gate: true refuses the write before any
     * store call (run-in entry: while the import attempt is active; idle
     * entry: while the import continuation is active). UI disabled states are
     * affordances only — this gate is the structural one.
     */
    private val writeStartBlocked: () -> Boolean,
    /**
     * Commit-time restart suppression: true keeps the committed selection
     * (it applies to later runs) but must not dismiss/restart the current run
     * (import continuation active, or a run-in import attempt is active).
     */
    private val restartSuppressed: () -> Boolean,
    /** True while a run is active and a restart is meaningful at all. */
    private val restartNeeded: () -> Boolean,
) {
    enum class State {
        IDLE,
        WRITING,
        RESTART_RESERVED,
        RESTARTING,
    }

    /**
     * Current arbiter state, snapshot-backed so the picker's enabled state
     * recomposes with it. Only ever mutated on [mainDispatcher]; read from
     * the Main thread (composition / the holder's Main-confined gates).
     */
    private val stateState = mutableStateOf(State.IDLE)
    var state: State
        get() = stateState.value
        private set(value) {
            stateState.value = value
            onStateObserved?.invoke(value)
        }

    /**
     * Test-only seam (issue #328 review): observes every state transition so
     * the tests can assert the RESERVED/RESTARTING windows and the busy
     * predicate per state. Production leaves it null.
     */
    internal var onStateObserved: ((State) -> Unit)? = null

    /** True while any arbiter work (write or restart) is in progress. */
    val busy: Boolean get() = state != State.IDLE

    /**
     * A strategy row selection. Single-flight: while non-idle (write in
     * progress, restart reserved or running) the selection is refused
     * outright — no store call, no queueing (spec 328 arbiter policy: the
     * arbiter has exactly one writer).
     *
     * [onCommitted] runs on Main after the write committed and reports the
     * persisted selection so the UI state can follow it.
     */
    fun onStrategySelected(id: StrategyId, onCommitted: (StrategyId) -> Unit = {}) {
        if (state != State.IDLE) return
        if (writeStartBlocked()) return
        state = State.WRITING
        scope.launch {
            try {
                val committed = withContext(ioDispatcher) { writeStrategy(id) }

                val reserved = withContext(mainDispatcher) {
                    if (committed) onCommitted(id)
                    if (!committed || restartSuppressed() || !restartNeeded()) {
                        false
                    } else {
                        state = State.RESTART_RESERVED
                        true
                    }
                }

                if (reserved) {
                    withContext(mainDispatcher) { state = State.RESTARTING }
                    // Synchronously heavy (capture/composition/planning): never
                    // on Main (audit P2-1).
                    withContext(ioDispatcher) { restartRun() }
                }
            } finally {
                // Every terminal path releases the arbiter — restart success,
                // non-commit, Committed-no-restart, restart failure and
                // cancellation alike (spec 328: import/CTA refusal must not
                // outlive the arbiter work).
                withContext(NonCancellable + mainDispatcher) { state = State.IDLE }
            }
        }
    }
}
