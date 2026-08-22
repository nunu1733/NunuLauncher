package app.lawnchair.organizer.rules

import android.content.SharedPreferences
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
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
            val access = CategoryOverrideAtomicAccess(TestAtomicFile(File(directory, "snapshot-v1")), preferences)
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

    private class TestAtomicFile(
        private val finalFile: File,
    ) : CategoryOverrideAtomicFile {
        private val pending = File(finalFile.parentFile, "${finalFile.name}.new")

        override fun openRead(): FileInputStream {
            // AndroidX AtomicFile.openRead() recovers from an interrupted pending write
            // before exposing the committed base file to readers.
            if (pending.exists()) pending.delete()
            return FileInputStream(finalFile)
        }

        override fun startWrite(): FileOutputStream = FileOutputStream(pending)
        override fun finishWrite(stream: FileOutputStream) {
            stream.close()
            Files.move(pending.toPath(), finalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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
