package app.lawnchair.organizer.diagnostics.integration

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-67-12: Source/build contract evidence that the Issue #67 diagnostics
 * module adds no permission, telemetry/network dependency, upload worker,
 * transport API, or automatic recipient selection.
 *
 * Checks are scoped to the diagnostics module source files
 * (lawnchair/src/app/lawnchair/organizer/diagnostics/). Pre-existing
 * permissions in the shared AndroidManifest.xml (upstream Lawnchair) are
 * not flagged by this test.
 */
class NoTransportContractTest {

    /**
     * Verify that the diagnostics module source files do not import
     * any network/telemetry/worker API.
     */
    @Test
    fun noNetworkImportsInDiagnosticsModule() {
        val diagnosticsDir = File(
            "lawnchair/src/app/lawnchair/organizer/diagnostics",
        )
        org.junit.Assume.assumeTrue(
            "Diagnostics module must exist",
            diagnosticsDir.exists(),
        )

        val forbiddenPatterns = listOf(
            "java.net.HttpURLConnection",
            "java.net.URL",
            "java.net.Socket",
            "okhttp3",
            "retrofit2",
            "androidx.work",
            "com.google.firebase",
            "com.google.android.gms.analytics",
            "android.app.job.JobScheduler",
            "android.app.DownloadManager",
        )

        val files = diagnosticsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("kt", "java") }
            .toList()

        for (file in files) {
            val content = file.readText(Charsets.UTF_8)
            for (pattern in forbiddenPatterns) {
                assertTrue(
                    "File ${file.name} must not contain import $pattern",
                    !content.contains(pattern),
                )
            }
        }
    }

    /**
     * Verify that no diagnostics module file has a name suggesting
     * upload/transport/worker functionality.
     */
    @Test
    fun noWorkerOrTransportFileNames() {
        val diagnosticsDir = File(
            "lawnchair/src/app/lawnchair/organizer/diagnostics",
        )
        org.junit.Assume.assumeTrue(
            "Diagnostics module must exist",
            diagnosticsDir.exists(),
        )

        val forbiddenNamePatterns = listOf(
            "Upload", "Sync", "Worker", "Transport",
            "Transmitter", "Sender", "Dispatcher",
            "JobScheduler", "JobService", "WorkManager",
            "PeriodicTask", "BackgroundService",
            "Firebase", "Analytics", "NetworkClient",
        )

        val files = diagnosticsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("kt", "java") }
            .toList()

        for (file in files) {
            for (pattern in forbiddenNamePatterns) {
                assertTrue(
                    "File ${file.name} must not have name matching $pattern",
                    !file.name.contains(pattern),
                )
            }
        }
    }

    /**
     * Verify that the diagnostics module source files do not contain
     * any permission string literal. This is scoped to the diagnostics
     * module only — pre-existing manifest permissions are not checked.
     */
    @Test
    fun noPermissionStringsInDiagnosticsModule() {
        val diagnosticsDir = File(
            "lawnchair/src/app/lawnchair/organizer/diagnostics",
        )
        org.junit.Assume.assumeTrue(
            "Diagnostics module must exist",
            diagnosticsDir.exists(),
        )

        val forbiddenPermissionStrings = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.MANAGE_DOCUMENTS",
        )

        val files = diagnosticsDir.walkTopDown()
            .filter { it.isFile && it.extension in listOf("kt", "java") }
            .toList()

        for (file in files) {
            val content = file.readText(Charsets.UTF_8)
            for (perm in forbiddenPermissionStrings) {
                assertTrue(
                    "File ${file.name} must not contain permission string $perm",
                    !content.contains(perm),
                )
            }
        }
    }
}
