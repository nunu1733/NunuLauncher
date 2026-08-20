package app.lawnchair.organizer.application.store

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Inspection-only reader. It has no AndroidX AtomicFile recovery behavior: a
 * final file is trusted only when the directory contains that one expected
 * regular file and no writer companion or unexpected entry.
 */
internal class RecoveryInspectionSnapshotReader(
    private val snapshotDirectory: File,
) {
    fun read(): RecoveryInspectionSnapshot? {
        val entries = snapshotDirectory.listFiles()?.sortedBy { it.name } ?: return null
        if (entries.size != 1) return null
        val final = entries.single()
        if (final.name != FINAL_FILE_NAME || !final.isFile) return null
        val bytes = readBounded(final) ?: return null
        return RecoveryInspectionSnapshotCodec.decode(bytes)
    }

    private fun readBounded(file: File): ByteArray? = try {
        if (file.length() !in 1..MAX_SNAPSHOT_BYTES.toLong()) return null
        FileInputStream(file).use { input ->
            ByteArrayOutputStream(file.length().toInt()).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_SNAPSHOT_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val DIRECTORY_NAME = "recovery-inspection"
        const val FINAL_FILE_NAME = "recovery-inspection.v1"
        const val MAX_SNAPSHOT_BYTES = 1_048_576
        private const val BUFFER_BYTES = 8_192
    }
}
