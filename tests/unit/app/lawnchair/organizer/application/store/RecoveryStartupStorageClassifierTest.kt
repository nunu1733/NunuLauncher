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
}
