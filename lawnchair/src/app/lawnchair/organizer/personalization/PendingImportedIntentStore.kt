package app.lawnchair.organizer.personalization

/**
 * Issue #374: the entry kind a durable pending intent was imported through
 * (spec 374 "durable recordの内容" (e)). Persisted so #375's rebind can
 * distinguish an idle-entry proposal (fresh run) from a run-in proposal
 * (selection restore).
 */
enum class PendingImportEntryKind {
    IDLE,
    RUN_IN,
}

/**
 * Issue #374 (spec 374 DI-AC-10): the durable mirror of [GroupSemantic] — the
 * exactly-one-of group semantic of one authored decision. Both arms are kept
 * lossless: [ExistingCategory.categoryRef] is an opaque export-scoped ref that
 * resolves only through the export session, and [ProposedGroup.proposalLabel]
 * is the normalized run-scoped label the planner consumes as its formation key
 * (`ItemPreference.groupProposalLabel`), so Booleanizing it would lose "which
 * items share a proposed group".
 */
sealed interface DurableGroupSemantic {

    /** An existing category, named by an export-scoped ref advertised by the session. */
    data class ExistingCategory(val categoryRef: String) : DurableGroupSemantic {
        init {
            require(categoryRef.isNotEmpty())
        }
    }

    /** A run-scoped proposal group label (see [GroupSemantic.proposalLabel]). */
    data class ProposedGroup(val proposalLabel: String) : DurableGroupSemantic {
        init {
            require(proposalLabel.isNotEmpty())
        }
    }
}

/**
 * Issue #374: the durable mirror of one [RefDecision]. The decided export ref
 * lives in the enclosing [DurableRefEntry] (the map-key equivalent of
 * [CompletedPersonalIntent.decisions]).
 */
sealed interface DurableRefDecision {

    /**
     * Authored semantic fields for the ref (spec 330 D-6: at least one field is
     * set — a bare entry canonicalizes to [UnresolvedAuthored] and is never
     * durable-persisted as authored).
     */
    data class Authored(
        val importance: Importance?,
        val desiredGroupRefs: List<String>?,
        val groupSemantic: DurableGroupSemantic?,
        val pageAffinity: Int?,
        val regionAffinity: ExportRegionKind?,
        val preserve: Boolean?,
    ) : DurableRefDecision {
        init {
            require(
                importance != null ||
                    desiredGroupRefs != null ||
                    groupSemantic != null ||
                    pageAffinity != null ||
                    regionAffinity != null ||
                    preserve != null,
            )
            desiredGroupRefs?.let { require(it.isNotEmpty()) }
        }
    }

    /** Explicitly unresolved, or a bare entry normalized at completion (same canonical decision). */
    data object UnresolvedAuthored : DurableRefDecision

    /** A ref the agent never mentioned (same canonical decision, different provenance). */
    data object UnresolvedByOmission : DurableRefDecision
}

/** Issue #374: one durable decision — the export-scoped ref plus its [DurableRefDecision]. */
data class DurableRefEntry(
    val ref: String,
    val decision: DurableRefDecision,
) {
    init {
        require(ref.isNotEmpty())
    }
}

/**
 * Issue #374 (spec 374 "durable recordの内容（privacy境界の確定）" / DI-AC-10):
 * the durable record of one imported (validated, unapplied) proposal.
 *
 * **Privacy boundary — persisted here:** the canonical decisions (export-scoped
 * refs plus the normalized [DurableGroupSemantic.ProposedGroup.proposalLabel]),
 * the intent content identity ([IntentIdentity] schemaVersion + digest computed
 * at import time over the canonical representation), the planner-effective
 * `minimizeMovement` (the only [GlobalPreference] field), the expiry echo (the
 * export session stays the master for display), the entry kind, the tombstone
 * mark, and the creation time.
 *
 * **NEVER persisted here (DI-AC-10):** `rationale` (agent free text),
 * `confidence` (agent self-report), app labels, folder titles, category display
 * names, and any ref-to-internal-ID mapping (`ExportSession.itemRefs` /
 * `categoryRefs` — the session is the master and resolves refs on read).
 */
