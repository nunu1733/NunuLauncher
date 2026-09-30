/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static com.android.launcher3.provider.LauncherDbUtils.itemIdMatch;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.content.ContentValues;
import android.content.Context;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.LauncherModel.CallbackTask;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.Utilities;
import com.android.launcher3.celllayout.CellPosMapper;
import com.android.launcher3.celllayout.CellPosMapper.CellPos;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.BgDataModel.Callbacks;
import com.android.launcher3.model.data.CollectionInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.util.ContentWriter;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.ItemInfoMatcher;
import com.android.launcher3.util.LooperExecutor;
import com.android.launcher3.widget.LauncherWidgetHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Class for handling model updates.
 */
public class ModelWriter {

    private static final String TAG = "ModelWriter";

    private final Context mContext;
    private final LauncherModel mModel;
    private final BgDataModel mBgDataModel;
    private final LooperExecutor mUiExecutor;

    @Nullable
    private final Callbacks mOwner;

    private final boolean mVerifyChanges;

    // Keep track of delete operations that occur when an Undo option is present; we
    // may not commit.
    private final List<ModelTask> mDeleteRunnables = new ArrayList<>();
    private boolean mPreparingToUndo;
    private final CellPosMapper mCellPosMapper;

    public ModelWriter(Context context, LauncherModel model, BgDataModel dataModel,
            boolean verifyChanges, CellPosMapper cellPosMapper, @Nullable Callbacks owner) {
        mContext = context;
        mModel = model;
        mBgDataModel = dataModel;
        mVerifyChanges = verifyChanges;
        mOwner = owner;
        mCellPosMapper = cellPosMapper;
        mUiExecutor = Executors.MAIN_EXECUTOR;
    }

    private void updateItemInfoProps(
            ItemInfo item, int container, int screenId, int cellX, int cellY) {
        CellPos modelPos = mCellPosMapper.mapPresenterToModel(cellX, cellY, screenId, container);

        item.container = container;
        item.cellX = modelPos.cellX;
        item.cellY = modelPos.cellY;
        item.screenId = modelPos.screenId;

    }

    /**
     * Adds an item to the DB if it was not created previously, or move it to a new
     * <container, screen, cellX, cellY>
     */
    public void addOrMoveItemInDatabase(ItemInfo item,
            int container, int screenId, int cellX, int cellY) {
        if (item.id == ItemInfo.NO_ID) {
            // From all apps
            addItemToDatabase(item, container, screenId, cellX, cellY);
        } else {
            // From somewhere else
            moveItemInDatabase(item, container, screenId, cellX, cellY);
        }
    }

    private void checkItemInfoLocked(int itemId, ItemInfo item, StackTraceElement[] stackTrace) {
        ItemInfo modelItem = mBgDataModel.itemsIdMap.get(itemId);
        if (modelItem != null && item != modelItem) {
            // check all the data is consistent
            if (!Utilities.IS_DEBUG_DEVICE && !FeatureFlags.IS_STUDIO_BUILD
                    && modelItem instanceof WorkspaceItemInfo
                    && item instanceof WorkspaceItemInfo) {
                if (modelItem.title.toString().equals(item.title.toString()) &&
                        modelItem.getIntent().filterEquals(item.getIntent()) &&
                        modelItem.id == item.id &&
                        modelItem.itemType == item.itemType &&
                        modelItem.container == item.container &&
                        modelItem.screenId == item.screenId &&
                        modelItem.cellX == item.cellX &&
                        modelItem.cellY == item.cellY &&
                        modelItem.spanX == item.spanX &&
                        modelItem.spanY == item.spanY) {
                    // For all intents and purposes, this is the same object
                    return;
                }
            }

            // the modelItem needs to match up perfectly with item if our model is
            // to be consistent with the database-- for now, just require
            // modelItem == item or the equality check above
            String msg = "item: " + ((item != null) ? item.toString() : "null") +
                    "modelItem: " +
                    ((modelItem != null) ? modelItem.toString() : "null") +
                    "Error: ItemInfo passed to checkItemInfo doesn't match original";
            RuntimeException e = new RuntimeException(msg);
            if (stackTrace != null) {
                e.setStackTrace(stackTrace);
            }
            throw e;
        }
    }
    
    /**
     * Clears all views from the home screen.
     */
    public boolean clearAllHomeScreenViewsByType(int type) {
        final ArrayList<ItemInfo> itemsToRemove = new ArrayList<>();

        for (ItemInfo item : mBgDataModel.itemsIdMap) {
            if (item.container == type) {
                itemsToRemove.add(item);
            }
        }

        if (itemsToRemove.isEmpty()) {
            return false;
        }

        enqueueDeleteRunnable(newModelTask(() -> {
            final ModelDbController db = mModel.getModelDbController();

            for (ItemInfo item : itemsToRemove) {
                db.delete(TABLE_NAME, itemIdMatch(item.id), null);
                mBgDataModel.removeItem(mContext, item);
            }
        }));

        mModel.forceReload();
        return true;
    }

    /**
     * Move an item in the DB to a new <container, screen, cellX, cellY>
     */
    public void moveItemInDatabase(final ItemInfo item,
            int container, int screenId, int cellX, int cellY) {
        updateItemInfoProps(item, container, screenId, cellX, cellY);

        // Issue #269: Launcher reloads WorkspaceItemInfo icons as 1x1. Persist
        // the same representation whenever an existing icon enters the
        // desktop, regardless of its source container. This also closes the
        // folder -> hotseat -> desktop path without classifying the source
        // parent or changing widget/folder moves.
        final boolean normalizeWorkspaceIcon = container == Favorites.CONTAINER_DESKTOP
                && item instanceof WorkspaceItemInfo;
        if (normalizeWorkspaceIcon) {
            item.spanX = 1;
            item.spanY = 1;
        }
        notifyItemModified(item);

        enqueueDeleteRunnable(new UpdateItemRunnable(item, () -> {
            ContentWriter writer = new ContentWriter(mContext)
                    .put(Favorites.CONTAINER, item.container)
                    .put(Favorites.CELLX, item.cellX)
                    .put(Favorites.CELLY, item.cellY)
                    .put(Favorites.RANK, item.rank)
                    .put(Favorites.SCREEN, item.screenId);
            if (normalizeWorkspaceIcon) {
                writer.put(Favorites.SPANX, item.spanX)
                        .put(Favorites.SPANY, item.spanY);
            }
            return writer;
        }));
    }

