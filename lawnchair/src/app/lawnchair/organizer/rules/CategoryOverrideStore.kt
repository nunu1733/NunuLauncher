package app.lawnchair.organizer.rules

import android.content.Context
import android.content.SharedPreferences
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Rule Management's writable owner for the Organizer v1 S1 override source.
 *
 * The public read surface deliberately remains [CategoryOverrideSnapshotSource].
 * This store adds a private complete-snapshot identity for optimistic mutations
 * without changing the #83 composer-visible, captured-profile-filtered identity.
 */
internal interface CategoryOverrideStore : CategoryOverrideSnapshotSource {
    fun readStored(): CategoryOverrideStoredReadResult

    fun mutate(
        request: CategoryOverrideMutation,
        expected: CategoryOverrideStoredIdentity,
        verificationProfiles: Set<ProfileId>,
    ): CategoryOverrideWriteResult
}

internal data class CategoryOverrideStoredIdentity(
    val schemaVersion: Int,
    val generation: Long,
    val sha256: String,
) {
    init {
        require(schemaVersion == SCHEMA_V1)
        require(generation >= 0L)
        require(SHA_256.matches(sha256))
    }
}

internal data class CategoryOverrideStoredSnapshot(
    val identity: CategoryOverrideStoredIdentity,
    val assignments: Map<CategoryOverrideKey, CategoryId>,
)

internal sealed interface CategoryOverrideStoredReadResult {
    data class Ready(val snapshot: CategoryOverrideStoredSnapshot) : CategoryOverrideStoredReadResult
    data object Unreadable : CategoryOverrideStoredReadResult
    data object UnsupportedSchema : CategoryOverrideStoredReadResult
    data object MigrationBarrierUncertain : CategoryOverrideStoredReadResult
}

internal sealed interface CategoryOverrideMutation {
    val key: CategoryOverrideKey

    data class Set(
        override val key: CategoryOverrideKey,
        val category: CategoryId,
    ) : CategoryOverrideMutation

    data class Remove(
        override val key: CategoryOverrideKey,
    ) : CategoryOverrideMutation
}

internal sealed interface CategoryOverrideWriteResult {
    data class Committed(
        val stored: CategoryOverrideStoredIdentity,
        val verificationVisible: PolicyInputIdentity,
    ) : CategoryOverrideWriteResult

    data class NoChange(
        val stored: CategoryOverrideStoredIdentity,
        val verificationVisible: PolicyInputIdentity,
    ) : CategoryOverrideWriteResult

    data object InvalidCategory : CategoryOverrideWriteResult
    data object TaxonomyUnavailable : CategoryOverrideWriteResult
    data object StoreUnreadable : CategoryOverrideWriteResult
    data object UnsupportedSchema : CategoryOverrideWriteResult
    data object MigrationBarrierUncertain : CategoryOverrideWriteResult
    data object Conflict : CategoryOverrideWriteResult
    data object WriteFailed : CategoryOverrideWriteResult
    data object VerificationFailed : CategoryOverrideWriteResult
}

