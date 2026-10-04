// Issue #113: MODEL_WRITER same-thread re-entry for mutations issued inside an
// open baseline transaction (folder-bind ANR regression coverage).
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #113 regression: the real folder-bind path opens
 * {@link ModelDbController#newTransaction()} and then calls the gated mutation
 * methods ({@link ModelDbController#update}, insert, delete) inside that
 * transaction on the same thread. Before the fix, the inner mutation acquired a
 * second MODEL_WRITER lease and blocked on its own outer lease — a deterministic
 * self-deadlock that ANRed cold start.
 *
 * <p>The deadlock reproduction runs its worker on a separate thread with a
 * bounded join so a regression fails the test instead of hanging the suite.
 * Every throwable stays recorded inside the worker (an uncaught background
 * exception would kill the instrumentation process and mask the remaining
 * tests): the timeout path interrupts the worker, whose try-with-resources
 * then closes the transaction and releases the coordinator for the remaining
 * tests. The competing-writer tests pin the other direction: re-entry must
 * stay bounded to the owning thread, so a writer from another thread still
 * blocks until the outer close.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ModelWriterTransactionReentryTest {

    private static final String TEST_DB = "model-writer-reentry-test.db";
    // A local SQLite update takes milliseconds; ten seconds means wedged, not slow.
    private static final long DEADLOCK_TIMEOUT_MS = 10_000;
    // Bounded settle window proving a competitor stayed blocked while the outer
    // transaction was open.
    private static final long SETTLE_TIMEOUT_MS = 500;

    private Context mContext;
    private TestController mController;
    private LayoutWriteCoordinator mCoordinator;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteDatabase(TEST_DB);
        mController = new TestController(mContext);
        mCoordinator = LayoutWriteCoordinator.getInstance();
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
    public void updateInsideOpenTransactionDoesNotSelfDeadlock() throws Exception {
        // Exact ANR shape from Issue #113: newTransaction() holds MODEL_WRITER,
        // then update() inside requests the same lease on the same thread.
        SQLiteDatabase db = mController.getDb();
        final long id = 9101;
        insertFavorite(db, id, "before");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runBounded(failure, () -> {
            try (SQLiteTransaction t = mController.newTransaction()) {
                mController.update(Favorites.TABLE_NAME,
                        titleValues("after"), itemIdMatch(id), null);
                t.commit();
            }
        });

        assertNull("update inside open transaction failed; "
                + "MODEL_WRITER self-deadlock reproduced", failure.get());
        assertEquals("after", queryTitle(db, id));
    }

    @Test
    public void multipleGatedMutationsInsideOneTransactionRetainOuterLease() throws Exception {
        // Folder.bind issues several updates inside one transaction; every gated
        // mutation re-enters, nothing commits before the outer close, and the
        // lease unlocks exactly once at the outermost close.
        SQLiteDatabase db = mController.getDb();
        final long moved = 9102;
        final long inserted = 9103;
        final long deleted = 9104;
        insertFavorite(db, moved, "moved");
        insertFavorite(db, deleted, "deleted");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runBounded(failure, () -> {
            try (SQLiteTransaction outer = mController.newTransaction()) {
                mController.update(Favorites.TABLE_NAME,
                        titleValues("moved-title"), itemIdMatch(moved), null);

                ContentValues values = baseValues(inserted, "inserted-inside");
                mController.insert(Favorites.TABLE_NAME, values);

                mController.delete(Favorites.TABLE_NAME, itemIdMatch(deleted), null);

                // The outer writer lease is still held while mutations run.
                assertNull("lease must stay held inside the open transaction",
                        mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));

                outer.commit();
            }
        });

        assertNull("gated mutations inside one open transaction failed",
                failure.get());
        assertEquals("moved-title", queryTitle(db, moved));
        assertEquals("inserted-inside", queryTitle(db, inserted));
        assertFalse("row deleted inside transaction must be gone", rowExists(db, deleted));

        // After the outermost close the lease is free for another kind/thread.
        try (LayoutWriteCoordinator.Lease post =
                     mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("outermost close must unlock exactly once", post);
        }
    }

    @Test
    public void nestedControllerCloseReleasesRecursionOnce() throws Exception {
        // Inner controller transactions re-enter the outer lease; a clean inner
        // close decrements recursion only, and the outermost close unlocks once.
        SQLiteDatabase db = mController.getDb();
        final long id = 9105;
        insertFavorite(db, id, "initial");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runBounded(failure, () -> {
            try (SQLiteTransaction outer = mController.newTransaction()) {
                mController.update(Favorites.TABLE_NAME,
                        titleValues("outer-write"), itemIdMatch(id), null);

                try (SQLiteTransaction inner = mController.newTransaction()) {
                    mController.update(Favorites.TABLE_NAME,
                            titleValues("inner-write"), itemIdMatch(id), null);
                    inner.commit();
                }

                assertNull("inner close must only decrement recursion",
                        mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
                outer.commit();
            }
        });

        assertNull("nested controller transactions did not unwind cleanly", failure.get());
        assertEquals("outermost close commits the whole unit", "inner-write",
                queryTitle(db, id));
        try (LayoutWriteCoordinator.Lease post =
                     mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("lease released exactly once after all closes", post);
        }
    }

    @Test
    public void innerMutationFailureUnwindsItsLeaseViewOnce() throws Exception {
        // An exception thrown by a gated mutation inside an inner controller
        // transaction unwinds that inner lease view exactly once; the outer
        // close afterwards unlocks without leaking recursion.
        //
        // A failed nested SQLiteTransaction poisons the whole transaction on
        // every supported platform; this test focuses on the separate lease
        // unwind contract rather than duplicating the data rollback oracle in
        // NestedTransactionTest.
        SQLiteDatabase db = mController.getDb();
        final long id = 9106;
        insertFavorite(db, id, "initial");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runBounded(failure, () -> {
            try (SQLiteTransaction outer = mController.newTransaction()) {
                try (SQLiteTransaction inner = mController.newTransaction()) {
                    try {
                        // Unknown column forces the gated update to throw after
                        // the inner lease was taken.
                        ContentValues bad = new ContentValues();
                        bad.put("no_such_column", 1);
                        mController.update(Favorites.TABLE_NAME, bad,
                                itemIdMatch(id), null);
                        throw new IllegalStateException("expected update to fail");
                    } catch (RuntimeException expected) {
                        // Inner closes without success.
                    }
                }

                assertNull("inner failure must only decrement recursion",
                        mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
                outer.commit();
            }
        });

        assertNull("nested close/failure path did not unwind cleanly", failure.get());
        // The row was seeded before the transaction, so whole-unit rollback
        // leaves the pre-test value intact; no partial inner rollback is
        // implied by this lease-unwind test.
        assertEquals("initial", queryTitle(db, id));
        try (LayoutWriteCoordinator.Lease post =
                     mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER)) {
            assertNotNull("lease released exactly once after all closes", post);
        }
    }

    @Test
    public void competingWriterFromOtherThreadBlocksUntilOuterClose() throws Exception {
        // Re-entry is bounded to the owning thread: a different thread's writer
        // must serialize behind the open transaction, then proceed after it.
        SQLiteDatabase db = mController.getDb();
        final long id = 9107;
        insertFavorite(db, id, "initial");

        CountDownLatch competitorStarted = new CountDownLatch(1);
        AtomicBoolean competitorDone = new AtomicBoolean(false);
        AtomicReference<Throwable> competitorFailure = new AtomicReference<>();

        // Created up front but started only after the outer transaction is open,
        // so the competitor cannot win the lease race.
        Thread competitor = new Thread(() -> {
            competitorStarted.countDown();
            try {
                mController.update(Favorites.TABLE_NAME,
                        titleValues("competitor"), itemIdMatch(id), null);
                competitorDone.set(true);
            } catch (Throwable t) {
                competitorFailure.set(t);
            }
        });
        competitor.setDaemon(true);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        runBounded(failure, () -> {
            try (SQLiteTransaction outer = mController.newTransaction()) {
                mController.update(Favorites.TABLE_NAME,
                        titleValues("inside-transaction"), itemIdMatch(id), null);

                competitor.start();
                assertTrue(competitorStarted.await(5, TimeUnit.SECONDS));

                // The competitor must not slip in while the outer lease is held.
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(SETTLE_TIMEOUT_MS);
                while (System.nanoTime() < deadline) {
                    assertFalse("competing writer entered during the open transaction",
                            competitorDone.get());
                    Thread.sleep(50);
                }

                outer.commit();
            }
        });

        assertNull("outer transaction body failed", failure.get());
        competitor.join(DEADLOCK_TIMEOUT_MS);
        assertFalse("competing writer still blocked after outer close",
                competitor.isAlive());
        assertNull(competitorFailure.get());
        assertTrue(competitorDone.get());
        assertEquals("serialized writes apply in order", "competitor",
                queryTitle(db, id));
    }

    // -- helpers --

    /**
     * Runs [body] on a worker thread, failing fast (instead of hanging forever)
     * if it deadlocks. On timeout the worker is interrupted so its blocked
     * acquisition throws; the body's own try-with-resources closes any open
     * transaction and releases the coordinator before the throwable is
     * recorded into [failure].
     */
    private static void runBounded(
            AtomicReference<Throwable> failure, ThrowingRunnable body)
            throws InterruptedException {
        Thread worker = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.setDaemon(true);
        worker.start();
        worker.join(DEADLOCK_TIMEOUT_MS);
        if (worker.isAlive()) {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static ContentValues titleValues(String title) {
        ContentValues values = new ContentValues();
        values.put(Favorites.TITLE, title);
        return values;
    }

    private static ContentValues baseValues(long id, String title) {
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
        return values;
    }

    private static String itemIdMatch(long id) {
        return Favorites._ID + " = " + id;
    }

    private static void insertFavorite(SQLiteDatabase db, long id, String title) {
        assertTrue("row " + id + " inserted",
                db.insertOrThrow(Favorites.TABLE_NAME, null,
                        baseValues(id, title)) >= 0);
    }

    private static String queryTitle(SQLiteDatabase db, long id) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] {Favorites.TITLE},
                Favorites._ID + " = ?", new String[] {String.valueOf(id)},
                null, null, null)) {
            assertTrue("row " + id + " exists", c.moveToFirst());
            return c.getString(0);
        }
    }

    private static boolean rowExists(SQLiteDatabase db, long id) {
        try (Cursor c = db.query(Favorites.TABLE_NAME, new String[] {Favorites._ID},
                Favorites._ID + " = ?", new String[] {String.valueOf(id)},
                null, null, null)) {
            return c.moveToFirst();
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
