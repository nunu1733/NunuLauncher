package app.lawnchair.probe.sessionbadge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
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
 * Issue #509 re-evaluation probe v3 (E4b3 variant, reconstructed from the
 * installed APK — see probe-v3-installed-dexdump.txt; source file was edited
 * in-place between E4a and E4b3 and the v3 text was not separately saved).
 *
 * Differences from ProbeActivity.java (the E4a variant committed alongside):
 *  - setInstallerPackageName(getPackageName()) instead of "com.android.vending"
 *  - dynamic commit-status receiver ("COMMIT_RESULT") that reads
 *    EXTRA_STATUS and launches EXTRA_INTENT on STATUS_PENDING_USER_ACTION
 *  - createIntentSender uses PendingIntent FLAG_MUTABLE | FLAG_UPDATE_CURRENT
 *    (FLAG_IMMUTABLE drops EXTRA_STATUS on this API level)
 *  - log strings "installer=self, reason=4 (no spoof)",
 *    "commit status receiver registered", "commit status=", "commit STATUS_SUCCESS",
 *    "commit failure status=", "PENDING_USER_ACTION but EXTRA_INTENT null"
 *
 * Verified against the E4b3 logs: "session created id=... with badging (icon+label),
 * installer=self, reason=4 (no spoof)" and "commit status receiver registered".
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "SessionBadgeProbe";
    static final String TARGET_APK = "target.apk";
    static final String TARGET_PKG = "app.lawnchair.benchmark.target01";
    private static final String ACTION_COMMIT_RESULT = "app.lawnchair.probe.sessionbadge.COMMIT_RESULT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("SessionBadgeProbe\nwaiting…");
        setContentView(tv);
        log("activity started");

        try {
            PackageInstaller pi0 = getPackageManager().getPackageInstaller();
            pi0.registerSessionCallback(new PackageInstaller.SessionCallback() {
                @Override public void onCreated(int sessionId) { log("cb onCreated " + sessionId); }
                @Override public void onBadgingChanged(int sessionId) { }
                @Override public void onActiveChanged(int sessionId, boolean active) { }
                @Override public void onProgressChanged(int sessionId, float progress) { }
                @Override public void onFinished(int sessionId, boolean success) { log("cb onFinished " + sessionId + " success=" + success); }
            }, new Handler(Looper.getMainLooper()));
            log("session callback registered");
        } catch (Throwable t) {
            log("callback registration failed: " + t);
        }

        try {
            IntentFilter f = new IntentFilter(ACTION_COMMIT_RESULT);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int status = intent.getIntExtra("android.content.pm.extra.STATUS", -999);
                    log("commit status=" + status);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        try {
                            Intent confirm = (Intent) intent.getParcelableExtra("android.intent.extra.INTENT");
                            if (confirm != null) {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(confirm);
                                log("launched confirm intent");
                            } else {
                                log("PENDING_USER_ACTION but EXTRA_INTENT null");
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "confirm launch failed", t);
                        }
                    } else if (status == PackageInstaller.STATUS_SUCCESS) {
                        log("commit STATUS_SUCCESS");
                    } else {
                        log("commit failure status=" + status
                                + " msg=" + intent.getStringExtra("android.content.pm.extra.STATUS_MESSAGE"));
                    }
                }
            }, f, RECEIVER_EXPORTED);
            log("commit status receiver registered");
        } catch (Throwable t) {
            log("commit receiver registration failed: " + t);
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
            params.setInstallerPackageName(getPackageName());
            params.setAppLabel("Benchmark Target 01");
            params.setAppPackageName(TARGET_PKG);
            params.setAppIcon(makeIcon());
            params.setSize(apkBytes.length);

            int sessionId = pi.createSession(params);
            log("session created id=" + sessionId + " with badging (icon+label), installer=self, reason=4 (no spoof)");

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
        Intent intent = new Intent(ACTION_COMMIT_RESULT).setPackage(getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, sessionId,
                intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
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
}