/** All AtomicFile access is serialized here, including normal reads and migration. */
internal class CategoryOverrideAtomicAccess internal constructor(
    private val atomicFile: CategoryOverrideAtomicFile,
    private val legacyPreferences: SharedPreferences,
) {
    private val lock = Any()
    private var migrationBarrierUncertain = false

    fun readStored(): CategoryOverrideStoredReadResult = synchronized(lock) { readStoredLocked() }

    fun readVisible(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = synchronized(lock) {
        when (val stored = readStoredLocked()) {
            is CategoryOverrideStoredReadResult.Ready -> OverrideSnapshotReadResult.Ready(
                composerVisibleSnapshot(stored.snapshot, capturedProfiles),
            )

            CategoryOverrideStoredReadResult.UnsupportedSchema -> OverrideSnapshotReadResult.UnsupportedSchema

            CategoryOverrideStoredReadResult.Unreadable,
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain,
            -> OverrideSnapshotReadResult.Unreadable
        }
    }

    fun mutate(
        request: CategoryOverrideMutation,
        expected: CategoryOverrideStoredIdentity,
        verificationProfiles: Set<ProfileId>,
        allowedCategories: Set<CategoryId>,
    ): CategoryOverrideWriteResult = synchronized(lock) {
        if (migrationBarrierUncertain) return@synchronized CategoryOverrideWriteResult.MigrationBarrierUncertain
        if (request is CategoryOverrideMutation.Set && request.category !in allowedCategories) {
            return@synchronized CategoryOverrideWriteResult.InvalidCategory
        }
        when (val migrated = ensureAtomicAuthorityLocked()) {
            AuthorityTransition.Ready -> Unit
            AuthorityTransition.Unreadable -> return@synchronized CategoryOverrideWriteResult.StoreUnreadable
            AuthorityTransition.Unsupported -> return@synchronized CategoryOverrideWriteResult.UnsupportedSchema
            AuthorityTransition.BarrierUncertain -> return@synchronized CategoryOverrideWriteResult.MigrationBarrierUncertain
            AuthorityTransition.WriteFailed -> return@synchronized CategoryOverrideWriteResult.WriteFailed
            AuthorityTransition.VerificationFailed -> return@synchronized CategoryOverrideWriteResult.VerificationFailed
        }
        val current = when (val read = readAtomicStoredLocked()) {
            is CategoryOverrideStoredReadResult.Ready -> read.snapshot
            CategoryOverrideStoredReadResult.UnsupportedSchema -> return@synchronized CategoryOverrideWriteResult.UnsupportedSchema
            CategoryOverrideStoredReadResult.Unreadable -> return@synchronized CategoryOverrideWriteResult.StoreUnreadable
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return@synchronized CategoryOverrideWriteResult.MigrationBarrierUncertain
        }
        if (current.identity != expected) return@synchronized CategoryOverrideWriteResult.Conflict

        val nextAssignments = current.assignments.toMutableMap()
        val changed = when (request) {
            is CategoryOverrideMutation.Set -> {
                if (nextAssignments[request.key] == request.category) {
                    false
                } else {
                    nextAssignments[request.key] = request.category
                    true
                }
            }

            is CategoryOverrideMutation.Remove -> nextAssignments.remove(request.key) != null
        }
        if (!changed) {
            val visible = composerVisibleSnapshot(current, verificationProfiles)
            return@synchronized CategoryOverrideWriteResult.NoChange(current.identity, visible.identity)
        }
        val next = storedSnapshot(current.identity.generation + 1L, nextAssignments)
        when (publishLocked(next)) {
            PublishResult.WriteFailed -> return@synchronized CategoryOverrideWriteResult.WriteFailed
            PublishResult.VerificationFailed -> return@synchronized CategoryOverrideWriteResult.VerificationFailed
            PublishResult.Success -> Unit
        }
        val verified = (readAtomicStoredLocked() as? CategoryOverrideStoredReadResult.Ready)?.snapshot
            ?: return@synchronized CategoryOverrideWriteResult.VerificationFailed
        if (verified != next) return@synchronized CategoryOverrideWriteResult.VerificationFailed
        val visible = composerVisibleSnapshot(verified, verificationProfiles)
        CategoryOverrideWriteResult.Committed(verified.identity, visible.identity)
    }

    /**
     * Compatibility authority selection. Before schema=2 is durable, the legacy
     * source remains authoritative. AtomicFile migration is attempted only by a
     * writer and no ordinary mutation is admitted until the barrier succeeds.
     */
    private fun readStoredLocked(): CategoryOverrideStoredReadResult {
        if (migrationBarrierUncertain) return CategoryOverrideStoredReadResult.MigrationBarrierUncertain
        return if (legacyHasAtomicAuthorityLocked()) readAtomicStoredLocked() else readLegacyStoredLocked()
    }

    private fun ensureAtomicAuthorityLocked(): AuthorityTransition {
        if (migrationBarrierUncertain) return AuthorityTransition.BarrierUncertain
        if (legacyHasAtomicAuthorityLocked()) return AuthorityTransition.Ready
        val legacy = when (val read = readLegacyStoredLocked()) {
            is CategoryOverrideStoredReadResult.Ready -> read.snapshot
            CategoryOverrideStoredReadResult.Unreadable -> return AuthorityTransition.Unreadable
            CategoryOverrideStoredReadResult.UnsupportedSchema -> return AuthorityTransition.Unsupported
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return AuthorityTransition.BarrierUncertain
        }
        val atomic = readAtomicStoredIfPresentLocked()
        when (atomic) {
            null -> when (publishLocked(legacy)) {
                PublishResult.WriteFailed -> return AuthorityTransition.WriteFailed
                PublishResult.VerificationFailed -> return AuthorityTransition.VerificationFailed
                PublishResult.Success -> Unit
            }

            is CategoryOverrideStoredReadResult.Ready -> if (atomic.snapshot != legacy) {
                return AuthorityTransition.Unreadable
            }

            CategoryOverrideStoredReadResult.Unreadable -> return AuthorityTransition.Unreadable

            CategoryOverrideStoredReadResult.UnsupportedSchema -> return AuthorityTransition.Unsupported

            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return AuthorityTransition.BarrierUncertain
        }
        val markerCommitted = try {
            legacyPreferences.edit().putInt(LEGACY_SCHEMA_KEY, LEGACY_ATOMIC_AUTHORITY_SCHEMA).commit()
        } catch (_: RuntimeException) {
            false
        }
        if (!markerCommitted) {
            migrationBarrierUncertain = true
            return AuthorityTransition.BarrierUncertain
        }
        val marker = try {
            legacyPreferences.getInt(LEGACY_SCHEMA_KEY, -1)
        } catch (_: RuntimeException) {
            migrationBarrierUncertain = true
            return AuthorityTransition.BarrierUncertain
        }
        if (marker != LEGACY_ATOMIC_AUTHORITY_SCHEMA) {
            migrationBarrierUncertain = true
            return AuthorityTransition.BarrierUncertain
        }
        return AuthorityTransition.Ready
    }

    private fun legacyHasAtomicAuthorityLocked(): Boolean = try {
        legacyPreferences.contains(LEGACY_SCHEMA_KEY) &&
            legacyPreferences.getInt(LEGACY_SCHEMA_KEY, -1) == LEGACY_ATOMIC_AUTHORITY_SCHEMA
    } catch (_: RuntimeException) {
        false
    }

    private fun readLegacyStoredLocked(): CategoryOverrideStoredReadResult = try {
        if (!legacyPreferences.contains(LEGACY_SCHEMA_KEY)) {
            CategoryOverrideStoredReadResult.Ready(storedSnapshot(0L, emptyMap()))
        } else if (legacyPreferences.getInt(LEGACY_SCHEMA_KEY, -1) != SCHEMA_V1) {
            CategoryOverrideStoredReadResult.UnsupportedSchema
        } else {
            val generation = legacyPreferences.getLong(LEGACY_GENERATION_KEY, -1L)
            val entries = legacyPreferences.getString(LEGACY_ENTRIES_KEY, "")
            if (generation < 0L || entries == null) {
                CategoryOverrideStoredReadResult.Unreadable
            } else {
                parseLegacyEntries(entries)?.let { CategoryOverrideStoredReadResult.Ready(storedSnapshot(generation, it)) }
                    ?: CategoryOverrideStoredReadResult.Unreadable
            }
        }
    } catch (_: RuntimeException) {
        CategoryOverrideStoredReadResult.Unreadable
    }

    private fun readAtomicStoredLocked(): CategoryOverrideStoredReadResult = readAtomicStoredIfPresentLocked() ?: CategoryOverrideStoredReadResult.Unreadable

    /** null means physical absence, which is valid only before atomic authority. */
    private fun readAtomicStoredIfPresentLocked(): CategoryOverrideStoredReadResult? = try {
        if (!atomicFile.exists()) return null
        atomicFile.openRead().use { input ->
            CategoryOverrideFullStoreCodec.decode(input.readBytes())
                ?.let { CategoryOverrideStoredReadResult.Ready(it) }
                ?: CategoryOverrideStoredReadResult.Unreadable
        }
    } catch (_: IOException) {
        CategoryOverrideStoredReadResult.Unreadable
    } catch (_: SecurityException) {
        CategoryOverrideStoredReadResult.Unreadable
    }

    private fun publishLocked(snapshot: CategoryOverrideStoredSnapshot): PublishResult {
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            stream.write(CategoryOverrideFullStoreCodec.encode(snapshot))
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
            val verified = (readAtomicStoredIfPresentLocked() as? CategoryOverrideStoredReadResult.Ready)?.snapshot
            if (verified == snapshot) PublishResult.Success else PublishResult.VerificationFailed
        } catch (_: IOException) {
            PublishResult.WriteFailed
        } catch (_: SecurityException) {
            PublishResult.WriteFailed
        } finally {
            stream?.let {
                try {
                    atomicFile.failWrite(it)
                } catch (_: IOException) {
                    // The prior final file remains the only supported reader input.
                }
            }
        }
    }

    private fun composerVisibleSnapshot(
        stored: CategoryOverrideStoredSnapshot,
        capturedProfiles: Set<ProfileId>,
    ): CategoryOverrideSnapshot {
        val visible = stored.assignments.filterKeys { it.profile in capturedProfiles }
        val canonical = canonicalEntries(visible)
        return CategoryOverrideSnapshot(
            schemaVersion = SCHEMA_V1,
            generation = stored.identity.generation,
            assignments = visible,
            identity = PolicyInputIdentity(
                PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                "schema-$SCHEMA_V1-generation-${stored.identity.generation}",
                sha256Canonical(canonical),
            ),
        )
    }

    private enum class AuthorityTransition {
        Ready,
        Unreadable,
        Unsupported,
        BarrierUncertain,
        WriteFailed,
        VerificationFailed,
    }

    private enum class PublishResult { Success, WriteFailed, VerificationFailed }
}

