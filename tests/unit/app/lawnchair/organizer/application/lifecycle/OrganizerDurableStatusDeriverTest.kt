package app.lawnchair.organizer.application.lifecycle

import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.OrganizerDurableStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixture/boundary coverage of the pure durable-status derivation (Issue #271,
 * DS-AC-04/DS-AC-05): the closed mapping over record lifecycles, checksum
 * validity, retention boundaries, and tombstone reasons.
 */
class OrganizerDurableStatusDeriverTest {

    private fun record(
        lifecycle: LifecycleState,
        createdAtMs: Long = 1_000L,
        updatedAtMs: Long = createdAtMs,
        checksumValid: Boolean = true,
    ) = OrganizerDurableStatusDeriver.DurableRecord(lifecycle, createdAtMs, updatedAtMs, checksumValid)

    private fun tombstone(
        reason: RecoveryStorePort.TombstoneReason,
        expiresAtMs: Long = 2_000_000L,
    ) = OrganizerDurableStatusDeriver.DurableTombstone(reason, expiresAtMs)

    private fun derive(
        records: List<OrganizerDurableStatusDeriver.DurableRecord> = emptyList(),
        tombstones: List<OrganizerDurableStatusDeriver.DurableTombstone> = emptyList(),
        nowMs: Long = 60_000L,
    ) = OrganizerDurableStatusDeriver.derive(records, tombstones, nowMs)

    @Test
    fun emptyStoreDerivesNeverOrganized() {
        assertEquals(OrganizerDurableStatus.NEVER_ORGANIZED, derive())
    }

    @Test
    fun verifiedWithinRetentionIsRestorable() {
        val created = 1_000L
        assertEquals(
            OrganizerDurableStatus.ORGANIZED_RESTORABLE,
            derive(records = listOf(record(LifecycleState.VERIFIED, createdAtMs = created)), nowMs = created + RetentionPolicy.RETENTION_MILLIS - 1),
        )
    }

    @Test
    fun verifiedAtExactRetentionBoundaryIsNoLongerRestorable() {
        val created = 1_000L
        assertEquals(
            OrganizerDurableStatus.RESTORED_OR_EXPIRED,
            derive(records = listOf(record(LifecycleState.VERIFIED, createdAtMs = created)), nowMs = created + RetentionPolicy.RETENTION_MILLIS),
        )
    }

    @Test
    fun lapsedVerifiedRowDerivesRestoredOrExpiredBeforeEviction() {
        val created = 1_000L
        assertEquals(
            OrganizerDurableStatus.RESTORED_OR_EXPIRED,
            derive(records = listOf(record(LifecycleState.VERIFIED, createdAtMs = created)), nowMs = created + RetentionPolicy.RETENTION_MILLIS + 5),
        )
    }

    @Test
    fun checksumInvalidVerifiedDerivesUnresolved() {
        assertEquals(
            OrganizerDurableStatus.UNRESOLVED,
            derive(records = listOf(record(LifecycleState.VERIFIED, checksumValid = false))),
        )
    }

    @Test
    fun checksumInvalidVerifiedOutranksARestorableVerifiedRow() {
        assertEquals(
            OrganizerDurableStatus.UNRESOLVED,
            derive(records = listOf(record(LifecycleState.VERIFIED), record(LifecycleState.VERIFIED, checksumValid = false))),
        )
    }

    @Test
    fun nonFinalLifecyclesDeriveUnresolved() {
        for (lifecycle in listOf(
            LifecycleState.CREATING,
            LifecycleState.READY,
            LifecycleState.APPLYING,
            LifecycleState.COMMITTED_UNVERIFIED,
            LifecycleState.RESTORING,
        )) {
            assertEquals(lifecycle.name, OrganizerDurableStatus.UNRESOLVED, derive(records = listOf(record(lifecycle))))
        }
    }

    @Test
    fun finalCorruptAndIncompatibleRowsDeriveUnresolved() {
        assertEquals(OrganizerDurableStatus.UNRESOLVED, derive(records = listOf(record(LifecycleState.CORRUPT))))
        assertEquals(OrganizerDurableStatus.UNRESOLVED, derive(records = listOf(record(LifecycleState.INCOMPATIBLE))))
    }

    @Test
    fun unresolvedRowOutranksARestorableVerifiedRow() {
        assertEquals(
            OrganizerDurableStatus.UNRESOLVED,
            derive(records = listOf(record(LifecycleState.VERIFIED), record(LifecycleState.CORRUPT))),
        )
    }

    @Test
    fun restoredAndExpiredRowsDeriveRestoredOrExpired() {
        assertEquals(OrganizerDurableStatus.RESTORED_OR_EXPIRED, derive(records = listOf(record(LifecycleState.RESTORED))))
        assertEquals(OrganizerDurableStatus.RESTORED_OR_EXPIRED, derive(records = listOf(record(LifecycleState.EXPIRED))))
    }

    @Test
    fun restorableVerifiedOutranksAFinalRestoredRow() {
        assertEquals(
            OrganizerDurableStatus.ORGANIZED_RESTORABLE,
            derive(records = listOf(record(LifecycleState.VERIFIED), record(LifecycleState.RESTORED))),
        )
    }

    @Test
    fun restoredAndExpiredTombstonesDeriveRestoredOrExpiredWithinRetention() {
        assertEquals(
            OrganizerDurableStatus.RESTORED_OR_EXPIRED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.ALREADY_RESTORED))),
        )
        assertEquals(
            OrganizerDurableStatus.RESTORED_OR_EXPIRED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.EXPIRED))),
        )
    }

    @Test
    fun expiredTombstoneOutsideRetentionDerivesNeverOrganized() {
        assertEquals(
            OrganizerDurableStatus.NEVER_ORGANIZED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.EXPIRED, expiresAtMs = 60_000L)), nowMs = 60_000L),
        )
        assertEquals(
            OrganizerDurableStatus.RESTORED_OR_EXPIRED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.EXPIRED, expiresAtMs = 60_001L)), nowMs = 60_000L),
        )
    }

    @Test
    fun corruptAndIncompatibleTombstonesDeriveUnresolved() {
        assertEquals(
            OrganizerDurableStatus.UNRESOLVED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.CORRUPT))),
        )
        assertEquals(
            OrganizerDurableStatus.UNRESOLVED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION))),
        )
    }

    @Test
    fun prunedAndQuarantinedTombstonesDeriveNeverOrganized() {
        assertEquals(
            OrganizerDurableStatus.NEVER_ORGANIZED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.PRUNED_UNUSED))),
        )
        assertEquals(
            OrganizerDurableStatus.NEVER_ORGANIZED,
            derive(tombstones = listOf(tombstone(RecoveryStorePort.TombstoneReason.QUARANTINED))),
        )
    }

    @Test
    fun removingTheRecordInvalidatesTheRestorableProjection() {
        val records = listOf(record(LifecycleState.VERIFIED))
        assertEquals(OrganizerDurableStatus.ORGANIZED_RESTORABLE, derive(records = records))
        assertEquals(OrganizerDurableStatus.NEVER_ORGANIZED, derive(records = records.drop(1)))
    }
}
