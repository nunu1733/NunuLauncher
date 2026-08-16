package com.android.launcher3.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings.Favorites;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GridMigrationSuccessTest {
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
    public void initialTargetTransactionContainsBackupJournalMigrationUnknownAndTmpCleanup() {
        ControllerFixture fixture = controllerFixture(false);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertTargetIsUnknown(context, TARGET_DB);
        assertFalse(GridMigrationTestSupport.tableExists(fixture.controller.getDb(), Favorites.TMP_TABLE));
        GridMigrationTestSupport.assertJournal(
                GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.FINALIZED, TARGET_DB, SOURCE_DB,
                new DeviceGridState(context));
    }

    @Test
    public void fastControllerMigrationPublishesUnknownTargetAfterTransactionClose() {
        ControllerFixture fixture = controllerFixture(true);

        fixture.controller.tryMigrateDB(null);

        assertTrue(fixture.controller.isTargetPublished());
        assertFalse(fixture.runtime.executed(GridMigrationOperation.PLACEMENT));
        GridMigrationTestSupport.assertTargetIsUnknown(context, TARGET_DB);
        GridMigrationTestSupport.assertJournal(
                GridMigrationTestSupport.journal(context, TARGET_DB),
                GridMigrationJournal.Phase.FINALIZED, TARGET_DB, SOURCE_DB,
                new DeviceGridState(context));
    }

    @Test
    public void generalControllerMigrationPreservesSourceUntilTargetIsPublished() {
        ControllerFixture fixture = controllerFixture(false);

        fixture.controller.tryMigrateDB(null);

        GridMigrationTestSupport.assertLocks(fixture.source.getWritableDatabase(), 2, 1);
        assertTrue(fixture.runtime.executed(GridMigrationOperation.PLACEMENT));
        GridMigrationTestSupport.assertTargetIsUnknown(context, TARGET_DB);
    }

    private ControllerFixture controllerFixture(boolean fastPath) {
        DatabaseHelper source = GridMigrationTestSupport.open(context, SOURCE_DB);
        GridMigrationTestSupport.seedSource(source.getWritableDatabase());
        DeviceGridState targetState = fastPath
                ? new DeviceGridState(3, 4, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB)
                : new DeviceGridState(4, 3, 3, InvariantDeviceProfile.TYPE_PHONE, TARGET_DB);
        GridMigrationTestSupport.ScriptedRuntime runtime =
                new GridMigrationTestSupport.ScriptedRuntime(fastPath);
        return new ControllerFixture(source, runtime, new Controller(context, source, targetState, runtime));
    }

    private static final class ControllerFixture {
        final DatabaseHelper source;
        final GridMigrationTestSupport.ScriptedRuntime runtime;
        final Controller controller;

        ControllerFixture(DatabaseHelper source, GridMigrationTestSupport.ScriptedRuntime runtime,
                Controller controller) {
            this.source = source;
            this.runtime = runtime;
            this.controller = controller;
        }
    }

    static final class Controller extends ModelDbController {
        private final Context context;
        private final DatabaseHelper source;
        private final DeviceGridState targetState;
        private DatabaseHelper target;
        private String targetDatabaseName;

        Controller(Context context, DatabaseHelper source, DeviceGridState targetState,
                GridMigrationRuntime runtime) {
            super(context, runtime);
            this.context = context;
            this.source = source;
            this.targetState = targetState;
            targetDatabaseName = targetState.getDbFile();
        }

        @Override
        protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
            if (!forMigration) return source;
            target = GridMigrationTestSupport.open(context, targetDatabaseName);
            return target;
        }

        @Override
        protected String getMigrationTargetDatabaseName(InvariantDeviceProfile ignored) {
            return targetDatabaseName;
        }

        @Override
        protected DeviceGridState getMigrationDestinationState(InvariantDeviceProfile ignored) {
            return targetState;
        }

        boolean isTargetPublished() {
            return mOpenHelper == target;
        }

        boolean isSourcePublished() {
            return mOpenHelper != null
                    && source.getDatabaseName().equals(mOpenHelper.getDatabaseName());
        }

        DatabaseHelper publishedHelper() {
            return mOpenHelper;
        }

        void useTargetDatabaseName(String databaseName) {
            targetDatabaseName = databaseName;
        }
    }
}
