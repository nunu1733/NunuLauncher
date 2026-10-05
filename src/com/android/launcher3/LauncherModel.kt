/*
 * Copyright (C) 2008 The Android Open Source Project
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
package com.android.launcher3

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.os.Looper
import android.os.UserHandle
import androidx.annotation.WorkerThread
import com.android.launcher3.celllayout.CellPosMapper
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.IconCache
import com.android.launcher3.logging.DumpManager
import com.android.launcher3.logging.DumpManager.LauncherDumpable
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BaseLauncherBinder.BaseLauncherBinderFactory
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.CacheDataUpdatedTask
import com.android.launcher3.model.ItemInstallQueue
import com.android.launcher3.model.LoaderTask
import com.android.launcher3.model.LoaderTask.LoaderTaskFactory
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.ModelDelegate
import com.android.launcher3.model.ModelInitializer
import com.android.launcher3.model.ModelLauncherCallbacks
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.ModelWriter
import com.android.launcher3.model.PackageUpdatedTask
import com.android.launcher3.model.ShortcutsChangedTask
import com.android.launcher3.model.UserLockStateChangedTask
import com.android.launcher3.model.UserManagerState
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.shortcuts.ShortcutRequest
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import com.android.launcher3.util.PackageUserKey
import app.lawnchair.organizer.application.adapter.ModelProjectionCodec
import app.lawnchair.organizer.application.protocol.ModelSnapshot
import java.io.PrintWriter
import java.util.concurrent.CancellationException
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

/**
 * Maintains in-memory state of the Launcher. It is expected that there should be only one
 * LauncherModel object held in a static. Also provide APIs for updating the database state for the
 * Launcher.
 */
