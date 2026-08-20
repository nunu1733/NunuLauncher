package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.actions.RecoveryAction
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.LifecycleTransitions
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId

/**
 * Internal port: layout writer. Production = Launcher adapter; tests =
 * FakeLayoutWriter. The protocol depends only on this seam, never on SQLite
 * or Android.
 *
 * Issue #14 Stage B step 4.
 */
interface LayoutWriterPort {

    /** Acquire the writer lease for [kind]. Non-blocking; returns null on contention. */
    fun tryAcquireLease(kind: WriterKind, token: Long): LeaseHandle?

    /** Capture the authoritative current snapshot under a freshly acquired lease. */
    fun captureCurrent(captureId: CaptureId): CapturedSnapshot

    /** Materialize lossless post intent before the checkpoint is marked APPLYING. */
    fun prepareApplyWriteSet(
        capture: CapturedSnapshot,
        plan: app.lawnchair.organizer.application.public.ValidatedLayoutPlan,
    ): WriteSetPreparation

    /** Build the exact manifest-backed restore set against the reviewed capture. */
    fun prepareRecoveryWriteSet(
        targetManifest: PersistenceManifest,
        reviewedCurrent: CapturedSnapshot,
    ): WriteSetPreparation

    /**
     * Apply [writeSet] inside one Launcher DB transaction. The writer re-reads
     * the current revision/preconditions after acquiring its in-transaction
     * lock and returns [ApplyTxOutcome] for ambiguous close.
     *
     * Spec §“Apply protocol” A5–A6, §“Transaction outcome classification”.
     */
    fun applyWriteSet(
        lease: LeaseHandle,
        writeSet: MaterializedWriteSet,
        pointId: RecoveryPointId?,
        faults: FaultInjector,
    ): ApplyTxOutcome

    /** Recapture the DB independently of the model snapshot. */
    fun recaptureDb(): CapturedSnapshot

    /**
     * Request a correlated reload and wait for the matching generation.
     *
     * The exact organizer lease capability is carried into the one LoaderTask
     * created for this request. That task may perform loader cleanup while the
     * outer organizer lease remains held; tokenless loader/model work defers.
     */
    fun requestCorrelatedReload(lease: LeaseHandle): ReloadResult

    /** Run [block] under a lease; defers tokenless MODEL_EXECUTOR work via the coordinator. */
    fun <T> withLease(kind: WriterKind, token: Long, block: (LeaseHandle) -> T): T?

    /** Classify the authoritative Launcher state at the recovery boundary. */
    fun classifyAuthoritativeState(
        preDigest: ByteArray,
        intendedPostDigest: ByteArray,
        recoveryTargetDigest: ByteArray?,
        reviewedCurrentDigest: ByteArray?,
    ): AuthoritativeClass
}

enum class WriterKind { ORGANIZER, MODEL_WRITER, GRID_MIGRATION, RESTORE, BACKUP_RESTORE }

interface LeaseHandle {
    val kind: WriterKind
    val token: Long
    fun close()
}

data class CaptureId(val value: String)

data class CapturedSnapshot(
    val layoutState: LayoutState,
    val manifest: PersistenceManifest,
    val revision: RevisionId,
    val digest: ByteArray,
)

/** Materialized row-accounted write-set produced from actions. */
data class MaterializedWriteSet(
    val actions: List<ApplyAction>,
    val intendedState: LayoutState,
    val intendedManifest: PersistenceManifest,
    val actionSetDigest: ByteArray,
    val recoveryActions: List<RecoveryAction> = emptyList(),
    /** Revision of the source snapshot against which the A5 reread validates. */
    val sourceRevision: RevisionId,
)

sealed interface WriteSetPreparation {
    data class Ready(val writeSet: MaterializedWriteSet) : WriteSetPreparation
    data object InvalidPlan : WriteSetPreparation
    data object IdentityExhausted : WriteSetPreparation
    data object ContextMismatch : WriteSetPreparation
}

sealed interface ApplyTxOutcome {
    data object Committed : ApplyTxOutcome
    data class Failed(val cause: Throwable) : ApplyTxOutcome
    data object OutcomeUnknown : ApplyTxOutcome

    // A5 in-transaction reread caught a stale revision or precondition change
    // before any mutation committed. Maps to ApplyResult.Rejected in the protocol.
    data class PreconditionFailed(val rejection: PreWriteRejection) : ApplyTxOutcome
}

sealed interface ReloadResult {
    data object Completed : ReloadResult
    data object Failed : ReloadResult
    data object Superseded : ReloadResult
    data object Timeout : ReloadResult
}

enum class AuthoritativeClass {
    PRE_STATE,
    INTENDED_POST_STATE,
    RECOVERY_TARGET,
    REVIEWED_CURRENT_STATE,
    NEITHER,
}

/** Internal port: recovery store. Production = SQLite; tests = FakeRecoveryStore. */
interface RecoveryStorePort {

    fun availability(): StoreAvailability

    /**
     * Existing maintenance-capable lookup. Recovery-store implementations may
     * purge expired tombstones while serving this path.
     */
    fun readTombstone(pointId: RecoveryPointId): Tombstone?

