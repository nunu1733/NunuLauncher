package app.lawnchair.organizer.application.store

import android.content.Context
import java.io.File

/**
 * Issue #187 / ADR-0011: file-level reset of the organizer recovery startup
 * artifacts for the ZIP backup restore path.
 *
 * `LawnchairBackup.restore()` wipes the whole `databases/` directory, which
 * includes the organizer recovery DB, while the inspection snapshot published
 * by a pre-restore startup reconciliation survives under
 * `no_backup/recovery-inspection/`. The leftover snapshot then classifies
 * every later process as `RecoveryStartupStorageClassifier.State.SuspiciousAbsence`
 * (READ_FAILED), permanently failing startup reconciliation and every manual
 * organization compose (`NotReady(RECONCILIATION_FAILED)`). Clearing the
 * snapshot inside the same restore lease keeps the artifact pair consistent
 * (both absent = Pristine) without weakening the classifier's fail-closed
 * rules.
 */
internal object RecoveryStartupArtifacts {

    /**
     * Deletes the inspection snapshot directory and verifies the removal.
     * Returns **false** when the directory cannot be removed or emptied —
     * callers MUST abort the restore (hard stop) without deleting the
     * databases directory, otherwise the DB-absent + snapshot-present poison
     * state is regenerated.
     */
    fun clearInspectionSnapshot(context: Context): Boolean = clearInspectionSnapshot(
        File(context.applicationContext.noBackupFilesDir, RecoveryInspectionSnapshotReader.DIRECTORY_NAME),
    )

    /**
     * Verification is part of the contract: "false" covers both a failed
     * delete and a delete that left entries behind. Injection point for tests.
     */
    internal fun clearInspectionSnapshot(
        snapshotDirectory: File,
        delete: (File) -> Boolean = { it.deleteRecursively() },
    ): Boolean {
        if (snapshotDirectory.exists() && !delete(snapshotDirectory)) return false
        if (!snapshotDirectory.exists()) return true
        val entries = snapshotDirectory.listFiles() ?: return false
        return entries.isEmpty()
    }
}
