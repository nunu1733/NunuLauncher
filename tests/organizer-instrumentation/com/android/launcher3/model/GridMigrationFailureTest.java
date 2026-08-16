package com.android.launcher3.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GridMigrationFailureTest {
    private static final String SOURCE_DB = "issue-59-controller-source.db";
    private static final String TARGET_DB = "issue-59-controller-target.db";
    private Context context;
    private DeviceGridState previousState;
    private DeviceGridState sourceState;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        LauncherAppState.getIDP(context);
        previousState = new DeviceGridState(context);
        sourceState = new DeviceGridState(3, 3, 3, InvariantDeviceProfile.TYPE_PHONE, SOURCE_DB);
        GridMigrationTestSupport.deleteDatabase(context, SOURCE_DB);
        GridMigrationTestSupport.deleteDatabase(context, TARGET_DB);
        GridMigrationTestSupport.writeGridState(context, sourceState);
    }

    @After
    public void tearDown() {
        GridMigrationTestSupport.writeGridState(context, previousState);
        GridMigrationTestSupport.deleteDatabase(context, SOURCE_DB);
        GridMigrationTestSupport.deleteDatabase(context, TARGET_DB);
    }

    @Test
    public void transactionCloseDelegateThenThrowReconcilesCommittedTarget() {
        Fixture fixture = fixture();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.runtime.executed(GridMigrationOperation.TRANSACTION_CLOSE));
        GridMigrationTestSupport.assertLocks(fixture.source.getWritableDatabase(), 2, 1);
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        assertFalse(GridMigrationTestSupport.journal(context, TARGET_DB).exists());
    }

    @Test
    public void sourceDetachDelegateThenThrowReconcilesCommittedTarget() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.SOURCE_DETACH);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.runtime.executed(GridMigrationOperation.SOURCE_DETACH));
        assertTrue(fixture.controller.isSourcePublished());
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        assertFalse(GridMigrationTestSupport.journal(context, TARGET_DB).exists());
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.assertLocks(target.getWritableDatabase(), 2);
        }
    }

    @Test
    public void sourceDetachBeforeDelegateFailureRetainsDurableRecovery() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failBeforeDelegate(GridMigrationOperation.SOURCE_DETACH);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.controller.isSourcePublished());
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        assertFalse(GridMigrationTestSupport.journal(context, TARGET_DB).exists());
    }

    @Test
    public void sourceCloseDelegateThenThrowReopensSourceHelper() {
        Fixture fixture = fixture();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.SOURCE_HELPER_CLOSE);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.runtime.executed(GridMigrationOperation.SOURCE_HELPER_CLOSE));
        assertTrue(fixture.controller.isSourcePublished());
        assertNotSame(fixture.source, fixture.controller.publishedHelper());
        GridMigrationTestSupport.assertLocks(fixture.controller.getDb(), 2, 1);
    }

    @Test
    public void destinationPreferenceDelegateThenThrowRestoresSourceAuthority() {
        Fixture fixture = fixture();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.DESTINATION_PREF_WRITE);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        assertFalse(GridMigrationTestSupport.journal(context, TARGET_DB).exists());
        assertTrue(fixture.controller.generateNewItemId() > 2);
    }

    @Test
    public void targetRestoreDelegateThenThrowLeavesRecoveryPendingAndRetriesOnNextEntry() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.TARGET_RESTORE);
        fixture.runtime.failAfterDelegate(GridMigrationOperation.DESTINATION_PREF_WRITE);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.runtime.executed(GridMigrationOperation.TARGET_RESTORE));
        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.RESTORE_FAILED, TARGET_DB, SOURCE_DB, sourceState);
        fixture.controller.tryMigrateDB(null);
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
    }

    @Test
    public void targetRestoreBeforeDelegateFailureRecordsRestoreFailedAndRetriesOnNextEntry() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failBeforeDelegate(GridMigrationOperation.TARGET_RESTORE);
        fixture.runtime.failAfterDelegate(GridMigrationOperation.DESTINATION_PREF_WRITE);

        try {
            fixture.controller.tryMigrateDB(null);
        } catch (RuntimeException failure) {
            fail("Public controller entry must retain recoverable restore failure: " + failure);
        }

        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.RESTORE_FAILED, TARGET_DB, SOURCE_DB, sourceState);
        fixture.controller.tryMigrateDB(null);
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
    }

    @Test
    public void processDeathAfterTransactionCloseWithSourcePreferencesRestoresTargetOnFreshEntry() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);

        expectProcessDeath(fixture.controller);
        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.MIGRATED_PENDING_FINALIZATION, TARGET_DB, SOURCE_DB,
                sourceState);
        Fixture fresh = freshFixture(sourceState, false);
        fresh.controller.tryMigrateDB(null);

        assertTrue(fresh.controller.isSourcePublished());
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.assertLocks(target.getWritableDatabase(), 2);
        }
    }

    @Test
    public void processDeathAfterTransactionCloseWithDestinationPreferencesFinalizesTargetOnFreshEntry() {
        Fixture fixture = fixture();
        fixture.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);

        expectProcessDeath(fixture.controller);
        DeviceGridState destination = new DeviceGridState(
                4, 3, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB);
        GridMigrationTestSupport.writeGridState(context, destination);
        Fixture fresh = freshFixture(destination, false);
        fresh.controller.tryMigrateDB(null);

        assertEquals(TARGET_DB, fresh.controller.publishedHelper().getDatabaseName());
        GridMigrationTestSupport.assertTargetIsUnknown(context, TARGET_DB);
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
    }

    @Test
    public void pendingMigrationWithUnknownPreferencesRestoresSourceOnFreshEntry() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);

        expectProcessDeath(fixture.controller);
        DeviceGridState unknown = new DeviceGridState(
                5, 5, 5, InvariantDeviceProfile.TYPE_PHONE, SOURCE_DB);
        Fixture fresh = freshFixture(unknown, false);
        fresh.controller.tryMigrateDB(null);

        assertTrue(fresh.controller.isSourcePublished());
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.assertLocks(target.getWritableDatabase(), 2);
        }
    }

    @Test
    public void crashBeforePreferencesRestoresTarget() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.SOURCE_HELPER_CLOSE);

        fixture.controller.tryMigrateDB(null);

        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.assertLocks(target.getWritableDatabase(), 2);
        }
        assertFalse(GridMigrationTestSupport.journal(context, TARGET_DB).exists());
    }

    @Test
    public void finalizedCleanupFailureLeavesMetadataForRetry() {
        Fixture fixture = fixture();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.TARGET_DELETE);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.runtime.executed(GridMigrationOperation.TARGET_DELETE));
        GridMigrationTestSupport.assertTargetIsUnknown(context, TARGET_DB);
        GridMigrationTestSupport.assertRecoveryMetadataPresent(context, TARGET_DB);
        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.FINALIZED, TARGET_DB, SOURCE_DB,
                new DeviceGridState(context));

        fixture.controller.tryMigrateDB(null);
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
    }

    @Test
    public void finalizedCleanupFailureWithCorruptBackupRestoresSourceAuthorityOnFreshEntry() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.TARGET_DELETE);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.FINALIZED, TARGET_DB, SOURCE_DB,
                new DeviceGridState(context));
        GridMigrationTestSupport.assertRecoveryMetadataPresent(context, TARGET_DB);
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.mutateBackupFavorite(target.getWritableDatabase(), 50);
        }
        Fixture fresh = freshFixture(new DeviceGridState(context), false);

        fresh.controller.tryMigrateDB(null);

        assertEquals(SOURCE_DB, fresh.controller.publishedHelper().getDatabaseName());
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.RESTORE_FAILED, TARGET_DB, SOURCE_DB, sourceState);
        GridMigrationTestSupport.assertRecoveryMetadataPresent(context, TARGET_DB);
    }

    @Test
    public void finalizedValidationWithMissingSourceAbortsBeforeTargetCanBeUsed() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.TARGET_DELETE);

        fixture.controller.tryMigrateDB(null);

        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.mutateBackupFavorite(target.getWritableDatabase(), 50);
        }
        GridMigrationTestSupport.deleteDatabase(context, SOURCE_DB);
        Fixture fresh = freshFixture(new DeviceGridState(context), false);

        try {
            fresh.controller.tryMigrateDB(null);
            fail("Missing finalized source must abort instead of publishing the target");
        } catch (RuntimeException expected) {
            assertNull(fresh.controller.publishedHelper());
        }
    }

    @Test
    public void finalizedJournalWithTargetAsSourceAbortsActiveTargetMigration() {
        DeviceGridState destination = new DeviceGridState(
                4, 3, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB);
        GridMigrationTestSupport.createDurablePhaseFixture(context, TARGET_DB, TARGET_DB,
                sourceState, destination, GridMigrationJournal.Phase.FINALIZED);
        Fixture active = freshFixture(destination, false);

        try {
            active.controller.tryMigrateDB(null);
            fail("A finalized journal whose source is the target must abort");
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void restoreFailureDoesNotClaimRollback() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.TARGET_RESTORE);
        fixture.runtime.failAfterDelegate(GridMigrationOperation.DESTINATION_PREF_WRITE);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.RESTORE_FAILED, TARGET_DB, SOURCE_DB, sourceState);
    }

    @Test
    public void repeatedReconciliationIsIdempotent() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.failAfterDelegate(GridMigrationOperation.DESTINATION_PREF_WRITE);

        fixture.controller.tryMigrateDB(null);
        fixture.controller.tryMigrateDB(null);
        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
    }

    @Test
    public void finalizedJournalIsDeletedBeforeLaterMigration() {
        Fixture fixture = fixture();

        fixture.controller.tryMigrateDB(null);
        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
    }

    @Test
    public void unknownPreferencesRestoreAndProgressForCandidateAdmission() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);
        expectProcessDeath(fixture.controller);
        DeviceGridState unknown = new DeviceGridState(
                5, 5, 5, InvariantDeviceProfile.TYPE_PHONE, SOURCE_DB);
        Fixture fresh = freshFixture(unknown, false);

        fresh.controller.tryMigrateDB(null);

        assertTrue(fresh.controller.isSourcePublished());
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
    }

    @Test
    public void unknownPreferencesRestoreAndProgressForActiveAdmission() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);
        expectProcessDeath(fixture.controller);
        DeviceGridState unknownTarget = new DeviceGridState(
                5, 5, 5, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB);
        Fixture fresh = freshFixture(unknownTarget, false);

        fresh.controller.tryMigrateDB(null);

        assertEquals(SOURCE_DB, fresh.controller.publishedHelper().getDatabaseName());
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
    }

    @Test
    public void sourceCommitFalseLeavesRestoreFailed() {
        Fixture fixture = fixture();
        fixture.runtime.preferenceWriteResults(false, false);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.RESTORE_FAILED, TARGET_DB, SOURCE_DB, sourceState);
    }

    @Test
    public void destinationCommitFalseCompensatesAndRestores() {
        Fixture fixture = fixtureWithPreexistingTarget();
        fixture.runtime.preferenceWriteResults(false, true);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.controller.isSourcePublished());
        GridMigrationTestSupport.assertGridState(sourceState, new DeviceGridState(context));
        GridMigrationTestSupport.assertRecoveryMetadataAbsent(context, TARGET_DB);
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.assertLocks(target.getWritableDatabase(), 2);
        }
    }

    @Test
    public void restoreDigestMismatchRetainsJournalAndBackup() {
        DeviceGridState destination = new DeviceGridState(
                4, 3, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB);
        GridMigrationTestSupport.createDurablePhaseFixture(context, TARGET_DB, SOURCE_DB,
                sourceState, destination, GridMigrationJournal.Phase.MIGRATED_PENDING_FINALIZATION);
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.mutateBackupFavorite(target.getWritableDatabase(), 50);
        }
        Fixture fresh = freshFixture(sourceState, false);

        fresh.controller.tryMigrateDB(null);

        assertEquals(SOURCE_DB, fresh.controller.publishedHelper().getDatabaseName());
        GridMigrationTestSupport.assertJournal(GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.RESTORE_FAILED, TARGET_DB, SOURCE_DB, sourceState);
        GridMigrationTestSupport.assertRecoveryMetadataPresent(context, TARGET_DB);
    }

    @Test
    public void activeAndCandidateReconciliationHavePerPhaseParity() {
        Fixture candidate = fixtureWithPreexistingTarget();
        candidate.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);
        expectProcessDeath(candidate.controller);
        Fixture candidateEntry = freshFixture(sourceState, false);
        candidateEntry.controller.tryMigrateDB(null);
        boolean candidateRecovered = !GridMigrationTestSupport.journal(context, TARGET_DB).exists();

        GridMigrationTestSupport.deleteDatabase(context, TARGET_DB);
        Fixture active = fixtureWithPreexistingTarget();
        active.runtime.terminateAfterDelegate(GridMigrationOperation.TRANSACTION_CLOSE);
        expectProcessDeath(active.controller);
        DeviceGridState targetState = new DeviceGridState(
                5, 5, 5, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB);
        Fixture activeEntry = freshFixture(targetState, false);
        activeEntry.controller.tryMigrateDB(null);

        assertEquals(candidateRecovered,
                !GridMigrationTestSupport.journal(context, TARGET_DB).exists());
        assertEquals(candidateEntry.controller.publishedHelper().getDatabaseName(),
                activeEntry.controller.publishedHelper().getDatabaseName());
    }

    @Test
    public void sameDatabaseIsRejectedBeforeJournal() {
        Fixture fixture = fixture();
        fixture.controller.useTargetDatabaseName(SOURCE_DB);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertLocks(fixture.source.getWritableDatabase(), 2, 1);
        assertFalse(GridMigrationTestSupport.journal(context, SOURCE_DB).exists());
    }

    private Fixture fixture() {
        DatabaseHelper source = GridMigrationTestSupport.open(context, SOURCE_DB);
        GridMigrationTestSupport.seedSource(source.getWritableDatabase());
        GridMigrationTestSupport.ScriptedRuntime runtime = new GridMigrationTestSupport.ScriptedRuntime(
                false);
        return new Fixture(source, runtime, new GridMigrationSuccessTest.Controller(context, source,
                new DeviceGridState(4, 3, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB), runtime));
    }

    private Fixture freshFixture(DeviceGridState activeState, boolean fastPath) {
        GridMigrationTestSupport.writeGridState(context, activeState);
        DatabaseHelper active = GridMigrationTestSupport.open(context, activeState.getDbFile());
        GridMigrationTestSupport.ScriptedRuntime runtime = new GridMigrationTestSupport.ScriptedRuntime(
                fastPath);
        return new Fixture(active, runtime, new GridMigrationSuccessTest.Controller(context, active,
                new DeviceGridState(4, 3, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB), runtime));
    }

    private static void expectProcessDeath(ModelDbController controller) {
        try {
            controller.tryMigrateDB(null);
            fail("A simulated process death must escape normal RuntimeException compensation");
        } catch (GridMigrationTestSupport.SimulatedProcessDeath expected) {
        }
    }

    private Fixture fixtureWithPreexistingTarget() {
        Fixture fixture = fixture();
        try (DatabaseHelper target = GridMigrationTestSupport.open(context, TARGET_DB)) {
            GridMigrationTestSupport.insertFavorite(target.getWritableDatabase(), 50, 2);
        }
        return fixture;
    }

    private static final class Fixture {
        final DatabaseHelper source;
        final GridMigrationTestSupport.ScriptedRuntime runtime;
        final GridMigrationSuccessTest.Controller controller;

        Fixture(DatabaseHelper source, GridMigrationTestSupport.ScriptedRuntime runtime,
                GridMigrationSuccessTest.Controller controller) {
            this.source = source;
            this.runtime = runtime;
            this.controller = controller;
        }

    }
}
