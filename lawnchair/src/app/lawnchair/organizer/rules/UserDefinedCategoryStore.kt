package app.lawnchair.organizer.rules

import android.content.Context
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.planning.UserCategoryId
import app.lawnchair.organizer.planning.UserDefinedCategory
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import java.util.UUID

/**
 * Read-side contract of the Rule Management-owned user-defined category
 * catalog (Issue #336). The composer consumes this narrow port inside the
 * mandatory dynamic cut; no writer capability leaks through it.
 */
fun interface UserDefinedCategoryCatalogSource {
    fun read(): UserDefinedCategoryCatalogReadResult
}

sealed interface UserDefinedCategoryCatalogReadResult {
    data class Ready(val snapshot: UserDefinedCategoryCatalogSnapshot) : UserDefinedCategoryCatalogReadResult

    /** Corrupt, duplicate ID, malformed name, or digest mismatch — fail-closed. */
    data object Unreadable : UserDefinedCategoryCatalogReadResult

    /** A schema newer than this binary supports — fail-closed. */
    data object UnsupportedSchema : UserDefinedCategoryCatalogReadResult
}

/**
 * One complete catalog snapshot as proven by the store. The identity is
 * content-addressed: a non-empty catalog carries `(schema, generation,
 * digest)`; the defined first-run empty catalog carries the canonical
 * sentinel identity so provenance and the dynamic cut stay byte-stable for
 * built-in-only runs.
 */
data class UserDefinedCategoryCatalogSnapshot(
    val schemaVersion: Int,
    val generation: Long,
    val categories: List<UserDefinedCategory>,
    val identity: PolicyInputIdentity,
)

/**
 * Provenance identity rules for the catalog (Issue #336). Mirrors the #204
 * no-intent sentinel: the defined empty-catalog state is a valid
 * content-addressed identity, never a missing source.
 */
object UserDefinedCategoryCatalogIdentity {
    const val EMPTY_CATALOG_VERSION = "none"
    const val EMPTY_CATALOG_CANONICAL = "user-defined-category-catalog:none"

    fun emptyCatalogSentinel(): PolicyInputIdentity = PolicyInputIdentity(
        PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG,
        EMPTY_CATALOG_VERSION,
        sha256Canonical(EMPTY_CATALOG_CANONICAL),
    )

    /** `(schema, generation, digest)` of a snapshot; sentinel when empty. */
    fun identityOf(
        schemaVersion: Int,
        generation: Long,
        categories: List<UserDefinedCategory>,
    ): PolicyInputIdentity {
        if (categories.isEmpty()) return emptyCatalogSentinel()
        return PolicyInputIdentity(
            PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG,
            "schema-$schemaVersion-generation-$generation",
            sha256Canonical(canonicalEntries(categories)),
        )
    }
}

