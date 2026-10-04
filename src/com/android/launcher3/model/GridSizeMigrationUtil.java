/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME;
import static com.android.launcher3.LauncherSettings.Favorites.TMP_TABLE;
import static com.android.launcher3.provider.LauncherDbUtils.copyTableFromAttachedDb;
import static com.android.launcher3.provider.LauncherDbUtils.dropTable;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Point;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.launcher3.LauncherSettings;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;

/**
 * Issue #532 S3a (rebase of Issue #59/#14): the fork's grid-migration transaction
 * shell, retained for the durable journal contracts exercised through
 * {@code ModelDbController.tryMigrateDB}. The placement algorithm itself lives in
 * the anchor split structure ({@link GridSizeMigrationDBController} /
 * {@link GridSizeMigrationLogic}); this shell only drives the runtime-guarded
 * operations (target copy, placement, unknown-lock mark, temp cleanup) inside the
 * caller's transaction.
 */
public class GridSizeMigrationUtil {

    private static final String TAG = "GridSizeMigrationUtil";
    private static final boolean DEBUG = true;

    private GridSizeMigrationUtil() {
        // Util class should not be instantiated
    }

    static void migrateGridInTransaction(
            @NonNull Context context,
            @NonNull DeviceGridState srcDeviceState,
            @NonNull DeviceGridState destDeviceState,
            @NonNull DatabaseHelper target,
            @NonNull SQLiteDatabase targetDatabase,
            boolean enableGridMigrationFix,
            @NonNull GridMigrationRuntime runtime,
            @NonNull ModelDelegate modelDelegate) {
        long migrationStartTime = System.currentTimeMillis();
        try {
            if (!hasOrganizerLockColumn(targetDatabase)) {
                throw new IllegalStateException("Grid migration target is missing organizer lock column");
            }
            boolean fastPath = enableGridMigrationFix
                    && srcDeviceState.getColumns().equals(destDeviceState.getColumns())
                    && srcDeviceState.getRows() < destDeviceState.getRows();
            String targetTable = fastPath ? TABLE_NAME : TMP_TABLE;
            runtime.execute(GridMigrationOperation.TARGET_COPY,
                    () -> copyTableFromAttachedDb(TABLE_NAME, targetDatabase, targetTable, context));
            if (!fastPath) {
                DbReader srcReader = new DbReader(
                        targetDatabase, TMP_TABLE, context);
                DbReader destReader = new DbReader(
                        targetDatabase, TABLE_NAME, context);
                Point targetSize = new Point(
                        destDeviceState.getColumns(), destDeviceState.getRows());
                runtime.execute(GridMigrationOperation.PLACEMENT,
                        () -> GridSizeMigrationDBController.migrate(target, srcReader, destReader,
                                srcDeviceState.getNumHotseat(), destDeviceState.getNumHotseat(),
                                targetSize, srcDeviceState, destDeviceState));
            }
            runtime.execute(GridMigrationOperation.UNKNOWN_MARK,
                    () -> markOrganizerLocksUnknown(targetDatabase));
            runtime.execute(GridMigrationOperation.TMP_CLEANUP,
                    () -> dropTable(targetDatabase, TMP_TABLE));
        } finally {
            Log.v(TAG, "Workspace migration completed in "
                    + (System.currentTimeMillis() - migrationStartTime));
            // Anchor (16-dev) contract: notify the delegate that the migration pass
            // completed, so it can refresh whatever state it tracks around grid changes.
            modelDelegate.gridMigrationComplete(srcDeviceState, destDeviceState);
        }
    }

    private static boolean hasOrganizerLockColumn(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(favorites)", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                if (LauncherSettings.Favorites.ORGANIZER_LOCK_STATE.equals(
                        cursor.getString(nameIndex))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Production migration primitive, exposed so instrumentation exercises the real SQL path. */
    public static void markOrganizerLocksUnknown(SQLiteDatabase db) {
        db.execSQL("UPDATE favorites SET "
                + LauncherSettings.Favorites.ORGANIZER_LOCK_STATE + " = 0");
    }
}
