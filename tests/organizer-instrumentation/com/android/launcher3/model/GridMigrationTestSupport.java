package com.android.launcher3.model;

import static com.android.launcher3.LauncherPrefs.DB_FILE;
import static com.android.launcher3.LauncherPrefs.DEVICE_TYPE;
import static com.android.launcher3.LauncherPrefs.HOTSEAT_COUNT;
import static com.android.launcher3.LauncherPrefs.WORKSPACE_SIZE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.provider.LauncherDbUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class GridMigrationTestSupport {
    private GridMigrationTestSupport() { }

    static DatabaseHelper open(Context context, String databaseName) {
        return new DatabaseHelper(context, databaseName, user -> 0L, () -> { });
    }

    static void insertFavorite(SQLiteDatabase database, int id, int lockState) {
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.TITLE, "Issue 59 " + id);
        values.put(Favorites.INTENT, "#Intent;end");
        values.put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
        values.put(Favorites.SCREEN, 0);
        values.put(Favorites.CELLX, id - 1);
        values.put(Favorites.CELLY, 0);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
        values.put(Favorites.PROFILE_ID, 0);
        values.put(Favorites.ORGANIZER_LOCK_STATE, lockState);
        database.insertOrThrow(Favorites.TABLE_NAME, null, values);
    }

    static void seedSource(SQLiteDatabase database) {
        if (lockStates(database).length == 0) {
            insertFavorite(database, 1, 2);
            insertFavorite(database, 2, 1);
        }
    }

    static int[] lockStates(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery(
                "SELECT organizerLockState FROM favorites ORDER BY _id", null)) {
            int[] states = new int[cursor.getCount()];
            for (int index = 0; cursor.moveToNext(); index++) {
                states[index] = cursor.getInt(0);
            }
            return states;
        }
    }

    static boolean tableExists(SQLiteDatabase database, String tableName) {
        try (Cursor cursor = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[] { tableName })) {
            return cursor.moveToFirst();
        }
    }

    public static void deleteDatabase(Context context, String databaseName) {
        File database = context.getDatabasePath(databaseName);
        context.deleteDatabase(databaseName);
        new File(database.getPath() + "-journal").delete();
        new File(database.getPath() + "-wal").delete();
        new File(database.getPath() + "-shm").delete();
    }

    static void assertDatabaseArtifactsAbsent(Context context, String databaseName) {
        File database = context.getDatabasePath(databaseName);
        assertFalse(database.exists());
        assertFalse(new File(database.getPath() + "-journal").exists());
        assertFalse(new File(database.getPath() + "-wal").exists());
        assertFalse(new File(database.getPath() + "-shm").exists());
    }

    static void writeGridState(Context context, DeviceGridState state) {
        LauncherPrefs.get(context).putSync(
                WORKSPACE_SIZE.to(state.getColumns() + "," + state.getRows()),
                HOTSEAT_COUNT.to(state.getNumHotseat()),
                DEVICE_TYPE.to(state.getDeviceType()),
                DB_FILE.to(state.getDbFile()));
    }

    static void assertGridState(DeviceGridState expected, DeviceGridState actual) {
        assertEquals(expected.getColumns(), actual.getColumns());
        assertEquals(expected.getRows(), actual.getRows());
        assertEquals(expected.getNumHotseat(), actual.getNumHotseat());
        assertEquals(expected.getDeviceType(), actual.getDeviceType());
        assertEquals(expected.getDbFile(), actual.getDbFile());
    }

    static void assertLocks(SQLiteDatabase database, int... expected) {
        assertArrayEquals(expected, lockStates(database));
    }

    static GridMigrationJournal.Entry journal(Context context, String targetDatabaseName) {
        return GridMigrationJournal.read(context, targetDatabaseName);
    }

    static void assertJournal(GridMigrationJournal.Entry journal,
            GridMigrationJournal.Phase phase, String targetDatabaseName,
            String sourceDatabaseName, DeviceGridState preferences) {
        assertEquals(phase, journal.phase());
        assertEquals(targetDatabaseName, journal.targetDatabaseName());
        assertEquals(sourceDatabaseName, journal.sourceDatabaseName());
        assertEquals(preferences.getDbFile(), journal.authoritativePreferences().getDbFile());
    }

    static void createDurablePhaseFixture(Context context, String targetDatabaseName,
            String sourceDatabaseName, DeviceGridState sourcePreferences,
            DeviceGridState destinationPreferences, GridMigrationJournal.Phase phase) {
        try (DatabaseHelper target = open(context, targetDatabaseName)) {
            SQLiteDatabase database = target.getWritableDatabase();
            if (lockStates(database).length == 0) {
                insertFavorite(database, 50, 2);
            }
            LauncherDbUtils.copyTable(database, Favorites.TABLE_NAME, database,
                    GridMigrationJournal.BACKUP_TABLE, context);
            GridMigrationJournal.create(database, targetDatabaseName, sourceDatabaseName,
                    sourcePreferences, destinationPreferences);
            GridMigrationJournal.setPhase(database, phase);
        }
    }

    static void mutateBackupFavorite(SQLiteDatabase database, int id) {
        ContentValues values = new ContentValues();
        values.put(Favorites.TITLE, "Issue 59 mutated " + id);
        assertEquals(1, database.update(GridMigrationJournal.BACKUP_TABLE, values,
                Favorites._ID + " = ?",
                new String[] { Integer.toString(id) }));
    }

    static void assertRecoveryMetadataAbsent(Context context, String targetDatabaseName) {
        assertFalse(journal(context, targetDatabaseName).exists());
        try (DatabaseHelper target = open(context, targetDatabaseName)) {
            assertFalse(tableExists(target.getWritableDatabase(), GridMigrationJournal.BACKUP_TABLE));
        }
    }

    static void assertRecoveryMetadataPresent(Context context, String targetDatabaseName) {
        assertTrue(journal(context, targetDatabaseName).exists());
        try (DatabaseHelper target = open(context, targetDatabaseName)) {
            assertTrue(tableExists(target.getWritableDatabase(), GridMigrationJournal.BACKUP_TABLE));
        }
    }

    static final class ScriptedRuntime implements GridMigrationRuntime {
        private final boolean enableGridMigrationFix;
        private final EnumSet<GridMigrationOperation> failures =
                EnumSet.noneOf(GridMigrationOperation.class);
        private final EnumSet<GridMigrationOperation> failuresBeforeDelegate =
                EnumSet.noneOf(GridMigrationOperation.class);
        private final EnumSet<GridMigrationOperation> fatalFailures =
                EnumSet.noneOf(GridMigrationOperation.class);
        private final List<GridMigrationOperation> executed = new ArrayList<>();
        private final Deque<Boolean> preferenceWriteResults = new ArrayDeque<>();

        ScriptedRuntime(boolean enableGridMigrationFix) {
            this.enableGridMigrationFix = enableGridMigrationFix;
        }

        void failAfterDelegate(GridMigrationOperation operation) {
            failures.add(operation);
        }

        void failBeforeDelegate(GridMigrationOperation operation) {
            failuresBeforeDelegate.add(operation);
        }

        void terminateAfterDelegate(GridMigrationOperation operation) {
            fatalFailures.add(operation);
        }

        void preferenceWriteResults(boolean... results) {
            for (boolean result : results) {
                preferenceWriteResults.addLast(result);
            }
        }

        boolean executed(GridMigrationOperation operation) {
            return executed.contains(operation);
        }

        @Override
        public boolean enableGridMigrationFix() {
            return enableGridMigrationFix;
        }

        @Override
        public void execute(GridMigrationOperation operation, Runnable delegate) {
            if (failuresBeforeDelegate.remove(operation)) {
                throw new IllegalStateException("Issue 59 " + operation + " before-delegate");
            }
            delegate.run();
            executed.add(operation);
            if (fatalFailures.remove(operation)) {
                throw new SimulatedProcessDeath();
            }
            if (failures.remove(operation)) {
                throw new IllegalStateException("Issue 59 " + operation + " delegate-then-throw");
            }
        }

        @Override
        public boolean writeGridPreferences(DeviceGridState state, BooleanSupplier writer) {
            boolean persisted = writer.getAsBoolean();
            return preferenceWriteResults.isEmpty()
                    ? persisted : preferenceWriteResults.removeFirst() && persisted;
        }
    }

    static final class SimulatedProcessDeath extends Error { }

    static void assertTargetIsUnknown(Context context, String databaseName) {
        try (DatabaseHelper helper = open(context, databaseName)) {
            assertTrue(lockStates(helper.getWritableDatabase()).length > 0);
            assertLocks(helper.getWritableDatabase(), 0, 0);
        }
    }
}
