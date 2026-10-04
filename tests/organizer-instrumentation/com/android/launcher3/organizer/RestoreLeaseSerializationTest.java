// Issue #58: restore-family lease serialization tests. Spec 58 §"Coordinator extension",
// scenario matrix SR-04, SR-05, SR-09, SR-12; acceptance AC-1, AC-2, AC-3.
package com.android.launcher3.organizer;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.RestoreDbTask;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the Issue #58 restore lease family: one restore-kind lease spans raw
 * file replacement, DB reopen and {@link RestoreDbTask#performRestore}
 * sanitization, and ordinary model/provider writes defer behind it.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RestoreLeaseSerializationTest {

    private final LayoutWriteCoordinator mCoordinator = LayoutWriteCoordinator.getInstance();

    @Test
    public void restoreFamilyIsReentrantAcrossRestoreKindsAndExcludesOthers() {
        LayoutWriteCoordinator.Lease outer =
                mCoordinator.acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE);
        try {
            // Same thread holding any restore kind reenters without releasing.
            LayoutWriteCoordinator.Lease inner = mCoordinator.tryReenterRestoreFamily();
            assertNotNull(inner);
            assertEquals(outer.kind(), inner.kind());
            assertEquals(outer.token(), inner.token());

            // Direct reentrancy through a different restore kind is still one family:
            // tryReenter with the outer kind/token keeps working.
            LayoutWriteCoordinator.Lease exact = mCoordinator.tryReenter(
                    LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE, outer.token());
            assertNotNull(exact);
            exact.close();

            // Non-restore kinds cannot enter while a restore lease is held.
            assertNull(mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER));
            assertNull(mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.ORGANIZER));
            assertNull(mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.GRID_MIGRATION));

            // Another thread cannot reenter the family either.
            final AtomicBoolean foreignReenter = new AtomicBoolean(true);
            runOnSeparateThread(() -> {
                LayoutWriteCoordinator.Lease foreign = mCoordinator.tryReenterRestoreFamily();
                foreignReenter.set(foreign != null);
                if (foreign != null) foreign.close();
            });
            assertFalse(foreignReenter.get());

            inner.close();
        } finally {
            outer.close();
        }
        // Exclusion is released with the outermost lease.
        LayoutWriteCoordinator.Lease writer =
                mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
        assertNotNull(writer);
        writer.close();
    }

    @Test
    public void tokenlessModelAndProviderWorkDeferBehindRestoreLease() throws Exception {
        AtomicBoolean modelTaskRan = new AtomicBoolean(false);
        CompletableFuture<Integer> providerFuture;
        LayoutWriteCoordinator.Lease lease =
                mCoordinator.acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.DECK_FILE_RESTORE);
        try {
            mCoordinator.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    /* token= */ 0L,
                    /* exactOrganizerToken= */ false,
                    () -> modelTaskRan.set(true));
            assertFalse(modelTaskRan.get());

            providerFuture = mCoordinator
                    .runOrDeferWithOperationFuture(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    /* token= */ 0L,
                    /* exactOrganizerToken= */ false,
                    () -> 7);
            assertFalse(providerFuture.isDone());
        } finally {
            lease.close();
        }
        // SR-04/SR-05: deferred work runs only after the restore lease is released.
        assertTrue(modelTaskRan.get());
        assertTrue(providerFuture.isDone());
        assertEquals(7, providerFuture.get().intValue());
    }

    @Test
    public void concurrentModelWriterAcquisitionBlocksUntilRestoreLeaseRelease()
            throws Exception {
        LayoutWriteCoordinator.Lease restore =
                mCoordinator.acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.RESTORE);
        CountDownLatch writerAcquired = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            LayoutWriteCoordinator.Lease l = null;
            try {
                l = mCoordinator.acquireBlocking(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
                writerAcquired.countDown();
            } catch (InterruptedException ignored) {
            } finally {
                if (l != null) l.close();
            }
        });
        writer.start();
        // The blocking writer cannot acquire while the restore lease is held.
        assertFalse(writerAcquired.await(500, TimeUnit.MILLISECONDS));
        restore.close();
        assertTrue(writerAcquired.await(5, TimeUnit.SECONDS));
        writer.join(5_000);
    }

    /**
     * SR-12 / AC-2: direct performRestore (no outer lease) acquires the RESTORE
     * lease before opening the DB through controller.getDb().
     */
    @Test
    public void directPerformRestoreAcquiresLeaseBeforeOpeningDb() {
        LeaseProbeController controller = new LeaseProbeController();
        // Return value is irrelevant to the ordering assertion; a failing sanitize on
        // the mocked db simply produces the documented boolean failure surface.
        RestoreDbTask.performRestore(getInstrumentation().getTargetContext(), controller);
        assertTrue("getDb must observe an active restore-family lease", controller.leaseHeldAtOpen.get());
        assertEquals(LayoutWriteCoordinator.OwnerKind.RESTORE, controller.kindAtOpen.get());
    }

    /**
     * SR-09 / AC-1: performRestore reenters an outer restore-family lease held by
     * the calling thread (no self-deadlock) and the outer lease stays continuous
     * (same kind and token) across the nested restore.
     */
    @Test
    public void performRestoreReentersOuterRestoreFamilyLease() {
        LayoutWriteCoordinator.Lease outer =
                mCoordinator.acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE);
        try {
            LeaseProbeController controller = new LeaseProbeController();
            RestoreDbTask.performRestore(getInstrumentation().getTargetContext(), controller);
            assertTrue(controller.leaseHeldAtOpen.get());
            assertEquals(LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE,
                    controller.kindAtOpen.get());
            assertEquals(outer.token(), controller.tokenAtOpen.get());
        } finally {
            outer.close();
        }
        // The nested reentrancy fully unwound with the outer lease.
        assertNull(mCoordinator.tryReenterRestoreFamily());
        LayoutWriteCoordinator.Lease writer =
                mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
        assertNotNull(writer);
        writer.close();
    }

    /**
     * Audit fix (PR #77): only organizer and restore-family leases defer tokenless
     * work. GRID_MIGRATION and MODEL_WRITER leases must not change executor
     * deferral semantics.
     */
    @Test
    public void gridMigrationAndModelWriterLeasesDoNotDeferTokenlessWork() {
        for (LayoutWriteCoordinator.OwnerKind kind :
                new LayoutWriteCoordinator.OwnerKind[] {
                    LayoutWriteCoordinator.OwnerKind.GRID_MIGRATION,
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER}) {
            AtomicBoolean ran = new AtomicBoolean(false);
            LayoutWriteCoordinator.Lease lease = mCoordinator.acquireBlockingQuietly(kind);
            try {
                mCoordinator.runOrDefer(
                        LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                        /* token= */ 0L,
                        /* exactOrganizerToken= */ false,
                        () -> ran.set(true));
                assertTrue(kind + " lease must not defer tokenless work", ran.get());
            } finally {
                lease.close();
            }
        }
    }

    /**
     * SR-06 / AC-4: closeActiveHelperForRestore closes the active helper (no open
     * handle on the DB files, mOpenHelper cleared) and the next getDb lazily
     * constructs a fresh helper on the same controller.
     */
    @Test
    public void closeActiveHelperClosesHandleAndReopensFreshHelper() {
        Context appContext = ApplicationProvider.getApplicationContext();
        HelperProbeController controller = new HelperProbeController(appContext);
        SQLiteDatabase before = controller.getDb();
        assertTrue(before.isOpen());
        Object helperBefore = controller.openHelper();

        controller.closeActiveHelperForRestore();

        // The old handle is closed and the controller no longer holds a helper,
        // so the underlying files are replaceable as raw bytes.
        assertFalse(before.isOpen());
        assertNull(controller.openHelper());
        File dbFile = appContext.getDatabasePath(HelperProbeController.TEST_DB_NAME);
        assertTrue("db file must be deletable while helper is closed", dbFile.delete());

        // Lazy reopen: the next getDb builds a fresh helper with a usable database.
        SQLiteDatabase after = controller.getDb();
        assertTrue(after.isOpen());
        assertTrue(after != before);
        assertTrue(controller.openHelper() != null);
        assertTrue(controller.openHelper() != helperBefore);
        after.execSQL("SELECT 1");
        controller.closeActiveHelperForRestore();
    }

    /**
     * SR-07 / AC-5: a failure on the restore path (sanitization failure on
     * invalid DB content) returns the baseline boolean failure surface
     * (performRestore == false) and, critically, does not wedge the
     * coordinator: a subsequent tryAcquire succeeds and tokenless work runs
     * immediately instead of being parked on the deferred queue.
     */
    @Test
    public void sanitizationFailureReturnsFalseAndDoesNotWedgeCoordinator() {
        android.content.Context context = getInstrumentation().getTargetContext();
        File bad = context.getDatabasePath("restore-fault-injection.db");
        bad.delete();
        bad.getParentFile().mkdirs();
        SQLiteDatabase badDb = SQLiteDatabase.openOrCreateDatabase(bad, null);
        // Favorites table without the columns sanitizeDB requires: any restore
        // query on it throws, exercising the failure path inside performRestore.
        badDb.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY)");
        AtomicBoolean deferredDrained = new AtomicBoolean(false);
        try {
            InvalidContentController controller = new InvalidContentController(badDb);
            boolean result = RestoreDbTask.performRestore(context, controller);
            assertFalse("sanitization failure must surface as performRestore == false", result);

            // The coordinator is not wedged by the failed restore.
            LayoutWriteCoordinator.Lease writer =
                    mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
            assertNotNull(writer);
            writer.close();

            // Tokenless work is no longer deferred behind a restore lease.
            mCoordinator.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    /* token= */ 0L,
                    /* exactOrganizerToken= */ false,
                    () -> deferredDrained.set(true));
            assertTrue(deferredDrained.get());
            assertEquals(0, mCoordinator.pendingDeferredCount());
        } finally {
            badDb.close();
            bad.delete();
        }
    }

    /**
     * SR-11 / AC-6: baseline fallback when the organizer application is absent.
     * The nullable-app overloads make the null-guard directly testable: both
     * quiesce and reload are observable no-ops (no exception, coordinator state
     * untouched). Remaining gap: the app-absent end-to-end production path with
     * a live raw-file swap is not exercised here.
     */
    @Test
    public void prepareAndReloadAreNoOpsWhenAppStateAbsent() {
        android.content.Context context = getInstrumentation().getTargetContext();
        RestoreDbTask.prepareForRawFileRestore(context, /* app= */ null);
        RestoreDbTask.reloadAfterRestore(/* app= */ (com.android.launcher3.LauncherAppState) null);
        // Also exercise the production entry points; whichever branch runs
        // (app present or absent in this process) must not throw.
        RestoreDbTask.prepareForRawFileRestore(context);
        RestoreDbTask.reloadAfterRestore(context);
        assertEquals(0, mCoordinator.pendingDeferredCount());
        LayoutWriteCoordinator.Lease writer =
                mCoordinator.tryAcquire(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
        assertNotNull(writer);
        writer.close();
    }

    private static void runOnSeparateThread(Runnable r) {
        try {
            Thread t = new Thread(r);
            t.start();
            t.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Test double whose getDb probes the coordinator lease state at DB-open time. */
    private static final class LeaseProbeController extends ModelDbController {        final AtomicBoolean leaseHeldAtOpen = new AtomicBoolean(false);
        final AtomicLong tokenAtOpen = new AtomicLong(-1);
        final AtomicReference<LayoutWriteCoordinator.OwnerKind> kindAtOpen =
                new AtomicReference<>(null);

        LeaseProbeController() {
            super(getInstrumentation().getTargetContext());
        }

        @Override
        public SQLiteDatabase getDb() {
            LayoutWriteCoordinator.Lease probe =
                    LayoutWriteCoordinator.getInstance().tryReenterRestoreFamily();
            if (probe != null) {
                leaseHeldAtOpen.set(true);
                tokenAtOpen.set(probe.token());
                kindAtOpen.set(probe.kind());
                probe.close();
            }
            // A null db makes the subsequent transaction construction fail inside
            // performRestore's documented boolean failure surface; the ordering probe
            // above has already recorded whether a lease was held at open time.
            return null;
        }
    }

    /**
     * Test double for SR-06/AC-4: creates helpers on an isolated test DB file
     * (mirroring DatabaseHelperSchema33Test) so close/reopen is observable
     * without touching the launcher's real grid databases.
     */
    private static final class HelperProbeController extends ModelDbController {
        static final String TEST_DB_NAME = "restore-helper-close-test.db";

        private final Context mContext;

        HelperProbeController(Context context) {
            super(context);
            mContext = context;
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            return new DatabaseHelper(mContext, TEST_DB_NAME, user -> 0L, () -> { });
        }

        Object openHelper() {
            return mOpenHelper;
        }
    }

    /**
     * Test double for SR-07/AC-5: serves a DB with invalid launcher content so
     * sanitization inside performRestore throws and the failure surface runs.
     */
    private static final class InvalidContentController extends ModelDbController {
        private final SQLiteDatabase mBadDb;

        InvalidContentController(SQLiteDatabase badDb) {
            super(getInstrumentation().getTargetContext());
            mBadDb = badDb;
        }

        @Override
        public SQLiteDatabase getDb() {
            return mBadDb;
        }
    }
}
