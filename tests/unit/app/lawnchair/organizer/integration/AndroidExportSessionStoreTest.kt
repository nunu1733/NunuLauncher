package app.lawnchair.organizer.integration

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.ExportCapabilities
import app.lawnchair.organizer.personalization.ExportEntryOrigin
import app.lawnchair.organizer.personalization.ExportGridContext
import app.lawnchair.organizer.personalization.ExportInvalidationResult
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
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Issue #337: the advertised category ref → identity mapping (the only
        // resolution surface; the document itself carries no stable ID).
        categoryRefs = mapOf(
            "cat-1" to app.lawnchair.organizer.planning.CategoryIdentity.BuiltIn(
                app.lawnchair.organizer.planning.CategoryId("NEWS"),
            ),
            "cat-2" to app.lawnchair.organizer.planning.CategoryIdentity.UserDefined(
                app.lawnchair.organizer.planning.UserCategoryId(USER_CATEGORY_ID),
            ),
        ),
    )

    private companion object {
        /** Canonical lowercase UUID v4 fixture; a session-only identity. */
        const val USER_CATEGORY_ID = "3f2b8c4e-1234-4abc-9de0-1234567890ab"
    }

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
                categoryRef = null,
                folderCategoryRef = null,
                label = null,
                pageAffinity = null,
                regionAffinity = null,
                mobility = Mobility.MOVABLE,
                fixReason = null,
                usage = null,
            )
        },
        categories = emptyList(),
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

    /** A scope-selected session shape (one selected candidate subject). */
    private fun scopedSession(): ExportSession {
        val candidate = CandidateTarget.AppKey(ComponentKey("com.example.candidate"), ProfileId("p0"))
        return session.copy(
            itemRefs = session.itemRefs + ("ref-candidate" to CandidatePlanningIds.planningId(candidate)),
            scopeCandidates = listOf(candidate),
        )
    }

    /**
     * Issue #417 (spec 417 "Data and state", AC-6): the durable entry origin
     * is written exactly once at save and survives process death verbatim.
     * Reads and a `NoMatch` conditional invalidation never rewrite the record
     * (zero-write): the origin stays exactly as saved.
     */
    @Test
    fun entryOriginIsWrittenAtSaveAndSurvivesProcessDeath() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            val store = store(directory, "s1")
            store.save(session.copy(entryOrigin = ExportEntryOrigin.RUN_IN))
            assertTrue(file.readText().contains("\"entryOrigin\":\"RUN_IN\""))

            // Simulate process death: a brand-new store instance reads the
            // same origin, both raw and resolved.
            val reloaded = store(directory, "s1").load("export-1")!!
            assertEquals(ExportEntryOrigin.RUN_IN, reloaded.entryOrigin)
            assertEquals(ExportEntryOrigin.RUN_IN, reloaded.resolvedEntryOrigin)

            // A mismatching invalidateIf is a zero-write NoMatch: the record
            // (and its origin) is byte-identical afterwards.
            val before = file.readText()
            assertEquals(ExportInvalidationResult.NoMatch, store.invalidateIf("export-2"))
            assertEquals(before, file.readText())
            assertEquals(ExportEntryOrigin.RUN_IN, store.load("export-1")!!.entryOrigin)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417 legacy decode rule (spec 417 "Data and state", AC-6): an
     * ABSENT origin + non-empty `scopeCandidates` is a pre-#417 RUN_IN record
     * — the raw origin stays null and the resolved origin is RUN_IN, keeping
     * #375's selection-restore rebind semantics for upgraded records.
     */
    @Test
    fun legacyRecordWithNonEmptyScopeDecodesAsRunIn() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            // Saving an untagged session omits the origin key entirely: the
            // durable bytes are exactly a pre-#417 record.
            store(directory, "s1").save(scopedSession())
            assertFalse(file.readText().contains("entryOrigin"))

            val reloaded = store(directory, "s1").load("export-1")!!
            assertNull(reloaded.entryOrigin)
            assertEquals(ExportEntryOrigin.RUN_IN, reloaded.resolvedEntryOrigin)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417 legacy decode rule (spec 417 "Data and state", AC-6): an
     * ABSENT origin + empty scope decodes as IDLE — the fail-safe arm (a
     * legacy IDLE record and a zero-selection RUN_IN record are
     * indistinguishable, and neither regains direct attach authority).
     */
    @Test
    fun legacyRecordWithEmptyScopeDecodesAsIdle() {
        val directory = tempDirectory()
        try {
            store(directory, "s1").save(session)
            val reloaded = store(directory, "s1").load("export-1")!!
            assertNull(reloaded.entryOrigin)
            assertEquals(ExportEntryOrigin.IDLE, reloaded.resolvedEntryOrigin)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417 (spec 417 "Data and state"): a PRESENT origin is respected
     * as-is — the legacy rule never overrides a written value, whatever the
     * scope shape.
     */
    @Test
    fun presentEntryOriginIsRespectedOverTheLegacyRule() {
        val directory = tempDirectory()
        try {
            // An explicitly IDLE origin wins over the non-empty-scope rule.
            store(directory, "s1").save(scopedSession().copy(entryOrigin = ExportEntryOrigin.IDLE))
            assertEquals(
                ExportEntryOrigin.IDLE,
                store(directory, "s1").load("export-1")!!.resolvedEntryOrigin,
            )

            // An explicitly RUN_IN origin survives an empty scope.
            store(directory, "s2").save(session.copy(entryOrigin = ExportEntryOrigin.RUN_IN))
            assertEquals(
                ExportEntryOrigin.RUN_IN,
                store(directory, "s2").load("export-1")!!.resolvedEntryOrigin,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417 (spec 417 "scope-bound依頼破棄の契約化", AC-5/AC-6): the
     * failure-aware conditional invalidation commit — a matching session is
     * tombstoned atomically, the commit is observable as [Committed], and the
     * invalidated session reads as absent afterwards.
     */
    @Test
    fun invalidateIfCommitsForTheMatchingSessionAndItReadsAsAbsent() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            val store = store(directory, "s1")
            assertTrue(store.save(session))

            assertEquals(ExportInvalidationResult.Committed, store.invalidateIf("export-1"))
            assertNull(store.load("export-1"))
            assertNull(store.active(1_500L))
            // The tombstone commit is the durable validity truth; the
            // physical delete ran best-effort afterwards.
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417: `NoMatch` covers every "nothing to invalidate" shape — an
     * absent store, a different (newer) session, an already-invalidated
     * session, and an unreadable record — and never touches a surviving
     * session.
     */
    @Test
    fun invalidateIfReturnsNoMatchWhenThereIsNothingToInvalidate() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            // Absent store.
            assertEquals(ExportInvalidationResult.NoMatch, store.invalidateIf("export-1"))

            // A different live session is not the victim.
            assertTrue(store.save(session))
            assertEquals(ExportInvalidationResult.NoMatch, store.invalidateIf("export-2"))
            assertEquals(session, store.load("export-1"))

            // An already-invalidated session is gone: nothing left to match.
            assertEquals(ExportInvalidationResult.Committed, store.invalidateIf("export-1"))
            assertEquals(ExportInvalidationResult.NoMatch, store.invalidateIf("export-1"))

            // An unreadable record reads as absent (fail-closed).
            val file = File(directory, "s2")
            directory.mkdirs()
            file.writeText("{corrupt")
            assertEquals(
                ExportInvalidationResult.NoMatch,
                store(directory, "s2").invalidateIf("export-1"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417 (AC-11 failure injection): a tombstone-commit write failure
     * is observable as [WriteFailed] and the session stays VALID (retryable)
     * — never half-invalidated. Injection follows the pending store test: the
     * store's directory is made non-writable so the atomic rewrite cannot
     * create its write target (an IOException from `AtomicFile` startWrite),
     * while the preceding load still reads the record.
     */
    @Test
    fun invalidateIfWriteFailureIsObservableAndKeepsTheSessionValid() {
        val directory = tempDirectory()
        try {
            val store = store(directory, "s1")
            assertTrue(store.save(session.copy(entryOrigin = ExportEntryOrigin.RUN_IN)))
            assertTrue(directory.setWritable(false))

            assertEquals(ExportInvalidationResult.WriteFailed, store.invalidateIf("export-1"))

            // The session survived the failed invalidation with its origin
            // intact; a retry after writability is restored still sees the
            // original record and commits.
            assertTrue(directory.setWritable(true))
            val survived = store.load("export-1")
            assertEquals(ExportEntryOrigin.RUN_IN, survived!!.entryOrigin)
            assertEquals(ExportInvalidationResult.Committed, store.invalidateIf("export-1"))
            assertNull(store.load("export-1"))
        } finally {
            directory.setWritable(true)
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #417: a committed invalidation tombstone whose best-effort
     * physical delete never landed (a process death right after the commit)
     * still reads as "no session" — the mark, not the file's absence, is the
     * validity truth.
     */
    @Test
    fun invalidationTombstoneReadsAsAbsentEvenWhenTheDeleteNeverLands() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            directory.mkdirs()
            file.writeText(
                """{"schemaVersion":2,"exportId":"export-1",""" +
                    """"itemRefs":[{"ref":"ref-a","itemId":"item-1"}],"tier":"EXTERNAL_REDACTED",""" +
                    """"sourceContextDigest":"${"d".repeat(64)}","signalProvenance":null,""" +
                    """"createdAtEpochMs":1000,"expiresAtEpochMs":${1_000L + ContextExportContract.SESSION_TTL_MS},""" +
                    """"scopeCandidates":[],"scopeCandidateDigest":"",""" +
                    """"entryOrigin":"RUN_IN","invalidated":true}""",
            )
            val store = store(directory, "s1")
            assertTrue(file.exists())
            assertNull(store.load("export-1"))
            assertNull(store.active(1_500L))
            assertEquals(ExportInvalidationResult.NoMatch, store.invalidateIf("export-1"))
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
    fun pre331Schema1RecordIsRejectedFailClosed() {
        // Issue #331: the persisted record schema is v2 (candidate scope
        // fields). A pre-331 v1 record — even shaped otherwise like a v2
        // record — must never load as an active session (the user re-exports).
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            directory.mkdirs()
            file.writeText(
                """{"schemaVersion":1,"exportId":"export-1",""" +
                    """"itemRefs":[{"ref":"ref-a","itemId":"item-1"}],"tier":"EXTERNAL_REDACTED",""" +
                    """"sourceContextDigest":"${"d".repeat(64)}","signalProvenance":null,""" +
                    """"createdAtEpochMs":1000,"expiresAtEpochMs":${1_000L + ContextExportContract.SESSION_TTL_MS},""" +
                    """"scopeCandidates":[],"scopeCandidateDigest":""}""",
            )
            assertNull(store(directory, "s1").load("export-1"))
            assertNull(store(directory, "s1").active(1_500L))
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

    /**
     * Issue #337 (spec 337 D-1/D-5, AC-11): the ref → identity mapping is
     * durable with the session (process death must not break category
     * resolution), and a record written before v4 (no mapping) still decodes
     * with an empty mapping — its refs fail closed rather than resolving.
     */
    @Test
    fun categoryRefMappingIsDurableAndLegacyRecordsFailClosed() {
        val directory = tempDirectory()
        try {
            store(directory, "s1").save(session)
            val reloaded = store(directory, "s1").load("export-1")!!
            assertEquals(session.categoryRefs, reloaded.categoryRefs)
            assertEquals("NEWS", (reloaded.categoryRefs.getValue("cat-1") as CategoryIdentity.BuiltIn).id.value)

            // A record without the additive field (pre-v4 writer) decodes with
            // an empty mapping: nothing resolves, the import fails closed.
            val legacy = File(directory, "s2")
            legacy.writeText(
                """{"schemaVersion":2,"exportId":"export-2",""" +
                    """"itemRefs":[{"ref":"ref-a","itemId":"item-1"}],"tier":"EXTERNAL_REDACTED",""" +
                    """"sourceContextDigest":"${"d".repeat(64)}","signalProvenance":null,""" +
                    """"createdAtEpochMs":1000,"expiresAtEpochMs":${1_000L + ContextExportContract.SESSION_TTL_MS},""" +
                    """"scopeCandidates":[],"scopeCandidateDigest":""}""",
            )
            val legacySession = store(directory, "s2").load("export-2")!!
            assertTrue(legacySession.categoryRefs.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #337 (spec 337 D-8, AC-11): the record schema is strict — a
     * category entry with an unknown kind (corrupted/partially rewritten file)
     * degrades to "no session" instead of being accepted as user-defined.
     */
    @Test
    fun unknownCategoryRefKindInTheRecordFailsClosed() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            directory.mkdirs()
            file.writeText(
                """{"schemaVersion":2,"exportId":"export-1",""" +
                    """"itemRefs":[{"ref":"ref-a","itemId":"item-1"}],"tier":"EXTERNAL_REDACTED",""" +
                    """"sourceContextDigest":"${"d".repeat(64)}","signalProvenance":null,""" +
                    """"createdAtEpochMs":1000,"expiresAtEpochMs":${1_000L + ContextExportContract.SESSION_TTL_MS},""" +
                    """"scopeCandidates":[],"scopeCandidateDigest":"",""" +
                    """"categoryRefs":[{"ref":"cat-1","kind":"FUTURE_KIND","id":"$USER_CATEGORY_ID"}]}""",
            )
            assertNull("an unknown ref kind must not load", store(directory, "s1").load("export-1"))
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Issue #337 (AC-11 reverse direction): a v4 record read by an older
     * strict reader fails closed on the unknown key. Pinned here by removing
     * the field from a written record and confirming the strict decoder
     * rejects a record that carries an unexpected property instead of
     * silently ignoring it.
     */
    @Test
    fun recordsCarryingUnknownPropertiesAreRejected() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "s1")
            directory.mkdirs()
            file.writeText(
                """{"schemaVersion":2,"exportId":"export-1",""" +
                    """"itemRefs":[],"tier":"EXTERNAL_REDACTED",""" +
                    """"sourceContextDigest":"${"d".repeat(64)}","signalProvenance":null,""" +
                    """"createdAtEpochMs":1000,"expiresAtEpochMs":${1_000L + ContextExportContract.SESSION_TTL_MS},""" +
                    """"scopeCandidates":[],"scopeCandidateDigest":"","futureField":1}""",
            )
            assertNull("unknown properties are not ignored", store(directory, "s1").load("export-1"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
