/*
 * Copyright (C) 2025 The Android Open Source Project
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

import androidx.annotation.Nullable;

import com.android.launcher3.model.data.FolderInfo;

/**
 * Issue #448: minimal plain-data contract between the fork-side homeedit
 * planner and {@link ModelWriter}'s direct-edit operations (ADR-0013 contract
 * 2 and 4). Pure JDK types only: {@code src/} must not depend on the lawnchair
 * module, and the pure planning layer must not receive Android or DB row types.
 *
 * <p>The write structure every direct-edit operation follows is
 * "stage-1 validation (at submit time) → MODEL_WRITER admission → stage-2
 * re-validation against the current state (inside admission) → model/DB
 * change". The {@link Validator} receives an immutable {@link Snapshot} of the
 * current state inside admission and decides whether the write validated at
 * stage 1 may still proceed; it must re-run the same pure planning function
 * against that snapshot and reject a different destination instead of
 * re-planning silently.
 */
public final class DirectEditContract {

    private DirectEditContract() {
    }

    /** Machine-readable failure keys reported through {@link Decision}. */
    public static final String FAIL_STALE = "STALE";
    public static final String FAIL_ITEM_GONE = "ITEM_GONE";
    public static final String FAIL_NO_SPACE = "NO_SPACE";
    public static final String FAIL_REDUNDANT = "REDUNDANT";
    public static final String FAIL_FOLDER_GONE = "FOLDER_GONE";
    public static final String FAIL_PROFILE_MISMATCH = "PROFILE_MISMATCH";
    public static final String FAIL_UNSUPPORTED = "UNSUPPORTED";
    public static final String FAIL_WRITE_FAILED = "WRITE_FAILED";

    /**
     * Issue #450: favorites OPTIONS bit marking a folder that a direct-edit
     * action (the #448 popup "new folder") intentionally created. Such a
     * folder legitimately starts with a single child, so the automatic
     * single-child cleanups (Folder bind/close/remove) must not flatten it.
     * Persisted in the
     * existing OPTIONS column so the marker survives reloads — the cleanup
     * keeps respecting the user's just-created folder after the undo window
     * and across process restarts. No clearing is needed: the folder row is
     * deleted by the undo or by the user, which removes the bit with it.
     * 0x1..0x8 are taken by FolderInfo's FLAG_* constants.
     */
    public static final int OPTIONS_DIRECT_EDIT_CREATED_FOLDER = 0x00000010;

    /**
     * Issue #450: typed failure keys for the direct-edit inverse operations
     * (undo). Spec 450 "Failure and rejection vocabulary"; the UI maps each
     * key to a localized string, never a raw exception or internal id.
     */
    public static final String FAIL_UNDO_STALE = "UNDO_STALE";
    public static final String FAIL_UNDO_NO_SPACE = "UNDO_NO_SPACE";
    public static final String FAIL_UNDO_FOLDER_CHANGED = "UNDO_FOLDER_CHANGED";
    public static final String FAIL_UNDO_ITEM_UNAVAILABLE = "UNDO_ITEM_UNAVAILABLE";
    public static final String FAIL_UNDO_WRITE_FAILED = "UNDO_WRITE_FAILED";

    /** One favorites-row projection. Plain data; no Android types. */
    public static final class Row {
        public final int id;
        public final int container;
        public final int screenId;
        public final int cellX;
        public final int cellY;
        public final int spanX;
        public final int spanY;
        public final int itemType;
        public final int rank;
        public final long userSerial;

        public Row(int id, int container, int screenId, int cellX, int cellY,
                int spanX, int spanY, int itemType, int rank, long userSerial) {
            this.id = id;
            this.container = container;
            this.screenId = screenId;
            this.cellX = cellX;
            this.cellY = cellY;
            this.spanX = spanX;
            this.spanY = spanY;
            this.itemType = itemType;
            this.rank = rank;
            this.userSerial = userSerial;
        }
    }

    /**
     * Immutable projection of the current layout state used for pure
     * validation. {@code screenIds} is the workspace page order; rows include
     * desktop, hotseat and folder-child rows.
     *
     * <p>Issue #450: {@code hotseatCount} is the current device profile's
     * hotseat capacity; the undo planner verifies a recorded hotseat slot
     * against it so a shrunken hotseat rejects the restore (zero write).
     */
    public static final class Snapshot {
        public final int columnCount;
        public final int rowCount;
        public final int[] screenIds;
        public final Row[] rows;
        /** Issue #450: current hotseat capacity (numHotseatIcons). */
        public final int hotseatCount;

