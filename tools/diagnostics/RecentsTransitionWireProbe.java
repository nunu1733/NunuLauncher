import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Issue #559 manual ART diagnostic: calls the production consumer, never a cloned writer. */
public final class RecentsTransitionWireProbe {
    private static final String EXACT =
            "google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys";
    private static final String TASKS = "com.android.wm.shell.recents.IRecentTasks";
    private static int failures;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Capture extends Binder {
        int calls;
        int code;
        int flags;
        Parcel payload;

        @Override
        protected boolean onTransact(int transaction, Parcel data, Parcel reply, int mode) {
            calls++;
            code = transaction;
            flags = mode;
            payload = Parcel.obtain();
            payload.appendFrom(data, 0, data.dataSize());
            payload.setDataPosition(0);
            return true;
        }
    }

    private static void run(String[] args, Context context) throws Exception {
        int sdk = Integer.parseInt(args[2]);
        String label = args[3];
        boolean initial = label.equals("exact") || label.equals("BP2A")
                || label.equals("BD1A") || label.equals("Nothing");
        String fingerprint = label.equals("BP2A") || label.equals("Nothing")
                ? "probe/BP2A.000" : label.equals("BD1A") ? "probe/BD1A.000"
                : label.equals("unknown") ? EXACT.replace("13894323", "13894324") : EXACT;
        initial &= sdk == 36;
        int expectedCode = initial && (label.equals("BP2A") || label.equals("BD1A")) ? 5 : 6;
        Field sdkField = field(Build.VERSION.class, "SDK_INT");
        Field fingerprintField = field(Build.class, "FINGERPRINT");
        int originalSdk = sdkField.getInt(null);
        String originalFingerprint = (String) fingerprintField.get(null);
        try {
            // Process-scoped overrides; never change device properties. Fresh loader resets caches.
            sdkField.setInt(null, sdk);
            fingerprintField.set(null, fingerprint);
            ClassLoader loader = new DexClassLoader(args[0], args[1], null,
                    RecentsTransitionWireProbe.class.getClassLoader());
            Class<?> compatibility = loader.loadClass("app.lawnchair.util.CompatibilityKt");
            Field nothing = field(compatibility, "isNothingOs");
            boolean originalNothing = nothing.getBoolean(null);
            try {
                nothing.setBoolean(null, label.equals("Nothing"));
                Class<?> owner = loader.loadClass("com.android.quickstep.SystemUiProxy");
                Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
                Object unsafe = field(unsafeType, "theUnsafe").get(null);
                Object consumer = unsafeType.getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, owner);
                field(owner, "context").set(consumer, context);
                Capture capture = new Capture();
                Object recentTasks = loader.loadClass(TASKS + "$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, capture);
                field(owner, "recentTasks").set(consumer, recentTasks);
                Class<?> listenerType = loader.loadClass(
                        "com.android.systemui.shared.system.RecentsAnimationListener");
                Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType},
                        (object, method, values) -> null);
                Class<?> wctType = Class.forName("android.window.WindowContainerTransaction");
                Method start = owner.getMethod("startRecentsActivity", Intent.class,
                        ActivityOptions.class, listenerType, boolean.class, wctType, int.class);
                Intent intent = new Intent("probe.intent.559").setPackage("com.android.settings")
                        .putExtra("probe-input", 559);
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(0);
                PendingIntent expectedPending = PendingIntent.getActivity(context, 0,
                        new Intent().setPackage(context.getPackageName()),
                        PendingIntent.FLAG_MUTABLE | 0x01000000 | Intent.FILL_IN_COMPONENT,
                        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
                IBinder caller = ((IInterface) Context.class.getMethod("getIApplicationThread")
                        .invoke(context)).asBinder();

                // Given the real consumer and remote-style Binder; When starting recents;
                // Then consume the exact server schema, checking identity and no unread tail.
                boolean result = (Boolean) start.invoke(consumer, intent, options, listener,
                        true, null, 0);
                try {
                    check(result && capture.calls == 1, "expected one successful transaction");
                    check(capture.code == expectedCode, "transaction expected=" + expectedCode
                            + " actual=" + capture.code);
                    check(capture.flags == IBinder.FLAG_ONEWAY, "FLAG_ONEWAY missing");
                    Parcel data = capture.payload;
                    data.enforceInterface(TASKS);
                    check(expectedPending.equals(data.readTypedObject(PendingIntent.CREATOR)),
                            "PendingIntent identity/order");
                    Intent actual = data.readTypedObject(Intent.CREATOR);
                    check(intent.filterEquals(actual) && actual.getIntExtra("probe-input", 0) == 559,
                            "Intent identity/order");
                    Bundle bundle = data.readTypedObject(Bundle.CREATOR);
                    check(bundle.getBoolean("is_synthetic_recents_transition")
                            && bundle.getInt("android.activity.launchDisplayId", -1) == 0,
                            "Bundle content/order");
                    if (!initial) {
                        check(data.readInt() == 0, "modern null WCT marker missing");
                    }
                    check(data.readStrongBinder() == caller,
                            "caller binder identity/order (unexpected WCT marker on five-arg wire)");
                    IBinder runner = data.readStrongBinder();
                    check(runner != null && field(runner.getClass(), "listener").get(runner) == listener,
                            "runner binder listener identity/order");
                    data.enforceNoDataAvail();
                    System.out.println(label + "/" + sdk + " wire PASS tx=" + capture.code
                            + " args=" + (initial ? 5 : 6) + " oneway descriptor identities tail=0");
                } finally {
                    if (capture.payload != null) capture.payload.recycle();
                    capture.payload = null;
                }

                // Given non-null WCT; When invoking the same entry; Then initial is zero transact.
                capture.calls = 0;
                Object wct = wctType.getConstructor().newInstance();
                boolean wctResult = (Boolean) start.invoke(consumer, intent, options, listener,
                        false, wct, 0);
                try {
                    if (initial) {
                        check(!wctResult && capture.calls == 0, "non-null WCT must reject before transact");
                    } else {
                        check(wctResult && capture.calls == 1, "modern WCT route changed");
                        Parcel data = capture.payload;
                        data.enforceInterface(TASKS);
                        data.readTypedObject(PendingIntent.CREATOR);
                        data.readTypedObject(Intent.CREATOR);
                        check(!data.readTypedObject(Bundle.CREATOR)
                                .getBoolean("is_synthetic_recents_transition"), "synthetic=false lost");
                        Parcelable.Creator<?> creator = (Parcelable.Creator<?>) wctType.getField("CREATOR").get(null);
                        check(data.readTypedObject(creator) != null, "modern non-null WCT lost");
                        check(data.readStrongBinder() == caller, "modern caller displaced");
                        check(data.readStrongBinder() != null, "modern runner missing");
                        data.enforceNoDataAvail();
                    }
                    System.out.println(label + "/" + sdk + " WCT PASS transactions=" + capture.calls);
                } finally {
                    if (capture.payload != null) capture.payload.recycle();
                }
            } finally {
                nothing.setBoolean(null, originalNothing);
            }
        } finally {
            sdkField.setInt(null, originalSdk);
            fingerprintField.set(null, originalFingerprint);
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        try {
            run(args, context);
        } catch (AssertionError error) {
            failures++;
            System.out.println("ASSERTION FAIL " + error.getMessage());
        }
        System.out.println("RESULT " + (failures == 0 ? "PASS" : "FAIL"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
