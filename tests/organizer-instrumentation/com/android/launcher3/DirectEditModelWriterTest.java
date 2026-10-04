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
        public int update(ContentValues values, String selection,
                String[] selectionArgs) {
            if (failOnUpdate) {
                throw new IllegalStateException("injected update failure");
            }
            return super.update(values, selection, selectionArgs);
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new FailableController(mContext);
        mController.getDb();
        mBgDataModel = ModelWriterTestSupport.createBgDataModel(mContext);
        // Issue #532 rebase: the anchor LauncherModel is final and injects its
        // DB controller, so the model is built with the isolated FailableController.
        LauncherModel model = ModelWriterTestSupport.createIsolatedModel(
                mContext, mController, mBgDataModel);
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
        // Issue #532 rebase: the anchor model carries every item in itemsIdMap;
        // the fork-side workspaceItems index is gone (the container field owns
        // the surface membership the old index tracked).
        mBgDataModel.addItem(mContext, item, null);
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
        mBgDataModel.addItem(mContext, folder, null);
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
        // Issue #532 rebase: leaving the workspace surface is carried by the
        // live item's container (the folder id), not a workspaceItems index.
        assertFalse("item must leave the workspace surface",
                item.container == Favorites.CONTAINER_DESKTOP
                        || item.container == Favorites.CONTAINER_HOTSEAT);
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
        // Live model: the new folder is in the model items map, item inside contents.
        ItemInfo collection = mBgDataModel.itemsIdMap.get(folderId);
        assertNotNull("created folder must join the live model", collection);
        assertTrue(collection instanceof FolderInfo);
        FolderInfo folder = (FolderInfo) collection;
        assertTrue(folder.getContents().contains(item));
        assertEquals(folderId, item.container);
        assertFalse("item must leave the workspace surface",
                item.container == Favorites.CONTAINER_DESKTOP
                        || item.container == Favorites.CONTAINER_HOTSEAT);
        assertTrue(mBgDataModel.itemsIdMap.get(folderId) == folder);
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
        // Live model unchanged: the seeded item is still the only model object
        // (no folder joined the model) and still points at the desktop cell.
        assertEquals("no folder object may join the live model", 1, modelItemCount());
        assertTrue(mBgDataModel.itemsIdMap.get(501) == item);
        assertEquals(Favorites.CONTAINER_DESKTOP, item.container);
        assertEquals(1, item.screenId);
        assertEquals(0, item.cellX);
        assertEquals(4, item.cellY);
        assertEquals(0, item.rank);
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
        // Issue #532 rebase: workspaceItems/collections are gone; the live
        // model membership is itemsIdMap, and the survivor keeps its surface
        // placement via the container field.
        assertNull("removed item must leave the live model", mBgDataModel.itemsIdMap.get(501));
        assertTrue("survivor must stay in the live model",
                mBgDataModel.itemsIdMap.get(502) == survivor);
        assertEquals(Favorites.CONTAINER_DESKTOP, survivor.container);
        assertFalse(mBgDataModel.itemsIdMap.get(502) == victim);
    }

    private int modelItemCount() {
        int count = 0;
        for (ItemInfo ignored : mBgDataModel.itemsIdMap) {
            count++;
        }
        return count;
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