/** Complete-snapshot identity of the store: the optimistic conflict token. */
internal data class UserDefinedCategoryStoredIdentity(
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

internal data class UserDefinedCategoryStoredSnapshot(
    val identity: UserDefinedCategoryStoredIdentity,
    val categories: List<UserDefinedCategory>,
)

internal sealed interface UserDefinedCategoryStoredReadResult {
    data class Ready(val snapshot: UserDefinedCategoryStoredSnapshot) : UserDefinedCategoryStoredReadResult
    data object Unreadable : UserDefinedCategoryStoredReadResult
    data object UnsupportedSchema : UserDefinedCategoryStoredReadResult
}

internal sealed interface UserDefinedCategoryMutation {
    data class Create(val displayName: String) : UserDefinedCategoryMutation
    data class Rename(val id: UserCategoryId, val displayName: String) : UserDefinedCategoryMutation
    data class Delete(val id: UserCategoryId) : UserDefinedCategoryMutation
}

internal sealed interface UserDefinedCategoryWriteResult {
    data class Committed(
        val stored: UserDefinedCategoryStoredIdentity,
        val catalog: PolicyInputIdentity,
        /** The minted entry for Create; null for Rename/Delete. */
        val created: UserDefinedCategory?,
    ) : UserDefinedCategoryWriteResult

    /** No state change: file, generation, and identity preserved. */
    data class NoChange(val stored: UserDefinedCategoryStoredIdentity, val catalog: PolicyInputIdentity) : UserDefinedCategoryWriteResult

    /** Normalized name empty/overlong or carrying a forbidden character. */
    data object InvalidName : UserDefinedCategoryWriteResult

    /** Normalized name collides with an existing entry of the catalog. */
    data object DuplicateName : UserDefinedCategoryWriteResult

    /** Rename/Delete naming an ID absent from the catalog. */
    data object UnknownId : UserDefinedCategoryWriteResult

    /** The bounded catalog capacity of 64 entries is exhausted. */
    data object CapacityExceeded : UserDefinedCategoryWriteResult
    data object StoreUnreadable : UserDefinedCategoryWriteResult
    data object UnsupportedSchema : UserDefinedCategoryWriteResult
    data object Conflict : UserDefinedCategoryWriteResult
    data object WriteFailed : UserDefinedCategoryWriteResult
    data object VerificationFailed : UserDefinedCategoryWriteResult
}

/**
 * Rule Management's writable owner for the user-defined category catalog
 * (Issue #336). Complete-snapshot persistence behind one process-local access
 * boundary, following the #99 `CategoryOverrideAtomicAccess` pattern: the same
 * mutex for every read and write, recovery-aware `AtomicFile.openRead()`, the
 * `.new` file never an input, `startWrite`/fsync/`finishWrite` publication
 * with `failWrite` on interruption, and a full re-read verification through
 * the same boundary before any success is reported. Physical absence of the
 * file is the defined schema-1 generation-0 empty catalog, not a missing
 * source.
 */
internal class UserDefinedCategoryAtomicAccess(private val atomicFile: UserDefinedCategoryAtomicFile) {

    private val lock = Any()

    fun readStored(): UserDefinedCategoryStoredReadResult = synchronized(lock) { readStoredLocked() }

    fun readVisible(): UserDefinedCategoryCatalogReadResult = synchronized(lock) {
        when (val stored = readStoredLocked()) {
            is UserDefinedCategoryStoredReadResult.Ready -> UserDefinedCategoryCatalogReadResult.Ready(visibleSnapshot(stored.snapshot))
            UserDefinedCategoryStoredReadResult.Unreadable -> UserDefinedCategoryCatalogReadResult.Unreadable
            UserDefinedCategoryStoredReadResult.UnsupportedSchema -> UserDefinedCategoryCatalogReadResult.UnsupportedSchema
        }
    }

    fun mutate(
        request: UserDefinedCategoryMutation,
        expected: UserDefinedCategoryStoredIdentity,
    ): UserDefinedCategoryWriteResult = synchronized(lock) {
        val current = when (val read = readStoredLocked()) {
            is UserDefinedCategoryStoredReadResult.Ready -> read.snapshot
            UserDefinedCategoryStoredReadResult.Unreadable -> return@synchronized UserDefinedCategoryWriteResult.StoreUnreadable
            UserDefinedCategoryStoredReadResult.UnsupportedSchema -> return@synchronized UserDefinedCategoryWriteResult.UnsupportedSchema
        }
        if (current.identity != expected) return@synchronized UserDefinedCategoryWriteResult.Conflict

        val requestedName = when (request) {
            is UserDefinedCategoryMutation.Create -> UserDefinedCategoryNameRules.normalize(request.displayName)
            is UserDefinedCategoryMutation.Rename -> UserDefinedCategoryNameRules.normalize(request.displayName)
            is UserDefinedCategoryMutation.Delete -> null
        }
        if (requestedName != null && !UserDefinedCategoryNameRules.isValid(requestedName)) {
            return@synchronized UserDefinedCategoryWriteResult.InvalidName
        }

        val next: List<UserDefinedCategory> = when (request) {
            is UserDefinedCategoryMutation.Create -> {
                if (current.categories.any { it.displayName == requestedName }) {
                    return@synchronized UserDefinedCategoryWriteResult.DuplicateName
                }
                if (current.categories.size >= CAPACITY) {
                    return@synchronized UserDefinedCategoryWriteResult.CapacityExceeded
                }
                // ID minting is Rule Management's job: a fresh random UUID v4,
                // never derived from the display name or any external input.
                var minted = UserCategoryId(UUID.randomUUID().toString())
                while (current.categories.any { it.id == minted }) {
                    minted = UserCategoryId(UUID.randomUUID().toString())
                }
                current.categories + UserDefinedCategory(minted, requestedName!!)
            }

            is UserDefinedCategoryMutation.Rename -> {
                val existing = current.categories.firstOrNull { it.id == request.id }
                    ?: return@synchronized UserDefinedCategoryWriteResult.UnknownId
                if (existing.displayName == requestedName) {
                    return@synchronized noChangeLocked(current)
                }
                if (current.categories.any { it.id != request.id && it.displayName == requestedName }) {
                    return@synchronized UserDefinedCategoryWriteResult.DuplicateName
                }
                current.categories.map { entry ->
                    if (entry.id == request.id) entry.copy(displayName = requestedName!!) else entry
                }
            }

            is UserDefinedCategoryMutation.Delete -> {
                if (current.categories.none { it.id == request.id }) {
                    return@synchronized UserDefinedCategoryWriteResult.UnknownId
                }
                current.categories.filterNot { it.id == request.id }
            }
        }

        // Canonical store order is ID byte order; the ID and its position are
        // rename-invariant (only the display name changes).
        val sorted = next.sortedBy { it.id }
        if (sorted == current.categories) {
            return@synchronized noChangeLocked(current)
        }

        val nextSnapshot = storedSnapshot(current.identity.generation + 1L, sorted)
        when (publishLocked(nextSnapshot)) {
            PublishResult.WriteFailed -> return@synchronized UserDefinedCategoryWriteResult.WriteFailed
            PublishResult.VerificationFailed -> return@synchronized UserDefinedCategoryWriteResult.VerificationFailed
            PublishResult.Success -> Unit
        }
        val verified = (readStoredLocked() as? UserDefinedCategoryStoredReadResult.Ready)?.snapshot
            ?: return@synchronized UserDefinedCategoryWriteResult.VerificationFailed
        if (verified != nextSnapshot) return@synchronized UserDefinedCategoryWriteResult.VerificationFailed
        val created = when (request) {
            is UserDefinedCategoryMutation.Create ->
                verified.categories.firstOrNull { entry -> current.categories.none { it.id == entry.id } }

            else -> null
        }
        UserDefinedCategoryWriteResult.Committed(
            verified.identity,
            visibleSnapshot(verified).identity,
            created,
        )
    }

    private fun noChangeLocked(current: UserDefinedCategoryStoredSnapshot) = UserDefinedCategoryWriteResult.NoChange(
        current.identity,
        visibleSnapshot(current).identity,
    )

    private fun readStoredLocked(): UserDefinedCategoryStoredReadResult = try {
        atomicFile.openRead().use { input ->
            when (val outcome = UserDefinedCategoryStoreCodec.decodeOutcome(input.readBytes())) {
                is UserDefinedCategoryStoreDecodeOutcome.Ready -> UserDefinedCategoryStoredReadResult.Ready(outcome.snapshot)
                UserDefinedCategoryStoreDecodeOutcome.UnsupportedSchema -> UserDefinedCategoryStoredReadResult.UnsupportedSchema
                UserDefinedCategoryStoreDecodeOutcome.Unreadable -> UserDefinedCategoryStoredReadResult.Unreadable
            }
        }
    } catch (_: FileNotFoundException) {
        // Physical absence is the defined generation-0 empty catalog.
        UserDefinedCategoryStoredReadResult.Ready(storedSnapshot(0L, emptyList()))
    } catch (_: IOException) {
        UserDefinedCategoryStoredReadResult.Unreadable
    } catch (_: SecurityException) {
        UserDefinedCategoryStoredReadResult.Unreadable
    }

    private fun publishLocked(snapshot: UserDefinedCategoryStoredSnapshot): PublishResult {
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            atomicFile.write(stream, UserDefinedCategoryStoreCodec.encode(snapshot))
            atomicFile.sync(stream)
            atomicFile.finishWrite(stream)
            stream = null
            val verified = (readStoredLocked() as? UserDefinedCategoryStoredReadResult.Ready)?.snapshot
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

    private fun visibleSnapshot(stored: UserDefinedCategoryStoredSnapshot): UserDefinedCategoryCatalogSnapshot {
        val identity = UserDefinedCategoryCatalogIdentity.identityOf(
            stored.identity.schemaVersion,
            stored.identity.generation,
            stored.categories,
        )
        return UserDefinedCategoryCatalogSnapshot(
            schemaVersion = stored.identity.schemaVersion,
            generation = stored.identity.generation,
            categories = stored.categories,
            identity = identity,
        )
    }

    private companion object {
        /**
         * Bounded catalog capacity (spec #336): a Create exceeding it is a
         * typed failure with no write.
         */
        const val CAPACITY = USER_DEFINED_CATEGORY_CAPACITY
    }

    private enum class PublishResult { Success, WriteFailed, VerificationFailed }
}

/** Name normalization + validation rules (spec #336 Identity model). */
internal object UserDefinedCategoryNameRules {

    /**
     * The store applies this before validation and persists the normalized
     * form, so codec validation and UI tests see one canonical rule.
     */
    fun normalize(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)

    fun isValid(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        if (normalized.codePointCount(0, normalized.length) > MAX_CODE_POINTS) return false
        if (normalized.contains('|')) return false
        if (normalized.any { it == '\n' || it == '\r' }) return false
        return true
    }

    const val MAX_CODE_POINTS = 50
}

internal interface UserDefinedCategoryAtomicFile {
    fun openRead(): FileInputStream
    fun startWrite(): FileOutputStream
    fun write(stream: FileOutputStream, bytes: ByteArray)
    fun sync(stream: FileOutputStream)
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
}

private class AndroidxUserDefinedCategoryAtomicFile(
    finalFile: File,
) : UserDefinedCategoryAtomicFile {
    private val atomicFile = AtomicFile(finalFile)

    override fun openRead(): FileInputStream = atomicFile.openRead()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun write(stream: FileOutputStream, bytes: ByteArray) = stream.write(bytes)
    override fun sync(stream: FileOutputStream) = stream.fd.sync()
    override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
}

/** Writer surface owned by Rule Management authoring (lifecycle task of #336). */
internal interface UserDefinedCategoryStore : UserDefinedCategoryCatalogSource {
    fun readStored(): UserDefinedCategoryStoredReadResult

    fun mutate(
        request: UserDefinedCategoryMutation,
        expected: UserDefinedCategoryStoredIdentity,
    ): UserDefinedCategoryWriteResult
}

internal class AtomicFileUserDefinedCategoryStore(
    private val access: UserDefinedCategoryAtomicAccess,
) : UserDefinedCategoryStore {
    override fun read(): UserDefinedCategoryCatalogReadResult = access.readVisible()

    override fun readStored(): UserDefinedCategoryStoredReadResult = access.readStored()

    override fun mutate(
        request: UserDefinedCategoryMutation,
        expected: UserDefinedCategoryStoredIdentity,
    ): UserDefinedCategoryWriteResult = access.mutate(request, expected)
}

/**
 * Process-local production wiring. The file lives under
 * `noBackupFilesDir` — excluded from every backup/restore transport by the
 * platform (and the repo's `backupscheme.xml` is an include-allowlist that
 * never names it), so a restored installation starts from the defined empty
 * catalog (Issue #336).
 */
internal object UserDefinedCategoryStoreModule {
    @Volatile private var instance: AtomicFileUserDefinedCategoryStore? = null

    fun get(context: Context): AtomicFileUserDefinedCategoryStore = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    fun source(context: Context): UserDefinedCategoryCatalogSource = get(context)

    private fun create(context: Context): AtomicFileUserDefinedCategoryStore {
        val directory = File(context.noBackupFilesDir, CATALOG_DIRECTORY_NAME)
        if (!directory.exists()) directory.mkdirs()
        return AtomicFileUserDefinedCategoryStore(
            UserDefinedCategoryAtomicAccess(AndroidxUserDefinedCategoryAtomicFile(File(directory, CATALOG_FILE_NAME))),
        )
    }

    internal const val CATALOG_DIRECTORY_NAME = "organizer_user_categories"
    internal const val CATALOG_FILE_NAME = "catalog-v1"
}

internal object UserDefinedCategoryStoreCodec {
    private const val HEADER_SCHEMA = "schema"
    private const val HEADER_GENERATION = "generation"
    private const val HEADER_DIGEST = "digest"
    private const val HEADER_ENTRIES = "entries"

    fun encode(snapshot: UserDefinedCategoryStoredSnapshot): ByteArray = buildString {
        append(HEADER_SCHEMA).append('=').append(snapshot.identity.schemaVersion).append('\n')
        append(HEADER_GENERATION).append('=').append(snapshot.identity.generation).append('\n')
        append(HEADER_DIGEST).append('=').append(snapshot.identity.sha256).append('\n')
        append(HEADER_ENTRIES).append('\n')
        append(canonicalEntries(snapshot.categories))
        if (snapshot.categories.isNotEmpty()) append('\n')
    }.toByteArray(Charsets.UTF_8)

    /** Ready-only view of [decodeOutcome]; null for every non-success. */
    fun decode(bytes: ByteArray): UserDefinedCategoryStoredSnapshot? = (decodeOutcome(bytes) as? UserDefinedCategoryStoreDecodeOutcome.Ready)?.snapshot

    /**
     * Typed decode routing (accepted contract #336): a WELL-FORMED header
     * naming a schema newer than this binary supports is the typed
     * `UnsupportedSchema` (forward-compat, fail-closed, zero-write); a header
     * that is absent, non-numeric, non-canonically encoded (`schema=01`),
     * numerically below the supported version, structurally damaged, or a
     * supported schema whose content fails validation is `Unreadable` — no
     * repair, no default, no partial catalog.
     */
    fun decodeOutcome(bytes: ByteArray): UserDefinedCategoryStoreDecodeOutcome = try {
        val text = bytes.toString(Charsets.UTF_8)
        val entriesMarker = "$HEADER_ENTRIES\n"
        val markerIndex = text.indexOf(entriesMarker)
        if (markerIndex < 0 || text.indexOf(entriesMarker, markerIndex + entriesMarker.length) >= 0) {
            UserDefinedCategoryStoreDecodeOutcome.Unreadable
        } else {
            val header = text.substring(0, markerIndex).removeSuffix("\n").split('\n')
            if (header.size != 3 || !header[0].startsWith("$HEADER_SCHEMA=")) {
                UserDefinedCategoryStoreDecodeOutcome.Unreadable
            } else {
                val schema = header[0].removePrefix("$HEADER_SCHEMA=").toIntOrNull()
                when {
                    // Non-numeric or malformed schema line: not a recognizable
                    // version statement at all. The re-encoding check rejects
                    // non-canonical decimal forms (`schema=01`, `schema=+1`)
                    // so the writer's exact header vocabulary is the only one
                    // that can ever reach a decode branch.
                    schema == null || schema < 0 -> UserDefinedCategoryStoreDecodeOutcome.Unreadable

                    header[0] != "$HEADER_SCHEMA=$schema" -> UserDefinedCategoryStoreDecodeOutcome.Unreadable

                    schema > SCHEMA_V1 -> UserDefinedCategoryStoreDecodeOutcome.UnsupportedSchema

                    schema < SCHEMA_V1 -> UserDefinedCategoryStoreDecodeOutcome.Unreadable

                    else -> decodeSupportedSchema(text, markerIndex, entriesMarker, header)
                }
            }
        }
    } catch (_: RuntimeException) {
        UserDefinedCategoryStoreDecodeOutcome.Unreadable
    }

    private fun decodeSupportedSchema(
        text: String,
        markerIndex: Int,
        entriesMarker: String,
        header: List<String>,
    ): UserDefinedCategoryStoreDecodeOutcome {
        val generationPrefix = "$HEADER_GENERATION="
        if (!header[1].startsWith(generationPrefix)) return UserDefinedCategoryStoreDecodeOutcome.Unreadable
        val generation = header[1].removePrefix(generationPrefix).toLongOrNull()
            ?: return UserDefinedCategoryStoreDecodeOutcome.Unreadable
        if (generation < 0L) return UserDefinedCategoryStoreDecodeOutcome.Unreadable
        val digestPrefix = "$HEADER_DIGEST="
        if (!header[2].startsWith(digestPrefix)) return UserDefinedCategoryStoreDecodeOutcome.Unreadable
        val digest = header[2].removePrefix(digestPrefix)
        if (!SHA_256.matches(digest)) return UserDefinedCategoryStoreDecodeOutcome.Unreadable
        val entries = parseEntries(text.substring(markerIndex + entriesMarker.length))
            ?: return UserDefinedCategoryStoreDecodeOutcome.Unreadable
        val snapshot = storedSnapshot(generation, entries)
        return snapshot
            .takeIf { it.identity.sha256 == digest }
            ?.let { UserDefinedCategoryStoreDecodeOutcome.Ready(it) }
            ?: UserDefinedCategoryStoreDecodeOutcome.Unreadable
    }
}

/** Typed codec routing of one catalog store read (Issue #336). */
internal sealed interface UserDefinedCategoryStoreDecodeOutcome {
    data class Ready(val snapshot: UserDefinedCategoryStoredSnapshot) : UserDefinedCategoryStoreDecodeOutcome
    data object Unreadable : UserDefinedCategoryStoreDecodeOutcome
    data object UnsupportedSchema : UserDefinedCategoryStoreDecodeOutcome
}

/**
 * Canonical complete entry set: `id|displayName` rows in ID byte order. The
 * store digest is exactly `SHA-256(canonicalEntries)`, so the empty catalog's
 * digest equals `SHA-256("")`.
 */
internal fun canonicalEntries(categories: List<UserDefinedCategory>): String = categories
    .sortedBy { it.id }
    .joinToString("\n") { "${it.id.value}|${it.displayName}" }

/**
 * Read-time invariant validation (accepted contract #336): every persisted
 * name must already be in canonical form (`name == normalize(name)` — the
 * store persists only normalized names), normalized names must be unique
 * within the catalog (covering exact, trim-equivalent, and NFC-equivalent
 * duplicates), and the bounded capacity applies on read too. Any violation
 * fails the whole decode.
 */
private fun parseEntries(text: String): List<UserDefinedCategory>? {
    if (text.isEmpty()) return emptyList()
    if (!text.endsWith("\n")) return null
    val lines = text.removeSuffix("\n").split('\n')
    if (lines.size == 1 && lines[0].isEmpty()) return emptyList()
    if (lines.size > USER_DEFINED_CATEGORY_CAPACITY) return null
    val parsed = LinkedHashMap<UserCategoryId, UserDefinedCategory>()
    val seenNormalizedNames = linkedSetOf<String>()
    var previousId: UserCategoryId? = null
    for (encoded in lines) {
        val parts = encoded.split('|')
        if (parts.size != 2) return null
        val id = try {
            UserCategoryId(parts[0])
        } catch (_: IllegalArgumentException) {
            return null
        }
        val name = parts[1]
        val normalized = UserDefinedCategoryNameRules.normalize(name)
        if (!UserDefinedCategoryNameRules.isValid(normalized)) return null
        // The writer persists only canonical (trim+NFC-normalized) names; a
        // stored non-canonical form is external corruption.
        if (name != normalized) return null
        if (!seenNormalizedNames.add(normalized)) return null
        if (id in parsed) return null
        if (previousId != null && previousId >= id) return null
        parsed[id] = UserDefinedCategory(id, name)
        previousId = id
    }
    return parsed.values.toList()
}

/** Bounded catalog capacity (spec #336), enforced on write and on read. */
internal const val USER_DEFINED_CATEGORY_CAPACITY = 64

internal fun storedSnapshot(
    generation: Long,
    categories: List<UserDefinedCategory>,
): UserDefinedCategoryStoredSnapshot {
    val sorted = categories.sortedBy { it.id }
    val canonical = canonicalEntries(sorted)
    return UserDefinedCategoryStoredSnapshot(
        UserDefinedCategoryStoredIdentity(SCHEMA_V1, generation, sha256Canonical(canonical)),
        sorted,
    )
}

private const val SCHEMA_V1 = 1
private val SHA_256 = Regex("[0-9a-f]{64}")
