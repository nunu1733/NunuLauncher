package app.lawnchair.organizer.integration

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.DurableGroupSemantic
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.DurableRefDecision
import app.lawnchair.organizer.personalization.DurableRefEntry
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #374 DI-AC-01/04 (store unit-test part): process-death durability,
 * single-active overwrite, fail-closed reads (corruption / unknown schema),
 * and the tombstone two-phase discard of the app-private durable pending
 * imported intent store.
 */
class AndroidPendingImportedIntentStoreTest {

    private val record = DurablePendingIntent(
        exportId = "export-1",
        intentIdentitySchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
        intentIdentityDigest = "a".repeat(64),
        decisions = listOf(
            DurableRefEntry(
                ref = "ref-a",
                decision = DurableRefDecision.Authored(
                    importance = Importance.HIGH,
                    desiredGroupRefs = listOf("ref-b"),
                    groupSemantic = DurableGroupSemantic.ExistingCategory("cat-1"),
                    pageAffinity = 1,
                    regionAffinity = ExportRegionKind.BOTTOM,
                    preserve = true,
                ),
            ),
            DurableRefEntry(
                ref = "ref-b",
                decision = DurableRefDecision.Authored(
                    importance = null,
                    desiredGroupRefs = null,
                    groupSemantic = DurableGroupSemantic.ProposedGroup("Morning"),
                    pageAffinity = null,
                    regionAffinity = ExportRegionKind.TOP,
                    preserve = null,
                ),
            ),
            DurableRefEntry("ref-c", DurableRefDecision.UnresolvedAuthored),
            DurableRefEntry("ref-d", DurableRefDecision.UnresolvedByOmission),
        ),
        minimizeMovement = true,
        expiresAtEpochMs = 1_000L + ContextExportContract.SESSION_TTL_MS,
        entryKind = PendingImportEntryKind.IDLE,
        discarded = false,
        createdAtEpochMs = 1_000L,
    )

