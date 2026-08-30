package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.canonical.Digest
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId

/**
 * (De)serializes a recovery record (canonical pre-state manifest, intended
 * post-state manifest, action-set digest, reviewed-current manifest/digest for
 * `RESTORING`, lifecycle state, counts, checksums, timestamps) to/from the
 * recovery-DB blob columns. Versioned by the logical record
 * [RECORD_FORMAT_VERSION], which is separate from the physical DB schema
 * version ([RecoveryDbSchema.SCHEMA_VERSION]): schema 3 stores keep logical
 * record format 2 and its checksum bytes (Issue #174, ADR-0009).
 *
 * The codec rejects NULLs in required columns, negative counts/times, unknown
 * lifecycle/reason integers, malformed IDs, non-32-byte digests, and
 * checksum/count mismatches.
 *
 * Issue #14 Stage B step 3.
 */
object RecoveryRecordCodec {

    /**
     * Logical recovery-record format version. Persisted in
     * `recovery_points.format_version` and tombstone `format_version`, included
     * in the payload checksum, and required by [decode] and
     * [app.lawnchair.organizer.application.lifecycle.LifecycleReconciler].
     * Physical schema 3 records keep this value; migration never rewrites it.
     */
    const val RECORD_FORMAT_VERSION: Int = 2

    /**
     * Encoded recovery record. The `payloadChecksum` covers every other field
     * in the [RecoveryDbSchema.CHECKSUM_COLUMNS] order; lifecycle updates
     * recompute it.
     */
    data class Encoded(
        val pointId: RecoveryPointId,
        val runId: RunId,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val lifecycle: LifecycleState,
        val priorLifecycle: LifecycleState?,
        val preManifest: ByteArray,
        val preRevision: RevisionId,
        val preDigest: ByteArray,
        val intendedManifest: ByteArray,
        val intendedDigest: ByteArray,
        val applyActionDigest: ByteArray,
        val reviewedManifest: ByteArray?,
        val reviewedDigest: ByteArray?,
        val recoveryActionDigest: ByteArray?,
        val itemCount: Int,
        val resourceCount: Int,
        val payloadChecksum: ByteArray,
        val formatVersion: Int = RECORD_FORMAT_VERSION,
    )

    /** Decoded recovery record. */
    data class Decoded(
        val pointId: RecoveryPointId,
        val runId: RunId,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val lifecycle: LifecycleState,
        val priorLifecycle: LifecycleState?,
        val preManifest: PersistenceManifest,
        val preRevision: RevisionId,
        val preDigest: ByteArray,
        val intendedManifest: PersistenceManifest,
        val intendedDigest: ByteArray,
        val applyActionDigest: ByteArray,
        val reviewedManifest: PersistenceManifest?,
        val reviewedDigest: ByteArray?,
        val recoveryActionDigest: ByteArray?,
        val itemCount: Int,
        val resourceCount: Int,
        val payloadChecksum: ByteArray,
        val formatVersion: Int,
    ) {
        init {
            require(itemCount >= 0) { "itemCount must be non-negative" }
            require(resourceCount >= 0) { "resourceCount must be non-negative" }
            require(createdAtMs >= 0L) { "createdAtMs must be non-negative" }
            require(updatedAtMs >= createdAtMs) { "updatedAtMs must be >= createdAtMs" }
            require(preDigest.size == Digest.HASH_BYTES_PUBLIC) {
                "preDigest must be ${Digest.HASH_BYTES_PUBLIC} bytes"
            }
            require(intendedDigest.size == Digest.HASH_BYTES_PUBLIC) {
                "intendedDigest must be ${Digest.HASH_BYTES_PUBLIC} bytes"
            }
            require(applyActionDigest.size == Digest.HASH_BYTES_PUBLIC) {
                "applyActionDigest must be ${Digest.HASH_BYTES_PUBLIC} bytes"
            }
            reviewedDigest?.let {
                require(it.size == Digest.HASH_BYTES_PUBLIC) {
                    "reviewedDigest must be ${Digest.HASH_BYTES_PUBLIC} bytes when present"
                }
            }
            recoveryActionDigest?.let {
                require(it.size == Digest.HASH_BYTES_PUBLIC) {
                    "recoveryActionDigest must be ${Digest.HASH_BYTES_PUBLIC} bytes when present"
                }
            }
            require(payloadChecksum.size == Digest.HASH_BYTES_PUBLIC) {
                "payloadChecksum must be ${Digest.HASH_BYTES_PUBLIC} bytes"
            }
        }
    }

