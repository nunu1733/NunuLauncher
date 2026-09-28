package app.lawnchair.organizer.application.public

/**
 * Issue #376 (D-15): coarse remaining-retention window for the selected
 * recovery point. Hours are floored and clamped to the 24h retention budget
 * (spec 13); anything under a full hour is a distinct closed value. No
 * absolute time ever crosses the application boundary.
 */
sealed interface RemainingWindow {
    data class HoursRemaining(val value: Int) : RemainingWindow
    data object LessThanOneHour : RemainingWindow
}

/**
 * Issue #376 (D-15): the closed UI-facing hint for the restore entry on the
 * hub status card. [pointId] is the existing opaque recovery correlation key
 * (spec 84) — it is never rendered, logged, or persisted; only the
 * coordinator may hold it to start the existing inspection seam.
 */
data class RestorableRecoveryEntry(
    val pointId: RecoveryPointId,
    val remainingWindow: RemainingWindow,
)
