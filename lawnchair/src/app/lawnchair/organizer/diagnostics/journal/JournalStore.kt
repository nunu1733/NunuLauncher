package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.RunEvent
import java.io.File
import java.io.RandomAccessFile

/**
 * Append-only journal store for diagnostic RunEvents.
 *
 * The journal is an app-private file with line-delimited JSON records
 * (one event per line). The journal file is:
 * - Opened on first append (lazy open).
 * - Append-only: new events are written to the end.
 * - Read-back: the entire journal is re-read and parsed into memory.
 * - Retention: lazy pruning on append using [RetentionPolicy].
 * - Corruption: if the journal file is unparseable, it is reset
 *   (backed up and replaced with an empty journal). The sequence
 *   file is NOT reset — only the journal.
 *
 * Design:
 * - Each event is serialized as a single JSON line.
 * - Lines are separated by '\n'.
 * - The journal is fsynced after each append for durability.
 * - Retention is evaluated before each append and prunes eligible
 *   runs from the oldest eligible first.
 *
 * Thread safety: NOT thread-safe. Caller must ensure single-threaded
 * access at the organizer level.
 */
class JournalStore(
    private val journalFile: File,
    private val sequence: JournalSequence,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var opened: Boolean = false
    private var raf: RandomAccessFile? = null
    private var cachedEvents: List<RunEvent> = emptyList()
    private var eventByteSizes: MutableMap<Long, Long> = mutableMapOf()

    /** Open the journal store. Must be called before any append/read. */
    fun open() {
        if (opened) return
        try {
            sequence.open()
            if (!journalFile.exists()) {
                journalFile.parentFile?.mkdirs()
                journalFile.createNewFile()
            }
            // Read back existing events
            reloadEvents()
            opened = true
        } catch (_: Exception) {
            // Fail-open: if open fails, the store is still usable
            // (append will try again and handle the failure)
            opened = true // Mark as opened so append doesn't try again
        }
    }

    /** Close the journal store. */
    fun close() {
        if (!opened) return
        try {
            raf?.close()
        } catch (_: Exception) {
            // Fail-open
        }
        raf = null
        opened = false
    }

    /**
     * Append an event to the journal.
     *
     * @return true if the event was successfully persisted, false on failure.
     */
    fun append(event: RunEvent): Boolean {
        if (!opened) open()
        return try {
            // Run retention before appending (lazy evaluation)
            runRetention()

            val seq = sequence.next()
            val eventWithSeq = event.copy(journalSequence = seq)
            val line = RunEventSerializer.encodeToString(eventWithSeq) + "\n"
            val bytes = line.toByteArray(Charsets.UTF_8)

            val file = RandomAccessFile(journalFile, "rw")
            try {
                file.seek(file.length())
                file.write(bytes)
                file.fd.sync()
            } finally {
                file.close()
            }

            // Update cache
            cachedEvents = cachedEvents + eventWithSeq
            eventByteSizes[seq] = bytes.size.toLong()

            true
        } catch (_: Exception) {
            // Fail-open: diagnostics failure does not fail the organizer run
            false
        }
    }

    /**
     * Read all events from the journal in sequence order.
     */
    fun readAll(): Sequence<RunEvent> {
        if (!opened) open()
        return cachedEvents.asSequence()
    }

    /**
     * Read all events in journal order and return as a list.
     */
    fun readAllEvents(): List<RunEvent> {
        if (!opened) open()
        return cachedEvents
    }

    /**
     * Run retention: prune eligible runs to stay within limits.
     */
    fun prune() {
        if (!opened) open()
        runRetention()
    }

    /**
     * Current sequence value (0 before first open/append).
     */
    fun currentSequence(): Long = sequence.currentValue()

    /**
     * Number of events currently cached.
     */
    fun eventCount(): Int = cachedEvents.size

    // --- Internal helpers ---

    private fun reloadEvents() {
        try {
            if (!journalFile.exists() || journalFile.length() == 0L) {
                cachedEvents = emptyList()
                eventByteSizes.clear()
                return
            }
            val lines = journalFile.readLines()
            val events = mutableListOf<RunEvent>()
            val sizes = mutableMapOf<Long, Long>()
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val event = RunEventSerializer.decode(line.toByteArray(Charsets.UTF_8))
                    events.add(event)
                    sizes[event.journalSequence] = (line.toByteArray(Charsets.UTF_8).size + 1).toLong() // +1 for newline
                } catch (_: Exception) {
                    // Skip unparseable line
                }
            }
            cachedEvents = events
            eventByteSizes = sizes.toMutableMap()
        } catch (_: Exception) {
            // Corrupt journal — reset
            resetJournal()
        }
    }

    private fun runRetention() {
        if (cachedEvents.isEmpty()) return
        val now = clock()
        val result = RetentionPolicy.evaluate(cachedEvents, eventByteSizes, now)
        if (result.pruneRunIds.isEmpty()) return
        pruneRuns(result.pruneRunIds)
    }

    private fun pruneRuns(runIds: Set<String>) {
        val remaining = cachedEvents.filter { it.runId == null || it.runId !in runIds }
        if (remaining.size == cachedEvents.size) return
        rewriteJournal(remaining)
    }

    private fun rewriteJournal(events: List<RunEvent>) {
        try {
            val tempFile = File(journalFile.parentFile, journalFile.name + ".tmp")
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (event in events) {
                    val line = RunEventSerializer.encodeToString(event) + "\n"
                    writer.write(line)
                }
            }
            tempFile.renameTo(journalFile)
            cachedEvents = events
            eventByteSizes.clear()
            // Recompute byte sizes
            for (event in events) {
                val line = RunEventSerializer.encodeToString(event) + "\n"
                eventByteSizes[event.journalSequence] = line.toByteArray(Charsets.UTF_8).size.toLong()
            }
        } catch (_: Exception) {
            // Fail-open: if rewrite fails, we keep the old journal
        }
    }

    private fun resetJournal() {
        try {
            // Rename corrupt journal to .corrupt backup, then create new empty one
            val backup = File(journalFile.parentFile, journalFile.name + ".corrupt")
            if (journalFile.exists()) {
                journalFile.renameTo(backup)
            }
            journalFile.createNewFile()
            cachedEvents = emptyList()
            eventByteSizes.clear()
            // Do NOT reset the sequence — only the journal
        } catch (_: Exception) {
            // Fail-open
        }
    }
}
