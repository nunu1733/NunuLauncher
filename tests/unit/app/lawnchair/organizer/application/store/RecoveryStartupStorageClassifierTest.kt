package app.lawnchair.organizer.application.store

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryStartupStorageClassifierTest {
    @Test
    fun absentMainAndAbsentOrEmptySnapshotDirectoryArePristine() {
        val root = Files.createTempDirectory("recovery-startup").toFile()
        try {
            val main = File(root, RecoveryDbSchema.FILE_NAME)
            val snapshot = File(root, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)

            assertEquals(RecoveryStartupStorageClassifier.State.Pristine, RecoveryStartupStorageClassifier.classify(main, snapshot))
            check(snapshot.mkdirs())
            assertEquals(RecoveryStartupStorageClassifier.State.Pristine, RecoveryStartupStorageClassifier.classify(main, snapshot))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absentMainWithAnyResidualCompanionOrSnapshotIsSuspicious() {
        val root = Files.createTempDirectory("recovery-startup").toFile()
        try {
            val main = File(root, RecoveryDbSchema.FILE_NAME)
            val snapshot = File(root, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)
            File("${main.absolutePath}-wal").writeText("residual")
            assertEquals(
                RecoveryStartupStorageClassifier.State.SuspiciousAbsence,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
            check(File("${main.absolutePath}-wal").delete())
            check(snapshot.mkdirs())
            File(snapshot, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).writeText("residual")
            assertEquals(
                RecoveryStartupStorageClassifier.State.SuspiciousAbsence,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nonSqliteRegularMainFileIsNeverClassifiedAsExisting() {
        val root = Files.createTempDirectory("recovery-startup").toFile()
        try {
            val main = File(root, RecoveryDbSchema.FILE_NAME)
            val snapshot = File(root, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)
            main.writeText("not a sqlite database")

            assertEquals(
                RecoveryStartupStorageClassifier.State.InvalidMain,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zeroLengthAndNonRegularMainFileAreNeverPristine() {
        val root = Files.createTempDirectory("recovery-startup").toFile()
        try {
            val main = File(root, RecoveryDbSchema.FILE_NAME)
            val snapshot = File(root, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)
            check(main.createNewFile())
            assertEquals(
                RecoveryStartupStorageClassifier.State.ZeroLengthMain,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
            check(main.delete())
            check(main.mkdirs())
            assertEquals(
                RecoveryStartupStorageClassifier.State.InvalidMain,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Issue #153 deterministic trigger pin (characterization of the current
     * fail-closed outcome; the healing decision belongs to the focused fix
     * issue).
     *
     * Production trigger: a successful pre-restore startup reconciliation
     * publishes the inspection snapshot under `no_backup/recovery-inspection/`
     * and creates the recovery DB under `databases/`. `LawnchairBackup.restore()`
     * then wipes the whole databases directory
     * (`getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile?.deleteRecursively()`)
     * while `no_backup/` survives, so every later process classifies as
     * [RecoveryStartupStorageClassifier.State.SuspiciousAbsence], startup
     * availability is READ_FAILED, `RestartReconciler.reconcileAll` fails at
     * the availability branch before any SQLite open, the gate stays FAILED,
     * and every compose returns `NotReady(ReconciliationFailed)` —
     * journaled as `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED`.
     * Observed on emulator heads 74c2156767 through 667e8915f2
     * (docs/assessment/issue-153-zip-restore-notready-root-cause.md).
     */
    @Test
    fun zipRestoreLeavesRecoveryDbDeletedWithPublishedSnapshotSuspiciousAbsence() {
        val root = Files.createTempDirectory("issue153-zip-restore").toFile()
        try {
            val databases = File(root, "databases")
            val noBackup = File(root, "no_backup")
            check(databases.mkdirs())
            check(noBackup.mkdirs())
            val main = File(databases, RecoveryDbSchema.FILE_NAME)
            val snapshot = File(noBackup, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)

            // Pre-restore process: store opened and snapshot published.
            check(main.createNewFile())
            check(snapshot.mkdirs())
            File(snapshot, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).writeText("snapshot")

            // Restore-equivalent artifact transition (LawnchairBackup.restore):
            // the databases directory is deleted recursively; no_backup is not.
            check(databases.deleteRecursively())
            check(!snapshot.exists() || File(snapshot, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).exists())

            assertEquals(
                RecoveryStartupStorageClassifier.State.SuspiciousAbsence,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
