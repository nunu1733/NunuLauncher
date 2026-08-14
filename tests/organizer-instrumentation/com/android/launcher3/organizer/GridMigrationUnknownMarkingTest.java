package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.launcher3.model.GridSizeMigrationUtil;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GridMigrationUnknownMarkingTest {
    @Test public void targetWideUnknownUpdateIsTransactional() {
        File f = ApplicationProvider.getApplicationContext().getDatabasePath("grid-target.db"); f.delete();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null)) {
            db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, organizerLockState INTEGER NOT NULL)");
            db.execSQL("INSERT INTO favorites VALUES (1,1),(2,2)");
            db.beginTransaction(); try { GridSizeMigrationUtil.markOrganizerLocksUnknown(db); db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
            try (Cursor c = db.rawQuery("SELECT count(*) FROM favorites WHERE organizerLockState<>0", null)) {
                c.moveToFirst(); assertEquals(0, c.getInt(0));
            }
        } finally { f.delete(); }
    }
}
