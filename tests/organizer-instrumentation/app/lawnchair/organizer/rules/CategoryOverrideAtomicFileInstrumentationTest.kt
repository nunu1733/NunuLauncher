package app.lawnchair.organizer.rules

import android.content.Context
import android.os.Process
import androidx.core.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AndroidX AtomicFile and SharedPreferences evidence for Issue #99 AC-6/6a.
 *
 * The JVM suite injects failures at each publication seam. These tests keep the
 * real AndroidX recovery protocol and a fresh access object on the other side
 * of the interrupted write/migration boundary, so the production reader never
 * treats `.new` as an independent source.
 */
@RunWith(AndroidJUnit4::class)
class CategoryOverrideAtomicFileInstrumentationTest {
    private val directories = mutableListOf<File>()
    private val preferenceNames = mutableListOf<String>()

    @After
    fun tearDown() {
        directories.forEach { it.deleteRecursively() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferenceNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun androidXAtomicFileRestoresBackupForFreshAccessAfterInterruptedWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = newDirectory("restart-recovery")
        val preferences = newPreferences(context)
        preferences.edit().putInt(SCHEMA_KEY, ATOMIC_AUTHORITY_SCHEMA).commit()
        val finalFile = File(directory, FINAL_FILE_NAME)
        val initial = snapshot(
            generation = 3L,
            assignments = mapOf(key("com.example.old") to CategoryId("SOCIAL")),
        )
        val next = snapshot(
            generation = 4L,
            assignments = mapOf(key("com.example.new") to CategoryId("GAME")),
        )
        val writer = AndroidxAtomicFile(finalFile)
        publish(writer, initial)

        // Model process death after startWrite/fsync and before finishWrite.
        // AndroidX has moved the committed base to .bak and leaves the new
        // generation in .new; no reader is allowed to inspect .new directly.
        val interrupted = writer.startWrite()
        writer.write(interrupted, CategoryOverrideFullStoreCodec.encode(next))
        writer.sync(interrupted)
        interrupted.close()
        assertTrue(File("${finalFile.path}.new").exists())
        val backup = File("${finalFile.path}.bak")
        assertTrue(finalFile.renameTo(backup))
        assertTrue(backup.exists())

        val restarted = CategoryOverrideAtomicAccess(AndroidxAtomicFile(finalFile), preferences)
        assertEquals(
            CategoryOverrideStoredReadResult.Ready(initial),
            restarted.readStored(),
        )
    }

    @Test
    fun androidSharedPreferencesMigrationRemainsUnsupportedToFreshLegacyReader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = newDirectory("migration-restart")
        val preferences = newPreferences(context)
        val oldKey = key("com.example.legacy")
        preferences.edit()
            .putInt(SCHEMA_KEY, 1)
            .putLong(GENERATION_KEY, 0L)
            .putString(ENTRIES_KEY, "${oldKey.packageName.value}|${oldKey.profile.value}|SOCIAL")
            .commit()
        val access = CategoryOverrideAtomicAccess(
            AndroidxAtomicFile(File(directory, FINAL_FILE_NAME)),
            preferences,
        )
        val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity

        val result = access.mutate(
            request = CategoryOverrideMutation.Set(oldKey, CategoryId("GAME")),
            expected = expected,
            verificationProfiles = setOf(oldKey.profile),
            allowedCategories = setOf(CategoryId("GAME"), CategoryId("SOCIAL")),
        )
        assertTrue(result is CategoryOverrideWriteResult.Committed)

        // A new access object is the process-recreation boundary. The old
        // #83 reader must fail closed after the durable schema=2 barrier rather
        // than consuming the legacy copy again.
        val freshAccess = CategoryOverrideAtomicAccess(
            AndroidxAtomicFile(File(directory, FINAL_FILE_NAME)),
            preferences,
        )
        assertEquals(
            CategoryOverrideStoredReadResult.Ready(
                snapshot(1L, mapOf(oldKey to CategoryId("GAME"))),
            ),
            freshAccess.readStored(),
        )
        assertEquals(
            OverrideSnapshotReadResult.UnsupportedSchema,
            SharedPreferencesCategoryOverrideSnapshotSource(preferences).read(setOf(oldKey.profile)),
        )
    }

    private fun newDirectory(name: String): File {
        val directory = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "category-override-$name-${UUID.randomUUID()}",
        )
        check(directory.mkdirs()) { "Unable to create ${directory.absolutePath}" }
        directories += directory
        return directory
    }

    private fun newPreferences(context: Context) = "category-override-instrumentation-${UUID.randomUUID()}".let { name ->
        preferenceNames += name
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun key(packageName: String) = CategoryOverrideKey(PackageName(packageName), ProfileId("0"))

    private fun snapshot(
        generation: Long,
        assignments: Map<CategoryOverrideKey, CategoryId>,
    ) = CategoryOverrideStoredSnapshot(
        CategoryOverrideStoredIdentity(
            schemaVersion = 1,
            generation = generation,
            sha256 = sha256Canonical(canonicalEntries(assignments)),
        ),
        assignments,
    )

    private fun canonicalEntries(assignments: Map<CategoryOverrideKey, CategoryId>): String = assignments.entries
        .sortedWith(compareBy<Map.Entry<CategoryOverrideKey, CategoryId>> { it.key.profile.value }.thenBy { it.key.packageName.value })
        .joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.value}" }

    private fun publish(atomic: AndroidxAtomicFile, snapshot: CategoryOverrideStoredSnapshot) {
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            atomic.write(stream, CategoryOverrideFullStoreCodec.encode(snapshot))
            atomic.sync(stream)
            atomic.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(atomic::failWrite)
        }
    }

    private class AndroidxAtomicFile(finalFile: File) : CategoryOverrideAtomicFile {
        private val atomicFile = AtomicFile(finalFile)

        override fun openRead(): FileInputStream = atomicFile.openRead()
        override fun startWrite(): FileOutputStream = atomicFile.startWrite()
        override fun write(stream: FileOutputStream, bytes: ByteArray) = stream.write(bytes)
        override fun sync(stream: FileOutputStream) = stream.fd.sync()
        override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)
        override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
    }

    private companion object {
        const val SCHEMA_KEY = "schema"
        const val GENERATION_KEY = "generation"
        const val ENTRIES_KEY = "entries"
        const val ATOMIC_AUTHORITY_SCHEMA = 2
        const val FINAL_FILE_NAME = "snapshot-v1"
    }
}

