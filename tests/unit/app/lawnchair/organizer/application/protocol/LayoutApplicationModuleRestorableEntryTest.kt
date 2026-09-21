package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.adapter.FakeClock
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.adapter.FakeRecoveryStore
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RecoveryPreviewConfirmation
import app.lawnchair.organizer.application.public.RecoveryPreviewResult
import app.lawnchair.organizer.application.public.RecoveryRejection
import app.lawnchair.organizer.application.public.RecoveryResult
import app.lawnchair.organizer.application.public.RemainingWindow.HoursRemaining
import app.lawnchair.organizer.application.public.RemainingWindow.LessThanOneHour
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.diagnostics.DiagnosticsPort
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.planning.RevisionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #376 (spec D1/D4/RS-AC-02/RS-AC-03): the application-module restore
 * entry read and the one-shot confirmation registry's ownership boundary.
 *
 * The registry (`pendingPreviewConfirmations`) is an instance field of
 * [LayoutApplicationModule]: a **fresh module** — the structural surrogate
 * for a process death — cannot consume an old token, while a re-created
 * *coordinator* would not clear it (that contrast is what makes a correct
 * test; see the spec's D4).
 */
class LayoutApplicationModuleRestorableEntryTest {

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
            folderTitleResolver = app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
            diagnosticsPort = diagnostics,
        )
        module.reconcileAtStart()
        seedVerifiedRecord()
    }

    @Test
    fun entryReadSelectsTheVerifiedPointWithACoarseWindow() {
        val entry = module.readRestorableRecoveryEntry()

        assertEquals(pointId, entry?.pointId)
        // createdAt=1000, retention 24h → 24 whole hours remain.
        assertEquals(HoursRemaining(24), entry?.remainingWindow)
    }

    @Test
    fun entryReadIsZeroWriteAndSilent() {
        val before = writer.capturedSnapshots

        module.readRestorableRecoveryEntry()

        assertEquals(before, writer.capturedSnapshots)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, writer.reloadCount)
        assertEquals(0, store.markRestoringCalls)
        assertEquals(emptyList<RunEvent>(), diagnostics.snapshot())
    }

    @Test
    fun entryReadFailsClosedWhenStartupReconciliationHasFailed() {
        module.failStartupReconciliation()

        assertNull(module.readRestorableRecoveryEntry())
    }

    @Test
    fun entryReadFloorsTheRemainingWindowBelowOneHour() {
        FakeClock.advance(RetentionPolicy.RETENTION_MILLIS - 1_800_000L)

        val entry = module.readRestorableRecoveryEntry()

        assertEquals(LessThanOneHour, entry?.remainingWindow)
    }

    @Test
    fun entryReadIsNullOnceRetentionLapses() {
        FakeClock.advance(RetentionPolicy.RETENTION_MILLIS)

        assertNull(module.readRestorableRecoveryEntry())
    }

    @Test
    fun freshModuleInstanceCannotConsumeTheOldConfirmationToken() {
        // Issue #376 (RS-AC-03): the token registry is owned by the module
        // instance, so a fresh module — the structural surrogate for a process
        // death — rejects the dead process's token as MISSING. A fresh store
        // is part of the fresh module (each store binds to exactly one
        // module's reconciliation mutex); the MISSING rejection happens in
        // the registry consume, before any store access.
        val preview = module.inspectRecovery(pointId)
        assertTrue(preview is RecoveryPreviewResult.Restorable)
        val confirmation = (preview as RecoveryPreviewResult.Restorable).confirmation

        val freshWriter = FakeLayoutWriter(CanonicalFixtures.state(items = listOf(CanonicalFixtures.appItem())))
        val freshStore = FakeRecoveryStore { FakeClock.nowMillis() }
        val freshModule = LayoutApplicationModule(
            writer = freshWriter,
            store = freshStore,
            clock = FakeClock,
            operationIds = FixedOperationIdSource(),
            folderTitleResolver = app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver(),
            diagnosticsPort = diagnostics,
        )
        freshModule.reconcileAtStart()

        val result = freshModule.confirmRecoveryPreview(pointId, confirmation)
        assertEquals(RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING), result)
        assertEquals(0, writer.appliedWriteSets)
        assertEquals(0, freshWriter.appliedWriteSets)
    }

    @Test
    fun sameModuleInstanceStillConsumesTheTokenUntilItIsSpent() {
        // The contrast that proves the boundary above: within one module
        // instance the token stays consumable — a re-created coordinator
        // alone would never produce this registry loss.
        val preview = module.inspectRecovery(pointId)
        assertTrue(preview is RecoveryPreviewResult.Restorable)
        val confirmation = (preview as RecoveryPreviewResult.Restorable).confirmation

        val first = module.confirmRecoveryPreview(pointId, confirmation)
        val second = module.confirmRecoveryPreview(pointId, confirmation)

        assertTrue(first !is RecoveryResult.NotRestorable || first.reason != RecoveryRejection.MISSING)
        assertEquals(RecoveryResult.NotRestorable(pointId, RecoveryRejection.MISSING), second)
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
                override val pointId: RecoveryPointId get() = this@LayoutApplicationModuleRestorableEntryTest.pointId
                override val runId: RunId get() = this@LayoutApplicationModuleRestorableEntryTest.runId
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
                override val formatVersion: Int = LifecycleReconciler.SUPPORTED_FORMAT
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
