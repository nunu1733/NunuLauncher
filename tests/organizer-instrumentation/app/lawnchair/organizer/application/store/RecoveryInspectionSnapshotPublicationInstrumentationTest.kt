package app.lawnchair.organizer.application.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the writer-only publication failure contract in
 * Issue #89. The controlled AtomicFile boundary keeps write, finish, and
 * post-finish validation failures deterministic while exercising the real
 * publisher and reader code over a physical final/`.new` file pair.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryInspectionSnapshotPublicationInstrumentationTest {
    private val directories = mutableListOf<File>()

    @After
    fun tearDown() {
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun writeOrFinishFailureKeepsOldFinalButDirtyFenceCannotUseItAsSuccessSource() {
        listOf(Failure.WRITE, Failure.FINISH).forEach { failure ->
            val directory = newDirectory(failure.name.lowercase())
            val initial = snapshot(generation = 1L)
            val final = File(directory, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME)
            final.writeBytes(RecoveryInspectionSnapshotCodec.encode(initial))
            val fence = validFence(initial.generation)
            val mutation = requireNotNull(fence.beginOrdinaryMutation())
            val atomicFile = ControlledAtomicFile(directory, failure)
            val publisher = RecoveryInspectionSnapshotPublisher(directory, atomicFile)

            assertFalse(publisher.publish(snapshot(generation = mutation.candidateGeneration)))
            assertEquals(1, atomicFile.failWriteCalls)
            assertArrayEquals(RecoveryInspectionSnapshotCodec.encode(initial), final.readBytes())
            assertFalse(File(directory, "${RecoveryInspectionSnapshotReader.FINAL_FILE_NAME}.new").exists())
            assertTrue(fence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN))
            assertEquals(
                InspectionSnapshotFence.State.DIRTY(mutation.candidateGeneration),
                fence.state(),
            )
            assertEquals(initial.generation, publisher.reader().read()?.generation)
            assertNull(readGenerationWhenTrusted(fence, publisher))
        }
    }

    @Test
    fun failedFinalRevalidationLeavesDirtyAndIncompleteFinalCannotBecomeSuccess() {
        val directory = newDirectory("revalidation")
        val initial = snapshot(generation = 1L)
        File(directory, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME).writeBytes(
            RecoveryInspectionSnapshotCodec.encode(initial),
        )
        val fence = validFence(initial.generation)
        val mutation = requireNotNull(fence.beginOrdinaryMutation())
        val atomicFile = ControlledAtomicFile(directory, Failure.REVALIDATION)
        val publisher = RecoveryInspectionSnapshotPublisher(directory, atomicFile)

        assertFalse(publisher.publish(snapshot(generation = mutation.candidateGeneration)))
        assertEquals(0, atomicFile.failWriteCalls)
        assertFalse(File(directory, "${RecoveryInspectionSnapshotReader.FINAL_FILE_NAME}.new").exists())
        assertNull(publisher.reader().read())
        assertTrue(fence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN))
        assertEquals(
            InspectionSnapshotFence.State.DIRTY(mutation.candidateGeneration),
            fence.state(),
        )
        assertNull(readGenerationWhenTrusted(fence, publisher))
    }

    private fun validFence(generation: Long): InspectionSnapshotFence {
        val fence = InspectionSnapshotFence()
        val reconciliation = fence.beginReconciliationMutation()
        assertEquals(generation, reconciliation.candidateGeneration)
        assertTrue(fence.markValid(reconciliation, generation))
        return fence
    }

    private fun readGenerationWhenTrusted(
        fence: InspectionSnapshotFence,
        publisher: RecoveryInspectionSnapshotPublisher,
    ): Long? {
        val valid = fence.state() as? InspectionSnapshotFence.State.VALID ?: return null
        return publisher.reader().read()
            ?.takeIf { it.generation == valid.generation }
            ?.generation
    }

    private fun snapshot(generation: Long): RecoveryInspectionSnapshot = RecoveryInspectionSnapshot(
        generation = generation,
        records = emptyList(),
        tombstones = emptyList(),
    )

    private fun newDirectory(name: String): File {
        val directory = File(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "recovery-inspection-publication-$name-${UUID.randomUUID()}",
        )
        check(directory.mkdirs()) { "Unable to create ${directory.absolutePath}" }
        directories += directory
        return directory
    }

    private enum class Failure {
        WRITE,
        FINISH,
        REVALIDATION,
    }

    /**
     * Models only the documented base/`.new` protocol needed by the publisher.
     * REVALIDATION commits a deliberately truncated final file without throwing,
     * proving that a post-finish reader validation failure cannot be accepted.
     */
    private class ControlledAtomicFile(
        private val directory: File,
        private val failure: Failure,
    ) : RecoveryInspectionSnapshotAtomicFile {
        private val final = File(directory, RecoveryInspectionSnapshotReader.FINAL_FILE_NAME)
        private val new = File(directory, "${RecoveryInspectionSnapshotReader.FINAL_FILE_NAME}.new")
        var failWriteCalls: Int = 0
            private set

        override fun startWrite(): FileOutputStream = when (failure) {
            Failure.WRITE -> FailingWriteFileOutputStream(new)
            Failure.FINISH,
            Failure.REVALIDATION,
            -> FileOutputStream(new)
        }

        override fun finishWrite(stream: FileOutputStream) {
            when (failure) {
                Failure.FINISH -> throw IOException("deterministic finish failure")
                Failure.WRITE -> error("finishWrite must not follow write failure")
                Failure.REVALIDATION -> {
                    stream.flush()
                    stream.close()
                    val published = new.readBytes()
                    final.writeBytes(published.copyOf(published.size - 1))
                    check(new.delete()) { "Unable to remove ${new.absolutePath}" }
                }
            }
        }

        override fun failWrite(stream: FileOutputStream) {
            failWriteCalls += 1
            try {
                stream.close()
            } finally {
                new.delete()
            }
        }

        private class FailingWriteFileOutputStream(file: File) : FileOutputStream(file) {
            override fun write(buffer: ByteArray) {
                throw IOException("deterministic write failure")
            }
        }
    }
}
