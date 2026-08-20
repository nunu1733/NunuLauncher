package app.lawnchair.organizer.application.store

import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.RecoveryPointId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryInspectionSnapshotTest {
    private val pointId = RecoveryPointId("11111111111111111111111111111111")

    @Test
    fun canonicalSnapshotRoundTripsWithGenerationAndTypedMetadataOnly() {
        val snapshot = RecoveryInspectionSnapshot(
            generation = 7L,
            records = listOf(
                RecoveryInspectionSnapshot.Record(
                    pointId = pointId,
                    lifecycle = LifecycleState.VERIFIED,
                    createdAtMs = 10L,
                    updatedAtMs = 11L,
                    checksumValid = true,
                    formatVersion = 1,
                ),
            ),
            tombstones = emptyList(),
        )

        val decoded = RecoveryInspectionSnapshotCodec.decode(RecoveryInspectionSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(
            RecoveryStorePort.InspectionProjection.Record(
                pointId = pointId,
                lifecycle = LifecycleState.VERIFIED,
                createdAtMs = 10L,
                updatedAtMs = 11L,
                checksumValid = true,
                formatVersion = 1,
            ),
            decoded?.project(pointId),
        )
    }

    @Test
    fun checksumOrTrailingMutationIsUnavailable() {
        val bytes = RecoveryInspectionSnapshotCodec.encode(
            RecoveryInspectionSnapshot(
                generation = 1L,
                records = emptyList(),
                tombstones = emptyList(),
            ),
        )
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()

        assertNull(RecoveryInspectionSnapshotCodec.decode(bytes))
    }

    @Test
    fun companionOrUnexpectedEntryIsUnavailableWithoutChangingDirectoryInventory() {
        val directory = kotlin.io.path.createTempDirectory("recovery-inspection").toFile()
        try {
            val final = File(directory, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME)
            final.writeBytes(
                RecoveryInspectionSnapshotCodec.encode(
                    RecoveryInspectionSnapshot(1L, emptyList(), emptyList()),
                ),
            )
            val companion = File(directory, "${RecoveryInspectionSnapshotReader.FINAL_FILE_NAME}.new")
            companion.writeText("pending")
            val before = directory.listFiles()!!.map { it.name to it.readBytes().toList() }.sortedBy { it.first }

            assertNull(RecoveryInspectionSnapshotReader(directory).read())
            val after = directory.listFiles()!!.map { it.name to it.readBytes().toList() }.sortedBy { it.first }
            assertEquals(before, after)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun provenNoCommitRestoresPreviousValidGenerationButUncertainStaysDirty() {
        val fence = InspectionSnapshotFence()
        val initial = fence.beginReconciliationMutation()
        assertTrue(fence.markValid(initial, initial.candidateGeneration))
        val previous = initial.candidateGeneration

        val collision = fence.beginOrdinaryMutation()!!
        assertTrue(fence.finish(collision, InspectionSnapshotFence.MutationOutcome.PROVEN_NO_COMMIT))
        assertEquals(InspectionSnapshotFence.State.VALID(previous), fence.state())

        val uncertain = fence.beginOrdinaryMutation()!!
        assertTrue(fence.finish(uncertain, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN))
        assertEquals(InspectionSnapshotFence.State.DIRTY(uncertain.candidateGeneration), fence.state())
    }
}