    /**
     * Compute the SHA-256 payload checksum over the fixed column order. The
     * format tag is written first, then every column with a length prefix.
     */
    fun computePayloadChecksum(encoded: Encoded): ByteArray {
        val sink = Digest.tagged(Digest.Kind.RECOVERY_PAYLOAD_CHECKSUM)
        sink.text(encoded.pointId.value)
        sink.int(encoded.formatVersion)
        sink.text(encoded.runId.value)
        sink.long(encoded.createdAtMs)
        sink.long(encoded.updatedAtMs)
        sink.int(encoded.lifecycle.canonicalInt)
        val prior = encoded.priorLifecycle
        if (prior == null) {
            sink.byte(0)
        } else {
            sink.byte(1)
            sink.int(prior.canonicalInt)
        }
        sink.bytes(encoded.preManifest)
        sink.text(encoded.preRevision.value)
        sink.bytes(encoded.preDigest)
        sink.bytes(encoded.intendedManifest)
        sink.bytes(encoded.intendedDigest)
        sink.bytes(encoded.applyActionDigest)
        val reviewed = encoded.reviewedManifest
        if (reviewed == null) {
            sink.byte(0)
        } else {
            sink.byte(1)
            sink.bytes(reviewed)
        }
        val reviewedDigest = encoded.reviewedDigest
        if (reviewedDigest == null) {
            sink.byte(0)
        } else {
            sink.byte(1)
            sink.bytes(reviewedDigest)
        }
        val recovery = encoded.recoveryActionDigest
        if (recovery == null) {
            sink.byte(0)
        } else {
            sink.byte(1)
            sink.bytes(recovery)
        }
        sink.int(encoded.itemCount)
        sink.int(encoded.resourceCount)
        return sink.result()
    }

    /**
     * Verify that the persisted payload checksum matches the recomputed value.
     * Spec §“Recovery record and lifecycle”: read-after-write validation.
     */
    fun verifyPayloadChecksum(encoded: Encoded): Boolean {
        val recomputed = computePayloadChecksum(encoded)
        return recomputed.contentEquals(encoded.payloadChecksum)
    }

    /** Validate the complete persisted record before it is exposed to protocol code. */
    fun decode(encoded: Encoded): Decoded {
        require(encoded.formatVersion == RECORD_FORMAT_VERSION) {
            "Unsupported recovery record format: ${encoded.formatVersion}"
        }
        require(verifyPayloadChecksum(encoded)) { "Recovery payload checksum mismatch" }
        require((encoded.reviewedManifest == null) == (encoded.reviewedDigest == null)) {
            "Reviewed manifest and digest must be present together"
        }
        require(encoded.recoveryActionDigest == null || encoded.reviewedManifest != null) {
            "Recovery action digest requires a reviewed manifest"
        }
        val pre = decodeManifest(encoded.preManifest)
        val intended = decodeManifest(encoded.intendedManifest)
        val reviewed = encoded.reviewedManifest?.let(::decodeManifest)
        require(encoded.itemCount == intended.rowCount) { "Recovery item count mismatch" }
        require(encoded.resourceCount == intended.resources.size) { "Recovery resource count mismatch" }
        return Decoded(
            pointId = encoded.pointId,
            runId = encoded.runId,
            createdAtMs = encoded.createdAtMs,
            updatedAtMs = encoded.updatedAtMs,
            lifecycle = encoded.lifecycle,
            priorLifecycle = encoded.priorLifecycle,
            preManifest = pre,
            preRevision = encoded.preRevision,
            preDigest = encoded.preDigest,
            intendedManifest = intended,
            intendedDigest = encoded.intendedDigest,
            applyActionDigest = encoded.applyActionDigest,
            reviewedManifest = reviewed,
            reviewedDigest = encoded.reviewedDigest,
            recoveryActionDigest = encoded.recoveryActionDigest,
            itemCount = encoded.itemCount,
            resourceCount = encoded.resourceCount,
            payloadChecksum = encoded.payloadChecksum,
            formatVersion = encoded.formatVersion,
        )
    }