    /**
     * Read one snapshot projection without opening SQLite, invoking
     * SQLiteOpenHelper/version probing, retention cleanup, transaction writes,
     * or lifecycle mutation. Used only by recovery preview inspection.
     */
    fun readInspectionProjection(pointId: RecoveryPointId): InspectionProjectionRead

    /** Startup-only authoritative rehydration of the derived inspection snapshot. */
    fun rebuildInspectionSnapshotForReconciliation(): Boolean

    fun checkpoint(payload: CheckpointPayload): CheckpointResult

    fun markApplying(
        pointId: RecoveryPointId,
        intendedManifest: PersistenceManifest,
        intendedDigest: ByteArray,
        applyActionDigest: ByteArray,
        itemCount: Int,
        resourceCount: Int,
    ): Boolean

    fun advance(pointId: RecoveryPointId, next: LifecycleState): Boolean

    fun markRestoring(
        pointId: RecoveryPointId,
        reviewedManifest: PersistenceManifest,
        reviewedDigest: ByteArray,
        recoveryActionDigest: ByteArray,
    ): Boolean

    fun readRecord(pointId: RecoveryPointId): StoredRecord?

    fun listNonFinalRecords(): List<StoredRecord>

    /** Delete a checkpoint proven unused while Launcher still matches its pre-state. */
    fun pruneUnused(pointId: RecoveryPointId): Boolean

    fun runRetention(nowMillis: Long): RetentionOutcome

    enum class StoreAvailability { READY, INCOMPATIBLE_VERSION, READ_FAILED }

    /** Closed result of the SQLite-free inspection snapshot seam. */
    sealed interface InspectionProjectionRead {
        data class Value(val projection: InspectionProjection) : InspectionProjectionRead
        data object Unavailable : InspectionProjectionRead
        data object Incompatible : InspectionProjectionRead
    }

    /** Minimal derived metadata needed by #84 classification; no payload data. */
    sealed interface InspectionProjection {
        data class Record(
            val pointId: RecoveryPointId,
            val lifecycle: LifecycleState,
            val createdAtMs: Long,
            val updatedAtMs: Long,
            val checksumValid: Boolean,
            val formatVersion: Int,
        ) : InspectionProjection

        data class Tombstone(
            val pointId: RecoveryPointId,
            val reason: TombstoneReason,
            val expiresAtMs: Long,
        ) : InspectionProjection

        data object Missing : InspectionProjection
    }

    enum class TombstoneReason {
        CORRUPT,
        INCOMPATIBLE_VERSION,
        ALREADY_RESTORED,
        EXPIRED,
        PRUNED_UNUSED,
    }

    data class Tombstone(
        val pointId: RecoveryPointId,
        val reason: TombstoneReason,
        val expiresAtMs: Long,
    )

    data class CheckpointPayload(
        val pointId: RecoveryPointId,
        val runId: RunId,
        val preManifest: PersistenceManifest,
        val preRevision: RevisionId,
        val preDigest: ByteArray,
        val applyActionDigest: ByteArray,
        val itemCount: Int,
        val resourceCount: Int,
    )

    sealed interface CheckpointResult {
        data class Ready(val record: StoredRecord) : CheckpointResult
        data object PointIdCollision : CheckpointResult
        data object CreateFailed : CheckpointResult
        data object ValidateFailed : CheckpointResult
        data object StoreUnavailable : CheckpointResult
    }

    sealed interface RetentionOutcome {
        data object Applied : RetentionOutcome
        data object StoreUnavailable : RetentionOutcome
        data object Failed : RetentionOutcome
    }

    interface StoredRecord {
        val pointId: RecoveryPointId
        val runId: RunId
        val lifecycle: LifecycleState
        val priorLifecycle: LifecycleState?
        val createdAtMs: Long
        val updatedAtMs: Long
        val preManifest: PersistenceManifest
        val preRevision: RevisionId
        val preDigest: ByteArray
        val intendedManifest: PersistenceManifest
        val intendedDigest: ByteArray
        val applyActionDigest: ByteArray
        val reviewedManifest: PersistenceManifest?
        val reviewedDigest: ByteArray?
        val recoveryActionDigest: ByteArray?
        val itemCount: Int
        val resourceCount: Int
        val checksumValid: Boolean
        val formatVersion: Int
    }
}

/** Internal reconciliation-only seam; ordinary protocols never receive this port. */
internal interface RecoveryStoreReconciliationPort {
    fun <T> withReconciliationScope(block: () -> T): T
}

/** Internal port: clock for retention and timestamps. */
interface Clock {
    fun nowMillis(): Long
}

/** Internal port: canonical 128-bit lowercase hex run/point IDs. */
interface OperationIdSource {
    fun newRunId(): RunId
    fun newPointId(): RecoveryPointId
}

