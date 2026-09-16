package app.lawnchair.organizer.rules

import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 store unit tests: codec round-trip, generation/digest/conflict/
 * no-op semantics, recovery-aware reads, failWrite/verification-failure
 * injection, the bounded capacity, and name normalization + uniqueness.
 */
class UserDefinedCategoryStoreTest {

    private val idA = UserCategoryId("0a000000-0000-4000-8000-00000000000a")
    private val idB = UserCategoryId("1b000000-0000-4000-9000-00000000000b")

    private fun tempAtomic(name: String = "catalog-v1"): Pair<File, TestAtomicFile> {
        val directory = Files.createTempDirectory("catalog-store").toFile()
        val atomic = TestAtomicFile(File(directory, name))
        return Pair(directory, atomic)
    }

    private fun accessOf(atomic: TestAtomicFile) = UserDefinedCategoryAtomicAccess(atomic)

    private fun expectedOf(access: UserDefinedCategoryAtomicAccess) = (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot.identity

    // --- codec ---------------------------------------------------------------

    @Test
    fun codecRoundTripsCanonicalCompleteEntrySet() {
        val categories = listOf(
            UserDefinedCategory(idB, "AI tools"),
            UserDefinedCategory(idA, "Commute"),
        )
        val snapshot = storedSnapshot(3L, categories)
        val decoded = UserDefinedCategoryStoreCodec.decode(UserDefinedCategoryStoreCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        // Canonical order is ID byte order regardless of insertion order.
        assertEquals(listOf(idA, idB), decoded!!.categories.map { it.id })
    }

    @Test
    fun codecDigestIsSha256OfCanonicalEntriesAndEmptyCatalogDigestsEmptyString() {
        assertEquals(sha256Canonical(""), storedSnapshot(0L, emptyList()).identity.sha256)
        val one = storedSnapshot(1L, listOf(UserDefinedCategory(idA, "Commute")))
        assertEquals(sha256Canonical("${idA.value}|Commute"), one.identity.sha256)
    }

    @Test
    fun codecRejectsCorruptDuplicateMalformedAndUnsupportedHeaders() {
        val snapshot = storedSnapshot(2L, listOf(UserDefinedCategory(idA, "Commute")))
        val bytes = UserDefinedCategoryStoreCodec.encode(snapshot)
        assertEquals(snapshot, UserDefinedCategoryStoreCodec.decode(bytes))

        // Well-formed newer schema: typed UnsupportedSchema (the Ready-only
        // decode view still yields null), per the accepted forward-compat
        // contract for the current binary.
        val newer = bytes.toString(Charsets.UTF_8).replace("schema=1", "schema=2").toByteArray()
        assertNull(UserDefinedCategoryStoreCodec.decode(newer))
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.UnsupportedSchema,
            UserDefinedCategoryStoreCodec.decodeOutcome(newer),
        )
        // Non-numeric schema line: not a recognizable version statement at all.
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(
                bytes.toString(Charsets.UTF_8).replace("schema=1", "schema=one").toByteArray(),
            ),
        )
        // Non-canonical decimal forms and unknown-low schemas: the writer never
        // emits these headers, so they must never reach a Ready decode — not
        // even by silent reinterpretation as schema 1 (review 2 regression).
        for (header in listOf("schema=0", "schema=01", "schema=02", "schema=+1", "schema= 1", "schema=1 ")) {
            assertEquals(
                "header $header must stay Unreadable",
                UserDefinedCategoryStoreDecodeOutcome.Unreadable,
                UserDefinedCategoryStoreCodec.decodeOutcome(
                    bytes.toString(Charsets.UTF_8).replace("schema=1", header).toByteArray(),
                ),
            )
        }
        // Broken digest.
        assertNull(UserDefinedCategoryStoreCodec.decode(bytes.toString(Charsets.UTF_8).replace("digest=", "digest=0").toByteArray()))
        // Duplicate ID.
        assertNull(
            UserDefinedCategoryStoreCodec.decode(
                bytes.toString(Charsets.UTF_8).replace("entries\n", "entries\n${idA.value}|Other\n").toByteArray(),
            ),
        )
        // Malformed name (field separator inside the line).
        assertNull(UserDefinedCategoryStoreCodec.decode(bytes.toString(Charsets.UTF_8).replace("Commute", "Com|mute").toByteArray()))
        // Malformed ID.
        assertNull(UserDefinedCategoryStoreCodec.decode(bytes.toString(Charsets.UTF_8).replace(idA.value, "garbage").toByteArray()))
        // Negative generation.
        assertNull(UserDefinedCategoryStoreCodec.decode(bytes.toString(Charsets.UTF_8).replace("generation=2", "generation=-1").toByteArray()))
        // Truncated tail.
        assertNull(UserDefinedCategoryStoreCodec.decode(bytes.copyOf(bytes.size - 1)))
    }

