package app.lawnchair.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.launcher3.R
import com.android.launcher3.Utilities

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

fun PackageManager.getPackageVersionCode(packageName: String): Long = try {
    val info = getPackageInfo(packageName, 0)
    when {
        Utilities.ATLEAST_P -> info.longVersionCode
        else -> info.versionCode.toLong()
    }
} catch (_: PackageManager.NameNotFoundException) {
    -1L
}

fun PackageManager.isPackageInstalledAndEnabled(packageName: String) = try {
    getApplicationInfo(packageName, 0).enabled
} catch (_: PackageManager.NameNotFoundException) {
    false
}

fun PackageManager.getThemedIconPacksInstalled(context: Context): List<String> = try {
    queryIntentActivityOptions(
        ComponentName(context.applicationInfo.packageName, context.applicationInfo.className),
        null,
        Intent(context.resources.getString(R.string.icon_packs_intent_name)),
        PackageManager.GET_RESOLVED_FILTER,
    ).map { it.activityInfo.packageName }
} catch (_: PackageManager.NameNotFoundException) {
    emptyList()
}

// Rebase Phase 2 adapt (#532): PackageManagerHelper.isSystemApp was removed by the anchor
// rework; the fork's app categorization and uninstall shortcut still need it.
fun PackageManager.isSystemApp(packageName: String): Boolean = try {
    getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
} catch (_: PackageManager.NameNotFoundException) {
    false
}

fun Context.isSystemApp(intent: Intent): Boolean {
    val resolveInfo = packageManager.resolveActivity(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY,
    ) ?: return false
    return packageManager.isSystemApp(resolveInfo.activityInfo.packageName)
}

// Rebase Phase 2 adapt (#532): PackageManagerHelper.isAppSuspended was removed by the
// anchor rework; resolve per-user suspended state through LauncherApps.
fun Context.isAppSuspended(packageName: String, user: UserHandle): Boolean = try {
    getSystemService(LauncherApps::class.java)
        .getApplicationInfo(packageName, 0, user)
        .flags and ApplicationInfo.FLAG_SUSPENDED != 0
} catch (_: PackageManager.NameNotFoundException) {
    false
}
