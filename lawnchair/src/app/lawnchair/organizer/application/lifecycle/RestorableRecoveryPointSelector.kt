package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RemainingWindow
import app.lawnchair.organizer.application.public.RestorableRecoveryEntry

/**
 * Issue #376 (D-15): pure selection of the restore entry — the latest
 * checksum-valid `VERIFIED` record within retention. Mirrors the
 * [OrganizerDurableStatusDeriver] restorable premise (`VERIFIED` +
 * `checksumValid` + `createdAt + RETENTION_MILLIS > now`); the #84
 * inspection re-validates format version, lifecycle and retention, so this
 * selection is only a hint and the inspection stays the authoritative gate.
 *
 * No I/O, no Android types; the protocol layer maps the inspection snapshot
 * into the minimal input type and supplies the clock.
 */
object RestorableRecoveryPointSelector {

    /** Minimal record metadata the selection may observe. */
    data class CandidateRecord(
        val pointId: RecoveryPointId,
        val lifecycle: LifecycleState,
        val createdAtMs: Long,
        val checksumValid: Boolean,
    )

    fun select(records: List<CandidateRecord>, nowMs: Long): RestorableRecoveryEntry? {
        val latest = records
            .asSequence()
            .filter {
                it.lifecycle == LifecycleState.VERIFIED &&
                    it.checksumValid &&
                    nowMs < it.createdAtMs + RetentionPolicy.RETENTION_MILLIS
            }
            .maxWithOrNull(
                compareBy({ it.createdAtMs }, { it.pointId.value }),
            )
            ?: return null
        val remainingMs = latest.createdAtMs + RetentionPolicy.RETENTION_MILLIS - nowMs
        val hours = (remainingMs / MILLIS_PER_HOUR).toInt()
        val window = if (hours >= 1) {
            RemainingWindow.HoursRemaining(hours.coerceAtMost((RetentionPolicy.RETENTION_MILLIS / MILLIS_PER_HOUR).toInt()))
        } else {
            RemainingWindow.LessThanOneHour
        }
        return RestorableRecoveryEntry(pointId = latest.pointId, remainingWindow = window)
    }

    private const val MILLIS_PER_HOUR: Long = 3_600_000L
}
