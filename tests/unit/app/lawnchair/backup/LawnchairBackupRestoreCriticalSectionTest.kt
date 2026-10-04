package app.lawnchair.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #187 / ADR-0011 (spec AC-2(b)(c)): the restore critical section must
 * hard-stop when the inspection snapshot cannot be cleared — quiesce and the
 * databases wipe are never reached, the recovery DB survives, and the poison
 * state (DB absent + snapshot present) is never regenerated — and on success
 * the only reachable order is cleanup/verify -> quiesce -> wipe.
 */
class LawnchairBackupRestoreCriticalSectionTest {

    private class RecordingOrchestration(clearResult: Boolean) {
        val ops = mutableListOf<String>()
        var clearResult = clearResult
        lateinit var dbFile: File

        val suspendRecoveryOperations: (() -> Unit) -> Unit = { section ->
            section()
        }
        val clearInspectionSnapshot: () -> Boolean = {
            ops += "cleanup"
            clearResult
        }
        val quiesce: () -> Unit = {
            ops += "quiesce"
        }
        val wipeDatabases: () -> Unit = {
            ops += "wipe"
            dbFile.delete()
        }
    }

    private fun withDbFile(block: (File) -> Unit) {
        val root = Files.createTempDirectory("issue187-critical-section").toFile()
        try {
            val dbFile = File(root, "launcher_4_4_5.db")
            check(dbFile.createNewFile())
            block(dbFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupFailureHardStopsBeforeQuiesceAndWipeLeavingRecoveryDbIntact() {
        withDbFile { dbFile ->
            val orchestration = RecordingOrchestration(clearResult = false).apply { this.dbFile = dbFile }

            try {
                LawnchairBackup.runRestoreCriticalSection(
                    suspendRecoveryOperations = orchestration.suspendRecoveryOperations,
                    clearInspectionSnapshot = orchestration.clearInspectionSnapshot,
                    quiesce = orchestration.quiesce,
                    wipeDatabases = orchestration.wipeDatabases,
                )
                fail("cleanup failure must abort the restore")
            } catch (expected: IllegalStateException) {
                assertEquals(
                    "Restore aborted: could not clear the organizer inspection snapshot",
                    expected.message,
                )
            }

            assertEquals(listOf("cleanup"), orchestration.ops)
            assertTrue("recovery DB must survive the hard stop", dbFile.exists())
        }
    }

    @Test
    fun successRunsCleanupThenQuiesceThenWipeInOrder() {
        withDbFile { dbFile ->
            val orchestration = RecordingOrchestration(clearResult = true).apply { this.dbFile = dbFile }

            LawnchairBackup.runRestoreCriticalSection(
                suspendRecoveryOperations = orchestration.suspendRecoveryOperations,
                clearInspectionSnapshot = orchestration.clearInspectionSnapshot,
                quiesce = orchestration.quiesce,
                wipeDatabases = orchestration.wipeDatabases,
            )

            assertEquals(listOf("cleanup", "quiesce", "wipe"), orchestration.ops)
            assertFalse("the databases wipe removes the recovery DB", dbFile.exists())
        }
    }

    @Test
    fun wipeIsOnlyReachableInsideTheRecoverySuspensionSection() {
        withDbFile {
            val orchestration = RecordingOrchestration(clearResult = true).apply { dbFile = it }
            var sectionRan = false
            val suspendRecoveryOperations: (() -> Unit) -> Unit = { section ->
                sectionRan = true
                section()
            }

            LawnchairBackup.runRestoreCriticalSection(
                suspendRecoveryOperations = suspendRecoveryOperations,
                clearInspectionSnapshot = orchestration.clearInspectionSnapshot,
                quiesce = orchestration.quiesce,
                wipeDatabases = orchestration.wipeDatabases,
            )

            assertTrue(sectionRan)
            assertEquals("cleanup", orchestration.ops.first())
        }
    }
}
