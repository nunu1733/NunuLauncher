package app.lawnchair.organizer.application.store

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Same-directory AndroidX AtomicFile publisher for the derived projection. */
internal class RecoveryInspectionSnapshotPublisher(context: Context) {
    private val snapshotDirectory = File(
        context.applicationContext.noBackupFilesDir,
        RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
    )
    private val finalFile = File(snapshotDirectory, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME)
    private val atomicFile = AtomicFile(finalFile)
    private val reader = RecoveryInspectionSnapshotReader(snapshotDirectory)

    fun publish(snapshot: RecoveryInspectionSnapshot): Boolean {
        if (!ensureDirectory()) return false
        val bytes = try {
            RecoveryInspectionSnapshotCodec.encode(snapshot)
        } catch (_: IllegalArgumentException) {
            return false
        }
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
            stream = null
            reader.read()?.generation == snapshot.generation
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            stream?.let { atomicFile.failWrite(it) }
        }
    }

    fun reader(): RecoveryInspectionSnapshotReader = reader

    private fun ensureDirectory(): Boolean = try {
        when {
            snapshotDirectory.isDirectory -> true
            snapshotDirectory.exists() -> false
            else -> snapshotDirectory.mkdirs() && snapshotDirectory.isDirectory
        }
    } catch (_: SecurityException) {
        false
    }
}
