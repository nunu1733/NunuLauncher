/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.launcher3.util;

import android.content.Context;

import androidx.annotation.Nullable;

import com.android.launcher3.dagger.LauncherAppComponent;
import com.android.launcher3.dagger.LauncherComponentProvider;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A class to provide DaggerSingleton objects in a traditional way.
 * We should delete this class at the end and use @Inject to get dagger provided singletons.
 *
 * LC bridge (S2c of #532, restore contract): fork callers
 * ({@link com.android.launcher3.LauncherProvider},
 * {@link com.android.launcher3.provider.RestoreDbTask}) need
 * "run only if already created" semantics that the old
 * MainThreadInitializedObject provided via {@code executeIfCreated} /
 * {@code getNoCreate}. The instance is tracked at publication time; if it is
 * not created yet, the callbacks are skipped (fail-closed) instead of forcing
 * a component build on the caller's thread.
 */
public class DaggerSingletonObject<T> {
    private final Function<LauncherAppComponent, T> mFunction;

    // Publication of a Dagger singleton is itself thread-safe; this only mirrors
    // the reference once it exists so callers can observe "created or not"
    // without triggering construction.
    private volatile T mInstance;

    public DaggerSingletonObject(Function<LauncherAppComponent, T> function) {
        mFunction = function;
    }

    public T get(Context context) {
        T instance = mFunction.apply(LauncherComponentProvider.get(context));
        mInstance = instance;
        return instance;
    }

    /**
     * Executes the callback if the value is already created.
     *
     * @return true if the callback was executed, false otherwise
     */
    public boolean executeIfCreated(Consumer<T> callback) {
        T v = mInstance;
        if (v != null) {
            callback.accept(v);
            return true;
        }
        return false;
    }

    /** Returns the already-created value, or null. Never triggers construction. */
    @Nullable
    public T getNoCreate() {
        return mInstance;
    }
}
