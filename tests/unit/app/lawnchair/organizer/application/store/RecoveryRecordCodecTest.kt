package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentResource
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #14 Stage B step 3: RecoveryRecordCodec encodes/decodes the recovery
 * record payload checksum and lossless manifest blob. Pure JVM — no SQLite.
 *
 * The SQLite-backed durability and lifecycle tests live in the dedicated
 * instrumentation source set (`RecoveryStoreLifecycleTest`).
 */
class RecoveryRecordCodecTest {

    @Test
    fun payloadChecksumIsStableAcrossTwoEncodings() {
        val encoded = sampleEncoded()
        val first = RecoveryRecordCodec.computePayloadChecksum(encoded)
        val second = RecoveryRecordCodec.computePayloadChecksum(encoded)
        assertArrayEquals(first, second)
    }

    @Test
    fun payloadChecksumVerifiesForValidRecord() {
        val encoded = sampleEncoded()
        val withChecksum = encoded.copy(
            payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(encoded),
        )
        assertTrue(RecoveryRecordCodec.verifyPayloadChecksum(withChecksum))
    }

    @Test
    fun payloadChecksumFailsForTamperedLifecycle() {
        val encoded = sampleEncoded()
        val withChecksum = encoded.copy(
            payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(encoded),
        )
        val tampered = withChecksum.copy(lifecycle = LifecycleState.VERIFIED)
        assertFalse(
            "Tampered lifecycle must fail checksum verification",
            RecoveryRecordCodec.verifyPayloadChecksum(tampered),
        )
    }

    @Test
    fun payloadChecksumFailsForTamperedItemCount() {
        val encoded = sampleEncoded()
        val withChecksum = encoded.copy(
            payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(encoded),
        )
        val tampered = withChecksum.copy(itemCount = encoded.itemCount + 1)
        assertFalse(RecoveryRecordCodec.verifyPayloadChecksum(tampered))
    }

    @Test
    fun payloadChecksumDistinguishesRecordsWithDifferentPointIds() {
        val a = sampleEncoded().copy(pointId = RecoveryPointId("a".repeat(32)))
        val b = sampleEncoded().copy(pointId = RecoveryPointId("b".repeat(32)))
        assertNotEquals(
            RecoveryRecordCodec.computePayloadChecksum(a).toList(),
            RecoveryRecordCodec.computePayloadChecksum(b).toList(),
        )
    }

    @Test
    fun manifestEncodingRoundTrips() {
        val manifest = sampleManifest()
        val bytes = RecoveryRecordCodec.encodeManifest(manifest)
        val decoded = RecoveryRecordCodec.decodeManifest(bytes)
        assertEquals(manifest.formatVersion, decoded.formatVersion)
        assertEquals(manifest.schemaVersion, decoded.schemaVersion)
        assertEquals(manifest.rowCount, decoded.rowCount)
        assertEquals(manifest.rows.size, decoded.rows.size)
        assertEquals(manifest.resources.size, decoded.resources.size)
        val original = manifest.rows.first()
        val decodedRow = decoded.rows.first()
        assertEquals(original, decodedRow)
    }

