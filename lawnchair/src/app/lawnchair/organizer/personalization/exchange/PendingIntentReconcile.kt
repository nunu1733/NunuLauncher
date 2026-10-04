package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CategoryRefKind
import app.lawnchair.organizer.personalization.CompletedPersonalIntent
import app.lawnchair.organizer.personalization.DurableGroupSemantic
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.DurableRefDecision
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.GlobalPreference
import app.lawnchair.organizer.personalization.toDecisionsMap
import app.lawnchair.organizer.planning.CategoryIdentity

/**
 * Issue #374 (spec 374 "読取時reconcile"): the outcome of validating one
 * durable pending record against the active export session before the record
 * is displayed, opened, or referenced. Every failure is fail-closed — the
 * caller invalidates and cleans the record instead of inventing a state.
 */
sealed interface PendingIntentReconcile {

    /** Every condition holds; [proposal] may be shown, opened, and referenced. */
    data class Valid(val proposal: DurablePendingIntent) : PendingIntentReconcile

    /** A condition failed; [proposal] is stale/invalid and must be cleaned (never shown). */
    data class Invalid(val proposal: DurablePendingIntent) : PendingIntentReconcile

    /** No record exists at all. */
    data object Absent : PendingIntentReconcile
}

/**
 * Issue #374 (spec 374 DI-AC-05): the read-time reconcile — the master defense
 * instead of cross-store atomic commits. All conditions fail-closed to
 * [PendingIntentReconcile.Invalid]:
 *
 * (a) the discard tombstone mark, (b) no session / `exportId` mismatch
 * (replacement mid-flight process death lands here), (c) the session TTL (the
 * session's own expiry is authoritative; the record's echo is display-only),
 * (d) structural validity — the record's ref set must equal the session's
 * `itemRefs` keys, every authored `desiredGroupRefs` entry must be a session
 * item ref, and every authored existing-category `categoryRef` must be a
 * session category ref. A structurally mismatched record counts as corruption.
 */
fun reconcilePendingIntent(
    record: DurablePendingIntent?,
    session: ExportSession?,
    nowEpochMs: Long,
): PendingIntentReconcile {
    if (record == null) return PendingIntentReconcile.Absent
    if (record.discarded) return PendingIntentReconcile.Invalid(record)
    if (session == null) return PendingIntentReconcile.Invalid(record)
    if (record.exportId != session.exportId) return PendingIntentReconcile.Invalid(record)
    if (nowEpochMs >= session.expiresAtEpochMs) return PendingIntentReconcile.Invalid(record)

    val sessionItemRefs = session.itemRefs.keys
    if (record.decisions.map { it.ref }.toSet() != sessionItemRefs) {
        return PendingIntentReconcile.Invalid(record)
    }
    for (entry in record.decisions) {
        val authored = entry.decision as? DurableRefDecision.Authored ?: continue
        if (authored.desiredGroupRefs.orEmpty().any { it !in sessionItemRefs }) {
            return PendingIntentReconcile.Invalid(record)
        }
        val categoryRef = (authored.groupSemantic as? DurableGroupSemantic.ExistingCategory)?.categoryRef
        if (categoryRef != null && categoryRef !in session.categoryRefs.keys) {
            return PendingIntentReconcile.Invalid(record)
        }
    }
    return PendingIntentReconcile.Valid(record)
}

/**
 * Issue #374 (spec 374 DI-AC-01/10): reconstructs the import-success summary
 * from the durable record plus the export session — the same pure derivation
 * and the same inputs as [exchangeImportSummary]. The `categoryKindByRef`
 * mapping comes from the session's [CategoryIdentity] discrimination (the
 * session is the only ref-to-identity surface), the scope count from the
 * session's candidates, and the item counts from the record's canonical
 * decisions; `rationale`/`confidence` are structurally absent.
 */
fun durableImportSummary(
    proposal: DurablePendingIntent,
    session: ExportSession,
): ExchangeImportSummary {
    val completed = CompletedPersonalIntent(
        exportId = proposal.exportId,
        decisions = proposal.toDecisionsMap(),
        globalPreference = if (proposal.minimizeMovement) GlobalPreference(minimizeMovement = true) else null,
        rationale = null,
        confidence = null,
    )
    val categoryKindByRef = session.categoryRefs.mapValues { (_, identity) ->
        when (identity) {
            is CategoryIdentity.BuiltIn -> CategoryRefKind.BUILT_IN
            is CategoryIdentity.UserDefined -> CategoryRefKind.USER_DEFINED
        }
    }
    return exchangeImportSummary(
        completed = completed,
        scopeCandidateCount = session.scopeCandidates.size,
        categoryKindByRef = categoryKindByRef,
    )
}