/** First half of the two-instrumentation process-restart proof. */
@RunWith(AndroidJUnit4::class)
class CategoryOverrideAtomicFileRestartWriterInstrumentationTest {
    @Test
    fun leaveInterruptedAtomicFileGenerationForTheNextInstrumentationProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, RESTART_DIRECTORY)
        directory.deleteRecursively()
        check(directory.mkdirs()) { "Unable to create ${directory.absolutePath}" }
        val preferences = context.getSharedPreferences(RESTART_PREFERENCES, Context.MODE_PRIVATE)
        val oldKey = key("com.example.old")
        check(
            preferences.edit()
                .clear()
                .putInt(RESTART_SCHEMA_KEY, 1)
                .putLong(RESTART_GENERATION_KEY, 0L)
                .putString(RESTART_ENTRIES_KEY, "${oldKey.packageName.value}|${oldKey.profile.value}|SOCIAL")
                .commit(),
        )
        val finalFile = File(directory, RESTART_FINAL_FILE_NAME)
        val access = CategoryOverrideAtomicAccess(RestartAtomicFile(finalFile), preferences)
        val expected = (access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot.identity
        val migrated = access.mutate(
            request = CategoryOverrideMutation.Set(oldKey, CategoryId("GAME")),
            expected = expected,
            verificationProfiles = setOf(oldKey.profile),
            allowedCategories = setOf(CategoryId("GAME"), CategoryId("SOCIAL")),
        )
        check(migrated is CategoryOverrideWriteResult.Committed)
        val initial = restartSnapshot(1L, oldKey, CategoryId("GAME"))
        check((access.readStored() as CategoryOverrideStoredReadResult.Ready).snapshot == initial)
        val next = restartSnapshot(2L, key("com.example.new"), CategoryId("GAME"))
        val atomic = RestartAtomicFile(finalFile)

        val interrupted = atomic.startWrite()
        atomic.write(interrupted, CategoryOverrideFullStoreCodec.encode(next))
        atomic.sync(interrupted)
        interrupted.close()
        File(directory, RESTART_PID_FILE).writeText(Process.myPid().toString())
        val backup = File("${finalFile.path}.bak")
        check(finalFile.renameTo(backup))
        check(backup.exists())
        check(File("${finalFile.path}.new").exists())
    }
}

/** Second half of the process-restart proof, run after an explicit force-stop. */
@RunWith(AndroidJUnit4::class)
class CategoryOverrideAtomicFileRestartReaderInstrumentationTest {
    @Test
    fun freshInstrumentationProcessReadsOnlyTheRecoveredCommittedGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, RESTART_DIRECTORY)
        val writerPid = File(directory, RESTART_PID_FILE).readText().toInt()
        check(writerPid != Process.myPid()) { "restart fixture was not read by a fresh process" }
        val preferences = context.getSharedPreferences(RESTART_PREFERENCES, Context.MODE_PRIVATE)
        val access = CategoryOverrideAtomicAccess(
            RestartAtomicFile(File(directory, RESTART_FINAL_FILE_NAME)),
            preferences,
        )

        assertEquals(
            CategoryOverrideStoredReadResult.Ready(
                restartSnapshot(1L, key("com.example.old"), CategoryId("GAME")),
            ),
            access.readStored(),
        )
        assertEquals(
            OverrideSnapshotReadResult.UnsupportedSchema,
            SharedPreferencesCategoryOverrideSnapshotSource(preferences).read(setOf(ProfileId("0"))),
        )
        directory.deleteRecursively()
        preferences.edit().clear().commit()
    }
}

private fun key(packageName: String) = CategoryOverrideKey(PackageName(packageName), ProfileId("0"))

private fun restartSnapshot(
    generation: Long,
    key: CategoryOverrideKey,
    category: CategoryId,
) = CategoryOverrideStoredSnapshot(
    CategoryOverrideStoredIdentity(
        schemaVersion = 1,
        generation = generation,
        sha256 = sha256Canonical("${key.packageName.value}|${key.profile.value}|${category.value}"),
    ),
    mapOf(key to category),
)

private class RestartAtomicFile(finalFile: File) : CategoryOverrideAtomicFile {
    private val atomicFile = AtomicFile(finalFile)

    override fun openRead(): FileInputStream = atomicFile.openRead()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun write(stream: FileOutputStream, bytes: ByteArray) = stream.write(bytes)
    override fun sync(stream: FileOutputStream) = stream.fd.sync()
    override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
}

private const val RESTART_DIRECTORY = "category-override-atomic-restart"
private const val RESTART_PREFERENCES = "category-override-atomic-restart"
private const val RESTART_PID_FILE = "writer.pid"
private const val RESTART_SCHEMA_KEY = "schema"
private const val RESTART_GENERATION_KEY = "generation"
private const val RESTART_ENTRIES_KEY = "entries"
private const val RESTART_FINAL_FILE_NAME = "snapshot-v1"
