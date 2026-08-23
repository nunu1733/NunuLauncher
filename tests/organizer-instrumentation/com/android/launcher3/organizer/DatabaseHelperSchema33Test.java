// Issue #14 Stage B step 6: Schema-33 fresh DB and non-wiping 32->33 migration.
// Plan: tests/organizer-instrumentation/com/android/launcher3/organizer/DatabaseHelperSchema33Test.java
package com.android.launcher3.organizer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.R;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.DbDowngradeHelper;
import com.android.launcher3.model.GridMigrationTestSupport;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import java.io.InputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class DatabaseHelperSchema33Test {

    private Context context;
    private DatabaseHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        GridMigrationTestSupport.deleteDatabase(context, "schema33-test.db");
        helper = new DatabaseHelper(context, "schema33-test.db", user -> 0L, () -> { });
        db = helper.getWritableDatabase();
    }

    @After
    public void tearDown() {
        if (helper != null) helper.close();
        GridMigrationTestSupport.deleteDatabase(context, "schema33-test.db");
    }

    @Test
    public void freshDbIsSchema33() {
        assertEquals(33, db.getVersion());
    }

    @Test
    public void organizerLockStateColumnExists() {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='favorites'",
                null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
        }

        boolean found = false;
        try (Cursor columns = db.rawQuery("PRAGMA table_info(favorites)", null)) {
            while (columns.moveToNext()) {
                String name = columns.getString(columns.getColumnIndexOrThrow("name"));
                if (LauncherSettings.Favorites.ORGANIZER_LOCK_STATE.equals(name)) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue("organizerLockState column must exist on a fresh schema-33 DB", found);
    }

    @Test
    public void freshRowDefaultsToUnlocked() {
        ContentValues row = new ContentValues();
        row.put(LauncherSettings.Favorites._ID, 1);
        row.put(LauncherSettings.Favorites.TITLE, "fresh row");
        db.insertOrThrow(LauncherSettings.Favorites.TABLE_NAME, null, row);

        try (Cursor cursor = db.rawQuery(
                "SELECT organizerLockState FROM favorites WHERE _id=1", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getInt(0));
        }
    }

    @Test
    public void schema32SourceUpgradeMarksLegacyRowsUnknown() throws Exception {
        insertFavorite(2, "legacy row", 2);

        downgradeToSchema32();
        reopenThroughProductionHelper();

        assertEquals(33, db.getVersion());
        assertFavorite(2, "legacy row", 0);
    }

    @Test
    public void schema32To33To32To33PreservesRowsAndEndsUnknown() throws Exception {
        insertFavorite(3, "cycle row", 2);

        downgradeToSchema32();
        reopenThroughProductionHelper();
        assertFavorite(3, "cycle row", 0);

        downgradeToSchema32();
        reopenThroughProductionHelper();

        assertEquals(33, db.getVersion());
        assertFavorite(3, "cycle row", 0);
    }

    @Test
    public void schema32UpgradeFailureRollsBackWithoutChangingLegacyRow() throws Exception {
        insertFavorite(4, "trigger row", 2);
        downgradeToSchema32();
        db.execSQL("CREATE TRIGGER fail_schema33_upgrade BEFORE UPDATE ON favorites "
                + "BEGIN SELECT RAISE(ABORT, 'Issue 59 upgrade failure'); END;");
        helper.close();
        helper = null;

        try (DatabaseHelper failingHelper = new DatabaseHelper(
                context, "schema33-test.db", user -> 0L, () -> { })) {
            try {
                failingHelper.getWritableDatabase();
                fail("Schema-32 upgrade must fail when its legacy-row update aborts");
            } catch (android.database.sqlite.SQLiteException expected) {
            }
        }

        try (SQLiteDatabase raw = SQLiteDatabase.openDatabase(
                context.getDatabasePath("schema33-test.db").getPath(), null,
                SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals(32, raw.getVersion());
            assertFalse(hasOrganizerLockColumn(raw));
            try (Cursor cursor = raw.rawQuery("SELECT title FROM favorites WHERE _id=4", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("trigger row", cursor.getString(0));
            }
        }
    }

    private void insertFavorite(int id, String title, int organizerLockState) {
        ContentValues row = new ContentValues();
        row.put(LauncherSettings.Favorites._ID, id);
        row.put(LauncherSettings.Favorites.TITLE, title);
        row.put(LauncherSettings.Favorites.ORGANIZER_LOCK_STATE, organizerLockState);
        db.insertOrThrow(LauncherSettings.Favorites.TABLE_NAME, null, row);
    }

    private void downgradeToSchema32() throws Exception {
        try (InputStream schema = context.getResources().openRawResource(R.raw.downgrade_schema)) {
            // Issue #118: the caller owns the downgrade transaction.
            try (SQLiteTransaction t = new SQLiteTransaction(db)) {
                DbDowngradeHelper.parse(schema.readAllBytes()).onDowngrade(db, 33, 32);
                t.commit();
            }
        }
        db.setVersion(32);
    }

    private void reopenThroughProductionHelper() {
        helper.close();
        helper = new DatabaseHelper(context, "schema33-test.db", user -> 0L, () -> { });
        db = helper.getWritableDatabase();
    }

    private void assertFavorite(int id, String title, int organizerLockState) {
        try (Cursor cursor = db.rawQuery(
                "SELECT title, organizerLockState FROM favorites WHERE _id=?",
                new String[] { String.valueOf(id) })) {
            assertTrue(cursor.moveToFirst());
            assertEquals(title, cursor.getString(0));
            assertEquals(organizerLockState, cursor.getInt(1));
        }
    }

    private static boolean hasOrganizerLockColumn(SQLiteDatabase database) {
        try (Cursor columns = database.rawQuery("PRAGMA table_info(favorites)", null)) {
            while (columns.moveToNext()) {
                if (LauncherSettings.Favorites.ORGANIZER_LOCK_STATE.equals(
                        columns.getString(columns.getColumnIndexOrThrow("name")))) {
                    return true;
                }
            }
            return false;
        }
    }

}
