package app.lawnchair.organizer.application.public

/**
 * Closed, field-free projection of the organizer's durable recovery-store
 * state (Issue #271). The application module derives it from the recovery
 * store's persisted records and tombstones so a re-opened Settings surface can
 * distinguish "never organized" from a restorable, unresolved, or consumed
 * recovery point instead of presenting a bare `Idle`.
 *
 * The type deliberately carries no fields: it cannot leak a record payload,
 * revision, digest, item identity, identifier, or timestamp, and it is not
 * persisted anywhere — it is re-derived on every read, so it can never outlive
 * the record it describes. Spec §§"Recovery record and lifecycle", "Retention"
 * (spec 13) stay the contract; this projection is non-authoritative.
 */
enum class OrganizerDurableStatus {
    /** No durable record represents an organized (applied) layout. */
    NEVER_ORGANIZED,

    /** A `VERIFIED` recovery point exists within its retention window. */
    ORGANIZED_RESTORABLE,

    /** A durable record or tombstone presents an unresolved recovery. */
    UNRESOLVED,

    /** The saved point was restored or its retention lapsed. */
    RESTORED_OR_EXPIRED,

    /**
     * Fail-closed result: the durable truth cannot be read (store
     * unavailable, startup reconciliation not completed, writer contention,
     * or unreadable inspection snapshot). Renders no status.
     */
    UNAVAILABLE,
}
