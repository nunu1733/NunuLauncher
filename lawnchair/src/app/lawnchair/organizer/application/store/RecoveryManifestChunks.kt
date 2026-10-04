package app.lawnchair.organizer.application.store

import android.database.sqlite.SQLiteDatabase
import app.lawnchair.organizer.application.public.RecoveryPointId

/**
 * Store-internal chunked manifest-slot storage for physical schema 3
 * (Issue #174, ADR-0009).
 *
 * Manifest bytes live as fixed-size chunk rows in
 * [RecoveryDbSchema.TABLE_MANIFEST_CHUNKS]; every physical row — record row
 * and chunk row — stays far below Android SQLite's 2 MB `CursorWindow`.
 * Assembly validates the exact chunk shape against the strictly positive
 * slot size persisted in the record row before any byte is exposed.
 *
 * Every function runs inside the caller's transaction. Chunk ownership is
 * explicit: deletion goes through [deletePoint] (child-first, same
 * transaction); no foreign key is declared or assumed.
 */
internal object RecoveryManifestChunks {

    /**
     * Pure assembly validation: the slot is readable only when the chunk rows
     * are contiguous, zero-based, exactly [RecoveryDbSchema.CHUNK_BYTES] long
     * except the final chunk, and reconstruct exactly [expectedSize] bytes.
     * Returns null for any missing/malformed/duplicate/short slot. JVM-testable.
     */
    fun assemble(expectedSize: Int, chunks: List<Pair<Int, ByteArray>>): ByteArray? {
        if (expectedSize !in 1..RecoveryDbSchema.MAX_MANIFEST_BYTES) return null
        if (chunks.isEmpty()) return null
        val chunkBytes = RecoveryDbSchema.CHUNK_BYTES
        val requiredCount = (expectedSize + chunkBytes - 1) / chunkBytes
        if (chunks.size != requiredCount) return null
        val out = ByteArray(expectedSize)
        chunks.forEachIndexed { position, (index, bytes) ->
            if (index != position) return null
            val expectedLength = if (position == chunks.size - 1) {
                expectedSize - position * chunkBytes
            } else {
                chunkBytes
            }
            if (bytes.size != expectedLength) return null
            bytes.copyInto(out, position * chunkBytes)
        }
        return out
    }

    /**
     * Write one full slot, replacing any existing rows for it. Empty bytes are
     * never a valid manifest and bytes beyond the engineering bound fail the
     * transaction (mapped to the existing fail-closed results by the caller).
     */
    fun writeSlot(
        db: SQLiteDatabase,
        pointId: RecoveryPointId,
        slot: Int,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty()) { "Empty manifest bytes are never a valid slot" }
        require(bytes.size <= RecoveryDbSchema.MAX_MANIFEST_BYTES) {
            "Manifest exceeds the engineering bound: ${bytes.size}"
        }
        deleteSlot(db, pointId, slot)
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val length = minOf(RecoveryDbSchema.CHUNK_BYTES, bytes.size - offset)
            db.execSQL(
                "INSERT INTO ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} " +
                    "(point_id, slot, chunk_index, chunk) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(pointId.value, slot, index, bytes.copyOfRange(offset, offset + length)),
            )
            offset += length
            index += 1
        }
    }

    fun deleteSlot(db: SQLiteDatabase, pointId: RecoveryPointId, slot: Int) {
        db.delete(
            RecoveryDbSchema.TABLE_MANIFEST_CHUNKS,
            "point_id = ? AND slot = ?",
            arrayOf(pointId.value, slot.toString()),
        )
    }

    /**
     * The single child-first ownership primitive: delete every chunk row owned
     * by [pointId], then the point row, inside the caller's transaction. A
     * failure rolls both back; a committed store contains no orphan chunks.
     */
    fun deletePoint(db: SQLiteDatabase, pointId: RecoveryPointId) {
        db.delete(
            RecoveryDbSchema.TABLE_MANIFEST_CHUNKS,
            "point_id = ?",
            arrayOf(pointId.value),
        )
        db.delete(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            "point_id = ?",
            arrayOf(pointId.value),
        )
    }

    /**
     * Read and validate one slot against the persisted positive size. Returns
     * null when the slot is missing or malformed (unreadable), never an empty
     * array — empty bytes are never a valid manifest.
     */
    fun readSlot(
        db: SQLiteDatabase,
        pointId: RecoveryPointId,
        slot: Int,
        expectedSize: Int,
    ): ByteArray? {
        if (expectedSize !in 1..RecoveryDbSchema.MAX_MANIFEST_BYTES) return null
        val rows = ArrayList<Pair<Int, ByteArray>>()
        db.query(
            RecoveryDbSchema.TABLE_MANIFEST_CHUNKS,
            arrayOf("chunk_index", "chunk"),
            "point_id = ? AND slot = ?",
            arrayOf(pointId.value, slot.toString()),
            null,
            null,
            "chunk_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.getInt(0) to cursor.getBlob(1)
            }
        }
        return assemble(expectedSize, rows)
    }

    /** Test oracle: committed stores must contain zero orphan chunk rows. */
    fun countOrphanChunks(db: SQLiteDatabase): Long {
        var orphans = 0L
        db.query(
            RecoveryDbSchema.TABLE_MANIFEST_CHUNKS,
            arrayOf("COUNT(*)"),
            "NOT EXISTS (SELECT 1 FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} WHERE point_id = ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS}.point_id)",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) orphans = cursor.getLong(0)
        }
        return orphans
    }
}