data class DurablePendingIntent(
    val exportId: String,
    /** Identity anchor: the export session this proposal answers (1:1, single-active). */
    val intentIdentitySchemaVersion: String,
    /** Import-time digest over the canonical representation (includes fields never persisted here). */
    val intentIdentityDigest: String,
    /** Every export ref exactly once — the canonical decision list (see [DurableRefEntry]). */
    val decisions: List<DurableRefEntry>,
    /** Planner-effective `globalPreference.minimizeMovement == true` at import time. */
    val minimizeMovement: Boolean,
    /** Session echo; the session's own value is the master (read-time reconcile compares against it). */
    val expiresAtEpochMs: Long,
    val entryKind: PendingImportEntryKind,
    /** Tombstone mark: a committed discard keeps the record until its best-effort delete. */
    val discarded: Boolean,
    val createdAtEpochMs: Long,
) {
    init {
        require(exportId.isNotEmpty())
        require(intentIdentitySchemaVersion.isNotEmpty())
        require(intentIdentityDigest.isNotEmpty())
        require(decisions.map { it.ref }.toSet().size == decisions.size)
    }
}

/**
 * Issue #374: the pure seam for the durable pending imported intent store
 * (spec 374 "store契約"). The interface (and the record model) live in the
 * pure personalization package, mirroring [ExportSessionStore]; the
 * Android/file-backed implementation lives in `organizer/integration/` so the
 * purity guard can cover this package without exceptions.
 *
 * Single-active: a successful import overwrites the one prior record. A
 * proposal's validity is subordinate to its export session's validity — the
 * read-time reconcile (spec 374 "読取時reconcile") is the master, not
 * cross-store write ordering.
 */
interface PendingImportedIntentStore {

    /**
     * Persists the proposal as THE single active record (atomic overwrite).
     * Returns false when the durable write failed — the caller must then NOT
     * adopt the import success state and surface a retryable typed failure
     * instead (spec 374 Contract notes 1).
     */
    fun save(proposal: DurablePendingIntent): Boolean

    /**
     * Returns the single active record, or null when absent, corrupt
     * (including a ref-set unreadable through the session), or of an
     * unsupported schema — fail-closed "no proposal"; no invented state.
     * A committed tombstone still loads (the reconcile turns it into Invalid);
     * the physical delete after a tombstone commit is best-effort.
     */
    fun load(): DurablePendingIntent?

    /**
     * User-visible discard — tombstone two-phase commit (spec 374
     * "取り込み破棄のtombstone 2段commit"): atomically rewrites the current
     * record with `discarded = true` and, only if that commit succeeds,
     * best-effort physically deletes it (delete failures are ignored; the
     * discard mark itself blocks re-display).
     *
     * Returns whether the tombstone commit succeeded. A record that failed to
     * commit keeps the proposal valid and retryable. No record present → true
     * (nothing to discard); a corrupt record → plain [delete] and true (a
     * corrupt record cannot be shown, so the discard is vacuously successful).
     */
    fun discard(): Boolean

    /**
     * Plain best-effort physical delete — the reconcile-cleanup and
     * replacement-invalidation path. Read-time reconcile remains the master
     * correctness defense, so no tombstone is needed here.
     */
    fun delete()

    /**
     * Issue #374 (attempt-fenced durable writes): compare-and-delete — removes
     * the stored record ONLY when it is still exactly [proposal] (full data
     * equality), and never touches a newer/different record. Returns whether
     * the delete happened. The caller serializes this against its writes (the
     * holder's pending-write mutex); the implementation only guarantees the
     * read-compare-delete triple is atomic against other store access.
     */
    fun deleteIf(proposal: DurablePendingIntent): Boolean

    /**
     * Issue #375 (spec "gate上への線形化統一"): the CONDITIONAL INVALIDATION
     * COMMIT of one flow-internal attempt invalidation — when the stored
     * record is still exactly [expected] (full data equality), atomically
     * commits the `discarded=true` tombstone (the durable, crash-safe
     * validity truth every reader's reconcile sees) and then best-effort
     * physically deletes it.
     *
     * The result distinguishes the three outcomes the invalidation contract
     * needs: [DiscardIfResult.Committed] and [DiscardIfResult.NoMatch]
     * (absent / corrupt / replaced — nothing stale can resurface) are both
     * invalidation successes; [DiscardIfResult.WriteFailed] means the
     * invalidation did NOT take effect — the proposal stays valid and the
     * commit is retryable (the same fail-closed semantics as a failed user
     * [discard]). A tombstone write failure combined with a process death
     * must never surface as a "successful invalidation".
     *
     * The caller runs it inside the exchange mutation gate; the
     * read-compare-tombstone triple is atomic against other store access.
     */
    fun discardIf(expected: DurablePendingIntent): DiscardIfResult
}

