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
     * Allocate and persist the next sequence value.
     * Returns the new value (strictly greater than any previous).
     */
    fun next(): Long {
        val next = current + 1L
        try {
            seqFile.writeText(next.toString())
        } catch (_: Exception) {
            // Fail-open: if the sequence file write fails, we still
            // return the next value. The journal append will also fail
            // and the caller will handle it.
        }
        current = next
        return next
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