/** Compatibility source used by #83; it exposes no writer capability. */
internal class AtomicFileCategoryOverrideSnapshotSource(
    private val access: CategoryOverrideAtomicAccess,
) : CategoryOverrideSnapshotSource {
    override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = access.readVisible(capturedProfiles)
}

internal class AtomicFileCategoryOverrideStore(
    private val access: CategoryOverrideAtomicAccess,
    private val bundleSource: OrganizerPolicyBundleSource,
) : CategoryOverrideStore {
    override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = access.readVisible(capturedProfiles)

    override fun readStored(): CategoryOverrideStoredReadResult = access.readStored()

    override fun mutate(
        request: CategoryOverrideMutation,
        expected: CategoryOverrideStoredIdentity,
        verificationProfiles: Set<ProfileId>,
    ): CategoryOverrideWriteResult {
        val bundle = (bundleSource.readActive() as? BundleReadResult.Ready)?.bundle
            ?: return CategoryOverrideWriteResult.TaxonomyUnavailable
        if (bundle.validate() != null) return CategoryOverrideWriteResult.TaxonomyUnavailable
        return access.mutate(request, expected, verificationProfiles, bundle.taxonomy.allowedCategories.toSet())
    }
}

internal interface CategoryOverrideAtomicFile {
    fun exists(): Boolean
    fun openRead(): FileInputStream
    fun startWrite(): FileOutputStream
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
}

