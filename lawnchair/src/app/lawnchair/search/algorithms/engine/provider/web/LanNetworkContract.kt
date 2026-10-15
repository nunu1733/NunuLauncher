package app.lawnchair.search.algorithms.engine.provider.web

/**
 * Platform constants for the Android 17 local network protection (Issue #528).
 *
 * The symbolic references (`Build.VERSION_CODES.CINNAMON_BUN`,
 * `Manifest.permission.ACCESS_LOCAL_NETWORK`) are present in the compileSdk 37
 * android.jar but cannot be resolved from this module's Kotlin/Java source:
 * `prebuilts/libs/framework-16.jar` (the Android 16 framework jar wired via
 * `addFrameworkJar`, build.gradle:436) is pinned first on the compile
 * classpath for both javac and Kotlin and only carries API 36 members of
 * android.os.Build / android.Manifest. The literals below are stable platform
 * contracts — the API level int and the runtime permission name string — and
 * are compile-time constants, inlined at every use site.
 */
object LanNetworkContract {

    /** Build.VERSION_CODES.CINNAMON_BUN (API 37, Android 17). */
    const val SDK_CINNAMON_BUN: Int = 37

    /** android.Manifest.permission.ACCESS_LOCAL_NETWORK (Android 17 LNP). */
    const val PERMISSION_ACCESS_LOCAL_NETWORK: String = "android.permission.ACCESS_LOCAL_NETWORK"
}
