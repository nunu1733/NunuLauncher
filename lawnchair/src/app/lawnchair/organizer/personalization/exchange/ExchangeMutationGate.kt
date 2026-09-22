package app.lawnchair.organizer.personalization.exchange

/**
 * Issue #375 (spec "exchange mutation gate"): the process-wide serialization
 * point shared by every operation that can change the active export session
 * or the durable pending record, and by the rebind admission's fresh
 * re-verification. Blocking (thread-affine) on purpose: the rebind admission
 * anchor acquires it from inside the run's synchronous lock section, where a
 * suspending coroutine mutex cannot be used — and suspending
 * while holding it is forbidden by the spec ("gate保持中にsuspension pointを
 * 作らない").
 *
 * Holders of the gate (all short):
 * - session replacement (the controller's new-session save + old-record
 *   invalidation) and pre-send invalidation,
 * - the durable save of an imported proposal (the #374 attempt-fenced write,
 *   refactored by #375 to settle UI only AFTER release),
 * - the user-discard tombstone, the reconcile cleanups (resume face and
 *   startup), and
 * - the rebind admission anchor's fresh-read → verdict → operation-creation
 *   section.
 *
 * UI work never happens while the gate is held: no `withContext(main)`
 * waits, no readiness gates, no model loads, no detection. Sections are a
 * handful of small `AtomicFile` reads/writes plus the admission verdict, so
 * UI-side cancel/confirm on other faces blocks only briefly.
 */
class ExchangeMutationGate {

    private val lock = Object()

    /** Number of threads currently inside [withGate] (diagnostics/tests). */
    @Volatile
    var heldCount: Int = 0
        private set

    fun <T> withGate(block: () -> T): T = synchronized(lock) {
        heldCount++
        try {
            block()
        } finally {
            heldCount--
        }
    }
}

/**
 * Issue #375: runs [block] inside [gate]; a null gate (legacy fixtures) runs
 * the block un-gated. Top-level so callers with an optional gate share one
 * short, obvious form.
 */
fun <T> ExchangeMutationGate?.withGateOrNull(block: () -> T): T = this?.withGate(block) ?: block()
