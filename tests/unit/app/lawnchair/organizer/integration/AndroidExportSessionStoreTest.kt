package app.lawnchair.organizer.integration

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.ExportCapabilities
import app.lawnchair.organizer.personalization.ExportGridContext
import app.lawnchair.organizer.personalization.ExportItem
import app.lawnchair.organizer.personalization.ExportItemRole
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.IntentValidation
import app.lawnchair.organizer.personalization.IntentValidationFailure
import app.lawnchair.organizer.personalization.IntentValidator
import app.lawnchair.organizer.personalization.ItemIntent
import app.lawnchair.organizer.personalization.Mobility
import app.lawnchair.organizer.personalization.PersonalizationContextExportV1
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.PreservedConstraints
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
            val reloaded = store(directory, "s1").load("export-1")!!
            assertEquals(session, reloaded)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun expiredMatchingRecordIsReturnedForTypedValidatorRejection() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            assertTrue(store.save(session))

            // Unknown export → absent (EXPORT_MISMATCH at the validator).
            assertNull(store.load("export-2"))
            // Matching but expired record is NOT collapsed into absence: the
            // validator turns it into SESSION_EXPIRED.
            assertEquals(session, store.load("export-1"))
            assertNull(store.active(nowEpochMs = session.expiresAtEpochMs))
            assertEquals(session, store.active(nowEpochMs = 2_000L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun processDeathAfterExpiryDistinguishesSessionExpiredFromExportMismatch() {
        val directory = tempDirectory()
        try {
            store(directory, "s1").save(session)

            // Simulate process death: brand-new store instance, expired record.
            val reloaded = store(directory, "s1").load("export-1")
            val expiredValidation = IntentValidator.validate(
                intent = validIntentFor("export-1", reloaded!!),
                export = exportFor(reloaded),
                session = reloaded,
                nowEpochMs = session.expiresAtEpochMs + 1,
                currentStructuralDigest = reloaded.sourceContextDigest,
            )
            assertEquals(
                IntentValidationFailure.SessionExpired,
                (expiredValidation as IntentValidation.Failure).failure,
            )
            // Unknown export id has no record at all → EXPORT_MISMATCH.
            assertNull(store(directory, "s1").load("unknown"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun exportFor(session: ExportSession) = PersonalizationContextExportV1(
        exportId = session.exportId,
        tier = session.tier,
        grid = ExportGridContext(4, 6, 1),
        items = session.itemRefs.map { (ref, _) ->
            ExportItem(
                ref = ref,
                role = ExportItemRole.APP_OR_SHORTCUT,
                category = null,
                groupSemantic = null,
                label = null,
                pageAffinity = null,
                regionAffinity = null,
                mobility = Mobility.MOVABLE,
                fixReason = null,
                usage = null,
            )
        },
        preservedConstraints = PreservedConstraints(emptyList(), emptyMap()),
        capabilities = ExportCapabilities(ContextExportContract.INTENT_SCHEMA_VERSION, ContextExportContract.FIXED_CAPABILITIES),
        usageSignals = null,
    )

    private fun validIntentFor(exportId: String, session: ExportSession) = PersonalizedIntentV1(
        exportId = exportId,
        itemIntents = session.itemRefs.keys.map { ItemIntent(ref = it) },
    )

    @Test
    fun singleActiveSessionReplacesThePreviousOne() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            store.save(session)
            val replacement = session.copy(exportId = "export-2", createdAtEpochMs = 3_000L, expiresAtEpochMs = 3_000L + ContextExportContract.SESSION_TTL_MS)
            store.save(replacement)

            // The prior session is invalidated by the new export.
            assertNull(store.load("export-1"))
            assertEquals(replacement, store.load("export-2"))
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
            assertEquals(session, store.load("export-1"))
            store.invalidate("export-1")
            assertNull(store.load("export-1"))
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
            assertNull(store(directory, "s1").load("export-1"))

            file.writeText("""{"schemaVersion":99,"exportId":"e","itemRefs":[],"tier":"LOCAL_FULL","sourceContextDigest":"d","createdAtEpochMs":0,"expiresAtEpochMs":1}""")
            assertNull(store(directory, "s1").load("e"))
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
