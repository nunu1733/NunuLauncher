package app.lawnchair.organizer.application.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.LifecycleTransitions
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId

/**
 * SQLite-backed recovery store implementing the recovery-record lifecycle.
 * Each lifecycle transition is one transaction; durability boundary is
 * successful `endTransaction` under `synchronous=FULL`, followed by close/reopen
 * read-back validation for checkpoint/lifecycle records.
 *
 * Spec §§“Recovery record and lifecycle”, “Retention”; ADR-0003.
 *
 * Issue #14 Stage B step 3.
 */
class RecoveryStore(
    context: Context,
    private val clock: () -> Long,
) : RecoveryStorePort {

    private var faultPort: RecoveryStoreFaultPort = RecoveryStoreFaultPort.NOOP

    /**
     * Test-only constructor that injects a local fault port. Production code
     * uses the primary constructor and the NOOP default.
     */
    constructor(
        context: Context,
        clock: () -> Long,
        faultPort: RecoveryStoreFaultPort,
    ) : this(context, clock) {
        this.faultPort = faultPort
    }

    private val helper: RecoveryDbHelper = RecoveryDbHelper(context.applicationContext)
    private val versionGateFile: java.io.File =
        context.applicationContext.getDatabasePath(RecoveryDbSchema.FILE_NAME)

    fun probeVersion(): RecoveryDbVersionGate.VersionDecision = RecoveryDbVersionGate.probe(versionGateFile)

    override fun availability(): RecoveryStorePort.StoreAvailability = when (probeVersion()) {
        RecoveryDbVersionGate.VersionDecision.CreateNew,
        is RecoveryDbVersionGate.VersionDecision.OpenExisting,
        -> RecoveryStorePort.StoreAvailability.READY

        is RecoveryDbVersionGate.VersionDecision.Incompatible ->
            RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION

        is RecoveryDbVersionGate.VersionDecision.ReadFailed ->
            RecoveryStorePort.StoreAvailability.READ_FAILED
    }

    /**
     * Insert a new recovery record with lifecycle `CREATING`, then advance to
     * `READY`. The insert and the lifecycle transition are each one
     * transaction. Returns the persisted [StoredRecord] after read-back
     * validation.
     */
    override fun checkpoint(
        payload: RecoveryStorePort.CheckpointPayload,
    ): RecoveryStorePort.CheckpointResult {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) {
            return RecoveryStorePort.CheckpointResult.StoreUnavailable
        }
        val now = clock()
        val preManifestBytes = RecoveryRecordCodec.encodeManifest(payload.preManifest)
        val encoded = RecoveryRecordCodec.Encoded(
            pointId = payload.pointId,
            runId = payload.runId,
            createdAtMs = now,
            updatedAtMs = now,
            lifecycle = LifecycleState.CREATING,
            priorLifecycle = null,
            preManifest = preManifestBytes,
            preRevision = payload.preRevision,
            preDigest = payload.preDigest,
            intendedManifest = preManifestBytes,
            intendedDigest = payload.preDigest,
            applyActionDigest = payload.applyActionDigest,
            reviewedManifest = null,
            reviewedDigest = null,
            recoveryActionDigest = null,
            itemCount = payload.itemCount,
            resourceCount = payload.resourceCount,
            payloadChecksum = ByteArray(app.lawnchair.organizer.application.canonical.Digest.HASH_BYTES_PUBLIC),
        )
        val withChecksum = encoded.copy(payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(encoded))

        return run {
            try {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    when (val decision = RetentionPolicy.planCreate(listRetentionRecords(db), now)) {
                        RetentionPolicy.CreateDecision.Unavailable ->
                            return@run RecoveryStorePort.CheckpointResult.StoreUnavailable

                        is RetentionPolicy.CreateDecision.Allowed -> decision.toEvict.forEach { record ->
                            val action = RetentionPolicy.actionFor(record, now).let { planned ->
                                if (planned is RetentionPolicy.RetentionAction.Keep) {
                                    RetentionPolicy.RetentionAction.Tombstone(
                                        RetentionPolicy.ExpireReason.COUNT_RETENTION,
                                    )
                                } else {
                                    planned
                                }
                            }
                            writeTombstone(db, record, action, now)
                        }
                    }
                    purgeExpiredTombstones(db, now)
                    faultPort.beforeCommit(RecoveryStoreFaultPort.Phase.CREATING, payload.pointId)
                    insertRecordRow(db, withChecksum)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                faultPort.afterCommit(RecoveryStoreFaultPort.Phase.CREATING, payload.pointId)

                // Force a real close/reopen boundary before read-back validation.
                helper.close()
                val creatingReadback = readRecord(payload.pointId)
                    ?: return@run RecoveryStorePort.CheckpointResult.ValidateFailed
                if (creatingReadback.lifecycle != LifecycleState.CREATING ||
                    !validateRecord(creatingReadback.encoded)
                ) {
                    return@run RecoveryStorePort.CheckpointResult.ValidateFailed
                }

                if (!advance(payload.pointId, LifecycleState.READY)) {
                    return@run RecoveryStorePort.CheckpointResult.CreateFailed
                }
                val readyReadback = readRecord(payload.pointId)
                    ?: return@run RecoveryStorePort.CheckpointResult.ValidateFailed
                if (readyReadback.lifecycle != LifecycleState.READY ||
                    !validateRecord(readyReadback.encoded)
                ) {
                    return@run RecoveryStorePort.CheckpointResult.ValidateFailed
                }
                RecoveryStorePort.CheckpointResult.Ready(readyReadback)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                RecoveryStorePort.CheckpointResult.PointIdCollision
            } catch (e: android.database.SQLException) {
                RecoveryStorePort.CheckpointResult.CreateFailed
            } catch (e: RuntimeException) {
                RecoveryStorePort.CheckpointResult.CreateFailed
            }
        }
    }

    /**
     * Persist the complete intended post-state manifest/digest and mark the
     * record `APPLYING`. Spec §“Apply protocol” A5.
     */
    override fun markApplying(
        pointId: RecoveryPointId,
        intendedManifest: PersistenceManifest,
        intendedDigest: ByteArray,
        applyActionDigest: ByteArray,
        itemCount: Int,
        resourceCount: Int,
    ): Boolean {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return false
        val intendedBytes = RecoveryRecordCodec.encodeManifest(intendedManifest)
        return updateRecord(pointId, RecoveryStoreFaultPort.Phase.APPLYING) { current ->
            if (!LifecycleTransitions.isLegal(current.lifecycle, LifecycleState.APPLYING)) return@updateRecord null
            current.copy(
                updatedAtMs = clock(),
                lifecycle = LifecycleState.APPLYING,
                priorLifecycle = current.lifecycle,
                intendedManifest = intendedBytes,
                intendedDigest = intendedDigest,
                applyActionDigest = applyActionDigest,
                itemCount = itemCount,
                resourceCount = resourceCount,
            )
        }
    }

    /**
     * Advance the lifecycle of [pointId] to [next] if the transition is legal.
     * Returns true on success; false if the row is missing, the transition is
     * illegal, or the update failed.
     */
    override fun advance(pointId: RecoveryPointId, next: LifecycleState): Boolean {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return false
        return updateRecord(pointId, next.toFaultPhase()) { current ->
            if (!LifecycleTransitions.isLegal(current.lifecycle, next)) return@updateRecord null
            current.copy(
                updatedAtMs = clock(),
                lifecycle = next,
                priorLifecycle = current.lifecycle,
            )
        }
    }

    /**
     * Persist the reviewed-current-state manifest/digest and complete recovery
     * action-set digest, mark `RESTORING`. Spec §“Recovery protocol” step 6.
     */
    override fun markRestoring(
        pointId: RecoveryPointId,
        reviewedManifest: PersistenceManifest,
        reviewedDigest: ByteArray,
        recoveryActionDigest: ByteArray,
    ): Boolean {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return false
        val reviewedBytes = RecoveryRecordCodec.encodeManifest(reviewedManifest)
        return updateRecord(pointId, RecoveryStoreFaultPort.Phase.RESTORING) { current ->
            if (current.lifecycle != LifecycleState.RESTORING &&
                !LifecycleTransitions.isLegal(current.lifecycle, LifecycleState.RESTORING)
            ) {
                return@updateRecord null
            }
            current.copy(
                updatedAtMs = clock(),
                lifecycle = LifecycleState.RESTORING,
                priorLifecycle = if (current.lifecycle == LifecycleState.RESTORING) {
                    current.priorLifecycle
                } else {
                    current.lifecycle
                },
                reviewedManifest = reviewedBytes,
                reviewedDigest = reviewedDigest,
                recoveryActionDigest = recoveryActionDigest,
            )
        }
    }

    /**
     * Apply retention: evict records per [RetentionPolicy] and write tombstones.
     * Caller must wrap this in a single recovery DB transaction.
     */
    override fun runRetention(nowMillis: Long): RecoveryStorePort.RetentionOutcome {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) {
            return RecoveryStorePort.RetentionOutcome.StoreUnavailable
        }
        val toEvict = listRetentionRecords(includeFinal = true).filter {
            RetentionPolicy.actionFor(it, nowMillis) !is RetentionPolicy.RetentionAction.Keep
        }
        return applyEvictions(toEvict, nowMillis)
    }

    private fun applyEvictions(
        toEvict: List<RetentionPolicy.RetentionRecord>,
        nowMillis: Long,
    ): RecoveryStorePort.RetentionOutcome {
        val firstPointId = toEvict.firstOrNull()?.pointId
        val db = helper.writableDatabase
        var txOk = false
        var outcome: RecoveryStorePort.RetentionOutcome = RecoveryStorePort.RetentionOutcome.Failed
        try {
            db.beginTransaction()
            try {
                purgeExpiredTombstones(db, nowMillis)
                if (firstPointId != null) {
                    faultPort.beforeCommit(RecoveryStoreFaultPort.Phase.TOMBSTONE, firstPointId)
                }
                for (record in toEvict) {
                    val action = RetentionPolicy.actionFor(record, nowMillis)
                    when (action) {
                        is RetentionPolicy.RetentionAction.Expire,
                        is RetentionPolicy.RetentionAction.Tombstone,
                        -> writeTombstone(db, record, action, nowMillis)

                        else -> Unit
                    }
                }
                db.setTransactionSuccessful()
                txOk = true
            } finally {
                db.endTransaction()
            }
            outcome = if (!txOk) {
                RecoveryStorePort.RetentionOutcome.Failed
            } else {
                if (firstPointId != null) {
                    faultPort.afterCommit(RecoveryStoreFaultPort.Phase.TOMBSTONE, firstPointId)
                }
                RecoveryStorePort.RetentionOutcome.Applied
            }
        } catch (_: RuntimeException) {
            outcome = RecoveryStorePort.RetentionOutcome.Failed
        }
        return outcome
    }

    override fun readRecord(pointId: RecoveryPointId): StoredRecord? {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return null
        val db = helper.readableDatabase
        val cursor = db.query(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            null,
            "point_id = ?",
            arrayOf(pointId.value),
            null,
            null,
            null,
        )
        return cursor.use {
            if (!it.moveToFirst()) null else cursorToEncoded(it).let(::storedFromEncoded)
        }
    }

    override fun listNonFinalRecords(): List<StoredRecord> {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return emptyList()
        val db = helper.readableDatabase
        val cursor = db.query(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            null,
            null,
            null,
            null,
            null,
            "created_at_ms ASC",
        )
        val out = ArrayList<StoredRecord>()
        cursor.use {
            while (it.moveToNext()) {
                val encoded = cursorToEncoded(it)
                if (!LifecycleTransitions.isFinal(encoded.lifecycle)) {
                    out += storedFromEncoded(encoded)
                }
            }
        }
        return out
    }

    override fun pruneUnused(pointId: RecoveryPointId): Boolean = try {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return false
        val db = helper.writableDatabase
        var committed = false
        db.beginTransaction()
        try {
            val record = readEncoded(db, pointId) ?: return false
            if (record.lifecycle != LifecycleState.READY) return false
            faultPort.beforeCommit(RecoveryStoreFaultPort.Phase.TOMBSTONE, pointId)
            writeTombstone(
                db,
                RetentionPolicy.RetentionRecord(
                    pointId,
                    record.lifecycle,
                    record.createdAtMs,
                    record.updatedAtMs,
                ),
                RetentionPolicy.RetentionAction.Tombstone(
                    RetentionPolicy.ExpireReason.PRUNE_UNUSED_READY,
                ),
                clock(),
            )
            db.setTransactionSuccessful()
            committed = true
        } finally {
            db.endTransaction()
        }
        if (!committed) return false
        try {
            faultPort.afterCommit(RecoveryStoreFaultPort.Phase.TOMBSTONE, pointId)
        } catch (_: RuntimeException) {
            return false
        }
        helper.close()
        val tombstoneReadback = readTombstone(pointId)
        tombstoneReadback != null &&
            tombstoneReadback.reason == RecoveryStorePort.TombstoneReason.PRUNED_UNUSED
    } catch (_: RuntimeException) {
        false
    }

    override fun readTombstone(pointId: RecoveryPointId): RecoveryStorePort.Tombstone? {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return null
        val db = helper.writableDatabase
        val now = clock()
        db.beginTransaction()
        return try {
            purgeExpiredTombstones(db, now)
            val cursor = db.query(
                RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
                arrayOf("reason", "expires_at_ms"),
                "point_id = ?",
                arrayOf(pointId.value),
                null,
                null,
                null,
            )
            val result = cursor.use {
                if (!it.moveToFirst()) {
                    null
                } else {
                    RecoveryStorePort.Tombstone(
                        pointId,
                        tombstoneReasonFromCanonicalInt(it.getInt(0)),
                        it.getLong(1),
                    )
                }
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun listRetentionRecords(
        includeFinal: Boolean = false,
    ): List<RetentionPolicy.RetentionRecord> {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return emptyList()
        return listRetentionRecords(helper.readableDatabase).filter {
            includeFinal || !LifecycleTransitions.isFinal(it.lifecycle)
        }
    }

    private fun listRetentionRecords(db: SQLiteDatabase): List<RetentionPolicy.RetentionRecord> {
        val cursor = db.query(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            arrayOf("point_id", "lifecycle", "created_at_ms", "updated_at_ms"),
            null,
            null,
            null,
            null,
            "created_at_ms ASC",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        RetentionPolicy.RetentionRecord(
                            pointId = RecoveryPointId(it.getString(0)),
                            lifecycle = LifecycleState.fromCanonicalInt(it.getInt(1)),
                            createdAtMillis = it.getLong(2),
                            updatedAtMillis = it.getLong(3),
                        ),
                    )
                }
            }
        }
    }

    private fun updateRecord(
        pointId: RecoveryPointId,
        phase: RecoveryStoreFaultPort.Phase,
        transform: (RecoveryRecordCodec.Encoded) -> RecoveryRecordCodec.Encoded?,
    ): Boolean {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return false
        val db = helper.writableDatabase
        var committed = false
        db.beginTransaction()
        try {
            val current = readEncoded(db, pointId) ?: return false
            val transformed = transform(current) ?: return false
            faultPort.beforeCommit(phase, pointId)
            val updated = transformed.copy(
                payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(transformed),
            )
            val count = writeMutableRecordColumns(db, updated)
            if (count != 1) return false
            db.setTransactionSuccessful()
            committed = true
        } catch (_: RuntimeException) {
            return false
        } finally {
            db.endTransaction()
        }
        if (!committed) return false
        try {
            faultPort.afterCommit(phase, pointId)
        } catch (_: RuntimeException) {
            return false
        }
        helper.close()
        val readback = try {
            readRecord(pointId)
        } catch (_: RuntimeException) {
            null
        }
        return readback != null && validateRecord(readback.encoded)
    }

    private fun writeMutableRecordColumns(
        db: SQLiteDatabase,
        encoded: RecoveryRecordCodec.Encoded,
    ): Int {
        val cv = ContentValues()
        cv.put("updated_at_ms", encoded.updatedAtMs)
        cv.put("lifecycle", encoded.lifecycle.canonicalInt)
        encoded.priorLifecycle?.let { cv.put("prior_lifecycle", it.canonicalInt) }
            ?: cv.putNull("prior_lifecycle")
        cv.put("intended_manifest", encoded.intendedManifest)
        cv.put("intended_digest", encoded.intendedDigest)
        cv.put("apply_action_digest", encoded.applyActionDigest)
        encoded.reviewedManifest?.let { cv.put("reviewed_manifest", it) }
            ?: cv.putNull("reviewed_manifest")
        encoded.reviewedDigest?.let { cv.put("reviewed_digest", it) }
            ?: cv.putNull("reviewed_digest")
        encoded.recoveryActionDigest?.let { cv.put("recovery_action_digest", it) }
            ?: cv.putNull("recovery_action_digest")
        cv.put("item_count", encoded.itemCount)
        cv.put("resource_count", encoded.resourceCount)
        cv.put("payload_checksum", encoded.payloadChecksum)
        return db.update(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            cv,
            "point_id = ?",
            arrayOf(encoded.pointId.value),
        )
    }

    private fun readEncoded(db: SQLiteDatabase, pointId: RecoveryPointId): RecoveryRecordCodec.Encoded? {
        val cursor = db.query(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            null,
            "point_id = ?",
            arrayOf(pointId.value),
            null,
            null,
            null,
        )
        return cursor.use {
            if (!it.moveToFirst()) null else cursorToEncoded(it)
        }
    }

    private fun insertRecordRow(db: SQLiteDatabase, encoded: RecoveryRecordCodec.Encoded) {
        val cv = ContentValues()
        cv.put("point_id", encoded.pointId.value)
        cv.put("format_version", encoded.formatVersion)
        cv.put("run_id", encoded.runId.value)
        cv.put("created_at_ms", encoded.createdAtMs)
        cv.put("updated_at_ms", encoded.updatedAtMs)
        cv.put("lifecycle", encoded.lifecycle.canonicalInt)
        encoded.priorLifecycle?.let { cv.put("prior_lifecycle", it.canonicalInt) }
            ?: cv.putNull("prior_lifecycle")
        cv.put("pre_manifest", encoded.preManifest)
        cv.put("pre_revision", encoded.preRevision.value)
        cv.put("pre_digest", encoded.preDigest)
        cv.put("intended_manifest", encoded.intendedManifest)
        cv.put("intended_digest", encoded.intendedDigest)
        cv.put("apply_action_digest", encoded.applyActionDigest)
        cv.putNull("reviewed_manifest")
        cv.putNull("reviewed_digest")
        cv.putNull("recovery_action_digest")
        cv.put("item_count", encoded.itemCount)
        cv.put("resource_count", encoded.resourceCount)
        cv.put("payload_checksum", encoded.payloadChecksum)
        db.insertOrThrow(RecoveryDbSchema.TABLE_RECOVERY_POINTS, null, cv)
    }

    private fun cursorToEncoded(cursor: android.database.Cursor): RecoveryRecordCodec.Encoded {
        val priorLifecycleInt = if (cursor.isNull(cursor.getColumnIndexOrThrow("prior_lifecycle"))) {
            null
        } else {
            cursor.getInt(cursor.getColumnIndexOrThrow("prior_lifecycle"))
        }
        val reviewedManifest = cursor.getBlobOrNull("reviewed_manifest")
        val reviewedDigest = cursor.getBlobOrNull("reviewed_digest")
        val recoveryActionDigest = cursor.getBlobOrNull("recovery_action_digest")
        return RecoveryRecordCodec.Encoded(
            pointId = RecoveryPointId(cursor.getString(cursor.getColumnIndexOrThrow("point_id"))),
            runId = RunId(cursor.getString(cursor.getColumnIndexOrThrow("run_id"))),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
            lifecycle = LifecycleState.fromCanonicalInt(cursor.getInt(cursor.getColumnIndexOrThrow("lifecycle"))),
            priorLifecycle = priorLifecycleInt?.let(LifecycleState::fromCanonicalInt),
            preManifest = cursor.getBlob(cursor.getColumnIndexOrThrow("pre_manifest")),
            preRevision = RevisionId(cursor.getString(cursor.getColumnIndexOrThrow("pre_revision"))),
            preDigest = cursor.getBlob(cursor.getColumnIndexOrThrow("pre_digest")),
            intendedManifest = cursor.getBlob(cursor.getColumnIndexOrThrow("intended_manifest")),
            intendedDigest = cursor.getBlob(cursor.getColumnIndexOrThrow("intended_digest")),
            applyActionDigest = cursor.getBlob(cursor.getColumnIndexOrThrow("apply_action_digest")),
            reviewedManifest = reviewedManifest,
            reviewedDigest = reviewedDigest,
            recoveryActionDigest = recoveryActionDigest,
            itemCount = cursor.getInt(cursor.getColumnIndexOrThrow("item_count")),
            resourceCount = cursor.getInt(cursor.getColumnIndexOrThrow("resource_count")),
            payloadChecksum = cursor.getBlob(cursor.getColumnIndexOrThrow("payload_checksum")),
            formatVersion = cursor.getInt(cursor.getColumnIndexOrThrow("format_version")),
        )
    }

    private fun storedFromEncoded(encoded: RecoveryRecordCodec.Encoded): StoredRecord = StoredRecord(encoded)

    private fun validateRecord(encoded: RecoveryRecordCodec.Encoded): Boolean = try {
        RecoveryRecordCodec.decode(encoded)
        true
    } catch (_: Exception) {
        false
    }

    private fun writeTombstone(
        db: SQLiteDatabase,
        record: RetentionPolicy.RetentionRecord,
        action: RetentionPolicy.RetentionAction,
        nowMillis: Long,
    ) {
        val reason = when (record.lifecycle) {
            LifecycleState.CORRUPT -> RecoveryStorePort.TombstoneReason.CORRUPT

            LifecycleState.INCOMPATIBLE -> RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION

            LifecycleState.RESTORED -> RecoveryStorePort.TombstoneReason.ALREADY_RESTORED

            LifecycleState.EXPIRED -> RecoveryStorePort.TombstoneReason.EXPIRED

            else -> when (action) {
                is RetentionPolicy.RetentionAction.Tombstone -> when (action.reason) {
                    RetentionPolicy.ExpireReason.PRUNE_UNUSED_READY ->
                        RecoveryStorePort.TombstoneReason.PRUNED_UNUSED

                    else -> RecoveryStorePort.TombstoneReason.EXPIRED
                }

                is RetentionPolicy.RetentionAction.Expire -> RecoveryStorePort.TombstoneReason.EXPIRED

                else -> return
            }
        }
        val cv = ContentValues()
        cv.put("point_id", record.pointId.value)
        cv.put("reason", reason.canonicalInt)
        cv.put("format_version", RecoveryDbSchema.FORMAT_VERSION)
        cv.put("expires_at_ms", nowMillis + RetentionPolicy.TOMBSTONE_RETENTION_MILLIS)
        db.insertWithOnConflict(
            RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        db.delete(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            "point_id = ?",
            arrayOf(record.pointId.value),
        )
    }

    private fun purgeExpiredTombstones(db: SQLiteDatabase, nowMillis: Long) {
        db.delete(
            RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
            "expires_at_ms <= ?",
            arrayOf(nowMillis.toString()),
        )
    }

    private fun LifecycleState.toFaultPhase(): RecoveryStoreFaultPort.Phase = when (this) {
        LifecycleState.CREATING -> RecoveryStoreFaultPort.Phase.CREATING
        LifecycleState.READY -> RecoveryStoreFaultPort.Phase.READY
        LifecycleState.APPLYING -> RecoveryStoreFaultPort.Phase.APPLYING
        LifecycleState.COMMITTED_UNVERIFIED -> RecoveryStoreFaultPort.Phase.COMMITTED_UNVERIFIED
        LifecycleState.VERIFIED -> RecoveryStoreFaultPort.Phase.VERIFIED
        LifecycleState.RESTORING -> RecoveryStoreFaultPort.Phase.RESTORING
        LifecycleState.RESTORED -> RecoveryStoreFaultPort.Phase.RESTORED
        LifecycleState.EXPIRED -> RecoveryStoreFaultPort.Phase.EXPIRED
        LifecycleState.CORRUPT -> RecoveryStoreFaultPort.Phase.CORRUPT
        LifecycleState.INCOMPATIBLE -> RecoveryStoreFaultPort.Phase.INCOMPATIBLE
    }

    private val RecoveryStorePort.TombstoneReason.canonicalInt: Int
        get() = when (this) {
            RecoveryStorePort.TombstoneReason.CORRUPT -> 1
            RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION -> 2
            RecoveryStorePort.TombstoneReason.ALREADY_RESTORED -> 3
            RecoveryStorePort.TombstoneReason.EXPIRED -> 4
            RecoveryStorePort.TombstoneReason.PRUNED_UNUSED -> 5
        }

    private fun tombstoneReasonFromCanonicalInt(value: Int): RecoveryStorePort.TombstoneReason = when (value) {
        1 -> RecoveryStorePort.TombstoneReason.CORRUPT
        2 -> RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION
        3 -> RecoveryStorePort.TombstoneReason.ALREADY_RESTORED
        4 -> RecoveryStorePort.TombstoneReason.EXPIRED
        5 -> RecoveryStorePort.TombstoneReason.PRUNED_UNUSED
        else -> throw IllegalArgumentException("Unknown tombstone reason: $value")
    }

    private fun android.database.Cursor.getBlobOrNull(column: String): ByteArray? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getBlob(idx)
    }

    data class StoredRecord(val encoded: RecoveryRecordCodec.Encoded) : RecoveryStorePort.StoredRecord {
        override val pointId: RecoveryPointId get() = encoded.pointId
        override val runId: RunId get() = encoded.runId
        override val lifecycle: LifecycleState get() = encoded.lifecycle
        override val priorLifecycle: LifecycleState? get() = encoded.priorLifecycle
        override val createdAtMs: Long get() = encoded.createdAtMs
        override val updatedAtMs: Long get() = encoded.updatedAtMs
        override val preManifest: PersistenceManifest
            get() = RecoveryRecordCodec.decodeManifest(encoded.preManifest)
        override val preRevision: RevisionId get() = encoded.preRevision
        override val preDigest: ByteArray get() = encoded.preDigest.copyOf()
        override val intendedManifest: PersistenceManifest
            get() = RecoveryRecordCodec.decodeManifest(encoded.intendedManifest)
        override val intendedDigest: ByteArray get() = encoded.intendedDigest.copyOf()
        override val applyActionDigest: ByteArray get() = encoded.applyActionDigest.copyOf()
        override val reviewedManifest: PersistenceManifest?
            get() = encoded.reviewedManifest?.let(RecoveryRecordCodec::decodeManifest)
        override val reviewedDigest: ByteArray? get() = encoded.reviewedDigest?.copyOf()
        override val recoveryActionDigest: ByteArray? get() = encoded.recoveryActionDigest?.copyOf()
        override val itemCount: Int get() = encoded.itemCount
        override val resourceCount: Int get() = encoded.resourceCount
        override val checksumValid: Boolean get() = RecoveryRecordCodec.verifyPayloadChecksum(encoded)
        override val formatVersion: Int get() = encoded.formatVersion
    }
}