    /**
     * Move items in the DB to a new <container, screen, cellX, cellY>. We assume
     * that the
     * cellX, cellY have already been updated on the ItemInfos.
     */
    public void moveItemsInDatabase(final ArrayList<ItemInfo> items, int container, int screen) {
        ArrayList<ContentValues> contentValues = new ArrayList<>();
        int count = items.size();
        notifyOtherCallbacks(c -> c.bindItemsModified(items));

        for (int i = 0; i < count; i++) {
            ItemInfo item = items.get(i);
            updateItemInfoProps(item, container, screen, item.cellX, item.cellY);

            final ContentValues values = new ContentValues();
            values.put(Favorites.CONTAINER, item.container);
            values.put(Favorites.CELLX, item.cellX);
            values.put(Favorites.CELLY, item.cellY);
            values.put(Favorites.RANK, item.rank);
            values.put(Favorites.SCREEN, item.screenId);

            contentValues.add(values);
        }
        enqueueDeleteRunnable(new UpdateItemsRunnable(items, contentValues));
    }

    /**
     * Move and/or resize item in the DB to a new <container, screen, cellX, cellY,
     * spanX, spanY>
     */
    public void modifyItemInDatabase(final ItemInfo item,
            int container, int screenId, int cellX, int cellY, int spanX, int spanY) {
        updateItemInfoProps(item, container, screenId, cellX, cellY);
        item.spanX = spanX;
        item.spanY = spanY;
        notifyItemModified(item);
        new UpdateItemRunnable(item, () -> new ContentWriter(mContext)
                .put(Favorites.CONTAINER, item.container)
                .put(Favorites.CELLX, item.cellX)
                .put(Favorites.CELLY, item.cellY)
                .put(Favorites.RANK, item.rank)
                .put(Favorites.SPANX, item.spanX)
                .put(Favorites.SPANY, item.spanY)
                .put(Favorites.SCREEN, item.screenId))
                .executeOnModelThread();
    }

    /**
     * Update an item to the database in a specified container.
     */
    public void updateItemInDatabase(ItemInfo item) {
        notifyItemModified(item);
        new UpdateItemRunnable(item, () -> {
            ContentWriter writer = new ContentWriter(mContext);
            item.onAddToDatabase(writer);
            return writer;
        }).executeOnModelThread();
    }

    private void notifyItemModified(ItemInfo item) {
        notifyOtherCallbacks(c -> c.bindItemsModified(Collections.singletonList(item)));
    }

    /**
     * Add an item to the database in a specified container. Sets the container,
     * screen, cellX and
     * cellY fields of the item. Also assigns an ID to the item.
     */
    public void addItemToDatabase(final ItemInfo item,
            int container, int screenId, int cellX, int cellY) {
        updateItemInfoProps(item, container, screenId, cellX, cellY);

        item.id = mModel.getModelDbController().generateNewItemId();
        notifyOtherCallbacks(c -> c.bindItems(Collections.singletonList(item), false));

        ModelVerifier verifier = new ModelVerifier();
        final StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        newModelTask(() -> {
            // Write the item on background thread, as some properties might have been
            // updated in
            // the background.
            final ContentWriter writer = new ContentWriter(mContext);
            item.onAddToDatabase(writer);
            writer.put(Favorites._ID, item.id);

            mModel.getModelDbController().insert(Favorites.TABLE_NAME, writer.getValues(mContext));
            synchronized (mBgDataModel) {
                checkItemInfoLocked(item.id, item, stackTrace);
                mBgDataModel.addItem(mContext, item, true);
                verifier.verifyModel();
            }
        }).executeOnModelThread();
    }

    /**
     * Removes the specified item from the database
     */
    public void deleteItemFromDatabase(ItemInfo item, @Nullable final String reason) {
        deleteItemsFromDatabase(Arrays.asList(item), reason);
    }

    /**
     * Removes all the items from the database matching {@param matcher}.
     */
    public void deleteItemsFromDatabase(@NonNull final Predicate<ItemInfo> matcher,
            @Nullable final String reason) {
        deleteItemsFromDatabase(StreamSupport.stream(mBgDataModel.itemsIdMap.spliterator(), false)
                .filter(matcher).collect(Collectors.toList()), reason);
    }

    /**
     * Removes the specified items from the database
     */
    public void deleteItemsFromDatabase(final Collection<? extends ItemInfo> items,
            @Nullable final String reason) {
        ModelVerifier verifier = new ModelVerifier();
        FileLog.d(TAG, "removing items from db " + items.stream().map(
                (item) -> item.getTargetComponent() == null ? ""
                        : item.getTargetComponent().getPackageName())
                .collect(
                        Collectors.joining(","))
                + ". Reason: [" + (TextUtils.isEmpty(reason) ? "unknown" : reason) + "]");
        notifyDelete(items);
        enqueueDeleteRunnable(newModelTask(() -> {
            for (ItemInfo item : items) {
                mModel.getModelDbController().delete(TABLE_NAME, itemIdMatch(item.id), null);
                mBgDataModel.removeItem(mContext, item);
                verifier.verifyModel();
            }
        }));
    }

    /**
     * Remove the specified folder and all its contents from the database.
     */
    public void deleteCollectionAndContentsFromDatabase(final CollectionInfo info) {
        ModelVerifier verifier = new ModelVerifier();
        notifyDelete(Collections.singleton(info));

        enqueueDeleteRunnable(newModelTask(() -> {
            mModel.getModelDbController().delete(Favorites.TABLE_NAME,
                    Favorites.CONTAINER + "=" + info.id, null);
            mBgDataModel.removeItem(mContext, info.getContents());
            info.getContents().clear();

            mModel.getModelDbController().delete(Favorites.TABLE_NAME,
                    Favorites._ID + "=" + info.id, null);
            mBgDataModel.removeItem(mContext, info);
            verifier.verifyModel();
        }));
    }

    /**
     * Deletes the widget info and the widget id.
     */
    public void deleteWidgetInfo(final LauncherAppWidgetInfo info, LauncherWidgetHolder holder,
            @Nullable final String reason) {
        notifyDelete(Collections.singleton(info));
        if (holder != null && !info.isCustomWidget() && info.isWidgetIdAllocated()) {
            // Deleting an app widget ID is a void call but writes to disk before returning
            // to the caller...
            enqueueDeleteRunnable(newModelTask(() -> holder.deleteAppWidgetId(info.appWidgetId)));
        }
        deleteItemFromDatabase(info, reason);
    }

