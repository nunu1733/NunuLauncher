package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.launcher3.provider.RestoreDbTask;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RestoreProfileRemapTest {
    @Test public void profileRemapPreservesLockAndDeletionRemovesUnavailableProfile() {
        File f = ApplicationProvider.getApplicationContext().getDatabasePath("profile-remap.db"); f.delete();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null)) {
            db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, profileId INTEGER, organizerLockState INTEGER)");
            db.execSQL("INSERT INTO favorites VALUES (1,10,2),(2,11,1)");
            new TestRestoreDbTask().migrate(db, 10, 20);
            db.delete("favorites", "profileId=?", new String[]{"11"});
            try (Cursor c = db.rawQuery("SELECT profileId,organizerLockState,count(*) FROM favorites", null)) {
                c.moveToFirst(); assertEquals(20, c.getInt(0)); assertEquals(2, c.getInt(1)); assertEquals(1, c.getInt(2));
            }
        } finally { f.delete(); }
    }

    private static final class TestRestoreDbTask extends RestoreDbTask {
        void migrate(SQLiteDatabase db, long from, long to) {
            migrateProfileId(db, from, to);
        }
    }
}
