package app.lawnchair.organizer.integration.exchange

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.DiscardIfResult
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.DurableRefDecision
import app.lawnchair.organizer.personalization.DurableRefEntry
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import app.lawnchair.organizer.personalization.PrivacyTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Issue #374 (spec 374 DI-AC-05 / Contract notes 8): the STARTUP application
 * point of the read-time reconcile — the store-complete, gate-independent
 * early cleanup that runs at the head of
 * `LawnchairApp.ensureOrganizerStartupReconciliation()` even when the hub is
 * never opened. Valid/Absent write nothing; every Invalid condition
 * (exportId mismatch after a mid-replacement process death, an expired
 * session, a tombstone whose physical delete never landed, no session) cleans
 * the record; a load failure never throws.
 */
class PendingImportStartupReconcileTest {

    private class FakeSessionStore(private val session: ExportSession?) : ExportSessionStore {
        override fun save(session: ExportSession): Boolean = error("save is not part of this seam")

        override fun load(exportId: String): ExportSession? = session?.takeIf { it.exportId == exportId }

        override fun active(nowEpochMs: Long): ExportSession? = session?.takeIf { !it.isExpired(nowEpochMs) }

        override fun invalidate(exportId: String) = Unit
    }

    private class FakePendingStore(var record: DurablePendingIntent?) : PendingImportedIntentStore {
        var deletes = 0
        var loadThrows = false

        /**
         * Issue #374 (review finding 3): simulates a file that EXISTS but is
         * unreadable — the real store's read path cleans such residue
         * (best-effort delete) and returns null, which is why this shape
         * "deletes" and reads as absent rather than throwing.
         */
        var residueOnDisk = false

        override fun save(proposal: DurablePendingIntent): Boolean = error("save is not part of this seam")

        override fun load(): DurablePendingIntent? {
            if (loadThrows) throw IllegalStateException("corrupt read")
            if (residueOnDisk) {
                deletes++
                record = null
                return null
            }
            return record
        }

        override fun discard(): Boolean = error("discard is not part of this seam")

        override fun delete() {
            deletes++
            record = null
        }

        override fun discardIf(expected: DurablePendingIntent): DiscardIfResult = error("discardIf is not part of this seam")

        override fun deleteIf(proposal: DurablePendingIntent): Boolean {
            if (record == proposal) {
                record = null
                return true
            }
            return false
        }
    }

    private val validDigest = "a".repeat(64)

    private fun record(
        exportId: String,
        refs: Set<String>,
        expiresAtEpochMs: Long,
        discarded: Boolean = false,
    ): DurablePendingIntent = DurablePendingIntent(
        exportId = exportId,
        // Issue #375: the reconcile's identity-shape check requires the
        // current schema version and a 64-char digest — a corrupt shape is
        // Invalid, so this fixture carries a well-formed identity.
        intentIdentitySchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
        intentIdentityDigest = validDigest,
        decisions = refs.sorted().map { DurableRefEntry(it, DurableRefDecision.UnresolvedByOmission) },
        minimizeMovement = false,
        expiresAtEpochMs = expiresAtEpochMs,
        entryKind = PendingImportEntryKind.IDLE,
        discarded = discarded,
        createdAtEpochMs = 1_000L,
    )

    private fun session(
        exportId: String,
        refs: Set<String>,
        ttlMs: Long = HOUR_MS,
    ): ExportSession = ExportSession(
        exportId = exportId,
        itemRefs = refs.associateWith { app.lawnchair.organizer.planning.ItemId("item-$it") },
        tier = PrivacyTier.EXTERNAL_REDACTED,
        sourceContextDigest = "digest",
        signalProvenance = null,
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 1_000L + ttlMs,
    )

    private fun reconcile(
        pendingStore: FakePendingStore,
        sessionStore: FakeSessionStore,
        nowEpochMs: Long = 2_000L,
    ) = PendingImportStartupReconcile.reconcileAtStartup(
        store = pendingStore,
        sessionStore = sessionStore,
        nowEpochMs = nowEpochMs,
    )

