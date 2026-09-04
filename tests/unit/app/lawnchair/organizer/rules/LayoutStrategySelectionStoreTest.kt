package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.StrategyId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutStrategySelectionStoreTest {

    private val supported = StrategyId("CANONICAL_PAGE_COMPACT_V1")

    @Test
    fun physicalAbsenceIsTheDefinedEmptyGenerationZeroDefault() {
        val directory = tempDirectory()
        try {
            val access = access(File(directory, "selection-v1"))
            val read = access.read() as LayoutStrategySelectionReadResult.Ready

            assertEquals(1, read.snapshot.schemaVersion)
            assertEquals(0L, read.snapshot.generation)
            assertEquals(null, read.snapshot.selection)
            assertEquals(
                PolicyInputIdentity(PolicySourceKind.LAYOUT_STRATEGY_SELECTION, "schema-1-generation-0", sha256Canonical("")),
                read.snapshot.identity,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validatedWritePublishesAtomicallyWithNewGenerationAndDigest() {
        val directory = tempDirectory()
        try {
            val access = access(File(directory, "selection-v1"))

            val result = access.select(supported)
            assertTrue(result is LayoutStrategySelectionWriteResult.Committed)
            val committed = (result as LayoutStrategySelectionWriteResult.Committed).snapshot

            assertEquals(supported, committed.selection)
            assertEquals(1L, committed.generation)
            assertEquals("schema-1-generation-1", committed.identity.versionOrGeneration)
            assertEquals(sha256Canonical(supported.value), committed.identity.sha256)
            assertEquals(committed, (access.read() as LayoutStrategySelectionReadResult.Ready).snapshot)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedStrategyIsRejectedAtWriteTimeWithoutTouchingStorage() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "selection-v1")
            val access = access(file)
            access.select(supported)
            val before = file.readBytes()

            val result = access.select(StrategyId("REMOVED_STRATEGY_V1"))

            assertEquals(LayoutStrategySelectionWriteResult.UnsupportedStrategy, result)
            assertEquals(before.contentToString(), file.readBytes().contentToString())
            assertEquals(supported, (access.read() as LayoutStrategySelectionReadResult.Ready).snapshot.selection)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writeFailureKeepsTheExistingSelectionIntact() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "selection-v1")
            val failing = FailingAtomicFile(file)
            val access = LayoutStrategySelectionAccess(failing, BuiltInOrganizerPolicyBundleSource)
            access.select(supported)
            val committed = (access.read() as LayoutStrategySelectionReadResult.Ready).snapshot

            failing.failNextWrite = true
            // Write failure is exercised with the supported ID; the unsupported
            // path is covered by unsupportedStrategyIsRejectedAtWriteTime...
            val result = access.select(supported)

            assertEquals(LayoutStrategySelectionWriteResult.WriteFailed, result)
            val after = access.read() as LayoutStrategySelectionReadResult.Ready
            assertEquals(committed, after.snapshot)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun selectDoesNotReadBackAfterTheCommitPoint() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "selection-v1")
            val failing = FailingAtomicFile(file)
            val access = LayoutStrategySelectionAccess(failing, BuiltInOrganizerPolicyBundleSource)

            // The commit point is AtomicFile.finishWrite() success (spec 182 /
            // AC-3b). select() must therefore perform exactly one openRead() —
            // the pre-publish current-state read — and never read back after
            // committing: a post-commit read failure must be unable to convert
            // a durable write into WriteFailed, which would let the caller
            // assume the previous selection while later runs silently observe
            // the new one.
            // First seed a durable selection so the pre-publish read is a
            // real openRead() (the first-ever select starts from the defined
            // absent state, where openRead() throws FileNotFoundException).
            access.select(supported)
            failing.successfulReadCount = 0

            val result = access.select(supported)

            assertEquals(1, failing.successfulReadCount)

            assertTrue(result is LayoutStrategySelectionWriteResult.Committed)
            val committed = (result as LayoutStrategySelectionWriteResult.Committed).snapshot
            assertEquals(supported, committed.selection)
            // Second select after the seed: generation advanced to 2.
            assertEquals(2L, committed.generation)

            // The durable state independently matches the committed snapshot.
            val observed = access.read() as LayoutStrategySelectionReadResult.Ready
            assertEquals(committed, observed.snapshot)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptStoreFailsClosedAsUnreadable() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "selection-v1")
            file.writeText("schema=1\ngeneration=5\ndigest=${"a".repeat(64)}\nselection=THIS_IS_NOT_A_REAL_STRATEGY_V1\n")

            val read = access(file).read()

            assertTrue(read is LayoutStrategySelectionReadResult.Unreadable)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun newerSchemaFailsClosedAsUnsupported() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "selection-v1")
            file.writeText("schema=2\ngeneration=5\ndigest=${"a".repeat(64)}\nselection=\n")

            val read = access(file).read()

            assertTrue(read is LayoutStrategySelectionReadResult.UnsupportedSchema)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storeAwareBinaryWithBundleUnavailableCannotWrite() {
        val directory = tempDirectory()
        try {
            val access = LayoutStrategySelectionAccess(
                FailingAtomicFile(File(directory, "selection-v1")),
                bundleSource = object : OrganizerPolicyBundleSource {
                    override fun readActive(): BundleReadResult = BundleReadResult.Missing
                },
            )
            assertEquals(LayoutStrategySelectionWriteResult.BundleUnavailable, access.select(supported))
            assertFalse(File(directory, "selection-v1").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun access(file: File) = LayoutStrategySelectionAccess(FailingAtomicFile(file), BuiltInOrganizerPolicyBundleSource)

    private fun tempDirectory() = createTempDirectory()

    private fun createTempDirectory(): File = File(System.getProperty("java.io.tmpdir"), "strategy-store-${System.nanoTime()}")
        .apply { mkdirs() }

    private class FailingAtomicFile(
        private val finalFile: File,
    ) : LayoutStrategySelectionAtomicFile {
        var failNextWrite = false
        var successfulReadCount = 0

        override fun openRead(): FileInputStream = FileInputStream(finalFile).also { successfulReadCount++ }

        override fun startWrite(): FileOutputStream {
            if (failNextWrite) throw IOException("injected start-write failure")
            return FileOutputStream(finalFile)
        }

        override fun finishWrite(stream: FileOutputStream) {
            stream.close()
        }

        override fun failWrite(stream: FileOutputStream) {
            stream.close()
        }
    }
}
