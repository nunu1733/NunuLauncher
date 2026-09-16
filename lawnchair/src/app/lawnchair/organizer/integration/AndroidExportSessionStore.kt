package app.lawnchair.organizer.integration

import android.content.Context
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.personalization.CandidateScopeIdentity
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.ExportSessionStore
import app.lawnchair.organizer.personalization.PrivacyTier
import app.lawnchair.organizer.personalization.RandomIdAllocator
import app.lawnchair.organizer.personalization.SignalProvenance
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
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
        )
        val bytes = json.encodeToString(SessionRecord.serializer(), record).encodeToByteArray()
        val out = try {
            atomicFile.startWrite()
        } catch (e: IOException) {
            return@synchronized false
        }
        try {
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

    private fun readSession(): ExportSession? {
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
        return runCatching {
            ExportSession(
                exportId = record.exportId,
                itemRefs = record.itemRefs.associate { it.ref to ItemId(it.itemId) },
                tier = PrivacyTier.valueOf(record.tier),
                sourceContextDigest = record.sourceContextDigest,
                signalProvenance = record.signalProvenance?.let {
                    SignalProvenance(schemaVersion = it.schemaVersion, contentDigest = it.contentDigest)
                },
                createdAtEpochMs = record.createdAtEpochMs,
                expiresAtEpochMs = record.expiresAtEpochMs,
                scopeCandidates = record.scopeCandidates.map {
                    CandidateTarget.AppKey(ComponentKey(it.component), ProfileId(it.profile))
                },
                scopeCandidateDigest = record.scopeCandidateDigest.ifEmpty { CandidateScopeIdentity.EMPTY_DIGEST },
            )
        }.getOrNull()
    }

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
        const val SESSION_FILE_NAME = "organizer_personalization_export_session_v1.json"
        const val SCHEMA_VERSION = 1
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