private class AndroidxCategoryOverrideAtomicFile(
    finalFile: File,
) : CategoryOverrideAtomicFile {
    private val atomicFile = AtomicFile(finalFile)
    private val baseFile = finalFile

    override fun exists(): Boolean = baseFile.exists()
    override fun openRead(): FileInputStream = atomicFile.openRead()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
}

/** Process-local production wiring shared by the composer and the authoring UI. */
internal object CategoryOverrideStoreModule {
    @Volatile private var instance: AtomicFileCategoryOverrideStore? = null

    fun get(context: Context): AtomicFileCategoryOverrideStore = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    fun source(context: Context): CategoryOverrideSnapshotSource = get(context)

    private fun create(context: Context): AtomicFileCategoryOverrideStore {
        val directory = File(context.noBackupFilesDir, OVERRIDE_DIRECTORY_NAME)
        if (!directory.exists()) directory.mkdirs()
        val preferences = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val access = CategoryOverrideAtomicAccess(
            AndroidxCategoryOverrideAtomicFile(File(directory, OVERRIDE_FILE_NAME)),
            preferences,
        )
        return AtomicFileCategoryOverrideStore(access, BuiltInOrganizerPolicyBundleSource)
    }
}

internal object CategoryOverrideFullStoreCodec {
    private const val HEADER_SCHEMA = "schema"
    private const val HEADER_GENERATION = "generation"
    private const val HEADER_DIGEST = "digest"
    private const val HEADER_ENTRIES = "entries"

