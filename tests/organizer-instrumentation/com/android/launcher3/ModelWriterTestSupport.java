// Issue #532 G2 (rebase): the anchor model (ADR-0018 Decision 9) is a final,
// Dagger-injected LauncherModel, so the pre-rebase TestLauncherModel subclass
// (which routed ModelWriter to an isolated ModelDbController by overriding
// getModelDbController) no longer compiles. The anchor's injection seam serves
// the same purpose: a real LauncherModel is built here with the caller's
// test-scoped ModelDbController and BgDataModel injected through the
// constructor, keeping the writer-routed isolation and failure injection the
// direct-edit oracles assert on, without overriding any anchor API.
package com.android.launcher3;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.dagger.LauncherAppComponent;
import com.android.launcher3.dagger.LauncherComponentProvider;
import com.android.launcher3.model.AllAppsList;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.ModelDelegate;
import com.android.launcher3.model.ModelInitializer;
import com.android.launcher3.model.WidgetsModel;

import java.util.concurrent.atomic.AtomicReference;

import app.lawnchair.icons.LawnchairIconProvider;

/** Builds real anchor model objects wired to test-scoped seams. */
public final class ModelWriterTestSupport {

    private ModelWriterTestSupport() { }

    /** Returns LauncherAppState, which asserts UI-thread construction. */
    public static LauncherAppState obtainAppStateOnMain(Context context) {
        final AtomicReference<LauncherAppState> appRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> appRef.set(LauncherAppState.getInstance(context)));
        return appRef.get();
    }

    /**
     * Returns a live BgDataModel the caller can assert on, built through the
     * anchor constructor (the fork's no-arg constructor is gone).
     */
    public static BgDataModel createBgDataModel(Context context) {
        LauncherAppComponent component = LauncherComponentProvider.get(context);
        return new BgDataModel(
                new WidgetsModel(context),
                () -> null,
                component.getDumpManager(),
                component.getDaggerSingletonTracker());
    }

    /**
     * Returns a real LauncherModel whose {@code modelDbController} is the
     * caller's test controller, so every ModelWriter DB operation routes to
     * the isolated test database (and its injected failures).
     *
     * <p>The icons db name is {@code null}, which keeps the anchor model from
     * registering the app-wide model callbacks; the model is never loaded, so
     * the loader/binder factories below are never invoked.</p>
     */
    public static LauncherModel createIsolatedModel(
            Context context, ModelDbController controller, BgDataModel bgDataModel) {
        LauncherAppComponent component = LauncherComponentProvider.get(context);
        LauncherAppState app = obtainAppStateOnMain(context);
        return new LauncherModel(
                context,
                () -> null,
                app.getIconCache(),
                LauncherPrefs.get(context),
                component.getItemInstallQueue(),
                /* dbFileName= */ null,
                new ModelInitializer(
                        context,
                        component.getIconPool(),
                        app.getIconCache(),
                        LauncherAppState.getIDP(context),
                        component.getThemeManager(),
                        component.getUserCache(),
                        component.getSettingsCache(),
                        new LawnchairIconProvider(context, false),
                        component.getCustomWidgetManager(),
                        component.getInstallSessionHelper(),
                        component.getDaggerSingletonTracker()),
                component.getDaggerSingletonTracker(),
                new ModelDelegate(context),
                new AllAppsList(
                        app.getIconCache(),
                        new AppFilter(context),
                        () -> null),
                bgDataModel,
                (binder, userState, leaseToken, notifyRestoreReloadComplete) -> null,
                (callbacks, organizerReloadComplete) -> null,
                controller,
                component.getDumpManager());
    }
}
