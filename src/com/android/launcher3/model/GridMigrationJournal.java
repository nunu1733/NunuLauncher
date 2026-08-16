package com.android.launcher3.model;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.Nullable;

import com.android.launcher3.provider.LauncherDbUtils;

import java.io.File;

final class GridMigrationJournal {
    static final String BACKUP_TABLE = "_issue59_target_favorites_backup";
    private static final String TABLE = "_issue59_grid_migration_journal";
    private static final int ENTRY_ID = 1;

    enum Phase {
        TARGET_OLD,
        MIGRATED_PENDING_FINALIZATION,
        RESTORE_PENDING,
        RESTORE_FAILED,
        FINALIZED
    }

    record Entry(
            boolean exists,
            @Nullable Phase phase,
            String targetDatabaseName,
            String sourceDatabaseName,
            DeviceGridState sourcePreferences,
            DeviceGridState destinationPreferences,
            int backupDigestVersion,
            String backupDigest) {
        DeviceGridState authoritativePreferences() {
            return phase == Phase.FINALIZED ? destinationPreferences : sourcePreferences;
        }
    }

    private GridMigrationJournal() { }

    static Entry read(Context context, String targetDatabaseName) {
        File databaseFile = context.getDatabasePath(targetDatabaseName);
        if (!databaseFile.exists()) {
            return missing();
        }
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(
                databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            return read(database);
        }
    }

    static Entry read(SQLiteDatabase database) {
        if (!LauncherDbUtils.tableExists(database, TABLE)) {
            return missing();
        }
        try (Cursor cursor = database.query(TABLE, null, "entry_id = ?",
                new String[] { Integer.toString(ENTRY_ID) }, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return missing();
            }
            DeviceGridState source = readState(cursor, "source");
            DeviceGridState destination = readState(cursor, "destination");
            return new Entry(
                    true,
                    Phase.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("phase"))),
                    cursor.getString(cursor.getColumnIndexOrThrow("target_name")),
                    cursor.getString(cursor.getColumnIndexOrThrow("source_name")),
                    source,
                    destination,
                    readDigestVersion(cursor),
                    readDigest(cursor));
        }
    }

    static void create(
            SQLiteDatabase database,
            String targetDatabaseName,
            String sourceDatabaseName,
            DeviceGridState sourcePreferences,
            DeviceGridState destinationPreferences) {
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "entry_id INTEGER PRIMARY KEY,"
                + "phase TEXT NOT NULL,"
                + "target_name TEXT NOT NULL,"
                + "source_name TEXT NOT NULL,"
                + "backup_digest_version INTEGER NOT NULL,"
                + "backup_digest_sha256 TEXT NOT NULL,"
                + stateColumns("source") + ","
                + stateColumns("destination") + ")");
        database.delete(TABLE, null, null);
        ContentValues values = new ContentValues();
        values.put("entry_id", ENTRY_ID);
        values.put("phase", Phase.TARGET_OLD.name());
        values.put("target_name", targetDatabaseName);
        values.put("source_name", sourceDatabaseName);
        values.put("backup_digest_version", FavoritesTableDigest.VERSION);
        values.put("backup_digest_sha256",
                FavoritesTableDigest.digest(database, BACKUP_TABLE));
        putState(values, "source", sourcePreferences);
        putState(values, "destination", destinationPreferences);
        database.insertOrThrow(TABLE, null, values);
    }

    static void setPhase(SQLiteDatabase database, Phase phase) {
        ContentValues values = new ContentValues();
        values.put("phase", phase.name());
        if (database.update(TABLE, values, "entry_id = ?",
                new String[] { Integer.toString(ENTRY_ID) }) != 1) {
            throw new IllegalStateException("Grid migration journal entry is missing");
        }
    }

    static void delete(SQLiteDatabase database) {
        LauncherDbUtils.dropTable(database, TABLE);
    }

    static void verifyRestoredFavorites(SQLiteDatabase database, Entry entry) {
        verifyDigest(database, com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME, entry);
    }

    static void verifyBackup(SQLiteDatabase database, Entry entry) {
        verifyDigest(database, BACKUP_TABLE, entry);
    }

    private static void verifyDigest(SQLiteDatabase database, String tableName, Entry entry) {
        if (entry.backupDigestVersion() != FavoritesTableDigest.VERSION) {
            throw new IllegalStateException(
                    "Unsupported favorites backup digest version: "
                            + entry.backupDigestVersion());
        }
        if (!isLowercaseSha256(entry.backupDigest())) {
            throw new IllegalStateException("Favorites backup digest is not canonical SHA-256");
        }
        String restoredDigest = FavoritesTableDigest.digest(database, tableName);
        if (!entry.backupDigest().equals(restoredDigest)) {
            throw new IllegalStateException("Restored favorites digest mismatch");
        }
    }

    private static String stateColumns(String prefix) {
        return prefix + "_columns INTEGER NOT NULL,"
                + prefix + "_rows INTEGER NOT NULL,"
                + prefix + "_hotseat INTEGER NOT NULL,"
                + prefix + "_device_type INTEGER NOT NULL,"
                + prefix + "_database TEXT NOT NULL";
    }

    private static void putState(ContentValues values, String prefix, DeviceGridState state) {
        values.put(prefix + "_columns", state.getColumns());
        values.put(prefix + "_rows", state.getRows());
        values.put(prefix + "_hotseat", state.getNumHotseat());
        values.put(prefix + "_device_type", state.getDeviceType());
        values.put(prefix + "_database", state.getDbFile());
    }

    private static DeviceGridState readState(Cursor cursor, String prefix) {
        return new DeviceGridState(
                cursor.getInt(cursor.getColumnIndexOrThrow(prefix + "_columns")),
                cursor.getInt(cursor.getColumnIndexOrThrow(prefix + "_rows")),
                cursor.getInt(cursor.getColumnIndexOrThrow(prefix + "_hotseat")),
                cursor.getInt(cursor.getColumnIndexOrThrow(prefix + "_device_type")),
                cursor.getString(cursor.getColumnIndexOrThrow(prefix + "_database")));
    }

    private static int readDigestVersion(Cursor cursor) {
        int index = cursor.getColumnIndex("backup_digest_version");
        return index < 0 ? 0 : cursor.getInt(index);
    }

    private static String readDigest(Cursor cursor) {
        int index = cursor.getColumnIndex("backup_digest_sha256");
        return index < 0 ? "" : cursor.getString(index);
    }

    private static boolean isLowercaseSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static Entry missing() {
        return new Entry(false, null, "", "", null, null, 0, "");
    }
}
