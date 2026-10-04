package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.DbDowngradeHelper;
import com.android.launcher3.model.GridMigrationTestSupport;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.R;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import java.io.File;
import java.io.InputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InactiveGridDbNormalizationTest {
    private File inactiveGrid;
    private File activeGrid;
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        inactiveGrid = context.getDatabasePath(
                "issue-59-inactive-grid.db");
        activeGrid = context.getDatabasePath(
                "issue-59-active-grid.db");
        GridMigrationTestSupport.deleteDatabase(context, inactiveGrid.getName());
        GridMigrationTestSupport.deleteDatabase(context, activeGrid.getName());
    }

    @After
    public void tearDown() {
        GridMigrationTestSupport.deleteDatabase(context, inactiveGrid.getName());
        GridMigrationTestSupport.deleteDatabase(context, activeGrid.getName());
    }

    @Test
    public void inactiveSchema32NormalizationDoesNotRewriteSchema33Source() throws Exception {
        try (DatabaseHelper inactive = new DatabaseHelper(context, inactiveGrid.getName(), user -> 0L, () -> { });
                DatabaseHelper active = new DatabaseHelper(context, activeGrid.getName(), user -> 0L, () -> { })) {
            insertFavorite(inactive.getWritableDatabase(), 1, 2);
            try (InputStream schema = context.getResources().openRawResource(R.raw.downgrade_schema)) {
                // Issue #118: the caller owns the downgrade transaction.
                try (SQLiteTransaction t = new SQLiteTransaction(inactive.getWritableDatabase())) {
                    DbDowngradeHelper.parse(schema.readAllBytes()).onDowngrade(
                            inactive.getWritableDatabase(), 33, 32);
                    t.commit();
                }
            }
            inactive.getWritableDatabase().setVersion(32);
            insertFavorite(active.getWritableDatabase(), 1, 2);
            insertFavorite(active.getWritableDatabase(), 2, 1);
        }
        try (DatabaseHelper upgradedInactive = new DatabaseHelper(
                context, inactiveGrid.getName(), user -> 0L, () -> { });
                DatabaseHelper active = new DatabaseHelper(context, activeGrid.getName(), user -> 0L, () -> { })) {
            assertEquals(33, upgradedInactive.getWritableDatabase().getVersion());
            assertLockStates(upgradedInactive.getWritableDatabase(), 0);
            assertLockStates(active.getWritableDatabase(), 2, 1);
        }
    }

    private static void assertLockStates(SQLiteDatabase db, int... expected) {
        try (Cursor cursor = db.rawQuery(
                "SELECT organizerLockState FROM favorites ORDER BY _id", null)) {
            for (int state : expected) {
                org.junit.Assert.assertTrue("Expected a row with lock state " + state,
                        cursor.moveToNext());
                assertEquals("Schema-33 source lock state must remain unchanged", state,
                        cursor.getInt(0));
            }
            org.junit.Assert.assertFalse("Unexpected extra favorite row", cursor.moveToNext());
        }
    }

    private static void insertFavorite(SQLiteDatabase db, int id, int lockState) {
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.TITLE, "Issue 59 " + id);
        values.put(Favorites.ORGANIZER_LOCK_STATE, lockState);
        db.insertOrThrow(Favorites.TABLE_NAME, null, values);
    }

}
