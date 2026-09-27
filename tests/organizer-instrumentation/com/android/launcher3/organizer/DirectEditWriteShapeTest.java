// Issue #448: direct-edit write-shape tests on the shared-writer seam.
// The JVM planner/validator tests own the stage-2 decision logic; these
// instrumentation tests pin the two properties ADR-0013's required-test table
// asks of the write side: (1) the multi-row folder-creation sequence is one
// transaction that rolls back completely on an injected failure (contract 3),
// and (2) the validate-then-write task shape runs its stage-2 validation only
// inside MODEL_WRITER admission — after any organizer lease release — and
// performs no write when validation rejects (contract 2 + 4).
package com.android.launcher3.organizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.DirectEditContract;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.util.ContentWriter;

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
public class DirectEditWriteShapeTest {

    private static final String TEST_DB = "direct-edit-write-shape-test.db";
    private static final long TIMEOUT_MS = 10_000;

    private Context mContext;
    private FailableController mController;
    private LayoutWriteCoordinator mCoordinator;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new FailableController(mContext);
        mCoordinator = LayoutWriteCoordinator.getInstance();
        mController.getDb();
    }

    @After
    public void tearDown() {
        if (mController != null) {
            mController.closeActiveHelperForRestore();
        }
        mContext.deleteDatabase(TEST_DB);
    }

    /**
     * Contract 3: the new-folder action writes two rows (folder INSERT + child
     * UPDATE) inside one explicit transaction. Injecting a failure at the
     * second write must leave both rows unchanged — never a folder row without
     * the moved child.
     */
    @Test
    public void folderCreationSequenceRollsBackOnSecondWriteFailure() throws Exception {
        SQLiteDatabase db = mController.getDb();
        final int itemId = 44001;
        insertAppRow(db, itemId);

        mController.updateFailed = false;
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        runBounded(thrown, () -> {
            try {
                runFolderCreationSequence(mController, itemId, /* failOnUpdate */ true);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        assertTrue("injected failure did not surface", mController.updateFailed);

        assertEquals("folder row must not survive a failed transaction",
                0, countRows(db, Favorites.ITEM_TYPE_FOLDER));
        assertEquals("child row must keep its desktop placement",
                Favorites.CONTAINER_DESKTOP, queryInt(db, itemId, Favorites.CONTAINER));
        assertEquals(0, queryInt(db, itemId, Favorites.RANK));
    }

    /** The same sequence without the failure commits both rows atomically. */
    @Test
    public void folderCreationSequenceCommitsBothRows() throws Exception {
        SQLiteDatabase db = mController.getDb();
        final int itemId = 44002;
        insertAppRow(db, itemId);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        runBounded(thrown, () -> {
            try {
                runFolderCreationSequence(mController, itemId, false);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        assertNull("folder creation sequence failed", thrown.get());

        assertEquals(1, countRows(db, Favorites.ITEM_TYPE_FOLDER));
        assertEquals(0, queryInt(db, itemId, Favorites.RANK));
        assertTrue(queryInt(db, itemId, Favorites.CONTAINER) > 0);
    }

    /**
     * Contract 2 + 4 ordering: while an organizer lease is held the
     * direct-edit task is deferred (it neither validates nor writes); after
     * the lease releases, stage-2 validation runs first inside admission and
     * a rejection performs no write. This pins the shape every direct-edit
     * task in ModelWriter implements (validate as the first admitted
     * statement, before any model/DB change).
     */
    @Test
    public void stage2ValidationRunsInsideAdmissionAndRejectsWithoutWrite() throws Exception {
        SQLiteDatabase db = mController.getDb();
        final int itemId = 44003;
        insertAppRow(db, itemId);

        AtomicBoolean validatorRan = new AtomicBoolean(false);
        AtomicBoolean leaseFreeDuringValidation = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LayoutWriteCoordinator.Lease> organizerLease =
                new AtomicReference<>(mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER));
        assertNotNull("organizer lease must be acquirable", organizerLease.get());

        DirectEditContract.Validator rejector = current -> {
            validatorRan.set(true);
            // Deferred tokenless work drains after the holder released; a
            // MODEL_WRITER lease being acquirable here proves the organizer
            // lease was gone before stage-2 validation ran.
            LayoutWriteCoordinator.Lease probe =
                    mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
            leaseFreeDuringValidation.set(probe != null);
            if (probe != null) {
                probe.close();
            }
            return DirectEditContract.Decision.reject(DirectEditContract.FAIL_STALE);
        };

        mCoordinator.runOrDefer(
                LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, 0L, false, () -> {
                    // DirectEditTask shape: validate first, write only on proceed.
                    DirectEditContract.Decision decision = rejector.validate(null);
                    if (decision.proceed) {
                        mController.update(Favorites.TABLE_NAME, rankValues(1),
                                Favorites._ID + "=" + itemId, null);
                    }
                    done.countDown();
                });

        // While the organizer lease is held the task stays deferred.
        assertFalse("validator must not run while the organizer lease is held",
                done.await(300, TimeUnit.MILLISECONDS));
        assertFalse("stage-2 validation must stay deferred", validatorRan.get());

        // Lease release resolves the FIFO; the task validates then rejects.
        organizerLease.get().close();
        organizerLease.set(null);
        assertTrue("task did not finish after the organizer lease released",
                done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue("stage-2 validation must run inside admission", validatorRan.get());
        assertTrue("validation must run only after the organizer lease released",
                leaseFreeDuringValidation.get());
        assertEquals("rejected write must leave the row unchanged",
                0, queryInt(db, itemId, Favorites.RANK));
    }

    /** The write sequence used by ModelWriter.DirectEditCreateFolderTask. */
    private void runFolderCreationSequence(ModelDbController controller, int itemId,
            boolean failOnUpdate) throws Exception {
        int folderId = (int) controller.generateNewItemId();
        try (SQLiteTransaction t = controller.newTransaction()) {
            ContentWriter folderWriter = new ContentWriter(mContext);
            folderWriter.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                    .put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                    .put(Favorites.SCREEN, 1)
                    .put(Favorites.CELLX, 0)
                    .put(Favorites.CELLY, 0)
                    .put(Favorites.SPANX, 1)
                    .put(Favorites.SPANY, 1)
                    .put(Favorites.RANK, 0)
                    .put(Favorites.OPTIONS, 0)
                    .put(Favorites._ID, folderId);
            controller.insert(Favorites.TABLE_NAME, folderWriter.getValues(mContext));

            mController.failOnNextUpdate = failOnUpdate;
            controller.update(Favorites.TABLE_NAME, childValues(folderId),
                    Favorites._ID + "=" + itemId, null);
            t.commit();
        } finally {
            mController.failOnNextUpdate = false;
        }
    }

    private ContentValues childValues(int folderId) {
        ContentValues values = new ContentValues();
        values.put(Favorites.CONTAINER, folderId);
        values.put(Favorites.SCREEN, 0);
        values.put(Favorites.CELLX, -1);
        values.put(Favorites.CELLY, -1);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.RANK, 0);
        return values;
    }

    private ContentValues rankValues(int rank) {
        ContentValues values = new ContentValues();
        values.put(Favorites.RANK, rank);
        return values;
    }

    private void insertAppRow(SQLiteDatabase db, int id) {
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
        values.put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
        values.put(Favorites.SCREEN, 0);
        values.put(Favorites.CELLX, 0);
        values.put(Favorites.CELLY, 5);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.RANK, 0);
        db.insertWithOnConflict(Favorites.TABLE_NAME, null, values, 5 /* IGNORE */);
    }

    private int countRows(SQLiteDatabase db, int itemType) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + Favorites.TABLE_NAME + " WHERE "
                        + Favorites.ITEM_TYPE + "=?",
                new String[]{String.valueOf(itemType)})) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private int queryInt(SQLiteDatabase db, int id, String column) {
        try (Cursor c = db.rawQuery(
                "SELECT " + column + " FROM " + Favorites.TABLE_NAME + " WHERE " + Favorites._ID + "=?",
                new String[]{String.valueOf(id)})) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private void runBounded(AtomicReference<Throwable> failure, Runnable body)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        worker.start();
        assertTrue("worker did not finish in " + TIMEOUT_MS + "ms; lease wedged",
                latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private static class FailableController extends ModelDbController {
        private final Context mContext;
        volatile boolean failOnNextUpdate;
        volatile boolean updateFailed;

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
            if (failOnNextUpdate) {
                updateFailed = true;
                throw new IllegalStateException("injected update failure");
            }
            return super.update(table, values, selection, selectionArgs);
        }
    }
}