    @Test
    fun validRecordWithAnActiveSessionIsUntouched() {
        val refs = setOf("ref-a", "ref-b")
        val record = record("export", refs, expiresAtEpochMs = 1_000L + HOUR_MS)
        val pendingStore = FakePendingStore(record)
        val sessionStore = FakeSessionStore(session("export", refs))

        reconcile(pendingStore, sessionStore, nowEpochMs = 2_000L)

        assertEquals("a valid record is never cleaned or rewritten", 0, pendingStore.deletes)
        assertSame(record, pendingStore.record)
    }

    @Test
    fun recordWithAMismatchedExportIdIsDeleted() {
        // The mid-replacement process death oracle: the NEW session was saved,
        // the OLD pending invalidation never landed.
        val refs = setOf("ref-a")
        val pendingStore = FakePendingStore(record("old-export", refs, expiresAtEpochMs = 1_000L + HOUR_MS))
        val sessionStore = FakeSessionStore(session("new-export", refs))

        reconcile(pendingStore, sessionStore)

        assertEquals(1, pendingStore.deletes)
        assertNull(pendingStore.record)
    }

    @Test
    fun recordWhoseSessionExpiredIsDeleted() {
        val refs = setOf("ref-a")
        val pendingStore = FakePendingStore(record("export", refs, expiresAtEpochMs = 1_000L + HOUR_MS))
        val sessionStore = FakeSessionStore(session("export", refs))

        reconcile(pendingStore, sessionStore, nowEpochMs = 1_000L + HOUR_MS + 1)

        assertEquals(1, pendingStore.deletes)
        assertNull(pendingStore.record)
    }

    @Test
    fun tombstoneRemainingOnDiskIsDeleted() {
        // The discard-commit oracle: the tombstone committed but the physical
        // delete never landed (process death right after the commit).
        val refs = setOf("ref-a")
        val pendingStore = FakePendingStore(record("export", refs, expiresAtEpochMs = 1_000L + HOUR_MS, discarded = true))
        val sessionStore = FakeSessionStore(session("export", refs))

        reconcile(pendingStore, sessionStore)

        assertEquals(1, pendingStore.deletes)
        assertNull(pendingStore.record)
    }

    @Test
    fun recordWithoutAnySessionIsDeleted() {
        val pendingStore = FakePendingStore(record("export", setOf("ref-a"), expiresAtEpochMs = 1_000L + HOUR_MS))

        reconcile(pendingStore, FakeSessionStore(null))

        assertEquals(1, pendingStore.deletes)
        assertNull(pendingStore.record)
    }

    @Test
    fun absentRecordWritesNothing() {
        val pendingStore = FakePendingStore(null)

        reconcile(pendingStore, FakeSessionStore(session("export", setOf("ref-a"))))

        assertEquals(0, pendingStore.deletes)
        assertNull(pendingStore.record)
    }

    @Test
    fun corruptLoadFailsClosedWithoutThrowing() {
        val pendingStore = FakePendingStore(record("export", setOf("ref-a"), expiresAtEpochMs = 1_000L + HOUR_MS)).apply { loadThrows = true }
        val sessionStore = FakeSessionStore(session("export", setOf("ref-a")))

        // A load failure already degrades to "no record" (nothing to clean);
        // the helper itself never throws so the startup thread continues.
        reconcile(pendingStore, sessionStore)

        assertEquals(0, pendingStore.deletes)
    }

    @Test
    fun unreadableResidueIsPhysicallyCleanedAndNeverShown() {
        // DI-AC-05 (review finding 3): "file exists but garbage" is NOT a
        // silent absence — the store's read path fail-closed cleans the
        // residue, so even a load that reads as absent leaves NO file behind
        // (a later read can never resurrect the record). The startup helper
        // itself writes nothing beyond that.
        val pendingStore = FakePendingStore(record("export", setOf("ref-a"), expiresAtEpochMs = 1_000L + HOUR_MS)).apply { residueOnDisk = true }
        val sessionStore = FakeSessionStore(session("export", setOf("ref-a")))

        reconcile(pendingStore, sessionStore)

        assertEquals("the residue was cleaned by the store's read path", 1, pendingStore.deletes)
        assertNull("no record remains on disk", pendingStore.record)
    }

    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
    }
}
