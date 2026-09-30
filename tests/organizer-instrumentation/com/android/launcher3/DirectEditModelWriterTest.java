// Issue #448: production-seam tests for the ModelWriter direct-edit
// operations. Unlike DirectEditWriteShapeTest (which pins the raw write
// shapes), these drive the real public methods through admission, the
// stage-2 validator, the DB writes, and the live BgDataModel/FolderInfo
// synchronization, per the accepted spec's AC-2/3/4/7 write oracles.
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

import com.android.launcher3.celllayout.CellPosMapper;
import com.android.launcher3.LauncherSettings.Favorites;
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
public class DirectEditModelWriterTest {

    private static final String TEST_DB = "direct-edit-model-writer-test.db";
    private static final long TIMEOUT_MS = 10_000;

    private Context mContext;
    private FailableController mController;
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

    private static class FailableController extends ModelDbController {
        private final Context mContext;
        volatile boolean failOnUpdate;

        FailableController(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            return new DatabaseHelper(mContext, TEST_DB, user -> 0L, () -> { });
        }

        @Override
        public int update(String table, ContentValues values, String selection,
                String[] selectionArgs) {
            if (failOnUpdate) {
                throw new IllegalStateException("injected update failure");
            }
            return super.update(table, values, selection, selectionArgs);
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new FailableController(mContext);
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
        insertRow(id, container, screenId, cellX, cellY, rank);
        return item;
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
        insertRow(id, Favorites.CONTAINER_DESKTOP, screenId, cellX, cellY, 0);
        return folder;
    }

