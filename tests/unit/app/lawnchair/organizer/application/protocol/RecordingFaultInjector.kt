package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.public.RecoveryPointId

/**
 * Recording [FaultInjector] used by protocol tests. Each flag toggles a single
 * deterministic phase so tests assert exactly one injected behavior at a time.
 *
 * Issue #14 Stage B step 4.
 */
class RecordingFaultInjector : FaultInjector {
    var serializationContention: Boolean = false
    var recoveryStoreReadFailure: Boolean = false
    var recoveryStoreWriteFailure: Boolean = false
    var cleanupRetentionTransactionFailure: Boolean = false
    var lockStateColumnReadFailure: Boolean = false
    var transactionCloseDirective: FaultInjector.TransactionCloseDirective = FaultInjector.TransactionCloseDirective.PROCEED
    var reloadDirective: FaultInjector.ReloadDirective = FaultInjector.ReloadDirective.PROCEED
    var gridMigrationDirective: FaultInjector.GridMigrationDirective = FaultInjector.GridMigrationDirective.SUCCEED

    val recordedLifecyclePhases: MutableList<Pair<FaultInjector.RecoveryLifecyclePhase, RecoveryPointId?>> = mutableListOf()
    val recordedRestartBoundaries: MutableList<FaultInjector.RestartPhase> = mutableListOf()
    val recordedLauncherWrites: MutableList<Pair<Int, RecoveryPointId?>> = mutableListOf()
    var reloadRequestCount: Int = 0
        private set
    var independentRecaptureCount: Int = 0
        private set
    var verificationCount: Int = 0
        private set

    fun reset() {
        serializationContention = false
        recoveryStoreReadFailure = false
        recoveryStoreWriteFailure = false
        cleanupRetentionTransactionFailure = false
        lockStateColumnReadFailure = false
        transactionCloseDirective = FaultInjector.TransactionCloseDirective.PROCEED
        reloadDirective = FaultInjector.ReloadDirective.PROCEED
        gridMigrationDirective = FaultInjector.GridMigrationDirective.SUCCEED
        recordedLifecyclePhases.clear()
        recordedRestartBoundaries.clear()
        recordedLauncherWrites.clear()
        reloadRequestCount = 0
        independentRecaptureCount = 0
        verificationCount = 0
    }

    override fun beforeRecoveryLifecycleCommit(phase: FaultInjector.RecoveryLifecyclePhase, pointId: RecoveryPointId) {
        recordedLifecyclePhases += phase to pointId
    }
    override fun afterRecoveryLifecycleCommit(phase: FaultInjector.RecoveryLifecyclePhase, pointId: RecoveryPointId) {
        recordedLifecyclePhases += phase to pointId
    }
    override fun beforeLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?) {
        recordedLauncherWrites += indexInTransaction to pointId
    }
    override fun afterLauncherWrite(indexInTransaction: Int, pointId: RecoveryPointId?) {
        recordedLauncherWrites += indexInTransaction to pointId
    }
    override fun atTransactionClose(pointId: RecoveryPointId?): FaultInjector.TransactionCloseDirective = transactionCloseDirective
    override fun beforeModelReloadRequest() {
        reloadRequestCount += 1
    }
    override fun afterCorrelatedGenerationWait(): FaultInjector.ReloadDirective = reloadDirective
    override fun beforeIndependentDbRecapture() {
        independentRecaptureCount += 1
    }
    override fun afterVerification() {
        verificationCount += 1
    }
    override fun serializationContention(): Boolean = serializationContention
    override fun restartBoundary(phase: FaultInjector.RestartPhase): FaultInjector.RestartDirective {
        recordedRestartBoundaries += phase
        return FaultInjector.RestartDirective.CONTINUE
    }
    override fun gridMigrationOutcome(): FaultInjector.GridMigrationDirective = gridMigrationDirective
    override fun recoveryStoreReadFailure(): Boolean = recoveryStoreReadFailure
    override fun recoveryStoreWriteFailure(): Boolean = recoveryStoreWriteFailure
    override fun cleanupRetentionTransactionFailure(): Boolean = cleanupRetentionTransactionFailure
    override fun lockStateColumnReadFailure(): Boolean = lockStateColumnReadFailure
}

/**
 * OperationIdSource that returns deterministic IDs from a fixed sequence —
 * tests assert against stable RunId/RecoveryPointId values without depending
 * on randomness.
 */
class FixedOperationIdSource(
    private val runIds: List<String> = listOf("11111111111111111111111111111111"),
    private val pointIds: List<String> = listOf("22222222222222222222222222222222"),
) : OperationIdSource {
    private var runIndex: Int = 0
    private var pointIndex: Int = 0
    init {
        require(runIds.isNotEmpty())
        require(pointIds.isNotEmpty())
    }
    override fun newRunId(): app.lawnchair.organizer.application.public.RunId = app.lawnchair.organizer.application.public.RunId(
        runIds[runIndex.coerceAtMost(runIds.lastIndex)].also { runIndex += 1 },
    )
    override fun newPointId(): app.lawnchair.organizer.application.public.RecoveryPointId = app.lawnchair.organizer.application.public.RecoveryPointId(
        pointIds[pointIndex.coerceAtMost(pointIds.lastIndex)].also { pointIndex += 1 },
    )
}
