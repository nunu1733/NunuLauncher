package app.lawnchair.organizer.integration

import android.content.Context
import androidx.core.util.AtomicFile
import app.lawnchair.organizer.personalization.DiscardIfResult
import app.lawnchair.organizer.personalization.DurableGroupSemantic
import app.lawnchair.organizer.personalization.DurablePendingIntent
import app.lawnchair.organizer.personalization.DurableRefDecision
import app.lawnchair.organizer.personalization.DurableRefEntry
import app.lawnchair.organizer.personalization.ExportRegionKind
import app.lawnchair.organizer.personalization.Importance
import app.lawnchair.organizer.personalization.PendingImportEntryKind
import app.lawnchair.organizer.personalization.PendingImportedIntentStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #374: the Android/file-backed implementation of the durable pending
 * imported intent store (spec 374). App-private (`noBackupFilesDir`, outside
 * any backup — the same class as the export session store),
 * Launcher-favorites-DB-independent, single-active: the store holds at most one
 * record, so a successful import overwrites the prior one. A missing file reads
 * as "no proposal"; a record that exists but is unreadable (corruption, unknown
 * schema, read I/O failure) is fail-closed invalidated AND best-effort
 * physically cleaned, so it can never reappear on a later read (DI-AC-05).
 *
 * Purity boundary: this class is the ONLY Android/storage boundary of the
 * durable pending intent contract; the pure package holds the seam interface
 * and the record model. The wire record carries exactly the durable fields —
 * no rationale, no confidence, and no ref-to-internal-ID mapping (the export
 * session is the master; spec 374 DI-AC-10).
 */
class AndroidPendingImportedIntentStore : PendingImportedIntentStore {

    private val json = Json
    private val lock = Any()
    private val atomicFile: AtomicFile

    /** Production constructor: app-private, backup-excluded storage. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, PENDING_FILE_NAME))

    /** Test constructor: explicit file target, same semantics. */
    internal constructor(baseFile: File) {
        atomicFile = AtomicFile(baseFile)
    }

    override fun save(proposal: DurablePendingIntent): Boolean = synchronized(lock) {
        writeRecord(pendingRecordOf(proposal))
    }

    override fun load(): DurablePendingIntent? = synchronized(lock) { readRecord() }

    override fun discard(): Boolean = synchronized(lock) {
        val current = readRecord()
        if (current == null) {
            // Absent or corrupt: a corrupt record cannot be shown, so the
            // discard is vacuously successful; still clean any residue.
            atomicFile.delete()
            return@synchronized true
        }
        // Tombstone commit first: only a successful atomic rewrite counts as
        // a discard; the physical delete afterwards is best-effort (the mark
        // itself blocks re-display even if the delete fails or the process
        // dies right after the commit).
        val committed = writeRecord(pendingRecordOf(current).copy(discarded = true))
        if (!committed) return@synchronized false
        atomicFile.delete()
        true
    }

    override fun delete() {
        synchronized(lock) { atomicFile.delete() }
    }

    override fun discardIf(expected: DurablePendingIntent): DiscardIfResult = synchronized(lock) {
        // Issue #375 conditional invalidation commit: read-compare-tombstone,
        // atomic against other store access. Only the exact expected record is
        // tombstoned; a replaced/absent record is `NoMatch` (nothing stale can
        // resurface), and a failed atomic rewrite is `WriteFailed` — the
        // record stays valid and the invalidation is retryable.
        val current = readRecord() ?: return@synchronized DiscardIfResult.NoMatch
        if (current != expected) return@synchronized DiscardIfResult.NoMatch
        val committed = writeRecord(pendingRecordOf(current).copy(discarded = true))
        if (!committed) return@synchronized DiscardIfResult.WriteFailed
        atomicFile.delete()
        DiscardIfResult.Committed
    }

    override fun deleteIf(proposal: DurablePendingIntent): Boolean = synchronized(lock) {
        // Compare-and-delete: only a record that still reads back EXACTLY
        // [proposal] is removed; a newer/different record (or absence) is left
        // untouched. A corrupt residue is cleaned by readRecord itself and
        // reads as absent (no match, no delete here).
        val current = readRecord() ?: return@synchronized false
        if (current != proposal) return@synchronized false
        atomicFile.delete()
        true
    }

