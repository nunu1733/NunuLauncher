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

package com.android.systemui.shared.system;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;

import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.WindowManagerGlobal;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Manages the input consumer that allows the SystemUI to directly receive input.
 * TODO: Refactor this for the gesture nav case
 */
public class InputConsumerController {

    private static final String TAG = InputConsumerController.class.getSimpleName();

    // API 37 (Build.VERSION_CODES.CINNAMON_BUN) is absent from the framework-16.jar compile
    // classpath, so the gate uses the literal (see #545).
    private static final int API_37 = 37;

    /**
     * Listener interface for callers to subscribe to input events.
     */
    public interface InputListener {
        /** Handles any input event. */
        boolean onInputEvent(InputEvent ev);
    }

    /**
     * Listener interface for callers to learn when this class is registered or unregistered with
     * window manager
     */
    public interface RegistrationListener {
        void onRegistrationChanged(boolean isRegistered);
    }

    /**
     * Input handler used for the input consumer. Input events are batched and consumed with the
     * SurfaceFlinger vsync.
     */
    private final class InputEventReceiver extends BatchedInputEventReceiver {

        InputEventReceiver(InputChannel inputChannel, Looper looper,
                Choreographer choreographer) {
            super(inputChannel, looper, choreographer);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = true;
            try {
                if (mListener != null) {
                    handled = mListener.onInputEvent(event);
                }
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    private final IWindowManager mWindowManager;
    private final IBinder mToken;
    private final String mName;

    private InputEventReceiver mInputEventReceiver;
    private InputListener mListener;
    private RegistrationListener mRegistrationListener;

    /**
     * @param name the name corresponding to the input consumer that is defined in the system.
     */
    public InputConsumerController(IWindowManager windowManager, String name) {
        mWindowManager = windowManager;
        mToken = new Binder();
        mName = name;
    }

    /**
     * @return A controller for the recents animation input consumer.
     */
    public static InputConsumerController getRecentsAnimationInputConsumer() {
        return new InputConsumerController(WindowManagerGlobal.getWindowManagerService(),
                INPUT_CONSUMER_RECENTS_ANIMATION);
    }

    /**
     * Sets the input listener.
     */
    public void setInputListener(InputListener listener) {
        mListener = listener;
    }

    /**
     * Sets the registration listener.
     */
    public void setRegistrationListener(RegistrationListener listener) {
        mRegistrationListener = listener;
        if (mRegistrationListener != null) {
            mRegistrationListener.onRegistrationChanged(mInputEventReceiver != null);
        }
    }

    /**
     * Check if the InputConsumer is currently registered with WindowManager
     *
     * @return {@code true} if registered, {@code false} if not.
     */
    public boolean isRegistered() {
        return mInputEventReceiver != null;
    }

    /**
     * Registers the input consumer.
     */
    public void registerInputConsumer() {
        if (mInputEventReceiver == null) {
            InputChannel inputChannel = null;
            try {
                mWindowManager.destroyInputConsumer(mToken, DEFAULT_DISPLAY);
                if (Build.VERSION.SDK_INT >= API_37) {
                    // #545: Android 17 (API 37) replaced IWindowManager.createInputConsumer in
                    // place — the compiled out-param form (IBinder, String, int, InputChannel)
                    // -> void was removed in favor of (IBinder, String, int) -> InputChannel.
                    // The compile classpath (framework-16.jar) only carries the API 36 form, so
                    // the API 37 form is invoked reflectively. A lookup failure degrades to an
                    // unregistered consumer instead of crashing the provider bind path.
                    inputChannel = createInputConsumerCompat(mToken, mName, DEFAULT_DISPLAY);
                } else {
                    inputChannel = new InputChannel();
                    mWindowManager.createInputConsumer(mToken, mName, DEFAULT_DISPLAY, inputChannel);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to create input consumer", e);
            }
            if (inputChannel == null) {
                Log.e(TAG, "Input consumer not registered (see #545)");
                return;
            }
            mInputEventReceiver = new InputEventReceiver(inputChannel, Looper.myLooper(),
                    Choreographer.getInstance());
            if (mRegistrationListener != null) {
                mRegistrationListener.onRegistrationChanged(true /* isRegistered */);
            }
        }
    }

    /**
     * Invokes the Android 17 (API 37) return-InputChannel form of
     * IWindowManager.createInputConsumer reflectively (see #545). Returns null when the lookup
     * or invocation fails, so the caller can degrade to an unregistered consumer without
     * crashing.
     */
    private InputChannel createInputConsumerCompat(IBinder token, String name, int displayId) {
        try {
            Class<?> iWindowManagerClass = Class.forName("android.view.IWindowManager");
            Method createInputConsumerMethod = iWindowManagerClass.getMethod(
                    "createInputConsumer", IBinder.class, String.class, int.class);
            return (InputChannel) createInputConsumerMethod.invoke(
                    mWindowManager, token, name, displayId);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException e) {
            Log.e(TAG, "Failed to invoke createInputConsumer (API 37 form, see #545)", e);
            return null;
        }
    }

    /**
     * Unregisters the input consumer.
     */
    public void unregisterInputConsumer() {
        if (mInputEventReceiver != null) {
            try {
                mWindowManager.destroyInputConsumer(mToken, DEFAULT_DISPLAY);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to destroy input consumer", e);
            }
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
            if (mRegistrationListener != null) {
                mRegistrationListener.onRegistrationChanged(false /* isRegistered */);
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "registered=" + (mInputEventReceiver != null));
    }
}