    fun encodeManifest(manifest: PersistenceManifest): ByteArray {
        require(manifest.formatVersion == MANIFEST_VERSION) {
            "Unsupported manifest format: ${manifest.formatVersion}"
        }
        val out = java.io.ByteArrayOutputStream(estimateManifestSize(manifest))
        val dout = java.io.DataOutputStream(out)
        dout.writeByte(MANIFEST_VERSION)
        dout.writeInt(manifest.formatVersion)
        dout.writeInt(manifest.schemaVersion)
        dout.writeInt(manifest.rowCount)
        dout.writeInt(manifest.rows.size)
        for (row in manifest.rows.sortedBy { it.rowId }) {
            dout.writeLong(row.rowId)
            writeUtf8(dout, row.itemId.value)
            writeUtf8(dout, row.profileId.value)
            dout.writeInt(row.containerCode.value)
            writeNullableString(dout, row.screenId?.value)
            writeNullableInt(dout, row.cellX)
            writeNullableInt(dout, row.cellY)
            writeNullableInt(dout, row.spanX)
            writeNullableInt(dout, row.spanY)
            dout.writeInt(row.rank)
            dout.writeInt(row.itemType.value)
            writeNullableInt(dout, row.appWidgetId?.value)
            writeNullableString(dout, row.appWidgetProvider?.value)
            writeNullableBytes(dout, row.iconBytes)
            writeNullableString(dout, row.title)
            writeNullableString(dout, row.intent)
            writeNullableInt(dout, row.restored)
            writeNullableInt(dout, row.options)
            writeNullableInt(dout, row.appWidgetSource)
            dout.writeLong(row.modified)
            dout.writeByte(row.organizerLockState.ordinal)
            writeNullableGridCell(dout, row.rawCell)
            writeNullableGridSpan(dout, row.rawSpan)
        }
        dout.writeInt(manifest.resources.size)
        for (res in manifest.resources.sortedBy { it.order }) {
            dout.writeByte(res.kind.ordinal)
            if (res.profileId == null) {
                dout.writeByte(0)
            } else {
                dout.writeByte(1)
                writeUtf8(dout, res.profileId.value)
            }
            dout.writeLong(res.order)
            dout.writeInt(res.payload.size)
            dout.write(res.payload)
        }
        dout.writeLong(manifest.modifiedAtMillis)
        return out.toByteArray()
    }

