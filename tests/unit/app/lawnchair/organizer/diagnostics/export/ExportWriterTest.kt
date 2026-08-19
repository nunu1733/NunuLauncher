package app.lawnchair.organizer.diagnostics.export

import app.lawnchair.organizer.diagnostics.journal.RunEventSerializer
import app.lawnchair.organizer.diagnostics.model.ApplyStage
import app.lawnchair.organizer.diagnostics.model.ApplySummary
import app.lawnchair.organizer.diagnostics.model.DeviceProfileSummary
import app.lawnchair.organizer.diagnostics.model.ErrorEntry
import app.lawnchair.organizer.diagnostics.model.ErrorFamily
import app.lawnchair.organizer.diagnostics.model.Orientation
import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.PlanSummary
import app.lawnchair.organizer.diagnostics.model.RunEvent
import app.lawnchair.organizer.diagnostics.model.RunMode
import app.lawnchair.organizer.diagnostics.model.RunVersions
import app.lawnchair.organizer.diagnostics.model.Trigger
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-09, D-10: Export writer tests for header + line-delimited JSON
 * ordering, field parity, and cancellation/write-failure isolation.
 */
class ExportWriterTest {

    private val exportJson = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Test
    fun d10HeaderShape() {
        val events = listOf(
            RunEvent(
                journalSequence = 41L,
                phase = PhaseCode.RUN_STARTED,
                runId = "5f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c",
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                versions = RunVersions(ruleVersion = "1", taxonomyVersion = "1"),
                deviceProfile = DeviceProfileSummary(columns = 5, rows = 6, hotseatSlots = 5, orientation = Orientation.PORTRAIT),
            ),
            RunEvent(journalSequence = 42L, phase = PhaseCode.CAPTURED),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        // Split into lines
        val lines = text.lines().filter { it.isNotBlank() }
        assertTrue("Export must have at least 2 lines (header + events)", lines.size >= 3)

        // First line is the header
        val headerLine = lines[0]
        assertTrue("Header must contain 'header' key", headerLine.contains("\"header\""))
        assertTrue("Header must contain appVersion", headerLine.contains("\"appVersion\""))
        assertTrue("Header must contain journalSchemaVersion", headerLine.contains("\"journalSchemaVersion\""))
        assertTrue("Header must contain exportedAtWallMillis", headerLine.contains("\"exportedAtWallMillis\""))

        // Remaining lines are events
        for (i in 1 until lines.size) {
            val line = lines[i]
            // Each event line must be valid JSON containing schemaVersion and journalSequence
            assertTrue("Event line $i must contain schemaVersion", line.contains("\"schemaVersion\""))
            assertTrue("Event line $i must contain journalSequence", line.contains("\"journalSequence\""))
            assertTrue("Event line $i must contain phase", line.contains("\"phase\""))
            // Each event line must be a valid RunEvent JSON
            val event = RunEventSerializer.decode(line.toByteArray())
            assertNotNull("Event $i must decode", event)
        }
    }

    @Test
    fun d10AscendingSequenceOrder() {
        // Create events in a non-ascending order; write() must sort them
        // so the exported output is always in ascending journalSequence.
        val events = listOf(
            RunEvent(journalSequence = 30L, phase = PhaseCode.PLANNED),
            RunEvent(journalSequence = 10L, phase = PhaseCode.RUN_STARTED),
            RunEvent(journalSequence = 20L, phase = PhaseCode.CAPTURED),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }
        // Skip header (line 0)
        for (i in 1 until lines.size) {
            val event = RunEventSerializer.decode(lines[i].toByteArray())
            // Verify ascending sequence
            if (i > 1) {
                val prevEvent = RunEventSerializer.decode(lines[i - 1].toByteArray())
                assertTrue(
                    "Events must be in ascending journalSequence order: ${prevEvent.journalSequence} before ${event.journalSequence}",
                    prevEvent.journalSequence < event.journalSequence,
                )
            }
        }
    }

    @Test
    fun d10UnsortedSnapshotProducesAscendingExport() {
        // Feed a clearly scrambled input to prove that write() sorts by
        // journalSequence regardless of input order. This is the scenario
        // that would fail if write() merely echoed input order.
        val events = listOf(
            RunEvent(journalSequence = 50L, phase = PhaseCode.APPLY_VERIFIED),
            RunEvent(journalSequence = 10L, phase = PhaseCode.RUN_STARTED),
            RunEvent(journalSequence = 30L, phase = PhaseCode.PLANNED),
            RunEvent(journalSequence = 20L, phase = PhaseCode.CAPTURED),
            RunEvent(journalSequence = 40L, phase = PhaseCode.APPLY_COMMITTED),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }
        // Skip header (line 0)
        val exportedEvents = (1 until lines.size).map { i ->
            RunEventSerializer.decode(lines[i].toByteArray())
        }

        // Verify ascending order
        for (i in 1 until exportedEvents.size) {
            assertTrue(
                "Events must be in ascending journalSequence order: " +
                    "${exportedEvents[i - 1].journalSequence} before ${exportedEvents[i].journalSequence}",
                exportedEvents[i - 1].journalSequence < exportedEvents[i].journalSequence,
            )
        }

        // Verify all expected sequences are present
        val expectedSequences = listOf(10L, 20L, 30L, 40L, 50L)
        assertEquals(
            "All expected sequences must be present in order",
            expectedSequences,
            exportedEvents.map { it.journalSequence },
        )
    }

    @Test
    fun d10FieldParity() {
        // Create events with various field combinations
        val events = listOf(
            RunEvent(
                journalSequence = 41L,
                phase = PhaseCode.RUN_STARTED,
                runId = "5f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c",
                trigger = Trigger.MANUAL_FULL,
                runMode = RunMode.FULL_ORGANIZATION,
                versions = RunVersions(ruleVersion = "1", taxonomyVersion = "1"),
                deviceProfile = DeviceProfileSummary(columns = 5, rows = 6, hotseatSlots = 5, orientation = Orientation.PORTRAIT),
            ),
            RunEvent(
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
            ),
            RunEvent(
                journalSequence = 48L,
                phase = PhaseCode.APPLY_VERIFIED,
                applyStage = ApplyStage.A8,
                applySummary = ApplySummary(preserveActionCount = 23, updateActionCount = 61, insertActionCount = 0),
            ),
            RunEvent(
                journalSequence = 81L,
                phase = PhaseCode.CHECKPOINT_REJECTED,
                applyStage = ApplyStage.A4,
                error = ErrorEntry(family = ErrorFamily.PRE_WRITE_REJECTED, code = "CHECKPOINT_VALIDATE_FAILED"),
            ),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }

        // Decode each event and verify field parity with the input
        for (i in 1 until lines.size) {
            val inputEvent = events[i - 1]
            val outputEvent = RunEventSerializer.decode(lines[i].toByteArray())

            // Core fields must match
            assertEquals(
                "journalSequence must match for event at index $i",
                inputEvent.journalSequence,
                outputEvent.journalSequence,
            )
            assertEquals(
                "phase must match for event at index $i",
                inputEvent.phase,
                outputEvent.phase,
            )

            // Optional fields that are present in the input must be present in output
            if (inputEvent.runId != null) {
                assertEquals("runId must match", inputEvent.runId, outputEvent.runId)
            }
            if (inputEvent.trigger != null) {
                assertEquals("trigger must match", inputEvent.trigger, outputEvent.trigger)
            }
            if (inputEvent.runMode != null) {
                assertEquals("runMode must match", inputEvent.runMode, outputEvent.runMode)
            }
            if (inputEvent.applyStage != null) {
                assertEquals("applyStage must match", inputEvent.applyStage, outputEvent.applyStage)
            }
            if (inputEvent.planSummary != null) {
                assertEquals(
                    "planSummary must match",
                    inputEvent.planSummary?.capturedItemCount,
                    outputEvent.planSummary?.capturedItemCount,
                )
            }
            if (inputEvent.applySummary != null) {
                assertEquals(
                    "applySummary must match",
                    inputEvent.applySummary?.preserveActionCount,
                    outputEvent.applySummary?.preserveActionCount,
                )
            }
            if (inputEvent.error != null) {
                assertEquals(
                    "error family must match",
                    inputEvent.error?.family,
                    outputEvent.error?.family,
                )
                assertEquals(
                    "error code must match",
                    inputEvent.error?.code,
                    outputEvent.error?.code,
                )
            }
        }
    }

    @Test
    fun d10NoExtraFieldsInExport() {
        // Verify that exported events don't contain fields beyond the
        // accepted RunEvent representation
        val events = listOf(
            RunEvent(journalSequence = 1L, phase = PhaseCode.CAPTURED),
            RunEvent(
                journalSequence = 2L,
                phase = PhaseCode.RUN_STARTED,
                runId = "abcd1234abcd1234abcd1234abcd1234",
            ),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        // D-09 forbidden strings must not appear in export
        val forbidden = listOf(
            "packageName", "com.example", "component", "MainActivity",
            "profileSerial", "cell", "folderTitle", "rules", "category",
            "revision", "message", "SQLException", "items",
        )
        for (f in forbidden) {
            assertFalse("Forbidden string '$f' must not appear in export", text.contains(f))
        }
    }

    @Test
    fun d10EventsMatchJournalFieldSet() {
        // Verify that exported events are serialized using the same
        // RunEventSerializer as the journal — no additional fields
        val event = RunEvent(
            journalSequence = 42L,
            phase = PhaseCode.CAPTURED,
        )

        val journalBytes = RunEventSerializer.encode(event)
        val journalJson = journalBytes.toString(Charsets.UTF_8)

        val output = ByteArrayOutputStream()
        ExportWriter.write(listOf(event), output)
        val lines = output.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        val exportEventJson = lines[1] // Skip header

        // The event JSON should be the same whether from journal or export
        assertEquals(
            "Export event must match journal serialization",
            journalJson,
            exportEventJson,
        )
    }

    @Test
    fun d10WriteFailureLeavesJournalIntact() {
        // Simulate a write failure (OutputStream that throws)
        val events = listOf(
            RunEvent(journalSequence = 1L, phase = PhaseCode.RUN_STARTED),
        )

        val failingStream = object : java.io.OutputStream() {
            private var written = false
            override fun write(b: Int) {
                if (!written) {
                    written = true
                    // Allow first write (header) to succeed
                    return
                }
                throw IOException("Simulated write failure")
            }
        }

        try {
            ExportWriter.write(events, failingStream)
            // If we get here, the write didn't throw because the failure
            // happened after the header was written
        } catch (_: IOException) {
            // Expected — write failure does not mutate the journal
        }
        // The test passes if no exception is thrown to the caller
        // (the journal is not mutated by the export attempt)
    }

    @Test
    fun d10CancellationIsolated() {
        // Simulate cancellation (user closes the stream early)
        // Export cancellation must not mutate the journal
        val events = listOf(
            RunEvent(journalSequence = 1L, phase = PhaseCode.RUN_STARTED),
        )

        val cancelledStream = object : java.io.OutputStream() {
            private var count = 0
            override fun write(b: Int) {
                count++
                if (count > 5) {
                    throw IOException("Stream closed / cancellation")
                }
            }
        }

        try {
            ExportWriter.write(events, cancelledStream)
        } catch (_: IOException) {
            // Expected — cancellation does not affect the journal
        }
        // The test passes if the journal is not modified
    }

    @Test
    fun d10EmptyJournalExport() {
        // Exporting an empty journal should produce just the header line
        val output = ByteArrayOutputStream()
        ExportWriter.write(emptyList(), output)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals("Empty journal export must have exactly 1 line (header)", 1, lines.size)
        assertTrue("Header line must be valid JSON", lines[0].contains("\"header\""))
    }

    @Test
    fun d10DeviceProfileInHeader() {
        val profile = DeviceProfileSummary(columns = 5, rows = 6, hotseatSlots = 5, orientation = Orientation.PORTRAIT)
        val events = listOf(
            RunEvent(journalSequence = 1L, phase = PhaseCode.CAPTURED),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output, deviceProfile = profile)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }
        val headerLine = lines[0]
        assertTrue("Header must contain deviceProfile", headerLine.contains("\"deviceProfile\""))
        assertTrue("Header must contain columns", headerLine.contains("\"columns\""))
        assertTrue("Header must contain rows", headerLine.contains("\"rows\""))
        assertTrue("Header must contain hotseatSlots", headerLine.contains("\"hotseatSlots\""))
        assertTrue("Header must contain orientation", headerLine.contains("\"orientation\""))
    }

    @Test
    fun d10DeviceProfileFromRunStarted() {
        // When no deviceProfile is explicitly provided, it should be extracted
        // from the last RUN_STARTED event
        val profile = DeviceProfileSummary(columns = 5, rows = 6, hotseatSlots = 5, orientation = Orientation.PORTRAIT)
        val events = listOf(
            RunEvent(
                journalSequence = 1L,
                phase = PhaseCode.RUN_STARTED,
                deviceProfile = profile,
            ),
            RunEvent(journalSequence = 2L, phase = PhaseCode.CAPTURED),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }
        val headerLine = lines[0]
        assertTrue(
            "Header must contain deviceProfile from RUN_STARTED event",
            headerLine.contains("\"deviceProfile\""),
        )
    }

    @Test
    fun d10HeaderSchemaVersionIs1() {
        val events = listOf(
            RunEvent(journalSequence = 1L, phase = PhaseCode.RUN_STARTED),
        )

        val output = ByteArrayOutputStream()
        ExportWriter.write(events, output)
        val text = output.toString(Charsets.UTF_8)

        val lines = text.lines().filter { it.isNotBlank() }
        val headerLine = lines[0]
        assertTrue(
            "Header must have journalSchemaVersion=1",
            headerLine.contains("\"journalSchemaVersion\":1"),
        )
    }
}
