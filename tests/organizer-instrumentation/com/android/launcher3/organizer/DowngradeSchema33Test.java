package com.android.launcher3.organizer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.R;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.DbDowngradeHelper;
import com.android.launcher3.model.GridMigrationTestSupport;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DowngradeSchema33Test {
    @Test public void productionRecipeRebuilds33To32WithoutLosingRows() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        GridMigrationTestSupport.deleteDatabase(context, "organizer-downgrade.db");
        try (DatabaseHelper helper = new DatabaseHelper(
                    context, "organizer-downgrade.db", user -> 0L, () -> { });
             InputStream schema = context.getResources().openRawResource(R.raw.downgrade_schema)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues row = new ContentValues();
            row.put(Favorites._ID, 1); row.put(Favorites.TITLE, "kept");
            row.put(Favorites.ORGANIZER_LOCK_STATE, 2);
            db.insertOrThrow(Favorites.TABLE_NAME, null, row);
            // Issue #118: the caller owns the downgrade transaction.
            try (SQLiteTransaction t = new SQLiteTransaction(db)) {
                DbDowngradeHelper.parse(schema.readAllBytes()).onDowngrade(db, 33, 32);
                t.commit();
            }
            try (Cursor c = db.rawQuery("PRAGMA table_info(favorites)", null)) {
                boolean lock = false;
                while (c.moveToNext()) lock |= Favorites.ORGANIZER_LOCK_STATE.equals(c.getString(1));
                assertFalse(lock);
            }
            try (Cursor c = db.rawQuery("SELECT title FROM favorites WHERE _id=1", null)) {
                assertTrue(c.moveToFirst()); assertTrue("kept".equals(c.getString(0)));
            }
        } finally {
            GridMigrationTestSupport.deleteDatabase(context, "organizer-downgrade.db");
        }
    }
}
