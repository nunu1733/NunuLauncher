package app.lawnchair.util;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.VisibleForTesting;

import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.TraceHelper;

import java.util.concurrent.ExecutionException;

/**
 * Utility class for defining singletons which are initiated on main thread.
 */
public class MainThreadInitializedObject<T> {

    private final ObjectProvider<T> mProvider;
    private T mValue;

    public MainThreadInitializedObject(ObjectProvider<T> provider) {
        mProvider = provider;
    }

    public T get(Context context) {
        if (mValue == null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mValue = TraceHelper.allowIpcs("main.thread.object",
                        () -> mProvider.get(context.getApplicationContext()));
                onPostInit(context);
            } else {
                try {
                    return MAIN_EXECUTOR.submit(() -> get(context)).get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return mValue;
    }

    protected void onPostInit(Context context) {
    }

    public T getNoCreate() {
        return mValue;
    }

    @VisibleForTesting
    public void initializeForTesting(T value) {
        mValue = value;
    }

    public interface ObjectProvider<T> {

        T get(Context context);
    }

}
