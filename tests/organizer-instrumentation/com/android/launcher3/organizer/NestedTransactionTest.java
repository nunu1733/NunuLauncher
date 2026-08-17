// Issue #60 ER-06: nested/reentrant SQLiteTransaction through ModelDbController.
// Spec 60 Section "ER-06 nested transaction".
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
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * ER-06: Nested/reentrant {@link SQLiteTransaction} through {@link
 * ModelDbController}.
 *
 * <p>SQLite's built-in transaction nesting (beginTransaction/endTransaction) is
 * exercised through the lease-owning
 * {@link ModelDbController#newTransaction()} seam. The outer transaction
 * holds a coordinator lease; inner transactions (plain or reentrant-lease)
 * nest within the same SQLite transaction.
 *
 * <p>Android's {@link SQLiteDatabase} implements nested transactions as
 * named savepoints. A nested transaction that ends without
 * {@link SQLiteTransaction#commit()} (i.e., without
 * {@link SQLiteDatabase#setTransactionSuccessful()}) rolls back to the
 * savepoint, preserving the parent transaction's writes. The parent can
 * then commit or roll back independently ("savepoint semantics").
 *
 * <p>ER-06 acceptance: (1) outer+inner commit as one unit, (2) inner
 * failure does not commit inner writes while the outer preserves its own
 * scope, (3) the coordinator lease is held until the outer close, (4) an
 * exception inside the inner propagates and the outer close still releases
 * the lease.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NestedTransactionTest {

    private static final String TEST_DB = "nested-transaction-test.db";

    private Context mContext;
    private TestController mController;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new TestController(mContext);
        // Force database creation and table setup.
        mController.getDb();
    }

    @After
    public void tearDown() {
        if (mController != null) {
            mController.closeActiveHelperForRestore();
        }
        mContext.deleteDatabase(TEST_DB);
    }

    @Test
    public void outerAndInnerTransactionCommitAsOneUnit() {
        // ER-06: outer+inner commit persists both writes.
        SQLiteDatabase db = mController.getDb();
        final long idA = 9001;
        final long idB = 9002;
        try (SQLiteTransaction outer = mController.newTransaction()) {
            insertFavorite(db, idA, "outer");
            try (SQLiteTransaction inner = new SQLiteTransaction(db)) {
                insertFavorite(db, idB, "inner");
                inner.commit();
            }
            outer.commit();
        }
        assertTitleEquals(db, idA, "outer");
        assertTitleEquals(db, idB, "inner");
    }

    @Test
    public void innerCloseWithoutCommitRollsBackInnerWrites() {
        // ER-06: a nested transaction closed without commit rolls back its
        // own writes (savepoint rollback) while the outer transaction's
        // writes are unaffected and can still commit.
        SQLiteDatabase db = mController.getDb();
        final long idA = 9003;
        final long idB = 9004;
        try (SQLiteTransaction outer = mController.newTransaction()) {
            insertFavorite(db, idA, "outer-keep");
            try (SQLiteTransaction inner = new SQLiteTransaction(db)) {
                insertFavorite(db, idB, "inner-rollback");
                // close() without commit() -> SQLite rolls back to the
                // savepoint, preserving the outer-level writes.
            }
            outer.commit();
        }
        // Outer writes are committed.
        assertTitleEquals(db, idA, "outer-keep");
        // Inner writes are rolled back.
        assertTitleAbsent(db, idB);
    }

    @Test
    public void leaseHeldUntilOuterClose() {
        // ER-06: the coordinator lease is held until the outer transaction
        // close; inner close (plain, no lease) does not release it.
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        SQLiteDatabase db = mController.getDb();

        try (SQLiteTransaction outer = mController.newTransaction()) {
            assertNull("other lease blocked while outer is held",
                    coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));

            try (SQLiteTransaction inner = new SQLiteTransaction(db)) {
                inner.commit();
            }

            // The lease is still held after inner close.
            assertNull("lease still held after inner close",
                    coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));

            outer.commit();
        }

        // After outer close, the lease is released: another kind can now
        // acquire.
        try (LayoutWriteCoordinator.Lease post =
                     coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("lease released after outer close", post);
        }
    }

    @Test
    public void reentrantLeaseInnerCloseDoesNotReleaseOuterLease() {
        // ER-06: inner close on a reentrant lease decrements the recursion
        // count but does not release the outer lease. The coordinator lock
        // stays held (recursionCount 2->1) until the outer close (1->0).
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        SQLiteDatabase db = mController.getDb();

        // Acquire the outer lease directly so its token is observable;
        // ModelDbController.newTransaction() hides the lease token.
        try (LayoutWriteCoordinator.Lease outerLease = coordinator.acquireBlockingQuietly(
                     LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            try (SQLiteTransaction outer = new SQLiteTransaction(db, outerLease)) {
                assertNull("other lease blocked while outer is held",
                        coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));

                // Reentrant lease on the same thread/kind/token; the inner
                // SQLiteTransaction closes it, decrementing recursionCount.
                LayoutWriteCoordinator.Lease innerLease = coordinator.tryReenter(
                        LayoutWriteCoordinator.OwnerKind.MODEL_WRITER, outerLease.token());
                assertNotNull("reentrant lease acquired", innerLease);

                try (SQLiteTransaction inner = new SQLiteTransaction(db, innerLease)) {
                    insertFavorite(db, 9005, "reentrant-inner");
                    inner.commit();
                }

                // After inner close, recursionCount is 1, so the lease is
                // still held.
                assertNull("lease still held after reentrant inner close",
                        coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));

                outer.commit();
            }
        }

        // After outer close, the lease is released.
        try (LayoutWriteCoordinator.Lease post =
                     coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("lease released after outer close", post);
        }
    }

    @Test
    public void exceptionInsideInnerPropagatesAndOuterCloseReleasesLease() {
        // ER-06: an exception thrown inside the inner transaction propagates
        // to the caller; the outer close still releases the coordinator
        // lease.
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        SQLiteDatabase db = mController.getDb();

        try (SQLiteTransaction outer = mController.newTransaction()) {
            try {
                try (SQLiteTransaction inner = new SQLiteTransaction(db)) {
                    throw new RuntimeException("simulated inner failure");
                }
            } catch (RuntimeException e) {
                assertEquals("simulated inner failure", e.getMessage());
            }
            // The outer lease is still held after the inner exception.
            assertNull("lease still held after inner exception",
                    coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
            outer.commit();
        }

        // After outer close, the lease is released.
        try (LayoutWriteCoordinator.Lease post =
                     coordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("lease released after outer close despite inner exception", post);
        }
    }

    // -- helpers --

    private static void insertFavorite(SQLiteDatabase db, long id, String title) {
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, id);
        values.put(Favorites.TITLE, title);
        values.put(Favorites.INTENT, "#Intent;end");
        values.put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
        values.put(Favorites.SCREEN, 0);
        values.put(Favorites.CELLX, 0);
        values.put(Favorites.CELLY, 0);
        values.put(Favorites.SPANX, 1);
        values.put(Favorites.SPANY, 1);
        values.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
        values.put(Favorites.PROFILE_ID, 0);
        long rowId = db.insertOrThrow(Favorites.TABLE_NAME, null, values);
        assertTrue("row " + id + " inserted", rowId >= 0);
    }

    private static void assertTitleEquals(SQLiteDatabase db, long id, String expectedTitle) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] { Favorites.TITLE },
                Favorites._ID + " = ?", new String[] { String.valueOf(id) },
                null, null, null)) {
            assertTrue("row " + id + " exists", c.moveToFirst());
            assertEquals(expectedTitle, c.getString(0));
        }
    }

    private static void assertTitleAbsent(SQLiteDatabase db, long id) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] { Favorites.TITLE },
                Favorites._ID + " = ?", new String[] { String.valueOf(id) },
                null, null, null)) {
            assertFalse("row " + id + " should not exist", c.moveToFirst());
        }
    }

    /** Test double that uses an isolated database file. */
    private static final class TestController extends ModelDbController {
        private final Context mContext;

        TestController(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            return new DatabaseHelper(mContext, TEST_DB, user -> 0L, () -> { });
        }
    }
}