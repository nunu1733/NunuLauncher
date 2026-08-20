package app.lawnchair.organizer.application.store

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Narrow writer-only boundary around the pinned AndroidX [AtomicFile] protocol.
 *
 * The production implementation is the only implementation used outside tests.
 * Keeping this boundary local lets regression tests deterministically exercise
 * `write`, `finishWrite`, and final-file revalidation failures without changing
 * the reader or inspection contracts.
 */
internal interface RecoveryInspectionSnapshotAtomicFile {
    fun startWrite(): FileOutputStream

    fun finishWrite(stream: FileOutputStream)

    fun failWrite(stream: FileOutputStream)
}

private class AndroidxRecoveryInspectionSnapshotAtomicFile(
    finalFile: File,
) : RecoveryInspectionSnapshotAtomicFile {
    private val atomicFile = AtomicFile(finalFile)

    override fun startWrite(): FileOutputStream = atomicFile.startWrite()

    override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)

    override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
}

/** Same-directory AndroidX AtomicFile publisher for the derived projection. */
internal class RecoveryInspectionSnapshotPublisher internal constructor(
    private val snapshotDirectory: File,
    private val atomicFile: RecoveryInspectionSnapshotAtomicFile,
) {
    private val reader = RecoveryInspectionSnapshotReader(snapshotDirectory)

    constructor(context: Context) : this(
        snapshotDirectory = File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ),
        atomicFile = AndroidxRecoveryInspectionSnapshotAtomicFile(
            File(
                File(
                    context.applicationContext.noBackupFilesDir,
                    RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
                ),
                RecoveryInspectionSnapshotReader.FINAL_FILE_NAME,
            ),
        ),
    )

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

    fun directoryForStartupInventory(): File = snapshotDirectory

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
