package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.GlobalPreference
import app.lawnchair.organizer.personalization.IntentIdentity
import app.lawnchair.organizer.personalization.PersonalizedIntentV1
import app.lawnchair.organizer.personalization.RefDecision
import app.lawnchair.organizer.personalization.SourceContextIdentity
import app.lawnchair.organizer.personalization.ValidatedPersonalizedIntent
import app.lawnchair.organizer.personalization.toDecisionsMap

/**
 * Issue #375 (spec "継続時のfail-closed再検証" / disposition §11): the pure
 * seam that rebuilds a planner input from a durable pending proposal for a
 * fresh-run rebind. It reuses the import pipeline's exact verification
 * vocabulary — read-time reconcile, the `sourceContextDigest` equality check
 * (`CONTEXT_STALE` semantics), and export-view reconstruction — and never
 * weakens validation: the reply text is not re-interpreted, no new authority
 * is invented, and the planner input is reconstructed only from the stored
 * canonical decisions plus the session's master ref tables.
 *
 * **Identity is injected, never re-derived** (spec 374 Contract notes 3):
 * the record carries the import-time [IntentIdentity] whose digest covers the
 * canonical representation *including* `rationale`/`confidence` — fields the
 * record deliberately does not persist. Re-deriving would produce a different
 * identity and break the `PersonalizedIntentProjection.identity` → policy
 * provenance equality between import-time and cold-rebind runs; injecting the
 * stored identity keeps every projection field identical.
 */
object RebindIntentRebuilder {

    sealed interface Outcome {
        /**
         * The proposal may continue to fresh run admission — subject to the
         * admission anchor's fresh re-verification (spec Stale state /
         * concurrency). [sourceRecord] is the admission anchor's full-equality
         * reference.
         */
        data class Rebuilt(
            val intent: ValidatedPersonalizedIntent,
            val sourceRecord: DurablePendingIntent,
        ) : Outcome

        /**
         * The proposal is stale, invalid, or corrupt (reconcile `Invalid` or
         * absent). The caller invalidates/cleans per #374 — the proposal is
         * never shown or continued.
         */
        data class InvalidProposal(val record: DurablePendingIntent?) : Outcome

        /**
         * `CONTEXT_STALE` semantics (spec 375 Contract notes 1): the home
         * structure changed since the export, or reconstruction diverged. The
         * proposal itself survives (its validity is subordinate to the
         * session, not to the structure); the only remedy is re-creating the
         * request. Zero-write; no run admission.
         */
        data object ContextStale : Outcome
    }

    fun rebuild(
        record: DurablePendingIntent?,
        session: ExportSession?,
        currentStructural: CanonicalStructuralInputs,
        nowEpochMs: Long,
    ): Outcome {
        // (a) The #374 read-time reconcile is the shared validity verdict —
        // exportId match, TTL, discard mark, ref-set structure, and (since
        // #375) the identity shape, so a corrupt-but-decodable identity can
        // never reach the `IntentIdentity` require below as an exception.
        when (val reconcile = reconcilePendingIntent(record, session, nowEpochMs)) {
            is PendingIntentReconcile.Absent ->
                return Outcome.InvalidProposal(null)

            is PendingIntentReconcile.Invalid ->
                return Outcome.InvalidProposal(reconcile.proposal)

            is PendingIntentReconcile.Valid -> Unit
        }
        val validRecord = record ?: return Outcome.InvalidProposal(null)
        val activeSession = session ?: return Outcome.InvalidProposal(validRecord)

        // (b) Structural freshness — the same check the import pipeline makes
        // at import time (spec 204 `CONTEXT_STALE`), re-applied because #374
        // keeps validated content alive for up to 24h while the structure may
        // have changed. Fail-closed: a skipped check would let the planner
        // projection hit vanished `ItemId` references through `getValue`.
        val currentDigest = SourceContextIdentity.digest(currentStructural)
        if (currentDigest != activeSession.sourceContextDigest) {
            return Outcome.ContextStale
        }

        // (c) Export view reconstruction — the existing seam; divergence maps
        // to the same `CONTEXT_STALE` semantics as the import pipeline.
        val exportView = when (val reconstructed = SessionExportReconstructor.rebuild(activeSession, currentStructural)) {
            is ReconstructionResult.Diverged -> return Outcome.ContextStale
            is ReconstructionResult.Rebuilt -> reconstructed.export
        }

        // (d) Minimal intent document from the canonical decisions. The
        // planner consumes `completed.decisions` (round-trips exactly) and
        // `validated.identity` (injected below) — rationale/confidence never
        // existed here, so they are null by construction. Omitted refs are
        // left out of the document so completion reproduces their canonical
        // `UnresolvedByOmission` provenance.
        val decisions = validRecord.toDecisionsMap()
        val intent = PersonalizedIntentV1(
            exportId = validRecord.exportId,
            itemIntents = decisions.values.filterIsInstance<RefDecision.Authored>().map { it.intent },
            unresolvedRefs = decisions.filterValues { it == RefDecision.UnresolvedAuthored }.keys.toList(),
            globalPreference = if (validRecord.minimizeMovement) GlobalPreference(minimizeMovement = true) else null,
            rationale = null,
            confidence = null,
        )
        val validated = ValidatedPersonalizedIntent(
            intent = intent,
            export = exportView,
            session = activeSession,
            // Identity injection — never `IntentIdentityCalculator.identity(...)`.
            identity = IntentIdentity(
                schemaVersion = validRecord.intentIdentitySchemaVersion,
                digest = validRecord.intentIdentityDigest,
            ),
        )
        return Outcome.Rebuilt(intent = validated, sourceRecord = validRecord)
    }
}
