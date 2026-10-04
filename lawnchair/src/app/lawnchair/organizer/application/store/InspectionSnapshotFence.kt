package app.lawnchair.organizer.application.store

/**
 * In-process health fence for the derived inspection snapshot.
 *
 * This is deliberately memory-only. A new process starts [State.UNKNOWN] even
 * when a checksum-valid final file remains on disk, so restart reconciliation
 * must validate the authoritative store before inspection can succeed.
 */
internal class InspectionSnapshotFence {
    sealed interface State {
        data object UNKNOWN : State
        data class DIRTY(val generation: Long) : State
        data class VALID(val generation: Long) : State
        data object INCOMPATIBLE : State
    }

    enum class MutationOutcome { PROVEN_NO_COMMIT, COMMITTED, OUTCOME_UNCERTAIN }

    data class Mutation internal constructor(
        internal val token: Long,
        internal val candidateGeneration: Long,
        internal val previousValidGeneration: Long?,
        internal val reconciliation: Boolean,
    )

    private var nextToken = 0L
    private var nextGeneration = 0L
    private var active: Mutation? = null
    private var state: State = State.UNKNOWN

    @Synchronized
    fun state(): State = state

    @Synchronized
    fun beginOrdinaryMutation(): Mutation? {
        val previous = (state as? State.VALID)?.generation ?: return null
        return begin(previous, reconciliation = false)
    }

    @Synchronized
    fun beginReconciliationMutation(): Mutation {
        val previous = (state as? State.VALID)?.generation
        return begin(previous, reconciliation = true)
    }

    @Synchronized
    fun finish(mutation: Mutation, outcome: MutationOutcome): Boolean {
        if (active?.token != mutation.token) return false
        active = null
        when (outcome) {
            MutationOutcome.PROVEN_NO_COMMIT -> {
                state = if (!mutation.reconciliation && mutation.previousValidGeneration != null) {
                    State.VALID(mutation.previousValidGeneration)
                } else {
                    // Reconciliation begins UNKNOWN/DIRTY and may not invent
                    // success merely because no mutation committed.
                    State.UNKNOWN
                }
            }

            MutationOutcome.COMMITTED,
            MutationOutcome.OUTCOME_UNCERTAIN,
            -> state = State.DIRTY(mutation.candidateGeneration)
        }
        return true
    }

    @Synchronized
    fun markValid(mutation: Mutation, envelopeGeneration: Long): Boolean {
        if (active?.token != mutation.token || envelopeGeneration != mutation.candidateGeneration) return false
        active = null
        state = State.VALID(envelopeGeneration)
        return true
    }

    @Synchronized
    fun markIncompatible() {
        active = null
        state = State.INCOMPATIBLE
    }

    @Synchronized
    fun markUnknown() {
        active = null
        state = State.UNKNOWN
    }

    private fun begin(previousValidGeneration: Long?, reconciliation: Boolean): Mutation {
        check(active == null) { "nested snapshot fence mutation" }
        val mutation = Mutation(
            token = ++nextToken,
            candidateGeneration = ++nextGeneration,
            previousValidGeneration = previousValidGeneration,
            reconciliation = reconciliation,
        )
        active = mutation
        state = State.DIRTY(mutation.candidateGeneration)
        return mutation
    }
}