    @Test
    fun manifestWithMultipleRowsPreservesOrder() {
        val manifest = PersistenceManifest(
            formatVersion = 1,
            schemaVersion = 33,
            rowCount = 3,
            rows = listOf(
                PersistentRow(
                    rowId = 3L,
                    itemId = ItemId("c"),
                    profileId = ProfileId("personal"),
                    containerCode = ContainerCode(0),
                    screenId = null,
                    cellX = 2,
                    cellY = 2,
                    spanX = 1,
                    spanY = 1,
                    rank = 0,
                    itemType = KindCode(0),
                    appWidgetId = null,
                    appWidgetProvider = null,
                    iconBytes = null,
                    title = null,
                    intent = null,
                    restored = null,
                    options = null,
                    appWidgetSource = null,
                    modified = 3L,
                    organizerLockState = OrganizerLockState.LOCKED,
                    rawCell = null,
                    rawSpan = null,
                ),
                PersistentRow(
                    rowId = 1L,
                    itemId = ItemId("a"),
                    profileId = ProfileId("personal"),
                    containerCode = ContainerCode(0),
                    screenId = null,
                    cellX = 0,
                    cellY = 0,
                    spanX = 1,
                    spanY = 1,
                    rank = 0,
                    itemType = KindCode(0),
                    appWidgetId = null,
                    appWidgetProvider = null,
                    iconBytes = null,
                    title = null,
                    intent = null,
                    restored = null,
                    options = null,
                    appWidgetSource = null,
                    modified = 1L,
                    organizerLockState = OrganizerLockState.UNLOCKED,
                    rawCell = null,
                    rawSpan = null,
                ),
                PersistentRow(
                    rowId = 2L,
                    itemId = ItemId("b"),
                    profileId = ProfileId("personal"),
                    containerCode = ContainerCode(0),
                    screenId = null,
                    cellX = 1,
                    cellY = 1,
                    spanX = 1,
                    spanY = 1,
                    rank = 0,
                    itemType = KindCode(0),
                    appWidgetId = null,
                    appWidgetProvider = null,
                    iconBytes = null,
                    title = null,
                    intent = null,
                    restored = null,
                    options = null,
                    appWidgetSource = null,
                    modified = 2L,
                    organizerLockState = OrganizerLockState.UNKNOWN,
                    rawCell = null,
                    rawSpan = null,
                ),
            ),
            resources = listOf(
                PersistentResource(
                    kind = PersistentResourceKind.WORKSPACE_SCREEN,
                    profileId = ProfileId("personal"),
                    order = 0L,
                    payload = byteArrayOf(0, 1, 2),
                ),
            ),
            modifiedAtMillis = 99L,
        )
        val decoded = RecoveryRecordCodec.decodeManifest(RecoveryRecordCodec.encodeManifest(manifest))
        // Codec sorts rows by rowId; verify both shapes preserve identity.
        assertEquals(3, decoded.rows.size)
        assertEquals(1L, decoded.rows[0].rowId)
        assertEquals(2L, decoded.rows[1].rowId)
        assertEquals(3L, decoded.rows[2].rowId)
        assertEquals(1, decoded.resources.size)
        assertEquals(PersistentResourceKind.WORKSPACE_SCREEN, decoded.resources[0].kind)
        assertArrayEquals(byteArrayOf(0, 1, 2), decoded.resources[0].payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestRejectsTrailingBytes() {
        RecoveryRecordCodec.decodeManifest(
            RecoveryRecordCodec.encodeManifest(sampleManifest()) + byteArrayOf(1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun recordDecodeRejectsMismatchedCount() {
        val encoded = sampleEncoded().copy(itemCount = 2)
        val checksummed = encoded.copy(
            payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(encoded),
        )
        RecoveryRecordCodec.decode(checksummed)
    }

    private fun sampleEncoded(): RecoveryRecordCodec.Encoded {
        val manifestBytes = RecoveryRecordCodec.encodeManifest(sampleManifest())
        val digest = ByteArray(32) { it.toByte() }
        return RecoveryRecordCodec.Encoded(
            pointId = RecoveryPointId("0".repeat(32)),
            runId = RunId("1".repeat(32)),
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
            lifecycle = LifecycleState.READY,
            priorLifecycle = null,
            preManifest = manifestBytes,
            preRevision = RevisionId("2".repeat(32)),
            preDigest = digest,
            intendedManifest = manifestBytes,
            intendedDigest = digest,
            applyActionDigest = digest,
            reviewedManifest = null,
            reviewedDigest = null,
            recoveryActionDigest = null,
            itemCount = 1,
            resourceCount = 0,
            payloadChecksum = digest,
        )
    }

    private fun sampleManifest(): PersistenceManifest = PersistenceManifest(
        formatVersion = 1,
        schemaVersion = 33,
        rowCount = 1,
        rows = listOf(
            PersistentRow(
                rowId = 1L,
                itemId = ItemId("app.a"),
                profileId = ProfileId("personal"),
                containerCode = ContainerCode(0),
                screenId = PageId("page-1"),
                cellX = 0,
                cellY = 0,
                spanX = 1,
                spanY = 1,
                rank = 0,
                itemType = KindCode(0),
                appWidgetId = AppWidgetId(42),
                appWidgetProvider = ComponentKey("app.a/.Widget"),
                iconBytes = byteArrayOf(0, 1, -1),
                title = "A title",
                intent = "intent:#Intent;component=app.a/.Main;end",
                restored = 7,
                options = 3,
                appWidgetSource = 2,
                modified = 1L,
                organizerLockState = OrganizerLockState.UNLOCKED,
                rawCell = GridCell(4, 5),
                rawSpan = GridSpan(2, 3),
            ),
        ),
        resources = emptyList(),
        modifiedAtMillis = 1L,
    )
}
