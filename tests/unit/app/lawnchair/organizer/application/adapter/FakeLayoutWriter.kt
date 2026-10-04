package app.lawnchair.organizer.application.adapter

import app.lawnchair.organizer.application.actions.RecoveryWriteSetMaterializer
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.protocol.ApplyTxOutcome
import app.lawnchair.organizer.application.protocol.AuthoritativeClass
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.Clock
import app.lawnchair.organizer.application.protocol.FaultInjector
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.protocol.LeaseHandle
import app.lawnchair.organizer.application.protocol.MaterializedWriteSet
import app.lawnchair.organizer.application.protocol.ReloadResult
import app.lawnchair.organizer.application.protocol.WriteSetPreparation
import app.lawnchair.organizer.application.protocol.WriterKind
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PreWriteRejection
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.ValidatedLayoutPlan
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.ProfileId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class FakeLayoutWriter(
    initialState: LayoutState,
) : LayoutWriterPort {

    private val stateRef: AtomicReference<LayoutState> = AtomicReference(initialState)
    private val knownStates: MutableList<LayoutState> = mutableListOf(initialState)
    private val leaseHeld: AtomicLong = AtomicLong(NO_LEASE)

    private val failOnNthWrite: AtomicLong = AtomicLong(-1L)
    private val writeCount: AtomicLong = AtomicLong(0L)

    var refuseLease: Boolean = false
    var nextTxOutcome: ApplyTxOutcome = ApplyTxOutcome.Committed
    var reloadResult: ReloadResult = ReloadResult.Completed
    var materializedIntendedStateOverride: ((LayoutState) -> LayoutState)? = null
    var onApplyA5Reread: (() -> Unit)? = null

    var capturedSnapshots: Int = 0
        private set
    var recaptureCount: Int = 0
        private set
    var reloadCount: Int = 0
        private set
    var appliedWriteSets: Int = 0
        private set
    var lastIntendedState: LayoutState? = null
        private set

    fun setFailOnNthWrite(n: Long) {
        failOnNthWrite.set(n)
    }

    fun currentState(): LayoutState = stateRef.get()

    fun setCurrentState(state: LayoutState) {
        knownStates += state
        stateRef.set(state)
    }

    override fun tryAcquireLease(kind: WriterKind, token: Long): LeaseHandle? {
        if (refuseLease) return null
        if (!leaseHeld.compareAndSet(NO_LEASE, token)) return null
        return LeaseHandleImpl(token)
    }

    override fun captureCurrent(captureId: CaptureId): CapturedSnapshot {
        capturedSnapshots += 1
        return captureOf(stateRef.get())
    }

    override fun prepareApplyWriteSet(
        capture: CapturedSnapshot,
        plan: ValidatedLayoutPlan,
    ): WriteSetPreparation {
        val intendedState = materializedIntendedStateOverride?.invoke(plan.intendedState) ?: plan.intendedState
        val intendedManifest = manifestFor(intendedState)
        knownStates += intendedState
        return WriteSetPreparation.Ready(
            MaterializedWriteSet(
                actions = plan.actions,
                intendedState = intendedState,
                intendedManifest = intendedManifest,
                actionSetDigest = RevisionCalculator.actionSetDigestOf(plan.actions),
                sourceRevision = capture.revision,
            ),
        )
    }

    override fun prepareRecoveryWriteSet(
        targetManifest: PersistenceManifest,
        reviewedCurrent: CapturedSnapshot,
    ): WriteSetPreparation {
        val recovery = try {
            RecoveryWriteSetMaterializer.materialize(targetManifest, reviewedCurrent.manifest)
        } catch (_: IllegalArgumentException) {
            return WriteSetPreparation.ContextMismatch
        }
        return WriteSetPreparation.Ready(
            MaterializedWriteSet(
                actions = emptyList(),
                intendedState = stateForManifest(targetManifest) ?: reviewedCurrent.layoutState,
                intendedManifest = targetManifest,
                actionSetDigest = recovery.digest(),
                recoveryActions = recovery.actions,
                sourceRevision = reviewedCurrent.revision,
            ),
        )
    }

    override fun applyWriteSet(
        lease: LeaseHandle,
        writeSet: MaterializedWriteSet,
        pointId: RecoveryPointId?,
        faults: FaultInjector,
    ): ApplyTxOutcome {
        // A5 in-transaction reread: full-revision + item precondition check.
        onApplyA5Reread?.invoke()
        val before = captureOf(stateRef.get())
        if (before.revision != writeSet.sourceRevision) {
            return ApplyTxOutcome.PreconditionFailed(PreWriteRejection.STALE_REVISION)
        }
        val beforeItems = before.layoutState.items.associateBy { it.ref }
        if (writeSet.actions.any { action ->
                when (action) {
                    is ApplyAction.Preserve -> beforeItems[action.ref] != action.expected
                    is ApplyAction.Update -> beforeItems[action.ref] != action.expected
                    is ApplyAction.Insert -> action.ref in beforeItems
                }
            }
        ) {
            return ApplyTxOutcome.PreconditionFailed(PreWriteRejection.EXACT_PRECONDITION_FAILED)
        }
        faults.beforeLauncherWrite(0, pointId)
        val n = writeCount.incrementAndGet()
        val failAt = failOnNthWrite.get()
        if (failAt > 0L && n == failAt) {
            return ApplyTxOutcome.Failed(RuntimeException("injected Nth-write failure"))
        }
        faults.afterLauncherWrite(0, pointId)
        when (faults.atTransactionClose(pointId)) {
            FaultInjector.TransactionCloseDirective.PROCEED -> Unit

            FaultInjector.TransactionCloseDirective.THROW_SQLITE_EXCEPTION ->
                return ApplyTxOutcome.Failed(RuntimeException("injected transaction-close failure"))

            FaultInjector.TransactionCloseDirective.SIMULATE_PROCESS_DEATH ->
                return ApplyTxOutcome.OutcomeUnknown
        }
        if (nextTxOutcome !is ApplyTxOutcome.Failed) {
            appliedWriteSets += 1
            lastIntendedState = writeSet.intendedState
            knownStates += writeSet.intendedState
            stateRef.set(writeSet.intendedState)
        }
        return nextTxOutcome
    }

    override fun recaptureDb(): CapturedSnapshot {
        recaptureCount += 1
        return captureOf(stateRef.get())
    }

    override fun requestCorrelatedReload(lease: LeaseHandle): ReloadResult {
        reloadCount += 1
        return reloadResult
    }

    override fun <T> withLease(kind: WriterKind, token: Long, block: (LeaseHandle) -> T): T? {
        val lease = tryAcquireLease(kind, token) ?: return null
        return try {
            block(lease)
        } finally {
            lease.close()
        }
    }

    override fun classifyAuthoritativeState(
        preDigest: ByteArray,
        intendedPostDigest: ByteArray,
        recoveryTargetDigest: ByteArray?,
        reviewedCurrentDigest: ByteArray?,
    ): AuthoritativeClass {
        val currentDigest = RevisionCalculator.classificationDigestOf(stateRef.get())
        return when {
            currentDigest.contentEquals(preDigest) -> AuthoritativeClass.PRE_STATE

            currentDigest.contentEquals(intendedPostDigest) -> AuthoritativeClass.INTENDED_POST_STATE

            recoveryTargetDigest != null && currentDigest.contentEquals(recoveryTargetDigest) ->
                AuthoritativeClass.RECOVERY_TARGET

            reviewedCurrentDigest != null && currentDigest.contentEquals(reviewedCurrentDigest) ->
                AuthoritativeClass.REVIEWED_CURRENT_STATE

            else -> AuthoritativeClass.NEITHER
        }
    }

    private fun captureOf(state: LayoutState): CapturedSnapshot = CapturedSnapshot(
        layoutState = state,
        manifest = manifestFor(state),
        revision = RevisionCalculator.revisionOf(state),
        digest = RevisionCalculator.classificationDigestOf(state),
    )

    private fun manifestFor(state: LayoutState): PersistenceManifest {
        val rows = state.items.mapIndexed { index, item ->
            PersistentRow(
                rowId = (index + 1).toLong(),
                itemId = ItemId("item-$index"),
                profileId = item.profile,
                containerCode = ContainerCode(0),
                screenId = null,
                cellX = 0,
                cellY = 0,
                spanX = 1,
                spanY = 1,
                rank = 0,
                itemType = KindCode(0),
                appWidgetId = null,
                appWidgetProvider = null,
                iconBytes = null,
                title = null,
                intent = null,
                restored = null,
                options = null,
                appWidgetSource = null,
                modified = 0L,
                organizerLockState = item.lockState,
                rawCell = null,
                rawSpan = null,
            )
        }
        val resources = state.pages.mapIndexed { index, page ->
            PersistentResource(
                kind = PersistentResourceKind.WORKSPACE_SCREEN,
                profileId = ProfileId("personal"),
                order = index.toLong(),
                payload = byteArrayOf(index.toByte()),
            )
        }
        return PersistenceManifest(
            formatVersion = 1,
            schemaVersion = 33,
            rowCount = rows.size,
            rows = rows,
            resources = resources,
            modifiedAtMillis = 0L,
        )
    }

    private fun stateForManifest(manifest: PersistenceManifest): LayoutState? = knownStates.lastOrNull { manifestFor(it) == manifest }

    private inner class LeaseHandleImpl(
        private val leaseToken: Long,
    ) : LeaseHandle {
        override val kind: WriterKind = WriterKind.ORGANIZER
        override val token: Long get() = leaseToken

        override fun close() {
            leaseHeld.compareAndSet(leaseToken, NO_LEASE)
        }
    }

    private companion object {
        const val NO_LEASE: Long = Long.MIN_VALUE
    }
}

object FakeClock : Clock {
    private val now: AtomicLong = AtomicLong(1_000L)
    fun set(value: Long) {
        now.set(value)
    }
    fun advance(by: Long) {
        now.addAndGet(by)
    }
    override fun nowMillis(): Long = now.get()
}
