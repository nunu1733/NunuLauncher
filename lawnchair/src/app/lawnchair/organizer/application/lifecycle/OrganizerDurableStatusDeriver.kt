package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.OrganizerDurableStatus

/**
 * Pure derivation of the closed [OrganizerDurableStatus] from the recovery
 * store's bounded record/tombstone metadata (Issue #271). No I/O, no Android
 * types; the protocol layer maps the inspection snapshot into the minimal
 * input types and supplies the clock.
 *
 * Retention windows come from [RetentionPolicy] (spec 13 §“Retention”):
 * a `VERIFIED` record is restorable until `createdAt + RETENTION_MILLIS`, and
 * a tombstone is retained until its `expiresAtMs`.
 */
object OrganizerDurableStatusDeriver {

    /** Minimal record metadata the derivation may observe. */
    data class DurableRecord(
        val lifecycle: LifecycleState,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val checksumValid: Boolean,
    )

    /** Minimal tombstone metadata the derivation may observe. */
    data class DurableTombstone(
        val reason: RecoveryStorePort.TombstoneReason,
        val expiresAtMs: Long,
    )

    private val UNRESOLVED_NON_FINAL_LIFECYCLES: Set<LifecycleState> = setOf(
        LifecycleState.CREATING,
        LifecycleState.READY,
        LifecycleState.APPLYING,
        LifecycleState.COMMITTED_UNVERIFIED,
        LifecycleState.RESTORING,
    )

    fun derive(
        records: List<DurableRecord>,
        tombstones: List<DurableTombstone>,
        nowMs: Long,
    ): OrganizerDurableStatus {
        // 1–3: anything presenting an unresolved recovery outranks restorable.
        // Non-final rows normally cannot coexist with a completed startup
        // reconciliation (the readiness gate fail-closes instead); the mapping
        // keeps the closed vocabulary total.
        if (records.any { it.lifecycle in UNRESOLVED_NON_FINAL_LIFECYCLES }) {
            return OrganizerDurableStatus.UNRESOLVED
        }
        // Final CORRUPT/INCOMPATIBLE rows are skipped by reconciliation
        // candidates, so they persist after a completed reconciliation.
        if (records.any {
                it.lifecycle == LifecycleState.CORRUPT || it.lifecycle == LifecycleState.INCOMPATIBLE
            }
        ) {
            return OrganizerDurableStatus.UNRESOLVED
        }
        // RestartReconciler reconciles an unreadable row silently without
        // advancing it, so a VERIFIED row can stay VERIFIED with an invalid
        // payload checksum after a completed reconciliation.
        if (records.any { it.lifecycle == LifecycleState.VERIFIED && !it.checksumValid }) {
            return OrganizerDurableStatus.UNRESOLVED
        }
        val verified = records.filter { it.lifecycle == LifecycleState.VERIFIED }
        // 4: restorable until createdAt + retention (lazy eviction means a
        // lapsed row can still be present; it must never read as restorable).
        if (verified.any { nowMs < it.createdAtMs + RetentionPolicy.RETENTION_MILLIS }) {
            return OrganizerDurableStatus.ORGANIZED_RESTORABLE
        }
        // 5: lapsed VERIFIED row (pre-eviction).
        // 6: final RESTORED/EXPIRED row — the durable state for up to 24h
        // after a restore/retention lapse, until eviction writes the tombstone.
        if (verified.isNotEmpty() ||
            records.any {
                it.lifecycle == LifecycleState.RESTORED || it.lifecycle == LifecycleState.EXPIRED
            }
        ) {
            return OrganizerDurableStatus.RESTORED_OR_EXPIRED
        }
        // 7–8: retained tombstones. Only PRUNED_UNUSED (unused checkpoint) and
        // QUARANTINED (proven no-mutation) never represented an applied result.
        val retained = tombstones.filter { it.expiresAtMs > nowMs }
        if (retained.any {
                it.reason == RecoveryStorePort.TombstoneReason.ALREADY_RESTORED ||
                    it.reason == RecoveryStorePort.TombstoneReason.EXPIRED
            }
        ) {
            return OrganizerDurableStatus.RESTORED_OR_EXPIRED
        }
        if (retained.any {
                it.reason == RecoveryStorePort.TombstoneReason.CORRUPT ||
                    it.reason == RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION
            }
        ) {
            return OrganizerDurableStatus.UNRESOLVED
        }
        return OrganizerDurableStatus.NEVER_ORGANIZED
    }
}
