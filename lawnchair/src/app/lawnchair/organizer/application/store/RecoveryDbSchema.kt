package app.lawnchair.organizer.application.store

/**
 * Recovery DB schema — private SQLite database `organizer_recovery.db`,
 * independent of `DatabaseHelper.SCHEMA_VERSION`. Format version 1.
 *
 * Spec §“Recovery record and lifecycle”; ADR-0003.
 *
 * Issue #14 Stage B step 3.
 */
object RecoveryDbSchema {

    /** Recovery DB filename. Deliberately absent from LawnchairBackup/backupscheme allowlists. */
    const val FILE_NAME: String = "organizer_recovery.db"

    /** Format version persisted as `PRAGMA user_version`. */
    const val FORMAT_VERSION: Int = 1

    const val TABLE_RECOVERY_POINTS: String = "recovery_points"
    const val TABLE_RECOVERY_TOMBSTONES: String = "recovery_tombstones"

    /**
     * Exact format-1 DDL. All enum values are canonical integers; all digests
     * are 32-byte SHA-256 values.
     *
     * Order of columns is fixed: the codec computes `payload_checksum` over
     * every preceding column in this order with length prefixes.
     */
    const val DDL_FORMAT_1: String = """
        CREATE TABLE recovery_points (
          point_id TEXT PRIMARY KEY NOT NULL,
          format_version INTEGER NOT NULL,
          run_id TEXT NOT NULL,
          created_at_ms INTEGER NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          lifecycle INTEGER NOT NULL,
          prior_lifecycle INTEGER,
          pre_manifest BLOB NOT NULL,
          pre_revision TEXT NOT NULL,
          pre_digest BLOB NOT NULL,
          intended_manifest BLOB NOT NULL,
          intended_digest BLOB NOT NULL,
          apply_action_digest BLOB NOT NULL,
          reviewed_manifest BLOB,
          reviewed_digest BLOB,
          recovery_action_digest BLOB,
          item_count INTEGER NOT NULL,
          resource_count INTEGER NOT NULL,
          payload_checksum BLOB NOT NULL
        );

        CREATE TABLE recovery_tombstones (
          point_id TEXT PRIMARY KEY NOT NULL,
          reason INTEGER NOT NULL,
          format_version INTEGER NOT NULL,
          expires_at_ms INTEGER NOT NULL
        );

        PRAGMA user_version = 1;
    """

    /** Column order for checksum computation — must not be reordered. */
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

    /** WAL plus `synchronous=FULL` configuration. */
    val PRAGMA_CONFIGS: List<String> = listOf(
        "PRAGMA journal_mode=WAL;",
        "PRAGMA synchronous=FULL;",
    )
}