    private void insertRow(int id, int container, int screenId, int cellX, int cellY, int rank) {
        SQLiteDatabase db = mController.getDb();
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.ITEM_TYPE,
                container > 0 ? Favorites.ITEM_TYPE_FOLDER : Favorites.ITEM_TYPE_APPLICATION);
        values.put(Favorites.CONTAINER, container);
        values.put(Favorites.SCREEN, screenId);
        values.put(Favorites.CELLX, cellX);
        values.put(Favorites.CELLY, cellY);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.RANK, rank);
        db.insertWithOnConflict(Favorites.TABLE_NAME, null, values, 5 /* IGNORE */);
    }

    private DirectEditContract.Validator proceed() {
        return current -> DirectEditContract.Decision.proceed();
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

    private int countFolders() {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + Favorites.TABLE_NAME
                + " WHERE " + Favorites.ITEM_TYPE + "=" + Favorites.ITEM_TYPE_FOLDER, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private void awaitCallback(CountDownLatch latch) throws InterruptedException {
        assertTrue("direct edit did not finish in " + TIMEOUT_MS + "ms",
                latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    // --- (a) existing-folder add: DB + live model + FolderInfo.contents ---

    @Test
    public void existingFolderAddUpdatesDbAndModelState() throws Exception {
        WorkspaceItemInfo item = seedAppItem(501, Favorites.CONTAINER_HOTSEAT, 2, 2, 0, 2);
        FolderInfo folder = seedFolder(510, 0, 0, 4);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<DirectEditContract.Decision> seen = new AtomicReference<>();
        mWriter.moveItemForDirectEdit(501, 510, 0, -1, -1, 1,
                current -> {
                    DirectEditContract.Decision d = DirectEditContract.Decision.proceed();
                    seen.set(d);
                    return d;
                },
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created, removedRow) -> {
                    assertTrue(success);
                    assertEquals(0, folderId);
                    done.countDown();
                });
        awaitCallback(done);

        assertNotNull("stage-2 validator must run inside admission", seen.get());
        // DB row moved into the folder.
        assertEquals(510, queryInt(501, Favorites.CONTAINER));
        assertEquals(0, queryInt(501, Favorites.SCREEN));
        assertEquals(-1, queryInt(501, Favorites.CELLX));
        assertEquals(1, queryInt(501, Favorites.RANK));
        // Live model state: container synced, contents appended, workspace
        // membership cleaned (updateItemArrays path).
        assertEquals(510, item.container);
        assertEquals(1, item.rank);
        assertTrue("FolderInfo.contents must contain the moved item",
                folder.getContents().contains(item));
        assertFalse("item must leave workspaceItems",
                mBgDataModel.workspaceItems.contains(item));
        assertTrue(mBgDataModel.itemsIdMap.get(501) == item);
    }

    // --- (b) create-folder: success updates everything; failure changes nothing ---

    @Test
    public void createFolderSuccessUpdatesDbCollectionsAndContents() throws Exception {
        WorkspaceItemInfo item = seedAppItem(501, Favorites.CONTAINER_DESKTOP, 1, 0, 4, 0);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Integer> createdId = new AtomicReference<>();
        mWriter.createFolderAndMoveForDirectEdit(501, 1, 0, 0, proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created, removedRow) -> {
                    assertTrue(success);
                    createdId.set(folderId);
                    done.countDown();
                });
        awaitCallback(done);

        int folderId = createdId.get();
        assertTrue(folderId > 0);
        assertEquals(1, countFolders());
        assertEquals(folderId, queryInt(501, Favorites.CONTAINER));
        assertEquals(0, queryInt(501, Favorites.RANK));
        // Live model: the new folder is in collections, item inside contents.
        FolderInfo folder = (FolderInfo) mBgDataModel.collections.get(folderId);
        assertNotNull("created folder must join the model collections", folder);
        assertTrue(folder.getContents().contains(item));
        assertEquals(folderId, item.container);
        assertFalse(mBgDataModel.workspaceItems.contains(item));
        assertTrue(mBgDataModel.workspaceItems.contains(folder));
    }

    @Test
    public void createFolderFailureLeavesModelAndDbAtOldPlacement() throws Exception {
        WorkspaceItemInfo item = seedAppItem(501, Favorites.CONTAINER_DESKTOP, 1, 0, 4, 0);
        mController.failOnUpdate = true;

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        mWriter.createFolderAndMoveForDirectEdit(501, 1, 0, 0, proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created, removedRow) -> {
                    assertFalse(success);
                    failure.set(reason);
                    done.countDown();
                });
        awaitCallback(done);
        mController.failOnUpdate = false;

        assertEquals(DirectEditContract.FAIL_WRITE_FAILED, failure.get());
        // DB rolled back: no folder row, child at the old placement.
        assertEquals(0, countFolders());
        assertEquals(Favorites.CONTAINER_DESKTOP, queryInt(501, Favorites.CONTAINER));
        assertEquals(1, queryInt(501, Favorites.SCREEN));
        assertEquals(0, queryInt(501, Favorites.CELLX));
        assertEquals(4, queryInt(501, Favorites.CELLY));
        assertEquals(0, queryInt(501, Favorites.RANK));
        // Live model unchanged: the item still points at the desktop cell.
        assertEquals(Favorites.CONTAINER_DESKTOP, item.container);
        assertEquals(1, item.screenId);
        assertEquals(0, item.cellX);
        assertEquals(4, item.cellY);
        assertEquals(0, item.rank);
        assertEquals(0, mBgDataModel.collections.size());
        assertTrue(mBgDataModel.workspaceItems.contains(item));
    }

    /**
     * ADR-0013 required-test table, "admission後の再検証（defer後のstale検証）"
     * row, through the production task: while an organizer lease is held the
     * direct-edit move defers with no model/DB change; a competing writer
     * moves the target; after release the task's stage-2 validator rejects
     * the moved target and nothing is written.
     */
    @Test
    public void deferredMoveRejectsStaleTargetWithoutWrite() throws Exception {
        WorkspaceItemInfo item = seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 0, 4, 0);

        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        AtomicReference<LayoutWriteCoordinator.Lease> lease =
                new AtomicReference<>(coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER));
        assertNotNull(lease.get());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 0, 0, 1, 0,
                current -> {
                    // Stage 2 inside admission: the competing writer below has
                    // moved the target, so the snapshot no longer matches the
                    // stage-1 placement and the write must be rejected.
                    ItemInfo target = mBgDataModel.itemsIdMap.get(501);
                    if (target == null || target.cellY != 4) {
                        return DirectEditContract.Decision.reject(DirectEditContract.FAIL_STALE);
                    }
                    return DirectEditContract.Decision.proceed();
                },
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created, removedRow) -> {
                    assertFalse(success);
                    failure.set(reason);
                    done.countDown();
                });

        // While deferred: no callback, no model/DB change.
        assertFalse("task must stay deferred", done.await(300, TimeUnit.MILLISECONDS));
        assertEquals(4, queryInt(501, Favorites.CELLY));
        assertEquals(4, item.cellY);

        // A competing writer moves the target while the lease is held. Raw
        // SQL on purpose: a controller update here would take the tokenless
        // MODEL_WRITER lane, queue behind our own deferred task behind the
        // organizer lease, and deadlock the test thread.
        item.cellY = 2;
        ContentValues moved = new ContentValues();
        moved.put(Favorites.CELLY, 2);
        mController.getDb().update(Favorites.TABLE_NAME, moved,
                Favorites._ID + "=" + 501, null);

        lease.get().close();
        lease.set(null);
        awaitCallback(done);
        assertEquals(DirectEditContract.FAIL_STALE, failure.get());
        // Nothing written on top: the DB keeps the competing writer's state.
        assertEquals(2, queryInt(501, Favorites.CELLY));
        assertEquals(2, item.cellY);
    }

    // --- (c) remove: exactly the target row ---

    @Test
    public void removeDeletesOnlyTargetRowAndCleansModel() throws Exception {
        WorkspaceItemInfo victim = seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 0, 4, 0);
        WorkspaceItemInfo survivor = seedAppItem(502, Favorites.CONTAINER_DESKTOP, 0, 1, 4, 0);

        CountDownLatch done = new CountDownLatch(1);
        mWriter.removeItemForDirectEdit(501, proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created, removedRow) -> {
                    assertTrue(success);
                    done.countDown();
                });
        awaitCallback(done);

        assertTrue("removed row must be gone", rowMissing(501));
        assertEquals(Favorites.CONTAINER_DESKTOP, queryInt(502, Favorites.CONTAINER));
        assertFalse(mBgDataModel.itemsIdMap.containsKey(501));
        assertTrue(mBgDataModel.itemsIdMap.containsKey(502));
        assertFalse(mBgDataModel.workspaceItems.contains(victim));
        assertTrue(mBgDataModel.workspaceItems.contains(survivor));
    }

    private boolean rowMissing(int id) {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + Favorites.TABLE_NAME
                + " WHERE " + Favorites._ID + "=?", new String[]{String.valueOf(id)})) {
            c.moveToFirst();
            return c.getInt(0) == 0;
        }
    }

    // --- (d) the production task defers behind an organizer lease ---

    @Test
    public void directEditMoveDefersUntilOrganizerLeaseReleases() throws Exception {
        WorkspaceItemInfo item = seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 0, 4, 0);

        AtomicBoolean validatorRan = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        AtomicReference<LayoutWriteCoordinator.Lease> lease =
                new AtomicReference<>(coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER));
        assertNotNull(lease.get());

        mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 0, 0, 1, 0,
                current -> {
                    validatorRan.set(true);
                    return DirectEditContract.Decision.proceed();
                },
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created, removedRow) ->
                        done.countDown());

        assertFalse("task must stay deferred while the organizer lease is held",
                done.await(300, TimeUnit.MILLISECONDS));
        assertFalse(validatorRan.get());

        lease.get().close();
        lease.set(null);
        awaitCallback(done);
        assertTrue(validatorRan.get());
        assertEquals(1, queryInt(501, Favorites.CELLY));
        assertEquals(1, item.cellY);
    }

}
