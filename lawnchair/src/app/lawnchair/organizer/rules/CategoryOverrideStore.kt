package app.lawnchair.organizer.rules

import android.content.Context
import android.content.SharedPreferences
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.UserCategoryId
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
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

    /**
     * Issue #336: applies several mutations as ONE atomic publication — the
     * overrides-first delete protocol removes every assignment referencing a
     * category through exactly one generation bump and one verified snapshot
     * write, never one publication per key.
     */
    fun mutateAll(
        requests: List<CategoryOverrideMutation>,
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
        // Issue #336: the physical snapshot advances to schema 2; schema-1
        // identities remain valid until a writer migrates (and for legacy
        // comparisons). A schema the binary cannot decode never becomes a
        // stored identity here — decode fails closed first.
        require(schemaVersion == SCHEMA_V1 || schemaVersion == SCHEMA_V2)
        require(generation >= 0L)
        require(SHA_256.matches(sha256))
    }
}

internal data class CategoryOverrideStoredSnapshot(
    val identity: CategoryOverrideStoredIdentity,
    /** Issue #336: values are identity-typed (built-in or user-defined). */
    val assignments: Map<CategoryOverrideKey, CategoryIdentity>,
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
        /** Issue #336: the assignment target is the identity-typed value. */
        val category: CategoryIdentity,
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
        allowedIdentities: Set<CategoryIdentity>,
    ): CategoryOverrideWriteResult = mutateAll(listOf(request), expected, verificationProfiles, allowedIdentities)

    fun mutateAll(
        requests: List<CategoryOverrideMutation>,
        expected: CategoryOverrideStoredIdentity,
        verificationProfiles: Set<ProfileId>,
        allowedIdentities: Set<CategoryIdentity>,
    ): CategoryOverrideWriteResult = synchronized(lock) {
        if (migrationBarrierUncertain) return@synchronized CategoryOverrideWriteResult.MigrationBarrierUncertain
        if (requests.any { it is CategoryOverrideMutation.Set && it.category !in allowedIdentities }) {
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
        // Issue #336: the identity-typed schema-2 snapshot is published by a
        // read-validate-write migration of the schema-1 snapshot — same
        // assignments, exactly one generation bump, through this same access
        // boundary. A failed migration is a typed non-success: the schema-1
        // content stays authoritative and no user mutation is admitted.
        val preMigration = when (val read = readAtomicStoredLocked()) {
            is CategoryOverrideStoredReadResult.Ready -> read.snapshot
            CategoryOverrideStoredReadResult.UnsupportedSchema -> return@synchronized CategoryOverrideWriteResult.UnsupportedSchema
            CategoryOverrideStoredReadResult.Unreadable -> return@synchronized CategoryOverrideWriteResult.StoreUnreadable
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return@synchronized CategoryOverrideWriteResult.MigrationBarrierUncertain
        }
        when (val identityMigration = ensureIdentitySchemaLocked()) {
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
        // The optimistic token semantics of #99 carry over: an identity read
        // before THIS mutation's migration still matches — the migration is
        // part of the same mutation, changed only the encoding, and preserved
        // every assignment. Anything else is a concurrent writer's Conflict.
        val expectedMatches = current.identity == expected ||
            (
                preMigration.identity == expected &&
                    current.identity.schemaVersion == SCHEMA_V2 &&
                    current.identity.generation == preMigration.identity.generation + 1L &&
                    current.assignments == preMigration.assignments
                )
        if (!expectedMatches) return@synchronized CategoryOverrideWriteResult.Conflict

        val nextAssignments = current.assignments.toMutableMap()
        var changed = false
        for (request in requests) {
            when (request) {
                is CategoryOverrideMutation.Set -> {
                    if (nextAssignments[request.key] != request.category) {
                        nextAssignments[request.key] = request.category
                        changed = true
                    }
                }

                is CategoryOverrideMutation.Remove -> changed = nextAssignments.remove(request.key) != null || changed
            }
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

    /**
     * Issue #336: read-validate-publish migration from the schema-1 encoding
     * to the identity-typed schema-2 encoding. Reads never migrate: until a
     * writer runs, the schema-1 content stays authoritative and keeps its
     * exact pre-336 composer-visible semantics. There is no separate barrier
     * marker — a failed migration simply leaves the schema-1 file in place and
     * every later mutation re-attempts fail-closed.
     */
    private fun ensureIdentitySchemaLocked(): AuthorityTransition {
        val current = when (val read = readAtomicStoredLocked()) {
            is CategoryOverrideStoredReadResult.Ready -> read.snapshot
            CategoryOverrideStoredReadResult.UnsupportedSchema -> return AuthorityTransition.Unsupported
            CategoryOverrideStoredReadResult.Unreadable -> return AuthorityTransition.Unreadable
            CategoryOverrideStoredReadResult.MigrationBarrierUncertain -> return AuthorityTransition.BarrierUncertain
        }
        if (current.identity.schemaVersion == SCHEMA_V2) return AuthorityTransition.Ready
        val next = storedSnapshot(current.identity.generation + 1L, current.assignments)
        return when (publishLocked(next)) {
            PublishResult.WriteFailed -> AuthorityTransition.WriteFailed
            PublishResult.VerificationFailed -> AuthorityTransition.VerificationFailed
            PublishResult.Success -> AuthorityTransition.Ready
        }
    }

    private fun legacyHasAtomicAuthorityLocked(): Boolean = try {
        legacyPreferences.contains(LEGACY_SCHEMA_KEY) &&
            legacyPreferences.getInt(LEGACY_SCHEMA_KEY, -1) == LEGACY_ATOMIC_AUTHORITY_SCHEMA
    } catch (_: RuntimeException) {
        false
    }

    private fun readLegacyStoredLocked(): CategoryOverrideStoredReadResult = try {
        if (!legacyPreferences.contains(LEGACY_SCHEMA_KEY)) {
            CategoryOverrideStoredReadResult.Ready(legacyStoredSnapshot(0L, emptyMap()))
        } else if (legacyPreferences.getInt(LEGACY_SCHEMA_KEY, -1) != SCHEMA_V1) {
            CategoryOverrideStoredReadResult.UnsupportedSchema
        } else {
            val generation = legacyPreferences.getLong(LEGACY_GENERATION_KEY, -1L)
            val entries = legacyPreferences.getString(LEGACY_ENTRIES_KEY, "")
            if (generation < 0L || entries == null) {
                CategoryOverrideStoredReadResult.Unreadable
            } else {
                parseLegacyEntries(entries)?.let { CategoryOverrideStoredReadResult.Ready(legacyStoredSnapshot(generation, it)) }
                    ?: CategoryOverrideStoredReadResult.Unreadable
            }
        }
    } catch (_: RuntimeException) {
        CategoryOverrideStoredReadResult.Unreadable
    }

    private fun readAtomicStoredLocked(): CategoryOverrideStoredReadResult = readAtomicStoredIfPresentLocked() ?: CategoryOverrideStoredReadResult.Unreadable

    /**
     * The AtomicFile wrapper alone decides absence and interrupted-write recovery.
     * A `.new` file is never inspected or treated as an independent candidate.
     */
    private fun readAtomicStoredIfPresentLocked(): CategoryOverrideStoredReadResult? = try {
        atomicFile.openRead().use { input ->
            when (val outcome = CategoryOverrideFullStoreCodec.decodeOutcome(input.readBytes())) {
                is CategoryOverrideDecodeOutcome.Ready -> CategoryOverrideStoredReadResult.Ready(outcome.snapshot)
                CategoryOverrideDecodeOutcome.UnsupportedSchema -> CategoryOverrideStoredReadResult.UnsupportedSchema
                CategoryOverrideDecodeOutcome.Unreadable -> CategoryOverrideStoredReadResult.Unreadable
            }
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IOException) {
        CategoryOverrideStoredReadResult.Unreadable
    } catch (_: SecurityException) {
        CategoryOverrideStoredReadResult.Unreadable
    }

    private fun publishLocked(snapshot: CategoryOverrideStoredSnapshot): PublishResult {
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            atomicFile.write(stream, CategoryOverrideFullStoreCodec.encode(snapshot))
            atomicFile.sync(stream)
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
        // The identity format is content-addressed per schema: schema-1 rows
        // keep the pre-336 `pkg|profile|category` canonical bytes, schema-2
        // rows carry the kind discriminator (Issue #336).
        val canonical = canonicalEntries(visible, stored.identity.schemaVersion)
        return CategoryOverrideSnapshot(
            schemaVersion = stored.identity.schemaVersion,
            generation = stored.identity.generation,
            assignments = visible,
            identity = PolicyInputIdentity(
                PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                "schema-${stored.identity.schemaVersion}-generation-${stored.identity.generation}",
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
    /**
     * Issue #336: the active user-defined catalog joins write-time membership
     * validation. A catalog read failure is fail-closed (`TaxonomyUnavailable`)
     * — never an implicit empty catalog.
     */
    private val catalogSource: UserDefinedCategoryCatalogSource,
) : CategoryOverrideStore {
    override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = access.readVisible(capturedProfiles)

    override fun readStored(): CategoryOverrideStoredReadResult = access.readStored()

    override fun mutate(
        request: CategoryOverrideMutation,
        expected: CategoryOverrideStoredIdentity,
        verificationProfiles: Set<ProfileId>,
    ): CategoryOverrideWriteResult = mutateAll(listOf(request), expected, verificationProfiles)

    override fun mutateAll(
        requests: List<CategoryOverrideMutation>,
        expected: CategoryOverrideStoredIdentity,
        verificationProfiles: Set<ProfileId>,
    ): CategoryOverrideWriteResult {
        val bundle = (bundleSource.readActive() as? BundleReadResult.Ready)?.bundle
            ?: return CategoryOverrideWriteResult.TaxonomyUnavailable
        if (bundle.validate() != null) return CategoryOverrideWriteResult.TaxonomyUnavailable
        val allowedIdentities: MutableSet<CategoryIdentity> = linkedSetOf()
        bundle.taxonomy.allowedCategories.forEach { allowedIdentities += CategoryIdentity.BuiltIn(it) }
        when (val catalog = catalogSource.read()) {
            is UserDefinedCategoryCatalogReadResult.Ready ->
                catalog.snapshot.categories.forEach { allowedIdentities += CategoryIdentity.UserDefined(it.id) }

            UserDefinedCategoryCatalogReadResult.Unreadable,
            UserDefinedCategoryCatalogReadResult.UnsupportedSchema,
            -> return CategoryOverrideWriteResult.TaxonomyUnavailable
        }
        return access.mutateAll(requests, expected, verificationProfiles, allowedIdentities)
    }
}

internal interface CategoryOverrideAtomicFile {
    fun openRead(): FileInputStream
    fun startWrite(): FileOutputStream
    fun write(stream: FileOutputStream, bytes: ByteArray)
    fun sync(stream: FileOutputStream)
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
}

private class AndroidxCategoryOverrideAtomicFile(
    finalFile: File,
) : CategoryOverrideAtomicFile {
    private val atomicFile = AtomicFile(finalFile)

    override fun openRead(): FileInputStream = atomicFile.openRead()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun write(stream: FileOutputStream, bytes: ByteArray) = stream.write(bytes)
    override fun sync(stream: FileOutputStream) = stream.fd.sync()
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
        // Issue #336: write-time membership validation covers the active
        // user-defined catalog too.
        return AtomicFileCategoryOverrideStore(access, BuiltInOrganizerPolicyBundleSource, UserDefinedCategoryStoreModule.get(context))
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
        append(canonicalEntries(snapshot.assignments, snapshot.identity.schemaVersion))
        append('\n')
    }.toByteArray(Charsets.UTF_8)

    /** Ready-only view of [decodeOutcome]; null for every non-success. */
    fun decode(bytes: ByteArray): CategoryOverrideStoredSnapshot? = (decodeOutcome(bytes) as? CategoryOverrideDecodeOutcome.Ready)?.snapshot

    /**
     * Typed decode routing (accepted contract #336): schema 1 and schema 2
     * decode as today; a WELL-FORMED header naming a schema newer than this
     * binary supports is the typed `UnsupportedSchema` (forward-compat,
     * fail-closed); a header that is absent, non-numeric, structurally
     * damaged, or a supported schema whose content fails validation is
     * `Unreadable`. The pre-336 binary's observable outcome on schema-2 data
     * (`Unreadable`, from its own unchanged code) is not redefined here.
     */
    fun decodeOutcome(bytes: ByteArray): CategoryOverrideDecodeOutcome = try {
        val text = bytes.toString(Charsets.UTF_8)
        val entriesMarker = "$HEADER_ENTRIES\n"
        val markerIndex = text.indexOf(entriesMarker)
        if (markerIndex < 0 || !text.endsWith('\n') || text.indexOf(entriesMarker, markerIndex + entriesMarker.length) >= 0) {
            CategoryOverrideDecodeOutcome.Unreadable
        } else {
            val header = text.substring(0, markerIndex).removeSuffix("\n").split('\n')
            val parsedSchema = header[0].removePrefix("$HEADER_SCHEMA=").toIntOrNull()
            if (header.size != 3 || parsedSchema == null || parsedSchema < 0 || header[0] != "$HEADER_SCHEMA=$parsedSchema") {
                CategoryOverrideDecodeOutcome.Unreadable
            } else {
                when {
                    parsedSchema > SCHEMA_V2 -> CategoryOverrideDecodeOutcome.UnsupportedSchema
                    else -> decodeSupportedSchema(text, markerIndex, entriesMarker, header, parsedSchema)
                }
            }
        }
    } catch (_: RuntimeException) {
        CategoryOverrideDecodeOutcome.Unreadable
    }

    private fun decodeSupportedSchema(
        text: String,
        markerIndex: Int,
        entriesMarker: String,
        header: List<String>,
        parsedSchema: Int,
    ): CategoryOverrideDecodeOutcome {
        val generationPrefix = "$HEADER_GENERATION="
        if (!header[1].startsWith(generationPrefix)) return CategoryOverrideDecodeOutcome.Unreadable
        val generation = header[1].removePrefix(generationPrefix).toLongOrNull()
            ?: return CategoryOverrideDecodeOutcome.Unreadable
        if (generation < 0L) return CategoryOverrideDecodeOutcome.Unreadable
        val digestPrefix = "$HEADER_DIGEST="
        if (!header[2].startsWith(digestPrefix)) return CategoryOverrideDecodeOutcome.Unreadable
        val digest = header[2].removePrefix(digestPrefix)
        if (!SHA_256.matches(digest)) return CategoryOverrideDecodeOutcome.Unreadable
        val entries = text.substring(markerIndex + entriesMarker.length).removeSuffix("\n")
        val assignments = when (parsedSchema) {
            SCHEMA_V1 -> parseLegacyEntries(entries)?.mapValues { (_, category) -> CategoryIdentity.BuiltIn(category) }
            SCHEMA_V2 -> parseIdentityEntries(entries)
            else -> return CategoryOverrideDecodeOutcome.Unreadable
        } ?: return CategoryOverrideDecodeOutcome.Unreadable
        val snapshot = storedSnapshot(generation, assignments, parsedSchema)
        return snapshot
            .takeIf { it.identity.sha256 == digest }
            ?.let { CategoryOverrideDecodeOutcome.Ready(it) }
            ?: CategoryOverrideDecodeOutcome.Unreadable
    }
}

/** Typed codec routing of one override store read (Issue #336). */
internal sealed interface CategoryOverrideDecodeOutcome {
    data class Ready(val snapshot: CategoryOverrideStoredSnapshot) : CategoryOverrideDecodeOutcome
    data object Unreadable : CategoryOverrideDecodeOutcome
    data object UnsupportedSchema : CategoryOverrideDecodeOutcome
}

private fun parseLegacyEntries(entries: String): Map<CategoryOverrideKey, CategoryId>? = parseEntries(if (entries.isBlank()) emptyList() else entries.lineSequence().toList())

/**
 * Issue #336: schema-2 rows are kind-discriminated — `pkg|profile|b|<CategoryId>`
 * for built-in targets, `pkg|profile|u|<UserCategoryId>` for user-defined
 * ones. A malformed kind or a non-canonical user ID fails the whole decode.
 */
private fun parseIdentityEntries(entries: String): Map<CategoryOverrideKey, CategoryIdentity>? {
    if (entries.isBlank()) return emptyMap()
    val parsed = linkedMapOf<CategoryOverrideKey, CategoryIdentity>()
    for (encoded in entries.lineSequence()) {
        if (encoded.isBlank()) return null
        val parts = encoded.split("|", limit = 4)
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        val identity = when (parts[2]) {
            KIND_BUILT_IN -> CategoryIdentity.BuiltIn(CategoryId(parts[3]))

            KIND_USER_DEFINED -> try {
                CategoryIdentity.UserDefined(UserCategoryId(parts[3]))
            } catch (_: IllegalArgumentException) {
                return null
            }

            else -> return null
        }
        val key = CategoryOverrideKey(PackageName(parts[0]), ProfileId(parts[1]))
        if (parsed.put(key, identity) != null) return null
    }
    return parsed
}

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

/**
 * New writes always publish the current schema-2 identity encoding; pass
 * [schema] = [SCHEMA_V1] only for the pre-336 representations (legacy prefs
 * reads and schema-1 AtomicFile decoding), whose identity bytes must stay
 * exactly as #99 defined them.
 */
private fun storedSnapshot(
    generation: Long,
    assignments: Map<CategoryOverrideKey, CategoryIdentity>,
    schema: Int = SCHEMA_V2,
): CategoryOverrideStoredSnapshot {
    val canonical = canonicalEntries(assignments, schema)
    return CategoryOverrideStoredSnapshot(
        CategoryOverrideStoredIdentity(schema, generation, sha256Canonical(canonical)),
        assignments.toSortedMap(compareBy<CategoryOverrideKey> { it.profile.value }.thenBy { it.packageName.value }),
    )
}

private fun legacyStoredSnapshot(
    generation: Long,
    assignments: Map<CategoryOverrideKey, CategoryId>,
): CategoryOverrideStoredSnapshot {
    val canonical = assignments.entries
        .sortedWith(
            compareBy<Map.Entry<CategoryOverrideKey, CategoryId>> { it.key.profile.value }.thenBy { it.key.packageName.value },
        )
        .joinToString("\n") { "${it.key.packageName.value}|${it.key.profile.value}|${it.value.value}" }
    return CategoryOverrideStoredSnapshot(
        CategoryOverrideStoredIdentity(SCHEMA_V1, generation, sha256Canonical(canonical)),
        assignments.mapValues { (_, v) -> CategoryIdentity.BuiltIn(v) }
            .toSortedMap(compareBy<CategoryOverrideKey> { it.profile.value }.thenBy { it.packageName.value }),
    )
}

internal fun canonicalEntries(assignments: Map<CategoryOverrideKey, CategoryIdentity>, schema: Int): String {
    val sorted = assignments.entries.sortedWith(
        compareBy<Map.Entry<CategoryOverrideKey, CategoryIdentity>> { it.key.profile.value }.thenBy { it.key.packageName.value },
    )
    return when (schema) {
        SCHEMA_V1 -> sorted.joinToString("\n") { entry ->
            // Schema-1 rows predate user-defined identities (Issue #336);
            // only built-in values are representable in these bytes.
            val category = requireNotNull((entry.value as? CategoryIdentity.BuiltIn)?.id) {
                "schema-1 encoding cannot represent a user-defined identity"
            }
            "${entry.key.packageName.value}|${entry.key.profile.value}|${category.value}"
        }

        else -> sorted.joinToString("\n") { entry ->
            "${entry.key.packageName.value}|${entry.key.profile.value}|${identityEntryValue(entry.value)}"
        }
    }
}

/** Kind-discriminated value of one schema-2 entry. */
internal fun identityEntryValue(identity: CategoryIdentity): String = when (identity) {
    is CategoryIdentity.BuiltIn -> "$KIND_BUILT_IN|${identity.id.value}"
    is CategoryIdentity.UserDefined -> "$KIND_USER_DEFINED|${identity.id.value}"
}

private const val KIND_BUILT_IN = "b"
private const val KIND_USER_DEFINED = "u"
private const val SCHEMA_V2 = 2
private const val SCHEMA_V1 = 1
private const val LEGACY_ATOMIC_AUTHORITY_SCHEMA = 2
private const val LEGACY_SCHEMA_KEY = "schema"
private const val LEGACY_GENERATION_KEY = "generation"
private const val LEGACY_ENTRIES_KEY = "entries"
private const val LEGACY_PREFERENCES_NAME = "organizer_category_overrides"
private const val OVERRIDE_DIRECTORY_NAME = "organizer_category_overrides"
private const val OVERRIDE_FILE_NAME = "snapshot-v1"
private val SHA_256 = Regex("[0-9a-f]{64}")
