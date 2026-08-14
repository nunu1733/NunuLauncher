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
public class InactiveGridDbNormalizationTest {
    @Test public void schema32RowsNormalizeToUnknownBeforeCopy() {
        File f = ApplicationProvider.getApplicationContext().getDatabasePath("inactive-grid.db"); f.delete();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null)) {
            db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY)");
            db.execSQL("INSERT INTO favorites VALUES (1)");
            GridSizeMigrationUtil.ensureOrganizerLockColumn(db);
            try (Cursor c = db.rawQuery("SELECT organizerLockState FROM favorites", null)) {
                c.moveToFirst(); assertEquals(0, c.getInt(0));
            }
        } finally { f.delete(); }
    }
}
