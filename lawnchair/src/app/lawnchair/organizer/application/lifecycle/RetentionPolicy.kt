package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.public.RecoveryPointId

/**
 * Pure retention logic for the recovery store. Spec §“Retention”.
 *
 *  - 24 h (`86_400_000 ms`) from creation.
 *  - At most three capacity-bearing points; final non-restorable rows are
 *    retained as tombstones and do not consume capacity.
 *  - Never expire `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING`.
 *  - Tombstones (CORRUPT, INCOMPATIBLE, RESTORED, EXPIRED) remain 24 h.
 *  - `RECOVERY_POINT_ADMISSION_BLOCKED` when three unresolved points block
 *    checkpoint admission.
 *
 * No I/O; the recovery store calls into this with the current clock and the
 * records it observes, then performs the returned actions in its own
 * transaction.
 *
 * Issue #14 Stage B step 2.
 */
object RetentionPolicy {

    const val RETENTION_MILLIS: Long = 86_400_000L
    const val MAX_NON_EXPIRED_POINTS: Int = 3
    const val TOMBSTONE_RETENTION_MILLIS: Long = 86_400_000L

    /** Record identity and lifecycle metadata as the recovery store observes it. */
    data class RetentionRecord(
        val pointId: RecoveryPointId,
        val lifecycle: LifecycleState,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
    )

    /** What the recovery store must do with this record at [nowMillis]. */
    sealed interface RetentionAction {
        data object Keep : RetentionAction
        data class Expire(val reason: ExpireReason) : RetentionAction
        data class Tombstone(val reason: ExpireReason) : RetentionAction
    }

    enum class ExpireReason { AGE_RETENTION, COUNT_RETENTION, PRUNE_UNUSED_READY }

    /**
     * Compute the retention action for a single record given the current
     * clock. Pure; does not consult other records.
     */
    fun actionFor(record: RetentionRecord, nowMillis: Long): RetentionAction {
        if (LifecycleTransitions.isActive(record.lifecycle)) {
            return RetentionAction.Keep
        }
        if (record.lifecycle == LifecycleState.READY) {
            // READY is always pruned during reconciliation; age is not relevant.
            return RetentionAction.Tombstone(ExpireReason.PRUNE_UNUSED_READY)
        }
        val isFinal = LifecycleTransitions.isFinal(record.lifecycle)
        val ageOrigin = if (record.lifecycle == LifecycleState.VERIFIED) {
            record.createdAtMillis
        } else {
            record.updatedAtMillis
        }
        val ageCutoff = ageOrigin + if (isFinal) TOMBSTONE_RETENTION_MILLIS else RETENTION_MILLIS
        return if (nowMillis >= ageCutoff) {
            if (isFinal) {
                RetentionAction.Tombstone(ExpireReason.AGE_RETENTION)
            } else {
                RetentionAction.Expire(ExpireReason.AGE_RETENTION)
            }
        } else {
            RetentionAction.Keep
        }
    }

    /**
     * Decide whether creating a new point is allowed given the existing
     * record set. Returns the records to evict, or
     * [CreateDecision.AdmissionBlocked] when three unresolved records block
     * cleanup.
     *
     * Spec: expiry/count cleanup and creation of the replacement point are one
     * recovery DB transaction, so a failed creation does not discard the prior
     * usable set.
     */
    fun planCreate(
        existing: List<RetentionRecord>,
        nowMillis: Long,
    ): CreateDecision {
        val resolved = mutableListOf<RetentionRecord>()
        val active = mutableListOf<RetentionRecord>()
        val evictable = mutableListOf<RetentionRecord>()
        for (record in existing) {
            val action = actionFor(record, nowMillis)
            when {
                LifecycleTransitions.isActive(record.lifecycle) -> active += record
                action is RetentionAction.Expire || action is RetentionAction.Tombstone -> evictable += record
                else -> resolved += record
            }
        }
        // Evict aged/count-evictable records first; only evict by count if still needed.
        val toEvict = evictable.toMutableList()
        var retainedUsable = active.size + resolved.size
        // Reserve one slot for the point being created. When capacity is
        // needed, collapse every resolved final non-restorable row so its
        // tombstone can stop consuming capacity; then evict resolved VERIFIED
        // points oldest first if another slot is still needed. Active records
        // are never eligible even when they alone consume the entire
        // capacity. Final non-restorable rows remain physical records until
        // capacity is needed, so three RESTORED rows exercise the admission
        // transaction rather than being removed while the set is built.
        val finalNonRestorable = resolved.filter {
            LifecycleTransitions.isFinal(it.lifecycle) &&
                !LifecycleTransitions.isRestorable(it.lifecycle)
        }
        if (retainedUsable >= MAX_NON_EXPIRED_POINTS) {
            toEvict += finalNonRestorable
            retainedUsable -= finalNonRestorable.size
        }
        val verifiedByAge = resolved
            .filter {
                it.lifecycle == LifecycleState.VERIFIED
            }
            .sortedWith(
                compareBy<RetentionRecord> { it.createdAtMillis }.thenBy { it.pointId.value },
            )
            .iterator()
        while (retainedUsable >= MAX_NON_EXPIRED_POINTS && verifiedByAge.hasNext()) {
            toEvict += verifiedByAge.next()
            retainedUsable -= 1
        }
        return if (retainedUsable >= MAX_NON_EXPIRED_POINTS) {
            CreateDecision.AdmissionBlocked
        } else {
            CreateDecision.Allowed(toEvict = toEvict.toList())
        }
    }

    sealed interface CreateDecision {
        data class Allowed(val toEvict: List<RetentionRecord>) : CreateDecision
        data object AdmissionBlocked : CreateDecision
    }
}
