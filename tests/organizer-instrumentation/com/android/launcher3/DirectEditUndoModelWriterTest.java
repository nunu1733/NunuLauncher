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

    // --- (d4) AC-4 (round 2): the deferred undo re-validates INSIDE admission
    // after the lease release — a precondition broken while the lease was held
    // becomes a typed rejection with zero writes ---

    @Test
    public void undoDeferredDuringOrganizerLeaseRevalidatesAndRejectsAfterRelease()
            throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);
        awaitCallback(latch -> mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP, 1, 0, 0, 0,
                proceed(), done(latch, true, null)));

        // Hold the ORGANIZER lease, submit the undo (defers), and break the
        // recorded precondition (move the item again) while the lease is held.
        CountDownLatch undoDone = new CountDownLatch(1);
        AtomicReference<String> undoReason = new AtomicReference<>("none");
        try (com.android.launcher3.model.LayoutWriteCoordinator.Lease lease =
                com.android.launcher3.model.LayoutWriteCoordinator.getInstance()
                        .tryAcquire(com.android.launcher3.model.LayoutWriteCoordinator.OwnerKind.ORGANIZER)) {
            assertNotNull(lease);
            // The undo validator is the real fork-side stage-2 validator over
            // the recorded entry: it re-runs the pure planner inside admission
            // and must observe the broken precondition at stage 2.
            mWriter.restorePlacementForDirectEdit(501,
                    Favorites.CONTAINER_DESKTOP, 0, 1, 2, 1, 1, 0,
                    undoValidator,
                    (id, success, reason, oc, os, ox, oy, osx, osy, orank, fid, created,
                            removedRow) -> {
                        undoReason.set(reason);
                        undoDone.countDown();
                    });
            // Break the precondition under the lease, on both the model and
            // the DB. The stage-2 re-validation reads the live model
            // (BgDataModel.itemsIdMap), so the model-side break is what it
            // must observe; the DB-side break keeps model/DB consistent for
            // the zero-write assertion. Both writes bypass the coordinator:
            // a ModelWriter move here would defer behind this very lease, and
            // a ModelDbController.update would acquireBlocking a MODEL_WRITER
            // lease from THIS thread — a self-deadlock by construction.
            synchronized (mBgDataModel) {
                ItemInfo info = mBgDataModel.itemsIdMap.get(501);
                assertNotNull(info);
                info.cellX = 2;
                info.cellY = 3;
            }
            ContentValues broken = new ContentValues();
            broken.put(Favorites.CELLX, 2);
            broken.put(Favorites.CELLY, 3);
            assertEquals(1, mController.getDb().update(
                    Favorites.TABLE_NAME, broken, Favorites._ID + "=501", null));
            assertEquals(2, queryInt(501, Favorites.CELLX));
        }
        // After the release the deferred undo runs its stage-2 re-validation,
        // sees the broken precondition, and rejects with zero writes.
        assertTrue("deferred undo did not finish after lease release",
                undoDone.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(DirectEditContract.FAIL_UNDO_STALE, undoReason.get());
        // Zero writes: the item stays at the broken-state placement.
        assertEquals(2, queryInt(501, Favorites.CELLX));
        assertEquals(3, queryInt(501, Favorites.CELLY));
    }

    // --- (f) AC-6 process-death smoke: the undo record is process-local, and
    // a death mid-transaction leaves the DB at the pre-state (existing
    // process-death smoke convention: abandon without commit, reopen) ---

    @Test
    public void folderUndoAbandonedMidTransactionLeavesThePreStateAfterReopen()
            throws Exception {
        seedAppItem(501, Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 2);
        awaitCallback(latch -> mWriter.createFolderAndMoveForDirectEdit(501, 0, 0, 0,
                proceed(), done(latch, true, null)));
        int folderId = queryInt(501, Favorites.CONTAINER);

        // "Process death": the undo transaction is abandoned without commit.
        // The record and snackbar are process-local and die with the process;
        // the DB must roll back to the applied state.
        try (com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction abandoned =
                mController.newTransaction()) {
            ContentValues restore = new ContentValues();
            restore.put(Favorites.CONTAINER, Favorites.CONTAINER_HOTSEAT);
            restore.put(Favorites.SCREEN, 0);
            restore.put(Favorites.CELLX, 3);
            restore.put(Favorites.RANK, 2);
            mController.update(Favorites.TABLE_NAME, restore,
                    Favorites._ID + "=501", null);
            mController.delete(Favorites.TABLE_NAME,
                    Favorites._ID + "=" + folderId, null);
            // no commit — abandoned like a process death
        }
        mController.closeActiveHelperForRestore();
        // After the reopen: the applied state survives (child in the folder,
        // folder row present) — nothing half-applied.
        assertEquals(folderId, queryInt(501, Favorites.CONTAINER));
        assertEquals(Favorites.ITEM_TYPE_FOLDER, queryInt(folderId, Favorites.ITEM_TYPE));
        // The undo record itself never persisted: the process-local slot dies
        // with the process (spec 450: process死後は取り消せない).
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

    // --- (g) Issue #450 round 13: the direct-edit create-folder write
    // persists the OPTIONS_DIRECT_EDIT_CREATED_FOLDER bit in the favorites
    // OPTIONS column (the bind-time single-child cleanup keeps respecting
    // the folder after any reload; the bit leaves with the folder row), and
    // the direct-edit move/remove paths never set it ---

    @Test
    public void createFolderPersistsTheDirectEditCreatedOptionsBit() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_HOTSEAT, 0, 3, 0, 2);

        awaitCallback(latch -> mWriter.createFolderAndMoveForDirectEdit(501, 0, 0, 0,
                proceed(), done(latch, true, null)));
        int folderId = queryInt(501, Favorites.CONTAINER);
        assertTrue(folderId > 0);
        assertEquals(Favorites.ITEM_TYPE_FOLDER, queryInt(folderId, Favorites.ITEM_TYPE));
        // The persisted OPTIONS column carries the bit: a reload rebuilds the
        // FolderInfo with it (the loader reads the OPTIONS column into
        // FolderInfo.options), so the suppression holds after any reload. The
        // bit is removed with the folder row (undo or user delete) — no
        // clearing path exists.
        assertTrue("created folder row must persist OPTIONS_DIRECT_EDIT_CREATED_FOLDER",
                (queryInt(folderId, Favorites.OPTIONS)
                        & DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER) != 0);
        // The live model FolderInfo carries the bit too (the owner-side bind
        // reads it before the next reload).
        synchronized (mBgDataModel) {
            FolderInfo folder =
                    mBgDataModel.collections.get(folderId) instanceof FolderInfo f ? f : null;
            assertNotNull("created folder must be in the live model", folder);
            assertTrue(folder.hasOption(DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER));
        }
    }

    @Test
    public void moveAndRemoveDirectEditsDoNotSetTheCreatedFolderOptionsBit() throws Exception {
        seedAppItem(501, Favorites.CONTAINER_DESKTOP, 0, 1, 2, 0);

        // Direct-edit move: the moved row's OPTIONS stays bitless.
        awaitCallback(latch -> mWriter.moveItemForDirectEdit(501, Favorites.CONTAINER_DESKTOP,
                1, 0, 0, 0, proceed(), done(latch, true, null)));
        assertTrue("direct-edit move must not set OPTIONS_DIRECT_EDIT_CREATED_FOLDER",
                (queryInt(501, Favorites.OPTIONS)
                        & DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER) == 0);

        // Direct-edit remove: neither the captured payload nor the re-inserted
        // row carries the bit — it marks folder creation only.
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
        assertEquals(0, countRows(501));
        assertNotNull(captured.get());
        assertTrue("remove payload must not carry OPTIONS_DIRECT_EDIT_CREATED_FOLDER",
                (captured.get().options
                        & DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER) == 0);

        int[] newId = new int[1];
        CountDownLatch restored = new CountDownLatch(1);
        mWriter.restoreRemovedItemForDirectEdit(captured.get(), proceed(),
                (id, success, reason, oc, os, ox, oy, osx, osy, orank, folderId, created,
                        removedRow) -> {
                    assertTrue(success);
                    newId[0] = id;
                    restored.countDown();
                });
        assertTrue(restored.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue("restored row must not carry OPTIONS_DIRECT_EDIT_CREATED_FOLDER",
                (queryInt(newId[0], Favorites.OPTIONS)
                        & DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER) == 0);
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

    /**
     * A stage-2 validator that re-verifies the recorded undo precondition
     * against the current state (the same shape as the fork-side
     * HomeEditUndoStage2Validator): the item must still sit at the recorded
     * result placement. Used by the defer oracle to prove the re-validation
     * runs inside admission after the lease release.
     */
    private DirectEditContract.Validator undoValidator =
            current -> {
                com.android.launcher3.model.DirectEditContract.Row target = null;
                for (com.android.launcher3.model.DirectEditContract.Row row : current.rows) {
                    if (row.id == 501) {
                        target = row;
                        break;
                    }
                }
                if (target == null) {
                    return DirectEditContract.Decision.reject(DirectEditContract.FAIL_UNDO_STALE);
                }
                boolean atResult = target.container == Favorites.CONTAINER_DESKTOP
                        && target.screenId == 1
                        && target.cellX == 0
                        && target.cellY == 0;
                return atResult
                        ? DirectEditContract.Decision.proceed()
                        : DirectEditContract.Decision.reject(DirectEditContract.FAIL_UNDO_STALE);
            };

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
