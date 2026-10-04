package app.lawnchair.organizer.application.store

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.lifecycle.LifecycleTransitions
import app.lawnchair.organizer.application.lifecycle.RetentionPolicy
import app.lawnchair.organizer.application.protocol.ReconciliationCandidate
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationIssuer
import app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationPort
import app.lawnchair.organizer.application.protocol.RecoveryStoreReconciliationSession
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.planning.RevisionId

/**
 * SQLite-backed recovery store implementing the recovery-record lifecycle.
 * Each lifecycle transition is one transaction; durability boundary is
 * successful `endTransaction` under `synchronous=FULL`, followed by close/reopen
 * read-back validation for checkpoint/lifecycle records.
 *
 * Physical schema 3 (Issue #174, ADR-0009): manifest bytes live in the chunked
 * side table ([RecoveryManifestChunks]); every read uses the bounded
 * [RecoveryDbSchema.RECORD_COLUMNS] projection plus per-point chunk assembly,
 * so no physical row can exceed Android SQLite's 2 MB `CursorWindow`. Logical
 * records stay format 2 ([RecoveryRecordCodec.RECORD_FORMAT_VERSION]) and
 * payload-checksum bytes are unchanged, including across the server-side
 * schema-2 → schema-3 migration.
 *
 * Spec §§“Recovery record and lifecycle”, “Retention”; ADR-0003, ADR-0009.
 *
 * Issue #14 Stage B step 3; Issue #174.
 */