/**
 * Internal port: deterministic failure injection. [NOOP] is the production
 * instance; tests use a recording double to flip the exact phase under test.
 *
 * Spec §“Failure injection”: every durable phase has a before/after hook,
 * plus the process-death variants at SA-12/13/14 and interrupted recovery.
 *
 * Issue #14 Stage B step 4.
 */
interface FaultInjector {
    fun beforeRecoveryLifecycleCommit(phase: RecoveryLifecyclePhase, pointId: RecoveryPointId)
    fun afterRecoveryLifecycleCommit(phase: RecoveryLifecyclePhase, pointId: RecoveryPointId)
    fun beforeLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?)
    fun afterLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?)
    fun atTransactionClose(pointId: RecoveryPointId?): TransactionCloseDirective
    fun beforeModelReloadRequest()
    fun afterCorrelatedGenerationWait(): ReloadDirective
    fun beforeIndependentDbRecapture()
    fun afterVerification()
    fun serializationContention(): Boolean
    fun restartBoundary(phase: RestartPhase): RestartDirective
    fun gridMigrationOutcome(): GridMigrationDirective
    fun recoveryStoreReadFailure(): Boolean
    fun recoveryStoreWriteFailure(): Boolean
    fun cleanupRetentionTransactionFailure(): Boolean
    fun lockStateColumnReadFailure(): Boolean

    enum class RecoveryLifecyclePhase {
        CREATING,
        READY,
        APPLYING,
        COMMITTED_UNVERIFIED,
        VERIFIED,
        RESTORING,
        RESTORED,
        EXPIRED,
        TOMBSTONE_CLEANUP,
    }
    enum class TransactionCloseDirective { PROCEED, THROW_SQLITE_EXCEPTION, SIMULATE_PROCESS_DEATH }
    enum class ReloadDirective { PROCEED, FAIL, SUPERSEDE }
    enum class RestartPhase {
        BEFORE_RECONCILE,
        AFTER_RECONCILE,
        BEFORE_CREATE,
        AFTER_CREATE,
        BEFORE_APPLYING,
        AFTER_APPLYING,
        BEFORE_COMMITTED_UNVERIFIED,
        AFTER_COMMITTED_UNVERIFIED,
        BEFORE_VERIFIED,
        AFTER_VERIFIED,
        BEFORE_RESTORING,
        AFTER_RESTORING,
    }
    enum class RestartDirective { CONTINUE, SIMULATE_DEATH }
    enum class GridMigrationDirective { SUCCEED, FAIL }

    companion object {
        val NOOP: FaultInjector = object : FaultInjector {
            override fun beforeRecoveryLifecycleCommit(phase: RecoveryLifecyclePhase, pointId: RecoveryPointId) = Unit
            override fun afterRecoveryLifecycleCommit(phase: RecoveryLifecyclePhase, pointId: RecoveryPointId) = Unit
            override fun beforeLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?) = Unit
            override fun afterLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?) = Unit
            override fun atTransactionClose(pointId: RecoveryPointId?): TransactionCloseDirective = TransactionCloseDirective.PROCEED
            override fun beforeModelReloadRequest() = Unit
            override fun afterCorrelatedGenerationWait(): ReloadDirective = ReloadDirective.PROCEED
            override fun beforeIndependentDbRecapture() = Unit
            override fun afterVerification() = Unit
            override fun serializationContention(): Boolean = false
            override fun restartBoundary(phase: RestartPhase): RestartDirective = RestartDirective.CONTINUE
            override fun gridMigrationOutcome(): GridMigrationDirective = GridMigrationDirective.SUCCEED
            override fun recoveryStoreReadFailure(): Boolean = false
            override fun recoveryStoreWriteFailure(): Boolean = false
            override fun cleanupRetentionTransactionFailure(): Boolean = false
            override fun lockStateColumnReadFailure(): Boolean = false
        }
    }
}

/**
 * Run mutex: A0 rejects concurrent apply/recover across all instances.
 * Single-process monitor keyed on the active run id; safe to use from
 * tests because it is not held across I/O except inside the protocol's own
 * scope.
 *
 * Issue #14 Stage B step 4 (A0).
 */
interface RunMutexPort {
    fun tryAcquire(runId: RunId): Boolean
    fun release(runId: RunId)
}

class RunMutex : RunMutexPort {
    private var holder: RunId? = null

    @Synchronized
    override fun tryAcquire(runId: RunId): Boolean {
        if (holder != null) return false
        holder = runId
        return true
    }

    @Synchronized
    override fun release(runId: RunId) {
        if (holder == runId) holder = null
    }

    @Synchronized
    fun currentHolder(): RunId? = holder
}

/**
 * Convenience to assert a lifecycle transition is legal at the protocol layer.
 * The state-machine legality is owned by [LifecycleTransitions]; this is a
 * thin wrapper to avoid leaking the helper into the protocol code paths.
 */
fun RecoveryStorePort.advanceOrThrow(pointId: RecoveryPointId, next: LifecycleState, currentExpected: LifecycleState) {
    LifecycleTransitions.requireLegal(currentExpected, next)
    check(advance(pointId, next)) {
        "Recovery store refused to advance $pointId: $currentExpected -> $next"
    }
}
