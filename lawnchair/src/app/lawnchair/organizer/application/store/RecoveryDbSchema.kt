package app.lawnchair.organizer.application.store

/**
 * Recovery DB schema — private SQLite database `organizer_recovery.db`,
 * independent of `DatabaseHelper.SCHEMA_VERSION`. Physical schema version 3.
 *
 * Physical schema versioning ([SCHEMA_VERSION], `PRAGMA user_version`) is
 * separate from the logical recovery-record format version
 * ([RecoveryRecordCodec.RECORD_FORMAT_VERSION]): schema 3 rows keep the
 * logical record format 2 and its payload-checksum bytes, so migration never
 * rewrites either. Spec §“Data and state”; ADR-0009.
 *
 * Issue #174.
 */
object RecoveryDbSchema {

    /** Recovery DB filename. Deliberately absent from LawnchairBackup/backupscheme allowlists. */
    const val FILE_NAME: String = "organizer_recovery.db"

    /** Physical schema version persisted as `PRAGMA user_version`. */
    // Schema 3 moves the manifest blobs out of the recovery-points row into a
    // chunked side table so no physical row can exceed Android SQLite's 2 MB
    // CursorWindow (Issue #174). A schema-2 store is migrated server-side
    // before the helper opens it; schema 1 is handled by the legacy
    // empty-store rule in RecoveryStore.
    const val SCHEMA_VERSION: Int = 3

    const val TABLE_RECOVERY_POINTS: String = "recovery_points"
    const val TABLE_RECOVERY_TOMBSTONES: String = "recovery_tombstones"
    const val TABLE_MANIFEST_CHUNKS: String = "recovery_manifest_chunks"

    /** Manifest slot stored in [TABLE_MANIFEST_CHUNKS]. Canonical integers. */
    const val SLOT_PRE: Int = 0
    const val SLOT_INTENDED: Int = 1
    const val SLOT_REVIEWED: Int = 2

    /** Fixed chunk size; every chunk row stays far below the 2 MB CursorWindow. */
    const val CHUNK_BYTES: Int = 512 * 1024

    /**
     * Per-slot engineering bound for one manifest. This is a corruption bound,
     * far above any real-device scale, not a product cap: the #174 acceptance
     * requires deterministic success at the observed ≥2.25 MB record scale.
     */
    const val MAX_MANIFEST_BYTES: Int = 64 * 1024 * 1024

    /**
     * Bounded columns + checks of the schema-3 record row, without the
     * `CREATE TABLE` wrapper so the server-side migration can stage the same
     * shape under `recovery_points_v3` (ADR-0009).
     */
    val RECOVERY_POINTS_COLUMNS: String = """
        point_id TEXT PRIMARY KEY NOT NULL,
        format_version INTEGER NOT NULL,
        run_id TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL,
        lifecycle INTEGER NOT NULL,
        prior_lifecycle INTEGER,
        pre_manifest_size INTEGER NOT NULL
          CHECK (pre_manifest_size BETWEEN 1 AND 67108864),
        pre_revision TEXT NOT NULL,
        pre_digest BLOB NOT NULL,
        intended_manifest_size INTEGER NOT NULL
          CHECK (intended_manifest_size BETWEEN 1 AND 67108864),
        intended_digest BLOB NOT NULL,
        apply_action_digest BLOB NOT NULL,
        reviewed_manifest_size INTEGER
          CHECK (reviewed_manifest_size IS NULL
                 OR reviewed_manifest_size BETWEEN 1 AND 67108864),
        reviewed_digest BLOB,
        recovery_action_digest BLOB,
        item_count INTEGER NOT NULL,
        resource_count INTEGER NOT NULL,
        payload_checksum BLOB NOT NULL,
        CHECK ((reviewed_manifest_size IS NULL) = (reviewed_digest IS NULL)),
        CHECK (recovery_action_digest IS NULL OR reviewed_manifest_size IS NOT NULL)
    """.trimIndent()

    /**
     * Bounded columns + checks of the manifest chunk side table. No foreign
     * key is declared: Android SQLite foreign-key enablement is not an
     * implicit invariant of this store; chunk ownership is enforced by the
     * child-first deletion primitive plus migration/deletion orphan checks.
     */
    val MANIFEST_CHUNKS_COLUMNS: String = """
        point_id TEXT NOT NULL,
        slot INTEGER NOT NULL CHECK (slot IN (0, 1, 2)),
        chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
        chunk BLOB NOT NULL CHECK (length(chunk) BETWEEN 1 AND 524288),
        PRIMARY KEY (point_id, slot, chunk_index)
    """.trimIndent()

    /**
     * Exact schema-3 DDL. All enum values are canonical integers; all digests
     * are 32-byte SHA-256 values. Manifest bytes live in
     * [TABLE_MANIFEST_CHUNKS]; the record row keeps strictly positive physical
     * byte sizes per required/present slot (absent `REVIEWED` is `NULL`).
     */
    val DDL_SCHEMA_3: String = """
        CREATE TABLE $TABLE_RECOVERY_POINTS (
          $RECOVERY_POINTS_COLUMNS
        );

        CREATE TABLE $TABLE_MANIFEST_CHUNKS (
          $MANIFEST_CHUNKS_COLUMNS
        );

        CREATE TABLE $TABLE_RECOVERY_TOMBSTONES (
          point_id TEXT PRIMARY KEY NOT NULL,
          reason INTEGER NOT NULL,
          format_version INTEGER NOT NULL,
          expires_at_ms INTEGER NOT NULL
        );

        PRAGMA user_version = $SCHEMA_VERSION;
    """.trimIndent()

    /** Logical field order for the payload checksum — must not be reordered. */
    val CHECKSUM_COLUMNS: List<String> = listOf(
        "point_id",
        "format_version",
        "run_id",
        "created_at_ms",
        "updated_at_ms",
        "lifecycle",
        "prior_lifecycle",
        "pre_manifest",
        "pre_revision",
        "pre_digest",
        "intended_manifest",
        "intended_digest",
        "apply_action_digest",
        "reviewed_manifest",
        "reviewed_digest",
        "recovery_action_digest",
        "item_count",
        "resource_count",
    )

    /**
     * Bounded projected columns for every record query. No recovery read path
     * may use `SELECT *` over [TABLE_RECOVERY_POINTS]; the projected row is
     * small metadata only, and manifest bytes are assembled per point from the
     * chunk table.
     */
    val RECORD_COLUMNS: List<String> = listOf(
        "point_id",
        "format_version",
        "run_id",
        "created_at_ms",
        "updated_at_ms",
        "lifecycle",
        "prior_lifecycle",
        "pre_manifest_size",
        "pre_revision",
        "pre_digest",
        "intended_manifest_size",
        "intended_digest",
        "apply_action_digest",
        "reviewed_manifest_size",
        "reviewed_digest",
        "recovery_action_digest",
        "item_count",
        "resource_count",
        "payload_checksum",
    )

    /**
     * Canonical schema-3 DDL split into executable statements (including the
     * `PRAGMA user_version` update). Shared by `RecoveryDbHelper.onCreate` and
     * the v1-empty physical rebuild in `RecoveryStore` so both paths create
     * the exact same structure (Issue #174 review).
     */
    val DDL_STATEMENTS: List<String> = DDL_SCHEMA_3
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** WAL plus `synchronous=FULL` configuration. */
    val PRAGMA_CONFIGS: List<String> = listOf(
        "PRAGMA journal_mode=WAL;",
        "PRAGMA synchronous=FULL;",
    )
}
