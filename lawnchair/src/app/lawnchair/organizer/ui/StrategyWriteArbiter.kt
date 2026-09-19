package app.lawnchair.organizer.ui

import androidx.compose.runtime.mutableStateOf
import app.lawnchair.organizer.planning.StrategyId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #368 (spec 368): the single strategy-write arbiter, hosted by the
 * materials surface T-05 (the run surface no longer offers strategy
 * selection). The arbiter serializes writes (write-vs-write single flight)
 * and joins the process-local admission domain: every write holds an
 * `OrganizationOperationLease.Kind.AUTHORING` token from its start until the
 * terminal path, so runs, recovery, and other authoring mutually exclude with
 * it exactly like the category authoring surfaces.
 *
 * States and transitions (every transition is executed on [mainDispatcher],
 * the Main-confined serialization point):
 *
 * ```text
 * Idle -> Writing -> Idle
 * ```
 *
 * A selection attempt reports its outcome synchronously as a [StartOutcome];
 * every refusal is a typed non-write (no store call, no `Writing` entry).
 * The asynchronous commit result is reported separately through
 * [onStrategySelected]'s `onCommitted` callback.
 *
 * All reads of [state]/[busy] and the calls that start a transition
 * ([onStrategySelected]) happen on the Main thread in production.
 */
internal class StrategyWriteArbiter(
    private val scope: CoroutineScope,
    /** Where the strategy write executes (heavy IO). */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Main-confined serialization point for every state transition. */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * The strategy write seam. Returns true when the write committed; any
     * other result (and a thrown failure) leaves the selection unchanged.
     */
    private val writeStrategy: suspend (StrategyId) -> Boolean,
    /**
     * The admission domain shared with runs, recovery, and authoring. Holds
     * the AUTHORING token for the whole write (spec #368). Tests inject
     * [NoopOrganizationOperationGate] or a controllable fake.
     */
    private val operationGate: OrganizationOperationGate = OrganizationOperationLease,
    /**
     * Whether a run or recovery operation is currently alive (the run
     * coordinator's operation-lifetime projection). Only consulted to
     * classify a failed token acquisition — the lease acquisition itself
     * stays the structural gate.
     */
    private val runOrRecoveryActive: () -> Boolean,
) {
    enum class State {
        IDLE,
        WRITING,
    }

    /**
     * Synchronous result of a selection attempt (spec #368). Every refusal
     * below leaves the selection store unchanged and never enters
     * [State.WRITING], so the caller can surface a typed retry notice
     * instead of dropping the tap silently.
     */
    enum class StartOutcome {
        /** The write started; the commit result arrives via `onCommitted`. */
        Started,

        /** A run or recovery operation holds the admission domain. */
        RefusedRunOrRecoveryActive,

        /** Another authoring operation (e.g. category overrides) holds it. */
        RefusedAuthoringBusy,

        /** This arbiter already has a write in flight (single flight). */
        RefusedWriteBusy,
    }

    /**
     * Current arbiter state, snapshot-backed so the picker's enabled state
     * recomposes with it. Only ever mutated on [mainDispatcher]; read from
     * the Main thread (composition / the T-05 host).
     */
    private val stateState = mutableStateOf(State.IDLE)
    var state: State
        get() = stateState.value
        private set(value) {
            stateState.value = value
        }

    /** True while the arbiter's write is in progress. */
    val busy: Boolean get() = state != State.IDLE

    /**
     * A strategy row selection. Single-flight: while non-idle the selection
     * is refused outright — no store call, no queueing. The AUTHORING token
     * is acquired on the Main-confined point together with the `Writing`
     * entry and released on every terminal path (commit, non-commit,
     * failure, cancellation) inside the `finally`-equivalent block, so a
     * run can never start between the gate and the publication.
     *
     * The write coroutine starts [CoroutineStart.UNDISPATCHED]: the body
     * enters its `try` block inline on the calling (Main) thread before any
     * suspension, so even a scope cancelled in the same turn as the
     * selection — e.g. the host leaving composition — runs the release
     * path. A plain `launch` could leave the token acquired and the state
     * stuck at `Writing` forever if the coroutine was cancelled before its
     * body ever ran (spec #368: the token never outlives the write).
     *
     * [onCommitted] runs on Main after the write committed and reports the
     * persisted selection so the UI state can follow it.
     */
    fun onStrategySelected(id: StrategyId, onCommitted: (StrategyId) -> Unit = {}): StartOutcome {
        if (state != State.IDLE) return StartOutcome.RefusedWriteBusy
        val token = operationGate.tryAcquire(OrganizationOperationLease.Kind.AUTHORING)
            ?: return if (runOrRecoveryActive()) {
                StartOutcome.RefusedRunOrRecoveryActive
            } else {
                StartOutcome.RefusedAuthoringBusy
            }
        state = State.WRITING
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val committed = withContext(ioDispatcher) { writeStrategy(id) }
                withContext(mainDispatcher) {
                    if (committed) onCommitted(id)
                }
            } finally {
                // Every terminal path releases the arbiter and the admission
                // domain — commit, non-commit, failure and cancellation alike
                // (spec #368: the token never outlives the write).
                withContext(NonCancellable + mainDispatcher) {
                    token.close()
                    state = State.IDLE
                }
            }
        }
        return StartOutcome.Started
    }
}
