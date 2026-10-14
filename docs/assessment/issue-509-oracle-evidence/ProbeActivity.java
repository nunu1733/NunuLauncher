package app.lawnchair.probe.sessionbadge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Issue #509 re-evaluation probe (temporary artifact, not committed).
 *
 * Creates a streaming install session that carries badging (app icon + label),
 * impersonating a trusted installer (com.android.vending), with install reason USER.
 * If the launcher's promise path gates on badging + trusted installer, this session
 * should trigger a promise icon enqueue on a real third-party launcher.
 *
 * UI buttons are replaced by broadcast/debug flow: launching the activity performs
 * all steps and logs the outcome. Target APK must be pushed to app files dir first.
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "SessionBadgeProbe";
    static final String TARGET_APK = "target.apk";
    static final String TARGET_PKG = "app.lawnchair.benchmark.target01";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("SessionBadgeProbe\nwaiting…");
        setContentView(tv);
        log("activity started");

        // Session callback registration via PackageInstaller (validates event delivery
        // to a third-party data app; the launcher registers via LauncherApps).
        try {
            PackageInstaller pi0 = getPackageManager().getPackageInstaller();
            pi0.registerSessionCallback(new PackageInstaller.SessionCallback() {
                @Override public void onCreated(int sessionId) { Log.i(TAG, "cb onCreated " + sessionId); }
                @Override public void onBadgingChanged(int sessionId) { Log.i(TAG, "cb onBadgingChanged " + sessionId); }
                @Override public void onActiveChanged(int sessionId, boolean active) { }
                @Override public void onProgressChanged(int sessionId, float progress) { }
                @Override public void onFinished(int sessionId, boolean success) { Log.i(TAG, "cb onFinished " + sessionId + " success=" + success); }
            }, new Handler(Looper.getMainLooper()));
            log("session callback registered");
        } catch (Throwable t) {
            log("callback registration failed: " + t);
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::runSession, 1500);
    }

    private void runSession() {
        try {
            byte[] apkBytes = readApk();
            log("apk loaded, bytes=" + apkBytes.length);

            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setInstallReason(4); // INSTALL_REASON_USER = 4
            params.setInstallerPackageName("com.android.vending");
            params.setAppLabel("Benchmark Target 01");
            params.setAppPackageName(TARGET_PKG);
            params.setAppIcon(makeIcon());
            params.setSize(apkBytes.length);

            int sessionId = pi.createSession(params);
            log("session created id=" + sessionId + " with badging (icon+label), installer=com.android.vending, reason=4");

            PackageInstaller.Session session = pi.openSession(sessionId);
            try (OutputStream out = session.openWrite("base", 0, apkBytes.length)) {
                out.write(apkBytes);
                session.fsync(out);
            }
            session.commit(createIntentSender(sessionId));
            log("session committed");
        } catch (Throwable t) {
            Log.e(TAG, "session failed", t);
            log("session FAILED: " + t);
        }
    }

    private android.content.IntentSender createIntentSender(int sessionId) throws IOException {
        Intent intent = new Intent("app.lawnchair.probe.sessionbadge.COMMIT_RESULT")
                .setPackage(getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, sessionId,
                intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return pi.getIntentSender();
    }

    private Bitmap makeIcon() {
        Bitmap bmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.rgb(0x33, 0x99, 0xCC));
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(32);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("T1", 54, 68, p);
        return bmp;
    }

    private byte[] readApk() throws IOException {
        try (InputStream in = openFileInput(TARGET_APK);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
    }

    /** Unused placeholder removed. */
    public static class CommitReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : "?";
            Log.i(TAG, "PACKAGE_ADDED: " + pkg + " replacing=" + intent.getBooleanExtra(Intent.EXTRA_REPLACING, false));
        }
    }
}