    fun decodeManifest(bytes: ByteArray): PersistenceManifest {
        require(bytes.isNotEmpty()) { "Manifest must not be empty" }
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Manifest exceeds size limit" }
        val din = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        val version = din.readByte().toInt()
        require(version == MANIFEST_VERSION) {
            "Unknown manifest codec version: $version"
        }
        val formatVersion = din.readInt()
        require(formatVersion == MANIFEST_VERSION) {
            "Unsupported manifest format: $formatVersion"
        }
        val schemaVersion = din.readInt()
        val rowCount = din.readInt()
        require(rowCount >= 0) { "rowCount must be non-negative" }
        val rowsSize = din.readInt()
        require(rowsSize >= 0) { "rows size must be non-negative" }
        require(rowsSize == rowCount) { "rowCount must equal rows.size" }
        val rows = ArrayList<app.lawnchair.organizer.application.canonical.PersistentRow>(rowsSize)
        repeat(rowsSize) {
            val rowId = din.readLong()
            val itemId = app.lawnchair.organizer.planning.ItemId(readUtf8(din))
            val profileId = app.lawnchair.organizer.planning.ProfileId(readUtf8(din))
            val containerCode = app.lawnchair.organizer.planning.ContainerCode(din.readInt())
            val screenId = readNullableString(din)?.let { app.lawnchair.organizer.planning.PageId(it) }
            val cellX = readNullableInt(din)
            val cellY = readNullableInt(din)
            val spanX = readNullableInt(din)
            val spanY = readNullableInt(din)
            val rank = din.readInt()
            val itemType = app.lawnchair.organizer.planning.KindCode(din.readInt())
            val appWidgetId = readNullableInt(din)?.let { app.lawnchair.organizer.planning.AppWidgetId(it) }
            val appWidgetProvider = readNullableString(din)?.let {
                app.lawnchair.organizer.planning.ComponentKey(it)
            }
            val iconBytes = readNullableBytes(din)
            val title = readNullableString(din)
            val intent = readNullableString(din)
            val restored = readNullableInt(din)
            val options = readNullableInt(din)
            val appWidgetSource = readNullableInt(din)
            val modified = din.readLong()
            val lockOrdinal = din.readByte().toInt()
            val lockState = enumEntry<app.lawnchair.organizer.application.public.OrganizerLockState>(
                lockOrdinal,
                "organizerLockState",
            )
            val rawCell = readNullableGridCell(din)
            val rawSpan = readNullableGridSpan(din)
            rows += app.lawnchair.organizer.application.canonical.PersistentRow(
                rowId = rowId,
                itemId = itemId,
                profileId = profileId,
                containerCode = containerCode,
                screenId = screenId,
                cellX = cellX,
                cellY = cellY,
                spanX = spanX,
                spanY = spanY,
                rank = rank,
                itemType = itemType,
                appWidgetId = appWidgetId,
                appWidgetProvider = appWidgetProvider,
                iconBytes = iconBytes,
                title = title,
                intent = intent,
                restored = restored,
                options = options,
                appWidgetSource = appWidgetSource,
                modified = modified,
                organizerLockState = lockState,
                rawCell = rawCell,
                rawSpan = rawSpan,
            )
        }
        val resourcesSize = din.readInt()
        require(resourcesSize >= 0) { "resources size must be non-negative" }
        val resources = ArrayList<app.lawnchair.organizer.application.canonical.PersistentResource>(resourcesSize)
        repeat(resourcesSize) {
            val kindOrdinal = din.readByte().toInt()
            val kind = enumEntry<app.lawnchair.organizer.application.canonical.PersistentResourceKind>(
                kindOrdinal,
                "resource kind",
            )
            val hasProfile = readPresence(din, "resource profile")
            val profileId = if (hasProfile) app.lawnchair.organizer.planning.ProfileId(readUtf8(din)) else null
            val order = din.readLong()
            val payloadSize = din.readInt()
            require(payloadSize > 0) { "payload must be non-empty" }
            val payload = ByteArray(payloadSize).also { din.readFully(it) }
            resources += app.lawnchair.organizer.application.canonical.PersistentResource(
                kind = kind,
                profileId = profileId,
                order = order,
                payload = payload,
            )
        }
        val modifiedAtMillis = din.readLong()
        require(din.read() == -1) { "Manifest contains trailing bytes" }
        return PersistenceManifest(
            formatVersion = formatVersion,
            schemaVersion = schemaVersion,
            rowCount = rowCount,
            rows = rows,
            resources = resources,
            modifiedAtMillis = modifiedAtMillis,
        )
    }

    private fun estimateManifestSize(manifest: PersistenceManifest): Int = 64 + manifest.rows.size * 96 + manifest.resources.sumOf { it.payload.size + 16 }

