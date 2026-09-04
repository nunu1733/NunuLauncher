package app.lawnchair.organizer.rules

import android.content.Context
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.planning.StrategyId
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Spec 182: Rule Management-owned read source for the user's selected layout
 * strategy. First-run absence is the defined default state; corruption,
 * unsupported schema, or a read failure fails closed — the composer must never
 * silently substitute another strategy.
 */
fun interface LayoutStrategySelectionSource {
    fun read(): LayoutStrategySelectionReadResult
}

data class LayoutStrategySelectionSnapshot(
    val schemaVersion: Int,
    val generation: Long,
    val selection: StrategyId?,
    val identity: PolicyInputIdentity,
)

sealed interface LayoutStrategySelectionReadResult {
    data class Ready(val snapshot: LayoutStrategySelectionSnapshot) : LayoutStrategySelectionReadResult
    data object Unreadable : LayoutStrategySelectionReadResult
    data object UnsupportedSchema : LayoutStrategySelectionReadResult
}

/** Rule Management-owned validated write command; the UI never writes storage directly. */
sealed interface LayoutStrategySelectionWriteResult {
    data class Committed(val snapshot: LayoutStrategySelectionSnapshot) : LayoutStrategySelectionWriteResult
    data object UnsupportedStrategy : LayoutStrategySelectionWriteResult
    data object BundleUnavailable : LayoutStrategySelectionWriteResult
    data object StoreUnreadable : LayoutStrategySelectionWriteResult
    data object UnsupportedSchema : LayoutStrategySelectionWriteResult
    data object WriteFailed : LayoutStrategySelectionWriteResult
}

private fun OrganizerPolicyBundleSource.readValidatedBundle(): BundleReadResult = when (val read = readActive()) {
    is BundleReadResult.Ready -> read.bundle.validate() ?: read
    else -> read
}

