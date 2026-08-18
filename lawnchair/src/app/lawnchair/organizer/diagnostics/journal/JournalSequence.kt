package app.lawnchair.organizer.diagnostics.journal

import java.io.File

/**
 * Durable strictly-monotonic journal sequence.
 *
 * The sequence value is persisted in a separate small file so that
 * corruption of the journal file does not affect the sequence and
 * vice versa. On corruption of the sequence file (or missing file),
 * the sequence resets to 1.
 *
 * The sequence does NOT depend on wall clock — it is a pure
 * incrementing counter.
 */
class JournalSequence(
    private val seqFile: File,
) {
    private var current: Long = 0L

    /** Current sequence value (0 before first [open]). */
    fun currentValue(): Long = current

    /** Open (or create) the sequence file and read the last value. */
    fun open() {
        current = if (seqFile.exists()) {
            try {
                seqFile.readText().trim().toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
    }

    /**
     * Reconcile the sequence value with a known high-water mark from the
     * journal (e.g. the max journalSequence among retained events). This
     * ensures the sequence is never lower than what was actually written.
     */
    fun reconcile(journalMaxSequence: Long) {
        if (journalMaxSequence > current) {
            current = journalMaxSequence
            try {
                seqFile.writeText(current.toString())
            } catch (_: Exception) {
                // Fail-open
            }
        }
    }

    /**
     * Allocate and persist the next sequence value.
     * Returns the new value on success, or null on write failure (so the
     * caller can abort the append rather than persisting a duplicate seq).
     */
    fun next(): Long? {
        val next = current + 1L
        try {
            seqFile.writeText(next.toString())
            current = next
            return next
        } catch (_: Exception) {
            // Write failure: do NOT advance the counter, return null so
            // the caller (JournalStore.append) can fail the append.
            return null
        }
    }

    /** Reset the sequence to 1 (e.g. after journal corruption). */
    fun reset() {
        current = 0L
        try {
            seqFile.writeText("0")
        } catch (_: Exception) {
            // Fail-open
        }
    }
}
