package app.lawnchair.organizer.diagnostics.journal

import app.lawnchair.organizer.diagnostics.model.RunEvent
import java.io.File
import java.io.RandomAccessFile

/**
 * Injectable hook for sync operations during retention rewrite, so tests
 * can assert the correct ordering: write-temp -> sync-temp -> rename -> sync-dir.
 */
interface SyncHook {
    /** Sync the file descriptor of [file]. */
    fun syncFile(file: File)

    /** Sync the parent directory of [file] (best-effort; may not be available on all
     *  platforms). */
    fun syncDirectory(file: File)

    companion object {
        /** Production hook: fsync via RandomAccessFile fd.sync(). */
        val PRODUCTION: SyncHook = object : SyncHook {
            override fun syncFile(file: File) {
                if (!file.exists()) return
                RandomAccessFile(file, "rw").use { it.fd.sync() }
            }

            override fun syncDirectory(file: File) {
                val dir = file.parentFile ?: return
                if (!dir.exists()) return
                try {
                    RandomAccessFile(dir, "r").use { it.fd.sync() }
                } catch (_: Exception) {
                    // Best-effort: directory sync is not available on all platforms.
                    // The file-level sync is the primary durability guarantee.
                }
            }
        }
    }
}

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
 * - Retention is evaluated after each append (post-append) so the just-appended
 *   event is included in the evaluation, ensuring limits are satisfied after every append.
 *
 * Thread safety: [append] is synchronized because emission may happen
 * outside the RunMutex (e.g. from the journal port lambda).
 */
class JournalStore(
    private val journalFile: File,
    private val sequence: JournalSequence,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val syncHook: SyncHook = SyncHook.PRODUCTION,
) {
    private var opened: Boolean = false
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
            // Reconcile sequence with the max journalSequence among retained events
            // so that if the seq file was lost, we recover from the journal.
            val maxSeq = cachedEvents.maxOfOrNull { it.journalSequence } ?: 0L
            sequence.reconcile(maxSeq)
            opened = true
        } catch (_: Exception) {
            // Fail-open: if open fails, the store is still usable
            // (append will try again and handle the failure)
            opened = true // Mark as opened so append doesn't try again
        }
    }

    /** Close the journal store. */
    fun close() {
        opened = false
    }

    /**
     * Append an event to the journal.
     *
     * @return true if the event was successfully persisted, false on failure.
     */
    @Synchronized
    fun append(event: RunEvent): Boolean {
        if (!opened) open()
        return try {
            val seq = sequence.next() ?: return false
            val now = clock()
            val eventWithSeq = event.copy(
                journalSequence = seq,
                recordedAtWallMillis = if (event.recordedAtWallMillis == 0L) now else event.recordedAtWallMillis,
            )
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

            // Post-append lazy retention: evaluate after the append so that the
            // just-appended event is included in the evaluation. This ensures the
            // journal satisfies all limits after every append.
            runRetention()

            true
        } catch (_: Exception) {
            // Fail-open: diagnostics failure does not fail the organizer run
            false
        }
    }

    /**
     * Return a stable immutable snapshot of the current cached events
     * under the same lock/serialization guarantees as [append].
     */
    @Synchronized
    fun snapshot(): List<RunEvent> {
        if (!opened) open()
        return cachedEvents.toList()
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
            var decodeFailed = false
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val event = RunEventSerializer.decode(line.toByteArray(Charsets.UTF_8))
                    events.add(event)
                    sizes[event.journalSequence] = (line.toByteArray(Charsets.UTF_8).size + 1).toLong()
                } catch (_: Exception) {
                    // Any decode failure resets the journal (per spec corruption-isolation scenario)
                    decodeFailed = true
                    break
                }
            }
            if (decodeFailed) {
                resetJournal()
                return
            }
            cachedEvents = events
            eventByteSizes = sizes.toMutableMap()
        } catch (_: Exception) {
            resetJournal()
        }
    }

    private fun runRetention() {
        if (cachedEvents.isEmpty()) return
        val now = clock()
        val result = RetentionPolicy.evaluate(cachedEvents, eventByteSizes, now)
        if (result.pruneRunIds.isEmpty() && result.pruneOrphanedSequences.isEmpty()) return
        pruneRuns(result.pruneRunIds, result.pruneOrphanedSequences)
    }

    private fun pruneRuns(runIds: Set<String>, orphanedSequences: Set<Long> = emptySet()) {
        val remaining = cachedEvents.filter { event ->
            val runIdMatch = event.runId != null && event.runId in runIds
            val seqMatch = event.journalSequence in orphanedSequences
            !runIdMatch && !seqMatch
        }
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
            // (a) Sync the temp file before rename so the replacement file is
            // durably persisted. This maintains the durability guarantee that
            // the just-synced append event originally had.
            syncHook.syncFile(tempFile)
            val renamed = tempFile.renameTo(journalFile)
            if (!renamed) {
                // renameTo failed (e.g. cross-filesystem); try copy-and-delete
                try {
                    journalFile.copyFrom(tempFile)
                    // (a.2) Sync the destination file after copy so that the
                    // replacement bytes are durable. The temp file's earlier
                    // fsync only covers the source, not the destination.
                    syncHook.syncFile(journalFile)
                    tempFile.delete()
                } catch (_: Exception) {
                    tempFile.delete()
                    return // Keep old journal cache consistent
                }
            }
            // (b) Sync the parent directory after rename so the new journal
            // file name is committed to directory metadata. Best-effort;
            // directory fd sync is not available on all platforms.
            syncHook.syncDirectory(journalFile)
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

    private fun File.copyFrom(source: File) {
        source.inputStream().use { input ->
            this.outputStream().use { output ->
                input.copyTo(output)
            }
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
