import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import dalvik.system.DexClassLoader;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic probe of the real callback Stubs without a production test export. */
public final class RecentsParcelProbe {
    private static final String RUNNER = "com.android.wm.shell.recents.IRecentsAnimationRunner";
    private static final String HOME = "com.android.wm.shell.shared.IHomeTransitionListener";
    private static int failures;
    private static final class DrainFinished extends RuntimeException {}
    private static void drain() {
        new Handler(Looper.getMainLooper()).post(() -> { throw new DrainFinished(); });
        try { Looper.loop(); } catch (DrainFinished expected) {}
    }
    private static void probe(String label, IBinder binder, int code, Parcel data,
            boolean shouldReject, AtomicInteger calls, int expectedDelta) throws Exception {
        int before = calls.get();
        boolean rejected = false;
        try { binder.transact(code, data, null, IBinder.FLAG_ONEWAY); }
        catch (RuntimeException e) { rejected = true; System.out.println(label + " rejected: " + e); }
        finally { data.recycle(); }
        drain();
        boolean passed = rejected == shouldReject && calls.get() - before == expectedDelta;
        System.out.println(label + ": " + (passed ? "PASS" : "FAIL") + " calls=" + (calls.get()-before));
        if (!passed) failures++;
    }
    private static Parcel home(String token, int fields) {
        Parcel p = Parcel.obtain(); p.writeInterfaceToken(token);
        for (int i=0; i<fields; i++) p.writeBoolean(false);
        return p;
    }
    private static Parcel recents(String token, boolean includeInfo, boolean extra) {
        Parcel p = Parcel.obtain(); p.writeInterfaceToken(token);
        p.writeStrongBinder(new Binder());
        p.writeTypedArray((android.os.Parcelable[])null, 0);
        p.writeTypedArray((android.os.Parcelable[])null, 0);
        p.writeTypedObject(null, 0);
        Bundle extras = new Bundle(); extras.putInt("probe", 554);
        p.writeTypedObject(extras, 0);
        if (includeInfo) p.writeTypedObject(null, 0);
        if (extra) p.writeInt(123);
        return p;
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        ClassLoader loader = new DexClassLoader(args[0], args[1], null,
                RecentsParcelProbe.class.getClassLoader());
        AtomicInteger starts = new AtomicInteger();
        Class<?> listenerType = loader.loadClass("com.android.systemui.shared.system.RecentsAnimationListener");
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (obj, method, values) -> {
            if (method.getName().equals("onAnimationStart")) {
                if (values[4] != null || ((Bundle) values[5]).getInt("probe") != 554)
                    throw new AssertionError("Listener normalization lost minimized bounds/extras");
                starts.incrementAndGet();
            }
            return null;
        });
        Class<?> stub = loader.loadClass("com.android.quickstep.SystemUiProxy$RecentsAnimationListenerStub");
        Constructor<?> ctor = stub.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        IBinder runner = (IBinder) ctor.newInstance(listener);
        probe("runner-valid", runner, 3, recents(RUNNER, true, false), false, starts, 1);
        probe("runner-missing-info-marker", runner, 3, recents(RUNNER, false, false), true, starts, 0);
        probe("runner-wrong-descriptor", runner, 3, recents("wrong", true, false), true, starts, 0);
        probe("runner-extra-tail", runner, 3, recents(RUNNER, true, true), true, starts, 0);
        AtomicInteger visibility = new AtomicInteger();
        Class<?> homeType = loader.loadClass("com.android.quickstep.HomeVisibilityState");
        Object state = homeType.getDeclaredConstructor().newInstance();
        Class<?> visibilityType = loader.loadClass("com.android.quickstep.HomeVisibilityState$VisibilityChangeListener");
        Object changeListener = Proxy.newProxyInstance(loader, new Class<?>[]{visibilityType}, (obj, method, values) -> {
            if (method.getName().equals("onHomeVisibilityChanged")) visibility.incrementAndGet();
            if (method.getName().equals("hashCode")) return 554;
            if (method.getName().equals("equals")) return obj == values[0];
            return null;
        });
        homeType.getMethod("addListener", visibilityType).invoke(state, changeListener);
        Class<?> transitionsType = loader.loadClass("com.android.wm.shell.shared.IShellTransitions");
        IBinder[] home = new IBinder[1];
        Object transitions = Proxy.newProxyInstance(loader, new Class<?>[]{transitionsType}, (obj, method, values) -> {
            if (method.getName().equals("setHomeTransitionListener")) home[0] = (IBinder) values[0];
            return null;
        });
        homeType.getMethod("init", transitionsType).invoke(state, transitions);
        if (home[0] == null) throw new AssertionError("Home listener was not registered");
        probe("home-valid", home[0], 1, home(HOME, 3), false, visibility, 1);
        probe("home-missing-tail", home[0], 1, home(HOME, 1), true, visibility, 0);
        probe("home-wrong-descriptor", home[0], 1, home("wrong", 3), true, visibility, 0);
        probe("home-extra-tail", home[0], 1, home(HOME, 4), true, visibility, 0);
        System.out.println("RESULT " + (failures == 0 ? "PASS" : "FAIL") + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