    private void notifyDelete(Collection<? extends ItemInfo> items) {
        notifyOtherCallbacks(c -> c.bindWorkspaceComponentsRemoved(ItemInfoMatcher.ofItems(items)));
    }

    /**
     * Delete operations tracked using {@link #enqueueDeleteRunnable} will only be
     * called
     * if {@link #commitDelete} is called. Note that one of {@link #commitDelete()}
     * or
     * {@link #abortDelete} MUST be called after this method, or else all delete
     * operations will remain uncommitted indefinitely.
     */
    public void prepareToUndoDelete() {
        if (!mPreparingToUndo) {
            if (!mDeleteRunnables.isEmpty() && FeatureFlags.IS_STUDIO_BUILD) {
                throw new IllegalStateException("There are still uncommitted delete operations!");
            }
            mDeleteRunnables.clear();
            mPreparingToUndo = true;
        }
    }

    /**
     * If {@link #prepareToUndoDelete} has been called, we store the Runnable to be
     * run when
     * {@link #commitDelete()} is called (or abandoned if {@link #abortDelete} is
     * called).
     * Otherwise, we run the Runnable immediately.
     */
    private void enqueueDeleteRunnable(ModelTask r) {
        if (mPreparingToUndo) {
            mDeleteRunnables.add(r);
        } else {
            r.executeOnModelThread();
        }
    }

    public void commitDelete() {
        mPreparingToUndo = false;
        mDeleteRunnables.forEach(ModelTask::executeOnModelThread);
        mDeleteRunnables.clear();
    }

    /**
     * Aborts a previous delete operation pending commit
     */
    public void abortDelete() {
        mPreparingToUndo = false;
        mDeleteRunnables.clear();
        // We do a full reload here instead of just a rebind because Folders change
        // their internal
        // state when dragging an item out, which clobbers the rebind unless we load
        // from the DB.
        mModel.forceReload();
    }

    private void notifyOtherCallbacks(CallbackTask task) {
        if (mOwner == null) {
            // If the call is happening from a model, it will take care of updating the
            // callbacks
            return;
        }
        mUiExecutor.execute(() -> {
            for (Callbacks c : mModel.getCallbacks()) {
                if (c != mOwner) {
                    task.execute(c);
                }
            }
        });
    }

    private class UpdateItemRunnable extends UpdateItemBaseRunnable {
        private final ItemInfo mItem;
        private final Supplier<ContentWriter> mWriter;
        private final int mItemId;

        UpdateItemRunnable(ItemInfo item, Supplier<ContentWriter> writer) {
            mItem = item;
            mWriter = writer;
            mItemId = item.id;
        }

        @Override
        public void runImpl() {
            mModel.getModelDbController().update(
                    TABLE_NAME, mWriter.get().getValues(mContext), itemIdMatch(mItemId), null);
            updateItemArrays(mItem, mItemId);
        }
    }

    private class UpdateItemsRunnable extends UpdateItemBaseRunnable {
        private final ArrayList<ContentValues> mValues;
        private final ArrayList<ItemInfo> mItems;

        UpdateItemsRunnable(ArrayList<ItemInfo> items, ArrayList<ContentValues> values) {
            mValues = values;
            mItems = items;
        }

