package app.lawnchair.organizer.integration

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.SignalProvenance
import app.lawnchair.organizer.planning.ItemId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 AC-11: process-death durability and single-active-session
 * semantics of the app-private export session store.
 */
class AndroidExportSessionStoreTest {

    private val session = ExportSession(
        exportId = "export-1",
        itemRefs = mapOf("ref-a" to ItemId("item-1"), "ref-b" to ItemId("item-2")),
        tier = PrivacyTier.EXTERNAL_REDACTED,
        sourceContextDigest = "d".repeat(64),
        signalProvenance = SignalProvenance("personalization-signals-v1", "e".repeat(64)),
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 1_000L + ContextExportContract.SESSION_TTL_MS,
    )

    private fun store(directory: File, name: String): AndroidExportSessionStore {
        directory.mkdirs()
        return AndroidExportSessionStore(File(directory, name))
    }

    @Test
    fun sessionSurvivesStoreRecreationAcrossProcessDeath() {
        val directory = tempDirectory()
        try {
            store(directory, "s1").save(session)

            // Simulate process death: a brand-new store instance reads the
            // same durable record.
            val reloaded = store(directory, "s1").load("export-1", nowEpochMs = 2_000L)!!
            assertEquals(session, reloaded)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownExportIdsAndExpiredSessionsReadAsAbsent() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            store.save(session)

            assertNull(store.load("export-2", nowEpochMs = 2_000L))
            assertNull(store.load("export-1", nowEpochMs = session.expiresAtEpochMs))
            assertNull(store.active(nowEpochMs = session.expiresAtEpochMs))
            assertEquals(session, store.active(nowEpochMs = 2_000L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun singleActiveSessionReplacesThePreviousOne() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            store.save(session)
            val replacement = session.copy(exportId = "export-2", createdAtEpochMs = 3_000L, expiresAtEpochMs = 3_000L + ContextExportContract.SESSION_TTL_MS)
            store.save(replacement)

            // The prior session is invalidated by the new export.
            assertNull(store.load("export-1", nowEpochMs = 3_500L))
            assertEquals(replacement, store.load("export-2", nowEpochMs = 3_500L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidationRemovesOnlyTheMatchingSession() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            store.save(session)
            store.invalidate("export-2")
            assertEquals(session, store.load("export-1", nowEpochMs = 2_000L))
            store.invalidate("export-1")
            assertNull(store.load("export-1", nowEpochMs = 2_000L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptedStorageDegradesToNoSessionFailClosed() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            directory.mkdirs()
            file.writeText("{corrupt")
            assertNull(store(directory, "s1").load("export-1", nowEpochMs = 2_000L))

            file.writeText("""{"schemaVersion":99,"exportId":"e","itemRefs":[],"tier":"LOCAL_FULL","sourceContextDigest":"d","createdAtEpochMs":0,"expiresAtEpochMs":1}""")
            assertNull(store(directory, "s1").load("e", nowEpochMs = 2_000L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun secureRandomAllocatorProducesDistinctUrlSafeIdentifiers() {
        val allocator = SecureRandomIdAllocator()
        val ids = (0 until 100).map { allocator.newId() }
        assertEquals(100, ids.toSet().size)
        ids.forEach {
            assertTrue(it.isNotEmpty())
            assertTrue(it.none { c -> c == '+' || c == '/' || c == '=' })
        }
    }

    private fun tempDirectory(): File {
        val directory = File.createTempFile("session-store", "test")
        directory.delete()
        directory.mkdirs()
        return directory
    }
}