    // --- read-time invariants (accepted contract #336) -----------------------

    /**
     * Builds raw store bytes with a digest that is CORRECT for the exact rows
     * written, so a rejection below is attributable to the read-time invariant
     * under test and never to a digest mismatch.
     */
    private fun rawCatalogBytes(generation: Long, rows: List<String>): ByteArray {
        val canonical = rows.joinToString("\n")
        val entries = if (rows.isEmpty()) "" else "$canonical\n"
        return "schema=1\ngeneration=$generation\ndigest=${sha256Canonical(canonical)}\nentries\n$entries".toByteArray()
    }

    private fun idFor(i: Int): String = String.format("%08d-0000-4000-8000-%012d", i, i)

    @Test
    fun decodeRejectsExactTrimEquivalentAndNfcEquivalentDuplicateNames() {
        val exact = rawCatalogBytes(3L, listOf("${idA.value}|Commute", "${idB.value}|Commute"))
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(exact),
        )
        // The second row is not canonical (leading whitespace): the stored
        // catalog can never contain a trim-equivalent duplicate.
        val trimEquivalent = rawCatalogBytes(3L, listOf("${idA.value}|Commute", "${idB.value}| Commute"))
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(trimEquivalent),
        )
        // The decomposed row is not canonical (NFD): NFC-equivalent duplicates
        // are unreachable through the writer and fail closed on read.
        val nfcEquivalent = rawCatalogBytes(
            3L,
            listOf("${idA.value}|caf\u00e9", "${idB.value}|cafe\u0301"),
        )
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(nfcEquivalent),
        )
    }

    @Test
    fun decodeRejectsStoredNonCanonicalNames() {
        // NFD-decomposed name (not NFC).
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(rawCatalogBytes(1L, listOf("${idA.value}|cafe\u0301"))),
        )
        // Outer whitespace (not trimmed).
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(rawCatalogBytes(1L, listOf("${idA.value}| Commute"))),
        )
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(rawCatalogBytes(1L, listOf("${idA.value}|Commute "))),
        )
        // The canonical form of the same name decodes.
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Ready::class,
            UserDefinedCategoryStoreCodec.decodeOutcome(rawCatalogBytes(1L, listOf("${idA.value}|Commute")))::class,
        )
    }

    @Test
    fun decodeEnforcesTheBoundedCapacityOnRead() {
        val atCapacity = rawCatalogBytes(1L, (1..USER_DEFINED_CATEGORY_CAPACITY).map { i -> "${idFor(i)}|cat-$i" })
        assertTrue(UserDefinedCategoryStoreCodec.decodeOutcome(atCapacity) is UserDefinedCategoryStoreDecodeOutcome.Ready)
        val overCapacity = rawCatalogBytes(1L, (1..USER_DEFINED_CATEGORY_CAPACITY + 1).map { i -> "${idFor(i)}|cat-$i" })
        assertEquals(
            UserDefinedCategoryStoreDecodeOutcome.Unreadable,
            UserDefinedCategoryStoreCodec.decodeOutcome(overCapacity),
        )
    }

    @Test
    fun accessRoutesWellFormedNewerSchemaToTypedUnsupportedSchema() {
        val (directory, atomic) = tempAtomic()
        try {
            // Well-formed header naming a schema this binary does not support.
            atomic.seedFinal("schema=2\ngeneration=4\ndigest=${sha256Canonical("")}\nentries\n\n".toByteArray())
            val access = accessOf(atomic)

            assertEquals(UserDefinedCategoryStoredReadResult.UnsupportedSchema, access.readStored())
            assertEquals(UserDefinedCategoryCatalogReadResult.UnsupportedSchema, access.readVisible())
        } finally {
            directory.deleteRecursively()
        }
    }

    // --- reads ---------------------------------------------------------------

    @Test
    fun physicalAbsenceIsTheDefinedGenerationZeroEmptyCatalog() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            val read = access.readVisible() as UserDefinedCategoryCatalogReadResult.Ready
            assertEquals(0L, read.snapshot.generation)
            assertEquals(emptyList<UserDefinedCategory>(), read.snapshot.categories)
            assertEquals(UserDefinedCategoryCatalogIdentity.emptyCatalogSentinel(), read.snapshot.identity)
            val stored = access.readStored() as UserDefinedCategoryStoredReadResult.Ready
            assertEquals(0L, stored.snapshot.identity.generation)
            assertEquals(sha256Canonical(""), stored.snapshot.identity.sha256)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedPendingWriteIsDiscardedBeforeTheFinalSnapshotIsRead() {
        val (directory, atomic) = tempAtomic()
        try {
            val stored = storedSnapshot(3L, listOf(UserDefinedCategory(idA, "Commute")))
            atomic.seedFinal(UserDefinedCategoryStoreCodec.encode(stored))
            atomic.leaveInterruptedWrite("not-a-catalog".toByteArray())

            val read = accessOf(atomic).readStored()

            assertEquals(stored, (read as UserDefinedCategoryStoredReadResult.Ready).snapshot)
            assertTrue(!atomic.hasPendingWrite())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptFinalFileIsTypedUnreadableWithoutRepair() {
        val (directory, atomic) = tempAtomic()
        try {
            atomic.seedFinal("schema=1\ngeneration=5\ndigest=${"0".repeat(64)}\nentries\n".toByteArray())
            assertEquals(UserDefinedCategoryStoredReadResult.Unreadable, accessOf(atomic).readStored())
            assertEquals(UserDefinedCategoryCatalogReadResult.Unreadable, accessOf(atomic).readVisible())
        } finally {
            directory.deleteRecursively()
        }
    }

    // --- mutations -----------------------------------------------------------

    @Test
    fun createMintsUuidV4IncrementsGenerationAndVerifies() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            val expected = expectedOf(access)

            val result = access.mutate(UserDefinedCategoryMutation.Create("  AI  tools "), expected)

            assertTrue(result is UserDefinedCategoryWriteResult.Committed)
            val committed = result as UserDefinedCategoryWriteResult.Committed
            val created = checkNotNull(committed.created)
            // The minted ID is a canonical UUID v4 (the type enforces the
            // format) and never derives from the display name.
            assertTrue(UserCategoryId.USER_CATEGORY_ID_FORMAT.matches(created.id.value))
            assertEquals("AI  tools", created.displayName) // trimmed at the edges, persisted
            assertEquals(1L, committed.stored.generation)
            assertEquals("schema-1-generation-1", committed.catalog.versionOrGeneration)
            val visible = (access.readVisible() as UserDefinedCategoryCatalogReadResult.Ready).snapshot
            assertEquals(listOf(UserDefinedCategory(created.id, "AI  tools")), visible.categories)
            assertEquals(committed.catalog, visible.identity)
            // Restart through a fresh boundary sees the same complete snapshot.
            val restarted = accessOf(atomic).readStored() as UserDefinedCategoryStoredReadResult.Ready
            assertEquals(committed.stored, restarted.snapshot.identity)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun renameKeepsIdAndOrderAndOnlyChangesTheDisplayName() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            val first = access.mutate(UserDefinedCategoryMutation.Create("Commute"), expectedOf(access))
            access.mutate(UserDefinedCategoryMutation.Create("AI tools"), expectedOf(access))
            val firstId = checkNotNull((first as UserDefinedCategoryWriteResult.Committed).created).id
            val before = (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot

            val result = access.mutate(UserDefinedCategoryMutation.Rename(firstId, "Morning routine"), before.identity)

            assertTrue(result is UserDefinedCategoryWriteResult.Committed)
            val after = (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot
            // The stable ID and the ID byte order are rename-invariant.
            assertEquals(
                (before.categories.map { it.id }),
                after.categories.map { it.id },
            )
            assertEquals(2, after.categories.size)
            assertEquals("Morning routine", after.categories.first { it.id == firstId }.displayName)
            assertEquals(before.identity.generation + 1L, after.identity.generation)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesExactlyOneEntry() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            val created = access.mutate(UserDefinedCategoryMutation.Create("Commute"), expectedOf(access))
            val createdId = checkNotNull((created as UserDefinedCategoryWriteResult.Committed).created).id
            val expected = expectedOf(access)

            val result = access.mutate(UserDefinedCategoryMutation.Delete(createdId), expected)

            assertTrue(result is UserDefinedCategoryWriteResult.Committed)
            val visible = (access.readVisible() as UserDefinedCategoryCatalogReadResult.Ready).snapshot
            // Empty again — the defined sentinel identity returns.
            assertEquals(emptyList<UserDefinedCategory>(), visible.categories)
            assertEquals(UserDefinedCategoryCatalogIdentity.emptyCatalogSentinel(), visible.identity)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun noOpRenamePreservesFileGenerationAndIdentity() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            val created = access.mutate(UserDefinedCategoryMutation.Create("Commute"), expectedOf(access))
            val createdId = checkNotNull((created as UserDefinedCategoryWriteResult.Committed).created).id
            val before = (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot
            val bytesBefore = atomic.finalBytes()

            val result = access.mutate(UserDefinedCategoryMutation.Rename(createdId, " Commute "), before.identity)

            assertTrue(result is UserDefinedCategoryWriteResult.NoChange)
            assertEquals(before.identity, (result as UserDefinedCategoryWriteResult.NoChange).stored)
            assertEquals(bytesBefore.toList(), atomic.finalBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun typedFailuresForInvalidDuplicateUnknownAndCapacity() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            access.mutate(UserDefinedCategoryMutation.Create("Commute"), expectedOf(access))
            val expected = expectedOf(access)

            // Invalid names (after trim+NFC): empty, overlong, separator, line break.
            assertEquals(UserDefinedCategoryWriteResult.InvalidName, access.mutate(UserDefinedCategoryMutation.Create("   "), expected))
            assertEquals(UserDefinedCategoryWriteResult.InvalidName, access.mutate(UserDefinedCategoryMutation.Create("a".repeat(51)), expected))
            assertEquals(UserDefinedCategoryWriteResult.InvalidName, access.mutate(UserDefinedCategoryMutation.Create("Com|mute"), expected))
            assertEquals(UserDefinedCategoryWriteResult.InvalidName, access.mutate(UserDefinedCategoryMutation.Create("Com\nmute"), expected))
            // Duplicate (under the fixed normalization).
            assertEquals(UserDefinedCategoryWriteResult.DuplicateName, access.mutate(UserDefinedCategoryMutation.Create(" Commute "), expected))
            // Unknown IDs for rename/delete.
            assertEquals(UserDefinedCategoryWriteResult.UnknownId, access.mutate(UserDefinedCategoryMutation.Rename(idB, "X"), expected))
            assertEquals(UserDefinedCategoryWriteResult.UnknownId, access.mutate(UserDefinedCategoryMutation.Delete(idB), expected))
            // No write happened: generation and file unchanged.
            assertEquals(expected, expectedOf(access))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun capacityBoundOfSixtyFourEntriesIsTypedAndNeverWrites() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            var expected = expectedOf(access)
            repeat(64) { index ->
                val result = access.mutate(UserDefinedCategoryMutation.Create("cat-$index"), expected)
                assertTrue(result is UserDefinedCategoryWriteResult.Committed)
                expected = (result as UserDefinedCategoryWriteResult.Committed).stored
            }
            assertEquals(64, (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot.categories.size)

            val result = access.mutate(UserDefinedCategoryMutation.Create("one too many"), expected)

            assertEquals(UserDefinedCategoryWriteResult.CapacityExceeded, result)
            assertEquals(64, (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot.categories.size)
            assertEquals(expected, expectedOf(access))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleExpectedIdentityIsTypedConflictWithoutOverwrite() {
        val (directory, atomic) = tempAtomic()
        try {
            val access = accessOf(atomic)
            val stale = expectedOf(access)
            access.mutate(UserDefinedCategoryMutation.Create("Commute"), expectedOf(access))

            val result = access.mutate(UserDefinedCategoryMutation.Create("Other name"), stale)

            assertEquals(UserDefinedCategoryWriteResult.Conflict, result)
            val stored = (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot
            assertEquals(listOf("Commute"), stored.categories.map { it.displayName })
            assertEquals(1L, stored.identity.generation)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writeFailureKeepsThePriorSnapshotAuthoritative() {
        val (directory, atomic) = tempAtomic()
        try {
            val prior = storedSnapshot(2L, listOf(UserDefinedCategory(idA, "Commute")))
            atomic.seedFinal(UserDefinedCategoryStoreCodec.encode(prior))
            atomic.failure = TestAtomicFile.FailurePoint.WRITE
            val access = accessOf(atomic)
            val expected = expectedOf(access)

            val result = access.mutate(UserDefinedCategoryMutation.Create("New"), expected)

            assertEquals(UserDefinedCategoryWriteResult.WriteFailed, result)
            assertEquals(prior, (access.readStored() as UserDefinedCategoryStoredReadResult.Ready).snapshot)
            assertTrue(!atomic.hasPendingWrite())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun postFinishVerificationFailureIsTypedAndNeverReady() {
        val (directory, atomic) = tempAtomic()
        try {
            val prior = storedSnapshot(2L, listOf(UserDefinedCategory(idA, "Commute")))
            atomic.seedFinal(UserDefinedCategoryStoreCodec.encode(prior))
            atomic.corruptAfterFinish = true
            val access = accessOf(atomic)
            val expected = expectedOf(access)

            val result = access.mutate(UserDefinedCategoryMutation.Create("New"), expected)

            assertEquals(UserDefinedCategoryWriteResult.VerificationFailed, result)
            // Fail-closed: no repair, no partial catalog.
            assertEquals(UserDefinedCategoryStoredReadResult.Unreadable, access.readStored())
            assertEquals(UserDefinedCategoryCatalogReadResult.Unreadable, access.readVisible())
        } finally {
            directory.deleteRecursively()
        }
    }

    // --- name rules ----------------------------------------------------------

    @Test
    fun nameNormalizationIsTrimPlusNfcWithFiftyCodePointBound() {
        // Trim: leading/trailing whitespace removed, inner spacing preserved.
        assertEquals("AI  tools", UserDefinedCategoryNameRules.normalize("  AI  tools \t"))
        // NFC: a decomposed e + combining acute (U+0301) normalizes to the
        // precomposed e-acute, so one canonical rule exists per visible name.
        val decomposed = "cafe\u0301"
        val precomposed = "caf\u00e9"
        assertEquals(precomposed, UserDefinedCategoryNameRules.normalize(decomposed))
        assertEquals(precomposed + precomposed, UserDefinedCategoryNameRules.normalize("  $decomposed$decomposed "))
        assertTrue(UserDefinedCategoryNameRules.isValid(precomposed))
        // Non-ASCII names are valid.
        assertTrue(UserDefinedCategoryNameRules.isValid("\u30b3\u30df\u30e5\u30fc\u30c8"))
        // 50 code points is valid...
        assertTrue(UserDefinedCategoryNameRules.isValid("\u3042".repeat(50)))
        // ...51 is not.
        assertTrue(!UserDefinedCategoryNameRules.isValid("\u3042".repeat(51)))
        // A surrogate pair counts code points, not chars: 25 emoji are valid.
        assertTrue(UserDefinedCategoryNameRules.isValid("\uD83D\uDE00".repeat(25)))
        // 51 emoji = 51 code points: over the bound even at 2 chars each.
        assertTrue(!UserDefinedCategoryNameRules.isValid("\uD83D\uDE00".repeat(51)))
        // Forbidden field separator and line breaks.
        assertTrue(!UserDefinedCategoryNameRules.isValid("a|b"))
        assertTrue(!UserDefinedCategoryNameRules.isValid("a\nb"))
        assertTrue(!UserDefinedCategoryNameRules.isValid("a\rb"))
    }

    private class TestAtomicFile(
        private val finalFile: File,
    ) : UserDefinedCategoryAtomicFile {
        private val pending = File(finalFile.parentFile, "${finalFile.name}.new")
        var failure: FailurePoint? = null
        var corruptAfterFinish = false

        override fun openRead(): FileInputStream {
            // AndroidX AtomicFile.openRead() recovers from an interrupted
            // pending write before exposing the committed base file.
            if (pending.exists()) pending.delete()
            return FileInputStream(finalFile)
        }

        override fun startWrite(): FileOutputStream {
            if (failure == FailurePoint.START_WRITE) throw IOException("startWrite failure")
            return FileOutputStream(pending)
        }

        override fun write(stream: FileOutputStream, bytes: ByteArray) {
            if (failure == FailurePoint.WRITE) throw IOException("write failure")
            stream.write(bytes)
        }

        override fun sync(stream: FileOutputStream) {
            if (failure == FailurePoint.SYNC) throw IOException("sync failure")
            stream.fd.sync()
        }

        override fun finishWrite(stream: FileOutputStream) {
            if (failure == FailurePoint.FINISH_WRITE) throw IOException("finishWrite failure")
            stream.close()
            Files.move(pending.toPath(), finalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (corruptAfterFinish) finalFile.writeBytes("corrupt".toByteArray())
        }

        override fun failWrite(stream: FileOutputStream) {
            stream.close()
            pending.delete()
        }

        fun seedFinal(bytes: ByteArray) {
            finalFile.writeBytes(bytes)
        }

        fun leaveInterruptedWrite(bytes: ByteArray) {
            pending.writeBytes(bytes)
        }

        fun hasPendingWrite(): Boolean = pending.exists()

        fun finalBytes(): ByteArray = finalFile.readBytes()

        enum class FailurePoint { START_WRITE, WRITE, SYNC, FINISH_WRITE }
    }
}
