/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.model;

import static android.provider.BaseColumns._ID;
import static android.util.Base64.NO_PADDING;
import static android.util.Base64.NO_WRAP;

import static com.android.launcher3.DefaultLayoutParser.RES_PARTNER_DEFAULT_LAYOUT;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APP_PAIR;
import static com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME;
import static com.android.launcher3.LauncherSettings.Favorites.addTableToDb;
import static com.android.launcher3.LauncherSettings.Settings.LAYOUT_DIGEST_KEY;
import static com.android.launcher3.LauncherSettings.Settings.LAYOUT_DIGEST_LABEL;
import static com.android.launcher3.LauncherSettings.Settings.LAYOUT_DIGEST_TAG;
import static com.android.launcher3.provider.LauncherDbUtils.tableExists;

import android.app.blob.BlobHandle;
import android.app.blob.BlobStoreManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.launcher3.AutoInstallsLayout;
import com.android.launcher3.AutoInstallsLayout.SourceResources;
import com.android.launcher3.ConstantItem;
import com.android.launcher3.DefaultLayoutParser;
import com.android.launcher3.EncryptionType;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.LauncherPreviewRenderer;
import com.android.launcher3.backuprestore.LauncherRestoreEventLogger;
import com.android.launcher3.backuprestore.LauncherRestoreEventLogger.RestoreError;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.provider.LauncherDbUtils;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.provider.RestoreDbTask;
import com.android.launcher3.util.IOUtils;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.MainThreadInitializedObject.SandboxContext;
import com.android.launcher3.util.Partner;
import com.android.launcher3.widget.LauncherWidgetHolder;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

import app.lawnchair.LawnchairApp;
import app.lawnchair.LawnchairAppKt;

/**
 * Utility class which maintains an instance of Launcher database and provides
 * utility methods
 * around it.
 */
public class ModelDbController {
    private static final String TAG = "LauncherProvider";

    private static final String EMPTY_DATABASE_CREATED = "EMPTY_DATABASE_CREATED";
    public static final String EXTRA_DB_NAME = "db_name";

    protected DatabaseHelper mOpenHelper;

    private final Context mContext;
    private final GridMigrationRuntime mGridMigrationRuntime;

    public ModelDbController(Context context) {
        this(context, GridMigrationRuntime.DIRECT);
    }

    ModelDbController(Context context, GridMigrationRuntime gridMigrationRuntime) {
        mContext = context;
        mGridMigrationRuntime = gridMigrationRuntime;
    }

    private synchronized void createDbIfNotExists() {
        if (mOpenHelper == null) {
            mOpenHelper = createDatabaseHelper(false /* forMigration */);
            RestoreDbTask.restoreIfNeeded(mContext, this);
        }
    }

    protected DatabaseHelper createDatabaseHelper(boolean forMigration) {
        boolean isSandbox = mContext instanceof SandboxContext;
        String dbName = isSandbox ? null : InvariantDeviceProfile.INSTANCE.get(mContext).dbFile;

        try {
            if (!forMigration && dbName != null) {
                LawnchairApp app = LawnchairAppKt.getLawnchairApp(mContext);
                app.renameRestoredDb(dbName);
                app.migrateDbName(dbName);
            }
        } catch (Throwable t) {
            // ignore
        }

        // Set the flag for empty DB
        Runnable onEmptyDbCreateCallback = forMigration ? () -> {
        }
                : () -> LauncherPrefs.get(mContext).putSync(getEmptyDbCreatedKey(dbName).to(true));

        DatabaseHelper databaseHelper = new DatabaseHelper(mContext, dbName,
                this::getSerialNumberForUser, onEmptyDbCreateCallback);
        return initializeDatabaseHelper(databaseHelper);
    }

    private DatabaseHelper initializeDatabaseHelper(DatabaseHelper databaseHelper) {
        // Table creation sometimes fails silently, which leads to a crash loop.
        // This way, we will try to create a table every time after crash, so the device
        // would eventually be able to recover.
        if (!tableExists(databaseHelper.getReadableDatabase(), Favorites.TABLE_NAME)) {
            Log.e(TAG, "Tables are missing after onCreate has been called. Trying to recreate");
            // This operation is a no-op if the table already exists.
            addTableToDb(databaseHelper.getWritableDatabase(),
                    getSerialNumberForUser(Process.myUserHandle()),
                    true /* optional */);
        }
        databaseHelper.mHotseatRestoreTableExists = tableExists(
                databaseHelper.getReadableDatabase(), Favorites.HYBRID_HOTSEAT_BACKUP_TABLE);

        databaseHelper.initIds();
        return databaseHelper;
    }

    protected String getMigrationTargetDatabaseName(InvariantDeviceProfile idp) {
        return idp.dbFile;
    }

    protected DeviceGridState getMigrationDestinationState(InvariantDeviceProfile idp) {
        return new DeviceGridState(idp);
    }

    /**
     * Refer {@link SQLiteDatabase#query}
     */
    @WorkerThread
    public Cursor query(String table, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        createDbIfNotExists();
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor result = db.query(
                table, projection, selection, selectionArgs, null, null, sortOrder);

        final Bundle extra = new Bundle();
        extra.putString(EXTRA_DB_NAME, mOpenHelper.getDatabaseName());
        result.setExtras(extra);
        return result;
    }

