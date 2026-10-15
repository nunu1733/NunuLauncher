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

import android.content.Context;
import android.os.UserHandle;

import androidx.annotation.NonNull;
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

    // ===== Issue #497: destination policy for auto-added installs =====
    // ADR-0013 target (b) / ADR-0015 Decision 7-10: the placement decision
    // for an automatically added install icon is the closed result of one
    // pure planning function re-run inside MODEL_WRITER admission. Like the
    // #448 types above these are plain-data platform contract types only:
    // src/ must not depend on the lawnchair module.

    /** Typed fallback reasons (ADR-0015 Decision 4/8/10). */
    public static final String DEST_FOLDER_MISSING = "DEST_FOLDER_MISSING";
    public static final String DEST_PROFILE_MISMATCH = "DEST_PROFILE_MISMATCH";
    public static final String DEST_DOCK_FOLDER = "DEST_DOCK_FOLDER";
    public static final String DEST_CONSTRAINT_VIOLATION = "DEST_CONSTRAINT_VIOLATION";
    public static final String DEST_SNAPSHOT_INVALID = "DEST_SNAPSHOT_INVALID";

    /** Action codes of {@link DestinationDecision}. */
    public static final int DEST_ACTION_FOLDER = 1;
    public static final int DEST_ACTION_DEFAULT = 2;
    public static final int DEST_ACTION_REJECT = 3;

    /** Sentinel result screen id when the finder allocated no new screen. */
    public static final int DEST_NO_SCREEN_ID = -1;

    /** Snapshot wire-format kind tokens (see {@link #serializeDestinationSnapshot}). */
    public static final String DEST_SNAPSHOT_KIND_UPSTREAM = "upstream";
    public static final String DEST_SNAPSHOT_KIND_FOLDER = "folder";

    private static final char DEST_SNAPSHOT_SEPARATOR = '|';

    /**
     * Serializes the policy snapshot captured at enqueue time (ADR-0015
     * Decision 10: policy kind, designated folder id, user serial and
     * package). The pipe-separated encoding is contract; package names never
     * contain a pipe character.
     */
    @NonNull
    public static String serializeDestinationSnapshot(@NonNull String kind, int folderId,
            long userSerial, @NonNull String packageName) {
        StringBuilder sb = new StringBuilder(kind)
                .append(DEST_SNAPSHOT_SEPARATOR).append(folderId)
                .append(DEST_SNAPSHOT_SEPARATOR).append(userSerial)
                .append(DEST_SNAPSHOT_SEPARATOR).append(packageName);
        return sb.toString();
    }

    /**
     * Parses a persisted snapshot into {@code [kind, folderId, userSerial,
     * packageName]} or null when the snapshot part is missing/corrupt. The
     * caller decides the typed meaning of null ({@code SNAPSHOT_INVALID})
     * against the base entry identity.
     */
    @Nullable
    public static String[] parseDestinationSnapshot(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 4
                || (!DEST_SNAPSHOT_KIND_UPSTREAM.equals(parts[0])
                    && !DEST_SNAPSHOT_KIND_FOLDER.equals(parts[0]))) {
            return null;
        }
        try {
            Integer.parseInt(parts[1]);
            Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return parts;
    }

    /**
     * True when the snapshot decodes completely into a valid UPSTREAM
     * selection. The stock-path bypass must use this, not the
     * {@code startsWith} prefix alone: a corrupt or identity-mismatched
     * upstream snapshot has to reach the stage-2 classifier for the typed
     * {@code SNAPSHOT_INVALID} handling (Issue #497 Phase 2 review).
     */
    public static boolean isValidUpstreamSnapshot(@Nullable String raw,
            long baseUserSerial, @NonNull String basePackageName) {
        String[] parts = parseDestinationSnapshot(raw);
        if (parts == null || !DEST_SNAPSHOT_KIND_UPSTREAM.equals(parts[0])) {
            return false;
        }
        return Long.parseLong(parts[2]) == baseUserSerial
                && basePackageName.equals(parts[3]);
    }

    /** Closed result (ADR-0015 Decision 8) decided by {@link DestinationValidator}. */
    public static final class DestinationDecision {
        public final int action;

        /** Designated folder id when {@link #action} is {@code DEST_ACTION_FOLDER}. */
        public final int folderId;

        /**
         * Fallback reason ({@code DEST_*} key) when the action is
         * {@code DEST_ACTION_DEFAULT}; typed failure key when the action is
         * {@code DEST_ACTION_REJECT}; null for {@code DEST_ACTION_FOLDER}.
         */
        @Nullable
        public final String reason;

        private DestinationDecision(int action, int folderId, @Nullable String reason) {
            this.action = action;
            this.folderId = folderId;
            this.reason = reason;
        }

        public static DestinationDecision folder(int folderId) {
            return new DestinationDecision(DEST_ACTION_FOLDER, folderId, null);
        }

        public static DestinationDecision upstreamDefault(@NonNull String reason) {
            return new DestinationDecision(DEST_ACTION_DEFAULT, 0, reason);
        }

        public static DestinationDecision reject(@NonNull String reason) {
            return new DestinationDecision(DEST_ACTION_REJECT, 0, reason);
        }
    }

    /**
     * Stage-2 validator for the destination write. Runs inside MODEL_WRITER
     * admission on the model thread and returns the closed result for the
     * persisted policy snapshot against the current state; a stale folder
     * re-plans to {@code upstreamDefault(reason)} instead of failing.
     */
    public interface DestinationValidator {
        DestinationDecision validate(Snapshot current);
    }

    /**
     * Result callback for the destination write, invoked on the model thread
     * after the admitted insert (or the typed rejection without any write).
     * {@code newScreenId} is the screen allocated by
     * {@code WorkspaceItemSpaceFinder} inside admission for an
     * upstream-default write, or {@link #DEST_NO_SCREEN_ID}; the caller uses
     * it for the single post-success bind so a newly created page is visible
     * without a reload. {@code reason} carries the fallback reason of a
     * re-planned upstream-default write.
     */
    public interface DestinationResultCallback {
        void onResult(boolean success, int container, int screenId, int cellX, int cellY,
                int rank, int newScreenId, @Nullable String reason);
    }

    /**
     * Flush-time routing value carried alongside the queue item (read-only
     * decode of the persisted snapshot). {@code usePolicyWrite} routes the
     * item to the admission-bounded destination write; the stock upstream
     * path stays untouched for the plain upstream-default snapshot.
     */
    public static final class DestinationRoute {
        public final boolean usePolicyWrite;

        @Nullable
        public final DestinationValidator validator;

        @Nullable
        public final DestinationResultCallback callback;

        public DestinationRoute(boolean usePolicyWrite,
                @Nullable DestinationValidator validator,
                @Nullable DestinationResultCallback callback) {
            this.usePolicyWrite = usePolicyWrite;
            this.validator = validator;
            this.callback = callback;
        }
    }

    /**
     * Injection port for the fork-side destination policy (ADR-0015 Decision
     * 12: owned by the direct-edit-side module). The platform side only sees
     * contract types; the fork registers an implementation at process start.
     * {@code captureDestination} returns the snapshot to persist at enqueue
     * time (never null for automatic adds); {@code route} maps the persisted
     * snapshot to a flush-time routing value, or null to leave the stock
     * upstream path untouched.
     */
    public interface DestinationResolver {
        @Nullable
        String captureDestination(@NonNull Context context, @NonNull String packageName,
                @NonNull UserHandle user);

        @Nullable
        DestinationRoute route(@Nullable String snapshot, long userSerial,
                @NonNull String packageName);
    }

    @Nullable
    private static volatile DestinationResolver sDestinationResolver;

    /** Registers the fork-side resolver; null restores the stock behavior. */
    public static void setDestinationResolver(@Nullable DestinationResolver resolver) {
        sDestinationResolver = resolver;
    }

    @Nullable
    public static DestinationResolver getDestinationResolver() {
        return sDestinationResolver;
    }
}
