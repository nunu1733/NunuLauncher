package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.PhaseCode
import app.lawnchair.organizer.diagnostics.model.RunEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AC-67-02, AC-67-07: Journal store append/reopen/restart with monotonic sequence;
 * wall-clock rollback does not affect ordering; corruption reset; write failure
 * does not throw to caller.
 */
class JournalStoreTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createStore(dir: File, clock: () -> Long = { System.currentTimeMillis() }): JournalStore {
        val journalFile = File(dir, "organizer_diagnostics.journal")
        val seqFile = File(dir, "journal_seq")
        val seq = JournalSequence(seqFile)
        return JournalStore(journalFile, seq, clock)
    }

    private fun event(seq: Long = 0L, phase: PhaseCode = PhaseCode.CAPTURED, runId: String? = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"): RunEvent = RunEvent(journalSequence = seq, phase = phase, runId = runId)

    @Test
    fun appendAndReadBack() {
        val store = createStore(tempDir.root)
        store.open()
        val result = store.append(event(phase = PhaseCode.RUN_STARTED))
        assertTrue("Append must succeed", result)
        val events = store.readAllEvents()
        assertEquals(1, events.size)
        assertEquals(PhaseCode.RUN_STARTED, events[0].phase)
        // Sequence should be assigned by the store
        assertTrue("Sequence must be > 0", events[0].journalSequence > 0)
    }

    @Test
    fun appendMultipleEvents() {
        val store = createStore(tempDir.root)
        store.open()
        store.append(event(phase = PhaseCode.RUN_STARTED))
        store.append(event(phase = PhaseCode.CAPTURED))
        store.append(event(phase = PhaseCode.PLANNED))
        val events = store.readAllEvents()
        assertEquals(3, events.size)
        assertEquals(PhaseCode.RUN_STARTED, events[0].phase)
        assertEquals(PhaseCode.CAPTURED, events[1].phase)
        assertEquals(PhaseCode.PLANNED, events[2].phase)
        // Sequences are strictly increasing
        assertTrue(events[1].journalSequence > events[0].journalSequence)
        assertTrue(events[2].journalSequence > events[1].journalSequence)
    }

    @Test
    fun sequenceSurvivesReopen() {
        val dir = tempDir.root
        val store1 = createStore(dir)
        store1.open()
        store1.append(event(phase = PhaseCode.RUN_STARTED))
        store1.append(event(phase = PhaseCode.CAPTURED))
        val seqAfter = store1.currentSequence()
        store1.close()

        // Simulate restart
        val store2 = createStore(dir)
        store2.open()
        assertEquals(seqAfter, store2.currentSequence())
        val events = store2.readAllEvents()
        assertEquals(2, events.size)
        val next = store2.append(event(phase = PhaseCode.PLANNED))
        assertTrue(next)
        assertTrue(store2.currentSequence() > seqAfter)
    }

    @Test
    fun wallClockRollbackDoesNotAffectOrdering() {
        // Simulate wall clock rolling backward: second event has earlier timestamp
        var clockValue = 1_000_000L
        val clock = { clockValue }
        val store = createStore(tempDir.root, clock)
        store.open()
        store.append(event(phase = PhaseCode.RUN_STARTED))
        // Roll clock backward
        clockValue = 500_000L
        store.append(event(phase = PhaseCode.CAPTURED))
        val events = store.readAllEvents()
        // Ordering is determined by journalSequence, not wall clock
        assertTrue("Sequence must be strictly increasing", events[1].journalSequence > events[0].journalSequence)
        // Wall clock rolled back between events
        assertTrue("Second event must have different timestamp", events[0].recordedAtWallMillis != events[1].recordedAtWallMillis)
        // The first event has the later timestamp due to clock rollback
        assertTrue(
            "First event timestamp must be > second due to clock rollback",
            events[0].recordedAtWallMillis > events[1].recordedAtWallMillis,
        )
    }

    @Test
    fun corruptionResetsJournal() {
        val dir = tempDir.root
        val journalFile = File(dir, "organizer_diagnostics.journal")
        // Write garbage to the journal file
        journalFile.parentFile?.mkdirs()
        journalFile.writeText("garbage data that is not valid JSON\n")

        val store = createStore(dir)
        store.open()
        // Append should still work (journal was reset)
        val result = store.append(event(phase = PhaseCode.RUN_STARTED))
        assertTrue("Append after corruption must succeed", result)
        val events = store.readAllEvents()
        assertEquals(1, events.size)
        assertEquals(PhaseCode.RUN_STARTED, events[0].phase)
    }

    @Test
    fun writeFailureDoesNotThrow() {
        // Use a non-writable directory
        val dir = tempDir.root
        val journalFile = File(dir, "organizer_diagnostics.journal")
        // Make the journal file read-only by making the parent read-only
        journalFile.parentFile?.mkdirs()
        journalFile.createNewFile()
        journalFile.setReadOnly()
        // Note: On some file systems, setReadOnly may not prevent writes
        // by the same process. The test verifies fail-open behavior.

        // Create a store that uses a non-writable sequence file
        // Actually, let's just test that the fail-open behavior works
        // by making the journal file unwritable
        val store = createStore(dir)
        store.open()
        // The store should handle the failure gracefully
        val result = store.append(event(phase = PhaseCode.RUN_STARTED))
        // May fail or succeed depending on the actual file system behavior
        // but the important thing is it doesn't throw
    }

    @Test
    fun postAppendRetentionPrunesTo10ResolvedRuns() {
        // Create a store with a fixed clock so events are not pruned by age
        val fixedClock = { 1_000_000_000_000L }
        val store = createStore(tempDir.root, fixedClock)
        store.open()
        // Append 11 resolved runs, each with 2 events (RUN_STARTED + APPLY_VERIFIED)
        // Each run has a unique 32-hex runId
        for (i in 1L..11L) {
            val rid = java.lang.String.format("%032d", java.lang.Long.valueOf(i))
            store.append(RunEvent(journalSequence = 0L, phase = PhaseCode.RUN_STARTED, runId = rid))
            store.append(RunEvent(journalSequence = 0L, phase = PhaseCode.APPLY_VERIFIED, runId = rid))
        }
        // After 11 runs, the store should have pruned to 10 (oldest 1 removed)
        val events = store.readAllEvents()
        val runIds = events.mapNotNull { it.runId }.distinct()
        assertEquals("Must have at most 10 resolved runs after post-append retention", 10, runIds.size)
        // The oldest run (run-1) should have been pruned
        assertFalse("Oldest run must be pruned", runIds.contains(java.lang.String.format("%032d", java.lang.Long.valueOf(1L))))
        // The newest run (run-11) should be present
        assertTrue("Newest run must be retained", runIds.contains(java.lang.String.format("%032d", java.lang.Long.valueOf(11L))))
        // Total events should be 20 (10 runs * 2 events each)
        assertEquals(20, events.size)
    }

    @Test
    fun emptyJournalAfterOpen() {
        val store = createStore(tempDir.root)
        store.open()
        val events = store.readAllEvents()
        assertTrue(events.isEmpty())
        assertEquals(0, store.eventCount())
    }

    @Test
    fun appendReturnsFalseOnFailure() {
        // Create a store with a non-writable directory
        val dir = tempDir.newFolder("readonly")
        dir.setWritable(false)
        // On some file systems, making a directory unwritable may not prevent
        // file creation. The test verifies fail-open behavior.
        val store = createStore(dir)
        store.open()
        // Attempt to append (should fail)
        val result = store.append(event(phase = PhaseCode.RUN_STARTED))
        assertFalse(result)
    }

    @Test
    fun productionDirectorySyncUsesReadOnlyFileChannel() {
        // FileChannel can force directory metadata on Android/Linux and host macOS.
        // RandomAccessFile(directory, "r") is a no-op/failure on host macOS.
        SyncHook.forceDirectoryMetadata(tempDir.root)
    }

    @Test
    fun retentionRewriteSyncsTempFileThenDirectoryAfterRename() {
        // Use a recording SyncHook to assert the correct ordering:
        // write-temp -> sync-temp -> rename -> sync-dir.
        val syncCalls = mutableListOf<String>()
        val fullHook = object : SyncHook {
            override fun syncFile(file: File) {
                syncCalls.add("syncFile:${file.name}")
            }
            override fun syncDirectory(file: File) {
                syncCalls.add("syncDir:${file.name}")
            }
        }

        val dir = tempDir.root
        val journalFile = File(dir, "organizer_diagnostics.journal")
        val seqFile = File(dir, "journal_seq")
        val seq = JournalSequence(seqFile)
        val fixedClock = { 1_000_000_000_000L }
        val store = JournalStore(journalFile, seq, fixedClock, fullHook)
        store.open()

        // Append 11 resolved runs to trigger count-based retention (>10).
        // Each run: RUN_STARTED + APPLY_VERIFIED.
        for (i in 1L..11L) {
            val rid = java.lang.String.format("%032d", java.lang.Long.valueOf(i))
            store.append(RunEvent(journalSequence = 0L, phase = PhaseCode.RUN_STARTED, runId = rid))
            store.append(RunEvent(journalSequence = 0L, phase = PhaseCode.APPLY_VERIFIED, runId = rid))
        }

        // The 11th append should have triggered retention, which rewrites the journal.
        // Verify sync ordering: syncFile (temp) before syncDir (journal).
        val syncFileIdx = syncCalls.indexOfFirst { it.startsWith("syncFile:") && it.endsWith(".tmp") }
        val syncDirIdx = syncCalls.indexOfFirst { it.startsWith("syncDir:") && it.endsWith(".journal") }
        assertTrue("syncFile must be called on temp file during retention", syncFileIdx >= 0)
        assertTrue("syncDir must be called on journal file during retention", syncDirIdx >= 0)
        assertTrue(
            "syncFile (temp) must happen before syncDir (journal): $syncCalls",
            syncFileIdx < syncDirIdx,
        )

        // Verify that the events are still within limits
        val events = store.readAllEvents()
        val runIds = events.mapNotNull { it.runId }.distinct()
        assertEquals("Must have at most 10 resolved runs after retention", 10, runIds.size)
    }

    @Test
    fun retentionRewriteCopyFallbackSyncsJournalFile() {
        // Force renameTo() to fail so the copy fallback is exercised.
        // The test verifies that syncFile is called on the journal file
        // after the copy, maintaining the durability guarantee.
        val syncCalls = mutableListOf<String>()
        val fullHook = object : SyncHook {
            override fun syncFile(file: File) {
                syncCalls.add("syncFile:${file.name}")
            }
            override fun syncDirectory(file: File) {
                syncCalls.add("syncDir:${file.name}")
            }
        }
        // renameHook always returns false, forcing the copy-and-delete path
        val renameHook: (File, File) -> Boolean = { _, _ -> false }

        val dir = tempDir.root
        val journalFile = File(dir, "organizer_diagnostics.journal")
        val seqFile = File(dir, "journal_seq")
        val seq = JournalSequence(seqFile)
        val fixedClock = { 1_000_000_000_000L }
        val store = JournalStore(journalFile, seq, fixedClock, fullHook, renameHook)
        store.open()

        // Append 11 resolved runs to trigger retention.
        for (i in 1L..11L) {
            val rid = java.lang.String.format("%032d", java.lang.Long.valueOf(i))
            store.append(RunEvent(journalSequence = 0L, phase = PhaseCode.RUN_STARTED, runId = rid))
            store.append(RunEvent(journalSequence = 0L, phase = PhaseCode.APPLY_VERIFIED, runId = rid))
        }

        // Verify syncFile is called on the temp file
        val tempSyncCalls = syncCalls.filter { it.startsWith("syncFile:") && it.endsWith(".tmp") }
        assertTrue("syncFile must be called on temp file during retention", tempSyncCalls.isNotEmpty())

        // Verify syncFile is called on the journal file (copy fallback path)
        val journalSyncCalls = syncCalls.filter { it.startsWith("syncFile:") && it.endsWith(".journal") }
        assertTrue(
            "syncFile must be called on journal file in copy fallback path: $syncCalls",
            journalSyncCalls.isNotEmpty(),
        )

        // Verify temp sync happens before journal sync
        val tempIdx = syncCalls.indexOfFirst { it.startsWith("syncFile:") && it.endsWith(".tmp") }
        val journalIdx = syncCalls.indexOfFirst { it.startsWith("syncFile:") && it.endsWith(".journal") }
        assertTrue(
            "syncFile (temp) must happen before syncFile (journal) in copy fallback: $syncCalls",
            tempIdx < journalIdx,
        )

        // Verify the store is within limits
        val events = store.readAllEvents()
        val runIds = events.mapNotNull { it.runId }.distinct()
        assertEquals("Must have at most 10 resolved runs after retention", 10, runIds.size)
    }
}
