package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.public.AuthoritativeState
import app.lawnchair.organizer.application.public.RecoveryFailure
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.planning.RevisionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #84 confirmation must share LayoutApplicationModule's recovery
 * application behavior rather than calling RecoveryProtocol directly.
 */
class LayoutApplicationModuleRecoveryEntryTest {

    private lateinit var writer: FakeLayoutWriter
    private lateinit var store: FakeRecoveryStore
    private lateinit var diagnostics: RecordingDiagnostics
    private lateinit var module: LayoutApplicationModule<FakeRecoveryStore>
    private val pointId = RecoveryPointId("22222222222222222222222222222222")
    private val runId = RunId("11111111111111111111111111111111")

    @Before
    fun setUp() {
        FakeClock.set(1_000L)
        writer = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        store = FakeRecoveryStore { FakeClock.nowMillis() }
        diagnostics = RecordingDiagnostics()
        module = LayoutApplicationModule(
            writer = writer,
            store = store,
            clock = FakeClock,
            operationIds = FixedOperationIdSource(),
            diagnosticsPort = diagnostics,
        )
        module.reconcileAtStart()
        seedVerifiedRecord()
    }

    @Test
    fun expiredConfirmationReusesReadinessAndExistingRecoveryDiagnostics() {
        val preview = module.inspectRecovery(pointId)
        assertTrue("Expected Restorable, got $preview", preview is RecoveryPreviewResult.Restorable)
        val confirmation = (preview as RecoveryPreviewResult.Restorable).confirmation
        assertEquals(1, writer.capturedSnapshots)
        assertEquals(emptyList<RunEvent>(), diagnostics.snapshot())

        FakeClock.advance(RetentionPolicy.RETENTION_MILLIS + 1L)
        val result = module.confirmRecoveryPreview(pointId, confirmation)

        assertEquals(RecoveryResult.NotRestorable(pointId, RecoveryRejection.EXPIRED), result)
        assertEquals(
            listOf(PhaseCode.RECOVERY_REQUESTED, PhaseCode.RECOVERY_REJECTED),
            diagnostics.snapshot().map { it.phase },
        )
        assertEquals(1, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
        assertEquals(0, store.markRestoringCalls)
    }

    @Test
    fun confirmationIsOneShotAndReadinessIsRecheckedAfterPreview() {
        val preview = module.inspectRecovery(pointId) as RecoveryPreviewResult.Restorable

        module.failStartupReconciliation()
        val first = module.confirmRecoveryPreview(pointId, preview.confirmation)
        val second = module.confirmRecoveryPreview(pointId, preview.confirmation)

        assertEquals(
            RecoveryResult.RestoreFailed(
                pointId,
                RecoveryFailure.RECOVERY_STORE_FAILED,
                AuthoritativeState.UNKNOWN,
            ),
            first,
        )
        assertEquals(RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING), second)
        assertEquals(1, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(emptyList<RunEvent>(), diagnostics.snapshot())
    }

    @Test
    fun unregisteredConfirmationCannotEnterRecovery() {
        val forged = RecoveryPreviewConfirmation.issue(ByteArray(32))

        val result = module.confirmRecoveryPreview(pointId, forged)

        assertEquals(RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING), result)
        assertEquals(0, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(emptyList<RunEvent>(), diagnostics.snapshot())
    }

    @Test
    fun everyPersistedRevisionDimensionMakesPreviewConfirmationStale() {
        RevisionRaceDimensions.all.forEach { dimension ->
            setUp()
            writer.setCurrentState(dimension.source)
            seedVerifiedRecord()
            val preview = module.inspectRecovery(pointId) as RecoveryPreviewResult.Restorable
            writer.setCurrentState(dimension.mutated)

            val result = module.confirmRecoveryPreview(pointId, preview.confirmation)

            assertEquals(
                dimension.name,
                RecoveryResult.NotRestorable(pointId, RecoveryRejection.STALE_REVISION),
                result,
            )
            assertEquals(dimension.name, 0, writer.appliedWriteSets)
            assertEquals(dimension.name, 0, writer.reloadCount)
            assertEquals(dimension.name, 0, store.markRestoringCalls)
            assertEquals(
                dimension.name,
                listOf(PhaseCode.RECOVERY_REQUESTED, PhaseCode.RECOVERY_REJECTED),
                diagnostics.snapshot().map { it.phase },
            )
        }
    }

    private fun seedVerifiedRecord() {
        val state = writer.currentState()
        val revision = RevisionCalculator.revisionOf(state)
        val digest = RevisionCalculator.classificationDigestOf(state)
        val manifest = PersistenceManifest(
            formatVersion = 1,
            schemaVersion = 33,
            rowCount = 0,
            rows = emptyList(),
            resources = emptyList(),
            modifiedAtMillis = 0L,
        )
        store.seedRecord(
            object : RecoveryStorePort.StoredRecord {
                override val pointId: RecoveryPointId get() = this@LayoutApplicationModuleRecoveryEntryTest.pointId
                override val runId: RunId get() = this@LayoutApplicationModuleRecoveryEntryTest.runId
                override val lifecycle: LifecycleState = LifecycleState.VERIFIED
                override val priorLifecycle: LifecycleState? = null
                override val createdAtMs: Long = FakeClock.nowMillis()
                override val updatedAtMs: Long = FakeClock.nowMillis()
                override val preManifest: PersistenceManifest = manifest
                override val preRevision: RevisionId = revision
                override val preDigest: ByteArray = digest
                override val intendedManifest: PersistenceManifest = manifest
                override val intendedDigest: ByteArray = digest
                override val applyActionDigest: ByteArray = digest
                override val reviewedManifest: PersistenceManifest? = null
                override val reviewedDigest: ByteArray? = null
                override val recoveryActionDigest: ByteArray? = null
                override val itemCount: Int = 0
                override val resourceCount: Int = 0
                override val checksumValid: Boolean = true
                override val formatVersion: Int = 1
            },
        )
    }

    private class RecordingDiagnostics : DiagnosticsPort {
        private val events = mutableListOf<RunEvent>()
        override fun emit(event: RunEvent) {
            events += event
        }
        override fun snapshot(): List<RunEvent> = events.toList()
    }
}
