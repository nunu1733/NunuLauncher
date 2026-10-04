package app.lawnchair.organizer.application.store

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #187 / ADR-0011: the ZIP-restore artifact reset must keep the
 * organizer recovery artifacts consistent. The classifier itself is
 * unchanged (AC-3); these tests pin the cleanup hook's verified-removal
 * contract and the defined-safe intermediate state (AC-2 a/b/c, AC-5).
 */
class RecoveryStartupArtifactsTest {

    private fun newRoot(): ArtifactRoot {
        val root = Files.createTempDirectory("issue187-artifacts").toFile()
        val databases = File(root, "databases")
        val noBackup = File(root, "no_backup")
        check(databases.mkdirs() && noBackup.mkdirs())
        val main = File(databases, RecoveryDbSchema.FILE_NAME)
        val snapshot = File(noBackup, RecoveryInspectionSnapshotReader.DIRECTORY_NAME)
        return ArtifactRoot(root, main, snapshot)
    }

    private data class ArtifactRoot(val root: File, val main: File, val snapshot: File)

    @Test
    fun clearRemovesPublishedSnapshotLeavingPristineEquivalentState() {
        val (root, main, snapshot) = newRoot()
        try {
            // Reconciled-startup equivalent: store opened + snapshot published.
            check(main.createNewFile())
            check(snapshot.mkdirs())
            File(snapshot, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).writeText("snapshot")

            assertTrue(RecoveryStartupArtifacts.clearInspectionSnapshot(snapshot))
            assertFalse(snapshot.exists())

            // After the restore-equivalent databases wipe the state is both-absent.
            check(main.delete())
            assertEquals(
                RecoveryStartupStorageClassifier.State.Pristine,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absentSnapshotDirectoryReportsSuccessIdempotently() {
        val (root, _, snapshot) = newRoot()
        try {
            assertTrue(RecoveryStartupArtifacts.clearInspectionSnapshot(snapshot))
            assertTrue(RecoveryStartupArtifacts.clearInspectionSnapshot(snapshot))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySnapshotDirectoryIsClearedToPristine() {
        val (root, _, snapshot) = newRoot()
        try {
            check(snapshot.mkdirs())
            // An empty directory is equivalent to absent for the classifier, so
            // removing it and reporting success keeps the hook idempotent.
            assertTrue(RecoveryStartupArtifacts.clearInspectionSnapshot(snapshot))
            assertFalse(snapshot.exists())
            assertEquals(
                RecoveryStartupStorageClassifier.State.Pristine,
                RecoveryStartupStorageClassifier.classify(
                    File(root, "databases/${RecoveryDbSchema.FILE_NAME}"),
                    snapshot,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedDeleteOrLeftoverEntriesReportFailureForHardStop() {
        val (root, _, snapshot) = newRoot()
        try {
            check(snapshot.mkdirs())
            File(snapshot, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).writeText("snapshot")

            // (b) delete fails -> verification must fail so the caller hard-stops.
            assertFalse(
                RecoveryStartupArtifacts.clearInspectionSnapshot(snapshot) { false },
            )

            // A delete that reports success but leaves entries behind also fails
            // the verification (leftover entries keep the inventory non-empty).
            File(snapshot, "other-entry").writeText("x")
            val partialDelete = { _: File ->
                File(snapshot, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).delete()
                true
            }
            assertFalse(RecoveryStartupArtifacts.clearInspectionSnapshot(snapshot, partialDelete))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotAbsentWithStorePresentIsExistingAndNotPoison() {
        val (root, main, snapshot) = newRoot()
        try {
            // Defined-safe intermediate state (cleanup done, wipe pending): DB
            // exists, snapshot absent. Must classify as Existing, not poison.
            check(main.createNewFile())
            writeSqliteHeader(main)
            assertFalse(snapshot.exists())
            assertEquals(
                RecoveryStartupStorageClassifier.State.Existing,
                RecoveryStartupStorageClassifier.classify(main, snapshot),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSqliteHeader(file: File) {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        file.outputStream().use { it.write(header) }
    }
}
