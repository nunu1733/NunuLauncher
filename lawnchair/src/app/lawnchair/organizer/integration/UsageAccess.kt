package app.lawnchair.organizer.integration

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Issue #203: the Usage Access grant predicate shared by the production reader
 * and the Settings surface. Usage Access is an **app-op**, not a runtime
 * permission: the platform's `UsageStatsService` consults
 * `OPSTR_GET_USAGE_STATS` first and falls back to the package permission only
 * when the op is in `MODE_DEFAULT` (2026-09-15 re-review Blocking 1). A
 * `checkCallingOrSelfPermission`-only check would keep reporting `NOT_GRANTED`
 * after the user granted the app-op in system settings.
 */
object UsageAccess {

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val packageName = context.packageName
        val uid = context.applicationInfo.uid

        // `checkOpNoThrow` exists since API 19; `unsafeCheckOpNoThrow` would
        // need API 29 while this app's minSdk is 26 (2026-09-15 re-review).
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            uid,
            packageName,
        )
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true

            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED

            else -> false
        }
    }
}
