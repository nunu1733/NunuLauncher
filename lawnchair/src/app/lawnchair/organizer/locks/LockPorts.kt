package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.planning.RevisionId

/**
 * Lock-authoring capture. Mirrors the Issue #14 canonical snapshot the module
 * needs; no second truth is introduced — production recaptures the Launcher DB
 * through the same codec boundary.
 */
data class LockCapture(
    val state: LayoutState,
    val revision: RevisionId,
)

/**
 * Port: authoritative current capture. Production = Launcher DB adapter;
 * tests = fake. Spec §“Data and state”.
 */
fun interface LockCapturePort {
    fun capture(): LockCapture
}

/**
 * One reviewed single-column write. [expected] is the exact captured
 * canonical state of the row; the writer re-reads it inside the DB
 * transaction and rejects on any mismatch (Issue #14 A5 discipline scoped to
 * the lock column).
 */
data class LockRowWrite(
    val rowId: Long,
    val item: app.lawnchair.organizer.planning.ItemId,
    val expected: app.lawnchair.organizer.application.public.CanonicalItemState,
    val newState: LockTargetState,
)

/**
 * Closed write plan: every targeted row plus the source capture revision the
 * in-transaction reread must observe unchanged.
 */
data class LockWritePlan(
    val writes: List<LockRowWrite>,
    val sourceRevision: RevisionId,
) {
    init {
        require(writes.distinctBy { it.rowId }.size == writes.size) {
            "LockWritePlan must target each row at most once"
        }
    }
}

/** Typed in-transaction rejection of a lock write. */
enum class LockWriteRejection {
    STALE_REVISION,
    PRECONDITION_FAILED,
    WRITER_BUSY,
}

/**
 * Writer port outcome. [LockWriteOutcome.Committed.newRevision] is null when
 * the writer does not recapture after commit; the new revision is then
 * observable through a fresh [LockCapturePort.capture].
 */
sealed interface LockWriteOutcome {
    data class Committed(val newRevision: RevisionId?) : LockWriteOutcome
    data class Rejected(val reason: LockWriteRejection) : LockWriteOutcome
    data class Failed(val cause: Throwable) : LockWriteOutcome
}

/**
 * Port: transactional lock-state writer. Production acquires the
 * `LayoutWriteCoordinator` organizer lease, rereads the revision and every
 * exact row precondition inside one `ModelDbController` transaction, updates
 * only `favorites.organizerLockState`, and commits atomically.
 */
fun interface LockStateWriterPort {
    fun write(plan: LockWritePlan): LockWriteOutcome
}
