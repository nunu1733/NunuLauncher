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
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.model.DatabaseHelper;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.RestoreDbTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
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

    private static final String REPLACEMENT_DB_NAME = "restore-helper-replacement-source.db";
    private final LayoutWriteCoordinator mCoordinator = LayoutWriteCoordinator.getInstance();

    @Before
    public void setUpFixtureFiles() {
        Context context = ApplicationProvider.getApplicationContext();
        deleteDatabaseFileSet(context, HelperProbeController.TEST_DB_NAME);
        deleteDatabaseFileSet(context, REPLACEMENT_DB_NAME);
    }

    @After
    public void tearDownFixtureFiles() {
        Context context = ApplicationProvider.getApplicationContext();
        deleteDatabaseFileSet(context, HelperProbeController.TEST_DB_NAME);
        deleteDatabaseFileSet(context, REPLACEMENT_DB_NAME);
    }

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
                mCoordinator.acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.BACKUP_RESTORE);
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
     * Issue #120 matrix case 1 / SR-06 / AC-4: closeActiveHelperForRestore closes the active
     * helper (no open handle on the DB files, mOpenHelper cleared) and the next getDb lazily
     * constructs a fresh helper on the same controller after main-file-only deletion.
     */
    @Test
    public void closeActiveHelperClosesHandleAndReopensFreshHelper() {
        Context appContext = ApplicationProvider.getApplicationContext();
        HelperProbeController controller = new HelperProbeController(appContext);
        try {
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
            // SELECT is a query; execSQL routes through the changed-row-count path and is
            // rejected by API 36. Use the production read seam for this health check.
            try (Cursor cursor = after.rawQuery("SELECT 1", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(0));
            }
        } finally {
            controller.closeActiveHelperForRestore();
        }
    }

    /**
     * Issue #120 matrix case 2: production restores replace the complete SQLite file set,
     * not only the main database. The same controller must lazily reopen the replacement.
     */
    @Test
    public void fullFileSetReplacementReopensOnSameController() {
        Context context = ApplicationProvider.getApplicationContext();
        HelperProbeController controller = new HelperProbeController(context);
        File replacement = createReplacementDatabase(context);
        try {
            controller.getDb();
            controller.closeActiveHelperForRestore();

            replaceDatabaseFileSet(replacement,
                    context.getDatabasePath(HelperProbeController.TEST_DB_NAME));

            SQLiteDatabase reopened = controller.getDb();
            assertReplacementMarker(reopened);
        } finally {
            controller.closeActiveHelperForRestore();
        }
    }

    /**
     * Issue #120 matrix case 3: a fresh controller (the closest in-process analogue to the
     * production process restart) must observe the replacement file set without inheriting
     * the closed controller's helper or database handle.
     */
    @Test
    public void fullFileSetReplacementReopensWithFreshController() {
        Context context = ApplicationProvider.getApplicationContext();
        HelperProbeController activeController = new HelperProbeController(context);
        HelperProbeController freshController = null;
        File replacement = createReplacementDatabase(context);
        try {
            activeController.getDb();
            activeController.closeActiveHelperForRestore();
            replaceDatabaseFileSet(replacement,
                    context.getDatabasePath(HelperProbeController.TEST_DB_NAME));

            freshController = new HelperProbeController(context);
            SQLiteDatabase reopened = freshController.getDb();
            assertReplacementMarker(reopened);
        } finally {
            activeController.closeActiveHelperForRestore();
            if (freshController != null) {
                freshController.closeActiveHelperForRestore();
            }
        }
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
        final String badDatabaseName = "restore-fault-injection.db";
        deleteDatabaseFileSet(context, badDatabaseName);
        File bad = context.getDatabasePath(badDatabaseName);
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
            deleteDatabaseFileSet(context, badDatabaseName);
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

    private static File createReplacementDatabase(Context context) {
        DatabaseHelper helper = new DatabaseHelper(context, REPLACEMENT_DB_NAME,
                user -> 0L, () -> { });
        try {
            SQLiteDatabase database = helper.getWritableDatabase();
            database.execSQL("CREATE TABLE issue120_replacement_marker (value TEXT NOT NULL)");
            database.execSQL(
                    "INSERT INTO issue120_replacement_marker(value) VALUES ('replacement')");
        } finally {
            helper.close();
        }
        return context.getDatabasePath(REPLACEMENT_DB_NAME);
    }

    private static void assertReplacementMarker(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery(
                "SELECT value FROM issue120_replacement_marker", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("replacement", cursor.getString(0));
        }
    }

    private static void replaceDatabaseFileSet(File source, File target) {
        deleteDatabaseFileSet(target);
        for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
            File sourceFile = new File(source.getPath() + suffix);
            if (sourceFile.exists()) {
                copyFile(sourceFile, new File(target.getPath() + suffix));
            }
        }
        assertTrue("replacement main database must be present", target.isFile());
    }

    private static void deleteDatabaseFileSet(Context context, String databaseName) {
        deleteDatabaseFileSet(context.getDatabasePath(databaseName));
    }

    private static void deleteDatabaseFileSet(File database) {
        for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
            File artifact = new File(database.getPath() + suffix);
            if (artifact.exists()) {
                assertTrue("SQLite fixture artifact must be deleted: " + artifact,
                        artifact.delete());
            }
            assertFalse("SQLite fixture artifact must be absent: " + artifact,
                    artifact.exists());
        }
    }

    private static void copyFile(File source, File target) {
        try (FileInputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } catch (IOException e) {
            throw new AssertionError("Unable to replace SQLite file set", e);
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