internal class LayoutStrategySelectionAccess(
    private val atomicFile: LayoutStrategySelectionAtomicFile,
    private val bundleSource: OrganizerPolicyBundleSource,
) {
    private val lock = Any()

    fun read(): LayoutStrategySelectionReadResult = synchronized(lock) {
        readStoredLocked().toReadResult()
    }

    fun select(strategy: StrategyId): LayoutStrategySelectionWriteResult = synchronized(lock) {
        val bundle = (bundleSource.readActive() as? BundleReadResult.Ready)?.bundle
            ?: return@synchronized LayoutStrategySelectionWriteResult.BundleUnavailable
        bundle.validate()?.let { return@synchronized LayoutStrategySelectionWriteResult.BundleUnavailable }
        // Write-time catalog validation (spec 182): an unsupported request never
        // touches storage.
        if (strategy !in bundle.layoutStrategies.runtimeSupported) {
            return@synchronized LayoutStrategySelectionWriteResult.UnsupportedStrategy
        }
        val current = when (val stored = readStoredLocked()) {
            is StoredReady -> stored
            is StoredUnsupported -> return@synchronized LayoutStrategySelectionWriteResult.UnsupportedSchema
            is StoredUnreadable -> return@synchronized LayoutStrategySelectionWriteResult.StoreUnreadable
        }
        val nextGeneration = current.generation + 1L
        val canonical = canonicalSelection(strategy)
        val digest = sha256Canonical(canonical)
        when (publishLocked(schemaVersion = SCHEMA_V1, generation = nextGeneration, strategy = strategy, digest = digest)) {
            PublishResult.WriteFailed -> return@synchronized LayoutStrategySelectionWriteResult.WriteFailed
            PublishResult.Success -> Unit
        }
        val verified = readStoredLocked()
        if (verified !is StoredReady || verified.generation != nextGeneration || verified.selection != strategy) {
            return@synchronized LayoutStrategySelectionWriteResult.WriteFailed
        }
        LayoutStrategySelectionWriteResult.Committed(verified.toSnapshot())
    }

    private sealed interface StoredRead

    private data class StoredReady(
        val generation: Long,
        val selection: StrategyId?,
    ) : StoredRead

    private data class StoredUnreadable(val generation: Long = -1L) : StoredRead
    private data class StoredUnsupported(val generation: Long = -1L) : StoredRead

    private fun StoredRead.toReadResult(): LayoutStrategySelectionReadResult = when (this) {
        is StoredReady -> LayoutStrategySelectionReadResult.Ready(toSnapshot())
        is StoredUnsupported -> LayoutStrategySelectionReadResult.UnsupportedSchema
        is StoredUnreadable -> LayoutStrategySelectionReadResult.Unreadable
    }

    private fun StoredRead.toSnapshot(): LayoutStrategySelectionSnapshot {
        require(this is StoredReady)
        val canonical = canonicalSelection(selection)
        return LayoutStrategySelectionSnapshot(
            schemaVersion = SCHEMA_V1,
            generation = generation,
            selection = selection,
            identity = PolicyInputIdentity(
                PolicySourceKind.LAYOUT_STRATEGY_SELECTION,
                "schema-$SCHEMA_V1-generation-$generation",
                sha256Canonical(canonical),
            ),
        )
    }

    private fun readStoredLocked(): StoredRead = try {
        val text = atomicFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        parseStored(text)
    } catch (_: FileNotFoundException) {
        // Physical absence is the defined schema-v1, generation-0 empty state.
        StoredReady(generation = 0L, selection = null)
    } catch (_: IOException) {
        StoredUnreadable()
    } catch (_: SecurityException) {
        StoredUnreadable()
    }

    private fun publishLocked(
        schemaVersion: Int,
        generation: Long,
        strategy: StrategyId?,
        digest: String,
    ): PublishResult {
        var stream: FileOutputStream? = null
        return try {
            stream = atomicFile.startWrite()
            val bytes = buildString {
                append(HEADER_SCHEMA).append('=').append(schemaVersion).append('\n')
                append(HEADER_GENERATION).append('=').append(generation).append('\n')
                append(HEADER_DIGEST).append('=').append(digest).append('\n')
                append(HEADER_SELECTION).append('=').append(strategy?.value.orEmpty()).append('\n')
            }.toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            stream = null
            PublishResult.Success
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

    private fun parseStored(text: String): StoredRead {
        if (text.isEmpty()) return StoredReady(0L, null)
        val lines = text.split('\n')
        if (lines.size != 5 || !lines[4].isEmpty()) return StoredUnreadable()
        if (lines[0] != "$HEADER_SCHEMA=$SCHEMA_V1") return StoredUnsupported()
        val generationPrefix = "$HEADER_GENERATION="
        if (!lines[1].startsWith(generationPrefix)) return StoredUnreadable()
        val generation = lines[1].removePrefix(generationPrefix).toLongOrNull() ?: return StoredUnreadable()
        if (generation < 0L) return StoredUnreadable()
        val digestPrefix = "$HEADER_DIGEST="
        if (!lines[2].startsWith(digestPrefix)) return StoredUnreadable()
        val digest = lines[2].removePrefix(digestPrefix)
        if (!SHA_256_REGEX.matches(digest)) return StoredUnreadable()
        val selectionPrefix = "$HEADER_SELECTION="
        if (!lines[3].startsWith(selectionPrefix)) return StoredUnreadable()
        val selectionText = lines[3].removePrefix(selectionPrefix)
        val selection = selectionText.takeIf { it.isNotEmpty() }?.let { StrategyId(it) }
        if (sha256Canonical(canonicalSelection(selection)) != digest) return StoredUnreadable()
        return StoredReady(generation, selection)
    }

    private enum class PublishResult { Success, WriteFailed }

    private companion object {
        const val SCHEMA_V1 = 1
        const val HEADER_SCHEMA = "schema"
        const val HEADER_GENERATION = "generation"
        const val HEADER_DIGEST = "digest"
        const val HEADER_SELECTION = "selection"
    }
}

private fun canonicalSelection(selection: StrategyId?): String = selection?.value.orEmpty()

internal interface LayoutStrategySelectionAtomicFile {
    fun openRead(): FileInputStream
    fun startWrite(): FileOutputStream
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
}

private class AndroidxLayoutStrategySelectionAtomicFile(
    finalFile: File,
) : LayoutStrategySelectionAtomicFile {
    private val atomicFile = AtomicFile(finalFile)

    override fun openRead(): FileInputStream = atomicFile.openRead()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
}

/** Read-only source handed to the #83 composer; no write capability leaks. */
internal class AtomicFileLayoutStrategySelectionSource(
    private val access: LayoutStrategySelectionAccess,
) : LayoutStrategySelectionSource {
    override fun read(): LayoutStrategySelectionReadResult = access.read()
}

/** Process-local production wiring shared by the composer and the future picker UI. */
object LayoutStrategySelectionModule {
    @Volatile private var access: LayoutStrategySelectionAccess? = null

    fun source(context: Context): LayoutStrategySelectionSource = synchronized(this) {
        getAccess(context.applicationContext)
    }.let { AtomicFileLayoutStrategySelectionSource(it) }

    internal fun store(context: Context): LayoutStrategySelectionAccess = synchronized(this) {
        getAccess(context.applicationContext)
    }

    private fun getAccess(appContext: Context): LayoutStrategySelectionAccess {
        access?.let { return it }
        val directory = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
        if (!directory.exists()) directory.mkdirs()
        return LayoutStrategySelectionAccess(
            AndroidxLayoutStrategySelectionAtomicFile(File(directory, FILE_NAME)),
            BuiltInOrganizerPolicyBundleSource,
        ).also { access = it }
    }

    private const val DIRECTORY_NAME = "organizer_strategy_selection"
    private const val FILE_NAME = "selection-v1"
}

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
