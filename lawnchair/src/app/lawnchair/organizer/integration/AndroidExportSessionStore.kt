package app.lawnchair.organizer.integration

import android.content.Context
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.personalization.CandidateScopeIdentity
import app.lawnchair.organizer.personalization.ExportEntryOrigin
import app.lawnchair.organizer.personalization.ExportInvalidationResult
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SignalProvenance
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.UserCategoryId
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #204: the Android/file-backed implementation of the export session
 * store (spec 204). App-private (`noBackupFilesDir`, outside any backup),
 * Launcher-favorites-DB-independent, single-active-session: the store holds at
 * most one session, so creating a new export naturally invalidates the prior
 * one. Corruption, unknown schema, and interrupted writes degrade to "no
 * session" (fail-closed; the user re-exports).
 *
 * Purity boundary: this class is the ONLY Android/storage boundary of the
 * personalization contract; the pure package holds the seam interface only.
 */
class AndroidExportSessionStore : ExportSessionStore {

    private val json = Json
    private val lock = Any()
    private val atomicFile: AtomicFile

    /** Production constructor: app-private, backup-excluded storage. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, SESSION_FILE_NAME))

    /** Test constructor: explicit file target, same semantics. */
    internal constructor(baseFile: File) {
        atomicFile = AtomicFile(baseFile)
    }

    override fun save(session: ExportSession): Boolean = synchronized(lock) {
        val record = SessionRecord(
            schemaVersion = SCHEMA_VERSION,
            exportId = session.exportId,
            itemRefs = session.itemRefs.entries
                .map { (ref, itemId) -> RefEntry(ref = ref, itemId = itemId.value) }
                .sortedBy { it.ref },
            tier = session.tier.name,
            sourceContextDigest = session.sourceContextDigest,
            signalProvenance = session.signalProvenance?.let {
                SignalProvenanceRecord(schemaVersion = it.schemaVersion, contentDigest = it.contentDigest)
            },
            createdAtEpochMs = session.createdAtEpochMs,
            expiresAtEpochMs = session.expiresAtEpochMs,
            // Issue #331 (v2): the export scope's candidate identities and
            // projection digest (spec 331 "Data and state"). App-private
            // stable identities; never part of the export document.
            scopeCandidates = session.scopeCandidates
                .map { CandidateScopeRecord(component = it.component.value, profile = it.profile.value) }
                .sortedWith(compareBy({ it.component }, { it.profile })),
            scopeCandidateDigest = session.scopeCandidateDigest,
            // Issue #337: the ref → identity mapping (app-private; the
            // document itself carries no stable identifier).
            categoryRefs = session.categoryRefs.entries
                .map { (ref, identity) ->
                    when (identity) {
                        is CategoryIdentity.BuiltIn -> CategoryRefRecord(ref, "BUILT_IN", identity.id.value)
                        is CategoryIdentity.UserDefined -> CategoryRefRecord(ref, "USER_DEFINED", identity.id.value)
                    }
                }
                .sortedBy { it.ref },
            // Issue #417 (spec 417 "Data and state"): the durable entry
            // origin, written exactly once here at session creation and
            // immutable afterwards — no store operation rewrites it. A null
            // origin (an untagged record) stays encodable: the key is omitted
            // and the record reads back through the legacy decode rule.
            entryOrigin = session.entryOrigin?.name,
        )
        return writeRecord(record)
    }

    private fun writeRecord(record: SessionRecord): Boolean {
        val bytes = json.encodeToString(SessionRecord.serializer(), record).encodeToByteArray()
        val out = try {
            atomicFile.startWrite()
        } catch (e: IOException) {
            return false
        }
        return try {
            out.write(bytes)
            atomicFile.finishWrite(out)
            true
        } catch (e: IOException) {
            atomicFile.failWrite(out)
            false
        }
    }

    override fun load(exportId: String): ExportSession? = synchronized(lock) {
        // Expiry is deliberately NOT collapsed into absence: the validator
        // distinguishes SESSION_EXPIRED (matching, expired record) from
        // EXPORT_MISMATCH (unknown/old exportId).
        readSession()?.takeIf { it.exportId == exportId }
    }

    override fun active(nowEpochMs: Long): ExportSession? = synchronized(lock) {
        readSession()?.takeIf { !it.isExpired(nowEpochMs) }
    }

