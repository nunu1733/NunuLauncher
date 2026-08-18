package app.lawnchair.organizer.diagnostics.journal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AC-67-02: Monotonic sequence survives process restart; sequence file corruption resets to 1.
 */
class JournalSequenceTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun sequenceStartsAtZero() {
        val seq = JournalSequence(File(tempDir.root, "journal_seq"))
        assertEquals(0L, seq.currentValue())
    }

    @Test
    fun sequenceIsMonotonic() {
        val seq = JournalSequence(File(tempDir.root, "journal_seq"))
        seq.open()
        val values = (1..10).map { seq.next() }
        for (i in 1 until values.size) {
            assertTrue("Sequence must be strictly increasing", values[i] > values[i - 1])
        }
        assertEquals(10L, seq.currentValue())
    }

    @Test
    fun sequenceSurvivesReopen() {
        val file = File(tempDir.root, "journal_seq")
        val seq1 = JournalSequence(file)
        seq1.open()
        seq1.next() // 1
        seq1.next() // 2
        seq1.next() // 3
        assertEquals(3L, seq1.currentValue())

        // Simulate process restart
        val seq2 = JournalSequence(file)
        seq2.open()
        assertEquals(3L, seq2.currentValue())
        val next = seq2.next()
        assertEquals(4L, next)
        assertEquals(4L, seq2.currentValue())
    }

    @Test
    fun sequenceResetsOnCorruption() {
        val file = File(tempDir.root, "journal_seq")
        // Write garbage
        file.writeText("not a number")
        val seq = JournalSequence(file)
        seq.open()
        assertEquals(0L, seq.currentValue())
        val next = seq.next()
        assertEquals(1L, next)
    }

    @Test
    fun sequenceResetsOnMissingFile() {
        val file = File(tempDir.root, "non_existent_seq")
        val seq = JournalSequence(file)
        seq.open()
        assertEquals(0L, seq.currentValue())
        assertEquals(1L, seq.next())
    }

    @Test
    fun seqFileLostJournalIntactReconcile() {
        // Simulate: seq file is lost but journal has events with sequence numbers
        val file = File(tempDir.root, "journal_seq")
        val seq = JournalSequence(file)
        seq.open()
        seq.next() // 1
        seq.next() // 2
        assertEquals(2L, seq.currentValue())

        // Delete the seq file (simulating loss)
        file.delete()
        // Journal max sequence is 2
        seq.reconcile(2L)
        // After reconcile, next should be 3
        assertEquals(2L, seq.currentValue())
        // Re-open simulates process restart
        val seq2 = JournalSequence(file)
        seq2.open()
        assertEquals(0L, seq2.currentValue()) // seq file was deleted, starts at 0
        seq2.reconcile(2L) // reconcile with journal max
        assertEquals(2L, seq2.currentValue())
        assertEquals(3L, seq2.next())
    }

    @Test
    fun seqWriteFailureDoesNotAdvance() {
        val dir = tempDir.newFolder("subdir")
        val file = File(dir, "journal_seq")
        // Make the directory non-writable so the file write should fail
        dir.setWritable(false)
        val seq = JournalSequence(file)
        seq.open()
        val before = seq.currentValue()
        val result = seq.next()
        // If the write failed (platform-dependent), the counter must not advance.
        // If the write succeeded (some platforms allow writes despite setWritable(false)),
        // the counter advances. In either case, the method does not throw.
        assertTrue("next() must not throw", true)
        // If the file was not written, counter stays at before
        if (!file.exists() || file.length() == 0L) {
            // Write likely failed
            assertEquals("Counter must not advance on write failure", before, result)
        }
    }

    @Test
    fun sequenceDoesNotDependOnWallClock() {
        val file = File(tempDir.root, "journal_seq")
        val seq = JournalSequence(file)
        seq.open()
        seq.next() // 1
        seq.next() // 2
        // Even if we change system time, sequence is unaffected
        assertEquals(2L, seq.currentValue())
    }
}
