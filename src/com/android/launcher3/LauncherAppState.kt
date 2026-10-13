/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.widget.Toast
import app.lawnchair.LawnchairApp
import app.lawnchair.icons.LawnchairIconProvider
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.icons.IconCache
import com.android.launcher3.util.DaggerSingletonObject
import javax.inject.Inject
import javax.inject.Named

/** A collection of common dependencies used across Launcher */
@Deprecated("Inject the specific targets directly instead of using LauncherAppState")
data class LauncherAppState
@Inject
constructor(
    @ApplicationContext val context: Context,
    val iconProvider: LawnchairIconProvider,
    val iconCache: IconCache,
    val model: LauncherModel,
    val invariantDeviceProfile: InvariantDeviceProfile,
    @Named("SAFE_MODE") val isSafeModeEnabled: Boolean,
) {

    init {
        // LC bridge (S2c of #532, replaces the old MainThreadInitializedObject
        // onPostInit override, Issue #14): run the fork's one-time startup hook
        // after this instance is fully constructed. At this point every @Inject
        // dependency (including the model) is already built, and the component
        // build that produced this instance has completed for this binding, so
        // the hook must NOT call LauncherAppState.getInstance() re-entrantly;
        // LawnchairApp.onLauncherAppStateCreated() only composes fork-owned
        // objects from `this`. Preview/sandbox processes use a different
        // Application (and usually a separate component) and never construct
        // the production graph via LauncherAppState, so the hook stays confined
        // to the main launcher process.
        (context.applicationContext as? LawnchairApp)?.onLauncherAppStateCreated(this)
    }

    companion object {

        @JvmField var INSTANCE = DaggerSingletonObject { it.launcherAppState }

        @JvmStatic fun getInstance(context: Context) = INSTANCE[context]

        /** Shorthand for [.getInvariantDeviceProfile] */
        @JvmStatic fun getIDP(context: Context) = InvariantDeviceProfile.INSTANCE[context]

        /** LC bridge: returns the already-created instance without forcing construction. */
        @JvmStatic fun getInstanceNoCreate(context: Context): LauncherAppState? =
            INSTANCE.getNoCreate()
    }
}

/**
 * LC bridge (S2c of #532): icon-reload entry points kept on LauncherAppState for
 * fork callers (preferences/icons/predictions). Equivalent to the old
 * `refreshAndReloadLauncher`: drop the icon pool, refresh icon cache parameters
 * for the current grid, then force a model reload.
 */
fun LauncherAppState.reloadIcons() {
    com.android.launcher3.icons.LauncherIcons.clearPool(context.applicationContext)
    iconCache.updateIconParams(
        invariantDeviceProfile.fillResIconDpi,
        invariantDeviceProfile.iconBitmapSize,
    )
    model.forceReload()
}