        @Override
        public void runImpl() {
            try (SQLiteTransaction t = mModel.getModelDbController().newTransaction()) {
                int count = mItems.size();
                for (int i = 0; i < count; i++) {
                    ItemInfo item = mItems.get(i);
                    final int itemId = item.id;
                    mModel.getModelDbController().update(
                            TABLE_NAME, mValues.get(i), itemIdMatch(itemId), null);
                    updateItemArrays(item, itemId);
                }
                t.commit();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private abstract class UpdateItemBaseRunnable extends ModelTask {
        private final StackTraceElement[] mStackTrace;
        private final ModelVerifier mVerifier = new ModelVerifier();

        UpdateItemBaseRunnable() {
            mStackTrace = new Throwable().getStackTrace();
        }

        protected void updateItemArrays(ItemInfo item, int itemId) {
            // Lock on mBgLock *after* the db operation
            synchronized (mBgDataModel) {
                checkItemInfoLocked(itemId, item, mStackTrace);

                if (item.container != Favorites.CONTAINER_DESKTOP &&
                        item.container != Favorites.CONTAINER_HOTSEAT) {
                    // Item is in a collection, make sure this collection exists
                    if (!mBgDataModel.collections.containsKey(item.container)) {
                        // An items container is being set to a that of an item which is not in
                        // the list of Folders.
                        String msg = "item: " + item + " container being set to: " +
                                item.container + ", not in the list of collections";
                        Log.e(TAG, msg);
                    }
                }

                // Items are added/removed from the corresponding FolderInfo elsewhere, such
                // as in Workspace.onDrop. Here, we just add/remove them from the list of items
                // that are on the desktop, as appropriate
                ItemInfo modelItem = mBgDataModel.itemsIdMap.get(itemId);
                if (modelItem != null &&
                        (modelItem.container == Favorites.CONTAINER_DESKTOP ||
                                modelItem.container == Favorites.CONTAINER_HOTSEAT)) {
                    switch (modelItem.itemType) {
                        case Favorites.ITEM_TYPE_APPLICATION:
                        case Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                        case Favorites.ITEM_TYPE_FOLDER:
                        case Favorites.ITEM_TYPE_APP_PAIR:
                            if (!mBgDataModel.workspaceItems.contains(modelItem)) {
                                mBgDataModel.workspaceItems.add(modelItem);
                            }
                            break;
                        default:
                            break;
                    }
                } else {
                    mBgDataModel.workspaceItems.remove(modelItem);
                }
                mVerifier.verifyModel();
            }
        }
    }

    private abstract class ModelTask implements Runnable {

        private final int mLoadId = mBgDataModel.lastLoadId;

        @Override
        public final void run() {
            if (mLoadId != mModel.getLastLoadId()) {
                Log.d(TAG, "Model changed before the task could execute");
                return;
            }
            runImpl();
        }

        public final void executeOnModelThread() {
            // Issue #14: gate through coordinator; tokenless runnables defer when organizer
            // lease is active so they never block the exact reload task.
            LayoutWriteCoordinator coord = LayoutWriteCoordinator.getInstance();
            coord.runOrDefer(
                    LayoutWriteCoordinator.OwnerKind.MODEL_WRITER,
                    /* token= */ 0L,
                    /* exactOrganizerToken= */ false,
                    () -> MODEL_EXECUTOR.execute(this));
        }

        public abstract void runImpl();
    }

    private ModelTask newModelTask(Runnable r) {
        return new ModelTask() {
            @Override
            public void runImpl() {
                r.run();
            }
        };
    }

    /**
     * Issue #448: direct-edit move (page move, or add into an existing
     * folder). ADR-0013 contract 4: unlike the public update methods above,
     * nothing is mutated before admission — {@code validator} re-runs the pure
     * planning function against the current state inside admission (contract 2
     * stage 2) and only a positive decision reaches the model/DB change.
     * The write is one row update (naturally atomic, contract 3).
     *
     * @param targetContainer {@link Favorites#CONTAINER_DESKTOP} for a page
     *     move ({@code targetScreenId} is the destination page), or an
     *     existing folder id to append the item to that folder (rank ends up
     *     at {@code targetRank}, folder-internal position is derived).
     */
    public void moveItemForDirectEdit(int itemId, int targetContainer, int targetScreenId,
            int targetCellX, int targetCellY, int targetRank,
            DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
        new DirectEditMoveTask(itemId, targetContainer, targetScreenId,
                targetCellX, targetCellY, targetRank, validator, callback).executeOnModelThread();
    }

    /**
     * Issue #448: direct-edit "new folder" creation. Validates inside
     * admission, then inserts one folder row and moves the item into it as a
     * single transaction (contract 3). The item id is generated inside
     * admission; nothing is written before it.
     */
    public void createFolderAndMoveForDirectEdit(int itemId, int folderScreenId,
            int folderCellX, int folderCellY,
            DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
        new DirectEditCreateFolderTask(itemId, folderScreenId, folderCellX, folderCellY,
                validator, callback).executeOnModelThread();
    }

    /**
     * Issue #448: direct-edit "remove from home". Validates inside admission,
     * then deletes exactly the selected row (contract 6: an uninstall is not
     * performed and folder contents are left untouched). The pre-DELETE row is
     * captured in the same admission and reported through the undo payload
     * (Issue #450).
     */
    public void removeItemForDirectEdit(int itemId,
            DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
        new DirectEditRemoveTask(itemId, validator, callback).executeOnModelThread();
    }

    /**
     * Issue #450: undo of a direct-edit move ("ページへ移動…" / "フォルダへ入れる…").
     * Restores the recorded old placement in one row update (ADR-0013 contract
     * 5). The validator re-verifies inside admission that the item still sits
     * at the recorded result placement and the recorded old placement is free.
     */
    public void restorePlacementForDirectEdit(int itemId, int container, int screenId,
            int cellX, int cellY, int spanX, int spanY, int rank,
            DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
        new DirectEditRestorePlacementTask(itemId, container, screenId, cellX, cellY,
                spanX, spanY, rank, validator, callback).executeOnModelThread();
    }

    /**
     * Issue #450: undo of a direct-edit remove. Re-inserts the captured row
     * (one insert, contract 5) with a freshly allocated id. The validator
     * re-verifies placement preconditions and the launch-target availability
     * inside admission; a row whose launch target is gone is rejected with a
     * typed failure, never resurrected.
     */
    public void restoreRemovedItemForDirectEdit(DirectEditContract.UndoRowPayload payload,
            DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
        new DirectEditRestoreRemovedTask(payload, validator, callback).executeOnModelThread();
    }

    /**
     * Issue #450: undo of a direct-edit "新しいフォルダ". Restores the child to
     * its recorded placement and deletes the created folder row as one
     * transaction (contracts 3 and 5: all rows or none).
     */
    public void undoCreateFolderForDirectEdit(int itemId, int createdFolderId,
            int container, int screenId, int cellX, int cellY, int spanX, int spanY, int rank,
            DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
        new DirectEditUndoCreateFolderTask(itemId, createdFolderId, container, screenId,
                cellX, cellY, spanX, spanY, rank, validator, callback).executeOnModelThread();
    }

    /**
     * Builds the pure-data projection of the current state used by the
     * stage-2 validator. Model thread only.
     */
    private DirectEditContract.Snapshot buildDirectEditSnapshot() {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        int[] screenIds = mBgDataModel.collectWorkspaceScreens().toArray();
        List<DirectEditContract.Row> rows = new ArrayList<>();
        UserManager um = mContext.getSystemService(UserManager.class);
        for (ItemInfo item : mBgDataModel.itemsIdMap) {
            rows.add(new DirectEditContract.Row(item.id, item.container, item.screenId,
                    item.cellX, item.cellY, item.spanX, item.spanY, item.itemType, item.rank,
                    um.getSerialNumberForUser(item.user)));
        }
        // The QSB reservation occupies the head of the first screen; without
        // it the empty-cell scan would plan onto the search bar region.
        if (FeatureFlags.topQsbOnFirstScreenEnabled(mContext) && screenIds.length > 0) {
            rows.add(new DirectEditContract.Row(-1, Favorites.CONTAINER_DESKTOP, screenIds[0],
                    0, 0, idp.numSearchContainerColumns, 1, Favorites.ITEM_TYPE_APPLICATION, 0, 0));
        }
        return new DirectEditContract.Snapshot(
                idp.numColumns, idp.numRows, screenIds, rows.toArray(new DirectEditContract.Row[0]),
                LauncherAppState.getIDP(mContext).getDeviceProfile(mContext).numShownHotseatIcons);
    }

    /**
     * Base for admitted direct-edit tasks: fetch the item, run stage-2
     * validation against the current state, and only then perform the change.
     * Reports a typed failure without any change otherwise.
     */
    private abstract class DirectEditTask extends UpdateItemBaseRunnable {
        protected final int mItemId;
        private final DirectEditContract.Validator mValidator;
        private final DirectEditContract.ResultCallback mCallback;
        // The superclass keeps its capture private; direct-edit tasks record
        // their own evidence for the model consistency checks below.
        protected final StackTraceElement[] mEditStackTrace = new Throwable().getStackTrace();
        protected final ModelVerifier mEditVerifier = new ModelVerifier();

        DirectEditTask(int itemId,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            mItemId = itemId;
            mValidator = validator;
            mCallback = callback;
        }

        @Override
        public final void runImpl() {
            ItemInfo item = mBgDataModel.itemsIdMap.get(mItemId);
            DirectEditContract.Decision decision = mValidator.validate(buildDirectEditSnapshot());
            if (!decision.proceed || item == null) {
                reportFailure(item == null && decision.proceed
                        ? DirectEditContract.FAIL_ITEM_GONE : decision.failureReason);
                return;
            }
            runAdmitted(item);
        }

        protected abstract void runAdmitted(ItemInfo item);

        protected void reportFailure(String reason) {
            mCallback.onResult(mItemId, false, reason, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }

        protected void reportSuccess(ItemInfo item, int oldContainer, int oldScreenId,
                int oldCellX, int oldCellY, int oldSpanX, int oldSpanY, int oldRank,
                int createdFolderId, @Nullable FolderInfo createdFolder) {
            reportSuccess(item, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, createdFolderId, createdFolder, null);
        }

        /**
         * Issue #450: the remove-undo path reports the captured pre-DELETE row
         * payload through the extended #448 result contract; every other
         * direct-edit operation reports a null payload.
         */
        protected void reportSuccess(ItemInfo item, int oldContainer, int oldScreenId,
                int oldCellX, int oldCellY, int oldSpanX, int oldSpanY, int oldRank,
                int createdFolderId, @Nullable FolderInfo createdFolder,
                @Nullable DirectEditContract.UndoRowPayload removedRowPayload) {
            mCallback.onResult(item.id, true, null, oldContainer, oldScreenId,
                    oldCellX, oldCellY, oldSpanX, oldSpanY, oldRank, createdFolderId,
                    createdFolder, removedRowPayload);
        }
    }

    private class DirectEditMoveTask extends DirectEditTask {
        private final int mTargetContainer;
        private final int mTargetScreenId;
        private final int mTargetCellX;
        private final int mTargetCellY;
        private final int mTargetRank;

        DirectEditMoveTask(int itemId, int targetContainer, int targetScreenId,
                int targetCellX, int targetCellY, int targetRank,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            super(itemId, validator, callback);
            mTargetContainer = targetContainer;
            mTargetScreenId = targetScreenId;
            mTargetCellX = targetCellX;
            mTargetCellY = targetCellY;
            mTargetRank = targetRank;
        }

        @Override
        protected void runAdmitted(ItemInfo item) {
            int oldContainer = item.container;
            int oldScreenId = item.screenId;
            int oldCellX = item.cellX;
            int oldCellY = item.cellY;
            int oldSpanX = item.spanX;
            int oldSpanY = item.spanY;
            int oldRank = item.rank;

            boolean toDesktop = mTargetContainer == Favorites.CONTAINER_DESKTOP;
            // Build the row values without touching the live ItemInfo yet: a
            // failed update must leave the in-memory model unchanged too.
            ContentWriter writer = new ContentWriter(mContext)
                    .put(Favorites.CONTAINER, mTargetContainer)
                    .put(Favorites.CELLX, toDesktop ? mTargetCellX : -1)
                    .put(Favorites.CELLY, toDesktop ? mTargetCellY : -1)
                    .put(Favorites.RANK, toDesktop ? oldRank : mTargetRank)
                    .put(Favorites.SPANX, 1)
                    .put(Favorites.SPANY, 1)
                    .put(Favorites.SCREEN, toDesktop ? mTargetScreenId : 0);
            try {
                mModel.getModelDbController().update(TABLE_NAME, writer.getValues(mContext),
                        itemIdMatch(item.id), null);
            } catch (Exception e) {
                FileLog.e(TAG, "direct-edit move failed; nothing changed", e);
                reportFailure(DirectEditContract.FAIL_WRITE_FAILED);
                return;
            }
            // DB commit succeeded; now bring the live model object in sync.
            item.container = mTargetContainer;
            item.screenId = toDesktop ? mTargetScreenId : 0;
            item.cellX = toDesktop ? mTargetCellX : -1;
            item.cellY = toDesktop ? mTargetCellY : -1;
            // Issue #269: desktop icons are persisted as 1x1; folder children
            // are container-placed and the folder open path normalizes the
            // internal grid positions in batch.
            item.spanX = 1;
            item.spanY = 1;
            item.rank = toDesktop ? oldRank : mTargetRank;
            if (!toDesktop) {
                // updateItemArrays only maintains workspaceItems; the folder
                // membership bookkeeping mirrors the loader path. Silent
                // contents add: FolderInfo.add would notify the bound
                // FolderIcon from the model thread (view touch).
                CollectionInfo collection = mBgDataModel.collections.get(mTargetContainer);
                if (collection instanceof FolderInfo folder) {
                    folder.getContents().add(item);
                }
            }
            updateItemArrays(item, item.id);
            reportSuccess(item, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, 0, null, null);
        }
    }

    private class DirectEditCreateFolderTask extends DirectEditTask {
        private final int mFolderScreenId;
        private final int mFolderCellX;
        private final int mFolderCellY;

        DirectEditCreateFolderTask(int itemId, int folderScreenId, int folderCellX, int folderCellY,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            super(itemId, validator, callback);
            mFolderScreenId = folderScreenId;
            mFolderCellX = folderCellX;
            mFolderCellY = folderCellY;
        }

        @Override
        protected void runAdmitted(ItemInfo item) {
            int oldContainer = item.container;
            int oldScreenId = item.screenId;
            int oldCellX = item.cellX;
            int oldCellY = item.cellY;
            int oldSpanX = item.spanX;
            int oldSpanY = item.spanY;
            int oldRank = item.rank;

            FolderInfo folderInfo = new FolderInfo();
            folderInfo.id = mModel.getModelDbController().generateNewItemId();
            folderInfo.container = Favorites.CONTAINER_DESKTOP;
            folderInfo.screenId = mFolderScreenId;
            folderInfo.cellX = mFolderCellX;
            folderInfo.cellY = mFolderCellY;
            folderInfo.spanX = 1;
            folderInfo.spanY = 1;
            // Issue #450: the freshly created folder legitimately starts
            // with this single child; mark it (persisted OPTIONS bit) so the
            // automatic single-child cleanups (Folder bind/close/remove) do
            // not flatten it. The bit survives reloads and is removed with
            // the folder row (undo or user delete), so it never needs
            // clearing.
            folderInfo.options |= DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER;
            folderInfo.user = item.user;

            try (SQLiteTransaction t = mModel.getModelDbController().newTransaction()) {
                ContentWriter folderWriter = new ContentWriter(mContext);
                folderInfo.onAddToDatabase(folderWriter);
                folderWriter.put(Favorites._ID, folderInfo.id);
                mModel.getModelDbController().insert(
                        Favorites.TABLE_NAME, folderWriter.getValues(mContext));

                // Explicit child values: the live ItemInfo is mutated only
                // after the commit so a failed transaction leaves model and
                // DB both at the old placement (contract 3).
                ContentWriter childWriter = new ContentWriter(mContext)
                        .put(Favorites.CONTAINER, folderInfo.id)
                        .put(Favorites.CELLX, -1)
                        .put(Favorites.CELLY, -1)
                        .put(Favorites.RANK, 0)
                        .put(Favorites.SPANX, 1)
                        .put(Favorites.SPANY, 1)
                        .put(Favorites.SCREEN, 0);
                mModel.getModelDbController().update(TABLE_NAME,
                        childWriter.getValues(mContext), itemIdMatch(item.id), null);
                t.commit();
            } catch (Exception e) {
                FileLog.e(TAG, "direct-edit folder creation failed; rolled back", e);
                reportFailure(DirectEditContract.FAIL_WRITE_FAILED);
                return;
            }

            // Commit succeeded; sync the live model objects.
            item.container = folderInfo.id;
            item.screenId = 0;
            item.cellX = -1;
            item.cellY = -1;
            item.spanX = 1;
            item.spanY = 1;
            item.rank = 0;
            synchronized (mBgDataModel) {
                checkItemInfoLocked(folderInfo.id, folderInfo, mEditStackTrace);
                mBgDataModel.addItem(mContext, folderInfo, true);
                mEditVerifier.verifyModel();
                // Silent contents add; the executor refreshes the folder UI.
                folderInfo.getContents().add(item);
                updateItemArrays(item, item.id);
            }
            notifyOtherCallbacks(c -> c.bindItems(Collections.singletonList(folderInfo), false));
            notifyOtherCallbacks(c -> c.bindItemsModified(Collections.singletonList(item)));
            reportSuccess(item, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, folderInfo.id, folderInfo, null);
        }
    }

    private class DirectEditRemoveTask extends DirectEditTask {
        DirectEditRemoveTask(int itemId,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            super(itemId, validator, callback);
        }

        @Override
        protected void runAdmitted(ItemInfo item) {
            int oldContainer = item.container;
            int oldScreenId = item.screenId;
            int oldCellX = item.cellX;
            int oldCellY = item.cellY;
            int oldSpanX = item.spanX;
            int oldSpanY = item.spanY;
            int oldRank = item.rank;

            // Issue #450: capture the full row inside the same admission,
            // immediately before the DELETE — after the delete the row content
            // is unrecoverable, and capturing here structurally excludes any
            // change between stage-1 validation and the write. A failed capture
            // fails the whole remove (fail-closed, nothing written).
            DirectEditContract.UndoRowPayload payload = queryUndoRowPayload(item.id);
            if (payload == null) {
                reportFailure(DirectEditContract.FAIL_UNDO_WRITE_FAILED);
                return;
            }

            try {
                mModel.getModelDbController().delete(TABLE_NAME, itemIdMatch(item.id), null);
            } catch (Exception e) {
                FileLog.e(TAG, "direct-edit remove failed; nothing changed", e);
                reportFailure(DirectEditContract.FAIL_WRITE_FAILED);
                return;
            }
            synchronized (mBgDataModel) {
                mBgDataModel.removeItem(mContext, item);
                mEditVerifier.verifyModel();
            }
            notifyOtherCallbacks(c -> c.bindWorkspaceComponentsRemoved(
                    ItemInfoMatcher.ofItems(Collections.singletonList(item))));
            reportSuccess(item, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, 0, null, payload);
        }
    }

    /**
     * Issue #450: full favorites-row projection for the remove-undo. Model
     * thread only. Returns null when the row cannot be read (treated as a
     * write failure by the caller — never an optimistic remove).
     */
    private @Nullable DirectEditContract.UndoRowPayload queryUndoRowPayload(int itemId) {
        android.database.Cursor c = null;
        try {
            c = mModel.getModelDbController().getDb().query(TABLE_NAME, null,
                    itemIdMatch(itemId), null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            int itemType = c.getInt(c.getColumnIndexOrThrow(Favorites.ITEM_TYPE));
            String intentText = c.getString(c.getColumnIndexOrThrow(Favorites.INTENT));
            String componentName = null;
            String packageName = null;
            String shortcutId = null;
            if (intentText != null && (itemType == Favorites.ITEM_TYPE_APPLICATION
                    || itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT)) {
                try {
                    android.content.Intent intent = android.content.Intent.parseUri(intentText, 0);
                    if (itemType == Favorites.ITEM_TYPE_APPLICATION) {
                        componentName = intent.getComponent() != null
                                ? intent.getComponent().flattenToString() : null;
                    } else {
                        packageName = intent.getPackage() != null
                                ? intent.getPackage() : intent.getComponent() != null
                                ? intent.getComponent().getPackageName() : null;
                        shortcutId = intent.getStringExtra(
                                com.android.launcher3.shortcuts.ShortcutKey.EXTRA_SHORTCUT_ID);
                    }
                } catch (java.net.URISyntaxException e) {
                    FileLog.e(TAG, "undo payload intent parse failed", e);
                    return null;
                }
            }
            return new DirectEditContract.UndoRowPayload(
                    itemId,
                    itemType,
                    c.getInt(c.getColumnIndexOrThrow(Favorites.CONTAINER)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.SCREEN)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.CELLX)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.CELLY)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.SPANX)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.SPANY)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.RANK)),
                    c.getLong(c.getColumnIndexOrThrow(Favorites.PROFILE_ID)),
                    intentText,
                    c.getString(c.getColumnIndexOrThrow(Favorites.TITLE)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.OPTIONS)),
                    c.getInt(c.getColumnIndexOrThrow(Favorites.ORGANIZER_LOCK_STATE)),
                    componentName,
                    packageName,
                    shortcutId);
        } catch (Exception e) {
            FileLog.e(TAG, "undo row capture failed", e);
            return null;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Issue #450: admitted undo of a direct-edit move. Writes the recorded old
     * placement back verbatim in one update (contract 5); the fork-side
     * validator has re-verified inside admission that the item still sits at
     * the recorded result placement and the old placement is free.
     */
    private class DirectEditRestorePlacementTask extends DirectEditTask {
        private final int mRestoreContainer;
        private final int mRestoreScreenId;
        private final int mRestoreCellX;
        private final int mRestoreCellY;
        private final int mRestoreSpanX;
        private final int mRestoreSpanY;
        private final int mRestoreRank;

        DirectEditRestorePlacementTask(int itemId, int container, int screenId,
                int cellX, int cellY, int spanX, int spanY, int rank,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            super(itemId, validator, callback);
            mRestoreContainer = container;
            mRestoreScreenId = screenId;
            mRestoreCellX = cellX;
            mRestoreCellY = cellY;
            mRestoreSpanX = spanX;
            mRestoreSpanY = spanY;
            mRestoreRank = rank;
        }

        @Override
        protected void runAdmitted(ItemInfo item) {
            int oldContainer = item.container;
            int oldScreenId = item.screenId;
            int oldCellX = item.cellX;
            int oldCellY = item.cellY;
            int oldSpanX = item.spanX;
            int oldSpanY = item.spanY;
            int oldRank = item.rank;

            ContentWriter writer = new ContentWriter(mContext)
                    .put(Favorites.CONTAINER, mRestoreContainer)
                    .put(Favorites.SCREEN, mRestoreScreenId)
                    .put(Favorites.CELLX, mRestoreCellX)
                    .put(Favorites.CELLY, mRestoreCellY)
                    .put(Favorites.SPANX, mRestoreSpanX)
                    .put(Favorites.SPANY, mRestoreSpanY)
                    .put(Favorites.RANK, mRestoreRank);
            try {
                mModel.getModelDbController().update(TABLE_NAME, writer.getValues(mContext),
                        itemIdMatch(item.id), null);
            } catch (Exception e) {
                FileLog.e(TAG, "direct-edit restore placement failed; nothing changed", e);
                reportFailure(DirectEditContract.FAIL_UNDO_WRITE_FAILED);
                return;
            }

            // DB commit succeeded; mirror the live model object. Membership in
            // a folder the undo leaves is dropped, membership in a folder the
            // undo re-enters is added (mirrors the loader path bookkeeping).
            synchronized (mBgDataModel) {
                if (oldContainer != Favorites.CONTAINER_DESKTOP
                        && oldContainer != Favorites.CONTAINER_HOTSEAT) {
                    CollectionInfo previous = mBgDataModel.collections.get(oldContainer);
                    if (previous instanceof FolderInfo previousFolder) {
                        previousFolder.getContents().remove(item);
                    }
                }
                item.container = mRestoreContainer;
                item.screenId = mRestoreScreenId;
                item.cellX = mRestoreCellX;
                item.cellY = mRestoreCellY;
                item.spanX = mRestoreSpanX;
                item.spanY = mRestoreSpanY;
                item.rank = mRestoreRank;
                if (mRestoreContainer != Favorites.CONTAINER_DESKTOP
                        && mRestoreContainer != Favorites.CONTAINER_HOTSEAT) {
                    CollectionInfo target = mBgDataModel.collections.get(mRestoreContainer);
                    if (target instanceof FolderInfo targetFolder) {
                        targetFolder.getContents().add(item);
                    }
                }
                updateItemArrays(item, item.id);
            }
            notifyOtherCallbacks(c -> c.bindItemsModified(Collections.singletonList(item)));
            reportSuccess(item, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, 0, null, null);
        }
    }

    /**
     * Issue #450: admitted undo of a direct-edit remove. Re-inserts the
     * captured row with a freshly allocated id (the old id may already be
     * reused by SQLite) and restores the captured row content verbatim,
     * including the organizerLockState column. The base {@link DirectEditTask}
     * cannot be reused because the item is intentionally absent from the model.
     */
    private class DirectEditRestoreRemovedTask extends UpdateItemBaseRunnable {
        private final DirectEditContract.UndoRowPayload mPayload;
        private final DirectEditContract.Validator mValidator;
        private final DirectEditContract.ResultCallback mCallback;
        private final StackTraceElement[] mEditStackTrace = new Throwable().getStackTrace();
        private final ModelVerifier mEditVerifier = new ModelVerifier();

        DirectEditRestoreRemovedTask(DirectEditContract.UndoRowPayload payload,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            mPayload = payload;
            mValidator = validator;
            mCallback = callback;
        }

        private void reportUndoFailure(String reason) {
            mCallback.onResult(mPayload.itemId, false, reason,
                    0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }

        @Override
        public void runImpl() {
            // Stage 2 (placement + availability via the same fork-side
            // validator used at stage 1). The removed row must be absent: a
            // re-created item means the recorded precondition no longer holds.
            DirectEditContract.Decision decision = mValidator.validate(buildDirectEditSnapshot());
            if (!decision.proceed) {
                reportUndoFailure(decision.failureReason);
                return;
            }
            if (mBgDataModel.itemsIdMap.get(mPayload.itemId) != null) {
                reportUndoFailure(DirectEditContract.FAIL_UNDO_STALE);
                return;
            }

            WorkspaceItemInfo restored = new WorkspaceItemInfo();
            restored.id = mModel.getModelDbController().generateNewItemId();
            restored.itemType = mPayload.itemType;
            restored.container = mPayload.container;
            restored.screenId = mPayload.screenId;
            restored.cellX = mPayload.cellX;
            restored.cellY = mPayload.cellY;
            restored.spanX = mPayload.spanX;
            restored.spanY = mPayload.spanY;
            restored.rank = mPayload.rank;
            restored.title = mPayload.title;
            restored.options = mPayload.options;
            restored.user = mContext.getSystemService(UserManager.class)
                    .getUserForSerialNumber(mPayload.userSerial);
            if (mPayload.intent != null) {
                try {
                    restored.intent = android.content.Intent.parseUri(mPayload.intent, 0);
                } catch (java.net.URISyntaxException e) {
                    FileLog.e(TAG, "remove-undo intent parse failed; nothing changed", e);
                    reportUndoFailure(DirectEditContract.FAIL_UNDO_WRITE_FAILED);
                    return;
                }
            }

            try {
                ContentWriter writer = new ContentWriter(mContext);
                restored.onAddToDatabase(writer);
                writer.put(Favorites._ID, restored.id)
                        .put(Favorites.CONTAINER, restored.container)
                        .put(Favorites.SCREEN, restored.screenId)
                        .put(Favorites.CELLX, restored.cellX)
                        .put(Favorites.CELLY, restored.cellY)
                        .put(Favorites.SPANX, restored.spanX)
                        .put(Favorites.SPANY, restored.spanY)
                        .put(Favorites.RANK, restored.rank)
                        .put(Favorites.OPTIONS, restored.options)
                        .put(Favorites.ORGANIZER_LOCK_STATE, mPayload.organizerLockState);
                mModel.getModelDbController().insert(TABLE_NAME, writer.getValues(mContext));
            } catch (Exception e) {
                FileLog.e(TAG, "remove-undo insert failed; nothing changed", e);
                reportUndoFailure(DirectEditContract.FAIL_UNDO_WRITE_FAILED);
                return;
            }

            synchronized (mBgDataModel) {
                checkItemInfoLocked(restored.id, restored, mEditStackTrace);
                mBgDataModel.addItem(mContext, restored, true);
                mEditVerifier.verifyModel();
            }
            notifyOtherCallbacks(c -> c.bindItems(Collections.singletonList(restored), false));
            mCallback.onResult(restored.id, true, null,
                    0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
    }

    /**
     * Issue #450: admitted undo of a direct-edit "新しいフォルダ". Restores the
     * child to its recorded placement and deletes the created folder row in
     * one transaction (contract 3); on failure nothing changes.
     */
    private class DirectEditUndoCreateFolderTask extends DirectEditTask {
        private final int mCreatedFolderId;
        private final int mRestoreContainer;
        private final int mRestoreScreenId;
        private final int mRestoreCellX;
        private final int mRestoreCellY;
        private final int mRestoreSpanX;
        private final int mRestoreSpanY;
        private final int mRestoreRank;

        DirectEditUndoCreateFolderTask(int itemId, int createdFolderId, int container,
                int screenId, int cellX, int cellY, int spanX, int spanY, int rank,
                DirectEditContract.Validator validator, DirectEditContract.ResultCallback callback) {
            super(itemId, validator, callback);
            mCreatedFolderId = createdFolderId;
            mRestoreContainer = container;
            mRestoreScreenId = screenId;
            mRestoreCellX = cellX;
            mRestoreCellY = cellY;
            mRestoreSpanX = spanX;
            mRestoreSpanY = spanY;
            mRestoreRank = rank;
        }

        @Override
        protected void runAdmitted(ItemInfo item) {
            int oldContainer = item.container;
            int oldScreenId = item.screenId;
            int oldCellX = item.cellX;
            int oldCellY = item.cellY;
            int oldSpanX = item.spanX;
            int oldSpanY = item.spanY;
            int oldRank = item.rank;

            try (SQLiteTransaction t = mModel.getModelDbController().newTransaction()) {
                ContentWriter restore = new ContentWriter(mContext)
                        .put(Favorites.CONTAINER, mRestoreContainer)
                        .put(Favorites.SCREEN, mRestoreScreenId)
                        .put(Favorites.CELLX, mRestoreCellX)
                        .put(Favorites.CELLY, mRestoreCellY)
                        .put(Favorites.SPANX, mRestoreSpanX)
                        .put(Favorites.SPANY, mRestoreSpanY)
                        .put(Favorites.RANK, mRestoreRank);
                mModel.getModelDbController().update(TABLE_NAME, restore.getValues(mContext),
                        itemIdMatch(item.id), null);
                mModel.getModelDbController().delete(TABLE_NAME,
                        itemIdMatch(mCreatedFolderId), null);
                t.commit();
            } catch (Exception e) {
                FileLog.e(TAG, "direct-edit folder undo failed; rolled back", e);
                reportFailure(DirectEditContract.FAIL_UNDO_WRITE_FAILED);
                return;
            }

            // Commit succeeded; sync the live model objects (the exact inverse
            // of DirectEditCreateFolderTask's sync).
            FolderInfo folder =
                    mBgDataModel.collections.get(mCreatedFolderId) instanceof FolderInfo f ? f : null;
            synchronized (mBgDataModel) {
                if (folder != null) {
                    folder.getContents().remove(item);
                }
                item.container = mRestoreContainer;
                item.screenId = mRestoreScreenId;
                item.cellX = mRestoreCellX;
                item.cellY = mRestoreCellY;
                item.spanX = mRestoreSpanX;
                item.spanY = mRestoreSpanY;
                item.rank = mRestoreRank;
                if (mRestoreContainer != Favorites.CONTAINER_DESKTOP
                        && mRestoreContainer != Favorites.CONTAINER_HOTSEAT) {
                    CollectionInfo target = mBgDataModel.collections.get(mRestoreContainer);
                    if (target instanceof FolderInfo targetFolder) {
                        targetFolder.getContents().add(item);
                    }
                }
                updateItemArrays(item, item.id);
                if (folder != null) {
                    mBgDataModel.removeItem(mContext, folder);
                }
                mEditVerifier.verifyModel();
            }
            if (folder != null) {
                notifyOtherCallbacks(c -> c.bindWorkspaceComponentsRemoved(
                        ItemInfoMatcher.ofItems(Collections.singletonList(folder))));
            }
            notifyOtherCallbacks(c -> c.bindItemsModified(Collections.singletonList(item)));
            reportSuccess(item, oldContainer, oldScreenId, oldCellX, oldCellY,
                    oldSpanX, oldSpanY, oldRank, 0, null, null);
        }
    }

    /**
     * Utility class to verify model updates are propagated properly to the
     * callback.
     */
    public class ModelVerifier {

        final int startId;

        ModelVerifier() {
            startId = mBgDataModel.lastBindId;
        }

        void verifyModel() {
            if (!mVerifyChanges || !mModel.hasCallbacks()) {
                return;
            }

            int executeId = mBgDataModel.lastBindId;

            mUiExecutor.post(() -> {
                int currentId = mBgDataModel.lastBindId;
                if (currentId > executeId) {
                    // Model was already bound after job was executed.
                    return;
                }
                if (executeId == startId) {
                    // Bound model has not changed during the job
                    return;
                }

                // Bound model was changed between submitting the job and executing the job
                mModel.rebindCallbacks();
            });
        }
    }
}
