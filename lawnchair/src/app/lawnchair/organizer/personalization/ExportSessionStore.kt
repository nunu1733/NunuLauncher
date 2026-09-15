package app.lawnchair.organizer.personalization

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
}
