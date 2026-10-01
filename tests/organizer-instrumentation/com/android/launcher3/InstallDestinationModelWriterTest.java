// Issue #497: production-seam tests for the destination-policy write
// (ADR-0013 target (b) / ADR-0015 Decisions 7-11), the canonical surface per
// ADR-0016. Mirrors DirectEditModelWriterTest: the real ModelWriter op runs
// through MODEL_WRITER admission, the stage-2 validator, the DB INSERT, and
// the live BgDataModel/FolderInfo synchronization against an isolated test DB.
package com.android.launcher3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.AppFilter;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.celllayout.CellPosMapper;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.DirectEditContract;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.ModelWriter;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.PackageManagerHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InstallDestinationModelWriterTest {

    private static final String TEST_DB = "install-destination-model-writer-test.db";
    private static final long TIMEOUT_MS = 10_000;

    private Context mContext;
    private TestDbController mController;
    private BgDataModel mBgDataModel;
    private ModelWriter mWriter;

    /** Routes ModelWriter to the isolated test DB instead of the real one. */
    static class TestLauncherModel extends LauncherModel {
        private final ModelDbController mTestController;

        TestLauncherModel(Context context, LauncherAppState app, ModelDbController controller) {
            super(context, app, app.getIconCache(), new AppFilter(context),
                    new PackageManagerHelper(context), false);
            mTestController = controller;
        }

        @Override
        public ModelDbController getModelDbController() {
            return mTestController;
        }
    }

    private static class TestDbController extends ModelDbController {
        private final Context mContext;

        TestDbController(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            return new DatabaseHelper(mContext, TEST_DB, user -> 0L, () -> { });
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new TestDbController(mContext);
        mController.getDb();
        mBgDataModel = new BgDataModel();
        // LauncherAppState asserts UI thread and registers process callbacks;
        // build it once on the main thread like a real launch would.
        final AtomicReference<LauncherAppState> appRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> appRef.set(LauncherAppState.getInstance(mContext)));
        TestLauncherModel model = new TestLauncherModel(mContext, appRef.get(), mController);
        mWriter = new ModelWriter(mContext, model, mBgDataModel, /* verifyChanges= */ false,
                CellPosMapper.DEFAULT, /* owner= */ null);
    }

    @After
    public void tearDown() {
        if (mController != null) {
            mController.closeActiveHelperForRestore();
        }
        mContext.deleteDatabase(TEST_DB);
    }

    // --- helpers ---

    private WorkspaceItemInfo newPendingPayload() {
        WorkspaceItemInfo payload = new WorkspaceItemInfo();
        // id == NO_ID until the admitted task allocates one (contract 4: no
        // id allocation before admission).
        payload.itemType = Favorites.ITEM_TYPE_APPLICATION;
        payload.container = Favorites.CONTAINER_DESKTOP;
        payload.screenId = 0;
        payload.cellX = 0;
        payload.cellY = 0;
        payload.spanX = 1;
        payload.spanY = 1;
        payload.user = Process.myUserHandle();
        return payload;
    }

    private FolderInfo seedFolder(int id, int screenId, int cellX, int cellY) {
        FolderInfo folder = new FolderInfo();
        folder.id = id;
        folder.itemType = Favorites.ITEM_TYPE_FOLDER;
        folder.container = Favorites.CONTAINER_DESKTOP;
        folder.screenId = screenId;
        folder.cellX = cellX;
        folder.cellY = cellY;
        folder.spanX = 1;
        folder.spanY = 1;
        folder.user = Process.myUserHandle();
        mBgDataModel.itemsIdMap.put(folder.id, folder);
        mBgDataModel.collections.put(folder.id, folder);
        mBgDataModel.workspaceItems.add(folder);
        insertRow(id, Favorites.CONTAINER_DESKTOP, screenId, cellX, cellY, 0,
                Favorites.ITEM_TYPE_FOLDER);
        return folder;
    }

    private WorkspaceItemInfo seedAppItem(int id, int container, int screenId,
            int cellX, int cellY, int rank) {
        WorkspaceItemInfo item = new WorkspaceItemInfo();
        item.id = id;
        item.itemType = Favorites.ITEM_TYPE_APPLICATION;
        item.container = container;
        item.screenId = screenId;
        item.cellX = cellX;
        item.cellY = cellY;
        item.spanX = 1;
        item.spanY = 1;
        item.rank = rank;
        item.user = Process.myUserHandle();
        mBgDataModel.itemsIdMap.put(item.id, item);
        if (container == Favorites.CONTAINER_DESKTOP || container == Favorites.CONTAINER_HOTSEAT) {
            mBgDataModel.workspaceItems.add(item);
        }
        insertRow(id, container, screenId, cellX, cellY, rank, Favorites.ITEM_TYPE_APPLICATION);
        return item;
    }

    private void insertRow(int id, int container, int screenId, int cellX, int cellY, int rank,
            int itemType) {
        SQLiteDatabase db = mController.getDb();
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.ITEM_TYPE, itemType);
        values.put(Favorites.CONTAINER, container);
        values.put(Favorites.SCREEN, screenId);
        values.put(Favorites.CELLX, cellX);
        values.put(Favorites.CELLY, cellY);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.RANK, rank);
        db.insertWithOnConflict(Favorites.TABLE_NAME, null, values, 5 /* IGNORE */);
    }

    private int queryInt(int id, String column) {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT " + column + " FROM " + Favorites.TABLE_NAME
                + " WHERE " + Favorites._ID + "=?",
                new String[]{String.valueOf(id)})) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private int countRows() {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + Favorites.TABLE_NAME, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private void awaitCallback(CountDownLatch latch) throws InterruptedException {
        assertTrue("destination write did not finish in " + TIMEOUT_MS + "ms",
                latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private static DirectEditContract.DestinationDecision folderDecision(int folderId) {
        return DirectEditContract.DestinationDecision.folder(folderId);
    }

    // --- folder target: one INSERT at tail rank, lock column untouched ---

    @Test
    public void folderTargetInsertAppendsAtTailRankWithoutLockColumnChange() throws Exception {
        FolderInfo folder = seedFolder(510, 0, 0, 4);
        seedAppItem(501, 510, 0, -1, -1, 0);
        WorkspaceItemInfo payload = newPendingPayload();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<DirectEditContract.DestinationDecision> seen = new AtomicReference<>();
        AtomicReference<int[]> result = new AtomicReference<>();
        mWriter.addPendingInstallForDirectEdit(payload,
                current -> {
                    DirectEditContract.DestinationDecision d = folderDecision(510);
                    seen.set(d);
                    return d;
                },
                (success, container, screenId, cellX, cellY, rank, newScreenId, reason) -> {
                    assertTrue(success);
                    result.set(new int[]{container, screenId, rank, newScreenId});
                    done.countDown();
                });
        awaitCallback(done);

        assertNotNull("stage-2 validator must run inside admission", seen.get());
        int insertedId = payload.id;
        assertTrue("id must be allocated inside admission", insertedId > 0);
        // DB row: one INSERT into the folder at the tail rank.
        assertEquals(510, queryInt(insertedId, Favorites.CONTAINER));
        assertEquals(0, queryInt(insertedId, Favorites.SCREEN));
        assertEquals(-1, queryInt(insertedId, Favorites.CELLX));
        assertEquals(1, queryInt(insertedId, Favorites.RANK));
        assertEquals("folder + seed child + new child only", 3, countRows());
        // Existing child's rank untouched (ADR-0015 Decision 6).
        assertEquals(0, queryInt(501, Favorites.RANK));
        // Lock column never written by the direct-edit path (ADR-0013).
        assertNull(queryLockColumn(insertedId));
        // Live model: contents appended, id allocated, callback placement.
        assertTrue(folder.getContents().contains(payload));
        assertEquals(510, payload.container);
        assertEquals(1, payload.rank);
        assertEquals(510, result.get()[0]);
        assertEquals(DirectEditContract.DEST_NO_SCREEN_ID, result.get()[3]);
    }

    private String queryLockColumn(int id) {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT " + Favorites.ORGANIZER_LOCK_STATE
                + " FROM " + Favorites.TABLE_NAME + " WHERE " + Favorites._ID + "=?",
                new String[]{String.valueOf(id)})) {
            assertTrue(c.moveToFirst());
            return c.isNull(0) ? null : c.getString(0);
        }
    }

    // --- upstream default: finder placement inside admission ---

    @Test
    public void upstreamDefaultWritesAtUpstreamPlacementInsideAdmission() throws Exception {
        seedFolder(510, 0, 0, 4);
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 0, 1, 0);
        WorkspaceItemInfo payload = newPendingPayload();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<int[]> result = new AtomicReference<>();
        AtomicReference<String> reason = new AtomicReference<>();
        mWriter.addPendingInstallForDirectEdit(payload,
                current -> DirectEditContract.DestinationDecision.upstreamDefault(
                        DirectEditContract.DEST_FOLDER_MISSING),
                (success, container, screenId, cellX, cellY, rank, newScreenId, why) -> {
                    assertTrue(success);
                    result.set(new int[]{container, screenId, cellX, cellY, newScreenId});
                    reason.set(why);
                    done.countDown();
                });
        awaitCallback(done);

        // Fallback reason travels to the callback (record + one-shot notice).
        assertEquals(DirectEditContract.DEST_FOLDER_MISSING, reason.get());
        assertEquals(Favorites.CONTAINER_DESKTOP, (int) result.get()[0]);
        // Placement is in-grid and free of the seeded cell (upstream finder
        // semantics; a fork-side scan duplication would drift from this).
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        assertTrue(result.get()[1] >= 0);
        assertTrue(result.get()[2] >= 0 && result.get()[2] < idp.numColumns);
        assertTrue(result.get()[3] >= 0 && result.get()[3] < idp.numRows);
        assertFalse("must not overlap the seeded cell",
                result.get()[1] == 0 && result.get()[3] == 1);
        assertEquals(DirectEditContract.DEST_NO_SCREEN_ID, (int) result.get()[4]);
        assertEquals(Favorites.CONTAINER_DESKTOP, queryInt(payload.id, Favorites.CONTAINER));
        assertTrue(mBgDataModel.itemsIdMap.get(payload.id) == payload);
        assertTrue(mBgDataModel.workspaceItems.contains(payload));
    }

    // --- ADR-0015 required-test row 1: defer → folder deleted → replan ---

    @Test
    public void deferredWriteReplansToUpstreamDefaultWhenFolderDeleted() throws Exception {
        FolderInfo folder = seedFolder(510, 0, 0, 4);
        WorkspaceItemInfo payload = newPendingPayload();
        int payloadCellY = payload.cellY;
        String payloadIntent = payload.intent == null ? null : payload.intent.toUri(0);

        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        AtomicReference<LayoutWriteCoordinator.Lease> lease =
                new AtomicReference<>(coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER));
        assertNotNull(lease.get());

        AtomicBoolean validatorRan = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<int[]> result = new AtomicReference<>();
        AtomicReference<String> reason = new AtomicReference<>();
        mWriter.addPendingInstallForDirectEdit(payload,
                current -> {
                    validatorRan.set(true);
                    // Stage 2 inside admission, after the lease released: the
                    // "organizer apply" removed the designated folder, so the
                    // same pure plan re-runs to a valid upstream-default plan.
                    if (mBgDataModel.collections.get(510) == null) {
                        return DirectEditContract.DestinationDecision.upstreamDefault(
                                DirectEditContract.DEST_FOLDER_MISSING);
                    }
                    return folderDecision(510);
                },
                (success, container, screenId, cellX, cellY, rank, newScreenId, why) -> {
                    assertTrue(success);
                    result.set(new int[]{container, newScreenId});
                    reason.set(why);
                    done.countDown();
                });

        // While deferred: no admission, no payload mutation, no callback.
        assertFalse("task must stay deferred", done.await(300, TimeUnit.MILLISECONDS));
        assertFalse(validatorRan.get());
        assertEquals(ItemInfo.NO_ID, payload.id);
        assertEquals(payloadCellY, payload.cellY);
        assertEquals(payloadIntent,
                payload.intent == null ? null : payload.intent.toUri(0));
        assertEquals(0, countRows());

        // The "organizer apply" deletes the designated folder while the lease
        // is held. Raw SQL on purpose: a controller delete would take the
        // tokenless MODEL_WRITER lane and queue behind our own deferred task.
        mController.getDb().delete(Favorites.TABLE_NAME, Favorites._ID + "=" + 510, null);
        mBgDataModel.collections.remove(510);
        mBgDataModel.itemsIdMap.remove(510);
        mBgDataModel.workspaceItems.remove(folder);

        lease.get().close();
        lease.set(null);
        awaitCallback(done);

        assertTrue(validatorRan.get());
        // Re-planned to a valid upstream-default write, not a failure.
        assertEquals(DirectEditContract.DEST_FOLDER_MISSING, reason.get());
        assertEquals(Favorites.CONTAINER_DESKTOP, (int) result.get()[0]);
        assertTrue("row must be written for the re-planned default", countRows() == 1);
        assertTrue(payload.id > 0);
    }

    // --- full-screens fallback: new screen allocated inside admission ---

    @Test
    public void fullScreensFallbackAllocatesNewScreenInsideAdmission() throws Exception {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        int cols = idp.numColumns;
        int rows = idp.numRows;
        // Fill BOTH seeded screens completely so the upstream finder must
        // allocate a new screen regardless of the QSB first-screen exclusion.
        // The seeded screen ids sit far away from what the process's real
        // launcher DB (which the finder consults for the new screen id) can
        // return on a fresh emulator, so the allocated id cannot collide with
        // a seeded screen's occupied cells.
        int nextId = 500;
        for (int screen : new int[]{100, 101}) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    seedAppItem(nextId++, Favorites.CONTAINER_DESKTOP, screen, x, y, 0);
                }
            }
        }
        WorkspaceItemInfo payload = newPendingPayload();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<int[]> result = new AtomicReference<>();
        mWriter.addPendingInstallForDirectEdit(payload,
                current -> DirectEditContract.DestinationDecision.upstreamDefault(
                        DirectEditContract.DEST_FOLDER_MISSING),
                (success, container, screenId, cellX, cellY, rank, newScreenId, why) -> {
                    assertTrue(success);
                    result.set(new int[]{container, screenId, cellX, cellY, newScreenId});
                    done.countDown();
                });
        awaitCallback(done);

        // The new screen id was allocated inside admission and reported for
        // the single post-success bind (page exists before the icon binds).
        int newScreen = result.get()[4];
        assertTrue("a new screen must be allocated when all screens are full",
                newScreen != DirectEditContract.DEST_NO_SCREEN_ID);
        assertTrue("the allocated screen must be a fresh one",
                newScreen != 100 && newScreen != 101);
        assertEquals(Favorites.CONTAINER_DESKTOP, (int) result.get()[0]);
        assertEquals(newScreen, queryInt(payload.id, Favorites.SCREEN));
    }

    // --- reject: invariant failure writes nothing ---

    @Test
    public void rejectWritesNothingAndReportsTypedFailure() throws Exception {
        seedFolder(510, 0, 0, 4);
        WorkspaceItemInfo payload = newPendingPayload();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        mWriter.addPendingInstallForDirectEdit(payload,
                current -> DirectEditContract.DestinationDecision.reject(
                        DirectEditContract.DEST_SNAPSHOT_INVALID),
                (success, container, screenId, cellX, cellY, rank, newScreenId, why) -> {
                    assertFalse(success);
                    failure.set(why);
                    done.countDown();
                });
        awaitCallback(done);

        assertEquals(DirectEditContract.DEST_SNAPSHOT_INVALID, failure.get());
        assertEquals("reject must not write", 1, countRows()); // folder only
        assertEquals("payload id must stay unallocated", ItemInfo.NO_ID, payload.id);
        assertFalse(mBgDataModel.itemsIdMap.containsKey(payload.id));
    }
}