    /**
     * Refer {@link SQLiteDatabase#insert(String, String, ContentValues)}
     */
    @WorkerThread
    public int insert(String table, ContentValues initialValues) {
        createDbIfNotExists();
        try (LayoutWriteCoordinator.Lease ignored = acquireMutationLease()) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            addModifiedTime(initialValues);
            int rowId = mOpenHelper.dbInsertAndCheck(db, table, initialValues);
            if (rowId >= 0) {
                onAddOrDeleteOp(db);
            }
            return rowId;
        }
    }

    /**
     * Refer {@link SQLiteDatabase#delete(String, String, String[])}
     */
    @WorkerThread
    public int delete(String table, String selection, String[] selectionArgs) {
        createDbIfNotExists();
        try (LayoutWriteCoordinator.Lease ignored = acquireMutationLease()) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            int count = db.delete(table, selection, selectionArgs);
            if (count > 0) {
                onAddOrDeleteOp(db);
            }
            return count;
        }
    }

    /**
     * Refer {@link SQLiteDatabase#update(String, ContentValues, String, String[])}
     */
    @WorkerThread
    public int update(String table, ContentValues values,
            String selection, String[] selectionArgs) {
        createDbIfNotExists();

        try (LayoutWriteCoordinator.Lease ignored = acquireMutationLease()) {
            addModifiedTime(values);
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            return db.update(table, values, selection, selectionArgs);
        }
    }

    /**
     * Clears a previously set flag corresponding to empty db creation
     */
    @WorkerThread
    public void clearEmptyDbFlag() {
        createDbIfNotExists();
        clearFlagEmptyDbCreated();
    }

    /**
     * Generates an id to be used for new item in the favorites table
     */
    @WorkerThread
    public int generateNewItemId() {
        createDbIfNotExists();
        return mOpenHelper.generateNewItemId();
    }

    /**
     * Generates an id to be used for new workspace screen
     */
    @WorkerThread
    public int getNewScreenId() {
        createDbIfNotExists();
        return mOpenHelper.getNewScreenId();
    }

    /**
     * Creates an empty DB clearing all existing data
     */
    @WorkerThread
    public void createEmptyDB() {
        createDbIfNotExists();
        try (LayoutWriteCoordinator.Lease ignored = acquireMutationLease()) {
            mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
            LauncherPrefs.get(mContext).putSync(getEmptyDbCreatedKey().to(true));
        }
    }

    /**
     * Removes any widget which are present in the framework, but not in out
     * internal DB
     */
    @WorkerThread
    public void removeGhostWidgets() {
        createDbIfNotExists();
        try (LayoutWriteCoordinator.Lease ignored = acquireMutationLease()) {
            mOpenHelper.removeGhostWidgets(mOpenHelper.getWritableDatabase());
        }
    }

    /**
     * Returns a new {@link SQLiteTransaction}
     */
    @WorkerThread
    public SQLiteTransaction newTransaction() {
        createDbIfNotExists();
        // Issue #14: hold the coordinator lease through the transaction close/endTransaction.
        return new SQLiteTransaction(mOpenHelper.getWritableDatabase(),
                getCoordinatorLease());
    }

    // Issue #14: organizer re-entry requires the exact outer capability token.
    public SQLiteTransaction newTransaction(long organizerToken) {
        createDbIfNotExists();
        LayoutWriteCoordinator.Lease lease = LayoutWriteCoordinator.getInstance()
                .tryAcquireOrganizerLease(organizerToken);
        if (lease == null) {
            throw new IllegalStateException("Organizer transaction lacks its exact writer lease");
        }
        return new SQLiteTransaction(mOpenHelper.getWritableDatabase(), lease);
    }

    // Issue #14: acquire the coordinator lease for organizer transactions;
    // the lease is held only through close() — it does not block MODEL_EXECUTOR.
    @Nullable
    private LayoutWriteCoordinator.Lease getCoordinatorLease() {
        LayoutWriteCoordinator coordinator = LayoutWriteCoordinator.getInstance();
        // Issue #14: the exact correlated loader holds a scoped organizer capability. Its
        // cleanup mutations must not block MODEL_EXECUTOR behind the outer organizer lease.
        LayoutWriteCoordinator.Lease organizerLease = coordinator.tryAcquireOrganizerCapability(
                currentOrganizerToken());
        if (organizerLease != null) {
            return organizerLease;
        }
        // Issue #58: DB mutations issued by a restore (e.g. RestoreDbTask widget-id remap
        // through ContentWriter) run on the thread that already holds a restore-family
        // lease; they reenter it instead of blocking on MODEL_WRITER (self-deadlock).
        LayoutWriteCoordinator.Lease restoreLease = coordinator.tryReenterRestoreFamily();
        if (restoreLease != null) {
            return restoreLease;
        }
        return coordinator.acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.MODEL_WRITER);
    }

    private long currentOrganizerToken() {
        // A nonmatching value intentionally falls through to ordinary serialization.
        // The coordinator validates the thread-scoped capability before returning a lease.
        return LayoutWriteCoordinator.getInstance().getActiveOrganizerToken();
    }

    // Issue #14: central gate for auto-transaction insert/update/delete calls.
    @NonNull
    private LayoutWriteCoordinator.Lease acquireMutationLease() {
        return getCoordinatorLease();
    }

    /**
     * Refreshes the internal state corresponding to presence of hotseat table
     */
    @WorkerThread
    public void refreshHotseatRestoreTable() {
        createDbIfNotExists();
        mOpenHelper.mHotseatRestoreTableExists = tableExists(
                mOpenHelper.getReadableDatabase(), Favorites.HYBRID_HOTSEAT_BACKUP_TABLE);
    }

    public void tryMigrateDB(@Nullable LauncherRestoreEventLogger restoreEventLogger) {

        if (!migrateGridIfNeeded()) {
            if (restoreEventLogger != null) {
                sendMetricsForFailedMigration(restoreEventLogger, getDb());
            }
            FileLog.d(TAG, "Migration failed: retaining launcher database");
        }
    }

    private boolean migrateGridIfNeeded() {
        createDbIfNotExists();
        DatabaseHelper activeHelper = mOpenHelper;
        Reconciliation activeReconciliation;
        try {
            activeReconciliation = reconcileActiveDatabaseJournal();
        } catch (GridMigrationRecoveryPendingException recoveryPending) {
            throw recoveryPending;
        } catch (RuntimeException recoveryFailure) {
            throw failClosedActiveRecovery(activeHelper, recoveryFailure);
        }
        if (activeReconciliation == Reconciliation.COMPLETED) {
            return true;
        }
        if (activeReconciliation == Reconciliation.FAILED) {
            return false;
        }
        if (LauncherPrefs.get(mContext).get(getEmptyDbCreatedKey())) {
            // If we have already create a new DB, ignore migration
            Log.d(TAG, "migrateGridIfNeeded: new DB already created, skipping migration");
            return false;
        }
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        DeviceGridState destinationState = getMigrationDestinationState(idp);
        DeviceGridState sourceState = new DeviceGridState(mContext);
        if (destinationState.isCompatible(sourceState)) {
            Log.d(TAG, "migrateGridIfNeeded: no grid migration needed");
            return true;
        }
        String targetDbName = getMigrationTargetDatabaseName(idp);
        if (TextUtils.equals(targetDbName, mOpenHelper.getDatabaseName())) {
            Log.e(TAG, "migrateGridIfNeeded: target db is same as current: " + targetDbName);
            return false;
        }
        DatabaseHelper sourceHelper = mOpenHelper;
        try (LayoutWriteCoordinator.Lease ignored = LayoutWriteCoordinator.getInstance()
                .acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.GRID_MIGRATION)) {
            DatabaseHelper targetHelper = null;
            try {
                targetHelper = (mContext instanceof SandboxContext) ? sourceHelper
                        : createDatabaseHelper(true /* forMigration */);
                if (targetHelper == sourceHelper) {
                    return false;
                }
                SQLiteDatabase sourceDatabase = sourceHelper.getWritableDatabase();
                SQLiteDatabase targetDatabase = targetHelper.getWritableDatabase();
                targetHelper.refreshMaxItemIdFromCommittedRows();
                if (sameDatabase(sourceDatabase, targetDatabase)) {
                    targetHelper.close();
                    return false;
                }

                GridMigrationJournal.Entry existingJournal = GridMigrationJournal.read(targetDatabase);
                if (existingJournal.exists()) {
                    Reconciliation reconciliation;
                    try {
                        reconciliation = reconcileDurableJournal(
                                sourceHelper, targetHelper, existingJournal);
                    } catch (GridMigrationRecoveryPendingException recoveryPending) {
                        throw recoveryPending;
                    } catch (RuntimeException recoveryFailure) {
                        // Issue #59: if recovery swapped the active helper mid-failure, the
                        // published helper is not trusted for normal loading and the failure
                        // must escape instead of becoming a boolean migration failure.
                        if (mOpenHelper != sourceHelper) {
                            throw recoveryFailure;
                        }
                        FileLog.e(TAG, "Grid migration recovery remains pending", recoveryFailure);
                        return false;
                    }
                    if (reconciliation == Reconciliation.COMPLETED) {
                        return true;
                    }
                    if (reconciliation == Reconciliation.FAILED) {
                        return false;
                    }
                }

                boolean attached = false;
                SQLiteTransaction transaction = null;
                RuntimeException initialFailure = null;
                try {
                    targetDatabase.execSQL("ATTACH DATABASE ? AS from_db",
                            new Object[] { sourceDatabase.getPath() });
                    attached = true;
                    transaction = new SQLiteTransaction(targetDatabase);
                    SQLiteTransaction initialTransaction = transaction;
                    mGridMigrationRuntime.execute(GridMigrationOperation.BACKUP_SNAPSHOT,
                            () -> LauncherDbUtils.copyTable(targetDatabase, Favorites.TABLE_NAME,
                                    targetDatabase, GridMigrationJournal.BACKUP_TABLE, mContext));
                    mGridMigrationRuntime.execute(GridMigrationOperation.JOURNAL_WRITE,
                            () -> GridMigrationJournal.create(targetDatabase, targetDbName,
                                    sourceHelper.getDatabaseName(), sourceState, destinationState));
                    GridSizeMigrationUtil.migrateGridInTransaction(
                            mContext, sourceState, destinationState, targetHelper, targetDatabase,
                            mGridMigrationRuntime.enableGridMigrationFix(), mGridMigrationRuntime);
                    mGridMigrationRuntime.execute(GridMigrationOperation.JOURNAL_WRITE,
                            () -> GridMigrationJournal.setPhase(targetDatabase,
                                    GridMigrationJournal.Phase.MIGRATED_PENDING_FINALIZATION));
                    initialTransaction.commit();
                    mGridMigrationRuntime.execute(
                            GridMigrationOperation.TRANSACTION_CLOSE, initialTransaction::close);
                    transaction = null;
                } catch (RuntimeException migrationFailure) {
                    if (transaction != null && targetDatabase.inTransaction()) {
                        transaction.close();
                    }
                    initialFailure = migrationFailure;
                } finally {
                    if (attached) {
                        try {
                            mGridMigrationRuntime.execute(GridMigrationOperation.SOURCE_DETACH,
                                    () -> targetDatabase.execSQL("DETACH DATABASE from_db"));
                        } catch (RuntimeException detachFailure) {
                            if (initialFailure == null) {
                                initialFailure = detachFailure;
                            } else {
                                initialFailure.addSuppressed(detachFailure);
                            }
                        }
                    }
                }

                if (initialFailure != null) {
                    GridMigrationJournal.Entry persisted = GridMigrationJournal.read(targetDatabase);
                    if (persisted.exists()
                            && persisted.phase()
                                    == GridMigrationJournal.Phase.MIGRATED_PENDING_FINALIZATION) {
                        return compensateAndRestore(
                                sourceHelper, targetHelper, persisted, initialFailure);
                    }
                    FileLog.e(TAG, "Failed initial grid migration transaction", initialFailure);
                    return false;
                }

                targetHelper.refreshMaxItemIdFromCommittedRows();
                return finalizeMigration(sourceHelper, targetHelper,
                        GridMigrationJournal.read(targetDatabase));
            } catch (Exception e) {
                FileLog.e(TAG, "Failed to migrate grid", e);
                publishFreshSource(sourceHelper, sourceHelper.getDatabaseName());
                if (targetHelper != null
                        && GridMigrationJournal.read(targetHelper.getWritableDatabase()).exists()) {
                    FileLog.e(TAG, "Grid migration recovery remains pending", e);
                }
                return false;
            }
        }
    }

    // Issue #59: unresolved active-database recovery fails closed; the untrusted active helper is
    // quarantined unless recovery already republished a proven source.
    private RuntimeException failClosedActiveRecovery(DatabaseHelper activeHelper,
            RuntimeException recoveryFailure) {
        if (mOpenHelper != activeHelper) {
            return recoveryFailure;
        }
        try {
            activeHelper.close();
        } catch (RuntimeException quarantineFailure) {
            recoveryFailure.addSuppressed(quarantineFailure);
        } finally {
            mOpenHelper = null;
        }
        GridMigrationRecoveryPendingException recoveryPending =
                new GridMigrationRecoveryPendingException(
                        "Grid migration recovery remains pending", recoveryFailure);
        return recoveryPending;
    }

    private Reconciliation reconcileActiveDatabaseJournal() {
        SQLiteDatabase activeDatabase = mOpenHelper.getWritableDatabase();
        GridMigrationJournal.Entry journal = GridMigrationJournal.read(activeDatabase);
        if (!journal.exists()) {
            return Reconciliation.CONTINUE;
        }
        DatabaseHelper targetHelper = mOpenHelper;
        try (LayoutWriteCoordinator.Lease ignored = LayoutWriteCoordinator.getInstance()
                .acquireBlockingQuietly(LayoutWriteCoordinator.OwnerKind.GRID_MIGRATION)) {
            DatabaseHelper sourceHelper = null;
            if (journal.phase() != GridMigrationJournal.Phase.FINALIZED) {
                // Issue #59: validate the recorded source before opening a writable helper so a
                // missing or mispointed source cannot be recreated as an empty database.
                validatedJournalSourceFile(journal, targetHelper);
                sourceHelper = openJournalSource(journal.sourceDatabaseName());
            }
            return reconcileDurableJournal(sourceHelper, targetHelper, journal);
        }
    }

    private boolean finalizeMigration(DatabaseHelper sourceHelper, DatabaseHelper targetHelper,
            GridMigrationJournal.Entry journal) {
        mOpenHelper = targetHelper;
        try {
            mGridMigrationRuntime.execute(
                    GridMigrationOperation.SOURCE_HELPER_CLOSE, sourceHelper::close);
            requireGridPreferences(GridMigrationOperation.DESTINATION_PREF_WRITE,
                    journal.destinationPreferences());
            SQLiteDatabase targetDatabase = targetHelper.getWritableDatabase();
            setJournalPhase(targetDatabase, GridMigrationJournal.Phase.FINALIZED);
            validateFinalizedTarget(targetHelper, journal);
            try {
                cleanupFinalizedMetadata(targetDatabase);
            } catch (RuntimeException cleanupFailure) {
                FileLog.e(TAG, "Failed to clean finalized grid migration metadata", cleanupFailure);
            }
            return true;
        } catch (RuntimeException finalizationFailure) {
            return compensateAndRestore(
                    sourceHelper, targetHelper, journal, finalizationFailure);
        }
    }

    private boolean compensateAndRestore(DatabaseHelper sourceHelper, DatabaseHelper targetHelper,
            GridMigrationJournal.Entry journal, RuntimeException failure) {
        // Issue #59: compensation may only republish a proven journal source, never a
        // manufactured empty database.
        validatedJournalSourceFile(journal, targetHelper);
        publishFreshSource(sourceHelper, journal.sourceDatabaseName());
        boolean preferencesRestored = false;
        try {
            preferencesRestored = commitGridPreferences(GridMigrationOperation.SOURCE_PREF_WRITE,
                    journal.sourcePreferences());
        } catch (RuntimeException preferenceFailure) {
            failure.addSuppressed(preferenceFailure);
        }
        boolean targetRestored = restoreTarget(targetHelper, journal, preferencesRestored);
        if (!preferencesRestored && targetRestored) {
            setJournalPhase(targetHelper.getWritableDatabase(),
                    GridMigrationJournal.Phase.RESTORE_FAILED);
        }
        FileLog.e(TAG, "Grid migration finalization failed", failure);
        return false;
    }

    private boolean restoreTarget(DatabaseHelper targetHelper, GridMigrationJournal.Entry journal,
            boolean deleteMetadataOnSuccess) {
        SQLiteDatabase targetDatabase = targetHelper.getWritableDatabase();
        setJournalPhase(targetDatabase, GridMigrationJournal.Phase.RESTORE_PENDING);
        try {
            mGridMigrationRuntime.execute(GridMigrationOperation.TARGET_RESTORE, () -> {
                try (SQLiteTransaction transaction = new SQLiteTransaction(targetDatabase)) {
                    LauncherDbUtils.copyTable(targetDatabase, GridMigrationJournal.BACKUP_TABLE,
                            targetDatabase, Favorites.TABLE_NAME, mContext);
                    GridMigrationJournal.verifyRestoredFavorites(targetDatabase, journal);
                    transaction.commit();
                }
                targetHelper.refreshMaxItemIdFromCommittedRows();
            });
        } catch (RuntimeException restoreFailure) {
            setJournalPhase(targetDatabase, GridMigrationJournal.Phase.RESTORE_FAILED);
            FileLog.e(TAG, "Failed to restore grid migration target", restoreFailure);
            return false;
        }
        return !deleteMetadataOnSuccess || deleteRestoredMetadata(targetDatabase);
    }

    private boolean deleteRestoredMetadata(SQLiteDatabase targetDatabase) {
        try {
            try (SQLiteTransaction transaction = new SQLiteTransaction(targetDatabase)) {
                mGridMigrationRuntime.execute(GridMigrationOperation.TARGET_DELETE, () -> {
                    LauncherDbUtils.dropTable(targetDatabase, GridMigrationJournal.BACKUP_TABLE);
                    GridMigrationJournal.delete(targetDatabase);
                });
                transaction.commit();
            }
            return true;
        } catch (RuntimeException cleanupFailure) {
            setJournalPhase(targetDatabase, GridMigrationJournal.Phase.RESTORE_FAILED);
            FileLog.e(TAG, "Failed to delete restored grid migration metadata", cleanupFailure);
            return false;
        }
    }

    private void cleanupFinalizedMetadata(SQLiteDatabase targetDatabase) {
        try (SQLiteTransaction transaction = new SQLiteTransaction(targetDatabase)) {
            mGridMigrationRuntime.execute(GridMigrationOperation.TARGET_DELETE, () -> {
                LauncherDbUtils.dropTable(targetDatabase, GridMigrationJournal.BACKUP_TABLE);
                GridMigrationJournal.delete(targetDatabase);
            });
            transaction.commit();
        }
    }

    private void setJournalPhase(SQLiteDatabase targetDatabase,
            GridMigrationJournal.Phase phase) {
        mGridMigrationRuntime.execute(GridMigrationOperation.JOURNAL_WRITE, () -> {
            try (SQLiteTransaction transaction = new SQLiteTransaction(targetDatabase)) {
                GridMigrationJournal.setPhase(targetDatabase, phase);
                transaction.commit();
            }
        });
    }

    private Reconciliation reconcileDurableJournal(@Nullable DatabaseHelper sourceHelper,
            DatabaseHelper targetHelper, GridMigrationJournal.Entry journal) {
        switch (journal.phase()) {
            case MIGRATED_PENDING_FINALIZATION: {
                PendingAuthority authority = pendingAuthority(journal);
                if (authority == PendingAuthority.DESTINATION
                        && isPendingTargetValid(targetHelper, journal)) {
                    return finalizeMigration(sourceHelper, targetHelper, journal)
                            ? Reconciliation.COMPLETED : Reconciliation.FAILED;
                }
                restoreSourceAuthority(sourceHelper, targetHelper, journal);
                return Reconciliation.FAILED;
            }
            case RESTORE_PENDING:
            case RESTORE_FAILED:
                restoreSourceAuthority(sourceHelper, targetHelper, journal);
                return Reconciliation.FAILED;
            case FINALIZED: {
                SQLiteDatabase targetDatabase = targetHelper.getWritableDatabase();
                try {
                    validateFinalizedTarget(targetHelper, journal);
                    requireGridPreferences(GridMigrationOperation.DESTINATION_PREF_WRITE,
                            journal.destinationPreferences());
                } catch (RuntimeException finalizedAuthorityReconciliationFailure) {
                    FileLog.e(TAG, "Grid migration finalized authority reconciliation failed",
                            finalizedAuthorityReconciliationFailure);
                    try {
                        DatabaseHelper recoverySource = validateFinalizedJournalSource(
                                sourceHelper, targetHelper, journal);
                        restoreSourceAuthority(recoverySource, targetHelper, journal);
                        return Reconciliation.FAILED;
                    } catch (RuntimeException recoveryFailure) {
                        if (mOpenHelper != targetHelper) {
                            throw recoveryFailure;
                        }
                        try {
                            targetHelper.close();
                        } catch (RuntimeException quarantineFailure) {
                            recoveryFailure.addSuppressed(quarantineFailure);
                        } finally {
                            mOpenHelper = null;
                        }
                        GridMigrationRecoveryPendingException recoveryPending =
                                new GridMigrationRecoveryPendingException(
                                        "Grid migration finalized authority recovery is pending",
                                        finalizedAuthorityReconciliationFailure);
                        recoveryPending.addSuppressed(recoveryFailure);
                        throw recoveryPending;
                    }
                }
                mOpenHelper = targetHelper;
                if (sourceHelper != null && sourceHelper != targetHelper) {
                    mGridMigrationRuntime.execute(
                            GridMigrationOperation.SOURCE_HELPER_CLOSE, sourceHelper::close);
                }
                // Issue #59: the target is already validated here, so cleanup failure only
                // leaves FINALIZED metadata for a later retry and stays non-fatal.
                try {
                    cleanupFinalizedMetadata(targetDatabase);
                } catch (RuntimeException cleanupFailure) {
                    FileLog.e(TAG, "Failed to clean finalized grid migration metadata",
                            cleanupFailure);
                }
                return Reconciliation.COMPLETED;
            }
            case TARGET_OLD:
            default:
                throw new IllegalStateException("Transaction-local grid migration phase is durable");
        }
    }

    private void restoreSourceAuthority(@Nullable DatabaseHelper sourceHelper,
            DatabaseHelper targetHelper, GridMigrationJournal.Entry journal) {
        // Issue #59: source authority may only be republished from a proven journal source.
        validatedJournalSourceFile(journal, targetHelper);
        publishFreshSource(sourceHelper, journal.sourceDatabaseName());
        boolean preferencesRestored = false;
        RuntimeException preferenceFailure = null;
        try {
            preferencesRestored = commitGridPreferences(
                    GridMigrationOperation.SOURCE_PREF_WRITE, journal.sourcePreferences());
        } catch (RuntimeException failure) {
            preferenceFailure = failure;
        }
        boolean targetRestored = restoreTarget(targetHelper, journal, preferencesRestored);
        if (!preferencesRestored && targetRestored) {
            setJournalPhase(targetHelper.getWritableDatabase(),
                    GridMigrationJournal.Phase.RESTORE_FAILED);
        }
        if (preferenceFailure != null) {
            throw preferenceFailure;
        }
    }

    private DatabaseHelper publishFreshSource(@Nullable DatabaseHelper sourceHelper,
            String sourceDatabaseName) {
        if (sourceHelper != null) {
            sourceHelper.close();
        }
        DatabaseHelper reopened = initializeDatabaseHelper(new DatabaseHelper(
                mContext, sourceDatabaseName, this::getSerialNumberForUser, () -> { }));
        mOpenHelper = reopened;
        return reopened;
    }

    private DatabaseHelper openJournalSource(String sourceDatabaseName) {
        DatabaseHelper sourceHelper = new DatabaseHelper(mContext, sourceDatabaseName,
                this::getSerialNumberForUser, () -> { });
        try {
            sourceHelper.refreshMaxItemIdFromCommittedRows();
            return sourceHelper;
        } catch (RuntimeException openFailure) {
            try {
                sourceHelper.close();
            } catch (RuntimeException closeFailure) {
                openFailure.addSuppressed(closeFailure);
            }
            throw openFailure;
        }
    }

    private DatabaseHelper validateFinalizedJournalSource(@Nullable DatabaseHelper sourceHelper,
            DatabaseHelper targetHelper, GridMigrationJournal.Entry journal) {
        String sourceDatabaseName = journal.sourceDatabaseName();
        File sourceFile = validatedJournalSourceFile(journal, targetHelper);
        try {
            if (sourceHelper != null) {
                if (!TextUtils.equals(sourceDatabaseName, sourceHelper.getDatabaseName())
                        || !sourceFile.equals(new File(sourceHelper.getWritableDatabase().getPath())
                                .getCanonicalFile())) {
                    throw new IllegalStateException(
                            "Finalized grid migration source helper does not match journal");
                }
                return sourceHelper;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to validate finalized grid migration source",
                    exception);
        }
        return openJournalSource(sourceDatabaseName);
    }

    // Issue #59: recovery must never construct or publish an empty source database, so the
    // journal source is identity/path/existence validated before any writable helper opens it.
    private File validatedJournalSourceFile(GridMigrationJournal.Entry journal,
            DatabaseHelper targetHelper) {
        String sourceDatabaseName = journal.sourceDatabaseName();
        if (TextUtils.isEmpty(sourceDatabaseName)
                || !TextUtils.equals(sourceDatabaseName, journal.sourcePreferences().getDbFile())) {
            throw new IllegalStateException(
                    "Grid migration source name does not match source preferences");
        }
        try {
            File sourceFile = mContext.getDatabasePath(sourceDatabaseName).getCanonicalFile();
            File targetFile = new File(targetHelper.getWritableDatabase().getPath())
                    .getCanonicalFile();
            if (!sourceFile.getParentFile().equals(targetFile.getParentFile())
                    || sourceFile.equals(targetFile) || !sourceFile.isFile()) {
                throw new IllegalStateException("Grid migration source is invalid");
            }
            return sourceFile;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to validate grid migration source", exception);
        }
    }

    private boolean writeGridPreferences(DeviceGridState state) {
        boolean committed = mGridMigrationRuntime.writeGridPreferences(state,
                () -> LauncherPrefs.get(mContext).putSync(
                        LauncherPrefs.WORKSPACE_SIZE.to(
                                state.getColumns() + "," + state.getRows()),
                        LauncherPrefs.HOTSEAT_COUNT.to(state.getNumHotseat()),
                        LauncherPrefs.DEVICE_TYPE.to(state.getDeviceType()),
                        LauncherPrefs.DB_FILE.to(state.getDbFile())));
        boolean matchesReadback = sameGridState(state, new DeviceGridState(mContext));
        return committed && matchesReadback;
    }

    private boolean commitGridPreferences(GridMigrationOperation operation, DeviceGridState state) {
        boolean[] committed = {false};
        mGridMigrationRuntime.execute(operation,
                () -> committed[0] = writeGridPreferences(state));
        return committed[0];
    }

    private void requireGridPreferences(GridMigrationOperation operation, DeviceGridState state) {
        if (!commitGridPreferences(operation, state)) {
            throw new IllegalStateException("Grid migration preferences did not commit and read back");
        }
    }

    private static boolean sameDatabase(SQLiteDatabase first, SQLiteDatabase second)
            throws IOException {
        return new File(first.getPath()).getCanonicalFile().equals(
                new File(second.getPath()).getCanonicalFile());
    }

    private static boolean sameGridState(DeviceGridState first, DeviceGridState second) {
        return first.getColumns().equals(second.getColumns())
                && first.getRows().equals(second.getRows())
                && first.getNumHotseat() == second.getNumHotseat()
                && first.getDeviceType() == second.getDeviceType()
                && TextUtils.equals(first.getDbFile(), second.getDbFile());
    }

    private PendingAuthority pendingAuthority(GridMigrationJournal.Entry journal) {
        DeviceGridState current = new DeviceGridState(mContext);
        if (sameGridState(current, journal.sourcePreferences())) {
            return PendingAuthority.SOURCE;
        }
        if (sameGridState(current, journal.destinationPreferences())) {
            return PendingAuthority.DESTINATION;
        }
        return PendingAuthority.UNKNOWN;
    }

    private static boolean isPendingTargetValid(DatabaseHelper targetHelper,
            GridMigrationJournal.Entry journal) {
        try {
            validateMigratedTarget(targetHelper, journal);
            return true;
        } catch (RuntimeException invalidTarget) {
            FileLog.e(TAG, "Grid migration target validation failed", invalidTarget);
            return false;
        }
    }

    private static void validateFinalizedTarget(DatabaseHelper targetHelper,
            GridMigrationJournal.Entry journal) {
        validateMigratedTarget(targetHelper, journal);
    }

    private static void validateMigratedTarget(DatabaseHelper targetHelper,
            GridMigrationJournal.Entry journal) {
        SQLiteDatabase targetDatabase = targetHelper.getWritableDatabase();
        if (!TextUtils.equals(targetHelper.getDatabaseName(), journal.targetDatabaseName())
                || !TextUtils.equals(journal.destinationPreferences().getDbFile(),
                        journal.targetDatabaseName())
                || !tableExists(targetDatabase, Favorites.TABLE_NAME)
                || !tableExists(targetDatabase, GridMigrationJournal.BACKUP_TABLE)
                || tableExists(targetDatabase, Favorites.TMP_TABLE)) {
            throw new IllegalStateException("Grid migration target identity is invalid");
        }
        GridMigrationJournal.verifyBackup(targetDatabase, journal);
        try (Cursor cursor = targetDatabase.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CASE WHEN organizerLockState = 0 THEN 0 ELSE 1 END), 0)"
                        + " FROM " + Favorites.TABLE_NAME,
                null)) {
            if (!cursor.moveToFirst() || cursor.getInt(1) != 0) {
                throw new IllegalStateException("Grid migration target is not entirely UNKNOWN");
            }
        }
    }

    private enum PendingAuthority {
        SOURCE,
        DESTINATION,
        UNKNOWN
    }

    private static final class GridMigrationRecoveryPendingException extends RuntimeException {
        GridMigrationRecoveryPendingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private enum Reconciliation {
        CONTINUE,
        COMPLETED,
        FAILED
    }

    /**
     * In case of migration failure, report metrics for the count of each itemType
     * in the DB.
     * 
     * @param restoreEventLogger logger used to report Launcher restore metrics
     */
    private void sendMetricsForFailedMigration(LauncherRestoreEventLogger restoreEventLogger,
            SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT itemType, COUNT(*) AS count FROM favorites GROUP BY itemType",
                null)) {
            if (cursor.moveToFirst()) {
                do {
                    restoreEventLogger.logFavoritesItemsRestoreFailed(
                            cursor.getInt(cursor.getColumnIndexOrThrow(ITEM_TYPE)),
                            cursor.getInt(cursor.getColumnIndexOrThrow("count")),
                            RestoreError.GRID_MIGRATION_FAILURE);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            FileLog.e(TAG, "sendMetricsForFailedDb: Error reading from database", e);
        }
    }

    /**
     * Returns the underlying model database
     */
    public SQLiteDatabase getDb() {
        createDbIfNotExists();
        return mOpenHelper.getWritableDatabase();
    }

    // Issue #58: restore-scoped seam for runtime raw-file restores. Closes the active
    // helper (dropping its cached DB references and file handles) so the DB files can be
    // replaced as raw bytes; a fresh helper is constructed lazily on the next access
    // (createDbIfNotExists), mirroring the publishFreshSource close/reopen pattern.
    // Callers must hold a restore-family coordinator lease across close, replacement
    // and reopen.
    public void closeActiveHelperForRestore() {
        DatabaseHelper helper;
        synchronized (this) {
            helper = mOpenHelper;
            mOpenHelper = null;
        }
        if (helper != null) {
            helper.close();
        }
    }

    // Issue #14: refresh allocator state only after the organizer classifies the transaction.
    public void refreshMaxItemIdFromCommittedRows() {
        createDbIfNotExists();
        mOpenHelper.refreshMaxItemIdFromCommittedRows();
    }

    private void onAddOrDeleteOp(SQLiteDatabase db) {
        mOpenHelper.onAddOrDeleteOp(db);
    }

    /**
     * Deletes any empty folder from the DB.
     * 
     * @return Ids of deleted folders.
     */
    @WorkerThread
    public IntArray deleteEmptyFolders() {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db, acquireMutationLease())) {
            // Select folders whose id do not match any container value.
            String selection = LauncherSettings.Favorites.ITEM_TYPE + " = "
                    + LauncherSettings.Favorites.ITEM_TYPE_FOLDER + " AND "
                    + LauncherSettings.Favorites._ID + " NOT IN (SELECT "
                    + LauncherSettings.Favorites.CONTAINER + " FROM "
                    + Favorites.TABLE_NAME + ")";

            IntArray folderIds = LauncherDbUtils.queryIntArray(false, db, Favorites.TABLE_NAME,
                    Favorites._ID, selection, null, null);
            if (!folderIds.isEmpty()) {
                db.delete(Favorites.TABLE_NAME, Utilities.createDbSelectionQuery(
                        LauncherSettings.Favorites._ID, folderIds), null);
            }
            t.commit();
            return folderIds;
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            return new IntArray();
        }
    }

    /**
     * Deletes any app pair that doesn't contain 2 member apps from the DB.
     * 
     * @return Ids of deleted app pairs.
     */
    @WorkerThread
    public IntArray deleteBadAppPairs() {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db, acquireMutationLease())) {
            // Select all entries with ITEM_TYPE = ITEM_TYPE_APP_PAIR whose id does not
            // appear
            // exactly twice in the CONTAINER column.
            String selection = ITEM_TYPE + " = " + ITEM_TYPE_APP_PAIR
                    + " AND " + _ID + " NOT IN"
                    + " (SELECT " + CONTAINER + " FROM " + TABLE_NAME
                    + " GROUP BY " + CONTAINER + " HAVING COUNT(*) = 2)";

            IntArray appPairIds = LauncherDbUtils.queryIntArray(false, db, TABLE_NAME,
                    _ID, selection, null, null);
            if (!appPairIds.isEmpty()) {
                db.delete(TABLE_NAME, Utilities.createDbSelectionQuery(
                        _ID, appPairIds), null);
            }
            t.commit();
            return appPairIds;
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            return new IntArray();
        }
    }

    /**
     * Deletes any app with a container id that doesn't exist.
     * 
     * @return Ids of deleted apps.
     */
    @WorkerThread
    public IntArray deleteUnparentedApps() {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db, acquireMutationLease())) {
            // Select all entries whose container id does not appear in the database.
            String selection = CONTAINER + " >= 0"
                    + " AND " + CONTAINER + " NOT IN"
                    + " (SELECT " + _ID + " FROM " + TABLE_NAME + ")";

            IntArray appIds = LauncherDbUtils.queryIntArray(false, db, TABLE_NAME,
                    _ID, selection, null, null);
            if (!appIds.isEmpty()) {
                db.delete(TABLE_NAME, Utilities.createDbSelectionQuery(
                        _ID, appIds), null);
            }
            t.commit();
            return appIds;
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            return new IntArray();
        }
    }

    private static void addModifiedTime(ContentValues values) {
        values.put(LauncherSettings.Favorites.MODIFIED, System.currentTimeMillis());
    }

    private void clearFlagEmptyDbCreated() {
        LauncherPrefs.get(mContext).removeSync(getEmptyDbCreatedKey());
    }

    /**
     * Loads the default workspace based on the following priority scheme:
     * 1) From the app restrictions
     * 2) From a package provided by play store
     * 3) From a partner configuration APK, already in the system image
     * 4) The default configuration for the particular device
     */
    @WorkerThread
    public synchronized void loadDefaultFavoritesIfNecessary() {
        createDbIfNotExists();

        if (!(mContext instanceof LauncherPreviewRenderer.PreviewContext)) {
            LawnchairAppKt.getLawnchairApp(mContext).cleanUpDatabases();
        }

        if (LauncherPrefs.get(mContext).get(getEmptyDbCreatedKey())) {
            Log.d(TAG, "loading default workspace");

            LauncherWidgetHolder widgetHolder = mOpenHelper.newLauncherWidgetHolder();
            try {
                AutoInstallsLayout loader = createWorkspaceLoaderFromAppRestriction(widgetHolder);
                if (loader == null) {
                    loader = AutoInstallsLayout.get(mContext, widgetHolder, mOpenHelper);
                }
                if (loader == null) {
                    final Partner partner = Partner.get(mContext.getPackageManager());
                    if (partner != null) {
                        int workspaceResId = partner.getXmlResId(RES_PARTNER_DEFAULT_LAYOUT);
                        if (workspaceResId != 0) {
                            loader = new DefaultLayoutParser(mContext, widgetHolder,
                                    mOpenHelper, partner.getResources(), workspaceResId);
                        }
                    }
                }

                final boolean usingExternallyProvidedLayout = loader != null;
                if (loader == null) {
                    loader = getDefaultLayoutParser(widgetHolder);
                }

                // There might be some partially restored DB items, due to buggy restore logic
                // in
                // previous versions of launcher.
                mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
                // Populate favorites table with initial favorites
                if ((mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(), loader) <= 0)
                        && usingExternallyProvidedLayout) {
                    // Unable to load external layout. Cleanup and load the internal layout.
                    mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
                    mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(),
                            getDefaultLayoutParser(widgetHolder));
                }
                clearFlagEmptyDbCreated();
            } finally {
                widgetHolder.destroy();
            }
        }
    }

    /**
     * Creates workspace loader from an XML resource listed in the app restrictions.
     *
     * @return the loader if the restrictions are set and the resource exists; null
     *         otherwise.
     */
    private AutoInstallsLayout createWorkspaceLoaderFromAppRestriction(
            LauncherWidgetHolder widgetHolder) {
        ContentResolver cr = mContext.getContentResolver();
        String blobHandlerDigest = Settings.Secure.getString(cr, LAYOUT_DIGEST_KEY);
        if (!TextUtils.isEmpty(blobHandlerDigest)) {
            BlobStoreManager blobManager = mContext.getSystemService(BlobStoreManager.class);
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                    blobManager.openBlob(BlobHandle.createWithSha256(
                            Base64.decode(blobHandlerDigest, NO_WRAP | NO_PADDING),
                            LAYOUT_DIGEST_LABEL, 0, LAYOUT_DIGEST_TAG)))) {
                return getAutoInstallsLayoutFromIS(in, widgetHolder, new SourceResources() {
                });
            } catch (Exception e) {
                Log.e(TAG, "Error getting layout from blob handle", e);
                return null;
            }
        }

        String authority = Settings.Secure.getString(cr, "launcher3.layout.provider");
        if (TextUtils.isEmpty(authority)) {
            return null;
        }

        PackageManager pm = mContext.getPackageManager();
        ProviderInfo pi = pm.resolveContentProvider(authority, 0);
        if (pi == null) {
            Log.e(TAG, "No provider found for authority " + authority);
            return null;
        }
        Uri uri = getLayoutUri(authority, mContext);
        try (InputStream in = cr.openInputStream(uri)) {
            Log.d(TAG, "Loading layout from " + authority);

            Resources res = pm.getResourcesForApplication(pi.applicationInfo);
            return getAutoInstallsLayoutFromIS(in, widgetHolder, SourceResources.wrap(res));
        } catch (Exception e) {
            Log.e(TAG, "Error getting layout stream from: " + authority, e);
            return null;
        }
    }

    private AutoInstallsLayout getAutoInstallsLayoutFromIS(InputStream in,
            LauncherWidgetHolder widgetHolder, SourceResources res) throws Exception {
        // Read the full xml so that we fail early in case of any IO error.
        String layout = new String(IOUtils.toByteArray(in));
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(layout));

        return new AutoInstallsLayout(mContext, widgetHolder, mOpenHelper, res,
                () -> parser, AutoInstallsLayout.TAG_WORKSPACE);
    }

    public static Uri getLayoutUri(String authority, Context ctx) {
        InvariantDeviceProfile grid = LauncherAppState.getIDP(ctx);
        return new Uri.Builder().scheme("content").authority(authority).path("launcher_layout")
                .appendQueryParameter("version", "1")
                .appendQueryParameter("gridWidth", Integer.toString(grid.numColumns))
                .appendQueryParameter("gridHeight", Integer.toString(grid.numRows))
                .appendQueryParameter("hotseatSize", Integer.toString(grid.numDatabaseHotseatIcons))
                .build();
    }

    private DefaultLayoutParser getDefaultLayoutParser(LauncherWidgetHolder widgetHolder) {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        int defaultLayout = idp.demoModeLayoutId != 0
                && mContext.getSystemService(UserManager.class).isDemoUser()
                        ? idp.demoModeLayoutId
                        : idp.defaultLayoutId;

        return new DefaultLayoutParser(mContext, widgetHolder,
                mOpenHelper, mContext.getResources(), defaultLayout);
    }

    private ConstantItem<Boolean> getEmptyDbCreatedKey() {
        return getEmptyDbCreatedKey(mOpenHelper.getDatabaseName());
    }

    /**
     * Re-composite given key in respect to database. If the current db is
     * {@link LauncherFiles#LAUNCHER_DB}, return the key as-is. Otherwise append the
     * db name to
     * given key. e.g. consider key="EMPTY_DATABASE_CREATED", dbName="minimal.db",
     * the returning
     * string will be "EMPTY_DATABASE_CREATED@minimal.db".
     */
    private ConstantItem<Boolean> getEmptyDbCreatedKey(String dbName) {
        if (mContext instanceof SandboxContext) {
            return LauncherPrefs.nonRestorableItem(EMPTY_DATABASE_CREATED,
                    false /* default value */, EncryptionType.ENCRYPTED);
        }
        String key = TextUtils.equals(dbName, LauncherFiles.LAUNCHER_DB)
                ? EMPTY_DATABASE_CREATED
                : EMPTY_DATABASE_CREATED + "@" + dbName;
        return LauncherPrefs.backedUpItem(key, false /* default value */, EncryptionType.ENCRYPTED);
    }

    /**
     * Returns the serial number for the provided user
     */
    public long getSerialNumberForUser(UserHandle user) {
        return UserCache.INSTANCE.get(mContext).getSerialNumberForUser(user);
    }
}
