package app.lawnchair.organizer.diagnostics.model

import app.lawnchair.organizer.diagnostics.journal.RunEventSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-01, D-01–D-10: Serialization round-trip and field closure.
 */
class RunEventSerializationTest {

    @Test
    fun roundTripRunStarted() {
        val event = RunEvent(
            journalSequence = 41L,
            phase = PhaseCode.RUN_STARTED,
            runId = "5f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c",
            trigger = Trigger.MANUAL_FULL,
            runMode = RunMode.FULL_ORGANIZATION,
            versions = RunVersions(ruleVersion = "1", taxonomyVersion = "1"),
            deviceProfile = DeviceProfileSummary(columns = 5, rows = 6, hotseatSlots = 5, orientation = "PORTRAIT"),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.journalSequence, decoded.journalSequence)
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.runId, decoded.runId)
        assertEquals(event.trigger, decoded.trigger)
        assertEquals(event.runMode, decoded.runMode)
        assertEquals(event.versions?.ruleVersion, decoded.versions?.ruleVersion)
        assertEquals(event.deviceProfile?.columns, decoded.deviceProfile?.columns)
    }

    @Test
    fun roundTripPlanned() {
        val event = RunEvent(
            journalSequence = 43L,
            phase = PhaseCode.PLANNED,
            planSummary = PlanSummary(
                capturedItemCount = 84,
                candidateItemCount = 0,
                movedCount = 61,
                preservedCount = 23,
                preservedByReason = mapOf("DOCK" to 5, "WIDGET" to 4, "LOCKED" to 3, "NON_TARGET" to 11),
                newFolderCount = 9,
                newPageCount = 1,
                unplacedCount = 0,
                warningByCode = emptyMap(),
                confidenceCounts = mapOf("EXPLICIT" to 2, "RULE" to 55, "FALLBACK" to 4),
            ),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.journalSequence, decoded.journalSequence)
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.planSummary?.capturedItemCount, decoded.planSummary?.capturedItemCount)
        assertEquals(event.planSummary?.movedCount, decoded.planSummary?.movedCount)
        assertEquals(event.planSummary?.preservedByReason, decoded.planSummary?.preservedByReason)
        assertEquals(event.planSummary?.confidenceCounts, decoded.planSummary?.confidenceCounts)
    }

    @Test
    fun roundTripCheckpointRejected() {
        val event = RunEvent(
            journalSequence = 81L,
            phase = PhaseCode.CHECKPOINT_REJECTED,
            applyStage = ApplyStage.A4,
            error = ErrorEntry(family = ErrorFamily.PRE_WRITE_REJECTED, code = "CHECKPOINT_VALIDATE_FAILED"),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.journalSequence, decoded.journalSequence)
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.applyStage, decoded.applyStage)
        assertEquals(event.error?.family, decoded.error?.family)
        assertEquals(event.error?.code, decoded.error?.code)
    }

    @Test
    fun roundTripRolledBack() {
        val event = RunEvent(
            journalSequence = 91L,
            phase = PhaseCode.APPLY_ROLLED_BACK,
            applyStage = ApplyStage.A6,
            error = ErrorEntry(family = ErrorFamily.APPLY_FAILURE, code = "WRITE_FAILED"),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.journalSequence, decoded.journalSequence)
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.applyStage, decoded.applyStage)
        assertEquals(event.error?.code, decoded.error?.code)
    }

    @Test
    fun roundTripRestartReconciled() {
        val event = RunEvent(
            journalSequence = 102L,
            phase = PhaseCode.RESTART_RECONCILED,
            runId = "ab311234567890abcdef1234567890ab",
            reconciliation = ReconciliationContext(
                subjectRunId = "ab311234567890abcdef1234567890ab",
                priorLifecycle = RecoveryLifecycle.COMMITTED_UNVERIFIED,
                classification = ReconciliationClassification.INTENDED_POST_STATE,
                resultingLifecycle = RecoveryLifecycle.VERIFIED,
            ),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.journalSequence, decoded.journalSequence)
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.reconciliation?.subjectRunId, decoded.reconciliation?.subjectRunId)
        assertEquals(event.reconciliation?.priorLifecycle, decoded.reconciliation?.priorLifecycle)
        assertEquals(event.reconciliation?.classification, decoded.reconciliation?.classification)
        assertEquals(event.reconciliation?.resultingLifecycle, decoded.reconciliation?.resultingLifecycle)
    }

    @Test
    fun roundTripRecovery() {
        val event = RunEvent(
            journalSequence = 111L,
            phase = PhaseCode.RECOVERY_REQUESTED,
            pointId = "9c2e1234567890abcdef1234567890ab",
            recovery = RecoveryContext(
                pointId = "9c2e1234567890abcdef1234567890ab",
                pointOriginRunId = "5f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c",
            ),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.journalSequence, decoded.journalSequence)
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.recovery?.pointId, decoded.recovery?.pointId)
        assertEquals(event.recovery?.pointOriginRunId, decoded.recovery?.pointOriginRunId)
    }

    @Test
    fun roundTripPlannedWithWarning() {
        val event = RunEvent(
            journalSequence = 52L,
            phase = PhaseCode.PLANNING_IMPOSSIBLE,
            planSummary = PlanSummary(
                capturedItemCount = 10,
                candidateItemCount = 1,
                movedCount = 0,
                preservedCount = 10,
                preservedByReason = mapOf("STRUCTURAL" to 10),
                newFolderCount = 0,
                newPageCount = 0,
                unplacedCount = 1,
                unplacedByReason = mapOf("EXCEEDS_GRID_DIMENSIONS" to 1),
                warningByCode = emptyMap(),
                confidenceCounts = mapOf("RULE" to 10),
            ),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.planSummary?.unplacedCount, decoded.planSummary?.unplacedCount)
        assertEquals(event.planSummary?.unplacedByReason, decoded.planSummary?.unplacedByReason)
    }

    @Test
    fun roundTripApplyVerified() {
        val event = RunEvent(
            journalSequence = 48L,
            phase = PhaseCode.APPLY_VERIFIED,
            applyStage = ApplyStage.A8,
            applySummary = ApplySummary(preserveActionCount = 23, updateActionCount = 61, insertActionCount = 0),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.applyStage, decoded.applyStage)
        assertEquals(event.applySummary?.preserveActionCount, decoded.applySummary?.preserveActionCount)
        assertEquals(event.applySummary?.updateActionCount, decoded.applySummary?.updateActionCount)
    }

    @Test
    fun roundTripPlannedWithError() {
        val event = RunEvent(
            journalSequence = 51L,
            phase = PhaseCode.PLANNING_REJECTED,
            error = ErrorEntry(family = ErrorFamily.PLANNING_INVALID, code = "BOUNDS_VIOLATION", reasonTotal = 1),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.error?.family, decoded.error?.family)
        assertEquals(event.error?.code, decoded.error?.code)
        assertEquals(event.error?.reasonTotal, decoded.error?.reasonTotal)
    }

    @Test
    fun roundTripStaleRejection() {
        val event = RunEvent(
            journalSequence = 61L,
            phase = PhaseCode.APPLY_REJECTED,
            applyStage = ApplyStage.A2,
            error = ErrorEntry(family = ErrorFamily.PRE_WRITE_REJECTED, code = "STALE_REVISION"),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.applyStage, decoded.applyStage)
        assertEquals(event.error?.code, decoded.error?.code)
    }

    @Test
    fun roundTripExactPreconditionFailed() {
        val event = RunEvent(
            journalSequence = 71L,
            phase = PhaseCode.APPLY_REJECTED,
            applyStage = ApplyStage.A5,
            error = ErrorEntry(family = ErrorFamily.PRE_WRITE_REJECTED, code = "EXACT_PRECONDITION_FAILED"),
        )
        val json = RunEventSerializer.encodeToString(event)
        val decoded = RunEventSerializer.decode(json.toByteArray())
        assertEquals(event.phase, decoded.phase)
        assertEquals(event.applyStage, decoded.applyStage)
        assertEquals(event.error?.code, decoded.error?.code)
    }

    /** D-09: negative fixture — forbidden strings must not appear in serialized output. */
    @Test
    fun negativeFixtureDataAbsent() {
        val event = RunEvent(
            journalSequence = 999L,
            phase = PhaseCode.RUN_STARTED,
            runId = null,
        )
        val json = RunEventSerializer.encodeToString(event)
        // D-09 forbidden strings: package, component, profile, cell, folder, rule, category, revision, message, items
        val forbidden = listOf(
            "packageName",
            "com.example",
            "component",
            "MainActivity",
            "profileSerial",
            "cell",
            "folderTitle",
            "rules",
            "category",
            "revision",
            "message",
            "SQLException",
            "items",
        )
        for (f in forbidden) {
            assertFalse("Forbidden string '$f' must not appear in serialized JSON", json.contains(f))
        }
    }

    /** D-09: Check that journal-serialized and export bytes don't contain forbidden strings. */
    @Test
    fun negativeFixtureJournalBytes() {
        val event = RunEvent(
            journalSequence = 1000L,
            phase = PhaseCode.CHECKPOINTED,
            pointId = "9c2e1234567890abcdef1234567890ab",
        )
        val bytes = RunEventSerializer.encode(event)
        val text = bytes.toString(Charsets.UTF_8)
        // Verify no forbidden patterns
        assertFalse(text.contains("packageName"))
        assertFalse(text.contains("component"))
        assertFalse(text.contains("profileSerial"))
        assertFalse(text.contains("revision"))
        assertFalse(text.contains("message"))
    }

    /** Schema version is always 1. */
    @Test
    fun schemaVersionIsAlways1() {
        val event = RunEvent(
            journalSequence = 1L,
            phase = PhaseCode.CAPTURED,
        )
        val json = RunEventSerializer.encodeToString(event)
        assertTrue(json.contains("\"schemaVersion\":1"))
    }

    /** No free-form string fields exist. */
    @Test
    fun noFreeFormTextField() {
        // RunEvent has no "message" or "notes" or "description" field
        val fields = RunEvent::class.java.declaredFields.map { it.name }
        assertFalse("RunEvent must not have a 'message' field", fields.contains("message"))
        assertFalse("RunEvent must not have a 'notes' field", fields.contains("notes"))
        assertFalse("RunEvent must not have a 'description' field", fields.contains("description"))
        assertFalse("RunEvent must not have a 'debug' field", fields.contains("debug"))
    }
}
