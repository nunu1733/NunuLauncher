package app.lawnchair.organizer.diagnostics

import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Diagnostics port — the single seam through which the organizer
 * emits diagnostic events.
 *
 * Implementations are responsible for:
 * - Persisting events to the journal.
 * - Rendering persisted events to logcat.
 *
 * The port is fail-open: an implementation must catch and swallow
 * its own exceptions so that diagnostics failure does not affect
 * the organizer operation.
 */
fun interface DiagnosticsPort {
    fun emit(event: RunEvent)

    companion object {
        /** No-op implementation for fail-open default wiring. */
        val NOOP = DiagnosticsPort {}
    }
}
