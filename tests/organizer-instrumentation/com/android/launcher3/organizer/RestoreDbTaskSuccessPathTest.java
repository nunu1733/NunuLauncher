// Issue #532 rebase Phase 2 / G4 T4 added oracle (#522 assessment §test表): the
// RestoreDbTask.performRestore SUCCESS entry asserted against a real schema-33
// database. Ownership boundary: RestoreLeaseSerializationTest owns the lease
// serialization and the failure surface (sanitization failure == false),
// RestoreProfileRemapTest owns the migrateProfileId method behavior on a bare
// fixture, and ModelWriterTransactionReentryTest owns the MODEL_WRITER
// transaction reentry. No existing class proves that the full production
// success entry — lease acquisition, transaction, profile inventory, surviving
// serial remap, vanished-profile deletion, restored flagging, default-column
// rebuild, screen-gap compaction and the widget-id pref remap — commits one
// consistent DB and returns true.
package com.android.launcher3.organizer;

import static android.os.Process.myUserHandle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.provider.RestoreDbTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RestoreDbTaskSuccessPathTest {

    // G4 E1 adjust (assert contract unchanged, precondition made emulator-reproducible):
    // the fixture simulates an old device whose default profile serial was not this
    // device's live main-user serial. With the previous default of 0 the guard
    // `assertNotEquals(OLD_DEFAULT_PROFILE_ID, serial)` was unsatisfiable on a standard
    // emulator (main-user serial is 0 there), so the profile remap and the
    // default-column rebuild could only run as no-ops. Seeding the old device default
    // as 10 exercises the same production paths (migrateProfileId +
    // changeDefaultColumn) on any device, and the guard keeps asserting exactly the
    // remap precondition: the live serial must differ from the old-device default.
    private static final long OLD_DEFAULT_PROFILE_ID = 10L;
    private static final long VANISHED_PROFILE_ID = 777L;
    private static final int OLD_WIDGET_ID = 42;
    private static final int NEW_WIDGET_ID = 43;

    private Context mContext;
    private SuccessPathController mController;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(SuccessPathController.TEST_DB_NAME);
        mController = new SuccessPathController(mContext);
        // Real schema-33 helper: favorites with the production column set.
        SQLiteDatabase db = mController.getDb();
        assertEquals(33, db.getVersion());

        // Simulate the old device's favorites default profile id (the same table
        // rebuild the production changeDefaultColumn performs), so the restore
        // sanitize sees oldProfileId != the live serial.
        db.execSQL("ALTER TABLE favorites RENAME TO favorites_old");
        Favorites.addTableToDb(db, OLD_DEFAULT_PROFILE_ID, false /* optional */);
        db.execSQL("INSERT INTO favorites SELECT * FROM favorites_old");
        db.execSQL("DROP TABLE favorites_old");

        // Surviving app row on screen 0, carrying an organizer lock that must
        // travel through the full restore entry untouched.
        insertFavorite(db, 1, OLD_DEFAULT_PROFILE_ID, Favorites.ITEM_TYPE_APPLICATION,
                0 /* screen */, 2 /* lockState */);
        // Surviving app row on screen 4 so {0, 4} exercises the screen-gap
        // compaction of the single-display restore path.
        insertFavorite(db, 2, OLD_DEFAULT_PROFILE_ID, Favorites.ITEM_TYPE_APPLICATION,
                4 /* screen */, 1 /* lockState (default unlocked) */);
        // Row of a profile that was never restored: no ancestral serial mapping
        // exists on this device, so the sanitize must delete it.
        insertFavorite(db, 3, VANISHED_PROFILE_ID, Favorites.ITEM_TYPE_APPLICATION,
                0 /* screen */, 1 /* lockState */);
        // Widget row seeded so the widget-id pref remap finds it: restored bit 0
        // set (the remap WHERE clause requires it) and the old widget id.
        ContentValues widget = baseValues(4, OLD_DEFAULT_PROFILE_ID,
                Favorites.ITEM_TYPE_APPWIDGET, 0 /* screen */);
        widget.put(Favorites.APPWIDGET_ID, OLD_WIDGET_ID);
        widget.put(Favorites.APPWIDGET_PROVIDER, "com.example.t4/.BoundaryWidget");
        widget.put(Favorites.RESTORED, 1);
        assertTrue(db.insertOrThrow(Favorites.TABLE_NAME, null, widget) >= 0);
    }

    @After
    public void tearDown() {
        // performRestore does not consume the pending flag (restoreIfNeeded
        // does); never leak it into the shared instrumentation process.
        LauncherPrefs.get(mContext).removeSync(LauncherPrefs.RESTORE_DEVICE);
        LauncherPrefs.get(mContext).removeSync(LauncherPrefs.APP_WIDGET_IDS);
        LauncherPrefs.get(mContext).removeSync(LauncherPrefs.OLD_APP_WIDGET_IDS);
        if (mController != null) {
            mController.closeActiveHelperForRestore();
        }
        mContext.deleteDatabase(SuccessPathController.TEST_DB_NAME);
    }

    @Test
    public void performRestoreCommitsSanitizedDbAndReturnsTrue() {
        // The remap inputs: widget id old -> new map and the pending flag that
        // keeps restoreAppWidgetIds allowed to touch the DB.
        LauncherPrefs.get(mContext).putSync(
                LauncherPrefs.OLD_APP_WIDGET_IDS.to(Integer.toString(OLD_WIDGET_ID)));
        LauncherPrefs.get(mContext).putSync(
                LauncherPrefs.APP_WIDGET_IDS.to(Integer.toString(NEW_WIDGET_ID)));
        LauncherPrefs.get(mContext).putSync(
                LauncherPrefs.RESTORE_DEVICE.to(InvariantDeviceProfile.TYPE_PHONE));

        assertTrue("performRestore must report success on a valid schema-33 DB",
                RestoreDbTask.performRestore(mContext, mController));

        long serial = mController.getSerialNumberForUser(myUserHandle());
        assertNotEquals("test requires the live main-user serial to differ from the seeded"
                        + " old-device default, otherwise the profile remap is a no-op",
                OLD_DEFAULT_PROFILE_ID, serial);
        SQLiteDatabase db = mController.getDb();

        // 1. Profile inventory: the vanished profile is deleted, every
        //    surviving row is remapped to the new main-user serial.
        assertEquals(3, countRows(db, null));
        assertEquals(0, countRows(db, Favorites.PROFILE_ID + "=?",
                Long.toString(VANISHED_PROFILE_ID)));
        assertEquals(3, countRows(db, Favorites.PROFILE_ID + "=?", Long.toString(serial)));

        // 2. Surviving lock state travels with the remap through the full
        //    entry (end-state observation; the migrate method boundary itself
        //    is owned by RestoreProfileRemapTest).
        assertEquals(2, intField(db, 1, Favorites.ORGANIZER_LOCK_STATE));

        // 3. App rows are marked as restored icons.
        long appFlags = longField(db, 1, Favorites.RESTORED);
        assertTrue((appFlags & WorkspaceItemInfo.FLAG_RESTORED_ICON) != 0);

        // 4. Screen-gap compaction rewrote screen 4 -> 1 (single-display
        //    restore: RESTORE_DEVICE != TYPE_MULTI_DISPLAY).
        assertEquals(1, intField(db, 2, Favorites.SCREEN));

        // 5. Widget remap: pref old->new applied to the matched row, invalid-id
        //    flag replaced by PROVIDER_NOT_READY (no live provider for the
        //    unallocated id), provider string intact.
        assertEquals(NEW_WIDGET_ID, intField(db, 4, Favorites.APPWIDGET_ID));
        assertEquals(LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY,
                intField(db, 4, Favorites.RESTORED));
        assertEquals("com.example.t4/.BoundaryWidget",
                stringField(db, 4, Favorites.APPWIDGET_PROVIDER));

        // 6. The default column was rebuilt with the new serial
        //    (changeDefaultColumn runs because serial != old default 10).
        assertEquals(serial, queryProfileIdDefault(db));

        // 7. Entry-boundary pref contract: performRestore consumed the widget
        //    id map but leaves the pending flag to restoreIfNeeded.
        assertFalse(LauncherPrefs.get(mContext).has(
                LauncherPrefs.APP_WIDGET_IDS, LauncherPrefs.OLD_APP_WIDGET_IDS));
        assertTrue(RestoreDbTask.isPending(mContext));

        // 8. The success path unwinds its lease view exactly once: the
        //    coordinator is free for a MODEL_WRITER afterwards.
        try (LayoutWriteCoordinator.Lease writer =
                LayoutWriteCoordinator.getInstance()
                        .tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("success path must release the restore lease", writer);
        }
    }

    // -- helpers --

    private static void insertFavorite(SQLiteDatabase db, long id, long profileId, int itemType,
            int screen, int lockState) {
        ContentValues values = baseValues(id, profileId, itemType, screen);
        values.put(Favorites.ORGANIZER_LOCK_STATE, lockState);
        assertTrue("row " + id + " inserted",
                db.insertOrThrow(Favorites.TABLE_NAME, null, values) >= 0);
    }

    private static ContentValues baseValues(long id, long profileId, int itemType, int screen) {
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.TITLE, "t4_row_" + id);
        values.put(Favorites.INTENT, "#Intent;end");
        values.put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
        values.put(Favorites.SCREEN, screen);
        values.put(Favorites.CELLX, 0);
        values.put(Favorites.CELLY, 0);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.ITEM_TYPE, itemType);
        values.put(Favorites.PROFILE_ID, profileId);
        return values;
    }

    private static int countRows(SQLiteDatabase db, String where, String... args) {
        try (Cursor c = db.query(Favorites.TABLE_NAME,
                new String[] {Favorites._ID}, where, args, null, null, null)) {
            return c.getCount();
        }
    }

    private static int intField(SQLiteDatabase db, long id, String column) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] {column},
                Favorites._ID + "=?", new String[] {Long.toString(id)}, null, null, null)) {
            assertTrue("row " + id + " exists", c.moveToFirst());
            return c.getInt(0);
        }
    }

    private static long longField(SQLiteDatabase db, long id, String column) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] {column},
                Favorites._ID + "=?", new String[] {Long.toString(id)}, null, null, null)) {
            assertTrue("row " + id + " exists", c.moveToFirst());
            return c.getLong(0);
        }
    }

    private static String stringField(SQLiteDatabase db, long id, String column) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] {column},
                Favorites._ID + "=?", new String[] {Long.toString(id)}, null, null, null)) {
            assertTrue("row " + id + " exists", c.moveToFirst());
            return c.getString(0);
        }
    }

    /** Reads the profileId column default (sanitizeDB's getDefaultProfileId seam). */
    private static long queryProfileIdDefault(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("PRAGMA table_info (favorites)", null)) {
            int nameIndex = c.getColumnIndex("name");
            int defaultIndex = c.getColumnIndex("dflt_value");
            while (c.moveToNext()) {
                if (Favorites.PROFILE_ID.equals(c.getString(nameIndex))) {
                    return Long.parseLong(c.getString(defaultIndex));
                }
            }
        }
        throw new AssertionError("favorites table has no profileId column");
    }

    /**
     * Test double that serves an isolated real schema-33 database while
     * skipping createDbIfNotExists's automatic pending-restore callback
     * consumption: the test drives the performRestore entry explicitly, so
     * getDb must not run restoreIfNeeded as a side effect of the first open.
     */
    private static final class SuccessPathController extends ModelDbController {
        static final String TEST_DB_NAME = "restore-success-path.db";

        private final Context mContext;

        SuccessPathController(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            return new DatabaseHelper(mContext, TEST_DB_NAME, user -> 0L, () -> { });
        }

        @Override
        public SQLiteDatabase getDb() {
            if (mOpenHelper == null) {
                mOpenHelper = createDatabaseHelper(false /* forMigration */);
            }
            return mOpenHelper.getWritableDatabase();
        }
    }
}
