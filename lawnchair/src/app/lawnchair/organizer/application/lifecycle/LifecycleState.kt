package app.lawnchair.organizer.application.lifecycle

/**
 * Closed lifecycle state set for a recovery record.
 *
 * Spec §“Recovery record and lifecycle”. Each state has its own durable
 * transition; nothing else may be persisted as the lifecycle column.
 *
 * Issue #14 Stage B step 2.
 */
enum class LifecycleState {
    CREATING,
    READY,
    APPLYING,
    COMMITTED_UNVERIFIED,
    VERIFIED,
    RESTORING,
    RESTORED,
    EXPIRED,
    CORRUPT,
    INCOMPATIBLE,
    ;

    /** Stable canonical integer persisted in the recovery DB. */
    val canonicalInt: Int get() = ordinal

    companion object {
        fun fromCanonicalInt(value: Int): LifecycleState = entries.getOrElse(value) {
            error("Unknown LifecycleState canonical int: $value")
        }
    }
}

/**
 * Pure state-machine legality check. Spec §“Recovery record and lifecycle”
 * fixes the transitions; this table is the single source of truth.
 *
 * Any transition not present here is illegal and must fail the recovery-store
 * write or restart reconciliation.
 */
object LifecycleTransitions {

    private val legal: Map<LifecycleState, Set<LifecycleState>> = mapOf(
        LifecycleState.CREATING to setOf(LifecycleState.READY, LifecycleState.CORRUPT),
        LifecycleState.READY to setOf(
            LifecycleState.APPLYING,
            LifecycleState.EXPIRED,
            LifecycleState.CORRUPT,
        ),
        LifecycleState.APPLYING to setOf(
            LifecycleState.COMMITTED_UNVERIFIED,
            LifecycleState.RESTORING,
            LifecycleState.READY,
            LifecycleState.CORRUPT,
        ),
        LifecycleState.COMMITTED_UNVERIFIED to setOf(
            LifecycleState.VERIFIED,
            LifecycleState.RESTORING,
            LifecycleState.READY,
            LifecycleState.CORRUPT,
        ),
        LifecycleState.VERIFIED to setOf(
            LifecycleState.EXPIRED,
            LifecycleState.RESTORING,
            LifecycleState.CORRUPT,
        ),
        LifecycleState.RESTORING to setOf(
            LifecycleState.RESTORED,
            LifecycleState.CORRUPT,
        ),
        LifecycleState.RESTORED to emptySet(),
        LifecycleState.EXPIRED to emptySet(),
        LifecycleState.CORRUPT to emptySet(),
        LifecycleState.INCOMPATIBLE to emptySet(),
    )

    /** Whether the transition [from] -> [to] is permitted by the spec table. */
    fun isLegal(from: LifecycleState, to: LifecycleState): Boolean = legal[from]?.contains(to) == true

    /** Throw if illegal; return normally otherwise. */
    fun requireLegal(from: LifecycleState, to: LifecycleState) {
        check(isLegal(from, to)) {
            "Illegal lifecycle transition: $from -> $to"
        }
    }

    /**
     * True if the state is a final/non-active state: reconciliation must never
     * attempt to advance a record in one of these states.
     */
    fun isFinal(state: LifecycleState): Boolean = state in FINAL_STATES

    /**
     * True if the state is "active" (eligible for ongoing work): a record in
     * such a state must never be expired by age/count and must be reconciled
     * before cleanup.
     */
    fun isActive(state: LifecycleState): Boolean = state in ACTIVE_STATES

    private val FINAL_STATES: Set<LifecycleState> = setOf(
        LifecycleState.RESTORED,
        LifecycleState.EXPIRED,
        LifecycleState.CORRUPT,
        LifecycleState.INCOMPATIBLE,
    )

    private val ACTIVE_STATES: Set<LifecycleState> = setOf(
        LifecycleState.APPLYING,
        LifecycleState.COMMITTED_UNVERIFIED,
        LifecycleState.RESTORING,
    )

    /** True if a record in this state is restorable (eligible for `recover`). */
    fun isRestorable(state: LifecycleState): Boolean = state in RESTORABLE_STATES

    private val RESTORABLE_STATES: Set<LifecycleState> = setOf(
        LifecycleState.VERIFIED,
    )
}
