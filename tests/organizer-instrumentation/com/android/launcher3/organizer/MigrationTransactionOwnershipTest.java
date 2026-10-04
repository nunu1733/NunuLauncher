// Issue #118: deterministic transaction ownership inside SQLiteOpenHelper
// migration callbacks. Spec: specs/118-sqlite-migration-transaction-audit/spec.md
//
// The framework wraps onUpgrade/onDowngrade in one transaction whose nesting is
// pure bookkeeping without SAVEPOINTs: a nested scope that ends unsuccessful
// poisons every ancestor and forces the outermost end to roll back even after
// setTransactionSuccessful(). The scenarios below reproduce the historical
// defect (catch-and-fallback silently rolls back, database left at the old
// version while the open reports success) and pin the remediated behavior.
//
// Scope note (review P1): the downgrade scenarios drive this tree's helper
// through the framework-style wrapper, which is production-faithful for any
// rollback executed by a binary built from this tree (e.g. a future schema
// 34->33 rollback). A rollback from 33 to 32 in the field is instead executed
// by an already-built schema-32 binary that bundles the pre-#118 helper; that
// binary's behavior is out of scope per the spec's non-goals.
package com.android.launcher3.organizer;

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
public class MigrationTransactionOwnershipTest {

    private static final String TEST_DB = "migration-transaction-owner-test.db";

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
    public void legacyUpgradeFailureFallsBackToWipeThatPersists() throws Exception {
        // Seed a schema-33 file database with one user row.
        seedFreshSchema33WithRow(7, "legacy row");

        // Rewind the file to version 13 and drop only appWidgetProvider so the
        // replayed case 13 succeeds by re-adding it while case 14's
        // addIntegerColumn("modified") fails on the still-present column.
        try (SQLiteDatabase raw = openRaw()) {
            raw.execSQL("ALTER TABLE favorites DROP COLUMN appWidgetProvider");
            raw.setVersion(13);
        }

        // Production open path: SQLiteOpenHelper wraps onUpgrade in one
        // transaction. The historical wipe fallback must persist instead of
        // being rolled back by a poisoned outer transaction.
        try (DatabaseHelper helper = new DatabaseHelper(
                mContext, TEST_DB, user -> 0L, () -> { })) {
            SQLiteDatabase db = helper.getWritableDatabase();
            assertEquals(33, db.getVersion());
            assertEquals("wipe fallback must persist", 0, countFavorites(db));
        }

        // The committed file state is a fresh current-shape schema-33 database.
        try (SQLiteDatabase raw = openRaw()) {
            assertEquals(33, raw.getVersion());
            assertEquals(0, countFavorites(raw));
            assertTrue(hasColumn(raw, "organizerLockState"));
        }
    }

    @Test
    public void downgradeStatementFailureFallsBackToWipeThatPersists() throws Exception {
        // Seed a schema-33 file database with one user row. Opening the helper
        // also materializes the downgrade schema file used by onDowngrade.
        seedFreshSchema33WithRow(11, "downgrade row");

        // Block the first 33->32 rebuild statement (RENAME to temp_favorites)
        // so the recipe fails after having begun. The staged table name comes
        // from res/raw/downgrade_schema.json and must not survive the fallback.
        try (SQLiteDatabase raw = openRaw()) {
            raw.execSQL("CREATE TABLE temp_favorites(_id INTEGER PRIMARY KEY)");
        }

        DatabaseHelper helper = new DatabaseHelper(mContext, TEST_DB, user -> 0L, () -> { });
        try (SQLiteDatabase raw = openRaw()) {
            // Mirror SQLiteOpenHelper.getDatabaseLocked: one transaction around
            // the callback, version bump and success marker only after it
            // returns normally.
            raw.beginTransaction();
            try {
                helper.onDowngrade(raw, 33, 32);
                raw.setVersion(32);
                raw.setTransactionSuccessful();
            } finally {
                raw.endTransaction();
            }

            assertEquals(32, raw.getVersion());
            assertEquals(
                    "caught downgrade failure must fall back to a persisted wipe",
                    0, countFavorites(raw));
            assertFalse("staged migration table must not survive the wipe",
                    tableExists(raw, "temp_favorites"));
        } finally {
            helper.close();
        }
    }

    @Test
    public void downgradePartialRecipeProgressStillEndsInDeterministicFreshWipe()
            throws Exception {
        // Review P2: cover a failure AFTER the recipe made progress. The
        // 33->22 statement chain concatenates every per-version block, so
        // blocking only the downgrade_to_27 CREATE TABLE workspaceScreens
        // lets the 32, 31, and 28 blocks fully rebuild favorites (carrying
        // the prior layout row) before any statement fails.
        seedFreshSchema33WithRow(12, "partial recipe row");
        try (SQLiteDatabase raw = openRaw()) {
            raw.execSQL("CREATE TABLE workspaceScreens(_id INTEGER PRIMARY KEY)");
        }

        DatabaseHelper helper = new DatabaseHelper(mContext, TEST_DB, user -> 0L, () -> { });
        try (SQLiteDatabase raw = openRaw()) {
            raw.beginTransaction();
            try {
                helper.onDowngrade(raw, 33, 22);
                raw.setVersion(22);
                raw.setTransactionSuccessful();
            } finally {
                raw.endTransaction();
            }

            assertEquals(22, raw.getVersion());
            assertEquals(
                    "rows copied by the partial rebuild must not leak past the wipe",
                    0, countFavorites(raw));
            assertFalse("recipe staging table must be gone after the wipe",
                    tableExists(raw, "temp_favorites"));
            assertFalse("sabotaged leftover table must not survive the wipe",
                    tableExists(raw, "workspaceScreens"));
        } finally {
            helper.close();
        }
    }

    // -- helpers --

    private SQLiteDatabase openRaw() {
        return SQLiteDatabase.openDatabase(
                mContext.getDatabasePath(TEST_DB).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
    }

    private void seedFreshSchema33WithRow(int id, String title) {
        try (DatabaseHelper helper = new DatabaseHelper(
                mContext, TEST_DB, user -> 0L, () -> { })) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues row = new ContentValues();
            row.put(Favorites._ID, id);
            row.put(Favorites.TITLE, title);
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

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM main.sqlite_master"
                        + " WHERE type='table' AND name='" + name + "'", null)) {
            c.moveToFirst();
            return c.getInt(0) > 0;
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + Favorites.TABLE_NAME + ")", null)) {
            while (c.moveToNext()) {
                if (name.equals(c.getString(c.getColumnIndexOrThrow("name")))) {
                    return true;
                }
            }
            return false;
        }
    }
}
