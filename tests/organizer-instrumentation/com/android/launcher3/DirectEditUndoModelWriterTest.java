/*
 * Issue #450: undo write-path tests on the real ModelWriter seam (ADR-0013
 * contract 5, spec 450 AC-2/3/4 + payload oracle). Sibling of
 * DirectEditModelWriterTest: isolated favorites DB, real admission, real
 * model sync. Covers the inverse operations' round-trips, the one-transaction
 * folder undo, the remove payload capture fidelity, the availability
 * fail-closed rejection, and the stage-2 stale zero-write path.
 */
package com.android.launcher3;

import static org.junit.Assert.assertEquals;
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
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.celllayout.CellPosMapper;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.DirectEditContract;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.ModelWriter;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.util.PackageManagerHelper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DirectEditUndoModelWriterTest {

    private static final String TEST_DB = "direct-edit-undo-model-writer-test.db";
    private static final long TIMEOUT_MS = 10_000;

    private Context mContext;
    private TestController mController;
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

    private static class TestController extends ModelDbController {
        private final Context mContext;
        volatile boolean failOnDelete;

        TestController(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            return new DatabaseHelper(mContext, TEST_DB, user -> 0L, () -> { });
        }

        @Override
        public int delete(String table, String selection, String[] selectionArgs) {
            if (failOnDelete) {
                throw new IllegalStateException("injected delete failure");
            }
            return super.delete(table, selection, selectionArgs);
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new TestController(mContext);
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

    // --- (a) move undo: round-trip back to the recorded placement ---

    @Test
    public void restorePlacementWritesTheRecordedOldPlacement() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);

        // Move to page 1 (row-major first free cell mirrors the popup path).
        awaitCallback(latch -> mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 1, 0, 0, 0,
                proceed(), done(latch, true, null)));
        assertEquals(1, queryInt(501, Favorites.SCREEN));

        // Undo: restore the recorded old placement.
        awaitCallback(latch -> mWriter.restorePlacementForDirectEdit(501,
                Favorites.CONTAINER_DESKTOP, 0, 1, 2, 1, 1, 0,
                proceed(), done(latch, true, null)));
        assertEquals(0, queryInt(501, Favorites.SCREEN));
        assertEquals(1, queryInt(501, Favorites.CELLX));
        assertEquals(2, queryInt(501, Favorites.CELLY));
        assertNotNull(modelItemById(501));
    }

    // --- (b) remove + restore: payload capture fidelity and inverse INSERT ---

    @Test
    public void removeCapturesTheRowAndRestoreReinsertsItVerbatim() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);
        String title = queryText(501, Favorites.TITLE);
        String intent = queryText(501, Favorites.INTENT);
        int lockState = queryInt(501, Favorites.ORGANIZER_LOCK_STATE);

        AtomicReference<DirectEditContract.UndoRowPayload> captured = new AtomicReference<>();
        CountDownLatch removed = new CountDownLatch(1);
        mWriter.removeItemForDirectEdit(501, proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created,
                        removedRow) -> {
                    assertTrue(success);
                    captured.set(removedRow);
                    removed.countDown();
                });
        assertTrue(removed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNull("row is gone after the remove", modelItemById(501));

        DirectEditContract.UndoRowPayload payload = captured.get();
        assertNotNull("remove must capture the pre-DELETE row", payload);
        assertEquals(501, payload.itemId);
        assertEquals(Favorites.ITEM_TYPE_APPLICATION, payload.itemType);
        assertEquals(1, payload.cellX);
        assertEquals(2, payload.cellY);
        assertEquals(lockState, payload.organizerLockState);
        assertNotNull("availability identity captured for an app row", payload.componentName);
        assertEquals(title, payload.title);
        assertEquals(intent, payload.intent);

        // Undo: the inverse INSERT restores the row content verbatim with a
        // freshly allocated id (the old id may already be reused by SQLite).
        int[] newId = new int[1];
        CountDownLatch restored = new CountDownLatch(1);
        mWriter.restoreRemovedItemForDirectEdit(payload, proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created,
                        removedRow) -> {
                    assertTrue(success);
                    newId[0] = id;
                    restored.countDown();
                });
        assertTrue(restored.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(newId[0] > 0);
        assertEquals(Favorites.ITEM_TYPE_APPLICATION, queryInt(newId[0], Favorites.ITEM_TYPE));
        assertEquals(1, queryInt(newId[0], Favorites.CELLX));
        assertEquals(2, queryInt(newId[0], Favorites.CELLY));
        assertEquals(lockState, queryInt(newId[0], Favorites.ORGANIZER_LOCK_STATE));
        assertEquals(title, queryText(newId[0], Favorites.TITLE));
        assertEquals(intent, queryText(newId[0], Favorites.INTENT));
        ItemInfo modelItem = modelItemById(newId[0]);
        assertNotNull("restored row is synced into the live model", modelItem);
    }

    // --- (c) stage-2 stale: the recorded precondition no longer holds ---

    @Test
    public void restorePlacementRejectsStaleWithoutWrite() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);
        awaitCallback(latch -> mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 1, 0, 0, 0,
                proceed(), done(latch, true, null)));

        CountDownLatch rejected = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>("none");
        mWriter.restorePlacementForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 1, 1, 0,
                current -> DirectEditContract.Decision.reject(DirectEditContract.FAIL_UNDO_STALE),
                (id, success, failure, oc, os, ox, oy, osx, osy, orank, folderId, created,
                        removedRow) -> {
                    reason.set(failure);
                    rejected.countDown();
                });
        assertTrue(rejected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(DirectEditContract.FAIL_UNDO_STALE, reason.get());
        assertEquals(1, queryInt(501, Favorites.SCREEN));
    }

    // --- (d) folder undo: one transaction (child restore + folder delete) ---

    @Test
    public void undoCreateFolderRestoresChildAndDeletesFolderInOneTransaction() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 2);

        awaitCallback(latch -> mWriter.createFolderAndMoveForDirectEdit(501, 0, 0, 0,
                proceed(), done(latch, true, null)));
        int folderId = queryInt(501, Favorites.CONTAINER);
        assertTrue(folderId > 0);
        assertEquals(Favorites.ITEM_TYPE_FOLDER, queryInt(folderId, Favorites.ITEM_TYPE));

        awaitCallback(latch -> mWriter.undoCreateFolderForDirectEdit(501, folderId,
                Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 1, 1, 2,
                proceed(), done(latch, true, null)));
        assertEquals(Favorites.CONTAINER_HOTSEAT, queryInt(501, Favorites.CONTAINER));
        assertEquals(3, queryInt(501, Favorites.CELLX));
        assertEquals(2, queryInt(501, Favorites.RANK));
        assertEquals(0, countRows(folderId));
        assertNull("folder row removed from the live model", modelItemById(folderId));
        assertNotNull(modelItemById(501));
    }

    @Test
    public void undoCreateFolderRejectsInsideAdmissionWithZeroWrite() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 2);
        awaitCallback(latch -> mWriter.createFolderAndMoveForDirectEdit(501, 0, 0, 0,
                proceed(), done(latch, true, null)));
        int folderId = queryInt(501, Favorites.CONTAINER);

        // The stage-2 validator rejects inside admission: zero writes.
        awaitCallback(latch -> mWriter.undoCreateFolderForDirectEdit(501, folderId,
                Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 1, 1, 2,
                current -> DirectEditContract.Decision.reject(
                        DirectEditContract.FAIL_UNDO_FOLDER_CHANGED),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, fid, created,
                        removedRow) -> {
                    assertEquals(false, success);
                    assertEquals(DirectEditContract.FAIL_UNDO_FOLDER_CHANGED, reason);
                    assertNull(removedRow);
                    latch.countDown();
                }));
        // Zero writes: the child is still in the folder and the folder row exists.
        assertEquals(folderId, queryInt(501, Favorites.CONTAINER));
        assertEquals(Favorites.ITEM_TYPE_FOLDER, queryInt(folderId, Favorites.ITEM_TYPE));
    }

    // --- (d2) AC-3: the folder undo is one transaction — an injected delete
    // failure between the child restore and the folder delete rolls both back ---

    @Test
    public void undoCreateFolderRollsBackBothWritesWhenTheDeleteFails() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 2);
        awaitCallback(latch -> mWriter.createFolderAndMoveForDirectEdit(501, 0, 0, 0,
                proceed(), done(latch, true, null)));
        int folderId = queryInt(501, Favorites.CONTAINER);

        // Inject the failure into the second write (folder DELETE): the child
        // UPDATE and the folder DELETE must both roll back (contract 3).
        mController.failOnDelete = true;
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>("none");
        mWriter.undoCreateFolderForDirectEdit(501, folderId,
                Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 1, 1, 2,
                proceed(),
                (id, success, failure, oc, os, ox, oy, osx, osy, orank, fid, created,
                        removedRow) -> {
                    reason.set(failure);
                    failed.countDown();
                });
        assertTrue(failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mController.failOnDelete = false;

        assertEquals(DirectEditContract.FAIL_UNDO_WRITE_FAILED, reason.get());
        // DB rolled back: the child is still in the folder, the folder row exists.
        assertEquals(folderId, queryInt(501, Favorites.CONTAINER));
        assertEquals(Favorites.ITEM_TYPE_FOLDER, queryInt(folderId, Favorites.ITEM_TYPE));
        // Live model unchanged: the folder is still a collection holding the item.
        synchronized (mBgDataModel) {
            FolderInfo folder =
                    mBgDataModel.collections.get(folderId) instanceof FolderInfo f ? f : null;
            assertNotNull("folder must remain in the model after rollback", folder);
            assertTrue(folder.getContents().stream().anyMatch(i -> i.id == 501));
        }
    }

    // --- (d3) AC-4: an undo submitted during an ORGANIZER lease defers and
    // re-validates inside admission after the lease is released ---

    @Test
    public void undoDefersDuringOrganizerLeaseAndRevalidatesAfterRelease() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);
        awaitCallback(latch -> mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 1, 0, 0, 0,
                proceed(), done(latch, true, null)));

        // Hold the ORGANIZER lease while the undo is submitted: the undo must
        // not run until the lease is released. The deferred undo's callback
        // records its post-release outcome.
        CountDownLatch undoDone = new CountDownLatch(1);
        AtomicReference<Boolean> undoSuccess = new AtomicReference<>(null);
        try (com.android.launcher3.model.LayoutWriteCoordinator.Lease lease =
                com.android.launcher3.model.LayoutWriteCoordinator.getInstance()
                        .tryAcquire(com.android.launcher3.model.LayoutWriteCoordinator.OwnerKind.ORGANIZER)) {
            assertNotNull(lease);
            mWriter.restorePlacementForDirectEdit(501,
                    Favorites.CONTAINER_DESKTOP, 0, 1, 2, 1, 1, 0,
                    proceed(),
                    (id, success, reason, oc, os, ox, oy, osx, osy, orank, fid, created,
                            removedRow) -> {
                        undoSuccess.set(success);
                        undoDone.countDown();
                    });
            // Give the deferred undo no time to run while the lease is held:
            // a successful run here would mean the undo bypassed the lease.
            Thread.sleep(300);
            assertEquals(
                    "undo must defer while the ORGANIZER lease is held",
                    1, queryInt(501, Favorites.SCREEN));
        }
        // After the lease is released the deferred undo runs its stage-2
        // re-validation and completes.
        assertTrue("deferred undo did not finish after lease release",
                undoDone.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        // The first undo (deferred above) restored the row; the recorded
        // outcome is a success and the DB/model agree.
        assertEquals(Boolean.TRUE, undoSuccess.get());
        assertEquals(0, queryInt(501, Favorites.SCREEN));
    }

    // --- (e) availability fail-closed: a gone launch target is never resurrected ---

    @Test
    public void restoreRemovedRejectsWithZeroWriteWhenTheStage2ValidatorRejectsAvailability()
            throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);
        AtomicReference<DirectEditContract.UndoRowPayload> captured = new AtomicReference<>();
        CountDownLatch removed = new CountDownLatch(1);
        mWriter.removeItemForDirectEdit(501, proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created,
                        removedRow) -> {
                    captured.set(removedRow);
                    removed.countDown();
                });
        assertTrue(removed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // The fork-side validator re-verifies availability inside admission
        // (the same production verifier as stage 1) and rejects a gone launch
        // target with the typed key: zero writes, no resurrection.
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>("none");
        mWriter.restoreRemovedItemForDirectEdit(captured.get(),
                current -> DirectEditContract.Decision.reject(
                        DirectEditContract.FAIL_UNDO_ITEM_UNAVAILABLE),
                (id, success, failure, oc, os, ox, oy, osx, osy, orank, folderId, created,
                        removedRow) -> {
                    reason.set(failure);
                    rejected.countDown();
                });
        assertTrue(rejected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(DirectEditContract.FAIL_UNDO_ITEM_UNAVAILABLE, reason.get());
        assertEquals(0, countRows(501));
    }

    // --- helpers ---

    private interface Callback1 {
        void call(CountDownLatch latch) throws Exception;
    }

    private void awaitCallback(Callback1 body) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        body.call(latch);
        assertTrue("undo did not finish in " + TIMEOUT_MS + "ms",
                latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private DirectEditContract.Validator proceed() {
        return current -> DirectEditContract.Decision.proceed();
    }

    private DirectEditContract.ResultCallback done(
            CountDownLatch latch, boolean expectSuccess, String expectReason) {
        return (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created,
                removedRow) -> {
            assertEquals(expectSuccess, success);
            if (expectReason != null) {
                assertEquals(expectReason, reason);
            }
            latch.countDown();
        };
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
        insertRow(id, container, screenId, cellX, cellY, rank);
        return item;
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
        values.put(Favorites.TITLE, "undo fixture " + id);
        values.put(
                Favorites.INTENT,
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        .setComponent(new android.content.ComponentName(
                                mContext.getPackageName(), Launcher.class.getName()))
                        .toUri(0));
        db.insertWithOnConflict(Favorites.TABLE_NAME, null, values, 5 /* IGNORE */);
    }

    private ItemInfo modelItemById(int id) {
        synchronized (mBgDataModel) {
            return mBgDataModel.itemsIdMap.get(id);
        }
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

    private String queryText(int id, String column) {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT " + column + " FROM " + Favorites.TABLE_NAME
                + " WHERE " + Favorites._ID + "=?",
                new String[]{String.valueOf(id)})) {
            c.moveToFirst();
            return c.getString(0);
        }
    }

    private int countRows(int id) {
        SQLiteDatabase db = mController.getDb();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + Favorites.TABLE_NAME
                + " WHERE " + Favorites._ID + "=?",
                new String[]{String.valueOf(id)})) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }
}
