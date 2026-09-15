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

    /** Persists the session as THE single active session. */
    fun save(session: ExportSession)

    /**
     * Returns the active session holding [exportId], or null when the session
     * is absent, invalidated, expired, unreadable, or of an unsupported
     * schema. Callers must still pass `nowEpochMs` into the validator.
     */
    fun load(exportId: String, nowEpochMs: Long): ExportSession?

    /** The currently active session (any exportId), if valid. */
    fun active(nowEpochMs: Long): ExportSession?

    /** Invalidates the session (invalidated sessions read as absent). */
    fun invalidate(exportId: String)
}