internal class RecoveryStore(
    context: Context,
    private val clock: () -> Long,
) : RecoveryStorePort,
    RecoveryStoreReconciliationPort {

    private var faultPort: RecoveryStoreFaultPort = RecoveryStoreFaultPort.NOOP
    private var versionProbe: (java.io.File) -> RecoveryDbVersionGate.VersionDecision = RecoveryDbVersionGate::probe

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

    /** Test-only constructor for asserting pre-probe fail-closed ordering. */
    constructor(
        context: Context,
        clock: () -> Long,
        versionProbe: (java.io.File) -> RecoveryDbVersionGate.VersionDecision,
    ) : this(context, clock) {
        this.versionProbe = versionProbe
    }

    /** Test-only constructor combining probe observation with commit fault injection. */
    constructor(
        context: Context,
        clock: () -> Long,
        faultPort: RecoveryStoreFaultPort,
        versionProbe: (java.io.File) -> RecoveryDbVersionGate.VersionDecision,
    ) : this(context, clock, versionProbe) {
        this.faultPort = faultPort
    }

    private val helper: RecoveryDbHelper = RecoveryDbHelper(context.applicationContext)
    private val versionGateFile: java.io.File =
        context.applicationContext.getDatabasePath(RecoveryDbSchema.FILE_NAME)
    private val snapshotFence = InspectionSnapshotFence()

    private companion object {
        const val LEGACY_FORMAT_VERSION: Int = 1

        /** Physical schema 2: the last inline-manifest schema, migrated pre-open. */
        const val LEGACY_SCHEMA2_VERSION: Int = 2
    }
    private val snapshotPublisher = RecoveryInspectionSnapshotPublisher(context.applicationContext)
    private var reconciliationMutex: RunMutex? = null

    fun probeVersion(): RecoveryDbVersionGate.VersionDecision = versionProbe(versionGateFile)

    override fun availability(): RecoveryStorePort.StoreAvailability = when (val decision = probeVersion()) {
        RecoveryDbVersionGate.VersionDecision.CreateNew,
        is RecoveryDbVersionGate.VersionDecision.OpenExisting,
        -> RecoveryStorePort.StoreAvailability.READY

        is RecoveryDbVersionGate.VersionDecision.Incompatible -> {
            if (decision.version == LEGACY_FORMAT_VERSION && migrateEmptyLegacyStore()) {
                when (probeVersion()) {
                    is RecoveryDbVersionGate.VersionDecision.OpenExisting -> RecoveryStorePort.StoreAvailability.READY
                    else -> RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION
                }
            } else if (decision.version == LEGACY_SCHEMA2_VERSION && migrateSchema2To3()) {
                when (probeVersion()) {
                    is RecoveryDbVersionGate.VersionDecision.OpenExisting -> RecoveryStorePort.StoreAvailability.READY
                    else -> RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION
                }
            } else {
                RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION
            }
        }

        is RecoveryDbVersionGate.VersionDecision.ReadFailed ->
            RecoveryStorePort.StoreAvailability.READ_FAILED
    }

    /**
     * Issue #155 / ADR-0008: v1 records lack the reservation context used by
     * v2 revisions. Only an empty legacy store can be advanced. Retained
     * tombstones make the store non-empty; expired tombstones are removed in
     * the same transaction as the PRAGMA update.
     */
    private fun migrateEmptyLegacyStore(): Boolean {
        // A legacy point or retained tombstone must be rejected without a
        // write-open. In particular, do not purge an expired tombstone from a
        // v1 store that also contains an active/verified record.
        if (!legacyStoreIsMigrationEligible()) return false
        val db = try {
            SQLiteDatabase.openDatabase(
                versionGateFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        } catch (_: android.database.sqlite.SQLiteException) {
            return false
        }
        return try {
            db.beginTransaction()
            try {
                val now = clock()
                val points = DatabaseUtils.longForQuery(
                    db,
                    "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}",
                    null,
                )
                val retainedTombstones = DatabaseUtils.longForQuery(
                    db,
                    "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES} WHERE expires_at_ms > ?",
                    arrayOf(now.toString()),
                )
                if (points != 0L || retainedTombstones != 0L) return false
                db.delete(
                    RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
                    "expires_at_ms <= ?",
                    arrayOf(now.toString()),
                )
                // Rebuild the physical store to the current schema, not only
                // the version pragma: an eligible v1 store has no points and
                // no retained tombstones, so the legacy tables hold nothing
                // that must survive, and a v3 pragma over the legacy shape
                // would block onCreate while every later operation fails
                // (Issue #174 review).
                db.execSQL("DROP TABLE IF EXISTS ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}")
                db.execSQL("DROP TABLE IF EXISTS ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS}")
                db.execSQL("DROP TABLE IF EXISTS ${RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES}")
                RecoveryDbSchema.DDL_STATEMENTS.forEach { db.execSQL(it) }
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (_: RuntimeException) {
            false
        } finally {
            db.close()
        }
    }

    private fun legacyStoreIsMigrationEligible(): Boolean {
        val db = try {
            SQLiteDatabase.openDatabase(
                versionGateFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        } catch (_: android.database.sqlite.SQLiteException) {
            return false
        }
        return try {
            val points = DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}",
                null,
            )
            val retainedTombstones = DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES} WHERE expires_at_ms > ?",
                arrayOf(clock().toString()),
            )
            points == 0L && retainedTombstones == 0L
        } catch (_: RuntimeException) {
            false
        } finally {
            db.close()
        }
    }

    /**
     * Issue #174 / ADR-0009: server-side schema-2 → schema-3 migration. The
     * manifest blobs are chunked entirely inside SQLite (`substr()` over a
     * bounded recursive CTE); no Android `Cursor` ever reads a manifest row,
     * so a store poisoned by a >2 MB schema-2 row migrates successfully.
     * Logical `format_version` and `payload_checksum` bytes are copied
     * unchanged, which is the checksum-invariance contract.
     *
     * The migration is one transaction: any DDL/CHECK/validation failure rolls
     * the store back to a fail-closed schema-2 state; the next start may retry.
     */
    private fun migrateSchema2To3(): Boolean {
        val db = try {
            SQLiteDatabase.openDatabase(
                versionGateFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        } catch (_: android.database.sqlite.SQLiteException) {
            return false
        }
        return try {
            db.beginTransaction()
            try {
                createSchema3Tables(db)
                copyRecordRows(db)
                chunkColumn(db, RecoveryDbSchema.SLOT_PRE, "pre_manifest")
                chunkColumn(db, RecoveryDbSchema.SLOT_INTENDED, "intended_manifest")
                chunkReviewedColumn(db)
                validateChunkCoverage(db)
                db.execSQL("DROP TABLE ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}")
                db.execSQL(
                    "ALTER TABLE recovery_points_v3 RENAME TO ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}",
                )
                db.execSQL("PRAGMA user_version = ${RecoveryDbSchema.SCHEMA_VERSION}")
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            android.util.Log.w("RecoveryStore", "schema 2->3 migration failed; store stays fail-closed", t)
            false
        } finally {
            db.close()
        }
    }

    private fun createSchema3Tables(db: SQLiteDatabase) {
        // Stage the exact canonical schema-3 shapes under the v3 staging name;
        // the existing v2 table keeps serving until the atomic rename.
        db.execSQL(
            "CREATE TABLE recovery_points_v3 (${RecoveryDbSchema.RECOVERY_POINTS_COLUMNS})",
        )
        db.execSQL(
            "CREATE TABLE ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} " +
                "(${RecoveryDbSchema.MANIFEST_CHUNKS_COLUMNS})",
        )
    }

    private fun copyRecordRows(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO recovery_points_v3 (
              point_id, format_version, run_id, created_at_ms, updated_at_ms,
              lifecycle, prior_lifecycle,
              pre_manifest_size, pre_revision, pre_digest,
              intended_manifest_size, intended_digest, apply_action_digest,
              reviewed_manifest_size, reviewed_digest, recovery_action_digest,
              item_count, resource_count, payload_checksum
            )
            SELECT point_id, format_version, run_id, created_at_ms, updated_at_ms,
              lifecycle, prior_lifecycle,
              length(pre_manifest), pre_revision, pre_digest,
              length(intended_manifest), intended_digest, apply_action_digest,
              CASE WHEN reviewed_manifest IS NULL
                   THEN NULL ELSE length(reviewed_manifest) END,
              reviewed_digest, recovery_action_digest,
              item_count, resource_count, payload_checksum
            FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}
            """.trimIndent(),
        )
    }

    /**
     * Chunk one manifest column inside SQLite. The recursive CTE is bounded by
     * the engineering bound (max chunk count for [RecoveryDbSchema.MAX_MANIFEST_BYTES]);
     * the per-row `WHERE i * L < length(col)` filters the actual chunk count.
     * Zero-length or oversized sources violate the v3 CHECK constraints or the
     * coverage validation and abort the transaction.
     */
    private fun chunkColumn(db: SQLiteDatabase, slot: Int, column: String) {
        val maxChunks = RecoveryDbSchema.MAX_MANIFEST_BYTES / RecoveryDbSchema.CHUNK_BYTES
        val last = maxChunks - 1
        db.execSQL(
            """
            WITH RECURSIVE cnt(i) AS (
              VALUES(0) UNION ALL SELECT i + 1 FROM cnt WHERE i < $last
            )
            INSERT INTO ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} (point_id, slot, chunk_index, chunk)
            SELECT point_id, $slot, i,
                   substr($column, i * ${RecoveryDbSchema.CHUNK_BYTES} + 1, ${RecoveryDbSchema.CHUNK_BYTES})
            FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}, cnt
            WHERE i * ${RecoveryDbSchema.CHUNK_BYTES} < length($column)
            """.trimIndent(),
        )
    }

    private fun chunkReviewedColumn(db: SQLiteDatabase) {
        val maxChunks = RecoveryDbSchema.MAX_MANIFEST_BYTES / RecoveryDbSchema.CHUNK_BYTES
        val last = maxChunks - 1
        db.execSQL(
            """
            WITH RECURSIVE cnt(i) AS (
              VALUES(0) UNION ALL SELECT i + 1 FROM cnt WHERE i < $last
            )
            INSERT INTO ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} (point_id, slot, chunk_index, chunk)
            SELECT point_id, ${RecoveryDbSchema.SLOT_REVIEWED}, i,
                   substr(reviewed_manifest, i * ${RecoveryDbSchema.CHUNK_BYTES} + 1, ${RecoveryDbSchema.CHUNK_BYTES})
            FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS}, cnt
            WHERE reviewed_manifest IS NOT NULL
              AND i * ${RecoveryDbSchema.CHUNK_BYTES} < length(reviewed_manifest)
            """.trimIndent(),
        )
    }

    /**
     * Cursor-free coverage validation: any violated expectation inserts into a
     * CHECK(0) table and throws, rolling the migration back. Proves, per
     * (point, slot): the recorded size matches the assembled chunk total, the
     * chunk count matches ceil(size / L), the indices are exactly 0..n-1, and
     * the chunk lengths match the required shape (all L except one final
     * remainder; a single chunk is exactly the size).
     */
    private fun validateChunkCoverage(db: SQLiteDatabase) {
        val l = RecoveryDbSchema.CHUNK_BYTES
        db.execSQL("CREATE TABLE migration_violations (violation INTEGER NOT NULL CHECK (violation = 0))")
        db.execSQL(
            """
            INSERT INTO migration_violations (violation)
            SELECT 1 FROM (
              SELECT point_id, slot,
                     COUNT(*) AS n,
                     COUNT(DISTINCT chunk_index) AS dn,
                     SUM(chunk_index) AS isum,
                     SUM(length(chunk)) AS total,
                     MAX(length(chunk)) AS max_len
              FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS}
              GROUP BY point_id, slot
            ) agg LEFT JOIN (
              SELECT point_id AS p_id, ${RecoveryDbSchema.SLOT_PRE} AS s_slot, pre_manifest_size AS sz
                FROM recovery_points_v3
              UNION ALL
              SELECT point_id, ${RecoveryDbSchema.SLOT_INTENDED}, intended_manifest_size
                FROM recovery_points_v3
              UNION ALL
              SELECT point_id, ${RecoveryDbSchema.SLOT_REVIEWED}, reviewed_manifest_size
                FROM recovery_points_v3 WHERE reviewed_manifest_size IS NOT NULL
            ) sz ON sz.p_id = agg.point_id AND sz.s_slot = agg.slot
            WHERE sz.p_id IS NULL
               OR agg.dn != agg.n
               OR agg.isum != agg.n * (agg.n - 1) / 2
               OR agg.total != sz.sz
               OR agg.n != (sz.sz + $l - 1) / $l
               OR agg.max_len != CASE WHEN agg.n = 1 THEN sz.sz ELSE $l END
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO migration_violations (violation)
            SELECT 1 FROM (
              SELECT point_id AS p_id, ${RecoveryDbSchema.SLOT_PRE} AS s_slot FROM recovery_points_v3
              UNION ALL
              SELECT point_id, ${RecoveryDbSchema.SLOT_INTENDED} FROM recovery_points_v3
              UNION ALL
              SELECT point_id, ${RecoveryDbSchema.SLOT_REVIEWED} FROM recovery_points_v3
                WHERE reviewed_manifest_size IS NOT NULL
            ) sz LEFT JOIN (
              SELECT DISTINCT point_id, slot FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS}
            ) agg ON agg.point_id = sz.p_id AND agg.slot = sz.s_slot
            WHERE agg.point_id IS NULL
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE migration_violations")
    }

    /** Startup-only availability: classify on-disk artifacts before any SQLite open. */
    private fun startupAvailability(): RecoveryStorePort.StoreAvailability = when (
        RecoveryStartupStorageClassifier.classify(
            versionGateFile,
            snapshotPublisher.directoryForStartupInventory(),
        )
    ) {
        RecoveryStartupStorageClassifier.State.Pristine -> RecoveryStorePort.StoreAvailability.READY

        RecoveryStartupStorageClassifier.State.Existing -> availability()

        RecoveryStartupStorageClassifier.State.SuspiciousAbsence,
        RecoveryStartupStorageClassifier.State.ZeroLengthMain,
        RecoveryStartupStorageClassifier.State.InvalidMain,
        RecoveryStartupStorageClassifier.State.UnreadableInventory,
        -> RecoveryStorePort.StoreAvailability.READ_FAILED
    }

    /**
     * #89 inspection boundary: never probe/open SQLite here. A process-local
     * valid fence and matching final snapshot are both required before the
     * direct reader may classify one point.
     */
    override fun readInspectionProjection(
        pointId: RecoveryPointId,
    ): RecoveryStorePort.InspectionProjectionRead = when (val state = snapshotFence.state()) {
        is InspectionSnapshotFence.State.VALID -> {
            val snapshot = snapshotPublisher.reader().read()
                ?: return RecoveryStorePort.InspectionProjectionRead.Unavailable
            if (snapshot.generation != state.generation) {
                RecoveryStorePort.InspectionProjectionRead.Unavailable
            } else {
                RecoveryStorePort.InspectionProjectionRead.Value(snapshot.project(pointId))
            }
        }

        InspectionSnapshotFence.State.INCOMPATIBLE -> RecoveryStorePort.InspectionProjectionRead.Incompatible

        InspectionSnapshotFence.State.UNKNOWN,
        is InspectionSnapshotFence.State.DIRTY,
        -> RecoveryStorePort.InspectionProjectionRead.Unavailable
    }

    @Synchronized
    override fun bindReconciliationIssuer(
        mutex: RunMutex,
    ): RecoveryStoreReconciliationIssuer? {
        val bound = reconciliationMutex
        if (bound != null && bound !== mutex) return null
        reconciliationMutex = mutex
        return ReconciliationIssuer(mutex)
    }

    private inner class ReconciliationIssuer(
        private val mutex: RunMutex,
    ) : RecoveryStoreReconciliationIssuer {
        override fun openSession(
            lease: RunMutex.ReconciliationLease,
        ): RecoveryStoreReconciliationSession? = if (lease.isActiveFor(mutex)) ReconciliationSession(lease, mutex) else null
    }

    /**
     * Insert a new recovery record with lifecycle `CREATING`, then advance to
     * `READY`. The insert (record row plus both slot chunk sets) and the
     * lifecycle transition are each one transaction. Returns the persisted
     * [StoredRecord] after read-back validation.
     */
    override fun checkpoint(
        payload: RecoveryStorePort.CheckpointPayload,
    ): RecoveryStorePort.CheckpointResult {
        val mutation = beginReadyOrdinaryMutation()
            ?: return RecoveryStorePort.CheckpointResult.StoreUnavailable
        val now = clock()
        val preManifestBytes = RecoveryRecordCodec.encodeManifest(payload.preManifest)
        // Engineering bound before any SQLite work: empty bytes are never a
        // valid manifest and oversized bytes cannot be chunked. Fail-closed,
        // no layout mutation (Issue #174).
        if (preManifestBytes.isEmpty() || preManifestBytes.size > RecoveryDbSchema.MAX_MANIFEST_BYTES) {
            snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
            return RecoveryStorePort.CheckpointResult.CreateFailed
        }
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

        val result = run {
            try {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    when (val decision = RetentionPolicy.planCreate(listRetentionRecords(db), now)) {
                        RetentionPolicy.CreateDecision.AdmissionBlocked ->
                            return@run RecoveryStorePort.CheckpointResult.AdmissionBlocked

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
                            val originalDeadline = if (
                                action is RetentionPolicy.RetentionAction.Tombstone &&
                                action.reason == RetentionPolicy.ExpireReason.COUNT_RETENTION &&
                                LifecycleTransitions.isFinal(record.lifecycle)
                            ) {
                                Math.addExact(record.updatedAtMillis, RetentionPolicy.TOMBSTONE_RETENTION_MILLIS)
                            } else {
                                null
                            }
                            writeTombstone(db, record, action, now, expiresAtMillis = originalDeadline)
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
                if (creatingReadback !is RecoveryStorePort.RecordRead.Readable ||
                    creatingReadback.record.lifecycle != LifecycleState.CREATING ||
                    !validateReadable(creatingReadback)
                ) {
                    return@run RecoveryStorePort.CheckpointResult.ValidateFailed
                }

                if (!advanceRaw(payload.pointId, LifecycleState.READY)) {
                    return@run RecoveryStorePort.CheckpointResult.CreateFailed
                }
                val readyReadback = readRecord(payload.pointId)
                if (readyReadback !is RecoveryStorePort.RecordRead.Readable ||
                    readyReadback.record.lifecycle != LifecycleState.READY ||
                    !validateReadable(readyReadback)
                ) {
                    return@run RecoveryStorePort.CheckpointResult.ValidateFailed
                }
                RecoveryStorePort.CheckpointResult.Ready(readyReadback.record)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                RecoveryStorePort.CheckpointResult.PointIdCollision
            } catch (e: android.database.SQLException) {
                RecoveryStorePort.CheckpointResult.CreateFailed
            } catch (e: RuntimeException) {
                RecoveryStorePort.CheckpointResult.CreateFailed
            }
        }
        return when (result) {
            is RecoveryStorePort.CheckpointResult.Ready -> if (publishCurrentProjection(mutation)) {
                result
            } else {
                RecoveryStorePort.CheckpointResult.ValidateFailed
            }

            RecoveryStorePort.CheckpointResult.PointIdCollision -> {
                snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.PROVEN_NO_COMMIT)
                result
            }

            RecoveryStorePort.CheckpointResult.AdmissionBlocked,
            -> {
                snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.PROVEN_NO_COMMIT)
                result
            }

            RecoveryStorePort.CheckpointResult.CreateFailed,
            RecoveryStorePort.CheckpointResult.ValidateFailed,
            RecoveryStorePort.CheckpointResult.StoreUnavailable,
            -> {
                snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
                result
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
        val mutation = beginReadyOrdinaryMutation() ?: return false
        val intendedBytes = RecoveryRecordCodec.encodeManifest(intendedManifest)
        val updated = updateRecord(pointId, RecoveryStoreFaultPort.Phase.APPLYING) { current ->
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
        return completeOrdinaryMutation(mutation, updated)
    }

    /**
     * Advance the lifecycle of [pointId] to [next] if the transition is legal.
     * Returns true on success; false if the row is missing, the transition is
     * illegal, or the update failed.
     */
    override fun advance(pointId: RecoveryPointId, next: LifecycleState): Boolean {
        val mutation = beginReadyOrdinaryMutation() ?: return false
        val advanced = advanceRaw(pointId, next)
        return if (advanced && publishCurrentProjection(mutation)) {
            true
        } else {
            snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
            false
        }
    }

    private fun advanceRaw(pointId: RecoveryPointId, next: LifecycleState): Boolean {
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
        val mutation = beginReadyOrdinaryMutation() ?: return false
        return completeOrdinaryMutation(
            mutation,
            markRestoringRaw(pointId, reviewedManifest, reviewedDigest, recoveryActionDigest),
        )
    }

    private fun markRestoringRaw(
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
        val mutation = beginReadyOrdinaryMutation()
            ?: return RecoveryStorePort.RetentionOutcome.StoreUnavailable
        val toEvict = listRetentionRecords(includeFinal = true).filter {
            RetentionPolicy.actionFor(it, nowMillis) !is RetentionPolicy.RetentionAction.Keep
        }
        val result = applyEvictions(toEvict, nowMillis)
        return if (result == RecoveryStorePort.RetentionOutcome.Applied && publishCurrentProjection(mutation)) {
            result
        } else {
            snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
            if (result == RecoveryStorePort.RetentionOutcome.StoreUnavailable) result else RecoveryStorePort.RetentionOutcome.Failed
        }
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

    /**
     * Ordinary closed point read (Issue #174). Bounded row projection first;
     * chunk assembly and manifest decode failures are [Unreadable], never an
     * exception or null.
     */
    override fun readRecord(pointId: RecoveryPointId): RecoveryStorePort.RecordRead {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) {
            return RecoveryStorePort.RecordRead.Failed
        }
        val db = try {
            helper.readableDatabase
        } catch (_: Exception) {
            return RecoveryStorePort.RecordRead.Failed
        }
        val row = try {
            readRowMetadata(db, pointId)
        } catch (_: Exception) {
            return RecoveryStorePort.RecordRead.Failed
        } ?: return RecoveryStorePort.RecordRead.Missing
        return try {
            rowToRecordRead(db, row)
        } catch (_: Exception) {
            RecoveryStorePort.RecordRead.Failed
        }
    }

    private fun rowToRecordRead(
        db: SQLiteDatabase,
        row: RecordRow,
    ): RecoveryStorePort.RecordRead {
        val metadata = row.toMetadata()
        // Manifest decode can raise the checked java.io.EOFException for a
        // truncated payload whose chunk shape still assembles, so the store
        // boundary normalizes Exception (not RuntimeException only) into the
        // closed result (Issue #174 review).
        val preManifest = try {
            RecoveryManifestChunks.readSlot(db, row.pointId, RecoveryDbSchema.SLOT_PRE, row.preManifestSize)
        } catch (_: Exception) {
            null
        } ?: return RecoveryStorePort.RecordRead.Unreadable(metadata)
        val intendedManifest = try {
            RecoveryManifestChunks.readSlot(db, row.pointId, RecoveryDbSchema.SLOT_INTENDED, row.intendedManifestSize)
        } catch (_: Exception) {
            null
        } ?: return RecoveryStorePort.RecordRead.Unreadable(metadata)
        val reviewedManifest = row.reviewedManifestSize?.let { size ->
            try {
                RecoveryManifestChunks.readSlot(db, row.pointId, RecoveryDbSchema.SLOT_REVIEWED, size)
            } catch (_: Exception) {
                null
            } ?: return RecoveryStorePort.RecordRead.Unreadable(metadata)
        }
        val encoded = row.toEncoded(preManifest, intendedManifest, reviewedManifest)
        try {
            RecoveryRecordCodec.decodeManifest(encoded.preManifest)
            RecoveryRecordCodec.decodeManifest(encoded.intendedManifest)
            encoded.reviewedManifest?.let(RecoveryRecordCodec::decodeManifest)
        } catch (_: Exception) {
            return RecoveryStorePort.RecordRead.Unreadable(metadata)
        }
        return RecoveryStorePort.RecordRead.Readable(StoredRecord(encoded))
    }

    /**
     * Bounded metadata enumeration for restart reconciliation (Issue #174):
     * every non-final record's small columns, ordered by creation time. A row
     * whose metadata cannot be decoded is reported [ReconciliationCandidate.Malformed]
     * and preserved; a store-level failure returns null.
     */
    fun listReconciliationCandidates(): List<ReconciliationCandidate>? {
        if (availability() != RecoveryStorePort.StoreAvailability.READY) return null
        val db = try {
            helper.readableDatabase
        } catch (_: RuntimeException) {
            return null
        }
        return try {
            val out = ArrayList<ReconciliationCandidate>()
            db.query(
                RecoveryDbSchema.TABLE_RECOVERY_POINTS,
                RecoveryDbSchema.RECORD_COLUMNS.toTypedArray(),
                null,
                null,
                null,
                null,
                "created_at_ms ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = try {
                        cursorToRow(cursor)
                    } catch (_: Exception) {
                        out += ReconciliationCandidate.Malformed(rowPointIdOrNull(cursor))
                        continue
                    }
                    if (!LifecycleTransitions.isFinal(row.lifecycle)) {
                        out += ReconciliationCandidate.Valid(row.toMetadata())
                    }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    override fun pruneUnused(pointId: RecoveryPointId): Boolean {
        val mutation = beginReadyOrdinaryMutation() ?: return false
        return completeOrdinaryMutation(mutation, pruneUnusedRaw(pointId))
    }

    private fun pruneUnusedRaw(pointId: RecoveryPointId): Boolean = try {
        val db = helper.writableDatabase
        var committed = false
        db.beginTransaction()
        try {
            val record = readAssembledEncoded(db, pointId) ?: return false
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
        return queryTombstone(helper.readableDatabase, pointId)
    }

    private fun queryTombstone(
        db: SQLiteDatabase,
        pointId: RecoveryPointId,
    ): RecoveryStorePort.Tombstone? {
        val cursor = db.query(
            RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
            arrayOf("reason", "expires_at_ms"),
            "point_id = ?",
            arrayOf(pointId.value),
            null,
            null,
            null,
        )
        return cursor.use {
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

    /**
     * One lifecycle mutation transaction: read the assembled record, transform,
     * write the bounded row columns, and rewrite exactly the manifest slots
     * whose bytes changed — all inside the caller's transaction (ADR-0003).
     */
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
            val current = readAssembledEncoded(db, pointId) ?: return false
            val transformed = transform(current) ?: return false
            faultPort.beforeCommit(phase, pointId)
            val updated = transformed.copy(
                payloadChecksum = RecoveryRecordCodec.computePayloadChecksum(transformed),
            )
            val count = writeMutableRecordColumns(db, updated)
            if (count != 1) return false
            if (!updated.intendedManifest.contentEquals(current.intendedManifest)) {
                RecoveryManifestChunks.writeSlot(db, pointId, RecoveryDbSchema.SLOT_INTENDED, updated.intendedManifest)
            }
            if (updated.reviewedManifest == null && current.reviewedManifest != null) {
                RecoveryManifestChunks.deleteSlot(db, pointId, RecoveryDbSchema.SLOT_REVIEWED)
            } else if (
                updated.reviewedManifest != null &&
                !updated.reviewedManifest.contentEquals(current.reviewedManifest)
            ) {
                RecoveryManifestChunks.writeSlot(db, pointId, RecoveryDbSchema.SLOT_REVIEWED, updated.reviewedManifest)
            }
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
        return readback is RecoveryStorePort.RecordRead.Readable && validateReadable(readback)
    }

    private inner class ReconciliationSession(
        private val lease: RunMutex.ReconciliationLease,
        private val mutex: RunMutex,
    ) : RecoveryStoreReconciliationSession {
        private var closed: Boolean = false

        override fun isActive(): Boolean = !closed && lease.isActiveFor(mutex)

        override fun availability(): RecoveryStorePort.StoreAvailability = if (isActive()) startupAvailability() else RecoveryStorePort.StoreAvailability.READ_FAILED

        override fun listReconciliationCandidates(): List<ReconciliationCandidate>? = if (isActive()) this@RecoveryStore.listReconciliationCandidates() else null

        override fun loadRecord(candidate: RecoveryStorePort.RecordMetadata): RecoveryStorePort.RecordRead? = if (isActive()) this@RecoveryStore.readRecord(candidate.pointId) else null

        override fun quarantineUnmutated(
            pointId: RecoveryPointId,
            expectedLifecycle: LifecycleState,
        ): Boolean = reconcileMutation { quarantineUnmutatedRaw(pointId, expectedLifecycle) }

        override fun advance(pointId: RecoveryPointId, next: LifecycleState): Boolean = reconcileMutation { advanceRaw(pointId, next) }

        override fun markRestoring(
            pointId: RecoveryPointId,
            reviewedManifest: PersistenceManifest,
            reviewedDigest: ByteArray,
            recoveryActionDigest: ByteArray,
        ): Boolean = reconcileMutation {
            markRestoringRaw(pointId, reviewedManifest, reviewedDigest, recoveryActionDigest)
        }

        override fun pruneUnused(pointId: RecoveryPointId): Boolean = reconcileMutation { pruneUnusedRaw(pointId) }

        override fun runRetention(nowMillis: Long): RecoveryStorePort.RetentionOutcome {
            if (!isActive()) return RecoveryStorePort.RetentionOutcome.StoreUnavailable
            val mutation = beginReconciliationMutation(this)
                ?: return RecoveryStorePort.RetentionOutcome.StoreUnavailable
            val records = listRetentionRecords(includeFinal = true).filter {
                RetentionPolicy.actionFor(it, nowMillis) !is RetentionPolicy.RetentionAction.Keep
            }
            val result = applyEvictions(records, nowMillis)
            return if (result == RecoveryStorePort.RetentionOutcome.Applied && publishCurrentProjection(mutation)) {
                result
            } else {
                snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
                if (result == RecoveryStorePort.RetentionOutcome.StoreUnavailable) result else RecoveryStorePort.RetentionOutcome.Failed
            }
        }

        override fun rebuildInspectionSnapshot(): Boolean {
            if (!isActive()) return false
            return when (startupAvailability()) {
                RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION -> {
                    snapshotFence.markIncompatible()
                    false
                }

                RecoveryStorePort.StoreAvailability.READ_FAILED -> {
                    snapshotFence.markUnknown()
                    false
                }

                RecoveryStorePort.StoreAvailability.READY -> {
                    val mutation = beginReconciliationMutation(this) ?: return false
                    publishCurrentProjection(mutation)
                }
            }
        }

        override fun close() {
            closed = true
        }

        private fun reconcileMutation(block: () -> Boolean): Boolean {
            if (!isActive()) return false
            val mutation = beginReconciliationMutation(this) ?: return false
            val committedAndValidated = block()
            return completeOrdinaryMutation(mutation, committedAndValidated)
        }
    }

    /**
     * Session-only quarantine of an unreadable record whose durable lifecycle
     * proves no Launcher mutation can have begun (Issue #174). Rechecks the
     * point ID and expected lifecycle inside the transaction, writes the typed
     * `QUARANTINED` tombstone, and deletes chunks child-first, then the row.
     */
    private fun quarantineUnmutatedRaw(
        pointId: RecoveryPointId,
        expectedLifecycle: LifecycleState,
    ): Boolean = try {
        require(
            expectedLifecycle == LifecycleState.CREATING || expectedLifecycle == LifecycleState.READY,
        ) { "Quarantine requires a proven no-mutation lifecycle: $expectedLifecycle" }
        val db = helper.writableDatabase
        var committed = false
        db.beginTransaction()
        try {
            val row = readRowMetadata(db, pointId) ?: return false
            if (row.lifecycle != expectedLifecycle) return false
            faultPort.beforeCommit(RecoveryStoreFaultPort.Phase.TOMBSTONE, pointId)
            writeTombstone(
                db,
                RetentionPolicy.RetentionRecord(
                    pointId,
                    row.lifecycle,
                    row.createdAtMs,
                    row.updatedAtMs,
                ),
                RetentionPolicy.RetentionAction.Keep,
                clock(),
                reasonOverride = RecoveryStorePort.TombstoneReason.QUARANTINED,
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
            tombstoneReadback.reason == RecoveryStorePort.TombstoneReason.QUARANTINED
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Ordinary callers may mutate only from an already trusted projection. The
     * fence becomes DIRTY before any authoritative availability/version probe
     * can open SQLite. A later failed probe is uncertain and therefore may not
     * revive the previous VALID generation.
     */
    private fun beginReadyOrdinaryMutation(): InspectionSnapshotFence.Mutation? {
        val mutation = snapshotFence.beginOrdinaryMutation() ?: return null
        return if (availability() == RecoveryStorePort.StoreAvailability.READY) {
            mutation
        } else {
            snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
            null
        }
    }

    private fun beginReconciliationMutation(session: ReconciliationSession): InspectionSnapshotFence.Mutation? = if (session.isActive()) snapshotFence.beginReconciliationMutation() else null

    private fun completeOrdinaryMutation(
        mutation: InspectionSnapshotFence.Mutation,
        committedAndValidated: Boolean,
    ): Boolean = if (committedAndValidated && publishCurrentProjection(mutation)) {
        true
    } else {
        snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
        false
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
        cv.put("intended_manifest_size", encoded.intendedManifest.size)
        cv.put("intended_digest", encoded.intendedDigest)
        cv.put("apply_action_digest", encoded.applyActionDigest)
        encoded.reviewedManifest?.let { cv.put("reviewed_manifest_size", it.size) }
            ?: cv.putNull("reviewed_manifest_size")
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

    /**
     * Insert the record row and both required manifest slot chunk sets inside
     * the caller's transaction (ADR-0003: one checkpoint transaction).
     */
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
        cv.put("pre_manifest_size", encoded.preManifest.size)
        cv.put("pre_revision", encoded.preRevision.value)
        cv.put("pre_digest", encoded.preDigest)
        cv.put("intended_manifest_size", encoded.intendedManifest.size)
        cv.put("intended_digest", encoded.intendedDigest)
        cv.put("apply_action_digest", encoded.applyActionDigest)
        cv.putNull("reviewed_manifest_size")
        cv.putNull("reviewed_digest")
        cv.putNull("recovery_action_digest")
        cv.put("item_count", encoded.itemCount)
        cv.put("resource_count", encoded.resourceCount)
        cv.put("payload_checksum", encoded.payloadChecksum)
        db.insertOrThrow(RecoveryDbSchema.TABLE_RECOVERY_POINTS, null, cv)
        RecoveryManifestChunks.writeSlot(db, encoded.pointId, RecoveryDbSchema.SLOT_PRE, encoded.preManifest)
        RecoveryManifestChunks.writeSlot(db, encoded.pointId, RecoveryDbSchema.SLOT_INTENDED, encoded.intendedManifest)
    }

    /** Bounded projected row: small metadata only, never manifest bytes. */
    private class RecordRow(
        val pointId: RecoveryPointId,
        val formatVersion: Int,
        val runId: RunId,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val lifecycle: LifecycleState,
        val priorLifecycle: LifecycleState?,
        val preManifestSize: Int,
        val preRevision: RevisionId,
        val preDigest: ByteArray,
        val intendedManifestSize: Int,
        val intendedDigest: ByteArray,
        val applyActionDigest: ByteArray,
        val reviewedManifestSize: Int?,
        val reviewedDigest: ByteArray?,
        val recoveryActionDigest: ByteArray?,
        val itemCount: Int,
        val resourceCount: Int,
        val payloadChecksum: ByteArray,
    ) {
        fun toMetadata(): RecoveryStorePort.RecordMetadata = RecoveryStorePort.RecordMetadata(
            pointId = pointId,
            runId = runId,
            lifecycle = lifecycle,
            priorLifecycle = priorLifecycle,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            formatVersion = formatVersion,
            preDigest = preDigest,
            intendedDigest = intendedDigest,
            reviewedDigest = reviewedDigest,
        )

        fun toEncoded(
            preManifest: ByteArray,
            intendedManifest: ByteArray,
            reviewedManifest: ByteArray?,
        ): RecoveryRecordCodec.Encoded = RecoveryRecordCodec.Encoded(
            pointId = pointId,
            runId = runId,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            lifecycle = lifecycle,
            priorLifecycle = priorLifecycle,
            preManifest = preManifest,
            preRevision = preRevision,
            preDigest = preDigest,
            intendedManifest = intendedManifest,
            intendedDigest = intendedDigest,
            applyActionDigest = applyActionDigest,
            reviewedManifest = reviewedManifest,
            reviewedDigest = reviewedDigest,
            recoveryActionDigest = recoveryActionDigest,
            itemCount = itemCount,
            resourceCount = resourceCount,
            payloadChecksum = payloadChecksum,
            formatVersion = formatVersion,
        )
    }

    /**
     * Parse the projected row. Point ID first so a lifecycle/format failure can
     * still report a [ReconciliationCandidate.Malformed] identity; unknown
     * lifecycle/format values throw (metadata failure, never speculation).
     */
    private fun cursorToRow(cursor: android.database.Cursor): RecordRow {
        val pointIdIndex = cursor.getColumnIndexOrThrow("point_id")
        val pointId = RecoveryPointId(cursor.getString(pointIdIndex))
        val priorLifecycleInt = if (cursor.isNull(cursor.getColumnIndexOrThrow("prior_lifecycle"))) {
            null
        } else {
            cursor.getInt(cursor.getColumnIndexOrThrow("prior_lifecycle"))
        }
        val reviewedSizeIndex = cursor.getColumnIndexOrThrow("reviewed_manifest_size")
        return RecordRow(
            pointId = pointId,
            formatVersion = cursor.getInt(cursor.getColumnIndexOrThrow("format_version")),
            runId = RunId(cursor.getString(cursor.getColumnIndexOrThrow("run_id"))),
            createdAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
            updatedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")),
            lifecycle = LifecycleState.fromCanonicalInt(cursor.getInt(cursor.getColumnIndexOrThrow("lifecycle"))),
            priorLifecycle = priorLifecycleInt?.let(LifecycleState::fromCanonicalInt),
            preManifestSize = cursor.getInt(cursor.getColumnIndexOrThrow("pre_manifest_size")),
            preRevision = RevisionId(cursor.getString(cursor.getColumnIndexOrThrow("pre_revision"))),
            preDigest = cursor.getBlob(cursor.getColumnIndexOrThrow("pre_digest")),
            intendedManifestSize = cursor.getInt(cursor.getColumnIndexOrThrow("intended_manifest_size")),
            intendedDigest = cursor.getBlob(cursor.getColumnIndexOrThrow("intended_digest")),
            applyActionDigest = cursor.getBlob(cursor.getColumnIndexOrThrow("apply_action_digest")),
            reviewedManifestSize = if (cursor.isNull(reviewedSizeIndex)) null else cursor.getInt(reviewedSizeIndex),
            reviewedDigest = cursor.getBlobOrNull("reviewed_digest"),
            recoveryActionDigest = cursor.getBlobOrNull("recovery_action_digest"),
            itemCount = cursor.getInt(cursor.getColumnIndexOrThrow("item_count")),
            resourceCount = cursor.getInt(cursor.getColumnIndexOrThrow("resource_count")),
            payloadChecksum = cursor.getBlob(cursor.getColumnIndexOrThrow("payload_checksum")),
        )
    }

    private fun rowPointIdOrNull(cursor: android.database.Cursor): RecoveryPointId? = try {
        RecoveryPointId(cursor.getString(cursor.getColumnIndexOrThrow("point_id")))
    } catch (_: Exception) {
        null
    }

    private fun readRowMetadata(db: SQLiteDatabase, pointId: RecoveryPointId): RecordRow? {
        val cursor = db.query(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            RecoveryDbSchema.RECORD_COLUMNS.toTypedArray(),
            "point_id = ?",
            arrayOf(pointId.value),
            null,
            null,
            null,
        )
        return cursor.use {
            if (!it.moveToFirst()) null else cursorToRow(it)
        }
    }

    /** Assemble the full logical record from the projected row plus chunk slots. */
    private fun readAssembledEncoded(db: SQLiteDatabase, pointId: RecoveryPointId): RecoveryRecordCodec.Encoded? {
        val row = readRowMetadata(db, pointId) ?: return null
        return rowAssembledEncoded(db, row)
    }

    private fun rowAssembledEncoded(db: SQLiteDatabase, row: RecordRow): RecoveryRecordCodec.Encoded? {
        val preManifest = try {
            RecoveryManifestChunks.readSlot(db, row.pointId, RecoveryDbSchema.SLOT_PRE, row.preManifestSize)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val intendedManifest = try {
            RecoveryManifestChunks.readSlot(db, row.pointId, RecoveryDbSchema.SLOT_INTENDED, row.intendedManifestSize)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val reviewedManifest = row.reviewedManifestSize?.let { size ->
            try {
                RecoveryManifestChunks.readSlot(db, row.pointId, RecoveryDbSchema.SLOT_REVIEWED, size)
            } catch (_: RuntimeException) {
                null
            } ?: return null
        }
        return row.toEncoded(preManifest, intendedManifest, reviewedManifest)
    }

    private fun storedFromEncoded(encoded: RecoveryRecordCodec.Encoded): StoredRecord = StoredRecord(encoded)

    private fun validateRecord(encoded: RecoveryRecordCodec.Encoded): Boolean = try {
        RecoveryRecordCodec.decode(encoded)
        true
    } catch (_: Exception) {
        false
    }

    /** Validate a point read-back through the port type; this store always returns its concrete record. */
    private fun validateReadable(read: RecoveryStorePort.RecordRead.Readable): Boolean = (read.record as? StoredRecord)
        ?.let { validateRecord(it.encoded) }
        ?: false

    private fun writeTombstone(
        db: SQLiteDatabase,
        record: RetentionPolicy.RetentionRecord,
        action: RetentionPolicy.RetentionAction,
        nowMillis: Long,
        reasonOverride: RecoveryStorePort.TombstoneReason? = null,
        expiresAtMillis: Long? = null,
    ) {
        val reason = reasonOverride ?: when (record.lifecycle) {
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
        // Tombstones carry the logical record format, not the physical schema.
        cv.put("format_version", RecoveryRecordCodec.RECORD_FORMAT_VERSION)
        cv.put(
            "expires_at_ms",
            expiresAtMillis ?: Math.addExact(nowMillis, RetentionPolicy.TOMBSTONE_RETENTION_MILLIS),
        )
        db.insertWithOnConflict(
            RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        // Explicit child-first chunk ownership (ADR-0009): no committed orphan.
        RecoveryManifestChunks.deletePoint(db, record.pointId)
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
            RecoveryStorePort.TombstoneReason.QUARANTINED -> 6
        }

    private fun tombstoneReasonFromCanonicalInt(value: Int): RecoveryStorePort.TombstoneReason = when (value) {
        1 -> RecoveryStorePort.TombstoneReason.CORRUPT
        2 -> RecoveryStorePort.TombstoneReason.INCOMPATIBLE_VERSION
        3 -> RecoveryStorePort.TombstoneReason.ALREADY_RESTORED
        4 -> RecoveryStorePort.TombstoneReason.EXPIRED
        5 -> RecoveryStorePort.TombstoneReason.PRUNED_UNUSED
        6 -> RecoveryStorePort.TombstoneReason.QUARANTINED
        else -> throw IllegalArgumentException("Unknown tombstone reason: $value")
    }

    /**
     * Build the full derived projection only from the authoritative DB writer path.
     * Every record is projected from bounded columns plus per-point assembly;
     * one unreadable record projects `checksumValid = false` and never aborts
     * publication (Issue #174).
     */
    private fun publishCurrentProjection(mutation: InspectionSnapshotFence.Mutation): Boolean = try {
        // A complete close/reopen precedes every derived publication so the
        // snapshot is built only from a durable authoritative read-back.
        helper.close()
        val db = helper.readableDatabase
        val records = ArrayList<RecoveryInspectionSnapshot.Record>()
        db.query(
            RecoveryDbSchema.TABLE_RECOVERY_POINTS,
            RecoveryDbSchema.RECORD_COLUMNS.toTypedArray(),
            null,
            null,
            null,
            null,
            "point_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = try {
                    cursorToRow(cursor)
                } catch (_: Exception) {
                    // Undecodable metadata cannot be projected; reconciliation
                    // reports it as malformed and preserves it. Never abort the
                    // publication for one row.
                    continue
                }
                val checksumValid = try {
                    val encoded = rowAssembledEncoded(db, row)
                    encoded != null && validateRecord(encoded)
                } catch (_: Exception) {
                    false
                }
                records += RecoveryInspectionSnapshot.Record(
                    pointId = row.pointId,
                    lifecycle = row.lifecycle,
                    createdAtMs = row.createdAtMs,
                    updatedAtMs = row.updatedAtMs,
                    checksumValid = checksumValid,
                    formatVersion = row.formatVersion,
                )
            }
        }
        val tombstones = ArrayList<RecoveryInspectionSnapshot.Tombstone>()
        db.query(
            RecoveryDbSchema.TABLE_RECOVERY_TOMBSTONES,
            arrayOf("point_id", "reason", "expires_at_ms"),
            null,
            null,
            null,
            null,
            "point_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tombstones += RecoveryInspectionSnapshot.Tombstone(
                    pointId = RecoveryPointId(cursor.getString(0)),
                    reason = tombstoneReasonFromCanonicalInt(cursor.getInt(1)),
                    expiresAtMs = cursor.getLong(2),
                )
            }
        }
        val snapshot = RecoveryInspectionSnapshot(
            generation = mutation.candidateGeneration,
            records = records,
            tombstones = tombstones,
        )
        if (snapshotPublisher.publish(snapshot) && snapshotFence.markValid(mutation, snapshot.generation)) {
            true
        } else {
            snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
            false
        }
    } catch (_: RuntimeException) {
        snapshotFence.finish(mutation, InspectionSnapshotFence.MutationOutcome.OUTCOME_UNCERTAIN)
        false
    } finally {
        // The snapshot is complete before the fence becomes valid. Closing the
        // writer-owned helper prevents a closed-store publication from leaving
        // WAL sidecars behind for the subsequent inspection physical oracle.
        helper.close()
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
