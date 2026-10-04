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
package com.android.launcher3.hybridhotseat;

import static com.android.launcher3.LauncherSettings.Favorites.HYBRID_HOTSEAT_BACKUP_TABLE;
import static com.android.launcher3.provider.LauncherDbUtils.tableExists;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.model.GridBackupTable;
import com.android.launcher3.model.LayoutWriteCoordinator;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;

import java.util.concurrent.Executor;

/**
 * A helper class to manage migration revert restoration for hybrid hotseat
 */
public class HotseatRestoreHelper {

    /**
     * Reserves Hotseat submission order, then schedules the DB body with atomic writer admission.
     *
     * <p>Issue #156 reserves this helper's stable logical FIFO position at call time even when
     * the coordinator is empty. A following ModelWriter migration therefore cannot overtake a
     * backup if an organizer or restore-family holder appears before executor admission. The
     * reservation callback only posts to MODEL_EXECUTOR; execution-time admission then atomically
     * owns MODEL_WRITER or defers again, so no holder can block MODEL_EXECUTOR in
     * ModelDbController.newTransaction().
     */
    private static void executeWithAtomicModelWriterAdmission(@NonNull Runnable dbBody) {
        executeWithAtomicModelWriterAdmission(MODEL_EXECUTOR, dbBody);
    }

    @VisibleForTesting
    static void executeWithAtomicModelWriterAdmission(
            @NonNull Executor executor, @NonNull Runnable dbBody) {
        LayoutWriteCoordinator.getInstance().runModelWriterWithCallTimeReservation(
                executor, dbBody);
    }


    /**
     * Creates a snapshot backup of Favorite table for future restoration use.
     */
    public static void createBackup(Context context) {
        executeWithAtomicModelWriterAdmission(() -> {
            ModelDbController dbController = LauncherAppState.getInstance(context)
                    .getModel().getModelDbController();
            try (SQLiteTransaction transaction = dbController.newTransaction()) {
                GridBackupTable backupTable = new GridBackupTable(context, transaction.getDb());
                backupTable.createCustomBackupTable(HYBRID_HOTSEAT_BACKUP_TABLE);
                transaction.commit();
                dbController.refreshHotseatRestoreTable();
            }
        });
    }

    /**
     * Finds and restores a previously saved snapshow of Favorites table
     */
    public static void restoreBackup(Context context) {
        executeWithAtomicModelWriterAdmission(() -> {
            LauncherModel model = LauncherAppState.getInstance(context).getModel();
            try (SQLiteTransaction transaction = model.getModelDbController().newTransaction()) {
                if (!tableExists(transaction.getDb(), HYBRID_HOTSEAT_BACKUP_TABLE)) {
                    return;
                }
                GridBackupTable backupTable = new GridBackupTable(context, transaction.getDb());
                backupTable.restoreFromCustomBackupTable(HYBRID_HOTSEAT_BACKUP_TABLE, true);
                transaction.commit();
                model.forceReload();
            }
        });
    }
}
