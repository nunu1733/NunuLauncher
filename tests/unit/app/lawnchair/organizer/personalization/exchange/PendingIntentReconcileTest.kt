package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.DurableGroupSemantic
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.DurableRefDecision
import app.lawnchair.organizer.personalization.DurableRefEntry
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ItemId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #374 DI-AC-05 (reconcile pure-function part): table-driven read-time
 * reconcile. Every mismatch — tombstone mark, missing session, exportId
 * mismatch (replacement mid-flight), session expiry, ref-set mismatch,
 * unknown desiredGroupRef, unknown categoryRef — fails closed to Invalid;
 * only a matching, unexpired, structurally consistent record is Valid.
 */
class PendingIntentReconcileTest {

    private val now = 2_000L

    private val session = ExportSession(
        exportId = "export-1",
        itemRefs = mapOf(
            "ref-a" to ItemId("item-1"),
            "ref-b" to ItemId("item-2"),
            "ref-c" to ItemId("item-3"),
        ),
        tier = PrivacyTier.LOCAL_FULL,
        sourceContextDigest = "d".repeat(64),
        signalProvenance = null,
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 5_000L,
        categoryRefs = mapOf("cat-1" to CategoryIdentity.BuiltIn(CategoryId("GAMES"))),
    )

    private fun record(vararg decisions: DurableRefEntry) = DurablePendingIntent(
        exportId = "export-1",
        intentIdentitySchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
        intentIdentityDigest = "a".repeat(64),
        decisions = decisions.toList(),
        minimizeMovement = true,
        expiresAtEpochMs = session.expiresAtEpochMs,
        entryKind = PendingImportEntryKind.IDLE,
        discarded = false,
        createdAtEpochMs = 1_000L,
    )

    private val validRecord = record(
        DurableRefEntry(
            ref = "ref-a",
            decision = DurableRefDecision.Authored(
                importance = Importance.HIGH,
                desiredGroupRefs = listOf("ref-b"),
                groupSemantic = DurableGroupSemantic.ExistingCategory("cat-1"),
                pageAffinity = 0,
                regionAffinity = ExportRegionKind.TOP,
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
                regionAffinity = null,
                preserve = null,
            ),
        ),
        DurableRefEntry("ref-c", DurableRefDecision.UnresolvedAuthored),
    )

    @Test
    fun aMatchingUnexpiredRecordIsReconciledAsValid() {
        val result = reconcilePendingIntent(validRecord, session, now)
        assertEquals(PendingIntentReconcile.Valid(validRecord), result)
    }

    @Test
    fun reconcileTableIsFailClosedForEveryMismatch() {
        val table = listOf(
            "discarded tombstone mark" to reconcilePendingIntent(validRecord.copy(discarded = true), session, now),
            "no active session" to reconcilePendingIntent(validRecord, null, now),
            "exportId mismatch (replacement mid-flight)" to
                reconcilePendingIntent(validRecord, session.copy(exportId = "export-2"), now),
            "session expired" to reconcilePendingIntent(validRecord, session, session.expiresAtEpochMs),
            "session expired one ms later" to reconcilePendingIntent(validRecord, session, session.expiresAtEpochMs + 1),
            "missing decision ref (ref-set mismatch)" to
                reconcilePendingIntent(
                    record(*validRecord.decisions.drop(1).toTypedArray()),
                    session,
                    now,
                ),
            "extra decision ref (ref-set mismatch)" to
                reconcilePendingIntent(
                    record(*(validRecord.decisions.toTypedArray() + DurableRefEntry("ref-x", DurableRefDecision.UnresolvedByOmission))),
                    session,
                    now,
                ),
            "unknown desiredGroupRef" to
                reconcilePendingIntent(
                    record(
                        DurableRefEntry(
                            "ref-a",
                            DurableRefDecision.Authored(
                                importance = Importance.NORMAL,
                                desiredGroupRefs = listOf("ref-zzz"),
                                groupSemantic = null,
                                pageAffinity = null,
                                regionAffinity = null,
                                preserve = null,
                            ),
                        ),
                        DurableRefEntry("ref-b", DurableRefDecision.UnresolvedByOmission),
                        DurableRefEntry("ref-c", DurableRefDecision.UnresolvedByOmission),
                    ),
                    session,
                    now,
                ),
            "unknown categoryRef" to
                reconcilePendingIntent(
                    record(
                        DurableRefEntry(
                            "ref-a",
                            DurableRefDecision.Authored(
                                importance = null,
                                desiredGroupRefs = null,
                                groupSemantic = DurableGroupSemantic.ExistingCategory("cat-x"),
                                pageAffinity = null,
                                regionAffinity = null,
                                preserve = null,
                            ),
                        ),
                        DurableRefEntry("ref-b", DurableRefDecision.UnresolvedByOmission),
                        DurableRefEntry("ref-c", DurableRefDecision.UnresolvedByOmission),
                    ),
                    session,
                    now,
                ),
        )
        for ((case, result) in table) {
            assertEquals("case '$case' must fail closed", PendingIntentReconcile.Invalid::class.java, result::class.java)
        }
        // Each Invalid carries its own record back (the caller cleans THAT record).
        val discardedCase = reconcilePendingIntent(validRecord.copy(discarded = true), session, now)
        assertEquals(validRecord.copy(discarded = true), (discardedCase as PendingIntentReconcile.Invalid).proposal)
    }

    @Test
    fun aNullRecordIsAbsentNotInvalid() {
        assertEquals(PendingIntentReconcile.Absent, reconcilePendingIntent(null, session, now))
        assertEquals(PendingIntentReconcile.Absent, reconcilePendingIntent(null, null, now))
    }
}
