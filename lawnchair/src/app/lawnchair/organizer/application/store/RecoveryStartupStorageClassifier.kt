package app.lawnchair.organizer.application.store

import java.io.File

/**
 * File-API-only startup classifier. It runs before any SQLite helper/open path
 * and therefore distinguishes a never-used recovery store from residual or
 * partial storage that must fail closed.
 */
internal object RecoveryStartupStorageClassifier {
    sealed interface State {
        data object Pristine : State
        data object Existing : State
        data object SuspiciousAbsence : State
        data object ZeroLengthMain : State
        data object InvalidMain : State
        data object UnreadableInventory : State
    }

    fun classify(
        mainDatabase: File,
        snapshotDirectory: File,
    ): State {
        if (mainDatabase.exists()) {
            return when {
                !mainDatabase.isFile -> State.InvalidMain
                mainDatabase.length() == 0L -> State.ZeroLengthMain
                else -> State.Existing
            }
        }

        val companions = listOf(
            File("${mainDatabase.absolutePath}-wal"),
            File("${mainDatabase.absolutePath}-shm"),
            File("${mainDatabase.absolutePath}-journal"),
        )
        if (companions.any(File::exists)) return State.SuspiciousAbsence

        if (!snapshotDirectory.exists()) return State.Pristine
        if (!snapshotDirectory.isDirectory) return State.SuspiciousAbsence
        val entries = snapshotDirectory.listFiles() ?: return State.UnreadableInventory
        return if (entries.isEmpty()) State.Pristine else State.SuspiciousAbsence
    }
}