    @Test
    fun recordSurvivesStoreRecreationAcrossProcessDeath() {
        val directory = tempDirectory()
        try {
            store(directory, "p1").save(record)

            // Simulate process death: a brand-new store instance reads the
            // same durable record. Mapping fidelity: save -> load -> equals.
            assertEquals(record, store(directory, "p1").load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun singleActiveSecondSaveOverwritesTheFirst() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "p1")
            assertTrue(store.save(record))
            val replacement = record.copy(
                exportId = "export-2",
                entryKind = PendingImportEntryKind.RUN_IN,
                createdAtEpochMs = 3_000L,
                expiresAtEpochMs = 3_000L + ContextExportContract.SESSION_TTL_MS,
            )
            assertTrue(store.save(replacement))

            assertEquals(replacement, store.load())
            assertEquals("export-2", store.load()!!.exportId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownSchemaVersionDegradesToNoProposalFailClosed() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "p1")
            directory.mkdirs()
            store(directory, "p1").save(record)
            file.writeText(file.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":99"))
            assertNull(store(directory, "p1").load())
            // DI-AC-05 (review finding 3): an existing-but-unknown-schema file
            // is invalid, not absent — it is physically cleaned so a later
            // read can never resurrect it.
            assertFalse("the unknown-schema record is cleaned", file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptedOrTruncatedStorageDegradesToNoProposalFailClosed() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "p1")
            directory.mkdirs()
            file.writeText("{corrupt")
            assertNull(store(directory, "p1").load())
            assertFalse("garbage residue is physically cleaned", file.exists())

            // A truncated record — decodable prefix, cut mid-decision list.
            store(directory, "p1").save(record)
            val json = file.readText()
            file.writeText(json.substring(0, json.length / 2))
            assertNull(store(directory, "p1").load())
            assertFalse("the truncated record is physically cleaned", file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * DI-AC-05 (review finding 3): a read I/O failure over an EXISTING file is
     * not a silent absence either — the residue is best-effort cleaned. A
     * directory in the record file's place makes the open succeed and the
     * read fail (EISDIR → IOException) without touching the decode path.
     */
    @Test
    fun readFailureOverAnExistingRecordIsCleanedFailClosed() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "p1")
            // A directory cannot be decoded: the read fails after a successful open.
            assertTrue(file.mkdirs())
            val store = AndroidPendingImportedIntentStore(file)
            assertNull(store.load())
            assertFalse("the unreadable residue is cleaned", file.exists())
            // The store keeps working afterwards (the file is a regular path again).
            assertTrue(store.save(record))
            assertEquals(record, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #374 review finding 1: the compare-and-delete half of the
     * attempt-fenced write contract — only the EXACT record is removed; a
     * newer/different record or absence is never touched.
     */
    @Test
    fun deleteIfRemovesOnlyTheIdenticalRecord() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "p1")
            val store = store(directory, "p1")

            // Absent store: nothing matches, nothing happens.
            assertFalse(store.deleteIf(record))

            assertTrue(store.save(record))
            assertTrue("the identical record is deleted", store.deleteIf(record))
            assertFalse(file.exists())
            assertNull(store.load())

            // A NEWER record is never the victim of a stale attempt's cleanup.
            assertTrue(store.save(record))
            val newer = record.copy(exportId = "export-2", createdAtEpochMs = 9_000L)
            assertTrue(store.save(newer))
            assertFalse("a different (newer) record is not touched", store.deleteIf(record))
            assertEquals(newer, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun discardCommitsTombstoneThenPhysicallyDeletes() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "p1")
            val store = store(directory, "p1")
            assertTrue(store.save(record))

            assertTrue(store.discard())
            // The tombstone commit succeeded and the best-effort delete ran:
            // nothing loadable remains.
            assertNull(store.load())
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun discardOnAnEmptyStoreIsVacuouslySuccessful() {
        val directory = tempDirectory()
        try {
            assertTrue(store(directory, "p1").discard())
            assertNull(store(directory, "p1").load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #374 DI-AC-08 (store unit-test part): a tombstone-commit write
     * failure is observable (false) and the proposal stays valid (retryable),
     * never half-discarded.
     *
     * Failure injection: the store's directory is made non-writable after a
     * successful save. The tombstone rewrite then cannot create its `.new`
     * write target (an uncaught-at-this-layer IOException from `AtomicFile`
     * startWrite), while the preceding load still reads the record — exactly
     * the commit-failure mid-flight shape. This path deliberately avoids
     * `AtomicFile`'s internal logcat branches, which do not run in unit tests.
     */
    @Test
    fun tombstoneCommitFailureIsObservableAndKeepsTheRecordValid() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "p1")
            assertTrue(store.save(record))
            assertTrue(directory.setWritable(false))

            assertFalse("the tombstone commit must report failure", store.discard())

            // The proposal survived the failed discard and a later retry
            // (writability restored) still sees the ORIGINAL record.
            assertTrue(directory.setWritable(true))
            assertEquals(record, store.load())
            assertTrue(store.discard())
            assertNull(store.load())
        } finally {
            directory.setWritable(true)
            directory.deleteRecursively()
        }
    }

    @Test
    fun discardOfACorruptRecordIsVacuouslySuccessfulAndCleansIt() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "p1")
            directory.mkdirs()
            file.writeText("{corrupt")

            assertTrue(store(directory, "p1").discard())
            assertFalse("residue is cleaned", file.exists())
            assertNull(store(directory, "p1").load())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun store(directory: File, name: String): AndroidPendingImportedIntentStore {
        directory.mkdirs()
        return AndroidPendingImportedIntentStore(File(directory, name))
    }

    private fun tempDirectory(): File {
        val directory = File.createTempFile("pending-intent-store", "test")
        directory.delete()
        directory.mkdirs()
        return directory
    }
}