        public Snapshot(int columnCount, int rowCount, int[] screenIds, Row[] rows, int hotseatCount) {
            this.columnCount = columnCount;
            this.rowCount = rowCount;
            this.screenIds = screenIds;
            this.rows = rows;
            this.hotseatCount = hotseatCount;
        }
    }

    /** Stage-2 decision produced by the fork-side validator. */
    public static final class Decision {
        public final boolean proceed;

        /** One of the {@code FAIL_*} keys when {@link #proceed} is false. */
        @Nullable
        public final String failureReason;

        private Decision(boolean proceed, @Nullable String failureReason) {
            this.proceed = proceed;
            this.failureReason = failureReason;
        }

        public static Decision proceed() {
            return new Decision(true, null);
        }

        public static Decision reject(String failureReason) {
            return new Decision(false, failureReason);
        }
    }

    /**
     * Issue #450: full favorites-row projection of the item removed by a
     * direct-edit remove, captured inside MODEL_WRITER admission immediately
     * before the DELETE (spec 450: the undo re-INSERT needs the deleted row's
     * content, which is not reconstructible after deletion). Plain data; no
     * Android types. The availability identity fields are resolved by the
     * platform side at capture time so the fork-side planner stays pure.
     */
    public static final class UndoRowPayload {
        public final int itemId;
        public final int itemType;
        public final int container;
        public final int screenId;
        public final int cellX;
        public final int cellY;
        public final int spanX;
        public final int spanY;
        public final int rank;
        public final long userSerial;
        @Nullable public final String intent;
        @Nullable public final String title;
        public final int options;
        /** The row's organizerLockState value, restored verbatim on re-INSERT. */
        public final int organizerLockState;
        /** Flattened component for an application row, null otherwise. */
        @Nullable public final String componentName;
        /** Package of a deep-shortcut row, null otherwise. */
        @Nullable public final String packageName;
        /** Shortcut id of a deep-shortcut row, null otherwise. */
        @Nullable public final String shortcutId;

        public UndoRowPayload(int itemId, int itemType, int container, int screenId,
                int cellX, int cellY, int spanX, int spanY, int rank, long userSerial,
                @Nullable String intent, @Nullable String title, int options,
                int organizerLockState, @Nullable String componentName,
                @Nullable String packageName, @Nullable String shortcutId) {
            this.itemId = itemId;
            this.itemType = itemType;
            this.container = container;
            this.screenId = screenId;
            this.cellX = cellX;
            this.cellY = cellY;
            this.spanX = spanX;
            this.spanY = spanY;
            this.rank = rank;
            this.userSerial = userSerial;
            this.intent = intent;
            this.title = title;
            this.options = options;
            this.organizerLockState = organizerLockState;
            this.componentName = componentName;
            this.packageName = packageName;
            this.shortcutId = shortcutId;
        }
    }

    /**
     * Stage-2 validator. The implementation (fork-side homeedit adapter)
     * re-runs the pure planning function for the already-validated intent
     * against {@code current} and only allows the write when the result still
     * matches the stage-1 destination. Called on the model thread inside
     * admission, before any model/DB change.
     *
     * <p>Issue #450: the undo validators are the same interface. The remove
     * -undo validator re-verifies the captured launch-target availability
     * itself (fork-side production verifier, identical determination at stage
     * 1 and stage 2) — ModelWriter never resolves launchability.
     */
    public interface Validator {
        Decision validate(Snapshot current);
    }

    /**
     * Result callback invoked on the model thread after the operation
     * completed (or was rejected without any change). The old-placement
     * fields plus {@code createdFolderId}/{@code createdFolder} carry the
     * undo evidence required by Issue #450; {@code createdFolder} is the live
     * model row of the folder created by this action, for owner-side UI bind.
     *
     * <p>Issue #450 extension: {@code removedRowPayload} carries the
     * pre-DELETE favorites-row projection for the remove-undo. It is non-null
     * only when a direct-edit remove succeeded; every other outcome passes
     * null. This widens the #448 result contract in place (the single
     * production implementor lives in the fork-side executor).
     */
    public interface ResultCallback {
        void onResult(int itemId, boolean success,
                @Nullable String failureReason,
                int oldContainer, int oldScreenId, int oldCellX, int oldCellY,
                int oldSpanX, int oldSpanY, int oldRank, int createdFolderId,
                @Nullable FolderInfo createdFolder,
                @Nullable UndoRowPayload removedRowPayload);
    }
}
