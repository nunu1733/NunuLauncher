// Issue #118 (PR #122 review): evidence for the REAL 33->32 downgrade path.
//
// The field rollback from a schema-33 install to a schema-32 install is
// executed by the schema-32 binary: SQLiteOpenHelper sees file version
// 33 > bundled 32 and runs that binary's onDowngrade inside its own
// framework transaction. Schema32RollbackDatabaseHelper /
// Schema32RollbackDbDowngradeHelper are verbatim copies of that binary's
// source (commit 866d231ffdfe2dcc8b0e550e65ea6f1301b6674c), so opening a
// seeded schema-33 database through the replica exercises the actual
// production rollback path end to end — including the real
// SQLiteOpenHelper wrapping, not a manual re-creation of it.
//
// One field-fidelity detail is reproduced deliberately: the rollback target
// parses apps/files/downgrade_schema.json, and on a real device that file
// was written by the newer (schema-33) binary, whose updateSchemaFile keeps
// any parsed file whose version >= its own. Seeding through the current
// helper materializes exactly that v33 recipe file before the replica runs.
//
// Spec: specs/118-sqlite-migration-transaction-audit/spec.md (AC-4).
package com.android.launcher3.organizer.rollback32;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.GridMigrationTestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class Schema32RollbackBinaryTest {

    private static final String TEST_DB = "schema32-rollback-target-test.db";

    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        GridMigrationTestSupport.deleteDatabase(mContext, TEST_DB);
    }

    @After
    public void tearDown() {
        GridMigrationTestSupport.deleteDatabase(mContext, TEST_DB);
    }

    @Test
    public void successful33To32RollbackReachesVersion32PreservingRows() {
        seedSchema33WithRow(21, "rollback survivor");

        // Opening the v33 file through the schema-32 replica triggers its
        // onDowngrade(33, 32) under the real framework transaction.
        try (Schema32RollbackDatabaseHelper helper = new Schema32RollbackDatabaseHelper(
                mContext, TEST_DB, user -> 0L, () -> { })) {
            SQLiteDatabase db = helper.getWritableDatabase();

            assertEquals(32, db.getVersion());
            assertEquals("the rebuild recipe must carry layout rows down",
                    1, countFavorites(db));
            assertFalse("committed table must have the schema-32 shape",
                    hasColumn(db, Favorites.ORGANIZER_LOCK_STATE));
        }
    }

    @Test
    public void failingRecipeOnRealRollbackBinaryLeavesFileUnDowngraded() {
        // Documents the installed rollback target's failure semantics instead
        // of a desired outcome: its pre-#118 catch-and-wipe cannot persist,
        // because its nested scope poisons the framework transaction and the
        // whole unit rolls back. Deterministic recovery begins with binaries
        // built after #118 (see MigrationTransactionOwnershipTest).
        seedSchema33WithRow(22, "rollback failure row");
        try (SQLiteDatabase raw = openRaw()) {
            raw.execSQL("CREATE TABLE temp_favorites(_id INTEGER PRIMARY KEY)");
        }

        try (Schema32RollbackDatabaseHelper helper = new Schema32RollbackDatabaseHelper(
                mContext, TEST_DB, user -> 0L, () -> { })) {
            SQLiteDatabase db = helper.getWritableDatabase();

            assertEquals("the historical open reports success without recovering",
                    33, db.getVersion());
            assertEquals("the pre-migration row survives the rolled-back unit",
                    1, countFavorites(db));
            assertTrue(hasColumn(db, Favorites.ORGANIZER_LOCK_STATE));
            assertTrue(tableExists(db, "temp_favorites"));
        }

        // Re-check the committed file state through a fresh handle.
        try (SQLiteDatabase raw = openRaw()) {
            assertEquals(33, raw.getVersion());
            assertEquals(1, countFavorites(raw));
        }
    }

    // -- helpers --

    private SQLiteDatabase openRaw() {
        return SQLiteDatabase.openDatabase(
                mContext.getDatabasePath(TEST_DB).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
    }

    private void seedSchema33WithRow(int id, String title) {
        try (DatabaseHelper helper = new DatabaseHelper(
                mContext, TEST_DB, user -> 0L, () -> { })) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues row = new ContentValues();
            row.put(Favorites._ID, id);
            row.put(Favorites.TITLE, title);
            row.put(Favorites.ORGANIZER_LOCK_STATE, 2);
            assertTrue(db.insertOrThrow(Favorites.TABLE_NAME, null, row) >= 0);
        }
    }

    private static int countFavorites(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + Favorites.TABLE_NAME, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "PRAGMA table_info(" + Favorites.TABLE_NAME + ")", null)) {
            while (c.moveToNext()) {
                if (name.equals(c.getString(c.getColumnIndexOrThrow("name")))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM main.sqlite_master"
                        + " WHERE type='table' AND name='" + name + "'", null)) {
            c.moveToFirst();
            return c.getInt(0) > 0;
        }
    }
}