    private fun writeUtf8(dout: java.io.DataOutput, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        dout.writeInt(bytes.size)
        dout.write(bytes)
    }

    private fun readUtf8(din: java.io.DataInput): String {
        val size = din.readInt()
        require(size >= 0) { "UTF-8 length must be non-negative" }
        require(size <= MAX_FIELD_BYTES) { "UTF-8 field exceeds size limit" }
        val bytes = ByteArray(size).also { din.readFully(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeNullableInt(dout: java.io.DataOutput, value: Int?) {
        if (value == null) {
            dout.writeByte(0)
        } else {
            dout.writeByte(1)
            dout.writeInt(value)
        }
    }

    private fun readNullableInt(din: java.io.DataInput): Int? = if (readPresence(din, "nullable int")) din.readInt() else null

    private fun writeNullableString(dout: java.io.DataOutput, value: String?) {
        if (value == null) {
            dout.writeByte(0)
        } else {
            dout.writeByte(1)
            writeUtf8(dout, value)
        }
    }

    private fun readNullableString(din: java.io.DataInput): String? = if (readPresence(din, "nullable string")) readUtf8(din) else null

    private fun writeNullableBytes(dout: java.io.DataOutput, value: ByteArray?) {
        if (value == null) {
            dout.writeByte(0)
        } else {
            dout.writeByte(1)
            dout.writeInt(value.size)
            dout.write(value)
        }
    }

    private fun readNullableBytes(din: java.io.DataInput): ByteArray? {
        if (!readPresence(din, "nullable bytes")) return null
        val size = din.readInt()
        require(size >= 0) { "Byte array length must be non-negative" }
        require(size <= MAX_FIELD_BYTES) { "Byte array exceeds size limit" }
        return ByteArray(size).also(din::readFully)
    }

    private fun writeNullableGridCell(
        dout: java.io.DataOutput,
        value: app.lawnchair.organizer.planning.GridCell?,
    ) {
        if (value == null) {
            dout.writeByte(0)
        } else {
            dout.writeByte(1)
            dout.writeInt(value.x)
            dout.writeInt(value.y)
        }
    }

    private fun readNullableGridCell(
        din: java.io.DataInput,
    ): app.lawnchair.organizer.planning.GridCell? = if (readPresence(din, "raw cell")) {
        app.lawnchair.organizer.planning.GridCell(din.readInt(), din.readInt())
    } else {
        null
    }

    private fun writeNullableGridSpan(
        dout: java.io.DataOutput,
        value: app.lawnchair.organizer.planning.GridSpan?,
    ) {
        if (value == null) {
            dout.writeByte(0)
        } else {
            dout.writeByte(1)
            dout.writeInt(value.width)
            dout.writeInt(value.height)
        }
    }

    private fun readNullableGridSpan(
        din: java.io.DataInput,
    ): app.lawnchair.organizer.planning.GridSpan? = if (readPresence(din, "raw span")) {
        app.lawnchair.organizer.planning.GridSpan(din.readInt(), din.readInt())
    } else {
        null
    }

    private fun readPresence(din: java.io.DataInput, field: String): Boolean = when (val marker = din.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid presence marker for $field: $marker")
    }

    private inline fun <reified T : Enum<T>> enumEntry(ordinal: Int, field: String): T {
        val entries = enumValues<T>()
        require(ordinal in entries.indices) { "Unknown $field ordinal: $ordinal" }
        return entries[ordinal]
    }

    // Recovery DB record v2 is gated by PRAGMA user_version; the manifest wire
    // framing remains v1 because resources are opaque bytes and no v1 DB is
    // opened by a v2 binary before compatibility checks (Issue #155).
    private const val MANIFEST_VERSION: Int = 1
    private const val MAX_MANIFEST_BYTES: Int = 64 * 1024 * 1024
    private const val MAX_FIELD_BYTES: Int = 16 * 1024 * 1024
}
