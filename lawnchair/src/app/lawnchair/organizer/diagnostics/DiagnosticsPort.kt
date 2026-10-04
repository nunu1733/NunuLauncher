package app.lawnchair.organizer.diagnostics

import app.lawnchair.organizer.diagnostics.model.RunEvent

/**
 * Diagnostics port — the single seam through which the organizer
 * emits diagnostic events.
 *
 * Implementations are responsible for:
 * - Persisting events to the journal.
 * - Rendering persisted events to logcat.
 * - Providing a stable snapshot of the journal for export.
 *
 * The port is fail-open: an implementation must catch and swallow
 * its own exceptions so that diagnostics failure does not affect
 * the organizer operation.
 */
interface DiagnosticsPort {
    fun emit(event: RunEvent)

    /**
     * Return a stable, point-in-time snapshot of all persisted events.
     * The snapshot must be consistent with the live journal (mutual
     * exclusion with concurrent [emit] calls).
     */
    fun snapshot(): List<RunEvent>

    companion object {
        /** No-op implementation for fail-open default wiring. */
        val NOOP = object : DiagnosticsPort {
            override fun emit(event: RunEvent) = Unit
            override fun snapshot(): List<RunEvent> = emptyList()
        }
    }
}