    fun encode(snapshot: CategoryOverrideStoredSnapshot): ByteArray = buildString {
        append(HEADER_SCHEMA).append('=').append(snapshot.identity.schemaVersion).append('\n')
        append(HEADER_GENERATION).append('=').append(snapshot.identity.generation).append('\n')
        append(HEADER_DIGEST).append('=').append(snapshot.identity.sha256).append('\n')
        append(HEADER_ENTRIES).append('\n')
        append(canonicalEntries(snapshot.assignments))
        append('\n')
    }.toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): CategoryOverrideStoredSnapshot? = try {
        val text = bytes.toString(Charsets.UTF_8)
        val entriesMarker = "$HEADER_ENTRIES\n"
        val markerIndex = text.indexOf(entriesMarker)
        if (markerIndex < 0 || !text.endsWith('\n') || text.indexOf(entriesMarker, markerIndex + entriesMarker.length) >= 0) return null
        val header = text.substring(0, markerIndex).removeSuffix("\n").split('\n')
        if (header.size != 3 || header[0] != "$HEADER_SCHEMA=$SCHEMA_V1") return null
        val generationPrefix = "$HEADER_GENERATION="
        if (!header[1].startsWith(generationPrefix)) return null
        val generation = header[1].removePrefix(generationPrefix).toLongOrNull() ?: return null
        if (generation < 0L) return null
        val digestPrefix = "$HEADER_DIGEST="
        if (!header[2].startsWith(digestPrefix)) return null
        val digest = header[2].removePrefix(digestPrefix)
        if (!SHA_256.matches(digest)) return null
        val entries = text.substring(markerIndex + entriesMarker.length).removeSuffix("\n")
        val assignments = parseLegacyEntries(entries) ?: return null
        val snapshot = storedSnapshot(generation, assignments)
        snapshot.takeIf { it.identity.sha256 == digest }
    } catch (_: RuntimeException) {
        null
    }
}

private fun parseLegacyEntries(entries: String): Map<CategoryOverrideKey, CategoryId>? = parseEntries(if (entries.isBlank()) emptyList() else entries.lineSequence().toList())

private fun parseEntries(lines: List<String>): Map<CategoryOverrideKey, CategoryId>? {
    val parsed = linkedMapOf<CategoryOverrideKey, CategoryId>()
    for (encoded in lines) {
        if (encoded.isBlank()) return null
        val parts = encoded.split("|", limit = 3)
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        val key = CategoryOverrideKey(PackageName(parts[0]), ProfileId(parts[1]))
        if (parsed.put(key, CategoryId(parts[2])) != null) return null
    }
    return parsed
}

private fun storedSnapshot(
    generation: Long,
    assignments: Map<CategoryOverrideKey, CategoryId>,
): CategoryOverrideStoredSnapshot {
    val canonical = canonicalEntries(assignments)
    return CategoryOverrideStoredSnapshot(
        CategoryOverrideStoredIdentity(SCHEMA_V1, generation, sha256Canonical(canonical)),
        assignments.toSortedMap(compareBy<CategoryOverrideKey> { it.profile.value }.thenBy { it.packageName.value }),
    )
}

private fun canonicalEntries(assignments: Map<CategoryOverrideKey, CategoryId>): String = assignments.entries
    .sortedWith(compareBy<Map.Entry<CategoryOverrideKey, CategoryId>> { it.key.profile.value }.thenBy { it.key.packageName.value })
    .joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.value}" }

private const val SCHEMA_V1 = 1
private const val LEGACY_ATOMIC_AUTHORITY_SCHEMA = 2
private const val LEGACY_SCHEMA_KEY = "schema"
private const val LEGACY_GENERATION_KEY = "generation"
private const val LEGACY_ENTRIES_KEY = "entries"
private const val LEGACY_PREFERENCES_NAME = "organizer_category_overrides"
private const val OVERRIDE_DIRECTORY_NAME = "organizer_category_overrides"
private const val OVERRIDE_FILE_NAME = "snapshot-v1"
private val SHA_256 = Regex("[0-9a-f]{64}")