    override fun invalidate(exportId: String) {
        synchronized(lock) {
            val current = readSession() ?: return
            if (current.exportId == exportId) atomicFile.delete()
        }
    }

    override fun invalidateIf(expectedExportId: String): ExportInvalidationResult = synchronized(lock) {
        // Issue #417 (spec 417 "scope-bound依頼破棄の契約化") conditional
        // invalidation commit — the session-store mirror of the pending
        // store's `discardIf` (spec 375): read-compare-tombstone, atomic
        // against other store access. Only the exact expected session is
        // tombstoned; a replaced/absent/already-invalidated record is
        // `NoMatch` (nothing stale can resurface), and a failed atomic
        // rewrite is `WriteFailed` — the session stays valid and the
        // invalidation is retryable.
        val record = readRecord() ?: return@synchronized ExportInvalidationResult.NoMatch
        // A structurally invalid record already reads as "no session" to
        // every reader, so there is nothing stale left to invalidate.
        if (
            record.invalidated ||
            record.exportId != expectedExportId ||
            record.toExportSession() == null
        ) {
            return@synchronized ExportInvalidationResult.NoMatch
        }
        val committed = writeRecord(record.copy(invalidated = true))
        if (!committed) return@synchronized ExportInvalidationResult.WriteFailed
        // The tombstone commit is the durable validity truth; the physical
        // delete afterwards is best-effort (a tombstone the delete never
        // reached still reads as "no session").
        atomicFile.delete()
        ExportInvalidationResult.Committed
    }

    /**
     * The raw durable record read: null for true absence, an unreadable or
     * corrupt body, or an unsupported schema (the pre-existing fail-closed
     * shapes). Does not interpret the tombstone or the session structure.
     */
    private fun readRecord(): SessionRecord? {
        val stream = try {
            atomicFile.openRead()
        } catch (e: FileNotFoundException) {
            return null
        }
        val bytes = try {
            stream.readBytes()
        } catch (e: IOException) {
            return null
        } finally {
            runCatching { stream.close() }
        }
        val record = runCatching {
            json.decodeFromString(SessionRecord.serializer(), bytes.decodeToString())
        }.getOrNull() ?: return null
        if (record.schemaVersion != SCHEMA_VERSION) return null
        return record
    }

    private fun readSession(): ExportSession? {
        val record = readRecord() ?: return null
        // Issue #417: a committed invalidation tombstone reads as absent —
        // the mark survives even when the best-effort physical delete after
        // the commit never landed (e.g. a process death right after it).
        if (record.invalidated) return null
        return record.toExportSession()
    }

    /**
     * Decodes the durable record into the session read model, or null when
     * the record is structurally invalid (fail-closed "no session").
     *
     * Issue #417 legacy decode rule (spec 417 "Data and state"): the raw
     * `entryOrigin` is carried verbatim (`null` = absent on a record written
     * before #417, and an unknown origin name is corruption, not a category
     * guess — the surrounding runCatching degrades it to "no session"). The
     * origin callers observe resolves on [ExportSession.resolvedEntryOrigin]:
     * absent origin + non-empty `scopeCandidates` = legacy RUN_IN (uniquely
     * recoverable from the durable scope, keeping #375's selection-restore
     * semantics); absent origin + empty scope = IDLE (fail-safe); a present
     * origin is respected as-is.
     */
    private fun SessionRecord.toExportSession(): ExportSession? = runCatching {
        ExportSession(
            exportId = exportId,
            itemRefs = itemRefs.associate { it.ref to ItemId(it.itemId) },
            tier = PrivacyTier.valueOf(tier),
            sourceContextDigest = sourceContextDigest,
            signalProvenance = signalProvenance?.let {
                SignalProvenance(schemaVersion = it.schemaVersion, contentDigest = it.contentDigest)
            },
            createdAtEpochMs = createdAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            scopeCandidates = scopeCandidates.map {
                CandidateTarget.AppKey(ComponentKey(it.component), ProfileId(it.profile))
            },
            scopeCandidateDigest = scopeCandidateDigest.ifEmpty { CandidateScopeIdentity.EMPTY_DIGEST },
            categoryRefs = categoryRefs.associate { entry ->
                // An unknown kind is a corrupted record, not a category:
                // the surrounding runCatching degrades it to "no session"
                // (fail-closed) instead of accepting it as user-defined.
                entry.ref to when (entry.kind) {
                    "BUILT_IN" -> CategoryIdentity.BuiltIn(CategoryId(entry.id))
                    "USER_DEFINED" -> CategoryIdentity.UserDefined(UserCategoryId(entry.id))
                    else -> error("unknown category ref kind")
                }
            },
            entryOrigin = entryOrigin?.let { ExportEntryOrigin.valueOf(it) },
        )
    }.getOrNull()

