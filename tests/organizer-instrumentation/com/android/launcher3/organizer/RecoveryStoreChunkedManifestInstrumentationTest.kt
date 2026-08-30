package com.android.launcher3.organizer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.canonical.PersistenceManifest
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.lifecycle.LifecycleState
import app.lawnchair.organizer.application.protocol.RecoveryStorePort
import app.lawnchair.organizer.application.protocol.RunMutex
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.RecoveryPointId
import app.lawnchair.organizer.application.public.RunId
import app.lawnchair.organizer.application.store.RecoveryDbSchema
import app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotReader
import app.lawnchair.organizer.application.store.RecoveryManifestChunks
import app.lawnchair.organizer.application.store.RecoveryRecordCodec
import app.lawnchair.organizer.application.store.RecoveryStore
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #174 real-SQLite evidence (CW-AC-02/03/04/05/10, ADR-0009): oversized
 * ≥2.25 MB checkpoints through the production writer, server-side schema-2 → 3
 * migration of the #171-device-class poisoned store, child-first chunk
 * ownership, and lifecycle-sensitive containment of unreadable records.
 *
 * The 2 MB `CursorWindow` is only enforced by real Android SQLite, so this
 * suite runs as connected instrumentation.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryStoreChunkedManifestInstrumentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanup() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        // Pristine classification also requires no residual snapshot artifacts.
        File(
            context.applicationContext.noBackupFilesDir,
            RecoveryInspectionSnapshotReader.DIRECTORY_NAME,
        ).let { directory ->
            directory.listFiles()?.forEach { file -> if (file.exists()) check(file.delete()) }
            directory.delete()
        }
    }

    // --- CW-AC-02: oversized checkpoint succeeds deterministically ---

    @Test
    fun oversizedCheckpointSucceedsDeterministicallyAtRealDeviceScale() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val manifest = oversizedManifest()
        val manifestBytes = RecoveryRecordCodec.encodeManifest(manifest)
        // The #171 device record: two ~1.12 MB manifests, ~2.25 MB committed row.
        assertTrue(
            "Fixture must reach the observed ≥2.25 MB record scale: ${2 * manifestBytes.size}",
            2 * manifestBytes.size >= 2_247_054,
        )

        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val result = store.checkpoint(checkpointPayload(pointId, manifest))
        assertTrue("Expected Ready, got $result", result is RecoveryStorePort.CheckpointResult.Ready)

        // The committed record survives a real close/reopen with full validation.
        val reopened = RecoveryStore(context) { 2_000L }
        assertEquals(RecoveryStorePort.StoreAvailability.READY, reopened.availability())
        val read = reopened.readRecord(pointId)
        assertTrue("Expected Readable, got $read", read is RecoveryStorePort.RecordRead.Readable)
        val record = (read as RecoveryStorePort.RecordRead.Readable).record
        assertEquals(LifecycleState.READY, record.lifecycle)
        assertTrue(record.checksumValid)
        assertEquals(manifest, record.preManifest)
        assertEquals(manifest, record.intendedManifest)
        assertEquals(RecoveryRecordCodec.RECORD_FORMAT_VERSION, record.formatVersion)

        // Every physical row is bounded; no chunk row approaches the window size.
        openReadOnly().use { db ->
            assertEquals(
                2L * chunkCountFor(manifestBytes.size),
                chunkRowCount(db, pointId),
            )
            assertTrue(maxChunkBytes(db) <= RecoveryDbSchema.CHUNK_BYTES)
            assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
        }
    }

    // --- CW-AC-03: oversized lifecycle rewrites keep every row bounded ---

    @Test
    fun oversizedRecordSupportsLifecycleTransitionsAndReviewedSlot() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val pre = oversizedManifest()
        assertTrue(store.checkpoint(checkpointPayload(pointId, pre)) is RecoveryStorePort.CheckpointResult.Ready)

        val intended = oversizedManifest(rows = 131)
        assertTrue(
            store.markApplying(pointId, intended, ByteArray(32), ByteArray(32), intended.rowCount, 0),
        )
        val reviewed = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)
        assertTrue(store.markRestoring(pointId, reviewed, ByteArray(32), ByteArray(32)))

        val reopened = RecoveryStore(context) { 2_000L }
        val read = reopened.readRecord(pointId)
        assertTrue(read is RecoveryStorePort.RecordRead.Readable)
        val record = (read as RecoveryStorePort.RecordRead.Readable).record
        assertEquals(LifecycleState.RESTORING, record.lifecycle)
        assertEquals(intended, record.intendedManifest)
        assertEquals(reviewed, record.reviewedManifest)
        assertTrue(record.checksumValid)
        openReadOnly().use { db ->
            assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
        }
    }

    // --- CW-AC-10: child-first chunk ownership on retention ---

    @Test
    fun retentionWithOversizedRecordDeletesChunksChildFirst() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val pointId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        assertTrue(store.checkpoint(checkpointPayload(pointId, oversizedManifest())) is RecoveryStorePort.CheckpointResult.Ready)
        assertTrue(store.advance(pointId, LifecycleState.APPLYING))
        assertTrue(store.advance(pointId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.VERIFIED))
        assertTrue(store.advance(pointId, LifecycleState.EXPIRED))

        assertTrue(store.runRetention(1_000L + 86_400_000L) == RecoveryStorePort.RetentionOutcome.Applied)
        assertEquals(RecoveryStorePort.TombstoneReason.EXPIRED, store.readTombstone(pointId)?.reason)
        assertEquals(RecoveryStorePort.RecordRead.Missing, store.readRecord(pointId))
        openReadOnly().use { db ->
            assertEquals(0L, chunkRowCount(db, pointId))
            assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
        }
    }

    // --- CW-AC-05: server-side migration of the poisoned schema-2 store ---

    @Test
    fun poisonedSchema2StoreMigratesServerSideAndStaysLogicalFormat2() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        val healthyId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val poisonedId = RecoveryPointId("1123456789abcdef0123456789abcdef")
        createSchema2Store(
            schema2RowBytes(healthyId, LifecycleState.VERIFIED, smallManifestBytes(), 0),
            schema2RowBytes(poisonedId, LifecycleState.CREATING, oversizedManifestBytes(), 130),
        )

        // Pre-open availability triggers the server-side migration: no Android
        // Cursor ever reads the >2 MB row.
        val store = RecoveryStore(context) { 1_000L }
        assertEquals(RecoveryStorePort.StoreAvailability.READY, store.availability())

        openReadOnly().use { db ->
            assertEquals(
                RecoveryDbSchema.SCHEMA_VERSION.toLong(),
                android.database.DatabaseUtils.longForQuery(db, "PRAGMA user_version", null),
            )
            // Logical record format is preserved byte-for-byte.
            android.database.DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM recovery_points WHERE format_version != ${RecoveryRecordCodec.RECORD_FORMAT_VERSION}",
                null,
            ).let { assertEquals(0L, it) }
            assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
            assertEquals(
                2L * chunkCountFor(oversizedManifestBytes().size),
                chunkRowCount(db, poisonedId),
            )
        }

        val readPoisoned = store.readRecord(poisonedId)
        assertTrue("Poisoned row must be readable after migration", readPoisoned is RecoveryStorePort.RecordRead.Readable)
        val poisoned = (readPoisoned as RecoveryStorePort.RecordRead.Readable).record
        assertEquals(LifecycleState.CREATING, poisoned.lifecycle)
        assertTrue(poisoned.checksumValid)

        val readHealthy = store.readRecord(healthyId)
        assertTrue(readHealthy is RecoveryStorePort.RecordRead.Readable)
        val healthy = (readHealthy as RecoveryStorePort.RecordRead.Readable).record
        assertEquals(LifecycleState.VERIFIED, healthy.lifecycle)
        assertTrue(healthy.checksumValid)

        // Both records are reconciliation candidates: no store poisoning.
        val mutex = RunMutex()
        val runId = RunId("cccccccccccccccccccccccccccccccc")
        assertTrue(mutex.tryAcquire(runId))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        val session = requireNotNull(issuer.openSession(lease))
        try {
            val candidates = requireNotNull(session.listReconciliationCandidates())
            assertEquals(2, candidates.size)
            candidates.forEach { candidate ->
                assertTrue(candidate is app.lawnchair.organizer.application.protocol.ReconciliationCandidate.Valid)
            }
        } finally {
            session.close()
            mutex.release(runId)
        }
    }

    @Test
    fun schema2StoreWithIllegalManifestSizeFailsMigrationFailClosed() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        val id = RecoveryPointId("0123456789abcdef0123456789abcdef")
        // A zero-length manifest is never a valid slot: the schema-3 CHECK
        // constraint aborts the migration transaction.
        createSchema2Store(
            schema2RowBytes(id, LifecycleState.CREATING, ByteArray(0), 0),
        )

        val store = RecoveryStore(context) { 1_000L }
        assertEquals(RecoveryStorePort.StoreAvailability.INCOMPATIBLE_VERSION, store.availability())

        openReadOnly().use { db ->
            assertEquals(2L, android.database.DatabaseUtils.longForQuery(db, "PRAGMA user_version", null))
            assertEquals(0L, android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'recovery_manifest_chunks'", null))
            assertEquals(0L, android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'recovery_points_v3'", null))
        }
    }

    // --- CW-AC-04: lifecycle-sensitive containment of unreadable records ---

    @Test
    fun unreadableChunkRecordIsQuarantinedWhenReadyAndPreservedWhenVerified() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val readyId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        val verifiedId = RecoveryPointId("1123456789abcdef0123456789abcdef")
        // VERIFIED first: a READY record is legitimately pruned by the next
        // checkpoint's retention plan (PRUNE_UNUSED_READY), so the READY fixture
        // must be the last one created.
        assertTrue(store.checkpoint(checkpointPayload(verifiedId, smallManifest())) is RecoveryStorePort.CheckpointResult.Ready)
        assertTrue(store.advance(verifiedId, LifecycleState.APPLYING))
        assertTrue(store.advance(verifiedId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(verifiedId, LifecycleState.VERIFIED))
        assertTrue(store.checkpoint(checkpointPayload(readyId, smallManifest())) is RecoveryStorePort.CheckpointResult.Ready)

        // Corrupt one required chunk of each record through the back door.
        openReadWrite().use { db ->
            db.execSQL(
                "DELETE FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} " +
                    "WHERE point_id = ? AND slot = ${RecoveryDbSchema.SLOT_PRE} AND chunk_index = 0",
                arrayOf<Any>(readyId.value),
            )
            db.execSQL(
                "DELETE FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} " +
                    "WHERE point_id = ? AND slot = ${RecoveryDbSchema.SLOT_PRE} AND chunk_index = 0",
                arrayOf<Any>(verifiedId.value),
            )
        }

        val readyRead = store.readRecord(readyId)
        assertTrue("readyRead=$readyRead", readyRead is RecoveryStorePort.RecordRead.Unreadable)
        assertEquals(LifecycleState.READY, (readyRead as RecoveryStorePort.RecordRead.Unreadable).metadata.lifecycle)
        val verifiedRead = store.readRecord(verifiedId)
        assertTrue("verifiedRead=$verifiedRead", verifiedRead is RecoveryStorePort.RecordRead.Unreadable)
        assertEquals(LifecycleState.VERIFIED, (verifiedRead as RecoveryStorePort.RecordRead.Unreadable).metadata.lifecycle)

        // A fresh store instance: the reconciliation issuer binds exactly once.
        val sessionStore = RecoveryStore(context) { 2_000L }
        val mutex = RunMutex()
        val runId = RunId("cccccccccccccccccccccccccccccccc")
        assertTrue(mutex.tryAcquire(runId))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val issuer = requireNotNull(sessionStore.bindReconciliationIssuer(mutex))
        val session = requireNotNull(issuer.openSession(lease))
        try {
            // READY is a proven no-mutation lifecycle: quarantined transactionally.
            assertTrue(session.quarantineUnmutated(readyId, LifecycleState.READY))
            assertEquals(RecoveryStorePort.RecordRead.Missing, sessionStore.readRecord(readyId))
            assertEquals(
                RecoveryStorePort.TombstoneReason.QUARANTINED,
                sessionStore.readTombstone(readyId)?.reason,
            )

            // VERIFIED is the recovery point itself: refused and preserved.
            assertTrue(!session.quarantineUnmutated(verifiedId, LifecycleState.VERIFIED))
            assertTrue(sessionStore.readRecord(verifiedId) is RecoveryStorePort.RecordRead.Unreadable)
        } finally {
            session.close()
            mutex.release(runId)
        }

        openReadOnly().use { db ->
            assertEquals(0L, chunkRowCount(db, readyId))
            assertEquals(0L, RecoveryManifestChunks.countOrphanChunks(db))
            android.database.DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} WHERE point_id = ?",
                arrayOf(verifiedId.value),
            ).let { assertEquals(1L, it) }
        }
    }

    // --- fixtures ---

    private fun oversizedManifest(rows: Int = 130, iconSize: Int = 9_000): PersistenceManifest {
        val rowList = (0 until rows).map { index ->
            PersistentRow(
                rowId = (index + 1).toLong(),
                itemId = ItemId("item-$index"),
                profileId = ProfileId("0"),
                containerCode = ContainerCode(0),
                screenId = PageId("0"),
                cellX = index % 5,
                cellY = index / 5,
                spanX = 1,
                spanY = 1,
                rank = index,
                itemType = KindCode(0),
                appWidgetId = null,
                appWidgetProvider = null,
                iconBytes = ByteArray(iconSize) { (it % 253).toByte() },
                title = "App $index",
                intent = "intent://app/$index",
                restored = null,
                options = null,
                appWidgetSource = null,
                modified = 0L,
                organizerLockState = OrganizerLockState.UNLOCKED,
                rawCell = null,
                rawSpan = null,
            )
        }
        return PersistenceManifest(
            formatVersion = 1,
            schemaVersion = 33,
            rowCount = rowList.size,
            rows = rowList,
            resources = emptyList(),
            modifiedAtMillis = 0L,
        )
    }

    private fun smallManifest(): PersistenceManifest = PersistenceManifest(1, 33, 0, emptyList(), emptyList(), 0L)

    private fun oversizedManifestBytes(): ByteArray = RecoveryRecordCodec.encodeManifest(oversizedManifest())

    private fun smallManifestBytes(): ByteArray = RecoveryRecordCodec.encodeManifest(smallManifest())

    private fun checkpointPayload(
        pointId: RecoveryPointId,
        manifest: PersistenceManifest,
    ): RecoveryStorePort.CheckpointPayload = RecoveryStorePort.CheckpointPayload(
        pointId = pointId,
        runId = RunId("abcdef0123456789abcdef0123456789"),
        preManifest = manifest,
        preRevision = RevisionId("revision"),
        preDigest = ByteArray(32),
        applyActionDigest = ByteArray(32),
        itemCount = manifest.rowCount,
        resourceCount = manifest.resources.size,
    )

    private fun chunkCountFor(size: Int): Long = ((size.toLong() + RecoveryDbSchema.CHUNK_BYTES - 1) / RecoveryDbSchema.CHUNK_BYTES)

    private fun chunkRowCount(db: SQLiteDatabase, pointId: RecoveryPointId): Long = android.database.DatabaseUtils.longForQuery(
        db,
        "SELECT COUNT(*) FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} WHERE point_id = ?",
        arrayOf(pointId.value),
    )

    private fun maxChunkBytes(db: SQLiteDatabase): Long = android.database.DatabaseUtils.longForQuery(
        db,
        "SELECT COALESCE(MAX(length(chunk)), 0) FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS}",
        null,
    )

    private fun openReadOnly(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

    private fun openReadWrite(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(RecoveryDbSchema.FILE_NAME).absolutePath,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    )

    /** Encoded schema-2 record-row payload with a valid logical payload checksum. */
    private fun schema2RowBytes(
        pointId: RecoveryPointId,
        lifecycle: LifecycleState,
        manifestBytes: ByteArray,
        itemCount: Int,
    ): List<Pair<String, Any?>> {
        // The payload checksum covers item/resource counts: the fixture must
        // build the Encoded with the exact values it inserts.
        val encoded = RecoveryRecordCodec.Encoded(
            pointId = pointId,
            runId = RunId("abcdef0123456789abcdef0123456789"),
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            lifecycle = lifecycle,
            priorLifecycle = null,
            preManifest = manifestBytes,
            preRevision = RevisionId("revision"),
            preDigest = ByteArray(32),
            intendedManifest = manifestBytes,
            intendedDigest = ByteArray(32),
            applyActionDigest = ByteArray(32),
            reviewedManifest = null,
            reviewedDigest = null,
            recoveryActionDigest = null,
            itemCount = itemCount,
            resourceCount = 0,
            payloadChecksum = ByteArray(32),
        )
        val checksum = RecoveryRecordCodec.computePayloadChecksum(encoded)
        return listOf(
            "point_id" to pointId.value,
            "format_version" to RecoveryRecordCodec.RECORD_FORMAT_VERSION,
            "run_id" to "abcdef0123456789abcdef0123456789",
            "created_at_ms" to 1_000L,
            "updated_at_ms" to 1_000L,
            "lifecycle" to lifecycle.canonicalInt,
            "prior_lifecycle" to null,
            "pre_manifest" to manifestBytes,
            "pre_revision" to "revision",
            "pre_digest" to ByteArray(32),
            "intended_manifest" to manifestBytes,
            "intended_digest" to ByteArray(32),
            "apply_action_digest" to ByteArray(32),
            "reviewed_manifest" to null,
            "reviewed_digest" to null,
            "recovery_action_digest" to null,
            "item_count" to itemCount,
            "resource_count" to 0,
            "payload_checksum" to checksum,
        )
    }

    private fun createSchema2Store(vararg rows: List<Pair<String, Any?>>) {
        val file = context.getDatabasePath(RecoveryDbSchema.FILE_NAME)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Exact schema-2 DDL (Issue #14 Stage B step 3): inline manifest blobs.
            db.execSQL(
                "CREATE TABLE recovery_points (" +
                    "point_id TEXT PRIMARY KEY NOT NULL," +
                    "format_version INTEGER NOT NULL," +
                    "run_id TEXT NOT NULL," +
                    "created_at_ms INTEGER NOT NULL," +
                    "updated_at_ms INTEGER NOT NULL," +
                    "lifecycle INTEGER NOT NULL," +
                    "prior_lifecycle INTEGER," +
                    "pre_manifest BLOB NOT NULL," +
                    "pre_revision TEXT NOT NULL," +
                    "pre_digest BLOB NOT NULL," +
                    "intended_manifest BLOB NOT NULL," +
                    "intended_digest BLOB NOT NULL," +
                    "apply_action_digest BLOB NOT NULL," +
                    "reviewed_manifest BLOB," +
                    "reviewed_digest BLOB," +
                    "recovery_action_digest BLOB," +
                    "item_count INTEGER NOT NULL," +
                    "resource_count INTEGER NOT NULL," +
                    "payload_checksum BLOB NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE recovery_tombstones (" +
                    "point_id TEXT PRIMARY KEY NOT NULL," +
                    "reason INTEGER NOT NULL," +
                    "format_version INTEGER NOT NULL," +
                    "expires_at_ms INTEGER NOT NULL)",
            )
            db.execSQL("PRAGMA user_version = 2")
            rows.forEach { row ->
                val columns = row.joinToString(", ") { it.first }
                val placeholders = row.joinToString(", ") { "?" }
                val args = row.map { it.second }.toTypedArray()
                db.execSQL("INSERT INTO recovery_points ($columns) VALUES ($placeholders)", args)
            }
        }
    }

    private fun prepareForMutation(store: RecoveryStore) {
        val mutex = RunMutex()
        val runId = RunId("cccccccccccccccccccccccccccccccc")
        assertTrue(mutex.tryAcquire(runId))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val issuer = requireNotNull(store.bindReconciliationIssuer(mutex))
        val session = requireNotNull(issuer.openSession(lease))
        try {
            assertTrue(session.rebuildInspectionSnapshot())
        } finally {
            session.close()
            mutex.release(runId)
        }
    }

    // --- CW-AC-04 review regression: checked decode failure stays inside the closed result ---

    @Test
    fun truncatedManifestPayloadIsUnreadableAndDoesNotPoisonHealthyReconciliation() {
        context.deleteDatabase(RecoveryDbSchema.FILE_NAME)
        val store = RecoveryStore(context) { 1_000L }
        prepareForMutation(store)
        val verifiedId = RecoveryPointId("1123456789abcdef0123456789abcdef")
        val truncatedId = RecoveryPointId("0123456789abcdef0123456789abcdef")
        assertTrue(store.checkpoint(checkpointPayload(verifiedId, smallManifest())) is RecoveryStorePort.CheckpointResult.Ready)
        assertTrue(store.advance(verifiedId, LifecycleState.APPLYING))
        assertTrue(store.advance(verifiedId, LifecycleState.COMMITTED_UNVERIFIED))
        assertTrue(store.advance(verifiedId, LifecycleState.VERIFIED))
        assertTrue(store.checkpoint(checkpointPayload(truncatedId, smallManifest())) is RecoveryStorePort.CheckpointResult.Ready)

        // Rewrite the READY record's chunks with a truncated manifest payload
        // whose chunk shape and recorded sizes stay consistent (so assembly
        // succeeds and the failure is exactly the manifest decode).
        val full = RecoveryRecordCodec.encodeManifest(smallManifest())
        val truncated = full.copyOf(full.size - 4)
        val encoded = RecoveryRecordCodec.Encoded(
            pointId = truncatedId,
            runId = RunId("abcdef0123456789abcdef0123456789"),
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            lifecycle = LifecycleState.READY,
            priorLifecycle = null,
            preManifest = truncated,
            preRevision = RevisionId("revision"),
            preDigest = ByteArray(32),
            intendedManifest = truncated,
            intendedDigest = ByteArray(32),
            applyActionDigest = ByteArray(32),
            reviewedManifest = null,
            reviewedDigest = null,
            recoveryActionDigest = null,
            itemCount = 0,
            resourceCount = 0,
            payloadChecksum = ByteArray(32),
        )
        openReadWrite().use { db ->
            db.execSQL(
                "DELETE FROM ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} WHERE point_id = ?",
                arrayOf<Any>(truncatedId.value),
            )
            db.execSQL(
                "UPDATE ${RecoveryDbSchema.TABLE_RECOVERY_POINTS} SET " +
                    "pre_manifest_size = ?, intended_manifest_size = ?, payload_checksum = ? WHERE point_id = ?",
                arrayOf<Any>(
                    truncated.size,
                    truncated.size,
                    RecoveryRecordCodec.computePayloadChecksum(encoded),
                    truncatedId.value,
                ),
            )
            db.execSQL(
                "INSERT INTO ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} (point_id, slot, chunk_index, chunk) " +
                    "VALUES (?, ${RecoveryDbSchema.SLOT_PRE}, 0, ?)",
                arrayOf<Any>(truncatedId.value, truncated),
            )
            db.execSQL(
                "INSERT INTO ${RecoveryDbSchema.TABLE_MANIFEST_CHUNKS} (point_id, slot, chunk_index, chunk) " +
                    "VALUES (?, ${RecoveryDbSchema.SLOT_INTENDED}, 0, ?)",
                arrayOf<Any>(truncatedId.value, truncated),
            )
        }

        // Ordinary read: chunk shape assembles, payload decode fails (checked
        // EOF) → closed Unreadable, never an escaping exception.
        val read = store.readRecord(truncatedId)
        assertTrue("Expected Unreadable, got $read", read is RecoveryStorePort.RecordRead.Unreadable)
        // Healthy record still fully readable.
        assertTrue(store.readRecord(verifiedId) is RecoveryStorePort.RecordRead.Readable)

        // Reconciliation sees both candidates and loads each independently.
        val sessionStore = RecoveryStore(context) { 2_000L }
        val mutex = RunMutex()
        val runId = RunId("cccccccccccccccccccccccccccccccc")
        assertTrue(mutex.tryAcquire(runId))
        val lease = requireNotNull(mutex.issueReconciliationLease(runId))
        val issuer = requireNotNull(sessionStore.bindReconciliationIssuer(mutex))
        val session = requireNotNull(issuer.openSession(lease))
        try {
            val candidates = requireNotNull(session.listReconciliationCandidates())
            assertEquals(2, candidates.size)
            candidates.forEach { candidate ->
                val meta = (candidate as app.lawnchair.organizer.application.protocol.ReconciliationCandidate.Valid).metadata
                val loaded = requireNotNull(session.loadRecord(meta))
                when (meta.pointId) {
                    truncatedId -> assertTrue(
                        "Expected Unreadable, got $loaded",
                        loaded is RecoveryStorePort.RecordRead.Unreadable,
                    )

                    else -> assertTrue(loaded is RecoveryStorePort.RecordRead.Readable)
                }
            }
        } finally {
            session.close()
            mutex.release(runId)
        }
    }

}
