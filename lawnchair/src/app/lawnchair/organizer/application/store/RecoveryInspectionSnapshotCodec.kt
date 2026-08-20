package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.RecoveryPointId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical, bounded binary codec for the derived inspection projection. */
internal object RecoveryInspectionSnapshotCodec {
    private const val MAGIC = 0x52493839 // "RI89"
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32
    private const val MAX_ENTRIES = 4_096
    private const val MAX_BYTES = 1_048_576

    fun encode(snapshot: RecoveryInspectionSnapshot): ByteArray {
        require(snapshot.generation > 0L) { "generation must be positive" }
        val records = snapshot.records.sortedBy { it.pointId.value }
        val tombstones = snapshot.tombstones.sortedBy { it.pointId.value }
        require(records.size <= MAX_ENTRIES && tombstones.size <= MAX_ENTRIES) { "too many entries" }
        require(records.map { it.pointId.value }.distinct().size == records.size) { "duplicate record id" }
        require(tombstones.map { it.pointId.value }.distinct().size == tombstones.size) { "duplicate tombstone id" }
        require(records.none { record -> tombstones.any { it.pointId == record.pointId } }) { "point in record and tombstone" }

        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(snapshot.generation)
                out.writeInt(records.size)
                records.forEach { record ->
                    writeId(out, record.pointId)
                    out.writeInt(record.lifecycle.canonicalInt)
                    out.writeLong(record.createdAtMs)
                    out.writeLong(record.updatedAtMs)
                    out.writeBoolean(record.checksumValid)
                    out.writeInt(record.formatVersion)
                }
                out.writeInt(tombstones.size)
                tombstones.forEach { tombstone ->
                    writeId(out, tombstone.pointId)
                    out.writeInt(tombstone.reason.canonicalInt())
                    out.writeLong(tombstone.expiresAtMs)
                }
            }
            bytes.toByteArray()
        }
        require(body.size <= MAX_BYTES - CHECKSUM_BYTES) { "snapshot too large" }
        return body + sha256(body)
    }

    fun decode(bytes: ByteArray): RecoveryInspectionSnapshot? {
        if (bytes.size !in (CHECKSUM_BYTES + 20)..MAX_BYTES) return null
        val bodySize = bytes.size - CHECKSUM_BYTES
        val body = bytes.copyOfRange(0, bodySize)
        val checksum = bytes.copyOfRange(bodySize, bytes.size)
        if (!MessageDigest.isEqual(sha256(body), checksum)) return null
        return try {
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val generation = input.readLong()
                if (generation <= 0L) return null
                val recordCount = input.readInt()
                if (recordCount !in 0..MAX_ENTRIES) return null
                val records = ArrayList<RecoveryInspectionSnapshot.Record>(recordCount)
                repeat(recordCount) {
                    records += RecoveryInspectionSnapshot.Record(
                        pointId = readId(input) ?: return null,
                        lifecycle = LifecycleState.fromCanonicalInt(input.readInt()),
                        createdAtMs = input.readLong(),
                        updatedAtMs = input.readLong(),
                        checksumValid = input.readBoolean(),
                        formatVersion = input.readInt(),
                    )
                }
                val tombstoneCount = input.readInt()
                if (tombstoneCount !in 0..MAX_ENTRIES) return null
                val tombstones = ArrayList<RecoveryInspectionSnapshot.Tombstone>(tombstoneCount)
                repeat(tombstoneCount) {
                    tombstones += RecoveryInspectionSnapshot.Tombstone(
                        pointId = readId(input) ?: return null,
                        reason = tombstoneReason(input.readInt()) ?: return null,
                        expiresAtMs = input.readLong(),
                    )
                }
                if (input.available() != 0) return null
                if (!isCanonical(records, tombstones)) return null
                RecoveryInspectionSnapshot(generation, records, tombstones)
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isCanonical(
        records: List<RecoveryInspectionSnapshot.Record>,
        tombstones: List<RecoveryInspectionSnapshot.Tombstone>,
    ): Boolean {
        if (records.zipWithNext().any { (left, right) -> left.pointId.value >= right.pointId.value }) return false
        if (tombstones.zipWithNext().any { (left, right) -> left.pointId.value >= right.pointId.value }) return false
        return records.none { record -> tombstones.any { it.pointId == record.pointId } }
    }

    private fun writeId(out: DataOutputStream, pointId: RecoveryPointId) {
        val value = pointId.value.toByteArray(StandardCharsets.US_ASCII)
        require(value.size == 32) { "non-canonical point id" }
        out.write(value)
    }

    private fun readId(input: DataInputStream): RecoveryPointId? {
        val bytes = ByteArray(32)
        input.readFully(bytes)
        val value = String(bytes, StandardCharsets.US_ASCII)
        return try {
            RecoveryPointId(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun RecoveryStorePort.TombstoneReason.canonicalInt(): Int = when (this) {
        RecoveryStorePort.TombstoneReason.CORRUPT -> 1
        RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION -> 2
        RecoveryStorePort.TombstoneReason.ALREADY_RESTORED -> 3
        RecoveryStorePort.TombstoneReason.EXPIRED -> 4
        RecoveryStorePort.TombstoneReason.PRUNED_UNUSED -> 5
    }

    private fun tombstoneReason(value: Int): RecoveryStorePort.TombstoneReason? = when (value) {
        1 -> RecoveryStorePort.TombstoneReason.CORRUPT
        2 -> RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION
        3 -> RecoveryStorePort.TombstoneReason.ALREADY_RESTORED
        4 -> RecoveryStorePort.TombstoneReason.EXPIRED
        5 -> RecoveryStorePort.TombstoneReason.PRUNED_UNUSED
        else -> null
    }
}
