package app.lawnchair.organizer.personalization

/**
 * Issue #417 (spec 417 "Data and state"): the durable entry origin of an
 * export session — whether the request was authored inside a run with an
 * explicit scope selection ([RUN_IN]) or outside one ([IDLE]). Written exactly
 * once at session creation (save) and immutable afterwards; absent (`null` in
 * [ExportSession.entryOrigin]) on records written before #417, which the
 * read-side legacy decode rule on [ExportSession.resolvedEntryOrigin] recovers.
 * `entryKind`'s authorship derives from this origin — runtime run-state
 * estimation is retired — while the pending store's own entry-kind type and
 * records stay unchanged.
 */
enum class ExportEntryOrigin {
    IDLE,
    RUN_IN,
}

/**
 * Issue #204: the pure seam for durable, expiring export sessions
 * (spec 204 "export session"). The interface (and the session model) live in
 * the pure personalization package; the Android/file-backed implementation
 * lives in `organizer/integration/` so the purity guard can cover this package
 * without exceptions.
 *
 * V1 is single-active: a newly created export session invalidates all prior
 * sessions (new export → old export's intents are `EXPORT_MISMATCH`).
 */
interface ExportSessionStore {

    /**
     * Persists the session as THE single active session. Returns false when
     * the durable write failed (publishing the export must then be suppressed
     * fail-closed by the caller — AC-11's durable-session premise).
     */
    fun save(session: ExportSession): Boolean

    /**
     * Returns the session holding [exportId] — **including an expired one** —
     * or null when it is absent, invalidated, unreadable, or of an unsupported
     * schema. Expiry is NOT collapsed into absence here: the validator
     * distinguishes the expired matching record (`SESSION_EXPIRED`) from an
     * unknown/old session (`EXPORT_MISMATCH`) via [ExportSession.isExpired].
     */
    fun load(exportId: String): ExportSession?

    /** The currently active (unexpired) session, if any. */
    fun active(nowEpochMs: Long): ExportSession?

    /** Invalidates the session (invalidated sessions read as absent). */
    fun invalidate(exportId: String)

    /**
     * Issue #417 (spec 417 "scope-bound依頼破棄の契約化" / spec 204 Amend): the
     * failure-aware conditional invalidation commit — the session-store mirror
     * of the pending store's `discardIf` (spec 375). Invalidates THE single
     * active session only when it is still exactly [expectedExportId], atomic
     * against other store access, and observably:
     * [ExportInvalidationResult.Committed] and [ExportInvalidationResult.NoMatch]
     * (absent / unreadable / replaced — nothing stale can resurface) are both
     * invalidation successes; [ExportInvalidationResult.WriteFailed] means the
     * invalidation did NOT take effect — the session stays valid and the
     * commit is retryable. A write failure combined with a process death must
     * never surface as a successful invalidation. The caller runs it inside
     * the exchange mutation gate.
     */
    fun invalidateIf(expectedExportId: String): ExportInvalidationResult
}

/**
 * Issue #417: the outcome of one conditional invalidation commit
 * ([ExportSessionStore.invalidateIf]) — the session-store mirror of the
 * pending store's `DiscardIfResult` (spec 375).
 */
sealed interface ExportInvalidationResult {
    /** The tombstone was atomically committed — the invalidation is durable. */
    data object Committed : ExportInvalidationResult

    /** Absent, unreadable, already invalidated, or replaced — nothing to invalidate. */
    data object NoMatch : ExportInvalidationResult

    /** The tombstone rewrite failed; the session survives and stays VALID (retryable). */
    data object WriteFailed : ExportInvalidationResult
}
