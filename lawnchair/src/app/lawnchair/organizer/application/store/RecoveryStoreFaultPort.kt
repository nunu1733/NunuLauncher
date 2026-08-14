package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.public.RecoveryPointId

/**
 * Local, typed fault port for [RecoveryStore]. Tests inject failures at the
 * exact durable phase and commit boundary; the production default is a NOOP.
 *
 * Phases mirror the recovery lifecycle states plus the shared tombstone write
 * used by retention and prune. "Before commit" means before the SQLite write
 * inside the transaction; "after commit" means after `endTransaction()` has
 * returned, simulating an ambiguous commit acknowledgement.
 *
 * AC-14. Issue #14 Stage B step 3.
 */
interface RecoveryStoreFaultPort {

    fun beforeCommit(phase: Phase, pointId: RecoveryPointId)

    fun afterCommit(phase: Phase, pointId: RecoveryPointId)

    /** Phase of a durable recovery-store write. */
    enum class Phase {
        CREATING,
        READY,
        APPLYING,
        COMMITTED_UNVERIFIED,
        VERIFIED,
        RESTORING,
        RESTORED,
        CORRUPT,
        INCOMPATIBLE,
        EXPIRED,
        TOMBSTONE,
    }

    companion object {
        val NOOP: RecoveryStoreFaultPort = object : RecoveryStoreFaultPort {
            override fun beforeCommit(phase: Phase, pointId: RecoveryPointId) = Unit
            override fun afterCommit(phase: Phase, pointId: RecoveryPointId) = Unit
        }
    }
}
