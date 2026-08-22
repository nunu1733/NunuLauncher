package app.lawnchair.organizer.rules

import android.content.SharedPreferences
import app.lawnchair.organizer.application.adapter.FakeLayoutWriter
import app.lawnchair.organizer.application.canonical.CanonicalFixtures
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.integration.CanonicalCaptureReadResult
import app.lawnchair.organizer.integration.CanonicalCaptureSource
import app.lawnchair.organizer.integration.ClassificationEvidenceRequest
import app.lawnchair.organizer.integration.ClassificationSignalSnapshotSource
import app.lawnchair.organizer.integration.DefaultOrganizationInputComposer
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.integration.PlatformClassificationEvidence
import app.lawnchair.organizer.integration.PlatformEvidenceReadResult
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.ui.CategoryOverrideApp
import app.lawnchair.organizer.ui.CategoryOverrideAppInventory
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringCoordinator
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringResult
import app.lawnchair.organizer.ui.CategoryOverrideProfile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOverrideAtomicAccessTest {
    @Test
    fun mutationMigratesLegacySnapshotAndFiltersComposerVisibilityByProfile() {
        val directory = Files.createTempDirectory("override-store").toFile()
        try {
            val preferences = FakePreferences().apply {
                putInitial("schema", 1)
                putInitial("generation", 0L)
                putInitial("entries", "com.personal|0|SOCIAL\ncom.work|10|TOOLS")
            }
            val atomic = TestAtomicFile(File(directory, "snapshot-v1"))
            val access = CategoryOverrideAtomicAccess(atomic, preferences)
            val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity

            val result = access.mutate(
                request = CategoryOverrideMutation.Set(
                    CategoryOverrideKey(PackageName("com.personal"), ProfileId("0")),
                    CategoryId("GAME"),
                ),
                expected = expected,
                verificationProfiles = setOf(ProfileId("0")),
                allowedCategories = setOf(CategoryId("GAME"), CategoryId("SOCIAL"), CategoryId("TOOLS")),
            )

            assertTrue(result is CategoryOverrideWriteResult.Committed)
            assertEquals(2, preferences.getInt("schema", -1))
            assertEquals(
                OverrideSnapshotReadResult.UnsupportedSchema,
                SharedPreferencesCategoryOverrideSnapshotSource(preferences).read(setOf(ProfileId("0"))),
            )
            val visible = access.readVisible(setOf(ProfileId("0"))) as OverrideSnapshotReadResult.Ready
            assertEquals(
                mapOf(CategoryOverrideKey(PackageName("com.personal"), ProfileId("0")) to CategoryId("GAME")),
                visible.snapshot.assignments,
            )
            assertFalse(visible.snapshot.identity.versionOrGeneration.isBlank())

            val restartedAccess = CategoryOverrideAtomicAccess(atomic, preferences)
            val restarted = restartedAccess.readStored() as CategoryOverrideStoredReadResult.Ready
            assertEquals(visible.snapshot.generation, restarted.snapshot.identity.generation)
            assertEquals(
                mapOf(
                    CategoryOverrideKey(PackageName("com.personal"), ProfileId("0")) to CategoryId("GAME"),
                    CategoryOverrideKey(PackageName("com.work"), ProfileId("10")) to CategoryId("TOOLS"),
                ),
                restarted.snapshot.assignments,
            )
            assertEquals(
                OverrideSnapshotReadResult.UnsupportedSchema,
                SharedPreferencesCategoryOverrideSnapshotSource(preferences).read(setOf(ProfileId("0"))),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedPendingWriteIsDiscardedBeforeTheFinalSnapshotIsRead() {
        val directory = Files.createTempDirectory("override-recovery").toFile()
        try {
            val key = CategoryOverrideKey(PackageName("com.personal"), ProfileId("0"))
            val assignments = mapOf(key to CategoryId("SOCIAL"))
            val stored = CategoryOverrideStoredSnapshot(
                CategoryOverrideStoredIdentity(1, 3L, sha256Canonical("com.personal|0|SOCIAL")),
                assignments,
            )
            val atomic = TestAtomicFile(File(directory, "snapshot-v1")).apply {
                seedFinal(CategoryOverrideFullStoreCodec.encode(stored))
                leaveInterruptedWrite("not-a-snapshot".toByteArray())
            }
            val preferences = FakePreferences().apply { putInitial("schema", 2) }

            val read = CategoryOverrideAtomicAccess(atomic, preferences).readStored()

            assertEquals(CategoryOverrideStoredReadResult.Ready(stored), read)
            assertFalse(atomic.hasPendingWrite())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun markerCommitFailureClosesTheMigrationBarrierAndRejectsFutureMutations() {
        val directory = Files.createTempDirectory("override-barrier").toFile()
        try {
            val preferences = FakePreferences(commitSucceeds = false).apply {
                putInitial("schema", 1)
                putInitial("generation", 0L)
                putInitial("entries", "com.personal|0|SOCIAL")
            }
            val access = CategoryOverrideAtomicAccess(TestAtomicFile(File(directory, "snapshot-v1")), preferences)
            val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity

            val result = access.mutate(
                request = CategoryOverrideMutation.Remove(CategoryOverrideKey(PackageName("com.personal"), ProfileId("0"))),
                expected = expected,
                verificationProfiles = setOf(ProfileId("0")),
                allowedCategories = setOf(CategoryId("SOCIAL")),
            )

            assertEquals(CategoryOverrideWriteResult.MigrationBarrierUncertain, result)
            assertEquals(CategoryOverrideStoredReadResult.MigrationBarrierUncertain, access.readStored())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startWriteFailureKeepsThePriorSnapshotAuthoritative() = assertWriteFailure(FailurePoint.START_WRITE)

    @Test
    fun writeFailureKeepsThePriorSnapshotAuthoritative() = assertWriteFailure(FailurePoint.WRITE)

    @Test
    fun syncFailureKeepsThePriorSnapshotAuthoritative() = assertWriteFailure(FailurePoint.SYNC)

    @Test
    fun finishWriteFailureKeepsThePriorSnapshotAuthoritative() = assertWriteFailure(FailurePoint.FINISH_WRITE)

    @Test
    fun postFinishVerificationFailureIsTypedAndNeverReady() {
        val directory = Files.createTempDirectory("override-verification").toFile()
        try {
            val key = CategoryOverrideKey(PackageName("com.example.old"), ProfileId("0"))
            val prior = storedSnapshot(key, CategoryId("SOCIAL"))
            val atomic = TestAtomicFile(File(directory, "snapshot-v1")).apply {
                seedFinal(CategoryOverrideFullStoreCodec.encode(prior))
                corruptAfterFinish = true
            }
            val preferences = FakePreferences().apply { putInitial("schema", 2) }
            val access = CategoryOverrideAtomicAccess(atomic, preferences)
            val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity

            val result = access.mutate(
                request = CategoryOverrideMutation.Set(
                    CategoryOverrideKey(PackageName("com.example.new"), ProfileId("0")),
                    CategoryId("GAME"),
                ),
                expected = expected,
                verificationProfiles = setOf(ProfileId("0")),
                allowedCategories = setOf(CategoryId("GAME"), CategoryId("SOCIAL")),
            )

            // Per the accepted contract, post-finish corruption is fail-closed rather than auto-repaired.
            assertEquals(CategoryOverrideWriteResult.VerificationFailed, result)
            assertEquals(CategoryOverrideStoredReadResult.Unreadable, access.readStored())
            assertEquals(
                OverrideSnapshotReadResult.Unreadable,
                access.readVisible(setOf(ProfileId("0"))),
            )
            val restartedAccess = CategoryOverrideAtomicAccess(atomic, preferences)
            assertEquals(CategoryOverrideStoredReadResult.Unreadable, restartedAccess.readStored())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentReadWaitsForPublicationAndSeesOneCompleteSnapshot() {
        val directory = Files.createTempDirectory("override-concurrency").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val key = CategoryOverrideKey(PackageName("com.example.old"), ProfileId("0"))
            val prior = storedSnapshot(key, CategoryId("SOCIAL"))
            val nextKey = CategoryOverrideKey(PackageName("com.example.new"), ProfileId("0"))
            val atomic = TestAtomicFile(File(directory, "snapshot-v1")).apply {
                seedFinal(CategoryOverrideFullStoreCodec.encode(prior))
            }
            val preferences = FakePreferences().apply { putInitial("schema", 2) }
            val access = CategoryOverrideAtomicAccess(atomic, preferences)
            val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity
            val finishEntered = CountDownLatch(1)
            val releaseFinish = CountDownLatch(1)
            val observedRead = CountDownLatch(1)
            atomic.blockFinish(finishEntered, releaseFinish)

            val writer = executor.submit<CategoryOverrideWriteResult> {
                access.mutate(
                    request = CategoryOverrideMutation.Set(nextKey, CategoryId("GAME")),
                    expected = expected,
                    verificationProfiles = setOf(ProfileId("0")),
                    allowedCategories = setOf(CategoryId("GAME"), CategoryId("SOCIAL")),
                )
            }
            assertTrue(finishEntered.await(1, TimeUnit.SECONDS))
            atomic.observeNextRead(observedRead)

            val reader = executor.submit<CategoryOverrideStoredReadResult> { access.readStored() }
            assertFalse(observedRead.await(100, TimeUnit.MILLISECONDS))

            releaseFinish.countDown()
            assertEquals(CategoryOverrideWriteResult.Committed::class, writer.get(1, TimeUnit.SECONDS)::class)
            val read = reader.get(1, TimeUnit.SECONDS) as CategoryOverrideStoredReadResult.Ready
            assertEquals(
                mapOf(key to CategoryId("SOCIAL"), nextKey to CategoryId("GAME")),
                read.snapshot.assignments,
            )
            assertEquals(1L, read.snapshot.identity.generation)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun authoringMutationThroughAtomicStoreFeedsFreshCompositionsAsS1() {
        val directory = Files.createTempDirectory("override-composition").toFile()
        try {
            // Production UserCache serials are persisted as decimal ProfileIds.
            val canonicalPersonalProfile = ProfileId("0")
            val targetKey = CategoryOverrideKey(PackageName("com.example.override"), canonicalPersonalProfile)
            val target = CategoryOverrideApp(
                key = targetKey,
                label = "Override",
                profile = CategoryOverrideProfile.PERSONAL,
                icon = null,
                assignedCategory = null,
            )
            val preferences = FakePreferences().apply {
                putInitial("schema", 1)
                putInitial("generation", 0L)
                putInitial("entries", "")
            }
            val access = CategoryOverrideAtomicAccess(TestAtomicFile(File(directory, "snapshot-v1")), preferences)
            val store = AtomicFileCategoryOverrideStore(access, BuiltInOrganizerPolicyBundleSource)
            val authoring = CategoryOverrideAuthoringCoordinator(
                store = store,
                bundleSource = BuiltInOrganizerPolicyBundleSource,
                inventory = CategoryOverrideAppInventory { listOf(target) },
            )

            assertTrue(authoring.save(target, CategoryId("OTHER")) is CategoryOverrideAuthoringResult.Saved)
            assertEquals(2, preferences.getInt("schema", -1))

            val state = CanonicalFixtures.state(
                items = listOf(
                    CanonicalFixtures.appItem(
                        itemId = "override",
                        profile = canonicalPersonalProfile.value,
                        target = TargetKey.AppKey(
                            ComponentKey("com.example.override/.Main"),
                            canonicalPersonalProfile,
                        ),
                    ),
                ),
                profiles = listOf(CanonicalFixtures.profile(canonicalPersonalProfile.value)),
            )
            fun composeFresh() = DefaultOrganizationInputComposer(
                captureSource = CanonicalCaptureSource {
                    CanonicalCaptureReadResult.Ready(
                        FakeLayoutWriter(state).captureCurrent(CaptureId("fresh-composition")),
                    )
                },
                bundleSource = BuiltInOrganizerPolicyBundleSource,
                overrides = AtomicFileCategoryOverrideSnapshotSource(access),
                platformEvidence = object : ClassificationSignalSnapshotSource {
                    override fun read(
                        requests: List<ClassificationEvidenceRequest>,
                        policy: ClassificationPolicy,
                    ) = PlatformEvidenceReadResult.Ready(
                        PlatformClassificationEvidence(
                            s2 = mapOf(ItemId("override") to CategoryId("GAME")),
                            s5 = emptyMap(),
                            identity = PolicyInputIdentity(
                                PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE,
                                "test",
                                "b".repeat(64),
                            ),
                        ),
                    )
                },
            ).composeFullOrganization()

            listOf(composeFresh(), composeFresh()).forEach { composition ->
                val ready = composition as OrganizationInputComposition.Ready
                assertEquals(
                    listOf("override:S1:OTHER"),
                    ready.input.signals.entries.map { "${it.item.value}:${it.source.name}:${it.candidate.value}" },
                )
            }
            val stored = access.readStored() as CategoryOverrideStoredReadResult.Ready
            assertEquals(CategoryId("OTHER"), stored.snapshot.assignments[targetKey])
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertWriteFailure(failurePoint: FailurePoint) {
        val directory = Files.createTempDirectory("override-write-failure").toFile()
        try {
            val key = CategoryOverrideKey(PackageName("com.example.old"), ProfileId("0"))
            val prior = storedSnapshot(key, CategoryId("SOCIAL"))
            val atomic = TestAtomicFile(File(directory, "snapshot-v1")).apply {
                seedFinal(CategoryOverrideFullStoreCodec.encode(prior))
                failure = failurePoint
            }
            val preferences = FakePreferences().apply { putInitial("schema", 2) }
            val access = CategoryOverrideAtomicAccess(atomic, preferences)
            val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity

            val result = access.mutate(
                request = CategoryOverrideMutation.Set(
                    CategoryOverrideKey(PackageName("com.example.new"), ProfileId("0")),
                    CategoryId("GAME"),
                ),
                expected = expected,
                verificationProfiles = setOf(ProfileId("0")),
                allowedCategories = setOf(CategoryId("GAME"), CategoryId("SOCIAL")),
            )

            assertEquals(CategoryOverrideWriteResult.WriteFailed, result)
            val read = access.readStored() as CategoryOverrideStoredReadResult.Ready
            assertEquals(prior, read.snapshot)
            val visible = access.readVisible(setOf(ProfileId("0"))) as OverrideSnapshotReadResult.Ready
            assertEquals(prior.assignments, visible.snapshot.assignments)
            assertEquals(prior.identity.generation, visible.snapshot.generation)
            assertFalse(atomic.hasPendingWrite())
            val restarted = CategoryOverrideAtomicAccess(atomic, FakePreferences().apply { putInitial("schema", 2) })
            assertEquals(prior, (restarted.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun storedSnapshot(
        key: CategoryOverrideKey,
        category: CategoryId,
    ) = CategoryOverrideStoredSnapshot(
        CategoryOverrideStoredIdentity(1, 0L, sha256Canonical("${key.packageName.value}|${key.profile.value}|${category.value}")),
        mapOf(key to category),
    )

    private enum class FailurePoint { START_WRITE, WRITE, SYNC, FINISH_WRITE }

    private class TestAtomicFile(
        private val finalFile: File,
    ) : CategoryOverrideAtomicFile {
        private val pending = File(finalFile.parentFile, "${finalFile.name}.new")
        var failure: FailurePoint? = null
        var corruptAfterFinish = false
        private var finishEntered: CountDownLatch? = null
        private var releaseFinish: CountDownLatch? = null
        private var observedRead: CountDownLatch? = null

        override fun openRead(): FileInputStream {
            observedRead?.countDown()
            // AndroidX AtomicFile.openRead() recovers from an interrupted pending write
            // before exposing the committed base file to readers.
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
            finishEntered?.countDown()
            releaseFinish?.await()
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

        fun blockFinish(entered: CountDownLatch, release: CountDownLatch) {
            finishEntered = entered
            releaseFinish = release
        }

        fun observeNextRead(observed: CountDownLatch) {
            observedRead = observed
        }
    }

    private class FakePreferences(
        private val commitSucceeds: Boolean = true,
    ) : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        fun putInitial(key: String, value: Any) {
            values[key] = value
        }

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = set(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = set(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = set(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = set(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = set(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = set(key, value)
            override fun remove(key: String): SharedPreferences.Editor = set(key, null)
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                if (!commitSucceeds) return false
                applyPending()
                return true
            }
            override fun apply() = applyPending()

            private fun set(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            private fun applyPending() {
                if (clearAll) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