    @Serializable
    private data class SessionRecord(
        @SerialName("schemaVersion") val schemaVersion: Int,
        @SerialName("exportId") val exportId: String,
        @SerialName("itemRefs") val itemRefs: List<RefEntry>,
        @SerialName("tier") val tier: String,
        @SerialName("sourceContextDigest") val sourceContextDigest: String,
        @SerialName("signalProvenance") val signalProvenance: SignalProvenanceRecord?,
        @SerialName("createdAtEpochMs") val createdAtEpochMs: Long,
        @SerialName("expiresAtEpochMs") val expiresAtEpochMs: Long,
        @SerialName("scopeCandidates") val scopeCandidates: List<CandidateScopeRecord> = emptyList(),
        @SerialName("scopeCandidateDigest") val scopeCandidateDigest: String = "",
        @SerialName("categoryRefs") val categoryRefs: List<CategoryRefRecord> = emptyList(),
        /**
         * Issue #417 (spec 417 "Data and state"): the durable entry origin
         * (see [ExportEntryOrigin]); null = absent on a record written before
         * #417. Additive with a null default, so a pre-#417 record still
         * decodes; a #417 record read by an older build fails to decode on
         * the unknown key — the pre-existing "no session" fail-closed path
         * (the same known behavior as `categoryRefs`), accepted by the spec.
         * Written exactly once at save; no store operation mutates it.
         */
        @SerialName("entryOrigin") val entryOrigin: String? = null,
        /**
         * Issue #417: the invalidation tombstone of [invalidateIf] (the
         * session-store mirror of the pending store's `discarded`). Additive
         * with a false default; a committed tombstone reads as "no session"
         * even when its best-effort physical delete never lands.
         */
        @SerialName("invalidated") val invalidated: Boolean = false,
    ) {
        init {
            require(exportId.isNotEmpty())
            require(expiresAtEpochMs > createdAtEpochMs)
        }
    }

    @Serializable
    private data class CandidateScopeRecord(
        @SerialName("component") val component: String,
        @SerialName("profile") val profile: String,
    )

    /**
     * Issue #337 (v4, spec 337 D-1/D-5): the advertised category ref → stable
     * identity mapping. Additive with an empty default, so a record written
     * before v4 still decodes (its refs cannot resolve and fail closed with
     * `UNKNOWN_CATEGORY_REF`); a v4 record read by an older build fails to
     * decode on the unknown key, which is the pre-existing "no session"
     * fail-closed path. Identity is kind-discriminated, never a display name.
     */
    @Serializable
    private data class CategoryRefRecord(
        @SerialName("ref") val ref: String,
        @SerialName("kind") val kind: String,
        @SerialName("id") val id: String,
    ) {
        init {
            require(ref.isNotEmpty())
            require(id.isNotEmpty())
        }
    }

    @Serializable
    private data class RefEntry(val ref: String, val itemId: String) {
        init {
            require(ref.isNotEmpty())
            require(itemId.isNotEmpty())
        }
    }

    @Serializable
    private data class SignalProvenanceRecord(
        @SerialName("schemaVersion") val schemaVersion: String,
        @SerialName("contentDigest") val contentDigest: String,
    )

    private companion object {
        // Issue #331: the candidate scope fields made the record format v2.
        // The renamed file orphans any pre-331 v1 record (fail-closed: the
        // user re-exports), and a v1-schema record found at the new path is
        // still rejected by the version check below.
        const val SESSION_FILE_NAME = "organizer_personalization_export_session_v2.json"
        const val SCHEMA_VERSION = 2
    }
}

/**
 * Production randomness source of the `RandomIdAllocator` seam: crypto-strength
 * 128-bit identifiers, URL-safe (no padding). Child A of spec 204.
 */
class SecureRandomIdAllocator : RandomIdAllocator {

    private val random = SecureRandom()
    private val buffer = ByteArray(RANDOM_BYTES)

    override fun newId(): String {
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private companion object {
        const val RANDOM_BYTES = 16
    }
}
