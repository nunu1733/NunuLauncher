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
    fun postCommitReadFailureStillReportsCommittedSelection() {
        val directory = tempDirectory()
        try {
            val file = File(directory, "selection-v1")
            val failing = FailingAtomicFile(file)
            val access = LayoutStrategySelectionAccess(failing, BuiltInOrganizerPolicyBundleSource)

            // select() performs exactly one read (the pre-publish current state).
            // Inject a failure at read #2 — the post-commit read-back the old
            // implementation used to convert into WriteFailed. The commit point
            // is finishWrite(), so the durable write must still be reported as
            // Committed (spec 182 / AC-3b): converting it would let the caller
            // assume the previous selection while later runs silently observe
            // the new one.
            failing.failReadNumber = 2
            val result = access.select(supported)

            assertTrue(result is LayoutStrategySelectionWriteResult.Committed)
            val committed = (result as LayoutStrategySelectionWriteResult.Committed).snapshot
            assertEquals(supported, committed.selection)
            assertEquals(1L, committed.generation)

            failing.failReadNumber = null
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

        /** 1-based index of the openRead() call that must fail; null disables. */
        var failReadNumber: Int? = null
        private var readCount = 0

        override fun openRead(): FileInputStream {
            readCount++
            if (failReadNumber == readCount) throw IOException("injected post-commit read failure")
            return FileInputStream(finalFile)
        }

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