@LauncherAppSingleton
class LauncherModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val taskControllerProvider: Provider<ModelTaskController>,
    private val iconCache: IconCache,
    private val prefs: LauncherPrefs,
    private val installQueue: ItemInstallQueue,
    @Named("ICONS_DB") dbFileName: String?,
    initializer: ModelInitializer,
    lifecycle: DaggerSingletonTracker,
    val modelDelegate: ModelDelegate,
    private val mBgAllAppsList: AllAppsList,
    private val mBgDataModel: BgDataModel,
    private val loaderFactory: LoaderTaskFactory,
    private val binderFactory: BaseLauncherBinderFactory,
    val modelDbController: ModelDbController,
    dumpManager: DumpManager,
) : LauncherDumpable {

    private val mCallbacksList = ArrayList<BgDataModel.Callbacks>(1)

    private val mLock = Any()

    private var mLoaderTask: LoaderTask? = null
    private var mIsLoaderTaskRunning = false

    // only allow this once per reboot to reload work apps
    private var mShouldReloadWorkProfile = true

    // Indicates whether the current model data is valid or not.
    // We start off with everything not loaded. After that, we assume that
    // our monitoring of the package manager provides all updates and we never
    // need to do a requery. This is only ever touched from the loader thread.
    private var mModelLoaded = false
    private var mModelDestroyed = false

    // Issue #14 (fork contract port): package-private bridge state for correlated reload.
    private var mOrganizerReloadToken: OrganizerReloadRequest? = null


    fun isModelLoaded() =
        synchronized(mLock) { mModelLoaded && mLoaderTask == null && !mModelDestroyed }

    /**
     * Returns the ID for the last model load. If the load ID doesn't match for a transaction, the
     * transaction should be ignored.
     */
    var lastLoadId: Int = -1
        private set

    // Runnable to check if the shortcuts permission has changed.
    private val mDataValidationCheck = Runnable {
        if (mModelLoaded) {
            modelDelegate.validateData()
        }
    }

    init {
        if (!dbFileName.isNullOrEmpty()) {
            initializer.initialize(this)
        }
        lifecycle.addCloseable { destroy() }
        modelDelegate.init(this, mBgAllAppsList, mBgDataModel)
        lifecycle.addCloseable(dumpManager.register(this))
    }

    fun newModelCallbacks() = ModelLauncherCallbacks(this::enqueueModelUpdateTask)

    fun getWriter(
        verifyChanges: Boolean,
        cellPosMapper: CellPosMapper?,
        owner: BgDataModel.Callbacks?,
    ) = ModelWriter(context, this, mBgDataModel, verifyChanges, cellPosMapper, owner)

    /** Called when the icon for an app changes, outside of package event */
    @WorkerThread
    fun onAppIconChanged(packageName: String, user: UserHandle) {
        // Update the icon for the calendar package
        enqueueModelUpdateTask(PackageUpdatedTask(PackageUpdatedTask.OP_UPDATE, user, packageName))
        ShortcutRequest(context, user).forPackage(packageName).query(ShortcutRequest.PINNED).let {
            if (it.isNotEmpty()) {
                enqueueModelUpdateTask(ShortcutsChangedTask(packageName, it, user, false))
            }
        }
    }

    /** Called when the workspace items have drastically changed */
    fun onWorkspaceUiChanged() {
        MODEL_EXECUTOR.execute(modelDelegate::workspaceLoadComplete)
    }

    /** Called when the model is destroyed */
    fun destroy() {
        mModelDestroyed = true
        MODEL_EXECUTOR.execute { modelDelegate.destroy() }
    }

    /**
     * Called then there use a user event
     *
     * @see UserCache.addUserEventListener
     */
    fun onUserEvent(user: UserHandle, action: String) {
        when (action) {
            Intent.ACTION_MANAGED_PROFILE_AVAILABLE -> {
                if (mShouldReloadWorkProfile) {
                    forceReload()
                } else {
                    enqueueModelUpdateTask(
                        PackageUpdatedTask(PackageUpdatedTask.OP_USER_AVAILABILITY_CHANGE, user)
                    )
                }
                mShouldReloadWorkProfile = false
            }
            Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE -> {
                mShouldReloadWorkProfile = false
                enqueueModelUpdateTask(
                    PackageUpdatedTask(PackageUpdatedTask.OP_USER_AVAILABILITY_CHANGE, user)
                )
            }
            UserCache.ACTION_PROFILE_LOCKED ->
                enqueueModelUpdateTask(UserLockStateChangedTask(user, false))
            UserCache.ACTION_PROFILE_UNLOCKED ->
                enqueueModelUpdateTask(UserLockStateChangedTask(user, true))
            Intent.ACTION_MANAGED_PROFILE_REMOVED -> {
                prefs.put(LauncherPrefs.WORK_EDU_STEP, 0)
                forceReload()
            }
            UserCache.ACTION_PROFILE_ADDED,
            UserCache.ACTION_PROFILE_REMOVED -> forceReload()
            UserCache.ACTION_PROFILE_AVAILABLE,
            UserCache.ACTION_PROFILE_UNAVAILABLE -> {
                // This broadcast is only available when android.os.Flags.allowPrivateProfile() is
                // set. For Work-profile this broadcast will be sent in addition to
                // ACTION_MANAGED_PROFILE_AVAILABLE/UNAVAILABLE. So effectively, this if block only
                // handles the non-work profile case.
                enqueueModelUpdateTask(
                    PackageUpdatedTask(PackageUpdatedTask.OP_USER_AVAILABILITY_CHANGE, user)
                )
            }
        }
    }

    /**
     * Reloads the workspace items from the DB and re-binds the workspace. This should generally not
     * be called as DB updates are automatically followed by UI update
     */
    fun forceReload() {
        synchronized(mLock) {
            // Stop any existing loaders first, so they don't set mModelLoaded to true later
            stopLoader()
            mModelLoaded = false
        }
        rebindCallbacks()
    }

    /** Reloads the model if it is already in use */
    fun reloadIfActive() {
        val wasActive: Boolean
        synchronized(mLock) { wasActive = mModelLoaded || stopLoader() }
        if (wasActive) forceReload()
    }

    /**
     * Issue #58 (S3a rebase): quiesce the model before a raw DB-file restore. Stops the
     * running loader and clears the loaded-model state so no loader observes
     * half-replaced DB files. The correlated reload happens after restore sanitization
     * via [forceReload]; loaders posted in between defer behind the restore-family
     * coordinator lease.
     */
    fun quiesceForRestore() {
        synchronized(mLock) {
            stopLoader()
            mModelLoaded = false
        }
    }

    /** Rebinds all existing callbacks with already loaded model */
    fun rebindCallbacks() {
        if (hasCallbacks()) {
            startLoader()
        }
    }

    /** Removes an existing callback */
    fun removeCallbacks(callbacks: BgDataModel.Callbacks) {
        synchronized(mCallbacksList) {
            if (mCallbacksList.remove(callbacks)) {
                if (stopLoader()) {
                    // Rebind existing callbacks
                    startLoader()
                }
            }
        }
    }

    /**
     * Adds a callbacks to receive model updates
     *
     * @return true if workspace load was performed synchronously
     */
    fun addCallbacksAndLoad(callbacks: BgDataModel.Callbacks): Boolean {
        synchronized(mLock) {
            addCallbacks(callbacks)
            return startLoader(arrayOf(callbacks), false)
        }
    }

    /** Adds a callbacks to receive model updates */
    fun addCallbacks(callbacks: BgDataModel.Callbacks) {
        synchronized(mCallbacksList) { mCallbacksList.add(callbacks) }
    }

    /**
     * Starts the loader. Tries to bind {@params synchronousBindPage} synchronously if possible.
     *
     * @return true if the page could be bound synchronously.
     */
    fun startLoader() = startLoader(arrayOf(), false)

    /**
     * Issue #376 (fork contract port): starts the tokenless loader generation when
     * no callbacks are bound. The loader-running install-queue flag set here stays
     * paused until a real Launcher binds, matching the existing "the loader runs
     * the next time launcher starts" semantics. Must be called on the UI thread.
     */
    fun startLoaderWithoutCallbacks(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "startLoaderWithoutCallbacks must run on the UI thread"
        }
        return startLoader(arrayOf(), true)
    }

    private fun startLoader(newCallbacks: Array<BgDataModel.Callbacks>, allowEmptyCallbacks: Boolean): Boolean {
        // Enable queue before starting loader. It will get disabled in Launcher#finishBindingItems
        installQueue.pauseModelPush(ItemInstallQueue.FLAG_LOADER_RUNNING)
        synchronized(mLock) {
            // If there is already one running, tell it to stop.
            val wasRunning = stopLoader()
            val bindDirectly = mModelLoaded && !mIsLoaderTaskRunning
            val bindAllCallbacks = wasRunning || !bindDirectly || newCallbacks.isEmpty()
            val callbacksList = if (bindAllCallbacks) callbacks else newCallbacks
            if (allowEmptyCallbacks || callbacksList.isNotEmpty()) {
                val organizerToken = mOrganizerReloadToken
                val restoreToken = mRestoreReloadRequest
                // Issue #152 (rebase Phase 2 port): the snapshot is captured inside the
                // exact #150 terminal boundary via the binder completion hook, gated by
                // the token identity check in completeOrganizerReload.
                // Review (PR #535 round 2): the snapshot capture runs a full
                // workspace projection — only an exact organizer-requested
                // generation needs it, and the capture must stay on
                // MODEL_EXECUTOR (the binder completion hook always runs
                // there). Tokenless bind/restore generations skip the capture
                // entirely and complete their tokens without a snapshot.
                val needsSnapshot = organizerToken != null
                val launcherBinder = binderFactory.createBinder(
                    callbacksList,
                    Runnable {
                        val snapshot = if (needsSnapshot) {
                            try {
                                ModelProjectionCodec.captureModelSnapshot(mBgDataModel, context)
                            } catch (t: Throwable) {
                                null
                            }
                        } else {
                            null
                        }
                        completeRestoreReload(restoreToken)
                        completeOrganizerReload(organizerToken, if (organizerToken != null) snapshot else null)
                    },
                )
                if (bindDirectly) {
                    // Divide the set of loaded items into those that we are binding synchronously,
                    // and everything else that is to be bound normally (asynchronously).
                    launcherBinder.bindWorkspace(bindAllCallbacks, /* isBindSync= */ true)
                    // For now, continue posting the binding of AllApps as there are other
                    // issues that arise from that.
                    launcherBinder.bindAllApps()
                    launcherBinder.bindDeepShortcuts()
                    launcherBinder.bindWidgets()
                    // The direct-bind generation owns the terminal signal too.
                    launcherBinder.notifyOrganizerReloadComplete()
                    return true
                } else {
                    val notifyRestoreReloadComplete = restoreToken != null
                    if (restoreToken != null) {
                        restoreToken.loaderStarted = true
                    }
                    if (organizerToken != null) {
                        // Issue #376: the async generation owns the terminal signal even
                        // with an empty callback list.
                        organizerToken.loaderStarted = true
                    }
                    val task = loaderFactory.newLoaderTask(
                        launcherBinder,
                        UserManagerState(),
                        organizerToken?.organizerLeaseToken ?: 0L,
                        notifyRestoreReloadComplete,
                    )
                    mLoaderTask = task

                    // Always post the loader task, instead of running directly
                    // (even on same thread) so that we exit any nested synchronized blocks
                    MODEL_EXECUTOR.post(task)
                }
            }
        }
        return false
    }

    // Issue #14: package-private bridge for correlated reload (fork contract port).
    // Sets a per-model organizer reload token that the exact loader task's
    // transaction-complete path signals; supersession/cancellation unsets it.
    // Issue #152: the completion carries the model snapshot captured at the
    // #150 terminal boundary.
    fun forceReloadForOrganizer(requestId: Long, organizerLeaseToken: Long,
            completed: Consumer<ModelSnapshot?>, cancelled: Runnable) {
        val token = OrganizerReloadRequest(requestId, organizerLeaseToken, completed, cancelled)
        val superseded: OrganizerReloadRequest?
        synchronized(mLock) {
            stopLoader()
            superseded = mOrganizerReloadToken
            mOrganizerReloadToken = token
            mModelLoaded = false
        }
        // Issue #150: terminalize a token displaced by this registration exactly once.
        superseded?.cancelled?.run()
        MAIN_EXECUTOR.execute {
            var neverStarted = false
            synchronized(mLock) {
                if (mOrganizerReloadToken !== token) return@execute
                if (hasCallbacks()) {
                    startLoader()
                } else {
                    // Issue #376: a settings-only cold process holds a model loaded
                    // without bound callbacks; the tokenless loader still completes
                    // the organizer token at the binder boundary.
                    startLoaderWithoutCallbacks()
                }
                neverStarted = mOrganizerReloadToken === token && !token.loaderStarted
                if (neverStarted) {
                    mOrganizerReloadToken = null
                }
            }
            // Issue #299 symmetry: the terminal callback runs outside mLock.
            if (neverStarted) {
                token.cancelled.run()
            }
        }
    }

    // Issue #14: only the token captured by the exact loader binder completes the request.
    // Issue #152: the token identity check gates snapshot delivery.
    private fun completeOrganizerReload(token: OrganizerReloadRequest?, snapshot: ModelSnapshot?) {
        if (token == null) return
        synchronized(mLock) {
            if (mOrganizerReloadToken !== token) return
            mOrganizerReloadToken = null
        }
        token.completed.accept(snapshot)
    }

    private fun cancelOrganizerReload() {
        val token: OrganizerReloadRequest?
        synchronized(mLock) {
            token = mOrganizerReloadToken
            mOrganizerReloadToken = null
        }
        token?.cancelled?.run()
    }

    /**
     * Issue #376: terminalize the pending organizer reload if (and only if) it is
     * still this request's, so a caller that gave up waiting leaves no stale token.
     */
    fun cancelOrganizerReloadIfCurrent(requestId: Long) {
        val token: OrganizerReloadRequest?
        synchronized(mLock) {
            token = mOrganizerReloadToken
            if (token == null || token.requestId != requestId) return
            mOrganizerReloadToken = null
        }
        token!!.cancelled.run()
    }

    private class OrganizerReloadRequest(
        val requestId: Long,
        val organizerLeaseToken: Long,
        val completed: Consumer<ModelSnapshot?>,
        val cancelled: Runnable,
    ) {
        // Issue #376: set by startLoader when the request's async loader generation
        // is created — startLoader's boolean return only reports the direct-bind case.
        var loaderStarted = false
    }

    // Issue #299: restore-path reload completion barrier. Unlike the organizer token
    // above this rides the tokenless reload (leaseToken 0), so the loader's repair
    // sanitize keeps running; the token only adds generation identity and a terminal
    // outcome so the restore can await ITS generation's successful completion.
    private var mRestoreReloadRequest: RestoreReloadRequest? = null
    private var mRestoreReloadRequestId = 0L

    /** Returns a fresh request id for [dispatchRestoreReload]. */
    fun beginRestoreReload(): Long {
        synchronized(mLock) {
            return ++mRestoreReloadRequestId
        }
    }

    /**
     * Issue #299: dispatches the tokenless (sanitize-carrying) reload and observes
     * its terminal outcome with generation identity. Exactly one of completed /
     * cancelled runs, on an arbitrary thread, after the matching generation
     * committed or was superseded/stopped.
     */
    fun dispatchRestoreReload(requestId: Long, completed: Runnable, cancelled: Runnable) {
        val token = RestoreReloadRequest(requestId, completed, cancelled)
        val superseded: RestoreReloadRequest?
        synchronized(mLock) {
            stopLoader()
            superseded = mRestoreReloadRequest
            mRestoreReloadRequest = token
            mModelLoaded = false
        }
        superseded?.cancelled?.run()
        // Issue #299: startLoaderWithoutCallbacks has a UI-thread precondition.
        MAIN_EXECUTOR.execute {
            var neverStarted = false
            synchronized(mLock) {
                if (mRestoreReloadRequest !== token) return@execute
                if (hasCallbacks()) {
                    startLoader()
                } else {
                    startLoaderWithoutCallbacks()
                }
                if (mRestoreReloadRequest === token && !token.loaderStarted) {
                    mRestoreReloadRequest = null
                    neverStarted = true
                }
            }
            if (neverStarted) token.cancelled.run()
        }
    }

    private fun completeRestoreReload(token: RestoreReloadRequest?) {
        if (token == null) return
        synchronized(mLock) {
            if (mRestoreReloadRequest !== token) return
            mRestoreReloadRequest = null
        }
        token.completed.run()
    }

    private fun cancelRestoreReload() {
        val token: RestoreReloadRequest?
        synchronized(mLock) {
            token = mRestoreReloadRequest
            mRestoreReloadRequest = null
        }
        token?.cancelled?.run()
    }

    fun cancelRestoreReloadIfCurrent(requestId: Long) {
        val token: RestoreReloadRequest?
        synchronized(mLock) {
            token = mRestoreReloadRequest
            if (token == null || token.requestId != requestId) return
            mRestoreReloadRequest = null
        }
        token!!.cancelled.run()
    }

    private class RestoreReloadRequest(
        val requestId: Long,
        val completed: Runnable,
        val cancelled: Runnable,
    ) {
        // Guarded by LauncherModel.mLock. Must not be inferred from mLoaderTask.
        var loaderStarted = false
    }

    /**
     * If there is already a loader task running, tell it to stop.
     *
     * @return true if an existing loader was stopped.
     */
    private fun stopLoader(): Boolean {
        synchronized(mLock) {
            val oldTask: LoaderTask? = mLoaderTask
            mLoaderTask = null
            if (oldTask != null) {
                oldTask.stopLocked()
                cancelOrganizerReload()
                cancelRestoreReload()
                return true
            }
            return false
        }
    }

    /**
     * Checks whether the launcher model is active.
     *
     * @return true if the model is loaded or if loader task is running.
     */
    fun isActive(): Boolean = mModelLoaded || mIsLoaderTaskRunning

    /**
     * Loads the model if not loaded
     *
     * @param callback called with the data model upon successful load or null on model thread.
     */
    fun loadAsync(callback: Consumer<BgDataModel?>) {
        synchronized(mLock) {
            if (!mModelLoaded && !mIsLoaderTaskRunning) {
                startLoader()
            }
        }
        MODEL_EXECUTOR.post { callback.accept(if (isModelLoaded()) mBgDataModel else null) }
    }

    inner class LoaderTransaction(task: LoaderTask) : AutoCloseable {
        private var mTask: LoaderTask? = null

        init {
            synchronized(mLock) {
                if (mLoaderTask !== task) {
                    throw CancellationException("Loader already stopped")
                }
                this@LauncherModel.lastLoadId++
                mTask = task
                mIsLoaderTaskRunning = true
                mModelLoaded = false
            }
        }

        fun commit() {
            synchronized(mLock) {
                // Everything loaded bind the data.
                mModelLoaded = true
            }
        }

        override fun close() {
            synchronized(mLock) {
                // If we are still the last one to be scheduled, remove ourselves.
                if (mLoaderTask === mTask) {
                    mLoaderTask = null
                }
                mIsLoaderTaskRunning = false
            }
        }
    }

    @Throws(CancellationException::class)
    fun beginLoader(task: LoaderTask) = LoaderTransaction(task)

    /**
     * Refreshes the cached shortcuts if the shortcut permission has changed. Current implementation
     * simply reloads the workspace, but it can be optimized to use partial updates similar to
     * [UserCache]
     */
    fun validateModelDataOnResume() {
        MODEL_EXECUTOR.handler.removeCallbacks(mDataValidationCheck)
        MODEL_EXECUTOR.post(mDataValidationCheck)
    }

    /** Called when the icons for packages have been updated in the icon cache. */
    fun onPackageIconsUpdated(updatedPackages: HashSet<String?>, user: UserHandle) {
        // If any package icon has changed (app was updated while launcher was dead),
        // update the corresponding shortcuts.
        enqueueModelUpdateTask(
            CacheDataUpdatedTask(CacheDataUpdatedTask.OP_CACHE_UPDATE, user, updatedPackages)
        )
    }

    /** Called when the labels for the widgets has updated in the icon cache. */
    fun onWidgetLabelsUpdated(updatedPackages: HashSet<String?>, user: UserHandle) {
        enqueueModelUpdateTask { taskController, dataModel, _ ->
            dataModel.widgetsModel.onPackageIconsUpdated(updatedPackages, user)
            taskController.bindUpdatedWidgets(dataModel)
        }
    }

    fun enqueueModelUpdateTask(task: ModelUpdateTask) {
        if (mModelDestroyed) {
            return
        }
        MODEL_EXECUTOR.execute {
            if (!isModelLoaded()) {
                // Loader has not yet run.
                return@execute
            }
            task.execute(taskControllerProvider.get(), mBgDataModel, mBgAllAppsList)
        }
    }

    /**
     * A task to be executed on the current callbacks on the UI thread. If there is no current
     * callbacks, the task is ignored.
     */
    fun interface CallbackTask {
        fun execute(callbacks: BgDataModel.Callbacks)
    }

    fun interface ModelUpdateTask {
        fun execute(taskController: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList)
    }

    fun updateAndBindWorkspaceItem(si: WorkspaceItemInfo, info: ShortcutInfo) {
        enqueueModelUpdateTask { taskController, _, _ ->
            si.updateFromDeepShortcutInfo(info, context)
            iconCache.getShortcutIcon(si, info)
            taskController.getModelWriter().updateItemInDatabase(si)
            taskController.bindUpdatedWorkspaceItems(listOf(si))
        }
    }

    fun refreshAndBindWidgetsAndShortcuts(packageUser: PackageUserKey?) {
        enqueueModelUpdateTask { taskController, dataModel, _ ->
            dataModel.widgetsModel.update(packageUser)
            taskController.bindUpdatedWidgets(dataModel)
        }
    }

    override fun dump(prefix: String, writer: PrintWriter, args: Array<String>?) {
        if (args?.getOrNull(0) == "--all") {
            writer.println(prefix + "All apps list: size=" + mBgAllAppsList.data.size)
            for (info in mBgAllAppsList.data) {
                writer.println(
                    "$prefix   title=\"${info.title}\" bitmapIcon=${info.bitmap.icon} componentName=${info.targetPackage}"
                )
            }
            writer.println()
        }
    }

    /** Returns true if there are any callbacks attached to the model */
    fun hasCallbacks() = synchronized(mCallbacksList) { mCallbacksList.isNotEmpty() }

    /** Returns an array of currently attached callbacks */
    val callbacks: Array<BgDataModel.Callbacks>
        get() {
            synchronized(mCallbacksList) {
                return mCallbacksList.toTypedArray<BgDataModel.Callbacks>()
            }
        }

    companion object {
        const val TAG = "Launcher.Model"
    }
}