    private fun writeRecord(record: PendingRecord): Boolean {
        val bytes = json.encodeToString(PendingRecord.serializer(), record).encodeToByteArray()
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

    /**
     * Issue #374 (review finding 3, DI-AC-05 fail-closed): the read
     * distinguishes TRUE ABSENCE from EXISTS-BUT-UNREADABLE. A missing file is
     * the only silent "no proposal"; once the file exists, a read I/O failure,
     * a corrupt/truncated body, an unknown schema, or a record that fails its
     * structural validation is an INVALID record — best-effort physically
     * deleted here (so a later read cannot resurrect it) and returned as null.
     * Never throws.
     */
    private fun readRecord(): DurablePendingIntent? {
        val stream = try {
            atomicFile.openRead()
        } catch (e: FileNotFoundException) {
            // True absence only when the path really is not there; an EXISTING
            // file that cannot be opened (e.g. a directory in its place, or a
            // permission failure) is unreadable residue, cleaned fail-closed.
            if (!atomicFile.baseFile.exists()) return null
            cleanResidue()
            return null
        }
        val bytes = try {
            stream.readBytes()
        } catch (e: IOException) {
            runCatching { stream.close() }
            cleanResidue()
            return null
        }
        runCatching { stream.close() }
        val record = runCatching {
            json.decodeFromString(PendingRecord.serializer(), bytes.decodeToString())
        }.getOrNull()
        if (record == null || record.schemaVersion != SCHEMA_VERSION) {
            // Corrupt/truncated body or unknown schema: invalid, cleaned.
            cleanResidue()
            return null
        }
        val durable = runCatching { record.toDurablePendingIntent() }.getOrNull()
        if (durable == null) {
            // Decodable but structurally invalid: invalid, cleaned.
            cleanResidue()
            return null
        }
        return durable
    }

    /** Best-effort physical cleanup of an invalid record's residue. */
    private fun cleanResidue() {
        runCatching { atomicFile.delete() }
    }

    private fun pendingRecordOf(proposal: DurablePendingIntent): PendingRecord = PendingRecord(
        schemaVersion = SCHEMA_VERSION,
        exportId = proposal.exportId,
        intentIdentitySchemaVersion = proposal.intentIdentitySchemaVersion,
        intentIdentityDigest = proposal.intentIdentityDigest,
        decisions = proposal.decisions.map(::decisionRecord),
        minimizeMovement = proposal.minimizeMovement,
        expiresAtEpochMs = proposal.expiresAtEpochMs,
        entryKind = proposal.entryKind.name,
        discarded = proposal.discarded,
        createdAtEpochMs = proposal.createdAtEpochMs,
    )

    private fun decisionRecord(entry: DurableRefEntry): DecisionRecord {
        val authored = entry.decision as? DurableRefDecision.Authored
        return DecisionRecord(
            ref = entry.ref,
            kind = when (entry.decision) {
                is DurableRefDecision.Authored -> DECISION_KIND_AUTHORED
                DurableRefDecision.UnresolvedAuthored -> DECISION_KIND_UNRESOLVED_AUTHORED
                DurableRefDecision.UnresolvedByOmission -> DECISION_KIND_UNRESOLVED_BY_OMISSION
            },
            importance = authored?.importance?.name,
            desiredGroupRefs = authored?.desiredGroupRefs,
            groupSemantic = authored?.groupSemantic?.let { semantic ->
                when (semantic) {
                    is DurableGroupSemantic.ExistingCategory ->
                        GroupSemanticRecord(kind = GROUP_KIND_CATEGORY_REF, categoryRef = semantic.categoryRef)

                    is DurableGroupSemantic.ProposedGroup ->
                        GroupSemanticRecord(kind = GROUP_KIND_PROPOSAL_LABEL, proposalLabel = semantic.proposalLabel)
                }
            },
            pageAffinity = authored?.pageAffinity,
            regionAffinity = authored?.regionAffinity?.name,
            preserve = authored?.preserve,
        )
    }

    private fun PendingRecord.toDurablePendingIntent() = DurablePendingIntent(
        exportId = exportId,
        intentIdentitySchemaVersion = intentIdentitySchemaVersion,
        intentIdentityDigest = intentIdentityDigest,
        decisions = decisions.map { it.toDurableRefEntry() },
        minimizeMovement = minimizeMovement,
        expiresAtEpochMs = expiresAtEpochMs,
        entryKind = PendingImportEntryKind.valueOf(entryKind),
        discarded = discarded,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun DecisionRecord.toDurableRefEntry() = DurableRefEntry(
        ref = ref,
        decision = when (kind) {
            DECISION_KIND_AUTHORED -> DurableRefDecision.Authored(
                importance = importance?.let { Importance.valueOf(it) },
                desiredGroupRefs = desiredGroupRefs,
                groupSemantic = groupSemantic?.let { semantic ->
                    when (semantic.kind) {
                        GROUP_KIND_CATEGORY_REF ->
                            DurableGroupSemantic.ExistingCategory(semantic.categoryRef ?: error("categoryRef kind without a ref"))

                        GROUP_KIND_PROPOSAL_LABEL ->
                            DurableGroupSemantic.ProposedGroup(semantic.proposalLabel ?: error("proposalLabel kind without a label"))

                        else -> error("unknown group semantic kind")
                    }
                },
                pageAffinity = pageAffinity,
                regionAffinity = regionAffinity?.let { ExportRegionKind.valueOf(it) },
                preserve = preserve,
            )

            DECISION_KIND_UNRESOLVED_AUTHORED -> DurableRefDecision.UnresolvedAuthored

            DECISION_KIND_UNRESOLVED_BY_OMISSION -> DurableRefDecision.UnresolvedByOmission

            else -> error("unknown decision kind")
        },
    )

    @Serializable
    private data class PendingRecord(
        @SerialName("schemaVersion") val schemaVersion: Int,
        @SerialName("exportId") val exportId: String,
        @SerialName("intentIdentitySchemaVersion") val intentIdentitySchemaVersion: String,
        @SerialName("intentIdentityDigest") val intentIdentityDigest: String,
        @SerialName("decisions") val decisions: List<DecisionRecord>,
        @SerialName("minimizeMovement") val minimizeMovement: Boolean,
        @SerialName("expiresAtEpochMs") val expiresAtEpochMs: Long,
        @SerialName("entryKind") val entryKind: String,
        @SerialName("discarded") val discarded: Boolean,
        @SerialName("createdAtEpochMs") val createdAtEpochMs: Long,
    ) {
        init {
            require(exportId.isNotEmpty())
            require(intentIdentitySchemaVersion.isNotEmpty())
            require(intentIdentityDigest.isNotEmpty())
        }
    }

    /**
     * Issue #374: one durable decision, kind-discriminated like the session
     * store's category entries. An unknown kind is a corrupted record, not a
     * decision: the surrounding runCatching degrades it to "no proposal"
     * (fail-closed) instead of guessing. Unresolved kinds carry no fields.
     */
    @Serializable
    private data class DecisionRecord(
        @SerialName("ref") val ref: String,
        @SerialName("kind") val kind: String,
        @SerialName("importance") val importance: String? = null,
        @SerialName("desiredGroupRefs") val desiredGroupRefs: List<String>? = null,
        @SerialName("groupSemantic") val groupSemantic: GroupSemanticRecord? = null,
        @SerialName("pageAffinity") val pageAffinity: Int? = null,
        @SerialName("regionAffinity") val regionAffinity: String? = null,
        @SerialName("preserve") val preserve: Boolean? = null,
    ) {
        init {
            require(ref.isNotEmpty())
            require(
                kind == DECISION_KIND_AUTHORED ||
                    kind == DECISION_KIND_UNRESOLVED_AUTHORED ||
                    kind == DECISION_KIND_UNRESOLVED_BY_OMISSION,
            )
            if (kind != DECISION_KIND_AUTHORED) {
                require(
                    importance == null &&
                        desiredGroupRefs == null &&
                        groupSemantic == null &&
                        pageAffinity == null &&
                        regionAffinity == null &&
                        preserve == null,
                )
            }
            desiredGroupRefs?.let { require(it.isNotEmpty()) }
        }
    }

    /**
     * Issue #374: the exactly-one-of group semantic of one authored decision.
     * The kind must agree with the set field; anything else is corruption.
     */
    @Serializable
    private data class GroupSemanticRecord(
        @SerialName("kind") val kind: String,
        @SerialName("categoryRef") val categoryRef: String? = null,
        @SerialName("proposalLabel") val proposalLabel: String? = null,
    ) {
        init {
            require((categoryRef == null) != (proposalLabel == null))
            require(categoryRef == null || kind == GROUP_KIND_CATEGORY_REF)
            require(proposalLabel == null || kind == GROUP_KIND_PROPOSAL_LABEL)
            require(categoryRef == null || categoryRef.isNotEmpty())
            require(proposalLabel == null || proposalLabel.isNotEmpty())
        }
    }

    private companion object {
        const val PENDING_FILE_NAME = "organizer_pending_imported_intent_v1.json"
        const val SCHEMA_VERSION = 1
        const val DECISION_KIND_AUTHORED = "AUTHORED"
        const val DECISION_KIND_UNRESOLVED_AUTHORED = "UNRESOLVED_AUTHORED"
        const val DECISION_KIND_UNRESOLVED_BY_OMISSION = "UNRESOLVED_BY_OMISSION"
        const val GROUP_KIND_CATEGORY_REF = "CATEGORY_REF"
        const val GROUP_KIND_PROPOSAL_LABEL = "PROPOSAL_LABEL"
    }
}
