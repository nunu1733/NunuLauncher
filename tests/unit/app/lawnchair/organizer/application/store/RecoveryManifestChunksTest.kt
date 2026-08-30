package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.lifecycle.LifecycleReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #174 chunk-assembly and version-separation contracts (ADR-0009).
 *
 * [RecoveryManifestChunks.assemble] is the pure validation core shared by
 * every store read; the SQL write/assembly paths are covered by the
 * instrumentation suite on real SQLite.
 */
class RecoveryManifestChunksTest {

    @Test
    fun assembleReconstructsExactBytesAcrossMultipleChunks() {
        val chunkBytes = RecoveryDbSchema.CHUNK_BYTES
        val size = 2 * chunkBytes + 5
        val expected = ByteArray(size) { (it % 251).toByte() }
        val chunks = listOf(
            0 to expected.copyOfRange(0, chunkBytes),
            1 to expected.copyOfRange(chunkBytes, 2 * chunkBytes),
            2 to expected.copyOfRange(2 * chunkBytes, size),
        )
        val assembled = RecoveryManifestChunks.assemble(size, chunks)
        assertNotNull(assembled)
        assertTrue(expected.contentEquals(assembled!!))
    }

    @Test
    fun assembleReconstructsSingleChunkSlots() {
        val expected = ByteArray(1024) { (it % 7).toByte() }
        val assembled = RecoveryManifestChunks.assemble(1024, listOf(0 to expected))
        assertNotNull(assembled)
        assertTrue(expected.contentEquals(assembled!!))
    }

    @Test
    fun assembleRejectsEmptySizesAndBoundViolations() {
        val chunk = ByteArray(RecoveryDbSchema.CHUNK_BYTES)
        assertNull(RecoveryManifestChunks.assemble(0, listOf(0 to chunk)))
        assertNull(
            RecoveryManifestChunks.assemble(
                RecoveryDbSchema.MAX_MANIFEST_BYTES + 1,
                listOf(0 to chunk),
            ),
        )
        // Empty bytes are never a valid manifest: zero chunks for any size is missing.
        assertNull(RecoveryManifestChunks.assemble(1, emptyList()))
    }

    @Test
    fun assembleRejectsMissingExtraAndNonContiguousChunks() {
        val chunkBytes = RecoveryDbSchema.CHUNK_BYTES
        val size = 2 * chunkBytes
        val chunk = ByteArray(chunkBytes)
        // Count mismatch: one short.
        assertNull(RecoveryManifestChunks.assemble(size, listOf(0 to chunk)))
        // Count mismatch: one extra.
        assertNull(RecoveryManifestChunks.assemble(size, listOf(0 to chunk, 1 to chunk, 2 to chunk)))
        // Gap at index 1.
        assertNull(RecoveryManifestChunks.assemble(size, listOf(0 to chunk, 2 to chunk)))
        // Duplicate index.
        assertNull(RecoveryManifestChunks.assemble(size, listOf(0 to chunk, 0 to chunk)))
        // Not zero-based.
        assertNull(RecoveryManifestChunks.assemble(size, listOf(1 to chunk, 2 to chunk)))
    }

    @Test
    fun assembleRejectsWrongChunkLengthShape() {
        val chunkBytes = RecoveryDbSchema.CHUNK_BYTES
        val size = 2 * chunkBytes + 5
        val finalChunk = ByteArray(5)
        val wrongFinal = ByteArray(4)
        val wrongMiddle = ByteArray(chunkBytes - 1)
        // Final chunk shorter than the remainder.
        assertNull(
            RecoveryManifestChunks.assemble(
                size,
                listOf(0 to ByteArray(chunkBytes), 1 to ByteArray(chunkBytes), 2 to wrongFinal),
            ),
        )
        // Middle chunk short while the final chunk is full.
        assertNull(
            RecoveryManifestChunks.assemble(
                size,
                listOf(0 to wrongMiddle, 1 to ByteArray(chunkBytes), 2 to finalChunk),
            ),
        )
        // Final chunk longer than the remainder.
        assertNull(
            RecoveryManifestChunks.assemble(
                size,
                listOf(0 to ByteArray(chunkBytes), 1 to ByteArray(chunkBytes), 2 to ByteArray(6)),
            ),
        )
        // Assembled total shorter than the recorded size.
        assertNull(RecoveryManifestChunks.assemble(size, listOf(0 to ByteArray(chunkBytes), 1 to finalChunk)))
    }

    @Test
    fun physicalSchemaVersionIsSeparateFromLogicalRecordFormat() {
        // ADR-0009: physical schema 3 stores logical record format 2.
        assertEquals(3, RecoveryDbSchema.SCHEMA_VERSION)
        assertEquals(2, RecoveryRecordCodec.RECORD_FORMAT_VERSION)
        assertEquals(RecoveryRecordCodec.RECORD_FORMAT_VERSION, LifecycleReconciler.SUPPORTED_FORMAT)
        assertNotEquals(RecoveryDbSchema.SCHEMA_VERSION, RecoveryRecordCodec.RECORD_FORMAT_VERSION)
    }

    @Test
    fun chunkBoundsKeepEveryPhysicalRowBelowTheCursorWindow() {
        // 2 MB CursorWindow headroom: a chunk row is far below it, and the
        // engineering bound is far above the ≥2.25 MB record scale that must
        // succeed (the #171 device record was ~2.25 MB total, ~1.12 MB per slot).
        assertTrue(RecoveryDbSchema.CHUNK_BYTES < 1_000_000)
        assertTrue(RecoveryDbSchema.MAX_MANIFEST_BYTES >= 2_247_054)
        assertEquals(
            RecoveryDbSchema.MAX_MANIFEST_BYTES / RecoveryDbSchema.CHUNK_BYTES,
            128,
        )
    }

    @Test
    fun projectedRecordColumnsNeverContainManifestBlobs() {
        val columns = RecoveryDbSchema.RECORD_COLUMNS
        assertFalse(columns.contains("pre_manifest"))
        assertFalse(columns.contains("intended_manifest"))
        assertFalse(columns.contains("reviewed_manifest"))
        assertTrue(columns.contains("pre_manifest_size"))
        assertTrue(columns.contains("intended_manifest_size"))
        assertTrue(columns.contains("reviewed_manifest_size"))
        // The logical checksum field order is representation-independent and unchanged.
        assertEquals(
            columns.filterNot { it.endsWith("_manifest_size") } - "payload_checksum",
            RecoveryDbSchema.CHECKSUM_COLUMNS.filterNot { it in setOf("pre_manifest", "intended_manifest", "reviewed_manifest") },
        )
    }

    @Test
    fun truncatedManifestDecodeThrowsCheckedExceptionNotRuntimeException() {
        val full = RecoveryRecordCodec.encodeManifest(
            app.lawnchair.organizer.application.canonical.PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L),
        )
        val truncated = full.copyOf(full.size - 4)

        val thrown = runCatching { RecoveryRecordCodec.decodeManifest(truncated) }.exceptionOrNull()

        assertTrue("Expected a decode failure, got $thrown", thrown != null)
        // java.io.EOFException is a checked IOException: the store boundary
        // must normalize Exception (not RuntimeException only) into the closed
        // RecordRead result, or a truncated-but-shape-valid manifest escapes
        // the Unreadable contract.
        assertTrue(
            "Expected a checked exception, got $thrown (${thrown!!::class.java.name})",
            thrown !is RuntimeException,
        )
    }
}
