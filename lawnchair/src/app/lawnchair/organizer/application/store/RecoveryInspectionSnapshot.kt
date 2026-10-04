package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.RecoveryPointId

/**
 * Non-authoritative, bounded recovery-store projection used only by #84
 * inspection. It deliberately excludes manifests, revisions, digests, rows,
 * payloads, and any UI-facing values.
 */
internal data class RecoveryInspectionSnapshot(
    val generation: Long,
    val records: List<Record>,
    val tombstones: List<Tombstone>,
) {
    data class Record(
        val pointId: RecoveryPointId,
        val lifecycle: LifecycleState,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val checksumValid: Boolean,
        val formatVersion: Int,
    )

    data class Tombstone(
        val pointId: RecoveryPointId,
        val reason: RecoveryStorePort.TombstoneReason,
        val expiresAtMs: Long,
    )

    fun record(pointId: RecoveryPointId): Record? = records.firstOrNull { it.pointId == pointId }

    fun tombstone(pointId: RecoveryPointId): Tombstone? = tombstones.firstOrNull { it.pointId == pointId }
}

internal fun RecoveryInspectionSnapshot.project(pointId: RecoveryPointId): RecoveryStorePort.InspectionProjection = record(pointId)?.let {
    RecoveryStorePort.InspectionProjection.Record(
        pointId = it.pointId,
        lifecycle = it.lifecycle,
        createdAtMs = it.createdAtMs,
        updatedAtMs = it.updatedAtMs,
        checksumValid = it.checksumValid,
        formatVersion = it.formatVersion,
    )
} ?: tombstone(pointId)?.let {
    RecoveryStorePort.InspectionProjection.Tombstone(
        pointId = it.pointId,
        reason = it.reason,
        expiresAtMs = it.expiresAtMs,
    )
} ?: RecoveryStorePort.InspectionProjection.Missing