/** Issue #375: the outcome of one conditional invalidation commit ([PendingImportedIntentStore.discardIf]). */
sealed interface DiscardIfResult {
    /** The tombstone was atomically committed — the invalidation is durable. */
    data object Committed : DiscardIfResult

    /** Absent, corrupt, or replaced by a newer record — nothing to invalidate. */
    data object NoMatch : DiscardIfResult

    /** The tombstone rewrite failed; the record survives and stays VALID (retryable). */
    data object WriteFailed : DiscardIfResult
}

/**
 * Issue #374: rebuilds the canonical `CompletedPersonalIntent`-style decisions
 * map from the durable record. Authored entries reconstruct their [ItemIntent]
 * from the durable fields (refs are carried verbatim; the session resolves
 * them); the two unresolved states map to their canonical counterparts, which
 * is identity-relevant only as the shared `unresolved|ref` canonical row.
 */
fun DurablePendingIntent.toDecisionsMap(): Map<String, RefDecision> = decisions.associate { entry ->
    entry.ref to when (val decision = entry.decision) {
        is DurableRefDecision.Authored -> RefDecision.Authored(
            ItemIntent(
                ref = entry.ref,
                importance = decision.importance,
                desiredGroupRefs = decision.desiredGroupRefs,
                groupSemantic = decision.groupSemantic?.let { semantic ->
                    when (semantic) {
                        is DurableGroupSemantic.ExistingCategory ->
                            GroupSemantic(categoryRef = semantic.categoryRef, proposalLabel = null)

                        is DurableGroupSemantic.ProposedGroup ->
                            GroupSemantic(categoryRef = null, proposalLabel = semantic.proposalLabel)
                    }
                },
                pageAffinity = decision.pageAffinity,
                regionAffinity = decision.regionAffinity,
                preserve = decision.preserve,
            ),
        )

        DurableRefDecision.UnresolvedAuthored -> RefDecision.UnresolvedAuthored

        DurableRefDecision.UnresolvedByOmission -> RefDecision.UnresolvedByOmission
    }
}

/**
 * Issue #374: builds the durable record at import success. The decisions are
 * the completed canonical representation (every export ref exactly once, in
 * ref order for a byte-stable record), `minimizeMovement` keeps only the
 * planner-effective value of [GlobalPreference], and the identity is the
 * import-time [IntentIdentity] master — the digest already covers the canonical
 * representation.
 *
 * [CompletedPersonalIntent.rationale] and [CompletedPersonalIntent.confidence]
 * are dropped **by construction**: the record type has no field for them, so
 * agent free text and self-reported scores never reach the durable store
 * (spec 374 DI-AC-10).
 */
fun durablePendingIntentFrom(
    completed: CompletedPersonalIntent,
    identity: IntentIdentity,
    entryKind: PendingImportEntryKind,
    nowEpochMs: Long,
    expiresAtEpochMs: Long,
): DurablePendingIntent = DurablePendingIntent(
    exportId = completed.exportId,
    intentIdentitySchemaVersion = identity.schemaVersion,
    intentIdentityDigest = identity.digest,
    decisions = completed.decisions.entries
        .sortedBy { it.key }
        .map { (ref, decision) ->
            DurableRefEntry(ref = ref, decision = decision.toDurableDecision())
        },
    minimizeMovement = completed.globalPreference?.minimizeMovement == true,
    expiresAtEpochMs = expiresAtEpochMs,
    entryKind = entryKind,
    discarded = false,
    createdAtEpochMs = nowEpochMs,
)

private fun RefDecision.toDurableDecision(): DurableRefDecision = when (this) {
    is RefDecision.Authored -> DurableRefDecision.Authored(
        importance = intent.importance,
        desiredGroupRefs = intent.desiredGroupRefs,
        groupSemantic = intent.groupSemantic?.let { semantic ->
            when {
                semantic.categoryRef != null ->
                    DurableGroupSemantic.ExistingCategory(semantic.categoryRef)

                else -> DurableGroupSemantic.ProposedGroup(semantic.proposalLabel ?: error("exactly-one-of groupSemantic"))
            }
        },
        pageAffinity = intent.pageAffinity,
        regionAffinity = intent.regionAffinity,
        preserve = intent.preserve,
    )

    RefDecision.UnresolvedAuthored -> DurableRefDecision.UnresolvedAuthored

    RefDecision.UnresolvedByOmission -> DurableRefDecision.UnresolvedByOmission
}
