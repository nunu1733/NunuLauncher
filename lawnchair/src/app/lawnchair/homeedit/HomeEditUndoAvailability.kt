/*
 * Issue #450: pure availability input of the remove-undo verification
 * (spec 450 Scope「現在状態との照合」). The planner is a pure function of the
 * snapshot, the entry, and this value; the production determination lives in
 * HomeEditUndoVerifier and is shared verbatim by stage 1 and stage 2.
 */
package app.lawnchair.homeedit

enum class HomeEditUndoAvailability {
    /** The launch target (app component / deep shortcut) is present. */
    AVAILABLE,

    /** The launch target is gone (uninstalled / shortcut removed). */
    UNAVAILABLE,

    /**
     * The verification itself failed (binder / security / platform read
     * failure). Fail-closed: treated exactly like [UNAVAILABLE] — the row is
     * never resurrected on an unverifiable precondition.
     */
    UNKNOWN,
}
